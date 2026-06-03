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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/** Tests for [Package].  */
@RunWith(JUnit4::class)
class PackageTest {
    private var fileSystem: FileSystem? = null

    @Before
    fun setUp() {
        this.fileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildPartialPopulatesImplicitTestSuiteIgnoresManualTests() {
        val pkgBuilder: Package.Builder = pkgBuilder("test_pkg")
        val testLabel: Label? = Label.parseCanonicalUnchecked("//test_pkg:my_test")
        addRule(pkgBuilder, testLabel, FAUX_TEST_CLASS)

        val manualTestLabel: Label? = Label.parseCanonicalUnchecked("//test_pkg:my_manual_test")
        val tag2Rule: Rule = addRule(pkgBuilder, manualTestLabel, FAUX_TEST_CLASS)
        tag2Rule.setAttributeValue(
            FAUX_TEST_CLASS.getAttributeProvider().getAttributeByName("tags"),
            com.google.common.collect.ImmutableList.of<E?>("manual"),  /* explicit= */
            true
        )

        val taggedTestLabel: Label? = Label.parseCanonicalUnchecked("//test_pkg:my_tagged_test")
        val taggedTestRule: Rule = addRule(pkgBuilder, taggedTestLabel, FAUX_TEST_CLASS)
        taggedTestRule.setAttributeValue(
            FAUX_TEST_CLASS.getAttributeProvider().getAttributeByName("tags"),
            com.google.common.collect.ImmutableList.of<E?>("tag1"),  /* explicit= */
            true
        )

        val taggedManualTestLabel: Label? = Label.parseCanonicalUnchecked("//test_pkg:my_tagged_manual_test")
        val taggedManualTestRule: Rule = addRule(pkgBuilder, taggedManualTestLabel, FAUX_TEST_CLASS)
        taggedManualTestRule.setAttributeValue(
            FAUX_TEST_CLASS.getAttributeProvider().getAttributeByName("tags"),
            com.google.common.collect.ImmutableList.of<E?>("manual", "tag1"),  /* explicit= */
            true
        )

        val allTests: MutableList<Label?>? =
            pkgBuilder.getTestSuiteImplicitTestsRef( /*tags=*/com.google.common.collect.ImmutableList.of<E?>())
        val tag1Tests: MutableList<Label?>? =
            pkgBuilder.getTestSuiteImplicitTestsRef(com.google.common.collect.ImmutableList.of<E?>("tag1"))

        pkgBuilder.buildPartial()

        Truth.assertThat(allTests).containsExactly(testLabel, taggedTestLabel)
        Truth.assertThat(tag1Tests).containsExactly(taggedTestLabel)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildPartialPopulatesImplicitTestSuiteValueOnlyForRequestedTags() {
        val pkgBuilder: Package.Builder = pkgBuilder("test_pkg")
        val tag1Label: Label? = Label.parseCanonicalUnchecked("//test_pkg:my_test_tag_1")
        val tag1Rule: Rule = addRule(pkgBuilder, tag1Label, FAUX_TEST_CLASS)
        tag1Rule.setAttributeValue(
            FAUX_TEST_CLASS.getAttributeProvider().getAttributeByName("tags"),
            com.google.common.collect.ImmutableList.of<E?>("tag1"),  /* explicit= */
            true
        )

        val tag2Label: Label? = Label.parseCanonicalUnchecked("//test_pkg:my_test_tag_2")
        val tag2Rule: Rule = addRule(pkgBuilder, tag2Label, FAUX_TEST_CLASS)
        tag2Rule.setAttributeValue(
            FAUX_TEST_CLASS.getAttributeProvider().getAttributeByName("tags"),
            com.google.common.collect.ImmutableList.of<E?>("tag2"),  /* explicit= */
            true
        )

        val result: MutableList<Label?>? =
            pkgBuilder.getTestSuiteImplicitTestsRef(com.google.common.collect.ImmutableList.of<E?>("tag1"))

        pkgBuilder.buildPartial()

        Truth.assertThat(result).containsExactly(tag1Label)

        // Neither "tag2" nor empty (all tags) were requested before buildPartial, so they weren't
        // accumulated.
        assertThat(pkgBuilder.getTestSuiteImplicitTestsRef(com.google.common.collect.ImmutableList.of<E?>("tag2"))).isEmpty()
        assertThat(pkgBuilder.getTestSuiteImplicitTestsRef( /*tags=*/com.google.common.collect.ImmutableList.of<E?>())).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildPartialPopulatesImplicitTestSuitesMatchingTags() {
        val pkgBuilder: Package.Builder = pkgBuilder("test_pkg")
        val matchingLabel: Label? = Label.parseCanonicalUnchecked("//test_pkg:matching")
        val matchingRule: Rule = addRule(pkgBuilder, matchingLabel, FAUX_TEST_CLASS)
        matchingRule.setAttributeValue(
            FAUX_TEST_CLASS.getAttributeProvider().getAttributeByName("tags"),
            com.google.common.collect.ImmutableList.of<E?>("tag1"),  /* explicit= */
            true
        )

        val excludedLabel: Label? = Label.parseCanonicalUnchecked("//test_pkg:excluded")
        val excludedRule: Rule = addRule(pkgBuilder, excludedLabel, FAUX_TEST_CLASS)
        excludedRule.setAttributeValue(
            FAUX_TEST_CLASS.getAttributeProvider().getAttributeByName("tags"),
            com.google.common.collect.ImmutableList.of<E?>("tag1", "tag2"),  /* explicit= */
            true
        )

        val result: MutableList<Label?>? =
            pkgBuilder.getTestSuiteImplicitTestsRef(com.google.common.collect.ImmutableList.of<E?>("tag1", "-tag2"))

        pkgBuilder.buildPartial()
        Truth.assertThat(result).containsExactly(matchingLabel)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildPartialPopulatesImplicitTestSuiteValueIdempotently() {
        val pkgBuilder: Package.Builder = pkgBuilder("test_pkg")
        val testLabel: Label? = Label.parseCanonicalUnchecked("//test_pkg:my_test")
        addRule(pkgBuilder, testLabel, FAUX_TEST_CLASS)

        // Ensure targets are accumulated.
        val result: MutableList<Label?>? =
            pkgBuilder.getTestSuiteImplicitTestsRef( /*tags=*/com.google.common.collect.ImmutableList.of<E?>())

        pkgBuilder.buildPartial()
        Truth.assertThat(result).containsExactly(testLabel)

        // Multiple calls are valid - make sure they're safe.
        pkgBuilder.buildPartial()
        Truth.assertThat(result).containsExactly(testLabel)
    }

    private fun pkgBuilder(name: String?): Package.Builder {
        return java.lang.Package.newPackageBuilder(
            PackageSettings.DEFAULTS,
            PackageIdentifier.createInMainRepo(name),  /* filename= */
            RootedPath.toRootedPath(
                Root.fromPath(fileSystem.getPath("/irrelevantRoot")),
                PathFragment.create(name + "/BUILD")
            ),
            "workspace",
            java.util.Optional.empty<T?>(),
            java.util.Optional.empty<T?>(),  /* noImplicitFileExport= */
            true,  /* simplifyUnconditionalSelectsInRuleAttrs= */
            StarlarkSemantics.DEFAULT.getBool(
                BuildLanguageOptions.INCOMPATIBLE_SIMPLIFY_UNCONDITIONAL_SELECTS_IN_RULE_ATTRS
            ),  /* repositoryMapping= */
            RepositoryMapping.EMPTY,  /* mainRepositoryMapping= */
            null,  /* cpuBoundSemaphore= */
            null,
            PackageOverheadEstimator.NOOP_ESTIMATOR,  /* generatorMap= */
            null,  /* configSettingVisibilityPolicy= */
            null,  /* globber= */
            null,  /* enableNameConflictChecking= */
            true,  /* trackFullMacroInformation= */
            false,
            java.lang.Package.Builder.PackageLimits.DEFAULTS
        )
    }

    companion object {
        private val FAUX_TEST_CLASS: RuleClass = Builder("faux_test", RuleClassType.TEST,  /* starlark= */false)
            .addAttribute(
                Attribute.attr("tags", Types.STRING_LIST).nonconfigurable("tags aren't").build()
            )
            .addAttribute(Attribute.attr("size", Type.STRING).nonconfigurable("size isn't").build())
            .addAttribute(Attribute.attr("timeout", Type.STRING).build())
            .addAttribute(Attribute.attr("flaky", Type.BOOLEAN).build())
            .addAttribute(Attribute.attr("shard_count", Type.INTEGER).build())
            .addAttribute(Attribute.attr("local", Type.BOOLEAN).build())
            .setConfiguredTargetFunction(< T > mock < T ? > (StarlarkCallable::class.java))
        .build()

        @Throws(java.lang.Exception::class)
        private fun addRule(pkgBuilder: Package.Builder, label: Label?, ruleClass: RuleClass?): Rule {
            val rule: Rule = pkgBuilder.createRule(
                label,
                ruleClass,  /* threadCallStack= */
                com.google.common.collect.ImmutableList.of<E?>()
            )
            rule.populateOutputFiles(StoredEventHandler(), pkgBuilder.getPackageIdentifier())
            pkgBuilder.addRule(rule)
            return rule
        }
    }
}
