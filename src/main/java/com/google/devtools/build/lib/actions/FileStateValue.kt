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

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * Encapsulates the filesystem operations needed to get state for a path. This is equivalent to an
 * 'lstat' that does not follow symlinks to determine what type of file the path is.
 * 
 * 
 *  * For a non-existent file, the non-existence is noted.
 *  * For a symlink, the symlink target is noted.
 *  * For a directory, the existence is noted.
 *  * For a file, the existence is noted, along with metadata about the file (e.g. file digest).
 * See [RegularFileStateValue].
 * 
 * 
 * 
 * This class is an implementation detail of [FileValue] and should not be used by [ ]s other than [ ]. Instead, [FileValue] should be used
 * by [com.google.devtools.build.skyframe.SkyFunction] consumers that care about files.
 * 
 * 
 * The common case for [FileValue] is [RegularFileValue] (i.e. the path's real path
 * is itself, and it's an existing file). As a memory optimization for this common case, we have
 * [FileStateValue] be a [RegularFileValue] so that we don't need a wrapper object for
 * the value of the corresponding [FileValue] node.
 * 
 * 
 * All subclasses must implement [.equals] and [.hashCode] properly.
 */
abstract class FileStateValue private constructor() : RegularFileValue(), HasDigest {
    override fun realFileStateValue(): FileStateValue {
        return this
    }

    abstract fun getType(): FileStateType?

    /** Returns the target of the symlink, or throws an exception if this is not a symlink.  */
    open fun getSymlinkTarget(): PathFragment? {
        throw java.lang.IllegalStateException()
    }

    override fun getSize(): Long {
        throw java.lang.IllegalStateException()
    }

    abstract fun getContentsProxy(): FileContentsProxy?

    override fun getDigest(): ByteArray? {
        throw java.lang.IllegalStateException()
    }

    abstract fun getValueFingerprint(): ByteArray?

    override fun toString(): String {
        return prettyPrint()!!
    }

    abstract fun prettyPrint(): String?

    /**
     * Implementation of [FileStateValue] for regular files when a [.digest] is provided.
     */
    class RegularFileStateValueWithDigest @com.google.common.annotations.VisibleForTesting constructor(
        private val size: Long,
        digest: ByteArray?
    ) : FileStateValue() {
        private val digest: ByteArray

        init {
            this.digest = com.google.common.base.Preconditions.checkNotNull<ByteArray?>(digest)
        }

        override fun getType(): FileStateType {
            return FileStateType.REGULAR_FILE
        }

        override fun getSize(): Long {
            return size
        }

        override fun getDigest(): ByteArray {
            return digest
        }

        override fun getContentsProxy(): FileContentsProxy? {
            return null
        }

        override fun equals(obj: Any?): Boolean {
            if (obj === this) {
                return true
            }
            if (obj !is RegularFileStateValueWithDigest) {
                return false
            }
            return size == obj.size && digest.contentEquals(obj.digest)
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(size, digest.contentHashCode())
        }

        override fun getValueFingerprint(): ByteArray {
            val fp: Fingerprint = Fingerprint().addLong(size)
            fp.addBytes(digest)
            return fp.digestAndReset()
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("digest", digest).add("size", size)
                .toString()
        }

        public override fun prettyPrint(): String? {
            val contents: String? = String.format("digest of %s", digest.contentToString())
            return String.format("regular file with size of %d and %s", size, contents)
        }
    }

    /**
     * Implementation of [FileStateValue] for regular files when [FileContentsProxy] is
     * provided.
     * 
     * 
     * [.contentsProxy] is used to determine whether the file was modified.
     */
    class RegularFileStateValueWithContentsProxy @com.google.common.annotations.VisibleForTesting constructor(
        private val size: Long,
        contentsProxy: FileContentsProxy?
    ) : FileStateValue() {
        private val contentsProxy: FileContentsProxy

        init {
            this.contentsProxy = com.google.common.base.Preconditions.checkNotNull<FileContentsProxy>(contentsProxy)
        }

        override fun getType(): FileStateType {
            return FileStateType.REGULAR_FILE
        }

        override fun getSize(): Long {
            return size
        }

        override fun getDigest(): ByteArray? {
            return null
        }

        override fun getContentsProxy(): FileContentsProxy {
            return contentsProxy
        }

        override fun equals(obj: Any?): Boolean {
            if (obj === this) {
                return true
            }
            if (obj !is RegularFileStateValueWithContentsProxy) {
                return false
            }
            return size == obj.size && contentsProxy == obj.contentsProxy
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(size, contentsProxy)
        }

        override fun getValueFingerprint(): ByteArray {
            val fp: Fingerprint = Fingerprint().addLong(size)
            contentsProxy.addToFingerprint(fp)
            return fp.digestAndReset()
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("size", size)
                .add("contentsProxy", contentsProxy)
                .toString()
        }

        public override fun prettyPrint(): String? {
            return String.format(
                "regular file with size of %d and %s", size, contentsProxy.prettyPrint()
            )
        }
    }

    /**
     * Implementation of [FileStateValue] for regular files when its metadata is backed by a
     * [FileArtifactValue].
     */
    class RegularFileStateValueWithMetadata @com.google.common.annotations.VisibleForTesting constructor(metadata: FileArtifactValue?) :
        FileStateValue() {
        private val metadata: FileArtifactValue

        init {
            this.metadata = com.google.common.base.Preconditions.checkNotNull<FileArtifactValue>(metadata)
        }

        override fun getType(): FileStateType {
            return FileStateType.REGULAR_FILE
        }

        override fun getSize(): Long {
            return metadata.getSize()
        }

        override fun getDigest(): ByteArray? {
            return metadata.getDigest()
        }

        override fun getContentsProxy(): FileContentsProxy? {
            return metadata.getContentsProxy()
        }

        fun getMetadata(): FileArtifactValue {
            return metadata
        }

        override fun equals(obj: Any?): Boolean {
            if (obj === this) {
                return true
            }
            if (obj !is RegularFileStateValueWithMetadata) {
                return false
            }
            return obj.metadata == this.metadata
        }

        override fun hashCode(): Int {
            return metadata.hashCode()
        }

        override fun getValueFingerprint(): ByteArray {
            val fp: Fingerprint = Fingerprint().addLong(getSize())
            fp.addBytes(getDigest())
            return fp.digestAndReset()
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("metadata", metadata).toString()
        }

        public override fun prettyPrint(): String? {
            return String.format("regular file with size of %d and %s", getSize(), metadata)
        }
    }

    /** Implementation of [FileStateValue] for special files that exist.  */
    @com.google.common.annotations.VisibleForTesting
    class SpecialFileStateValue @com.google.common.annotations.VisibleForTesting constructor(contentsProxy: FileContentsProxy?) :
        FileStateValue() {
        private val contentsProxy: FileContentsProxy

        init {
            this.contentsProxy = com.google.common.base.Preconditions.checkNotNull<FileContentsProxy>(contentsProxy)
        }

        override fun getType(): FileStateType {
            return FileStateType.SPECIAL_FILE
        }

        override fun getSize(): Long {
            return 0
        }

        override fun getDigest(): ByteArray? {
            return null
        }

        override fun getContentsProxy(): FileContentsProxy {
            return contentsProxy
        }

        override fun equals(obj: Any?): Boolean {
            if (obj === this) {
                return true
            }
            if (obj !is SpecialFileStateValue) {
                return false
            }
            return contentsProxy == obj.contentsProxy
        }

        override fun hashCode(): Int {
            return contentsProxy.hashCode()
        }

        override fun getValueFingerprint(): ByteArray {
            val fp: Fingerprint = Fingerprint()
            contentsProxy.addToFingerprint(fp)
            return fp.digestAndReset()
        }

        public override fun prettyPrint(): String? {
            return String.format("special file with %s", contentsProxy.prettyPrint())
        }

        companion object {
            @Throws(IOException::class)
            private fun fromStat(
                path: PathFragment?, stat: FileStatus, tsgm: TimestampGranularityMonitor?
            ): SpecialFileStateValue {
                // Note that TimestampGranularityMonitor#notifyDependenceOnFileTime is a thread-safe method.
                if (tsgm != null) {
                    tsgm.notifyDependenceOnFileTime(path, stat.getLastChangeTime())
                }
                return SpecialFileStateValue(FileContentsProxy.Companion.create(stat))
            }
        }
    }

    /** Implementation of [FileStateValue] for directories that exist.  */
    class DirectoryFileStateValue private constructor() : FileStateValue() {
        override fun getType(): FileStateType {
            return FileStateType.DIRECTORY
        }

        override fun getContentsProxy(): FileContentsProxy? {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun prettyPrint(): String {
            return "directory"
        }

        // This object is normally a singleton, but deserialization produces copies.
        override fun equals(obj: Any?): Boolean {
            return obj is DirectoryFileStateValue
        }

        override fun hashCode(): Int {
            return 7654321
        }

        override fun getValueFingerprint(): ByteArray? {
            return FINGERPRINT
        }

        companion object {
            private val FINGERPRINT: ByteArray? =
                "DirectoryFileStateValue".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        }
    }

    /** Implementation of [FileStateValue] for symlinks.  */
    @com.google.common.annotations.VisibleForTesting
    class SymlinkFileStateValue @com.google.common.annotations.VisibleForTesting constructor(symlinkTarget: PathFragment) :
        FileStateValue() {
        private val symlinkTarget: PathFragment

        init {
            this.symlinkTarget = symlinkTarget
        }

        override fun getType(): FileStateType {
            return FileStateType.SYMLINK
        }

        override fun getSymlinkTarget(): PathFragment {
            return symlinkTarget
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is SymlinkFileStateValue) {
                return false
            }
            return symlinkTarget.equals(obj.symlinkTarget)
        }

        override fun hashCode(): Int {
            return symlinkTarget.hashCode()
        }

        override fun getContentsProxy(): FileContentsProxy? {
            return null
        }

        override fun getValueFingerprint(): ByteArray {
            return Fingerprint().addPath(symlinkTarget).digestAndReset()
        }

        public override fun prettyPrint(): String {
            return "symlink to " + symlinkTarget
        }
    }

    /** Implementation of [FileStateValue] for nonexistent files.  */
    private class NonexistentFileStateValue : FileStateValue() {
        override fun getType(): FileStateType {
            return FileStateType.NONEXISTENT
        }

        override fun getContentsProxy(): FileContentsProxy? {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun prettyPrint(): String {
            return "nonexistent path"
        }

        // This object is normally a singleton, but deserialization produces copies.
        override fun equals(obj: Any?): Boolean {
            if (obj === this) {
                return true
            }
            return obj is NonexistentFileStateValue
        }

        override fun hashCode(): Int {
            return 8765432
        }

        override fun getValueFingerprint(): ByteArray? {
            return FINGERPRINT
        }

        companion object {
            private val FINGERPRINT: ByteArray? =
                "NonexistentFileStateValue".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        }
    }

    companion object {
        @SerializationConstant
        val DIRECTORY_FILE_STATE_NODE: DirectoryFileStateValue = DirectoryFileStateValue()

        @SerializationConstant
        val NONEXISTENT_FILE_STATE_NODE: NonexistentFileStateValue = NonexistentFileStateValue()

        @Throws(IOException::class)
        fun create(
            rootedPath: RootedPath, syscallCache: SyscallCache, tsgm: TimestampGranularityMonitor?
        ): FileStateValue? {
            val path: Path = rootedPath.asPath()
            val typeWithSkip: SyscallCache.DirentTypeWithSkip? = syscallCache.getType(path, Symlinks.NOFOLLOW)
            var stat: FileStatus? = null
            var type: Dirent.Type? = null
            if (typeWithSkip === SyscallCache.DirentTypeWithSkip.FILESYSTEM_OP_SKIPPED) {
                stat = syscallCache.statIfFound(path, Symlinks.NOFOLLOW)
                type = SyscallCache.statusToDirentType(stat)
            } else if (typeWithSkip != null) {
                type = typeWithSkip.getType()
            }
            if (type == null) {
                return NONEXISTENT_FILE_STATE_NODE
            }
            return when (type) {
                DIRECTORY -> DIRECTORY_FILE_STATE_NODE
                SYMLINK -> SymlinkFileStateValue(path.readSymbolicLinkUnchecked())
                FILE, UNKNOWN -> {
                    if (stat == null) {
                        stat = syscallCache.statIfFound(path, Symlinks.NOFOLLOW)
                    }
                    if (stat == null) {
                        throw InconsistentFilesystemException(
                            "File " + rootedPath + " found in directory, but stat failed"
                        )
                    }
                    createWithStatNoFollow(
                        rootedPath,
                        checkNotNull(FileStatusWithDigestAdapter.maybeAdapt(stat), rootedPath),
                        syscallCache,
                        tsgm
                    )
                }
            }
        }

        @Throws(IOException::class)
        fun createWithStatNoFollow(
            rootedPath: RootedPath,
            statNoFollow: FileStatusWithDigest,
            xattrProvider: XattrProvider,
            tsgm: TimestampGranularityMonitor?
        ): FileStateValue {
            val path: Path = rootedPath.asPath()
            if (statNoFollow.isFile()) {
                return if (statNoFollow.isSpecialFile())
                    SpecialFileStateValue.Companion.fromStat(path.asFragment(), statNoFollow, tsgm)
                else
                    createRegularFileStateValueFromPath(path, statNoFollow, xattrProvider, tsgm)
            } else if (statNoFollow.isDirectory()) {
                return DIRECTORY_FILE_STATE_NODE
            } else if (statNoFollow.isSymbolicLink()) {
                return SymlinkFileStateValue(path.readSymbolicLinkUnchecked())
            }
            throw InconsistentFilesystemException(
                ("according to stat, existing path " + path + " is "
                        + "neither a file nor directory nor symlink.")
            )
        }

        /**
         * Creates a [FileStateValue] instance corresponding to the given existing file.
         * 
         * 
         * We use digests only if a fast digest lookup is available from the filesystem. If not, we
         * fall back to mtime-based digests. This avoids the case where Blaze must read all files involved
         * in the build in order to check for modifications in the case where fast digest lookups are not
         * available.
         * 
         * @param stat must be of type "File". (Not a symlink).
         */
        @Throws(InconsistentFilesystemException::class)
        private fun createRegularFileStateValueFromPath(
            path: Path,
            stat: FileStatusWithDigest,
            xattrProvider: XattrProvider,
            tsgm: TimestampGranularityMonitor?
        ): FileStateValue {
            checkState(stat.isFile(), path)

            if (stat is FileStatusWithMetadata) {
                return RegularFileStateValueWithMetadata(stat.getMetadata())
            }
            try {
                val digest = tryGetDigest(path, stat, xattrProvider)
                if (digest == null) {
                    // Note that TimestampGranularityMonitor#notifyDependenceOnFileTime is a thread-safe method.
                    if (tsgm != null) {
                        tsgm.notifyDependenceOnFileTime(path.asFragment(), stat.getLastChangeTime())
                    }
                    return RegularFileStateValueWithContentsProxy(
                        stat.getSize(), FileContentsProxy.Companion.create(stat)
                    )
                } else {
                    // We are careful here to avoid putting the value ID into FileMetadata if we already have a
                    // digest. Arbitrary filesystems may do weird things with the value ID; a digest is more
                    // robust.
                    return RegularFileStateValueWithDigest(stat.getSize(), digest)
                }
            } catch (e: IOException) {
                val errorMessage = if (e.message != null) "error '" + e.message + "'" else "an error"
                throw InconsistentFilesystemException(
                    ("'stat' said "
                            + path
                            + " is a file but then we "
                            + "later encountered "
                            + errorMessage
                            + " which indicates that "
                            + path
                            + " is no "
                            + "longer a file. Did you delete it during the build?")
                )
            }
        }

        @Throws(IOException::class)
        private fun tryGetDigest(
            path: Path, stat: FileStatusWithDigest, xattrProvider: XattrProvider
        ): ByteArray? {
            try {
                val digest: ByteArray? = stat.getDigest()
                return if (digest != null) digest else xattrProvider.getFastDigest(path)
            } catch (ioe: IOException) {
                if (!path.isReadable()) {
                    return null
                }
                throw ioe
            }
        }

        @ThreadSafe
        fun key(rootedPath: RootedPath?): RootedPath? {
            // RootedPath is already the SkyKey we want; see FileStateKey. This method and that interface
            // are provided as readability aids.
            return rootedPath
        }
    }
}
