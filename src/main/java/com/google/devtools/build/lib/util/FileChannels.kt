// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.common.flogger.GoogleLogger
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

/** Utility methods for [FileChannel].  */
object FileChannels {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    private val alreadyLogged: AtomicBoolean = AtomicBoolean(false)

    private val SET_UNINTERRUPTIBLE: java.lang.invoke.MethodHandle?

    init {
        var handle: java.lang.invoke.MethodHandle? = null
        try {
            handle =
                java.lang.invoke.MethodHandles.lookup()
                    .unreflect(
                        java.lang.Class.forName("sun.nio.ch.FileChannelImpl")
                            .getDeclaredMethod("setUninterruptible")
                    )
        } catch (e: java.lang.ReflectiveOperationException) {
            // Ignore: maybe we're using a JDK that doesn't provide this API.
            com.google.devtools.build.lib.util.FileChannels.logger.atWarning().withCause(e).log(
                "Failed to obtain method handle for FileChannelImpl.setUninterruptible"
            )
        } finally {
            com.google.devtools.build.lib.util.FileChannels.SET_UNINTERRUPTIBLE = handle
        }
    }

    /**
     * Makes the given channel uninterruptible.
     * 
     * 
     * This uses an internal OpenJDK API and may silently fail if it's not available.
     */
    fun setUninterruptible(channel: FileChannel?) {
        if (com.google.devtools.build.lib.util.FileChannels.SET_UNINTERRUPTIBLE != null) {
            try {
                com.google.devtools.build.lib.util.FileChannels.SET_UNINTERRUPTIBLE.invoke(channel)
            } catch (e: Throwable) {
                // Ignore: maybe we're using a JDK that doesn't provide this API.
                if (com.google.devtools.build.lib.util.FileChannels.alreadyLogged.compareAndSet(false, true)) {
                    com.google.devtools.build.lib.util.FileChannels.logger.atWarning().withCause(e).log(
                        "Failed to call FileChannelImpl.setUninterruptible (only logged once)"
                    )
                }
            }
        }
    }
}
