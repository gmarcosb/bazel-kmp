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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/** A configured target in the context of a Skyframe graph.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class RuleConfiguredTargetValue
    (
    configuredTarget: RuleConfiguredTarget,
    transitivePackages: NestedSet<Package.Metadata?>?
) : AbstractConfiguredTargetValue<RuleConfiguredTarget?>(configuredTarget, transitivePackages),
    RuleConfiguredObjectValue, ConfiguredTargetValue {
    private val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?

    init {
        // These are specifically *not* copied to save memory.
        this.actions = configuredTarget.getActions()
    }

    public override fun getActions(): com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>? {
        return actions
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("actions", actions)
            .add("configuredTarget", getConfiguredTarget())
            .toString()
    }
}
