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

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata
import com.google.devtools.build.lib.actions.ActionLookupValue

/**
 * Basic implementation of [ActionLookupValue] where the value itself owns and maintains the
 * list of generating actions.
 */
open class BasicActionLookupValue @com.google.common.annotations.VisibleForTesting constructor(actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?) :
    ActionLookupValue {
    protected val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?

    init {
        this.actions = actions
    }

    override fun getActions(): com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>? {
        return actions
    }

    protected open fun getStringHelper(): com.google.common.base.MoreObjects.ToStringHelper? {
        return com.google.common.base.MoreObjects.toStringHelper(this).add("actions", actions)
    }
}
