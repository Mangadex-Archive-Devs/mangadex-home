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

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException

class InputQueryHandlerCLI private constructor() : InputQueryHandler {
    private val cmdreader: BufferedReader

    init {
        cmdreader = BufferedReader(InputStreamReader(System.`in`))
    }

    override fun queryString(querytext: String): String? {
        print("$querytext: ")
        var s: String? = null

        try {
            s = cmdreader.readLine()
        } catch (e: IOException) {
        }

        if (s == null) {
            print("Interrupted")
            Settings.activeClient!!.shutdown()
        }

        return s
    }

    companion object {

        val iqhcli: InputQueryHandlerCLI
            @Throws(IOException::class)
            get() = InputQueryHandlerCLI()
    }
}
