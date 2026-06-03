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

/** Tests for [HashSetCodec].  */
@RunWith(JUnit4::class)
class HashSetCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smokeTest() {
        val subjectTwo: LinkedHashSet<String?> = LinkedHashSet<String?>()
        subjectTwo.add("one")
        subjectTwo.add("two")
        subjectTwo.add("three")
        val nullSet: LinkedHashSet<String?> = LinkedHashSet<String?>()
        nullSet.add(null)
        val mixedSet: LinkedHashSet<String?> = LinkedHashSet<String?>()
        mixedSet.add(null)
        mixedSet.add("memberOne")
        val plainHashSet: HashSet<String?> = HashSet<String?>()
        plainHashSet.add("maybeFirst")
        plainHashSet.add("maybeSecond")
        SerializationTester(
            LinkedHashSet<String?>(), subjectTwo, nullSet, mixedSet, plainHashSet
        )
            .setVerificationFunction(
                VerificationFunction { deserialized, subject ->
                    assertThat(deserialized).isEqualTo(subject)
                    assertThat(deserialized.size()).isEqualTo(subject.size())
                } as VerificationFunction<HashSet<String?>?>)
            .runTests()
    }
}
