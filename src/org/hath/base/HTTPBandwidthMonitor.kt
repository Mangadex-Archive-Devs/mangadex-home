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

import java.lang.Thread

class HTTPBandwidthMonitor {
    private val TIME_RESOLUTION = 50
    private val WINDOW_LENGTH = 5
    private val millisPerTick: Int
    private val bytesPerTick: Int
    private val tickBytes: IntArray
    private val tickSeconds: IntArray

    init {
        bytesPerTick = Math.ceil((Settings.throttleBytesPerSec / TIME_RESOLUTION).toDouble()).toInt()
        millisPerTick = 1000 / TIME_RESOLUTION
        tickBytes = IntArray(TIME_RESOLUTION)
        tickSeconds = IntArray(TIME_RESOLUTION)
    }

    @Synchronized
    fun waitForQuota(thread: Thread, bytecount: Int) {
        do {
            val now = System.currentTimeMillis()
            val epochSeconds = now / 1000
            val currentTick = ((now - epochSeconds * 1000) / millisPerTick).toInt()
            val currentSecond = epochSeconds.toInt()
            var bytesThisTick = 0
            var bytesLastWindow = 0
            var bytesLastSecond = 0
            var tickCounter = currentTick - TIME_RESOLUTION
            var tickIndex: Int
            var validSecond: Int

            while (++tickCounter <= currentTick) {
                tickIndex = if (tickCounter < 0) TIME_RESOLUTION + tickCounter else tickCounter
                validSecond = if (tickCounter < 0) currentSecond - 1 else currentSecond

                if (tickSeconds[tickIndex] == validSecond) {
                    if (tickCounter == currentTick) {
                        bytesThisTick += tickBytes[tickIndex]
                    } else {
                        if (tickCounter >= currentTick - WINDOW_LENGTH) {
                            bytesLastWindow += tickBytes[tickIndex]
                        }

                        // technically, 49/50ths of a second
                        bytesLastSecond += tickBytes[tickIndex]
                    }
                }
            }

            if (bytesThisTick > bytesPerTick * 1.1 || bytesLastWindow > bytesPerTick.toDouble() * WINDOW_LENGTH.toDouble() * 1.05 || bytesLastSecond > bytesPerTick * TIME_RESOLUTION) {
                //Out.debug("sleeping with currentTick=" + currentTick + " second=" + currentSecond + " bytesPerTick=" + bytesPerTick + " bytesThisTick=" + bytesThisTick + " bytesLastWindow=" + bytesLastWindow + " bytesLastSecond=" + bytesLastSecond);

                try {
                    Thread.sleep(10)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                continue
            }

            //Out.debug("granted with currentTick=" + currentTick + " second=" + currentSecond + " bytesPerTick=" + bytesPerTick + " bytesThisTick=" + bytesThisTick + " bytesLastWindow=" + bytesLastWindow + " bytesLastSecond=" + bytesLastSecond);

            if (tickSeconds[currentTick] != currentSecond) {
                tickSeconds[currentTick] = currentSecond
                tickBytes[currentTick] = 0
            }

            tickBytes[currentTick] += bytecount

            break
        } while (true)
    }
}
