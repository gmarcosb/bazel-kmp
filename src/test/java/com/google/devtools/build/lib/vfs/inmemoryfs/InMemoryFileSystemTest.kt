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
package com.google.devtools.build.lib.vfs.inmemoryfs

import com.google.devtools.build.lib.vfs.DigestHashFunction

/**
 * Tests for [InMemoryFileSystem].
 * 
 * 
 * Note that most tests are inherited from [SymlinkAwareFileSystemTest] and ancestors.
 */
class InMemoryFileSystemTest : SymlinkAwareFileSystemTest() {
    public override fun getFreshFileSystem(digestHashFunction: DigestHashFunction): FileSystem {
        return InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), digestHashFunction)
    }

    public override fun destroyFileSystem(fileSystem: FileSystem?) {}

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPermissions() {
        val file: Path = testFS.getPath("/file")
        FileSystemUtils.createEmptyFile(file)
        for (bits in 0..511) {
            val msg: String? = "for permissions 0%s".formatted(bits.toString(8))
            file.chmod(bits)
            Truth.assertWithMessage(msg).that(file.stat().getPermissions()).isEqualTo(bits)
            Truth.assertWithMessage(msg).that(file.isReadable()).isEqualTo((bits and 256) != 0)
            Truth.assertWithMessage(msg).that(file.isWritable()).isEqualTo((bits and 128) != 0)
            Truth.assertWithMessage(msg).that(file.isExecutable()).isEqualTo((bits and 64) != 0)
        }
    }

    /**
     * Tests concurrent creation of a substantial tree hierarchy including files, directories,
     * symlinks, file contents, and permissions.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConcurrentTreeConstruction() {
        val n = 10000
        val baseSelector: AtomicInteger = AtomicInteger()

        // 1) Define the intended path structure.
        val pathCreator: TestRunnable =
            TestRunnable {
                val base: Path = testFS.getPath("/base" + baseSelector.getAndIncrement())
                base.createDirectory()
                for (i in 0..<n) {
                    val subdir1: Path = base.getRelative("subdir1_" + i)
                    subdir1.createDirectory()
                    val subdir2: Path = base.getRelative("subdir2_" + i)
                    subdir2.createDirectory()

                    val file: Path = base.getRelative("somefile" + i)
                    writeToFile(file, TEST_FILE_DATA)

                    subdir1.setReadable(true)
                    subdir2.setReadable(false)
                    file.setReadable(true)

                    subdir1.setWritable(false)
                    subdir2.setWritable(true)
                    file.setWritable(false)

                    subdir1.setExecutable(false)
                    subdir2.setExecutable(true)
                    file.setExecutable(false)

                    subdir1.setLastModifiedTime(100)
                    subdir2.setLastModifiedTime(200)
                    file.setLastModifiedTime(300)

                    val symlink: Path = base.getRelative("symlink" + i)
                    symlink.createSymbolicLink(file)
                }
            }

        // 2) Construct the tree.
        var threads: MutableCollection<TestThread> =
            com.google.common.collect.Lists.newArrayListWithCapacity<TestThread?>(NUM_THREADS_FOR_CONCURRENCY_TESTS)
        for (i in 0..<NUM_THREADS_FOR_CONCURRENCY_TESTS) {
            val thread: TestThread = TestThread(pathCreator)
            thread.start()
            threads.add(thread)
        }
        for (thread in threads) {
            thread.joinAndAssertState(0)
        }

        // 3) Define the validation logic.
        val pathValidator: TestRunnable =
            TestRunnable {
                val base: Path = testFS.getPath("/base" + baseSelector.getAndIncrement())
                assertThat(base.exists()).isTrue()
                assertThat(base.getRelative("notreal").exists()).isFalse()
                for (i in 0..<n) {
                    val subdir1: Path = base.getRelative("subdir1_" + i)
                    assertThat(subdir1.exists()).isTrue()
                    assertThat(subdir1.isDirectory()).isTrue()
                    assertThat(subdir1.isReadable()).isTrue()
                    assertThat(subdir1.isWritable()).isFalse()
                    assertThat(subdir1.isExecutable()).isFalse()
                    assertThat(subdir1.getLastModifiedTime()).isEqualTo(100)

                    val subdir2: Path = base.getRelative("subdir2_" + i)
                    assertThat(subdir2.exists()).isTrue()
                    assertThat(subdir2.isDirectory()).isTrue()
                    assertThat(subdir2.isReadable()).isFalse()
                    assertThat(subdir2.isWritable()).isTrue()
                    assertThat(subdir2.isExecutable()).isTrue()
                    assertThat(subdir2.getLastModifiedTime()).isEqualTo(200)

                    val file: Path = base.getRelative("somefile" + i)
                    assertThat(file.exists()).isTrue()
                    assertThat(file.isFile()).isTrue()
                    assertThat(file.isReadable()).isTrue()
                    assertThat(file.isWritable()).isFalse()
                    assertThat(file.isExecutable()).isFalse()
                    assertThat(file.getLastModifiedTime()).isEqualTo(300)
                    BufferedReader(
                        java.io.InputStreamReader(file.getInputStream(), java.nio.charset.Charset.defaultCharset())
                    ).use { reader ->
                        Truth.assertThat(reader.readLine()).isEqualTo(TEST_FILE_DATA)
                        Truth.assertThat(reader.readLine()).isNull()
                    }
                    val symlink: Path = base.getRelative("symlink" + i)
                    assertThat(symlink.exists()).isTrue()
                    assertThat(symlink.isSymbolicLink()).isTrue()
                    assertThat(symlink.readSymbolicLink()).isEqualTo(file.asFragment())
                }
            }

        // 4) Validate the results.
        baseSelector.set(0)
        threads = com.google.common.collect.Lists.newArrayListWithCapacity<TestThread?>(
            NUM_THREADS_FOR_CONCURRENCY_TESTS
        )
        for (i in 0..<NUM_THREADS_FOR_CONCURRENCY_TESTS) {
            val thread: TestThread = TestThread(pathValidator)
            thread.start()
            threads.add(thread)
        }
        for (thread in threads) {
            thread.joinAndAssertState(0)
        }
    }

    /**
     * Tests concurrent creation of many files, all within the same directory.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConcurrentDirectoryConstruction() {
        val n = 10000
        val baseSelector: AtomicInteger = AtomicInteger()

        // 1) Define the intended path structure.
        val pathCreator: TestRunnable =
            TestRunnable {
                val threadId: Int = baseSelector.getAndIncrement()
                val base: Path = testFS.getPath("/common_dir")
                base.createDirectory()
                for (i in 0..<n) {
                    val file: Path = base.getRelative("somefile_" + threadId + "_" + i)
                    writeToFile(file, TEST_FILE_DATA)
                    file.setReadable(i % 2 == 0)
                    file.setWritable(i % 3 == 0)
                    file.setExecutable(i % 4 == 0)
                    file.setLastModifiedTime(i)
                    val symlink: Path = base.getRelative("symlink_" + threadId + "_" + i)
                    symlink.createSymbolicLink(file)
                }
            }

        // 2) Create the files.
        var threads: MutableCollection<TestThread> =
            com.google.common.collect.Lists.newArrayListWithCapacity<TestThread?>(NUM_THREADS_FOR_CONCURRENCY_TESTS)
        for (i in 0..<NUM_THREADS_FOR_CONCURRENCY_TESTS) {
            val thread: TestThread = TestThread(pathCreator)
            thread.start()
            threads.add(thread)
        }
        for (thread in threads) {
            thread.joinAndAssertState(0)
        }

        // 3) Define the validation logic.
        val pathValidator: TestRunnable =
            TestRunnable {
                val threadId: Int = baseSelector.getAndIncrement()
                val base: Path = testFS.getPath("/common_dir")
                assertThat(base.exists()).isTrue()
                for (i in 0..<n) {
                    val file: Path = base.getRelative("somefile_" + threadId + "_" + i)
                    assertThat(file.exists()).isTrue()
                    assertThat(file.isFile()).isTrue()
                    assertThat(file.isReadable()).isEqualTo(i % 2 == 0)
                    assertThat(file.isWritable()).isEqualTo(i % 3 == 0)
                    assertThat(file.isExecutable()).isEqualTo(i % 4 == 0)
                    assertThat(file.getLastModifiedTime()).isEqualTo(i)
                    if (file.isReadable()) {
                        BufferedReader(
                            java.io.InputStreamReader(file.getInputStream(), java.nio.charset.Charset.defaultCharset())
                        ).use { reader ->
                            Truth.assertThat(reader.readLine()).isEqualTo(TEST_FILE_DATA)
                            Truth.assertThat(reader.readLine()).isNull()
                        }
                    }

                    val symlink: Path = base.getRelative("symlink_" + threadId + "_" + i)
                    assertThat(symlink.exists()).isTrue()
                    assertThat(symlink.isSymbolicLink()).isTrue()
                    assertThat(symlink.readSymbolicLink()).isEqualTo(file.asFragment())
                }
            }

        // 4) Validate the results.
        baseSelector.set(0)
        threads = com.google.common.collect.Lists.newArrayListWithCapacity<TestThread?>(
            NUM_THREADS_FOR_CONCURRENCY_TESTS
        )
        for (i in 0..<NUM_THREADS_FOR_CONCURRENCY_TESTS) {
            val thread: TestThread = TestThread(pathValidator)
            thread.start()
            threads.add(thread)
        }
        for (thread in threads) {
            thread.joinAndAssertState(0)
        }
    }

    /**
     * Tests concurrent file deletion.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConcurrentDeletion() {
        val n = 10000
        val baseSelector: AtomicInteger = AtomicInteger()

        val base: Path = testFS.getPath("/base")
        base.createDirectory()

        // 1) Create a bunch of files.
        for (i in 0..<n) {
            writeToFile(base.getRelative("file" + i), TEST_FILE_DATA)
        }

        // 2) Define our deletion strategy.
        val fileDeleter: TestRunnable =
            TestRunnable {
                for (i in 0..<n / NUM_THREADS_FOR_CONCURRENCY_TESTS) {
                    val whichFile: Int = baseSelector.getAndIncrement()
                    val file: Path = base.getRelative("file" + whichFile)
                    if (whichFile % 25 != 0) {
                        assertThat(file.delete()).isTrue()
                    } else {
                        // Throw another concurrent access point into the mix.
                        file.setExecutable(whichFile % 2 == 0)
                    }
                    assertThat(base.getRelative("doesnotexist" + whichFile).delete()).isFalse()
                }
            }

        // 3) Delete some files.
        val threads: MutableCollection<TestThread> =
            com.google.common.collect.Lists.newArrayListWithCapacity<TestThread?>(NUM_THREADS_FOR_CONCURRENCY_TESTS)
        for (i in 0..<NUM_THREADS_FOR_CONCURRENCY_TESTS) {
            val thread: TestThread = TestThread(fileDeleter)
            thread.start()
            threads.add(thread)
        }
        for (thread in threads) {
            thread.joinAndAssertState(0)
        }

        // 4) Check the results.
        for (i in 0..<n) {
            val file: Path = base.getRelative("file" + i)
            if (i % 25 != 0) {
                assertThat(file.exists()).isFalse()
            } else {
                assertThat(file.exists()).isTrue()
                assertThat(file.isExecutable()).isEqualTo(i % 2 == 0)
            }
        }
    }

    /**
     * Tests concurrent file renaming.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConcurrentRenaming() {
        val n = 10000
        val baseSelector: AtomicInteger = AtomicInteger()

        val base: Path = testFS.getPath("/base")
        base.createDirectory()

        // 1) Create a bunch of files.
        for (i in 0..<n) {
            writeToFile(base.getRelative("file" + i), TEST_FILE_DATA)
        }

        // 2) Define our renaming strategy.
        val fileDeleter: TestRunnable =
            TestRunnable {
                for (i in 0..<n / NUM_THREADS_FOR_CONCURRENCY_TESTS) {
                    val whichFile: Int = baseSelector.getAndIncrement()
                    val file: Path = base.getRelative("file" + whichFile)
                    if (whichFile % 25 != 0) {
                        val newName: Path? = base.getRelative("newname" + whichFile)
                        file.renameTo(newName)
                    } else {
                        // Throw another concurrent access point into the mix.
                        file.setExecutable(whichFile % 2 == 0)
                    }
                    assertThat(base.getRelative("doesnotexist" + whichFile).delete()).isFalse()
                }
            }

        // 3) Rename some files.
        val threads: MutableCollection<TestThread> =
            com.google.common.collect.Lists.newArrayListWithCapacity<TestThread?>(NUM_THREADS_FOR_CONCURRENCY_TESTS)
        for (i in 0..<NUM_THREADS_FOR_CONCURRENCY_TESTS) {
            val thread: TestThread = TestThread(fileDeleter)
            thread.start()
            threads.add(thread)
        }
        for (thread in threads) {
            thread.joinAndAssertState(0)
        }

        // 4) Check the results.
        for (i in 0..<n) {
            val file: Path = base.getRelative("file" + i)
            if (i % 25 != 0) {
                assertThat(file.exists()).isFalse()
                assertThat(base.getRelative("newname" + i).exists()).isTrue()
            } else {
                assertThat(file.exists()).isTrue()
                assertThat(file.isExecutable()).isEqualTo(i % 2 == 0)
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEloop() {
        // The test assumes that aName and bName is not a prefix of the workingDir.
        val aName = "/" + UUID.randomUUID()
        val bName = "/" + UUID.randomUUID()

        val a: Path = testFS.getPath(aName)
        val b: Path = testFS.getPath(bName)
        a.createSymbolicLink(PathFragment.create(bName))
        b.createSymbolicLink(PathFragment.create(aName))
        val e: FileSymlinkLoopException? =
            org.junit.Assert.assertThrows<T?>(FileSymlinkLoopException::class.java, a::stat)
        assertThat(e).hasMessageThat().isEqualTo(aName + " (Too many levels of symbolic links)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEloopSelf() {
        // The test assumes that aName is not a prefix of the workingDir.
        val aName = "/" + UUID.randomUUID()

        val a: Path = testFS.getPath(aName)
        a.createSymbolicLink(PathFragment.create(aName))
        val e: FileSymlinkLoopException? =
            org.junit.Assert.assertThrows<T?>(FileSymlinkLoopException::class.java, a::stat)
        assertThat(e).hasMessageThat().isEqualTo(aName + " (Too many levels of symbolic links)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getxattr_symlink_returnsNull() {
        val dir: Path = testFS.getPath("/any/dir")
        dir.createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(dir.getRelative("file.txt"), "contents")
        val symlink: Path = dir.getRelative("link")
        symlink.createSymbolicLink(PathFragment.create("file.txt"))

        assertThat(symlink.getxattr("some.xattr")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLargeFile() {
        val file: Path? = testFS.getPath("/file")

        val largeStr: String = "abcdefghijklmnopqrstuvwxyz".repeat(1000000)
        FileSystemUtils.writeContent(file, java.nio.charset.StandardCharsets.UTF_8, largeStr)
        assertThat(FileSystemUtils.readContent(file, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(largeStr)
    }

    companion object {
        private const val NUM_THREADS_FOR_CONCURRENCY_TESTS = 10
        private const val TEST_FILE_DATA = "data"

        /**
         * Writes the given data to the given file.
         */
        @Throws(IOException::class)
        private fun writeToFile(path: Path, data: String) {
            path.getOutputStream().use { out ->
                out.write(data.toByteArray(java.nio.charset.Charset.defaultCharset()))
            }
        }
    }
}
