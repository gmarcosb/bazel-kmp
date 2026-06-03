// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.metrics

import com.google.devtools.build.lib.sandbox.Cgroup

@RunWith(JUnit4::class)
class CgroupsInfoCollectorTest {
    private val scratch: FsApparatus = FsApparatus.newNative()

    @org.junit.Test
    fun testCollectResourceUsage_returnsValidCgroupInfoMemoryUsage() {
        val clock: com.google.devtools.build.lib.clock.Clock = com.google.devtools.build.lib.clock.BlazeClock.instance()
        val cgroupsInfo1: CgroupsInfo = Mockito.mock<CgroupsInfo>(CgroupsInfo::class.java)
        Mockito.`when`<T?>(cgroupsInfo1.getMemoryUsageInKb()).thenReturn(1000)
        Mockito.`when`<T?>(cgroupsInfo1.exists()).thenReturn(true)
        val cgroupsInfo2: CgroupsInfo = Mockito.mock<CgroupsInfo>(CgroupsInfo::class.java)
        Mockito.`when`<T?>(cgroupsInfo2.exists()).thenReturn(false)
        Mockito.`when`<T?>(cgroupsInfo2.getMemoryUsageInKb()).thenReturn(2000)
        val cgroupsInfo3: CgroupsInfo = Mockito.mock<CgroupsInfo>(CgroupsInfo::class.java)
        Mockito.`when`<T?>(cgroupsInfo3.exists()).thenReturn(true)
        Mockito.`when`<T?>(cgroupsInfo3.getMemoryUsageInKb()).thenReturn(3000)

        val snapshot: ResourceSnapshot =
            CgroupsInfoCollector.instance()
                .collectResourceUsage(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        1L,
                        cgroupsInfo1,
                        2L,
                        cgroupsInfo2,
                        3L,
                        cgroupsInfo3
                    ), clock
                )
        assertThat(snapshot.pidToMemoryInKb()).containsExactly(1L, 1000, 3L, 3000)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCollectResourceUsage_returnsValidCgroupMemoryUsage() {
        val clock: com.google.devtools.build.lib.clock.Clock = com.google.devtools.build.lib.clock.BlazeClock.instance()
        val pidToCgroups: com.google.common.collect.ImmutableMap.Builder<Long?, Cgroup?> =
            com.google.common.collect.ImmutableMap.builder<Long?, Cgroup?>()

        for (i in 1..3) {
            var memory: UnifiedMemory? = null
            if (i != 2) {
                val memoryPath: Path = scratch.path("cgroup-" + i + "/memory").getPathFile().toPath()
                scratch.file(memoryPath.toString() + "/memory.current", (i * 1000 * 1024).toString())
                memory = UnifiedMemory(memoryPath)
            }
            val cgroup: VirtualCgroup? =
                VirtualCgroup.create( /* cpu= */
                    null,
                    memory,
                    com.google.common.collect.ImmutableSet.of<E?>(),
                    com.google.devtools.build.lib.sandbox.cgroups.proto.CgroupsInfoProtos.CgroupsInfo
                        .getDefaultInstance()
                )
            pidToCgroups.put(i.toLong(), cgroup)
        }

        val snapshot: ResourceSnapshot =
            CgroupsInfoCollector.instance().collectResourceUsage(pidToCgroups.buildOrThrow(), clock)

        // Results from cgroups 2 should not be in the snapshot since it doesn't exist.
        assertThat(snapshot.pidToMemoryInKb()).containsExactly(1L, 1000, 3L, 3000)
    }
}
