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

import java.nio.charset.Charset
import java.nio.ByteBuffer

class HTTPResponseProcessorText @JvmOverloads constructor(responseBody: String, mimeType: String = "text/html", charset: Charset = Charset.forName("ISO-8859-1")) : HTTPResponseProcessor() {
    private val responseBytes: ByteArray?
    private var writeoff = 0
    override val contentType: String

    override val contentLength: Int
        get() = responseBytes?.size ?: 0

    init {
        val strlen = responseBody.length

        if (strlen > 0) {
            Out.debug("Response Written:")

            if (strlen < 10000) {
                Out.debug(responseBody)
            } else {
                Out.debug("tl;dw")
            }
        }

        responseBytes = responseBody.toByteArray(charset)
        contentType = mimeType + "; charset=" + charset.name()
    }

    @Throws(Exception::class)
    override fun getPreparedTCPBuffer(lingeringBytes: Int): ByteBuffer {
        val bytecount = Math.min(contentLength - writeoff, Settings.TCP_PACKET_SIZE - lingeringBytes)
        val buffer = ByteBuffer.wrap(responseBytes!!, writeoff, bytecount)
        writeoff += bytecount

        // this was a wrap, so we do not flip
        return buffer
    }

}
