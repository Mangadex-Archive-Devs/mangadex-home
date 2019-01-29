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

import java.nio.ByteBuffer

abstract class HTTPResponseProcessor {
    var header = ""
        private set

    val contentType: String
        get() = Settings.CONTENT_TYPE_DEFAULT

    val contentLength: Int
        get() = 0

    val preparedTCPBuffer: ByteBuffer
        @Throws(Exception::class)
        get() = getPreparedTCPBuffer(0)

    open fun initialize(): Int {
        return 0
    }

    open fun cleanup() {}

    @Throws(Exception::class)
    abstract fun getPreparedTCPBuffer(lingeringBytes: Int): ByteBuffer

    fun addHeaderField(name: String, value: String) {
        // TODO: encode the value if needed.
        this.header += "$name: $value\r\n"
    }

    open fun requestCompleted() {
        // if the response processor needs to do some action after the request has completed, this can be overridden
    }
}
