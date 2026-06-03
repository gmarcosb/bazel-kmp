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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.ActionEnvironment

/**
 * This class manages the creation of the runfiles symlink farms.
 * 
 * 
 * For executables that might depend on the existence of files at run-time, we create a symlink
 * farm: a directory which contains symlinks to the right locations for those runfiles.
 * 
 * 
 * The runfiles symlink farm serves two purposes. The first is to allow programs (and
 * programmers) to refer to files using their workspace-relative paths, regardless of whether the
 * files were source files or generated files, and regardless of which part of the package path they
 * came from. The second purpose is to ensure that all run-time dependencies are explicitly declared
 * in the BUILD files; programs may only use files which the build system knows that they depend on.
 * 
 * 
 * The symlink farm contains a MANIFEST file which describes its contents. The MANIFEST file
 * lists the names and contents of all of the symlinks in the symlink farm. For efficiency, Blaze's
 * dependency analysis ignores the actual symlinks and just looks at the MANIFEST file. It is an
 * invariant that the MANIFEST file should accurately represent the contents of the symlinks
 * whenever the MANIFEST file is present. build_runfile_links.py preserves this invariant (modulo
 * bugs - currently it has a bug where it may fail to preserve that invariant if it gets
 * interrupted). So the Blaze dependency analysis looks only at the MANIFEST file, rather than at
 * the individual symlinks.
 * 
 * 
 * We create an Artifact for the MANIFEST file and a RunfilesAction Action to create it. This
 * action does not depend on any other Artifacts.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class RunfilesSupport private constructor(
    private val runfilesTree: RunfilesTreeImpl,
    runfilesInputManifest: Artifact?,
    runfilesManifest: Artifact?,
    runfilesTreeArtifact: Artifact,
    owningExecutable: Artifact?,
    args: FlatCommandLine?,
    actionEnvironment: ActionEnvironment?
) {
    /** The implementation of [RunfilesTree].  */
    @com.google.common.annotations.VisibleForTesting
    class RunfilesTreeImpl private constructor(
        execPath: PathFragment?,
        runfiles: com.google.devtools.build.lib.analysis.Runfiles,
        repoMappingManifest: Artifact?,
        buildRunfileLinks: Boolean,
        cacheMapping: Boolean,
        runfileSymlinksMode: RunfileSymlinksMode?
    ) : RunfilesTree {
        private val execPath: PathFragment?
        private val runfiles: com.google.devtools.build.lib.analysis.Runfiles
        private val repoMappingManifest: Artifact?

        /**
         * The cached runfiles mapping. Possible values:
         * 
         * 
         *  * null if caching is not desired
         *  * A weak reference pointing to null if the cached value is not available (either [       ][.NOT_YET_COMPUTED] or flushed from RAM)
         *  * A weak reference to the cached value
         * 
         * 
         * 
         * Using weak references is preferable to soft references because [ ] may throw a manual OOM before all
         * soft references are collected. See b/322474776.
         */
        @kotlin.concurrent.Volatile
        private var cachedMapping: java.lang.ref.WeakReference<SortedMap<PathFragment?, Artifact?>?>?

        private val buildRunfileLinks: Boolean
        private val runfileSymlinksMode: RunfileSymlinksMode?

        init {
            this.execPath = execPath
            this.runfiles = runfiles
            this.repoMappingManifest = repoMappingManifest
            this.buildRunfileLinks = buildRunfileLinks
            this.runfileSymlinksMode = runfileSymlinksMode
            this.cachedMapping = if (cacheMapping) NOT_YET_COMPUTED else null
        }

        @com.google.common.annotations.VisibleForTesting
        constructor(execPath: PathFragment?, runfiles: com.google.devtools.build.lib.analysis.Runfiles) : this(
            execPath,
            runfiles,  /* repoMappingManifest= */
            null,  /* buildRunfileLinks= */
            false,  /* cacheMapping= */
            false,
            RunfileSymlinksMode.CREATE
        )

        override fun getExecPath(): PathFragment? {
            return execPath
        }

        override fun getMapping(): SortedMap<PathFragment?, Artifact?> {
            if (cachedMapping == null) {
                return runfiles.getRunfilesInputs(repoMappingManifest)
            }

            var result: SortedMap<PathFragment?, Artifact?>? = cachedMapping.get()
            if (result != null) {
                return result
            }

            synchronized(this) {
                result = cachedMapping.get()
                if (result != null) {
                    return result
                }

                result = runfiles.getRunfilesInputs(repoMappingManifest)
                cachedMapping = java.lang.ref.WeakReference<SortedMap<PathFragment?, Artifact?>?>(result)
                return result
            }
        }

        override fun getArtifacts(): NestedSet<Artifact?>? {
            return runfiles.getAllArtifacts()
        }

        override fun getArtifactsAtCanonicalLocationsForLogging(): NestedSet<Artifact?>? {
            return runfiles.getArtifacts()
        }

        override fun getEmptyFilenamesForLogging(): Iterable<PathFragment?>? {
            return runfiles.getEmptyFilenames()
        }

        override fun getSymlinksForLogging(): NestedSet<SymlinkEntry?>? {
            return runfiles.getSymlinks()
        }

        override fun getRootSymlinksForLogging(): NestedSet<SymlinkEntry?>? {
            return runfiles.getRootSymlinks()
        }

        override fun getRepoMappingManifestForLogging(): Artifact? {
            return repoMappingManifest
        }

        override fun isMappingCached(): Boolean {
            return cachedMapping != null
        }

        override fun fingerprint(
            actionKeyContext: ActionKeyContext, fp: Fingerprint, digestAbsolutePaths: Boolean
        ) {
            runfiles.fingerprint(actionKeyContext, fp, digestAbsolutePaths)
        }

        override fun getSymlinksMode(): RunfileSymlinksMode? {
            return runfileSymlinksMode
        }

        override fun isBuildRunfileLinks(): Boolean {
            return buildRunfileLinks
        }

        override fun getWorkspaceName(): String? {
            return runfiles.getPrefix()
        }

        override fun containsConstantMetadata(): Boolean {
            if (cachedMapping != null) {
                val mapping: SortedMap<PathFragment?, Artifact?>? = cachedMapping.get()
                if (mapping != null) {
                    return mapping.values().stream()
                        .anyMatch(java.util.function.Predicate { artifact: Artifact? -> artifact != null && artifact.isConstantMetadata() })
                }
            }
            return getArtifacts().toList().stream().anyMatch(Artifact::isConstantMetadata)
        }

        companion object {
            private val NOT_YET_COMPUTED: java.lang.ref.WeakReference<SortedMap<PathFragment?, Artifact?>?> =
                java.lang.ref.WeakReference<SortedMap<PathFragment?, Artifact?>?>(null)
        }
    }

    private val runfilesInputManifest: Artifact?
    private val runfilesManifest: Artifact?
    private val runfilesTreeArtifact: Artifact
    private val owningExecutable: Artifact?
    private val args: FlatCommandLine?
    private val actionEnvironment: ActionEnvironment?

    init {
        this.runfilesInputManifest = runfilesInputManifest
        this.runfilesManifest = runfilesManifest
        this.runfilesTreeArtifact = runfilesTreeArtifact
        this.owningExecutable = owningExecutable
        this.args = args
        this.actionEnvironment = actionEnvironment
    }

    /** Returns the executable owning this RunfilesSupport.  */
    fun getExecutable(): Artifact? {
        return owningExecutable
    }

    fun getRunfiles(): com.google.devtools.build.lib.analysis.Runfiles {
        return runfilesTree.runfiles
    }

    /**
     * Returns the .runfiles_manifest file outside of the runfiles symlink farm. Returns null if
     * --nobuild_runfile_manifests is in effect.
     * 
     * 
     * The MANIFEST file represents the contents of all of the symlinks in the symlink farm. For
     * efficiency, Blaze's dependency analysis ignores the actual symlinks and just looks at the
     * MANIFEST file. It is an invariant that the MANIFEST file should accurately represent the
     * contents of the symlinks whenever the MANIFEST file is present.
     */
    fun getRunfilesInputManifest(): Artifact? {
        return runfilesInputManifest
    }

    /**
     * Returns the MANIFEST file in the runfiles symlink farm if Bazel is run with
     * --build_runfile_links. Returns the .runfiles_manifest file outside of the symlink farm, if
     * Bazel is run with --nobuild_runfile_links. Returns null if --nobuild_runfile_manifests is
     * passed.
     * 
     * 
     * Beware: In most cases [.getRunfilesInputManifest] is the more appropriate function.
     */
    fun getRunfilesManifest(): Artifact? {
        return runfilesManifest
    }

    /**
     * Returns the foo.repo_mapping file if Bazel is run with transitive package tracking turned on
     * (see `SkyframeExecutor#getForcedSingleSourceRootIfNoExecrootSymlinkCreation`) and any of
     * the transitive packages come from a repository with strict deps (see `#collectRepoMappings`). Otherwise, returns null.
     */
    fun getRepoMappingManifest(): Artifact? {
        return runfilesTree.repoMappingManifest
    }

    /** Returns the root directory of the runfiles symlink farm; otherwise, returns null.  */
    fun getRunfilesDirectory(): Path? {
        return runfilesTreeArtifact.getPath()
    }

    /**
     * Returns the runfiles tree artifact that depends on getExecutable(), getRunfilesManifest(), and
     * getRunfilesSymlinkTargets(). Anything which needs to actually run the executable should depend
     * on this.
     */
    fun getRunfilesTreeArtifact(): Artifact {
        return runfilesTreeArtifact
    }

    /** Returns the unmodifiable list of expanded and tokenized 'args' attribute values.  */
    fun getArgs(): FlatCommandLine? {
        return args
    }

    /** Returns the immutable environment from the 'env' and 'env_inherit' attribute values.  */
    fun getActionEnvironment(): ActionEnvironment? {
        return actionEnvironment
    }

    fun getRunfilesTree(): RunfilesTree {
        return runfilesTree
    }

    companion object {
        private const val RUNFILES_DIR_EXT = ".runfiles"
        const val INPUT_MANIFEST_EXT: String = ".runfiles_manifest"
        private const val OUTPUT_MANIFEST_BASENAME = "MANIFEST"
        private const val REPO_MAPPING_MANIFEST_EXT = ".repo_mapping"

        // Only cache mappings if there is a chance that more than one action will use it within a single
        // build. This helps reduce peak memory usage, especially when the value of --jobs is high, but
        // avoids the additional overhead of a weak reference when it is not needed.
        private fun cacheRunfilesMappings(ruleContext: RuleContext): Boolean {
            if (!TargetUtils.isTestRule(ruleContext.getTarget())) {
                // Runfiles trees of non-test rules are tools and can thus be used by multiple actions.
                return true
            }

            // Test runfiles are only used by a single test runner action unless there are multiple runs or
            // shards.
            if (TestActionBuilder.Companion.getRunsPerTest(ruleContext) > 1) {
                return true
            }

            if (TestActionBuilder.Companion.getShardCount(ruleContext) > 1) {
                return true
            }

            return false
        }

        /**
         * Creates the RunfilesSupport helper with the given executable and runfiles.
         * 
         * @param ruleContext the rule context to create the runfiles support for
         * @param executable the executable for whose runfiles this runfiles support is responsible
         * @param runfiles the runfiles
         */
        private fun create(
            ruleContext: RuleContext,
            executable: Artifact?,
            runfiles: com.google.devtools.build.lib.analysis.Runfiles,
            args: FlatCommandLine?,
            actionEnvironment: ActionEnvironment?
        ): RunfilesSupport {
            var runfiles: com.google.devtools.build.lib.analysis.Runfiles = runfiles
            com.google.common.base.Preconditions.checkNotNull<Any?>(executable)
            val runfileSymlinksMode: RunfileSymlinksMode? =
                ruleContext.getConfiguration().getRunfileSymlinksMode()
            val buildRunfileManifests: Boolean = ruleContext.getConfiguration().buildRunfileManifests()
            val buildRunfileLinks: Boolean = ruleContext.getConfiguration().buildRunfileLinks()

            // Adding run_under target to the runfiles manifest so it would become part
            // of runfiles tree and would be executable everywhere.
            val runUnder: RunUnder? = ruleContext.getConfiguration().getRunUnder()
            if (runUnder is LabelRunUnder && TargetUtils.isTestRule(ruleContext.getRule())) {
                val runUnderTarget: TransitiveInfoCollection? = ruleContext.getRunUnderPrerequisite()
                runfiles =
                    com.google.devtools.build.lib.analysis.Runfiles.Builder(ruleContext.getWorkspaceName())
                        .merge(getRunfiles(runUnderTarget, ruleContext.getWorkspaceName()))
                        .merge(runfiles)
                        .build()
            }
            com.google.common.base.Preconditions.checkState(!runfiles.isEmpty(), "Empty runfiles")

            val repoMappingManifest: Artifact? =
                createRepoMappingManifestAction(ruleContext, runfiles, executable)

            val runfilesTreeArtifact: Artifact = declareRunfilesTreeArtifact(ruleContext, executable)

            val runfilesInputManifest: Artifact?
            val runfilesManifest: Artifact?
            if (buildRunfileManifests) {
                runfilesInputManifest = createRunfilesInputManifestArtifact(ruleContext, executable)
                runfilesManifest =
                    createRunfilesAction(
                        ruleContext,
                        runfiles,
                        runfilesTreeArtifact,
                        buildRunfileLinks,
                        runfilesInputManifest,
                        repoMappingManifest
                    )
            } else {
                runfilesInputManifest = null
                runfilesManifest = null
            }

            val runfilesTree =
                RunfilesTreeImpl(
                    runfilesTreeArtifact.getExecPath(),
                    runfiles,
                    repoMappingManifest,
                    buildRunfileLinks,
                    cacheRunfilesMappings(ruleContext),
                    runfileSymlinksMode
                )

            createRunfilesTreeArtifactAction(
                ruleContext, runfilesTreeArtifact, runfilesTree, runfilesManifest, repoMappingManifest
            )

            return RunfilesSupport(
                runfilesTree,
                runfilesInputManifest,
                runfilesManifest,
                runfilesTreeArtifact,
                executable,
                args,
                actionEnvironment
            )
        }

        /**
         * Helper method that returns a collection of artifacts that are necessary for the runfiles of the
         * given target. Note that the runfile symlink tree is never built, so this may include artifacts
         * that end up not being used (see [Runfiles]).
         * 
         * @return the Runfiles object
         */
        private fun getRunfiles(
            target: TransitiveInfoCollection,
            workspaceName: String?
        ): com.google.devtools.build.lib.analysis.Runfiles? {
            val runfilesProvider: RunfilesProvider? = target.getProvider(RunfilesProvider::class.java)
            if (runfilesProvider != null) {
                return runfilesProvider.getDefaultRunfiles()
            } else {
                return com.google.devtools.build.lib.analysis.Runfiles.Builder(workspaceName)
                    .addTransitiveArtifacts(target.getProvider(FileProvider::class.java).getFilesToBuild())
                    .build()
            }
        }

        private fun createRunfilesInputManifestArtifact(
            context: RuleContext, owningExecutable: Artifact
        ): Artifact? {
            val relativePath: PathFragment =
                owningExecutable.getOutputDirRelativePath(
                    context.getConfiguration().isSiblingRepositoryLayout()
                )
            val basename: String? = relativePath.getBaseName()
            val inputManifestPath: PathFragment? = relativePath.replaceName(basename + INPUT_MANIFEST_EXT)
            return context.getDerivedArtifact(inputManifestPath, context.getBinDirectory())
        }

        private fun declareRunfilesTreeArtifact(
            ruleContext: RuleContext, owningExecutable: Artifact
        ): Artifact {
            val executableRootRelativePath: PathFragment = owningExecutable.getRootRelativePath()
            val runfilesRootRelativePath: PathFragment? =
                executableRootRelativePath.replaceName(
                    executableRootRelativePath.getBaseName() + RUNFILES_DIR_EXT
                )
            return ruleContext
                .getAnalysisEnvironment()
                .getRunfilesArtifact(runfilesRootRelativePath, owningExecutable.getRoot())
        }

        fun createRunfilesTreeArtifactAction(
            context: ActionConstructionContext,
            runfilesTreeArtifact: Artifact,
            runfilesTree: RunfilesTree,
            runfilesManifest: Artifact?,
            repoMappingManifest: Artifact?
        ) {
            val contentsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
            contentsBuilder.addTransitive(runfilesTree.getArtifacts())
            if (runfilesManifest != null) {
                contentsBuilder.add(runfilesManifest)
            }
            if (repoMappingManifest != null) {
                contentsBuilder.add(repoMappingManifest)
            }

            val contents: NestedSet<Artifact?>? = contentsBuilder.build()

            val runfilesTreeAction: RunfilesTreeAction =
                RunfilesTreeAction(
                    context.getActionOwner(),
                    runfilesTree,
                    contents,
                    com.google.common.collect.ImmutableSet.of<E?>(runfilesTreeArtifact)
                )
            context.registerAction(runfilesTreeAction)
        }

        /**
         * Creates a runfiles action for all of the specified files, and returns the output artifact (the
         * artifact for the MANIFEST file).
         * 
         * 
         * The "runfiles" action creates a symlink farm that links all the runfiles (which may come
         * from different places, e.g. different package paths, generated files, etc.) into a single tree,
         * so that programs can access them using the workspace-relative name.
         */
        private fun createRunfilesAction(
            context: ActionConstructionContext,
            runfiles: com.google.devtools.build.lib.analysis.Runfiles?,
            runfilesTreeArtifact: Artifact,
            createSymlinks: Boolean,
            inputManifest: Artifact?,
            repoMappingManifest: Artifact?
        ): Artifact? {
            // Compute the names of the runfiles directory and its MANIFEST file.
            context
                .getAnalysisEnvironment()
                .registerAction(
                    SourceManifestAction(
                        ManifestType.SOURCE_SYMLINKS,
                        context.getActionOwner(),
                        inputManifest,
                        runfiles,
                        repoMappingManifest,
                        context.getConfiguration().remotableSourceManifestActions()
                    )
                )

            if (!createSymlinks) {
                // Just return the manifest if that's all the build calls for.
                return inputManifest
            }

            val runfilesDir: PathFragment =
                runfilesTreeArtifact.getOutputDirRelativePath(
                    context.getConfiguration().isSiblingRepositoryLayout()
                )
            val outputManifestPath: PathFragment? = runfilesDir.getRelative(OUTPUT_MANIFEST_BASENAME)

            val config: BuildConfigurationValue = context.getConfiguration()
            val outputManifest: Artifact? =
                context.getDerivedArtifact(outputManifestPath, context.getBinDirectory())
            context
                .getAnalysisEnvironment()
                .registerAction(
                    SymlinkTreeAction(
                        context.getActionOwner(),
                        config,
                        inputManifest,
                        runfiles,
                        outputManifest,
                        repoMappingManifest
                    )
                )
            return outputManifest
        }

        fun createSymlinkTree(
            ruleContext: RuleContext,
            runfilesTreeArtifact: Artifact,
            runfiles: com.google.devtools.build.lib.analysis.Runfiles
        ) {
            // We always want symlinks to be created because that's the point of a symlink tree.
            val buildRunfilesLinks = true
            val runfilesTree =
                RunfilesTreeImpl(
                    runfilesTreeArtifact.getExecPath(),
                    runfiles,
                    null,
                    buildRunfilesLinks,
                    false,
                    ruleContext.getConfiguration().getRunfileSymlinksMode()
                )
            val rootRelativePath: PathFragment = runfilesTreeArtifact.getRootRelativePath()
            val manifestPath: PathFragment? =
                rootRelativePath.replaceName(rootRelativePath.getBaseName() + ".symlink_tree_manifest")
            val inputManifest: Artifact? =
                ruleContext.getDerivedArtifact(manifestPath, ruleContext.getBinDirectory())
            val runfilesManifest: Artifact? =
                createRunfilesAction(
                    ruleContext, runfiles, runfilesTreeArtifact, buildRunfilesLinks, inputManifest, null
                )
            createRunfilesTreeArtifactAction(
                ruleContext, runfilesTreeArtifact, runfilesTree, runfilesManifest, null
            )
        }

        /**
         * Creates and returns a [RunfilesSupport] object for the given rule and executable. Note
         * that this method calls back into the passed in rule to obtain the runfiles.
         * 
         * 
         * If the executable is a test, runfiles mappings are cached and re-used between shards. It's a
         * win since when there is a large number of test shards and/or runs per test, the same runfiles
         * tree is needed many times.
         */
        @Throws(java.lang.InterruptedException::class)
        fun withExecutable(
            ruleContext: RuleContext,
            runfiles: com.google.devtools.build.lib.analysis.Runfiles,
            executable: Artifact?,
            runEnvironmentInfo: RunEnvironmentInfo?
        ): RunfilesSupport {
            return create(
                ruleContext,
                executable,
                runfiles,
                computeArgs(ruleContext),
                computeActionEnvironment(ruleContext, runEnvironmentInfo)
            )
        }

        @Throws(java.lang.InterruptedException::class)
        private fun computeArgs(ruleContext: RuleContext): FlatCommandLine {
            if (!ruleContext.getRule().isAttrDefined("args", Types.STRING_LIST)) {
                // Some non-_binary rules create RunfilesSupport instances; it is fine to not have an args
                // attribute here.
                return CommandLine.empty()
            }
            val args: com.google.common.collect.ImmutableList<String?> =
                ruleContext.getExpander().withDataLocations().tokenized("args")
            return if (args.isEmpty()) CommandLine.empty() else CommandLine.of(args)
        }

        private fun computeActionEnvironment(
            ruleContext: RuleContext, runEnvironmentInfo: RunEnvironmentInfo?
        ): ActionEnvironment {
            if (runEnvironmentInfo != null) {
                // Must be a Starlark rule.
                return ActionEnvironment.create(
                    runEnvironmentInfo.getEnvironment(),
                    com.google.common.collect.ImmutableSet.copyOf(runEnvironmentInfo.getInheritedEnvironment())
                )
            }

            val isNativeRule =
                ruleContext.getRule().getRuleClassObject().getRuleDefinitionEnvironmentLabel() == null
            if (!isNativeRule) {
                return ActionEnvironment.EMPTY
            }

            val envAttrDefined: Boolean = ruleContext.getRule().isAttrDefined("env", Types.STRING_DICT)
            val envInheritAttrDefined: Boolean =
                ruleContext.getRule().isAttrDefined("env_inherit", Types.STRING_LIST)
            if (!envAttrDefined && !envInheritAttrDefined) {
                return ActionEnvironment.EMPTY
            }

            val fixedEnv: TreeMap<String?, String?> = TreeMap<String?, String?>()
            val inheritedEnv: MutableSet<String?> = LinkedHashSet<String?>()
            if (envAttrDefined) {
                val expander: com.google.devtools.build.lib.analysis.Expander =
                    ruleContext.getExpander().withDataLocations()
                for (entry in ruleContext.attributes().get("env", Types.STRING_DICT).entrySet()) {
                    fixedEnv.put(entry.getKey(), expander.expand("env", entry.getValue()))
                }
            }
            if (envInheritAttrDefined) {
                for (key in ruleContext.attributes().get("env_inherit", Types.STRING_LIST)) {
                    if (!fixedEnv.containsKey(key)) {
                        inheritedEnv.add(key)
                    }
                }
            }
            return ActionEnvironment.create(
                com.google.common.collect.ImmutableMap.< K,
                V > copyOf<K?, V?>(fixedEnv),
                com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (inheritedEnv)
            )
        }

        /** Returns the exec path of the `.runfiles` directory for the given executable.  */
        fun runfilesDirExecPath(executable: Artifact): PathFragment {
            val executablePath: PathFragment = executable.getExecPath()
            return executablePath.replaceName(executablePath.getBaseName() + RUNFILES_DIR_EXT)
        }

        /**
         * Returns the exec path of the corresponding `.runfiles_manifest` file for the given `.runfiles` directory.
         * 
         * 
         * The input manifest is produced by [SourceManifestAction] and is an input to [ ].
         */
        fun inputManifestExecPath(runfilesDirExecPath: PathFragment?): PathFragment {
            return FileSystemUtils.replaceExtension(runfilesDirExecPath, INPUT_MANIFEST_EXT)
        }

        /**
         * Returns the exec path of the corresponding `MANIFEST` file for the given `.runfiles` directory.
         * 
         * 
         * The output manifest is a symlink to the [input manifest][.inputManifestExecPath].
         * It is located in the `.runfiles` directory and is the output of [ ].
         */
        fun outputManifestExecPath(runfilesDirExecPath: PathFragment): PathFragment {
            return runfilesDirExecPath.getRelative(OUTPUT_MANIFEST_BASENAME)
        }

        private fun createRepoMappingManifestAction(
            ruleContext: RuleContext,
            runfiles: com.google.devtools.build.lib.analysis.Runfiles,
            owningExecutable: Artifact?
        ): Artifact? {
            if (ruleContext.getTransitivePackagesForRunfileRepoMappingManifest() == null) {
                // If transitive packages are not tracked for repo mapping manifest, we don't need the action.
                return null
            }

            val executablePath: PathFragment =
                if (owningExecutable != null)
                    owningExecutable.getOutputDirRelativePath(
                        ruleContext.getConfiguration().isSiblingRepositoryLayout()
                    )
                else
                    ruleContext.getPackageDirectory().getRelative(ruleContext.getLabel().getName())
            val repoMappingManifest: Artifact? =
                ruleContext.getDerivedArtifact(
                    executablePath.replaceName(executablePath.getBaseName() + REPO_MAPPING_MANIFEST_EXT),
                    ruleContext.getBinDirectory()
                )
            ruleContext
                .getAnalysisEnvironment()
                .registerAction(
                    RepoMappingManifestAction(
                        ruleContext.getActionOwner(),
                        repoMappingManifest,
                        ruleContext.getTransitivePackagesForRunfileRepoMappingManifest(),
                        runfiles.getArtifacts(),
                        runfiles.getSymlinks(),
                        runfiles.getRootSymlinks(),
                        ruleContext.getWorkspaceName(),
                        ruleContext
                            .getConfiguration()
                            .getOptions()
                            .get<T?>(CoreOptions::class.java)
                            .getCompactRepoMapping()
                    )
                )
            return repoMappingManifest
        }
    }
}
