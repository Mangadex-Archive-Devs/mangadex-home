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

import java.util.Date
import java.util.TimeZone
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SocketChannel
import java.net.InetAddress
import java.lang.Thread
import java.lang.StringBuilder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.regex.Pattern
import java.util.regex.Matcher

class HTTPSession(private val socketChannel: SocketChannel, private val connId: Int, val isLocalNetworkAccess: Boolean,
        // accessors

                  val httpServer: HTTPServer) : Runnable {
    private var myThread: Thread? = null
    private val sessionStartTime: Long
    private var lastPacketSend: Long = 0
    private var hr: HTTPResponse? = null

    val socketInetAddress: InetAddress
        get() = socketChannel.socket().inetAddress

    init {
        sessionStartTime = System.currentTimeMillis()
    }

    fun handleSession() {
        myThread = Thread(this)
        myThread!!.start()
    }

    private fun connectionFinished() {
        if (hr != null) {
            hr!!.requestCompleted()
        }

        httpServer.removeHTTPSession(this)
    }

    @Throws(java.io.IOException::class)
    private fun readHeader(channel: ReadableByteChannel): String? {
        var rcvdBytesTotal = 0
        var totalWaitTime = 0

        // if the request exceeds 1000 bytes, it's almost certainly not valid
        // the request header itself can still be larger than 1000 bytes, as the GET/HEAD part will always be the first line of the request header
        val buffer = ByteArray(1000)
        val byteBuffer = ByteBuffer.wrap(buffer)

        do {
            val rcvdBytes = channel.read(byteBuffer)

            if (rcvdBytes < 0) {
                Out.debug("Premature EOF while reading request header")
                return null
            } else if (rcvdBytes == 0) {
                if (totalWaitTime > 5000) {
                    Out.debug("Request header read timeout")
                    return null
                } else {
                    try {
                        totalWaitTime += 10
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        Out.debug("Request header read interrupted")
                        return null
                    }

                }
            } else {
                rcvdBytesTotal += rcvdBytes

                if (!isLocalNetworkAccess) {
                    Stats.bytesRcvd(rcvdBytes)
                }

                val currentFullHeader = String(buffer, 0, rcvdBytesTotal)
                val matcher = getheadPattern.matcher(currentFullHeader)
                val isValid = matcher.matches()

                if (isValid || matcher.hitEnd()) {
                    if (isValid) {
                        for (i in 1 until rcvdBytesTotal) {
                            if (buffer[i] == '\n'.toByte() && buffer[i - 1] == '\r'.toByte()) {
                                // only return the first line with the request string, sans the CRLF
                                return String(buffer, 0, i - 1)
                            }
                        }
                    }
                } else {
                    Out.debug("Malformed request header")
                    //Out.debug(currentFullHeader);
                    return null
                }

                Out.debug("Request incomplete; looping")
                //Out.debug(currentFullHeader);
            }

            if (!byteBuffer.hasRemaining()) {
                Out.debug("Oversize request")
                return null
            }
        } while (true)
    }

    override fun run() {
        var hpc: HTTPResponseProcessor? = null
        var info = "$this "

        try {
            val request = readHeader(socketChannel)
            socketChannel.shutdownInput()

            hr = HTTPResponse(this)

            // parse the request - this will also update the response code and initialize the proper response processor
            hr!!.parseRequest(request, isLocalNetworkAccess)

            // get the status code and response processor - in case of an error, this will be a text type with the error message
            hpc = hr!!.httpResponseProcessor
            val statusCode = hr!!.responseStatusCode
            val contentLength = hpc!!.contentLength

            // we'll create a new date formatter for each session instead of synchronizing on a shared formatter. (sdf is not thread-safe)
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", java.util.Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")

            // build the header
            val header = StringBuilder(300)
            header.append(getHTTPStatusHeader(statusCode))
            header.append(hpc.header)
            header.append("Date: " + sdf.format(Date()) + " GMT" + CRLF)
            header.append("Server: Genetic Lifeform and Distributed Open Server " + Settings.CLIENT_VERSION + CRLF)
            header.append("Connection: close$CRLF")
            header.append("Content-Type: " + hpc.contentType + CRLF)

            if (contentLength > 0) {
                header.append("Cache-Control: public, max-age=31536000$CRLF")
                header.append("Content-Length: $contentLength$CRLF")
            }

            header.append(CRLF)

            // write the header to the socket
            val headerBytes = header.toString().toByteArray(Charset.forName("ISO-8859-1"))

            if (contentLength > 0) {
                try {
                    // buffer size might be limited by OS. for linux, check net.core.wmem_max
                    val bufferSize = Math.min((contentLength + headerBytes.size + 32).toLong(), Math.min((if (Settings.isUseLessMemory) 131072 else 524288).toLong(), Math.round(0.2 * Settings.throttleBytesPerSec))).toInt()
                    socketChannel.socket().sendBufferSize = bufferSize
                    //Out.debug("Socket size for " + connId + " is now " + socketChannel.socket().getSendBufferSize() + " (requested " + bufferSize + ")");
                } catch (e: Exception) {
                    Out.info(e.message)
                }

            }

            // we try to feed the SocketChannel buffers in chunks of 1460 bytes, to make the TCP/IP packets fit neatly into the 1500 byte Ethernet MTU
            // because of this, we record the number of remainder bytes after the header has been written, and limit the first packet to 1460 sans that count
            // we do not have any guarantees that the header won't get shipped off in a packet by itself, but generally this should improve fillrate and prevent fragmentation
            var lingeringBytes = headerBytes.size % Settings.TCP_PACKET_SIZE

            // wrap and write the header
            val bwm = httpServer.bandwidthMonitor
            var tcpBuffer = ByteBuffer.wrap(headerBytes)
            var lastWriteLen = 0

            if (bwm != null && !isLocalNetworkAccess) {
                bwm.waitForQuota(myThread, tcpBuffer.remaining())
            }

            while (tcpBuffer.hasRemaining()) {
                lastWriteLen += socketChannel.write(tcpBuffer)
            }

            //Out.debug("Wrote " + lastWriteLen + " header bytes to socketChannel for connId=" + connId);

            if (!isLocalNetworkAccess) {
                Stats.bytesSent(lastWriteLen)
            }

            if (hr!!.isRequestHeadOnly) {
                // if this is a HEAD request, we are done
                info += "Code=$statusCode "
                Out.info(info + (request ?: "Invalid Request"))
            } else {
                // if this is a GET request, process the body if we have one
                info += "Code=" + statusCode + " Bytes=" + String.format("%1$-8s", contentLength) + " "

                if (request != null) {
                    // skip the startup message for error requests
                    Out.info(info + request)
                }

                val startTime = System.currentTimeMillis()

                if (contentLength > 0) {
                    var writtenBytes = 0

                    while (writtenBytes < contentLength) {
                        lastPacketSend = System.currentTimeMillis()
                        tcpBuffer = hpc.getPreparedTCPBuffer(lingeringBytes)
                        lingeringBytes = 0
                        lastWriteLen = 0

                        if (bwm != null && !isLocalNetworkAccess) {
                            bwm.waitForQuota(myThread, tcpBuffer.remaining())
                        }

                        while (tcpBuffer.hasRemaining()) {
                            lastWriteLen += socketChannel.write(tcpBuffer)

                            // we should be blocking, but if we're not, loop until the buffer is empty
                            if (tcpBuffer.hasRemaining()) {
                                Thread.sleep(1)
                            }
                        }

                        //Out.debug("Wrote " + lastWriteLen + " content bytes to socketChannel for connId=" + connId);

                        writtenBytes += lastWriteLen

                        if (!isLocalNetworkAccess) {
                            Stats.bytesSent(lastWriteLen)
                        }
                    }
                }

                val sendTime = System.currentTimeMillis() - startTime
                val df = DecimalFormat("0.00")
                Out.info(info + "Finished processing request in " + df.format(sendTime / 1000.0) + " seconds" + if (sendTime >= 10) " (" + df.format((contentLength / sendTime.toFloat()).toDouble()) + " KB/s)" else "")
            }
        } catch (e: Exception) {
            Out.info(info + "The connection was interrupted or closed by the remote host.")
            Out.debug(if (e == null) "(no exception)" else e.message)
            //e.printStackTrace();
        } finally {
            hpc?.cleanup()

            try {
                socketChannel.close()
            } catch (e: Exception) {
            }

        }

        connectionFinished()
    }

    private fun getHTTPStatusHeader(statuscode: Int): String {
        when (statuscode) {
            200 -> return "HTTP/1.1 200 OK$CRLF"
            301 -> return "HTTP/1.1 301 Moved Permanently$CRLF"
            400 -> return "HTTP/1.1 400 Bad Request$CRLF"
            403 -> return "HTTP/1.1 403 Permission Denied$CRLF"
            404 -> return "HTTP/1.1 404 Not Found$CRLF"
            405 -> return "HTTP/1.1 405 Method Not Allowed$CRLF"
            418 -> return "HTTP/1.1 418 I'm a teapot$CRLF"
            501 -> return "HTTP/1.1 501 Not Implemented$CRLF"
            502 -> return "HTTP/1.1 502 Bad Gateway$CRLF"
            else -> return "HTTP/1.1 500 Internal Server Error$CRLF"
        }
    }

    fun doTimeoutCheck(forceKill: Boolean): Boolean {
        val nowtime = System.currentTimeMillis()

        if (lastPacketSend < nowtime - 1000 && !socketChannel.isOpen) {
            // the connecion was already closed and should be removed by the HTTPServer instance.
            // the lastPacketSend check was added to prevent spurious "Killing stuck session" errors
            return true
        } else {
            val startTimeout = if (hr != null) if (hr!!.isServercmd) 1800000 else 180000 else 30000

            if (forceKill || sessionStartTime > 0 && sessionStartTime < nowtime - startTimeout || lastPacketSend > 0 && lastPacketSend < nowtime - 30000) {
                // DIE DIE DIE
                //Out.info(this + " The connection has exceeded its time limits: timing out.");
                try {
                    socketChannel.close()
                } catch (e: Exception) {
                    Out.debug(e.toString())
                }

            }
        }

        return false
    }

    override fun toString(): String {
        return "{" + connId + String.format("%1$-17s", "$socketInetAddress}")
    }

    companion object {

        val CRLF = "\r\n"

        private val getheadPattern = Pattern.compile("^((GET)|(HEAD)).*", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    }

}
