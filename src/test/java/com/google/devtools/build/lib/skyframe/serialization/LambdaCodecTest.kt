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

import com.google.devtools.build.lib.skyframe.serialization.testutils.RoundTripping

/** Tests for [LambdaCodec].  */
@RunWith(JUnit4::class)
class LambdaCodecTest {
    private interface MyInterface {
        fun func(arg: String?): Boolean
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        val returnValue: MutableList<Boolean?> = java.util.ArrayList<Boolean?>()
        SerializationTester(
            java.util.function.Supplier { null } as java.util.function.Supplier<java.lang.Void?>,
            java.util.function.Function { obj: Any? -> obj.toString() } as java.util.function.Function<Any?, String?>,
            MyInterface { arg: String? -> "hello" == arg } as MyInterface,
            MyInterface { anObject: String? -> "hello".equals(anObject) } as MyInterface,
            MyInterface { arg: String? -> !returnValue.isEmpty() } as MyInterface) // We can't compare lambdas for equality, just make sure they get deserialized.
            .setVerificationFunction({ original, deserialized -> assertThat(deserialized).isNotNull() })
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lambdaBehaviorPreserved() {
        val returnValue: MutableList<Boolean?> = java.util.ArrayList<Boolean?>()
        val lambda = MyInterface { arg: String? -> returnValue.isEmpty() } as MyInterface
        val deserializedLambda: MyInterface = RoundTripping.roundTrip(lambda)
        Truth.assertThat(lambda.func("any")).isTrue()
        Truth.assertThat(deserializedLambda.func("any")).isTrue()
        returnValue.add(true)
        Truth.assertThat(lambda.func("any")).isFalse()
        // Deserialized object's list is not the same as original's. Changes to original aren't seen.
        Truth.assertThat(deserializedLambda.func("any")).isTrue()
    }

    @org.junit.Test
    fun onlySerializableWorks() {
        val unserializableLambda: MyInterface = MyInterface { arg: String? -> true }
        org.junit.Assert.assertThrows<T?>(
            SerializationException::class.java,
            org.junit.function.ThrowingRunnable {
                RoundTripping.toBytesMemoized(
                    unserializableLambda,
                    AutoRegistry.get()
                )
            })
    }
}
