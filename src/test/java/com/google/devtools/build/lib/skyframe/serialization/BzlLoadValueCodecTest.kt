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

import com.google.devtools.build.lib.cmdline.RepositoryName

/** Tests for [BzlLoadValue] serialization.  */
@RunWith(JUnit4::class)
class BzlLoadValueCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun objectCodecTests() {
        val module: Module = Module.create()
        module.setGlobal("a", 1)
        module.setGlobal("b", 2)
        module.setGlobal("c", 3)
        val digest: ByteArray? = "dummy".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)

        SerializationTester(BzlLoadValue(module, digest, BzlVisibility.PUBLIC, SOME_TABLE))
            .setVerificationFunction(
                SerializationTester.VerificationFunction { x, y ->
                    if (!java.util.Arrays.equals(x.transitiveDigest, y.transitiveDigest)) {
                        throw java.lang.AssertionError("unequal digests after serialization")
                    }
                    assertThat(x.recordedRepoMappings).isEqualTo(y.recordedRepoMappings)
                } as SerializationTester.VerificationFunction<BzlLoadValue?>)
            .runTestsWithoutStableSerializationCheck()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun canSendBuiltins() {
        val builtin = Any()
        val registry: ObjectCodecRegistry? =
            AutoRegistry.get().getBuilder().addReferenceConstant(builtin).build()
        val value: BzlLoadValue = makeBLV("var", builtin)
        val deserialized: BzlLoadValue = RoundTripping.roundTrip(value, registry)
        val deserializedDummy: Any? = deserialized.getModule().getGlobal("var")
        Truth.assertThat(deserializedDummy).isSameInstanceAs(builtin)
    }

    companion object {
        private val SOME_TABLE: com.google.common.collect.ImmutableTable<RepositoryName?, String?, RepositoryName?> =
            com.google.common.collect.ImmutableTable.of<R?, C?, V?>(
                RepositoryName.createUnvalidated("foo"), "bar", RepositoryName.createUnvalidated("quux")
            )

        /** Makes a simple [BzlLoadValue] with just one entry and no dependencies.  */
        private fun makeBLV(name: String?, value: Any?): BzlLoadValue {
            val module: Module = Module.create()
            module.setGlobal(name, value)

            val digest: ByteArray? = "dummy".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)
            return BzlLoadValue(module, digest, BzlVisibility.PUBLIC, SOME_TABLE)
        }
    }
}
