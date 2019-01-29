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

import java.net.ServerSocket
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.net.InetAddress
import java.lang.Thread
import java.util.*
import java.util.regex.Pattern

class HTTPServer(val hentaiAtHomeClient: HentaiAtHomeClient) : Runnable {
    var bandwidthMonitor: HTTPBandwidthMonitor? = null
        private set
    private var listener: ServerSocketChannel? = null
    private var myThread: Thread? = null
    private val sessions: MutableList<HTTPSession>
    private var currentConnId = 0
    private var allowNormalConnections = false
    private val floodControlTable: Hashtable<String, FloodControlEntry>
    private val localNetworkPattern: Pattern

    private val newConnId: Int
        @Synchronized get() = ++currentConnId

    init {
        sessions = Collections.checkedList(ArrayList(), HTTPSession::class.java!!)
        floodControlTable = Hashtable()

        if (!Settings.isDisableBWM) {
            bandwidthMonitor = HTTPBandwidthMonitor()
        }

        //  private network: localhost, 127.x.y.z, 10.0.0.0 - 10.255.255.255, 172.16.0.0 - 172.31.255.255,  192.168.0.0 - 192.168.255.255, 169.254.0.0 -169.254.255.255
        localNetworkPattern = Pattern.compile("^((localhost)|(127\\.)|(10\\.)|(192\\.168\\.)|(172\\.((1[6-9])|(2[0-9])|(3[0-1]))\\.)|(169\\.254\\.)|(::1)|(0:0:0:0:0:0:0:1)|(fc)|(fd)).*$")
    }

    fun startConnectionListener(port: Int): Boolean {
        try {
            Out.info("Starting up the internal HTTP Server...")

            listener = ServerSocketChannel.open()
            val ss = listener!!.socket()
            ss.bind(InetSocketAddress(port))

            myThread = Thread(this)
            myThread!!.start()

            Out.info("Internal HTTP Server was successfully started, and is listening on port $port")

            return true
        } catch (e: Exception) {
            allowNormalConnections()

            e.printStackTrace()
            Out.info("")
            Out.info("************************************************************************************************************************************")
            Out.info("Could not start the internal HTTP server.")
            Out.info("This is most likely caused by something else running on the port H@H is trying to use.")
            Out.info("In order to fix this, either shut down whatever else is using the port, or assign a different port to H@H.")
            Out.info("************************************************************************************************************************************")
            Out.info("")
        }

        return false
    }

    fun stopConnectionListener() {
        if (listener != null) {
            try {
                listener!!.close()    // will cause listener.accept() to throw an exception, terminating the accept thread
            } catch (e: Exception) {
            }

            listener = null
        }
    }

    fun pruneFloodControlTable() {
        var toPrune: MutableList<String>? = Collections.checkedList(ArrayList(), String::class.java!!)

        synchronized(floodControlTable) {
            val keys = floodControlTable.keys()

            while (keys.hasMoreElements()) {
                val key = keys.nextElement()
                if (floodControlTable[key].isStale) {
                    toPrune!!.add(key)
                }
            }

            for (key in toPrune!!) {
                floodControlTable.remove(key)
            }
        }

        toPrune!!.clear()
        toPrune = null
        System.gc()
    }

    fun nukeOldConnections(killall: Boolean) {
        synchronized(sessions) {
            // in some rare cases, the connection is unable to remove itself from the session list. if so, it will return true for doTimeoutCheck, meaning that we will have to clear it out from here instead
            val remove = Collections.checkedList(ArrayList<HTTPSession>(), HTTPSession::class.java!!)

            for (session in sessions) {
                if (session.doTimeoutCheck(killall)) {
                    Out.debug("Killing stuck session $session")
                    remove.add(session)
                }
            }

            for (session in remove) {
                sessions.remove(session)
            }
        }
    }

    fun allowNormalConnections() {
        allowNormalConnections = true
    }

    override fun run() {
        try {
            while (true) {
                val socketChannel = listener!!.accept()

                synchronized(sessions) {
                    var forceClose = false
                    val addr = socketChannel.socket().inetAddress
                    val hostAddress = addr.hostAddress.toLowerCase()
                    val localNetworkAccess = Settings.clientHost.replace("::ffff:", "") == hostAddress || localNetworkPattern.matcher(hostAddress).matches()
                    val apiServerAccess = Settings.isValidRPCServer(addr)

                    if (!apiServerAccess && !allowNormalConnections) {
                        Out.warning("Rejecting connection request from $hostAddress during startup.")
                        forceClose = true
                    } else if (!apiServerAccess && !localNetworkAccess) {
                        // connections from the API Server and the local network are not subject to the max connection limit or the flood control

                        val maxConnections = Settings.maxConnections
                        val currentConnections = sessions.size

                        if (currentConnections > maxConnections) {
                            Out.warning("Exceeded the maximum allowed number of incoming connections ($maxConnections).")
                            forceClose = true
                        } else {
                            if (currentConnections > maxConnections * 0.8) {
                                // let the dispatcher know that we're close to the breaking point. this will make it back off for 30 sec, and temporarily turns down the dispatch rate to half.
                                hentaiAtHomeClient.serverHandler!!.notifyOverload()
                            }

                            // this flood control will stop clients from opening more than ten connections over a (roughly) five second floating window, and forcibly block them for 60 seconds if they do.
                            var fce: FloodControlEntry? = null
                            synchronized(floodControlTable) {
                                fce = floodControlTable[hostAddress]
                                if (fce == null) {
                                    fce = FloodControlEntry(addr)
                                    floodControlTable[hostAddress] = fce!!
                                }
                            }

                            if (!fce!!.isBlocked) {
                                if (!fce!!.hit()) {
                                    Out.warning("Flood control activated for  $hostAddress (blocking for 60 seconds)")
                                    forceClose = true
                                }
                            } else {
                                forceClose = true
                            }
                        }
                    }

                    if (forceClose) {
                        try {
                            socketChannel.close()
                        } catch (e: Exception) {
                        }

                    } else {
                        // all is well. keep truckin'
                        val hs = HTTPSession(socketChannel, newConnId, localNetworkAccess, this)
                        sessions.add(hs)
                        Stats.openConnections = sessions.size
                        hs.handleSession()
                    }
                }
            }
        } catch (e: java.io.IOException) {
            if (!hentaiAtHomeClient.isShuttingDown) {
                Out.error("ServerSocket terminated unexpectedly!")
                HentaiAtHomeClient.dieWithError(e)
            } else {
                Out.info("ServerSocket was closed and will no longer accept new connections.")
            }

            listener = null
        }

    }

    fun removeHTTPSession(httpSession: HTTPSession) {
        synchronized(sessions) {
            sessions.remove(httpSession)
            Stats.openConnections = sessions.size
        }
    }

    private inner class FloodControlEntry(private val addr: InetAddress) {
        private var connectCount: Int = 0
        private var lastConnect: Long = 0
        private var blocktime: Long = 0

        val isStale: Boolean
            get() = lastConnect < System.currentTimeMillis() - 60000

        val isBlocked: Boolean
            get() = blocktime > System.currentTimeMillis()

        init {
            this.connectCount = 0
            this.lastConnect = 0
            this.blocktime = 0
        }

        fun hit(): Boolean {
            val nowtime = System.currentTimeMillis()
            connectCount = Math.max(0, connectCount - Math.floor(((nowtime - lastConnect) / 1000).toDouble()).toInt()) + 1
            lastConnect = nowtime

            if (connectCount > 10) {
                // block this client from connecting for 60 seconds
                blocktime = nowtime + 60000
                return false
            } else {
                return true
            }
        }
    }
}
