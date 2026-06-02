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
package com.google.devtools.build.skyframe.state

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.skyframe.SkyFunction.LookupEnvironment
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.SkyframeLookupResult
import com.google.devtools.build.skyframe.state.StateMachine
import com.google.devtools.build.skyframe.state.TaskTreeNode
import java.util.ArrayDeque

/**
 * This class drives a [StateMachine] instance.
 * 
 * 
 * One recommended usage pattern for this class is to embed an instance within a top level [ ] implementation and from there, re-export the [.drive] method. Then the
 * results from the [StateMachine] will be readily retrievable from the [SkyFunction]
 * state.
 */
// TODO(shahan); this is incompatible with partial re-evaluation, which causes the assumption that
// an unavailable previously requested dependency implies an error to no longer be true. This can be
// fixed by integrating with the partial re-evaluation mailbox.
class Driver(root: StateMachine?) {
    private val ready: ArrayDeque<TaskTreeNode?> = ArrayDeque<TaskTreeNode?>()

    /** A Skyframe lookup has not yet been made for the key.  */
    private val newlyAdded: java.util.ArrayList<com.google.devtools.build.skyframe.state.Lookup> =
        java.util.ArrayList<com.google.devtools.build.skyframe.state.Lookup>()

    /** A Skyframe lookup has already been made for the key, but it was not available.  */
    private val pending: java.util.ArrayList<com.google.devtools.build.skyframe.state.Lookup> =
        java.util.ArrayList<com.google.devtools.build.skyframe.state.Lookup>()

    init {
        ready.addLast(TaskTreeNode(this,  /* parent= */null, root))
    }

    /**
     * Drives the machine as far as it can go without a Skyframe restart.
     * 
     * @return true if execution is complete, false if a restart is needed.
     */
    @Throws(java.lang.InterruptedException::class)
    fun drive(env: LookupEnvironment): Boolean {
        if (!pending.isEmpty()) {
            // If pending is non-empty, it means there was a Skyframe restart. Either everything that was
            // pending is available now or we are in error bubbling. In the latter case, this method
            // returns early when it either observes an error or missing value.
            //
            // NB: this assumption does not hold under partial re-evaluation and likewise the inference
            // below about unavailable values being errors.
            val result: SkyframeLookupResult = env.getLookupHandleForPreviouslyRequestedDeps()
            var hasExceptionOrMissingValue = false
            for (lookup in pending) {
                if (!result.queryDep(lookup.key(), lookup)) {
                    // Since the key was previously requested, unavailability here could be an unhandled
                    // exception or a missing value during error bubbling. It's not possible to determine
                    // which here. Requests the key to ensure that if it is an error, the environment
                    // instance knows that the failure is due to child error.
                    val unusedNull: SkyValue? = env.getValue(lookup.key())
                    // Failing fast here would make behavior dependent on element ordering and possibly miss
                    // errors in error bubbling, so instead, flags the exception and fails after all lookups
                    // have been processed.
                    hasExceptionOrMissingValue = true
                }
            }
            if (hasExceptionOrMissingValue) {
                return false
            }
            pending.clear()
        }

        while (true) {
            // Runs all ready tasks, including ones that may be added during execution.
            var next: TaskTreeNode?
            while ((ready.poll().also { next = it }) != null) {
                next.run()
            }

            // No more tasks are ready. If there are no newly added lookups, it isn't possible to drive
            // this machine any further.
            if (newlyAdded.isEmpty()) {
                return pending.isEmpty() // If there are no pending lookups, the machine is done.
            }

            // Performs lookups for any newly added keys.
            if (newlyAdded.size() == 1) { // Uses a lower overhead lookup for the unary case.
                val onlyLookup: com.google.devtools.build.skyframe.state.Lookup = newlyAdded.get(0)
                if (!onlyLookup.doLookup(env)) {
                    pending.add(onlyLookup)
                }
            } else {
                val result: SkyframeLookupResult =
                    env.getValuesAndExceptions(
                        com.google.common.collect.Lists.transform<com.google.devtools.build.skyframe.state.Lookup?, SkyKey?>(
                            newlyAdded,
                            com.google.common.base.Function { obj: com.google.devtools.build.skyframe.state.Lookup? -> obj.key() })
                    )
                for (lookup in newlyAdded) {
                    if (!result.queryDep(lookup.key(), lookup)) {
                        pending.add(lookup) // Unhandled exceptions also end up here.
                    }
                }
            }
            newlyAdded.clear() // Every entry is either done or has moved to pending.
        }
    }

    fun addReady(task: TaskTreeNode?) {
        ready.addLast(task)
    }

    /**
     * Adds a dependency to look up.
     * 
     * 
     * The callback could be deferred until the next Skyframe restart if the queried key is not
     * immediately available.
     */
    fun addLookup(lookup: com.google.devtools.build.skyframe.state.Lookup?) {
        newlyAdded.add(lookup)
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("ready", ready)
            .add("newlyAdded", newlyAdded)
            .add("pending", pending)
            .toString()
    }
}
