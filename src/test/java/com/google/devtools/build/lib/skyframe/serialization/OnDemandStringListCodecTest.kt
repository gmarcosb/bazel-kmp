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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.query2.proto.proto2api.Build

/** Tests for [LazyStringListCodec].  */
@RunWith(JUnit4::class)
class OnDemandStringListCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        SerializationTester( // Tests empty list as well, since license has two repeated string fields.
            Build.License.newBuilder().addLicenseType("first").addLicenseType("second").build()
        )
            .runTests()
    }
}
