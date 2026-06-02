// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Artifact

/**
 * Tests for [AbstractConfiguredTarget]
 */
@RunWith(JUnit4::class)
class AbstractConfiguredTargetTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setupStarlarkJavaBinary() {
        setBuildLanguageOptions("--experimental_google_legacy_api")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesProviderIsNotImportant() {
        val x: ConfiguredTarget? =
            scratchConfiguredTarget(
                "java/a",
                "a",
                "load('@rules_java//java:defs.bzl', 'java_binary',"
                        + " 'java_library')",
                "java_binary(name='a', srcs=['A.java'], deps=[':b'])",
                "java_library(name='b', srcs=['B.java'])"
            )

        val artifacts: com.google.common.collect.ImmutableSet<Artifact?>? =
            TopLevelArtifactHelper.getAllArtifactsToBuild(
                x,
                TopLevelArtifactContext( /* runTestsExclusively= */
                    false,  /* expandFilesets= */
                    false,  /* outputGroups= */
                    com.google.common.collect.ImmutableSortedSet.< E > of < E ? > (
                            OutputGroupInfo.DEFAULT, OutputGroupInfo.HIDDEN_TOP_LEVEL
                )
            ))
        .getImportantArtifacts()
            .toSet()

        Truth.assertThat(baseArtifactNames(artifacts)).doesNotContain("libb.jar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunUnderWithExperimental() {
        scratch.file(
            "foo/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name = 'test', srcs = ['test.sh'], data = ['test.txt'])"
        )
        scratch.file(
            "experimental/bar/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'bar', srcs = ['test.sh'])"
        )
        useConfiguration("--run_under=//experimental/bar")
        getConfiguredTarget("//foo:test")
    }
}
