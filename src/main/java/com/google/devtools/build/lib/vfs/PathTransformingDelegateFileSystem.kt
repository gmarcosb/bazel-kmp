// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.vfs.DigestHashFunction
import com.google.devtools.build.lib.vfs.FileStatus
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.lib.vfs.SymlinkTargetType
import java.io.IOException
import java.nio.channels.SeekableByteChannel

/**
 * FileSystem implementation which delegates all operations to a provided instance with a
 * transformed path.
 * 
 * 
 * Please consider using [DelegateFileSystem] if you don't need to transform the paths.
 */
abstract class PathTransformingDelegateFileSystem
/**
 * Constructs an instance with no initial delegate [FileSystem].
 * 
 * 
 * [.setDelegateFs] must be called prior to any [FileSystem] operations.
 */
protected constructor(hashFunction: DigestHashFunction?) : com.google.devtools.build.lib.vfs.FileSystem(hashFunction) {
    private var delegateFs: com.google.devtools.build.lib.vfs.FileSystem? = null

    /** Constructs an instance with an initial delegate [FileSystem].  */
    protected constructor(delegateFs: com.google.devtools.build.lib.vfs.FileSystem) : this(delegateFs.getDigestFunction()) {
        setDelegateFs(delegateFs)
    }

    fun getDelegateFs(): com.google.devtools.build.lib.vfs.FileSystem {
        return com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.FileSystem>(
            delegateFs
        )
    }

    fun setDelegateFs(delegateFs: com.google.devtools.build.lib.vfs.FileSystem) {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.FileSystem?>(delegateFs)
        com.google.common.base.Preconditions.checkArgument(
            delegateFs.getDigestFunction() == getDigestFunction(),
            "Digest function mismatch: initialized with %s, but delegate %s has %s",
            getDigestFunction(),
            delegateFs,
            delegateFs.getDigestFunction()
        )
        this.delegateFs = delegateFs
        onDelegateFsChange(delegateFs)
    }

    @com.google.errorprone.annotations.ForOverride
    protected fun onDelegateFsChange(delegateFs: com.google.devtools.build.lib.vfs.FileSystem?) {
    }

    override fun supportsModifications(path: PathFragment?): Boolean {
        return delegateFs.supportsModifications(toDelegatePath(path))
    }

    override fun supportsSymbolicLinksNatively(path: PathFragment?): Boolean {
        return delegateFs.supportsSymbolicLinksNatively(toDelegatePath(path))
    }

    override fun supportsHardLinksNatively(path: PathFragment?): Boolean {
        return delegateFs.supportsHardLinksNatively(toDelegatePath(path))
    }

    override fun mayBeCaseOrNormalizationInsensitive(): Boolean {
        return delegateFs.mayBeCaseOrNormalizationInsensitive()
    }

    @Throws(IOException::class)
    override fun createDirectory(path: PathFragment?): Boolean {
        return delegateFs.createDirectory(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun createDirectoryAndParents(path: PathFragment?) {
        delegateFs.createDirectoryAndParents(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun getFileSize(path: PathFragment?, followSymlinks: Boolean): Long {
        return delegateFs.getFileSize(toDelegatePath(path), followSymlinks)
    }

    @Throws(IOException::class)
    override fun delete(path: PathFragment?): Boolean {
        return delegateFs.delete(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun getLastModifiedTime(path: PathFragment?, followSymlinks: Boolean): Long {
        return delegateFs.getLastModifiedTime(toDelegatePath(path), followSymlinks)
    }

    @Throws(IOException::class)
    override fun setLastModifiedTime(path: PathFragment?, newTime: Long) {
        delegateFs.setLastModifiedTime(toDelegatePath(path), newTime)
    }

    override fun isSymbolicLink(path: PathFragment?): Boolean {
        return delegateFs.isSymbolicLink(toDelegatePath(path))
    }

    override fun isDirectory(path: PathFragment?, followSymlinks: Boolean): Boolean {
        return delegateFs.isDirectory(toDelegatePath(path), followSymlinks)
    }

    override fun isFile(path: PathFragment?, followSymlinks: Boolean): Boolean {
        return delegateFs.isFile(toDelegatePath(path), followSymlinks)
    }

    override fun isSpecialFile(path: PathFragment?, followSymlinks: Boolean): Boolean {
        return delegateFs.isSpecialFile(toDelegatePath(path), followSymlinks)
    }

    @Throws(IOException::class)
    override fun createSymbolicLink(
        linkPath: PathFragment?, targetFragment: PathFragment?, type: SymlinkTargetType?
    ) {
        delegateFs.createSymbolicLink(toDelegatePath(linkPath), targetFragment, type)
    }

    @Throws(IOException::class)
    override fun readSymbolicLink(path: PathFragment?): PathFragment? {
        return fromDelegatePath(delegateFs.readSymbolicLink(toDelegatePath(path)))
    }

    override fun exists(path: PathFragment?, followSymlinks: Boolean): Boolean {
        return delegateFs.exists(toDelegatePath(path), followSymlinks)
    }

    override fun exists(path: PathFragment?): Boolean {
        return delegateFs.exists(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun getDirectoryEntries(path: PathFragment?): MutableCollection<String?>? {
        return delegateFs.getDirectoryEntries(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun isReadable(path: PathFragment?): Boolean {
        return delegateFs.isReadable(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun setReadable(path: PathFragment?, readable: Boolean) {
        delegateFs.setReadable(toDelegatePath(path), readable)
    }

    @Throws(IOException::class)
    override fun isWritable(path: PathFragment?): Boolean {
        return delegateFs.isWritable(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun setWritable(path: PathFragment?, writable: Boolean) {
        delegateFs.setWritable(toDelegatePath(path), writable)
    }

    @Throws(IOException::class)
    override fun isExecutable(path: PathFragment?): Boolean {
        return delegateFs.isExecutable(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun setExecutable(path: PathFragment?, executable: Boolean) {
        delegateFs.setExecutable(toDelegatePath(path), executable)
    }

    @Throws(IOException::class)
    override fun getInputStream(path: PathFragment?): java.io.InputStream? {
        return delegateFs.getInputStream(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun createReadWriteByteChannel(path: PathFragment?): SeekableByteChannel? {
        return delegateFs.createReadWriteByteChannel(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun getOutputStream(path: PathFragment?, append: Boolean, internal: Boolean): java.io.OutputStream? {
        return delegateFs.getOutputStream(toDelegatePath(path), append, internal)
    }

    @Throws(IOException::class)
    override fun renameTo(sourcePath: PathFragment?, targetPath: PathFragment?) {
        delegateFs.renameTo(toDelegatePath(sourcePath), toDelegatePath(targetPath))
    }

    @Throws(IOException::class)
    override fun createFSDependentHardLink(linkPath: PathFragment?, originalPath: PathFragment?) {
        delegateFs.createFSDependentHardLink(toDelegatePath(linkPath), toDelegatePath(originalPath))
    }

    override fun getFileSystemType(path: PathFragment?): String? {
        return delegateFs.getFileSystemType(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun deleteTree(path: PathFragment?) {
        delegateFs.deleteTree(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun deleteTreesBelow(dir: PathFragment?) {
        delegateFs.deleteTreesBelow(toDelegatePath(dir))
    }

    @Throws(IOException::class)
    override fun getxattr(path: PathFragment?, name: String?, followSymlinks: Boolean): ByteArray? {
        return delegateFs.getxattr(toDelegatePath(path), name, followSymlinks)
    }

    @Throws(IOException::class)
    override fun getFastDigest(path: PathFragment?): ByteArray? {
        return delegateFs.getFastDigest(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun getDigest(path: PathFragment?): ByteArray? {
        return delegateFs.getDigest(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun resolveOneLink(path: PathFragment?): PathFragment? {
        return delegateFs.resolveOneLink(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun resolveSymbolicLinks(path: PathFragment?): com.google.devtools.build.lib.vfs.Path? {
        return getPath(
            fromDelegatePath(delegateFs.resolveSymbolicLinks(toDelegatePath(path)).asFragment())
        )
    }

    @Throws(IOException::class)
    override fun stat(path: PathFragment?, followSymlinks: Boolean): FileStatus? {
        return delegateFs.stat(toDelegatePath(path), followSymlinks)
    }

    override fun statNullable(path: PathFragment?, followSymlinks: Boolean): FileStatus? {
        return delegateFs.statNullable(toDelegatePath(path), followSymlinks)
    }

    @Throws(IOException::class)
    override fun statIfFound(path: PathFragment?, followSymlinks: Boolean): FileStatus? {
        return delegateFs.statIfFound(toDelegatePath(path), followSymlinks)
    }

    @Throws(IOException::class)
    override fun readSymbolicLinkUnchecked(path: PathFragment?): PathFragment? {
        return delegateFs.readSymbolicLinkUnchecked(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun readdir(
        path: PathFragment?,
        followSymlinks: Boolean
    ): MutableCollection<com.google.devtools.build.lib.vfs.Dirent?>? {
        return delegateFs.readdir(toDelegatePath(path), followSymlinks)
    }

    @Throws(IOException::class)
    override fun chmod(path: PathFragment?, mode: Int) {
        delegateFs.chmod(toDelegatePath(path), mode)
    }

    @Throws(IOException::class)
    override fun createHardLink(linkPath: PathFragment?, originalPath: PathFragment?) {
        delegateFs.createHardLink(toDelegatePath(linkPath), toDelegatePath(originalPath))
    }

    override fun prefetchPackageAsync(path: PathFragment?, maxDirs: Int) {
        delegateFs.prefetchPackageAsync(toDelegatePath(path), maxDirs)
    }

    override fun getIoFile(path: PathFragment?): java.io.File? {
        return delegateFs.getIoFile(toDelegatePath(path))
    }

    override fun getNioPath(path: PathFragment?): java.nio.file.Path? {
        return delegateFs.getNioPath(toDelegatePath(path))
    }

    @Throws(IOException::class)
    override fun createTempDirectory(parent: PathFragment?, prefix: String?): PathFragment? {
        return delegateFs.createTempDirectory(toDelegatePath(parent), prefix)
    }

    /** Transform original path to a different one to be used with the `delegateFs`.  */
    protected abstract fun toDelegatePath(path: PathFragment?): PathFragment?

    /**
     * Transform a path from one to be used with `delegateFs` to original one.
     * 
     * 
     * We expect that for each `path`: `fromDelegatePath(toDelegatePath(path)).equals(path)`.
     */
    protected abstract fun fromDelegatePath(delegatePath: PathFragment?): PathFragment?
}
