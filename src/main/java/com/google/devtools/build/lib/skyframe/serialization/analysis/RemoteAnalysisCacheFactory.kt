// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.actions.Artifact.ArtifactSerializationContext

/** Factory for [RemoteAnalysisCacheManager].  */
object RemoteAnalysisCacheFactory {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

    @Throws(java.lang.InterruptedException::class, AbruptExitException::class, InvalidConfigurationException::class)
    fun create(
        env: CommandEnvironment,
        maybeActiveDirectoriesMatcher: java.util.Optional<PathFragmentPrefixTrie?>,
        topLevelTargets: MutableCollection<Label?>,
        topLevelOptions: BuildOptions,
        userOptions: MutableMap<String?, String?>?,
        projectSclOptions: MutableSet<String?>?
    ): AnalysisDeps {
        // Bail out early if needed
        val options: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            env.getOptions().getOptions(RemoteAnalysisCachingOptions::class.java)
        if (options == null || !env.getCommand().buildPhase()
                .executes() || options.getMode() === RemoteAnalysisCacheMode.OFF
        ) {
            val disabledDeps: RemoteAnalysisCacheDeps = RemoteAnalysisCacheDeps.Companion.createDisabled()
            return AnalysisDeps(
                RemoteAnalysisCacheManager.Companion.createDisabled(), disabledDeps, disabledDeps
            )
        }

        if (options.getMode() === RemoteAnalysisCacheMode.UPLOAD
            || options.getMode() === RemoteAnalysisCacheMode.DUMP_UPLOAD_MANIFEST_ONLY
        ) {
            val coreOptions: CoreOptions? = topLevelOptions.get(CoreOptions::class.java)
            if (coreOptions != null && !coreOptions.getCheckVisibility()) {
                throw AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(
                                "Skycache upload mode requires --check_visibility=true, but it was false."
                            )
                            .setRemoteAnalysisCaching(
                                RemoteAnalysisCaching.newBuilder()
                                    .setCode(RemoteAnalysisCaching.Code.INCOMPATIBLE_OPTIONS)
                            )
                            .build()
                    )
                )
            }
        }

        // Set up active directory matcher
        val maybeActiveDirectoriesMatcherFromFlags: java.util.Optional<PathFragmentPrefixTrie?> =
            finalizeActiveDirectoriesMatcher(env, maybeActiveDirectoriesMatcher, options.getMode())
        val activeDirectoriesMatcher: java.util.Optional<java.util.function.Predicate<PackageIdentifier?>?>? =
            maybeActiveDirectoriesMatcherFromFlags.map<java.util.function.Predicate<PackageIdentifier?>?>(java.util.function.Function { v: PathFragmentPrefixTrie? ->
                java.util.function.Predicate { pi: PackageIdentifier? ->
                    v.includes(
                        pi.getPackageFragment()
                    )
                }
            })

        // Compute versions we are evaluating at
        var workspaceInfoFromDiff: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            env.getWorkspaceInfoFromDiff()
        if (workspaceInfoFromDiff == null) {
            workspaceInfoFromDiff = object : WorkspaceInfoFromDiff() {} // Rely on default implementations
        }
        val clientId: ClientId? =
            workspaceInfoFromDiff
                .getSnapshot()
                .orElse(LongVersionClientId(workspaceInfoFromDiff.getEvaluatingVersion().getVal()))
        val blazeInstallMD5: com.google.common.hash.HashCode = computeBlazeInstallMD5(env, options)

        val starlarkSemanticsFingerprint: ByteArray? =
            BuildLanguageOptions.stableFingerprint(
                env.getSkyframeExecutor()
                    .getEffectiveStarlarkSemantics(
                        env.getOptions().getOptions(BuildLanguageOptions::class.java)
                    )
            )
                .toByteArray()

        // Skycache builds are primed with --check_visibility=true, so all cached entries
        // are computed with visibility checking turned on. In download mode, if the user specified
        // --check_visibility=false, we compute configuration checksums as if it
        // were true so that we can reuse entries from the cache despite the
        // different visibility settings. This is safe as long as we don't cache
        // failures.
        val trimmedTopLevelOptions: BuildOptions = trimConfigurations(topLevelOptions)

        val frontierNodeVersion: FrontierNodeVersion =
            FrontierNodeVersion(
                trimmedTopLevelOptions.checksum(),
                blazeInstallMD5,
                starlarkSemanticsFingerprint,
                workspaceInfoFromDiff.getEvaluatingVersion(),
                com.google.common.base.Strings.nullToEmpty(options.getAnalysisCacheKeyDistinguisherForTesting()),
                env.useFakeStampData,
                workspaceInfoFromDiff.getSnapshot()
            )
        env.getRemoteAnalysisCachingEventListener().recordSkyValueVersion(frontierNodeVersion)
        env.getRemoteAnalysisCachingEventListener().setClientId(clientId)
        logger.atInfo().log(
            "Remote analysis caching SkyValue version: %s (actual evaluating version: %s)",
            frontierNodeVersion, workspaceInfoFromDiff.getEvaluatingVersion()
        )

        // Create various objets we need
        val objectCodecs: com.google.common.util.concurrent.ListenableFuture<ObjectCodecs?> =
            createObjectCodecs(env, topLevelOptions)

        val servicesSupplier: RemoteAnalysisCachingServicesSupplier =
            env.getBlazeWorkspace().remoteAnalysisCachingServicesSupplier()
        servicesSupplier.configure(options, clientId, env.getCommandId().toString())

        // Set up parameters for the metadata store, if needed
        val skycacheMetadataParams: SkycacheMetadataParams? = servicesSupplier.getSkycacheMetadataParams()
        val areMetadataQueriesEnabled =
            skycacheMetadataParams != null && options.getAnalysisCacheEnableMetadataQueries()

        if (areMetadataQueriesEnabled) {
            skycacheMetadataParams.init(
                workspaceInfoFromDiff.getEvaluatingVersion().getVal(),
                java.lang.String.format("%s-%s", BlazeVersionInfo.instance().getReleaseName(), blazeInstallMD5),
                topLevelTargets.stream().map<Any?>(Label::toString)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>()),
                env.useFakeStampData,
                userOptions,
                projectSclOptions
            )
        }

        if (skycacheMetadataParams != null) {
            skycacheMetadataParams.setConfigurationHash(trimmedTopLevelOptions.checksum())
            skycacheMetadataParams.setOriginalConfigurationOptions(
                getConfigurationOptionsAsStrings(topLevelOptions)
            )
        }

        // Create the return values
        val deps: RemoteAnalysisCacheDeps =
            RemoteAnalysisCacheDeps(
                env.getReporter(),
                options.getMode(),
                options.getAnalysisCacheBailOnMissingFingerprint(),
                options.getSkycacheMinimizeMemory(),
                servicesSupplier,
                env.getRemoteAnalysisCachingEventListener(),
                objectCodecs,
                frontierNodeVersion,
                activeDirectoriesMatcher,
                options.getSerializedFrontierProfile(),
                options.getSkycacheAnalysisOnly()
            )

        val analysisCacheInvalidator: com.google.common.util.concurrent.ListenableFuture<AnalysisCacheInvalidator?>? =
            createAnalysisCacheInvalidator(
                env.getReporter(),
                clientId,
                frontierNodeVersion,
                objectCodecs,
                servicesSupplier.getFingerprintValueService(),
                servicesSupplier.getAnalysisCacheClient(),
                env.getRemoteAnalysisCachingEventListener()
            )

        val manager: RemoteAnalysisCacheManager =
            RemoteAnalysisCacheManager(
                options.getMode(),
                areMetadataQueriesEnabled,
                env.getReporter(),
                skycacheMetadataParams,
                servicesSupplier.getAnalysisCacheClient(),
                analysisCacheInvalidator,
                topLevelTargets,
                activeDirectoriesMatcher,
                options.getSkycacheMinimizeMemory()
            )

        // Bail out if needed
        return when (options.getMode()) {
            RemoteAnalysisCacheMode.DUMP_UPLOAD_MANIFEST_ONLY, RemoteAnalysisCacheMode.UPLOAD -> AnalysisDeps(
                manager,
                deps,
                deps
            )

            RemoteAnalysisCacheMode.DOWNLOAD -> {
                val analysisCacheClient: RemoteAnalysisCacheClient?
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("initAnalysisCacheClient")
                    .use { unused ->
                        analysisCacheClient = deps.getAnalysisCacheClient()
                    }
                if (analysisCacheClient == null) {
                    if (com.google.common.base.Strings.isNullOrEmpty(options.getAnalysisCacheService())) {
                        env.getReporter()
                            .handle(
                                com.google.devtools.build.lib.events.Event.warn(
                                    ("--experimental_remote_analysis_cache_mode=DOWNLOAD was requested but"
                                            + " --experimental_analysis_cache_service was not specified. Falling"
                                            + " back on local evaluation.")
                                )
                            )
                    } else {
                        env.getReporter()
                            .handle(
                                com.google.devtools.build.lib.events.Event.warn(
                                    "Failed to establish connection to AnalysisCacheService. Falling back to"
                                            + " local evaluation."
                                )
                            )
                    }
                    AnalysisDeps(
                        RemoteAnalysisCacheManager.Companion.createDisabled(),
                        RemoteAnalysisCacheDeps.Companion.createDisabled(),
                        RemoteAnalysisCacheDeps.Companion.createDisabled()
                    )
                }
                AnalysisDeps(manager, deps, deps)
            }

            else -> throw java.lang.IllegalStateException("Unknown RemoteAnalysisCacheMode: " + options.getMode())
        }
    }

    @Throws(InvalidConfigurationException::class)
    private fun finalizeActiveDirectoriesMatcher(
        env: CommandEnvironment,
        maybeProjectFileMatcher: java.util.Optional<PathFragmentPrefixTrie?>,
        mode: RemoteAnalysisCacheMode
    ): java.util.Optional<PathFragmentPrefixTrie?> {
        return when (mode) {
            RemoteAnalysisCacheMode.DOWNLOAD, RemoteAnalysisCacheMode.OFF -> java.util.Optional.empty<PathFragmentPrefixTrie?>()
            RemoteAnalysisCacheMode.UPLOAD, RemoteAnalysisCacheMode.DUMP_UPLOAD_MANIFEST_ONLY -> {
                // Upload or Dump mode: allow overriding the project file matcher with the active
                // directories flag.
                val activeDirectoriesFromFlag: MutableList<String?> =
                    env.getOptions().getOptions(SkyfocusOptions::class.java).getActiveDirectories()
                var result: java.util.Optional<PathFragmentPrefixTrie?> = maybeProjectFileMatcher
                if (!activeDirectoriesFromFlag.isEmpty()) {
                    env.getReporter()
                        .handle(
                            com.google.devtools.build.lib.events.Event.warn(
                                "Specifying --experimental_active_directories will override the active"
                                        + " directories specified in the PROJECT.scl file"
                            )
                        )
                    try {
                        result = java.util.Optional.of<T?>(PathFragmentPrefixTrie.of(activeDirectoriesFromFlag))
                    } catch (e: PathFragmentPrefixTrieException) {
                        throw InvalidConfigurationException(
                            "Active directories configuration error: " + e.getMessage(), Code.INVALID_PROJECT
                        )
                    }
                }

                if (result.isEmpty() || !result.get().hasIncludedPaths()) {
                    env.getReporter()
                        .handle(
                            com.google.devtools.build.lib.events.Event.warn(
                                "No active directories were found. Falling back on full serialization."
                            )
                        )
                    java.util.Optional.empty<PathFragmentPrefixTrie?>()
                }
                result
            }
        }
    }

    private fun initAnalysisObjectCodecs(
        registry: ObjectCodecRegistry?,
        ruleClassProvider: RuleClassProvider?,
        skyframeExecutor: SkyframeExecutor,
        directories: BlazeDirectories,
        topLevelOptions: BuildOptions?
    ): ObjectCodecs {
        val roots: com.google.common.collect.ImmutableList.Builder<Root?> =
            com.google.common.collect.ImmutableList.builder<Root?>().add(Root.fromPath(directories.getWorkspace()))
        // TODO: b/406458763 - clean this up
        if (com.google.common.base.Ascii.equalsIgnoreCase(directories.getProductName(), "blaze")) {
            roots.add(Root.fromPath(directories.getBlazeExecRoot()))
        }

        val serializationDeps: com.google.common.collect.ImmutableClassToInstanceMap.Builder<Any?> =
            com.google.common.collect.ImmutableClassToInstanceMap.builder<Any?>()
                .put<ArtifactSerializationContext?>(
                    ArtifactSerializationContext::class.java,
                    skyframeExecutor.getSkyframeBuildView().getArtifactFactory()::getSourceArtifact
                )
                .put<RuleClassProvider?>(RuleClassProvider::class.java, ruleClassProvider)
                .put<RootCodecDependencies?>(RootCodecDependencies::class.java, RootCodecDependencies(roots.build()))
                .put<PackagePathCodecDependencies?>(
                    PackagePathCodecDependencies::class.java,
                    PackagePathCodecDependencies { skyframeExecutor.getPackagePathEntries() }) // This is needed to determine TargetData for a ConfiguredTarget during serialization.
                .put<PrerequisitePackageFunction?>(
                    PrerequisitePackageFunction::class.java,
                    PrerequisitePackageFunction { id: PackageIdentifier? -> skyframeExecutor.getExistingPackage(id) })
                .put<BuildOptions?>(BuildOptions::class.java, topLevelOptions)

        return ObjectCodecs(registry, serializationDeps.build())
    }

    private fun createObjectCodecs(
        env: CommandEnvironment, topLevelOptions: BuildOptions?
    ): com.google.common.util.concurrent.ListenableFuture<ObjectCodecs?> {
        return com.google.common.util.concurrent.Futures.submit<ObjectCodecs?>(
            java.util.concurrent.Callable {
                initAnalysisObjectCodecs(
                    java.util.Objects.requireNonNull<T?>(
                        env.getBlazeWorkspace().getAnalysisObjectCodecRegistrySupplier()
                    )
                        .get(),
                    env.getRuntime().getRuleClassProvider(),
                    env.getBlazeWorkspace().getSkyframeExecutor(),
                    env.getDirectories(),
                    topLevelOptions
                )
            },
            ForkJoinPool.commonPool()
        )
    }

    private fun trimConfigurations(options: BuildOptions): BuildOptions {
        val coreOptions: CoreOptions? = options.get(CoreOptions::class.java)
        if (coreOptions != null && !coreOptions.getCheckVisibility()) {
            val builder: BuildOptions.Builder = options.toBuilder()
            builder.getFragmentOptions(CoreOptions::class.java).setCheckVisibility(true)
            return builder.build()
        }
        return options
    }

    // In case we don't expect a connection to the analysis cache server
    private fun createAnalysisCacheInvalidator(
        eventHandler: ExtendedEventHandler?,
        clientId: ClientId?,
        frontierNodeVersion: FrontierNodeVersion?,
        objectCodecs: com.google.common.util.concurrent.ListenableFuture<out ObjectCodecs?>,
        fingerprintValueService: com.google.common.util.concurrent.ListenableFuture<out FingerprintValueService?>?,
        analysisCacheClient: com.google.common.util.concurrent.ListenableFuture<out RemoteAnalysisCacheClient?>?,
        eventListener: RemoteAnalysisCachingEventListener?
    ): com.google.common.util.concurrent.ListenableFuture<AnalysisCacheInvalidator?>? {
        if (analysisCacheClient == null) {
            return com.google.common.util.concurrent.Futures.immediateFuture<AnalysisCacheInvalidator?>(null)
        }
        return com.google.common.util.concurrent.Futures.whenAllSucceed<Any?>(
            objectCodecs,
            fingerprintValueService,
            analysisCacheClient
        )
            .call<AnalysisCacheInvalidator?>(
                java.util.concurrent.Callable {
                    AnalysisCacheInvalidator(
                        analysisCacheClient.get(),
                        objectCodecs.get(),
                        fingerprintValueService.get(),
                        frontierNodeVersion,
                        clientId,
                        eventHandler,
                        eventListener
                    )
                },
                ForkJoinPool.commonPool()
            )
    }

    @Throws(AbruptExitException::class)
    private fun computeBlazeInstallMD5(
        env: CommandEnvironment, options: RemoteAnalysisCachingOptions
    ): com.google.common.hash.HashCode {
        if (options.getServerChecksumOverride() == null) {
            return java.util.Objects.requireNonNull<T>(env.getDirectories().getInstallMD5())
        }

        if (options.getMode() != RemoteAnalysisCacheMode.DOWNLOAD) {
            throw AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage("Server checksum override can only be used in download mode")
                        .setRemoteAnalysisCaching(
                            RemoteAnalysisCaching.newBuilder()
                                .setCode(RemoteAnalysisCaching.Code.INCOMPATIBLE_OPTIONS)
                        )
                        .build()
                )
            )
        }

        env.getReporter()
            .handle(
                com.google.devtools.build.lib.events.Event.warn(
                    java.lang.String.format(
                        ("Skycache will use server checksum '%s' instead of '%s', which describes"
                                + " this binary. This may cause crashes or even silent incorrectness."
                                + " You've been warned! (check the documentation of the command line "
                                + " flag for more details)"),
                        options.getServerChecksumOverride(), env.getDirectories().getInstallMD5()
                    )
                )
            )

        return options.getServerChecksumOverride()
    }

    private fun getConfigurationOptionsAsStrings(targetOptions: BuildOptions): com.google.common.collect.ImmutableSet<String?> {
        val allOptionsAsStringsBuilder: com.google.common.collect.ImmutableSet.Builder<String?> =
            com.google.common.collect.ImmutableSet.Builder<String?>()

        // Collect a list of BuildOptions, excluding TestOptions.
        targetOptions.getStarlarkOptions().keySet().stream()
            .map({ obj: Any? -> obj.toString() })
            .forEach(allOptionsAsStringsBuilder::add)
        for (fragmentOptions in targetOptions.getNativeOptions()) {
            if (fragmentOptions is TestConfiguration.TestOptions) {
                continue
            }
            fragmentOptions.asMap().keySet().forEach(allOptionsAsStringsBuilder::add)
        }
        return allOptionsAsStringsBuilder.build()
    }
}
