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

import com.google.devtools.build.lib.testutil.ExternalFileSystemLock

/** Tests for [FileSystemLock].  */
@RunWith(JUnit4::class)
class FileSystemLockTest {
    private var lockPath: Path? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        val rootDir: Path = com.google.devtools.build.lib.testutil.TestUtils.createUniqueTmpDir(null)
        lockPath = rootDir.getRelative("subdir/lock")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tryGet_shared_whenNotLocked_succeeds() {
        FileSystemLock.tryGet(lockPath, LockMode.SHARED).use { lock ->
            assertThat(lock.isShared()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun get_shared_whenNotLocked_succeeds() {
        FileSystemLock.get(lockPath, LockMode.SHARED).use { lock ->
            assertThat(lock.isShared()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tryGet_shared_whenLockedForSharedUse_succeeds() {
        ExternalFileSystemLock.getShared(lockPath).use { externalLock ->
            FileSystemLock.tryGet(lockPath, LockMode.SHARED).use { lock ->
                assertThat(lock.isShared()).isTrue()
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun get_shared_whenLockedForSharedUse_succeeds() {
        ExternalFileSystemLock.getShared(lockPath).use { externalLock ->
            FileSystemLock.get(lockPath, LockMode.SHARED).use { lock ->
                assertThat(lock.isShared()).isTrue()
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tryGet_shared_whenLockedForExclusiveUse_fails() {
        ExternalFileSystemLock.getExclusive(lockPath).use { externalLock ->
            val e: LockAlreadyHeldException? =
                org.junit.Assert.assertThrows<T?>(
                    LockAlreadyHeldException::class.java,
                    org.junit.function.ThrowingRunnable { FileSystemLock.tryGet(lockPath, LockMode.SHARED) })
            assertThat(e).hasMessageThat().contains("failed to acquire shared filesystem lock")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun get_shared_whenLockedForExclusiveUse_blocks() {
        testBlocks(ExternalFileSystemLock.getExclusive(lockPath), LockMode.SHARED)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tryGet_exclusive_whenNotLocked_succeeds() {
        FileSystemLock.tryGet(lockPath, LockMode.EXCLUSIVE).use { lock ->
            assertThat(lock.isExclusive()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun get_exclusive_whenNotLocked_succeeds() {
        FileSystemLock.get(lockPath, LockMode.EXCLUSIVE).use { lock ->
            assertThat(lock.isExclusive()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tryGet_exclusive_whenLockedForSharedUse_fails() {
        ExternalFileSystemLock.getShared(lockPath).use { externalLock ->
            val e: LockAlreadyHeldException? =
                org.junit.Assert.assertThrows<T?>(
                    LockAlreadyHeldException::class.java,
                    org.junit.function.ThrowingRunnable { FileSystemLock.tryGet(lockPath, LockMode.EXCLUSIVE) })
            assertThat(e).hasMessageThat().contains("failed to acquire exclusive filesystem lock")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun get_exclusive_whenLockedForSharedUse_blocks() {
        testBlocks(ExternalFileSystemLock.getShared(lockPath), LockMode.EXCLUSIVE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tryGet_exclusive_whenLockedForExclusiveUse_fails() {
        ExternalFileSystemLock.getExclusive(lockPath).use { lock ->
            val e: LockAlreadyHeldException? =
                org.junit.Assert.assertThrows<T?>(
                    LockAlreadyHeldException::class.java,
                    org.junit.function.ThrowingRunnable { FileSystemLock.tryGet(lockPath, LockMode.EXCLUSIVE) })
            assertThat(e).hasMessageThat().contains("failed to acquire exclusive filesystem lock")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun get_exclusive_whenLockedForExclusiveUse_blocks() {
        testBlocks(ExternalFileSystemLock.getExclusive(lockPath), LockMode.EXCLUSIVE)
    }

    @Throws(java.lang.Exception::class)
    private fun testBlocks(externalLock: ExternalFileSystemLock, mode: LockMode?) {
        var future: java.util.concurrent.Future<Boolean?>
        try {
            val latch: CountDownLatch = CountDownLatch(1)
            val externalLockReleased: AtomicBoolean = AtomicBoolean()
            future =
                Executors.newSingleThreadExecutor()
                    .submit<Boolean?>(
                        java.util.concurrent.Callable {
                            latch.countDown()
                            FileSystemLock.get(lockPath, mode).use { lock ->
                                return@submit externalLockReleased.get()
                            }
                        })
            latch.await()
            java.lang.Thread.sleep(1)
            externalLockReleased.set(true)
        } finally {
            externalLock.close()
        }
        Truth.assertThat(future.get()).isTrue()
    }
}
