// Copyright 2022 The Bazel Authors. All rights reserved.
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

/** Tests for [Byte] serialization.  */
@RunWith(JUnit4::class)
class ByteArrayCodecTest {
    @org.junit.Test
    fun smoke() {
        SerializationTester(byteArray(), byteArray(12, 34), byteArray(-128, 0, 127))
    }

    companion object {
        private fun byteArray(vararg bytes: Int): ByteArray {
            val result = ByteArray(bytes.size)
            for (i in bytes.indices) {
                result[i] = bytes[i].toByte()
            }
            return result
        }
    }
}
