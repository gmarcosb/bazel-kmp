// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.skyframe.serialization.testutils.RoundTripping.roundTripMemoized

/** Tests for [AspectValue].  */
@RunWith(JUnit4::class)
class AspectValueTest : AnalysisTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun keyEquality() {
        update()
        val c1: BuildConfigurationValue? = getTargetConfiguration()
        val c2: BuildConfigurationValue? = getExecConfiguration()
        val l1: Label? = Label.parseCanonical("//a:l1")
        val l1b: Label? = Label.parseCanonical("//a:l1")
        val l2: Label? = Label.parseCanonical("//a:l2")
        val i1: AspectParameters? = Builder()
            .addAttribute("foo", "bar")
            .build()
        val i1b: AspectParameters? = Builder()
            .addAttribute("foo", "bar")
            .build()
        val i2: AspectParameters? = Builder()
            .addAttribute("foo", "baz")
            .build()
        val a1: AttributeAspect = TestAspects.ATTRIBUTE_ASPECT
        val a1b: AttributeAspect = TestAspects.ATTRIBUTE_ASPECT
        val a2: ExtraAttributeAspect = TestAspects.EXTRA_ATTRIBUTE_ASPECT

        // label: //a:l1 or //a:l2
        // baseConfiguration: target or exec
        // aspect: Attribute or ExtraAttribute
        // parameters: bar or baz
        EqualsTester()
            .addEqualityGroup(
                createKey(l1, c1, a1, i1),
                createKey(l1, c1, a1, i1b),
                createKey(l1, c1, a1b, i1),
                createKey(l1, c1, a1b, i1b),
                createKey(l1b, c1, a1, i1),
                createKey(l1b, c1, a1, i1b),
                createKey(l1b, c1, a1b, i1),
                createKey(l1b, c1, a1b, i1b)
            )
            .addEqualityGroup(
                createKey(l1, c1, a1, i2),
                createKey(l1, c1, a1b, i2),
                createKey(l1b, c1, a1, i2),
                createKey(l1b, c1, a1b, i2)
            )
            .addEqualityGroup(
                createKey(l1, c1, a2, i1),
                createKey(l1, c1, a2, i1b),
                createKey(l1b, c1, a2, i1),
                createKey(l1b, c1, a2, i1b)
            )
            .addEqualityGroup(createKey(l1, c1, a2, i2), createKey(l1b, c1, a2, i2))
            .addEqualityGroup(
                createKey(l1, c2, a1, i1),
                createKey(l1, c2, a1, i1b),
                createKey(l1, c2, a1b, i1),
                createKey(l1, c2, a1b, i1b),
                createKey(l1b, c2, a1, i1),
                createKey(l1b, c2, a1, i1b),
                createKey(l1b, c2, a1b, i1),
                createKey(l1b, c2, a1b, i1b)
            )
            .addEqualityGroup(
                createKey(l1, c2, a1, i2),
                createKey(l1, c2, a1b, i2),
                createKey(l1b, c2, a1, i2),
                createKey(l1b, c2, a1b, i2)
            )
            .addEqualityGroup(
                createKey(l1, c2, a2, i1),
                createKey(l1, c2, a2, i1b),
                createKey(l1b, c2, a2, i1),
                createKey(l1b, c2, a2, i1b)
            )
            .addEqualityGroup(createKey(l1, c2, a2, i2), createKey(l1b, c2, a2, i2))
            .addEqualityGroup(
                createKey(l2, c1, a1, i1),
                createKey(l2, c1, a1, i1b),
                createKey(l2, c1, a1b, i1),
                createKey(l2, c1, a1b, i1b)
            )
            .addEqualityGroup(createKey(l2, c1, a1, i2), createKey(l2, c1, a1b, i2))
            .addEqualityGroup(createKey(l2, c1, a2, i1), createKey(l2, c1, a2, i1b))
            .addEqualityGroup(createKey(l2, c1, a2, i2))
            .addEqualityGroup(
                createKey(l2, c2, a1, i1),
                createKey(l2, c2, a1, i1b),
                createKey(l2, c2, a1b, i1),
                createKey(l2, c2, a1b, i1b)
            )
            .addEqualityGroup(createKey(l2, c2, a1, i2), createKey(l2, c2, a1b, i2))
            .addEqualityGroup(createKey(l2, c2, a2, i1), createKey(l2, c2, a2, i1b))
            .addEqualityGroup(createKey(l2, c2, a2, i2))
            .addEqualityGroup(
                createDerivedKey(l1, c1, a1, i1, a2, i2), createDerivedKey(l1, c1, a1, i1b, a2, i2)
            )
            .addEqualityGroup(
                createDerivedKey(l1, c1, a2, i1, a1, i2), createDerivedKey(l1, c1, a2, i1b, a1, i2)
            )
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun roundTrippingEmptyAspectParameters_outputsSingleInstance() {
        val subject: com.google.common.collect.ImmutableList<E?> =
            com.google.common.collect.ImmutableList.of<E?>(
                Builder().build(), Builder().build()
            )
        // Empty parameters has its own singleton serialization constant.
        assertThat(subject.get(0)).isSameInstanceAs(subject.get(1))
        val deserialized: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            roundTripMemoized(subject, AutoRegistry.get())
        // It's preserved by round tripping.
        assertThat(deserialized.get(0)).isSameInstanceAs(subject.get(0))
        assertThat(deserialized.get(1)).isSameInstanceAs(subject.get(0))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun roundTripping_mergesEquivalentAspectParameters() {
        val subject: com.google.common.collect.ImmutableList<E?> =
            com.google.common.collect.ImmutableList.of<E?>(
                Builder().addAttribute("abc", "def").build(),
                Builder().addAttribute("abc", "def").build()
            )
        assertThat(subject.get(0)).isNotSameInstanceAs(subject.get(1))
        val deserialized: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            roundTripMemoized(subject, AutoRegistry.get())
        assertThat(deserialized.get(0)).isSameInstanceAs(deserialized.get(1))
    }

    companion object {
        private fun createKey(
            label: Label?,
            baseConfiguration: BuildConfigurationValue?,
            aspectClass: NativeAspectClass?,
            parameters: AspectParameters?
        ): AspectKey {
            return AspectKeyCreator.createAspectKey(
                AspectDescriptor.of(aspectClass, parameters),
                ConfiguredTargetKey.builder().setLabel(label).setConfiguration(baseConfiguration).build()
            )
        }

        private fun createDerivedKey(
            label: Label?,
            baseConfiguration: BuildConfigurationValue?,
            aspectClass1: NativeAspectClass?,
            parameters1: AspectParameters?,
            aspectClass2: NativeAspectClass?,
            parameters2: AspectParameters?
        ): SkyKey {
            val baseKey: AspectKey = createKey(label, baseConfiguration, aspectClass1, parameters1)
            return AspectKeyCreator.createAspectKey(
                AspectDescriptor.of(aspectClass2, parameters2),
                com.google.common.collect.ImmutableList.of<E?>(baseKey),
                ConfiguredTargetKey.builder().setLabel(label).setConfiguration(baseConfiguration).build()
            )
        }
    }
}
