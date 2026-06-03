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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.actions.LocalHostCapacity

/** Tests [LoadingPhaseThreadCountConverter].  */
@RunWith(JUnit4::class)
class LoadingPhaseThreadsConverterTest {
    private var loadingPhaseThreadCountConverter: LoadingPhaseThreadCountConverter? = null

    @Before
    @Throws(OptionsParsingException::class)
    fun setUp() {
        loadingPhaseThreadCountConverter = LoadingPhaseThreadCountConverter()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAutoLoadingPhaseThreadsUsesHardwareSettings() {
        LocalHostCapacity.setLocalHostCapacity(ResourceSet.createWithRamCpu(1, 7))
        assertThat(loadingPhaseThreadCountConverter.convert("auto")).isEqualTo(7)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAutoLoadingPhaseThreadsCappedForTests() {
        LocalHostCapacity.setLocalHostCapacity(ResourceSet.createWithRamCpu(1, 123))
        assertThat(loadingPhaseThreadCountConverter.convert("auto")).isEqualTo(20)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExplicitLoadingPhaseThreadsCappedForTests() {
        assertThat(loadingPhaseThreadCountConverter.convert("200")).isEqualTo(20)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExplicitLoadingPhaseThreadsMustBeAtLeast1() {
        val thrown: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { loadingPhaseThreadCountConverter.convert("0") })
        Truth.assertThat(thrown).hasMessageThat().contains("must be at least 1")
    }
}
