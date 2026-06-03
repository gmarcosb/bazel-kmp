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
package com.google.devtools.build.lib.unsafe

import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Tests for [StringUnsafe].  */
@RunWith(JUnit4::class)
class StringUnsafeTest {
    @Test
    fun testGetCoder() {
        assertThat(StringUnsafe.getCoder("")).isEqualTo(StringUnsafe.LATIN1)
        assertThat(StringUnsafe.getCoder("hello")).isEqualTo(StringUnsafe.LATIN1)
        assertThat(StringUnsafe.getCoder("lambda λ")).isEqualTo(StringUnsafe.UTF16)
    }

    @Test
    fun testGetBytes() {
        Truth.assertThat<ByteBuffer?>(ByteBuffer.wrap(StringUnsafe.getByteArray("hello")))
            .isEqualTo(StandardCharsets.ISO_8859_1.encode("hello"))

        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) {
            Truth.assertThat<ByteBuffer?>(ByteBuffer.wrap(StringUnsafe.getByteArray("lambda λ")))
                .isEqualTo(StandardCharsets.UTF_16BE.encode("lambda λ"))
        } else {
            Truth.assertThat<ByteBuffer?>(ByteBuffer.wrap(StringUnsafe.getByteArray("lambda λ")))
                .isEqualTo(StandardCharsets.UTF_16LE.encode("lambda λ"))
        }
    }

    @Test
    @Throws(Exception::class)
    fun testNewInstance() {
        val s = "hello"
        assertThat(StringUnsafe.newInstance(StringUnsafe.getByteArray(s), StringUnsafe.getCoder(s)))
            .isEqualTo("hello")
    }

    @Test
    fun testIsAscii() {
        assertThat(StringUnsafe.isAscii("")).isTrue()
        assertThat(StringUnsafe.isAscii("hello")).isTrue()
        assertThat(StringUnsafe.isAscii("hällo")).isFalse()
        assertThat(StringUnsafe.isAscii("hållo")).isFalse()
        assertThat(StringUnsafe.isAscii("h👋llo")).isFalse()
    }
}
