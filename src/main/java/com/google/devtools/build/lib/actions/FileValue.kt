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

import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable

/**
 * A value that corresponds to a file (or directory or symlink or non-existent file), fully
 * accounting for symlinks (e.g. proper dependencies on ancestor symlinks so as to be incrementally
 * correct). Anything in Skyframe that cares about the fully resolved path of a file (e.g. anything
 * that cares about the contents of a file) should have a dependency on the corresponding [ ].
 * 
 * 
 * Note that the existence of a file value does not imply that the file exists on the filesystem.
 * File values for missing files will be created on purpose in order to facilitate incremental
 * builds in the case those files have reappeared.
 * 
 * 
 * This interface encapsulates the relevant metadata for a file, although not the contents. Note
 * that since a FileValue doesn't necessarily store its corresponding SkyKey, it's possible for the
 * FileValues for two different paths to be the same.
 * 
 * 
 * This should not be used for build outputs; use [ArtifactSkyKey] to create keys for
 * those.
 */
@Immutable
@ThreadSafe
interface FileValue : SkyValue {
    fun exists(): Boolean {
        return realFileStateValue().getType() != FileStateType.NONEXISTENT
    }

    /** Returns true if the original path is a symlink; the target path can never be a symlink.  */
    fun isSymlink(): Boolean {
        return false
    }

    /**
     * Returns true if this value corresponds to a file or symlink to an existing regular or special
     * file. If so, its parent directory is guaranteed to exist.
     */
    fun isFile(): Boolean {
        return realFileStateValue().getType() == FileStateType.REGULAR_FILE
                || realFileStateValue().getType() == FileStateType.SPECIAL_FILE
    }

    /**
     * Returns true if this value corresponds to a special file or symlink to a special file. If so,
     * its parent directory is guaranteed to exist.
     */
    fun isSpecialFile(): Boolean {
        return realFileStateValue().getType() == FileStateType.SPECIAL_FILE
    }

    /**
     * Returns true if the file is a directory or a symlink to an existing directory. If so, its
     * parent directory is guaranteed to exist.
     */
    fun isDirectory(): Boolean {
        return realFileStateValue().getType() == FileStateType.DIRECTORY
    }

    /**
     * If `!isFile() && exists()`, returns an ordered list of the [RootedPath]s that were
     * considered when determining `realRootedPath()`.
     * 
     * 
     * This information is used to detect unbounded symlink expansions.
     * 
     * 
     * As a memory optimization, we don't store this information when `isFile() || !exists()`
     * -- this information is only needed for resolving ancestors, and an existing file or a
     * non-existent directory has no descendants, by definition.
     */
    fun logicalChainDuringResolution(initialRootedPath: RootedPath?): com.google.common.collect.ImmutableList<RootedPath?>?

    /**
     * If a symlink pointing back to its own ancestor was encountered during the resolution of this
     * [FileValue], returns the path to it. Otherwise, returns null.
     */
    fun pathToUnboundedAncestorSymlinkExpansionChain(): com.google.common.collect.ImmutableList<RootedPath?>?

    /**
     * If a symlink pointing back to its own ancestor was encountered during the resolution of this
     * [FileValue], returns the symlinks in the cycle. Otherwise, returns null.
     * 
     * 
     * If you're about to attempt a recursive directory traversal starting at the original path,
     * you should first use this method to check if there's an unbounded ancestor symlink expansion.
     * If there is, you should either error out and give up, or you should perform the traversal
     * carefully (e.g. with a visited set) lest the traversal never terminate.
     */
    fun unboundedAncestorSymlinkExpansionChain(): com.google.common.collect.ImmutableList<RootedPath?>?

    /**
     * Returns the real rooted path of the file, taking ancestor symlinks into account. For example,
     * the rooted path ['root']/['a/b'] is really ['root']/['c/b'] if 'a' is a symlink to 'c'. Note
     * that ancestor symlinks outside the root boundary are not taken into consideration.
     */
    fun realRootedPath(initialRootedPath: RootedPath?): RootedPath?

    fun realFileStateValue(): FileStateValue?

    /**
     * Returns the unresolved link target if [.isSymlink].
     * 
     * 
     * This is useful if the caller wants to, for example, duplicate a relative symlink. An actual
     * example could be a build rule that copies a set of input files to the output directory, but
     * upon encountering symbolic links it can decide between copying or following them.
     */
    fun getUnresolvedLinkTarget(): PathFragment? {
        throw java.lang.IllegalStateException(this.toString())
    }

    fun getSize(): Long {
        com.google.common.base.Preconditions.checkState(isFile(), this)
        return realFileStateValue().getSize()
    }

    fun getDigest(): ByteArray? {
        com.google.common.base.Preconditions.checkState(isFile(), this)
        return realFileStateValue().getDigest()
    }

    /**
     * A [FileValue] for paths whose fully resolved path is the same as the requested path. For
     * example, this is the case for the path "foo/bar/baz" if neither 'foo' nor 'foo/bar' nor
     * 'foo/bar/baz' are symlinks.
     */
    class RegularFileValue : FileValue {
        override fun logicalChainDuringResolution(initialRootedPath: RootedPath): com.google.common.collect.ImmutableList<RootedPath?> {
            return com.google.common.collect.ImmutableList.of<RootedPath?>(initialRootedPath)
        }

        override fun pathToUnboundedAncestorSymlinkExpansionChain(): com.google.common.collect.ImmutableList<RootedPath?>? {
            return null
        }

        override fun unboundedAncestorSymlinkExpansionChain(): com.google.common.collect.ImmutableList<RootedPath?>? {
            return null
        }

        override fun realRootedPath(initialRootedPath: RootedPath?): RootedPath? {
            return initialRootedPath
        }
    }

    /**
     * A [FileValue] for a non-symlink but that had an ancestor symlink such that the resolution
     * required traversing a symlink chain caused by a symlink pointing to its own ancestor but which
     * eventually points to a real file.
     */
    class DifferentRealPathFileValueWithUnboundedAncestorExpansion
    @com.google.common.annotations.VisibleForTesting constructor(
        realRootedPath: RootedPath?,
        realFileStateValue: FileStateValue?,
        logicalChainDuringResolution: com.google.common.collect.ImmutableList<RootedPath?>,
        pathToUnboundedAncestorSymlinkExpansionChain: com.google.common.collect.ImmutableList<RootedPath?>,
        unboundedAncestorSymlinkExpansionChain: com.google.common.collect.ImmutableList<RootedPath?>
    ) : DifferentRealPathFileValueWithStoredChain(realRootedPath, realFileStateValue, logicalChainDuringResolution) {
        protected val pathToUnboundedAncestorSymlinkExpansionChain: com.google.common.collect.ImmutableList<RootedPath?>
        protected val unboundedAncestorSymlinkExpansionChain: com.google.common.collect.ImmutableList<RootedPath?>

        init {
            this.pathToUnboundedAncestorSymlinkExpansionChain =
                pathToUnboundedAncestorSymlinkExpansionChain
            this.unboundedAncestorSymlinkExpansionChain = unboundedAncestorSymlinkExpansionChain
        }

        override fun pathToUnboundedAncestorSymlinkExpansionChain(): com.google.common.collect.ImmutableList<RootedPath?> {
            return pathToUnboundedAncestorSymlinkExpansionChain
        }

        override fun unboundedAncestorSymlinkExpansionChain(): com.google.common.collect.ImmutableList<RootedPath?> {
            return unboundedAncestorSymlinkExpansionChain
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(
                realRootedPath,
                realFileStateValue,
                logicalChainDuringResolution,
                pathToUnboundedAncestorSymlinkExpansionChain,
                unboundedAncestorSymlinkExpansionChain
            )
        }

        override fun equals(obj: Any?): Boolean {
            if (obj == null) {
                return false
            }

            if (obj.javaClass != DifferentRealPathFileValueWithUnboundedAncestorExpansion::class.java) {
                return false
            }

            val other =
                obj as DifferentRealPathFileValueWithUnboundedAncestorExpansion
            return realRootedPath.equals(other.realRootedPath)
                    && realFileStateValue == other.realFileStateValue
                    && logicalChainDuringResolution == other.logicalChainDuringResolution
                    && pathToUnboundedAncestorSymlinkExpansionChain == other.pathToUnboundedAncestorSymlinkExpansionChain
                    && unboundedAncestorSymlinkExpansionChain == other.unboundedAncestorSymlinkExpansionChain
        }

        override fun toString(): String {
            return String.format(
                "symlink ancestor (real_path=%s, real_state=%s, chain=%s, path=%s, cycle=%s)",
                realRootedPath,
                realFileStateValue,
                logicalChainDuringResolution,
                pathToUnboundedAncestorSymlinkExpansionChain,
                unboundedAncestorSymlinkExpansionChain
            )
        }
    }

    /**
     * Implementation of [FileValue] for paths whose fully resolved path is different than the
     * requested path, but the path itself is not a symlink. For example, this is the case for the
     * path "foo/bar/baz" if at least one of {'foo', 'foo/bar'} is a symlink but 'foo/bar/baz' not.
     */
    @com.google.common.annotations.VisibleForTesting
    class DifferentRealPathFileValueWithStoredChain @com.google.common.annotations.VisibleForTesting constructor(
        realRootedPath: RootedPath?,
        realFileStateValue: FileStateValue?,
        logicalChainDuringResolution: com.google.common.collect.ImmutableList<RootedPath?>
    ) : FileValue {
        protected val realRootedPath: RootedPath
        protected val realFileStateValue: FileStateValue
        protected val logicalChainDuringResolution: com.google.common.collect.ImmutableList<RootedPath?>

        init {
            this.realRootedPath = com.google.common.base.Preconditions.checkNotNull<RootedPath>(realRootedPath)
            this.realFileStateValue =
                com.google.common.base.Preconditions.checkNotNull<FileStateValue>(realFileStateValue)
            this.logicalChainDuringResolution = logicalChainDuringResolution
        }

        override fun realRootedPath(initialRootedPath: RootedPath?): RootedPath {
            return realRootedPath
        }

        override fun realFileStateValue(): FileStateValue {
            return realFileStateValue
        }

        override fun logicalChainDuringResolution(initialRootedPath: RootedPath?): com.google.common.collect.ImmutableList<RootedPath?> {
            return logicalChainDuringResolution
        }

        override fun pathToUnboundedAncestorSymlinkExpansionChain(): com.google.common.collect.ImmutableList<RootedPath?>? {
            return null
        }

        override fun unboundedAncestorSymlinkExpansionChain(): com.google.common.collect.ImmutableList<RootedPath?>? {
            return null
        }

        override fun equals(obj: Any?): Boolean {
            if (obj == null) {
                return false
            }
            // Note that we can't use 'instanceof' because this class has a subclass.
            if (obj.javaClass != DifferentRealPathFileValueWithStoredChain::class.java) {
                return false
            }
            val other =
                obj as DifferentRealPathFileValueWithStoredChain
            return realRootedPath.equals(other.realRootedPath)
                    && realFileStateValue == other.realFileStateValue
                    && logicalChainDuringResolution == other.logicalChainDuringResolution
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(realRootedPath, realFileStateValue, logicalChainDuringResolution)
        }

        override fun toString(): String {
            return String.format(
                "symlink ancestor (real_path=%s, real_state=%s, chain=%s)",
                realRootedPath, realFileStateValue, logicalChainDuringResolution
            )
        }
    }

    /**
     * Same as [DifferentRealPathFileValueWithStoredChain], except without [ ][.logicalChainDuringResolution].
     */
    @com.google.common.annotations.VisibleForTesting
    class DifferentRealPathFileValueWithoutStoredChain @com.google.common.annotations.VisibleForTesting constructor(
        realRootedPath: RootedPath?,
        realFileStateValue: FileStateValue?
    ) : FileValue {
        protected val realRootedPath: RootedPath
        protected val realFileStateValue: FileStateValue

        init {
            this.realRootedPath = com.google.common.base.Preconditions.checkNotNull<RootedPath>(realRootedPath)
            this.realFileStateValue =
                com.google.common.base.Preconditions.checkNotNull<FileStateValue>(realFileStateValue)
        }

        override fun realRootedPath(initialRootedPath: RootedPath?): RootedPath {
            return realRootedPath
        }

        override fun realFileStateValue(): FileStateValue {
            return realFileStateValue
        }

        override fun logicalChainDuringResolution(initialRootedPath: RootedPath?): com.google.common.collect.ImmutableList<RootedPath?>? {
            throw java.lang.IllegalStateException(this.toString())
        }

        override fun pathToUnboundedAncestorSymlinkExpansionChain(): com.google.common.collect.ImmutableList<RootedPath?>? {
            return null
        }

        override fun unboundedAncestorSymlinkExpansionChain(): com.google.common.collect.ImmutableList<RootedPath?>? {
            return null
        }

        override fun equals(obj: Any?): Boolean {
            if (obj == null) {
                return false
            }
            // Note that we can't use 'instanceof' because this class has a subclass.
            if (obj.javaClass != DifferentRealPathFileValueWithoutStoredChain::class.java) {
                return false
            }
            val other =
                obj as DifferentRealPathFileValueWithoutStoredChain
            return realRootedPath.equals(other.realRootedPath)
                    && realFileStateValue == other.realFileStateValue
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(realRootedPath, realFileStateValue)
        }

        override fun toString(): String {
            return String.format(
                "symlink ancestor (real_path=%s, real_state=%s)", realRootedPath, realFileStateValue
            )
        }
    }

    /**
     * A [FileValue] for a symlink whose resolution required traversing a symlink chain caused
     * by a symlink pointing to its own ancestor and which eventually points to a symlink.
     */
    @com.google.common.annotations.VisibleForTesting
    class SymlinkFileValueWithUnboundedAncestorExpansion
    @com.google.common.annotations.VisibleForTesting constructor(
        realRootedPath: RootedPath?,
        realFileStateValue: FileStateValue?,
        logicalChainDuringResolution: com.google.common.collect.ImmutableList<RootedPath?>,
        linkTarget: PathFragment,
        pathToUnboundedAncestorSymlinkExpansionChain: com.google.common.collect.ImmutableList<RootedPath?>,
        unboundedAncestorSymlinkExpansionChain: com.google.common.collect.ImmutableList<RootedPath?>
    ) : SymlinkFileValueWithStoredChain(realRootedPath, realFileStateValue, logicalChainDuringResolution, linkTarget) {
        private val pathToUnboundedAncestorSymlinkExpansionChain: com.google.common.collect.ImmutableList<RootedPath?>
        private val unboundedAncestorSymlinkExpansionChain: com.google.common.collect.ImmutableList<RootedPath?>

        init {
            this.pathToUnboundedAncestorSymlinkExpansionChain =
                pathToUnboundedAncestorSymlinkExpansionChain
            this.unboundedAncestorSymlinkExpansionChain = unboundedAncestorSymlinkExpansionChain
        }

        override fun pathToUnboundedAncestorSymlinkExpansionChain(): com.google.common.collect.ImmutableList<RootedPath?> {
            return pathToUnboundedAncestorSymlinkExpansionChain
        }

        override fun unboundedAncestorSymlinkExpansionChain(): com.google.common.collect.ImmutableList<RootedPath?> {
            return unboundedAncestorSymlinkExpansionChain
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(
                realRootedPath,
                realFileStateValue,
                logicalChainDuringResolution,
                linkTarget,
                pathToUnboundedAncestorSymlinkExpansionChain,
                unboundedAncestorSymlinkExpansionChain
            )
        }

        override fun equals(obj: Any?): Boolean {
            if (obj == null) {
                return false
            }

            if (obj.javaClass != SymlinkFileValueWithUnboundedAncestorExpansion::class.java) {
                return false
            }

            val other =
                obj as SymlinkFileValueWithUnboundedAncestorExpansion
            return realRootedPath.equals(other.realRootedPath)
                    && realFileStateValue == other.realFileStateValue
                    && logicalChainDuringResolution == other.logicalChainDuringResolution
                    && linkTarget.equals(other.linkTarget)
                    && pathToUnboundedAncestorSymlinkExpansionChain == other.pathToUnboundedAncestorSymlinkExpansionChain
                    && unboundedAncestorSymlinkExpansionChain == other.unboundedAncestorSymlinkExpansionChain
        }

        override fun toString(): String {
            return String.format(
                "symlink ancestor (real_path=%s, real_state=%s, target=%s, chain=%s, path=%s, cycle=%s)",
                realRootedPath,
                realFileStateValue,
                linkTarget,
                logicalChainDuringResolution,
                pathToUnboundedAncestorSymlinkExpansionChain,
                unboundedAncestorSymlinkExpansionChain
            )
        }
    }

    /** Implementation of [FileValue] for paths that are themselves symlinks.  */
    @com.google.common.annotations.VisibleForTesting
    class SymlinkFileValueWithStoredChain @com.google.common.annotations.VisibleForTesting constructor(
        realRootedPath: RootedPath?,
        realFileStateValue: FileStateValue?,
        logicalChainDuringResolution: com.google.common.collect.ImmutableList<RootedPath?>,
        linkTarget: PathFragment
    ) : DifferentRealPathFileValueWithStoredChain(realRootedPath, realFileStateValue, logicalChainDuringResolution) {
        protected val linkTarget: PathFragment

        init {
            this.linkTarget = linkTarget
        }

        override fun isSymlink(): Boolean {
            return true
        }

        override fun getUnresolvedLinkTarget(): PathFragment {
            return linkTarget
        }

        override fun equals(obj: Any?): Boolean {
            if (obj == null) {
                return false
            }
            if (obj !is SymlinkFileValueWithStoredChain) {
                return false
            }
            return realRootedPath.equals(obj.realRootedPath)
                    && realFileStateValue == obj.realFileStateValue
                    && logicalChainDuringResolution == obj.logicalChainDuringResolution
                    && linkTarget.equals(obj.linkTarget)
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(
                realRootedPath, realFileStateValue, logicalChainDuringResolution, linkTarget
            )
        }

        override fun toString(): String {
            return String.format(
                "symlink (real_path=%s, real_state=%s, link_value=%s, chain=%s)",
                realRootedPath, realFileStateValue, linkTarget, logicalChainDuringResolution
            )
        }
    }

    /**
     * Same as [SymlinkFileValueWithStoredChain], except without [ ][.logicalChainDuringResolution].
     */
    @com.google.common.annotations.VisibleForTesting
    class SymlinkFileValueWithoutStoredChain
    @com.google.common.annotations.VisibleForTesting constructor(
        realRootedPath: RootedPath?,
        realFileStateValue: FileStateValue?,
        linkTarget: PathFragment
    ) : DifferentRealPathFileValueWithoutStoredChain(realRootedPath, realFileStateValue) {
        private val linkTarget: PathFragment

        init {
            this.linkTarget = linkTarget
        }

        override fun isSymlink(): Boolean {
            return true
        }

        override fun getUnresolvedLinkTarget(): PathFragment {
            return linkTarget
        }

        override fun equals(obj: Any?): Boolean {
            if (obj == null) {
                return false
            }
            if (obj !is SymlinkFileValueWithoutStoredChain) {
                return false
            }
            return realRootedPath.equals(obj.realRootedPath)
                    && realFileStateValue == obj.realFileStateValue
                    && linkTarget.equals(obj.linkTarget)
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(realRootedPath, realFileStateValue, linkTarget)
        }

        override fun toString(): String {
            return String.format(
                "symlink (real_path=%s, real_state=%s, link_value=%s)",
                realRootedPath, realFileStateValue, linkTarget
            )
        }
    }

    companion object {
        /** Returns a key for building a file value for the given root-relative path.  */
        @ThreadSafe
        fun key(rootedPath: RootedPath?): FileKey {
            return FileKey.create(rootedPath)
        }

        /**
         * Only intended to be used by [com.google.devtools.build.lib.skyframe.FileFunction]. Should
         * not be used for symlink cycles.
         */
        fun value(
            logicalChainDuringResolution: com.google.common.collect.ImmutableList<RootedPath?>,
            pathToUnboundedAncestorSymlinkExpansionChain: com.google.common.collect.ImmutableList<RootedPath?>?,
            unboundedAncestorSymlinkExpansionChain: com.google.common.collect.ImmutableList<RootedPath?>,
            originalRootedPath: RootedPath,
            fileStateValueFromAncestors: FileStateValue,
            realRootedPath: RootedPath?,
            realFileStateValue: FileStateValue
        ): FileValue {
            if (originalRootedPath.equals(realRootedPath)) {
                com.google.common.base.Preconditions.checkState(
                    fileStateValueFromAncestors.getType() != FileStateType.SYMLINK,
                    "originalRootedPath: %s, fileStateValueFromAncestors: %s, "
                            + "realRootedPath: %s, fileStateValueFromAncestors: %s",
                    originalRootedPath,
                    fileStateValueFromAncestors,
                    realRootedPath,
                    realFileStateValue
                )
                com.google.common.base.Preconditions.checkState(
                    !realFileStateValue.getType().exists() || realFileStateValue.getType().isFile()
                            || com.google.common.collect.Iterables.getOnlyElement<RootedPath?>(
                        logicalChainDuringResolution
                    ).equals(originalRootedPath),
                    "logicalChainDuringResolution: %s, originalRootedPath: %s",
                    logicalChainDuringResolution,
                    originalRootedPath
                )
                return fileStateValueFromAncestors
            }

            val shouldStoreChain =
                when (realFileStateValue.getType()) {
                    FileStateType.REGULAR_FILE, FileStateType.SPECIAL_FILE, FileStateType.NONEXISTENT -> false
                    FileStateType.SYMLINK, FileStateType.DIRECTORY -> true
                }

            if (fileStateValueFromAncestors.getType() == FileStateType.SYMLINK) {
                val symlinkTarget: PathFragment = fileStateValueFromAncestors.getSymlinkTarget()
                if (pathToUnboundedAncestorSymlinkExpansionChain != null) {
                    return SymlinkFileValueWithUnboundedAncestorExpansion(
                        realRootedPath,
                        realFileStateValue,
                        logicalChainDuringResolution,
                        symlinkTarget,
                        pathToUnboundedAncestorSymlinkExpansionChain,
                        unboundedAncestorSymlinkExpansionChain
                    )
                } else if (shouldStoreChain) {
                    return SymlinkFileValueWithStoredChain(
                        realRootedPath, realFileStateValue, logicalChainDuringResolution, symlinkTarget
                    )
                } else {
                    return SymlinkFileValueWithoutStoredChain(
                        realRootedPath, realFileStateValue, symlinkTarget
                    )
                }
            } else {
                if (pathToUnboundedAncestorSymlinkExpansionChain != null) {
                    return DifferentRealPathFileValueWithUnboundedAncestorExpansion(
                        realRootedPath,
                        realFileStateValue,
                        logicalChainDuringResolution,
                        pathToUnboundedAncestorSymlinkExpansionChain,
                        unboundedAncestorSymlinkExpansionChain
                    )
                } else if (shouldStoreChain) {
                    return DifferentRealPathFileValueWithStoredChain(
                        realRootedPath, realFileStateValue, logicalChainDuringResolution
                    )
                } else {
                    return DifferentRealPathFileValueWithoutStoredChain(realRootedPath, realFileStateValue)
                }
            }
        }
    }
}
