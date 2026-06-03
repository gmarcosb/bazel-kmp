// Copyright 2021 The Bazel Authors. All rights reserved.
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

import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [CpuResourceConverter].  */
@RunWith(JUnit4::class)
class CpuResourceConverterTest {
    @org.junit.Test
    fun testConstructor_correctMinAndMaxValues() {
        val cpuResourceConverter: CpuResourceConverter = CpuResourceConverter()

        assertThat(cpuResourceConverter.minValue).isEqualTo(0)
        assertThat(cpuResourceConverter.maxValue).isEqualTo(Int.Companion.MAX_VALUE)
    }
}
