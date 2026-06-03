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

import com.google.devtools.build.lib.actions.FileArtifactValue.Companion.createForTesting
import com.google.devtools.build.lib.actions.FileArtifactValue.Companion.createForUnresolvedSymlink
import com.google.devtools.build.lib.vfs.PathFragment.pathFragmentCodec

/**
 * A value that represents a file for the purposes of up-to-dateness checks of actions.
 * 
 * 
 * It always stands for an actual file. In particular, tree artifacts and runfiles trees do not
 * have a corresponding [FileArtifactValue]. However, the file is not necessarily present in
 * the file system; this happens when intermediate build outputs are not downloaded (and maybe when
 * an input artifact of an action is missing?)
 * 
 * 
 * It makes its main appearance in `ActionExecutionValue.artifactData`. It has two main
 * uses:
 * 
 * 
 *  * This is how dependent actions get hold of the output metadata of their generated inputs.
 *  * This is how `FileSystemValueChecker` figures out which actions need to be invalidated
 * (just propagating the invalidation up from leaf nodes is not enough, because the output
 * tree may have been changed while Blaze was not looking)
 * 
 * 
 * 
 * [FileArtifactValue] instance equality should only be used for testing purposes. To
 * determine whether a metadata is equivalent to another for invalidation purposes, use [ ][.couldBeModifiedSince] or [.wasModifiedSinceDigest].
 */
@Immutable
@ThreadSafe
abstract class FileArtifactValue : SkyValue, HasDigest {
    /**
     * The type of the underlying file system object. If it is a regular file, then it is guaranteed
     * to have a digest. Otherwise it does not have a digest.
     */
    abstract val type: FileStateType?

    /**
     * Returns a digest of the content of the underlying file system object; must always return a
     * non-null value for instances of type [FileStateType.REGULAR_FILE] that are owned by an
     * `ActionExecutionValue`.
     * 
     * 
     * All instances of this interface must either have a digest or return a last-modified time.
     * Clients should prefer using the digest for content identification (e.g., for caching), and only
     * fall back to the last-modified time if no digest is available.
     * 
     * 
     * The return value is owned by this object and must not be modified.
     */
    abstract val digest: ByteArray?

    /** Returns the file's size, or 0 if the underlying file system object is not a file.  */ // TODO(ulfjack): Throw an exception if it's not a file.
    abstract val size: Long
        /** Returns the file's size, or 0 if the underlying file system object is not a file.  */
        get

    /**
     * Returns the last modified time; see the documentation of [.getDigest] for when this can
     * and should be called.
     */
    abstract fun getModifiedTime(): Long

    /**
     * Returns a contents proxy (typically, a subset of the file system object's inode properties)
     * that can be used to detect modifications more cheaply (at the cost of increased chance of a
     * false negative) in situations where a digest would be too expensive to compute.
     * 
     * 
     * If no proxy is available, returns null.
     */
    open fun getContentsProxy(): FileContentsProxy? {
        return null
    }

    /**
     * Sets the contents proxy. If this metadata does not support setting the contents proxy, does
     * nothing.
     */
    open fun setContentsProxy(proxy: FileContentsProxy?) {}

    open fun getValueFingerprint(): ByteArray? {
        // TODO(janakr): return fingerprint in other cases: symlink, directory.
        return this.digest
    }

    /**
     * Returns the unresolved symlink target path, which is always normalized.
     * 
     * @throws UnsupportedOperationException if the metadata is not of symlink file type.
     */
    open fun getUnresolvedSymlinkTarget(): String? {
        throw java.lang.UnsupportedOperationException()
    }

    /**
     * Returns whether the file contents are inline, i.e., can be obtained directly from this [ ] by calling [.getInputStream].
     */
    open fun isInline(): Boolean {
        return false
    }

    /**
     * Returns an input stream for the inline file contents.
     * 
     * @throws UnsupportedOperationException if the file contents are not inline.
     */
    open fun getInputStream(): java.io.InputStream? {
        throw java.lang.UnsupportedOperationException()
    }

    /** Returns whether the file contents exist remotely.  */
    open fun isRemote(): Boolean {
        return false
    }

    /** Returns the location index for remote files. For non-remote files, returns 0.  */
    open fun getLocationIndex(): Int {
        return 0
    }

    /**
     * Returns the time when the remote file contents may expire. If the contents never expire,
     * including when they're not remote, returns null.
     * 
     * 
     * The expiration time does not factor into equality, as it can be mutated by [ ][.setExpirationTime].
     */
    open fun getExpirationTime(): Instant? {
        return null
    }

    /**
     * Sets the expiration time. If this metadata does not support setting the expiration time, does
     * nothing.
     */
    open fun setExpirationTime(newExpirationTime: Instant?) {}

    /**
     * Provides a best-effort determination whether the file was changed since the digest was
     * computed. This method performs file system I/O, so may be expensive. It's primarily intended to
     * avoid storing bad cache entries in an action cache. It should return true if there is a chance
     * that the file was modified since the digest was computed. Better not upload if we are not sure
     * that the cache entry is reliable.
     */
    // TODO(lberki): This is very similar to couldBeModifiedSince(). Check if we can unify these.
    @Throws(IOException::class)
    abstract fun wasModifiedSinceDigest(path: Path?): Boolean

    /**
     * Returns whether the two [FileArtifactValue] instances could be considered the same for
     * purposes of action invalidation.
     */
    // TODO(lberki): This is very similar to wasModifiedSinceDigest(). Check if we can unify these.
    fun couldBeModifiedSince(lastKnown: FileArtifactValue): Boolean {
        if (this is Singleton || lastKnown is Singleton) {
            return true
        }

        if (this.type != lastKnown.type) {
            return true
        }

        if (this.digest != null && lastKnown.digest != null) {
            // If we know the digests, we can tell with certainty whether the file has changed.
            return !this.digest.contentEquals(lastKnown.digest) || this.size != lastKnown.size
        } else {
            // If not, we assume by default that the file has changed, but individual implementations
            // might know better. For example, regular local files can be compared by ctime or mtime.
            return couldBeModifiedByMetadata(lastKnown)
        }
    }

    /** Adds this file metadata to the given [Fingerprint].  */
    fun addTo(fp: Fingerprint) {
        val digest = this.digest
        if (digest != null) {
            fp.addBytes(digest)
        } else {
            // Use the timestamp if the digest is not present, but not both. Modifying a timestamp while
            // keeping the contents of a file the same should not cause rebuilds.
            fp.addLong(getModifiedTime())
        }
    }

    protected open fun couldBeModifiedByMetadata(lastKnown: FileArtifactValue?): Boolean {
        return true
    }

    /**
     * Returns the real path at which the file contents this metadata refers to can be found.
     * 
     * 
     * If present, an artifact possessing this metadata is materialized in the filesystem as a
     * symlink to another artifact, but acts as a copy of that artifact for invalidation purposes.
     * Thus, all other metadata fields reflect the properties of the file system object found at the
     * real path. In particular, this means that [.getType] doesn't necessarily return [ ][FileStateType.SYMLINK].
     * 
     * 
     * The path must be absolute and not contain any unresolved symlinks, i.e., calling [ ][Path.resolveSymbolicLinks] on it should yield the same path.
     * 
     * 
     * This allows such an artifact to be created as a symlink to the real path when lazily
     * materialized on disk, in situations where making a copy is undesirable (e.g. because it would
     * result in redundant downloads of the same remote output file) or impossible (e.g. because the
     * original is a source file or a local output file, and its contents cannot be obtained from the
     * digest). An output service is free to ignore this hint and materialize the artifact in some
     * other way (e.g. as a regular file backed by a FUSE filesystem).
     */
    open fun getResolvedPath(): PathFragment? {
        return null
    }

    /**
     * Marker interface for singleton implementations of this class.
     * 
     * 
     * Needed for a correct implementation of `equals`.
     */
    internal interface Singleton

    private class DirectoryArtifactValue(private val mtime: Long) : FileArtifactValue() {
        override fun equals(o: Any?): Boolean {
            if (o !is DirectoryArtifactValue) {
                return false
            }

            return mtime == o.mtime
        }

        override fun hashCode(): Int {
            return java.lang.Long.hashCode(mtime)
        }

        override fun getType(): FileStateType {
            return FileStateType.DIRECTORY
        }

        override fun getDigest(): ByteArray? {
            return null
        }

        override fun getValueFingerprint(): ByteArray {
            return Fingerprint()
                .addString(javaClass.getCanonicalName())
                .addLong(mtime)
                .digestAndReset()
        }

        override fun getModifiedTime(): Long {
            return mtime
        }

        override fun getSize(): Long {
            return 0
        }

        override fun wasModifiedSinceDigest(path: Path?): Boolean {
            return false
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("mtime", mtime).toString()
        }
    }

    private class HashedDirectoryArtifactValue(private val digest: ByteArray?) : FileArtifactValue() {
        override fun equals(o: Any?): Boolean {
            if (o !is HashedDirectoryArtifactValue) {
                return false
            }

            return digest.contentEquals(o.digest)
        }

        override fun hashCode(): Int {
            return digest.contentHashCode()
        }

        override fun getType(): FileStateType {
            return FileStateType.DIRECTORY
        }

        override fun getDigest(): ByteArray? {
            return digest
        }

        override fun getModifiedTime(): Long {
            return 0
        }

        override fun getSize(): Long {
            return 0
        }

        override fun wasModifiedSinceDigest(path: Path?): Boolean {
            // TODO(ulfjack): Ideally, we'd attempt to detect intra-build modifications here. I'm
            // consciously deferring work here as this code will most likely change again, and we're
            // already doing better than before by detecting inter-build modifications.
            return false
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("digest", bytesToString(digest))
                .toString()
        }
    }

    private class RegularFileArtifactValue(private val digest: ByteArray?, proxy: FileContentsProxy?, size: Long) :
        FileArtifactValue() {
        private val proxy: FileContentsProxy?
        private val size: Long

        init {
            this.proxy = proxy
            this.size = size
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is RegularFileArtifactValue) {
                return false
            }
            return digest.contentEquals(o.digest) && proxy == o.proxy
                    && size == o.size
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(digest.contentHashCode(), proxy, size)
        }

        override fun getType(): FileStateType {
            return FileStateType.REGULAR_FILE
        }

        override fun getDigest(): ByteArray? {
            return digest
        }

        override fun getContentsProxy(): FileContentsProxy? {
            return proxy
        }

        override fun getSize(): Long {
            return size
        }

        @Throws(IOException::class)
        override fun wasModifiedSinceDigest(path: Path): Boolean {
            if (proxy == null) {
                return false
            }
            val stat: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                path.statIfFound(Symlinks.FOLLOW)
            if (stat == null || !stat.isFile()) {
                // The file no longer exists or changed type, so it certainly has changed.
                return true
            }
            val newProxy: FileContentsProxy = FileContentsProxy.Companion.create(stat)
            if (proxy == newProxy) {
                // If the proxy is the same, then the file certainly hasn't been modified. This is the
                // common case, so we check it first.
                return false
            }
            if (proxy.isModified(newProxy)) {
                // If the non-ctime information in the proxy changed, the file has certainly been modified
                // between the time the digest was computed and now.
                return true
            }
            // At this point the ctime changed, so some of the file's metadata has changed since we
            // computed the digest. Returning true here would allow us to cautiously report modification
            // even in complex ABA scenarios (file modified, then modified back with its mtime reset).
            // However, we would also report modification in case a hardlink to the file was created or
            // removed, such as by the hermetic Linux sandbox or certain optimized copy actions.
            // As a compromise, we check whether the current state of the file differs from the previous
            // one, ignoring any inbetween modifications that may have happened.
            //
            // Note that this path is always taken when using the hermetic Linux sandbox, but the
            // associated cost should amortize over the next build as the digest will be cached under the
            // new stat.
            val newDigest: ByteArray? = DigestUtils.getDigestWithManualFallback(path, SyscallCache.NO_CACHE, stat)
            return !digest.contentEquals(newDigest)
        }

        override fun getModifiedTime(): Long {
            throw java.lang.UnsupportedOperationException(
                "regular file's mtime should never be called. (" + this + ")"
            )
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("digest", bytesToString(digest))
                .add("size", size)
                .add("proxy", proxy)
                .toString()
        }

        override fun couldBeModifiedByMetadata(lastKnown: FileArtifactValue): Boolean {
            return size != lastKnown.size || proxy != lastKnown.getContentsProxy()
        }
    }

    /** Proxy metadata for a runfiles tree.  */
    private class RunfilesProxyArtifactValue(private val digest: ByteArray?) : FileArtifactValue() {
        override fun getType(): FileStateType {
            return FileStateType.DIRECTORY
        }

        override fun getSize(): Long {
            return 0
        }

        override fun wasModifiedSinceDigest(path: Path?): Boolean {
            return false
        }

        override fun getDigest(): ByteArray? {
            return digest
        }

        override fun getModifiedTime(): Long {
            throw java.lang.UnsupportedOperationException(
                "runfile proxy's mtime should never be called. (" + this + ")"
            )
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is RunfilesProxyArtifactValue) {
                return false
            }
            return digest.contentEquals(o.digest)
        }

        override fun hashCode(): Int {
            return digest.contentHashCode()
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("digest", bytesToString(digest))
                .toString()
        }
    }

    /** Metadata for remotely stored files.  */
    private open class RemoteFileArtifactValue(
        digest: ByteArray?,
        private val size: Long,
        private val locationIndex: Int
    ) : FileArtifactValue() {
        private val digest: ByteArray

        init {
            this.digest = com.google.common.base.Preconditions.checkNotNull<ByteArray?>(digest)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is RemoteFileArtifactValue) {
                return false
            }

            return digest.contentEquals(o.digest) && size == o.size && locationIndex == o.locationIndex
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(digest.contentHashCode(), size, locationIndex)
        }

        override fun getType(): FileStateType {
            return FileStateType.REGULAR_FILE
        }

        override fun getDigest(): ByteArray {
            return digest
        }

        override fun getSize(): Long {
            return size
        }

        override fun getModifiedTime(): Long {
            throw java.lang.UnsupportedOperationException(
                "RemoteFileArtifactValue doesn't support getModifiedTime"
            )
        }

        override fun getLocationIndex(): Int {
            return locationIndex
        }

        override fun wasModifiedSinceDigest(path: Path?): Boolean {
            return false
        }

        override fun isRemote(): Boolean {
            return true
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("digest", bytesToString(digest))
                .add("size", size)
                .add("locationIndex", locationIndex)
                .toString()
        }
    }

    /**
     * Metadata for remotely stored files, with the additional ability to store a [ ][.getExpirationTime] modifiable via [.setExpirationTime], and a [.getContentsProxy]
     * modifiable via [.setContentsProxy].
     * 
     * 
     * This is used when the output mode allows for late materialization of remote outputs in the
     * local filesystem.
     */
    private class RemoteFileArtifactValueWithMaterializationData
        (digest: ByteArray?, size: Long, locationIndex: Int, expirationTime: Instant?) :
        RemoteFileArtifactValue(digest, size, locationIndex) {
        private var expirationTime: Long
        private var proxy: FileContentsProxy? = null

        init {
            this.expirationTime = toEpochMilli(expirationTime)
        }

        override fun getExpirationTime(): Instant? {
            return fromEpochMilli(expirationTime)
        }

        override fun setExpirationTime(expirationTime: Instant?) {
            this.expirationTime = toEpochMilli(expirationTime)
        }

        /**
         * {@inheritDoc}
         * 
         * 
         * Returns non-null if the file contents have been materialized in the local filesystem.
         */
        override fun getContentsProxy(): FileContentsProxy? {
            return proxy
        }

        /**
         * {@inheritDoc}
         * 
         * 
         * Called when the file contents are materialized in the local filesystem.
         */
        override fun setContentsProxy(proxy: FileContentsProxy?) {
            this.proxy = proxy
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is RemoteFileArtifactValueWithMaterializationData) {
                return false
            }

            return getDigest().contentEquals(o.getDigest()) && getSize() == o.getSize() && getLocationIndex() == o.getLocationIndex()
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(getDigest().contentHashCode(), getSize(), getLocationIndex())
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("digest", bytesToString(getDigest()))
                .add("size", getSize())
                .add("locationIndex", getLocationIndex())
                .add("expirationTime", fromEpochMilli(expirationTime))
                .add("proxy", proxy)
                .toString()
        }

        companion object {
            private fun toEpochMilli(expirationTime: Instant?): Long {
                return if (expirationTime != null) expirationTime.toEpochMilli() else -1
            }

            private fun fromEpochMilli(expirationTime: Long): Instant? {
                return if (expirationTime >= 0) Instant.ofEpochMilli(expirationTime) else null
            }
        }
    }

    /**
     * Metadata for an artifact that is materialized in the filesystem as a symlink to another
     * artifact, but acts as a copy of that artifact for invalidation purposes. See the documentation
     * of [.getResolvedPath] for when this is useful.
     * 
     * 
     * Other than [.getResolvedPath], all methods delegate to the [FileArtifactValue]
     * of the artifact pointed to, which must itself have a null [.getResolvedPath]).
     */
    private class ResolvedSymlinkArtifactValue(delegate: FileArtifactValue, resolvedPath: PathFragment) :
        FileArtifactValue() {
        private val delegate: FileArtifactValue
        private val resolvedPath: PathFragment

        // TODO(b/329460099): Store just the execpath once multiple source roots are no longer
        // supported. At that point it becomes possible to reliably compute the absolute path from the
        // execpath.
        init {
            com.google.common.base.Preconditions.checkArgument(
                delegate !is Singleton,
                "delegate is a singleton: %s",
                delegate
            )
            checkArgument(resolvedPath.isAbsolute(), "resolved path is not absolute: %s", resolvedPath)
            com.google.common.base.Preconditions.checkArgument(
                delegate.getResolvedPath() == null || delegate.getResolvedPath().equals(resolvedPath),
                "delegate has a different resolved path: %s",
                delegate
            )
            this.delegate =
                if (delegate is ResolvedSymlinkArtifactValue)
                    delegate.delegate
                else
                    delegate
            this.resolvedPath = resolvedPath
        }

        override fun getResolvedPath(): PathFragment {
            return resolvedPath
        }

        override fun getType(): FileStateType? {
            return delegate.type
        }

        override fun getDigest(): ByteArray? {
            return delegate.digest
        }

        override fun getContentsProxy(): FileContentsProxy? {
            return delegate.getContentsProxy()
        }

        override fun setContentsProxy(proxy: FileContentsProxy?) {
            delegate.setContentsProxy(proxy)
        }

        override fun getSize(): Long {
            return delegate.size
        }

        override fun getModifiedTime(): Long {
            return delegate.getModifiedTime()
        }

        @Throws(IOException::class)
        override fun wasModifiedSinceDigest(path: Path?): Boolean {
            return delegate.wasModifiedSinceDigest(path)
        }

        override fun couldBeModifiedByMetadata(lastKnown: FileArtifactValue?): Boolean {
            return delegate.couldBeModifiedByMetadata(lastKnown)
        }

        override fun getValueFingerprint(): ByteArray? {
            return delegate.getValueFingerprint()
        }

        override fun isInline(): Boolean {
            return delegate.isInline()
        }

        override fun getInputStream(): java.io.InputStream? {
            return delegate.getInputStream()
        }

        override fun isRemote(): Boolean {
            return delegate.isRemote()
        }

        override fun getLocationIndex(): Int {
            return delegate.getLocationIndex()
        }

        override fun getExpirationTime(): Instant? {
            return delegate.getExpirationTime()
        }

        override fun setExpirationTime(newExpirationTime: Instant?) {
            delegate.setExpirationTime(newExpirationTime)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is ResolvedSymlinkArtifactValue) {
                return false
            }
            return delegate == o.delegate && resolvedPath.equals(o.resolvedPath)
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(delegate, resolvedPath)
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("delegate", delegate)
                .add("resolvedPath", resolvedPath)
                .toString()
        }
    }

    /**
     * Codec that serializes the absolute [ResolvedSymlinkArtifactValue.resolvedPath] by finding
     * its root in [PackagePathCodecDependencies] and relativizing.
     */
    // TODO: b/329460099 - This would not be necessary if we could store a source root relative path.
    @com.google.errorprone.annotations.Keep // Used reflectively.
    private class ResolvedSymlinkArtifactValueCodec

        : ObjectCodec<ResolvedSymlinkArtifactValue?> {
        public override fun getEncodedClass(): java.lang.Class<out ResolvedSymlinkArtifactValue?> {
            return ResolvedSymlinkArtifactValue::class.java
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: ResolvedSymlinkArtifactValue, codedOut: CodedOutputStream
        ) {
            context.serialize(obj.delegate, codedOut)

            val resolvedPath: PathFragment? = obj.resolvedPath
            val roots: com.google.common.collect.ImmutableList<Root> =
                context.getDependency(PackagePathCodecDependencies::class.java).getPackageRoots()
            for (i in roots.indices) {
                val root: Root = roots.get(i)
                if (root.contains(resolvedPath)) {
                    val relativePath: PathFragment? = root.relativize(resolvedPath)
                    context.serializeLeaf(relativePath, pathFragmentCodec(), codedOut)
                    codedOut.write(i.toByte())
                    return
                }
            }
            throw SerializationException(resolvedPath.toString() + " is not under any package roots: " + roots)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserialize(
            context: DeserializationContext, codedIn: CodedInputStream
        ): ResolvedSymlinkArtifactValue {
            val delegate: FileArtifactValue = context.deserialize(codedIn)
            val relativePath: PathFragment? = context.deserializeLeaf(codedIn, pathFragmentCodec())
            val rootIndex: Int = codedIn.readRawByte().toInt()
            val root: Root =
                context
                    .getDependency(PackagePathCodecDependencies::class.java)
                    .getPackageRoots()
                    .get(rootIndex)
            val resolvedPath: PathFragment = root.getRelative(relativePath).asFragment()
            return ResolvedSymlinkArtifactValue(delegate, resolvedPath)
        }
    }

    /**
     * Metadata for a symlink that is not to be resolved.
     * 
     * 
     * Unlike [ResolvedSymlinkArtifactValue], only the textual contents of the symlink matter
     * for invalidation purposes.
     */
    private class UnresolvedSymlinkArtifactValue(symlink: Path) : FileArtifactValue() {
        private val symlinkTarget: String?
        private val digest: ByteArray

        init {
            val symlinkTarget: String? = symlink.readSymbolicLink().getPathString()

            val digest: ByteArray =
                symlink
                    .getFileSystem()
                    .getDigestFunction()
                    .getHashFunction()
                    .hashString(symlinkTarget, java.nio.charset.StandardCharsets.ISO_8859_1)
                    .asBytes()

            // We need to be able to tell the difference between a symlink and a file containing the same
            // text. So we transform the digest a bit. This works because if one wants to craft a file
            // with the same digest as a symlink, one would need to mount a preimage attack on the digest
            // function (this would be different if we tweaked the data before applying the hash function)
            digest[0] = (digest[0].toInt() xor 0xff).toByte()

            this.symlinkTarget = symlinkTarget
            this.digest = digest
        }

        override fun getUnresolvedSymlinkTarget(): String? {
            return symlinkTarget
        }

        override fun getType(): FileStateType {
            return FileStateType.SYMLINK
        }

        override fun getDigest(): ByteArray {
            return digest
        }

        override fun getSize(): Long {
            return 0
        }

        override fun getModifiedTime(): Long {
            throw java.lang.IllegalStateException()
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is UnresolvedSymlinkArtifactValue) {
                return false
            }
            val that = o
            return digest.contentEquals(that.digest)
        }

        override fun hashCode(): Int {
            return digest.contentHashCode()
        }

        override fun wasModifiedSinceDigest(path: Path): Boolean {
            try {
                val newMetadata: FileArtifactValue = Companion.createForUnresolvedSymlink(path)
                return !digest.contentEquals(newMetadata.digest)
            } catch (e: IOException) {
                return true
            }
        }
    }

    /** Metadata for files whose contents are available in memory.  */
    @AutoCodec
    @VisibleForSerialization
    class InlineFileArtifactValue internal constructor(data: ByteArray?, digest: ByteArray?) : FileArtifactValue() {
        private val data: ByteArray
        private val digest: ByteArray

        init {
            this.data = com.google.common.base.Preconditions.checkNotNull<ByteArray?>(data)
            this.digest = com.google.common.base.Preconditions.checkNotNull<ByteArray?>(digest)
        }

        override fun isInline(): Boolean {
            return true
        }

        override fun getInputStream(): ByteArrayInputStream {
            return ByteArrayInputStream(data)
        }

        override fun getType(): FileStateType {
            return FileStateType.REGULAR_FILE
        }

        override fun getDigest(): ByteArray {
            return digest
        }

        override fun getSize(): Long {
            return data.size.toLong()
        }

        override fun getModifiedTime(): Long {
            throw java.lang.UnsupportedOperationException()
        }

        override fun wasModifiedSinceDigest(path: Path?): Boolean {
            throw java.lang.UnsupportedOperationException()
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is InlineFileArtifactValue) {
                return false
            }
            return digest.contentEquals(o.digest)
        }

        override fun hashCode(): Int {
            return digest.contentHashCode()
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("digest", bytesToString(digest))
                .add("size", getSize())
                .toString()
        }
    }

    /**
     * Metadata for an artifact obtained via a path proxy.
     * 
     * 
     * This is used to inform action file systems which would otherwise not read local disk that
     * the source of truth for an output is at [.getTargetPath].
     */
    class ProxyFileArtifactValue(delegate: FileArtifactValue?, path: Path?) : FileArtifactValue() {
        private val delegate: FileArtifactValue
        private val path: Path

        init {
            this.delegate = com.google.common.base.Preconditions.checkNotNull<FileArtifactValue>(delegate)
            this.path = com.google.common.base.Preconditions.checkNotNull<Path>(path)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is ProxyFileArtifactValue) {
                return false
            }
            return this.delegate == o.delegate && this.path.equals(o.path)
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(delegate, path)
        }

        fun getTargetPath(): Path {
            return path
        }

        override fun getType(): FileStateType? {
            return delegate.type
        }

        override fun getDigest(): ByteArray? {
            return delegate.digest
        }

        override fun getContentsProxy(): FileContentsProxy? {
            return delegate.getContentsProxy()
        }

        override fun setContentsProxy(proxy: FileContentsProxy?) {
            delegate.setContentsProxy(proxy)
        }

        override fun getSize(): Long {
            return delegate.size
        }

        override fun getModifiedTime(): Long {
            return delegate.getModifiedTime()
        }

        @Throws(IOException::class)
        override fun wasModifiedSinceDigest(path: Path?): Boolean {
            return delegate.wasModifiedSinceDigest(path)
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("delegate", delegate)
                .add("path", path)
                .toString()
        }
    }

    private class SingletonMarkerValue : FileArtifactValue(), Singleton {
        override fun getType(): FileStateType {
            return FileStateType.NONEXISTENT
        }

        override fun getDigest(): ByteArray? {
            return null
        }

        override fun getSize(): Long {
            return 0
        }

        override fun getModifiedTime(): Long {
            return 0
        }

        override fun wasModifiedSinceDigest(path: Path?): Boolean {
            return false
        }

        override fun getValueFingerprint(): ByteArray {
            return FINGERPRINT
        }

        override fun toString(): String {
            return "singleton marker artifact value (" + hashCode() + ")"
        }

        companion object {
            private val FINGERPRINT = byteArrayOf(0x10)
        }
    }

    /** [FileArtifactValue] subclass for artifacts with constant metadata. A singleton.  */
    class ConstantMetadataValue private constructor() : FileArtifactValue(), Singleton {
        override fun getType(): FileStateType {
            return FileStateType.REGULAR_FILE
        }

        override fun getDigest(): ByteArray {
            return DIGEST
        }

        override fun getSize(): Long {
            return 0
        }

        override fun getModifiedTime(): Long {
            return -1
        }

        override fun wasModifiedSinceDigest(path: Path?): Boolean {
            throw java.lang.UnsupportedOperationException(
                "ConstantMetadataValue doesn't support wasModifiedSinceDigest " + path
            )
        }

        companion object {
            val INSTANCE: ConstantMetadataValue = ConstantMetadataValue()

            // This needs to not be of length 0, so it is distinguishable from a missing digest when written
            // into a Fingerprint.
            private val DIGEST = ByteArray(1)
        }
    }

    companion object {
        /**
         * Metadata for runfiles trees.
         * 
         * 
         * This should really be more nuanced so that runfiles trees don't need to be special-cased in
         * the local action cache, but it works well enough. The only downsides are that we don't detect
         * when someone changed a runfiles tree like we do for other output artifacts and a number of
         * extra branches.
         * 
         * 
         * In Skyframe, we check whether a runfiles tree changed based on [ ], which does contain data about its contents.
         */
        @SerializationConstant
        val RUNFILES_TREE_MARKER: FileArtifactValue = SingletonMarkerValue()

        /** Data that marks that a file is not present on the filesystem.  */
        @SerializationConstant
        val MISSING_FILE_MARKER: FileArtifactValue = SingletonMarkerValue()

        @Throws(IOException::class)
        fun createForSourceArtifact(
            artifact: Artifact, fileValue: FileValue, xattrProvider: XattrProvider?
        ): FileArtifactValue {
            // Artifacts with known generating actions should obtain the derived artifact's SkyValue
            // from the generating action, instead.
            com.google.common.base.Preconditions.checkState(!artifact.hasKnownGeneratingAction())
            com.google.common.base.Preconditions.checkState(!artifact.isConstantMetadata())
            val isFile: Boolean = fileValue.isFile()
            return create(
                artifact.getPath(),
                isFile,
                if (isFile) fileValue.getSize() else 0,
                if (isFile) fileValue.realFileStateValue().getContentsProxy() else null,
                if (isFile) fileValue.getDigest() else null,
                xattrProvider
            )
        }

        fun createFromInjectedDigest(
            metadata: FileArtifactValue, digest: ByteArray?
        ): FileArtifactValue {
            return createForNormalFile(digest, metadata.getContentsProxy(), metadata.size)
        }

        @com.google.common.annotations.VisibleForTesting
        @Throws(IOException::class)
        fun createForTesting(artifact: Artifact): FileArtifactValue? {
            return createForTesting(artifact.getPath())
        }

        @com.google.common.annotations.VisibleForTesting
        @Throws(IOException::class)
        fun createForTesting(path: Path): FileArtifactValue {
            // Caution: there's a race condition between stating the file and computing the digest. We need
            // to stat first, since we're using the stat to detect changes. We follow symlinks here to be
            // consistent with getDigest.
            return createFromStat(path, path.stat(Symlinks.FOLLOW), SyscallCache.NO_CACHE)
        }

        @Throws(IOException::class)
        fun createFromStat(
            path: Path, stat: FileStatus, xattrProvider: XattrProvider?
        ): FileArtifactValue {
            return create(
                path,
                stat.isFile(),
                stat.getSize(),
                FileContentsProxy.Companion.create(stat),
                if (stat is FileStatusWithDigest) stat.getDigest() else null,
                xattrProvider
            )
        }

        @Throws(IOException::class)
        private fun create(
            path: Path,
            isFile: Boolean,
            size: Long,
            proxy: FileContentsProxy?,
            digest: ByteArray?,
            xattrProvider: XattrProvider?
        ): FileArtifactValue {
            var digest = digest
            if (!isFile) {
                // In this case, we need to store the mtime because the action cache uses mtime for
                // directories to determine if this artifact has changed. We want this code path to go away
                // somehow.
                return DirectoryArtifactValue(path.getLastModifiedTime())
            }
            if (digest == null) {
                digest = DigestUtils.getDigestWithManualFallback(path, xattrProvider)
            }
            com.google.common.base.Preconditions.checkState(digest != null, path)
            return createForNormalFile(digest, proxy, size)
        }

        fun createForVirtualActionInput(digest: ByteArray?, size: Long): FileArtifactValue {
            return RegularFileArtifactValue(digest,  /* proxy= */null, size)
        }

        @Throws(IOException::class)
        fun createForUnresolvedSymlink(artifact: Artifact): FileArtifactValue? {
            com.google.common.base.Preconditions.checkArgument(artifact.isSymlink())
            return createForUnresolvedSymlink(artifact.getPath())
        }

        @Throws(IOException::class)
        fun createForUnresolvedSymlink(symlink: Path): FileArtifactValue {
            return UnresolvedSymlinkArtifactValue(symlink)
        }

        fun createForNormalFile(
            digest: ByteArray?, proxy: FileContentsProxy?, size: Long
        ): FileArtifactValue {
            return RegularFileArtifactValue(digest, proxy, size)
        }

        /**
         * Create a FileArtifactValue using the [Path] and size. FileArtifactValue#create will
         * handle getting the digest using the Path and size values.
         */
        @Throws(IOException::class)
        fun createForNormalFileUsingPath(
            path: Path, size: Long, xattrProvider: XattrProvider?
        ): FileArtifactValue {
            return create(
                path,  /* isFile= */true, size,  /* proxy= */null,  /* digest= */null, xattrProvider
            )
        }

        fun createForDirectoryWithHash(digest: ByteArray?): FileArtifactValue {
            return HashedDirectoryArtifactValue(digest)
        }

        fun createForDirectoryWithMtime(mtime: Long): FileArtifactValue {
            return DirectoryArtifactValue(mtime)
        }

        fun createForInlineFile(
            bytes: ByteArray,
            hashFunction: com.google.common.hash.HashFunction
        ): FileArtifactValue {
            return InlineFileArtifactValue(bytes, hashFunction.hashBytes(bytes).asBytes())
        }

        /**
         * Prefer [.createForRemoteFileWithMaterializationData] if the remote file may be
         * materialized in the local filesystem at a later point as this overload doesn't support [ ][.setContentsProxy].
         */
        fun createForRemoteFile(digest: ByteArray?, size: Long, locationIndex: Int): FileArtifactValue {
            return RemoteFileArtifactValue(digest, size, locationIndex)
        }

        fun createForRemoteFileWithMaterializationData(
            digest: ByteArray?, size: Long, locationIndex: Int, expirationTime: Instant?
        ): FileArtifactValue {
            return RemoteFileArtifactValueWithMaterializationData(
                digest, size, locationIndex, expirationTime
            )
        }

        fun createFromExistingWithResolvedPath(
            delegate: FileArtifactValue, resolvedPath: PathFragment
        ): FileArtifactValue {
            return ResolvedSymlinkArtifactValue(delegate, resolvedPath)
        }

        /**
         * Creates a FileArtifactValue used as a 'proxy' input for a [RunfilesArtifactValue]. These
         * are used in [ActionCacheChecker].
         */
        fun createRunfilesProxy(digest: ByteArray?): FileArtifactValue {
            com.google.common.base.Preconditions.checkNotNull<ByteArray?>(digest)
            return RunfilesProxyArtifactValue(digest)
        }

        private fun bytesToString(bytes: ByteArray?): String {
            return if (bytes == null) "null" else "0x" + com.google.common.io.BaseEncoding.base16().omitPadding()
                .encode(bytes)
        }
    }
}
