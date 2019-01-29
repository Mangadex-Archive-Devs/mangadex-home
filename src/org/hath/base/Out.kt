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

import java.util.Date
import java.util.TimeZone
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.io.File
import java.io.PrintStream
import java.io.OutputStream
import java.io.FileWriter

object Out {
    val DEBUG = 1
    val INFO = 2
    val WARNING = 4
    val ERROR = 8

    val LOGOUT = DEBUG or INFO or WARNING or ERROR
    val LOGERR = WARNING or ERROR
    val OUTPUT = INFO or WARNING or ERROR
    val VERBOSE = ERROR

    private var overridden: Boolean = false
    private var writeLogs: Boolean = false
    private var suppressedOutput: Int = 0
    private var logout_count: Int = 0
    private var logerr_count: Int = 0
    private var def_out: PrintStream? = null
    private var def_err: PrintStream? = null
    private var or_out: OutPrintStream? = null
    private var or_err: OutPrintStream? = null
    private var logout: FileWriter? = null
    private var logerr: FileWriter? = null
    private var sdf: SimpleDateFormat? = null
    private var outListeners: MutableList<OutListener>? = null

    init {
        overrideDefaultOutput()
    }

    fun overrideDefaultOutput() {
        if (overridden) {
            return
        }

        writeLogs = false
        overridden = true
        outListeners = ArrayList()

        suppressedOutput = 0

        sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'") // ISO 8601
        sdf!!.timeZone = TimeZone.getTimeZone("UTC")
        def_out = System.out
        def_err = System.err

        or_out = OutPrintStream(def_out, "out", INFO)
        or_err = OutPrintStream(def_err, "ERR", ERROR)
        System.setOut(or_out)
        System.setErr(or_err)
    }

    fun startLoggers() {
        logerr = startLogger(Settings.errorLogPath)

        if (!Settings.isDisableLogs) {
            logout = startLogger(Settings.outputLogPath)
            writeLogs = true
        }
    }

    fun addOutListener(listener: OutListener) {
        synchronized(outListeners) {
            if (!outListeners!!.contains(listener)) {
                outListeners!!.add(listener)
            }
        }
    }

    fun removeOutListener(listener: OutListener) {
        synchronized(outListeners) {
            outListeners!!.remove(listener)
        }
    }

    fun disableLogging() {
        if (writeLogs) {
            info("Logging ended.")
            writeLogs = false
            flushLogs()

            if (logout != null) {
                stopLogger(logout)
                logout = null
            }
        }
    }

    fun flushLogs() {
        if (logout != null) {
            try {
                logout!!.flush()
            } catch (e: Exception) {
            }

        }
    }

    private fun startLogger(logfile: String?): FileWriter? {
        var writer: FileWriter? = null

        if (logfile != null) {
            // delete old log if present, and rotate
            File("$logfile.old").delete()
            File(logfile).renameTo(File("$logfile.old"))

            if (logfile.length > 0) {
                try {
                    writer = FileWriter(logfile, true)
                } catch (e: java.io.IOException) {
                    e.printStackTrace()
                    System.err.println("Failed to open log file $logfile")
                }

            }
        }

        if (writer != null) {
            log("\n" + sdf!!.format(Date()) + " Logging started", writer, true)
        }

        return writer
    }

    private fun stopLogger(logger: FileWriter?): Boolean {
        try {
            logger!!.close()
        } catch (e: Exception) {
            e.printStackTrace(def_err)
            def_err!!.println("Unable to close file writer handle: Cannot rotate log.")
            return false
        }

        return true
    }

    fun debug(x: String) {
        or_out!!.println(x, "debug", DEBUG)
    }

    fun info(x: String) {
        or_out!!.println(x, "info", INFO)
    }

    fun warning(x: String) {
        or_out!!.println(x, "WARN", WARNING)
    }

    fun error(x: String) {
        or_out!!.println(x, "ERROR", ERROR)
    }

    @Synchronized
    private fun log(data: String, severity: Int) {
        if (severity and LOGOUT > 0 && writeLogs) {
            log(data, logout, false)

            if (++logout_count > 100000) {
                logout_count = 0
                def_out!!.println("Rotating output logfile...")

                if (stopLogger(logout)) {
                    logout = startLogger(Settings.outputLogPath)
                    def_out!!.println("Output logfile rotated.")
                }
            }
        }

        if (severity and LOGERR > 0) {
            log(data, logerr, true)

            if (++logerr_count > 10000) {
                logerr_count = 0
                def_out!!.println("Rotating error logfile...")

                if (stopLogger(logerr)) {
                    logerr = startLogger(Settings.errorLogPath)
                    def_out!!.println("Error logfile rotated.")
                }
            }
        }
    }

    private fun log(data: String, writer: FileWriter?, flush: Boolean) {
        // note: unsynchronized. usage of this function for a specific writer must be serialized.
        if (writer != null) {
            try {
                writer.write(data + "\n")

                if (flush || Settings.isFlushLogs) {
                    writer.flush()
                }
            } catch (ioe: java.io.IOException) {
                // IMPORTANT: writes to the default System.err to prevent loops
                ioe.printStackTrace(def_err)
            }

        }
    }

    fun verbose(severity: Int): String {
        if (severity and VERBOSE > 0) {
            val ste = java.lang.Thread.currentThread().stackTrace

            var offset = 0
            while (++offset < ste.size) {
                val s = ste[offset].className
                if (s != "org.hath.base.Out" && s != "org.hath.base.Out\$OutPrintStream" && s != "java.lang.Thread") {
                    break
                }
            }

            return if (offset < ste.size) {
                if (ste[offset].className != "java.lang.Throwable") {
                    "{" + ste[offset] + "} "
                } else {
                    ""
                }
            } else "{Unknown Source}"

        }

        return ""
    }

    private class OutPrintStream(private val ps: PrintStream, private val name: String, private val severity: Int) : PrintStream(ps) {
        private val out: Out? = null

        override fun println(x: String) {
            println(x, name, severity)
        }

        @JvmOverloads
        fun println(x: String?, name: String, severity: Int = severity) {
            if (x == null) {
                return
            }

            val output = severity and Out.OUTPUT and Out.suppressedOutput.inv() > 0
            val log = severity and (Out.LOGOUT or Out.LOGERR) > 0

            if (output || log) {
                synchronized(outListeners) {
                    val v = Out.verbose(severity)
                    val split = x.split("\n".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
                    for (s in split) {
                        val data = sdf!!.format(Date()) + " [" + name + "] " + v + s

                        if (output) {
                            ps.println(data)

                            for (listener in outListeners!!) {
                                listener.outputWritten(data)
                            }
                        }

                        if (log) {
                            Out.log(data, severity)
                        }
                    }
                }
            }
        }

        override fun println(x: Boolean) {
            println(x.toString())
        }

        override fun println(x: Char) {
            println(x.toString())
        }

        override fun println(x: CharArray) {
            println(String(x))
        }

        override fun println(x: Double) {
            println(x.toString())
        }

        override fun println(x: Float) {
            println(x.toString())
        }

        override fun println(x: Int) {
            println(x.toString())
        }

        override fun println(x: Long) {
            println(x.toString())
        }

        override fun println(x: Any?) {
            println(x.toString())
        }
    }
}
