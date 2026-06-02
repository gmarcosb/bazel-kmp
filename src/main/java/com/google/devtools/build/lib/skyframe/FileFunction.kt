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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.FileStateType

/**
 * A [SkyFunction] for [FileValue]s.
 * 
 * 
 * Most of the complexity in the implementation results from wanting incremental correctness in
 * the presence of symlinks, esp. ancestor directory symlinks.
 * 
 * 
 * For an overview of the problem space and our approach, see the https://youtu.be/EoYdWmMcqDs
 * talk from BazelCon 2019 (slides:
 * https://docs.google.com/presentation/d/e/2PACX-1vQWq1DUhl92dDs_okNxM7Qy9zX72tp7hMsGosGxmjhBLZ5e02IJf9dySK_6lEU2j6u_NOEaUCQGxEFh/pub).
 * [2024] N.B. The general idea of that talk is still right, but as of cl/334982640 aka commit
 * 7598bc6 on GitHub (Oct 2020), we no longer unconditionally error out when encountering an
 * unbounded ancestor expansion and instead leave it to consumers to decide what to do. A consumer
 * that wants to do a recursive directory traversal starting from the path will probably want to
 * error out, while a consumer that just wants metadata from the path probably doesn't care.
 */
class FileFunction(pkgLocator: AtomicReference<PathPackageLocator?>, directories: BlazeDirectories) : SkyFunction {
    private val pkgLocator: AtomicReference<PathPackageLocator?>
    private val immutablePaths: com.google.common.collect.ImmutableList<Root?>

    init {
        this.pkgLocator = pkgLocator
        this.immutablePaths =
            com.google.common.collect.ImmutableList.of<Root?>(
                Root.fromPath(directories.getOutputBase()),
                Root.fromPath(directories.getInstallBase())
            )
    }

    private class SymlinkResolutionState {
        // Suppose we have a path p. One of the goals of FileFunction is to resolve the "real path", if
        // any, of p. The basic algorithm is to use the fully resolved path of p's parent directory to
        // determine the fully resolved path of p. This is complicated when symlinks are involved, and
        // is especially complicated when ancestor directory symlinks are involved.
        //
        // Since FileStateValues are the roots of invalidation, care has to be taken to ensuring we
        // declare the proper FileStateValue deps. As a concrete example, let p = a/b and imagine (i) a
        // is a direct symlink to c and also (ii) c/b is an existing file. Among other direct deps, we
        // want to have a direct dep on FileStateValue(c/b), since that's the node that will be changed
        // if the actual contents of a/b (aka c/b) changes. To rephrase: a dep on FileStateValue(a/b)
        // won't do anything productive since that path will never be in the Skyframe diff.
        //
        // In the course of resolving the real path of p, there will be a logical chain of paths we
        // consider. Going with the example from above, the full chain of paths we consider is
        // [a/b, c/b].
        val logicalChain: java.util.ArrayList<RootedPath?> = java.util.ArrayList<RootedPath?>()

        // Same contents as 'logicalChain', except stored as a sorted TreeSet for efficiency reasons.
        // See the usage in checkPathSeenDuringPartialResolutionInternal.
        val sortedLogicalChain: TreeSet<com.google.devtools.build.lib.vfs.Path?> =
            com.google.common.collect.Sets.newTreeSet<com.google.devtools.build.lib.vfs.Path?>()

        var pathToUnboundedAncestorSymlinkExpansionChain: com.google.common.collect.ImmutableList<RootedPath?>? = null
        var unboundedAncestorSymlinkExpansionChain: com.google.common.collect.ImmutableList<RootedPath?>? = null
    }

    @Throws(FileFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): FileValue? {
        val rootedPath: RootedPath = skyKey.argument() as RootedPath
        val symlinkResolutionState = SymlinkResolutionState()

        // Fully resolve the path of the parent directory, but only if the current file is not the
        // filesystem root (has no parent) or a package path root (treated opaquely and handled by
        // skyframe's DiffAwareness interface).
        //
        // This entails resolving ancestor symlinks fully. Note that this is the first thing we do - if
        // an ancestor is part of a symlink cycle, we want to detect that quickly as it gives a more
        // informative error message than we'd get doing bogus filesystem operations.
        val resolveFromAncestorsResult =
            resolveFromAncestors(rootedPath, symlinkResolutionState, env)
        if (resolveFromAncestorsResult == null) {
            return null
        }
        val rootedPathFromAncestors: RootedPath = resolveFromAncestorsResult.rootedPath
        val fileStateValueFromAncestors: FileStateValue = resolveFromAncestorsResult.fileStateValue
        if (fileStateValueFromAncestors.getType() === FileStateType.NONEXISTENT) {
            return FileValue.value(
                com.google.common.collect.ImmutableList.< E > copyOf < E ? > (symlinkResolutionState.logicalChain),
                symlinkResolutionState.pathToUnboundedAncestorSymlinkExpansionChain,
                symlinkResolutionState.unboundedAncestorSymlinkExpansionChain,
                rootedPath,
                FileStateValue.NONEXISTENT_FILE_STATE_NODE,
                rootedPathFromAncestors,
                fileStateValueFromAncestors
            )
        }

        var realRootedPath: RootedPath = rootedPathFromAncestors
        var realFileStateValue: FileStateValue = fileStateValueFromAncestors

        while (realFileStateValue.getType().isSymlink()) {
            val getSymlinkTargetRootedPathResult =
                getSymlinkTargetRootedPath(
                    realRootedPath, realFileStateValue.getSymlinkTarget(), symlinkResolutionState, env
                )
            if (getSymlinkTargetRootedPathResult == null) {
                return null
            }
            realRootedPath = getSymlinkTargetRootedPathResult.rootedPath
            realFileStateValue = getSymlinkTargetRootedPathResult.fileStateValue
        }

        return FileValue.value(
            com.google.common.collect.ImmutableList.< E > copyOf < E ? > (symlinkResolutionState.logicalChain),
            symlinkResolutionState.pathToUnboundedAncestorSymlinkExpansionChain,
            symlinkResolutionState.unboundedAncestorSymlinkExpansionChain,
            rootedPath,
            fileStateValueFromAncestors,
            realRootedPath,
            realFileStateValue
        )
    }

    private fun toRootedPath(path: com.google.devtools.build.lib.vfs.Path?): RootedPath {
        // We check whether the path to be transformed is under the output base or the install base.
        // These directories are under the control of Bazel and it therefore does not make much sense
        // to check for changes in them or in their ancestors in the usual Skyframe way.
        return RootedPath.toRootedPathMaybeUnderRoot(
            path, com.google.common.collect.Iterables.concat(pkgLocator.get().getPathEntries(), immutablePaths)
        )
    }

    private class PartialResolutionResult(rootedPath: RootedPath, fileStateValue: FileStateValue) {
        private val rootedPath: RootedPath
        private val fileStateValue: FileStateValue

        init {
            this.rootedPath = rootedPath
            this.fileStateValue = fileStateValue
        }
    }

    /**
     * Returns the symlink target and file state of `rootedPath`'s symlink to `symlinkTarget`, accounting for ancestor symlinks, or `null` if there was a missing dep.
     */
    @Throws(FileFunctionException::class, java.lang.InterruptedException::class)
    private fun getSymlinkTargetRootedPath(
        rootedPath: RootedPath,
        symlinkTarget: PathFragment,
        symlinkResolutionState: SymlinkResolutionState,
        env: SkyFunction.Environment
    ): PartialResolutionResult? {
        val path: com.google.devtools.build.lib.vfs.Path = rootedPath.asPath()
        val symlinkTargetPath: com.google.devtools.build.lib.vfs.Path?
        if (symlinkTarget.isAbsolute()) {
            symlinkTargetPath = path.getRelative(symlinkTarget)
        } else {
            val parentPath: com.google.devtools.build.lib.vfs.Path? = path.getParentDirectory()
            symlinkTargetPath =
                if (parentPath != null)
                    parentPath.getRelative(symlinkTarget)
                else
                    path.getRelative(symlinkTarget)
        }
        val symlinkTargetRootedPath: RootedPath = toRootedPath(symlinkTargetPath)
        checkPathSeenDuringPartialResolution(symlinkTargetRootedPath, symlinkResolutionState, env)
        if (env.valuesMissing()) {
            return null
        }
        // The symlink target could have a different parent directory, which itself could be a directory
        // symlink (or have an ancestor directory symlink)!
        return resolveFromAncestors(symlinkTargetRootedPath, symlinkResolutionState, env)
    }

    /**
     * Used to declare all the exception types that can be wrapped in the exception thrown by [ ][FileFunction.compute].
     */
    private class FileFunctionException(e: IOException?, transience: Transience?) : SkyFunctionException(e, transience)
    companion object {
        private fun getChild(
            parent: RootedPath, baseName: String?, originalParent: RootedPath?, originalChild: RootedPath
        ): RootedPath {
            if (parent == originalParent) {
                return originalChild // Avoid constructing a new instance if we already have the child.
            }
            return RootedPath.toRootedPath(
                parent.getRoot(), parent.getRootRelativePath().getChild(baseName)
            )
        }

        /**
         * Returns the path and file state of `rootedPath`, accounting for ancestor symlinks, or
         * `null` if there was a missing dep.
         */
        @Throws(java.lang.InterruptedException::class, FileFunctionException::class)
        private fun resolveFromAncestors(
            rootedPath: RootedPath, symlinkResolutionState: SymlinkResolutionState, env: SkyFunction.Environment
        ): PartialResolutionResult? {
            val parentRootedPath: RootedPath? = rootedPath.getParentDirectory()
            return if (parentRootedPath != null)
                resolveFromAncestorsWithParent(rootedPath, parentRootedPath, symlinkResolutionState, env)
            else
                resolveFromAncestorsNoParent(rootedPath, symlinkResolutionState, env)
        }

        @Throws(java.lang.InterruptedException::class, FileFunctionException::class)
        private fun resolveFromAncestorsWithParent(
            rootedPath: RootedPath,
            parentRootedPath: RootedPath?,
            symlinkResolutionState: SymlinkResolutionState,
            env: SkyFunction.Environment
        ): PartialResolutionResult? {
            val relativePath: PathFragment = rootedPath.getRootRelativePath()
            val baseName: String? = relativePath.getBaseName()

            val parentFileValue: FileValue? = env.getValue(FileValue.key(parentRootedPath)) as FileValue?
            if (parentFileValue == null) {
                return null
            }

            val rootedPathFromAncestors: RootedPath =
                getChild(
                    parentFileValue.realRootedPath(parentRootedPath),
                    baseName,
                    parentRootedPath,
                    rootedPath
                )

            if (!parentFileValue.exists() || !parentFileValue.isDirectory()) {
                return PartialResolutionResult(
                    rootedPathFromAncestors, FileStateValue.NONEXISTENT_FILE_STATE_NODE
                )
            }

            for (parentPartialRootedPath in parentFileValue.logicalChainDuringResolution(parentRootedPath)) {
                checkAndNotePathSeenDuringPartialResolution(
                    getChild(parentPartialRootedPath, baseName, parentRootedPath, rootedPath),
                    symlinkResolutionState,
                    env
                )
                if (env.valuesMissing()) {
                    return null
                }
            }

            val fileStateValueFromAncestors: FileStateValue? =
                env.getValue(FileStateValue.key(rootedPathFromAncestors)) as FileStateValue?
            if (fileStateValueFromAncestors == null) {
                return null
            }

            return PartialResolutionResult(rootedPathFromAncestors, fileStateValueFromAncestors)
        }

        @Throws(java.lang.InterruptedException::class, FileFunctionException::class)
        private fun resolveFromAncestorsNoParent(
            rootedPath: RootedPath, symlinkResolutionState: SymlinkResolutionState, env: SkyFunction.Environment
        ): PartialResolutionResult? {
            checkAndNotePathSeenDuringPartialResolution(rootedPath, symlinkResolutionState, env)
            if (env.valuesMissing()) {
                return null
            }
            val realFileStateValue: FileStateValue? =
                env.getValue(FileStateValue.key(rootedPath)) as FileStateValue?
            if (realFileStateValue == null) {
                return null
            }
            return PartialResolutionResult(rootedPath, realFileStateValue)
        }

        @Throws(FileFunctionException::class, java.lang.InterruptedException::class)
        private fun checkAndNotePathSeenDuringPartialResolution(
            rootedPath: RootedPath, symlinkResolutionState: SymlinkResolutionState, env: SkyFunction.Environment
        ) {
            val path: com.google.devtools.build.lib.vfs.Path = rootedPath.asPath()
            checkPathSeenDuringPartialResolutionInternal(rootedPath, path, symlinkResolutionState, env)
            symlinkResolutionState.sortedLogicalChain.add(path)
            symlinkResolutionState.logicalChain.add(rootedPath)
        }

        @Throws(FileFunctionException::class, java.lang.InterruptedException::class)
        private fun checkPathSeenDuringPartialResolution(
            rootedPath: RootedPath, symlinkResolutionState: SymlinkResolutionState, env: SkyFunction.Environment
        ) {
            checkPathSeenDuringPartialResolutionInternal(
                rootedPath, rootedPath.asPath(), symlinkResolutionState, env
            )
        }

        @Throws(FileFunctionException::class, java.lang.InterruptedException::class)
        private fun checkPathSeenDuringPartialResolutionInternal(
            rootedPath: RootedPath,
            path: com.google.devtools.build.lib.vfs.Path,
            symlinkResolutionState: SymlinkResolutionState,
            env: SkyFunction.Environment
        ) {
            // We are about to perform another step of partial real path resolution. 'logicalChain' is the
            // chain of paths we've considered so far, and 'rootedPath' / 'path' is the proposed next path
            // we consider.
            //
            // There are three interesting cases to consider, all stemming from symlinks:
            //   (i) Symlink cycle:
            //     p -> p1 -> p2 -> p1
            //     This means `p` has no real path, so we error out.
            //   (ii) Unbounded expansion caused by a symlink to a descendant of a member of the chain:
            //     p -> a/b -> c/d -> a/b/e
            //     This means `p` has no real path, so we error out.
            //   (iii) Unbounded expansion caused by a symlink to an ancestor of a member of the chain:
            //     p -> a/b -> c/d -> a
            //     This is not necessarily a problem (the real path of `p` in this example is simply `a`),
            //     so we just note the unbounded ancestor expansion and let consumers decide what to do.
            //
            // We can detect all three of these symlink issues via inspection of the proposed new element.
            // Here is our incremental algorithm:
            //   If 'path' is in 'sortedLogicalChain' then we have a found a cycle (i).
            //   If 'path' is a descendant of any path p in 'sortedLogicalChain' then we have unbounded
            //   expansion (ii).
            //   If 'path' is an ancestor of any path p in 'sortedLogicalChain' then we have unbounded
            //   expansion (iii).
            // We can check for these cases efficiently (read: sublinear time) by finding the extremal
            // candidate p for (ii) and (iii).
            var uniquenessKey: SkyKey? = null
            var fse: FileSymlinkException? = null
            val seenFloorPath: com.google.devtools.build.lib.vfs.Path? =
                symlinkResolutionState.sortedLogicalChain.floor(path)
            val seenCeilingPath: com.google.devtools.build.lib.vfs.Path? =
                symlinkResolutionState.sortedLogicalChain.ceiling(path)
            if (symlinkResolutionState.sortedLogicalChain.contains(path)) {
                // 'rootedPath' is [transitively] a symlink to a previous element in the symlink chain (i).
                val pathAndChain: com.google.devtools.build.lib.util.Pair<com.google.common.collect.ImmutableList<RootedPath?>?, com.google.common.collect.ImmutableList<RootedPath?>?> =
                    CycleUtils.splitIntoPathAndChain(
                        isPathPredicate(path), symlinkResolutionState.logicalChain
                    )
                val fsce: FileSymlinkCycleException =
                    FileSymlinkCycleException(pathAndChain.getFirst(), pathAndChain.getSecond())
                uniquenessKey = FileSymlinkCycleUniquenessFunction.key(fsce.getCycle())
                fse = fsce
            } else if (seenFloorPath != null && path.startsWith(seenFloorPath)) {
                // 'rootedPath' is [transitively] a symlink to a descendant of a previous element in the
                // symlink chain (ii).
                val pathAndChain: com.google.devtools.build.lib.util.Pair<com.google.common.collect.ImmutableList<RootedPath?>?, com.google.common.collect.ImmutableList<RootedPath?>?> =
                    CycleUtils.splitIntoPathAndChain(
                        isPathPredicate(seenFloorPath),
                        com.google.common.collect.ImmutableList.< E > copyOf < E ? > (
                                com.google.common.collect.Iterables.< T > concat < T ? > (
                                        symlinkResolutionState.logicalChain,
                        com.google.common.collect.ImmutableList.of<E?>(rootedPath)
                    )))
                uniquenessKey = FileSymlinkInfiniteExpansionUniquenessFunction.key(pathAndChain.getSecond())
                fse =
                    FileSymlinkInfiniteExpansionException(
                        pathAndChain.getFirst(), pathAndChain.getSecond()
                    )
            } else if (seenCeilingPath != null && seenCeilingPath.startsWith(path)) {
                // 'rootedPath' is [transitively] a symlink to an ancestor of a previous element in the
                // symlink chain (iii).
                if (symlinkResolutionState.unboundedAncestorSymlinkExpansionChain == null) {
                    val pathAndChain: com.google.devtools.build.lib.util.Pair<com.google.common.collect.ImmutableList<RootedPath?>?, com.google.common.collect.ImmutableList<RootedPath?>?> =
                        CycleUtils.splitIntoPathAndChain(
                            isPathPredicate(seenCeilingPath),
                            com.google.common.collect.ImmutableList.< E > copyOf < E ? > (
                                    com.google.common.collect.Iterables.< T > concat < T ? > (
                                            symlinkResolutionState.logicalChain,
                            com.google.common.collect.ImmutableList.of<E?>(rootedPath)
                        )))
                    symlinkResolutionState.pathToUnboundedAncestorSymlinkExpansionChain =
                        pathAndChain.getFirst()
                    symlinkResolutionState.unboundedAncestorSymlinkExpansionChain = pathAndChain.getSecond()
                }
            }
            if (uniquenessKey != null) {
                // Note that this dependency is merely to ensure that each unique symlink error gets
                // reported exactly once.
                env.getValue(uniquenessKey)
                if (env.valuesMissing()) {
                    return
                }
                throw FileFunctionException(
                    com.google.common.base.Preconditions.< IOException > checkNotNull < IOException ? > (fse, rootedPath
                ), Transience.PERSISTENT)
            }
        }

        private fun isPathPredicate(path: com.google.devtools.build.lib.vfs.Path?): com.google.common.base.Predicate<RootedPath?> {
            return com.google.common.base.Predicate { rootedPath: RootedPath? -> rootedPath.asPath() == path }
        }
    }
}
