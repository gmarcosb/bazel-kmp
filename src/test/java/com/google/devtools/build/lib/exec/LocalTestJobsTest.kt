// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.exec.ExecutionOptions.LocalTestJobsConverter

/** Tests [com.google.devtools.build.lib.exec.ExecutionOptions.LocalTestJobsConverter].  */
@RunWith(JUnit4::class)
class LocalTestJobsTest {
    private var localTestJobsConverter: LocalTestJobsConverter? = null

    @Before
    @Throws(OptionsParsingException::class)
    fun setUp() {
        localTestJobsConverter = LocalTestJobsConverter()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testLocalTestJobsMustBePositive() {
        val thrown: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { localTestJobsConverter.convert("-1") })
        Truth.assertThat(thrown).hasMessageThat().contains("must be at least 0")
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testLocalTestJobsAutoIsZero() {
        assertThat(localTestJobsConverter.convert("auto")).isEqualTo(0)
    }
}
