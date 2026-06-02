// Copyright 2022 The Bazel Authors. All rights reserved.
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

/** Tests for [BazelDepGraphFunction].  */
@RunWith(JUnit4::class)
class BazelDepGraphFunctionTest : FoundationTestCase() {
    private var evaluator: MemoizingEvaluator? = null
    private var evaluationContext: EvaluationContext? = null
    private var resolutionFunctionMock: BazelModuleResolutionFunctionMock? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setup() {
        val differencer: RecordingDifferencer = SequencedRecordingDifferencer()
        evaluationContext =
            EvaluationContext.newBuilder().setParallelism(8).setEventHandler(reporter).build()

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

        resolutionFunctionMock = BazelModuleResolutionFunctionMock()

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
                    .put(
                        SkyFunctions.MODULE_FILE,
                        ModuleFileFunction(
                            TestRuleClassProvider.getRuleClassProvider().getBazelStarlarkEnvironment(),
                            rootDirectory,
                            com.google.common.collect.ImmutableMap.of<K?, V?>()
                        )
                    )
                    .put(SkyFunctions.PRECOMPUTED, PrecomputedFunction())
                    .put(
                        SkyFunctions.BAZEL_LOCK_FILE,
                        BazelLockFileFunction(rootDirectory, directories.getOutputBase())
                    )
                    .put(SkyFunctions.BAZEL_DEP_GRAPH, BazelDepGraphFunction())
                    .put(SkyFunctions.BAZEL_MODULE_RESOLUTION, resolutionFunctionMock)
                    .put(
                        SkyFunctions.REGISTRY,
                        RegistryFunction(
                            com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry.Factory(),
                            directories.getWorkspace()
                        )
                    )
                    .put(SkyFunctions.REPO_SPEC, RepoSpecFunction())
                    .put(SkyFunctions.YANKED_VERSIONS, YankedVersionsFunction())
                    .put(
                        SkyFunctions.MODULE_EXTENSION_REPO_MAPPING_ENTRIES,
                        ModuleExtensionRepoMappingEntriesFunction()
                    )
                    .put(
                        SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE,
                        ClientEnvironmentFunction(
                            AtomicReference<V?>(
                                com.google.common.collect.ImmutableMap.of<String?, String?>(
                                    "BZLMOD_ALLOW_YANKED_VERSIONS",
                                    ""
                                )
                            )
                        )
                    )
                    .buildOrThrow(),
                differencer
            )

        PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT)
        ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, false)
        ModuleFileFunction.INJECTED_REPOSITORIES.set(differencer, com.google.common.collect.ImmutableMap.of<K?, V?>())
        ModuleFileFunction.REGISTRIES.set(differencer, com.google.common.collect.ImmutableSet.of<E?>())
        RegistryFunction.MODULE_MIRRORS.set(differencer, com.google.common.collect.ImmutableMap.of<K?, V?>())
        ModuleFileFunction.MODULE_OVERRIDES.set(differencer, com.google.common.collect.ImmutableMap.of<K?, V?>())
        BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES.set(
            differencer, CheckDirectDepsMode.OFF
        )
        BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE.set(
            differencer, BazelCompatibilityMode.ERROR
        )
        BazelLockFileFunction.LOCKFILE_MODE.set(differencer, LockfileMode.UPDATE)
        YankedVersionsUtil.ALLOWED_YANKED_VERSIONS.set(differencer, com.google.common.collect.ImmutableList.of<E?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createValue_basic() {
        // Root depends on dep@1.0 and dep@2.0 at the same time with a multiple-version override.
        // Root also depends on rules_cc as a normal dep.
        // dep@1.0 depends on rules_java, which is overridden by a non-registry override (see below).
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, java.lang.Module?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, java.lang.Module?>()
                .put(
                    ModuleKey.ROOT,
                    BzlmodTestUtil.buildModule("my_root", "1.0")
                        .setKey(ModuleKey.ROOT)
                        .addDep("my_dep_1", BzlmodTestUtil.createModuleKey("dep", "1.0"))
                        .addDep("my_dep_2", BzlmodTestUtil.createModuleKey("dep", "2.0"))
                        .addDep("rules_cc", BzlmodTestUtil.createModuleKey("rules_cc", "1.0"))
                        .setFlagAliases(com.google.common.collect.ImmutableMap.of<K?, V?>())
                        .build()
                )
                .put(
                    BzlmodTestUtil.createModuleKey("dep", "1.0"),
                    BzlmodTestUtil.buildModule("dep", "1.0")
                        .addDep("rules_java", BzlmodTestUtil.createModuleKey("rules_java", ""))
                        .setFlagAliases(com.google.common.collect.ImmutableMap.of<K?, V?>())
                        .build()
                )
                .put(
                    BzlmodTestUtil.createModuleKey("dep", "2.0"),
                    BzlmodTestUtil.buildModule("dep", "2.0")
                        .setFlagAliases(com.google.common.collect.ImmutableMap.of<K?, V?>()).build()
                )
                .put(
                    BzlmodTestUtil.createModuleKey("rules_cc", "1.0"),
                    BzlmodTestUtil.buildModule("rules_cc", "1.0")
                        .setFlagAliases(com.google.common.collect.ImmutableMap.of<K?, V?>()).build()
                )
                .put(
                    BzlmodTestUtil.createModuleKey("rules_java", ""),
                    BzlmodTestUtil.buildModule("rules_java", "1.0")
                        .setKey(BzlmodTestUtil.createModuleKey("rules_java", ""))
                        .setFlagAliases(com.google.common.collect.ImmutableMap.of<K?, V?>())
                        .build()
                )
                .buildOrThrow()

        resolutionFunctionMock!!.setDepGraph(depGraph)
        val result: EvaluationResult<BazelDepGraphValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(BazelDepGraphValue.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val value: BazelDepGraphValue = result.get(BazelDepGraphValue.KEY)
        assertThat(value.canonicalRepoNameLookup)
            .containsExactly(
                RepositoryName.MAIN,
                ModuleKey.ROOT,
                RepositoryName.create("dep+1.0"),
                BzlmodTestUtil.createModuleKey("dep", "1.0"),
                RepositoryName.create("dep+2.0"),
                BzlmodTestUtil.createModuleKey("dep", "2.0"),
                RepositoryName.create("rules_cc+"),
                BzlmodTestUtil.createModuleKey("rules_cc", "1.0"),
                RepositoryName.create("rules_java+"),
                BzlmodTestUtil.createModuleKey("rules_java", "")
            )
        assertThat(value.abridgedModules)
            .containsExactlyElementsIn(
                depGraph.values.stream().map<Any?>(AbridgedModule::from)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createValue_moduleExtensions() {
        val rjeKey: ModuleKey = BzlmodTestUtil.createModuleKey("rules_jvm_external", "1.0")
        val rpyKey: ModuleKey = BzlmodTestUtil.createModuleKey("rules_python", "2.0")
        val root: java.lang.Module =
            BzlmodTestUtil.buildModule("root", "1.0")
                .setKey(ModuleKey.ROOT)
                .addDep("rje", rjeKey)
                .addDep("rpy", rpyKey)
                .addExtensionUsage(
                    createModuleExtensionUsage("@rje//:defs.bzl", "maven", "av", "autovalue")
                )
                .addExtensionUsage(
                    createModuleExtensionUsage("@rpy//:defs.bzl", "pip", "numpy", "numpy")
                )
                .setFlagAliases(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .build()
        val depKey: ModuleKey = BzlmodTestUtil.createModuleKey("dep", "2.0")
        val dep: java.lang.Module =
            BzlmodTestUtil.buildModule("dep", "2.0")
                .setKey(depKey)
                .addDep("rules_python", rpyKey)
                .addExtensionUsage(
                    createModuleExtensionUsage("@rules_python//:defs.bzl", "pip", "np", "numpy")
                )
                .addExtensionUsage(
                    createModuleExtensionUsage("//:defs.bzl", "myext", "oneext", "myext")
                )
                .addExtensionUsage(
                    createModuleExtensionUsage("//incredible:conflict.bzl", "myext", "twoext", "myext")
                )
                .setFlagAliases(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .build()
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, java.lang.Module?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                ModuleKey.ROOT,
                root,
                depKey,
                dep,
                rjeKey,
                BzlmodTestUtil.buildModule("rules_jvm_external", "1.0")
                    .setKey(rjeKey)
                    .setFlagAliases(com.google.common.collect.ImmutableMap.of<K?, V?>())
                    .build(),
                rpyKey,
                BzlmodTestUtil.buildModule("rules_python", "2.0")
                    .setKey(rpyKey)
                    .setFlagAliases(com.google.common.collect.ImmutableMap.of<K?, V?>())
                    .build()
            )

        val maven: ModuleExtensionId? =
            ModuleExtensionId.create(
                Label.parseCanonical("@@rules_jvm_external+//:defs.bzl"), "maven", java.util.Optional.empty<T?>()
            )
        val pip: ModuleExtensionId? =
            ModuleExtensionId.create(
                Label.parseCanonical("@@rules_python+//:defs.bzl"), "pip", java.util.Optional.empty<T?>()
            )
        val myext: ModuleExtensionId? =
            ModuleExtensionId.create(
                Label.parseCanonical("@@dep+//:defs.bzl"), "myext", java.util.Optional.empty<T?>()
            )
        val myext2: ModuleExtensionId? =
            ModuleExtensionId.create(
                Label.parseCanonical("@@dep+//incredible:conflict.bzl"), "myext", java.util.Optional.empty<T?>()
            )

        resolutionFunctionMock!!.setDepGraph(depGraph)
        val result: EvaluationResult<BazelDepGraphValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(BazelDepGraphValue.KEY),
                evaluationContext
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val value: BazelDepGraphValue = result.get(BazelDepGraphValue.KEY)

        assertThat(value.getExtensionUsagesTable()).hasSize(5)
        assertThat(value.getExtensionUsagesTable())
            .containsCell(maven, ModuleKey.ROOT, root.getExtensionUsages().get(0))
        assertThat(value.getExtensionUsagesTable())
            .containsCell(pip, ModuleKey.ROOT, root.getExtensionUsages().get(1))
        assertThat(value.getExtensionUsagesTable())
            .containsCell(pip, depKey, dep.getExtensionUsages().get(0))
        assertThat(value.getExtensionUsagesTable())
            .containsCell(myext, depKey, dep.getExtensionUsages().get(1))
        assertThat(value.getExtensionUsagesTable())
            .containsCell(myext2, depKey, dep.getExtensionUsages().get(2))

        assertThat(value.getExtensionUniqueNames())
            .containsExactly(
                maven, "rules_jvm_external++maven",
                pip, "rules_python++pip",
                myext, "dep++myext",
                myext2, "dep++myext2"
            )

        assertThat(value.getFullRepoMapping(ModuleKey.ROOT))
            .isEqualTo(
                BzlmodTestUtil.createRepositoryMapping(
                    ModuleKey.ROOT,
                    "",
                    "",
                    "root",
                    "",
                    "rje",
                    "rules_jvm_external+",
                    "rpy",
                    "rules_python+",
                    "av",
                    "rules_jvm_external++maven+autovalue",
                    "numpy",
                    "rules_python++pip+numpy"
                )
            )
        assertThat(value.getFullRepoMapping(depKey))
            .isEqualTo(
                BzlmodTestUtil.createRepositoryMapping(
                    depKey,
                    "dep",
                    "dep+",
                    "rules_python",
                    "rules_python+",
                    "np",
                    "rules_python++pip+numpy",
                    "oneext",
                    "dep++myext+myext",
                    "twoext",
                    "dep++myext2+myext"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun useExtensionBadLabelFails() {
        val root: java.lang.Module =
            BzlmodTestUtil.buildModule("module", "1.0")
                .setKey(ModuleKey.ROOT)
                .addExtensionUsage(createModuleExtensionUsage("@foo//:defs.bzl", "bar"))
                .setFlagAliases(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .build()
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, java.lang.Module?> =
            com.google.common.collect.ImmutableMap.of<ModuleKey?, java.lang.Module?>(ModuleKey.ROOT, root)

        resolutionFunctionMock!!.setDepGraph(depGraph)
        val result: EvaluationResult<BazelDepGraphValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(BazelDepGraphValue.KEY),
                evaluationContext
            )
        if (!result.hasError()) {
            org.junit.Assert.fail("expected error about @foo not being visible, but succeeded")
        }
        com.google.common.truth.Subject.contains("no repo visible as '@foo' here")
    }

    private class BazelModuleResolutionFunctionMock : SkyFunction {
        private var depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, java.lang.Module?>? =
            com.google.common.collect.ImmutableMap.of<ModuleKey?, java.lang.Module?>()

        fun setDepGraph(depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, java.lang.Module?>?) {
            this.depGraph = depGraph
        }

        public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue? {
            return BazelModuleResolutionValue.create(
                depGraph,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        }
    }

    companion object {
        private fun createModuleExtensionUsage(
            bzlFile: String?, name: String?, vararg imports: String?
        ): ModuleExtensionUsage {
            val importsBuilder: com.google.common.collect.ImmutableBiMap.Builder<String?, String?> =
                com.google.common.collect.ImmutableBiMap.builder<String?, String?>()
            var i = 0
            while (i < imports.size) {
                importsBuilder.put(imports[i], imports[i + 1])
                i += 2
            }
            return ModuleExtensionUsage.builder()
                .setExtensionBzlFile(bzlFile)
                .setExtensionName(name)
                .setIsolationKey(java.util.Optional.empty<T?>())
                .setRepoOverrides(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .addProxy(
                    ModuleExtensionUsage.Proxy.builder()
                        .setDevDependency(false)
                        .setLocation(net.starlark.java.syntax.Location.BUILTIN)
                        .setImports(importsBuilder.buildOrThrow())
                        .setContainingModuleFilePath(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                        .build()
                )
                .build()
        }
    }
}
