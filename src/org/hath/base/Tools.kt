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

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Arrays
import java.util.Hashtable
import java.security.MessageDigest
import java.lang.StringBuilder

object Tools {
    @Throws(java.io.IOException::class)
    fun checkAndCreateDir(dir: File): File {
        if (dir.isFile) {
            dir.delete()
        }

        if (!dir.isDirectory) {
            dir.mkdirs()
        }

        return dir
    }

    @Throws(java.io.IOException::class)
    fun getStringFileContents(file: File): String {
        val cbuf = CharArray(file.length().toInt())
        val fr = FileReader(file)
        fr.read(cbuf)
        fr.close()
        return String(cbuf)
    }

    @Throws(java.io.IOException::class)
    fun putStringFileContents(file: File, content: String) {
        val fw = FileWriter(file)
        fw.write(content)
        fw.close()
    }

    @Throws(java.io.IOException::class)
    fun putStringFileContents(file: File, content: String, charset: String) {
        val fileLength = content.length
        val bw = BufferedWriter(OutputStreamWriter(FileOutputStream(file), charset))
        bw.write(content, 0, fileLength)
        bw.close()
    }

    fun listSortedFiles(dir: File): Array<File>? {
        val files = dir.listFiles()

        if (files != null) {
            Arrays.sort(files)
        }

        return files
    }

    fun parseAdditional(additional: String?): Hashtable<String, String> {
        val addTable = Hashtable<String, String>()

        if (additional != null) {
            if (!additional.isEmpty()) {
                val keyValuePairs = additional.trim { it <= ' ' }.split(";".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()

                for (kvPair in keyValuePairs) {
                    // you cannot get k=v with less than a three-characters string
                    if (kvPair.length > 2) {
                        val kvPairParts = kvPair.trim({ it <= ' ' }).split("=".toRegex(), 2).toTypedArray()

                        if (kvPairParts.size == 2) {
                            addTable[kvPairParts[0].trim({ it <= ' ' })] = kvPairParts[1].trim({ it <= ' ' })
                        } else {
                            Out.warning("Invalid kvPair: $kvPair")
                        }
                    }
                }
            }
        }

        return addTable
    }

    fun getSHA1String(stringToHash: String): String? {
        var hash: String? = null

        try {
            hash = binaryToHex(MessageDigest.getInstance("SHA-1").digest(stringToHash.toByteArray()))
        } catch (e: java.security.NoSuchAlgorithmException) {
            HentaiAtHomeClient.dieWithError(e)
        }

        return hash
    }

    fun getSHA1String(fileToHash: File): String? {
        var fileChannel: FileChannel? = null
        var hash: String? = null

        try {
            fileChannel = FileChannel.open(fileToHash.toPath(), StandardOpenOption.READ)
            val messageDigest = MessageDigest.getInstance("SHA-1")
            val byteBuffer = ByteBuffer.allocateDirect(Math.min(65536, fileToHash.length()).toInt())

            while (fileChannel!!.read(byteBuffer) != -1) {
                byteBuffer.flip()
                messageDigest.update(byteBuffer)
                byteBuffer.clear()
            }

            hash = binaryToHex(messageDigest.digest())
        } catch (e: java.security.NoSuchAlgorithmException) {
            HentaiAtHomeClient.dieWithError(e)
        } catch (e: java.io.IOException) {
            Out.warning("Failed to calculate SHA-1 hash of file " + fileToHash + ": " + e.message)
        } finally {
            try {
                fileChannel!!.close()
            } catch (e: Exception) {
            }

        }

        return hash
    }

    fun binaryToHex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)

        for (b in data) {
            val i = b.toInt() and 0xff

            if (i < 0x10) {
                sb.append("0")
            }

            sb.append(Integer.toHexString(i))
        }

        return sb.toString().toLowerCase()
    }
}
