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
package com.google.devtools.build.lib.query2.aquery

import com.google.devtools.build.lib.analysis.ConfiguredTargetValue

/** Tests for [ActionGraphQueryEnvironment].  */
@RunWith(JUnit4::class)
class ActionGraphQueryTest : PostAnalysisQueryTest<ConfiguredTargetValue>() {
    val defaultFunctions: HashMap<String?, QueryFunction?>
        get() {
            val defaultFunctions: com.google.common.collect.ImmutableList<QueryFunction> =
                com.google.common.collect.ImmutableList.Builder<QueryFunction?>()
                    .addAll(ActionGraphQueryEnvironment.FUNCTIONS)
                    .addAll(ActionGraphQueryEnvironment.AQUERY_FUNCTIONS)
                    .build()
            val functions: HashMap<String?, QueryFunction?> = HashMap<String?, QueryFunction?>()
            for (queryFunction in defaultFunctions) {
                functions.put(queryFunction.name, queryFunction)
            }
            return functions
        }

    override fun getConfiguration(configuredTargetValue: ConfiguredTargetValue): BuildConfigurationValue {
        return getHelper()
            .getSkyframeExecutor()
            .getConfiguration(
                getHelper().getReporter(),
                configuredTargetValue.getConfiguredTarget().getConfigurationKey()
            )
    }

    override fun createQueryHelper(): QueryHelper<ConfiguredTargetValue?> {
        return ActionGraphQueryHelper()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    override fun testMultipleTopLevelConfigurations_nullConfigs() {
        writeFile(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "my_java",
            srcs = ["foo.java"],
        )
        
        """.trimIndent()
        )

        val result: MutableSet<ConfiguredTargetValue> = eval("//test:my_java+//test:foo.java")

        Truth.assertThat(result).hasSize(2)

        val resultIterator: MutableIterator<ConfiguredTargetValue> = result.iterator()
        val first: ConfiguredTargetValue = resultIterator.next()
        if (first.getConfiguredTarget().getLabel().toString().equals("//test:foo.java")) {
            assertThat(getConfiguration(first)).isNull()
            assertThat(getConfiguration(resultIterator.next())).isNotNull()
        } else {
            assertThat(getConfiguration(first)).isNotNull()
            assertThat(getConfiguration(resultIterator.next())).isNull()
        }
    }

    // Regression test for b/235526333.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplicitToolchainBinding_containsToolchainTarget() {
        writeFile(
            "q/BUILD",
            """
        load(":q.bzl", "r", "tc")

        genrule(
            name = "gr",
            srcs = [],
            outs = ["gro"],
            cmd = "echo GRO > ${'$'}@",
        )

        tc(
            name = "tc",
            dep = ":gr",
        )

        toolchain_type(name = "type")

        toolchain(
            name = "tc.toolchain",
            toolchain = ":tc",
            toolchain_type = ":type",
        )

        r(name = "r")
        
        """.trimIndent()
        )
        writeFile(
            "q/q.bzl",
            """
        def _r_impl(ctx):
            gro = ctx.toolchains["//q:type"].gro
            o = ctx.actions.declare_file(ctx.label.name + ".output")
            ctx.actions.run_shell(
                inputs = depset([gro]),
                outputs = [o],
                command = "cp " + gro.path + " " + o.path,
            )
            return DefaultInfo(files = depset([o]))

        def _tc_impl(ctx):
            gro = ctx.files.dep[0]
            return [platform_common.ToolchainInfo(gro = gro)]

        tc = rule(
            implementation = _tc_impl,
            attrs = {"dep": attr.label()},
        )
        r = rule(
            implementation = _r_impl,
            toolchains = ["//q:type"],
        )
        
        """.trimIndent()
        )
        overwriteFile("MODULE.bazel", "register_toolchains('//q:tc.toolchain')")

        val result: MutableSet<ConfiguredTargetValue> = eval("deps('//q:r')")

        assertDoesNotContainEvent("Targets were missing from graph")
        Truth.assertThat(
            result.stream()
                .map<Any?> { x: ConfiguredTargetValue -> x.getConfiguredTarget().getOriginalLabel().getCanonicalForm() }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>()))
            .contains("//q:tc")
    }
}
