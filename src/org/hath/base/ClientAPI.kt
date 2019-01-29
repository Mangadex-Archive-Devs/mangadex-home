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

// note: this class can be invoked by local extentions to play with stuff

class ClientAPI(private val client: HentaiAtHomeClient) {

    // available hooks for controlling the client
    fun clientSuspend(suspendTime: Int): ClientAPIResult {
        return ClientAPIResult(API_COMMAND_CLIENT_SUSPEND, if (client.suspendMasterThread(suspendTime)) "OK" else "FAIL")
    }

    fun clientResume(): ClientAPIResult {
        return ClientAPIResult(API_COMMAND_CLIENT_RESUME, if (client.resumeMasterThread()) "OK" else "FAIL")
    }

    fun refreshSettings(): ClientAPIResult {
        return ClientAPIResult(API_COMMAND_REFRESH_SETTINGS, if (client.serverHandler!!.refreshServerSettings()) "OK" else "FAIL")
    }

    companion object {
        val API_COMMAND_CLIENT_START = 1
        val API_COMMAND_CLIENT_SUSPEND = 2
        val API_COMMAND_CLIENT_RESUME = 5
        val API_COMMAND_MODIFY_SETTING = 3
        val API_COMMAND_REFRESH_SETTINGS = 4
    }
}
