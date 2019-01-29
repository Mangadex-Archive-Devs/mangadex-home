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

import java.util.Arrays
import java.text.DecimalFormat
import java.awt.*
import javax.swing.*

class HHControlPane(private val clientGUI: HentaiAtHomeClientGUI) : JPanel() {
    private val statPane: StatPane
    private val graphPane: GraphPane
    private val argStrings = arrayOf("Client Status:", "Uptime:", "Last Check-In:", "Total Files Sent:", "Total Files Rcvd:", "Total Bytes Sent:", "Total Bytes Rcvd:", "Avg Bytes Sent:", "Avg Bytes Rcvd:", "Cache Filecount:", "Used Cache Size:", "Cache Utilization:", "Free Cache Size:", "Static Ranges:", "Connections:")
    private var argLengths: IntArray? = null
    private val df: DecimalFormat

    init {
        df = DecimalFormat("0.00")

        preferredSize = Dimension(1000, 220)
        layout = BorderLayout()

        statPane = StatPane()
        graphPane = GraphPane()

        add(statPane, BorderLayout.LINE_START)
        add(graphPane, BorderLayout.CENTER)
    }

    fun updateData() {
        statPane.updateStats()
    }

    private inner class StatPane : JPanel(), StatListener {
        private var myFont: Font? = null

        init {

            preferredSize = Dimension(350, 220)
            border = BorderFactory.createCompoundBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Program Stats"), BorderFactory.createEmptyBorder(5, 5, 5, 5)), border)
            Stats.addStatListener(this)

            repaint()
        }

        override fun statChanged(stat: String) {
            //repaint(200);
        }

        fun updateStats() {
            repaint(50)
        }

        override fun paint(g: Graphics?) {
            if (!clientGUI.isShowing) {
                return
            }

            val g2 = g as Graphics2D?
            g2!!.clearRect(0, 0, width, height)

            super.paint(g)

            if (myFont == null) {
                myFont = Font("Sans-serif", Font.PLAIN, 10)
            }

            g2.font = myFont
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color.BLACK

            val xoff = 10
            val yoff = 30
            val yspace = 24
            val xargwidth = 95
            val xvalwidth = 70
            val x1pos = xoff + xargwidth
            val x2pos = x1pos + xvalwidth + xargwidth

            if (argLengths == null) {
                // FontMetrics is inefficient, so we only want to do this once
                val fontMetrics = g2.fontMetrics
                argLengths = IntArray(argStrings.size)
                var i = 0

                for (arg in argStrings) {
                    argLengths[i++] = fontMetrics.stringWidth(arg)
                }
            }

            var argx = 0
            var argl = 0

            for (arg in argStrings) {
                g2.drawString(arg, (if (argx % 2 == 0) x1pos else x2pos) - argLengths!![argl++] - 7, yoff + yspace * (argx / 2))
                argx += if (argx == 0) 2 else 1
            }

            val lastServerContact = Stats.lastServerContact
            val rawbytesSent = Stats.bytesSent
            val rawbytesRcvd = Stats.bytesRcvd
            val rawbytesSentPerSec = Stats.bytesSentPerSec
            val rawbytesRcvdPerSec = Stats.bytesRcvdPerSec
            val rawcacheSize = Stats.getCacheSize()
            val rawcacheFree = Stats.cacheFree

            g2.drawString(Stats.getProgramStatus(), x1pos, yoff + yspace * 0)
            g2.drawString(Stats.uptime / 3600 + " hr " + Stats.uptime % 3600 / 60 + " min", x1pos, yoff + yspace * 1)
            g2.drawString(if (lastServerContact == 0) "Never" else ((System.currentTimeMillis() / 1000).toInt() - lastServerContact).toString() + " sec ago", x2pos, yoff + yspace * 1)
            g2.drawString(Stats.getFilesSent().toString() + "", x1pos, yoff + yspace * 2)
            g2.drawString(Stats.getFilesRcvd().toString() + "", x2pos, yoff + yspace * 2)
            g2.drawString(StorageUnit.of(rawbytesSent).format(rawbytesSent), x1pos, yoff + yspace * 3)
            g2.drawString(StorageUnit.of(rawbytesRcvd).format(rawbytesRcvd), x2pos, yoff + yspace * 3)
            g2.drawString(StorageUnit.of(rawbytesSentPerSec.toLong()).format(rawbytesSentPerSec.toLong()) + "/s", x1pos, yoff + yspace * 4)
            g2.drawString(StorageUnit.of(rawbytesRcvdPerSec.toLong()).format(rawbytesRcvdPerSec.toLong()) + "/s", x2pos, yoff + yspace * 4)
            g2.drawString(Stats.getCacheCount().toString() + "", x1pos, yoff + yspace * 5)
            g2.drawString(StorageUnit.of(rawcacheSize).format(rawcacheSize), x2pos, yoff + yspace * 5)
            g2.drawString(df.format((Stats.cacheFill * 100).toDouble()) + "%", x1pos, yoff + yspace * 6)
            g2.drawString(StorageUnit.of(rawcacheFree).format(rawcacheFree), x2pos, yoff + yspace * 6)
            g2.drawString(Settings.staticRangeCount + "", x1pos, yoff + yspace * 7)
            g2.drawString(Stats.openConnections.toString() + " / " + Settings.maxConnections, x2pos, yoff + yspace * 7)
        }
    }

    private inner class GraphPane : JPanel(), StatListener {
        private var graphHeights: ShortArray? = null
        private val lastGraphRefresh: Long = 0
        private var peakSpeedKBps = 0
        private val graphStroke: BasicStroke
        private val otherStroke: BasicStroke
        private var myFont: Font? = null

        init {

            graphStroke = BasicStroke(2f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER)
            otherStroke = BasicStroke(1f)

            minimumSize = Dimension(650, 220)
            //setBorder(BorderFactory.createCompoundBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Beautiful Line"), BorderFactory.createEmptyBorder(5,5,5,5)), getBorder()));
            Stats.addStatListener(this)

            repaint()
        }

        override fun statChanged(stat: String) {
            if (stat == "bytesSentHistory") {
                repaint()
            }
        }

        override fun paint(g: Graphics?) {
            if (!clientGUI.isShowing) {
                return
            }

            val g2 = g as Graphics2D?
            g2!!.clearRect(0, 0, width, height)

            super.paint(g)

            if (myFont == null) {
                myFont = Font("Sans-serif", Font.PLAIN, 10)
            }

            g2.font = myFont
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val xoff = 0
            val adjustedxwidth = width - 2
            val blipwidth = adjustedxwidth / 354.0

            g2.color = Color.BLACK
            g2.fillRect(xoff, 2, adjustedxwidth + 1, 215)

            g2.color = Color.GRAY

            val barwidth = adjustedxwidth / 6
            val blipBottom = 215
            val blipTop = 2

            for (i in 1..5) {
                val xpos = xoff + barwidth * i
                g2.drawLine(xpos, blipTop, xpos, blipBottom)
            }

            val blipLeftOff = xoff + 1
            val blipMaxHeight = blipBottom - blipTop

            if (graphHeights == null || lastGraphRefresh < System.currentTimeMillis() - 10000) {
                if (graphHeights == null) {
                    // the bytesSent array has 361 entries with the current decasecond as index 0, so we want to read the data from index 1-360
                    // because every entry is the average over the last six entries, we lose the first five entries for the graphHeights array
                    graphHeights = ShortArray(354)
                }

                Arrays.fill(graphHeights!!, 0.toShort())

                val bytesSent = Stats.bytesSentHistory
                var maxBytesSentPerMinute = 6000000.0

                if (bytesSent != null) {
                    val initialAverage = (bytesSent[360] + bytesSent[359] + bytesSent[358] + bytesSent[357] + bytesSent[356] + bytesSent[355]).toDouble()
                    var bytesLastMinute = initialAverage

                    for (i in 360 downTo 7) {
                        bytesLastMinute = bytesLastMinute + bytesSent[i - 6] - bytesSent[i]
                        maxBytesSentPerMinute = Math.max(maxBytesSentPerMinute, bytesLastMinute)
                    }

                    maxBytesSentPerMinute = 1200000 * Math.ceil(maxBytesSentPerMinute / 1200000)
                    bytesLastMinute = initialAverage
                    var newi = 0

                    for (i in 360 downTo 7) {
                        bytesLastMinute = bytesLastMinute + bytesSent[i - 6] - bytesSent[i]
                        graphHeights[newi++] = (blipMaxHeight * bytesLastMinute / maxBytesSentPerMinute).toShort()
                    }

                    /*
					java.lang.StringBuilder debug = new java.lang.StringBuilder();

					for(short height : graphHeights) {
						debug.append(height + " ");
					}

					Out.debug(debug.toString());
					*/
                }

                peakSpeedKBps = (maxBytesSentPerMinute / 60000).toInt()
            }

            // draw the graph line
            g2.color = Color.GREEN
            g2.stroke = graphStroke

            var x1 = 0
            var x2 = Math.round(blipLeftOff + blipwidth * 0).toInt()
            var y1 = 0
            var y2 = Math.round((blipBottom - graphHeights!![0]).toFloat())

            for (i in 1..353) {
                x1 = x2
                x2 = Math.round(blipLeftOff + blipwidth * i).toInt()
                y1 = y2
                y2 = Math.round((blipBottom - graphHeights!![i]).toFloat())
                g2.drawLine(x1, y1, x2, y2)
            }

            // draw speed demarkers
            g2.color = Color.LIGHT_GRAY
            g2.stroke = otherStroke

            val s2 = (blipTop + blipMaxHeight * 0.25).toInt()
            val s3 = (blipTop + blipMaxHeight * 0.50).toInt()
            val s4 = (blipTop + blipMaxHeight * 0.75).toInt()

            g2.drawString("$peakSpeedKBps KB/s", 7, blipTop + 12)
            g2.drawLine(xoff, blipTop, adjustedxwidth + 3, blipTop)
            g2.drawString((peakSpeedKBps * 0.75).toInt().toString() + " KB/s", 7, s2 + 12)
            g2.drawLine(xoff, s2, adjustedxwidth + 3, s2)
            g2.drawString((peakSpeedKBps * 0.5).toInt().toString() + " KB/s", 7, s3 + 12)
            g2.drawLine(xoff, s3, adjustedxwidth + 3, s3)
            g2.drawString((peakSpeedKBps * 0.25).toInt().toString() + " KB/s", 7, s4 + 12)
            g2.drawLine(xoff, s4, adjustedxwidth + 3, s4)
        }
    }
}
