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

/** Tests for [UnmodifiableMapCodec].  */
@RunWith(JUnit4::class)
class UnmodifiableMapCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        val map1: HashMap<String?, String?> = HashMap<String?, String?>()
        map1.put("a", "first")
        map1.put("b", null)
        val map2: LinkedHashMap<String?, String?> = LinkedHashMap<String?, String?>()
        map2.put("c", null)
        map2.put("a", "second")
        SerializationTester(Collections.unmodifiableMap<K?, V?>(map1), Collections.unmodifiableMap<K?, V?>(map2))
            .setVerificationFunction(
                VerificationFunction { original, deserialized ->
                    assertThat(deserialized).containsExactlyEntriesIn(
                        original
                    ).inOrder()
                } as VerificationFunction<MutableMap<String?, String?>?>)
            .runTests()
    }
}
