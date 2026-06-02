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
package com.google.devtools.build.lib.bazel

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Adds support for fetching external code.  */
class BazelRepositoryModule : BlazeModule {
    private val repositoryCache: RepositoryCache = RepositoryCache()
    private val repoEnvSupplier: MutableSupplier<com.google.common.collect.ImmutableMap<String?, String?>?> =
        MutableSupplier<com.google.common.collect.ImmutableMap<String?, String?>?>()
    private val nonstrictRepoEnvSupplier: MutableSupplier<com.google.common.collect.ImmutableMap<String?, String?>?> =
        MutableSupplier<com.google.common.collect.ImmutableMap<String?, String?>?>()
    private var fetchDisabled = false
    private var overrides: com.google.common.collect.ImmutableMap<String?, PathFragment?> =
        com.google.common.collect.ImmutableMap.of<String?, PathFragment?>()
    private var injections: com.google.common.collect.ImmutableMap<String?, PathFragment?> =
        com.google.common.collect.ImmutableMap.of<String?, PathFragment?>()
    private var moduleOverrides: com.google.common.collect.ImmutableMap<String?, ModuleOverride?> =
        com.google.common.collect.ImmutableMap.of<String?, ModuleOverride?>()
    private var filesystem: com.google.devtools.build.lib.vfs.FileSystem? = null
    private var registries: com.google.common.collect.ImmutableSet<String?>? = null
    private var moduleMirrors: com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableSet<String?>?>? =
        null
    private val ignoreDevDeps: AtomicBoolean = AtomicBoolean(false)
    private var checkDirectDepsMode: CheckDirectDepsMode? = CheckDirectDepsMode.WARNING
    private var bazelCompatibilityMode: BazelCompatibilityMode? = BazelCompatibilityMode.ERROR
    private var bazelLockfileMode: LockfileMode? = LockfileMode.UPDATE
    private var clock: com.google.devtools.build.lib.clock.Clock? = null
    private var lastRegistryInvalidation: Instant = Instant.EPOCH

    private var vendorDirectory: java.util.Optional<com.google.devtools.build.lib.vfs.Path?> =
        java.util.Optional.empty<com.google.devtools.build.lib.vfs.Path?>()
    private var allowedYankedVersions: MutableList<String?>? = com.google.common.collect.ImmutableList.of<String?>()
    private var repositoryFetchFunction: RepositoryFetchFunction? = null
    private var singleExtensionEvalFunction: SingleExtensionEvalFunction? = null
    private var moduleFileFunction: ModuleFileFunction? = null
    private var repoSpecFunction: RepoSpecFunction? = null
    private var yankedVersionsFunction: YankedVersionsFunction? = null

    private val vendorCommand: VendorCommand = VendorCommand(nonstrictRepoEnvSupplier)
    private val registryFactory: RegistryFactoryImpl = RegistryFactoryImpl(nonstrictRepoEnvSupplier)

    private var credentialModule: CredentialModule? = null

    private var builtinModules: com.google.common.collect.ImmutableMap<String?, NonRegistryOverride?>? = null

    constructor()

    @com.google.common.annotations.VisibleForTesting
    constructor(builtinModules: com.google.common.collect.ImmutableMap<String?, NonRegistryOverride?>?) {
        this.builtinModules = builtinModules
    }

    private class RepositoryCacheInfoItem(repositoryCache: RepositoryCache) :
        InfoItem("repository_cache", "The location of the repository download cache used") {
        private val repositoryCache: RepositoryCache

        init {
            this.repositoryCache = repositoryCache
        }

        @Throws(AbruptExitException::class, java.lang.InterruptedException::class)
        override fun get(
            configurationSupplier: com.google.common.base.Supplier<BuildConfigurationValue?>?, env: CommandEnvironment?
        ): ByteArray? {
            return InfoItem.print(repositoryCache.getPath())
        }
    }

    override fun serverInit(
        startupOptions: com.google.devtools.common.options.OptionsParsingResult?,
        builder: com.google.devtools.build.lib.runtime.ServerBuilder
    ) {
        builder.addCommands(FetchCommand())
        builder.addCommands(ModCommand())
        builder.addCommands(vendorCommand)
        builder.addInfoItems(RepositoryCacheInfoItem(repositoryCache))
    }

    override fun workspaceInit(
        runtime: BlazeRuntime, directories: BlazeDirectories, builder: WorkspaceBuilder
    ) {
        builder.allowExternalRepositories(true)
        builder.setRepoContentsCachePathSupplier(java.util.function.Supplier {
            repositoryCache.getRepoContentsCache().getPath()
        })

        repositoryFetchFunction =
            RepositoryFetchFunction(
                repoEnvSupplier,
                nonstrictRepoEnvSupplier,
                directories,
                repositoryCache.getRepoContentsCache()
            )
        singleExtensionEvalFunction =
            SingleExtensionEvalFunction(directories, repoEnvSupplier, nonstrictRepoEnvSupplier)

        if (builtinModules == null) {
            builtinModules = ModuleFileFunction.getBuiltinModules()
        }

        moduleFileFunction =
            ModuleFileFunction(
                runtime.getRuleClassProvider().getBazelStarlarkEnvironment(),
                directories.getWorkspace(),
                builtinModules
            )
        repoSpecFunction = RepoSpecFunction()
        yankedVersionsFunction = YankedVersionsFunction()

        builder
            .addSkyFunction(SkyFunctions.REPOSITORY_DIRECTORY, repositoryFetchFunction)
            .addSkyFunction(SkyFunctions.MODULE_FILE, moduleFileFunction)
            .addSkyFunction(SkyFunctions.BAZEL_DEP_GRAPH, BazelDepGraphFunction())
            .addSkyFunction(
                SkyFunctions.BAZEL_LOCK_FILE,
                BazelLockFileFunction(directories.getWorkspace(), directories.getOutputBase())
            )
            .addSkyFunction(SkyFunctions.BAZEL_FETCH_ALL, BazelFetchAllFunction())
            .addSkyFunction(SkyFunctions.BAZEL_MOD_TIDY, BazelModTidyFunction())
            .addSkyFunction(SkyFunctions.BAZEL_MODULE_INSPECTION, BazelModuleInspectorFunction())
            .addSkyFunction(SkyFunctions.BAZEL_MODULE_RESOLUTION, BazelModuleResolutionFunction())
            .addSkyFunction(SkyFunctions.SINGLE_EXTENSION, SingleExtensionFunction())
            .addSkyFunction(SkyFunctions.SINGLE_EXTENSION_EVAL, singleExtensionEvalFunction)
            .addSkyFunction(SkyFunctions.SINGLE_EXTENSION_USAGES, SingleExtensionUsagesFunction())
            .addSkyFunction(
                SkyFunctions.REGISTRY,
                RegistryFunction(registryFactory, directories.getWorkspace())
            )
            .addSkyFunction(SkyFunctions.REPO_SPEC, repoSpecFunction)
            .addSkyFunction(SkyFunctions.YANKED_VERSIONS, yankedVersionsFunction)
            .addSkyFunction(
                SkyFunctions.VENDOR_FILE,
                VendorFileFunction(runtime.getRuleClassProvider().getBazelStarlarkEnvironment())
            )
            .addSkyFunction(
                SkyFunctions.MODULE_EXTENSION_REPO_MAPPING_ENTRIES,
                ModuleExtensionRepoMappingEntriesFunction()
            )
        filesystem = runtime.getFileSystem()

        credentialModule = com.google.common.base.Preconditions.checkNotNull<CredentialModule?>(
            runtime.getBlazeModule<CredentialModule?>(CredentialModule::class.java)
        )
    }

    override fun initializeRuleClasses(builder: ConfiguredRuleClassProvider.Builder) {
        builder.addStarlarkBootstrap(RepositoryBootstrap(StarlarkRepositoryModule()))
    }

    @Throws(AbruptExitException::class)
    override fun beforeCommand(env: CommandEnvironment) {
        val downloadManager: DownloadManager =
            DownloadManager(
                repositoryCache.getDownloadCache(),
                env.getDownloaderDelegate(),
                env.getHttpDownloader(),
                env.getReporter()
            )
        this.repositoryFetchFunction.setDownloadManager(downloadManager)
        this.moduleFileFunction.setDownloadManager(downloadManager)
        this.repoSpecFunction.setDownloadManager(downloadManager)
        this.yankedVersionsFunction.setDownloadManager(downloadManager)
        this.vendorCommand.setDownloadManager(downloadManager)

        repoEnvSupplier.set(env.getRepoEnv())
        nonstrictRepoEnvSupplier.set(env.getNonstrictRepoEnv())
        val pkgOptions: PackageOptions? = env.getOptions().getOptions<PackageOptions?>(PackageOptions::class.java)
        fetchDisabled = pkgOptions != null && !pkgOptions.getFetch()

        val processWrapper: ProcessWrapper? = ProcessWrapper.fromCommandEnvironment(env)
        repositoryFetchFunction.setProcessWrapper(processWrapper)
        repositoryFetchFunction.setSyscallCache(env.getSyscallCache())
        singleExtensionEvalFunction.setProcessWrapper(processWrapper)
        singleExtensionEvalFunction.setDownloadManager(downloadManager)

        val repoOptions: RepositoryOptions? =
            env.getOptions().getOptions<RepositoryOptions?>(RepositoryOptions::class.java)
        if (repoOptions != null) {
            downloadManager.setDisableDownload(repoOptions.getDisableDownload())
            if (repoOptions.getRepositoryDownloaderRetries() >= 0) {
                downloadManager.setRetries(repoOptions.getRepositoryDownloaderRetries())
            }

            repositoryCache.getDownloadCache().setHardlink(repoOptions.getUseHardlinks())
            if (repoOptions.getExperimentalScaleTimeouts() > 0.0) {
                repositoryFetchFunction.setTimeoutScaling(repoOptions.getExperimentalScaleTimeouts())
                singleExtensionEvalFunction.setTimeoutScaling(repoOptions.getExperimentalScaleTimeouts())
            } else {
                env.getReporter()
                    .handle(
                        com.google.devtools.build.lib.events.Event.warn(
                            "Ignoring request to scale timeouts for repositories by a non-positive"
                                    + " factor"
                        )
                    )
                repositoryFetchFunction.setTimeoutScaling(1.0)
                singleExtensionEvalFunction.setTimeoutScaling(1.0)
            }
            if (repoOptions.getRepositoryCache() != null) {
                repositoryCache.setPath(toPath(repoOptions.getRepositoryCache(), env))
            } else {
                repositoryCache.setPath(
                    env.getDirectories()
                        .getServerDirectories()
                        .getOutputUserRoot()
                        .getRelative(DEFAULT_CACHE_LOCATION)
                )
            }
            // Note that the repo contents cache stuff has to happen _after_ the repo cache stuff, because
            // the specific settings about the repo contents cache might overwrite the repo cache
            // settings. In particular, if `--repo_contents_cache` is not set (it's null), we use whatever
            // default set by `repositoryCache.setPath(...)`.
            if (repoOptions.getRepoContentsCache() != null) {
                repositoryCache
                    .getRepoContentsCache()
                    .setPath(toPath(repoOptions.getRepoContentsCache(), env))
            }
            val repoContentsCachePath: com.google.devtools.build.lib.vfs.Path? =
                repositoryCache.getRepoContentsCache().getPath()
            if (repoContentsCachePath != null) {
                // Check that the repo contents cache directory, which is managed by a garbage collecting
                // idle task, does not contain the output base. Since the specified output base path may be
                // a symlink, we resolve it fully. Intermediate symlinks do not have to be checked as the
                // garbage collector ignores symlinks. We also resolve the repo contents cache directory,
                // where intermediate symlinks also don't matter since deletion only occurs under the fully
                // resolved path.
                var resolvedOutputBase: com.google.devtools.build.lib.vfs.Path = env.getOutputBase()
                try {
                    resolvedOutputBase = resolvedOutputBase.resolveSymbolicLinks()
                } catch (ignored: FileNotFoundException) {
                    // Will be created later.
                } catch (e: IOException) {
                    throw AbruptExitException(
                        detailedExitCode(
                            "could not resolve output base: %s".formatted(e.getMessage()),
                            Code.BAD_REPO_CONTENTS_CACHE
                        ),
                        e
                    )
                }
                var resolvedRepoContentsCache: com.google.devtools.build.lib.vfs.Path? = repoContentsCachePath
                try {
                    resolvedRepoContentsCache = resolvedRepoContentsCache.resolveSymbolicLinks()
                } catch (ignored: FileNotFoundException) {
                    // Will be created later.
                } catch (e: IOException) {
                    throw AbruptExitException(
                        detailedExitCode(
                            "could not resolve repo contents cache path: %s".formatted(e.getMessage()),
                            Code.BAD_REPO_CONTENTS_CACHE
                        ),
                        e
                    )
                }
                if (resolvedOutputBase.startsWith(resolvedRepoContentsCache)) {
                    // This is dangerous as the repo contents cache GC may delete files in the output base.
                    throw AbruptExitException(
                        detailedExitCode(
                            """
                  The output base [%s] is inside the repo contents cache [%s]. This can cause spurious failures. Disable the repo contents cache with `--repo_contents_cache=`, or specify `--repo_contents_cache=<path that doesn't contain the output base>`.
                  
                  """
                                .trimIndent()
                                .formatted(resolvedOutputBase, resolvedRepoContentsCache),
                            Code.BAD_REPO_CONTENTS_CACHE
                        )
                    )
                }
            }
            if (repoContentsCachePath != null && env.getWorkspace() != null && repoContentsCachePath.startsWith(env.getWorkspace())) {
                // Having the repo contents cache inside the main repo is very dangerous. During the
                // lifetime of a Bazel invocation, we treat files inside the main repo as immutable. This
                // can cause mysterious failures if we write files inside the main repo during the
                // invocation, as is often the case with the repo contents cache.
                // TODO: wyv@ - This is a crude check that disables some use cases (such as when the output
                //   base itself is inside the main repo). Investigate a better check.
                repositoryCache.getRepoContentsCache().setPath(null)
                throw AbruptExitException(
                    detailedExitCode(
                        """
                The repo contents cache [%s] is inside the main repo [%s]. This can cause spurious failures. Disable the repo contents cache with `--repo_contents_cache=`, or specify `--repo_contents_cache=<path outside the main repo>`.
                
                """
                            .trimIndent()
                            .formatted(repoContentsCachePath, env.getWorkspace()),
                        Code.BAD_REPO_CONTENTS_CACHE
                    )
                )
            }
            if (repositoryCache.getRepoContentsCache().isEnabled()) {
                try {
                    com.google.devtools.build.lib.profiler.Profiler.instance()
                        .profile(
                            com.google.devtools.build.lib.profiler.ProfilerTask.REPO_CACHE_GC_WAIT,
                            "waiting to acquire repo cache lock"
                        ).use { c ->
                            repositoryCache.getRepoContentsCache().acquireSharedLock()
                        }
                } catch (e: IOException) {
                    throw AbruptExitException(
                        detailedExitCode(
                            "could not acquire lock on repo contents cache", Code.BAD_REPO_CONTENTS_CACHE
                        ),
                        e
                    )
                } catch (e: java.lang.InterruptedException) {
                    throw AbruptExitException(
                        detailedExitCode(
                            "could not acquire lock on repo contents cache", Code.BAD_REPO_CONTENTS_CACHE
                        ),
                        e
                    )
                }
                env.addIdleTask(
                    repositoryCache
                        .getRepoContentsCache()
                        .createGcIdleTask(
                            repoOptions.getRepoContentsCacheGcMaxAge(),
                            repoOptions.getRepoContentsCacheGcIdleDelay()
                        )
                )
            }

            try {
                downloadManager.setNetrcCreds(
                    UrlRewriter.Companion.newCredentialsFromNetrc(
                        env.getClientEnv(), env.getDirectories().getWorkingDirectory()
                    )
                )
            } catch (e: UrlRewriterParseException) {
                // If the credentials extraction failed, we're letting bazel try without credentials.
                env.getReporter()
                    .handle(
                        com.google.devtools.build.lib.events.Event.warn(
                            java.lang.String.format(
                                "Error parsing the .netrc file: %s.",
                                e.getMessage()
                            )
                        )
                    )
            }
            try {
                val rewriter: UrlRewriter =
                    UrlRewriter.Companion.getDownloaderUrlRewriter(
                        env.getWorkspace(), repoOptions.getDownloaderConfigs()
                    )
                downloadManager.setUrlRewriter(rewriter)
            } catch (e: UrlRewriterParseException) {
                // It's important that the build stops ASAP, because this config file may be required for
                // security purposes, and the build must not proceed ignoring it.
                throw AbruptExitException(
                    detailedExitCode(
                        java.lang.String.format(
                            "Failed to parse downloader config%s: %s",
                            if (e.getLocation() != null) java.lang.String.format(" at %s", e.getLocation()) else "",
                            e.getMessage()
                        ),
                        Code.BAD_DOWNLOADER_CONFIG
                    )
                )
            }

            try {
                val authAndTlsOptions: AuthAndTLSOptions? =
                    env.getOptions().getOptions<O?>(AuthAndTLSOptions::class.java)
                val credentialHelperEnvironment: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    CredentialHelperEnvironment.newBuilder()
                        .setEventReporter(env.getReporter())
                        .setWorkspacePath(env.getWorkspace())
                        .setClientEnvironment(env.getClientEnv())
                        .setHelperExecutionTimeout(authAndTlsOptions.credentialHelperTimeout)
                        .build()
                val credentialHelperProvider: CredentialHelperProvider? =
                    GoogleAuthUtils.newCredentialHelperProvider(
                        credentialHelperEnvironment,
                        env.getCommandLinePathFactory(),
                        authAndTlsOptions.credentialHelpers
                    )

                downloadManager.setCredentialFactory(
                    CredentialFactory { headers: MutableMap<java.net.URI?, MutableMap<String?, MutableList<String?>?>?>? ->
                        com.google.common.base.Preconditions.checkNotNull<MutableMap<java.net.URI?, MutableMap<String?, MutableList<String?>?>?>?>(
                            headers
                        )
                        CredentialHelperCredentials(
                            credentialHelperProvider,
                            credentialHelperEnvironment,
                            credentialModule.getCredentialCache(),
                            java.util.Optional.of<T?>(StaticCredentials(headers))
                        )
                    })
            } catch (e: IOException) {
                env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                env.getBlazeModuleEnvironment()
                    .exit(
                        AbruptExitException(
                            detailedExitCode(
                                "Error initializing credential helper", Code.CREDENTIALS_INIT_FAILURE
                            )
                        )
                    )
                return
            }

            if (repoOptions.getExperimentalDistdir() != null) {
                downloadManager.setDistdir(
                    repoOptions.getExperimentalDistdir().stream()
                        .map<com.google.devtools.build.lib.vfs.Path?>(
                            java.util.function.Function { path: PathFragment? ->
                                if (path.isAbsolute())
                                    filesystem.getPath(path)
                                else
                                    env.getBlazeWorkspace().getWorkspace().getRelative(path)
                            })
                        .collect(Collectors.toList())
                )
            } else {
                downloadManager.setDistdir(com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.vfs.Path?>())
            }

            if (repoOptions.getRepositoryOverrides() != null) {
                // To get the usual latest-wins semantics, we need a mutable map, as the builder
                // of an immutable map does not allow redefining the values of existing keys.
                // We use a LinkedHashMap to preserve the iteration order.
                val overrideMap: MutableMap<String?, PathFragment?> = LinkedHashMap<String?, PathFragment?>()
                for (override in repoOptions.getRepositoryOverrides()) {
                    if (override.path.isEmpty()) {
                        overrideMap.remove(override.repositoryName)
                        continue
                    }
                    val repoPath = getAbsolutePath(override.path, env)
                    overrideMap.put(override.repositoryName, PathFragment.create(repoPath))
                }
                val newOverrides: com.google.common.collect.ImmutableMap<String?, PathFragment?> =
                    com.google.common.collect.ImmutableMap.copyOf<String?, PathFragment?>(overrideMap)
                if (!com.google.common.collect.Maps.difference<String?, PathFragment?>(overrides, newOverrides)
                        .areEqual()
                ) {
                    overrides = newOverrides
                }
            } else {
                overrides = com.google.common.collect.ImmutableMap.of<String?, PathFragment?>()
            }

            if (repoOptions.getRepositoryInjections() != null) {
                val injectionMap: MutableMap<String?, PathFragment?> = LinkedHashMap<String?, PathFragment?>()
                for (injection in repoOptions.getRepositoryInjections()) {
                    if (injection.path.isEmpty()) {
                        injectionMap.remove(injection.apparentName)
                        continue
                    }
                    val repoPath = getAbsolutePath(injection.path, env)
                    injectionMap.put(injection.apparentName, PathFragment.create(repoPath))
                }
                val newInjections: com.google.common.collect.ImmutableMap<String?, PathFragment?> =
                    com.google.common.collect.ImmutableMap.copyOf<String?, PathFragment?>(injectionMap)
                if (!com.google.common.collect.Maps.difference<String?, PathFragment?>(injections, newInjections)
                        .areEqual()
                ) {
                    injections = newInjections
                }
            } else {
                injections = com.google.common.collect.ImmutableMap.of<String?, PathFragment?>()
            }

            if (repoOptions.getModuleOverrides() != null) {
                val moduleOverrideMap: MutableMap<String?, ModuleOverride?> = LinkedHashMap<String?, ModuleOverride?>()
                for (override in repoOptions.getModuleOverrides()) {
                    if (override.path.isEmpty()) {
                        moduleOverrideMap.remove(override.moduleName)
                        continue
                    }
                    val modulePath = getAbsolutePath(override.path, env)
                    moduleOverrideMap.put(
                        override.moduleName,
                        NonRegistryOverride(LocalPathRepoSpecs.create(modulePath))
                    )
                }
                val newModOverrides: com.google.common.collect.ImmutableMap<String?, ModuleOverride?> =
                    com.google.common.collect.ImmutableMap.copyOf<String?, ModuleOverride?>(moduleOverrideMap)
                if (!com.google.common.collect.Maps.difference<String?, ModuleOverride?>(
                        moduleOverrides,
                        newModOverrides
                    ).areEqual()
                ) {
                    moduleOverrides = newModOverrides
                }
            } else {
                moduleOverrides = com.google.common.collect.ImmutableMap.of<String?, ModuleOverride?>()
            }

            ignoreDevDeps.set(repoOptions.getIgnoreDevDependency())
            checkDirectDepsMode = repoOptions.getCheckDirectDependencies()
            bazelCompatibilityMode = repoOptions.getBazelCompatibilityMode()
            bazelLockfileMode = repoOptions.getLockfileMode()
            allowedYankedVersions = repoOptions.getAllowedYankedVersions()
            if (env.getWorkspace() != null) {
                val externalRoot: com.google.devtools.build.lib.vfs.Path =
                    env.getOutputBase().getRelative(LabelConstants.EXTERNAL_PATH_PREFIX)
                vendorDirectory =
                    java.util.Optional.ofNullable<PathFragment?>(repoOptions.getVendorDirectory())
                        .map<com.google.devtools.build.lib.vfs.Path?>(java.util.function.Function { vendorDirectory: PathFragment? ->
                            env.getWorkspace().getRelative(vendorDirectory)
                        })
                // Both vendoring and the local and remote repo contents cache rely on certain symlinks at
                // predictable locations to allow symlinks in external repos to be portable (in particular,
                // relative).
                if (vendorDirectory.isPresent()
                    || repoContentsCachePath != null || externalRoot.getFileSystem() is RemoteExternalOverlayFileSystem
                ) {
                    try {
                        com.google.devtools.build.lib.vfs.FileSystemUtils.ensureSymbolicLink(
                            externalRoot.getChild(RepositoryUtils.WORKSPACE_SYMLINK_NAME), env.getWorkspace()
                        )
                    } catch (e: IOException) {
                        env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                        env.getBlazeModuleEnvironment()
                            .exit(
                                AbruptExitException(
                                    detailedExitCode(
                                        "Failed to create symlink to workspace under external directory: "
                                                + e.getMessage(),
                                        Code.SYMLINKING_FAILED
                                    )
                                )
                            )
                    }
                }
                if (vendorDirectory.isPresent()) {
                    try {
                        // TODO: The same vendor directory may be used concurrently with multiple output bases,
                        //  which can cause conflicts that currently go undetected.
                        com.google.devtools.build.lib.vfs.FileSystemUtils.ensureSymbolicLink(
                            vendorDirectory.get().getRelative(VendorManager.EXTERNAL_ROOT_SYMLINK_NAME),
                            externalRoot
                        )
                        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
                            // On Windows, symlinks are resolved differently.
                            // Given <external>/repo_foo/link,
                            // where <external>/repo_foo points to <vendor dir>/repo_foo in vendor mode
                            // and repo_foo/link points to a relative path ../bazel-external/repo_bar/data.
                            // Windows won't resolve `repo_foo` before resolving `link`, which causes
                            // <external>/repo_foo/link to be resolved to <external>/bazel-external/repo_bar/data
                            // To work around this, we create a symlink <external>/bazel-external -> <external>.
                            com.google.devtools.build.lib.vfs.FileSystemUtils.ensureSymbolicLink(
                                externalRoot.getRelative(VendorManager.EXTERNAL_ROOT_SYMLINK_NAME), externalRoot
                            )
                        }
                    } catch (e: IOException) {
                        env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                        env.getBlazeModuleEnvironment()
                            .exit(
                                AbruptExitException(
                                    detailedExitCode(
                                        ("Failed to create symlink to external repo root under vendor directory:"
                                                + " "
                                                + e.getMessage()),
                                        Code.SYMLINKING_FAILED
                                    )
                                )
                            )
                    }
                }
            }

            if (repoOptions.getRegistries() != null && !repoOptions.getRegistries().isEmpty()) {
                registries = normalizeBaseUrls(repoOptions.getRegistries())
            } else {
                registries = DEFAULT_REGISTRIES
            }
            if (repoOptions.getModuleMirrors() != null && !repoOptions.getModuleMirrors().isEmpty()) {
                val registryToMirrors: Any =
                    repoOptions.getModuleMirrors().stream()
                        .collect()
                TODO(
                    """
                    |Cannot convert element
                    |With text:
                    |String, ImmutableSet<String>>toImmutableMap(
                    |                        entry -> normalizeBaseUrl(entry.getKey()),
                    |                        entry -> normalizeBaseUrls(entry.getValue()),
                    |                        // Last wins semantics allow previous settings to be overridden.
                    |                        (a, b) -> b)
                    """.trimMargin()
                )

                // The key "" returned by the option conversion defines mirrors for all registries that
                // don't have their own explicitly defined.
                val knownRegistries: com.google.common.collect.Sets.SetView<String?> =
                    com.google.common.collect.Sets.union<String?>(
                        registries,
                        com.google.common.collect.ImmutableSet.of<String?>("")
                    )
                val unknownRegistries: com.google.common.collect.Sets.SetView<String?> =
                    com.google.common.collect.Sets.difference<String?>(registryToMirrors.keySet(), knownRegistries)
                if (!unknownRegistries.isEmpty()) {
                    throw AbruptExitException(
                        detailedExitCode(
                            "--module_mirrors references registries not listed in --registries: "
                                    + java.lang.String.join(", ", unknownRegistries),
                            Code.UNKNOWN_REGISTRY
                        )
                    )
                }
                val moduleMirrorsBuilder: com.google.common.collect.ImmutableMap.Builder<String?, com.google.common.collect.ImmutableSet<String?>?> =
                    com.google.common.collect.ImmutableMap.builder<String?, com.google.common.collect.ImmutableSet<String?>?>()
                val defaultMirrors: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    registryToMirrors.getOrDefault("", com.google.common.collect.ImmutableSet.of<String?>())
                for (registry in registries) {
                    moduleMirrorsBuilder.put(
                        registry, registryToMirrors.getOrDefault(registry, defaultMirrors)
                    )
                }
                moduleMirrors = moduleMirrorsBuilder.buildOrThrow()
            } else {
                moduleMirrors = DEFAULT_MODULE_MIRRORS
            }

            val repositoryRemoteHelpersFactory: RepositoryRemoteHelpersFactory? =
                env.getRuntime().getRepositoryHelpersFactory()
            val remoteExecutor: RepositoryRemoteExecutor? = null
            val remoteRepoContentsCache: RemoteRepoContentsCache? = null
            if (repositoryRemoteHelpersFactory != null) {
                remoteExecutor = repositoryRemoteHelpersFactory.createExecutor()
                remoteRepoContentsCache = repositoryRemoteHelpersFactory.createRepoContentsCache()
            }
            repositoryFetchFunction.setRepositoryRemoteExecutor(remoteExecutor)
            repositoryFetchFunction.setRemoteRepoContentsCache(remoteRepoContentsCache)
            singleExtensionEvalFunction.setRepositoryRemoteExecutor(remoteExecutor)

            clock = env.getClock()
            try {
                val lastRegistryInvalidationValue: PrecomputedValue? =
                    env.getSkyframeExecutor()
                        .getEvaluator()
                        .getExistingValue(RegistryFunction.LAST_INVALIDATION.getKey()) as PrecomputedValue?
                if (lastRegistryInvalidationValue != null) {
                    lastRegistryInvalidation = lastRegistryInvalidationValue.get() as Instant
                }
            } catch (e: java.lang.InterruptedException) {
                // Not thrown in Bazel.
                throw java.lang.IllegalStateException(e)
            }
        }
    }

    /**
     * If the given path is absolute path, leave it as it is. If the given path is a relative path, it
     * is relative to the current working directory. If the given path starts with '%workspace%, it is
     * relative to the workspace root, which is the output of `bazel info workspace`.
     * 
     * @return Absolute Path
     */
    private fun getAbsolutePath(path: String, env: CommandEnvironment): String {
        var path = path
        if (env.getWorkspace() != null) {
            path = path.replace("%workspace%", env.getWorkspace().getPathString())
        }
        if (!PathFragment.isAbsolute(path)) {
            path = env.getWorkingDirectory().getRelative(path).getPathString()
        }
        return path
    }

    /**
     * An empty path fragment is turned into `null`; otherwise, it's treated as relative to the
     * workspace root.
     */
    private fun toPath(path: PathFragment, env: CommandEnvironment): com.google.devtools.build.lib.vfs.Path? {
        if (path.isEmpty() || env.getDirectories().getWorkspace() == null) {
            return null
        }
        // It is important to use getWorkspace() here, not getWorkingDirectory(). Both Paths have the
        // same underlying PathFragment, but may differ in their FileSystem if the remote repo contents
        // cache is in use. getWorkspace() uses the same FileSystem as everything other than the
        // workspace directory, while getWorkingDirectory() uses the workspace directory's FileSystem.
        // Even though the users of the returned Path may end up writing to it, they are not expected to
        // update source files within the workspace. Thus, the correct FileSystem is the one from
        // getWorkspace(), which e.g. allows moves from the external directory under the output base to
        // the local repo contents cache without crossing FileSystems.
        return env.getDirectories().getWorkspace().getRelative(path)
    }

    @Throws(AbruptExitException::class)
    override fun afterCommand() {
        if (repositoryCache.getRepoContentsCache().isEnabled()) {
            try {
                repositoryCache.getRepoContentsCache().releaseSharedLock()
            } catch (e: IOException) {
                throw AbruptExitException(
                    detailedExitCode(
                        "could not release lock on repo contents cache", Code.BAD_REPO_CONTENTS_CACHE
                    ),
                    e
                )
            }
        }
    }

    val precomputedValues: com.google.common.collect.ImmutableList<Injected?>
        get() {
            val now: Instant = clock.now()
            if (now.isAfter(lastRegistryInvalidation.plus(RegistryFunction.INVALIDATION_INTERVAL))) {
                lastRegistryInvalidation = now
            }
            return com.google.common.collect.ImmutableList.of<Injected?>(
                PrecomputedValue.injected<com.google.common.collect.ImmutableMap<String?, String?>?>(
                    PrecomputedValue.REPO_ENV,
                    repoEnvSupplier.get()
                ),
                PrecomputedValue.injected<MutableMap<String?, PathFragment?>?>(
                    RepoDefinitionFunction.Companion.REPOSITORY_OVERRIDES,
                    overrides
                ),
                PrecomputedValue.< ImmutableMap < String,
                PathFragment > > injected < com . google . common . collect . ImmutableMap < kotlin . String ?,
                PathFragment? > ? > (ModuleFileFunction.INJECTED_REPOSITORIES, injections),
            PrecomputedValue.< ImmutableMap < String, ModuleOverride>>injected<com.google.common.collect.ImmutableMap<kotlin.String?, ModuleOverride?>?>(ModuleFileFunction.MODULE_OVERRIDES, moduleOverrides),
            PrecomputedValue.injected<Boolean?>(
                RepositoryDirectoryValue.FETCH_DISABLED,
                fetchDisabled
            ),  // That key will be reinjected by the sync command with a universally unique identifier.
            // Nevertheless, we need to provide a default value for other commands.
            PrecomputedValue.injected<String?>(
                RepositoryDirectoryValue.FORCE_FETCH, RepositoryDirectoryValue.FORCE_FETCH_DISABLED
            ),
            PrecomputedValue.injected<String?>(
                RepositoryDirectoryValue.FORCE_FETCH_CONFIGURE,
                RepositoryDirectoryValue.FORCE_FETCH_DISABLED
            ),
            PrecomputedValue.< ImmutableSet < String > > injected<com.google.common.collect.ImmutableSet<String?>?>(
                ModuleFileFunction.REGISTRIES,
                registries
            ),
            PrecomputedValue.< ImmutableMap < String, ImmutableSet<String>>>injected<com.google.common.collect.ImmutableMap<kotlin.String?, com.google.common.collect.ImmutableSet<kotlin.String?>?>?>(RegistryFunction.MODULE_MIRRORS, moduleMirrors),
            PrecomputedValue.< Boolean > injected < Boolean ? > (ModuleFileFunction.IGNORE_DEV_DEPS, ignoreDevDeps.get()),
            PrecomputedValue.< CheckDirectDepsMode > injected < CheckDirectDepsMode ? > (
                    BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES, checkDirectDepsMode),
            PrecomputedValue.< BazelCompatibilityMode > injected < BazelCompatibilityMode ? > (
                    BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE, bazelCompatibilityMode),
            PrecomputedValue.< LockfileMode > injected < LockfileMode ? > (BazelLockFileFunction.LOCKFILE_MODE, bazelLockfileMode),
            PrecomputedValue.injected<Boolean?>(RepositoryDirectoryValue.IS_VENDOR_COMMAND, false),
            PrecomputedValue.injected<java.util.Optional<com.google.devtools.build.lib.vfs.Path?>?>(
                RepositoryDirectoryValue.VENDOR_DIRECTORY,
                vendorDirectory
            ),
            PrecomputedValue.< List < String > > injected<MutableList<String?>?>(
                YankedVersionsUtil.ALLOWED_YANKED_VERSIONS, allowedYankedVersions
            ),
            PrecomputedValue.< Instant > injected < Instant ? > (RegistryFunction.LAST_INVALIDATION, lastRegistryInvalidation))
        }

    val commonCommandOptions: Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase>>
        get() = com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
            RepositoryOptions::class.java
        )

    companion object {
        // Default location (relative to output user root) of the repository cache.
        const val DEFAULT_CACHE_LOCATION: String = "cache/repos/v1"

        // Default list of registries.
        @kotlin.jvm.JvmField
        val DEFAULT_REGISTRIES: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("https://bcr.bazel.build/")
        @kotlin.jvm.JvmField
        val DEFAULT_MODULE_MIRRORS: com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableSet<String?>?> =
            com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableSet<String?>?>()

        private fun detailedExitCode(message: String?, code: ExternalRepository.Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setExternalRepository(ExternalRepository.newBuilder().setCode(code))
                    .build()
            )
        }

        private fun normalizeBaseUrl(baseUrl: String): String {
            // Ensure that base URLs aren't duplicated even after /-delimited segments are appended to
            // them.
            return com.google.common.base.CharMatcher.`is`('/').trimTrailingFrom(baseUrl)
        }

        private fun normalizeBaseUrls(baseUrls: MutableList<String?>): com.google.common.collect.ImmutableSet<String?> {
            return baseUrls.stream()
                .map<String?>(java.util.function.Function { baseUrl: String? -> Companion.normalizeBaseUrl(baseUrl!!) })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())
        }
    }
}
