// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.testutils

import com.google.devtools.build.lib.skyframe.serialization.testutils.IsomorphismKey.areIsomorphismKeysEqual
import org.junit.Test

@RunWith(JUnit4::class)
class IsomorphismKeyTest {
    @Test
    fun comparingTrivialKeys_comparesTrue() {
        val key1: IsomorphismKey = IsomorphismKey("a")
        val key2: IsomorphismKey = IsomorphismKey("a")

        assertThat(areIsomorphismKeysEqual(key1, key2)).isTrue()
    }

    @Test
    fun differentFingerprints_comparesFalse() {
        val key1: IsomorphismKey = IsomorphismKey("a")
        val key2: IsomorphismKey = IsomorphismKey("b")

        assertThat(areIsomorphismKeysEqual(key1, key2)).isFalse()
    }

    @Test
    fun sameCyclicStructure_comparesTrue() {
        val key1: IsomorphismKey = IsomorphismKey("a")
        val key2: IsomorphismKey = IsomorphismKey("a")

        key1.addLink(key1)
        key2.addLink(key2)

        assertThat(areIsomorphismKeysEqual(key1, key2)).isTrue()
    }

    @Test
    fun differentStructure_comparesFalse() {
        val key1: IsomorphismKey = IsomorphismKey("a")
        val key2: IsomorphismKey = IsomorphismKey("a")

        key1.addLink(key1)

        assertThat(areIsomorphismKeysEqual(key1, key2)).isFalse()
    }

    @Test
    fun nestedStructure_comparesTrue() {
        val key1: IsomorphismKey = IsomorphismKey("a")
        val b1: IsomorphismKey = IsomorphismKey("b")
        val c1: IsomorphismKey = IsomorphismKey("c")
        val d1: IsomorphismKey = IsomorphismKey("d")

        key1.addLink(b1)
        key1.addLink(c1)
        b1.addLink(d1)
        c1.addLink(d1)

        val key2: IsomorphismKey = IsomorphismKey("a")
        val b2: IsomorphismKey = IsomorphismKey("b")
        val c2: IsomorphismKey = IsomorphismKey("c")
        val d2: IsomorphismKey = IsomorphismKey("d")

        key2.addLink(b2)
        key2.addLink(c2)
        b2.addLink(d2)
        c2.addLink(d2)

        assertThat(areIsomorphismKeysEqual(key1, key2)).isTrue()
    }

    @Test
    fun slightlyDifferentStructure_comparesFalse() {
        val key1: IsomorphismKey = IsomorphismKey("a")
        val b1: IsomorphismKey = IsomorphismKey("b")
        val c1: IsomorphismKey = IsomorphismKey("c")
        val d1: IsomorphismKey = IsomorphismKey("d")

        key1.addLink(b1)
        key1.addLink(c1)
        b1.addLink(d1)
        c1.addLink(d1)

        val key2: IsomorphismKey = IsomorphismKey("a")
        val b2: IsomorphismKey = IsomorphismKey("b")
        val c2: IsomorphismKey = IsomorphismKey("c")
        val d2: IsomorphismKey = IsomorphismKey("d")
        val d2prime: IsomorphismKey = IsomorphismKey("d")

        key2.addLink(b2)
        key2.addLink(c2)
        b2.addLink(d2)
        c2.addLink(d2prime)

        assertThat(areIsomorphismKeysEqual(key1, key2)).isFalse()
    }
}
