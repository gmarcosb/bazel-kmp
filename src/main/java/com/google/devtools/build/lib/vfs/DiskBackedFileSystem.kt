// Copyright 2019 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * This class extends [FileSystem] with default implementations providing access to files on
 * disk through standard library APIs.
 */
@ThreadSafe
abstract class DiskBackedFileSystem protected constructor(hashFunction: DigestHashFunction?) :
    com.google.devtools.build.lib.vfs.FileSystem(hashFunction) {
    // Force subclasses to override getIoFile and getNioPath, as the methods below require them.
    abstract override fun getIoFile(path: PathFragment?): java.io.File?

    abstract override fun getNioPath(path: PathFragment?): java.nio.file.Path?

    @Throws(IOException::class)
    override fun getInputStream(path: PathFragment): java.io.InputStream {
        val file: java.io.File = com.google.common.base.Preconditions.checkNotNull<java.io.File>(
            getIoFile(path),
            "getIoFile() must not be null"
        )

        val profileOpen = profiler.isActive() && profiler.isProfiling(ProfilerTask.VFS_OPEN)
        val profileRead = profiler.isActive() && profiler.isProfiling(ProfilerTask.VFS_READ)

        val startTime: Long = profiler.nanoTimeMaybe()
        try {
            return if (profileRead)
                ProfiledPatchedFileInputStream(file, path.getPathString())
            else
                PatchedFileInputStream(file)
        } catch (e: FileNotFoundException) {
            // FileInputStream throws FileNotFoundException if opening fails for any reason, including
            // permissions. Fix it up here.
            if (e.getMessage().endsWith(com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_PERMISSION_DENIED)) {
                throw FileAccessException(e.getMessage())
            }
            throw e
        } finally {
            if (profileOpen) {
                profiler.logSimpleTask(startTime, ProfilerTask.VFS_OPEN, path.getPathString())
            }
        }
    }

    @Throws(IOException::class)
    override fun getOutputStream(path: PathFragment, append: Boolean, internal: Boolean): java.io.OutputStream {
        val file: java.io.File = com.google.common.base.Preconditions.checkNotNull<java.io.File>(
            getIoFile(path),
            "getIoFile() must not be null"
        )

        val profileOpen =
            !internal && profiler.isActive() && profiler.isProfiling(ProfilerTask.VFS_OPEN)
        val profileWrite =
            !internal && profiler.isActive() && profiler.isProfiling(ProfilerTask.VFS_WRITE)

        val startTime: Long = profiler.nanoTimeMaybe()
        try {
            return if (profileWrite)
                ProfiledPatchedFileOutputStream(file, append, path.getPathString())
            else
                PatchedFileOutputStream(file, append)
        } catch (e: FileNotFoundException) {
            // FileOutputStream throws FileNotFoundException if opening fails for any reason, including
            // permissions. Fix it up here.
            if (e.getMessage().endsWith(com.google.devtools.build.lib.vfs.FileSystem.Companion.ERR_PERMISSION_DENIED)) {
                throw FileAccessException(e.getMessage())
            }
            throw e
        } finally {
            if (profileOpen) {
                profiler.logSimpleTask(startTime, ProfilerTask.VFS_OPEN, path.getPathString())
            }
        }
    }

    @Throws(IOException::class)
    override fun createReadWriteByteChannel(path: PathFragment): SeekableByteChannel? {
        val nioPath: java.nio.file.Path = com.google.common.base.Preconditions.checkNotNull<java.nio.file.Path>(
            getNioPath(path),
            "getNioPath() must not be null"
        )

        val profileOpen = profiler.isActive() && profiler.isProfiling(ProfilerTask.VFS_OPEN)

        val startTime: Long = Profiler.instance().nanoTimeMaybe()
        try {
            // TODO: add profiling for read/write operations.
            return java.nio.file.Files.newByteChannel(nioPath, READ_WRITE_BYTE_CHANNEL_OPEN_OPTIONS)
        } finally {
            if (profileOpen) {
                profiler.logSimpleTask(startTime, ProfilerTask.VFS_OPEN, path.toString())
            }
        }
    }

    // As of OpenJDK 25, FileInputStream.transferTo(FileOutputStream) closes the underlying
    // FileChannels and throws a ClosedByInterruptException (a subclass of IOException) when called
    // with the interrupt bit set. This is unfortunate because it's easy to forget to account for this
    // case and interrupt code paths aren't comprehensively tested, making it highly likely that such
    // an oversight will result in spurious build failures in production (b/463596620 is an example).
    //
    // To work around this, we patch FileInputStream/FileOutputStream's getChannel() method so that
    // it calls the FileChannelImpl.setUninterruptible() internal OpenJDK API to suppress the
    // close-on-interrupt behavior. Interestingly, Files.newInputStream() and Files.newOutputStream()
    // already do this, suggesting that FileInputStream and FileOutputStream not doing so might be an
    // implementation oversight rather than a deliberate design decision.
    //
    // This will not work on non-OpenJDK-based JDKs that don't provide this API, but we consider it
    // acceptable for the time being.
    //
    // The following alternatives were considered and rejected:
    //
    // - Clear the interrupt bit before calling transferTo() and restore it after: does not work,
    //   as there's still a time window during which the interrupt bit may be set.
    //
    // - Implement a retry loop around transferTo(): does not work, because a failed first attempt
    //   closes the FileChannel, thereby ensuring that subsequent attempts will also fail.
    //
    // - Implement a retry loop around transferTo() while wrapping the FileChannels in a proxy that
    //   replaces close() with a no-op: while this does appear to work and doesn't rely on internal
    //   APIs, it's significantly more complex and still relies on implementation details, arguably
    //   in a more dangerous way. For example, if the FileChannel implementation is such that
    //   ClosedByInterruptException may be thrown after some bytes have already been transferred,
    //   the retry loop might accidentally transfer them twice.
    //
    // - Use Files.newInputStream() and Files.newOutputStream(): not an option, because they return a
    //   ChannelInputStream or ChannelOutputStream, respectively, and some callers expect a
    //   FileInputStream or FileOutputStream (in order to be able to call fsync(), for example).
    //
    // - Provide an interruptibleTransferTo() helper method that converts ClosedByInterruptException
    //   into InterruptedException and adjust callsites accordingly: undesirable, because it remains
    //   possible to erroneously call FileInputStream.transferTo(), which is unlikely to be detected
    //   in testing.
    /**
     * A [FileInputStream] that patches the bug described above.
     * 
     * 
     * Implementation note: this class extends [FileInputStream] instead of wrapping around
     * it so that `instanceof FileInputStream` checks still work.
     */
    private open class PatchedFileInputStream(file: java.io.File) : FileInputStream(file) {
        @kotlin.concurrent.Volatile
        private var patched = false

        val channel: FileChannel?
            get() {
                val channel: FileChannel? = super.getChannel()
                // Benign data race: at worst we call setUninterruptible more than once.
                if (!patched) {
                    FileChannels.setUninterruptible(channel)
                    patched = true
                }
                return channel
            }
    }

    /**
     * A [FileInputStream] that patches the bug described above and adds profile traces around
     * read operations.
     * 
     * 
     * Implementation note: this class extends [FileInputStream] instead of wrapping around
     * it so that `instanceof FileInputStream` checks still work.
     */
    private class ProfiledPatchedFileInputStream(file: java.io.File, private val name: String?) :
        PatchedFileInputStream(file) {
        @Throws(IOException::class)
        override fun read(): Int {
            val startTime: Long = profiler.nanoTimeMaybe()
            try {
                return super.read()
            } finally {
                profiler.logSimpleTask(startTime, ProfilerTask.VFS_READ, name)
            }
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray): Int {
            return read(b, 0, b.size)
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray?, off: Int, len: Int): Int {
            val startTime: Long = profiler.nanoTimeMaybe()
            try {
                return super.read(b, off, len)
            } finally {
                profiler.logSimpleTask(startTime, ProfilerTask.VFS_READ, name)
            }
        }
    }

    /**
     * A [FileOutputStream] that patches the bug described above.
     * 
     * 
     * Implementation note: this class extends [FileOutputStream] instead of wrapping around
     * it so that `instanceof FileOutputStream` checks still work.
     */
    private open class PatchedFileOutputStream(file: java.io.File, append: Boolean) : FileOutputStream(file, append) {
        @kotlin.concurrent.Volatile
        private var patched = false

        val channel: FileChannel?
            get() {
                val channel: FileChannel? = super.getChannel()
                // Benign data race: at worst we call setUninterruptible more than once.
                if (!patched) {
                    FileChannels.setUninterruptible(channel)
                    patched = true
                }
                return channel
            }
    }

    /**
     * A [FileOutputStream] that patches the bug described above and adds profile traces around
     * write operations.
     * 
     * 
     * Implementation note: this class extends [FileOutputStream] instead of wrapping around
     * it so that `instanceof FileOutputStream` checks still work.
     */
    private class ProfiledPatchedFileOutputStream(file: java.io.File, append: Boolean, private val name: String?) :
        PatchedFileOutputStream(file, append) {
        @Throws(IOException::class)
        override fun write(b: Int) {
            val startTime: Long = profiler.nanoTimeMaybe()
            try {
                super.write(b)
            } finally {
                profiler.logSimpleTask(startTime, ProfilerTask.VFS_WRITE, name)
            }
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray) {
            write(b, 0, b.size)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray?, off: Int, len: Int) {
            val startTime: Long = profiler.nanoTimeMaybe()
            try {
                super.write(b, off, len)
            } finally {
                profiler.logSimpleTask(startTime, ProfilerTask.VFS_WRITE, name)
            }
        }
    }

    companion object {
        private val profiler: Profiler = Profiler.instance()

        private val READ_WRITE_BYTE_CHANNEL_OPEN_OPTIONS: com.google.common.collect.ImmutableSet<StandardOpenOption?> =
            com.google.common.collect.ImmutableSet.of<StandardOpenOption?>(
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
    }
}
