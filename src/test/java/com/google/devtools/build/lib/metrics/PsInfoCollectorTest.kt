// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.clock.BlazeClock.instance
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem.getInputStream
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito
import java.io.ByteArrayInputStream

@RunWith(JUnit4::class)
class PsInfoCollectorTest {
    private val spyCollector: PsInfoCollector = spy(PsInfoCollector.instance())

    @org.junit.Test
    fun testCollectStats_ignoreSpaces() {
        val psOutput = "    PID  \t  PPID \t  RSS\n   2 1 3216 \t\n  \t 3 1 \t 4096 \t"
        val psStream: java.io.InputStream =
            ByteArrayInputStream(psOutput.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val process: java.lang.Process = Mockito.mock<java.lang.Process>(java.lang.Process::class.java)
        Mockito.`when`<java.io.InputStream?>(process.getInputStream()).thenReturn(psStream)

        val pidToPsInfo: com.google.common.collect.ImmutableMap<Long?, PsInfoCollector.PsInfo?>? =
            PsInfoCollector.collectDataFromPsProcess(process)

        val expectedPidToPsInfo: com.google.common.collect.ImmutableMap<Long?, PsInfoCollector.PsInfo?> =
            com.google.common.collect.ImmutableMap.of<Long?, PsInfoCollector.PsInfo?>(
                2L, PsInfo(2, 1, 3216), 3L, PsInfo(3, 1, 4096)
            )
        Truth.assertThat(pidToPsInfo).isEqualTo(expectedPidToPsInfo)
    }

    @org.junit.Test
    fun testCollectStats_multipleSubprocesses() {
        val clock: com.google.devtools.build.lib.clock.Clock = com.google.devtools.build.lib.clock.BlazeClock.instance()

        // pstree of these processes
        // 0-+-1---3-+-7
        //   |       `-8
        //   |-2-+-4
        //   |   `-9
        //   |-5
        //   `-10

        // ps command results:
        // PID PPID RSS
        // 1   0    3216
        // 2   0    4232
        // 3   1    1234
        // 4   2    1001
        // 5   0    40000
        // 7   3    2345
        // 8   3    3456
        // 9   2    1032
        // 10  0    1024
        val psInfos: com.google.common.collect.ImmutableMap<Long?, PsInfoCollector.PsInfo?> =
            com.google.common.collect.ImmutableMap.of<Long?, PsInfoCollector.PsInfo?>(
                1L, PsInfo(1, 0, 3216),
                2L, PsInfo(2, 0, 4232),
                3L, PsInfo(3, 1, 1234),
                4L, PsInfo(4, 2, 1001),
                5L, PsInfo(5, 0, 40000),
                7L, PsInfo(7, 3, 2345),
                8L, PsInfo(8, 3, 3456),
                9L, PsInfo(9, 2, 1032),
                10L, PsInfo(10, 0, 1024)
            )
        val pids: com.google.common.collect.ImmutableSet<Long?> =
            com.google.common.collect.ImmutableSet.of<Long?>(1L, 2L, 5L, 6L)
        Mockito.`when`<T?>(spyCollector.collectDataFromPs()).thenReturn(psInfos)

        val resourceSnapshot: ResourceSnapshot = spyCollector.collectResourceUsage(pids, clock)

        val expectedMemoryUsageByPid: com.google.common.collect.ImmutableMap<Long?, Int?> =
            com.google.common.collect.ImmutableMap.of<Long?, Int?>(
                1L,
                3216 + 1234 + 2345 + 3456,
                2L,
                4232 + 1001 + 1032,
                5L,
                40000
            )
        assertThat(resourceSnapshot.pidToMemoryInKb()).isEqualTo(expectedMemoryUsageByPid)
    }
}
