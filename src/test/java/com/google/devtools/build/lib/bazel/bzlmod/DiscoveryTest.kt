// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Tests for [Discovery].  */
@RunWith(JUnit4::class)
class DiscoveryTest : FoundationTestCase() {
    private var workspaceRoot: Path? = null
    private var evaluator: MemoizingEvaluator? = null
    private var differencer: RecordingDifferencer? = null
    private var evaluationContext: EvaluationContext? = null
    private var registryFactory: com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry.Factory? = null

    /**
     * @param registryFileHashes Uses `Optional<String>` rather than `Optional<Checksum>`
     * for easier testing (Checksum doesn't implement `equals()`).
     */
    internal class DiscoveryValue(
        depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>?,
        registryFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<String?>?>?
    ) : SkyValue {
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?>?
        val registryFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<String?>?>?

        init {
            this.depGraph = depGraph
            this.registryFileHashes = registryFileHashes
        }

        companion object {
            val FUNCTION_NAME: SkyFunctionName? = SkyFunctionName.createHermetic("test_discovery")
            val KEY: SkyKey = SkyKey { FUNCTION_NAME }
        }
    }

    internal class DiscoveryFunction : SkyFunction {
        @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
        public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
            val root: RootModuleFileValue? =
                env.getValue(ModuleFileValue.KEY_FOR_ROOT_MODULE) as RootModuleFileValue?
            if (root == null) {
                return null
            }
            val discoveryResult: Discovery.Result?
            try {
                discoveryResult = Discovery.run(env, root)
            } catch (e: ExternalDepsException) {
                throw BazelModuleResolutionFunctionException(
                    e, SkyFunctionException.Transience.PERSISTENT
                )
            }
            return if (discoveryResult == null)
                null
            else
                DiscoveryValue(
                    discoveryResult.depGraph(),
                    com.google.common.collect.ImmutableMap.copyOf(
                        com.google.common.collect.Maps.transformValues(
                            discoveryResult.registryFileHashes(),
                            { value -> value.map(com.google.devtools.build.lib.bazel.repository.downloader.Checksum::toString) })
                    )
                )
        }
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setup() {
        setUpWithBuiltinModules(com.google.common.collect.ImmutableMap.of<String?, NonRegistryOverride?>())
    }

    @Throws(java.lang.Exception::class)
    private fun setUpWithBuiltinModules(builtinModules: com.google.common.collect.ImmutableMap<String?, NonRegistryOverride?>?) {
        workspaceRoot = scratch.dir("/ws")
        differencer = SequencedRecordingDifferencer()
        evaluationContext =
            EvaluationContext.newBuilder().setParallelism(8).setEventHandler(reporter).build()
        registryFactory = com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry.Factory()
        val packageLocator: AtomicReference<PathPackageLocator?> =
            AtomicReference<PathPackageLocator?>(
                PathPackageLocator(
                    outputBase,
                    com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(rootDirectory)),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                )
            )
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(rootDirectory, outputBase, rootDirectory),
                rootDirectory,
                AnalysisMock.Companion.get().getProductName()
            )
        val externalFilesHelper: ExternalFilesHelper? =
            ExternalFilesHelper.createForTesting(
                packageLocator,
                ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
                directories
            )
        val ruleClassProvider: ConfiguredRuleClassProvider = AnalysisMock.Companion.get().createRuleClassProvider()

        evaluator =
            InMemoryMemoizingEvaluator(
                com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                    .put(SkyFunctions.FILE, FileFunction(packageLocator, directories))
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
                    .put(DiscoveryValue.Companion.FUNCTION_NAME, DiscoveryFunction())
                    .put(
                        SkyFunctions.BAZEL_LOCK_FILE,
                        BazelLockFileFunction(rootDirectory, directories.getOutputBase())
                    )
                    .put(
                        SkyFunctions.MODULE_FILE,
                        ModuleFileFunction(
                            ruleClassProvider.getBazelStarlarkEnvironment(),
                            workspaceRoot,
                            builtinModules
                        )
                    )
                    .put(SkyFunctions.PRECOMPUTED, PrecomputedFunction())
                    .put(
                        SkyFunctions.REPOSITORY_DIRECTORY,
                        RepositoryFetchFunction(
                            com.google.common.collect.ImmutableMap::of,
                            com.google.common.collect.ImmutableMap::of,
                            directories,
                            LocalRepoContentsCache()
                        )
                    )
                    .put(RepoDefinitionValue.REPO_DEFINITION, RepoDefinitionFunction(directories))
                    .put(
                        SkyFunctions.REGISTRY,
                        RegistryFunction(registryFactory, directories.getWorkspace())
                    )
                    .put(SkyFunctions.REPO_SPEC, RepoSpecFunction())
                    .put(SkyFunctions.YANKED_VERSIONS, YankedVersionsFunction())
                    .put(
                        SkyFunctions.MODULE_EXTENSION_REPO_MAPPING_ENTRIES,
                        ModuleExtensionRepoMappingEntriesFunction()
                    )
                    .put(
                        SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE,
                        ClientEnvironmentFunction(AtomicReference<V?>(com.google.common.collect.ImmutableMap.of<Any?, Any?>()))
                    )
                    .buildOrThrow(),
                differencer
            )

        PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT)
        RepoDefinitionFunction.REPOSITORY_OVERRIDES.set(
            differencer,
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        RepositoryDirectoryValue.FETCH_DISABLED.set(differencer, false)
        RepositoryDirectoryValue.FORCE_FETCH.set(
            differencer, RepositoryDirectoryValue.FORCE_FETCH_DISABLED
        )
        RepositoryDirectoryValue.VENDOR_DIRECTORY.set(differencer, java.util.Optional.empty<T?>())

        PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, packageLocator.get())
        PrecomputedValue.REPO_ENV.set(differencer, com.google.common.collect.ImmutableMap.of<K?, V?>())
        ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, false)
        ModuleFileFunction.INJECTED_REPOSITORIES.set(differencer, com.google.common.collect.ImmutableMap.of<K?, V?>())
        ModuleFileFunction.MODULE_OVERRIDES.set(differencer, com.google.common.collect.ImmutableMap.of<K?, V?>())
        YankedVersionsUtil.ALLOWED_YANKED_VERSIONS.set(differencer, com.google.common.collect.ImmutableList.of<E?>())
        BazelLockFileFunction.LOCKFILE_MODE.set(differencer, LockfileMode.UPDATE)
        RegistryFunction.MODULE_MIRRORS.set(differencer, com.google.common.collect.ImmutableMap.of<K?, V?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleDiamond() {
        scratch.file(
            workspaceRoot.getRelative("MODULE.bazel").getPathString(),
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0')",
            "bazel_dep(name='ccc',version='2.0')"
        )
        val registry: FakeRegistry =
            registryFactory
                .newFakeRegistry("/foo")
                .addModule(
                    BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                    "module(name='bbb', version='1.0');bazel_dep(name='ddd',version='3.0')"
                )
                .addModule(
                    BzlmodTestUtil.createModuleKey("ccc", "2.0"),
                    "module(name='ccc', version='2.0');bazel_dep(name='ddd',version='3.0')"
                )
                .addModule(
                    BzlmodTestUtil.createModuleKey("ddd", "3.0"),  // Add a random override here; it should be ignored
                    "module(name='ddd', version='3.0');local_path_override(module_name='ff',path='f')"
                )
        ModuleFileFunction.REGISTRIES.set(differencer, com.google.common.collect.ImmutableSet.of<E?>(registry.getUrl()))

        val result: EvaluationResult<DiscoveryValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(DiscoveryValue.Companion.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val discoveryValue: DiscoveryValue = result.get(DiscoveryValue.Companion.KEY)
        Truth.assertThat(discoveryValue.depGraph.entries)
            .containsExactly(
                InterimModuleBuilder.Companion.create("aaa", "0.1")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "3.0"))
                    .setRegistry(registry)
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "3.0"))
                    .setRegistry(registry)
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "3.0").setRegistry(registry).buildEntry()
            )
        Truth.assertThat(discoveryValue.registryFileHashes)
            .containsExactly(
                registry.getUrl() + "/modules/bbb/1.0/MODULE.bazel",
                java.util.Optional.of<String?>("3f48e6d8694e0aa0d16617fd97b7d84da0e17ee9932c18cbc71888c12563372d"),
                registry.getUrl() + "/modules/ccc/2.0/MODULE.bazel",
                java.util.Optional.of<String?>("e613d4192495192c3d46ee444dc9882a176a9e7a243d1b5a840ab0f01553e8d6"),
                registry.getUrl() + "/modules/ddd/3.0/MODULE.bazel",
                java.util.Optional.of<String?>("f80d91453520d193b0b79f1501eb902b5b01a991762cc7fb659fc580b95648fd")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDevDependency() {
        scratch.file(
            workspaceRoot.getRelative("MODULE.bazel").getPathString(),
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0')",
            "bazel_dep(name='ccc',version='1.0',dev_dependency=True)"
        )
        val registry: FakeRegistry =
            registryFactory
                .newFakeRegistry("/foo")
                .addModule(
                    BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                    "module(name='bbb', version='1.0')",
                    "bazel_dep(name='ccc',version='2.0',dev_dependency=True)"
                )
                .addModule(BzlmodTestUtil.createModuleKey("ccc", "1.0"), "module(name='ccc', version='1.0')")
                .addModule(BzlmodTestUtil.createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')")
        ModuleFileFunction.REGISTRIES.set(differencer, com.google.common.collect.ImmutableSet.of<E?>(registry.getUrl()))

        val result: EvaluationResult<DiscoveryValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(DiscoveryValue.Companion.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val discoveryValue: DiscoveryValue = result.get(DiscoveryValue.Companion.KEY)
        Truth.assertThat(discoveryValue.depGraph.entries)
            .containsExactly(
                InterimModuleBuilder.Companion.create("aaa", "0.1")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0").setRegistry(registry).buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.0").setRegistry(registry).buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIgnoreDevDependency() {
        scratch.file(
            workspaceRoot.getRelative("MODULE.bazel").getPathString(),
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0')",
            "bazel_dep(name='ccc',version='1.0',dev_dependency=True)"
        )
        val registry: FakeRegistry =
            registryFactory
                .newFakeRegistry("/foo")
                .addModule(
                    BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                    "module(name='bbb', version='1.0')",
                    "bazel_dep(name='ccc',version='2.0',dev_dependency=True)"
                )
                .addModule(BzlmodTestUtil.createModuleKey("ccc", "1.0"), "module(name='ccc', version='1.0')")
                .addModule(BzlmodTestUtil.createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')")
        ModuleFileFunction.REGISTRIES.set(differencer, com.google.common.collect.ImmutableSet.of<E?>(registry.getUrl()))
        ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, true)
        ModuleFileFunction.INJECTED_REPOSITORIES.set(differencer, com.google.common.collect.ImmutableMap.of<K?, V?>())

        val result: EvaluationResult<DiscoveryValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(DiscoveryValue.Companion.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val discoveryValue: DiscoveryValue = result.get(DiscoveryValue.Companion.KEY)
        Truth.assertThat(discoveryValue.depGraph.entries)
            .containsExactly(
                InterimModuleBuilder.Companion.create("aaa", "0.1")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0").setRegistry(registry).buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNodep_unfulfilled() {
        scratch.file(
            workspaceRoot.getRelative("MODULE.bazel").getPathString(),
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0')",
            "bazel_dep(name='ccc',version='1.0',repo_name=None)"
        )
        val registry: FakeRegistry =
            registryFactory
                .newFakeRegistry("/foo")
                .addModule(BzlmodTestUtil.createModuleKey("bbb", "1.0"), "module(name='bbb', version='1.0')")
                .addModule(BzlmodTestUtil.createModuleKey("ccc", "1.0"), "module(name='ccc', version='1.0')")
        ModuleFileFunction.REGISTRIES.set(differencer, com.google.common.collect.ImmutableSet.of<E?>(registry.getUrl()))

        val result: EvaluationResult<DiscoveryValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(DiscoveryValue.Companion.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val discoveryValue: DiscoveryValue = result.get(DiscoveryValue.Companion.KEY)
        Truth.assertThat(discoveryValue.depGraph.entries)
            .containsExactly(
                InterimModuleBuilder.Companion.create("aaa", "0.1")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0").setRegistry(registry).buildEntry()
            )
        Truth.assertThat(discoveryValue.registryFileHashes.keys)
            .containsExactly(registry.getUrl() + "/modules/bbb/1.0/MODULE.bazel")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNodep_fulfilled() {
        scratch.file(
            workspaceRoot.getRelative("MODULE.bazel").getPathString(),
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0')",
            "bazel_dep(name='ccc',version='2.0',repo_name=None)"
        )
        val registry: FakeRegistry =
            registryFactory
                .newFakeRegistry("/foo")
                .addModule(
                    BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                    "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='1.0')"
                )
                .addModule(BzlmodTestUtil.createModuleKey("ccc", "1.0"), "module(name='ccc', version='1.0')")
                .addModule(BzlmodTestUtil.createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')")
        ModuleFileFunction.REGISTRIES.set(differencer, com.google.common.collect.ImmutableSet.of<E?>(registry.getUrl()))

        val result: EvaluationResult<DiscoveryValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(DiscoveryValue.Companion.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val discoveryValue: DiscoveryValue = result.get(DiscoveryValue.Companion.KEY)
        Truth.assertThat(discoveryValue.depGraph.entries)
            .containsExactly(
                InterimModuleBuilder.Companion.create("aaa", "0.1")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addNodepDep(BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .setRegistry(registry)
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.0").setRegistry(registry).buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0").setRegistry(registry).buildEntry()
            )
        Truth.assertThat(discoveryValue.registryFileHashes.keys)
            .containsExactly(
                registry.getUrl() + "/modules/bbb/1.0/MODULE.bazel",
                registry.getUrl() + "/modules/ccc/2.0/MODULE.bazel",
                registry.getUrl() + "/modules/ccc/1.0/MODULE.bazel"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNodep_fulfilled_manyRounds() {
        scratch.file(
            workspaceRoot.getRelative("MODULE.bazel").getPathString(),
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0')",
            "bazel_dep(name='ccc',version='2.0',repo_name=None)",
            "bazel_dep(name='ddd',version='2.0',repo_name=None)"
        )
        val registry: FakeRegistry =
            registryFactory
                .newFakeRegistry("/foo")
                .addModule(
                    BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                    "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='1.0')"
                )
                .addModule(BzlmodTestUtil.createModuleKey("ccc", "1.0"), "module(name='ccc', version='1.0')")
                .addModule(
                    BzlmodTestUtil.createModuleKey("ccc", "2.0"),
                    "module(name='ccc', version='2.0');bazel_dep(name='ddd',version='1.0')"
                )
                .addModule(BzlmodTestUtil.createModuleKey("ddd", "1.0"), "module(name='ddd', version='1.0')")
                .addModule(BzlmodTestUtil.createModuleKey("ddd", "2.0"), "module(name='ddd', version='2.0')")
        ModuleFileFunction.REGISTRIES.set(differencer, com.google.common.collect.ImmutableSet.of<E?>(registry.getUrl()))

        val result: EvaluationResult<DiscoveryValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(DiscoveryValue.Companion.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val discoveryValue: DiscoveryValue = result.get(DiscoveryValue.Companion.KEY)
        Truth.assertThat(discoveryValue.depGraph.entries)
            .containsExactly(
                InterimModuleBuilder.Companion.create("aaa", "0.1")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addNodepDep(BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .addNodepDep(BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .setRegistry(registry)
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.0").setRegistry(registry).buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .setRegistry(registry)
                    .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "1.0").setRegistry(registry).buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "2.0").setRegistry(registry).buildEntry()
            )
        Truth.assertThat(discoveryValue.registryFileHashes.keys)
            .containsExactly(
                registry.getUrl() + "/modules/bbb/1.0/MODULE.bazel",
                registry.getUrl() + "/modules/ccc/2.0/MODULE.bazel",
                registry.getUrl() + "/modules/ddd/2.0/MODULE.bazel",
                registry.getUrl() + "/modules/ccc/1.0/MODULE.bazel",
                registry.getUrl() + "/modules/ddd/1.0/MODULE.bazel"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNodep_fulfilled_withOverride() {
        // Regression test for https://github.com/bazelbuild/bazel/issues/26495
        scratch.file(
            workspaceRoot.getRelative("MODULE.bazel").getPathString(),
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0')",
            "bazel_dep(name='ccc',version='1.0')",
            "single_version_override(module_name='bbb',version='2.0')"
        )
        val registry: FakeRegistry =
            registryFactory
                .newFakeRegistry("/foo")
                .addModule(BzlmodTestUtil.createModuleKey("bbb", "1.0"), "module(name='bbb', version='1.0')")
                .addModule(BzlmodTestUtil.createModuleKey("bbb", "2.0"), "module(name='bbb', version='2.0')")
                .addModule(
                    BzlmodTestUtil.createModuleKey("ccc", "1.0"),
                    "module(name='ccc', version='1.0')",
                    "bazel_dep(name='bbb', version='1.0', repo_name=None)"
                )
        ModuleFileFunction.REGISTRIES.set(differencer, com.google.common.collect.ImmutableSet.of<E?>(registry.getUrl()))

        val result: EvaluationResult<DiscoveryValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(DiscoveryValue.Companion.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val discoveryValue: DiscoveryValue = result.get(DiscoveryValue.Companion.KEY)
        Truth.assertThat(discoveryValue.depGraph.entries)
            .containsExactly(
                InterimModuleBuilder.Companion.create("aaa", "0.1")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "2.0"))
                    .addOriginalDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "2.0").setRegistry(registry).buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.0")
                    .addNodepDep(BzlmodTestUtil.createModuleKey("bbb", "2.0"))
                    .setRegistry(registry)
                    .buildEntry()
            )
        Truth.assertThat(discoveryValue.registryFileHashes.keys)
            .containsExactly(
                registry.getUrl() + "/modules/bbb/2.0/MODULE.bazel",
                registry.getUrl() + "/modules/ccc/1.0/MODULE.bazel"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCircularDependency() {
        scratch.file(
            workspaceRoot.getRelative("MODULE.bazel").getPathString(),
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0')"
        )
        val registry: FakeRegistry =
            registryFactory
                .newFakeRegistry("/foo")
                .addModule(
                    BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                    "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')"
                )
                .addModule(
                    BzlmodTestUtil.createModuleKey("ccc", "2.0"),
                    "module(name='ccc', version='2.0');bazel_dep(name='bbb',version='1.0')"
                )
        ModuleFileFunction.REGISTRIES.set(differencer, com.google.common.collect.ImmutableSet.of<E?>(registry.getUrl()))

        val result: EvaluationResult<DiscoveryValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(DiscoveryValue.Companion.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val discoveryValue: DiscoveryValue = result.get(DiscoveryValue.Companion.KEY)
        Truth.assertThat(discoveryValue.depGraph.entries)
            .containsExactly(
                InterimModuleBuilder.Companion.create("aaa", "0.1")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .setRegistry(registry)
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .setRegistry(registry)
                    .buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCircularDependencyOnRootModule() {
        scratch.file(
            workspaceRoot.getRelative("MODULE.bazel").getPathString(),
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0')"
        )
        val registry: FakeRegistry =
            registryFactory
                .newFakeRegistry("/foo")
                .addModule(
                    BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                    "module(name='bbb', version='1.0');bazel_dep(name='aaa',version='2.0')"
                )
                .addModule(BzlmodTestUtil.createModuleKey("aaa", "2.0"), "module(name='aaa', version='2.0')")
        ModuleFileFunction.REGISTRIES.set(differencer, com.google.common.collect.ImmutableSet.of<E?>(registry.getUrl()))

        val result: EvaluationResult<DiscoveryValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(DiscoveryValue.Companion.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val discoveryValue: DiscoveryValue = result.get(DiscoveryValue.Companion.KEY)
        Truth.assertThat(discoveryValue.depGraph.entries)
            .containsExactly(
                InterimModuleBuilder.Companion.create("aaa", "0.1")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("aaa", ModuleKey.ROOT)
                    .addOriginalDep("aaa", BzlmodTestUtil.createModuleKey("aaa", "2.0"))
                    .setRegistry(registry)
                    .buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleVersionOverride() {
        scratch.file(
            workspaceRoot.getRelative("MODULE.bazel").getPathString(),
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='0.1')",
            "single_version_override(module_name='ccc',version='2.0')"
        )
        val registry: FakeRegistry =
            registryFactory
                .newFakeRegistry("/foo")
                .addModule(
                    BzlmodTestUtil.createModuleKey("bbb", "0.1"),
                    "module(name='bbb', version='0.1');bazel_dep(name='ccc',version='1.0')"
                )
                .addModule(BzlmodTestUtil.createModuleKey("ccc", "1.0"), "module(name='ccc', version='1.0');")
                .addModule(BzlmodTestUtil.createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0');")
        ModuleFileFunction.REGISTRIES.set(differencer, com.google.common.collect.ImmutableSet.of<E?>(registry.getUrl()))

        val result: EvaluationResult<DiscoveryValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(DiscoveryValue.Companion.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val discoveryValue: DiscoveryValue = result.get(DiscoveryValue.Companion.KEY)
        Truth.assertThat(discoveryValue.depGraph.entries)
            .containsExactly(
                InterimModuleBuilder.Companion.create("aaa", "0.1")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "0.1"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "0.1")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .addOriginalDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .setRegistry(registry)
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0").setRegistry(registry).buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegistryOverride() {
        val registry1: FakeRegistry =
            registryFactory
                .newFakeRegistry("/foo")
                .addModule(
                    BzlmodTestUtil.createModuleKey("bbb", "0.1"),
                    "module(name='bbb', version='0.1');bazel_dep(name='ccc',version='1.0')"
                )
                .addModule(BzlmodTestUtil.createModuleKey("ccc", "1.0"), "module(name='ccc', version='1.0');")
        val registry2: FakeRegistry =
            registryFactory
                .newFakeRegistry("/bar")
                .addModule(
                    BzlmodTestUtil.createModuleKey("ccc", "1.0"),
                    "module(name='ccc', version='1.0');bazel_dep(name='bbb',version='0.1')"
                )
        scratch.file(
            workspaceRoot.getRelative("MODULE.bazel").getPathString(),
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='0.1')",
            "single_version_override(module_name='ccc',registry='" + registry2.getUrl() + "')"
        )
        ModuleFileFunction.REGISTRIES.set(
            differencer,
            com.google.common.collect.ImmutableSet.of<E?>(registry1.getUrl())
        )

        val result: EvaluationResult<DiscoveryValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(DiscoveryValue.Companion.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val discoveryValue: DiscoveryValue = result.get(DiscoveryValue.Companion.KEY)
        Truth.assertThat(discoveryValue.depGraph.entries)
            .containsExactly(
                InterimModuleBuilder.Companion.create("aaa", "0.1")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "0.1"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "0.1")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .setRegistry(registry1)
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.0")
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "0.1"))
                    .setRegistry(registry2)
                    .buildEntry()
            )
    }

    @Ignore(
        ("b/389163906 - figure out how to convert this class to BuildViewTestCase; the need for a"
                + " custom SkyFunction kind of breaks it")
    )
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocalPathOverride() {
        val pathToC: Path = scratch.dir("/pathToC")
        scratch.file(
            pathToC.getRelative("MODULE.bazel").getPathString(), "module(name='ccc',version='2.0')"
        )
        scratch.file(
            workspaceRoot.getRelative("MODULE.bazel").getPathString(),
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='0.1')",
            "local_path_override(module_name='ccc',path='" + pathToC.getPathString() + "')"
        )
        val registry: FakeRegistry =
            registryFactory
                .newFakeRegistry("/foo")
                .addModule(
                    BzlmodTestUtil.createModuleKey("bbb", "0.1"),
                    "module(name='bbb', version='0.1');bazel_dep(name='ccc',version='1.0')"
                )
                .addModule(BzlmodTestUtil.createModuleKey("ccc", "1.0"), "module(name='ccc', version='1.0');")
        ModuleFileFunction.REGISTRIES.set(differencer, com.google.common.collect.ImmutableSet.of<E?>(registry.getUrl()))

        val result: EvaluationResult<DiscoveryValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(DiscoveryValue.Companion.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val discoveryValue: DiscoveryValue = result.get(DiscoveryValue.Companion.KEY)
        Truth.assertThat(discoveryValue.depGraph.entries)
            .containsExactly(
                InterimModuleBuilder.Companion.create("aaa", "0.1")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "0.1"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "0.1")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", ""))
                    .addOriginalDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .setRegistry(registry)
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .setKey(BzlmodTestUtil.createModuleKey("ccc", ""))
                    .buildEntry()
            )
        Truth.assertThat(discoveryValue.registryFileHashes)
            .containsExactly(
                registry.getUrl() + "/modules/bbb/0.1/MODULE.bazel",
                java.util.Optional.of<String?>("3f9e1a600b4adeee1c1a92b92df9d086eca4bbdde656c122872f48f8f3b874a3")
            )
            .inOrder()
    }

    @Ignore(
        ("b/389163906 - figure out how to convert this class to BuildViewTestCase; the need for a"
                + " custom SkyFunction kind of breaks it")
    )
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuiltinModules_forRoot() {
        val builtinModules: com.google.common.collect.ImmutableMap<String?, NonRegistryOverride?> =
            com.google.common.collect.ImmutableMap.of<String?, NonRegistryOverride?>(
                "bazel_tools",
                NonRegistryOverride(
                    LocalPathRepoSpecs.create(rootDirectory.getRelative("tools").getPathString())
                ),
                "other_tools",
                NonRegistryOverride(
                    LocalPathRepoSpecs.create(
                        rootDirectory.getRelative("other_tools").getPathString()
                    )
                )
            )
        setUpWithBuiltinModules(builtinModules)
        scratch.file(
            workspaceRoot.getRelative("MODULE.bazel").getPathString(),
            "bazel_dep(name='foo',version='2.0')"
        )
        scratch.file(
            rootDirectory.getRelative("tools/MODULE.bazel").getPathString(),
            "module(name='bazel_tools',version='1.0')",
            "bazel_dep(name='foo',version='1.0')"
        )
        scratch.file(
            rootDirectory.getRelative("other_tools/MODULE.bazel").getPathString(),
            "module(name='other_tools')"
        )
        val registry: FakeRegistry =
            registryFactory
                .newFakeRegistry("/foo")
                .addModule(BzlmodTestUtil.createModuleKey("foo", "1.0"), "module(name='foo', version='1.0')")
                .addModule(BzlmodTestUtil.createModuleKey("foo", "2.0"), "module(name='foo', version='2.0')")
        ModuleFileFunction.REGISTRIES.set(differencer, com.google.common.collect.ImmutableSet.of<E?>(registry.getUrl()))

        val result: EvaluationResult<DiscoveryValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(DiscoveryValue.Companion.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val discoveryValue: DiscoveryValue = result.get(DiscoveryValue.Companion.KEY)
        Truth.assertThat(discoveryValue.depGraph.entries)
            .containsExactly(
                InterimModuleBuilder.Companion.create("", "")
                    .addDep("bazel_tools", BzlmodTestUtil.createModuleKey("bazel_tools", ""))
                    .addDep("other_tools", BzlmodTestUtil.createModuleKey("other_tools", ""))
                    .addDep("foo", BzlmodTestUtil.createModuleKey("foo", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bazel_tools", "1.0")
                    .setKey(BzlmodTestUtil.createModuleKey("bazel_tools", ""))
                    .addDep("other_tools", BzlmodTestUtil.createModuleKey("other_tools", ""))
                    .addDep("foo", BzlmodTestUtil.createModuleKey("foo", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("other_tools", "")
                    .setKey(BzlmodTestUtil.createModuleKey("other_tools", ""))
                    .addDep("bazel_tools", BzlmodTestUtil.createModuleKey("bazel_tools", ""))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("foo", "1.0")
                    .addDep("bazel_tools", BzlmodTestUtil.createModuleKey("bazel_tools", ""))
                    .addDep("other_tools", BzlmodTestUtil.createModuleKey("other_tools", ""))
                    .setRegistry(registry)
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("foo", "2.0")
                    .addDep("bazel_tools", BzlmodTestUtil.createModuleKey("bazel_tools", ""))
                    .addDep("other_tools", BzlmodTestUtil.createModuleKey("other_tools", ""))
                    .setRegistry(registry)
                    .buildEntry()
            )

        Truth.assertThat(discoveryValue.registryFileHashes)
            .containsExactly(
                registry.getUrl() + "/modules/foo/2.0/MODULE.bazel",
                java.util.Optional.of<String?>("76ecb05b455aecab4ec958c1deb17e4cbbe6e708d9c4e85fceda2317f6c86d7b"),
                registry.getUrl() + "/modules/foo/1.0/MODULE.bazel",
                java.util.Optional.of<String?>("4d887e8dfc1863861e3aa5601eeeebca5d8f110977895f1de4bdb2646e546fb5")
            )
            .inOrder()
    }
}
