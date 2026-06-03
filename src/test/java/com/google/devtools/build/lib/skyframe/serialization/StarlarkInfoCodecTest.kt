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

/** Unit tests for [StarlarkInfoCodec].  */
@RunWith(JUnit4::class)
class StarlarkInfoCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun objectCodecTests() {
        val map: com.google.common.collect.ImmutableMap<String?, Any?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "a",
                StarlarkInt.of(1),
                "b",
                StarlarkInt.of(2),
                "c",
                StarlarkInt.of(3)
            )
        val provider: StarlarkProvider = makeProvider()
        SerializationTester(
            StarlarkInfo.create(provider, map),  // empty
            StarlarkInfo.create(
                provider,
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            ),  // with an error message
            StarlarkInfoWithMessage.createWithCustomMessage(provider, map, "Dummy error: %s"),
            StarlarkInfoWithMessage.createWithCustomMessage(
                StructProvider.STRUCT, map, "Dummy error: %s"
            )
        )
            .addDependency(StructProvider::class.java, StructProvider.STRUCT)
            .makeMemoizing()
            .setVerificationFunction({ original: StarlarkInfo, deserialized: StarlarkInfo ->
                verificationFunction(
                    original,
                    deserialized
                )
            })
            .runTests()
    }

    companion object {
        private fun verificationFunction(original: StarlarkInfo, deserialized: StarlarkInfo) {
            assertThat(deserialized).isEqualTo(original)
            assertThat(deserialized.getFieldNames())
                .containsExactlyElementsIn(original.getFieldNames())
                .inOrder()
        }

        /** Returns an exported, schemaless provider.  */
        private fun makeProvider(): StarlarkProvider {
            return StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .buildExported(
                    Key(
                        keyForBuild(Label.parseCanonicalUnchecked("//foo:bar.bzl")), "foo"
                    )
                )
        }
    }
}
