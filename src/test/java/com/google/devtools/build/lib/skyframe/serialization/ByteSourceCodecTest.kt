// Copyright 2025 The Bazel Authors. All rights reserved.
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

@RunWith(JUnit4::class)
class ByteSourceCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun codec_roundtrips() {
        SerializationTester(
            com.google.common.io.ByteSource.empty(),
            com.google.common.io.ByteSource.wrap(byteArrayOf(0.toByte(), 1.toByte(), 2.toByte(), 3.toByte()))
        )
            .setVerificationFunction({ original: com.google.common.io.ByteSource, deserialized: com.google.common.io.ByteSource ->
                verifyEquals(
                    original,
                    deserialized
                )
            })
            .runTests()
    }

    companion object {
        @Throws(IOException::class)
        private fun verifyEquals(
            original: com.google.common.io.ByteSource,
            deserialized: com.google.common.io.ByteSource
        ) {
            Truth.assertThat(deserialized.read()).isEqualTo(original.read())
        }
    }
}
