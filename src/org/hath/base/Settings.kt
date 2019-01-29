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
import java.net.InetAddress
import java.util.*
import java.lang.*

object Settings {
    val NEWLINE = System.getProperty("line.separator")

    // the client build is among other things used by the server to determine the client's capabilities. any forks should use the build number as an indication of compatibility with mainline, rather than an internal build number.
    val CLIENT_BUILD = 134
    val CLIENT_KEY_LENGTH = 20
    val MAX_KEY_TIME_DRIFT = 300
    val MAX_CONNECTION_BASE = 20
    val TCP_PACKET_SIZE = 1460

    val CLIENT_VERSION = "1.4.2"
    val CLIENT_RPC_PROTOCOL = "http://"
    val CLIENT_RPC_HOST = "rpc.hentaiathome.net"
    val CLIENT_RPC_FILE = "clientapi13.php?"
    val CLIENT_LOGIN_FILENAME = "client_login"
    val CONTENT_TYPE_DEFAULT = "text/html; charset=iso-8859-1"
    val CONTENT_TYPE_OCTET = "application/octet-stream"
    val CONTENT_TYPE_JPG = "image/jpeg"
    val CONTENT_TYPE_PNG = "image/png"
    val CONTENT_TYPE_GIF = "image/gif"
    val CONTENT_TYPE_WEBM = "video/webm"

    var activeClient: HentaiAtHomeClient? = null
    var activeGUI: HathGUI? = null
    private val rpcServerLock = Any()
    private var rpcServers: Array<InetAddress>? = null
    private var rpcServerCurrent: String? = null
    private var rpcServerLastFailed: String? = null
    private var staticRanges: Hashtable<String, Int>? = null
    // accessor methods
    var dataDir: File? = null
        private set
    var logDir: File? = null
        private set
    var cacheDir: File? = null
        private set
    var tempDir: File? = null
        private set
    var downloadDir: File? = null
        private set
    var clientKey = ""
        private set
    var clientHost = ""
        private set
    private var dataDirPath = "data"
    private var logDirPath = "log"
    private var cacheDirPath = "cache"
    private var tempDirPath = "tmp"
    private var downloadDirPath = "download"

    var clientID = 0
        private set
    var clientPort = 0
        private set
    var throttleBytesPerSec = 0
        private set
    private var overrideConns = 0
    private var serverTimeDelta = 0
    var maxAllowedFileSize = 104857600
        private set
    var staticRangeCount = 0
        private set
    var diskLimitBytes: Long = 0
        private set
    var diskMinRemainingBytes: Long = 0
        private set
    var isVerifyCache = false
        private set
    var isRescanCache = false
        private set
    var isSkipFreeSpaceCheck = false
        private set
    var isWarnNewClient = false
        private set
    var isUseLessMemory = false
        private set
    var isDisableBWM = false
        private set
    var isDisableDownloadBWM = false
        private set
    var isDisableLogs = false
        private set
    var isFlushLogs = false
        private set

    val serverTime: Int
        get() = (System.currentTimeMillis() / 1000).toInt() + serverTimeDelta

    val outputLogPath: String
        get() = logDir!!.path + "/log_out"

    val errorLogPath: String
        get() = logDir!!.path + "/log_err"

    val rpcServerHost: String?
        get() {
            synchronized(rpcServerLock) {
                if (rpcServerCurrent == null) {
                    if (rpcServers == null) {
                        return Settings.CLIENT_RPC_HOST
                    }

                    if (rpcServers!!.size < 1) {
                        return Settings.CLIENT_RPC_HOST
                    }

                    if (rpcServers!!.size == 1) {
                        rpcServerCurrent = rpcServers!![0].hostAddress.toLowerCase()
                    } else {
                        var rpcServerSelector = (Math.random() * rpcServers!!.size).toInt()
                        val scanDirection = if (Math.random() < 0.5) -1 else 1

                        while (true) {
                            val candidate = rpcServers!![(rpcServers!!.size + rpcServerSelector) % rpcServers!!.size].hostAddress.toLowerCase()

                            if (rpcServerLastFailed != null) {
                                if (candidate == rpcServerLastFailed) {
                                    Out.debug(rpcServerLastFailed!! + " was marked as last failed")
                                    rpcServerSelector += scanDirection
                                    continue
                                }
                            }

                            rpcServerCurrent = candidate
                            Out.debug("Selected rpcServerCurrent=" + rpcServerCurrent!!)
                            break
                        }
                    }
                }

                return rpcServerCurrent
            }
        }

    // throttle_bytes was changed to a required value several years ago
    val maxConnections: Int
        get() = if (overrideConns > 0) {
            overrideConns
        } else {
            MAX_CONNECTION_BASE + Math.min(480, throttleBytesPerSec / 10000)
        }

    fun loginCredentialsAreSyntaxValid(): Boolean {
        return clientID > 0 && java.util.regex.Pattern.matches("^[a-zA-Z0-9]{$CLIENT_KEY_LENGTH}$", clientKey)
    }

    fun loadClientLoginFromFile(): Boolean {
        val clientLogin = File(dataDir, CLIENT_LOGIN_FILENAME)

        if (!clientLogin.exists()) {
            return false
        }

        try {
            val filecontent = Tools.getStringFileContents(clientLogin)

            if (!filecontent.isEmpty()) {
                val split = filecontent.split("-".toRegex(), 2).toTypedArray()

                if (split.size == 2) {
                    clientID = Integer.parseInt(split[0])
                    clientKey = split[1]
                    Out.info("Loaded login settings from $CLIENT_LOGIN_FILENAME")

                    return true
                }
            }
        } catch (e: Exception) {
            Out.warning("Encountered error when reading $CLIENT_LOGIN_FILENAME: $e")
        }

        return false
    }

    fun promptForIDAndKey(iqh: InputQueryHandler) {
        Out.info("Before you can use this client, you will have to register it at http://hentaiathome.net/")
        Out.info("IMPORTANT: YOU NEED A SEPARATE IDENT FOR EACH CLIENT YOU WANT TO RUN.")
        Out.info("DO NOT ENTER AN IDENT THAT WAS ASSIGNED FOR A DIFFERENT CLIENT UNLESS IT HAS BEEN RETIRED.")
        Out.info("After registering, enter your ID and Key below to start your client.")
        Out.info("(You will only have to do this once.)\n")

        clientID = 0
        clientKey = ""

        do {
            try {
                clientID = Integer.parseInt(iqh.queryString("Enter Client ID").trim { it <= ' ' })
            } catch (nfe: java.lang.NumberFormatException) {
                Out.warning("Invalid Client ID. Please try again.")
            }

        } while (clientID < 1000)

        do {
            clientKey = iqh.queryString("Enter Client Key").trim { it <= ' ' }

            if (!loginCredentialsAreSyntaxValid()) {
                Out.warning("Invalid Client Key, it must be exactly 20 alphanumerical characters. Please try again.")
            }
        } while (!loginCredentialsAreSyntaxValid())

        try {
            Tools.putStringFileContents(File(dataDir, CLIENT_LOGIN_FILENAME), "$clientID-$clientKey")
        } catch (ioe: java.io.IOException) {
            Out.warning("Error encountered when writing $CLIENT_LOGIN_FILENAME: $ioe")
        }

    }

    fun parseAndUpdateSettings(settings: Array<String>?): Boolean {
        if (settings == null) {
            return false
        }

        for (s in settings) {
            if (s != null) {
                val split = s.split("=".toRegex(), 2).toTypedArray()

                if (split.size == 2) {
                    updateSetting(split[0].toLowerCase(), split[1])
                }
            }
        }

        return true
    }

    // note that these settings will currently be overwritten by any equal ones read from the server, so it should not be used to override server-side settings.
    fun parseArgs(args: Array<String>?): Boolean {
        if (args == null) {
            return false
        }

        for (s in args) {
            if (s != null) {
                if (s.startsWith("--")) {
                    val split = s.substring(2).split("=".toRegex(), 2).toTypedArray()

                    if (split.size == 2) {
                        updateSetting(split[0].toLowerCase(), split[1])
                    } else {
                        updateSetting(split[0].toLowerCase(), "true")
                    }
                } else {
                    Out.warning("Invalid command argument: $s")
                }
            }
        }

        return true
    }

    fun updateSetting(setting: String, value: String): Boolean {
        var setting = setting
        setting = setting.replace("-", "_")

        try {
            if (setting == "min_client_build") {
                if (Integer.parseInt(value) > CLIENT_BUILD) {
                    HentaiAtHomeClient.dieWithError("Your client is too old to connect to the Hentai@Home Network. Please download the new version of the client from http://hentaiathome.net/")
                }
            } else if (setting == "cur_client_build") {
                if (Integer.parseInt(value) > CLIENT_BUILD) {
                    isWarnNewClient = true
                }
            } else if (setting == "server_time") {
                serverTimeDelta = Integer.parseInt(value) - (System.currentTimeMillis() / 1000).toInt()
                Out.debug("Setting altered: serverTimeDelta=$serverTimeDelta")
                return true
            } else if (setting == "rpc_server_ip") {
                synchronized(rpcServerLock) {
                    val split = value.split(";".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
                    rpcServers = arrayOfNulls(split.size)
                    var i = 0
                    for (s in split) {
                        rpcServers[i++] = java.net.InetAddress.getByName(s)
                    }

                    rpcServerCurrent = null
                }
            } else if (setting == "host") {
                clientHost = value
            } else if (setting == "port") {
                if (clientPort == 0) {
                    clientPort = Integer.parseInt(value)
                }
            } else if (setting == "throttle_bytes") {
                // THIS SHOULD NOT BE ALTERED BY THE CLIENT AFTER STARTUP. Using the website interface will update the throttle value for the dispatcher first, and update the client on the first stillAlive test.
                throttleBytesPerSec = Integer.parseInt(value)
            } else if (setting == "disklimit_bytes") {
                val newLimit = java.lang.Long.parseLong(value)

                if (newLimit >= diskLimitBytes) {
                    diskLimitBytes = newLimit
                } else {
                    Out.warning("The disk limit has been reduced. However, this change will not take effect until you restart your client.")
                }
            } else if (setting == "diskremaining_bytes") {
                diskMinRemainingBytes = java.lang.Long.parseLong(value)
            } else if (setting == "rescan_cache") {
                isRescanCache = value == "true"
            } else if (setting == "verify_cache") {
                isVerifyCache = value == "true"
                isRescanCache = value == "true"
            } else if (setting == "use_less_memory") {
                isUseLessMemory = value == "true"
            } else if (setting == "disable_logging") {
                isDisableLogs = value == "true"
                Out.disableLogging()
            } else if (setting == "disable_bwm") {
                isDisableBWM = value == "true"
                isDisableDownloadBWM = value == "true"
            } else if (setting == "disable_download_bwm") {
                isDisableDownloadBWM = value == "true"
            } else if (setting == "skip_free_space_check") {
                isSkipFreeSpaceCheck = value == "true"
            } else if (setting == "max_connections") {
                overrideConns = Integer.parseInt(value)
            } else if (setting == "max_allowed_filesize") {
                maxAllowedFileSize = Integer.parseInt(value)
            } else if (setting == "static_ranges") {
                staticRanges = Hashtable((value.length * 0.3).toInt())
                staticRangeCount = 0

                for (s in value.split(";".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()) {
                    if (s.length == 4) {
                        ++staticRangeCount
                        staticRanges!![s] = 1
                    }
                }
            } else if (setting == "cache_dir") {
                cacheDirPath = value
            } else if (setting == "temp_dir") {
                tempDirPath = value
            } else if (setting == "data_dir") {
                dataDirPath = value
            } else if (setting == "log_dir") {
                logDirPath = value
            } else if (setting == "download_dir") {
                downloadDirPath = value
            } else if (setting == "flush_logs") {
                isFlushLogs = value == "true"
            } else if (setting != "silentstart") {
                // don't flag errors if the setting is handled by the GUI
                Out.warning("Unknown setting $setting = $value")
                return false
            }

            Out.debug("Setting altered: $setting=$value")
            return true
        } catch (e: Exception) {
            Out.warning("Failed parsing setting $setting = $value")
        }

        return false
    }

    @Throws(java.io.IOException::class)
    fun initializeDirectories() {
        Out.debug("Using --data-dir=$dataDirPath")
        dataDir = Tools.checkAndCreateDir(File(dataDirPath))

        Out.debug("Using --log-dir=$logDirPath")
        logDir = Tools.checkAndCreateDir(File(logDirPath))

        Out.debug("Using --cache-dir=$cacheDirPath")
        cacheDir = Tools.checkAndCreateDir(File(cacheDirPath))

        Out.debug("Using --temp-dir=$tempDirPath")
        tempDir = Tools.checkAndCreateDir(File(tempDirPath))

        Out.debug("Using --download-dir=$downloadDirPath")
        downloadDir = Tools.checkAndCreateDir(File(downloadDirPath))
    }

    fun isValidRPCServer(compareTo: InetAddress): Boolean {
        synchronized(rpcServerLock) {
            if (rpcServers == null) {
                return false
            }

            for (i in rpcServers!!) {
                if (i == compareTo) {
                    return true
                }
            }

            return false
        }
    }

    fun clearRPCServerFailure() {
        synchronized(rpcServerLock) {
            if (rpcServerLastFailed != null) {
                // to avoid long-term uneven loads on the RPC servers in case one of them goes down for a bit, we run this occasionally to clear the failure
                Out.debug("Cleared rpcServerLastFailed")
                rpcServerLastFailed = null
                rpcServerCurrent = null
            }
        }
    }

    fun markRPCServerFailure(failHost: String) {
        synchronized(rpcServerLock) {
            if (rpcServerCurrent != null) {
                Out.debug("Marking $failHost as rpcServerLastFailed")
                rpcServerLastFailed = failHost
                rpcServerCurrent = null
            }
        }
    }

    fun isStaticRange(fileid: String): Boolean {
        return if (staticRanges != null) {
            // hashtable is thread-safe
            staticRanges!!.containsKey(fileid.substring(0, 4))
        } else false

    }
}
