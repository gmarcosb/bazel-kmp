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
package com.google.devtools.build.lib.unix

import com.google.common.util.concurrent.Uninterruptibles
import com.google.devtools.build.lib.profiler.Profiler
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.nio.charset.StandardCharsets

/** Tests for the [com.google.devtools.build.lib.unix.UnixFileSystem] class.  */
class UnixFileSystemTest : SymlinkAwareFileSystemTest() {
    override fun getFreshFileSystem(digestHashFunction: DigestHashFunction?): FileSystem? {
        return UnixFileSystem(
            digestHashFunction,  /* hashAttributeName= */"", NativePosixFilesServiceImpl()
        )
    }

    public override fun destroyFileSystem(fileSystem: FileSystem?) {
        // Nothing.
    }

    // Most tests are just inherited from FileSystemTest.
    @Test
    @Throws(Exception::class)
    fun testPermissions() {
        val file: Path = absolutize("file")
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

    @Test
    @Throws(Exception::class)
    fun testPermissionsError() {
        val file: Path = absolutize("/")
        Assert.assertThrows<IOException?>(IOException::class.java, ThrowingRunnable { file.chmod(511) })
    }

    @Test
    @Throws(Exception::class)
    fun testCircularSymlinkFound() {
        val linkA: Path = absolutize("link-a")
        val linkB: Path = absolutize("link-b")
        linkA.createSymbolicLink(linkB)
        linkB.createSymbolicLink(linkA)
        assertThat(linkA.exists(Symlinks.FOLLOW)).isFalse()
        Assert.assertThrows<IOException?>(
            IOException::class.java,
            ThrowingRunnable { linkA.statIfFound(Symlinks.FOLLOW) })
    }

    @Test
    @Throws(Exception::class)
    fun testIsSpecialFile() {
        val regular: Path = absolutize("regular")
        val fifo: Path = absolutize("fifo")
        FileSystemUtils.createEmptyFile(regular)
        NativePosixFilesServiceImpl().mkfifo(fifo.toString(), 511)

        assertThat(regular.isFile()).isTrue()
        assertThat(regular.isSpecialFile()).isFalse()
        assertThat(regular.stat().isFile).isTrue()
        assertThat(regular.stat().isSpecialFile).isFalse()
        assertThat(fifo.isFile()).isTrue()
        assertThat(fifo.isSpecialFile()).isTrue()
        assertThat(fifo.stat().isFile).isTrue()
        assertThat(fifo.stat().isSpecialFile).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun testReaddirSpecialFile() {
        val dir: Path = absolutize("dir")
        val symlink: Path = dir.getChild("symlink")
        val fifo: Path = dir.getChild("fifo")
        dir.createDirectoryAndParents()
        symlink.createSymbolicLink(fifo.asFragment())
        NativePosixFilesServiceImpl().mkfifo(fifo.toString(), 511)

        assertThat(dir.getDirectoryEntries()).containsExactly(symlink, fifo)

        assertThat(dir.readdir(Symlinks.NOFOLLOW))
            .containsExactly(
                Dirent("symlink", Dirent.Type.SYMLINK), Dirent("fifo", Dirent.Type.UNKNOWN)
            )

        assertThat(dir.readdir(Symlinks.FOLLOW))
            .containsExactly(
                Dirent("symlink", Dirent.Type.UNKNOWN), Dirent("fifo", Dirent.Type.UNKNOWN)
            )
    }

    @Test
    @Throws(Exception::class)
    fun testReaddirPermissionError() {
        val dir: Path = absolutize("dir")
        dir.createDirectoryAndParents()
        dir.chmod(219) // unreadable

        Assert.assertThrows<T?>(FileAccessException::class.java, dir::getDirectoryEntries)
        Assert.assertThrows<T?>(FileAccessException::class.java, ThrowingRunnable { dir.readdir(Symlinks.NOFOLLOW) })
    }

    @Test
    @Throws(Exception::class)
    fun testGetxattr() {
        assumeXattrsSupported()

        val file: Path = absolutize("file")
        FileSystemUtils.writeContent(file, StandardCharsets.UTF_8, "hello world")

        Truth.assertThat(
            ProcessBuilder("xattr", "-w", "foo", "bar", file.getPathString()).start().waitFor()
        )
            .isEqualTo(0)

        assertThat(testFS.getxattr(file.asFragment(), "foo",  /* followSymlinks= */false))
            .isEqualTo("bar".toByteArray(StandardCharsets.UTF_8))
        assertThat(testFS.getxattr(file.asFragment(), "foo",  /* followSymlinks= */true))
            .isEqualTo("bar".toByteArray(StandardCharsets.UTF_8))
    }

    @Test
    @Throws(Exception::class)
    fun testGetxattrAttributeNotFound() {
        assumeXattrsSupported()

        val file: Path = absolutize("file")
        FileSystemUtils.createEmptyFile(file)

        assertThat(testFS.getxattr(file.asFragment(), "foo",  /* followSymlinks= */false)).isNull()
        assertThat(testFS.getxattr(file.asFragment(), "foo",  /* followSymlinks= */true)).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun testGetxattrFileNotFound() {
        assumeXattrsSupported()

        val file: Path = absolutize("file")

        Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            ThrowingRunnable { testFS.getxattr(file.asFragment(), "foo",  /* followSymlinks= */false) })
        Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            ThrowingRunnable { testFS.getxattr(file.asFragment(), "foo",  /* followSymlinks= */false) })
    }

    @Test
    @Throws(Throwable::class)
    fun testTransferToWorksWhenCallingThreadHasInterruptBitSet(
        @TestParameter profiling: Boolean
    ) {
        MaybeWithMockProfiler(profiling).use { m ->
            val src: Path = absolutize("src")
            val dst: Path = absolutize("dst")

            FileSystemUtils.writeContent(src, StandardCharsets.UTF_8, "hello world")

            val ready: CountDownLatch = CountDownLatch(1)
            val caughtException: AtomicReference<Throwable?> = AtomicReference<Throwable?>()
            val thread =
                Thread(
                    Runnable {
                        try {
                            src.getInputStream().use { `in` ->
                                dst.getOutputStream().use { out ->
                                    Uninterruptibles.awaitUninterruptibly(ready)
                                    Truth.assertThat(Thread.currentThread().isInterrupted()).isTrue()
                                    `in`.transferTo(out)
                                    Truth.assertThat(Thread.currentThread().isInterrupted()).isTrue()
                                    Truth.assertThat((`in` as FileInputStream).getChannel().isOpen()).isTrue()
                                    Truth.assertThat((out as FileOutputStream).getChannel().isOpen()).isTrue()
                                }
                            }
                        } catch (e: Throwable) {
                            caughtException.set(e)
                        }
                    })

            thread.start()
            thread.interrupt()
            ready.countDown()
            thread.join()

            if (caughtException.get() != null) {
                throw caughtException.get()
            }
            assertThat(dst.exists()).isTrue()
            assertThat(FileSystemUtils.readContent(dst, StandardCharsets.UTF_8)).isEqualTo("hello world")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testInputStreamIsFileInputStream(@TestParameter profiling: Boolean) {
        MaybeWithMockProfiler(profiling).use { m ->
            xFile.getInputStream().use { `in` ->
                Truth.assertThat(`in`).isInstanceOf(FileInputStream::class.java)
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun testOutputStreamIsFileOutputStream(@TestParameter profiling: Boolean) {
        MaybeWithMockProfiler(profiling).use { m ->
            xFile.getOutputStream().use { out ->
                Truth.assertThat(out).isInstanceOf(FileOutputStream::class.java)
            }
        }
    }

    private class MaybeWithMockProfiler(enabled: Boolean) : AutoCloseable {
        private val enabled: Boolean

        init {
            if (enabled) {
                val mock: TraceProfilerService = Mockito.mock<TraceProfilerService>(TraceProfilerService::class.java)
                Mockito.`when`<T?>(mock.isActive()).thenReturn(true)
                Mockito.`when`<T?>(mock.isProfiling(ArgumentMatchers.any<T?>(ProfilerTask::class.java)))
                    .thenReturn(true)
                Profiler.setTraceProfilerService(mock)
            }
            this.enabled = enabled
        }

        override fun close() {
            if (enabled) {
                Profiler.setTraceProfilerService(null)
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun testExceptionContainsFileAndLine() {
        val file: Path = absolutize("non-existent")

        val e: IOException? =
            Assert.assertThrows<IOException?>(IOException::class.java, ThrowingRunnable { file.stat() })

        Truth.assertThat(e).hasMessageThat().startsWith("[unix_jni.cc:")
        Truth.assertThat(e).hasMessageThat().endsWith("/non-existent (No such file or directory)")
    }

    companion object {
        /** Skips the test if the file system does not support extended attributes.  */
        @Throws(Exception::class)
        private fun assumeXattrsSupported() {
            // The standard file systems on macOS support extended attributes by default, so we can assume
            // that the test will work on that platform. For other systems, we currently don't have a
            // mechanism to validate this so the tests are skipped unconditionally.
            Assume.assumeTrue(OS.getCurrent() === OS.DARWIN)
        }
    }
}
