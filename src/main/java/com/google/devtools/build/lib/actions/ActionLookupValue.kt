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

import com.google.devtools.build.skyframe.SkyValue

/** Base interface for all values which can provide the generating action of an artifact.  */
interface ActionLookupValue : SkyValue {
    /** Returns a list of actions registered by this [SkyValue].  */
    fun getActions(): com.google.common.collect.ImmutableList<ActionAnalysisMetadata>?

    /** Returns the [Action] with index `index` in this value. Never null.  */
    fun getAction(index: Int): com.google.devtools.build.lib.actions.Action {
        val result: ActionAnalysisMetadata = getActions().get(index)
        // Avoid Preconditions.checkState which would box the int arg.
        check(result is com.google.devtools.build.lib.actions.Action) {
            java.lang.String.format(
                "Not action: %s %s %s",
                result,
                index,
                this
            )
        }
        return result
    }

    fun getActionTemplate(index: Int): ActionTemplate<*> {
        val result: ActionAnalysisMetadata = getActions().get(index)
        // Avoid Preconditions.checkState which would box the int arg.
        check(result is ActionTemplate<*>) {
            java.lang.String.format(
                "Not action template: %s %s %s",
                result,
                index,
                this
            )
        }
        return result
    }
}
