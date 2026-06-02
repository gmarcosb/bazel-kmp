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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.analysis.config.ExecutionInfoModifier.Converter

/** Tests [ExecutionInfoModifier].  */
@RunWith(JUnit4::class)
class ExecutionInfoModifierTest {
    private val converter: ExecutionInfoModifier.Converter = Converter()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executionInfoModifier_empty() {
        val modifier: ExecutionInfoModifier = converter.convert("")
        assertThat(modifier.matches("Anything")).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executionInfoModifier_singleAdd() {
        val modifier: ExecutionInfoModifier = converter.convert("Genrule=+x")
        assertThat(modifier.matches("SomethingElse")).isFalse()
        assertModifierMatchesAndResults(modifier, "Genrule", com.google.common.collect.ImmutableSet.of<String?>("x"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executionInfoModifier_singleRemove() {
        val modifier: ExecutionInfoModifier = converter.convert("Genrule=-x")
        val info: MutableMap<String?, String?> = HashMap<String?, String?>()
        info.put("x", "")

        modifier.apply("Genrule", info)

        Truth.assertThat(info).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executionInfoModifier_multipleExpressions() {
        val modifier: ExecutionInfoModifier = converter.convert("Genrule=+x,.*=+y,CppCompile=+z")
        assertModifierMatchesAndResults(
            modifier,
            "Genrule",
            com.google.common.collect.ImmutableSet.of<String?>("x", "y")
        )
        assertModifierMatchesAndResults(
            modifier,
            "CppCompile",
            com.google.common.collect.ImmutableSet.of<String?>("y", "z")
        )
        assertModifierMatchesAndResults(
            modifier,
            "GenericAction",
            com.google.common.collect.ImmutableSet.of<String?>("y")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executionInfoModifier_multipleOptionsAdditive() {
        val modifier1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            converter.convert(
                "Genrule=+x,CppCompile=-y1,GenericAction=+z,MergeLayers=+t,OtherAction=+o"
            )
        val modifier2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            converter.convert(
                "Genrule=-x,CppCompile=+y1,CppCompile=+y2,GenericAction=+z,MergeLayers=+u"
            )
        val modifier3: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            converter.convert(".*=-t")

        val modifiers: com.google.common.collect.ImmutableList<Any?> =
            com.google.common.collect.ImmutableList.of<Any?>(modifier1, modifier2, modifier3)
        assertModifierMatchesAndResults(
            modifiers,  /* additive= */
            true,
            "Genrule",
            com.google.common.collect.ImmutableSet.of<String?>()
        )
        assertModifierMatchesAndResults(
            modifiers,  /* additive= */
            true,
            "CppCompile",
            com.google.common.collect.ImmutableSet.of<String?>("y1", "y2")
        )
        assertModifierMatchesAndResults(
            modifiers,  /* additive= */true, "GenericAction", com.google.common.collect.ImmutableSet.of<String?>("z")
        )
        assertModifierMatchesAndResults(
            modifiers,  /* additive= */true, "MergeLayers", com.google.common.collect.ImmutableSet.of<String?>("u")
        )
        assertModifierMatchesAndResults(
            modifiers,  /* additive= */true, "OtherAction", com.google.common.collect.ImmutableSet.of<String?>("o")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executionInfoModifier_multipleOptionsNonAdditive() {
        val modifier1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            converter.convert(
                "Genrule=+x,CppCompile=-y1,GenericAction=+z,MergeLayers=+t,OtherAction=+o"
            )
        val modifier2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            converter.convert(
                "Genrule=-x,CppCompile=+y1,CppCompile=+y2,GenericAction=+z,MergeLayers=+u"
            )
        val modifier3: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            converter.convert(".*=-t")

        val modifiers1: com.google.common.collect.ImmutableList<Any?> =
            com.google.common.collect.ImmutableList.of<Any?>(modifier1, modifier2)

        assertModifierMatchesAndResults(
            modifiers1,  /* additive= */false, "Genrule", com.google.common.collect.ImmutableSet.of<String?>()
        )
        assertModifierMatchesAndResults(
            modifiers1,  /* additive= */
            false,
            "CppCompile",
            com.google.common.collect.ImmutableSet.of<String?>("y1", "y2")
        )
        assertModifierMatchesAndResults(
            modifiers1,  /* additive= */false, "GenericAction", com.google.common.collect.ImmutableSet.of<String?>("z")
        )
        assertModifierMatchesAndResults(
            modifiers1,  /* additive= */false, "MergeLayers", com.google.common.collect.ImmutableSet.of<String?>("u")
        )
        assertThat(ExecutionInfoModifier.matches(modifiers1, false, "OtherAction")).isFalse()

        val modifiers2: com.google.common.collect.ImmutableList<Any?> =
            com.google.common.collect.ImmutableList.of<Any?>(modifier1, modifier2, modifier3)

        assertModifierMatchesAndResults(
            modifiers2,  /* additive= */false, "Genrule", com.google.common.collect.ImmutableSet.of<String?>()
        )
        assertModifierMatchesAndResults(
            modifiers2,  /* additive= */false, "CppCompile", com.google.common.collect.ImmutableSet.of<String?>()
        )
        assertModifierMatchesAndResults(
            modifiers2,  /* additive= */false, "GenericAction", com.google.common.collect.ImmutableSet.of<String?>()
        )
        assertModifierMatchesAndResults(
            modifiers2,  /* additive= */false, "MergeLayers", com.google.common.collect.ImmutableSet.of<String?>()
        )
        assertModifierMatchesAndResults(
            modifiers2,  /* additive= */false, "OtherAction", com.google.common.collect.ImmutableSet.of<String?>()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executionInfoModifier_invalidFormat_throws() {
        val invalidModifiers: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("A", "=", "A=", "A=+", "=+", "A=-B,A", "A=B", "A", ",")
        for (invalidModifer in invalidModifiers) {
            org.junit.Assert.assertThrows<T?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { converter.convert(invalidModifer) })
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executionInfoModifier_invalidFormat_exceptionShowsOffender() {
        val thrown: OptionsParsingException? =
            org.junit.Assert.assertThrows<T?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { converter.convert("A=+1,B=2,C=-3") })
        assertThat(thrown).hasMessageThat().contains("malformed")
        assertThat(thrown).hasMessageThat().contains("'B=2'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executionInfoModifier_EqualsTester() {
        EqualsTester() // base empty
            .addEqualityGroup(converter.convert(""), converter.convert("")) // base non-empty
            .addEqualityGroup(converter.convert("A=+B"), converter.convert("A=+B")) // different pattern and key
            .addEqualityGroup(converter.convert("C=+D")) // different key
            .addEqualityGroup(converter.convert("A=+D")) // different pattern
            .addEqualityGroup(converter.convert("C=+B")) // different operation
            .addEqualityGroup(converter.convert("A=-B")) // more items
            .addEqualityGroup(converter.convert("A=+B,C=-D"), converter.convert("A=+B,C=-D")) // different order
            .addEqualityGroup(converter.convert("C=-D,A=+B"))
            .testEquals()
    }

    private fun assertModifierMatchesAndResults(
        modifier: ExecutionInfoModifier, mnemonic: String?, expectedKeys: MutableSet<String?>
    ) {
        assertModifierMatchesAndResults(
            com.google.common.collect.ImmutableList.of<ExecutionInfoModifier?>(modifier),  /* additive= */
            false,
            mnemonic,
            expectedKeys
        )
    }

    private fun assertModifierMatchesAndResults(
        modifiers: MutableList<ExecutionInfoModifier?>?,
        additive: Boolean,
        mnemonic: String?,
        expectedKeys: MutableSet<String?>
    ) {
        val copy: MutableMap<String?, String?> = HashMap<String?, String?>()
        ExecutionInfoModifier.apply(modifiers, additive, mnemonic, copy)
        assertThat(ExecutionInfoModifier.matches(modifiers, additive, mnemonic)).isTrue()
        Truth.assertThat(copy)
            .containsExactlyEntriesIn(
                expectedKeys.stream().collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<String?, String?, String?>(
                        java.util.function.Function { k: String? -> k },
                        java.util.function.Function { unused: String? -> "" })
                )
            )
    }
}
