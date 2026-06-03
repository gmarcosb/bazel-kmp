// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.outputfilter

import com.google.common.truth.Truth
import com.google.devtools.build.lib.events.OutputFilter
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.regex.Pattern

/** Tests for the `--output_filter` option.  */
@RunWith(JUnit4::class)
class OutputFilterTest {
    @Test
    fun testOutputEverythingAlwaysTrue() {
        Truth.assertThat(OutputFilter.OUTPUT_EVERYTHING.showOutput("some tag")).isTrue()
        Truth.assertThat(OutputFilter.OUTPUT_EVERYTHING.showOutput("literally anything")).isTrue()
        Truth.assertThat(OutputFilter.OUTPUT_EVERYTHING.showOutput("even empty")).isTrue()
        Truth.assertThat(OutputFilter.OUTPUT_EVERYTHING.showOutput("")).isTrue()
    }

    @Test
    fun testOutputNothingAlwaysTrue() {
        Truth.assertThat(OutputFilter.OUTPUT_NOTHING.showOutput("some tag")).isFalse()
        Truth.assertThat(OutputFilter.OUTPUT_NOTHING.showOutput("literally anything")).isFalse()
        Truth.assertThat(OutputFilter.OUTPUT_NOTHING.showOutput("even empty")).isFalse()
        Truth.assertThat(OutputFilter.OUTPUT_NOTHING.showOutput("")).isFalse()
    }

    @Test
    fun testRegexpFilterShowOutputMatchTagReturnsTrue() {
        val underTest =
            OutputFilter.RegexOutputFilter.forPattern(Pattern.compile("^//some/target"))
        Truth.assertThat(underTest.showOutput("//some/target")).isTrue()
    }

    @Test
    fun testRegexpFilterShowOutputNonMatchTagReturnsFalse() {
        val underTest =
            OutputFilter.RegexOutputFilter.forPattern(Pattern.compile("^//some/target"))
        Truth.assertThat(underTest.showOutput("//not/some/target")).isFalse()
    }
}
