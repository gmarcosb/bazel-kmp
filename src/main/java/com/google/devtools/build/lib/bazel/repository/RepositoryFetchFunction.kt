// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository

import com.google.devtools.build.lib.actions.FileStateValue

/** A [SkyFunction] that fetches the given repository.  */
class RepositoryFetchFunction(
    repoEnvSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableMap<String?, String?>?>,
    nonstrictRepoEnvSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableMap<String?, String?>?>,
    directories: BlazeDirectories,
    repoContentsCache: LocalRepoContentsCache
) : SkyFunction {
    private val directories: BlazeDirectories
    private val repoContentsCache: LocalRepoContentsCache
    private val repoEnvSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableMap<String?, String?>?>
    private val nonstrictRepoEnvSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableMap<String?, String?>?>

    private var timeoutScaling = 1.0
    private var downloadManager: DownloadManager? = null
    private var processWrapper: ProcessWrapper? = null
    private var repositoryRemoteExecutor: RepositoryRemoteExecutor? = null
    private var remoteRepoContentsCache: RemoteRepoContentsCache? = null
    private var syscallCache: SyscallCache? = null

    init {
        this.repoEnvSupplier = repoEnvSupplier
        this.nonstrictRepoEnvSupplier = nonstrictRepoEnvSupplier
        this.directories = directories
        this.repoContentsCache = repoContentsCache
    }

    fun setTimeoutScaling(timeoutScaling: Double) {
        this.timeoutScaling = timeoutScaling
    }

    fun setDownloadManager(downloadManager: DownloadManager?) {
        this.downloadManager = downloadManager
    }

    fun setProcessWrapper(processWrapper: ProcessWrapper?) {
        this.processWrapper = processWrapper
    }

    fun setSyscallCache(syscallCache: SyscallCache?) {
        this.syscallCache = com.google.common.base.Preconditions.checkNotNull<SyscallCache?>(syscallCache)
    }

    fun setRepositoryRemoteExecutor(repositoryRemoteExecutor: RepositoryRemoteExecutor?) {
        this.repositoryRemoteExecutor = repositoryRemoteExecutor
    }

    fun setRemoteRepoContentsCache(remoteRepoContentsCache: RemoteRepoContentsCache?) {
        this.remoteRepoContentsCache = remoteRepoContentsCache
    }

    @Throws(java.lang.InterruptedException::class, RepositoryFunctionException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? = PrecomputedValue.STARLARK_SEMANTICS.get(env)
        if (starlarkSemantics == null) {
            return null
        }

        val repositoryName: RepositoryName = skyKey.argument() as RepositoryName
        if (!repositoryName.isVisible()) {
            return com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Failure(
                java.lang.String.format(
                    "No repository visible as '@%s' from %s",
                    repositoryName.getName(), repositoryName.getContextRepoDisplayString()
                )
            )
        }

        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile(com.google.devtools.build.lib.profiler.ProfilerTask.REPOSITORY_FETCH, repositoryName.toString())
            .use { c ->
                val repoRoot: com.google.devtools.build.lib.vfs.Path =
                    RepositoryUtils.getExternalRepositoryDirectory(directories)
                        .getRelative(repositoryName.getName())
                val repoDefinition: RepoDefinition
                when (env.getValue(RepoDefinitionValue.Companion.key(repositoryName)) as RepoDefinitionValue?) {
                    null -> {
                        return null
                    }

                    -> {
                        return com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Failure(
                            java.lang.String.format(
                                "Repository '%s' is not defined",
                                repositoryName
                            )
                        )
                    }

                    -> {
                        return setupOverride(repoPath, env, repoRoot, repositoryName)
                    }

                    -> {
                        repoDefinition = rd
                    }
                }

                // See below (the `catch CancellationException` clause) for why there's a `while` loop here.
                while (true) {
                    val state: WorkerSkyKeyComputeState<RepositoryDirectoryValue?> =
                        env.getState<WorkerSkyKeyComputeState<RepositoryDirectoryValue?>>(java.util.function.Supplier { WorkerSkyKeyComputeState<Any?>() })
                    try {
                        return state.startOrContinueWork(
                            env,
                            "starlark-repository-" + repositoryName.getName(),
                            WorkerCallable { workerEnv: SkyFunction.Environment? ->
                                computeInternal(
                                    workerEnv, repositoryName, starlarkSemantics, repoRoot, repoDefinition
                                )
                            })
                    } catch (e: ExecutionException) {
                        com.google.common.base.Throwables.throwIfInstanceOf<RepositoryFunctionException?>(
                            e.getCause(),
                            RepositoryFunctionException::class.java
                        )
                        com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                            e.getCause(),
                            java.lang.InterruptedException::class.java
                        )
                        com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
                        throw java.lang.IllegalStateException(
                            "unexpected exception type: " + e.getCause().getClass(), e.getCause()
                        )
                    } catch (e: CancellationException) {
                        // This can only happen if the state object was invalidated due to memory pressure, in
                        // which case we can simply reattempt the fetch. Show a message and continue into the next
                        // `while` iteration.
                        env.getListener()
                            .post(
                                RepositoryFetchProgress.ongoing(
                                    repositoryName, "fetch interrupted due to memory pressure; restarting."
                                )
                            )
                    }
                }
            }
    }

    /**
     * The actual SkyFunction logic, run in a worker thread. Note that, although the worker thread
     * never sees Skyframe restarts, `env.valuesMissing()` can still be true due to deps in
     * error. So this function still needs to return `null` when appropriate. See Javadoc of
     * [com.google.devtools.build.skyframe.WorkerSkyFunctionEnvironment] for more information.
     */
    @Throws(java.lang.InterruptedException::class, RepositoryFunctionException::class)
    private fun computeInternal(
        env: SkyFunction.Environment,
        repositoryName: RepositoryName,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
        repoRoot: com.google.devtools.build.lib.vfs.Path,
        repoDefinition: RepoDefinition
    ): RepositoryDirectoryValue? {
        val digestWriter: DigestWriter? =
            DigestWriter.Companion.create(env, directories, repositoryName, repoDefinition, starlarkSemantics)
        if (digestWriter == null) {
            return null
        }

        var excludeRepoFromVendoring = true
        if (RepositoryDirectoryValue.VENDOR_DIRECTORY.get(env).isPresent()) { // If vendor mode is on
            val vendorFile: VendorFileValue? = env.getValue(VendorFileValue.KEY) as VendorFileValue?
            if (env.valuesMissing()) {
                return null
            }
            val excludeRepoByDefault = isRepoExcludedFromVendoringByDefault(repoDefinition)
            if (!excludeRepoByDefault && !vendorFile.ignoredRepos().contains(repositoryName)) {
                val repositoryDirectoryValue: RepositoryDirectoryValue? =
                    tryGettingValueUsingVendoredRepo(
                        env, repoRoot, repositoryName, digestWriter, vendorFile
                    )
                if (env.valuesMissing()) {
                    return null
                }
                if (repositoryDirectoryValue != null) {
                    return repositoryDirectoryValue
                }
            }
            excludeRepoFromVendoring =
                excludeRepoByDefault
                        || vendorFile.ignoredRepos().contains(repositoryName)
                        || vendorFile.pinnedRepos().contains(repositoryName)
        }

        if (shouldUseCachedRepoContents(env, repoDefinition)) {
            // Make sure marker file is up-to-date; correctly describes the current repository state
            if (digestWriter.areRepositoryAndMarkerFileConsistent(env).isEmpty()) {
                return com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Success(
                    Root.fromPath(
                        repoRoot
                    ), excludeRepoFromVendoring
                )
            }
            if (env.valuesMissing()) {
                return null
            }

            // Then check if the local repo contents cache has this.
            if (repoContentsCache.isEnabled()) {
                val candidateRepos: com.google.common.collect.ImmutableList<CandidateRepo> =
                    repoContentsCache.getCandidateRepos(digestWriter.predeclaredInputHash)
                for (candidate in candidateRepos) {
                    if (digestWriter
                            .areRepositoryAndMarkerFileConsistent(env, candidate.recordedInputsFile)
                            .isEmpty()
                    ) {
                        if (setupOverride(candidate.contentsDir.asFragment(), env, repoRoot, repositoryName)
                            == null
                        ) {
                            return null
                        }
                        candidate.touch()
                        return com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Success(
                            Root.fromPath(
                                repoRoot
                            ), excludeRepoFromVendoring
                        )
                    }
                    if (env.valuesMissing()) {
                        return null
                    }
                }
            }

            if (remoteRepoContentsCache != null) {
                try {
                    val cacheHit: Boolean =
                        remoteRepoContentsCache.lookupCache(
                            repositoryName, repoRoot, digestWriter.predeclaredInputHash, env
                        )
                    if (env.valuesMissing()) {
                        return null
                    }
                    if (cacheHit) {
                        return com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Success(
                            Root.fromPath(
                                repoRoot
                            ), excludeRepoFromVendoring
                        )
                    }
                } catch (e: IOException) {
                    env.getListener()
                        .handle(
                            com.google.devtools.build.lib.events.Event.warn(
                                "Remote repo contents cache lookup failed for %s: %s"
                                    .formatted(repositoryName, e.getMessage())
                            )
                        )
                }
            }
        }

        /* At this point: This is a force fetch, a local repository, OR The repository cache is old or
    didn't exist. In any of those cases, we initiate the fetching process UNLESS this is offline
    mode (fetching is disabled) */
        if (!RepositoryDirectoryValue.FETCH_DISABLED.get(env)) {
            // Fetching a repository is a long-running operation that can easily be interrupted. If it
            // is and the marker file exists on disk, a new call of this method may treat this
            // repository as valid even though it is in an inconsistent state. Clear the marker file and
            // only recreate it after fetching is done to prevent this scenario.
            DigestWriter.Companion.clearMarkerFile(directories, repositoryName)
            val result = fetchAndHandleEvents(repoDefinition, repoRoot, env, repositoryName)
            if (result == null) {
                return null
            }
            digestWriter.writeMarkerFile(result.recordedInputValues)
            if (result.reproducible == Reproducibility.YES && !repoDefinition.repoRule.local) {
                // This repo is eligible for the local and remote repo contents cache.
                // Replant symlinks before caching to convert absolute symlinks pointing to the
                // workspace or external root into relative paths, making the cached repo portable.
                val externalRepoRoot: com.google.devtools.build.lib.vfs.Path =
                    RepositoryUtils.getExternalRepositoryDirectory(directories)
                val safeForLocalCacheReuse: Boolean
                try {
                    safeForLocalCacheReuse =
                        RepositoryUtils.replantSymlinks(
                            repoRoot,
                            directories.getWorkspace(),
                            externalRepoRoot,
                            PathFragment.EMPTY_FRAGMENT
                        )
                } catch (e: IOException) {
                    throw RepositoryFunctionException(
                        IOException(
                            "error replanting symlinks in repo %s before caching: %s"
                                .formatted(repositoryName, e.getMessage()),
                            e
                        ),
                        Transience.TRANSIENT
                    )
                }
                if (remoteRepoContentsCache != null) {
                    remoteRepoContentsCache.addToCache(
                        repositoryName,
                        repoRoot,
                        digestWriter.markerPath,
                        digestWriter.predeclaredInputHash,
                        env.getListener()
                    )
                }
                if (safeForLocalCacheReuse && repoContentsCache.isEnabled()) {
                    val newCacheEntry: CandidateRepo?
                    try {
                        newCacheEntry =
                            repoContentsCache.moveToCache(
                                repoRoot, digestWriter.markerPath, digestWriter.predeclaredInputHash
                            )
                    } catch (e: IOException) {
                        throw RepositoryFunctionException(
                            IOException(
                                "error moving repo %s into the repo contents cache: %s"
                                    .formatted(repositoryName, e.getMessage()),
                                e
                            ),
                            Transience.TRANSIENT
                        )
                    }
                    val cachedRepoDir: com.google.devtools.build.lib.vfs.Path = newCacheEntry.contentsDir
                    val cachedRepoDirRootedPath: RootedPath? =
                        RootedPath.toRootedPath(
                            Root.absoluteRoot(cachedRepoDir.getFileSystem()), cachedRepoDir
                        )
                    // Don't forget to register a FileStateValue on the cache repo dir, so that we know to
                    // refetch if the cache entry gets GC'd from under us or the entire cache is deleted.
                    //
                    // Note that registering a FileValue dependency instead would lead to subtly incorrect
                    // behavior when the repo contents cache directory is deleted between builds:
                    // 1. We register a FileValue dependency on the cache entry.
                    // 2. Before the next build, the repo contents cache directory is deleted.
                    // 3. On the next build, FileSystemValueChecker invalidates the underlying
                    //    FileStateValue, which in turn results in the FileValue and the current
                    //    RepositoryDirectoryValue being marked as dirty.
                    // 4. Skyframe visits the dirty nodes bottom up to check for actual changes. In
                    //    particular, it reevaluates FileFunction before RepositoryFetchFunction and thus
                    //    the FileValue of the repo contents cache directory is locked in as non-existent
                    //    before RepositoryFetchFunction can recreate it.
                    // 5. Any other SkyFunction that depends on the FileValue of a file in the repo (e.g.
                    //    PackageFunction) will report that file as missing since the resolved path has a
                    //    parent that is non-existent.
                    // By using FileStateValue directly, which benefits from special logic built into
                    // DirtinessCheckerUtils that recognizes the repo contents cache directories with
                    // non-UUID names and prevents locking in their value during dirtiness checking, we
                    // avoid 4. and thus the incorrect missing file errors in 5.
                    if (env.getValue(FileStateValue.key(cachedRepoDirRootedPath)) == null) {
                        return null
                    }
                }
            }
            return com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Success(
                Root.fromPath(
                    repoRoot
                ), excludeRepoFromVendoring
            )
        }

        if (!repoRoot.exists()) {
            // The repository isn't on the file system, there is nothing we can do.
            throw RepositoryFunctionException(
                IOException(
                    ("to fix, run\n\tbazel fetch //...\nExternal repository "
                            + repositoryName
                            + " not found and fetching repositories is disabled.")
                ),
                Transience.TRANSIENT
            )
        }

        // Try to build with whatever is on the file system and emit a warning.
        env.getListener()
            .handle(
                com.google.devtools.build.lib.events.Event.warn(
                    java.lang.String.format(
                        "External repository '%s' is not up-to-date and fetching is disabled. To"
                                + " update, run the build without the '--nofetch' command line option.",
                        repositoryName
                    )
                )
            )

        return com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Success(
            Root.fromPath(repoRoot),
            excludeRepoFromVendoring
        )
    }

    @Throws(RepositoryFunctionException::class, java.lang.InterruptedException::class)
    private fun tryGettingValueUsingVendoredRepo(
        env: SkyFunction.Environment,
        repoRoot: com.google.devtools.build.lib.vfs.Path,
        repositoryName: RepositoryName,
        digestWriter: DigestWriter,
        vendorFile: VendorFileValue
    ): RepositoryDirectoryValue? {
        val vendorPath: com.google.devtools.build.lib.vfs.Path =
            RepositoryDirectoryValue.VENDOR_DIRECTORY.get(env).get()
        val vendorRepoPath: com.google.devtools.build.lib.vfs.Path = vendorPath.getRelative(repositoryName.getName())
        if (vendorRepoPath.exists()) {
            val vendorMarker: com.google.devtools.build.lib.vfs.Path =
                vendorPath.getChild(repositoryName.getMarkerFileName())
            if (vendorFile.pinnedRepos().contains(repositoryName)) {
                // pinned repos are used as they are without checking their marker file
                try {
                    // delete the marker as it may become out-of-date while it's pinned (old version or
                    // manual changes)
                    vendorMarker.delete()
                } catch (e: IOException) {
                    throw RepositoryFunctionException(e, Transience.TRANSIENT)
                }
                return setupOverride(vendorRepoPath.asFragment(), env, repoRoot, repositoryName)
            }

            val vendoredRepoOutOfDateReason: java.util.Optional<String?> =
                digestWriter.areRepositoryAndMarkerFileConsistent(env, vendorMarker)
            if (env.valuesMissing()) {
                return null
            }
            // If our repo is up-to-date, or this is an offline build (--nofetch), then the vendored repo
            // is used.
            if (vendoredRepoOutOfDateReason.isEmpty()
                || (!RepositoryDirectoryValue.IS_VENDOR_COMMAND.get(env)
                        && RepositoryDirectoryValue.FETCH_DISABLED.get(env))
            ) {
                if (vendoredRepoOutOfDateReason.isPresent()) {
                    env.getListener()
                        .handle(
                            com.google.devtools.build.lib.events.Event.warn(
                                java.lang.String.format(
                                    ("Vendored repository '%s' is out-of-date (%s) and fetching is disabled."
                                            + " Run build without the '--nofetch' option or run"
                                            + " the bazel vendor command to update it"),
                                    repositoryName.getName(), vendoredRepoOutOfDateReason.get()
                                )
                            )
                        )
                }
                return setupOverride(vendorRepoPath.asFragment(), env, repoRoot, repositoryName)
            } else if (!RepositoryDirectoryValue.IS_VENDOR_COMMAND
                    .get(env)
            ) { // build command & fetch enabled
                // We will continue fetching but warn the user that we are not using the vendored repo
                env.getListener()
                    .handle(
                        com.google.devtools.build.lib.events.Event.warn(
                            java.lang.String.format(
                                ("Vendored repository '%s' is out-of-date (%s). The up-to-date version will"
                                        + " be fetched into the external cache and used. To update the repo"
                                        + " in the vendor directory, run the bazel vendor command"),
                                repositoryName.getName(), vendoredRepoOutOfDateReason.get()
                            )
                        )
                    )
            }
        } else if (vendorFile.pinnedRepos().contains(repositoryName)) {
            throw RepositoryFunctionException(
                IOException(
                    ("Pinned repository "
                            + repositoryName.getName()
                            + " not found under the vendor directory")
                ),
                Transience.PERSISTENT
            )
        } else if (RepositoryDirectoryValue.FETCH_DISABLED.get(env)) {
            // repo not vendored & fetching is disabled (--nofetch)
            throw RepositoryFunctionException(
                IOException(
                    ("Vendored repository "
                            + repositoryName.getName()
                            + " not found under the vendor directory and fetching is disabled."
                            + " To fix, run the bazel vendor command or build without the '--nofetch'")
                ),
                Transience.TRANSIENT
            )
        }
        return null
    }

    /**
     * Determines whether we should use cache repo contents (either the one in `$outputBase/external` or any matching entry in the repo contents cache).
     */
    @Throws(java.lang.InterruptedException::class)
    private fun shouldUseCachedRepoContents(env: SkyFunction.Environment, repoDefinition: RepoDefinition): Boolean {
        /* If fetching is enabled & this is a local repo: do NOT use cache!
     * Local repository are generally fast and do not rely on non-local data, making caching them
     * across server instances impractical. */
        if (!RepositoryDirectoryValue.FETCH_DISABLED.get(env) && repoDefinition.repoRule.local) {
            return false
        }

        val forceFetchEnabled: Boolean = !RepositoryDirectoryValue.FORCE_FETCH.get(env).isEmpty()
        val forceFetchConfigureEnabled =
            repoDefinition.repoRule.configure
                    && !RepositoryDirectoryValue.FORCE_FETCH_CONFIGURE.get(env).isEmpty()

        /* For the non-local repositories, do NOT use cache if:
     * 1) Force fetch is enabled (bazel sync, or bazel fetch --force), OR
     * 2) Force fetch configure is enabled (bazel sync --configure) */
        if (forceFetchEnabled || forceFetchConfigureEnabled) {
            return false
        }

        return true
    }

    private fun isRepoExcludedFromVendoringByDefault(repoDefinition: RepoDefinition): Boolean {
        return repoDefinition.repoRule.local || repoDefinition.repoRule.configure
    }

    @Throws(java.lang.InterruptedException::class, RepositoryFunctionException::class)
    private fun fetchAndHandleEvents(
        repoDefinition: RepoDefinition,
        repoRoot: com.google.devtools.build.lib.vfs.Path,
        env: SkyFunction.Environment,
        repoName: RepositoryName
    ): FetchResult? {
        env.getListener().post(RepositoryFetchProgress.ongoing(repoName, "starting"))

        val result: FetchResult?
        try {
            result = fetch(repoDefinition, repoRoot, env, repoName)
        } catch (e: RepositoryFunctionException) {
            // Upon an exceptional exit, the fetching of that repository is over as well.
            env.getListener().post(RepositoryFetchProgress.finished(repoName))
            env.getListener().post(RepositoryFailedEvent(repoName, e.getMessage()))

            if (e.getCause() is AlreadyReportedException) {
                throw e
            }
            env.getListener()
                .handle(
                    com.google.devtools.build.lib.events.Event.error(
                        java.lang.String.format(
                            "fetching %s: %s",
                            repoDefinition.name,
                            e.getMessage()
                        )
                    )
                )

            // Rewrap the underlying exception to signal callers not to re-report this error.
            throw RepositoryFunctionException(
                AlreadyReportedRepositoryAccessException(e.getCause()),
                if (e.isTransient()) Transience.TRANSIENT else Transience.PERSISTENT
            )
        }

        if (env.valuesMissing()) {
            return null
        }
        env.getListener().post(RepositoryFetchProgress.finished(repoName))
        return com.google.common.base.Preconditions.checkNotNull<FetchResult?>(result)
    }

    /**
     * The result of the [.fetch] method.
     * 
     * @param recordedInputValues Any recorded inputs (and their values) encountered during the fetch
     * of the repo. Changes to these inputs will result in the repo being refetched in the future.
     * @param reproducible Whether the fetched repo contents are reproducible, hence cacheable.
     */
    private class FetchResult(
        recordedInputValues: com.google.common.collect.ImmutableList<WithValue?>?,
        reproducible: Reproducibility?
    ) {
        val recordedInputValues: com.google.common.collect.ImmutableList<WithValue?>?
        val reproducible: Reproducibility?

        init {
            this.recordedInputValues = recordedInputValues
            this.reproducible = reproducible
        }
    }

    @Throws(RepositoryFunctionException::class, java.lang.InterruptedException::class)
    private fun fetch(
        repoDefinition: RepoDefinition,
        outputDirectory: com.google.devtools.build.lib.vfs.Path,
        env: SkyFunction.Environment,
        repoName: RepositoryName
    ): FetchResult? {
        setupRepoRoot(outputDirectory)

        val defInfo: String? = RepositoryResolvedEvent.Companion.getRuleDefinitionInformation(repoDefinition)
        env.getListener()
            .post(StarlarkRepositoryDefinitionLocationEvent(repoDefinition.name, defInfo))

        val function: net.starlark.java.eval.StarlarkCallable? = repoDefinition.repoRule.impl
        val envVarValues: com.google.common.collect.ImmutableMap<String?, java.util.Optional<String?>?>? =
            RepoEnvironmentFunction.getEnvironmentView(env, repoDefinition.repoRule.environ)
        if (envVarValues == null) {
            return null
        }
        val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? = PrecomputedValue.STARLARK_SEMANTICS.get(env)
        if (env.valuesMissing()) {
            return null
        }

        val packageLocator: PathPackageLocator? = PrecomputedValue.PATH_PACKAGE_LOCATOR.get(env)
        if (env.valuesMissing()) {
            return null
        }

        val mainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?
        if (NonRegistryOverride.BOOTSTRAP_REPO_RULES.contains(repoDefinition.repoRule.id)) {
            // Avoid a cycle.
            mainRepoMapping = null
        } else {
            val mainRepoMappingValue: RepositoryMappingValue? =
                env.getValue(RepositoryMappingValue.key(RepositoryName.Companion.MAIN)) as RepositoryMappingValue?
            if (mainRepoMappingValue == null) {
                return null
            }
            mainRepoMapping = mainRepoMappingValue.repositoryMapping
        }

        val ignoredSubdirectories: IgnoredSubdirectoriesValue? =
            env.getValue(IgnoredSubdirectoriesValue.key()) as IgnoredSubdirectoriesValue?
        if (env.valuesMissing()) {
            return null
        }

        val recordedInputValues: com.google.common.collect.ImmutableList<WithValue?>?
        val repoMetadata: RepoMetadata?
        try {
            net.starlark.java.eval.Mutability.create("Starlark repository").use { mu ->
                StarlarkRepositoryContext(
                    repoDefinition,
                    packageLocator,
                    outputDirectory,
                    ignoredSubdirectories.asIgnoredSubdirectories(),
                    env,
                    repoEnvSupplier.get(),
                    com.google.common.collect.ImmutableMap.copyOf<String?, String?>(nonstrictRepoEnvSupplier.get()),
                    downloadManager,
                    timeoutScaling,
                    processWrapper,
                    starlarkSemantics,
                    repositoryRemoteExecutor,
                    syscallCache,
                    directories
                ).use { starlarkRepositoryContext ->
                    val thread: net.starlark.java.eval.StarlarkThread =
                        net.starlark.java.eval.StarlarkThread.create(
                            mu,
                            starlarkSemantics,
                            "repository " + repoName.getDisplayForm(mainRepoMapping),
                            net.starlark.java.eval.SymbolGenerator.create<String?>("fetching " + repoName)
                        )
                    thread.setPrintHandler(com.google.devtools.build.lib.events.Event.makeDebugPrintHandler(env.getListener()))
                    starlarkRepositoryContext.storeRepoMappingRecorderInThread(thread)

                    // We sort of want a starlark thread context here, but no extra info is needed. So we just
                    // use an anonymous class.
                    object : StarlarkThreadContext(InterruptibleSupplier { mainRepoMapping }) {}.storeInThread(thread)
                    if (starlarkRepositoryContext.isRemotable()) {
                        // If a rule is declared remotable then invalidate it if remote execution gets
                        // enabled or disabled.
                        PrecomputedValue.REMOTE_EXECUTION_ENABLED.get(env)
                    }

                    // This rule is mainly executed for its side effect. Nevertheless, the return value is
                    // of importance, as it provides information on how the call has to be modified to be a
                    // reproducible rule.
                    //
                    // Also we do a lot of stuff in there, maybe blocking operations and we should certainly make
                    // it possible to return null and not block but it doesn't seem to be easy with Starlark
                    // structure as it is.
                    val result: Any?
                    com.google.devtools.build.lib.profiler.Profiler.instance().profile(
                        com.google.devtools.build.lib.profiler.ProfilerTask.STARLARK_REPOSITORY_FN,
                        repoDefinition::name
                    ).use { c ->
                        result = net.starlark.java.eval.Starlark.positionalOnlyCall(
                            thread,
                            function,
                            starlarkRepositoryContext
                        )
                        starlarkRepositoryContext.markSuccessful()
                    }
                    repoMetadata =
                        when (result) {
                            -> RepoMetadata(
                                Reproducibility.NO,
                                net.starlark.java.eval.Dict.cast<String?, Any?>(
                                    dict,
                                    String::class.java,
                                    Any::class.java,
                                    "return value"
                                )
                            )

                            -> rm
                            else -> RepoMetadata.Companion.NONREPRODUCIBLE
                        }
                    val resolved: RepositoryResolvedEvent =
                        RepositoryResolvedEvent(repoDefinition, repoMetadata.attrsForReproducibility)
                    if (resolved.isNewInformationReturned()) {
                        // TODO: https://github.com/bazelbuild/bazel/issues/26511 - printing this information isn't
                        //  super useful, as it's often not actionable. Figure out what to do instead.
                        env.getListener()
                            .handle(com.google.devtools.build.lib.events.Event.debug(resolved.getMessage()))
                        env.getListener().handle(com.google.devtools.build.lib.events.Event.debug(defInfo))
                    }
                    recordedInputValues = starlarkRepositoryContext.getRecordedInputs()
                }
            }
        } catch (e: NeedsSkyframeRestartException) {
            return null
        } catch (e: net.starlark.java.eval.EvalException) {
            env.getListener()
                .handle(
                    com.google.devtools.build.lib.events.Event.error(
                        e.getInnermostLocation(),
                        ("An error occurred during the fetch of repository '"
                                + repoDefinition.name
                                + "':\n   "
                                + e.getMessageWithStack())
                    )
                )
            env.getListener()
                .handle(
                    com.google.devtools.build.lib.events.Event.info(
                        RepositoryResolvedEvent.Companion.getRuleDefinitionInformation(
                            repoDefinition
                        )
                    )
                )

            throw RepositoryFunctionException(
                AlreadyReportedRepositoryAccessException(e), Transience.TRANSIENT
            )
        } catch (e: IOException) {
            throw RepositoryFunctionException(e, Transience.TRANSIENT)
        }

        if (!outputDirectory.isDirectory()) {
            throw RepositoryFunctionException(
                IOException(repoDefinition.name + " must create a directory"),
                Transience.TRANSIENT
            )
        }

        // Make sure the fetched repo has a boundary file.
        if (!RepositoryUtils.isValidRepoRoot(outputDirectory)) {
            if (outputDirectory.isSymbolicLink()) {
                // The created repo is actually just a symlink to somewhere else (think local_repository).
                // In this case, we shouldn't try to create the repo boundary file ourselves, but report an
                // error instead.
                throw RepositoryFunctionException(
                    IOException(
                        "No MODULE.bazel, REPO.bazel, or WORKSPACE file found in " + outputDirectory
                    ),
                    Transience.TRANSIENT
                )
            }
            // Otherwise, we can just create an empty REPO.bazel file.
            try {
                com.google.devtools.build.lib.vfs.FileSystemUtils.createEmptyFile(
                    outputDirectory.getRelative(
                        LabelConstants.REPO_FILE_NAME
                    )
                )
            } catch (e: IOException) {
                throw RepositoryFunctionException(e, Transience.TRANSIENT)
            }
        }

        return FetchResult(recordedInputValues, repoMetadata.reproducible)
    }

    @Throws(RepositoryFunctionException::class, java.lang.InterruptedException::class)
    private fun setupOverride(
        sourcePath: PathFragment?,
        env: SkyFunction.Environment,
        repoRoot: com.google.devtools.build.lib.vfs.Path,
        repoName: RepositoryName
    ): RepositoryDirectoryValue? {
        DigestWriter.Companion.clearMarkerFile(directories, repoName)
        return symlinkRepoRoot(
            directories,
            repoRoot,
            directories.getWorkspace().getRelative(sourcePath),
            repoName.getName(),
            env
        )
    }

    companion object {
        @Throws(RepositoryFunctionException::class)
        private fun setupRepoRoot(repoRoot: com.google.devtools.build.lib.vfs.Path) {
            try {
                repoRoot.deleteTree()
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(repoRoot.getParentDirectory())
                    .createDirectoryAndParents()
            } catch (e: IOException) {
                throw RepositoryFunctionException(e, Transience.TRANSIENT)
            }
        }

        @Throws(RepositoryFunctionException::class, java.lang.InterruptedException::class)
        fun symlinkRepoRoot(
            directories: BlazeDirectories,
            source: com.google.devtools.build.lib.vfs.Path,
            destination: com.google.devtools.build.lib.vfs.Path,
            userDefinedPath: String?,
            env: SkyFunction.Environment
        ): RepositoryDirectoryValue? {
            if (source.isDirectory(Symlinks.NOFOLLOW)) {
                try {
                    source.deleteTree()
                } catch (e: IOException) {
                    throw RepositoryFunctionException(e, Transience.TRANSIENT)
                }
            }
            try {
                com.google.devtools.build.lib.vfs.FileSystemUtils.ensureSymbolicLink(source, destination)
            } catch (e: IOException) {
                throw RepositoryFunctionException(
                    IOException(
                        java.lang.String.format(
                            "Could not create symlink to repository \"%s\" (absolute path: \"%s\"): %s",
                            userDefinedPath, destination, e.getMessage()
                        ),
                        e
                    ),
                    Transience.TRANSIENT
                )
            }

            // Check that the target directory exists and is a directory.
            // Note that we have to check `destination` and not `source` here, otherwise we'd have a
            // circular dependency between SkyValues.
            val targetDirRootedPath: RootedPath?
            if (destination.startsWith(directories.getInstallBase())) {
                // The install base only changes with the Bazel binary so it's acceptable not to add its
                // ancestors as Skyframe dependencies.
                targetDirRootedPath =
                    RootedPath.toRootedPath(Root.fromPath(destination), PathFragment.EMPTY_FRAGMENT)
            } else {
                targetDirRootedPath =
                    RootedPath.toRootedPath(Root.absoluteRoot(destination.getFileSystem()), destination)
            }

            val targetDirValue: FileValue?
            try {
                targetDirValue =
                    env.getValueOrThrow<E?>(FileValue.key(targetDirRootedPath), IOException::class.java) as FileValue?
            } catch (e: IOException) {
                throw RepositoryFunctionException(
                    IOException("Could not access " + destination + ": " + e.getMessage()),
                    Transience.PERSISTENT
                )
            }
            if (targetDirValue == null) {
                // TODO(bazel-team): If this returns null, we unnecessarily recreate the symlink above on the
                // second execution.
                return null
            }

            if (!targetDirValue.isDirectory()) {
                throw RepositoryFunctionException(
                    IOException(
                        java.lang.String.format(
                            "The repository's path is \"%s\" (absolute: \"%s\") "
                                    + "but it does not exist or is not a directory.",
                            userDefinedPath, destination
                        )
                    ),
                    Transience.PERSISTENT
                )
            }

            // Check that the directory contains a repo boundary file.
            // Note that we need to do this here since we're not creating a repo boundary file ourselves,
            // but entrusting the entire contents of the repo root to this target directory.
            if (!RepositoryUtils.isValidRepoRoot(destination)) {
                throw RepositoryFunctionException(
                    IOException("No MODULE.bazel, REPO.bazel, or WORKSPACE file found in " + destination),
                    Transience.TRANSIENT
                )
            }
            return com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.Success(
                Root.fromPath(source),  /* excludeFromVendoring= */
                true
            )
        }
    }
}
