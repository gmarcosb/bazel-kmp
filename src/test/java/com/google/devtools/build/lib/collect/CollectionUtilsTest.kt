// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect

import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [CollectionUtils].  */
@RunWith(JUnit4::class)
class CollectionUtilsTest {
    @org.junit.Test
    fun testDuplicatedElementsOf() {
        assertDups(
            com.google.common.collect.ImmutableList.of<Int?>(),
            com.google.common.collect.ImmutableSet.of<Int?>()
        )
        assertDups(
            com.google.common.collect.ImmutableList.of<Int?>(0),
            com.google.common.collect.ImmutableSet.of<Int?>()
        )
        assertDups(
            com.google.common.collect.ImmutableList.of<Int?>(0, 0, 0),
            com.google.common.collect.ImmutableSet.of<Int?>(0)
        )
        assertDups(
            com.google.common.collect.ImmutableList.of<Int?>(1, 2, 3, 1, 2, 3),
            com.google.common.collect.ImmutableSet.of<Int?>(1, 2, 3)
        )
        assertDups(
            com.google.common.collect.ImmutableList.of<Int?>(1, 2, 3, 1, 2, 3, 4),
            com.google.common.collect.ImmutableSet.of<Int?>(1, 2, 3)
        )
        assertDups(
            com.google.common.collect.ImmutableList.of<Int?>(1, 2, 3, 4),
            com.google.common.collect.ImmutableSet.of<Int?>()
        )
    }

    @get:org.junit.Test
    val isNullOrEmpty_null: Unit
        get() {
            assertThat(CollectionUtils.isNullOrEmpty(null)).isTrue()
        }

    @get:org.junit.Test
    val isNullOrEmpty_empty: Unit
        get() {
            assertThat(CollectionUtils.isNullOrEmpty(com.google.common.collect.ImmutableList.of<E?>())).isTrue()
        }

    @get:org.junit.Test
    val isNullOrEmpty_nonEmpty: Unit
        get() {
            assertThat(CollectionUtils.isNullOrEmpty(com.google.common.collect.ImmutableList.of<E?>(1))).isFalse()
        }

    companion object {
        private fun assertDups(collection: MutableList<Int?>?, dups: MutableSet<Int?>?) {
            assertThat(CollectionUtils.duplicatedElementsOf(collection)).isEqualTo(dups)
        }
    }
}
