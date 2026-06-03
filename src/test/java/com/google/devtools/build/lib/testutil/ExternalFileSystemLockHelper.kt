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
package com.google.devtools.build.lib.testutil

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A helper program that attempts to obtain a shared or exclusive lock on a file and optionally
 * sleeps forever while holding it.
 * 
 * 
 * The arguments are as follows:
 * 
 * 
 *  1. The path of the file to lock.
 *  1. One of "shared" or "exclusive", indicating the type of lock to obtain.
 *  1. One of "sleep" or "exit", indicating whether to sleep forever or exit immediately once the
 * lock is held.
 * 
 * 
 * 
 * Does not block waiting for the lock, exiting immediately if it's already held.
 * 
 * 
 * Once the lock is held, prints '!' to stdout.
 * 
 * 
 * In a Java test, prefer [ExternalFileSystemLock] over using this directly.
 */
object ExternalFileSystemLockHelper {
    private val OPEN_OPTIONS: com.google.common.collect.ImmutableSet<OpenOption?> =
        com.google.common.collect.ImmutableSet.of<OpenOption?>(
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE
        )

    @Throws(IOException::class, java.lang.InterruptedException::class)
    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 3 || !(args[1] == "shared" || args[1] == "exclusive") || !(args[2] == "sleep" || args[2] == "exit")) {
            throw IOException("invalid arguments")
        }

        val path: Path = Path.of(args[0]).toAbsolutePath()
        val shared = args[1] == "shared"
        val sleep = args[2] == "sleep"

        java.nio.file.Files.createDirectories(path.getParent())
        FileChannel.open(path, OPEN_OPTIONS).use { channel ->
            channel.tryLock(0, Long.Companion.MAX_VALUE, shared).use { lock ->
                if (lock == null) {
                    throw IOException("lock already held")
                }
                // Signal parent that the lock is held.
                println("!")

                // If so requested, block until killed by parent.
                if (sleep) {
                    while (true) {
                        java.lang.Thread.sleep(1000)
                    }
                }
            }
        }
    }
}
