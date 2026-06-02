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

/** A keyed store of locks.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
interface KeyedLocker<K> {
    /**
     * Used to yield access to the implicit locks granted by [.writeLock] or [.readLock].
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    interface AutoUnlocker : java.lang.AutoCloseable {
        /** Exception used to indicate illegal use of [AutoUnlocker.close].  */
        class IllegalUnlockException(msg: String?) : java.lang.RuntimeException(msg)

        /**
         * Closes the [AutoUnlocker] instance. If this instance was the last unclosed one
         * returned by [.writeLock] with argument `k` owned by the current
         * thread, then exclusive access to `k` is yielded. If this instance was the last unclosed
         * one returned by [.readLock] with argument `k`, then a thread can request
         * exclusive write access using [.writeLock] with argument `k`.
         * 
         * 
         * This method may only be called at most once per [AutoUnlocker] instance and must
         * be called by the same thread that acquired the [AutoUnlocker] via [.writeLock]
         * or [.readLock]. Otherwise, an [IllegalUnlockException] is thrown.
         */
        override fun close()
    }

    /**
     * Blocks the current thread until it has exclusive access to do things with `k` and
     * returns a [AutoUnlocker] instance for yielding the implicit lock.
     * 
     * 
     * Notably, this means that a thread is allowed to call `writeLock(k)` again before
     * calling [AutoUnlocker.close] for the first call to `writeLock(k)`. Each call to
     * `#writeLock` will return a different [AutoUnlocker] instance.
     * 
     * 
     * The intended usage is:
     * 
     * <pre>
     * `try (AutoUnlocker unlocker = locker.writeLock(k)) {   // Your code here. } `
    </pre> * 
     * 
     * 
     * Note that the usual caveats about mutexes apply here, e.g. the following may deadlock:
     * 
     * <pre>
     * `// Thread A try (AutoUnlocker unlocker = locker.writeLock(k1)) {   // This will deadlock if Thread B already acquired a writeLock for k2.   try (AutoUnlocker unlocker = locker.writeLock(k2)) {   } } // end Thread A // Thread B try (AutoUnlocker unlocker = locker.writeLock(k2)) {   // This will deadlock if Thread A already acquired a writeLock for k1.   try (AutoUnlocker unlocker = locker.writeLock(k1)) {   } } // end Thread B `
    </pre> * 
     */
    fun writeLock(key: K?): AutoUnlocker?
}
