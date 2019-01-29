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

import java.lang.StringBuilder
import java.awt.*
import javax.swing.*
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener

class HHLogPane : JPanel(), OutListener, ComponentListener {
    private val LOG_LINE_COUNT = 100
    private val textArea: JTextArea
    private val loglines: Array<String>
    private val stringBuilder: StringBuilder
    private var logpointer = 0
    private var logLinesSinceRebuild = 0
    private var lastLogDisplayRebuild: Long = 0
    private val logSyncer = Any()
    private var stringCutoff = 142
    private var displayLineCount = 18
    private var windowResized = false

    init {
        loglines = arrayOfNulls(LOG_LINE_COUNT)
        stringBuilder = StringBuilder(3000)

        layout = BorderLayout()

        textArea = JTextArea("")
        textArea.font = Font("Courier", Font.PLAIN, 11)
        textArea.isEditable = false
        textArea.lineWrap = false
        addText("Hentai@Home GUI " + Settings.CLIENT_VERSION + " initializing...")
        addText("The client will automatically start up momentarily...")

        val taHolder = JScrollPane(textArea, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER)
        taHolder.preferredSize = Dimension(1000, 300)
        //taHolder.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Program Output"), BorderFactory.createEmptyBorder(5,5,5,5)), taHolder.getBorder()));

        add(taHolder, BorderLayout.CENTER)

        Out.addOutListener(this)
        addComponentListener(this)
    }

    override fun outputWritten(entry: String) {
        addText(entry)
    }

    fun addText(toAdd: String) {
        synchronized(logSyncer) {
            if (++logpointer >= LOG_LINE_COUNT) {
                logpointer = 0
            }

            if (toAdd.length > stringCutoff) {
                loglines[logpointer] = toAdd.substring(0, stringCutoff)
            } else {
                loglines[logpointer] = toAdd
            }

            ++logLinesSinceRebuild
        }
    }

    @Synchronized
    fun checkRebuildLogDisplay() {
        val nowtime = System.currentTimeMillis()

        if (windowResized) {
            windowResized = false
            stringCutoff = Math.max(stringCutoff, width / 7)
            displayLineCount = Math.max(1, Math.min(LOG_LINE_COUNT, height / 16))
        } else if (logLinesSinceRebuild < 1 || nowtime - lastLogDisplayRebuild < 500) {
            return
        }

        lastLogDisplayRebuild = nowtime
        logLinesSinceRebuild = 0
        stringBuilder.setLength(0)
        var displayLineIndex = LOG_LINE_COUNT - displayLineCount

        // sync to prevent weirdness from threads adding text to the log array while the display text is building
        synchronized(logSyncer) {
            while (++displayLineIndex <= LOG_LINE_COUNT) {
                var logindex = logpointer + displayLineIndex
                logindex = if (logindex >= LOG_LINE_COUNT) logindex - LOG_LINE_COUNT else logindex

                if (loglines[logindex] != null) {
                    stringBuilder.append(loglines[logindex])
                    stringBuilder.append("\n")
                }
            }
        }

        textArea.text = stringBuilder.toString()
        textArea.caretPosition = stringBuilder.length
    }

    override fun componentHidden(event: ComponentEvent) {}
    override fun componentMoved(event: ComponentEvent) {}
    override fun componentShown(event: ComponentEvent) {}

    override fun componentResized(event: ComponentEvent) {
        windowResized = true
        checkRebuildLogDisplay()
    }
}
