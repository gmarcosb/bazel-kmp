// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [ActionResult].  */
@RunWith(JUnit4::class)
class ActionResultTest {
    @org.junit.Test
    fun testCumulativeCommandExecutionTime_noSpawnResults() {
        val spawnResults: MutableList<SpawnResult?> = com.google.common.collect.ImmutableList.of<SpawnResult?>()
        val actionResult: ActionResult = ActionResult.create(spawnResults)
        assertThat(actionResult.cumulativeCommandExecutionWallTimeInMs()).isEqualTo(0)
        assertThat(actionResult.cumulativeCommandExecutionCpuTimeInMs()).isEqualTo(0)
        assertThat(actionResult.cumulativeCommandExecutionUserTimeInMs()).isEqualTo(0)
        assertThat(actionResult.cumulativeCommandExecutionSystemTimeInMs()).isEqualTo(0)
        assertThat(actionResult.cumulativeCommandExecutionBlockOutputOperations()).isNull()
        assertThat(actionResult.cumulativeCommandExecutionBlockInputOperations()).isNull()
        assertThat(actionResult.cumulativeCommandExecutionInvoluntaryContextSwitches()).isNull()
    }

    @org.junit.Test
    fun testCumulativeCommandExecutionTime_oneSpawnResult() {
        val spawnResult: SpawnResult =
            Builder()
                .setWallTimeInMs(1984)
                .setUserTimeInMs(225)
                .setSystemTimeInMs(42)
                .setNumBlockOutputOperations(10)
                .setNumBlockInputOperations(20)
                .setNumInvoluntaryContextSwitches(30)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()
        val spawnResults: MutableList<SpawnResult?> =
            com.google.common.collect.ImmutableList.of<SpawnResult?>(spawnResult)
        val actionResult: ActionResult = ActionResult.create(spawnResults)

        assertThat(actionResult.cumulativeCommandExecutionWallTimeInMs()).isEqualTo(1984)
        assertThat(actionResult.cumulativeCommandExecutionCpuTimeInMs()).isEqualTo(267)
        assertThat(actionResult.cumulativeCommandExecutionUserTimeInMs()).isEqualTo(225)

        assertThat(actionResult.cumulativeCommandExecutionSystemTimeInMs()).isEqualTo(42)
        assertThat(actionResult.cumulativeCommandExecutionBlockOutputOperations()).isEqualTo(10)
        assertThat(actionResult.cumulativeCommandExecutionBlockInputOperations()).isEqualTo(20)
        assertThat(actionResult.cumulativeCommandExecutionInvoluntaryContextSwitches()).isEqualTo(30)
    }

    @org.junit.Test
    fun testCumulativeCommandExecutionTime_manySpawnResults() {
        val spawnResult1: SpawnResult? =
            Builder()
                .setWallTimeInMs(1979)
                .setUserTimeInMs(1)
                .setSystemTimeInMs(33)
                .setNumBlockOutputOperations(10)
                .setNumBlockInputOperations(20)
                .setNumInvoluntaryContextSwitches(30)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()
        val spawnResult2: SpawnResult? =
            Builder()
                .setWallTimeInMs(4)
                .setUserTimeInMs(1)
                .setSystemTimeInMs(7)
                .setNumBlockOutputOperations(100)
                .setNumBlockInputOperations(200)
                .setNumInvoluntaryContextSwitches(300)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()
        val spawnResult3: SpawnResult? =
            Builder()
                .setWallTimeInMs(1)
                .setUserTimeInMs(2)
                .setSystemTimeInMs(2)
                .setNumBlockOutputOperations(1000)
                .setNumBlockInputOperations(2000)
                .setNumInvoluntaryContextSwitches(3000)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()
        val spawnResults: MutableList<SpawnResult?> =
            com.google.common.collect.ImmutableList.of<SpawnResult?>(spawnResult1, spawnResult2, spawnResult3)
        val actionResult: ActionResult = ActionResult.create(spawnResults)

        assertThat(actionResult.cumulativeCommandExecutionWallTimeInMs()).isEqualTo(1984L)
        assertThat(actionResult.cumulativeCommandExecutionCpuTimeInMs()).isEqualTo(46L)
        assertThat(actionResult.cumulativeCommandExecutionUserTimeInMs()).isEqualTo(4L)

        assertThat(actionResult.cumulativeCommandExecutionSystemTimeInMs()).isEqualTo(42L)
        assertThat(actionResult.cumulativeCommandExecutionBlockOutputOperations()).isEqualTo(1110L)
        assertThat(actionResult.cumulativeCommandExecutionBlockInputOperations()).isEqualTo(2220L)

        assertThat(actionResult.cumulativeCommandExecutionInvoluntaryContextSwitches())
            .isEqualTo(3330L)
    }

    @org.junit.Test
    fun testCumulativeCommandExecutionTime_manyEmptySpawnResults() {
        val spawnResult1: SpawnResult? =
            Builder()
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()
        val spawnResult2: SpawnResult? =
            Builder()
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()
        val spawnResult3: SpawnResult? =
            Builder()
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()
        val spawnResults: MutableList<SpawnResult?> =
            com.google.common.collect.ImmutableList.of<SpawnResult?>(spawnResult1, spawnResult2, spawnResult3)
        val actionResult: ActionResult = ActionResult.create(spawnResults)
        assertThat(actionResult.cumulativeCommandExecutionWallTimeInMs()).isEqualTo(0)
        assertThat(actionResult.cumulativeCommandExecutionCpuTimeInMs()).isEqualTo(0)
        assertThat(actionResult.cumulativeCommandExecutionUserTimeInMs()).isEqualTo(0)
        assertThat(actionResult.cumulativeCommandExecutionSystemTimeInMs()).isEqualTo(0)
        assertThat(actionResult.cumulativeCommandExecutionBlockOutputOperations()).isNull()
        assertThat(actionResult.cumulativeCommandExecutionBlockInputOperations()).isNull()
        assertThat(actionResult.cumulativeCommandExecutionInvoluntaryContextSwitches()).isNull()
    }

    @org.junit.Test
    fun testCumulativeCommandExecutionTime_manySpawnResults_butOnlyUserTime() {
        val spawnResult1: SpawnResult? =
            Builder()
                .setUserTimeInMs(2)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()
        val spawnResult2: SpawnResult? =
            Builder()
                .setUserTimeInMs(3)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()
        val spawnResult3: SpawnResult? =
            Builder()
                .setUserTimeInMs(4)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()
        val spawnResults: MutableList<SpawnResult?> =
            com.google.common.collect.ImmutableList.of<SpawnResult?>(spawnResult1, spawnResult2, spawnResult3)
        val actionResult: ActionResult = ActionResult.create(spawnResults)
        assertThat(actionResult.cumulativeCommandExecutionCpuTimeInMs()).isEqualTo(9L)
        assertThat(actionResult.cumulativeCommandExecutionUserTimeInMs()).isEqualTo(9L)
    }

    @org.junit.Test
    fun testCumulativeCommandExecutionTime_manySpawnResults_butOnlySystemTime() {
        val spawnResult1: SpawnResult? =
            Builder()
                .setSystemTimeInMs(33)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()
        val spawnResult2: SpawnResult? =
            Builder()
                .setSystemTimeInMs(7)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()
        val spawnResult3: SpawnResult? =
            Builder()
                .setSystemTimeInMs(2)
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("test")
                .build()
        val spawnResults: MutableList<SpawnResult?> =
            com.google.common.collect.ImmutableList.of<SpawnResult?>(spawnResult1, spawnResult2, spawnResult3)
        val actionResult: ActionResult = ActionResult.create(spawnResults)
        assertThat(actionResult.cumulativeCommandExecutionCpuTimeInMs()).isEqualTo(42L)

        assertThat(actionResult.cumulativeCommandExecutionSystemTimeInMs()).isEqualTo(42L)
    }
}
