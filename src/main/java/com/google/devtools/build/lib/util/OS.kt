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
package com.google.devtools.build.lib.util

import java.util.EnumSet

/**
 * Detects the running operating system and returns a describing enum value.
 */
enum class OS(canonicalName: String, detectionName: String) {
    DARWIN("osx", "Mac OS X"),
    FREEBSD("freebsd", "FreeBSD"),
    OPENBSD("openbsd", "OpenBSD"),
    LINUX("linux", "Linux"),
    WINDOWS("windows", "Windows"),
    UNKNOWN("unknown", "");

    val canonicalName: String?
    private val detectionName: String?

    override fun toString(): String {
        return this.canonicalName!!
    }

    init {
        this.canonicalName = canonicalName
        this.detectionName = detectionName
    }

    companion object {
        private val POSIX_COMPATIBLE: EnumSet<OS?> = EnumSet.of<OS?>(
            com.google.devtools.build.lib.util.OS.DARWIN,
            com.google.devtools.build.lib.util.OS.FREEBSD,
            com.google.devtools.build.lib.util.OS.OPENBSD,
            com.google.devtools.build.lib.util.OS.LINUX
        )

        private val HOST_SYSTEM: OS = com.google.devtools.build.lib.util.OS.Companion.determineCurrentOs()

        @kotlin.jvm.JvmStatic
        val current: OS
            /**
             * The current operating system.
             */
            get() = com.google.devtools.build.lib.util.OS.Companion.HOST_SYSTEM

        @kotlin.jvm.JvmStatic
        val isPosixCompatible: Boolean
            get() = com.google.devtools.build.lib.util.OS.Companion.POSIX_COMPATIBLE.contains(com.google.devtools.build.lib.util.OS.Companion.getCurrent())

        val version: String?
            get() = java.lang.System.getProperty("os.version")

        // We inject an OS name through blaze.os, so we can have
        // some coverage for Windows specific code on Linux.
        private fun determineCurrentOs(): OS {
            var osName: String? = java.lang.System.getProperty("blaze.os")
            if (osName == null) {
                osName = java.lang.System.getProperty("os.name")
            }

            if (osName == null) {
                return com.google.devtools.build.lib.util.OS.UNKNOWN
            }

            for (os in com.google.devtools.build.lib.util.OS.entries) {
                // Windows have many names, all starting with "Windows".
                if (osName.startsWith(os.detectionName)) {
                    return os
                }
            }

            return com.google.devtools.build.lib.util.OS.UNKNOWN
        }
    }
}
