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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.packages.TargetUtils
import com.google.devtools.build.lib.packages.TestSize
import com.google.devtools.build.lib.packages.TestTimeout
import com.google.devtools.build.lib.pkgcache.LoadingOptions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import java.util.HashSet

/**
 * Predicate that implements test filtering using the command-line options in [ ]. Implements [.hashCode] and [.equals] so it can be used as a Skyframe
 * key.
 */
@AutoCodec
class TestFilter @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization internal constructor(
    testSizeFilterSet: com.google.common.collect.ImmutableSet<TestSize?>,
    testTimeoutFilterSet: com.google.common.collect.ImmutableSet<TestTimeout?>,
    testTagFilterList: com.google.common.collect.ImmutableList<String?>,
    testLangFilterList: com.google.common.collect.ImmutableList<String>
) : com.google.common.base.Predicate<com.google.devtools.build.lib.packages.Target?> {
    private val testSizeFilterSet: com.google.common.collect.ImmutableSet<TestSize?>
    private val testTimeoutFilterSet: com.google.common.collect.ImmutableSet<TestTimeout?>
    private val testTagFilterList: com.google.common.collect.ImmutableList<String?>
    private val testLangFilterList: com.google.common.collect.ImmutableList<String>
    private val impl: java.util.function.Predicate<com.google.devtools.build.lib.packages.Target?>

    init {
        this.testSizeFilterSet = testSizeFilterSet
        this.testTimeoutFilterSet = testTimeoutFilterSet
        this.testTagFilterList = testTagFilterList
        this.testLangFilterList = testLangFilterList
        var testFilter: java.util.function.Predicate<com.google.devtools.build.lib.packages.Target?> = ALWAYS_TRUE
        if (!testSizeFilterSet.isEmpty()) {
            testFilter = testFilter.and(testSizeFilter(testSizeFilterSet))
        }
        if (!testTimeoutFilterSet.isEmpty()) {
            testFilter = testFilter.and(testTimeoutFilter(testTimeoutFilterSet))
        }
        if (!testTagFilterList.isEmpty()) {
            testFilter = testFilter.and(TargetUtils.tagFilter(testTagFilterList))
        }
        if (!testLangFilterList.isEmpty()) {
            testFilter = testFilter.and(testLangFilter(testLangFilterList))
        }
        impl = testFilter
    }

    override fun apply(input: com.google.devtools.build.lib.packages.Target?): Boolean {
        return impl.test(input)
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(
            testSizeFilterSet, testTimeoutFilterSet, testTagFilterList,
            testLangFilterList
        )
    }

    override fun equals(o: Any?): Boolean {
        if (o === this) {
            return true
        }
        if (o !is TestFilter) {
            return false
        }
        return o.testSizeFilterSet == testSizeFilterSet
                && o.testTimeoutFilterSet == testTimeoutFilterSet
                && o.testTagFilterList == testTagFilterList
                && o.testLangFilterList == testLangFilterList
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("testSizeFilterSet", testSizeFilterSet)
            .add("testTimeoutFilterSet", testTimeoutFilterSet)
            .add("testTagFilterList", testTagFilterList)
            .add("testLangFilterList", testLangFilterList)
            .toString()
    }

    companion object {
        private val ALWAYS_TRUE: java.util.function.Predicate<com.google.devtools.build.lib.packages.Target?> =
            java.util.function.Predicate { t: com.google.devtools.build.lib.packages.Target? -> true }

        /** Convert the options into a test filter.  */
        fun forOptions(options: LoadingOptions): TestFilter {
            return TestFilter(
                com.google.common.collect.ImmutableSet.copyOf<TestSize?>(options.getTestSizeFilterSet()),
                com.google.common.collect.ImmutableSet.copyOf<TestTimeout?>(options.getTestTimeoutFilterSet()),
                com.google.common.collect.ImmutableList.copyOf<String?>(options.getTestTagFilterList()),
                com.google.common.collect.ImmutableList.copyOf<String?>(options.getTestLangFilterList())
            )
        }

        /**
         * Returns a predicate to be used for test size filtering, i.e., that only accepts tests of the
         * given size.
         */
        @com.google.common.annotations.VisibleForTesting
        fun testSizeFilter(allowedSizes: MutableSet<TestSize?>): java.util.function.Predicate<com.google.devtools.build.lib.packages.Target?> {
            return java.util.function.Predicate { target: com.google.devtools.build.lib.packages.Target? ->
                target is com.google.devtools.build.lib.packages.Rule && allowedSizes.contains(
                    TestSize.Companion.getTestSize(target as com.google.devtools.build.lib.packages.Rule)
                )
            }
        }

        /**
         * Returns a predicate to be used for test timeout filtering, i.e., that only accepts tests of the
         * given timeout.
         */
        @com.google.common.annotations.VisibleForTesting
        fun testTimeoutFilter(allowedTimeouts: MutableSet<TestTimeout?>): java.util.function.Predicate<com.google.devtools.build.lib.packages.Target?> {
            return java.util.function.Predicate { target: com.google.devtools.build.lib.packages.Target? ->
                target is com.google.devtools.build.lib.packages.Rule && allowedTimeouts.contains(
                    TestTimeout.Companion.getTestTimeout(target)
                )
            }
        }

        /**
         * Returns a predicate to be used for test language filtering, i.e., that only accepts tests of
         * the specified languages.
         */
        private fun testLangFilter(langFilterList: MutableList<String>): java.util.function.Predicate<com.google.devtools.build.lib.packages.Target?> {
            val requiredLangs: MutableSet<String?> = HashSet<String?>()
            val excludedLangs: MutableSet<String?> = HashSet<String?>()

            for (lang in langFilterList) {
                var lang = lang
                if (lang.startsWith("-")) {
                    lang = lang.substring(1)
                    excludedLangs.add(lang)
                } else {
                    requiredLangs.add(lang)
                }
            }

            return java.util.function.Predicate { rule: com.google.devtools.build.lib.packages.Target? ->
                val ruleLang: String = TargetUtils.getRuleLanguage(rule)
                (requiredLangs.isEmpty() || requiredLangs.contains(ruleLang))
                        && !excludedLangs.contains(ruleLang)
            }
        }
    }
}
