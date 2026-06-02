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

import com.google.devtools.build.lib.actions.Artifact

/** A [SkyFunction] to build [RecursiveFilesystemTraversalValue]s.  */
class RecursiveFilesystemTraversalFunction internal constructor(syscallCache: SyscallCache?) : SkyFunction {
    /** The exception that [RecursiveFilesystemTraversalFunctionException] wraps.  */
    open class RecursiveFilesystemTraversalException : java.lang.Exception {
        /**
         * Categories of errors that prevent normal [RecursiveFilesystemTraversalFunction]
         * evaluation.
         */
        enum class Type {
            /**
             * The traversal encountered a subdirectory with a BUILD file but is not allowed to recurse
             * into it.
             */
            CANNOT_CROSS_PACKAGE_BOUNDARY,

            /** A dangling symlink was dereferenced.  */
            DANGLING_SYMLINK,

            /** A file operation failed.  */
            FILE_OPERATION_FAILURE,

            /** A generated directory's root-relative path conflicts with a package's path.  */
            GENERATED_PATH_CONFLICT,

            /** A file/directory visited was part of a symlink cycle or infinite expansion.  */
            SYMLINK_CYCLE_OR_INFINITE_EXPANSION,

            /** The filesystem told us inconsistent information.  */
            INCONSISTENT_FILESYSTEM,

            /** The filesystem threw a [DetailedIOException].  */
            DETAILED_IO_EXCEPTION,

            /** A traversal of a source directory was requested.  */
            CANNOT_TRAVERSE_SOURCE_DIRECTORY,
        }

        @kotlin.jvm.JvmField
        val type: Type?

        internal constructor(message: String?, cause: DetailedIOException?) : super(message, cause) {
            this.type =
                com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.DETAILED_IO_EXCEPTION
        }

        internal constructor(message: String?, type: Type?) : super(message) {
            com.google.common.base.Preconditions.checkArgument(type != com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.DETAILED_IO_EXCEPTION)
            this.type = type
        }
    }

    /**
     * Thrown when a dangling symlink is attempted to be dereferenced.
     * 
     * 
     * Note: this class is not identical to the one in com.google.devtools.build.lib.view.fileset
     * and it's not easy to merge the two because of the dependency structure. The other one will
     * probably be removed along with the rest of the legacy Fileset code.
     */
    internal class DanglingSymlinkException(path: String, unresolvedLink: String) :
        RecursiveFilesystemTraversalException(
            java.lang.String.format(
                "Found dangling symlink: %s, unresolved path: \"%s\"", path, unresolvedLink
            ),
            com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.DANGLING_SYMLINK
        ) {
        init {
            com.google.common.base.Preconditions.checkArgument(path != null && !path.isEmpty())
            com.google.common.base.Preconditions.checkArgument(unresolvedLink != null && !unresolvedLink.isEmpty())
        }
    }

    /** Exception type thrown by [RecursiveFilesystemTraversalFunction.compute].  */
    private class RecursiveFilesystemTraversalFunctionException(e: RecursiveFilesystemTraversalException?) :
        SkyFunctionException(e, Transience.PERSISTENT)

    private val syscallCache: SyscallCache?

    init {
        this.syscallCache = syscallCache
    }

    @Throws(RecursiveFilesystemTraversalFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): RecursiveFilesystemTraversalValue? {
        var traversal: TraversalRequest = skyKey.argument() as TraversalRequest
        try {
            Profiler.instance()
                .profile(ProfilerTask.FILESYSTEM_TRAVERSAL, traversal.root().toString()).use { c ->
                    // Stat the traversal root.
                    val rootInfo = lookUpFileInfo(env, traversal, syscallCache)
                    if (rootInfo == null) {
                        return null
                    }

                    if (!rootInfo.type.exists()) {
                        // May be a dangling symlink or a non-existent file. Handle gracefully.
                        if (rootInfo.type.isSymlink()) {
                            return RecursiveFilesystemTraversalValue.Companion.of(
                                ResolvedFileFactory.danglingSymlink(
                                    traversal.root().asRootedPath(), rootInfo.unresolvedSymlinkTarget
                                )
                            )
                        } else {
                            return RecursiveFilesystemTraversalValue.Companion.EMPTY
                        }
                    }

                    if (rootInfo.type.isFile()) {
                        return resultForFileRoot(traversal.root().asRootedPath(), rootInfo)
                    }
                    if (rootInfo.type.isDirectory() && rootInfo.metadata is TreeArtifactValue) {
                        val traversalValues: com.google.common.collect.ImmutableList.Builder<RecursiveFilesystemTraversalValue?> =
                            com.google.common.collect.ImmutableList.builderWithExpectedSize<RecursiveFilesystemTraversalValue?>(
                                metadata.getChildValues().size()
                            )
                        for (entry
                        in metadata.getChildValues().entrySet()) {
                            val path: RootedPath? =
                                RootedPath.toRootedPath(traversal.root().getRootPart(), entry.getKey().getPath())
                            traversalValues.add(
                                resultForFileRoot(
                                    path,  // TreeArtifact can't have symbolic inside. So the assumption for FileType.FILE
                                    // is always true.
                                    com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.FileInfo(
                                        com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.FILE,
                                        entry.getValue(),
                                        path,
                                        null
                                    )
                                )
                            )
                        }
                        return resultForDirectory(traversal, rootInfo, traversalValues.build())
                    }

                    // Otherwise the root is a directory or a symlink to one.
                    val pkgLookupResult = checkIfPackage(env, traversal, rootInfo, syscallCache)
                    if (pkgLookupResult == null) {
                        return null
                    }
                    traversal = pkgLookupResult.traversal

                    if (pkgLookupResult.isConflicting) {
                        // The traversal was requested for an output directory whose root-relative path conflicts
                        // with a source package. We can't handle that, bail out.
                        throw createGeneratedPathConflictException(traversal)
                    } else if (pkgLookupResult.isPackage && !traversal.skipTestingForSubpackage()) {
                        // The traversal was requested for a directory that defines a package which we should not
                        // traverse and should complain loudly (display an error).
                        val msg =
                            (traversal.errorInfo()
                                    + " crosses package boundary into package rooted at "
                                    + traversal.root().getRelativePart().getPathString())
                        throw RecursiveFilesystemTraversalFunctionException(
                            RecursiveFilesystemTraversalException(
                                msg,
                                com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.CANNOT_CROSS_PACKAGE_BOUNDARY
                            )
                        )
                    }

                    // We are free to traverse this directory.
                    val subdirTraversals: com.google.common.collect.ImmutableList<RecursiveFilesystemTraversalValue>? =
                        traverseChildren(env, traversal)
                    if (subdirTraversals == null) {
                        return null
                    }
                    return resultForDirectory(traversal, rootInfo, subdirTraversals)
                }
        } catch (e: IOException) {
            val message: String? =
                java.lang.String.format(
                    "Error while traversing directory %s: %s",
                    traversal.root().getRelativePart(), e.getMessage()
                )

            if (e is DetailedIOException) {
                throw RecursiveFilesystemTraversalFunctionException(
                    RecursiveFilesystemTraversalException(message, e)
                )
            }

            // Trying to stat the starting point of this root may have failed with a symlink cycle or
            // trying to get a package lookup value may have failed due to a symlink cycle.
            var exceptionType: RecursiveFilesystemTraversalException.Type? =
                com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.FILE_OPERATION_FAILURE
            if (e is InconsistentFilesystemException) {
                exceptionType =
                    com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.INCONSISTENT_FILESYSTEM
            }
            if (e is FileSymlinkException) {
                exceptionType =
                    com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.SYMLINK_CYCLE_OR_INFINITE_EXPANSION
            }
            if (e is DetailedException) {
                val code: FailureDetails.PackageLoading.Code? =
                    (e as DetailedException).detailedExitCode
                        .getFailureDetail()
                        .getPackageLoading()
                        .getCode()
                if (code === FailureDetails.PackageLoading.Code.SYMLINK_CYCLE_OR_INFINITE_EXPANSION) {
                    exceptionType =
                        com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.SYMLINK_CYCLE_OR_INFINITE_EXPANSION
                }
            }
            throw RecursiveFilesystemTraversalFunctionException(
                RecursiveFilesystemTraversalException(message, exceptionType)
            )
        } catch (e: BuildFileNotFoundException) {
            val message: String? =
                java.lang.String.format(
                    "Error while traversing directory %s: %s",
                    traversal.root().getRelativePart(), e.getMessage()
                )

            if (e is DetailedIOException) {
                throw RecursiveFilesystemTraversalFunctionException(
                    RecursiveFilesystemTraversalException(message, e)
                )
            }

            var exceptionType: RecursiveFilesystemTraversalException.Type? =
                com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.FILE_OPERATION_FAILURE
            if (e is InconsistentFilesystemException) {
                exceptionType =
                    com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.INCONSISTENT_FILESYSTEM
            }
            if (e is FileSymlinkException) {
                exceptionType =
                    com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.SYMLINK_CYCLE_OR_INFINITE_EXPANSION
            }
            if (e is DetailedException) {
                val code: FailureDetails.PackageLoading.Code? =
                    (e as DetailedException).detailedExitCode
                        .getFailureDetail()
                        .getPackageLoading()
                        .getCode()
                if (code === FailureDetails.PackageLoading.Code.SYMLINK_CYCLE_OR_INFINITE_EXPANSION) {
                    exceptionType =
                        com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.SYMLINK_CYCLE_OR_INFINITE_EXPANSION
                }
            }
            throw RecursiveFilesystemTraversalFunctionException(
                RecursiveFilesystemTraversalException(message, exceptionType)
            )
        }
    }

    private class FileInfo(
        type: com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType?,
        metadata: HasDigest,
        realPath: RootedPath?,
        unresolvedSymlinkTarget: PathFragment?
    ) {
        val type: com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType
        val metadata: HasDigest
        val realPath: RootedPath?
        val unresolvedSymlinkTarget: PathFragment?

        init {
            checkNotNull(metadata.getDigest(), metadata)
            this.type =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType>(
                    type
                )
            this.metadata = metadata
            this.realPath = realPath
            this.unresolvedSymlinkTarget = unresolvedSymlinkTarget
        }

        override fun toString(): String {
            if (type.isSymlink()) {
                return java.lang.String.format(
                    "(%s: link_value=%s, real_path=%s)", type,
                    unresolvedSymlinkTarget.getPathString(), realPath
                )
            } else {
                return java.lang.String.format("(%s: real_path=%s)", type, realPath)
            }
        }
    }

    private class PkgLookupResult(type: Type?, traversal: TraversalRequest?, rootInfo: FileInfo?) {
        private enum class Type {
            CONFLICT, DIRECTORY, PKG
        }

        private val type: Type
        val traversal: TraversalRequest
        val rootInfo: FileInfo

        init {
            this.type = com.google.common.base.Preconditions.checkNotNull<Type>(type)
            this.traversal = com.google.common.base.Preconditions.checkNotNull<TraversalRequest>(traversal)
            this.rootInfo = com.google.common.base.Preconditions.checkNotNull<FileInfo>(rootInfo)
        }

        val isPackage: Boolean
            get() = type == com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.PkgLookupResult.Type.PKG

        val isConflicting: Boolean
            get() = type == com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.PkgLookupResult.Type.CONFLICT

        override fun toString(): String {
            return java.lang.String.format("(%s: info=%s, traversal=%s)", type, rootInfo, traversal)
        }

        companion object {
            /** Result for a generated directory that conflicts with a source package.  */
            fun conflict(traversal: TraversalRequest?, rootInfo: FileInfo?): PkgLookupResult {
                return PkgLookupResult(
                    com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.PkgLookupResult.Type.CONFLICT,
                    traversal,
                    rootInfo
                )
            }

            /** Result for a source or generated directory (not a package).  */
            fun directory(traversal: TraversalRequest?, rootInfo: FileInfo?): PkgLookupResult {
                return PkgLookupResult(
                    com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.PkgLookupResult.Type.DIRECTORY,
                    traversal,
                    rootInfo
                )
            }

            /** Result for a package, i.e. a directory  with a BUILD file.  */
            fun pkg(traversal: TraversalRequest?, rootInfo: FileInfo?): PkgLookupResult {
                return PkgLookupResult(
                    com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.PkgLookupResult.Type.PKG,
                    traversal,
                    rootInfo
                )
            }
        }
    }

    /** Requests Skyframe to compute the dependent values and returns them.  */
    @Throws(
        java.lang.InterruptedException::class,
        RecursiveFilesystemTraversalFunctionException::class,
        IOException::class
    )
    private fun traverseChildren(
        env: SkyFunction.Environment, traversal: TraversalRequest
    ): com.google.common.collect.ImmutableList<RecursiveFilesystemTraversalValue>? {
        return if (traversal.isRootGenerated())
            traverseGeneratedChildren(env, traversal)
        else
            traverseSourceChildren(env, traversal)
    }

    @Throws(
        java.lang.InterruptedException::class,
        RecursiveFilesystemTraversalFunctionException::class,
        IOException::class
    )
    private fun traverseGeneratedChildren(
        env: SkyFunction.Environment, traversal: TraversalRequest
    ): com.google.common.collect.ImmutableList<RecursiveFilesystemTraversalValue?>? {
        // If we're dealing with an output file, read the directory directly instead of creating
        // filesystem nodes under the output tree.
        val dirents: MutableCollection<com.google.devtools.build.lib.vfs.Dirent?> =
            traversal.root().asPath().readdir(Symlinks.FOLLOW)
        if (dirents.isEmpty()) {
            return com.google.common.collect.ImmutableList.of<RecursiveFilesystemTraversalValue?>()
        }
        val sortedDirents: MutableList<com.google.devtools.build.lib.vfs.Dirent> =
            java.util.ArrayList<com.google.devtools.build.lib.vfs.Dirent>(dirents)
        Collections.sort<com.google.devtools.build.lib.vfs.Dirent?>(sortedDirents)

        val childValues: com.google.common.collect.ImmutableList.Builder<RecursiveFilesystemTraversalValue?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<RecursiveFilesystemTraversalValue?>(dirents.size())
        for (dirent in sortedDirents) {
            val childValue: RecursiveFilesystemTraversalValue? =
                compute(traversal.forChildEntry(dirent.getName()), env)
            if (childValue != null) {
                childValues.add(childValue)
            }
        }
        return if (env.valuesMissing()) null else childValues.build()
    }

    @Throws(java.lang.InterruptedException::class, IOException::class)
    private fun traverseSourceChildren(
        env: SkyFunction.Environment, traversal: TraversalRequest
    ): com.google.common.collect.ImmutableList<RecursiveFilesystemTraversalValue?>? {
        val dirListingValue: DirectoryListingValue? =
            env.getValueOrThrow<IOException?>(
                DirectoryListingValue.Companion.key(traversal.root().asRootedPath()), IOException::class.java
            ) as DirectoryListingValue?
        if (dirListingValue == null) {
            return null
        }
        val dirents: Dirents = dirListingValue.getDirents()
        if (dirents.size() == 0) {
            return com.google.common.collect.ImmutableList.of<RecursiveFilesystemTraversalValue?>()
        }

        val childKeys: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>(dirents.size())
        for (dirent in dirents) {
            val childRequest: TraversalRequest = traversal.forChildEntry(dirent.getName())
            // For source files, request the FileValue directly instead of another TraversalRequest. This
            // makes the base case of the recursive traversal a directory with no subdirectories instead
            // of a file, greatly reducing the number of skyframe nodes for directories with many files.
            if (dirent.getType() == com.google.devtools.build.lib.vfs.Dirent.Type.FILE) {
                childKeys.add(FileValue.key(childRequest.root().asRootedPath()))
            } else {
                childKeys.add(childRequest)
            }
        }

        val result: SkyframeLookupResult = env.getValuesAndExceptions(childKeys)
        val childValues: com.google.common.collect.ImmutableList.Builder<RecursiveFilesystemTraversalValue?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<RecursiveFilesystemTraversalValue?>(
                childKeys.size()
            )
        for (key in childKeys) {
            val value: SkyValue? = result.get(key)
            if (value == null) {
                continue
            }
            if (key is com.google.devtools.build.lib.skyframe.FileKey) {
                val fileInfo =
                    toFileInfo(
                        key.argument(),
                        value as FileValue,
                        env,
                        key.argument().asPath(),
                        syscallCache
                    )
                if (fileInfo != null) {
                    childValues.add(resultForFileRoot(key.argument(), fileInfo))
                }
            } else {
                childValues.add(value as RecursiveFilesystemTraversalValue)
            }
        }
        return if (env.valuesMissing()) null else childValues.build()
    }

    companion object {
        private val MISSING_FINGERPRINT: ByteArray? =
            BigInteger(1, "NonexistentFileStateValue".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toByteArray()

        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val NON_EXISTENT_HAS_DIGEST: HasDigest = HasDigest { MISSING_FINGERPRINT }

        private val NON_EXISTENT_FILE_INFO: FileInfo =
            com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.FileInfo(
                com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.NONEXISTENT,
                NON_EXISTENT_HAS_DIGEST,
                null,
                null
            )

        private fun createGeneratedPathConflictException(
            traversal: TraversalRequest
        ): RecursiveFilesystemTraversalFunctionException {
            val message: String? =
                java.lang.String.format(
                    "Generated directory %s conflicts with package under the same path. "
                            + "Additional info: %s",
                    traversal.root().getRelativePart().getPathString(), traversal.errorInfo()
                )
            return RecursiveFilesystemTraversalFunctionException(
                RecursiveFilesystemTraversalException(
                    message,
                    com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException.Type.GENERATED_PATH_CONFLICT
                )
            )
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        private fun lookUpFileInfo(
            env: SkyFunction.Environment, traversal: TraversalRequest, syscallCache: SyscallCache?
        ): FileInfo? {
            if (traversal.isRootGenerated()) {
                var fsVal: HasDigest? = null
                if (traversal.root().getOutputArtifact() != null) {
                    val artifact: Artifact? = traversal.root().getOutputArtifact()
                    val artifactKey: SkyKey? = Artifact.key(artifact)
                    val value: SkyValue? = env.getValue(artifactKey)
                    if (env.valuesMissing()) {
                        return null
                    }

                    if (value is FileArtifactValue || value is TreeArtifactValue) {
                        fsVal = value as HasDigest?
                    } else if (value is ActionExecutionValue) {
                        fsVal = value.getExistingFileArtifactValue(artifact)
                    } else {
                        return NON_EXISTENT_FILE_INFO
                    }
                }
                var realPath: RootedPath? = traversal.root().asRootedPath()
                if (traversal.strictOutputFiles()) {
                    com.google.common.base.Preconditions.checkNotNull<Any?>(
                        fsVal,
                        "Strict Fileset output tree has null FileArtifactValue"
                    )
                    return com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.FileInfo(
                        if (fsVal is TreeArtifactValue) com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.DIRECTORY else com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.FILE,
                        fsVal,
                        realPath,
                        null
                    )
                } else {
                    // FileArtifactValue does not currently track symlinks. If it did, we could potentially
                    // remove some of the filesystem operations we're doing here.
                    val path: com.google.devtools.build.lib.vfs.Path = traversal.root().asPath()
                    val fileState: FileStateValue =
                        FileStateValue.create(traversal.root().asRootedPath(), syscallCache, null)
                    if (fileState.getType() === FileStateType.NONEXISTENT) {
                        throw IOException("Missing file: " + path)
                    }
                    val followStat: FileStatus? = path.statIfFound(Symlinks.FOLLOW)
                    val type: com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType?
                    var unresolvedLinkTarget: PathFragment? = null
                    if (followStat == null) {
                        type =
                            com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.DANGLING_SYMLINK
                        if (fileState.getType() !== FileStateType.SYMLINK) {
                            throw IOException("Expected symlink for " + path + ", but got: " + fileState)
                        }
                        unresolvedLinkTarget = path.readSymbolicLink()
                    } else if (fileState.getType() === FileStateType.REGULAR_FILE) {
                        type = com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.FILE
                    } else if (fileState.getType() === FileStateType.DIRECTORY) {
                        type =
                            com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.DIRECTORY
                    } else {
                        unresolvedLinkTarget = path.readSymbolicLink()
                        realPath =
                            RootedPath.toRootedPath(
                                Root.absoluteRoot(path.getFileSystem()), path.resolveSymbolicLinks()
                            )
                        type =
                            if (followStat.isFile()) com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.SYMLINK_TO_FILE else com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.SYMLINK_TO_DIRECTORY
                    }
                    if (fsVal == null) {
                        fsVal = fileState
                    }
                    return com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.FileInfo(
                        type, withDigest(fsVal, path, syscallCache), realPath, unresolvedLinkTarget
                    )
                }
            } else {
                // Stat the file.
                val rootedPath: RootedPath? = traversal.root().asRootedPath()
                val fileValue: FileValue? =
                    env.getValueOrThrow<E?>(FileValue.key(rootedPath), IOException::class.java) as FileValue?

                if (env.valuesMissing()) {
                    return null
                }
                return toFileInfo(rootedPath, fileValue, env, traversal.root().asPath(), syscallCache)
            }
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        private fun toFileInfo(
            rootedPath: RootedPath?,
            fileValue: FileValue,
            env: SkyFunction.Environment,
            path: com.google.devtools.build.lib.vfs.Path?,
            syscallCache: SyscallCache?
        ): FileInfo? {
            if (fileValue.unboundedAncestorSymlinkExpansionChain() != null) {
                val uniquenessKey: SkyKey? =
                    FileSymlinkInfiniteExpansionUniquenessFunction.key(
                        fileValue.unboundedAncestorSymlinkExpansionChain()
                    )
                env.getValue(uniquenessKey)
                if (env.valuesMissing()) {
                    return null
                }

                throw FileSymlinkInfiniteExpansionException(
                    fileValue.pathToUnboundedAncestorSymlinkExpansionChain(),
                    fileValue.unboundedAncestorSymlinkExpansionChain()
                )
            }

            if (!fileValue.exists()) {
                // If it doesn't exist, or it's a dangling symlink, we still want to handle that gracefully.
                return com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.FileInfo(
                    if (fileValue.isSymlink()) com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.DANGLING_SYMLINK else com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.NONEXISTENT,
                    withDigest(fileValue.realFileStateValue(), null, syscallCache),
                    null,
                    if (fileValue.isSymlink()) fileValue.getUnresolvedLinkTarget() else null
                )
            }

            // If it exists, it may either be a symlink or a file/directory.
            var unresolvedLinkTarget: PathFragment? = null
            val type: com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType?
            if (fileValue.isSymlink()) {
                unresolvedLinkTarget = fileValue.getUnresolvedLinkTarget()
                type =
                    if (fileValue.isDirectory()) com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.SYMLINK_TO_DIRECTORY else com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.SYMLINK_TO_FILE
            } else {
                type =
                    if (fileValue.isDirectory()) com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.DIRECTORY else com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType.FILE
            }
            return com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.FileInfo(
                type,
                withDigest(fileValue.realFileStateValue(), path, syscallCache),
                fileValue.realRootedPath(rootedPath),
                unresolvedLinkTarget
            )
        }

        /**
         * Transform the HasDigest to the appropriate type based on the current state of the digest. If
         * fsVal is type RegularFileStateValue or FileArtifactValue and has a valid digest value, then we
         * want to convert it to a new FileArtifactValue type. Otherwise if they are of the two
         * forementioned types but do not have a digest, then we will create a FileArtifactValue using its
         * [Path]. Otherwise we will fingerprint the digest and return it as a new [ ] object.
         * 
         * @param fsVal - the HasDigest value that was in the graph.
         * @param path - the Path of the digest.
         * @return transformed HasDigest value based on the digest field and object type.
         */
        @com.google.common.annotations.VisibleForTesting
        @Throws(IOException::class)
        fun withDigest(
            fsVal: HasDigest?,
            path: com.google.devtools.build.lib.vfs.Path?,
            syscallCache: XattrProvider?
        ): HasDigest? {
            if (fsVal is FileStateValue) {
                if (fsVal is RegularFileStateValueWithDigest) {
                    return FileArtifactValue.createForVirtualActionInput(fsv.getDigest(), fsv.getSize())
                } else if (fsVal is RegularFileStateValueWithContentsProxy) {
                    return FileArtifactValue.createForNormalFileUsingPath(path, fsv.getSize(), syscallCache)
                }

                return ByteStringDigest(fsVal.getValueFingerprint())
            } else if (fsVal is FileArtifactValue) {
                if (fsVal.getDigest() != null) {
                    return fsVal
                }

                // In the case there is a directory, the HasDigest value should not be converted. Otherwise,
                // if the HasDigest value is a file, convert it using the Path and size values.
                return if (fsVal.getType().isFile())
                    FileArtifactValue.createForNormalFileUsingPath(path, fsVal.getSize(), syscallCache)
                else
                    ByteStringDigest(fsVal.getValueFingerprint())
            }
            return fsVal
        }

        /**
         * Checks whether the `traversal`'s path refers to a package directory.
         * 
         * @return the result of the lookup; it contains potentially new [TraversalRequest] and
         * [FileInfo] so the caller should use these instead of the old ones (this happens when
         * a package is found, but under a different root than expected)
         */
        @Throws(IOException::class, java.lang.InterruptedException::class, BuildFileNotFoundException::class)
        private fun checkIfPackage(
            env: SkyFunction.Environment, traversal: TraversalRequest, rootInfo: FileInfo, syscallCache: SyscallCache?
        ): PkgLookupResult? {
            var traversal: TraversalRequest = traversal
            var rootInfo = rootInfo
            com.google.common.base.Preconditions.checkArgument(
                rootInfo.type.exists() && !rootInfo.type.isFile(), "{%s} {%s}", traversal, rootInfo
            )
            // PackageLookupFunction/dependencies can only throw IOException, BuildFileNotFoundException,
            // and RepositoryFetchException, and RepositoryFetchException is not in play here. Note that
            // run-of-the-mill circular symlinks will *not* throw here, and will trigger later errors during
            // the recursive traversal.
            val pkgLookup: PackageLookupValue? =
                env.getValueOrThrow<E1?, E2?>(
                    PackageLookupValue.Companion.key(traversal.root().getRelativePart()),
                    BuildFileNotFoundException::class.java,
                    IOException::class.java
                ) as PackageLookupValue?
            if (env.valuesMissing()) {
                return null
            }

            if (pkgLookup.packageExists()) {
                if (traversal.isRootGenerated()) {
                    // The traversal's root was a generated directory, but its root-relative path conflicts with
                    // an existing package.
                    return PkgLookupResult.Companion.conflict(traversal, rootInfo)
                } else {
                    // The traversal's root was a source directory and it defines a package.
                    val pkgRoot: Root = pkgLookup.getRoot()
                    if (pkgRoot != traversal.root().getRootPart()) {
                        // However the root of this package is different from what we expected. stat() the real
                        // BUILD file of that package.
                        traversal = traversal.forChangedRootPath(pkgRoot)
                        rootInfo = lookUpFileInfo(env, traversal, syscallCache)!!
                        com.google.common.base.Verify.verify(rootInfo.type.exists(), "{%s} {%s}", traversal, rootInfo)
                    }
                    return PkgLookupResult.Companion.pkg(traversal, rootInfo)
                }
            } else {
                // The traversal's root was a directory (source or generated one), no package exists under the
                // same root-relative path.
                return PkgLookupResult.Companion.directory(traversal, rootInfo)
            }
        }

        /**
         * Creates results for a file or for a symlink that points to one.
         * 
         * 
         * A symlink may be direct (points to a file) or transitive (points at a direct or transitive
         * symlink).
         */
        @Throws(InconsistentFilesystemException::class)
        private fun resultForFileRoot(path: RootedPath?, info: FileInfo): RecursiveFilesystemTraversalValue {
            if (!info.type.isFile() || !info.type.exists()) {
                throw InconsistentFilesystemException(
                    java.lang.String.format(
                        "We were previously told %s was an existing file but it's actually %s", path, info
                    )
                )
            }

            if (info.type.isSymlink()) {
                return RecursiveFilesystemTraversalValue.Companion.of(
                    ResolvedFileFactory.symlinkToFile(
                        info.realPath, path, info.unresolvedSymlinkTarget, info.metadata
                    )
                )
            } else {
                return RecursiveFilesystemTraversalValue.Companion.of(
                    ResolvedFileFactory.regularFile(path, info.metadata)
                )
            }
        }

        private fun resultForDirectory(
            traversal: TraversalRequest,
            rootInfo: FileInfo,
            subdirTraversals: com.google.common.collect.ImmutableList<RecursiveFilesystemTraversalValue>
        ): RecursiveFilesystemTraversalValue? {
            // Collect transitive closure of files in subdirectories.
            var paths: NestedSetBuilder<ResolvedFile?> = NestedSetBuilder.stableOrder()
            for (child in subdirTraversals) {
                paths.addTransitive(child.getTransitiveFiles())
            }
            val root: ResolvedFile?
            if (rootInfo.type.isSymlink()) {
                val children: NestedSet<ResolvedFile?> = paths.build()
                root =
                    ResolvedFileFactory.symlinkToDirectory(
                        rootInfo.realPath,
                        traversal.root().asRootedPath(),
                        rootInfo.unresolvedSymlinkTarget,
                        hashDirectorySymlink(children, rootInfo.metadata)
                    )
                paths = NestedSetBuilder.< ResolvedFile > stableOrder < ResolvedFile ? > ().addTransitive(children)
                    .add(root)
            } else {
                root = ResolvedFileFactory.directory(rootInfo.realPath)
                if (traversal.emitEmptyDirectoryNodes() && paths.isEmpty()) {
                    paths.add(root)
                }
            }
            return RecursiveFilesystemTraversalValue.Companion.of(root, paths.build())
        }

        private fun hashDirectorySymlink(
            children: NestedSet<ResolvedFile?>, metadata: HasDigest
        ): HasDigest {
            // If the root is a directory symlink, the associated FileStateValue does not change when the
            // linked directory's contents change, so we can't use the FileStateValue as metadata like we
            // do with other ResolvedFile kinds. Instead we compute a metadata hash from the child
            // elements and return that as the ResolvedFile's metadata hash.
            val fp: Fingerprint = Fingerprint()
            fp.addBytes(metadata.getDigest())
            for (file in children.toList()) {
                fp.addPath(file.getNameInSymlinkTree())
                fp.addBytes(file.getMetadata().getDigest())
            }
            val result: ByteArray? = fp.digestAndReset()
            return ByteStringDigest(result)
        }
    }
}
