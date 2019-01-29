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

/*

1.4.2

- Workaround for broken backwards compatibility in Java 9 by removing dependency on module javax.xml.bind.DatatypeConverter that prevented H@H from working without manually loading the module.

- Previously, when shutting down, the client would immediately stop receiving new HTTP connections, then wait 25 seconds before exiting. Now, the client will wait five seconds before closing the socket, then shut down as soon as all pending requests have completed or when 25 additional seconds have passed. This will generally result in both faster and cleaner shutdowns than before.

- If the client was terminated between initializing the cache and finishing startup, the cache state was not saved and a full rescan would be required. This should now be fixed.

- If the client errored out from the program's main loop, most commonly when running out of disk space, the cache state might fail to save depending on thread interrupt timings. This should now be fixed.

- The specific failure reason should now always be printed if startup tests fail.


[b]To update an existing client: shut it down, download [url=https://repo.e-hentai.org/hath/HentaiAtHome_1.4.2.zip]Hentai@Home 1.4.2[/url], extract the archive, copy the jar files over the existing ones, then restart the client.[/b]

[b]The full source code for H@H is available and licensed under the GNU General Public License v3, and can be downloaded [url=https://repo.e-hentai.org/hath/HentaiAtHome_1.4.2_src.zip]here[/url]. Building it from source only requires the free Java SE 7 JDK.[/b]

[b]For information on how to join Hentai@Home, check out [url=https://forums.e-hentai.org/index.php?showtopic=19795]The Hentai@Home Project FAQ[/url].[/b]

[b]Other download options can be found at [url=https://e-hentai.org/hentaiathome.php]the usual place[/url].[/b]

*/

package org.hath.base

import java.io.File
import java.lang.Thread
import java.lang.Runtime

class HentaiAtHomeClient(val inputQueryHandler: InputQueryHandler, private val args: Array<String>) : Runnable {
    private var out: Out? = null
    var isShuttingDown: Boolean = false
        private set
    private var reportShutdown: Boolean = false
    private var fastShutdown: Boolean = false
    private var threadInterruptable: Boolean = false
    var httpServer: HTTPServer? = null
        private set
    var clientAPI: ClientAPI? = null
        private set
    var cacheHandler: CacheHandler? = null
        private set
    var serverHandler: ServerHandler? = null
        private set
    private val myThread: Thread
    private var galleryDownloader: GalleryDownloader? = null
    private val runtime: Runtime
    private var threadSkipCounter: Int = 0
    private var suspendedUntil: Long = 0

    val isSuspended: Boolean
        get() = suspendedUntil > System.currentTimeMillis()

    init {
        isShuttingDown = false
        reportShutdown = false
        threadInterruptable = false
        runtime = Runtime.getRuntime()

        myThread = Thread(this)
        myThread.start()
    }

    // master thread for all regularly scheduled tasks
    // note that this function also does most of the program initialization, so that the GUI thread doesn't get locked up doing this when the program is launched through the GUI extension.
    override fun run() {
        out = Out()

        System.setProperty("http.keepAlive", "false")

        Settings.activeClient = this
        Settings.parseArgs(args)

        try {
            Settings.initializeDirectories()
        } catch (ioe: java.io.IOException) {
            Out.error("Could not create program directories. Check file access permissions and free disk space.")
            System.exit(-1)
        }

        Out.startLoggers()
        Out.info("Hentai@Home " + Settings.CLIENT_VERSION + " (Build " + Settings.CLIENT_BUILD + ") starting up\n")
        Out.info("Copyright (c) 2008-2017, E-Hentai.org - all rights reserved.")
        Out.info("This software comes with ABSOLUTELY NO WARRANTY. This is free software, and you are welcome to modify and redistribute it under the GPL v3 license.\n")

        Stats.resetStats()
        Stats.setProgramStatus("Logging in to main server...")

        // processes commands from the server and interfacing code (like a GUI layer)
        clientAPI = ClientAPI(this)

        Settings.loadClientLoginFromFile()

        if (!Settings.loginCredentialsAreSyntaxValid()) {
            Settings.promptForIDAndKey(inputQueryHandler)
        }

        // handles notifications other communication with the hentai@home server
        serverHandler = ServerHandler(this)
        serverHandler!!.loadClientSettingsFromServer()

        Stats.setProgramStatus("Initializing cache handler...")

        try {
            // manages the files in the cache
            cacheHandler = CacheHandler(this)
        } catch (ioe: java.io.IOException) {
            setFastShutdown()
            dieWithError(ioe)
            return
        }

        if (isShuttingDown) {
            return
        }

        // if something causes the client to terminate after this point, we want the cache to be shut down cleanly to store the state
        java.lang.Runtime.getRuntime().addShutdownHook(ShutdownHook())

        Stats.setProgramStatus("Starting HTTP server...")

        // handles HTTP connections used to request images and receive commands from the server
        httpServer = HTTPServer(this)

        if (!httpServer!!.startConnectionListener(Settings.clientPort)) {
            setFastShutdown()
            dieWithError("Failed to initialize HTTPServer")
            return
        }

        Stats.setProgramStatus("Sending startup notification...")

        Out.info("Notifying the server that we have finished starting up the client...")

        if (!serverHandler!!.notifyStart()) {
            setFastShutdown()
            Out.info("Startup notification failed.")
            return
        }

        httpServer!!.allowNormalConnections()
        reportShutdown = true

        if (Settings.isWarnNewClient) {
            val newClientWarning = "A new client version is available. Please download it from http://hentaiathome.net/ at your convenience."
            Out.warning(newClientWarning)

            if (Settings.activeGUI != null) {
                Settings.activeGUI!!.notifyWarning("New Version Available", newClientWarning)
            }
        }

        if (cacheHandler!!.cacheCount < 1) {
            Out.info("Important: Your cache does not yet contain any files. You won't see any traffic until the client has downloaded some.")
            Out.info("For a brand new client, it can take several hours before you start seeing any real traffic.")
        }

        // check if we're in an active schedule
        serverHandler!!.refreshServerSettings()

        Stats.resetBytesSentHistory()
        Stats.programStarted()

        cacheHandler!!.processBlacklist(259200)

        suspendedUntil = 0
        threadSkipCounter = 1

        var lastThreadTime: Long = 0

        System.gc()

        Out.info("H@H initialization completed successfully. Starting normal operation")

        while (!isShuttingDown) {
            // this toggle prevents the thread from calling interrupt on itself in case an error triggers a shutdown from the main thread, which could interfere with interruptable filechannel operations when saving cachehandler state
            // not thread-safe; this variable should not be relied upon outside the shutdown hook
            threadInterruptable = true

            try {
                Thread.sleep(Math.max(1000, 10000 - lastThreadTime))
            } catch (e: java.lang.InterruptedException) {
                Out.debug("Master thread sleep interrupted")
            }

            // thread has left the sleep state and is no longer interruptable
            threadInterruptable = false

            val startTime = System.currentTimeMillis()

            if (!isShuttingDown && suspendedUntil < System.currentTimeMillis()) {
                Stats.setProgramStatus("Running")

                if (suspendedUntil > 0) {
                    resumeMasterThread()
                }

                if (threadSkipCounter % 11 == 0) {
                    serverHandler!!.stillAliveTest()
                }

                if (threadSkipCounter % 6 == 2) {
                    httpServer!!.pruneFloodControlTable()
                }

                if (threadSkipCounter % 1440 == 1439) {
                    Settings.clearRPCServerFailure()
                }

                if (threadSkipCounter % 2160 == 2159) {
                    cacheHandler!!.processBlacklist(43200)
                }

                cacheHandler!!.cycleLRUCacheTable()
                httpServer!!.nukeOldConnections(false)
                Stats.shiftBytesSentHistory()

                for (i in 0 until cacheHandler!!.pruneAggression) {
                    if (!cacheHandler!!.recheckFreeDiskSpace()) {
                        // disk is full. time to shut down so we don't add to the damage.
                        dieWithError("The free disk space has dropped below the minimum allowed threshold. H@H cannot safely continue.\nFree up space for H@H, or reduce the cache size from the H@H settings page:\nhttps://e-hentai.org/hentaiathome.php?cid=" + Settings.clientID)
                    }
                }

                System.gc()
                Out.debug("Memory total=" + runtime.totalMemory() / 1024 + "kB free=" + runtime.freeMemory() / 1024 + "kB max=" + runtime.maxMemory() / 1024 + "kB")

                ++threadSkipCounter
            }

            lastThreadTime = System.currentTimeMillis() - startTime
        }
    }

    fun suspendMasterThread(suspendTime: Int): Boolean {
        if (suspendTime > 0 && suspendTime <= 86400 && suspendedUntil < System.currentTimeMillis()) {
            Stats.programSuspended()
            val suspendTimeMillis = (suspendTime * 1000).toLong()
            suspendedUntil = System.currentTimeMillis() + suspendTimeMillis
            Out.debug("Master thread suppressed for " + suspendTimeMillis / 1000 + " seconds.")
            return serverHandler!!.notifySuspend()
        } else {
            return false
        }
    }

    fun resumeMasterThread(): Boolean {
        suspendedUntil = 0
        threadSkipCounter = 0
        Stats.programResumed()
        return serverHandler!!.notifyResume()
    }

    @Synchronized
    fun startDownloader() {
        if (galleryDownloader == null) {
            galleryDownloader = GalleryDownloader(this)
        }
    }

    fun deleteDownloader() {
        galleryDownloader = null
    }

    fun setFastShutdown() {
        Out.flushLogs()
        fastShutdown = true
    }

    fun shutdown() {
        shutdown(false, null)
    }

    private fun shutdown(error: String) {
        shutdown(false, error)
    }

    private fun shutdown(fromShutdownHook: Boolean, shutdownErrorMessage: String?) {
        Out.flushLogs()

        if (!isShuttingDown) {
            isShuttingDown = true
            Out.info("Shutting down...")

            if (reportShutdown) {
                if (serverHandler != null) {
                    serverHandler!!.notifyShutdown()
                }

                if (!fastShutdown && httpServer != null) {
                    Out.info("Shutdown in progress - please wait up to 30 seconds")

                    try {
                        Thread.currentThread().sleep(5000)
                    } catch (e: java.lang.InterruptedException) {
                    }

                    httpServer!!.stopConnectionListener()
                    var closeWaitCycles = 0
                    val maxWaitCycles = 25

                    while (++closeWaitCycles < maxWaitCycles && Stats.openConnections > 0) {
                        try {
                            Thread.currentThread().sleep(1000)
                        } catch (e: java.lang.InterruptedException) {
                        }

                        if (closeWaitCycles % 5 == 0) {
                            Out.info("Waiting for " + Stats.openConnections + " request(s) to finish; will wait for another " + (maxWaitCycles - closeWaitCycles) + " seconds")
                        }
                    }

                    if (Stats.openConnections > 0) {
                        httpServer!!.nukeOldConnections(true)
                        Out.info("All connections cleared.")
                    }
                }
            }

            if (threadInterruptable) {
                myThread.interrupt()
            }

            if (Math.random() > 0.99) {
                Out.info(
                        "                             .,---.\n" +
                                "                           ,/XM#MMMX;,\n" +
                                "                         -%##########M%,\n" +
                                "                        -@######%  $###@=\n" +
                                "         .,--,         -H#######$   $###M:\n" +
                                "      ,;\$M###MMX;     .;##########$;HM###X=\n" +
                                "    ,/@##########H=      ;################+\n" +
                                "   -+#############M/,      %##############+\n" +
                                "   %M###############=      /##############:\n" +
                                "   H################      .M#############;.\n" +
                                "   @###############M      ,@###########M:.\n" +
                                "   X################,      -$=X#######@:\n" +
                                "   /@##################%-     +######$-\n" +
                                "   .;##################X     .X#####+,\n" +
                                "    .;H################/     -X####+.\n" +
                                "      ,;X##############,       .MM/\n" +
                                "         ,:+\$H@M#######M#$-    .$$=\n" +
                                "              .,-=;+$@###X:    ;/=.\n" +
                                "                     .,/X$;   .::,\n" +
                                "                         .,    ..    \n")
            } else {
                val sd = arrayOf("I don't hate you", "Whyyyyyyyy...", "No hard feelings", "Your business is appreciated", "Good-night")
                Out.info(sd[Math.floor(Math.random() * sd.size).toInt()])
            }

            if (cacheHandler != null) {
                cacheHandler!!.terminateCache()
            }

            if (shutdownErrorMessage != null) {
                if (Settings.activeGUI != null) {
                    Settings.activeGUI!!.notifyError(shutdownErrorMessage)
                }
            }

            Out.disableLogging()
        }

        if (!fromShutdownHook) {
            System.exit(0)
        }
    }

    private inner class ShutdownHook : Thread() {
        override fun run() {
            shutdown(true, null)
        }
    }

    companion object {

        // static crap

        fun dieWithError(e: Exception) {
            e.printStackTrace()
            dieWithError(e.toString())
        }

        fun dieWithError(error: String) {
            Out.error("Critical Error: $error")
            Stats.setProgramStatus("Died")
            Settings.activeClient!!.shutdown(false, error)
        }

        @JvmStatic
        fun main(args: Array<String>) {
            var iqh: InputQueryHandler? = null

            try {
                iqh = InputQueryHandlerCLI.iqhcli
                HentaiAtHomeClient(iqh, args)
            } catch (e: Exception) {
                Out.error("Failed to initialize InputQueryHandler")
            }

        }
    }
}