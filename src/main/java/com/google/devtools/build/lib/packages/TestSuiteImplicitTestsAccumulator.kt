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

/**
 * Encapsulates logic for gathering tests for `test_suite`'s `$implicit_tests`
 * attribute.
 * 
 * 
 * Usage is tightly coupled with the package loading process. Expected flow is roughly
 * 
 * 
 *  1. [.getTestSuiteImplicitTestsRefForTags] for all relevant `test_suite`s
 *  1. Then, after all all targets have been added to the package...
 * 
 *  1. [.clearAccumulatedTests]
 *  1. [.processRule] for every rule in the package
 *  1. [.sortTests]
 * 
 *  1. Repeat the previous step(s) as necessary, eg due to skyframe restarts from missing deps
 * 
 */
internal class TestSuiteImplicitTestsAccumulator {
    private val testSuiteImplicitTests: MutableMap<com.google.common.collect.ImmutableSet<String?>?, ImplicitTestsAccumulator> =
        HashMap<com.google.common.collect.ImmutableSet<String?>?, ImplicitTestsAccumulator>()

    /**
     * Returns a reference to the list of tests matching tags (or all tests if empty), to be populated
     * by [.processRule].
     */
    fun getTestSuiteImplicitTestsRefForTags(tags: MutableList<String?>): MutableList<Label?> {
        val accumulatorForTags: ImplicitTestsAccumulator =
            testSuiteImplicitTests.computeIfAbsent(
                com.google.common.collect.ImmutableSet.copyOf<String?>(tags),
                java.util.function.Function { testTags: com.google.common.collect.ImmutableSet<kotlin.String?>? ->
                    ImplicitTestsAccumulator(testTags)
                })
        return Collections.unmodifiableList<Label?>(accumulatorForTags.tests)
    }

    /** Clears all accumulated tests.  */
    fun clearAccumulatedTests() {
        testSuiteImplicitTests.values()
            .forEach(java.util.function.Consumer { acc: ImplicitTestsAccumulator? -> acc.tests.clear() })
    }

    /**
     * Processes a rule from the package, adding it to the necessary `$implicit_test` values
     * returned by [.getTestSuiteImplicitTestsRefForTags].
     */
    fun processRule(rule: com.google.devtools.build.lib.packages.Rule?) {
        if (testSuiteImplicitTests.isEmpty()) {
            // No test suites requiring implicit test accumulation encountered.
            return
        }

        if (TargetUtils.isTestRule(rule) && !TargetUtils.hasManualTag(rule)) {
            val mapper: NonconfigurableAttributeMapper = NonconfigurableAttributeMapper.Companion.of(rule)
            val testSuiteTags: MutableSet<String?> =
                com.google.common.collect.ImmutableSet.builder<String?>()
                    .addAll(
                        mapper.get<MutableList<String?>?>(
                            "tags",
                            com.google.devtools.build.lib.packages.Types.STRING_LIST
                        )
                    )
                    .add(mapper.get<String?>("size", com.google.devtools.build.lib.packages.Type.Companion.STRING))
                    .build()
            for (acc in testSuiteImplicitTests.values()) {
                if (testMatchesFilters(testSuiteTags, acc.requiredTags, acc.excludedTags)) {
                    acc.tests.add(rule.getLabel())
                }
            }
        }
    }

    /**
     * Sorts all of accumulated test lists returned by [.getTestSuiteImplicitTestsRefForTags].
     */
    fun sortTests() {
        testSuiteImplicitTests.values()
            .forEach(java.util.function.Consumer { acc: ImplicitTestsAccumulator? -> Collections.sort<T?>(acc.tests) })
    }

    private class ImplicitTestsAccumulator(testTags: MutableSet<String?>) {
        private val requiredTags: MutableCollection<String?>?
        private val excludedTags: MutableCollection<String?>?
        private val tests: MutableList<Label?> = java.util.ArrayList<Label?>()

        init {
            val requiredAndExcludedTags: com.google.devtools.build.lib.util.Pair<MutableCollection<String?>?, MutableCollection<String?>?> =
                TestTargetUtils.sortTagsBySense(testTags)
            this.requiredTags = requiredAndExcludedTags.first
            this.excludedTags = requiredAndExcludedTags.second
        }
    }
}
