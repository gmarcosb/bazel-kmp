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
package com.google.devtools.build.lib.analysis.allowlisting

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

/** Tests for the Allowlist methods.  */
@RunWith(JUnit4::class)
class AllowlistTest : BuildViewTestCase() {
    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        return builder.addRuleDefinition(AllowlistDummyRule.DEFINITION).build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectPackage() {
        scratch.file(
            "allowlist/BUILD",
            """
        package_group(
            name = "allowlist",
            packages = [
                "//direct",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file("direct/BUILD", "rule_with_allowlist(name='x')")
        getConfiguredTarget("//direct:x")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursivePackage() {
        scratch.file(
            "allowlist/BUILD",
            """
        package_group(
            name = "allowlist",
            packages = [
                "//recursive/...",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file("recursive/x/BUILD", "rule_with_allowlist(name='y')")
        getConfiguredTarget("//recursive/x:y")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsentPackage() {
        scratch.file(
            "allowlist/BUILD",
            """
        package_group(
            name = "allowlist",
            packages = [
                "//somethingelse/...",
            ],
        )
        
        """.trimIndent()
        )
        checkError("absent", "x", "Dummy is not available.", "rule_with_allowlist(name='x')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCatchAll() {
        scratch.file(
            "allowlist/BUILD",
            """
        package_group(
            name = "allowlist",
            packages = [
                "//...",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file("notingroup/BUILD", "rule_with_allowlist(name='x')")
        getConfiguredTarget("//notingroup:x")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyPackageGroup() {
        scratch.file("allowlist/BUILD", "package_group(name='allowlist', packages=[])")
        checkError("x", "x", "Dummy is not available.", "rule_with_allowlist(name='x')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonExistentPackageGroup() {
        checkError(
            "x",
            "x",
            ("every rule of type rule_with_allowlist implicitly depends upon the target"
                    + " '//allowlist:allowlist', but this target could not be found because of: no such"
                    + " package 'allowlist': BUILD file not found"),
            "rule_with_allowlist(name='x')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncludes() {
        scratch.file(
            "suballowlist/BUILD",
            """
        package_group(
            name = "allowlist",
            packages = [
                "//x",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "allowlist/BUILD",
            """
        package_group(
            name = "allowlist",
            includes = [
                "//suballowlist:allowlist",
            ],
            packages = [
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "x/BUILD",
            """
        rule_with_allowlist(
            name = "x",
        )
        
        """.trimIndent()
        )
        getConfiguredTarget("//x:x")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetInAllowlist_targetAsStringParameter() {
        scratch.file(
            "allowlist/BUILD",
            """
        package_group(
            name = "allowlist",
            packages = [
                "//direct",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rule.bzl",
            "def _impl(ctx):",
            "  target = '//direct:rule_from_allowlist'",
            "  target_in_allowlist ="
                    + " ctx.attr._allowlist_test[PackageSpecificationInfo].contains(target)",
            "  if not target_in_allowlist:",
            "    fail('Target should be in the allowlist')",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    '_allowlist_test': attr.label(",
            "      default = '//allowlist:allowlist',",
            "      cfg = 'exec',",
            "      providers = [PackageSpecificationInfo]",
            "    ),",
            "  },",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rule.bzl", "custom_rule")

        custom_rule(name = "allowlist_rule")
        
        """.trimIndent()
        )

        getConfiguredTarget("//test:allowlist_rule")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetInAllowlist_targetAsLabelParameter() {
        scratch.file(
            "allowlist/BUILD",
            """
        package_group(
            name = "allowlist",
            packages = [
                "//test",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rule.bzl",
            "def _impl(ctx):",
            "  target = ctx.label",
            "  target_in_allowlist ="
                    + " ctx.attr._allowlist_test[PackageSpecificationInfo].contains(target)",
            "  if not target_in_allowlist:",
            "    fail('Target should be in the allowlist')",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    '_allowlist_test': attr.label(",
            "      default = '//allowlist:allowlist',",
            "      cfg = 'exec',",
            "      providers = [PackageSpecificationInfo]",
            "    ),",
            "  },",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rule.bzl", "custom_rule")

        custom_rule(name = "allowlist_rule")
        
        """.trimIndent()
        )

        getConfiguredTarget("//test:allowlist_rule")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetNotInAllowlist() {
        scratch.file(
            "allowlist/BUILD",
            """
        package_group(
            name = "allowlist",
            packages = [
                "//direct",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rule.bzl",
            "def _impl(ctx):",
            "  target = '//non_direct:rule_not_from_allowlist'",
            "  target_in_allowlist ="
                    + " ctx.attr._allowlist_test[PackageSpecificationInfo].contains(target)",
            "  if target_in_allowlist:",
            "    fail('Target should not be in the allowlist')",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    '_allowlist_test': attr.label(",
            "      default = '//allowlist:allowlist',",
            "      cfg = 'exec',",
            "      providers = [PackageSpecificationInfo]",
            "    ),",
            "  },",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rule.bzl", "custom_rule")

        custom_rule(name = "allowlist_rule")
        
        """.trimIndent()
        )

        getConfiguredTarget("//test:allowlist_rule")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetNotInAllowlist_negativePath() {
        scratch.file(
            "allowlist/BUILD",
            """
        package_group(
            name = "allowlist",
            packages = [
                "-//direct",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rule.bzl",
            "def _impl(ctx):",
            "  target = '//direct:rule_from_allowlist'",
            "  target_in_allowlist ="
                    + " ctx.attr._allowlist_test[PackageSpecificationInfo].contains(target)",
            "  if target_in_allowlist:",
            "    fail('Target should not be in the allowlist (negative path)')",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    '_allowlist_test': attr.label(",
            "      default = '//allowlist:allowlist',",
            "      cfg = 'exec',",
            "      providers = [PackageSpecificationInfo]",
            "    ),",
            "  },",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rule.bzl", "custom_rule")

        custom_rule(name = "allowlist_rule")
        
        """.trimIndent()
        )

        getConfiguredTarget("//test:allowlist_rule")

        assertNoEvents()
    }
}
