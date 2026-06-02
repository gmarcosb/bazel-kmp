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

import com.google.devtools.build.lib.events.Event

/**
 * An utility for custom reporting of errors from cycles in the Skyframe graph. This class is
 * stateful in order to differentiate between new cycles and cycles that have already been reported
 * (do not reuse the instances or cache the results as it could end up printing inconsistent
 * information or leak memory). It treats two cycles as the same if they contain the same [ ]s in the same order, but perhaps with different starting points. See [CycleDeduper]
 * for more information.
 */
class CyclesReporter(vararg cycleReporters: SingleCycleReporter?) {
    /**
     * Interface for reporting custom information about a single cycle.
     */
    interface SingleCycleReporter {
        /**
         * Reports the given cycle and returns `true`, or return `false` if this [ ] doesn't know how to report the cycle.
         * 
         * @param topLevelKey the top level key that transitively depended on the cycle
         * @param cycleInfo the cycle
         * @param alreadyReported whether the cycle has already been reported to the [     ].
         * @param eventHandler the eventHandler to which to report the error
         */
        fun maybeReportCycle(
            topLevelKey: SkyKey?,
            cycleInfo: CycleInfo?,
            alreadyReported: Boolean,
            eventHandler: ExtendedEventHandler?
        ): Boolean
    }

    private val cycleReporters: com.google.common.collect.ImmutableList<SingleCycleReporter>
    private val cycleDeduper: CycleDeduper<SkyKey?> = CycleDeduper<SkyKey?>()

    /**
     * Constructs a [CyclesReporter] that delegates to the given [SingleCycleReporter]s,
     * in the given order, to report custom information about cycles.
     */
    init {
        this.cycleReporters = com.google.common.collect.ImmutableList.copyOf<SingleCycleReporter?>(cycleReporters)
    }

    /**
     * Reports the given cycles, differentiating between cycles that have already been reported.
     * 
     * @param cycles The `Iterable` of cycles.
     * @param topLevelKey This key represents the top level value key that returned cycle errors.
     * @param eventHandler the eventHandler to which to report the error
     */
    fun reportCycles(
        cycles: Iterable<CycleInfo>, topLevelKey: SkyKey?, eventHandler: ExtendedEventHandler?
    ) {
        com.google.common.base.Preconditions.checkNotNull<Any?>(
            eventHandler,
            "topLevelKey: %s, Cycles %s",
            topLevelKey,
            cycles
        )
        var suppressedCycles = false
        var firstCycle = true
        for (cycleInfo in cycles) {
            // TODO(janakr): if this assertion is never hit, remove topLevelKey as an argument to method.
            if (cycleInfo.getTopKey() != topLevelKey) {
                BugReport.sendNonFatalBugReport(
                    java.lang.IllegalStateException("Cycle " + cycleInfo + " did not start with " + topLevelKey)
                )
            }
            suppressedCycles = suppressedCycles or !maybeReportCycle(cycleInfo, topLevelKey, firstCycle, eventHandler)
            firstCycle = false
        }
        if (suppressedCycles) {
            logger.atInfo().log(
                "Some cycles were omitted for %s because they were already reported", topLevelKey
            )
        }
    }

    /** Returns true if it reported the cycle.  */
    private fun maybeReportCycle(
        cycleInfo: CycleInfo,
        topLevelKey: SkyKey?,
        firstCycle: Boolean,
        eventHandler: ExtendedEventHandler
    ): Boolean {
        val alreadyReported: Boolean = cycleDeduper.alreadySeen(cycleInfo.getCycle())
        if (!firstCycle && alreadyReported) {
            // We've already reported this top-level key and this cycle, although maybe not together.
            // Enumerating all combinations of top-level keys and cycles is too spammy for the user.
            return false
        }
        for (cycleReporter in cycleReporters) {
            if (cycleReporter.maybeReportCycle(topLevelKey, cycleInfo, alreadyReported, eventHandler)) {
                return true
            }
        }

        // No proper cycle reporter could be found. Blaze bug! Not fatal, though.
        val rawCycle = printArbitraryCycle(topLevelKey, cycleInfo, alreadyReported)
        eventHandler.handle(
            Event.error(
                ("Cycle detected but could not be properly displayed due to an internal problem. Please"
                        + " file an issue. Raw display: "
                        + rawCycle)
            )
        )
        BugReport.sendNonFatalBugReport(java.lang.IllegalStateException(rawCycle + "\n" + cycleReporters))
        return true
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun printArbitraryCycle(
            topLevelKey: SkyKey?, cycleInfo: CycleInfo, alreadyReported: Boolean
        ): String {
            val cycleMessage: java.lang.StringBuilder =
                java.lang.StringBuilder()
                    .append("topLevelKey: ")
                    .append(topLevelKey)
                    .append("\n")
                    .append("alreadyReported: ")
                    .append(alreadyReported)
                    .append("\n")
                    .append("path to cycle:\n")
            for (skyKey in cycleInfo.getPathToCycle()) {
                cycleMessage.append(skyKey).append("\n")
            }
            cycleMessage.append("cycle:\n")
            for (skyKey in cycleInfo.getCycle()) {
                cycleMessage.append(skyKey).append("\n")
            }
            return cycleMessage.toString()
        }
    }
}
