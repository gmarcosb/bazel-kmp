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
package com.google.devtools.build.lib.util.io

/**
 * An implementation of [OutErr] that captures all out/err output into
 * a file for stdout and a file for stderr. The files are only created if any
 * output is made.
 * The OutErr assumes that the directory that will contain the output file
 * must exist.
 * 
 * You should not use this object from multiple different threads.
 */
// Note that it should be safe to treat the Output and Error streams within a FileOutErr each as
// individually ThreadCompatible.
@ThreadCompatible
open class FileOutErr : OutErr {
    private val childCount: AtomicInteger = AtomicInteger()

    /**
     * Create a new FileOutErr that will write its input,
     * if any, to the files specified by stdout/stderr.
     * 
     * No other process may write to the files,
     * 
     * @param stdout The file for the stdout of this outErr
     * @param stderr The file for the stderr of this outErr
     */
    constructor(stdout: com.google.devtools.build.lib.vfs.Path, stderr: com.google.devtools.build.lib.vfs.Path) : this(
        FileRecordingOutputStream(stdout),
        FileRecordingOutputStream(stderr)
    )

    /**
     * Creates a new FileOutErr that writes its input to the file specified by output. Both
     * stdout/stderr will be copied into the single file.
     * 
     * @param output The file for the both stdout and stderr of this outErr.
     */
    constructor(output: com.google.devtools.build.lib.vfs.Path) : this(FileRecordingOutputStream(output))

    protected constructor(
        out: AbstractFileRecordingOutputStream?,
        err: AbstractFileRecordingOutputStream?
    ) : super(out, err)

    /**
     * Creates a new FileOutErr that discards its input. Useful
     * for testing purposes.
     */
    @com.google.common.annotations.VisibleForTesting
    constructor() : this(NullFileRecordingOutputStream())

    private constructor(stream: java.io.OutputStream?) : super(stream, stream)

    /**
     * Returns true if any output was recorded.
     */
    fun hasRecordedOutput(): Boolean {
        return this.fileOutputStream!!.hasRecordedOutput() || this.fileErrorStream!!.hasRecordedOutput()
    }

    /**
     * Returns true if output was recorded on stdout.
     */
    fun hasRecordedStdout(): Boolean {
        return this.fileOutputStream!!.hasRecordedOutput()
    }

    /**
     * Returns true if output was recorded on stderr.
     */
    fun hasRecordedStderr(): Boolean {
        return this.fileErrorStream!!.hasRecordedOutput()
    }

    val outputPath: com.google.devtools.build.lib.vfs.Path?
        /**
         * Returns the path this OutErr uses to buffer stdout, marking the file as "accessed" because the
         * caller has unrestricted access to the underlying file.
         * 
         * 
         * The user must ensure that no other process is writing to the files at time of creation.
         * 
         * @return the path object with the contents of stdout
         */
        get() = this.fileOutputStream!!.file

    val outputPathFragment: PathFragment?
        /**
         * Returns the path this OutErr uses to buffer stdout without marking the file as "accessed".
         * 
         * 
         * The user must ensure that no other process is writing to the files at time of creation.
         * 
         * @return the path object with the contents of stdout
         */
        get() = this.fileOutputStream!!.fileUnsafe.asFragment()

    /** Returns the length of the stdout contents.  */
    @Throws(IOException::class)
    fun outSize(): Long {
        return this.fileOutputStream!!.recordedOutputSize
    }

    val errorPath: com.google.devtools.build.lib.vfs.Path?
        /**
         * Returns the path this OutErr uses to buffer stderr, marking the file as "accessed" because the
         * caller has unrestricted access to the underlying file.
         * 
         * @return the path object with the contents of stderr
         */
        get() = this.fileErrorStream!!.file

    val errorPathFragment: PathFragment?
        /**
         * Returns the path this OutErr uses to buffer stderr without marking the file as "accessed".
         * 
         * @return the path object with the contents of stderr
         */
        get() = this.fileErrorStream!!.fileUnsafe.asFragment()

    fun outAsBytes(): ByteArray? {
        return this.fileOutputStream!!.recordedOutput
    }

    @com.google.common.annotations.VisibleForTesting
    fun outAsLatin1(): String {
        return String(outAsBytes(), java.nio.charset.StandardCharsets.ISO_8859_1)
    }

    fun errAsBytes(): ByteArray? {
        return this.fileErrorStream!!.recordedOutput
    }

    @com.google.common.annotations.VisibleForTesting
    fun errAsLatin1(): String {
        return String(errAsBytes(), java.nio.charset.StandardCharsets.ISO_8859_1)
    }

    /** Returns the length of the stderr contents.  */
    @Throws(IOException::class)
    fun errSize(): Long {
        return this.fileErrorStream!!.recordedOutputSize
    }

    /**
     * Closes and deletes the error stream.
     */
    @Throws(IOException::class)
    fun clearErr() {
        this.fileErrorStream!!.clear()
    }

    /**
     * Closes and deletes the out stream.
     */
    @Throws(IOException::class)
    fun clearOut() {
        this.fileOutputStream!!.clear()
    }


    /**
     * Writes the captured out content to the given output stream,
     * avoiding keeping the entire contents in memory.
     */
    fun dumpOutAsLatin1(out: java.io.OutputStream?) {
        this.fileOutputStream!!.dumpOut(out)
    }

    /**
     * Writes the captured error content to the given error stream,
     * avoiding keeping the entire contents in memory.
     */
    fun dumpErrAsLatin1(out: java.io.OutputStream?) {
        this.fileErrorStream!!.dumpOut(out)
    }

    private val fileOutputStream: AbstractFileRecordingOutputStream?
        get() = getOutputStream() as AbstractFileRecordingOutputStream?

    private val fileErrorStream: AbstractFileRecordingOutputStream?
        get() = getErrorStream() as AbstractFileRecordingOutputStream?

    @ThreadSafe
    fun childOutErr(): FileOutErr {
        val index: Int = childCount.getAndIncrement()
        val outPath: com.google.devtools.build.lib.vfs.Path? = this.fileOutputStream!!.fileUnsafe
        val errPath: com.google.devtools.build.lib.vfs.Path? = this.fileErrorStream!!.fileUnsafe
        if (outPath == null || errPath == null) {
            return FileOutErr()
        }
        return FileOutErr(
            outPath.getParentDirectory().getRelative(outPath.getBaseName() + "-" + index),
            errPath.getParentDirectory().getRelative(errPath.getBaseName() + "-" + index)
        )
    }

    /**
     * An abstract supertype for the two other inner classes in this type
     * to implement streams that can write to a file.
     */
    private abstract class AbstractFileRecordingOutputStream : java.io.OutputStream() {
        /**
         * Returns true if this FileRecordingOutputStream has encountered an error.
         * 
         * @return true there was an error, false otherwise.
         */
        abstract fun hadError(): Boolean

        /**
         * Returns the file this FileRecordingOutputStream is writing to.
         */
        abstract val file: com.google.devtools.build.lib.vfs.Path?

        /**
         * Returns true if the FileOutErr has stored output.
         */
        abstract fun hasRecordedOutput(): Boolean

        /** Returns the output this AbstractFileOutErr has recorded.  */
        abstract val recordedOutput: ByteArray?

        @get:Throws(IOException::class)
        abstract val recordedOutputSize: Long

        /**
         * Writes the output to the given output stream,
         * avoiding keeping the entire contents in memory.
         */
        abstract fun dumpOut(out: java.io.OutputStream?)

        abstract val fileUnsafe: com.google.devtools.build.lib.vfs.Path?

        /** Closes and deletes the output.  */
        @Throws(IOException::class)
        abstract fun clear()
    }

    /**
     * An output stream that pretends to capture all its output into a file,
     * but instead discards it.
     */
    private class NullFileRecordingOutputStream : AbstractFileRecordingOutputStream() {
        override fun hadError(): Boolean {
            return false
        }

        override fun getFile(): com.google.devtools.build.lib.vfs.Path? {
            return null
        }

        override fun getFileUnsafe(): com.google.devtools.build.lib.vfs.Path? {
            return null
        }

        override fun hasRecordedOutput(): Boolean {
            return false
        }

        override fun getRecordedOutput(): ByteArray {
            return byteArrayOf()
        }

        override fun getRecordedOutputSize(): Long {
            return 0
        }

        override fun dumpOut(out: java.io.OutputStream?) {
            return
        }

        public override fun clear() {
        }

        override fun write(b: ByteArray?, off: Int, len: Int) {
        }

        override fun write(b: Int) {
        }

        override fun write(b: ByteArray?) {
        }
    }

    /**
     * An output stream that captures all output into a file. The file is created only if output is
     * received.
     * 
     * The user must take care that nobody else is writing to the file that is backing the output
     * stream.
     * 
     * The write() methods of type are synchronized to ensure that writes from different threads are
     * not mixed up. Note that this class is otherwise
     * [com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible]. Only the
     * write() methods are allowed to be concurrently, and only concurrently with each other. All
     * other calls must be serialized.
     * 
     * The outputStream is here only for the benefit of the pumping IO we're currently using for
     * execution - Once that is gone we can remove this output stream and fold its code into the
     * FileOutErr.
     */
    @ThreadCompatible
    open class FileRecordingOutputStream(outputFile: com.google.devtools.build.lib.vfs.Path) :
        AbstractFileRecordingOutputStream() {
        private val outputFile: com.google.devtools.build.lib.vfs.Path
        private var outputStream: java.io.OutputStream? = null
        private var error: String? = null
        private var cachedSize: Long?

        init {
            this.outputFile = outputFile
            this.cachedSize = 0L
        }

        override fun hadError(): Boolean {
            return error != null
        }

        override fun getFile(): com.google.devtools.build.lib.vfs.Path {
            // The caller is getting a reference to the filesystem path, so conservatively assume the
            // file has been modified.
            markDirty()
            return outputFile
        }

        override fun getFileUnsafe(): com.google.devtools.build.lib.vfs.Path {
            return outputFile
        }

        @kotlin.jvm.Synchronized
        private fun markDirty() {
            cachedSize = null
        }

        @Throws(IOException::class)
        private fun getOutputStream(): java.io.OutputStream? {
            // you should hold the lock before you invoke this method
            if (outputStream == null) {
                outputStream = outputFile.getOutputStream()
            }
            return outputStream
        }

        private fun hasOutputStream(): Boolean {
            return outputStream != null
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        public override fun clear() {
            close()
            outputStream = null
            outputFile.delete()
            cachedSize = 0L
        }

        /**
         * Called whenever the FileRecordingOutputStream finds an error.
         */
        protected fun recordError(exception: IOException) {
            val newErrorText: String? = exception.message
            error = if (error == null) newErrorText else error + "\n" + newErrorText
        }

        override fun hasRecordedOutput(): Boolean {
            try {
                return getRecordedOutputSize() > 0
            } catch (ex: IOException) {
                // Error already recorded by getRecordedOutputSize().
                return true
            }
        }

        override fun getRecordedOutput(): ByteArray? {
            var bytes: ByteArray? = null
            synchronized(this) {
                try {
                    bytes = com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(outputFile)
                    cachedSize = bytes!!.size.toLong()
                } catch (e: FileNotFoundException) {
                    cachedSize = 0L
                } catch (ex: IOException) {
                    recordError(ex)
                }
            }

            if (hadError()) {
                val errorBytes: ByteArray? = error.toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1)
                if (bytes == null) {
                    bytes = errorBytes
                } else {
                    bytes = com.google.common.primitives.Bytes.concat(bytes, errorBytes)
                }
            }
            return if (bytes == null) byteArrayOf() else bytes
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun getRecordedOutputSize(): Long {
            if (hadError()) {
                return error!!.length.toLong()
            }
            if (cachedSize == null) {
                try {
                    cachedSize = outputFile.getFileSize()
                } catch (e: FileNotFoundException) {
                    cachedSize = 0L
                } catch (e: IOException) {
                    recordError(e)
                    throw e
                }
            }
            return cachedSize!!
        }

        override fun dumpOut(out: java.io.OutputStream) {
            synchronized(this) {
                try {
                    outputFile.getInputStream().use { `in` ->
                        com.google.common.io.ByteStreams.copy(`in`, out)
                        out.flush()
                    }
                } catch (e: FileNotFoundException) {
                    cachedSize = 0L
                } catch (ex: IOException) {
                    recordError(ex)
                }
            }

            if (hadError()) {
                val ps: PrintStream = PrintStream(out)
                ps.print(error)
                ps.flush()
            }
        }

        @kotlin.jvm.Synchronized
        override fun write(b: ByteArray?, off: Int, len: Int) {
            if (len > 0) {
                markDirty()
                try {
                    getOutputStream().write(b, off, len)
                } catch (ex: IOException) {
                    recordError(ex)
                }
            }
        }

        @kotlin.jvm.Synchronized
        override fun write(b: Int) {
            markDirty()
            try {
                getOutputStream().write(b)
            } catch (ex: IOException) {
                recordError(ex)
            }
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun write(b: ByteArray) {
            if (b.size > 0) {
                markDirty()
                getOutputStream().write(b)
            }
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun flush() {
            if (hasOutputStream()) {
                getOutputStream().flush()
            }
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun close() {
            if (hasOutputStream()) {
                getOutputStream().close()
            }
        }
    }

    companion object {
        /**
         * Writes the captured content to the given [FileOutErr],
         * avoiding keeping the entire contents in memory.
         */
        fun dump(from: FileOutErr, to: FileOutErr) {
            from.dumpOutAsLatin1(to.getOutputStream())
            from.dumpErrAsLatin1(to.getErrorStream())
        }
    }
}
