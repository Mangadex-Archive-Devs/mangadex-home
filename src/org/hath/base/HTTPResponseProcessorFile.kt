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
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel

// this class provides provides a buffered interface to read a file in chunks

class HTTPResponseProcessorFile(private val requestedHVFile: HVFile) : HTTPResponseProcessor() {
    private var fileChannel: FileChannel? = null
    private var fileBuffer: ByteBuffer? = null
    private var readoff = 0

    override val contentType: String
        get() = requestedHVFile.mimeType

    override val contentLength: Int
        get() = if (fileChannel != null) {
            requestedHVFile.size
        } else {
            0
        }

    override fun initialize(): Int {
        var responseStatusCode = 0

        try {
            fileChannel = FileChannel.open(requestedHVFile.localFilePath, StandardOpenOption.READ)
            fileBuffer = ByteBuffer.allocateDirect(if (Settings.isUseLessMemory) 8192 else 65536)
            fileChannel!!.read(fileBuffer)
            fileBuffer!!.flip()
            responseStatusCode = 200
            Stats.fileSent()
        } catch (e: java.io.IOException) {
            Out.warning("Failed reading content from " + requestedHVFile.localFilePath)
            responseStatusCode = 500
        }

        return responseStatusCode
    }

    override fun cleanup() {
        if (fileChannel != null) {
            try {
                fileChannel!!.close()
            } catch (e: Exception) {
            }

        }
    }

    @Throws(Exception::class)
    override fun getPreparedTCPBuffer(lingeringBytes: Int): ByteBuffer {
        val readbytes = Math.min(contentLength - readoff, Settings.TCP_PACKET_SIZE - lingeringBytes)

        if (readbytes > fileBuffer!!.remaining()) {
            var fileBytes = 0
            fileBuffer!!.compact()

            while (readbytes > fileBuffer!!.position()) {
                fileBytes += fileChannel!!.read(fileBuffer)
            }

            fileBuffer!!.flip()
            //Out.debug("Refilled buffer for " + requestedHVFile + " with " + fileBytes + " bytes, new remaining=" + fileBuffer.remaining());
        }

        //Out.debug("Reading from file " + requestedHVFile + ", readoff=" + readoff + ", readbytes=" + readbytes + ", remaining=" + fileBuffer.remaining());

        val tcpBuffer = fileBuffer!!.slice()
        tcpBuffer.limit(tcpBuffer.position() + readbytes)
        fileBuffer!!.position(fileBuffer!!.position() + readbytes)
        readoff += readbytes

        return tcpBuffer
    }
}
