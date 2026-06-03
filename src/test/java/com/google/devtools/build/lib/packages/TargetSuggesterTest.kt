// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.common.truth.Truth
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.HashSet

@RunWith(JUnit4::class)
class TargetSuggesterTest {
    @org.junit.Test
    fun testRangeDoesntSuggestTarget() {
        val requestedTarget = "range"
        val packageTargets: MutableSet<String?> = HashSet<String?>()
        packageTargets.add("target")

        val suggestedTargets: com.google.common.collect.ImmutableList<String?>? =
            TargetSuggester.suggestedTargets(requestedTarget, packageTargets)
        Truth.assertThat(suggestedTargets).isEmpty()
    }

    @org.junit.Test
    fun testMisspelledTargetRetrievesProperSuggestion() {
        val misspelledTarget = "AnrdiodTest"

        val packageTargets: MutableSet<String?> = HashSet<String?>()
        packageTargets.add("AndroidTest")
        packageTargets.add("AndroidTest_deploy")
        packageTargets.add("AndroidTest_java")

        val suggestedTargets: com.google.common.collect.ImmutableList<String?>? =
            TargetSuggester.suggestedTargets(misspelledTarget, packageTargets)
        Truth.assertThat(suggestedTargets).containsExactly("AndroidTest")
    }

    @org.junit.Test
    fun testRetrieveMultipleTargets() {
        val misspelledTarget = "pixel_2_test"

        val packageTargets: MutableSet<String?> = HashSet<String?>()
        packageTargets.add("pixel_5_test")
        packageTargets.add("pixel_6_test")
        packageTargets.add("android_2_test")

        val suggestedTargets: com.google.common.collect.ImmutableList<String?>? =
            TargetSuggester.suggestedTargets(misspelledTarget, packageTargets)
        Truth.assertThat(suggestedTargets).containsExactly("pixel_5_test", "pixel_6_test")
    }

    @org.junit.Test
    fun testOnlyClosestTargetIsReturned() {
        val misspelledTarget = "Pixel_5_test"

        val packageTargets: MutableSet<String?> = HashSet<String?>()
        packageTargets.add("pixel_5_test")
        packageTargets.add("pixel_6_test")
        packageTargets.add("android_2_test")

        val suggestedTargets: com.google.common.collect.ImmutableList<String?>? =
            TargetSuggester.suggestedTargets(misspelledTarget, packageTargets)
        Truth.assertThat(suggestedTargets).containsExactly("pixel_5_test")
    }

    @org.junit.Test
    fun prettyPrintEmpty() {
        val empty: String? = TargetSuggester.prettyPrintTargets(com.google.common.collect.ImmutableList.of<E?>())
        Truth.assertThat(empty).isEmpty()
    }

    @org.junit.Test
    fun prettyPrintSingleTarget_returnsSingleTarget() {
        val targets: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("pixel_5_test")
        val targetString: String? = TargetSuggester.prettyPrintTargets(targets)
        Truth.assertThat(targetString).isEqualTo(" (did you mean pixel_5_test?)")
    }

    @org.junit.Test
    fun prettyPrintMultipleTargets_returnsMultipleTargets() {
        val targets: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("pixel_5_test", "pixel_6_test")
        val targetString: String? = TargetSuggester.prettyPrintTargets(targets)
        Truth.assertThat(targetString).isEqualTo(" (did you mean pixel_5_test, or pixel_6_test?)")
    }
}
