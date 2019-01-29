/*

Copyright 2008-2016 E-Hentai.org
https://forums.e-hentai.org/
ehentai@gmail.com

This file is part of Hentai@Home.

Hentai@Home is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Hentai@Home is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Hentai@Home.  If not, see <http://www.gnu.org/licenses/>.

*/

package org.hath.base

import java.lang.Thread
import java.util.ArrayList
import java.util.Collections
import java.util.Hashtable
import java.util.regex.Pattern
import java.io.File
import java.net.URL

class GalleryDownloader(protected var client: HentaiAtHomeClient) : Runnable {
    private val myThread: Thread
    private val validator: FileValidator
    protected var downloadLimiter: HTTPBandwidthMonitor? = null
    private var downloadsAvailable = true
    private var pendingDownload = false
    private var markDownloaded = false

    private var title: String? = null
    private var information: String? = null
    private var galleryFiles: Array<GalleryFile>? = null
    private var failures: MutableList<String>? = null
    protected var gid: Int = 0
    protected var filecount: Int = 0
    protected var minxres: String? = null
    protected var todir: File? = null

    init {
        validator = FileValidator()
        downloadLimiter = if (Settings.isDisableDownloadBWM) null else HTTPBandwidthMonitor()
        myThread = Thread(this)
        myThread.start()
    }

    override fun run() {
        while (!client.isShuttingDown && downloadsAvailable) {
            if (!pendingDownload) {
                pendingDownload = initializeNewGalleryMeta()
            }

            if (!pendingDownload) {
                downloadsAvailable = false
                break
            }

            Out.info("GalleryDownloader: Starting download of gallery: " + title!!)

            var galleryretry = 0
            var success = false

            while (!success && ++galleryretry < 10) {
                var successfulFiles = 0

                for (gFile in galleryFiles!!) {
                    if (client.isShuttingDown) {
                        break
                    }

                    var sleepTime: Long = 0

                    if (client.isSuspended) {
                        sleepTime = 60000
                    } else if (downloadDirectoryHasLowSpace()) {
                        Out.warning("GalleryDownloader: Download suspended; there is less than the minimum allowed space left on the storage device.")
                        sleepTime = 300000
                    } else {
                        val downloadState = gFile.download()

                        if (downloadState == GalleryFile.STATE_DOWNLOAD_SUCCESSFUL) {
                            ++successfulFiles
                            sleepTime = 1000
                        } else if (downloadState == GalleryFile.STATE_ALREADY_DOWNLOADED) {
                            ++successfulFiles
                        } else if (downloadState == GalleryFile.STATE_DOWNLOAD_FAILED) {
                            sleepTime = 5000
                        }
                    }

                    if (sleepTime > 0) {
                        try {
                            Thread.sleep(sleepTime)
                        } catch (e: java.lang.InterruptedException) {
                        }

                    }
                }

                if (successfulFiles == filecount) {
                    success = true
                }
            }

            finalizeGalleryDownload(success)
        }

        // the GalleryDownloader thread is created on-demand. when it is done, it goes away until needed again.
        Out.info("GalleryDownloader: Download thread finished.")
        client.deleteDownloader()
    }

    private fun downloadDirectoryHasLowSpace(): Boolean {
        return !Settings.isSkipFreeSpaceCheck && Settings.downloadDir!!.getFreeSpace() < Settings.diskMinRemainingBytes + 1048576000
    }

    private fun finalizeGalleryDownload(success: Boolean) {
        pendingDownload = false
        markDownloaded = true

        if (success) {
            Out.info("GalleryDownloader: Finished download of gallery: " + title!!)

            try {
                Tools.putStringFileContents(File(todir, "galleryinfo.txt"), information!!, "UTF8")
            } catch (e: java.io.IOException) {
                Out.warning("GalleryDownloader: Could not write galleryinfo file")
                e.printStackTrace()
            }

        } else {
            Out.warning("GalleryDownloader: Permanently failed downloading gallery: " + title!!)
        }
    }

    private fun initializeNewGalleryMeta(): Boolean {
        if (markDownloaded) {
            if (failures != null) {
                // tattletale
                client.serverHandler!!.reportDownloaderFailures(failures)
            }
        }

        val metaurl: URL

        try {
            metaurl = URL(Settings.CLIENT_RPC_PROTOCOL + Settings.rpcServerHost + "/hathdl.php?" + ServerHandler.getURLQueryString("fetchqueue", if (markDownloaded) "$gid;$minxres" else ""))
        } catch (e: java.net.MalformedURLException) {
            e.printStackTrace()
            return false
        }

        // this does two things: marks the previous gallery as downloaded and removes it from the queue, and fetches metadata of the next gallery in the queue
        val metaDownloader = FileDownloader(metaurl, 30000, 30000)
        val galleryMeta = metaDownloader.getResponseAsString("UTF8") ?: return false

        if (galleryMeta == "INVALID_REQUEST") {
            Out.warning("GalleryDownloader: Request was rejected by the server")
            return false
        }

        if (galleryMeta == "NO_PENDING_DOWNLOADS") {
            return false
        }

        Out.debug("GalleryDownloader: Started gallery metadata parsing")

        // reset
        gid = 0
        filecount = 0
        minxres = null
        title = null
        information = ""
        galleryFiles = null
        todir = null
        markDownloaded = false
        failures = null

        // parse the metadata for this gallery. this is basically a hathdl file with a new file list format
        // incidentally, did you know Java SE does not have a built-in JSON parser? how stupid is that
        var parseState = 0

        try {
            for (s in galleryMeta.split("\n".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()) {
                if (s == "FILELIST" && parseState == 0) {
                    parseState = 1
                    continue
                }

                if (s == "INFORMATION" && parseState == 1) {
                    parseState = 2
                    continue
                }

                if (parseState < 2 && s.isEmpty()) {
                    continue
                }

                if (parseState == 0) {
                    val split = s.split(" ".toRegex(), 2).toTypedArray()

                    if (split[0] == "GID") {
                        gid = Integer.parseInt(split[1])
                        Out.debug("GalleryDownloader: Parsed gid=$gid")
                    } else if (split[0] == "FILECOUNT") {
                        filecount = Integer.parseInt(split[1])
                        galleryFiles = arrayOfNulls(filecount)
                        Out.debug("GalleryDownloader: Parsed filecount=$filecount")
                    } else if (split[0] == "MINXRES") {
                        if (Pattern.matches("^org|\\d+$", split[1])) {
                            minxres = split[1]
                            Out.debug("GalleryDownloader: Parsed minxres=" + minxres!!)
                        } else {
                            throw Exception("Encountered invalid minxres")
                        }
                    } else if (split[0] == "TITLE") {
                        title = split[1].replace("(\\*|\\\"|\\\\|<|>|:\\|\\?)".toRegex(), "").replace("\\s+".toRegex(), " ").replace("(^\\s+|\\s+$)".toRegex(), "")
                        Out.debug("GalleryDownloader: Parsed title=" + title!!)

                        // MINXRES must be passed before TITLE for this to work. the only purpose is to make distinct titles
                        val xresTitle = if (minxres == "org") "" else "-" + minxres + "x"

                        if (title!!.length > 100) {
                            todir = File(Settings.downloadDir, title!!.substring(0, 97) + "... [" + gid + xresTitle + "]")
                        } else {
                            todir = File(Settings.downloadDir, "$title [$gid$xresTitle]")
                        }

                        // just in case, check for directory traversal
                        if (todir!!.parentFile != Settings.downloadDir) {
                            Out.warning("GalleryDownloader: Unexpected download location.")
                            todir = null
                            break
                        }

                        Tools.checkAndCreateDir(todir!!)
                        Out.debug("GalleryDownloader: Created directory " + todir!!)
                    }
                } else if (parseState == 1) {
                    // entries are on the form: page fileindex xres sha1hash filetype filename
                    val split = s.split(" ".toRegex(), 6).toTypedArray()
                    val page = Integer.parseInt(split[0])
                    val fileindex = Integer.parseInt(split[1])
                    val xres = split[2]

                    // sha1hash can be "unknown" if the file has not been generated yet
                    val sha1hash = if (split[3] == "unknown") null else split[3]

                    // the server guarantees that all filenames in the meta file are unique, and that none of them are reserved device filenames
                    val filetype = split[4]
                    val filename = split[5]

                    val gf = GalleryFile(page, fileindex, xres, sha1hash, filetype, filename)

                    if (gf != null) {
                        Out.debug("GalleryDownloader: Parsed file $gf")
                        galleryFiles[page - 1] = gf
                    }
                } else {
                    information = information + s + Settings.NEWLINE
                }
            }
        } catch (e: Exception) {
            Out.warning("GalleryDownloader: Failed to parse metadata for new gallery")
            e.printStackTrace()
            return false
        }

        return gid > 0 && filecount > 0 && minxres != null && title != null && todir != null && galleryFiles != null
    }

    protected fun logFailure(fail: String) {
        if (failures == null) {
            failures = Collections.checkedList(ArrayList(), String::class.java!!)
        }

        if (!failures!!.contains(fail)) {
            failures!!.add(fail)
        }
    }

    private inner class GalleryFile(private val page: Int, private val fileindex: Int, private val xres: String, private val expectedSHA1Hash: String?, private val filetype: String, private val filename: String) {
        private val tofile: File
        private var fileretry = 0
        private var fileComplete = false

        init {
            tofile = File(todir, "$filename.$filetype")
        }

        fun download(): Int {
            if (fileComplete) {
                return STATE_ALREADY_DOWNLOADED
            }

            if (tofile.isFile) {
                var verified = false

                if (tofile.length() > 0) {
                    try {
                        if (expectedSHA1Hash == null) {
                            // if the file was generated on-demand for this download, we cannot verify the hash
                            verified = true
                        } else if (validator.validateFile(tofile.toPath(), expectedSHA1Hash)) {
                            verified = true
                            Out.debug("GalleryDownloader: Verified SHA-1 hash for $this: $expectedSHA1Hash")
                        }
                    } catch (e: java.io.IOException) {
                        Out.warning("GalleryDownloader: Encountered I/O error while validating $tofile")
                        e.printStackTrace()
                    }

                }

                if (verified) {
                    fileComplete = true
                    return STATE_ALREADY_DOWNLOADED
                } else {
                    tofile.delete()
                }
            }

            // if this turns out to be a file that can be handled by this client, the returned link will be to localhost, which will trigger a static range fetch using the standard mechanism
            // we don't have enough information at this point to initiate a ProxyFileDownload directly, so while the extra roundtrip might seem wasteful, it is necessary (and usually fairly rare)
            val source = client.serverHandler!!.getDownloaderFetchURL(gid, page, fileindex, xres, ++fileretry > 1)

            if (source != null) {
                val dler = FileDownloader(source, 10000, 300000, tofile.toPath())
                dler.setDownloadLimiter(downloadLimiter)
                fileComplete = dler.downloadFile()

                try {
                    if (fileComplete && expectedSHA1Hash != null) {
                        if (!validator.validateFile(tofile.toPath(), expectedSHA1Hash)) {
                            fileComplete = false
                            tofile.delete()
                            Out.debug("GalleryDownloader: Corrupted download for $this, forcing retry")
                        } else {
                            Out.debug("GalleryDownloader: Verified SHA-1 hash for $this: $expectedSHA1Hash")
                        }
                    }
                } catch (e: java.io.IOException) {
                    Out.warning("GalleryDownloader: Encountered I/O error while validating $tofile")
                    e.printStackTrace()
                    fileComplete = false
                    tofile.delete()
                }

                Out.debug("GalleryDownloader: Download of " + this + " " + (if (fileComplete) "successful" else "FAILED") + " (attempt=" + fileretry + ")")

                if (fileComplete) {
                    Stats.fileRcvd()
                    Out.info("GalleryDownloader: Finished downloading gid=$gid page=$page: $filename.$filetype")
                } else {
                    logFailure(source.host + "-" + fileindex + "-" + xres)
                }
            }

            return if (fileComplete) STATE_DOWNLOAD_SUCCESSFUL else STATE_DOWNLOAD_FAILED
        }

        override fun toString(): String {
            return "gid=$gid page=$page fileindex=$fileindex xres=$xres filetype=$filetype filename=$filename"
        }

        companion object {
            val STATE_DOWNLOAD_FAILED = 0
            val STATE_DOWNLOAD_SUCCESSFUL = 1
            val STATE_ALREADY_DOWNLOADED = 2
        }
    }
}
