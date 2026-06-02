// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [VisibilityProvider].  */
@RunWith(JUnit4::class)
class VisibilityProviderTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        setBuildLanguageOptions( // Let's test the case where input files have proper visibilities by default.
            "--incompatible_no_implicit_file_export"
        )

        // NB: BuildViewTestCase sets the default_visibility to public unless we opt out. Since we're
        // only testing the visibility provider, and not doing actual visibility checking, we don't opt
        // out. This helps keep our test cases a little more readable.
    }

    /** Returns the visibility provider of the configured target with the given label.  */
    @Throws(java.lang.Exception::class)
    private fun getVisibility(label: String?): VisibilityProvider {
        val target: ConfiguredTarget? = getConfiguredTarget(label)
        com.google.common.base.Preconditions.checkNotNull<Any?>(
            target,
            "Configured target for %s was unexpectedly null",
            label
        )
        val provider: VisibilityProvider = target.getProvider(VisibilityProvider::class.java)
        com.google.common.base.Preconditions.checkNotNull<Any?>(
            provider,
            "Visibility provider for %s was unexpectedly null",
            label
        )
        return provider
    }

    /**
     * Creates definition of `//rules:simple_rule.bzl%simple_rule`, a rule that has a label
     * attribute `dep` and implicit output `<NAME>.bin`.
     */
    @Throws(java.lang.Exception::class)
    private fun defineSimpleRule() {
        scratch.file("rules/BUILD")
        scratch.file(
            "rules/simple_rule.bzl",
            """
        def _impl(ctx):
            ctx.actions.write(ctx.outputs.out, "")

        simple_rule = rule(
            implementation = _impl,
            attrs = {"dep": attr.label(mandatory=False, allow_files=True)},
            outputs = {"out": "%{name}.bin"},
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerValueForTargetsInBuildFile() {
        defineSimpleRule()
        scratch.file(
            "pkg/BUILD",
            """
        load("//rules:simple_rule.bzl", "simple_rule")

        package(default_visibility=["//default:__pkg__"])

        simple_rule(
            name = "rule_target",
            dep = "implicit_input.cc",
            visibility = ["//client:__pkg__"],
        )

        package_group(
            name = "pkg_group",
        )

        exports_files(["explicit_input.txt"])
        exports_files(["explicit_input_with_vis.txt"], visibility=["//client:__pkg__"])
        
        """.trimIndent()
        )

        val ruleTargetVisibility: VisibilityProvider = getVisibility("//pkg:rule_target")
        // The declaration location //pkg is not appended to visibility, but the visibility check will
        // treat it as if it were there. Same below.
        Truth.assertThat(getVisibilityStrings(ruleTargetVisibility)).containsExactly("//client")
        assertThat(ruleTargetVisibility.isCreatedInSymbolicMacro).isFalse()

        val pkgGroupVisibility: VisibilityProvider = getVisibility("//pkg:pkg_group")
        Truth.assertThat(getVisibilityStrings(pkgGroupVisibility)).containsExactly("public")
        assertThat(pkgGroupVisibility.isCreatedInSymbolicMacro).isFalse()

        val explicitInputVisibility: VisibilityProvider = getVisibility("//pkg:explicit_input.txt")
        Truth.assertThat(getVisibilityStrings(explicitInputVisibility)).containsExactly("public")
        assertThat(explicitInputVisibility.isCreatedInSymbolicMacro).isFalse()

        val explicitInputWithVisVisibility: VisibilityProvider =
            getVisibility("//pkg:explicit_input_with_vis.txt")
        Truth.assertThat(getVisibilityStrings(explicitInputWithVisVisibility)).containsExactly("//client")
        assertThat(explicitInputWithVisVisibility.isCreatedInSymbolicMacro).isFalse()

        val implicitInputVisibility: VisibilityProvider = getVisibility("//pkg:implicit_input.cc")
        // Private (not public, not default_visibility), due to --incompatible_no_implicit_file_export.
        Truth.assertThat(getVisibilityStrings(implicitInputVisibility)).isEmpty()
        assertThat(implicitInputVisibility.isCreatedInSymbolicMacro).isFalse()

        val outputVisibility: VisibilityProvider = getVisibility("//pkg:rule_target.bin")
        Truth.assertThat(getVisibilityStrings(outputVisibility)).containsExactly("//client")
        assertThat(outputVisibility.isCreatedInSymbolicMacro).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerValueForTargetsInMacro() {
        defineSimpleRule()
        scratch.file("lib/BUILD")
        scratch.file(
            "lib/macro.bzl",
            """
        load("//rules:simple_rule.bzl", "simple_rule")

        def _impl(name, visibility):
            simple_rule(
                name = name + "_rule_target",
                # No implicit input file, because they can only be created outside a symbolic
                # macro, and anyway that would be redundant with the above test case.
                visibility = ["//client:__pkg__"],
            )
            native.package_group(
                name = name + "_pkg_group",
            )
            native.exports_files([name + "_explicit_input.txt"])
            native.exports_files(
                [name + "_explicit_input_with_vis.txt"],
                visibility=["//client:__pkg__"])

        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//lib:macro.bzl", "my_macro")

        package(default_visibility=["//default:__pkg__"])

        my_macro(name = "foo")
        
        """.trimIndent()
        )

        val ruleTargetVisibility: VisibilityProvider = getVisibility("//pkg:foo_rule_target")
        // The declaration location //lib comes from the visibility attribute (after it has been
        // processed in RuleFactory). Same below.
        Truth.assertThat(getVisibilityStrings(ruleTargetVisibility)).containsExactly("//client", "//lib")
        assertThat(ruleTargetVisibility.isCreatedInSymbolicMacro).isTrue()

        val pkgGroupVisibility: VisibilityProvider = getVisibility("//pkg:foo_pkg_group")
        Truth.assertThat(getVisibilityStrings(pkgGroupVisibility)).containsExactly("public")
        // This is actually incorrect, but we don't care because package groups are always public.
        // (Storing the correct value would require a bool, so we don't bother.)
        assertThat(pkgGroupVisibility.isCreatedInSymbolicMacro).isFalse()

        val explicitInputVisibility: VisibilityProvider = getVisibility("//pkg:foo_explicit_input.txt")
        Truth.assertThat(getVisibilityStrings(explicitInputVisibility)).containsExactly("public")
        assertThat(explicitInputVisibility.isCreatedInSymbolicMacro).isTrue()

        val explicitInputWithVisVisibility: VisibilityProvider =
            getVisibility("//pkg:foo_explicit_input_with_vis.txt")
        Truth.assertThat(getVisibilityStrings(explicitInputWithVisVisibility))
            .containsExactly("//client", "//lib")
        assertThat(explicitInputWithVisVisibility.isCreatedInSymbolicMacro).isTrue()

        val outputVisibility: VisibilityProvider = getVisibility("//pkg:foo_rule_target.bin")
        Truth.assertThat(getVisibilityStrings(outputVisibility)).containsExactly("//client", "//lib")
        assertThat(outputVisibility.isCreatedInSymbolicMacro).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerValueForAlias() {
        // Check the provider of an alias target declared in a BUILD file referencing an actual target
        // in a macro, and vice versa.
        defineSimpleRule()
        scratch.file( // Put the .bzl in //pkg so we don't have to declare //pkg:__pkg__ in visibility.
            "pkg/macro.bzl",
            """
        load("//rules:simple_rule.bzl", "simple_rule")

        def _impl(name, visibility):
            simple_rule(
                name = name + "_actual",
                visibility = ["//actual_client:__pkg__"])
            native.alias(
                name = name + "_alias",
                actual = "//pkg:actual",
                visibility = ["//alias_client:__pkg__"],
            )

        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//rules:simple_rule.bzl", "simple_rule")
        load("//pkg:macro.bzl", "my_macro")

        my_macro(name = "foo")

        simple_rule(
            name = "actual",
            visibility = ["//actual_client:__pkg__"],
        )

        alias(
            name = "alias",
            actual = ":foo_actual",
            visibility = ["//alias_client:__pkg__"],
        )
        
        """.trimIndent()
        )

        val buildFileAliasVisibility: VisibilityProvider = getVisibility("//pkg:alias")
        Truth.assertThat(getVisibilityStrings(buildFileAliasVisibility)).containsExactly("//alias_client")
        assertThat(buildFileAliasVisibility.isCreatedInSymbolicMacro).isFalse()

        val macroAliasVisibility: VisibilityProvider = getVisibility("//pkg:foo_alias")
        Truth.assertThat(getVisibilityStrings(macroAliasVisibility))
            .containsExactly("//alias_client", "//pkg")
        assertThat(macroAliasVisibility.isCreatedInSymbolicMacro).isTrue()
    }

    companion object {
        /**
         * Returns a list of packages identified by the given visibility provider, as reported by [ ][PackageGroupContents.packageStrings] (formatted with the double slash).
         */
        private fun getVisibilityStrings(provider: VisibilityProvider): MutableList<String?> {
            return provider.visibility.toList().stream()
                .flatMap({ pgc -> pgc.packageStrings( /* includeDoubleSlash= */true).stream() })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        }
    }
}
