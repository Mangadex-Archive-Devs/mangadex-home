/*

Copyright 2008-2016 E-Hentai.org
https://forums.e-hentai.org/
ehentai@gmail.com

This file is part of Hentai@Home GUI.

Hentai@Home GUI is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Hentai@Home GUI is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Hentai@Home GUI.  If not, see <http://www.gnu.org/licenses/>.

*/

package org.hath.gui

enum class StorageUnit private constructor(private val symbol: String, private val divider: Long) {
    BYTE("B", 1L),
    KILOBYTE("KB", 1L shl 10),
    MEGABYTE("MB", 1L shl 20),
    GIGABYTE("GB", 1L shl 30),
    TERABYTE("TB", 1L shl 40),
    PETABYTE("PB", 1L shl 50),
    EXABYTE("EB", 1L shl 60);

    fun format(number: Long): String {
        return nf.format(number.toDouble() / divider) + " " + symbol
    }

    companion object {

        val BASE = BYTE

        fun of(number: Long): StorageUnit {
            val n = if (number > 0) -number else number
            return if (n > -(1L shl 10)) {
                BYTE
            } else if (n > -(1L shl 20)) {
                KILOBYTE
            } else if (n > -(1L shl 30)) {
                MEGABYTE
            } else if (n > -(1L shl 40)) {
                GIGABYTE
            } else if (n > -(1L shl 50)) {
                TERABYTE
            } else if (n > -(1L shl 60)) {
                PETABYTE
            } else {  // n >= Long.MIN_VALUE
                EXABYTE
            }
        }

        private val nf = java.text.NumberFormat.getInstance()

        init {
            nf.isGroupingUsed = false
            nf.minimumFractionDigits = 2
            nf.maximumFractionDigits = 2
        }
    }
} 
