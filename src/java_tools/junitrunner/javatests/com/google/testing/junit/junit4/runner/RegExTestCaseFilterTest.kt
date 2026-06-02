// Copyright 2010 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.junit4.runner

import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.regex.Pattern

/**
 * Tests for [RegExTestCaseFilter].
 */
@RunWith(JUnit4::class)
class RegExTestCaseFilterTest {
    @Test
    fun testIncludesSuites() {
        val filter: RegExTestCaseFilter = RegExTestCaseFilter.Companion.include("doNotMatch")
        Truth.assertThat(filter.shouldRun(createSuiteDescription("suite"))).isTrue()
    }

    private fun createSuiteDescription(name: String): Description {
        val suite = Description.createSuiteDescription(name)
        suite.addChild(Description.createTestDescription(Any::class.java, "child"))
        return suite
    }

    @Test
    fun testIncludesMatchingTestByFullNameQuotedRegex() {
        val filter: RegExTestCaseFilter = RegExTestCaseFilter.Companion.include(
            Pattern.quote("java.lang.Object#nameToMatch")
        )
        Truth.assertThat(filter.shouldRun(Description.createTestDescription(Any::class.java, "nameToMatch")))
            .isTrue()
    }

    @Test
    fun testIncludesMatchingTestByFullNameRegex() {
        val filter: RegExTestCaseFilter = RegExTestCaseFilter.Companion.include("^java.lang.Object#nameToMatch$")
        Truth.assertThat(filter.shouldRun(Description.createTestDescription(Any::class.java, "nameToMatch")))
            .isTrue()
    }

    @Test
    fun testIncludesMatchingTestBySimpleClassNameAndMethodName() {
        val filter: RegExTestCaseFilter = RegExTestCaseFilter.Companion.include("Object#nameToMatch")
        Truth.assertThat(filter.shouldRun(Description.createTestDescription(Any::class.java, "nameToMatch")))
            .isTrue()
    }

    @Test
    fun testIncludesMatchingTestWithNullMethodName() {
        val filter: RegExTestCaseFilter = RegExTestCaseFilter.Companion.include("java.lang.Object$")
        Truth.assertThat(filter.shouldRun(Description.createSuiteDescription(Any::class.java))).isTrue()
    }

    @Test
    fun testIncludesMatchingTestWithUnexpectedNameFormat() {
        val filter: RegExTestCaseFilter = RegExTestCaseFilter.Companion.include(
            Pattern.quote("java.lang.Object.hashCode()")
        )
        Truth.assertThat(filter.shouldRun(Description.createSuiteDescription("java.lang.Object.hashCode()")))
            .isTrue()
    }

    @Test
    fun testIncludesMatchingTestByTestMethodName() {
        val filter: RegExTestCaseFilter = RegExTestCaseFilter.Companion.include("nameToMatch")
        Truth.assertThat(filter.shouldRun(Description.createTestDescription(Any::class.java, "nameToMatch")))
            .isTrue()
    }

    @Test
    fun testExcludesNonmatchingTest() {
        val filter: RegExTestCaseFilter = RegExTestCaseFilter.Companion.include("doNotMatch")
        Truth.assertThat(filter.shouldRun(Description.createTestDescription(Any::class.java, "nameToMatch")))
            .isFalse()
    }

    @Test
    fun testFilterExcludeNonmatchingTest() {
        val filter: RegExTestCaseFilter = RegExTestCaseFilter.Companion.exclude("nameToMatch")
        Truth.assertThat(filter.shouldRun(Description.createTestDescription(Any::class.java, "nameToMatch")))
            .isFalse()
    }

    @Test
    fun testIncludesEmptyString() {
        val filter: RegExTestCaseFilter = RegExTestCaseFilter.Companion.include("")
        Truth.assertThat(filter.shouldRun(Description.createTestDescription(Any::class.java, "nameToMatch")))
            .isTrue()
    }

    @Test
    fun testIncludesMatchingByCaseRegex() {
        val filter: RegExTestCaseFilter = RegExTestCaseFilter.Companion.include("[Nn]ameToMatch")
        Truth.assertThat(filter.shouldRun(Description.createTestDescription(Any::class.java, "nameToMatch")))
            .isTrue()
    }

    @Test
    fun testIncludesMatchingByEscapedRegex() {
        val filter: RegExTestCaseFilter = RegExTestCaseFilter.Companion.include("java\\.lang\\.Object#nameToMatch")
        Truth.assertThat(filter.shouldRun(Description.createTestDescription(Any::class.java, "nameToMatch")))
            .isTrue()
    }

    @Test
    fun testIncludesMatchingByIncorrectCase() {
        val filter: RegExTestCaseFilter = RegExTestCaseFilter.Companion.include("NAMETOMATCH")
        Truth.assertThat(filter.shouldRun(Description.createTestDescription(Any::class.java, "nameToMatch")))
            .isFalse()
    }
}
