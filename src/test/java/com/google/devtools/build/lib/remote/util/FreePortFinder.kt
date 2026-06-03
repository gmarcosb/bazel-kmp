// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.util

import java.io.IOException
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.SocketException
import java.util.Random

/**
 * Container for a cross-platform routine for finding a free port for a fake server to bind to
 * during testing.
 */
object FreePortFinder {
    /**
     * Finds an unused port and returns it, throwing [java.io.IOException] if no port can be
     * found.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun pickUnusedRandomPort(): Int {
        val rand: Random = Random()
        for (i in 0..127) {
            val port: Int = rand.nextInt(64551) + 1024
            if (isPortAvailable(port)) {
                return port
            }
            if (java.lang.Thread.interrupted()) {
                throw java.lang.InterruptedException("interrupted")
            }
        }

        throw IOException("Failed to find available port")
    }

    private fun isPortAvailable(port: Int): Boolean {
        if (port < 1024 || port > 65535) {
            return false
        }

        try {
            ServerSocket(port).use { ss ->
                ss.setReuseAddress(true)
            }
        } catch (e: IOException) {
            return false
        }

        try {
            DatagramSocket(port).use { ds ->
                ds.setReuseAddress(true)
            }
        } catch (e: SocketException) {
            return false
        }

        return true
    }
}
