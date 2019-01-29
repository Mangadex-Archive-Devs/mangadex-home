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

import java.io.File
import java.nio.file.Path

class HVFile private constructor(val hash: String, val size: Int, private val xres: Int, private val yres: Int, val type: String) {

    val localFileRef: File
        get() = File(Settings.cacheDir, hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/" + fileid)

    val localFilePath: Path
        get() = localFileRef.toPath()

    // accessors
    val mimeType: String
        get() = if (type == "jpg") {
            Settings.CONTENT_TYPE_JPG
        } else if (type == "png") {
            Settings.CONTENT_TYPE_PNG
        } else if (type == "gif") {
            Settings.CONTENT_TYPE_GIF
        } else if (type == "wbm") {
            Settings.CONTENT_TYPE_WEBM
        } else {
            Settings.CONTENT_TYPE_OCTET
        }

    val fileid: String
        get() = "$hash-$size-$xres-$yres-$type"

    val staticRange: String
        get() = hash.substring(0, 4)

    override fun toString(): String {
        return fileid
    }

    companion object {


        // static stuff
        fun isValidHVFileid(fileid: String): Boolean {
            return java.util.regex.Pattern.matches("^[a-f0-9]{40}-[0-9]{1,8}-[0-9]{1,5}-[0-9]{1,5}-((jpg)|(png)|(gif)|(wbm))$", fileid)
        }

        @JvmOverloads
        fun getHVFileFromFile(file: File, validator: FileValidator? = null): HVFile? {
            if (file.exists()) {
                val fileid = file.name

                try {
                    val hvFile = getHVFileFromFileid(fileid) ?: return null

                    if (file.length() != hvFile.size.toLong()) {
                        return null
                    }

                    if (validator != null) {
                        if (!validator.validateFile(file.toPath(), fileid.substring(0, 40))) {
                            return null
                        }
                    }

                    return hvFile
                } catch (e: java.io.IOException) {
                    e.printStackTrace()
                    Out.warning("Warning: Encountered IO error computing the hash value of $file")
                }

            }

            return null
        }

        fun getHVFileFromFileid(fileid: String): HVFile? {
            if (isValidHVFileid(fileid)) {
                try {
                    val fileidParts = fileid.split("-".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
                    val hash = fileidParts[0]
                    val size = Integer.parseInt(fileidParts[1])
                    val xres = Integer.parseInt(fileidParts[2])
                    val yres = Integer.parseInt(fileidParts[3])
                    val type = fileidParts[4]
                    return HVFile(hash, size, xres, yres, type)
                } catch (e: Exception) {
                    Out.warning("Failed to parse fileid \"$fileid\" : $e")
                }

            } else {
                Out.warning("Invalid fileid \"$fileid\"")
            }

            return null
        }
    }
}
