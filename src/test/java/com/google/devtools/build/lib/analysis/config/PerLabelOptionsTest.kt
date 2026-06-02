// Copyright 2009 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.util.RegexFilter

/** A test for [PerLabelOptions].  */
@RunWith(JUnit4::class)
class PerLabelOptionsTest {
    private var options: PerLabelOptions? = null

    @Throws(OptionsParsingException::class)
    private fun createOptions(string: String?): PerLabelOptions? {
        options = PerLabelOptionsConverter().convert(string)
        return options
    }

    @Throws(OptionsParsingException::class)
    private fun assertRegexParsing(filter: String?) {
        val regexFilter: RegexFilter = RegexFilterConverter().convert(filter)
        assertThat(options.getRegexFilter().toString()).isEqualTo(regexFilter.toString())
    }

    @Throws(OptionsParsingException::class)
    private fun assertOptions(pattern: String?, opts: String?, expectedOptions: MutableList<String?>?) {
        createOptions(pattern + "@" + opts)
        assertRegexParsing(pattern)
        assertThat(options.options).isNotNull()
        assertThat(options.options).isEqualTo(expectedOptions)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmpty() {
        createOptions("")
        assertRegexParsing("")
        assertThat(options.options).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParsing() {
        assertOptions("", "", Collections.emptyList<String?>())
        assertOptions("", ", ,\t,", Collections.emptyList<String?>())
        assertOptions("a/b,+^c,_test$", ", ,\t,", Collections.emptyList<String?>())
        assertOptions("a/b,+^c,_test$", "", Collections.emptyList<String?>())
        assertOptions("a/b,+^c,_test$", "-g,-O0", java.util.Arrays.asList<String?>("-g", "-O0"))
        assertOptions("a/b,+^c,_test$", "-g@,-O0", java.util.Arrays.asList<String?>("-g@", "-O0"))
        assertOptions("a/b,+^c,_test$", "-g\\,,-O0", java.util.Arrays.asList<String?>("-g,", "-O0"))
        assertOptions("a/b,+^c,_test$", "-g\\,,,,,-O0,,,@,", java.util.Arrays.asList<String?>("-g,", "-O0", "@"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEquals() {
        EqualsTester()
            .addEqualityGroup(
                createOptions("a/b,+^c,_test$@-g,-O0"),
                createOptions("a/b,+^c,_test$@-g,-O0")
            )
            .addEqualityGroup(createOptions("a/b,+^c,_test$@-O0"))
            .testEquals()
    }
}
