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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.analysis.ViewCreationFailedException

/** Tests use of the --target_environment flag.  */
@RunWith(TestParameterInjector::class)
class EnvironmentRestrictedBuildTest : BuildIntegrationTestCase() {
    @TestParameter
    var mergedSkyframeAnalysisExecution: Boolean = false

    @Before
    @Throws(java.lang.Exception::class)
    fun addNoBuildOption() {
        if (mergedSkyframeAnalysisExecution) {
            // TODO(b/223761810): Add --nobuild after Skymeld supports it.
            addOptions("--experimental_merged_skyframe_analysis_execution")
        } else {
            addOptions("--nobuild") // Target enforcement happens before the execution phase.
        }
    }

    @Throws(java.lang.Exception::class)
    private fun writeEnvironmentRules(vararg defaults: String?) {
        val defaultsBuilder: java.lang.StringBuilder = java.lang.StringBuilder()
        for (defaultEnv in defaults) {
            defaultsBuilder.append("'" + defaultEnv + "', ")
        }

        write(
            "buildenv/BUILD",
            "environment_group(",
            "    name = 'group',",
            "    environments = [':one', ':two'],",
            "    defaults = [" + defaultsBuilder + "])",
            "environment(name = 'one')",
            "environment(name = 'two')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetEnvironmentError() {
        writeEnvironmentRules()
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar', srcs = ['bar.sh'])"
        )
        addOptions("--target_environment=//buildenv:one")
        assertThat(
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:bar") })
        )
            .hasMessageThat()
            .contains(
                (""
                        + "//foo:bar declares compatibility with:\n"
                        + "  []\n"
                        + "but does not support:\n"
                        + "  //buildenv:one")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetEnvironmentSuccess() {
        writeEnvironmentRules()
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar', srcs = ['bar.sh'], compatible_with = ['//buildenv:one'])"
        )
        write("foo/bar.sh")
        addOptions("--target_environment=//buildenv:one")
        buildTarget("//foo:bar")
        assertThat(getResult().getSuccess()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleTargetEnvironments() {
        writeEnvironmentRules()
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar', srcs = ['bar.sh'], compatible_with = ['//buildenv:one'])"
        )

        addOptions("--target_environment=//buildenv:one", "--target_environment=//buildenv:two")
        assertThat(
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:bar") })
        )
            .hasMessageThat()
            .contains(
                (""
                        + "//foo:bar declares compatibility with:\n"
                        + "  [//buildenv:one]\n"
                        + "but does not support:\n"
                        + "  //buildenv:two")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetEnvironmentIsDefault() {
        writeEnvironmentRules(":one")
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar', srcs = ['bar.sh'])"
        )
        write("foo/bar.sh")
        addOptions("--target_environment=//buildenv:one")
        buildTarget("//foo:bar")
        assertThat(getResult().getSuccess()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyTargetEnvironment() {
        writeEnvironmentRules()
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar', srcs = ['bar.sh'])"
        )
        write("foo/bar.sh")
        buildTarget("//foo:bar")
        assertThat(getResult().getSuccess()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOnlySomeTargetsQualify() {
        writeEnvironmentRules()
        write(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "good_bar",
            srcs = ["bar.sh"],
            compatible_with = ["//buildenv:one"],
        )

        foo_library(
            name = "bad_bar",
            srcs = ["bar.sh"],
            compatible_with = ["//buildenv:two"],
        )
        
        """.trimIndent()
        )
        write("foo/bar.sh")
        addOptions("--target_environment=//buildenv:one")
        assertThat(
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:all") })
        )
            .hasMessageThat()
            .contains(
                (""
                        + "//foo:bad_bar declares compatibility with:\n"
                        + "  [//buildenv:two]\n"
                        + "but does not support:\n"
                        + "  //buildenv:one")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoConstraintEnforcement() {
        writeEnvironmentRules()
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar', srcs = ['bar.sh'])"
        )
        write("foo/bar.sh")
        addOptions("--target_environment=//buildenv:one", "--noenforce_constraints")
        buildTarget("//foo:bar")
        assertThat(getResult().getSuccess()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagUsesNonexistentTarget() {
        writeEnvironmentRules()
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar', srcs = ['bar.sh'])"
        )

        addOptions("--target_environment=//buildenv:nada")
        assertThat(
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:bar") })
        )
            .hasMessageThat()
            .contains("invalid target environment: no such target '//buildenv:nada'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagUsesWrongTargetType() {
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar', srcs = ['bar.sh'])"
        )

        addOptions("--target_environment=//foo:bar")
        assertThat(
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:bar") })
        )
            .hasMessageThat()
            .contains("//foo:bar is not a valid environment definition")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRefinedEnvironmentCheckValidTarget() {
        writeEnvironmentRules()
        write(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        config_setting(
            name = "config_one",
            values = {"define": "mode=one"},
        )

        config_setting(
            name = "config_two",
            values = {"define": "mode=two"},
        )

        foo_library(
            name = "lib_one",
            srcs = [],
            compatible_with = ["//buildenv:one"],
        )

        foo_library(
            name = "lib_two",
            srcs = [],
            compatible_with = ["//buildenv:two"],
        )

        foo_library(
            name = "toplevel",
            srcs = ["toplevel.sh"],
            compatible_with = [
                "//buildenv:one",
                "//buildenv:two",
            ],
            deps = select({
                ":config_one": [":lib_one"],
                ":config_two": [":lib_two"],
            }),
        )
        
        """.trimIndent()
        )
        // "--define mode=one" refines :toplevel to (matching) ["//buildenv:one"]:
        addOptions("--target_environment=//buildenv:one", "--define", "mode=one")
        write("foo/toplevel.sh")
        buildTarget("//foo:toplevel")
        assertThat(getResult().getSuccess()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRefinedEnvironmentCheckBadTarget() {
        writeEnvironmentRules()
        write(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        config_setting(
            name = "config_one",
            values = {"define": "mode=one"},
        )

        config_setting(
            name = "config_two",
            values = {"define": "mode=two"},
        )

        foo_library(
            name = "lib_one",
            srcs = [],
            compatible_with = ["//buildenv:one"],
        )

        foo_library(
            name = "lib_two",
            srcs = [],
            compatible_with = ["//buildenv:two"],
        )

        foo_library(
            name = "toplevel",
            srcs = ["toplevel.sh"],
            compatible_with = [
                "//buildenv:one",
                "//buildenv:two",
            ],
            deps = select({
                ":config_one": [":lib_one"],
                ":config_two": [":lib_two"],
            }),
        )
        
        """.trimIndent()
        )
        // "--define mode=two" refines :toplevel to (non-matching) ["//buildenv:two"]:
        addOptions("--target_environment=//buildenv:one", "--define", "mode=two")
        assertThat(
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:toplevel") })
        )
            .hasMessageThat()
            .contains(
                (""
                        + "//foo:toplevel declares compatibility with:\n"
                        + "  [//buildenv:one, //buildenv:two]\n"
                        + "but does not support:\n"
                        + "  environment: //buildenv:one\n"
                        + "    removed by: //foo:toplevel")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelOutputFile() {
        writeEnvironmentRules()
        write(
            "foo/rule.bzl",
            """
        def _impl(ctx):
            file = ctx.actions.declare_file("libbar.a")
            ctx.actions.write(file, "hello")
            return [DefaultInfo(files = depset([file]))]

        crule = rule(
            _impl,
            outputs = {
                "archive": "lib%{name}.a",
            },
        )
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load(":rule.bzl", "crule")

        crule(
            name = "bar",
            compatible_with = ["//buildenv:one"],
        )
        
        """.trimIndent()
        )
        addOptions("--target_environment=//buildenv:one")
        buildTarget("//foo:libbar.a")
        assertThat(getResult().getSuccess()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelAliasToCompatibleOutputFile() {
        writeEnvironmentRules()
        write(
            "foo/BUILD",
            """
        genrule(
            name = "goodgen",
            srcs = [],
            outs = ["goodgen.out"],
            cmd = "touch ${'$'}@",
            compatible_with = ["//buildenv:one"],
        )

        alias(
            name = "goodalias",
            actual = "goodgen.out",
        )
        
        """.trimIndent()
        )
        addOptions("--target_environment=//buildenv:one")
        buildTarget("//foo:goodalias")
        assertThat(getResult().getSuccess()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelAliasToBadOutputFile() {
        writeEnvironmentRules()
        write(
            "foo/BUILD",
            """
        genrule(
            name = "badgen",
            srcs = [],
            outs = ["badgen.out"],
            cmd = "",
        )

        alias(
            name = "badalias",
            actual = "badgen.out",
        )
        
        """.trimIndent()
        )
        addOptions("--target_environment=//buildenv:one")
        assertThat(
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:badalias") })
        )
            .hasMessageThat()
            .contains(
                (""
                        + "//foo:badgen.out declares compatibility with:\n"
                        + "  []\n"
                        + "but does not support:\n"
                        + "  //buildenv:one")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun doesNotCheckDefaultEnvironments() {
        write(
            "buildenv/a/BUILD",
            """
        environment_group(
            name = "a",
            defaults = [":a1"],
            environments = [
                ":a1",
                ":a2",
            ],
        )

        environment(name = "a1")

        environment(name = "a2")
        
        """.trimIndent()
        )
        write(
            "buildenv/b/BUILD",
            """
        environment_group(
            name = "b",
            defaults = [":b1"],
            environments = [
                ":b1",
                ":b2",
            ],
        )

        environment(name = "b1")

        environment(name = "b2")
        
        """.trimIndent()
        )

        write("foo/bar.sh", "echo Bar!")
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar', srcs = ['bar.sh'], restricted_to = ['//buildenv/b:b2'])"
        )

        addOptions("--target_environment=//buildenv/a:a1")
        buildTarget("//foo:bar")
        assertThat(getResult().getSuccess()).isTrue()
    }
}
