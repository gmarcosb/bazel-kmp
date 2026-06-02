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
package com.google.devtools.build.lib.skyframe.rewinding

import com.google.devtools.build.lib.actions.Action

/**
 * This event is fired during the build for an action that failed because of lost inputs, and that
 * will try to recover through rewinding (see [ActionRewindStrategy]).
 */
class ActionRewoundEvent(
    /** Returns a nanotime taken before action execution began.  */
    val relativeActionStartTimeNanos: Long,
    /** Returns a nanotime taken after action execution finished.  */
    val relativeActionFinishTimeNanos: Long,
    failedRewoundAction: Action?
) : Postable {
    private val failedRewoundAction: Action?

    /**
     * Create an event for action that that failed because of lost inputs, and that will try to
     * recover through rewinding.
     * 
     * @param relativeActionStartTime a nanotime taken before action execution began
     * @param failedRewoundAction the failed action.
     */
    init {
        this.failedRewoundAction = failedRewoundAction
    }

    /** Returns the associated action.  */
    fun getFailedRewoundAction(): Action? {
        return failedRewoundAction
    }
}
