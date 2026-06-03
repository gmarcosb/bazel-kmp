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

/** Tests for [EnumMapCodec].  */
@RunWith(JUnit4::class)
class EnumMapCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        SerializationTester(
            java.util.EnumMap<K?, V?>(
                com.google.common.collect.ImmutableMap.of<TestEnum?, String?>(
                    com.google.devtools.build.lib.skyframe.serialization.EnumMapCodecTest.TestEnum.FIRST,
                    "first",
                    com.google.devtools.build.lib.skyframe.serialization.EnumMapCodecTest.TestEnum.THIRD,
                    "third",
                    com.google.devtools.build.lib.skyframe.serialization.EnumMapCodecTest.TestEnum.SECOND,
                    "second"
                )
            ),
            java.util.EnumMap<K?, V?>(com.google.devtools.build.lib.skyframe.serialization.EnumMapCodecTest.TestEnum::class.java),
            java.util.EnumMap<K?, V?>(EmptyEnum::class.java)
        )
            .runTests()
    }

    @org.junit.Test
    fun throwsOnSubclass() {
        val exception: SerializationException? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable { ObjectCodecs().serialize(SubEnum<E?, V?>(com.google.devtools.build.lib.skyframe.serialization.EnumMapCodecTest.TestEnum::class.java)) })
        assertThat(exception).hasMessageThat().contains("Cannot serialize subclasses of EnumMap")
    }

    private enum class TestEnum {
        FIRST,
        SECOND,
        THIRD
    }

    private enum class EmptyEnum

    private class SubEnum<E : Enum<E?>?, V>(keyType: java.lang.Class<E?>?) : java.util.EnumMap<E?, V?>(keyType)
}
