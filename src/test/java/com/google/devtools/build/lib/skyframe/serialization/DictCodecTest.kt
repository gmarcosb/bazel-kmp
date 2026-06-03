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

/** Tests for [DictCodec].  */
@RunWith(JUnit4::class)
class DictCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        val aliasedInnerDict: Dict<Any?, Any?> =
            Dict.immutableCopyOf(com.google.common.collect.ImmutableMap.of<K?, V?>("1", "2", "3", "4"))
        SerializationTester(
            Dict.empty(),
            Dict.immutableCopyOf(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "1",
                    "2",
                    "3",
                    Dict.immutableCopyOf(com.google.common.collect.ImmutableMap.of<K?, V?>("4", "5"))
                )
            ),
            Dict.immutableCopyOf(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "10",
                    aliasedInnerDict,
                    "20",
                    aliasedInnerDict
                )
            )
        )
            .makeMemoizing()
            .setVerificationFunction({ deserialized: MutableMap<Any?, Any?>?, subject: Dict<kotlin.Any?, kotlin.Any?> ->
                verifyDeserialization(
                    deserialized,
                    subject
                )
            })
            .runTests()
    }

    companion object {
        // Check for order.
        private fun verifyDeserialization(
            deserialized: MutableMap<Any?, Any?>?, subject: Dict<Any?, Any?>
        ) {
            Truth.assertThat(deserialized).isEqualTo(subject)
            Truth.assertThat(deserialized).containsExactlyEntriesIn(subject).inOrder()
        }
    }
}
