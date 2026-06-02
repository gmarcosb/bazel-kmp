// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.collect.nestedset.NestedSetVisitor.VisitedState

/**
 * Keeps track of visited nodes for [ events][com.google.devtools.build.lib.events.Reportable] stored in a [com.google.devtools.build.lib.collect.nestedset.NestedSet].
 * 
 * 
 * Also tracks warnings for purposes of deduplication, since those are not [ ][Event.storeForReplay].
 */
class EmittedEventState {
    private val seenEvents: MutableSet<Reportable?> = com.google.common.collect.Sets.newConcurrentHashSet<Reportable?>()
    private val seenWarnings: MutableSet<Event?> = com.google.common.collect.Sets.newConcurrentHashSet<Event?>()
    private var visitedState: VisitedState<Reportable?>? = VisitedState.createConcurrent(seenEvents::add)

    /** Clears the seen nodes and warnings.  */
    fun clear() {
        seenEvents.clear()
        seenWarnings.clear()
        visitedState = VisitedState.createConcurrent(seenEvents::add)
    }

    fun asVisitedState(): VisitedState<Reportable?>? {
        return visitedState
    }

    /** Returns `true` if the given warning was not seen before.  */
    fun addWarning(warning: Event): Boolean {
        com.google.common.base.Preconditions.checkArgument(
            warning.getKind() === EventKind.WARNING,
            "Not a warning: %s",
            warning
        )
        return seenWarnings.add(warning)
    }
}
