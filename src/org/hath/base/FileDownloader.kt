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

import java.net.*
import java.io.*
import java.nio.*
import java.nio.channels.*
import java.nio.file.*

class FileDownloader : Runnable {
    private var timeout = 30000
    private var maxDLTime = Integer.MAX_VALUE
    private var retries = 3
    private var timeDownloadStart: Long = 0
    private var timeFirstByte: Long = 0
    private var timeDownloadFinish: Long = 0
    private var byteBuffer: ByteBuffer? = null
    private var outputChannel: FileChannel? = null
    private var downloadLimiter: HTTPBandwidthMonitor? = null
    private val outputPath: Path? = null
    private var source: URL? = null
    private var myThread: Thread? = null
    private val downloadLock = Any()
    private var started = false
    private val discardData = false

    val downloadTimeMillis: Long
        get() = if (timeFirstByte > 0) timeDownloadFinish - timeFirstByte else 0

    constructor(source: URL, timeout: Int, maxDLTime: Int) {
        // everything will be written to a ByteBuffer
        this.source = source
        this.timeout = timeout
        this.maxDLTime = maxDLTime
    }

    constructor(source: URL, timeout: Int, maxDLTime: Int, discardData: Boolean) {
        // if discardData is true, no buffer will be allocated and the data stream will be discarded
        this.source = source
        this.timeout = timeout
        this.maxDLTime = maxDLTime
        this.discardData = discardData
    }

    constructor(source: URL, timeout: Int, maxDLTime: Int, outputPath: Path) {
        // in this case, the data will be written directly to a channel specified by outputPath
        this.source = source
        this.timeout = timeout
        this.maxDLTime = maxDLTime
        this.outputPath = outputPath
    }

    fun setDownloadLimiter(limiter: HTTPBandwidthMonitor) {
        downloadLimiter = limiter
    }

    fun downloadFile(): Boolean {
        // this will block while the file is downloaded
        if (myThread == null) {
            // if startAsyncDownload has not been called, we invoke run() directly and skip threading
            run()
        } else {
            waitAsyncDownload()
        }

        return timeDownloadFinish > 0
    }

    fun startAsyncDownload() {
        // start a new thread to handle the download. this will return immediately
        if (myThread == null) {
            myThread = Thread(this)
            myThread!!.start()
        }
    }

    fun waitAsyncDownload(): Boolean {
        // synchronize on the download lock to wait for the download attempts to complete before returning
        synchronized(downloadLock) {}
        return timeDownloadFinish > 0
    }

    fun getResponseAsString(charset: String): String? {
        if (downloadFile()) {
            if (byteBuffer != null) {
                byteBuffer!!.flip()
                val temp = ByteArray(byteBuffer!!.remaining())
                byteBuffer!!.get(temp)

                try {
                    return String(temp, charset)
                } catch (e: UnsupportedEncodingException) {
                    HentaiAtHomeClient.dieWithError(e)
                }

            }
        }

        return null
    }

    override fun run() {
        synchronized(downloadLock) {
            var success = false

            if (started) {
                return
            }

            started = true

            while (!success && --retries >= 0) {
                var `is`: InputStream? = null
                var rbc: ReadableByteChannel? = null

                try {
                    Out.info("Connecting to " + source!!.host + "...")

                    val connection = source!!.openConnection()
                    connection.connectTimeout = 5000
                    connection.readTimeout = timeout
                    connection.setRequestProperty("Connection", "Close")
                    connection.setRequestProperty("User-Agent", "Hentai@Home " + Settings.CLIENT_VERSION)
                    connection.connect()

                    val contentLength = connection.contentLength

                    if (contentLength < 0) {
                        // since we control all systems in this case, we'll demand that clients and servers always send the Content-Length
                        Out.warning("Request host did not send Content-Length, aborting transfer. ($connection)")
                        Out.warning("Note: A common reason for this is running firewalls with outgoing restrictions or programs like PeerGuardian/PeerBlock. Verify that the remote host is not blocked.")
                        throw java.net.SocketException("Invalid or missing Content-Length")
                    } else if (contentLength > 10485760 && !discardData && outputPath == null) {
                        // if we're writing to a ByteBuffer, hard limit responses to 10MB
                        Out.warning("Reported contentLength $contentLength exceeds max allowed size for memory buffer download")
                        throw java.net.SocketException("Reply exceeds expected length")
                    } else if (contentLength > Settings.maxAllowedFileSize) {
                        Out.warning("Reported contentLength " + contentLength + " exceeds currently max allowed filesize " + Settings.maxAllowedFileSize)
                        throw java.net.SocketException("Reply exceeds expected length")
                    }

                    `is` = connection.getInputStream()

                    if (!discardData) {
                        rbc = Channels.newChannel(`is`!!)
                        //Out.debug("ReadableByteChannel for input opened");

                        if (outputPath == null) {
                            if (byteBuffer != null) {
                                if (byteBuffer!!.capacity() < contentLength) {
                                    // if we are retrying and the length has increased, we have to allocate a new buffer
                                    byteBuffer = null
                                }
                            }

                            if (byteBuffer == null) {
                                byteBuffer = ByteBuffer.allocateDirect(contentLength)
                                //Out.debug("Allocated byteBuffer (length=" + byteBuffer.capacity() + ")");
                            } else {
                                byteBuffer!!.clear()
                                //Out.debug("Cleared byteBuffer (length=" + byteBuffer.capacity() + ")");
                            }
                        } else if (outputChannel == null) {
                            outputChannel = FileChannel.open(outputPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                            //Out.debug("FileChannel for output opened");
                        }
                    }

                    Out.info("Reading $contentLength bytes from $source")
                    timeDownloadStart = System.currentTimeMillis()

                    var writeoff: Long = 0    // counts the number of bytes read
                    var readcount: Long = 0    // the number of bytes in the last read
                    var available = 0    // the number of bytes available to read
                    var time = 0        // counts the approximate time (in nanofortnights) since last byte was received

                    while (writeoff < contentLength) {
                        available = `is`!!.available()

                        if (available > 0) {
                            if (timeFirstByte == 0L) {
                                timeFirstByte = System.currentTimeMillis()
                            }

                            time = 0

                            if (discardData) {
                                readcount = `is`.skip(available.toLong())
                                //Out.debug("Skipped " + readcount + " bytes");
                            } else if (outputPath == null) {
                                readcount = rbc!!.read(byteBuffer).toLong()
                                //Out.debug("Added " + readcount + " bytes to byteBuffer");
                            } else {
                                readcount = outputChannel!!.transferFrom(rbc, writeoff, available.toLong())
                                //Out.debug("Wrote " + readcount + " bytes to outputChannel");
                            }

                            if (readcount >= 0) {
                                writeoff += readcount
                            } else {
                                // readcount == -1 => EOF
                                Out.warning("\nServer sent premature EOF, aborting.. ($writeoff of $contentLength bytes received)")
                                throw java.net.SocketException("Unexpected end of file from server")
                            }

                            if (downloadLimiter != null) {
                                downloadLimiter!!.waitForQuota(Thread.currentThread(), readcount.toInt())
                            }
                        } else {
                            if (System.currentTimeMillis() - timeDownloadStart > maxDLTime) {
                                Out.warning("\nDownload time limit has expired, aborting...")
                                throw java.net.SocketTimeoutException("Download timed out")
                            } else if (time > timeout) {
                                Out.warning("\nTimeout detected waiting for byte $writeoff, aborting..")
                                throw java.net.SocketTimeoutException("Read timed out")
                            }

                            time += 5
                            Thread.currentThread().sleep(5)
                        }
                    }

                    timeDownloadFinish = System.currentTimeMillis()
                    val dltime = downloadTimeMillis
                    Out.debug("Finished in " + dltime + " ms" + (if (dltime > 0) ", speed=" + writeoff / dltime + "KB/s" else "") + ", writeoff=" + writeoff)
                    Stats.bytesRcvd(contentLength)
                    success = true
                } catch (e: Exception) {
                    if (e is java.io.FileNotFoundException) {
                        Out.warning("Server returned: 404 Not Found")
                        break
                    } else if (e.cause is java.io.FileNotFoundException) {
                        Out.warning("Server returned: 404 Not Found")
                        break
                    }

                    Out.warning(e.toString())
                    Out.warning("Retrying.. ($retries tries left)")
                    continue
                } finally {
                    if (rbc != null) {
                        try {
                            rbc.close()
                        } catch (e: Exception) {
                        }

                    }

                    try {
                        `is`!!.close()
                    } catch (e: Exception) {
                    }

                }
            }

            if (outputChannel != null) {
                try {
                    outputChannel!!.close()

                    if (!success) {
                        outputPath!!.toFile().delete()
                    }
                } catch (e: Exception) {
                }

            }

            if (!success) {
                Out.warning("Exhaused retries or aborted getting " + source!!)
            }
        }
    }
}
