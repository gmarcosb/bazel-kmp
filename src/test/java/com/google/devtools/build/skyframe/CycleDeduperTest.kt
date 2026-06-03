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
package com.google.devtools.build.skyframe

import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Simple tests for [CycleDeduper].  */
@RunWith(JUnit4::class)
class CycleDeduperTest {
    private val cycleDeduper: CycleDeduper<String?> = CycleDeduper()

    @org.junit.Test
    fun simple() {
        assertThat(cycleDeduper.alreadySeen(com.google.common.collect.ImmutableList.of<E?>("a", "b"))).isFalse()
        assertThat(cycleDeduper.alreadySeen(com.google.common.collect.ImmutableList.of<E?>("a", "b"))).isTrue()
        assertThat(cycleDeduper.alreadySeen(com.google.common.collect.ImmutableList.of<E?>("b", "a"))).isTrue()

        assertThat(cycleDeduper.alreadySeen(com.google.common.collect.ImmutableList.of<E?>("a", "b", "c"))).isFalse()
        assertThat(cycleDeduper.alreadySeen(com.google.common.collect.ImmutableList.of<E?>("b", "c", "a"))).isTrue()
        assertThat(cycleDeduper.alreadySeen(com.google.common.collect.ImmutableList.of<E?>("c", "a", "b"))).isTrue()
        assertThat(cycleDeduper.alreadySeen(com.google.common.collect.ImmutableList.of<E?>("b", "a", "c"))).isFalse()
        assertThat(cycleDeduper.alreadySeen(com.google.common.collect.ImmutableList.of<E?>("c", "b", "a"))).isTrue()
    }

    @org.junit.Test
    fun badCycle_empty() {
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { cycleDeduper.alreadySeen(com.google.common.collect.ImmutableList.of<E?>()) })
    }

    @org.junit.Test
    fun badCycle_nonUniqueMembers() {
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable {
                cycleDeduper.alreadySeen(
                    com.google.common.collect.ImmutableList.of<E?>(
                        "a",
                        "b",
                        "a"
                    )
                )
            })
    }
}
