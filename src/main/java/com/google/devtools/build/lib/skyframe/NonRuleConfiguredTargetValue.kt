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

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** A non-rule configured target in the context of a Skyframe graph.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class NonRuleConfiguredTargetValue

    : AbstractConfiguredTargetValue<ConfiguredTarget?>, ConfiguredTargetValue {
    // Non-null when this is an alias of a remotely fetched ConfiguredTarget.
    private val targetData: TargetData?

    internal constructor(
        configuredTarget: ConfiguredTarget?,
        transitivePackages: NestedSet<Package.Metadata?>?
    ) : super(configuredTarget, transitivePackages) {
        this.targetData = null
    }

    internal constructor(
        configuredTarget: ConfiguredTarget?,
        transitivePackages: NestedSet<Package.Metadata?>?,
        targetData: TargetData?
    ) : super(configuredTarget, transitivePackages) {
        this.targetData = targetData
    }

    public override fun getTargetData(): TargetData? {
        return targetData
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("configuredTarget", getConfiguredTarget())
            .toString()
    }
}
