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

import com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.dumpStructure

/** Tests for [HashMapCodec].  */
@RunWith(JUnit4::class)
class HashMapCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        val map1: HashMap<String?, String?> = HashMap<String?, String?>()
        map1.put("a", "first")
        map1.put("b", null)

        val map2: LinkedHashMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        map2.put("c", null)
        map2.put("a", "second")
        map2.put("d", map2) // This map contains itself.

        val emptyMap: HashMap<String?, String?> = HashMap<String?, String?>()

        val deserializedMaps: MutableList<MutableMap<String?, String?>?> =
            java.util.ArrayList<MutableMap<String?, String?>?>()
        // Put in empty map twice to make sure codec doesn't return same empty object.
        SerializationTester(map1, map2, emptyMap, emptyMap)
            .makeMemoizing()
            .setVerificationFunction(
                VerificationFunction { original, deserialized ->
                    var original = original
                    if (original !is LinkedHashMap<*, *>) {
                        original = LinkedHashMap<Any?, Any?>(original)
                    }
                    // Compares the structure to avoid stack overflow when the equals operation
                    // attempts to traverse the circular maps.
                    assertThat(dumpStructure(original)).isEqualTo(dumpStructure(deserialized))
                    deserializedMaps.add(deserialized)
                } as VerificationFunction<MutableMap<*, *>?>)
            .runTests()
        Truth.assertThat(deserializedMaps).hasSize(4)
        for (i in 0..3) {
            for (j in 0..3) {
                if (i == j) {
                    continue
                }
                Truth.assertThat(deserializedMaps.get(i)).isNotSameInstanceAs(deserializedMaps.get(j))
            }
        }
    }
}
