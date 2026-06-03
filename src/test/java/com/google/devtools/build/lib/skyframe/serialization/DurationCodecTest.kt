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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester

/** A test for [DurationCodec]  */
@RunWith(JUnit4::class)
class DurationCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDurationCodec() {
        SerializationTester(
            java.time.Duration.ofMinutes(123),
            java.time.Duration.ofSeconds(231),
            java.time.Duration.ofDays(321),
            java.time.Duration.ofSeconds(java.lang.Long.MAX_VALUE),
            java.time.Duration.ZERO,
            java.time.Duration.ofSeconds(java.lang.Long.MIN_VALUE),
            java.time.Duration.ofSeconds(java.lang.Long.MAX_VALUE, MAX_NANO_ADJUSTMENT.toLong()),
            java.time.Duration.ofSeconds(java.lang.Long.MAX_VALUE, MIN_NANO_ADJUSTMENT.toLong()),
            java.time.Duration.ofSeconds(java.lang.Long.MIN_VALUE, 123),
            java.time.Duration.ofSeconds(java.lang.Long.MAX_VALUE, 321)
        )
            .runTests()
    }

    companion object {
        private const val MAX_NANO_ADJUSTMENT = 999999999
        private const val MIN_NANO_ADJUSTMENT = 0
    }
}
