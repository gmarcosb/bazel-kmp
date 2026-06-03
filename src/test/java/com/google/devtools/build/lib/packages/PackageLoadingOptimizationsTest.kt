// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/** Tests for ensuring that optimizations we have during package loading actually occur.  */
@RunWith(JUnit4::class)
class PackageLoadingOptimizationsTest : PackageLoadingTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun attributeListValuesAreDedupedIntraPackage() {
        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        L = ["//other:t" + str(i) for i in range(10)]

        [foo_library(
            name = "t" + str(i),
            deps = L,
        ) for i in range(10)]
        
        """.trimIndent()
        )

        val fooPkg: java.lang.Package =
            getPackageManager()
                .getPackage(NullEventHandler.INSTANCE, PackageIdentifier.createInMainRepo("foo"))

        val allListsBuilder: com.google.common.collect.ImmutableList.Builder<com.google.common.collect.ImmutableList<Label?>?> =
            com.google.common.collect.ImmutableList.builder<com.google.common.collect.ImmutableList<Label?>?>()
        for (ruleInstance in fooPkg.getTargets(Rule::class.java)) {
            assertThat(ruleInstance.getTargetKind()).isEqualTo("foo_library rule")
            val depsList: com.google.common.collect.ImmutableList<Label?> =
                ruleInstance.getAttr("deps") as com.google.common.collect.ImmutableList<Label?>
            allListsBuilder.add(depsList)
        }
        val allLists: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableList<Label?>> =
            allListsBuilder.build()
        Truth.assertThat(allLists).hasSize(10)
        val firstList: com.google.common.collect.ImmutableList<Label?> = allLists.get(0)
        for (i in 1..<allLists.size) {
            Truth.assertThat(allLists.get(i)).isSameInstanceAs(firstList)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuntimeListValueIsDedupedAcrossRuleClasses() {
        scratch.file(
            "foo/foo.bzl",
            """
        def _foo_test_impl(ctx):
            return
        foo_test = rule(implementation = _foo_test_impl, test = True)
        
        """.trimIndent()
        )
        scratch.file(
            "foo/bar.bzl",
            """
        def _bar_test_impl(ctx):
            return
        bar_test = rule(implementation = _bar_test_impl, test = True)
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load(":foo.bzl", "foo_test")
        load(":bar.bzl", "bar_test")

        [foo_test(name = str(i) + "_foo_test") for i in range(5)]
        [bar_test(name = str(i) + "_test") for i in range(5)]
        
        """.trimIndent()
        )

        val fooPkg: java.lang.Package =
            getPackageManager()
                .getPackage(NullEventHandler.INSTANCE, PackageIdentifier.createInMainRepo("foo"))

        val allListsBuilder: com.google.common.collect.ImmutableList.Builder<com.google.common.collect.ImmutableList<Label?>?> =
            com.google.common.collect.ImmutableList.builder<com.google.common.collect.ImmutableList<Label?>?>()
        for (ruleInstance in fooPkg.getTargets(Rule::class.java)) {
            assertThat(ruleInstance.getTargetKind()).endsWith("_test rule")
            val testRuntimeList: com.google.common.collect.ImmutableList<Label?> =
                ruleInstance.getAttr("\$test_runtime") as com.google.common.collect.ImmutableList<Label?>
            allListsBuilder.add(testRuntimeList)
        }
        val allLists: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableList<Label?>> =
            allListsBuilder.build()
        Truth.assertThat(allLists).hasSize(10)
        val firstList: com.google.common.collect.ImmutableList<Label?> = allLists.get(0)
        for (i in 1..<allLists.size) {
            Truth.assertThat(allLists.get(i)).isSameInstanceAs(firstList)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkProviderIdentifierIsDedupedAcrossRuleClasses() {
        scratch.file("foo/provider.bzl", "foo_provider = provider()")
        scratch.file(
            "foo/foo.bzl",
            """
        load(":provider.bzl", "foo_provider")

        def _foo_impl(ctx):
            return

        foo_rule = rule(implementation = _foo_impl, provides = [foo_provider])
        
        """.trimIndent()
        )
        scratch.file(
            "foo/foobar.bzl",
            """
        load(":provider.bzl", "foo_provider")

        def _foobar_impl(ctx):
            return

        foobar_rule = rule(implementation = _foobar_impl, provides = [foo_provider])
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load(":foo.bzl", "foo_rule")
        load(":foobar.bzl", "foobar_rule")

        foo_rule(name = "foo_rule_instance")

        foobar_rule(name = "foobar_rule_instance")
        
        """.trimIndent()
        )

        val fooPkg: java.lang.Package =
            getPackageManager()
                .getPackage(NullEventHandler.INSTANCE, PackageIdentifier.createInMainRepo("foo"))

        val allListsBuilder: com.google.common.collect.ImmutableList.Builder<com.google.common.collect.ImmutableList<StarlarkProviderIdentifier?>?> =
            com.google.common.collect.ImmutableList.builder<com.google.common.collect.ImmutableList<StarlarkProviderIdentifier?>?>()
        for (ruleInstance in fooPkg.getTargets(Rule::class.java)) {
            val ruleClass: RuleClass = ruleInstance.getRuleClassObject()
            allListsBuilder.add(ruleClass.getAdvertisedProviders().getStarlarkProviders().asList())
        }
        val allLists: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableList<StarlarkProviderIdentifier?>> =
            allListsBuilder.build()
        Truth.assertThat(allLists).hasSize(2)
        val firstList: com.google.common.collect.ImmutableList<StarlarkProviderIdentifier?> = allLists.get(0)
        for (i in 1..<allLists.size) {
            assertThat(allLists.get(i).get(0)).isSameInstanceAs(firstList.get(0))
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuiteImplicitTestsAttributeValueIsSortedByTargetName() {
        // When we have a BUILD file that instantiates some test targets
        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        # (in an order that is not target-name-order),
        foo_test(
            name = "bTest",
            srcs = ["test.sh"],
        )

        foo_test(
            name = "cTest",
            srcs = ["test.sh"],
        )

        foo_test(
            name = "aTest",
            srcs = ["test.sh"],
        )

        # And also a `test_suite` target, without setting the `test_suite.tests` attribute,
        test_suite(name = "suite")
        
        """.trimIndent()
        )

        // Then when we load the package,
        val fooPkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo("foo")
        val fooPkg: java.lang.Package = getPackageManager().getPackage(NullEventHandler.INSTANCE, fooPkgId)

        // And we get the Rule instance for the `test_suite` target,
        val testSuiteRuleInstance: Rule = fooPkg.getTarget("suite") as Rule
        assertThat(testSuiteRuleInstance.getTargetKind()).isEqualTo("test_suite rule")
        val implicitTestsAttributeValue: MutableCollection<Label>? =
            testSuiteRuleInstance.getAttr("\$implicit_tests") as MutableCollection<Label>?
        // The $implicit_tests attribute's value is ordered by target-name.
        Truth.assertThat(implicitTestsAttributeValue)
            .containsExactly(
                Label.create(fooPkgId, "aTest"),
                Label.create(fooPkgId, "bTest"),
                Label.create(fooPkgId, "cTest")
            )
            .inOrder()
    }
}
