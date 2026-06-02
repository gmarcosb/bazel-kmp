// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.packages

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

/**
 * Concrete implementation of [PackageLoader] that uses skyframe under the covers, but with no
 * caching or incrementality.
 */
class BazelPackageLoader private constructor(builder: Builder) : AbstractPackageLoader(builder) {
    /** Builder for [BazelPackageLoader] instances.  */
    class Builder private constructor(
        workspaceDir: Root,
        installBase: com.google.devtools.build.lib.vfs.Path?,
        outputBase: com.google.devtools.build.lib.vfs.Path?
    ) : com.google.devtools.build.lib.skyframe.packages.AbstractPackageLoader.Builder(
        workspaceDir,
        installBase,
        outputBase,
        BUILD_FILES_BY_PRIORITY,
        ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS
    ) {
        // Prevent PackageLoader from fetching any remote repositories; these should only be fetched by
        // Bazel before calling PackageLoader.
        private var fetchDisabled = true

        init {
            addExtraPrecomputedValues(
                PrecomputedValue.Companion.injected<MutableMap<String?, String?>?>(
                    PrecomputedValue.Companion.ACTION_ENV,
                    com.google.common.collect.ImmutableMap.of<String?, String?>()
                ),
                PrecomputedValue.Companion.injected<com.google.common.collect.ImmutableMap<String?, String?>?>(
                    PrecomputedValue.Companion.REPO_ENV,
                    com.google.common.collect.ImmutableMap.of<String?, String?>()
                ),
                injected(
                    RepoDefinitionFunction.REPOSITORY_OVERRIDES,
                    com.google.common.base.Suppliers.ofInstance<T?>(com.google.common.collect.ImmutableMap.of<Any?, Any?>())
                ),
                injected(
                    RepositoryDirectoryValue.FORCE_FETCH, RepositoryDirectoryValue.FORCE_FETCH_DISABLED
                ),
                < ImmutableMap < Object, Object>>injected<com.google.common.collect.ImmutableMap<kotlin.Any?, kotlin.Any?>?>(ModuleFileFunction.INJECTED_REPOSITORIES, com.google.common.collect.ImmutableMap.of<kotlin.Any?, kotlin.Any?>()),
            <ImmutableMap<Object, Object> > injected<com.google.common.collect.ImmutableMap<Any?, Any?>?>(
                ModuleFileFunction.MODULE_OVERRIDES,
                com.google.common.collect.ImmutableMap.of<Any?, Any?>()
            ),
            injected(
                RepositoryDirectoryValue.FORCE_FETCH_CONFIGURE,
                RepositoryDirectoryValue.FORCE_FETCH_DISABLED
            ),
            <Optional<Object> > injected<java.util.Optional<Any?>?>(
                RepositoryDirectoryValue.VENDOR_DIRECTORY,
                java.util.Optional.empty<Any?>()
            ),
            injected(
                ModuleFileFunction.REGISTRIES, BazelRepositoryModule.DEFAULT_REGISTRIES
            ),
            injected(
                RegistryFunction.MODULE_MIRRORS, BazelRepositoryModule.DEFAULT_MODULE_MIRRORS
            ),
            <Boolean > injected<Boolean?>(ModuleFileFunction.IGNORE_DEV_DEPS, false),
            injected(
                BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES,
                RepositoryOptions.CheckDirectDepsMode.OFF
            ),
            injected(
                BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE,
                RepositoryOptions.BazelCompatibilityMode.OFF
            ),
            injected(
                BazelLockFileFunction.LOCKFILE_MODE, RepositoryOptions.LockfileMode.OFF
            ),
            <ImmutableList<Object> > injected<com.google.common.collect.ImmutableList<Any?>?>(
                YankedVersionsUtil.ALLOWED_YANKED_VERSIONS, com.google.common.collect.ImmutableList.of<Any?>()
            ))
        }

        public override fun buildImpl(): BazelPackageLoader {
            // Set up SkyFunctions and PrecomputedValues needed to make local repositories work correctly.
            val repositoryCache: RepositoryCache = RepositoryCache()
            val httpDownloader: HttpDownloader = HttpDownloader()
            val downloadManager: DownloadManager =
                DownloadManager(
                    repositoryCache.getDownloadCache(),
                    httpDownloader,
                    httpDownloader,  // Only used in tests, so it's okay to miss download progress events.
                    ExtendedEventHandler.NOOP
                )
            val registryFactory: RegistryFactoryImpl =
                RegistryFactoryImpl(com.google.common.base.Suppliers.ofInstance<T?>(com.google.common.collect.ImmutableMap.of<Any?, Any?>()))

            // Allow tests to override the following functions to use fake registry or custom built-in
            // modules
            if (!this.extraSkyFunctions.containsKey(SkyFunctions.MODULE_FILE)) {
                val moduleFileFunction: ModuleFileFunction =
                    ModuleFileFunction(
                        ruleClassProvider.getBazelStarlarkEnvironment(),
                        directories.getWorkspace(),
                        com.google.common.collect.ImmutableMap.copyOf(
                            com.google.common.collect.Maps.filterKeys(
                                ModuleFileFunction.getBuiltinModules(), "bazel_tools"::equals
                            )
                        )
                    )

                addExtraSkyFunctions(
                    com.google.common.collect.ImmutableMap.of<SkyFunctionName?, SkyFunction?>(
                        SkyFunctions.MODULE_FILE,
                        moduleFileFunction
                    )
                )
                moduleFileFunction.setDownloadManager(downloadManager)
            }
            if (!this.extraSkyFunctions.containsKey(SkyFunctions.REGISTRY)) {
                addExtraSkyFunctions(
                    com.google.common.collect.ImmutableMap.of<SkyFunctionName?, SkyFunction?>(
                        SkyFunctions.REGISTRY,
                        RegistryFunction(registryFactory, directories.getWorkspace())
                    )
                )
            }
            val repositoryFetchFunction: RepositoryFetchFunction =
                RepositoryFetchFunction(
                    com.google.common.collect.ImmutableMap::of,
                    com.google.common.collect.ImmutableMap::of,
                    directories,
                    repositoryCache.getRepoContentsCache()
                )
            repositoryFetchFunction.setDownloadManager(downloadManager)

            val repoSpecFunction: RepoSpecFunction = RepoSpecFunction()
            repoSpecFunction.setDownloadManager(downloadManager)

            val yankedVersionsFunction: YankedVersionsFunction = YankedVersionsFunction()
            yankedVersionsFunction.setDownloadManager(downloadManager)

            addExtraSkyFunctions(
                com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                    .put(
                        SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE,
                        ClientEnvironmentFunction(AtomicReference<V?>(com.google.common.collect.ImmutableMap.of<Any?, Any?>()))
                    )
                    .put(
                        SkyFunctions.DIRECTORY_LISTING_STATE,
                        DirectoryListingStateFunction(externalFilesHelper, SyscallCache.NO_CACHE)
                    )
                    .put(SkyFunctions.ACTION_ENVIRONMENT_VARIABLE, ActionEnvironmentFunction())
                    .put(SkyFunctions.REPOSITORY_ENVIRONMENT_VARIABLE, RepoEnvironmentFunction())
                    .put(SkyFunctions.DIRECTORY_LISTING, DirectoryListingFunction())
                    .put(SkyFunctions.LOCAL_REPOSITORY_LOOKUP, LocalRepositoryLookupFunction())
                    .put(SkyFunctions.REPOSITORY_DIRECTORY, repositoryFetchFunction)
                    .put(RepoDefinitionValue.REPO_DEFINITION, RepoDefinitionFunction(directories))
                    .put(
                        SkyFunctions.BAZEL_LOCK_FILE,
                        BazelLockFileFunction(
                            directories.getWorkspace(), directories.getOutputBase()
                        )
                    )
                    .put(SkyFunctions.BAZEL_DEP_GRAPH, BazelDepGraphFunction())
                    .put(SkyFunctions.BAZEL_MODULE_RESOLUTION, BazelModuleResolutionFunction())
                    .put(SkyFunctions.REPO_SPEC, repoSpecFunction)
                    .put(SkyFunctions.YANKED_VERSIONS, yankedVersionsFunction)
                    .buildOrThrow()
            )
            addExtraPrecomputedValues(
                < Boolean > injected < Boolean ? > (RepositoryDirectoryValue.FETCH_DISABLED, fetchDisabled))

            return BazelPackageLoader(this)
        }

        val defaultRuleClassProvider: ConfiguredRuleClassProvider
            get() = com.google.devtools.build.lib.skyframe.packages.BazelPackageLoader.Builder.Companion.DEFAULT_RULE_CLASS_PROVIDER

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun enableFetchForTesting(): Builder {
            this.fetchDisabled = false
            return this
        }

        companion object {
            private val DEFAULT_RULE_CLASS_PROVIDER: ConfiguredRuleClassProvider =
                com.google.devtools.build.lib.skyframe.packages.BazelPackageLoader.Builder.Companion.createRuleClassProvider()

            private fun createRuleClassProvider(): ConfiguredRuleClassProvider {
                val classProvider: ConfiguredRuleClassProvider.Builder = Builder()
                BazelRepositoryModule().initializeRuleClasses(classProvider)
                BazelRulesModule().initializeRuleClasses(classProvider)
                return classProvider.build()
            }
        }
    }

    val crossRepositoryLabelViolationStrategy: CrossRepositoryLabelViolationStrategy
        get() = BazelSkyframeExecutorConstants.CROSS_REPOSITORY_LABEL_VIOLATION_STRATEGY

    val buildFilesByPriority: com.google.common.collect.ImmutableList<BuildFileName?>
        get() = BUILD_FILES_BY_PRIORITY

    val actionOnIOExceptionReadingBuildFile: ActionOnIOExceptionReadingBuildFile?
        get() = BazelSkyframeExecutorConstants.ACTION_ON_IO_EXCEPTION_READING_BUILD_FILE

    override fun shouldUseRepoDotBazel(): Boolean {
        return BazelSkyframeExecutorConstants.USE_REPO_DOT_BAZEL
    }

    companion object {
        private val BUILD_FILES_BY_PRIORITY: com.google.common.collect.ImmutableList<BuildFileName?> =
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY

        /** Returns a fresh [Builder] instance.  */
        fun builder(
            workspaceDir: Root,
            installBase: com.google.devtools.build.lib.vfs.Path?,
            outputBase: com.google.devtools.build.lib.vfs.Path?
        ): Builder {
            return com.google.devtools.build.lib.skyframe.packages.BazelPackageLoader.Builder(
                workspaceDir,
                installBase,
                outputBase
            )
        }
    }
}
