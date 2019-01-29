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

import java.util.Random
import java.nio.ByteBuffer

class HTTPResponseProcessorSpeedtest(testsize: Int) : HTTPResponseProcessor() {
    override val contentLength = 0
    private var writeoff = 0
    private val randomLength = 8192
    private val randomBytes: ByteArray

    init {
        this.contentLength = testsize
        val rand = Random()
        randomBytes = ByteArray(randomLength)
        rand.nextBytes(randomBytes)
    }

    @Throws(Exception::class)
    override fun getPreparedTCPBuffer(lingeringBytes: Int): ByteBuffer {
        val bytecount = Math.min(contentLength - writeoff, Settings.TCP_PACKET_SIZE - lingeringBytes)
        val startbyte = Math.floor(Math.random() * (randomLength - bytecount)).toInt()

        // making this read-only is probably not necessary, but doing so is almost free, and we don't want anything messing with our precious random bytes
        val buffer = ByteBuffer.wrap(randomBytes, startbyte, bytecount).asReadOnlyBuffer()
        writeoff += bytecount

        // this was a wrap, so we do not flip
        return buffer
    }
}
