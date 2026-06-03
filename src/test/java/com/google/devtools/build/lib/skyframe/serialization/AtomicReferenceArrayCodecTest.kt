// Copyright 2023 The Bazel Authors. All rights reserved.
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

/** Tests for [AtomicReferenceArrayCodec].  */
@RunWith(JUnit4::class)
class AtomicReferenceArrayCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun arrays() {
        val instance1: AtomicReferenceArray<Int?> = AtomicReferenceArray<Int?>(3)
        instance1.setPlain(0, 0)
        instance1.setPlain(1, 1)
        instance1.setPlain(2, null)
        val instance2: AtomicReferenceArray<String?> = AtomicReferenceArray<String?>(3)
        instance2.setPlain(0, "foo")
        instance2.setPlain(1, null)
        instance2.setPlain(2, "bar")
        SerializationTester(AtomicReferenceArray<Any?>(0), instance1, instance2)
            .setVerificationFunction({ original: AtomicReferenceArray<*>, deserialized: AtomicReferenceArray<*> ->
                verifyDeserialized(
                    original,
                    deserialized
                )
            })
            .runTests()
    }

    companion object {
        private fun verifyDeserialized(
            original: AtomicReferenceArray<*>, deserialized: AtomicReferenceArray<*>
        ) {
            Truth.assertThat(deserialized.length()).isEqualTo(original.length())
            for (i in 0..<deserialized.length()) {
                Truth.assertThat(deserialized.getPlain(i)).isEqualTo(original.getPlain(i))
            }
        }
    }
}
