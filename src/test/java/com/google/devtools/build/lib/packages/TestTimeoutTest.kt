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

import com.google.devtools.build.lib.packages.TestTimeout.ETERNAL

/**
 * Tests the various methods of [TestTimeout]
 */
@RunWith(JUnit4::class)
class TestTimeoutTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasicConversion() {
        assertThat(TestTimeout.valueOf("SHORT")).isSameInstanceAs(SHORT)
        assertThat(TestTimeout.valueOf("MODERATE")).isSameInstanceAs(MODERATE)
        assertThat(TestTimeout.valueOf("LONG")).isSameInstanceAs(LONG)
        assertThat(TestTimeout.valueOf("ETERNAL")).isSameInstanceAs(ETERNAL)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuggestedTestSize() {
        assertThat(getSuggestedTestTimeout(0)).isEqualTo(SHORT)
        assertThat(getSuggestedTestTimeout(30)).isEqualTo(SHORT)
        assertThat(getSuggestedTestTimeout(50)).isEqualTo(MODERATE)
        assertThat(getSuggestedTestTimeout(250)).isEqualTo(LONG)
        assertThat(getSuggestedTestTimeout(700)).isEqualTo(ETERNAL)
        assertThat(getSuggestedTestTimeout(60 * 60 * 24 * 360)).isEqualTo(ETERNAL)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllTimesHaveSuggestions() {
        for (timeout in 0..<ETERNAL.timeoutSeconds) {
            val suggested: TestTimeout = getSuggestedTestTimeout(timeout)
            Truth.assertWithMessage("No suggested TestTimeout found for timeout %s", timeout)
                .that(suggested)
                .isNotNull()
            Truth.assertWithMessage("Suggested timeout %s is not in the fuzzy range for %s", suggested, timeout)
                .that(suggested.isInRangeFuzzy(timeout))
                .isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsInRangeFuzzy() {
        assertThat(SHORT.isInRangeFuzzy(0)).isTrue()
        assertThat(SHORT.isInRangeFuzzy(30)).isTrue()
        assertThat(SHORT.isInRangeFuzzy(55)).isFalse()
        assertThat(MODERATE.isInRangeFuzzy(10)).isFalse()
        assertThat(MODERATE.isInRangeFuzzy(40)).isTrue()
        assertThat(MODERATE.isInRangeFuzzy(290)).isFalse()
        assertThat(LONG.isInRangeFuzzy(30)).isFalse()
        assertThat(LONG.isInRangeFuzzy(200)).isTrue()
        assertThat(LONG.isInRangeFuzzy(890)).isFalse()
        assertThat(ETERNAL.isInRangeFuzzy(50)).isFalse()
        assertThat(ETERNAL.isInRangeFuzzy(500)).isTrue()
        assertThat(ETERNAL.isInRangeFuzzy(3500)).isTrue()
        assertThat(ETERNAL.isInRangeFuzzy(60 * 60 * 24 * 360)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllFuzzyRangesCovered() {
        for (timeout in 0..<ETERNAL.timeoutSeconds) {
            val truthValues: MutableList<Boolean?> = java.util.ArrayList<Boolean?>()
            for (testTimeout in java.util.Arrays.asList<Any>(SHORT, MODERATE, LONG, ETERNAL)) {
                truthValues.add(testTimeout.isInRangeFuzzy(timeout))
            }
            Truth.assertWithMessage("Timeout %s is not in any fuzzy range.", timeout)
                .that(truthValues)
                .contains(true)
        }
    }
}
