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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Static utilities for managing output directory symlinks.  */
object OutputDirectoryLinksUtils {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

    /**
     * Returns all (types of) convenience symlinks that may be created.
     * 
     * 
     * Note that this is independent of which symlinks are actually requested by the build options;
     * that's controlled by returning no candidates in [SymlinkDefinition.getLinkPaths].
     * 
     * 
     * The order of the result indicates precedence for [PathPrettyPrinter].
     */
    private fun getAllLinkDefinitions(
        symlinkDefinitions: Iterable<SymlinkDefinition?>
    ): com.google.common.collect.ImmutableList<SymlinkDefinition> {
        val builder: com.google.common.collect.ImmutableList.Builder<SymlinkDefinition?> =
            com.google.common.collect.ImmutableList.builder<SymlinkDefinition?>()
        builder.addAll(STANDARD_LINK_DEFINITIONS)
        builder.addAll(symlinkDefinitions)
        return builder.build()
    }

    private const val NO_CREATE_SYMLINKS_PREFIX = "/"

    /**
     * Attempts to create or delete convenience symlinks in the workspace to the various output
     * directories, and generates associated log events.
     * 
     * 
     * If `--symlink_prefix` is [.NO_CREATE_SYMLINKS_PREFIX], or `--experimental_convenience_symlinks` is [ConvenienceSymlinksMode.IGNORE], this method is
     * a no-op.
     * 
     * 
     * Otherwise, for each symlink type, we decide whether the symlink should exist or not. If it
     * should exist, it is created with the appropriate destination path; if not, it is deleted if
     * already present on the file system. In either case, the decision of whether to create or delete
     * the symlink is logged. (Note that deleting pre-existing symlinks helps ensure the user's
     * workspace is in a consistent state after the build. However, if the `--symlink_prefix`
     * has changed, we have no way to cleanup old symlink names leftover from a previous invocation.)
     * 
     * 
     * If `--experimental_convenience_symlinks` is set to [ ][ConvenienceSymlinksMode.CLEAN], all symlinks are set to be deleted. If it's set to [ ][ConvenienceSymlinksMode.NORMAL], each symlink type decides whether it should be created or
     * deleted. (A symlink may decide to be deleted if e.g. it is disabled by a flag, or would want to
     * point to more than one destination.) If it's set to [ConvenienceSymlinksMode.LOG_ONLY],
     * the same logic is run as in the `NORMAL` case, but the result is only emitting log
     * messages, with no actual filesystem mutations.
     * 
     * 
     * A warning is emitted if a symlink would resolve to multiple destinations, or if a filesystem
     * mutation operation fails.
     */
    fun createOutputDirectoryLinks(
        symlinkDefinitions: Iterable<SymlinkDefinition?>,
        buildRequestOptions: BuildRequestOptions,
        workspaceName: String?,
        workspace: com.google.devtools.build.lib.vfs.Path,
        directories: BlazeDirectories,
        eventHandler: com.google.devtools.build.lib.events.EventHandler,
        targetConfigs: MutableSet<BuildConfigurationValue?>?,
        productName: String?
    ): SymlinkCreationResult {
        val execRoot: com.google.devtools.build.lib.vfs.Path? = directories.getExecRoot(workspaceName)
        val outputPath: com.google.devtools.build.lib.vfs.Path? = directories.getOutputPath(workspaceName)
        val symlinkPrefix: String? = buildRequestOptions.getSymlinkPrefix(productName)
        val mode: ConvenienceSymlinksMode? = buildRequestOptions.getExperimentalConvenienceSymlinks()
        if (NO_CREATE_SYMLINKS_PREFIX == symlinkPrefix) {
            return EMPTY_SYMLINK_CREATION_RESULT
        }

        val convenienceSymlinksBuilder: com.google.common.collect.ImmutableList.Builder<ConvenienceSymlink?> =
            com.google.common.collect.ImmutableList.builder<ConvenienceSymlink?>()
        val createdConvenienceSymlinksBuilder: com.google.common.collect.ImmutableMap.Builder<PathFragment?, PathFragment?> =
            com.google.common.collect.ImmutableMap.builder<PathFragment?, PathFragment?>()
        val failures: MutableList<String?> = java.util.ArrayList<String?>()
        val ambiguousLinks: MutableList<String?> = java.util.ArrayList<String?>()
        val createdLinks: MutableSet<String?> = LinkedHashSet<String?>()
        val workspaceBaseName: String? = workspace.getBaseName()
        val repositoryName: RepositoryName = RepositoryName.Companion.MAIN
        val logOnly = mode == ConvenienceSymlinksMode.LOG_ONLY

        for (symlink in getAllLinkDefinitions(symlinkDefinitions)) {
            val linkName: String? = symlink.getLinkName(symlinkPrefix, workspaceBaseName)
            if (!createdLinks.add(linkName)) {
                // already created a link by this name
                continue
            }
            if (mode == ConvenienceSymlinksMode.CLEAN) {
                removeLink(workspace, linkName, failures, convenienceSymlinksBuilder, logOnly)
            } else {
                val candidatePaths: MutableSet<com.google.devtools.build.lib.vfs.Path?> =
                    symlink.getLinkPaths(
                        buildRequestOptions, targetConfigs, repositoryName, outputPath, execRoot
                    )
                if (candidatePaths.size() == 1) {
                    createLink(
                        workspace,
                        linkName,
                        execRoot,
                        directories,
                        com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.vfs.Path?>(
                            candidatePaths
                        ),
                        failures,
                        convenienceSymlinksBuilder,
                        createdConvenienceSymlinksBuilder,
                        logOnly
                    )
                } else {
                    removeLink(workspace, linkName, failures, convenienceSymlinksBuilder, logOnly)
                    // candidatePaths can be empty if the symlink decided not to be created. This can happen
                    // if the symlink is disabled by a flag, or it intercepts an error while computing its
                    // target path. In that case, don't trigger a warning about an ambiguous link.
                    if (candidatePaths.size() > 1) {
                        ambiguousLinks.add(linkName)
                    }
                }
            }
        }

        if (!failures.isEmpty()) {
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.warn(
                    java.lang.String.format(
                        "failed to create one or more convenience symlinks for prefix '%s':\n  %s",
                        symlinkPrefix, com.google.common.base.Joiner.on("\n  ").join(failures)
                    )
                )
            )
        }
        if (!ambiguousLinks.isEmpty()) {
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.warn(
                    java.lang.String.format(
                        ("cleared convenience symlink(s) %s because they wouldn't contain "
                                + "requested targets' outputs. Those targets self-transition to multiple "
                                + "distinct configurations"),
                        com.google.common.base.Joiner.on(", ").join(ambiguousLinks)
                    )
                )
            )
        }
        return SymlinkCreationResult(
            convenienceSymlinksBuilder.build(), createdConvenienceSymlinksBuilder.buildKeepingLast()
        )
    }

    /**
     * Attempts to remove the convenience symlinks in the workspace directory.
     * 
     * 
     * Issues a warning if it fails, e.g. because workspaceDirectory is readonly. Also cleans up
     * any child directories created by a custom prefix.
     * 
     * @param symlinkDefinitions extra symlink types added by the [ConfiguredRuleClassProvider]
     * @param workspace the runtime's workspace
     * @param eventHandler the error eventHandler
     * @param symlinkPrefix the symlink prefix which should be removed
     */
    fun removeOutputDirectoryLinks(
        symlinkDefinitions: Iterable<SymlinkDefinition?>,
        workspace: com.google.devtools.build.lib.vfs.Path,
        eventHandler: com.google.devtools.build.lib.events.EventHandler,
        symlinkPrefix: String?
    ) {
        if (NO_CREATE_SYMLINKS_PREFIX == symlinkPrefix) {
            return
        }
        val failures: MutableList<String?> = java.util.ArrayList<String?>()

        val workspaceBaseName: String? = workspace.getBaseName()

        for (link in getAllLinkDefinitions(symlinkDefinitions)) {
            removeLink(
                workspace,
                link.getLinkName(symlinkPrefix, workspaceBaseName),
                failures,
                com.google.common.collect.ImmutableList.builder<ConvenienceSymlink?>(),
                false
            )
        }

        com.google.devtools.build.lib.vfs.FileSystemUtils.removeDirectoryAndParents(
            workspace,
            PathFragment.create(symlinkPrefix)
        )
        if (!failures.isEmpty()) {
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.warn(
                    java.lang.String.format(
                        "failed to remove one or more convenience symlinks for prefix '%s':\n  %s", symlinkPrefix,
                        com.google.common.base.Joiner.on("\n  ").join(failures)
                    )
                )
            )
        }
    }

    /**
     * Creates a symlink and outputs a [ConvenienceSymlink] entry.
     * 
     * 
     * The symlink is created at path `name`, relative to `base`, creating directories
     * as needed; it points to `target`. Any filesystem errors are appended to `failures`.
     * 
     * 
     * A `ConvenienceSymlink` entry is added to `symlinksBuilder` describing the
     * symlink. `execRoot` and `directories` are used to determine the relative target
     * path for this entry.
     * 
     * 
     * If `logOnly` is true, the `ConvenienceSymlink` entry is added but no actual
     * filesystem operations are performed.
     */
    private fun createLink(
        base: com.google.devtools.build.lib.vfs.Path,
        name: String?,
        execRoot: com.google.devtools.build.lib.vfs.Path?,
        directories: BlazeDirectories,
        target: com.google.devtools.build.lib.vfs.Path,
        failures: MutableList<String?>,
        symlinksBuilder: com.google.common.collect.ImmutableList.Builder<ConvenienceSymlink?>,
        createdSymlinksBuilder: com.google.common.collect.ImmutableMap.Builder<PathFragment?, PathFragment?>,
        logOnly: Boolean
    ) {
        // The BEP event needs to report a target path relative to the output base. Usually the target
        // is already under the output base, but if the execroot is virtual (only happens in internal
        // blaze, see ModuleFileSystem), we need to rewrite the path using the real execroot.
        val outputBase: com.google.devtools.build.lib.vfs.Path = directories.getOutputBase()
        val targetForEvent: com.google.devtools.build.lib.vfs.Path =
            if (target.startsWith(outputBase))
                target
            else
                directories.getBlazeExecRoot().getRelative(target.relativeTo(execRoot))
        symlinksBuilder.add(
            ConvenienceSymlink.newBuilder()
                .setPath(name)
                .setTarget(targetForEvent.relativeTo(outputBase).getPathString())
                .setAction(Action.CREATE)
                .build()
        )

        val nameFragment: PathFragment? = PathFragment.create(name)
        if (logOnly) {
            // Still report as created - log-only implies we want to pretend it exists.
            createdSymlinksBuilder.put(nameFragment, target.asFragment())
            return
        }
        val link: com.google.devtools.build.lib.vfs.Path = base.getRelative(name)
        try {
            target.createDirectoryAndParents()
        } catch (e: IOException) {
            failures.add(
                java.lang.String.format(
                    "cannot create directory %s: %s",
                    target.getPathString(), e.getMessage()
                )
            )
            return
        }
        try {
            com.google.devtools.build.lib.vfs.FileSystemUtils.ensureSymbolicLink(link, target)
            createdSymlinksBuilder.put(nameFragment, target.asFragment())
        } catch (e: IOException) {
            failures.add(
                java.lang.String.format(
                    "cannot create symbolic link %s -> %s:  %s",
                    name, target.getPathString(), e.getMessage()
                )
            )
        }
    }

    /**
     * Deletes a symlink and outputs a [ConvenienceSymlink] entry.
     * 
     * 
     * The symlink to be deleted is at path `name`, relative to `base`. Any filesystem
     * errors are appended to `failures`.
     * 
     * 
     * A `ConvenienceSymlink` entry is added to `symlinksBuilder` describing the
     * symlink to be deleted.
     * 
     * 
     * If `logOnly` is true, the `ConvenienceSymlink` entry is added but no actual
     * filesystem operations are performed.
     */
    private fun removeLink(
        base: com.google.devtools.build.lib.vfs.Path,
        name: String?,
        failures: MutableList<String?>,
        symlinksBuilder: com.google.common.collect.ImmutableList.Builder<ConvenienceSymlink?>,
        logOnly: Boolean
    ) {
        symlinksBuilder.add(
            ConvenienceSymlink.newBuilder().setPath(name).setAction(Action.DELETE).build()
        )
        if (logOnly) {
            return
        }
        val link: com.google.devtools.build.lib.vfs.Path = base.getRelative(name)
        try {
            if (link.isSymbolicLink()) {
                // TODO(b/146885821): Consider also removing empty ancestor directories, to allow for
                //  cleaning up directories generated by --symlink_prefix=dir1/dir2/...
                //  Might be undesireable since it could also remove manually-created directories.
                logger.atFinest().log("Removing %s", link)
                link.delete()
            }
        } catch (e: IOException) {
            failures.add(java.lang.String.format("%s: %s", name, e.getMessage()))
        }
    }

    @Suppress("deprecation") // RuleContext#get*Directory not available here.
    private val STANDARD_LINK_DEFINITIONS: com.google.common.collect.ImmutableList<SymlinkDefinition?> =
        com.google.common.collect.ImmutableList.of<E?>(
            ConfigSymlink("bin", BuildConfigurationValue::getBinDirectory),
            ConfigSymlink("testlogs", BuildConfigurationValue::getTestLogsDirectory),
            object : ConfigSymlink("genfiles", BuildConfigurationValue::getGenfilesDirectory) {
                override fun getLinkPaths(
                    buildRequestOptions: BuildRequestOptions,
                    targetConfigs: MutableSet<BuildConfigurationValue?>,
                    repositoryName: RepositoryName?,
                    outputPath: com.google.devtools.build.lib.vfs.Path?,
                    execRoot: com.google.devtools.build.lib.vfs.Path?
                ): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?>? {
                    if (buildRequestOptions.getIncompatibleSkipGenfilesSymlink()) {
                        return com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.vfs.Path?>()
                    }
                    return super.getLinkPaths(
                        buildRequestOptions, targetConfigs, repositoryName, outputPath, execRoot
                    )
                }
            },  // output directory (bazel-out)
            object : SymlinkDefinition() {
                public override fun getLinkName(symlinkPrefix: String?, workspaceBaseName: String?): String {
                    return symlinkPrefix + "out"
                }

                public override fun getLinkPaths(
                    buildRequestOptions: BuildRequestOptions?,
                    targetConfigs: MutableSet<BuildConfigurationValue?>?,
                    repositoryName: RepositoryName?,
                    outputPath: com.google.devtools.build.lib.vfs.Path,
                    execRoot: com.google.devtools.build.lib.vfs.Path?
                ): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?> {
                    return com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.vfs.Path?>(outputPath)
                }
            },  // execroot
            object : SymlinkDefinition() {
                public override fun getLinkName(symlinkPrefix: String?, workspaceBaseName: String): String {
                    return symlinkPrefix + workspaceBaseName
                }

                public override fun getLinkPaths(
                    buildRequestOptions: BuildRequestOptions?,
                    targetConfigs: MutableSet<BuildConfigurationValue?>?,
                    repositoryName: RepositoryName?,
                    outputPath: com.google.devtools.build.lib.vfs.Path?,
                    execRoot: com.google.devtools.build.lib.vfs.Path
                ): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?> {
                    return com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.vfs.Path?>(execRoot)
                }
            })

    val EMPTY_SYMLINK_CREATION_RESULT: SymlinkCreationResult = SymlinkCreationResult(
        com.google.common.collect.ImmutableList.of<ConvenienceSymlink?>(),
        com.google.common.collect.ImmutableMap.of<PathFragment?, PathFragment?>()
    )

    /** Describes the outcome of symlink creation.  */
    internal class SymlinkCreationResult private constructor(
        convenienceSymlinkProtos: com.google.common.collect.ImmutableList<ConvenienceSymlink?>?,
        createdSymlinks: com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>?
    ) {
        private val convenienceSymlinkProtos: com.google.common.collect.ImmutableList<ConvenienceSymlink?>?
        private val createdSymlinks: com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>?

        init {
            this.convenienceSymlinkProtos = convenienceSymlinkProtos
            this.createdSymlinks = createdSymlinks
        }

        /** Returns descriptions of what symlinks were created and destroyed.  */
        fun getConvenienceSymlinkProtos(): com.google.common.collect.ImmutableList<ConvenienceSymlink?>? {
            return convenienceSymlinkProtos
        }

        /**
         * Returns symlink name -> target mappings of symlinks that were actually created (or in the
         * case of [ConvenienceSymlinksMode.LOG_ONLY], would have been created).
         */
        fun getCreatedSymlinks(): com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>? {
            return createdSymlinks
        }
    }
}
