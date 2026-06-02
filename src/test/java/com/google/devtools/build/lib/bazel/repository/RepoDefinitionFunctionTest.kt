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
package com.google.devtools.build.lib.bazel.repository

import com.google.devtools.build.lib.bazel.repository.RepoDefinitionValue.Found

/** Tests for [RepoDefinitionFunction].  */
@RunWith(JUnit4::class)
class RepoDefinitionFunctionTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoSpec_bazelModule() {
        scratch.overwriteFile(
            "MODULE.bazel", "module(name='aaa',version='0.1')", "bazel_dep(name='bbb',version='1.0')"
        )
        registry
            .addModule(
                BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')"
            )
            .addModule(BzlmodTestUtil.createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')")
        invalidatePackages(false)

        val repo: RepositoryName? = RepositoryName.create("ccc+")
        val result: EvaluationResult<RepoDefinitionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, RepoDefinitionValue.key(repo), false, reporter
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val repoDefinitionValue: RepoDefinitionValue = result.get(RepoDefinitionValue.key(repo))
        assertThat(repoDefinitionValue).isInstanceOf(Found::class.java)
        val repoDefinition: RepoDefinition = (repoDefinitionValue as Found).repoDefinition()

        assertThat(repoDefinition.repoRule().id().ruleName()).isEqualTo("local_repository")
        assertThat(repoDefinition.name()).isEqualTo("ccc+")
        assertThat(repoDefinition.attrValues().attributes().get("path"))
            .isEqualTo("/workspace/modules/ccc+2.0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoSpec_nonRegistryOverride() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0')",
            "local_path_override(module_name='ccc',path='/foo/bar/C')"
        )
        registry
            .addModule(
                BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')"
            )
            .addModule(BzlmodTestUtil.createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')")
        invalidatePackages(false)

        val repo: RepositoryName? = RepositoryName.create("ccc+")
        val result: EvaluationResult<RepoDefinitionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, RepoDefinitionValue.key(repo), false, reporter
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val repoDefinitionValue: RepoDefinitionValue = result.get(RepoDefinitionValue.key(repo))
        assertThat(repoDefinitionValue).isInstanceOf(Found::class.java)
        val repoDefinition: RepoDefinition = (repoDefinitionValue as Found).repoDefinition()

        assertThat(repoDefinition.repoRule().id().ruleName()).isEqualTo("local_repository")
        assertThat(repoDefinition.name()).isEqualTo("ccc+")
        assertThat(repoDefinition.attrValues().attributes().get("path")).isEqualTo("/foo/bar/C")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoSpec_singleVersionOverride() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0')",
            "single_version_override(",
            "  module_name='ccc',version='3.0')"
        )
        registry
            .addModule(
                BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')"
            )
            .addModule(BzlmodTestUtil.createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')")
            .addModule(BzlmodTestUtil.createModuleKey("ccc", "3.0"), "module(name='ccc', version='3.0')")
        invalidatePackages(false)

        val repo: RepositoryName? = RepositoryName.create("ccc+")
        val result: EvaluationResult<RepoDefinitionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, RepoDefinitionValue.key(repo), false, reporter
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val repoDefinitionValue: RepoDefinitionValue = result.get(RepoDefinitionValue.key(repo))
        assertThat(repoDefinitionValue).isInstanceOf(Found::class.java)
        val repoDefinition: RepoDefinition = (repoDefinitionValue as Found).repoDefinition()

        assertThat(repoDefinition.repoRule().id().ruleName()).isEqualTo("local_repository")
        assertThat(repoDefinition.name()).isEqualTo("ccc+")
        assertThat(repoDefinition.attrValues().attributes().get("path"))
            .isEqualTo("/workspace/modules/ccc+3.0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoSpec_multipleVersionOverride() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='aaa',version='0.1')",
            "bazel_dep(name='bbb',version='1.0')",
            "bazel_dep(name='ccc',version='2.0')",
            "multiple_version_override(module_name='ddd',versions=['1.0','2.0'])"
        )
        registry
            .addModule(
                BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                "module(name='bbb', version='1.0');bazel_dep(name='ddd',version='1.0')"
            )
            .addModule(
                BzlmodTestUtil.createModuleKey("ccc", "2.0"),
                "module(name='ccc', version='2.0');bazel_dep(name='ddd',version='2.0')"
            )
            .addModule(BzlmodTestUtil.createModuleKey("ddd", "1.0"), "module(name='ddd', version='1.0')")
            .addModule(BzlmodTestUtil.createModuleKey("ddd", "2.0"), "module(name='ddd', version='2.0')")
        invalidatePackages(false)

        val repo: RepositoryName? = RepositoryName.create("ddd+2.0")
        val result: EvaluationResult<RepoDefinitionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, RepoDefinitionValue.key(repo), false, reporter
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val repoDefinitionValue: RepoDefinitionValue = result.get(RepoDefinitionValue.key(repo))
        assertThat(repoDefinitionValue).isInstanceOf(Found::class.java)
        val repoDefinition: RepoDefinition = (repoDefinitionValue as Found).repoDefinition()

        assertThat(repoDefinition.repoRule().id().ruleName()).isEqualTo("local_repository")
        assertThat(repoDefinition.name()).isEqualTo("ddd+2.0")
        assertThat(repoDefinition.attrValues().attributes().get("path"))
            .isEqualTo("/workspace/modules/ddd+2.0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoSpec_notFound() {
        scratch.overwriteFile("MODULE.bazel", "module(name='aaa',version='0.1')")
        invalidatePackages(false)

        val repo: RepositoryName? = RepositoryName.create("ss")
        val result: EvaluationResult<RepoDefinitionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, RepoDefinitionValue.key(repo), false, reporter
            )
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val repoDefinitionValue: RepoDefinitionValue? = result.get(RepoDefinitionValue.key(repo))
        assertThat(repoDefinitionValue).isEqualTo(RepoDefinitionValue.NOT_FOUND)
    }
}
