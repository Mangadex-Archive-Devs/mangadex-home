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

// convenience class for the GUI

import java.util.ArrayList

object Stats {

    private var statListeners: MutableList<StatListener>? = null

    // accessor methods
    var isClientRunning: Boolean = false
        private set
    var isClientSuspended: Boolean = false
        private set
    private var programStatus: String? = null
    private var clientStartTime: Long = 0
    private var filesSent: Int = 0
    private var filesRcvd: Int = 0
    var bytesSent: Long = 0
        private set
    var bytesRcvd: Long = 0
        private set
    private var cacheCount: Int = 0
    private var cacheSize: Long = 0
    var bytesSentHistory: IntArray? = null
        private set
    var openConnections: Int = 0
        set(conns) {
            field = conns
            statChanged("openConnections")
        }
    var lastServerContact: Int = 0
        private set

    val uptime: Int
        get() = uptimeDouble.toInt()

    val uptimeDouble: Double
        get() = if (isClientRunning) {
            (System.currentTimeMillis() - clientStartTime) / 1000.0
        } else 0.0

    val bytesSentPerSec: Int
        get() {
            val uptime = uptimeDouble
            return if (uptime > 0) (bytesSent / uptime).toInt() else 0
        }

    val bytesRcvdPerSec: Int
        get() {
            val uptime = uptimeDouble
            return if (uptime > 0) (bytesRcvd / uptime).toInt() else 0
        }

    val cacheFree: Long
        get() = Settings.diskLimitBytes - cacheSize

    val cacheFill: Float
        get() = if (Settings.diskLimitBytes != 0L) cacheSize / Settings.diskLimitBytes.toFloat() else 0

    init {
        statListeners = ArrayList()
        resetStats()
    }

    fun trackBytesSentHistory() {
        bytesSentHistory = IntArray(361)
    }

    fun addStatListener(listener: StatListener) {
        synchronized(statListeners) {
            if (!statListeners!!.contains(listener)) {
                statListeners!!.add(listener)
            }
        }
    }

    fun removeStatListener(listener: StatListener) {
        synchronized(statListeners) {
            statListeners!!.remove(listener)
        }
    }

    private fun statChanged(stat: String) {
        val client = Settings.activeClient
        var announce = false

        if (client == null) {
            announce = true
        } else if (!client.isShuttingDown) {
            announce = true
        }

        if (announce) {
            synchronized(statListeners) {
                for (listener in statListeners!!) {
                    listener.statChanged(stat)
                }
            }
        }
    }

    // modify methods
    fun setProgramStatus(newStatus: String) {
        programStatus = newStatus
        statChanged("programStatus")
    }

    fun resetStats() {
        isClientRunning = false
        programStatus = "Stopped"
        clientStartTime = 0
        lastServerContact = 0
        filesSent = 0
        filesRcvd = 0
        bytesSent = 0
        bytesRcvd = 0
        cacheCount = 0
        cacheSize = 0
        resetBytesSentHistory()

        statChanged("reset")
    }

    // run this from a thread every 10 seconds
    fun shiftBytesSentHistory() {
        if (bytesSentHistory == null) {
            return
        }

        for (i in 360 downTo 1) {
            bytesSentHistory[i] = bytesSentHistory!![i - 1]
        }

        bytesSentHistory[0] = 0

        statChanged("bytesSentHistory")
    }

    fun resetBytesSentHistory() {
        if (bytesSentHistory == null) {
            return
        }

        java.util.Arrays.fill(bytesSentHistory!!, 0)
        statChanged("bytesSentHistory")
    }

    fun programStarted() {
        clientStartTime = System.currentTimeMillis()
        isClientRunning = true
        setProgramStatus("Running")
        statChanged("clientRunning")
    }

    fun programSuspended() {
        isClientSuspended = true
        setProgramStatus("Suspended")
        statChanged("clientSuspended")
    }

    fun programResumed() {
        isClientSuspended = false
        setProgramStatus("Running")
        statChanged("clientSuspended")
    }

    fun serverContact() {
        lastServerContact = (System.currentTimeMillis() / 1000).toInt()
        statChanged("lastServerContact")
    }

    fun fileSent() {
        ++filesSent
        statChanged("fileSent")
    }

    fun fileRcvd() {
        ++filesRcvd
        statChanged("fileRcvd")
    }

    fun bytesSent(b: Int) {
        if (bytesSentHistory == null) {
            return
        }

        if (isClientRunning) {
            bytesSent += b.toLong()
            bytesSentHistory[0] += b
        }

        statChanged("bytesSent")
    }

    fun bytesRcvd(b: Int) {
        if (isClientRunning) {
            bytesRcvd += b.toLong()
            statChanged("bytesRcvd")
        }
    }

    fun setCacheCount(count: Int) {
        cacheCount = count
        statChanged("cacheCount")
    }

    fun setCacheSize(size: Long) {
        cacheSize = size
        statChanged("cacheSize")
    }

    fun getProgramStatus(): String? {
        return programStatus
    }

    fun getFilesSent(): Long {
        return filesSent.toLong()
    }

    fun getFilesRcvd(): Long {
        return filesRcvd.toLong()
    }

    fun getCacheCount(): Int {
        return cacheCount
    }

    fun getCacheSize(): Long {
        return cacheSize
    }
}
