// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.OutputGroupInfo.DEFAULT

/** Tests for [OutputGroupInfo].  */
@RunWith(TestParameterInjector::class)
class OutputGroupProviderTest {
    @org.junit.Test
    fun testDetermineOutputGroupsOverridesDefaults(@TestParameter shouldRunTests: Boolean) {
        val outputGroups: MutableSet<String?>? =
            determineOutputGroups(
                com.google.common.collect.ImmutableSet.of<E?>("x", "y", "z"),
                mutableListOf<T?>("a", "b", "c"),
                ValidationMode.OFF,  /*shouldRunTests=*/
                shouldRunTests
            )
        Truth.assertThat(outputGroups)
            .containsExactlyElementsIn(
                outputGroupsWithDefaultIfRunningTests(shouldRunTests, "a", "b", "c")
            )
    }

    @org.junit.Test
    fun testDetermineOutputGroupsAddsToDefaults(@TestParameter shouldRunTests: Boolean) {
        val outputGroups: MutableSet<String?>? =
            determineOutputGroups(
                com.google.common.collect.ImmutableSet.of<E?>("x", "y", "z"),
                < T > asList < T ? > ("+a"),
        ValidationMode.OFF,  /*shouldRunTests=*/
        shouldRunTests)
        Truth.assertThat(outputGroups)
            .containsExactlyElementsIn(
                outputGroupsWithDefaultIfRunningTests(shouldRunTests, "x", "y", "z", "a")
            )
    }

    @org.junit.Test
    fun testDetermineOutputGroupsRemovesFromDefaults(@TestParameter shouldRunTests: Boolean) {
        val outputGroups: MutableSet<String?>? =
            determineOutputGroups(
                com.google.common.collect.ImmutableSet.of<E?>("x", "y", "z"),
                < T > asList < T ? > ("-y"),
        ValidationMode.OFF,  /*shouldRunTests=*/
        shouldRunTests)
        Truth.assertThat(outputGroups)
            .containsExactlyElementsIn(outputGroupsWithDefaultIfRunningTests(shouldRunTests, "x", "z"))
    }

    @org.junit.Test
    fun testDetermineOutputGroupsMixedOverrideAdditionOverrides(
        @TestParameter shouldRunTests: Boolean
    ) {
        val outputGroups: MutableSet<String?>? =
            determineOutputGroups(
                com.google.common.collect.ImmutableSet.of<E?>("x", "y", "z"),
                mutableListOf<T?>("a", "+b"),
                ValidationMode.OFF,  /*shouldRunTests=*/
                shouldRunTests
            )
        // The plain "a" causes the default output groups to be overridden.
        Truth.assertThat(outputGroups)
            .containsExactlyElementsIn(outputGroupsWithDefaultIfRunningTests(shouldRunTests, "a", "b"))
    }

    @org.junit.Test
    fun testDetermineOutputGroupsIgnoresUnknownGroup(@TestParameter shouldRunTests: Boolean) {
        val outputGroups: MutableSet<String?>? =
            determineOutputGroups(
                com.google.common.collect.ImmutableSet.of<E?>("x", "y", "z"),
                < T > asList < T ? > ("-foo"),
        ValidationMode.OFF,  /*shouldRunTests=*/
        shouldRunTests)
        // "foo" doesn't exist, but that shouldn't be a problem.
        Truth.assertThat(outputGroups)
            .containsExactlyElementsIn(
                outputGroupsWithDefaultIfRunningTests(shouldRunTests, "x", "y", "z")
            )
    }

    @org.junit.Test
    fun testDetermineOutputGroupsRemovesPreviouslyAddedGroup(
        @TestParameter shouldRunTests: Boolean
    ) {
        var outputGroups: MutableSet<String?>?
        outputGroups =
            determineOutputGroups(
                com.google.common.collect.ImmutableSet.of<E?>("x", "y", "z"),
                mutableListOf<T?>("+a", "-a"),
                ValidationMode.OFF,  /*shouldRunTests=*/
                shouldRunTests
            )
        Truth.assertThat(outputGroups)
            .containsExactlyElementsIn(
                outputGroupsWithDefaultIfRunningTests(shouldRunTests, "x", "y", "z")
            )

        // Order matters here.
        outputGroups =
            determineOutputGroups(
                com.google.common.collect.ImmutableSet.of<E?>("x", "y", "z"),
                mutableListOf<T?>("-a", "+a"),
                ValidationMode.OFF,  /*shouldRunTests=*/
                shouldRunTests
            )
        Truth.assertThat(outputGroups)
            .containsExactlyElementsIn(
                outputGroupsWithDefaultIfRunningTests(shouldRunTests, "x", "y", "z", "a")
            )
    }

    @org.junit.Test
    fun testDetermineOutputGroupsContainsValidationGroup(
        @TestParameter shouldRunTests: Boolean
    ) {
        val outputGroups: MutableSet<String?>? =
            determineOutputGroups(
                com.google.common.collect.ImmutableSet.of<E?>("x", "y", "z"),
                mutableListOf<T?>(),
                ValidationMode.OUTPUT_GROUP,  /*shouldRunTests=*/
                shouldRunTests
            )
        Truth.assertThat(outputGroups)
            .containsExactlyElementsIn(
                outputGroupsWithDefaultIfRunningTests(
                    shouldRunTests, "x", "y", "z", OutputGroupInfo.VALIDATION
                )
            )
    }

    @org.junit.Test
    fun testDetermineOutputGroupsContainsValidationGroupAfterOverride(
        @TestParameter shouldRunTests: Boolean
    ) {
        val outputGroups: MutableSet<String?>? =
            determineOutputGroups(
                com.google.common.collect.ImmutableSet.of<E?>("x", "y", "z"),
                < T > asList < T ? > ("foo"),
        ValidationMode.OUTPUT_GROUP,  /*shouldRunTests=*/
        shouldRunTests)
        Truth.assertThat(outputGroups)
            .containsExactlyElementsIn(
                outputGroupsWithDefaultIfRunningTests(
                    shouldRunTests, "foo", OutputGroupInfo.VALIDATION
                )
            )
    }

    @org.junit.Test
    fun testDetermineOutputGroupsContainsValidationGroupAfterAdd(
        @TestParameter shouldRunTests: Boolean
    ) {
        val outputGroups: MutableSet<String?>? =
            determineOutputGroups(
                com.google.common.collect.ImmutableSet.of<E?>("x", "y", "z"),
                < T > asList < T ? > ("+a"),
        ValidationMode.OUTPUT_GROUP,  /*shouldRunTests=*/
        shouldRunTests)
        Truth.assertThat(outputGroups)
            .containsExactlyElementsIn(
                outputGroupsWithDefaultIfRunningTests(
                    shouldRunTests, "x", "y", "z", "a", OutputGroupInfo.VALIDATION
                )
            )
    }

    @org.junit.Test
    fun testDetermineOutputGroupsContainsValidationGroupAfterRemove(
        @TestParameter shouldRunTests: Boolean
    ) {
        val outputGroups: MutableSet<String?>? =
            determineOutputGroups(
                com.google.common.collect.ImmutableSet.of<E?>("x", "y", "z"),
                < T > asList < T ? > ("-x"),
        ValidationMode.OUTPUT_GROUP,  /*shouldRunTests=*/
        shouldRunTests)
        Truth.assertThat(outputGroups)
            .containsExactlyElementsIn(
                outputGroupsWithDefaultIfRunningTests(
                    shouldRunTests, "y", "z", OutputGroupInfo.VALIDATION
                )
            )
    }

    @org.junit.Test
    fun testDetermineOutputGroupsContainsValidationGroupDespiteRemove(
        @TestParameter shouldRunTests: Boolean
    ) {
        val outputGroups: MutableSet<String?>? =
            determineOutputGroups(
                com.google.common.collect.ImmutableSet.of<E?>("x", "y", "z"),
                < T > asList < T ? > ("-" + OutputGroupInfo.VALIDATION),
        ValidationMode.OUTPUT_GROUP,  /*shouldRunTests=*/
        shouldRunTests)
        Truth.assertThat(outputGroups)
            .containsExactlyElementsIn(
                outputGroupsWithDefaultIfRunningTests(
                    shouldRunTests, "x", "y", "z", OutputGroupInfo.VALIDATION
                )
            )
    }

    @org.junit.Test
    fun testDetermineOutputGroupsContainsTopLevelValidationGroup(
        @TestParameter shouldRunTests: Boolean
    ) {
        val outputGroups: MutableSet<String?>? =
            determineOutputGroups(
                com.google.common.collect.ImmutableSet.of<E?>("x", "y", "z"),
                < T > asList < T ? > ("-" + OutputGroupInfo.VALIDATION_TOP_LEVEL),
        ValidationMode.ASPECT,  /*shouldRunTests=*/
        shouldRunTests)
        Truth.assertThat(outputGroups)
            .containsExactlyElementsIn(
                outputGroupsWithDefaultIfRunningTests(
                    shouldRunTests, "x", "y", "z", OutputGroupInfo.VALIDATION_TOP_LEVEL
                )
            )
    }

    companion object {
        private fun outputGroupsWithDefaultIfRunningTests(
            shouldRunTests: Boolean, vararg groups: String?
        ): Iterable<String?> {
            val result: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            result.add(*groups)
            if (shouldRunTests) {
                result.add(DEFAULT)
            }
            return result.build()
        }
    }
}
