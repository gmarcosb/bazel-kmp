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

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.net.InetAddress

/**
 * Various utility methods for network related stuff.
 */
object NetUtil {
    @kotlin.concurrent.Volatile
    private var hostname: String? = null
    private var hostnameSupplier: java.util.function.Supplier<String?>? = java.util.function.Supplier?
    { obj: NetUtil? -> com.google.devtools.build.lib.util.NetUtil.computeShortHostName() }

    @kotlin.jvm.JvmStatic
    val cachedShortHostName: String?
        /**
         * Returns the *cached* short hostname (computed at most once per the lifetime of a server). Can
         * take seconds to complete when the cache is cold.
         */
        get() {
            if (com.google.devtools.build.lib.util.NetUtil.hostname == null) {
                synchronized(NetUtil::class.java) {
                    if (com.google.devtools.build.lib.util.NetUtil.hostname == null) {
                        com.google.devtools.build.lib.util.NetUtil.hostname =
                            com.google.common.base.MoreObjects.firstNonNull<String?>(
                                com.google.devtools.build.lib.util.NetUtil.hostnameSupplier.get(),
                                "unknown"
                            )
                        com.google.devtools.build.lib.util.NetUtil.hostnameSupplier = null
                    }
                }
            }
            return com.google.devtools.build.lib.util.NetUtil.hostname
        }

    /**
     * Sets a [Supplier] for the hostname to return from [.getCachedShortHostName].
     * 
     * 
     * If not called, the hostname comes from [.computeShortHostName]. To prevent multiple
     * different hostnames from being used, it is illegal to call this after [ ][.getCachedShortHostName] has been called.
     */
    @kotlin.jvm.Synchronized
    fun overrideHostnameSupplier(override: java.util.function.Supplier<String?>?) {
        com.google.common.base.Preconditions.checkState(
            com.google.devtools.build.lib.util.NetUtil.hostname == null,
            "Hostname already set to %s",
            com.google.devtools.build.lib.util.NetUtil.hostname
        )
        com.google.devtools.build.lib.util.NetUtil.hostnameSupplier =
            com.google.common.base.Preconditions.checkNotNull<java.util.function.Supplier<String?>?>(override)
    }

    /**
     * Returns the short hostname or `unknown` if the host name could not be determined.
     * Performs reverse DNS lookup and can take seconds to complete.
     */
    private fun computeShortHostName(): String? {
        try {
            return InetAddress.getLocalHost().getHostName()
        } catch (e: java.net.UnknownHostException) {
            return "unknown"
        }
    }
}
