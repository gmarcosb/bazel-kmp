// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey

/** Tests for [RepositoryMappingFunction] and [RepositoryMappingValue].  */
@RunWith(JUnit4::class)
class RepositoryMappingFunctionTest : BuildViewTestCase() {
    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    private fun eval(key: SkyKey?): EvaluationResult<RepositoryMappingValue?> {
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("MODULE.bazel")).build(),
                Root.fromPath(rootDirectory)
            )
        return SkyframeExecutorTestUtils.evaluate<T?>(
            getSkyframeExecutor(), key,  /* keepGoing= */false, reporter
        )
    }

    val analysisMock: AnalysisMock
        get() =// Make sure we have minimal built-in modules affecting the dependency graph.
            object : com.google.devtools.build.lib.analysis.util.AnalysisMock.Delegate(AnalysisMock.get()) {
                public override fun getBuiltinModules(
                    directories: BlazeDirectories
                ): com.google.common.collect.ImmutableMap<String?, NonRegistryOverride?>? {
                    if (!isThisBazel()) {
                        return com.google.common.collect.ImmutableMap.of<String?, NonRegistryOverride?>()
                    }
                    return com.google.common.collect.ImmutableMap.of<String?, NonRegistryOverride?>(
                        "bazel_tools",
                        NonRegistryOverride(
                            LocalPathRepoSpecs.create(
                                directories
                                    .getWorkingDirectory()
                                    .getRelative("embedded_tools")
                                    .getPathString()
                            )
                        ),
                        "platforms",
                        NonRegistryOverride(
                            LocalPathRepoSpecs.create(
                                directories
                                    .getWorkingDirectory()
                                    .getRelative("platforms_workspace")
                                    .getPathString()
                            )
                        )
                    )
                }
            }

    @Throws(java.lang.Exception::class)
    private fun valueForRootModule(
        repositoryMapping: com.google.common.collect.ImmutableMap<String?, RepositoryName?>,
        rootModuleName: String?,
        rootModuleVersion: String?
    ): RepositoryMappingValue {
        val allMappings: com.google.common.collect.ImmutableMap.Builder<String?, RepositoryName?> =
            com.google.common.collect.ImmutableMap.builder<String?, RepositoryName?>()
        allMappings.putAll(repositoryMapping)
        return value(
            allMappings.buildOrThrow(), RepositoryName.MAIN, rootModuleName, rootModuleVersion
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoNameMapping_asRootModule() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0', repo_name = 'com_foo_bar_b')"
        )
        registry.addModule(createModuleKey("bbb", "1.0"), "module(name='bbb', version='1.0')")

        val skyKey: SkyKey? = RepositoryMappingValue.key(RepositoryName.MAIN)
        val result: EvaluationResult<RepositoryMappingValue?> = eval(skyKey)

        assertThat(result.hasError()).isFalse()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasEntryThat(skyKey)
            .isEqualTo(
                valueForRootModule(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "",
                        RepositoryName.MAIN,
                        "aaa",
                        RepositoryName.MAIN,
                        "com_foo_bar_b",
                        RepositoryName.create("bbb+")
                    ),
                    "aaa",
                    "0.1"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoNameMapping_asRootModule_withOwnRepoName() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='aaa',version='0.1',repo_name='haha')",
            "bazel_dep(name='bbb',version='1.0', repo_name = 'com_foo_bar_b')"
        )
        registry.addModule(createModuleKey("bbb", "1.0"), "module(name='bbb', version='1.0')")

        val skyKey: SkyKey? = RepositoryMappingValue.key(RepositoryName.MAIN)
        val result: EvaluationResult<RepositoryMappingValue?> = eval(skyKey)

        assertThat(result.hasError()).isFalse()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasEntryThat(skyKey)
            .isEqualTo(
                valueForRootModule(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "",
                        RepositoryName.MAIN,
                        "haha",
                        RepositoryName.MAIN,
                        "com_foo_bar_b",
                        RepositoryName.create("bbb+")
                    ),
                    "aaa",
                    "0.1"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoNameMapping_asDependency() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0')",
            "bazel_dep(name='ccc',version='1.0', repo_name = 'com_foo_bar_c')"
        )
        registry
            .addModule(createModuleKey("bbb", "1.0"), "module(name='bbb', version='1.0')")
            .addModule(
                createModuleKey("ccc", "1.0"),
                "module(name='ccc', version='1.0')",
                "bazel_dep(name='bbb', version='1.0', repo_name='com_foo_bar_b')"
            )

        val name: RepositoryName? = RepositoryName.create("ccc+")
        val skyKey: SkyKey? = RepositoryMappingValue.key(name)
        val result: EvaluationResult<RepositoryMappingValue?> = eval(skyKey)

        assertThat(result.hasError()).isFalse()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasEntryThat(skyKey)
            .isEqualTo(
                value(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "ccc", RepositoryName.create("ccc+"),
                        "com_foo_bar_b", RepositoryName.create("bbb+")
                    ),
                    name,
                    "ccc",
                    "1.0"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoNameMapping_dependencyOnRootModule() {
        scratch.overwriteFile(
            "MODULE.bazel", "module(name='aaa',version='0.1')", "bazel_dep(name='bbb',version='1.0')"
        )
        registry.addModule(
            createModuleKey("bbb", "1.0"),
            "module(name='bbb', version='1.0')",
            "bazel_dep(name='aaa',version='3.0')"
        )

        val name: RepositoryName? = RepositoryName.create("bbb+")
        val skyKey: SkyKey? = RepositoryMappingValue.key(name)
        val result: EvaluationResult<RepositoryMappingValue?> = eval(skyKey)

        assertThat(result.hasError()).isFalse()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasEntryThat(skyKey)
            .isEqualTo(
                value(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "bbb", RepositoryName.create("bbb+"), "aaa", RepositoryName.create("")
                    ),
                    name,
                    "bbb",
                    "1.0"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoNameMapping_multipleVersionOverride_fork() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0',repo_name='bbb1')",
            "bazel_dep(name='bbb',version='2.0',repo_name='bbb2')",
            "multiple_version_override(module_name='bbb',versions=['1.0','2.0'])"
        )
        registry
            .addModule(createModuleKey("bbb", "1.0"), "module(name='bbb', version='1.0')")
            .addModule(createModuleKey("bbb", "2.0"), "module(name='bbb', version='2.0')")

        val skyKey: SkyKey? = RepositoryMappingValue.key(RepositoryName.MAIN)
        val result: EvaluationResult<RepositoryMappingValue?> = eval(skyKey)

        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasEntryThat(skyKey)
            .isEqualTo(
                valueForRootModule(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "",
                        RepositoryName.MAIN,
                        "aaa",
                        RepositoryName.MAIN,
                        "bbb1",
                        RepositoryName.create("bbb+1.0"),
                        "bbb2",
                        RepositoryName.create("bbb+2.0")
                    ),
                    "aaa",
                    "0.1"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoNameMapping_multipleVersionOverride_diamond() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0')",
            "bazel_dep(name='ccc',version='2.0')",
            "multiple_version_override(module_name='ddd',versions=['1.0','2.0'])"
        )
        registry
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb', version='1.0');bazel_dep(name='ddd', version='1.0')"
            )
            .addModule(
                createModuleKey("ccc", "2.0"),
                "module(name='ccc', version='2.0');bazel_dep(name='ddd', version='2.0')"
            )
            .addModule(createModuleKey("ddd", "1.0"), "module(name='ddd', version='1.0')")
            .addModule(createModuleKey("ddd", "2.0"), "module(name='ddd', version='2.0')")

        val name: RepositoryName? = RepositoryName.create("bbb+")
        val skyKey: SkyKey? = RepositoryMappingValue.key(name)
        val result: EvaluationResult<RepositoryMappingValue?> = eval(skyKey)

        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasEntryThat(skyKey)
            .isEqualTo(
                value(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "bbb", RepositoryName.create("bbb+"),
                        "ddd", RepositoryName.create("ddd+1.0")
                    ),
                    name,
                    "bbb",
                    "1.0"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoNameMapping_multipleVersionOverride_lookup() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0',repo_name='bbb1')",
            "bazel_dep(name='bbb',version='2.0',repo_name='bbb2')",
            "multiple_version_override(module_name='bbb',versions=['1.0','2.0'])"
        )
        registry
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb', version='1.0');"
                        + "bazel_dep(name='ccc', version='1.0', repo_name='com_foo_bar_c')"
            )
            .addModule(createModuleKey("bbb", "2.0"), "module(name='bbb', version='2.0')")
            .addModule(createModuleKey("ccc", "1.0"), "module(name='ccc', version='1.0')")

        val name: RepositoryName? = RepositoryName.create("bbb+1.0")
        val skyKey: SkyKey? = RepositoryMappingValue.key(name)
        val result: EvaluationResult<RepositoryMappingValue?> = eval(skyKey)

        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasEntryThat(skyKey)
            .isEqualTo(
                value(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "bbb", RepositoryName.create("bbb+1.0"),
                        "com_foo_bar_c", RepositoryName.create("ccc+")
                    ),
                    name,
                    "bbb",
                    "1.0"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtinsRepo() {
        val builtinsKey: SkyKey? = RepositoryMappingValue.key(RepositoryName.create("_builtins"))
        val toolsKey: SkyKey? = RepositoryMappingValue.Key.create(ruleClassProvider.getToolsRepository())
        val builtinsResult: EvaluationResult<RepositoryMappingValue?> = eval(builtinsKey)
        assertThat(builtinsResult.hasError()).isFalse()
        val builtinsMapping: RepositoryMapping = builtinsResult.get(builtinsKey).repositoryMapping()
        val toolsResult: EvaluationResult<RepositoryMappingValue?> = eval(toolsKey)
        assertThat(toolsResult.hasError()).isFalse()
        val toolsMapping: RepositoryMapping = toolsResult.get(toolsKey).repositoryMapping()

        assertThat(builtinsMapping.entries()).containsAtLeastEntriesIn(toolsMapping.entries())
        assertThat(builtinsMapping.get("_builtins")).isEqualTo(RepositoryName.create("_builtins"))
        assertThat(builtinsMapping.get("")).isEqualTo(RepositoryName.MAIN)
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun value(
            repositoryMapping: com.google.common.collect.ImmutableMap<String?, RepositoryName?>,
            ownerRepo: RepositoryName?,
            associatedModuleName: String?,
            associatedModuleVersion: String?
        ): RepositoryMappingValue {
            val allMappings: com.google.common.collect.ImmutableMap.Builder<String?, RepositoryName?> =
                com.google.common.collect.ImmutableMap.builder<String?, RepositoryName?>()
            allMappings.putAll(repositoryMapping)
            if (AnalysisMock.get().isThisBazel()) {
                allMappings
                    .put("bazel_tools", RepositoryName.create("bazel_tools"))
                    .put("platforms", RepositoryName.create("platforms"))
            }
            return RepositoryMappingValue.create(
                RepositoryMapping.create(allMappings.buildOrThrow(), ownerRepo),
                associatedModuleName,
                Version.parse(associatedModuleVersion)
            )
        }
    }
}
