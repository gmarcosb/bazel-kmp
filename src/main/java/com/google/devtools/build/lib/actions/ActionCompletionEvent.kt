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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable

/** An event that is fired after an action completes (either successfully or not).  */
class ActionCompletionEvent(
    private val relativeActionStartTimeNanos: Long,
    private val finishTimeNanos: Long,
    action: com.google.devtools.build.lib.actions.Action?,
    inputMetadataProvider: InputMetadataProvider?,
    outputMetadataStore: OutputMetadataStore?,
    actionLookupData: ActionLookupData?
) : Postable {
    private val action: com.google.devtools.build.lib.actions.Action?
    private val inputMetadataProvider: InputMetadataProvider?
    private val outputMetadataStore: OutputMetadataStore?
    private val actionLookupData: ActionLookupData?

    init {
        this.action = action
        this.inputMetadataProvider = inputMetadataProvider
        this.outputMetadataStore = outputMetadataStore
        this.actionLookupData = actionLookupData
    }

    /**
     * Returns the action.
     */
    fun getAction(): com.google.devtools.build.lib.actions.Action? {
        return action
    }

    /** Returns the metadata provider describing the inputs of the action.  */
    fun getInputMetadataProvider(): InputMetadataProvider? {
        return inputMetadataProvider
    }

    /**
     * Returns the output metadata store describing the outputs of the action.
     * 
     * 
     * May be null if the action did not complete successfully.
     */
    fun getOutputMetadataStore(): OutputMetadataStore? {
        return outputMetadataStore
    }

    fun getRelativeActionStartTimeNanos(): Long {
        return relativeActionStartTimeNanos
    }

    fun getFinishTimeNanos(): Long {
        return finishTimeNanos
    }

    fun getActionLookupData(): ActionLookupData? {
        return actionLookupData
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper("ActionCompletionEvent")
            .add("relativeActionStartTimeNanos", relativeActionStartTimeNanos)
            .add("finishTimeNanos", finishTimeNanos)
            .add("action", action)
            .add("actionLookupData", actionLookupData)
            .toString()
    }
}
