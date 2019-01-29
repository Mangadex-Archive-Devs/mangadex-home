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

import java.net.URL
import java.lang.StringBuilder

class ServerHandler(private val client: HentaiAtHomeClient) {
    private var lastOverloadNotification: Long = 0

    init {
        lastOverloadNotification = 0
    }

    // communications that do not use additional variables can use this
    private fun simpleNotification(act: String, humanReadable: String): Boolean {
        val sr = ServerResponse.getServerResponse(act, this)

        if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_NULL) {
            Settings.markRPCServerFailure(sr.failHost)
        }

        if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_OK) {
            Out.debug("$humanReadable notification successful.")
            return true
        } else {
            Out.warning("$humanReadable notification failed.")
            return false
        }

    }


    // simple notifications

    fun notifySuspend(): Boolean {
        return simpleNotification(ACT_CLIENT_SUSPEND, "Suspend")
    }

    fun notifyResume(): Boolean {
        return simpleNotification(ACT_CLIENT_RESUME, "Resume")
    }

    fun notifyShutdown(): Boolean {
        return simpleNotification(ACT_CLIENT_STOP, "Shutdown")
    }

    fun notifyOverload(): Boolean {
        val nowtime = System.currentTimeMillis()

        if (lastOverloadNotification < nowtime - 30000) {
            lastOverloadNotification = nowtime
            return simpleNotification(ACT_OVERLOAD, "Overload")
        }

        return false
    }


    // these communcation methods are more complex, and have their own result parsing

    fun notifyStart(): Boolean {
        val sr = ServerResponse.getServerResponse(ACT_CLIENT_START, this)

        if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_NULL) {
            Settings.markRPCServerFailure(sr.failHost)
        }

        if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_OK) {
            Out.info("Start notification successful. Note that there may be a short wait before the server registers this client on the network.")
            Stats.serverContact()
            return true
        } else {
            val failcode = sr.failCode

            Out.warning("Startup Failure: " + failcode!!)
            Out.debug(sr.toString())

            if (failcode.startsWith("FAIL_CONNECT_TEST")) {
                Out.info("")
                Out.info("************************************************************************************************************************************")
                Out.info("The client has failed the external connection test.")
                Out.info("The server failed to verify that this client is online and available from the Internet.")
                Out.info("If you are behind a firewall, please check that port " + Settings.clientPort + " is forwarded to this computer.")
                Out.info("You might also want to check that " + Settings.clientHost + " is your actual public IP address.")
                Out.info("If you need assistance with forwarding a port to this client, locate a guide for your particular router at http://portforward.com/")
                Out.info("The client will remain running so you can run port connection tests.")
                Out.info("Use Program -> Exit in windowed mode or hit Ctrl+C in console mode to exit the program.")
                Out.info("************************************************************************************************************************************")
                Out.info("")

                return false
            } else if (failcode.startsWith("FAIL_OTHER_CLIENT_CONNECTED")) {
                Out.info("")
                Out.info("************************************************************************************************************************************")
                Out.info("The server detected that another client was already connected from this computer or local network.")
                Out.info("You can only have one client running per public IP address.")
                Out.info("The program will now terminate.")
                Out.info("************************************************************************************************************************************")
                Out.info("")

                HentaiAtHomeClient.dieWithError("FAIL_OTHER_CLIENT_CONNECTED")
            } else if (failcode.startsWith("FAIL_CID_IN_USE")) {
                Out.info("")
                Out.info("************************************************************************************************************************************")
                Out.info("The server detected that another client is already using this client ident.")
                Out.info("If you want to run more than one client, you have to apply for additional idents.")
                Out.info("The program will now terminate.")
                Out.info("************************************************************************************************************************************")
                Out.info("")

                HentaiAtHomeClient.dieWithError("FAIL_CID_IN_USE")
            }
        }

        return false
    }

    fun getBlacklist(deltatime: Long): Array<String>? {
        val blacklistURL = getServerConnectionURL(ACT_GET_BLACKLIST, "" + deltatime)
        val sr = ServerResponse.getServerResponse(blacklistURL, this)

        if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_NULL) {
            Settings.markRPCServerFailure(sr.failHost)
        }

        return if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_OK) {
            sr.responseText
        } else {
            null
        }
    }

    fun stillAliveTest() {
        val cs = CakeSphere(this, client)
        cs.stillAlive()
    }

    // this MUST NOT be called after the client has started up, as it will clear out and reset the client on the server, leaving the client in a limbo until restart
    fun loadClientSettingsFromServer() {
        Stats.setProgramStatus("Loading settings from server...")
        Out.info("Connecting to the Hentai@Home Server to register client with ID " + Settings.clientID + "...")

        try {
            do {
                if (!refreshServerStat()) {
                    HentaiAtHomeClient.dieWithError("Failed to get initial stat from server.")
                }

                Out.info("Reading Hentai@Home client settings from server...")
                val sr = ServerResponse.getServerResponse(ServerHandler.ACT_CLIENT_LOGIN, this)

                if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_OK) {
                    isLoginValidated = true
                    Out.info("Applying settings...")
                    Settings.parseAndUpdateSettings(sr.responseText)
                    Out.info("Finished applying settings")
                } else if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_NULL) {
                    HentaiAtHomeClient.dieWithError("Failed to get a login response from server.")
                } else {
                    Out.warning("\nAuthentication failed, please re-enter your Client ID and Key (Code: " + sr.failCode + ")")
                    Settings.promptForIDAndKey(client.inputQueryHandler)
                }
            } while (!isLoginValidated)
        } catch (e: Exception) {
            HentaiAtHomeClient.dieWithError(e)
        }

    }

    fun refreshServerSettings(): Boolean {
        Out.info("Refreshing Hentai@Home client settings from server...")
        val sr = ServerResponse.getServerResponse(ServerHandler.ACT_CLIENT_SETTINGS, this)

        if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_NULL) {
            Settings.markRPCServerFailure(sr.failHost)
        }

        if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_OK) {
            Settings.parseAndUpdateSettings(sr.responseText)
            Out.info("Finished applying settings")
            return true
        } else {
            Out.warning("Failed to refresh settings")
            return false
        }
    }

    fun refreshServerStat(): Boolean {
        Stats.setProgramStatus("Getting initial stats from server...")
        // get timestamp and minimum client build from server
        val sr = ServerResponse.getServerResponse(ServerHandler.ACT_SERVER_STAT, this)

        if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_NULL) {
            Settings.markRPCServerFailure(sr.failHost)
        }

        if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_OK) {
            Settings.parseAndUpdateSettings(sr.responseText)
            return true
        } else {
            return false
        }
    }

    fun getStaticRangeFetchURL(fileindex: String, xres: String, fileid: String): URL? {
        val requestURL = getServerConnectionURL(ACT_STATIC_RANGE_FETCH, "$fileindex;$xres;$fileid")
        val sr = ServerResponse.getServerResponse(requestURL, this)

        if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_NULL) {
            Settings.markRPCServerFailure(sr.failHost)
        }

        if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_OK) {
            val response = sr.responseText

            try {
                return URL(response!![0])
            } catch (e: Exception) {
            }

        }

        Out.info("Failed to request static range download link for $fileid.")
        return null
    }

    fun getDownloaderFetchURL(gid: Int, page: Int, fileindex: Int, xres: String, forceImageServer: Boolean): URL? {
        val requestURL = getServerConnectionURL(ACT_DOWNLOADER_FETCH, gid.toString() + ";" + page + ";" + fileindex + ";" + xres + ";" + if (forceImageServer) 1 else 0)
        val sr = ServerResponse.getServerResponse(requestURL, this)

        if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_NULL) {
            Settings.markRPCServerFailure(sr.failHost)
        }

        if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_OK) {
            val response = sr.responseText

            try {
                return URL(response!![0])
            } catch (e: Exception) {
            }

        }

        Out.info("Failed to request gallery file url for fileindex=$fileindex.")
        return null
    }

    fun reportDownloaderFailures(failures: List<String>?) {
        if (failures == null) {
            return
        }

        val failcount = failures.size

        if (failcount < 1 || failcount > 50) {
            // if we're getting a lot of distinct failures, it's probably a problem with this client
            return
        }

        val s = StringBuilder(failcount * 30)
        var i = 0

        for (failure in failures) {
            s.append(failure)

            if (++i < failcount) {
                s.append(";")
            }
        }

        val sr = ServerResponse.getServerResponse(getServerConnectionURL(ACT_DOWNLOADER_FAILREPORT, s.toString()), this)

        if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_NULL) {
            Settings.markRPCServerFailure(sr.failHost)
        }

        Out.debug("Reported " + failcount + " download failures with response " + if (sr.responseStatus == ServerResponse.RESPONSE_STATUS_OK) "OK" else "FAIL")
    }

    companion object {
        val ACT_SERVER_STAT = "server_stat"
        val ACT_GET_BLACKLIST = "get_blacklist"
        val ACT_CLIENT_LOGIN = "client_login"
        val ACT_CLIENT_SETTINGS = "client_settings"
        val ACT_CLIENT_START = "client_start"
        val ACT_CLIENT_SUSPEND = "client_suspend"
        val ACT_CLIENT_RESUME = "client_resume"
        val ACT_CLIENT_STOP = "client_stop"
        val ACT_STILL_ALIVE = "still_alive"
        val ACT_STATIC_RANGE_FETCH = "srfetch"
        val ACT_DOWNLOADER_FETCH = "dlfetch"
        val ACT_DOWNLOADER_FAILREPORT = "dlfails"
        val ACT_OVERLOAD = "overload"
        var isLoginValidated = false
            private set

        @JvmOverloads
        fun getServerConnectionURL(act: String, add: String = ""): URL? {
            var serverConnectionURL: URL? = null

            try {
                if (act == ACT_SERVER_STAT) {
                    serverConnectionURL = URL(Settings.CLIENT_RPC_PROTOCOL + Settings.rpcServerHost + "/" + Settings.CLIENT_RPC_FILE + "clientbuild=" + Settings.CLIENT_BUILD + "&act=" + act)
                } else {
                    serverConnectionURL = URL(Settings.CLIENT_RPC_PROTOCOL + Settings.rpcServerHost + "/" + Settings.CLIENT_RPC_FILE + getURLQueryString(act, add))
                }
            } catch (e: java.net.MalformedURLException) {
                HentaiAtHomeClient.dieWithError(e)
            }

            return serverConnectionURL
        }

        fun getURLQueryString(act: String, add: String): String {
            val correctedTime = Settings.serverTime
            val actkey = Tools.getSHA1String("hentai@home-" + act + "-" + add + "-" + Settings.clientID + "-" + correctedTime + "-" + Settings.clientKey)
            return "clientbuild=" + Settings.CLIENT_BUILD + "&act=" + act + "&add=" + add + "&cid=" + Settings.clientID + "&acttime=" + correctedTime + "&actkey=" + actkey
        }
    }
}
