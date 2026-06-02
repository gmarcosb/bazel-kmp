// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.HasDigest

/**
 * Collection of files found while recursively traversing a path.
 * 
 * 
 * The path may refer to files, symlinks or directories that may or may not exist.
 * 
 * 
 * Traversing a file or a symlink results in a single [ResolvedFile] corresponding to the
 * file or symlink.
 * 
 * 
 * Traversing a directory results in a collection of [ResolvedFile]s for all files and
 * symlinks under it, and in all of its subdirectories. The [TraversalRequest] can specify
 * whether to traverse source subdirectories that are packages (have BUILD files in them).
 * 
 * 
 * Traversing a symlink that points to a directory is the same as traversing a normal directory.
 * The paths in the result will not be resolved; the files will be listed under the symlink, as if
 * it was the actual directory they reside in.
 * 
 * 
 * Editing a file that is part of this traversal, or adding or removing a file in a directory
 * that is part of this traversal, will invalidate this [SkyValue]. This also applies to
 * directories that are symlinked to.
 */
class RecursiveFilesystemTraversalValue private constructor(
    resolvedRoot: java.util.Optional<ResolvedFile?>?,
    resolvedPaths: NestedSet<ResolvedFile?>?
) : SkyValue {
    /** The root of the traversal. May only be absent for the [.EMPTY] instance.  */
    private val resolvedRoot: java.util.Optional<ResolvedFile?>

    /** The transitive closure of [ResolvedFile]s.  */
    private val resolvedPaths: NestedSet<ResolvedFile?>

    init {
        this.resolvedRoot =
            com.google.common.base.Preconditions.checkNotNull<java.util.Optional<ResolvedFile?>>(resolvedRoot)
        this.resolvedPaths = com.google.common.base.Preconditions.checkNotNull<NestedSet<ResolvedFile?>>(resolvedPaths)
    }

    /** Returns the root of the traversal; absent only for the [.EMPTY] instance.  */
    fun getResolvedRoot(): java.util.Optional<ResolvedFile?> {
        return resolvedRoot
    }

    val transitiveFiles: NestedSet<ResolvedFile?>
        /**
         * Retrieves the set of [ResolvedFile]s that were found by this traversal.
         * 
         * 
         * The returned set may be empty if no files were found, or the ones found were to be
         * considered non-existent. Unless it's empty, the returned set always includes the
         * [resolved root][.getResolvedRoot].
         * 
         * 
         * The returned set also includes symlinks. If a symlink points to a directory, its contents
         * are also included in this set, and their path will start with the symlink's path, just like on
         * a usual Unix file system.
         */
        get() = resolvedPaths

    /** Type information about the filesystem entry residing at a path.  */
    internal enum class FileType {
        /** A regular file.  */
        FILE {
            override fun isFile(): Boolean {
                return true
            }

            override fun exists(): Boolean {
                return true
            }

            override fun toString(): String {
                return "<f>"
            }
        },

        /**
         * A symlink to a regular file.
         * 
         * 
         * The symlink may be direct (points to a non-symlink (here a file)) or it may be transitive
         * (points to a direct or transitive symlink).
         */
        SYMLINK_TO_FILE {
            override fun isFile(): Boolean {
                return true
            }

            override fun isSymlink(): Boolean {
                return true
            }

            override fun exists(): Boolean {
                return true
            }

            override fun toString(): String {
                return "<lf>"
            }
        },

        /** A directory.  */
        DIRECTORY {
            override fun isDirectory(): Boolean {
                return true
            }

            override fun exists(): Boolean {
                return true
            }

            override fun toString(): String {
                return "<d>"
            }
        },

        /**
         * A symlink to a directory.
         * 
         * 
         * The symlink may be direct (points to a non-symlink (here a directory)) or it may be
         * transitive (points to a direct or transitive symlink).
         */
        SYMLINK_TO_DIRECTORY {
            override fun isDirectory(): Boolean {
                return true
            }

            override fun isSymlink(): Boolean {
                return true
            }

            override fun exists(): Boolean {
                return true
            }

            override fun toString(): String {
                return "<ld>"
            }
        },

        /** A dangling symlink, i.e. one whose target is known not to exist.  */
        DANGLING_SYMLINK {
            override fun isFile(): Boolean {
                throw java.lang.UnsupportedOperationException()
            }

            override fun isDirectory(): Boolean {
                throw java.lang.UnsupportedOperationException()
            }

            override fun isSymlink(): Boolean {
                return true
            }

            override fun toString(): String {
                return "<l?>"
            }
        },

        /** A path that does not exist or should be ignored.  */
        NONEXISTENT {
            override fun toString(): String {
                return "<?>"
            }
        };

        open val isFile: Boolean
            get() = false

        open val isDirectory: Boolean
            get() = false

        open val isSymlink: Boolean
            get() = false

        open fun exists(): Boolean {
            return false
        }

        abstract override fun toString(): String
    }

    private class Symlink(linkName: RootedPath?, unresolvedLinkTarget: PathFragment?) {
        private val linkName: RootedPath
        private val unresolvedLinkTarget: PathFragment

        // The resolved link target is returned by ResolvedFile.getPath()
        init {
            this.linkName = com.google.common.base.Preconditions.checkNotNull<RootedPath>(linkName)
            this.unresolvedLinkTarget =
                com.google.common.base.Preconditions.checkNotNull<PathFragment>(unresolvedLinkTarget)
        }

        val nameInSymlinkTree: PathFragment?
            get() = linkName.getRootRelativePath()

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is Symlink) {
                return false
            }
            return linkName == obj.linkName && unresolvedLinkTarget == obj.unresolvedLinkTarget
        }

        override fun hashCode(): Int {
            return com.google.common.base.Objects.hashCode(linkName, unresolvedLinkTarget)
        }

        override fun toString(): String {
            return java.lang.String.format(
                "Symlink(link_name=%s, unresolved_target=%s)",
                linkName, unresolvedLinkTarget
            )
        }
    }

    private class RegularFile(path: RootedPath?, metadata: HasDigest?) : ResolvedFile {
        private val path: RootedPath
        private val metadata: HasDigest

        init {
            this.path = com.google.common.base.Preconditions.checkNotNull<RootedPath>(path)
            this.metadata = com.google.common.base.Preconditions.checkNotNull<HasDigest>(metadata)
        }

        override fun getType(): FileType {
            return com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.FILE
        }

        override fun getPath(): RootedPath {
            return path
        }

        override fun getMetadata(): HasDigest? {
            return metadata
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is RegularFile) {
                return false
            }
            return this.path == obj.path
                    && this.metadata.equals(obj.metadata)
        }

        override fun hashCode(): Int {
            return com.google.common.base.Objects.hashCode(path, metadata)
        }

        override fun toString(): String {
            return java.lang.String.format("RegularFile(path=%s -- %s)", path, metadata)
        }

        override fun getNameInSymlinkTree(): PathFragment? {
            return path.getRootRelativePath()
        }
    }

    private class Directory(path: RootedPath?) : ResolvedFile {
        private val path: RootedPath

        init {
            this.path = com.google.common.base.Preconditions.checkNotNull<RootedPath>(path)
        }

        override fun getType(): FileType {
            return com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.DIRECTORY
        }

        override fun getPath(): RootedPath {
            return path
        }

        override fun getMetadata(): HasDigest {
            return HasDigest.EMPTY
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is Directory) {
                return false
            }
            return this.path == obj.path
        }

        override fun hashCode(): Int {
            return path.hashCode()
        }

        override fun toString(): String {
            return java.lang.String.format("Directory(path=%s)", path)
        }

        override fun getNameInSymlinkTree(): PathFragment? {
            return path.getRootRelativePath()
        }
    }

    private class DanglingSymlink(linkNamePath: RootedPath?, linkTargetPath: PathFragment?, metadata: HasDigest?) :
        ResolvedFile {
        private val symlink: Symlink
        private val metadata: HasDigest

        init {
            this.symlink = Symlink(linkNamePath, linkTargetPath)
            this.metadata = com.google.common.base.Preconditions.checkNotNull<HasDigest>(metadata)
        }

        override fun getType(): FileType {
            return com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.DANGLING_SYMLINK
        }

        override fun getPath(): RootedPath? {
            return symlink.linkName
        }

        override fun getMetadata(): HasDigest {
            return metadata
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is DanglingSymlink) {
                return false
            }
            return this.metadata.equals(obj.metadata)
                    && this.symlink == obj.symlink
        }

        override fun hashCode(): Int {
            return com.google.common.base.Objects.hashCode(metadata, symlink)
        }

        override fun toString(): String {
            return java.lang.String.format("DanglingSymlink(%s)", symlink)
        }

        override fun getNameInSymlinkTree(): PathFragment? {
            return symlink.nameInSymlinkTree
        }
    }

    private class SymlinkToFile(
        targetPath: RootedPath?,
        linkNamePath: RootedPath?,
        linkTargetPath: PathFragment?,
        metadata: HasDigest?
    ) : ResolvedFile {
        private val path: RootedPath
        private val metadata: HasDigest
        private val symlink: Symlink

        init {
            this.path = com.google.common.base.Preconditions.checkNotNull<RootedPath>(targetPath)
            this.metadata = com.google.common.base.Preconditions.checkNotNull<HasDigest>(metadata)
            this.symlink = Symlink(linkNamePath, linkTargetPath)
        }

        override fun getType(): FileType {
            return com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.SYMLINK_TO_FILE
        }

        override fun getPath(): RootedPath {
            return symlink.linkName
        }

        override fun getMetadata(): HasDigest {
            return metadata
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is SymlinkToFile) {
                return false
            }
            return this.path == obj.path
                    && this.metadata.equals(obj.metadata)
                    && this.symlink == obj.symlink
        }

        override fun hashCode(): Int {
            return com.google.common.base.Objects.hashCode(path, metadata, symlink)
        }

        override fun toString(): String {
            return java.lang.String.format("SymlinkToFile(target=%s, %s)", path, symlink)
        }

        override fun getNameInSymlinkTree(): PathFragment? {
            return symlink.nameInSymlinkTree
        }
    }

    private class SymlinkToDirectory(
        targetPath: RootedPath?,
        linkNamePath: RootedPath?,
        linkValue: PathFragment?,
        metadata: HasDigest?
    ) : ResolvedFile {
        private val path: RootedPath
        private val metadata: HasDigest
        private val symlink: Symlink

        init {
            this.path = com.google.common.base.Preconditions.checkNotNull<RootedPath>(targetPath)
            this.metadata = com.google.common.base.Preconditions.checkNotNull<HasDigest>(metadata)
            this.symlink = Symlink(linkNamePath, linkValue)
        }

        override fun getType(): FileType {
            return com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.SYMLINK_TO_DIRECTORY
        }

        override fun getPath(): RootedPath {
            return symlink.linkName
        }

        override fun getMetadata(): HasDigest {
            return metadata
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is SymlinkToDirectory) {
                return false
            }
            return this.path == obj.path
                    && this.metadata.equals(obj.metadata)
                    && this.symlink == obj.symlink
        }

        override fun hashCode(): Int {
            return com.google.common.base.Objects.hashCode(path, metadata, symlink)
        }

        override fun toString(): String {
            return java.lang.String.format("SymlinkToDirectory(target=%s, %s)", path, symlink)
        }

        override fun getNameInSymlinkTree(): PathFragment? {
            return symlink.nameInSymlinkTree
        }
    }

    internal object ResolvedFileFactory {
        fun regularFile(path: RootedPath?, metadata: HasDigest?): ResolvedFile {
            return com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.RegularFile(path, metadata)
        }

        fun directory(path: RootedPath?): ResolvedFile {
            return com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.Directory(path)
        }

        fun symlinkToFile(
            targetPath: RootedPath?,
            linkNamePath: RootedPath?,
            linkTargetPath: PathFragment?,
            metadata: HasDigest?
        ): ResolvedFile {
            return SymlinkToFile(targetPath, linkNamePath, linkTargetPath, metadata)
        }

        fun symlinkToDirectory(
            targetPath: RootedPath?,
            linkNamePath: RootedPath?,
            linkValue: PathFragment?,
            metadata: HasDigest?
        ): ResolvedFile {
            return SymlinkToDirectory(targetPath, linkNamePath, linkValue, metadata)
        }

        fun danglingSymlink(linkNamePath: RootedPath?, linkValue: PathFragment): ResolvedFile {
            val digest: ByteArray =
                DigestHashFunction.SHA256
                    .getHashFunction()
                    .hashString(linkValue.getPathString(), java.nio.charset.StandardCharsets.ISO_8859_1)
                    .asBytes()
            // Ensure that the digest does not collide with that of a regular file.
            digest[0] = digest[0].toInt() xor 1
            return DanglingSymlink(linkNamePath, linkValue, ByteStringDigest(digest))
        }
    }

    /**
     * Path and type information about a single file or symlink.
     * 
     * 
     * The object stores things such as the absolute path of the file or symlink, its exact type
     * and, if it's a symlink, the resolved and unresolved link target paths.
     */
    interface ResolvedFile {
        /** Type of the entity under [.getPath].  */
        @kotlin.jvm.JvmField
        val type: FileType?

        /** Path of the file, directory or symlink.  */
        @kotlin.jvm.JvmField
        val path: RootedPath?

        /**
         * Return the best effort metadata about the target. Currently this will be a FileStateValue for
         * source targets. For generated targets we try to return a FileArtifactValue when possible, or
         * else this will be a Integer hashcode of the target.
         */
        @kotlin.jvm.JvmField
        val metadata: HasDigest?

        /**
         * Returns the path of the Fileset-output symlink relative to the output directory.
         * 
         * 
         * The path should contain the FilesetEntry-specific destination directory (if any) and
         * should have necessary prefixes stripped (if any).
         */
        @kotlin.jvm.JvmField
        val nameInSymlinkTree: PathFragment?
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is RecursiveFilesystemTraversalValue) {
            return false
        }
        return resolvedRoot == obj.resolvedRoot && resolvedPaths.equals(obj.resolvedPaths)
    }

    override fun hashCode(): Int {
        return com.google.common.base.Objects.hashCode(resolvedRoot, resolvedPaths)
    }

    companion object {
        val EMPTY: RecursiveFilesystemTraversalValue = RecursiveFilesystemTraversalValue(
            java.util.Optional.empty<ResolvedFile?>(), NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        )

        fun of(
            resolvedRoot: ResolvedFile,
            resolvedPaths: NestedSet<ResolvedFile?>
        ): RecursiveFilesystemTraversalValue? {
            if (resolvedPaths.isEmpty()) {
                return EMPTY
            } else {
                return RecursiveFilesystemTraversalValue(
                    java.util.Optional.of<ResolvedFile?>(resolvedRoot),
                    resolvedPaths
                )
            }
        }

        fun of(singleMember: ResolvedFile): RecursiveFilesystemTraversalValue {
            return RecursiveFilesystemTraversalValue(
                java.util.Optional.of<ResolvedFile?>(singleMember),
                NestedSetBuilder.create(Order.STABLE_ORDER, singleMember)
            )
        }
    }
}
