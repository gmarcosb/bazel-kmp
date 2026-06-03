// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.skyframe.PrepareDepsOfPatternsValue.TargetPatternSequence

/** Tests for serialization of [TargetPatternSequence].  */
@RunWith(JUnit4::class)
class TargetPatternSequenceCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        SerializationTester(
            TargetPatternSequence.create(com.google.common.collect.ImmutableList.of<E?>(), PathFragment.EMPTY_FRAGMENT),
            TargetPatternSequence.create(
                com.google.common.collect.ImmutableList.of<E?>("foo", "bar"), PathFragment.create("baz")
            ),
            TargetPatternSequence.create(
                com.google.common.collect.ImmutableList.of<E?>("uno", "dos"), PathFragment.create("tres")
            ),
            TargetPatternSequence.create(
                com.google.common.collect.ImmutableList.of<E?>("dos", "uno"), PathFragment.create("tres")
            )
        )
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPatternsOrderSignificant() {
        val codecs: ObjectCodecs = ObjectCodecs()
        val serialized1: ByteArray? =
            codecs
                .serialize(
                    TargetPatternSequence.create(
                        com.google.common.collect.ImmutableList.of<E?>("uno", "dos"), PathFragment.create("tres")
                    )
                )
                .toByteArray()
        Truth.assertThat(serialized1).asList().isNotEmpty()

        val serialized2: ByteArray? =
            codecs
                .serialize(
                    TargetPatternSequence.create(
                        com.google.common.collect.ImmutableList.of<E?>("dos", "uno"), PathFragment.create("tres")
                    )
                )
                .toByteArray()
        Truth.assertThat(serialized2).asList().isNotEmpty()
        Truth.assertThat(serialized1).isNotEqualTo(serialized2)
    }
}
