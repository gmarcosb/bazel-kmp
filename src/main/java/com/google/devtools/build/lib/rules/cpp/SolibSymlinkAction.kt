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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.AbstractAction

/**
 * Creates mangled symlinks in the solib directory for all shared libraries. For shared libraries
 * that have potential to contain a SONAME field, create a link to the shared library parent
 * directory instead - so that the name of the library file is preserved.
 * 
 * 
 * Such symlinks are used by the linker to ensure that all rpath entries can be specified
 * relative to the $ORIGIN.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class SolibSymlinkAction private constructor(owner: ActionOwner?, primaryInput: Artifact, primaryOutput: Artifact) :
    AbstractAction(
        owner,
        NestedSetBuilder.create(Order.STABLE_ORDER, primaryInput),
        com.google.common.collect.ImmutableSet.of<E?>(primaryOutput)
    ) {
    private val symlink: Artifact

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
        val mangledPath: com.google.devtools.build.lib.vfs.Path = actionExecutionContext.getInputPath(symlink)
        try {
            mangledPath.createSymbolicLink(actionExecutionContext.getInputPath(getPrimaryInput()))
        } catch (e: IOException) {
            val message: String? =
                java.lang.String.format(
                    "failed to create _solib symbolic link '%s' to target '%s': %s",
                    symlink.prettyPrint(), getPrimaryInput(), e.getMessage()
                )
            val code: DetailedExitCode =
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setSymlinkAction(
                            FailureDetails.SymlinkAction.newBuilder()
                                .setCode(Code.LINK_CREATION_IO_EXCEPTION)
                        )
                        .build()
                )
            throw ActionExecutionException(message, e, this, false, code)
        }

        val logContext: SpawnLogContext? = actionExecutionContext.getContext(SpawnLogContext::class.java)
        if (logContext != null) {
            try {
                logContext.logSymlinkAction(this)
            } catch (e: IOException) {
                val message: String? =
                    java.lang.String.format(
                        "failed to log creation of _solib symbolic link '%s' to target '%s': %s",
                        symlink.prettyPrint(), getPrimaryInput(), e.getMessage()
                    )
                val code: DetailedExitCode =
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(message)
                            .setSymlinkAction(
                                FailureDetails.SymlinkAction.newBuilder()
                                    .setCode(Code.LINK_LOG_IO_EXCEPTION)
                            )
                            .build()
                    )
                throw ActionExecutionException(message, e, this, false, code)
            }
        }
        SymlinkAction.maybeInjectMetadata(this, actionExecutionContext)
        return ActionResult.EMPTY
    }

    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addPath(symlink.getExecPath())
        fp.addPath(getPrimaryInput().getExecPath())
    }

    val mnemonic: String
        get() = "SolibSymlink"

    protected val rawProgressMessage: String?
        get() = null

    init {
        com.google.common.base.Preconditions.checkArgument(
            com.google.devtools.build.lib.rules.cpp.Link.SHARED_LIBRARY_FILETYPES.matches(
                primaryInput.getFilename()
            )
        )
        this.symlink = com.google.common.base.Preconditions.checkNotNull<Artifact>(primaryOutput)
    }

    public override fun mayInsensitivelyPropagateInputs(): Boolean {
        return true
    }

    val executionPlatform: PlatformInfo
        get() = PlatformInfo.EMPTY_PLATFORM_INFO

    val execProperties: com.google.common.collect.ImmutableMap<String?, String?>
        get() =// SolibSymlinkAction is platform agnostic.
            com.google.common.collect.ImmutableMap.of<String?, String?>()

    companion object {
        /**
         * Replaces shared library artifact with mangled symlink and creates related symlink action. For
         * artifacts that should retain filename (e.g. libraries with SONAME tag), link is created to the
         * parent directory instead.
         * 
         * 
         * This action is performed to minimize number of -rpath entries used during linking process
         * (by essentially "collecting" as many shared libraries as possible in the single directory),
         * since we will be paying quadratic price for each additional entry on the -rpath.
         * 
         * @param actionConstructionContext action construction context of rule requesting symlink
         * @param solibDir String giving the solib directory
         * @param library Shared library artifact that needs to be mangled.
         * @param preserveName whether to preserve the name of the library
         * @param prefixConsumer whether to prefix the output artifact name with the label of the consumer
         * @return mangled symlink artifact.
         */
        fun getDynamicLibrarySymlink(
            actionConstructionContext: ActionConstructionContext,
            solibDir: String?,
            library: Artifact,
            preserveName: Boolean,
            prefixConsumer: Boolean
        ): Artifact {
            val mangledName: PathFragment =
                getMangledName(
                    actionConstructionContext.getOwner().getLabel(),
                    solibDir,
                    actionConstructionContext.getConfiguration().getMnemonic(),
                    library.getRootRelativePath(),
                    preserveName,
                    prefixConsumer
                )
            return getDynamicLibrarySymlinkInternal(actionConstructionContext, library, mangledName)
        }

        /**
         * Replaces shared library artifact with user specified symlink and creates related symlink
         * action.
         * 
         * 
         * This action is performed to minimize number of -rpath entries used during linking process
         * (by essentially "collecting" as many shared libraries as possible in the single directory),
         * since we will be paying quadratic price for each additional entry on the -rpath.
         * 
         * @param actionConstructionContext action construction context of rule requesting symlink
         * @param solibDir String giving the solib directory
         * @param library Shared library artifact that needs to be linked.
         * @param path Symlink path underneath the solib directory.
         * @return linked symlink artifact.
         */
        fun getDynamicLibrarySymlink(
            actionConstructionContext: ActionConstructionContext,
            solibDir: String?,
            library: Artifact,
            path: PathFragment
        ): Artifact {
            com.google.common.base.Preconditions.checkArgument(
                com.google.devtools.build.lib.rules.cpp.Link.SHARED_LIBRARY_FILETYPES.matches(
                    library.getFilename()
                )
            )
            com.google.common.base.Preconditions.checkArgument(
                com.google.devtools.build.lib.rules.cpp.Link.SHARED_LIBRARY_FILETYPES.matches(
                    path.getBaseName()
                )
            )
            com.google.common.base.Preconditions.checkArgument(
                !library.getRootRelativePath().getPathString().startsWith("_solib_")
            )

            val solibDirPath: PathFragment = PathFragment.create(solibDir)
            val linkName: PathFragment? = solibDirPath.getRelative(path)
            return getDynamicLibrarySymlinkInternal(actionConstructionContext, library, linkName)
        }

        /**
         * Version of [.getDynamicLibrarySymlink] for the special case of C++ runtime libraries.
         * These are handled differently than other libraries: neither their names nor directories are
         * mangled, i.e. libstdc++.so.6 is symlinked from _solib_[arch]/libstdc++.so.6
         */
        fun getCppRuntimeSymlink(
            ruleContext: RuleContext,
            library: Artifact,
            toolchainProvidedSolibDir: String?,
            solibDirOverride: String?
        ): Artifact {
            val solibDir: PathFragment =
                PathFragment.create(
                    if (solibDirOverride != null) solibDirOverride else toolchainProvidedSolibDir
                )
            val symlinkName: PathFragment? = solibDir.getRelative(library.getRootRelativePath().getBaseName())
            return getDynamicLibrarySymlinkInternal( /* actionConstructionContext= */
                ruleContext,
                library,
                symlinkName
            )
        }

        /**
         * Internal implementation that takes a pre-determined symlink name; supports both the generic
         * [.getDynamicLibrarySymlink] and the specialized [.getCppRuntimeSymlink].
         */
        private fun getDynamicLibrarySymlinkInternal(
            actionConstructionContext: ActionConstructionContext,
            library: Artifact,
            symlinkName: PathFragment?
        ): Artifact {
            com.google.common.base.Preconditions.checkArgument(
                com.google.devtools.build.lib.rules.cpp.Link.SHARED_LIBRARY_FILETYPES.matches(library.getFilename()),
                "Library '%s' does not match expected filetype",
                library.getFilename()
            )
            com.google.common.base.Preconditions.checkArgument(
                !library.getRootRelativePath().getPathString().startsWith("_solib_")
            )

            // Ignore libraries that are already represented by the symlinks.
            val root: ArtifactRoot? = actionConstructionContext.getBinDirectory()
            val symlink: Artifact = actionConstructionContext.getShareableArtifact(symlinkName, root)
            actionConstructionContext.registerAction(
                SolibSymlinkAction(actionConstructionContext.getActionOwner(), library, symlink)
            )
            return symlink
        }

        @com.google.common.annotations.VisibleForTesting
        const val MAX_FILENAME_LENGTH: Int = 255

        private fun maybeHashPreserveExtension(filename: String): String {
            if (filename.length() <= MAX_FILENAME_LENGTH) {
                return filename
            } else {
                val hashedName = com.google.common.hash.Hashing.sha256()
                    .hashString(filename, java.nio.charset.StandardCharsets.UTF_8).toString()
                val extension: String = com.google.common.io.Files.getFileExtension(filename)
                if (extension.isEmpty()) {
                    return hashedName
                } else {
                    return hashedName + "." + extension
                }
            }
        }

        /**
         * Returns the name of the symlink that will be created for a library, given its name.
         * 
         * @param label label of the rule calling this
         * @param solibDir a String giving the solib directory
         * @param libraryPath the root-relative path of the library
         * @param preserveName true if filename should be preserved
         * @param prefixConsumer true if the result should be prefixed with the label of the consumer
         * @returns root relative path name
         */
        private fun getMangledName(
            label: Label?,
            solibDir: String?,
            mnemonic: String,
            libraryPath: PathFragment,
            preserveName: Boolean,
            prefixConsumer: Boolean
        ): PathFragment {
            val escapedRulePath: String? = Actions.escapedPath("_" + label)
            val soname = getDynamicLibrarySoname(libraryPath, preserveName, mnemonic)
            val solibDirPath: PathFragment = PathFragment.create(solibDir)
            if (preserveName) {
                val escapedLibraryPath: String? =
                    Actions.escapedPath("_" + libraryPath.getParentDirectory().getPathString())
                val escapedFullPath: String =
                    (if (prefixConsumer) escapedRulePath + "__" + escapedLibraryPath else escapedLibraryPath)!!
                val mangledDir: PathFragment =
                    solibDirPath.getRelative(maybeHashPreserveExtension(escapedFullPath))
                return mangledDir.getRelative(soname)
            } else {
                val filename: String = (if (prefixConsumer) escapedRulePath + "__" + soname else soname)!!
                return solibDirPath.getRelative(maybeHashPreserveExtension(filename))
            }
        }

        /**
         * Compute the SONAME to use for a dynamic library. This name is basically the name of the shared
         * library in its final symlinked location.
         * 
         * @param libraryPath name of the shared library that needs to be mangled
         * @param preserveName true if filename should be preserved, false - mangled
         * @param mnemonic the output directory mnemonic, to be mangled in for nondefault configurations
         * @return soname to embed in the dynamic library
         */
        fun getDynamicLibrarySoname(
            libraryPath: PathFragment, preserveName: Boolean, mnemonic: String
        ): String? {
            val mangledName: String?
            if (preserveName) {
                mangledName = libraryPath.getBaseName()
            } else {
                var mnemonicMangling = ""
                if (mnemonic.contains("ST-")) {
                    mnemonicMangling = mnemonic.substring(mnemonic.indexOf("ST-")) + "_"
                }
                mangledName = "lib" + mnemonicMangling + Actions.escapedPath(libraryPath.getPathString())
            }
            return mangledName
        }
    }
}
