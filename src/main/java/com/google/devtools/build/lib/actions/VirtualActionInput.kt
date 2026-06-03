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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.util.DeterministicWriter

/**
 * An ActionInput that does not actually exist on the filesystem, but can still be written to an
 * OutputStream.
 */
abstract class VirtualActionInput : ActionInput, DeterministicWriter {
    /**
     * Writes a [VirtualActionInput] so that no reader can observe an incomplete file, even in
     * the presence of concurrent writers.
     * 
     * 
     * Concurrent attempts to write the same file are possible when two actions share the same
     * input, or when a single action is dynamically executed and the input is simultaneously created
     * by the local and remote branches.
     * 
     * 
     * This implementation works by first creating a temporary file with a unique name and then
     * renaming it into place, relying on the atomicity of [FileSystem.renameTo]. Subclasses may
     * provide a more efficient implementation.
     * 
     * @param execRoot the path that this input should be written inside, typically the execroot
     * @return digest of written virtual input
     * @throws IOException if we fail to write the virtual input file
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    open fun atomicallyWriteRelativeTo(execRoot: Path): ByteArray? {
        val outputPath: Path = execRoot.getRelative(getExecPath())
        return atomicallyWriteTo(outputPath)
    }

    /**
     * Like [.atomicallyWriteRelativeTo], but takes the full path that the input should be
     * written to.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    protected open fun atomicallyWriteTo(outputPath: Path): ByteArray? {
        var tmpPath: Path? =
            outputPath
                .getFileSystem()
                .getPath(
                    (outputPath.getPathString()
                            + ".tmp."
                            + java.lang.Integer.toUnsignedString(TMP_SUFFIX.getAndIncrement()))
                )
        tmpPath.getParentDirectory().createDirectoryAndParents()
        tmpPath.delete()
        try {
            val digest = writeTo(tmpPath)
            try {
                tmpPath.renameTo(outputPath)
            } catch (e: FileAccessException) {
                // Moves fail on Windows if the target is accessed concurrently.
                if (OS.getCurrent() === OS.WINDOWS && java.util.Arrays.equals(outputPath.getDigest(), digest)) {
                    return digest
                }
                throw e
            }
            tmpPath = null // Avoid unnecessary deletion attempt.
            return digest
        } finally {
            try {
                if (tmpPath != null) {
                    // Make sure we don't leave temp files behind if we are interrupted.
                    tmpPath.delete()
                }
            } catch (e: IOException) {
                // Ignore.
            }
        }
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    protected fun writeTo(target: Path): ByteArray? {
        val digest: ByteArray?

        val fs: FileSystem = target.getFileSystem()
        target.getOutputStream().use { out ->
            com.google.common.hash.HashingOutputStream(fs.getDigestFunction().getHashFunction(), out)
                .use { hashingOut ->
                    BufferedOutputStream(hashingOut).use { bufferedHashingOut ->
                        writeTo(bufferedHashingOut)
                        bufferedHashingOut.flush()
                        digest = hashingOut.hash().asBytes()
                    }
                }
        }
        // Some of the virtual inputs can be executed, e.g. embedded tools. Setting executable flag for
        // other is fine since that is only more permissive. Please note that for action outputs (e.g.
        // file write, where the user can specify executable flag), we will have artifacts which do not
        // go through this code path.
        target.setExecutable(true)
        return digest
    }

    /**
     * Returns the metadata for this input if available. Null otherwise.
     * 
     * @throws IOException
     */
    @Throws(IOException::class)
    fun getMetadata(): FileArtifactValue? {
        return null
    }

    public override fun isDirectory(): Boolean {
        return false
    }

    public override fun isSymlink(): Boolean {
        return false
    }

    /**
     * In some cases, we want empty files in the runfiles tree that have no corresponding artifact. We
     * use instances of this class to represent those files.
     */
    class EmptyActionInput private constructor() : VirtualActionInput() {
        public override fun getExecPathString(): String? {
            throw java.lang.UnsupportedOperationException("empty virtual artifact doesn't have an execpath")
        }

        public override fun getExecPath(): PathFragment? {
            throw java.lang.UnsupportedOperationException("empty virtual artifact doesn't have an execpath")
        }

        override fun atomicallyWriteRelativeTo(execRoot: Path?): ByteArray {
            return emptyDigest
        }

        override fun atomicallyWriteTo(outputPath: Path?): ByteArray {
            return emptyDigest
        }

        @Throws(IOException::class)
        public override fun writeTo(out: java.io.OutputStream?) {
            // Write no content - it's an empty file.
        }

        public override fun getBytes(): ByteString? {
            return ByteString.EMPTY
        }

        override fun toString(): String {
            return "EmptyActionInput"
        }

        companion object {
            private val emptyDigest = ByteArray(0)
        }
    }

    companion object {
        /**
         * An empty virtual artifact **without** an execpath. This is used to denote empty files in
         * runfiles and filesets.
         */
        val EMPTY_MARKER: VirtualActionInput = EmptyActionInput()

        /** The next unique filename suffix to use when writing to a temporary path.  */
        private val TMP_SUFFIX: AtomicInteger = AtomicInteger(0)
    }
}
