// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.concurrent

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReadWriteLock

/**
 * An implementation of [KeyedLocker] backed by a [Striped].
 */
class StripedKeyedLocker<K>(stripes: Int) : com.google.devtools.build.lib.concurrent.KeyedLocker<K?> {
    private val locks: com.google.common.util.concurrent.Striped<ReadWriteLock?>

    init {
        locks = com.google.common.util.concurrent.Striped.readWriteLock(stripes)
    }

    override fun writeLock(key: K?): com.google.devtools.build.lib.concurrent.KeyedLocker.AutoUnlocker {
        return com.google.devtools.build.lib.concurrent.StripedKeyedLocker.Companion.lockAndMakeAutoUnlocker(
            locks.get(
                key
            ).writeLock(), key
        )
    }

    companion object {
        private fun lockAndMakeAutoUnlocker(
            lock: java.util.concurrent.locks.Lock, keyForDebugging: Any?
        ): com.google.devtools.build.lib.concurrent.KeyedLocker.AutoUnlocker {
            lock.lock()
            return object : com.google.devtools.build.lib.concurrent.KeyedLocker.AutoUnlocker {
                private val closeCalled: AtomicBoolean = AtomicBoolean(false)

                override fun close() {
                    if (closeCalled.getAndSet(true)) {
                        val msg: String? =
                            java.lang.String.format(
                                "For key %s, 'close' can be called at most once per AutoUnlocker instance",
                                keyForDebugging
                            )
                        throw com.google.devtools.build.lib.concurrent.KeyedLocker.AutoUnlocker.IllegalUnlockException(
                            msg
                        )
                    }
                    lock.unlock()
                }
            }
        }
    }
}
