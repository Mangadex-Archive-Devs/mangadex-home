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
import java.net.URL
import java.net.URLConnection
import java.io.File
import java.io.RandomAccessFile
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel
import java.nio.ByteBuffer
import java.security.MessageDigest

class ProxyFileDownloader(private val client: HentaiAtHomeClient, private val fileid: String, private val source: URL) : Runnable {
    private val requestedHVFile: HVFile?
    private var tempFile: File? = null
    private val returnFile: File? = null
    private var fileHandle: RandomAccessFile? = null
    private var fileChannel: FileChannel? = null
    private var connection: URLConnection? = null
    private val myThread: Thread
    private var sha1Digest: MessageDigest? = null
    private var readoff: Int = 0
    var currentWriteoff: Int = 0
        private set
    private var contentLength: Int = 0
    private var streamThreadSuccess = false
    private var streamThreadComplete = false
    private var proxyThreadComplete = false
    private var fileFinalized = false
    private val downloadLock = Any()

    val contentType: String
        get() = requestedHVFile!!.mimeType

    init {

        this.requestedHVFile = HVFile.getHVFileFromFileid(fileid)
        currentWriteoff = 0
        readoff = 0
        myThread = Thread(this)
    }

    fun initialize(): Int {
        // we'll need to run this in a private thread so we can push data to the originating client at the same time we download it (pass-through)
        Out.info("Proxy file download request initializing for $fileid...")

        try {
            Out.debug("ProxyFileDownloader: Requesting file download from $source")

            connection = source.openConnection()
            connection!!.connectTimeout = 10000
            connection!!.readTimeout = 30000
            connection!!.setRequestProperty("Hath-Request", Settings.clientID.toString() + "-" + Tools.getSHA1String(Settings.clientKey + fileid))
            connection!!.setRequestProperty("User-Agent", "Hentai@Home " + Settings.CLIENT_VERSION)
            connection!!.connect()

            val tempLength = connection!!.contentLength
            var retval = 0

            if (tempLength < 0) {
                Out.warning("Request host did not send Content-Length, aborting transfer. ($connection)")
                Out.warning("Note: A common reason for this is running firewalls with outgoing restrictions or programs like PeerGuardian/PeerBlock. Verify that the remote host is not blocked.")
                retval = 502
            } else if (tempLength > Settings.maxAllowedFileSize) {
                Out.warning("Reported contentLength " + contentLength + " exceeds currently max allowed filesize " + Settings.maxAllowedFileSize)
                retval = 502
            } else if (tempLength != requestedHVFile!!.size) {
                Out.warning("Reported contentLength $contentLength does not match expected length of file $fileid ($connection)")
                retval = 502
            }

            if (retval > 0) {
                // file could not be retrieved from upstream server
                return retval
            }

            contentLength = tempLength

            // create the temporary file used to hold the proxied data
            tempFile = File.createTempFile("proxyfile_", "", Settings.tempDir)
            fileHandle = RandomAccessFile(tempFile!!, "rw")
            fileChannel = fileHandle!!.channel

            // we need to calculate the SHA-1 hash at some point, so we might as well do it on the fly
            sha1Digest = MessageDigest.getInstance("SHA-1")

            // at this point, everything is ready to receive data from the server and pass it to the client. in order to do this, we'll fork off a new thread to handle the reading, while this thread returns.
            // control will thus pass to the HTTPSession where this HRP's read functions will be called, and data will be written to the connection this proxy request originated from.
            myThread.start()

            return 200
        } catch (e: Exception) {
            e.printStackTrace()

            try {
                if (fileHandle != null) {
                    fileHandle!!.close()
                }
            } catch (e2: Exception) {
            }

        }

        return 500
    }

    override fun run() {
        synchronized(downloadLock) {
            var trycounter = 3
            val bufferSize = 65536
            val bufferThreshold = Math.floor(bufferSize * 0.75).toInt()
            val byteBuffer = ByteBuffer.allocateDirect(Math.min(contentLength, bufferSize))

            do {
                var `is`: InputStream? = null
                var rbc: ReadableByteChannel? = null

                try {
                    `is` = connection!!.getInputStream()
                    rbc = Channels.newChannel(`is`!!)

                    val downloadStart = System.currentTimeMillis()
                    var readcount = 0    // the number of bytes in the last read
                    var writecount = 0    // the number of bytes in the last write
                    var time = 0        // counts the approximate time (in nanofortnights) since last byte was received

                    while (currentWriteoff < contentLength) {
                        if (`is`.available() > 0) {
                            time = 0
                            readcount = rbc!!.read(byteBuffer)
                            //Out.debug("Read " + readcount + " bytes from upstream server");

                            if (readcount >= 0) {
                                readoff += readcount

                                // we push the buffer to disk/digest if we either read all of the bytes, or we are above the flush threshold and we cannot fit the remainder in the buffer
                                if (readoff == contentLength || readoff > currentWriteoff + bufferThreshold && currentWriteoff < contentLength - bufferSize) {
                                    byteBuffer.flip()
                                    // we have to make a "metacopy" of this buffer to avoid it being consumed by the digest
                                    sha1Digest!!.update(byteBuffer.asReadOnlyBuffer())
                                    // FileChannel.write(ByteBuffer, long) is guaranteed to consume the entire buffer
                                    writecount = fileChannel!!.write(byteBuffer, currentWriteoff.toLong())
                                    currentWriteoff += writecount
                                    Stats.bytesRcvd(writecount)
                                    //Out.debug("Wrote " + writecount + " bytes to " + tempFile);
                                    byteBuffer.clear()
                                }
                            } else {
                                // readcount == -1 => EOF
                                Out.warning("\nServer sent premature EOF, aborting.. ($currentWriteoff of $contentLength bytes received)")
                                throw java.net.SocketException("Unexpected end of file from server")
                            }
                        } else {
                            if (System.currentTimeMillis() - downloadStart > 300000) {
                                Out.warning("\nDownload time limit has expired, aborting...")
                                throw java.net.SocketTimeoutException("Download timed out")
                            } else if (time > 30000) {
                                Out.warning("\nTimeout detected waiting for byte $currentWriteoff, aborting..")
                                throw java.net.SocketTimeoutException("Read timed out")
                            }

                            time += 5
                            Thread.currentThread().sleep(5)
                        }
                    }

                    Stats.fileRcvd()
                    streamThreadSuccess = true
                } catch (e: Exception) {
                    currentWriteoff = 0
                    readoff = 0
                    byteBuffer.clear()
                    sha1Digest!!.reset()
                    Out.debug("Retrying.. ($trycounter tries left)")
                } finally {
                    try {
                        rbc!!.close()
                    } catch (e: Exception) {
                    }

                    try {
                        `is`!!.close()
                    } catch (e: Exception) {
                    }

                }
            } while (!streamThreadSuccess && --trycounter > 0)

            streamThreadComplete = true
            checkFinalizeDownloadedFile()
        }
    }

    fun getContentLength(): Int {
        return requestedHVFile!!.size
    }

    @Throws(java.io.IOException::class)
    fun fillBuffer(buffer: ByteBuffer, offset: Int): Int {
        var readBytes = 0

        while (buffer.hasRemaining() && currentWriteoff > offset + readBytes) {
            // this method will never be called unless sufficient bytes are available, so we always want to fill the buffer before we return.
            // we do not buffer reads when doing proxied file downloads. as the data was *just* written, it is almost guaranteed to be in the OS disk buffer.
            readBytes += fileChannel!!.read(buffer, (offset + readBytes).toLong())
        }

        // flipping the buffer before use is the responsibility of the caller
        return readBytes
    }

    fun proxyThreadCompleted() {
        proxyThreadComplete = true
        checkFinalizeDownloadedFile()
    }

    @Synchronized
    private fun checkFinalizeDownloadedFile() {
        if (!streamThreadComplete || !proxyThreadComplete) {
            // we have to wait for both the upstream and downstream transfers to complete before we can close this file
            return
        }

        if (fileFinalized) {
            Out.warning("ProxyFileDownloader: Attempted to finalize file that was already finalized")
            return
        }

        fileFinalized = true

        if (fileChannel != null) {
            try {
                fileChannel!!.close()
            } catch (e: Exception) {
            }

        }

        if (fileHandle != null) {
            try {
                fileHandle!!.close()
            } catch (e: Exception) {
            }

        }

        if (tempFile!!.length() != getContentLength().toLong()) {
            Out.debug("Requested file " + fileid + " is incomplete, and will not be stored. (bytes=" + tempFile!!.length() + ")")
        } else {
            val sha1Hash = Tools.binaryToHex(sha1Digest!!.digest())

            if (requestedHVFile!!.hash != sha1Hash) {
                Out.debug("Requested file $fileid is corrupt, and will not be stored. (digest=$sha1Hash)")
            } else if (!Settings.isStaticRange(fileid)) {
                Out.debug("The file $fileid is not in a static range, and will not be stored.")
            } else {
                if (client.cacheHandler!!.importFile(tempFile, requestedHVFile)) {
                    Out.debug("Requested file $fileid was successfully stored in cache.")
                } else {
                    Out.debug("Requested file $fileid exists or cannot be cached.")
                }

                Out.info("Proxy file download request complete for $fileid")
            }
        }

        if (tempFile!!.exists()) {
            tempFile!!.delete()
        }
    }
}
