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
import java.util.Arrays

class ServerResponse {

    var responseStatus: Int = 0
        private set
    var responseText: Array<String>? = null
        private set
    var failCode: String? = null
        private set
    val failHost: String

    private constructor(responseStatus: Int, responseText: Array<String>) {
        this.responseStatus = responseStatus
        this.responseText = responseText
        this.failCode = null
    }

    private constructor(responseStatus: Int, failCode: String, failHost: String) {
        this.responseStatus = responseStatus
        this.failCode = failCode
        this.failHost = failHost
        this.responseText = null
    }

    override fun toString(): String {
        val sb = java.lang.StringBuffer()

        if (responseText != null) {
            for (s in responseText!!) {
                sb.append("$s,")
            }
        }

        return "ServerResponse {responseStatus=$responseStatus, responseText=$sb, failCode=$failCode}"
    }

    companion object {
        val RESPONSE_STATUS_NULL = 0
        val RESPONSE_STATUS_OK = 1
        val RESPONSE_STATUS_FAIL = -1

        fun getServerResponse(act: String, retryhandler: ServerHandler): ServerResponse {
            val serverConnectionURL = ServerHandler.getServerConnectionURL(act)
            return getServerResponse(serverConnectionURL, retryhandler, act)
        }

        fun getServerResponse(serverConnectionURL: URL?, retryhandler: ServerHandler?): ServerResponse {
            return getServerResponse(serverConnectionURL, retryhandler, null)
        }

        private fun getServerResponse(serverConnectionURL: URL?, retryhandler: ServerHandler?, retryact: String?): ServerResponse {
            val dler = FileDownloader(serverConnectionURL, 3600000, 3600000)
            val serverResponse = dler.getResponseAsString("ASCII")
                    ?: return ServerResponse(RESPONSE_STATUS_NULL, "NO_RESPONSE", serverConnectionURL!!.host.toLowerCase())

            Out.debug("Received response: $serverResponse")
            val split = serverResponse.split("\n".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()

            if (split.size < 1) {
                return ServerResponse(RESPONSE_STATUS_NULL, "NO_RESPONSE", serverConnectionURL!!.host.toLowerCase())
            } else if (split[0].startsWith("TEMPORARILY_UNAVAILABLE")) {
                return ServerResponse(RESPONSE_STATUS_NULL, "TEMPORARILY_UNAVAILABLE", serverConnectionURL!!.host.toLowerCase())
            } else if (split[0] == "OK") {
                return ServerResponse(RESPONSE_STATUS_OK, Arrays.copyOfRange<String>(split, 1, split.size))
            } else if (split[0] == "KEY_EXPIRED" && retryhandler != null && retryact != null) {
                Out.warning("Server reported expired key; attempting to refresh time from server and retrying")
                retryhandler.refreshServerStat()
                return getServerResponse(ServerHandler.getServerConnectionURL(retryact), null)
            } else {
                return ServerResponse(RESPONSE_STATUS_FAIL, split[0], serverConnectionURL!!.host.toLowerCase())
            }
        }
    }

}
