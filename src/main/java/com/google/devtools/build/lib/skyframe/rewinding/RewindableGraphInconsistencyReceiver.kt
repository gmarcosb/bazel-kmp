// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.rewinding

import com.google.devtools.build.skyframe.proto.GraphInconsistency.Inconsistency

/**
 * [GraphInconsistencyReceiver] for evaluations that support action rewinding (`--rewind_lost_inputs`).
 * 
 * 
 * Action rewinding results in various kinds of inconsistencies which this receiver tolerates.
 * The first occurrence of each type of tolerated inconsistency is logged. Stats are collected and
 * available through [.getInconsistencyStats].
 * 
 * 
 * [.reset] should be called between commands to clear stats and reset the [ ][.rewindingInitiated] state used for consistency checks.
 */
class RewindableGraphInconsistencyReceiver(
    private val heuristicallyDropNodes: Boolean,
    private val skymeldInconsistenciesExpected: Boolean
) : GraphInconsistencyReceiver {
    private val selfCounts: com.google.common.collect.Multiset<Inconsistency?> =
        com.google.common.collect.ConcurrentHashMultiset.create<Inconsistency?>()
    private val childCounts: com.google.common.collect.Multiset<Inconsistency?> =
        com.google.common.collect.ConcurrentHashMultiset.create<Inconsistency?>()
    private var rewindingInitiated = false

    override fun noteInconsistencyAndMaybeThrow(
        key: SkyKey?, otherKeys: MutableCollection<SkyKey?>?, inconsistency: Inconsistency
    ) {
        if (heuristicallyDropNodes
            && NodeDroppingInconsistencyReceiver.Companion.isExpectedInconsistency(
                key, otherKeys, inconsistency
            )
        ) {
            // If `--heuristically_drop_nodes` is enabled, check whether the inconsistency is caused by
            // dropped state node. If so, tolerate the inconsistency and return.
            return
        }

        // The following block categorizes inconsistencies that could happen because of rewinding or
        // skymeld, or a combination of both.
        // RESET_REQUESTED and PARENT_FORCE_REBUILD_OF_CHILD may be the first inconsistencies seen with
        // rewinding. BUILDING_PARENT_FOUND_UNDONE_CHILD may also be seen, but it will not be the first.
        // ALREADY_DECLARED_CHILD_MISSING is exclusively skymeld.
        when (inconsistency) {
            RESET_REQUESTED -> {
                com.google.common.base.Preconditions.checkState(
                    RewindingInconsistencyUtils.isTypeThatDependsOnRewindableNodes(key),
                    "Unexpected reset requested for: %s",
                    key
                )
                val isFirst = noteSelfInconsistency(inconsistency)
                if (isFirst) {
                    logger.atInfo().log("Reset requested for: %s", key)
                }
                rewindingInitiated = true
                return
            }

            PARENT_FORCE_REBUILD_OF_CHILD -> {
                val parentMayForceRebuildChildren: Boolean =
                    RewindingInconsistencyUtils.mayForceRebuildChildren(key)
                val unrewindableRebuildChildren: com.google.common.collect.ImmutableList<SkyKey?> =
                    otherKeys.stream()
                        .filter(java.util.function.Predicate.not<SkyKey?>(java.util.function.Predicate { obj: SkyKey? -> RewindingInconsistencyUtils.isRewindable() }))
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<SkyKey?>())
                com.google.common.base.Preconditions.checkState(
                    parentMayForceRebuildChildren && unrewindableRebuildChildren.isEmpty(),
                    "Unexpected force rebuild, parent = %s, children = %s",
                    key,
                    listChildren(if (parentMayForceRebuildChildren) unrewindableRebuildChildren else otherKeys)
                )
                isFirst = noteSelfInconsistency(inconsistency)
                childCounts.add(inconsistency, otherKeys.size())
                if (isFirst) {
                    logger.atInfo().log(
                        "Parent force rebuild of children: parent = %s, children = %s",
                        key, listChildren(otherKeys)
                    )
                }
                rewindingInitiated = true
                return
            }

            BUILDING_PARENT_FOUND_UNDONE_CHILD -> {
                val parentDependsOnRewindableNodes: Boolean =
                    RewindingInconsistencyUtils.isTypeThatDependsOnRewindableNodes(key)
                val unrewindableUndoneChildren: com.google.common.collect.ImmutableList<SkyKey?> =
                    otherKeys.stream()
                        .filter(java.util.function.Predicate.not<SkyKey?>(java.util.function.Predicate { obj: SkyKey? -> RewindingInconsistencyUtils.isRewindable() }))
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<SkyKey?>())

                // The children are not rewindable? Maybe it's a skymeld inconsistency.
                // If it's not, it's an illegal state.
                if (!unrewindableUndoneChildren.isEmpty() && skymeldInconsistenciesExpected
                    && NodeDroppingInconsistencyReceiver.Companion.isExpectedInconsistencySkymeld(
                        key, otherKeys, inconsistency
                    )
                ) {
                    return
                }
                com.google.common.base.Preconditions.checkState(
                    rewindingInitiated
                            && parentDependsOnRewindableNodes
                            && unrewindableUndoneChildren.isEmpty(),
                    "Unexpected undone children: parent = %s, children = %s",
                    key,
                    listChildren(
                        if (rewindingInitiated && parentDependsOnRewindableNodes)
                            unrewindableUndoneChildren
                        else
                            otherKeys
                    )
                )
                isFirst = noteSelfInconsistency(inconsistency)
                childCounts.add(inconsistency, otherKeys.size())
                if (isFirst) {
                    logger.atInfo().log(
                        "Building parent found undone children: parent = %s, children = %s",
                        key, listChildren(otherKeys)
                    )
                }
                return
            }

            ALREADY_DECLARED_CHILD_MISSING ->         // Only expected because of skymeld. This has nothing to do with rewinding.
                if (skymeldInconsistenciesExpected
                    && NodeDroppingInconsistencyReceiver.Companion.isExpectedInconsistencySkymeld(
                        key, otherKeys, inconsistency
                    )
                ) {
                    return
                } else {
                    throw unexpectedInconsistency(key, otherKeys, inconsistency)
                }

            else -> throw unexpectedInconsistency(key, otherKeys, inconsistency)
        }
    }

    /**
     * Notes in [.selfCounts] that an inconsistency occurred and returns true if it was the
     * first one detected.
     */
    private fun noteSelfInconsistency(inconsistency: Inconsistency?): Boolean {
        return selfCounts.add(inconsistency, 1) == 0
    }

    val inconsistencyStats: InconsistencyStats
        get() {
            val builder: InconsistencyStats.Builder = InconsistencyStats.newBuilder()
            addInconsistencyStats(
                selfCounts,
                builder::addSelfStatsBuilder
            )
            addInconsistencyStats(
                childCounts,
                builder::addChildStatsBuilder
            )
            return builder.build()
        }

    override fun reset() {
        selfCounts.clear()
        childCounts.clear()
        rewindingInitiated = false
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private const val LOGGED_CHILDREN_LIMIT = 50

        private fun unexpectedInconsistency(
            key: SkyKey?, otherKeys: MutableCollection<SkyKey?>?, inconsistency: Inconsistency?
        ): java.lang.IllegalStateException {
            return java.lang.IllegalStateException(
                java.lang.String.format(
                    "Unexpected inconsistency %s, key = %s, otherKeys = %s",
                    inconsistency, key, listChildren(otherKeys)
                )
            )
        }

        /**
         * Returns an object suitable for use as a string format arg in precondition checks or logger
         * statements.
         */
        private fun listChildren(children: MutableCollection<SkyKey?>?): Any {
            if (children == null) {
                return "null"
            }
            if (children.size() <= LOGGED_CHILDREN_LIMIT) {
                return children
            }
            return object : Any() {
                override fun toString(): String {
                    return com.google.devtools.build.lib.util.StringUtil.listItemsWithLimit(
                        java.lang.StringBuilder(),
                        LOGGED_CHILDREN_LIMIT,
                        children
                    )
                        .toString()
                }
            }
        }

        private fun addInconsistencyStats(
            inconsistencies: com.google.common.collect.Multiset<Inconsistency?>,
            builderSupplier: java.util.function.Supplier<InconsistencyStat.Builder?>
        ) {
            inconsistencies.forEachEntry(
                ObjIntConsumer { inconsistency: Inconsistency?, count: Int ->
                    builderSupplier.get().setInconsistency(inconsistency).setCount(count)
                })
        }
    }
}
