// Copyright 2025 The Bazel Authors. All rights reserved.
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
package net.starlark.java.eval

import com.google.common.testing.EqualsTester
import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import com.google.devtools.common.options.testing.ConverterTester.addEqualityGroup
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [SymbolGenerator].  */
@RunWith(JUnit4::class)
class SymbolGeneratorTest {
    @org.junit.Test
    fun localSymbol_equalityAndHashCode() {
        val owner1 = Any()
        val owner2 = Any()

        val generator1: SymbolGenerator<Any?> = SymbolGenerator.create(owner1)
        val generator2: SymbolGenerator<Any?> = SymbolGenerator.create(owner2)

        val s1a: SymbolGenerator.Symbol<Any?>? = generator1.generate() // owner1, index 0
        val s1b: SymbolGenerator.Symbol<Any?>? = generator1.generate() // owner1, index 1
        val s2a: SymbolGenerator.Symbol<Any?>? = generator2.generate() // owner2, index 0

        // Create another generator with the same owner object
        val generator1Prime: SymbolGenerator<Any?> = SymbolGenerator.create(owner1)
        val s1aPrime: SymbolGenerator.Symbol<Any?>? = generator1Prime.generate() // owner1, index 0

        EqualsTester()
            .addEqualityGroup(s1a, s1a) // Reflexive
            .addEqualityGroup(s1b)
            .addEqualityGroup(s2a)
            .addEqualityGroup(s1aPrime) // Different generator instance, same owner and index
            .testEquals()

        assertThat(s1a).isNotEqualTo(s1b)
        assertThat(s1a).isNotEqualTo(s2a)
        assertThat(s1b).isNotEqualTo(s2a)

        // These should not be equal because the index will be different
        assertThat(s1a).isNotEqualTo(s1aPrime)
    }

    @org.junit.Test
    fun localSymbol_hashCode_isMemoized() {
        class HashCounter internal constructor(private val hash: Int) {
            private var hashCodeCalls = 0

            override fun hashCode(): Int {
                hashCodeCalls++
                return hash
            }
        }

        val owner = HashCounter(123)
        val generator: SymbolGenerator<HashCounter?> = SymbolGenerator.create(owner)
        val symbol: SymbolGenerator.Symbol<HashCounter?> = generator.generate()

        val hash1: Int = symbol.hashCode()
        Truth.assertThat(owner.hashCodeCalls).isEqualTo(1)

        val hash2: Int = symbol.hashCode()
        Truth.assertThat(hash1).isEqualTo(hash2)
        Truth.assertThat(owner.hashCodeCalls).isEqualTo(1) // Should not have incremented

        val hash3: Int = symbol.hashCode()
        Truth.assertThat(hash1).isEqualTo(hash3)
        Truth.assertThat(owner.hashCodeCalls).isEqualTo(1) // Should still be 1
    }

    @org.junit.Test
    fun globalSymbol_equalityAndHashCode() {
        val owner1 = Any()
        val owner2 = Any()

        val generator1: SymbolGenerator<Any?> = SymbolGenerator.create(owner1)
        val generator2: SymbolGenerator<Any?> = SymbolGenerator.create(owner2)

        val local1: SymbolGenerator.Symbol<Any?> = generator1.generate()
        val local2: SymbolGenerator.Symbol<Any?> = generator2.generate()

        val g1a: SymbolGenerator.Symbol<Any?>? = local1.exportAs("name1")
        val g1aDup: SymbolGenerator.Symbol<Any?>? = local1.exportAs("name1")
        val g1b: SymbolGenerator.Symbol<Any?>? = local1.exportAs("name2")
        val g2a: SymbolGenerator.Symbol<Any?>? = local2.exportAs("name1")

        EqualsTester()
            .addEqualityGroup(g1a, g1aDup)
            .addEqualityGroup(g1b)
            .addEqualityGroup(g2a)
            .testEquals()
    }
}
