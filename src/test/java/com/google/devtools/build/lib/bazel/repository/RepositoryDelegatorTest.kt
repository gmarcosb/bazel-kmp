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

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Tests for [RepositoryFetchFunction]  */
@RunWith(JUnit4::class)
class RepositoryDelegatorTest : FoundationTestCase() {
    private var overrideDirectory: Path? = null
    private var evaluator: MemoizingEvaluator? = null
    private var differencer: RecordingDifferencer? = null
    private var rootPath: Path? = null
    private var registryFactory: com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry.Factory? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setupDelegator() {
        rootPath = scratch.dir("/outputbase")
        scratch.file(
            rootPath.getRelative("MODULE.bazel").getPathString(),
            "module(name='test',version='0.1')",
            "bazel_dep(name='bazel_tools',version='1.0')"
        )
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(rootPath, rootPath, rootPath),
                rootPath,
                TestConstants.PRODUCT_NAME
            )
        val delegatorFunction: RepositoryFetchFunction =
            RepositoryFetchFunction(
                com.google.common.collect.ImmutableMap::of,
                com.google.common.collect.ImmutableMap::of,
                directories,
                LocalRepoContentsCache()
            )
        val pkgLocator: AtomicReference<PathPackageLocator?> =
            AtomicReference<PathPackageLocator?>(
                PathPackageLocator(
                    rootPath,
                    com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(rootPath)),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                )
            )
        val externalFilesHelper: ExternalFilesHelper? =
            ExternalFilesHelper.createForTesting(
                pkgLocator,
                ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
                directories
            )
        differencer = SequencedRecordingDifferencer()

        val ruleClassProvider: ConfiguredRuleClassProvider = AnalysisMock.Companion.get().createRuleClassProvider()

        registryFactory = com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry.Factory()
        val registry: FakeRegistry =
            registryFactory
                .newFakeRegistry(scratch.dir("modules").getPathString())
                .addModule(
                    BzlmodTestUtil.createModuleKey("bazel_tools", "1.0"),
                    "module(name='bazel_tools', version='1.0');"
                )
        ModuleFileFunction.REGISTRIES.set(differencer, com.google.common.collect.ImmutableSet.of<E?>(registry.getUrl()))

        val hashFunction: com.google.common.hash.HashFunction? = fileSystem.getDigestFunction().getHashFunction()
        evaluator =
            InMemoryMemoizingEvaluator(
                com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                    .put(
                        FileStateKey.FILE_STATE,
                        FileStateFunction(
                            com.google.common.base.Suppliers.ofInstance<T?>(
                                TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())
                            ),
                            SyscallCache.NO_CACHE,
                            externalFilesHelper
                        )
                    )
                    .put(SkyFunctions.FILE, FileFunction(pkgLocator, directories))
                    .put(SkyFunctions.REPOSITORY_DIRECTORY, delegatorFunction)
                    .put(SkyFunctions.PACKAGE, PackageFunction.newBuilder().build())
                    .put(
                        SkyFunctions.PACKAGE_LOOKUP,
                        PackageLookupFunction(
                            AtomicReference<V?>(com.google.common.collect.ImmutableSet.of<Any?>()),
                            CrossRepositoryLabelViolationStrategy.ERROR,
                            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                        )
                    )
                    .put(SkyFunctions.LOCAL_REPOSITORY_LOOKUP, LocalRepositoryLookupFunction())
                    .put(SkyFunctions.PRECOMPUTED, PrecomputedFunction())
                    .put(
                        SkyFunctions.BZL_COMPILE,
                        BzlCompileFunction(
                            ruleClassProvider.getBazelStarlarkEnvironment(),
                            hashFunction,
                            PackageLoadingListener.NOOP_LISTENER
                        )
                    )
                    .put(
                        SkyFunctions.BZL_LOAD,
                        BzlLoadFunction.create(
                            ruleClassProvider,
                            directories,
                            hashFunction,
                            PackageLoadingListener.NOOP_LISTENER,
                            Caffeine.newBuilder().build<K1?, V1?>()
                        )
                    )
                    .put(
                        SkyFunctions.STARLARK_BUILTINS,
                        StarlarkBuiltinsFunction(ruleClassProvider.getBazelStarlarkEnvironment())
                    )
                    .put(SkyFunctions.CONTAINING_PACKAGE_LOOKUP, ContainingPackageLookupFunction())
                    .put(SkyFunctions.IGNORED_SUBDIRECTORIES, IgnoredSubdirectoriesFunction.NOOP)
                    .put(
                        SkyFunctions.REPOSITORY_MAPPING,
                        RepositoryMappingFunction(ruleClassProvider)
                    )
                    .put(
                        SkyFunctions.MODULE_FILE,
                        ModuleFileFunction(
                            ruleClassProvider.getBazelStarlarkEnvironment(),
                            rootPath,
                            com.google.common.collect.ImmutableMap.of<K?, V?>()
                        )
                    )
                    .put(SkyFunctions.BAZEL_DEP_GRAPH, BazelDepGraphFunction())
                    .put(
                        SkyFunctions.BAZEL_LOCK_FILE,
                        BazelLockFileFunction(rootDirectory, directories.getOutputBase())
                    )
                    .put(SkyFunctions.BAZEL_MODULE_RESOLUTION, BazelModuleResolutionFunction())
                    .put(RepoDefinitionValue.REPO_DEFINITION, RepoDefinitionFunction(directories))
                    .put(
                        SkyFunctions.REGISTRY,
                        RegistryFunction(registryFactory, directories.getWorkspace())
                    )
                    .put(SkyFunctions.REPO_SPEC, RepoSpecFunction())
                    .put(SkyFunctions.YANKED_VERSIONS, YankedVersionsFunction())
                    .put(SkyFunctions.SINGLE_EXTENSION, SingleExtensionFunction())
                    .put(
                        SkyFunctions.SINGLE_EXTENSION_EVAL,
                        SingleExtensionEvalFunction(
                            directories,
                            com.google.common.collect.ImmutableMap::of,
                            com.google.common.collect.ImmutableMap::of
                        )
                    )
                    .put(SkyFunctions.SINGLE_EXTENSION_USAGES, SingleExtensionUsagesFunction())
                    .put(
                        SkyFunctions.MODULE_EXTENSION_REPO_MAPPING_ENTRIES,
                        ModuleExtensionRepoMappingEntriesFunction()
                    )
                    .put(
                        SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE,
                        ClientEnvironmentFunction(AtomicReference<V?>(com.google.common.collect.ImmutableMap.of<Any?, Any?>()))
                    )
                    .build(),
                differencer
            )
        overrideDirectory = scratch.dir("/foo")
        scratch.file("/foo/REPO.bazel")
        RepoDefinitionFunction.REPOSITORY_OVERRIDES.set(
            differencer,
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        RepositoryDirectoryValue.IS_VENDOR_COMMAND.set(differencer, false)
        RepositoryDirectoryValue.VENDOR_DIRECTORY.set(differencer, java.util.Optional.empty<T?>())
        RepositoryDirectoryValue.FETCH_DISABLED.set(differencer, false)
        RepositoryDirectoryValue.FORCE_FETCH.set(
            differencer, RepositoryDirectoryValue.FORCE_FETCH_DISABLED
        )
        PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get())
        val semantics: StarlarkSemantics? = StarlarkSemantics.DEFAULT
        PrecomputedValue.STARLARK_SEMANTICS.set(differencer, semantics)
        PrecomputedValue.REPO_ENV.set(differencer, com.google.common.collect.ImmutableMap.of<K?, V?>())
        ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, false)
        ModuleFileFunction.INJECTED_REPOSITORIES.set(differencer, com.google.common.collect.ImmutableMap.of<K?, V?>())
        ModuleFileFunction.MODULE_OVERRIDES.set(differencer, com.google.common.collect.ImmutableMap.of<K?, V?>())
        YankedVersionsUtil.ALLOWED_YANKED_VERSIONS.set(differencer, com.google.common.collect.ImmutableList.of<E?>())
        BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES.set(
            differencer, CheckDirectDepsMode.WARNING
        )
        BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE.set(
            differencer, BazelCompatibilityMode.ERROR
        )
        BazelLockFileFunction.LOCKFILE_MODE.set(differencer, LockfileMode.UPDATE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOverride() {
        RepoDefinitionFunction.REPOSITORY_OVERRIDES.set(
            differencer, com.google.common.collect.ImmutableMap.of<K?, V?>("foo", overrideDirectory.asFragment())
        )

        val eventHandler: StoredEventHandler = StoredEventHandler()
        val key: SkyKey = RepositoryDirectoryValue.key(RepositoryName.createUnvalidated("foo"))
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(8)
                .setEventHandler(eventHandler)
                .build()
        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), evaluationContext)
        assertThat(result.hasError()).isFalse()
        val repositoryDirectoryValue: RepositoryDirectoryValue = result.get(key) as RepositoryDirectoryValue
        val expectedPath: Path = scratch.dir("/outputbase/external/foo")
        val actualPath: Path = (repositoryDirectoryValue as Success).root().asPath()
        assertThat(actualPath).isEqualTo(expectedPath)
        assertThat(actualPath.isSymbolicLink()).isTrue()
        assertThat(actualPath.readSymbolicLink()).isEqualTo(overrideDirectory.asFragment())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFetchRepositoryException_eventHandled() {
        scratch.file(
            rootPath.getRelative("rule.bzl").getPathString(),
            "def _impl(ctx):",
            "    pass",
            "sample = rule(",
            "    implementation = _impl,",
            "    toolchains = ['//:toolchain_type'],",
            ")"
        )
        scratch.file(
            rootPath.getRelative("BUILD").getPathString(),
            "load('rule.bzl', 'sample')",
            "toolchain_type(name = 'toolchain_type')",
            "sample(name = 'sample')"
        )
        scratch.file(
            rootPath.getRelative("repo_rule.bzl").getPathString(),
            "def _impl(repo_ctx):",
            "# Error: no file written",
            "    pass",
            "broken_repo = repository_rule(implementation = _impl)"
        )
        scratch.overwriteFile(
            rootPath.getRelative("MODULE.bazel").getPathString(),
            "broken_repo = use_repo_rule('//:repo_rule.bzl', 'broken_repo')",
            "broken_repo(name = 'broken')"
        )

        val eventHandler: StoredEventHandler = StoredEventHandler()
        val key: SkyKey =
            RepositoryDirectoryValue.key(RepositoryName.createUnvalidated("+broken_repo+broken"))
        // Make it be evaluated every time, as we are testing evaluation.
        differencer.invalidate(com.google.common.collect.ImmutableSet.of<E?>(key))
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(8)
                .setEventHandler(eventHandler)
                .build()
        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), evaluationContext)

        assertThat(result.hasError()).isTrue()
        assertThat(result.getError().getException())
            .isInstanceOf(AlreadyReportedRepositoryAccessException::class.java)
        Truth.assertThat(eventHandler.hasErrors()).isTrue()
        Truth.assertThat(eventHandler.getEvents()).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun loadRepositoryNotDefined() {
        // WORKSPACE is empty
        scratch.overwriteFile(rootPath.getRelative("MODULE.bazel").getPathString(), "")

        val eventHandler: StoredEventHandler = StoredEventHandler()
        val key: SkyKey = RepositoryDirectoryValue.key(RepositoryName.createUnvalidated("foo"))
        // Make it be evaluated every time, as we are testing evaluation.
        differencer.invalidate(com.google.common.collect.ImmutableSet.of<E?>(key))
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(8)
                .setEventHandler(eventHandler)
                .build()
        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), evaluationContext)
        assertThat(result.hasError()).isFalse()
        val repositoryDirectoryValue: RepositoryDirectoryValue = result.get(key) as RepositoryDirectoryValue
        assertThat(repositoryDirectoryValue).isInstanceOf(Failure::class.java)
        com.google.common.truth.Subject.contains("Repository '@@foo' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun loadInvisibleRepository() {
        val eventHandler: StoredEventHandler = StoredEventHandler()
        val key: SkyKey =
            RepositoryDirectoryValue.key(
                RepositoryName.createUnvalidated("foo")
                    .toNonVisible(RepositoryName.createUnvalidated("fake_owner_repo"))
            )
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(8)
                .setEventHandler(eventHandler)
                .build()
        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), evaluationContext)

        assertThat(result.hasError()).isFalse()
        val repositoryDirectoryValue: RepositoryDirectoryValue = result.get(key) as RepositoryDirectoryValue
        assertThat(repositoryDirectoryValue).isInstanceOf(Failure::class.java)
        com.google.common.truth.Subject.contains("No repository visible as '@foo' from repository '@@fake_owner_repo'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun loadInvisibleRepositoryFromMain() {
        // Create the WORKSPACE file to trigger error message for WORKSPACE deprecation.
        scratch.file(rootPath.getRelative("WORKSPACE").getPathString())
        val eventHandler: StoredEventHandler = StoredEventHandler()
        val key: SkyKey =
            RepositoryDirectoryValue.key(
                RepositoryName.createUnvalidated("foo").toNonVisible(RepositoryName.MAIN)
            )
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(8)
                .setEventHandler(eventHandler)
                .build()
        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), evaluationContext)

        assertThat(result.hasError()).isFalse()
        val repositoryDirectoryValue: RepositoryDirectoryValue = result.get(key) as RepositoryDirectoryValue
        assertThat(repositoryDirectoryValue).isInstanceOf(Failure::class.java)
        com.google.common.truth.Subject.contains("No repository visible as '@foo' from main repository")
    }
}
