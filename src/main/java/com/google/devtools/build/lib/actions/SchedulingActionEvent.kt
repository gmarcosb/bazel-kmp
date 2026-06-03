// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable

/**
 * Notifies that an in-flight action is being scheduled.
 * 
 * 
 * This event should only appear in-between corresponding [ActionStartedEvent] and [ ] events, and should only appear after a corresponding [ ]. TODO(jmmv): But this theory is not true today. Investigate.
 */
class SchedulingActionEvent(action: ActionExecutionMetadata?, strategy: String?) : Postable {
    private val action: ActionExecutionMetadata?
    private val strategy: String

    /** Constructs a new event.  */
    init {
        this.action = action
        this.strategy =
            com.google.common.base.Preconditions.checkNotNull<String>(strategy, "Strategy names are not optional")
    }

    /** Gets the metadata associated with the action being scheduled.  */
    fun getActionMetadata(): ActionExecutionMetadata? {
        return action
    }

    /** Gets the name of the strategy on which the action is scheduling.  */
    fun getStrategy(): String {
        return strategy
    }
}
