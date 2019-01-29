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
import javax.swing.*

class InputQueryHandlerGUI(private val frame: JFrame) : InputQueryHandler {

    override fun queryString(querytext: String): String {
        var s: String? = null

        do {
            s = JOptionPane.showInputDialog(frame, querytext, "Hentai@Home needs some input...", JOptionPane.PLAIN_MESSAGE)

            if (s == null) {
                print("Interrupted")
                System.exit(0)
            }
        } while (s!!.length == 0)

        return s
    }
}