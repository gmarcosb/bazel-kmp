// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.unix

import java.io.IOException
import java.util.HashMap

/**
 * Parse and return information from /proc/meminfo. In case of duplicate entries the first one is
 * used and other values are skipped.
 */
class ProcMeminfoParser @com.google.common.annotations.VisibleForTesting constructor(fileName: String) {
    private val memInfo: MutableMap<String?, Long>

    /**
     * Populates memory information by reading /proc/meminfo.
     * @throws IOException if reading the file failed.
     */
    constructor() : this(FILE)

    init {
        val lines: MutableList<String> =
            com.google.common.io.Files.readLines(java.io.File(fileName), java.nio.charset.Charset.defaultCharset())
        val newMemInfo: MutableMap<String?, Long?> = HashMap<String?, Long?>()
        for (line in lines) {
            val colon: Int = line.indexOf(':'.code)
            if (colon == -1) {
                continue
            }
            val keyword: String = line.substring(0, colon)
            val valString: String = line.substring(colon + 1)
            try {
                val `val`: Long =
                    java.lang.Long.parseLong(com.google.common.base.CharMatcher.inRange('0', '9').retainFrom(valString))
                newMemInfo.putIfAbsent(keyword, `val`)
            } catch (e: java.lang.NumberFormatException) {
                // Ignore: we'll fail later if somebody tries to capture this value.
            }
        }
        memInfo = com.google.common.collect.ImmutableMap.copyOf<String?, Long?>(newMemInfo)
    }

    /** Gets a named field in KB.  */
    @Throws(KeywordNotFoundException::class)
    fun getRamKb(keyword: String?): Long {
        val `val`: Long = memInfo.get(keyword)!!
        if (`val` == null) {
            throw KeywordNotFoundException(keyword)
        }
        return `val`
    }

    @get:Throws(KeywordNotFoundException::class)
    val totalKb: Long
        /** Return the total physical memory.  */
        get() = getRamKb("MemTotal")

    @get:Throws(KeywordNotFoundException::class)
    val freeRamKb: Long
        /**
         * Reads the amount of *available* memory as reported by the kernel. See https://goo.gl/ABn283 for
         * why this is better than trying to figure it out ourselves. This corresponds to the MemAvailable
         * line in /proc/meminfo.
         */
        get() {
            if (memInfo.containsKey("MemAvailable")) {
                return getRamKb("MemAvailable")
            }
            // We have no MemAvailable in /proc/meminfo; fall back to the previous estimation.
            return (getRamKb("MemTotal")
                    - getRamKb("Active") // Blaze doesn't want to use more than a third of inactive ram...
                    - (getRamKb("Inactive") * 0.3).toLong() - (getRamKb("Slab") * 0.8).toLong())
            // That said, this estimate will be more inaccurate as it diverges from kernel internals.
        }

    /** Exception thrown when /proc/meminfo does not have a requested key. Should be tolerated.  */
    class KeywordNotFoundException private constructor(keyword: String?) :
        IOException("Can't locate " + keyword + " in the /proc/meminfo")

    companion object {
        const val FILE: String = "/proc/meminfo"

        /**
         * Convert KB to MB.
         */
        fun kbToMb(kb: Long): Double {
            return (kb shr 10).toDouble()
        }
    }
}
