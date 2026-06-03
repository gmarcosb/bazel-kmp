// Copyright 2019 The Bazel Authors. All rights reserved.
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

/** Tests for [BigIntegerCodec].  */
@RunWith(JUnit4::class)
class BigIntegerCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        val bigBigInt: BigInteger =
            BigInteger("9999999999999999999999999999999999999999999999999999999999999")

        SerializationTester(
            BigInteger.ZERO,
            BigInteger.ONE,
            BigInteger.valueOf(-1),
            BigInteger.valueOf(java.lang.Long.MAX_VALUE),
            BigInteger.valueOf(java.lang.Long.MIN_VALUE),
            bigBigInt,
            bigBigInt.pow(10000)
        )
            .runTests()
    }
}
