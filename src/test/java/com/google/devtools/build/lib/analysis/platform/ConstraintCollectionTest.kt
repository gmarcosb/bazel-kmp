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
package com.google.devtools.build.lib.analysis.platform

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.cmdline.Label
import org.junit.Test

/** Tests of [ConstraintCollection].  */
@RunWith(JUnit4::class)
class ConstraintCollectionTest : BuildViewTestCase() {
    @Test
    @Throws(Exception::class)
    fun testSetArithmetic() {
        val setting1: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:s1"))
        val value1: ConstraintValueInfo =
            ConstraintValueInfo.create(setting1, Label.parseCanonicalUnchecked("//foo:value1"))
        val setting2: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:s2"))
        val value2: ConstraintValueInfo =
            ConstraintValueInfo.create(setting2, Label.parseCanonicalUnchecked("//foo:value2"))
        val setting3: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:s3"))
        val value3: ConstraintValueInfo =
            ConstraintValueInfo.create(setting3, Label.parseCanonicalUnchecked("//foo:value3"))

        val collection: ConstraintCollection =
            ConstraintCollection.builder().addConstraints(value1, value2).build()
        assertThat(collection.containsAll(ImmutableList.of<E?>(value1))).isTrue()
        assertThat(collection.findMissing(ImmutableList.of<E?>(value1))).isEmpty()
        assertThat(collection.containsAll(ImmutableList.of<E?>(value2))).isTrue()
        assertThat(collection.containsAll(ImmutableList.of<E?>(value1, value2))).isTrue()
        assertThat(collection.containsAll(ImmutableList.of<E?>(value3))).isFalse()
        assertThat(collection.findMissing(ImmutableList.of<E?>(value3))).containsExactly(value3)
        assertThat(collection.containsAll(ImmutableList.of<E?>(value1, value3))).isFalse()
        assertThat(collection.findMissing(ImmutableList.of<E?>(value3))).containsExactly(value3)
    }

    @Test
    @Throws(Exception::class)
    fun testSetArithmetic_withDefaultValues() {
        val setting: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(
                Label.parseCanonicalUnchecked("//foo:s"),
                Label.parseCanonicalUnchecked("//foo:value1")
            )
        val value1: ConstraintValueInfo =
            ConstraintValueInfo.create(setting, Label.parseCanonicalUnchecked("//foo:value1"))
        val value2: ConstraintValueInfo =
            ConstraintValueInfo.create(setting, Label.parseCanonicalUnchecked("//foo:value2"))

        val collection1: ConstraintCollection =
            ConstraintCollection.builder().addConstraints(value1).build()
        assertThat(collection1.containsAll(ImmutableList.of<E?>(value1))).isTrue()
        assertThat(collection1.findMissing(ImmutableList.of<E?>(value1))).isEmpty()
        assertThat(collection1.containsAll(ImmutableList.of<E?>(value2))).isFalse()
        assertThat(collection1.findMissing(ImmutableList.of<E?>(value2))).containsExactly(value2)

        val collectionWithDefault: ConstraintCollection = ConstraintCollection.builder().build()
        assertThat(collectionWithDefault.containsAll(ImmutableList.of<E?>(value1))).isTrue()
        assertThat(collectionWithDefault.findMissing(ImmutableList.of<E?>(value1))).isEmpty()
        assertThat(collectionWithDefault.containsAll(ImmutableList.of<E?>(value2))).isFalse()
        assertThat(collectionWithDefault.findMissing(ImmutableList.of<E?>(value2))).containsExactly(value2)
    }

    @Test
    @Throws(Exception::class)
    fun testDiff() {
        val setting1: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:s1"))
        val value1: ConstraintValueInfo? =
            ConstraintValueInfo.create(setting1, Label.parseCanonicalUnchecked("//foo:value1"))
        val setting2: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//foo:s2"))
        val value2a: ConstraintValueInfo? =
            ConstraintValueInfo.create(setting2, Label.parseCanonicalUnchecked("//foo:value2a"))
        val value2b: ConstraintValueInfo? =
            ConstraintValueInfo.create(setting2, Label.parseCanonicalUnchecked("//foo:value2b"))

        val collection1: ConstraintCollection =
            ConstraintCollection.builder().addConstraints(value1, value2a).build()
        val collection2: ConstraintCollection =
            ConstraintCollection.builder().addConstraints(value1, value2b).build()
        assertThat(collection1.diff(collection2)).containsExactly(setting2)
        assertThat(collection1.diff(collection2))
            .containsAtLeastElementsIn(collection2.diff(collection1))
    }
}
