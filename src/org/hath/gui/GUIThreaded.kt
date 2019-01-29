/*

Copyright 2008-2016 E-Hentai.org
https://forums.e-hentai.org/
ehentai@gmail.com

This file is part of Hentai@Home GUI.

Hentai@Home GUI is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Hentai@Home GUI is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Hentai@Home GUI.  If not, see <http://www.gnu.org/licenses/>.

*/

package org.hath.gui

import org.hath.base.*

import java.lang.Thread

class GUIThreaded(private val client: HentaiAtHomeClient, private val action: Int) : Runnable {
    private val myThread: Thread

    init {
        myThread = Thread(this)
        myThread.start()
    }

    override fun run() {
        if (action == ACTION_SHUTDOWN) {
            client.shutdown()
        }
    }

    companion object {
        val ACTION_SHUTDOWN = 1
    }
}

