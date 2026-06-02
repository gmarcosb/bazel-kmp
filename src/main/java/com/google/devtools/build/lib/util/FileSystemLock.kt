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
package com.google.devtools.build.lib.util

/**
 * Manages shared or exclusive access to the filesystem by concurrent processes through a lock file.
 */
class FileSystemLock private constructor(channel: FileChannel, lock: FileLock) : java.lang.AutoCloseable {
    private val channel: FileChannel
    private val lock: FileLock

    /**
     * The exception thrown when a lock cannot be acquired because it is already exclusively held by
     * another process.
     */
    class LockAlreadyHeldException internal constructor(
        mode: LockMode?,
        path: com.google.devtools.build.lib.vfs.Path?
    ) : IOException("failed to acquire %s filesystem lock on %s".formatted(mode, path))

    /** The mode of a lock.  */
    enum class LockMode {
        SHARED,
        EXCLUSIVE;

        override fun toString(): String {
            return when (this) {
                LockMode.SHARED -> "shared"
                LockMode.EXCLUSIVE -> "exclusive"
            }
        }
    }

    init {
        this.channel = channel
        this.lock = lock
    }

    @get:com.google.common.annotations.VisibleForTesting
    val isShared: Boolean
        get() = lock.isShared()

    @get:com.google.common.annotations.VisibleForTesting
    val isExclusive: Boolean
        get() = !this.isShared

    /** Releases access to the lock file.  */
    @Throws(IOException::class)
    override fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        @Throws(IOException::class)
        private fun prepareChannel(path: com.google.devtools.build.lib.vfs.Path): FileChannel {
            path.getParentDirectory().createDirectoryAndParents()
            return FileChannel.open( // Correctly handle non-ASCII paths by converting from the internal string encoding.
                java.nio.file.Path.of(StringEncoding.internalToPlatform(path.getPathString())),
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE
            )
        }

        /**
         * Tries to acquires a lock on the given path with the given mode. Throws an exception if the lock
         * is already held by another process.
         * 
         * 
         * This method must not be called concurrently from multiple threads with the same path.
         * 
         * @throws LockAlreadyHeldException if the lock is already exclusively held by another process
         * @throws IOException if another error occurred
         */
        @ThreadHostile
        @Throws(IOException::class)
        fun tryGet(path: com.google.devtools.build.lib.vfs.Path, mode: LockMode?): FileSystemLock {
            val channel: FileChannel = prepareChannel(path)
            val lock: FileLock = channel.tryLock(0, java.lang.Long.MAX_VALUE, mode == LockMode.SHARED)
            if (lock == null) {
                throw LockAlreadyHeldException(mode, path)
            }
            return FileSystemLock(channel, lock)
        }

        /**
         * Acquires a lock on the given path with the given mode. Blocks until the lock is acquired.
         * 
         * 
         * This method must not be called concurrently from multiple threads with the same path.
         */
        @ThreadHostile
        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun get(path: com.google.devtools.build.lib.vfs.Path, mode: LockMode?): FileSystemLock {
            val channel: FileChannel = prepareChannel(path)
            val lock: FileLock
            try {
                lock = channel.lock(0, java.lang.Long.MAX_VALUE, mode == LockMode.SHARED)
            } catch (e: FileLockInterruptionException) {
                java.lang.Thread.interrupted() // clear interrupt bit
                channel.close()
                throw java.lang.InterruptedException()
            }
            return FileSystemLock(channel, lock)
        }
    }
}
