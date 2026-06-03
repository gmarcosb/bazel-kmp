// Copyright 2011 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.junit4

import com.google.common.truth.Truth
import com.google.testing.junit.runner.junit4.JUnit4Options
import com.google.testing.junit.runner.junit4.JUnit4Options.testExcludeFilter
import com.google.testing.junit.runner.junit4.JUnit4Options.testIncludeFilter
import com.google.testing.junit.runner.junit4.JUnit4Options.unparsedArgs
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.HashMap

/**
 * Tests for [JUnit4Options]
 */
@RunWith(JUnit4::class)
class JUnit4OptionsTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParse_noArgs() {
        val options: JUnit4Options =
            JUnit4Options.parse(EMPTY_ENV, com.google.common.collect.ImmutableList.of<String?>())
        Truth.assertThat(options.testIncludeFilter).isNull()
        Truth.assertThat<String?>(options.unparsedArgs).isEmpty()
    }

    @org.junit.Test
    fun testParse_onlyUnparsedArgs() {
        val options: JUnit4Options =
            JUnit4Options.parse(EMPTY_ENV, com.google.common.collect.ImmutableList.of<E?>("--bar", "baz"))
        Truth.assertThat(options.testIncludeFilter).isNull()
        Truth.assertThat<String?>(options.unparsedArgs).isEqualTo(arrayOf<String>("--bar", "baz"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParse_withTwoArgTestFilter() {
        val options: JUnit4Options = JUnit4Options.parse(
            EMPTY_ENV, com.google.common.collect.ImmutableList.of<E?>("--test_filter", "foo")
        )
        Truth.assertThat(options.testIncludeFilter).isEqualTo("foo")
        Truth.assertThat<String?>(options.unparsedArgs).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParse_withOneArgTestFilter() {
        val options: JUnit4Options =
            JUnit4Options.parse(EMPTY_ENV, com.google.common.collect.ImmutableList.of<E?>("--test_filter=foo"))
        Truth.assertThat(options.testIncludeFilter).isEqualTo("foo")
        Truth.assertThat<String?>(options.unparsedArgs).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParse_testFilterAndUnparsedArgs() {
        val options: JUnit4Options = JUnit4Options.parse(
            EMPTY_ENV, com.google.common.collect.ImmutableList.of<E?>("--bar", "--test_filter=foo", "--baz")
        )
        Truth.assertThat(options.testIncludeFilter).isEqualTo("foo")
        Truth.assertThat<String?>(options.unparsedArgs).isEqualTo(arrayOf<String>("--bar", "--baz"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParse_testLastTestFilterWins() {
        val options: JUnit4Options =
            JUnit4Options.parse(
                EMPTY_ENV,
                com.google.common.collect.ImmutableList.of<E?>("--test_filter=foo", "--test_filter=bar")
            )
        Truth.assertThat(options.testIncludeFilter).isEqualTo("bar")
        Truth.assertThat<String?>(options.unparsedArgs).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParse_testFilterMissingSecondArg() {
        org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
            java.lang.RuntimeException::class.java,
            org.junit.function.ThrowingRunnable {
                JUnit4Options.parse(
                    EMPTY_ENV,
                    com.google.common.collect.ImmutableList.of<E?>("--test_filter")
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParse_testFilterExcludeWithTwoArgTestFilter() {
        val options: JUnit4Options = JUnit4Options.parse(
            EMPTY_ENV, com.google.common.collect.ImmutableList.of<E?>("--test_exclude_filter", "foo")
        )
        Truth.assertThat(options.testExcludeFilter).isEqualTo("foo")
        Truth.assertThat<String?>(options.unparsedArgs).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParse_testFilterExcludewithOneArgTestFilter() {
        val options: JUnit4Options = JUnit4Options.parse(
            EMPTY_ENV, com.google.common.collect.ImmutableList.of<E?>("--test_exclude_filter=foo")
        )
        Truth.assertThat(options.testExcludeFilter).isEqualTo("foo")
        Truth.assertThat<String?>(options.unparsedArgs).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParse_unknownOptionName() {
        val options: JUnit4Options = JUnit4Options.parse(
            EMPTY_ENV, com.google.common.collect.ImmutableList.of<E?>("--unknown=foo")
        )
        Truth.assertThat<String?>(options.unparsedArgs).isEqualTo(arrayOf<String>("--unknown=foo"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParse_withTestFilterFromEnv() {
        val env: MutableMap<String?, String?> = HashMap<String?, String?>()
        env.put("TESTBRIDGE_TEST_ONLY", "foo")
        val options: JUnit4Options = JUnit4Options.parse(env, com.google.common.collect.ImmutableList.of<String?>())
        Truth.assertThat(options.testIncludeFilter).isEqualTo("foo")
        Truth.assertThat<String?>(options.unparsedArgs).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParse_testFilterArgOverridesEnv() {
        val env: MutableMap<String?, String?> = HashMap<String?, String?>()
        env.put("TESTBRIDGE_TEST_ONLY", "foo")
        val options: JUnit4Options =
            JUnit4Options.parse(env, com.google.common.collect.ImmutableList.of<E?>("--test_filter=bar"))
        Truth.assertThat(options.testIncludeFilter).isEqualTo("bar")
        Truth.assertThat<String?>(options.unparsedArgs).isEmpty()
    }

    companion object {
        private val EMPTY_ENV: MutableMap<String?, String?> = mutableMapOf<String?, String?>()
    }
}
