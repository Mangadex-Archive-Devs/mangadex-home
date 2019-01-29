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

import java.util.*
import java.util.regex.Pattern
import java.net.URL

class HTTPResponse(private val session: HTTPSession) {

    var isRequestHeadOnly: Boolean = false
        private set
    var isServercmd: Boolean = false
        private set
    // accessors

    var responseStatusCode: Int = 0
        private set

    private var hpc: HTTPResponseProcessor? = null

    val httpResponseProcessor: HTTPResponseProcessor
        get() {
            if (hpc == null) {
                hpc = HTTPResponseProcessorText("An error has occurred. ($responseStatusCode)")

                if (responseStatusCode == 405) {
                    hpc!!.addHeaderField("Allow", "GET,HEAD")
                }
            } else if (hpc is HTTPResponseProcessorFile) {
                responseStatusCode = hpc!!.initialize()
            } else if (hpc is HTTPResponseProcessorProxy) {
                responseStatusCode = hpc!!.initialize()
            } else if (hpc is HTTPResponseProcessorSpeedtest) {
                Stats.setProgramStatus("Running speed tests...")
            }

            return hpc
        }

    init {
        isServercmd = false
        isRequestHeadOnly = false
        responseStatusCode = 500    // if nothing alters this, there's a bug somewhere
    }

    private fun processRemoteAPICommand(command: String, additional: String): HTTPResponseProcessor {
        val addTable = Tools.parseAdditional(additional)
        val client = session.httpServer.hentaiAtHomeClient

        try {
            if (command.equals("still_alive", ignoreCase = true)) {
                return HTTPResponseProcessorText("I feel FANTASTIC and I'm still alive")
            } else if (command.equals("threaded_proxy_test", ignoreCase = true)) {
                return processThreadedProxyTest(addTable)
            } else if (command.equals("speed_test", ignoreCase = true)) {
                val testsize = addTable["testsize"]
                return HTTPResponseProcessorSpeedtest(if (testsize != null) Integer.parseInt(testsize) else 1000000)
            } else if (command.equals("refresh_settings", ignoreCase = true)) {
                client.serverHandler!!.refreshServerSettings()
                return HTTPResponseProcessorText("")
            } else if (command.equals("start_downloader", ignoreCase = true)) {
                client.startDownloader()
                return HTTPResponseProcessorText("")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Out.warning("$session Failed to process command")
        }

        return HTTPResponseProcessorText("INVALID_COMMAND")
    }

    private fun processThreadedProxyTest(addTable: Hashtable<String, String>): HTTPResponseProcessorText {
        val ipaddr = addTable["ipaddr"]
        val port = Integer.parseInt(addTable["port"])
        val testsize = Integer.parseInt(addTable["testsize"])
        val testcount = Integer.parseInt(addTable["testcount"])
        val testtime = Integer.parseInt(addTable["testtime"])
        val testkey = addTable["testkey"]

        Out.debug("Running threaded proxy test against ipaddr=$ipaddr port=$port testsize=$testsize testcount=$testcount testtime=$testtime testkey=$testkey")

        var successfulTests = 0
        var totalTimeMillis: Long = 0

        try {
            val testfiles = Collections.checkedList(ArrayList<FileDownloader>(), FileDownloader::class.java!!)

            for (i in 0 until testcount) {
                val source = URL("http", ipaddr, port, "/t/" + testsize + "/" + testtime + "/" + testkey + "/" + Math.floor(Math.random() * Integer.MAX_VALUE).toInt())
                Out.debug("Test thread: $source")
                val dler = FileDownloader(source, 10000, 60000, true)
                testfiles.add(dler)
                dler.startAsyncDownload()
            }

            for (dler in testfiles) {
                if (dler.waitAsyncDownload()) {
                    successfulTests += 1
                    totalTimeMillis += dler.downloadTimeMillis
                }
            }
        } catch (e: java.net.MalformedURLException) {
            HentaiAtHomeClient.dieWithError(e)
        }

        return HTTPResponseProcessorText("OK:$successfulTests-$totalTimeMillis")
    }

    fun parseRequest(request: String?, localNetworkAccess: Boolean) {
        if (request == null) {
            Out.debug("$session Client did not send a request.")
            responseStatusCode = 400
            return
        }

        val requestParts = request.trim { it <= ' ' }.split(" ".toRegex(), 3).toTypedArray()

        if (requestParts.size != 3) {
            Out.debug("$session Invalid HTTP request form.")
            responseStatusCode = 400
            return
        }

        if (!(requestParts[0].equals("GET", ignoreCase = true) || requestParts[0].equals("HEAD", ignoreCase = true)) || !requestParts[2].startsWith("HTTP/")) {
            Out.debug("$session HTTP request is not GET or HEAD.")
            responseStatusCode = 405
            return
        }

        // The request URI may be an absolute path or an absolute URI for GET/HEAD requests (see section 5.1.2 of RFC2616)
        requestParts[1] = absoluteUriPattern.matcher(requestParts[1]).replaceFirst("/")
        val urlparts = requestParts[1].replace("%3d", "=").split("/".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()

        if (urlparts.size < 2 || urlparts[0] != "") {
            Out.debug("$session The requested URL is invalid or not supported.")
            responseStatusCode = 404
            return
        }

        isRequestHeadOnly = requestParts[0].equals("HEAD", ignoreCase = true)

        if (urlparts[1] == "h") {
            // form: /h/$fileid/$additional/$filename

            if (urlparts.size < 4) {
                responseStatusCode = 400
                return
            }

            val fileid = urlparts[2]
            val requestedHVFile = HVFile.getHVFileFromFileid(fileid)
            val additional = Tools.parseAdditional(urlparts[3])
            var keystampRejected = true

            try {
                val keystampParts = additional["keystamp"].split("-".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()

                if (keystampParts.size == 2) {
                    val keystampTime = Integer.parseInt(keystampParts[0])

                    if (Math.abs(Settings.serverTime - keystampTime) < 900) {
                        if (keystampParts[1].equals(Tools.getSHA1String(keystampTime.toString() + "-" + fileid + "-" + Settings.clientKey + "-hotlinkthis")!!.substring(0, 10), ignoreCase = true)) {
                            keystampRejected = false
                        }
                    }
                }
            } catch (e: Exception) {
            }

            val fileindex = additional["fileindex"]
            val xres = additional["xres"]

            if (keystampRejected) {
                responseStatusCode = 403
            } else if (requestedHVFile == null || fileindex == null || xres == null || !Pattern.matches("^\\d+$", fileindex) || !Pattern.matches("^org|\\d+$", xres)) {
                Out.debug("$session Invalid or missing arguments.")
                responseStatusCode = 404
            } else if (requestedHVFile.localFileRef.exists()) {
                // hpc will update responseStatusCode
                hpc = HTTPResponseProcessorFile(requestedHVFile)
                session.httpServer.hentaiAtHomeClient.cacheHandler!!.markRecentlyAccessed(requestedHVFile)
            } else if (Settings.isStaticRange(fileid)) {
                // non-existent file. do an on-demand request of the file directly from the image servers
                val source = session.httpServer.hentaiAtHomeClient.serverHandler!!.getStaticRangeFetchURL(fileindex, xres, fileid)

                if (source == null) {
                    responseStatusCode = 404
                } else {
                    // hpc will update responseStatusCode
                    hpc = HTTPResponseProcessorProxy(session, fileid, source)
                }
            } else {
                // file does not exist, and is not in one of the client's static ranges
                responseStatusCode = 404
            }

            return
        } else if (urlparts[1] == "servercmd") {
            // form: /servercmd/$command/$additional/$time/$key

            if (!Settings.isValidRPCServer(session.socketInetAddress)) {
                Out.debug("$session Got a servercmd from an unauthorized IP address")
                responseStatusCode = 403
                return
            }

            if (urlparts.size < 6) {
                Out.debug("$session Got a malformed servercmd")
                responseStatusCode = 403
                return
            }

            val command = urlparts[2]
            val additional = urlparts[3]
            val commandTime = Integer.parseInt(urlparts[4])
            val key = urlparts[5]

            if (Math.abs(commandTime - Settings.serverTime) > Settings.MAX_KEY_TIME_DRIFT || Tools.getSHA1String("hentai@home-servercmd-" + command + "-" + additional + "-" + Settings.clientID + "-" + commandTime + "-" + Settings.clientKey) != key) {
                Out.debug("$session Got a servercmd with expired or incorrect key")
                responseStatusCode = 403
                return
            }

            responseStatusCode = 200
            isServercmd = true
            hpc = processRemoteAPICommand(command, additional)
            return
        } else if (urlparts[1] == "t") {
            // form: /t/$testsize/$testtime/$testkey

            if (urlparts.size < 5) {
                responseStatusCode = 400
                return
            }

            // send a randomly generated file of a given length for speed testing purposes
            val testsize = Integer.parseInt(urlparts[2])
            val testtime = Integer.parseInt(urlparts[3])
            val testkey = urlparts[4]

            if (Math.abs(testtime - Settings.serverTime) > Settings.MAX_KEY_TIME_DRIFT) {
                Out.debug("$session Got a speedtest request with expired key")
                responseStatusCode = 403
                return
            }

            if (Tools.getSHA1String("hentai@home-speedtest-" + testsize + "-" + testtime + "-" + Settings.clientID + "-" + Settings.clientKey) != testkey) {
                Out.debug("$session Got a speedtest request with invalid key")
                responseStatusCode = 403
                return
            }

            Out.debug("Sending threaded proxy test with testsize=$testsize testtime=$testtime testkey=$testkey")

            responseStatusCode = 200
            hpc = HTTPResponseProcessorSpeedtest(testsize)
            return
        } else if (urlparts.size == 2) {
            if (urlparts[1] == "favicon.ico") {
                // Redirect to the main website icon (which should already be in the browser cache).
                hpc = HTTPResponseProcessorText("")
                hpc!!.addHeaderField("Location", "https://e-hentai.org/favicon.ico")
                responseStatusCode = 301 // Moved Permanently
                return
            } else if (urlparts[1] == "robots.txt") {
                // Bots are not welcome.
                hpc = HTTPResponseProcessorText("User-agent: *\nDisallow: /", "text/plain")
                responseStatusCode = 200 // Found
                return
            }
        }

        Out.debug(session.toString() + " Invalid request type '" + urlparts[1])
        responseStatusCode = 404
        return
    }

    fun requestCompleted() {
        hpc!!.requestCompleted()
    }

    companion object {
        private val absoluteUriPattern = Pattern.compile("^http://[^/]+/", Pattern.CASE_INSENSITIVE)
    }
}
