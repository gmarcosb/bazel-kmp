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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.actions.ActionResult

/** Testing SpawnStats  */
@RunWith(JUnit4::class)
class SpawnStatsTest {
    var stats: SpawnStats? = null

    @Before
    fun setUp() {
        stats = SpawnStats()
    }

    @org.junit.Test
    fun emptySet() {
        assertThat(SpawnStats.convertSummaryToString(stats.getSummary())).isEqualTo("0 processes.")
    }

    @org.junit.Test
    fun one() {
        val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
        spawns.add(
            Builder()
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("foo")
                .build()
        )
        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()
        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("1 process: 1 foo.")
    }

    @org.junit.Test
    fun oneRemote() {
        val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
        spawns.add(
            Builder()
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("remote cache hit")
                .build()
        )
        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()
        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("1 process: 1 remote cache hit.")
    }

    @org.junit.Test
    fun two() {
        for (i in 0..1) {
            val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
            spawns.add(
                Builder()
                    .setStatus(SpawnResult.Status.SUCCESS)
                    .setRunnerName("foo")
                    .build()
            )
            stats.countActionResult(ActionResult.create(spawns))
            stats.incrementActionCount()
        }
        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("2 processes: 2 foo.")
    }

    @org.junit.Test
    fun order() {
        var spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
        spawns.add(
            Builder().setStatus(SpawnResult.Status.SUCCESS).setRunnerName("a").build()
        )
        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()

        for (i in 0..1) {
            spawns = java.util.ArrayList<SpawnResult?>()
            spawns.add(
                Builder()
                    .setStatus(SpawnResult.Status.SUCCESS)
                    .setRunnerName("b")
                    .build()
            )
            stats.countActionResult(ActionResult.create(spawns))
            stats.incrementActionCount()
        }

        for (i in 0..2) {
            spawns = java.util.ArrayList<SpawnResult?>()
            spawns.add(
                Builder()
                    .setStatus(SpawnResult.Status.SUCCESS)
                    .setRunnerName("c")
                    .build()
            )
            stats.countActionResult(ActionResult.create(spawns))
            stats.incrementActionCount()
        }
        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("6 processes: 1 a, 2 b, 3 c.")
    }

    @org.junit.Test
    fun reverseOrder() {
        for (i in 0..2) {
            val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
            spawns.add(
                Builder()
                    .setStatus(SpawnResult.Status.SUCCESS)
                    .setRunnerName("a")
                    .build()
            )
            stats.countActionResult(ActionResult.create(spawns))
            stats.incrementActionCount()
        }

        for (i in 0..1) {
            val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
            spawns.add(
                Builder()
                    .setStatus(SpawnResult.Status.SUCCESS)
                    .setRunnerName("b")
                    .build()
            )
            stats.countActionResult(ActionResult.create(spawns))
            stats.incrementActionCount()
        }

        val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
        spawns.add(
            Builder().setStatus(SpawnResult.Status.SUCCESS).setRunnerName("c").build()
        )
        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()

        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("6 processes: 3 a, 2 b, 1 c.")
    }

    @org.junit.Test
    fun cacheFirst() {
        for (i in 0..2) {
            val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
            spawns.add(
                Builder()
                    .setStatus(SpawnResult.Status.SUCCESS)
                    .setRunnerName("a")
                    .build()
            )
            stats.countActionResult(ActionResult.create(spawns))
            stats.incrementActionCount()
        }

        for (i in 0..1) {
            val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
            spawns.add(
                Builder()
                    .setStatus(SpawnResult.Status.SUCCESS)
                    .setRunnerName("b")
                    .build()
            )
            stats.countActionResult(ActionResult.create(spawns))
            stats.incrementActionCount()
        }

        var spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
        spawns.add(
            Builder()
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("remote cache hit")
                .build()
        )
        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()

        spawns = java.util.ArrayList<SpawnResult?>()
        spawns.add(
            Builder().setStatus(SpawnResult.Status.SUCCESS).setRunnerName("c").build()
        )
        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()

        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("7 processes: 1 remote cache hit, 3 a, 2 b, 1 c.")
    }

    private val rA: SpawnResult? = Builder().setStatus(SpawnResult.Status.SUCCESS).setRunnerName("abc").build()
    private val rB: SpawnResult? = Builder().setStatus(SpawnResult.Status.SUCCESS).setRunnerName("cde").build()

    @org.junit.Test
    fun actionOneSpawn() {
        val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
        spawns.add(rA)

        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()
        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("1 process: 1 abc.")
    }

    @org.junit.Test
    fun actionManySpawn() {
        // One action with multiple spawns - should count as 1 action with 3 spawns

        val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
        spawns.add(rA)
        spawns.add(rA)
        spawns.add(rA)

        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()
        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("1 process: 3 abc.")
    }

    @org.junit.Test
    fun actionManySpawnMixed() {
        // One action with multiple spawns of different runners

        val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
        spawns.add(rA)
        spawns.add(rA)
        spawns.add(rB)

        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()
        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("1 process: 2 abc, 1 cde.")
    }

    @org.junit.Test
    fun actionManyActionsMixed() {
        // Five actions:
        // Action 1: 1 spawn (abc)
        // Action 2: 2 spawns (abc, abc)
        // Action 3: 3 spawns (abc, abc, cde)
        // Action 4: 3 spawns (abc, abc, cde)
        // Action 5: 3 spawns (abc, abc, cde)

        var spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
        spawns.add(rA)
        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()

        spawns = java.util.ArrayList<SpawnResult?>()
        spawns.add(rA)
        spawns.add(rA)
        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()

        spawns = java.util.ArrayList<SpawnResult?>()
        spawns.add(rA)
        spawns.add(rA)
        spawns.add(rB)
        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()
        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()
        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()

        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("5 processes: 9 abc, 3 cde.")
    }

    @org.junit.Test
    fun onlyInternal() {
        stats.incrementActionCount()
        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("1 process: 1 internal.")
    }

    @org.junit.Test
    fun orderCacheInternalRest() {
        for (i in 0..2) {
            val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
            spawns.add(
                Builder()
                    .setStatus(SpawnResult.Status.SUCCESS)
                    .setRunnerName("a")
                    .build()
            )
            stats.countActionResult(ActionResult.create(spawns))
            stats.incrementActionCount()
        }

        for (i in 0..1) {
            val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
            spawns.add(
                Builder()
                    .setStatus(SpawnResult.Status.SUCCESS)
                    .setRunnerName("b")
                    .build()
            )
            stats.countActionResult(ActionResult.create(spawns))
            stats.incrementActionCount()
        }

        var spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
        spawns.add(
            Builder()
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("remote cache hit")
                .build()
        )
        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()

        spawns = java.util.ArrayList<SpawnResult?>()
        spawns.add(
            Builder().setStatus(SpawnResult.Status.SUCCESS).setRunnerName("c").build()
        )
        stats.countActionResult(ActionResult.create(spawns))
        stats.incrementActionCount()

        for (i in 0..1) {
            spawns = java.util.ArrayList<SpawnResult?>()
            spawns.add(
                Builder()
                    .setStatus(SpawnResult.Status.SUCCESS)
                    .setRunnerName("z")
                    .build()
            )
            stats.countActionResult(ActionResult.create(spawns))
            stats.incrementActionCount()
        }

        // Add 2 internal actions (no spawns)
        for (i in 0..1) {
            stats.incrementActionCount()
        }

        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("11 processes: 1 remote cache hit, 2 internal, 3 a, 2 b, 1 c, 2 z.")
    }

    private val rC: SpawnResult? = Builder()
        .setStatus(SpawnResult.Status.SUCCESS)
        .setSpawnMetrics(SpawnMetrics.Builder.forExec(SpawnMetrics.ExecKind.OTHER).build())
        .setRunnerName("fgh")
        .build()

    @get:org.junit.Test
    val execKindDefined: Unit
        get() {
            val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
            spawns.add(rC)
            stats.countActionResult(ActionResult.create(spawns))
            assertThat(stats.getExecKindFor("fgh")).isEqualTo(SpawnMetrics.ExecKind.OTHER.toString())
        }

    @get:org.junit.Test
    val execKindNotDefined: Unit
        get() {
            val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                stats.getSummary()
            assertThat(stats.getExecKindFor("total")).isNull()
            assertThat(stats.getExecKindFor("internal")).isNull()
        }

    @org.junit.Test
    fun internalCountWithMultipleSpawnsPerAction() {
        // Action 1: 3 spawns (counts as 1 non-internal action)
        val spawnsA: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
        spawnsA.add(rA)
        spawnsA.add(rA)
        spawnsA.add(rA)
        stats.countActionResult(ActionResult.create(spawnsA))
        stats.incrementActionCount()

        // Action 2: 2 spawns (counts as 1 non-internal action)
        val spawnsB: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
        spawnsB.add(rB)
        spawnsB.add(rB)
        stats.countActionResult(ActionResult.create(spawnsB))
        stats.incrementActionCount()

        // Action 3: internal action (no spawns)
        stats.incrementActionCount()

        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("3 processes: 1 internal, 3 abc, 2 cde.")
    }

    @org.junit.Test
    fun largeNumbersFormattedWithCommas() {
        // Verify that large counts (>= 10,000 per IEEE style) are formatted with comma separators.
        val spawn: SpawnResult? =
            Builder()
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("darwin-sandbox")
                .build()

        for (i in 0..12344) {
            val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
            spawns.add(spawn)
            stats.countActionResult(ActionResult.create(spawns))
            stats.incrementActionCount()
        }

        for (i in 0..11233) {
            stats.incrementActionCount()
        }

        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("23,579 processes: 11,234 internal, 12,345 darwin-sandbox.")
    }

    @org.junit.Test
    fun smallNumbersNotFormattedWithCommas() {
        // Verify that counts below 10,000 (IEEE style threshold) are NOT formatted with commas.
        val spawn: SpawnResult? =
            Builder()
                .setStatus(SpawnResult.Status.SUCCESS)
                .setRunnerName("darwin-sandbox")
                .build()

        for (i in 0..2344) {
            val spawns: java.util.ArrayList<SpawnResult?> = java.util.ArrayList<SpawnResult?>()
            spawns.add(spawn)
            stats.countActionResult(ActionResult.create(spawns))
            stats.incrementActionCount()
        }

        for (i in 0..1233) {
            stats.incrementActionCount()
        }

        assertThat(SpawnStats.convertSummaryToString(stats.getSummary()))
            .isEqualTo("3579 processes: 1234 internal, 2345 darwin-sandbox.")
    }
}
