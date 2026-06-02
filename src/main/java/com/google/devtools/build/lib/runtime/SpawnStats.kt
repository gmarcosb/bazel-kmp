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

/** Collects results from SpawnResult.  */
@javax.annotation.concurrent.ThreadSafe
class SpawnStats {
    private val runners: com.google.common.collect.ConcurrentHashMultiset<String?> =
        com.google.common.collect.ConcurrentHashMultiset.create<String?>()
    private val runnerExecKinds: ConcurrentHashMap<String?, String?> = ConcurrentHashMap<String?, String?>()
    private val totalWallTimeMillis: AtomicLong = AtomicLong()

    // Counts all actions, where an action can run an arbitrary number of spawns (including zero).
    private val allActionsCount: AtomicInteger = AtomicInteger()

    // Counts internal actions, such as SymlinkTree actions, which are recognized by having zero
    // spawns.
    private val nonInternalActionsCount: AtomicInteger = AtomicInteger()
    private var actionCacheHitCount = 0

    fun countActionResult(actionResult: ActionResult) {
        // This method is usually not called for internal actions with {@link ActionResult#EMPTY}, but
        // just in case, we double-check here.
        if (!actionResult.spawnResults().isEmpty()) {
            nonInternalActionsCount.incrementAndGet()
        }
        for (r in actionResult.spawnResults()) {
            storeRunnerExecKind(r)
            runners.add(r.getRunnerName())
            totalWallTimeMillis.addAndGet(r.getMetrics().executionWallTimeInMs())
        }
    }

    private fun storeRunnerExecKind(r: SpawnResult) {
        val name: String? = r.getRunnerName()
        val execKind: String? = r.getMetrics().execKind().toString()
        runnerExecKinds.put(name, execKind)
    }

    fun incrementActionCount() {
        allActionsCount.incrementAndGet()
    }

    fun getTotalWallTimeMillis(): Long {
        return totalWallTimeMillis.get()
    }

    fun recordActionCacheStats(actionCacheStatistics: ActionCacheStatistics) {
        actionCacheHitCount = actionCacheStatistics.getHits()
    }

    val summary: com.google.common.collect.ImmutableMap<String?, Int?>
        /*
            * Returns a human-readable summary of spawns counted.
            */
        get() = getSummary(REPORT_FIRST)

    /*
   * Returns a human-readable summary of spawns counted.
   */
    fun getSummary(reportFirst: com.google.common.collect.ImmutableList<String>): com.google.common.collect.ImmutableMap<String?, Int?> {
        val result: com.google.common.collect.ImmutableMap.Builder<String?, Int?> =
            com.google.common.collect.ImmutableMap.builder<String?, Int?>()
        val numNonInternalActions: Int = nonInternalActionsCount.get()
        val numAllActions: Int = allActionsCount.get()
        result.put("total", numAllActions)

        // First report cache results.
        if (actionCacheHitCount > 0) {
            result.put("action cache hit", actionCacheHitCount)
        }
        for (s in reportFirst) {
            val count: Int = runners.setCount(s, 0)
            if (count > 0) {
                result.put(s, count)
            }
        }

        // Account for internal actions such as SymlinkTree.
        // This condition is always fulfilled if {@link #incrementActionCount} is called for each
        // action for which {@link #countActionResult} is called eventually.
        if (numNonInternalActions < numAllActions) {
            result.put("internal", numAllActions - numNonInternalActions)
        }

        // Sort the rest alphabetically
        val list: java.util.ArrayList<com.google.common.collect.Multiset.Entry<String?>> =
            java.util.ArrayList<com.google.common.collect.Multiset.Entry<String?>>(runners.entrySet())
        Collections.sort<com.google.common.collect.Multiset.Entry<String?>?>(
            list,
            java.util.Comparator.comparing<com.google.common.collect.Multiset.Entry<String?>?, String?>(java.util.function.Function { e: com.google.common.collect.Multiset.Entry<kotlin.String?>? -> e.getElement() })
        )

        for (e in list) {
            result.put(e.getElement(), e.getCount())
        }

        return result.buildOrThrow()
    }

    fun getExecKindFor(runnerName: String?): String? {
        return runnerExecKinds.getOrDefault(runnerName, null)
    }

    companion object {
        private val REPORT_FIRST: com.google.common.collect.ImmutableList<String> =
            com.google.common.collect.ImmutableList.of<String?>("disk cache hit", "remote cache hit")

        fun convertSummaryToString(spawnSummary: com.google.common.collect.ImmutableMap<String?, Int?>): String {
            // This summary is misleading in a number of ways:
            // - The "processes" count is actually the number of actions, not spawns.
            // - Even if it were the number of spawns, the "* cache hit" runners do not correspond to
            //   processes executed, but rather to cache hits that avoided process execution.
            // - The total count does not include action cache hits, so the sum of the parts is greater than
            //   the total.
            // TODO: Find a better way to report this information, e.g.:
            // 15 cache hits (5 action, 5 disk, 5 remote), 10 processes (2 local, 3 remote, 5 sandboxed), 7
            // internal.
            // A large number of integration tests rely on the current format, though, so changing it is
            // non-trivial.
            val total: Int? = spawnSummary.get("total")
            if (total == 0) {
                return "0 processes."
            }

            val stringSummary: java.lang.StringBuilder = java.lang.StringBuilder()
            stringSummary.append(com.google.devtools.build.lib.util.StringUtil.formatCount(total!!.toLong()))
                .append(" process")
            if (total > 1) {
                stringSummary.append("es")
            }
            var separator = ": "

            for (runnerStats in spawnSummary.entrySet()) {
                if ("total" == runnerStats.getKey()) {
                    continue
                }
                stringSummary.append(separator)
                separator = ", "
                stringSummary
                    .append(com.google.devtools.build.lib.util.StringUtil.formatCount(runnerStats.getValue().toLong()))
                    .append(' ')
                    .append(runnerStats.getKey())
            }
            stringSummary.append('.')
            return stringSummary.toString()
        }
    }
}
