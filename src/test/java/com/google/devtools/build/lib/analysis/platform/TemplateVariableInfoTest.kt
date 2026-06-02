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
package com.google.devtools.build.lib.analysis.platform

import com.google.devtools.build.lib.analysis.ConfiguredTarget
import org.junit.Test

/** Tests of [TemplateVariableInfo].  */
@RunWith(JUnit4::class)
class TemplateVariableInfoTest : BuildViewTestCase() {
    @Test
    @Throws(Exception::class)
    fun proxyTemplateVariableInfo() {
        scratch.file(
            "a/rule.bzl",
            """
        def _impl(ctx):
            return [ctx.attr._cc_toolchain[platform_common.TemplateVariableInfo]]

        crule = rule(_impl, attrs = {"_cc_toolchain": attr.label(default = Label("//a:a"))})
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "a")

        crule(name = "r")

        genrule(
            name = "g",
            srcs = [],
            outs = ["go"],
            cmd = "VAR ${'$'}(CC)",
            toolchains = [":r"],
        )
        
        """.trimIndent()
        )

        val action: SpawnAction = getGeneratingAction(getConfiguredTarget("//a:g"), "a/go") as SpawnAction
        assertThat(action.getArguments().get(2)).containsMatch("VAR .*gcc")
    }

    @Test
    @Throws(Exception::class)
    fun templateVariableInfo() {
        scratch.file(
            "a/rule.bzl",
            """
        Info = provider()
        def _impl(ctx):
            return Info(
                variables = ctx.attr._cc_toolchain[platform_common.TemplateVariableInfo].variables,
            )

        crule = rule(_impl, attrs = {"_cc_toolchain": attr.label(default = Label("//a:a"))})
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "a")

        crule(name = "r")
        
        """.trimIndent()
        )
        val ct: ConfiguredTarget? = getConfiguredTarget("//a:r")

        val info: StarlarkInfo = getStarlarkProvider(ct, "Info")
        val makeVariables = info.getValue("variables") as MutableMap<String?, String?>?
        Truth.assertThat(makeVariables).containsKey("CC")
    }

    @Test
    @Throws(Exception::class)
    fun templateVariableInfoConstructor() {
        scratch.file(
            "a/rule.bzl",
            """
        Info = provider()
        def _consumer_impl(ctx):
            return Info(
                var = ctx.attr.supplier[platform_common.TemplateVariableInfo]
                    .variables[ctx.attr.var],
            )

        def _supplier_impl(ctx):
            return [platform_common.TemplateVariableInfo({ctx.attr.var: ctx.attr.value})]

        consumer = rule(
            _consumer_impl,
            attrs = {"var": attr.string(), "supplier": attr.label()},
        )
        supplier = rule(
            _supplier_impl,
            attrs = {"var": attr.string(), "value": attr.string()},
        )
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load(":rule.bzl", "consumer", "supplier")

        consumer(
            name = "consumer",
            supplier = ":supplier",
            var = "cherry",
        )

        supplier(
            name = "supplier",
            value = "ontop",
            var = "cherry",
        )
        
        """.trimIndent()
        )

        val consumer: ConfiguredTarget? = getConfiguredTarget("//a:consumer")
        val info: StarlarkInfo = getStarlarkProvider(consumer, "Info")
        val value: String? = info.getValue("var", String::class.java)
        Truth.assertThat(value).isEqualTo("ontop")

        val supplier: ConfiguredTarget? = getConfiguredTarget("//a:supplier")
        assertThat(supplier.get(TemplateVariableInfo.PROVIDER).getVariables())
            .containsExactly("cherry", "ontop")
    }
}
