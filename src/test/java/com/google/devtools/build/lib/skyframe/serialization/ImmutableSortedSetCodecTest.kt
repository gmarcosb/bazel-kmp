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

import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant

/** Tests for [ImmutableSortedSetCodec].  */
@RunWith(JUnit4::class)
class ImmutableSortedSetCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        SerializationTester(
            com.google.common.collect.ImmutableSortedSet.of<E?>(),
            com.google.common.collect.ImmutableSortedSet.< E > of < E ? > ("a", "b", "d"
        ),
        com.google.common.collect.ImmutableSortedSet.orderedBy<String?>(java.util.Comparator.naturalOrder<String?>())
            .add("a", "c").build(),
        com.google.common.collect.ImmutableSortedSet.orderedBy<String?>(LENGTH_COMPARATOR).add("abc", "defg", "h", "")
            .build()) // Check for order and comparator.
        .setVerificationFunction(
            VerificationFunction { deserialized, subject ->
                assertThat(deserialized).isEqualTo(subject)
                assertThat(deserialized).containsExactlyElementsIn(subject).inOrder()
                assertThat(deserialized.comparator()).isEqualTo(subject.comparator())
            } as VerificationFunction<com.google.common.collect.ImmutableSortedSet<String?>?>)
            .runTests()
    }

    @org.junit.Test
    fun unknowComparatorThrows() {
        org.junit.Assert.assertThrows<T?>(
            SerializationException::class.java,
            org.junit.function.ThrowingRunnable {
                RoundTripping.roundTrip(
                    com.google.common.collect.ImmutableSortedSet.orderedBy<String?>(
                        java.util.Comparator.comparingInt<String?>(
                            ToIntFunction { obj: String? -> obj.length() })
                    )
                        .add("a", "bcd", "ef")
                )
            })
    }

    companion object {
        @SerializationConstant
        @VisibleForSerialization
        val LENGTH_COMPARATOR: java.util.Comparator<String?> =
            java.util.Comparator.comparingInt<String?>(ToIntFunction { obj: String? -> obj.length() })
    }
}
