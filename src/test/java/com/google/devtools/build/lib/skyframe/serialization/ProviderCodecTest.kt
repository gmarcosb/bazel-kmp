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

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Unit tests for [ProviderCodec].  */
@RunWith(JUnit4::class)
class ProviderCodecTest {
    private class DummyProvider : BuiltinProvider<StructImpl?>("DummyInfo", StructImpl::class.java)

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun objectCodecTests() {
        val dummyProvider = DummyProvider()
        SerializationTester(
            dummyProvider,
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .buildExported(
                    Key(
                        keyForBuild(Label.parseCanonicalUnchecked("//foo:bar.bzl")), "foo"
                    )
                )
        )
            .addDependency(DummyProvider::class.java, dummyProvider)
            .runTests()
    }
}
