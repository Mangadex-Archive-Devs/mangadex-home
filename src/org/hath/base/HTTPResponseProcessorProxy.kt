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
import java.nio.ByteBuffer

class HTTPResponseProcessorProxy(private val session: HTTPSession, fileid: String, source: URL) : HTTPResponseProcessor() {
    private val proxyDownloader: ProxyFileDownloader
    private var readoff = 0
    private var tcpBuffer: ByteBuffer? = null

    override val contentType: String
        get() = proxyDownloader.contentType

    override val contentLength: Int
        get() = proxyDownloader.contentLength

    init {
        proxyDownloader = ProxyFileDownloader(session.httpServer.hentaiAtHomeClient, fileid, source)
    }

    override fun initialize(): Int {
        Out.info("$session: Initializing proxy request...")
        tcpBuffer = ByteBuffer.allocateDirect(Settings.TCP_PACKET_SIZE)
        return proxyDownloader.initialize()
    }

    @Throws(Exception::class)
    override fun getPreparedTCPBuffer(lingeringBytes: Int): ByteBuffer {
        tcpBuffer!!.clear()

        if (lingeringBytes > 0) {
            tcpBuffer!!.limit(Settings.TCP_PACKET_SIZE - lingeringBytes)
        }

        var timeout = 0
        val nextReadThrehold = Math.min(contentLength, readoff + tcpBuffer!!.limit())
        //Out.debug("Filling buffer with limit=" + tcpBuffer.limit() + " at readoff=" + readoff + ", trying to read " + (nextReadThrehold - readoff) + " bytes up to byte " + nextReadThrehold);

        while (nextReadThrehold > proxyDownloader.currentWriteoff) {
            try {
                Thread.currentThread().sleep(10)
            } catch (e: Exception) {
            }

            if (++timeout > 30000) {
                // we have waited about five minutes, probably won't happen
                throw Exception("Timeout while waiting for proxy request.")
            }
        }

        val readBytes = proxyDownloader.fillBuffer(tcpBuffer, readoff)
        readoff += readBytes

        tcpBuffer!!.flip()
        return tcpBuffer
    }

    override fun requestCompleted() {
        proxyDownloader.proxyThreadCompleted()
    }
}
