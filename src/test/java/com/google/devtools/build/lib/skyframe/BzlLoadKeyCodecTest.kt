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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/** Tests for BzlLoadValue key's serialization.  */
@RunWith(JUnit4::class)
class BzlLoadKeyCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        // This test uses DynamicCodec.
        val serializationTester: SerializationTester =
            SerializationTester(
                BzlLoadValue.keyForBuild(Label.parseCanonicalUnchecked("//foo/bar:baz")),
                BzlLoadValue.keyForBuiltins(Label.parseCanonicalUnchecked("@_builtins//:foo/bar"))
            )
        FsUtils.addDependencies(serializationTester)
        serializationTester.runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLeafCodec() {
        // This test uses the explicit LeafObjectCodec implementation.
        SerializationTester(
            BzlLoadValue.keyForBuild(Label.parseCanonicalUnchecked("//foo/bar:baz")),
            BzlLoadValue.keyForBuiltins(Label.parseCanonicalUnchecked("@_builtins//:foo/bar"))
        )
            .addCodec(BzlLoadValue.bzlLoadKeyCodec())
            .runTests()
    }
}
