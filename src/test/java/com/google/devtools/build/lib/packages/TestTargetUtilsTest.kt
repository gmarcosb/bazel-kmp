// Copyright 2015 The Bazel Authors. All rights reserved.
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

@RunWith(JUnit4::class)
class TestTargetUtilsTest : PackageLoadingTestCase() {
    private var test1: Target? = null
    private var test2: Target? = null
    private var test1b: Target? = null
    private var suite: Target? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createTargets() {
        scratch.file(
            "tests/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "small_test_1",
            size = "small",
            srcs = ["small_test_1.sh"],
            data = [":xUnit"],
            tags = ["tag1"],
        )

        foo_test(
            name = "small_test_2",
            size = "small",
            srcs = ["small_test_2.sh"],
            data = ["//testing/shbase:googletest.sh"],
            tags = ["tag2"],
        )

        foo_test(
            name = "large_test_1",
            size = "large",
            srcs = ["large_test_1.sh"],
            data = [
                ":xUnit",
                "//testing/shbase:googletest.sh",
            ],
            tags = ["tag1"],
        )

        foo_binary(
            name = "notest",
            srcs = ["notest.sh"],
        )

        filegroup(name = "xUnit")

        test_suite(
            name = "smallTests",
            tags = ["small"],
        )
        
        """.trimIndent()
        )

        test1 = getTarget("//tests:small_test_1")
        test2 = getTarget("//tests:small_test_2")
        test1b = getTarget("//tests:large_test_1")
        suite = getTarget("//tests:smallTests")
    }

    @org.junit.Test
    fun testFilterBySize() {
        var sizeFilter: java.util.function.Predicate<Target?> =
            TestFilter.testSizeFilter(EnumSet.of(TestSize.SMALL, TestSize.LARGE))
        Truth.assertThat(sizeFilter.test(test1)).isTrue()
        Truth.assertThat(sizeFilter.test(test2)).isTrue()
        Truth.assertThat(sizeFilter.test(test1b)).isTrue()
        sizeFilter = TestFilter.testSizeFilter(EnumSet.< E > of < E ? > (TestSize.SMALL))
        Truth.assertThat(sizeFilter.test(test1)).isTrue()
        Truth.assertThat(sizeFilter.test(test2)).isTrue()
        Truth.assertThat(sizeFilter.test(test1b)).isFalse()
    }

    @org.junit.Test
    fun testFilterByLang() {
        val options: LoadingOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(LoadingOptions::class.java)
        options.setTestLangFilterList(com.google.common.collect.ImmutableList.of<E?>("positive", "-negative"))
        options.setTestSizeFilterSet(com.google.common.collect.ImmutableSet.of<E?>())
        options.setTestTimeoutFilterSet(com.google.common.collect.ImmutableSet.of<E?>())
        options.setTestTagFilterList(com.google.common.collect.ImmutableList.of<E?>())
        val filter: TestFilter = TestFilter.forOptions(options)
        val pkg: java.lang.Package? = Mockito.mock<java.lang.Package?>(java.lang.Package::class.java)
        val ruleClass: RuleClass = Mockito.mock<RuleClass>(RuleClass::class.java)
        Mockito.`when`<T?>(ruleClass.getDefaultImplicitOutputsFunction())
            .thenReturn(SafeImplicitOutputsFunction.NONE)
        Mockito.`when`<T?>(ruleClass.getAttributeProvider())
            .thenReturn(< T > mock < T ? > (AttributeProvider::class.java))
        val mockRule: Rule =
            Rule(
                pkg,
                Label.parseCanonicalUnchecked("//pkg:a"),
                ruleClass,
                net.starlark.java.syntax.Location.fromFile(""),  /* interiorCallStack= */
                null
            )
        Mockito.`when`<T?>(ruleClass.getName()).thenReturn("positive_test")
        assertThat(filter.apply(mockRule)).isTrue()
        Mockito.`when`<T?>(ruleClass.getName()).thenReturn("negative_test")
        assertThat(filter.apply(mockRule)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilterByTimeout() {
        scratch.file(
            "timeouts/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "long_timeout",
            size = "small",
            timeout = "long",
            srcs = ["a.sh"],
        )

        foo_test(
            name = "short_timeout",
            size = "small",
            srcs = ["b.sh"],
        )

        foo_test(
            name = "moderate_timeout",
            size = "small",
            timeout = "moderate",
            srcs = ["c.sh"],
        )
        
        """.trimIndent()
        )
        val longTest: Target = getTarget("//timeouts:long_timeout")
        val shortTest: Target = getTarget("//timeouts:short_timeout")
        val moderateTest: Target = getTarget("//timeouts:moderate_timeout")

        val timeoutFilter: java.util.function.Predicate<Target?> =
            TestFilter.testTimeoutFilter(EnumSet.of(TestTimeout.SHORT, TestTimeout.LONG))
        Truth.assertThat(timeoutFilter.test(longTest)).isTrue()
        Truth.assertThat(timeoutFilter.test(shortTest)).isTrue()
        Truth.assertThat(timeoutFilter.test(moderateTest)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSkyframeExpandTestSuites() {
        assertExpandedSuitesSkyframe(
            com.google.common.collect.Sets.newHashSet<Target?>(test1, test2),
            com.google.common.collect.ImmutableSet.of<Target?>(test1, test2)
        )
        assertExpandedSuitesSkyframe(
            com.google.common.collect.Sets.newHashSet<Target?>(test1, test2),
            com.google.common.collect.ImmutableSet.of<Target?>(suite)
        )
        assertExpandedSuitesSkyframe(
            com.google.common.collect.Sets.newHashSet<Target?>(test1, test2, test1b),
            com.google.common.collect.ImmutableSet.of<Target?>(test1, suite, test1b)
        )
        // The large test if returned as filtered from the test_suite rule, but should still be in the
        // result set as it's explicitly added.
        assertExpandedSuitesSkyframe(
            com.google.common.collect.Sets.newHashSet<Target?>(test1, test2, test1b),
            com.google.common.collect.ImmutableSet.of<Target?>(test1b, suite)
        )
    }

    @org.junit.Test
    fun testSortTagsBySenseSeparatesTagsNaively() {
        // Contrived, but intentional.
        val result: Pair<MutableCollection<String?>?, MutableCollection<String?>?> =
            TestTargetUtils.sortTagsBySense(
                com.google.common.collect.ImmutableList.of<E?>("tag1", "tag2", "tag3", "-tag1", "+tag2", "-tag3")
            )

        assertThat(result.first).containsExactly("tag1", "tag2", "tag3")
        assertThat(result.second).containsExactly("tag1", "tag3")
    }

    @Throws(java.lang.Exception::class)
    private fun assertExpandedSuitesSkyframe(expected: Iterable<Target?>, suites: MutableCollection<Target?>) {
        val expectedLabels: com.google.common.collect.ImmutableSet<Label?> =
            com.google.common.collect.ImmutableSet.< Label > copyOf < Label ? > (com.google.common.collect.Iterables.transform<Target?, Any?>(
                expected,
                Target::getLabel
            ))
        val suiteLabels: com.google.common.collect.ImmutableSet<Label?> =
            com.google.common.collect.ImmutableSet.< Label > copyOf < Label ? > (com.google.common.collect.Iterables.transform<Target?, Any?>(
                suites,
                Target::getLabel
            ))
        val key: SkyKey = TestsForTargetPatternValue.key(suiteLabels)
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(1)
                .setEventHandler(reporter)
                .build()
        val result: EvaluationResult<TestsForTargetPatternValue?> =
            getSkyframeExecutor().getEvaluator()
                .evaluate(com.google.common.collect.ImmutableList.of<E?>(key), evaluationContext)
        val actual: ResolvedTargets<Label?> = result.get(key).getLabels()
        assertThat(actual.hasError()).isFalse()
        assertThat(actual.getTargets()).containsExactlyElementsIn(expectedLabels)
    }
}
