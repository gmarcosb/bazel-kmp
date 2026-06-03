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

/** Tests for [MutableListCodec].  */
@RunWith(JUnit4::class)
class MutableListCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        val aliasedInnerList: StarlarkList<Any?>? = StarlarkList.immutableOf("1", "2")
        SerializationTester(
            StarlarkList.empty(),
            StarlarkList.immutableOf("1", "2", StarlarkList.immutableOf("3", "4"), "5"),
            StarlarkList.immutableOf(aliasedInnerList, aliasedInnerList)
        )
            .makeMemoizing() // Note that verification uses an equals() test, which ensures not just correct order, but
            // also that we got back the right kind of value (list versus tuple).
            .runTests()
    }
}
