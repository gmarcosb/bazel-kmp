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
package com.google.devtools.build.lib.concurrent

import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSortedMap
import com.google.common.truth.Truth
import com.google.devtools.build.lib.packages.metrics.PackageMetricsRecorder.clear
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicLong

/** Very basic tests for [FastHotKeyAtomicLongMap].  */
@RunWith(JUnit4::class)
class FastHotKeyAtomicLongMapTest {
    @Test
    fun simple() {
        val map: FastHotKeyAtomicLongMap<String?> = FastHotKeyAtomicLongMap.create()
        assertThat(map.asImmutableMap()).isEmpty()
        val catAtomicLong: AtomicLong = map.getCounter("cat")
        Truth.assertThat(catAtomicLong.get()).isEqualTo(0L)
        assertThat(map.incrementAndGet("cat")).isEqualTo(1L)
        Truth.assertThat(catAtomicLong.get()).isEqualTo(1L)
        Truth.assertThat(catAtomicLong.incrementAndGet()).isEqualTo(2L)
        assertThat(map.incrementAndGet("dog")).isEqualTo(1L)
        Truth.assertThat(ImmutableSortedMap.copyOf(map.asImmutableMap())).isEqualTo(
            ImmutableMap.of<String?, Long?>("cat", 2L, "dog", 1L)
        )
        assertThat(map.incrementAndGet("cat")).isEqualTo(3L)
        Truth.assertThat(ImmutableSortedMap.copyOf(map.asImmutableMap())).isEqualTo(
            ImmutableMap.of<String?, Long?>("cat", 3L, "dog", 1L)
        )
        assertThat(map.decrementAndGet("cat")).isEqualTo(2L)
        Truth.assertThat(catAtomicLong.decrementAndGet()).isEqualTo(1L)
        assertThat(map.decrementAndGet("dog")).isEqualTo(0L)
        assertThat(map.decrementAndGet("cat")).isEqualTo(0L)
        Truth.assertThat(ImmutableSortedMap.copyOf(map.asImmutableMap())).isEqualTo(
            ImmutableMap.of<String?, Long?>("cat", 0L, "dog", 0L)
        )
        map.clear()
        assertThat(map.asImmutableMap()).isEmpty()
    }
}
