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

import com.google.devtools.build.lib.cmdline.Label

/** Tests for [NoSuchTargetException] serialization.  */
@RunWith(JUnit4::class)
class NoSuchTargetExceptionCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        SerializationTester(
            NoSuchTargetException("sup"),
            NoSuchTargetException(Label.parseCanonical("//foo:bar"), "busted"),
            NoSuchTargetException(mockTarget("//broken:target"))
        )
            .makeMemoizing()
            .setVerificationFunction(verifyDeserialization)
            .runTests()
    }

    companion object {
        @Throws(LabelSyntaxException::class)
        private fun mockTarget(label: String?): Target {
            val mockTarget: Target = Mockito.mock<Target>(Target::class.java)
            Mockito.`when`<T?>(mockTarget.getLabel()).thenReturn(Label.parseCanonical(label))
            return mockTarget
        }

        private val verifyDeserialization: SerializationTester.VerificationFunction<NoSuchTargetException?> =
            SerializationTester.VerificationFunction { deserialized, subject ->
                assertThat(deserialized).hasMessageThat().isEqualTo(subject.getMessage())
                assertThat(deserialized.getLabel()).isEqualTo(subject.getLabel())
                assertThat(deserialized.hasTarget()).isEqualTo(subject.hasTarget())
            }
    }
}
