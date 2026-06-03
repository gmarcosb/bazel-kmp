// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester

/** A test for [RegexFilter].  */
@RunWith(JUnit4::class)
class RegexFilterTest {
    protected var filter: RegexFilter? = null

    @Throws(OptionsParsingException::class)
    protected fun createFilter(filterString: String?): RegexFilter? {
        filter = RegexFilterConverter().convert(filterString)
        return filter
    }

    protected fun assertIncluded(value: String?) {
        assertThat(filter.isIncluded(value)).isTrue()
    }

    protected fun assertExcluded(value: String?) {
        assertThat(filter.isIncluded(value)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyFilter() {
        createFilter("")
        assertIncluded("a/b/c")
        assertIncluded("d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inclusions() {
        createFilter("a/b,+^c,_test$")
        assertThat(filter.toString()).isEqualTo("(?:(?>^c)|(?>_test$)|(?>a/b))")
        assertIncluded("a/b")
        assertIncluded("a/b/c")
        assertIncluded("c")
        assertIncluded("c/d")
        assertIncluded("e/a/b")
        assertIncluded("f/1/2/3/_test")
        assertExcluded("a")
        assertExcluded("a/c")
        assertExcluded("d")
        assertExcluded("e/f/g")
        assertExcluded("f/_test2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exclusions() {
        createFilter("-a/b,-^c,-_test$")
        assertThat(filter.toString()).isEqualTo("-(?:(?>^c)|(?>_test$)|(?>a/b))")
        assertExcluded("a/b")
        assertExcluded("a/b/c")
        assertExcluded("c")
        assertExcluded("c/d")
        assertExcluded("f/a/b/d")
        assertExcluded("f/a_test")
        assertIncluded("a")
        assertIncluded("a/c")
        assertIncluded("d")
        assertIncluded("e/f/g")
        assertIncluded("f/a_test_case")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inclusionsAndExclusions() {
        createFilter("a,-^c,,-,+,d,+a/b/c,-a/b,a/b/d")
        assertThat(filter.toString())
            .isEqualTo("(?:(?>a)|(?>a/b/c)|(?>a/b/d)|(?>d)),-(?:(?>^c)|(?>a/b))")
        assertIncluded("a")
        assertIncluded("a/c")
        assertExcluded("a/b")
        assertExcluded("a/b/c") // Exclusions take precedence over inclusions. Order is not important.
        assertExcluded("a/b/d") // Exclusions take precedence over inclusions. Order is not important.
        assertExcluded("a/c/a/b/d")
        assertExcluded("c")
        assertExcluded("c/d")
        assertIncluded("d/e")
        assertExcluded("e")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun commas() {
        createFilter("a\\,b,c\\,d")
        assertThat(filter.toString()).isEqualTo("(?:(?>a\\,b)|(?>c\\,d))")
        assertIncluded("a,b")
        assertIncluded("c,d")
        assertExcluded("a")
        assertExcluded("b,c")
        assertExcluded("d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidExpression() {
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { createFilter("*a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "Failed to build valid regular expression: Dangling meta character '*' "
                        + "near index"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun equals() {
        EqualsTester()
            .addEqualityGroup(createFilter("a,b,c"), createFilter("a,b,c"))
            .addEqualityGroup(createFilter("a,b,c,d"))
            .addEqualityGroup(createFilter("a,b,-c"), createFilter("a,b,-c"))
            .addEqualityGroup(createFilter("a,b,-c,-d"))
            .addEqualityGroup(createFilter("-a,-b,-c"), createFilter("-a,-b,-c"))
            .addEqualityGroup(createFilter("-a,-b,-c,-d"))
            .addEqualityGroup(createFilter(""), createFilter(""))
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun codec() {
        SerializationTester(
            com.google.common.collect.ImmutableList.of<String?>(
                "",
                "a/b,+^c,_test$",
                "-a/b,-^c,-_test$",
                "a,-^c,,-,+,d,+a/b/c,-a/b,a/b/d",
                "a\\,b,c\\,d"
            )
                .stream()
                .map<RegexFilter?> { filterString: String? -> safeCreateFilter(filterString) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>()))
            .runTests()
    }

    @org.junit.Test
    fun initialDoubleDash_error() {
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { createFilter("--compilation_mode") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "Failed to build filter: value looks like another flag (--compilation_mode). Either"
                        + " escape the value with \"\\-\\-\", or pass an explicit value to the flag."
            )
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun initialDoubleDash_escaped() {
        createFilter("\\-\\-compilation_mode")
        assertIncluded("--compilation_mode")
    }

    companion object {
        private fun safeCreateFilter(filterString: String?): RegexFilter {
            try {
                return RegexFilterConverter().convert(filterString)
            } catch (e: OptionsParsingException) {
                throw java.lang.RuntimeException(e)
            }
        }
    }
}
