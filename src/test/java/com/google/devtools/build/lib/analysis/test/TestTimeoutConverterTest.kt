// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.packages.TestTimeout

/**
 * A test for [TestTimeoutConverter].
 */
@RunWith(JUnit4::class)
class TestTimeoutConverterTest {
    private var timeouts: MutableMap<TestTimeout?, java.time.Duration?>? = null

    @Throws(OptionsParsingException::class)
    protected fun setTimeouts(option: String?) {
        timeouts = TestTimeoutConverter().convert(option)
    }

    protected fun assertTimeout(timeout: TestTimeout?, expected: Int) {
        Truth.assertThat(timeouts).containsEntry(timeout, java.time.Duration.ofSeconds(expected.toLong()))
    }

    protected fun assertDefaultTimeout(timeout: TestTimeout) {
        assertTimeout(timeout, timeout.timeoutSeconds)
    }

    protected fun assertFailure(option: String?) {
        org.junit.Assert.assertThrows<T?>(
            "Incorrectly parsed '" + option + "'",
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable { setTimeouts(option) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultTimeout() {
        setTimeouts("-1")
        assertDefaultTimeout(TestTimeout.SHORT)
        assertDefaultTimeout(TestTimeout.MODERATE)
        assertDefaultTimeout(TestTimeout.LONG)
        assertDefaultTimeout(TestTimeout.ETERNAL)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUniversalTimeout() {
        setTimeouts("1")
        assertTimeout(TestTimeout.SHORT, 1)
        assertTimeout(TestTimeout.MODERATE, 1)
        assertTimeout(TestTimeout.LONG, 1)
        assertTimeout(TestTimeout.ETERNAL, 1)

        setTimeouts("2,") // comma at the end is ignored.
        assertTimeout(TestTimeout.SHORT, 2)
        assertTimeout(TestTimeout.MODERATE, 2)
        assertTimeout(TestTimeout.LONG, 2)
        assertTimeout(TestTimeout.ETERNAL, 2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSeparateTimeouts() {
        setTimeouts("1,0,-1,3")
        assertTimeout(TestTimeout.SHORT, 1)
        assertDefaultTimeout(TestTimeout.MODERATE)
        assertDefaultTimeout(TestTimeout.LONG)
        assertTimeout(TestTimeout.ETERNAL, 3)

        setTimeouts("0,-1,3,20")
        assertDefaultTimeout(TestTimeout.SHORT)
        assertDefaultTimeout(TestTimeout.MODERATE)
        assertTimeout(TestTimeout.LONG, 3)
        assertTimeout(TestTimeout.ETERNAL, 20)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncorrectStrings() {
        assertFailure("")
        assertFailure("1a")
        assertFailure("1 2 3 4")
        assertFailure("1:2:3:4")
        assertFailure("1,2,3")
        assertFailure("1,2,3,4,")
        assertFailure("1,2,,3,4")
        assertFailure("1,2,3 4")
        assertFailure("1,2,3,4,5")
    }
}
