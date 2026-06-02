// Copyright 2024 The Bazel Authors. All rights reserved.
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

/** Common base class for configured target values for rules and non-rules.  */
internal abstract class AbstractConfiguredTargetValue<T : ConfiguredTarget?>
    (configuredTarget: T?, transitivePackages: NestedSet<Package.Metadata?>?) : ConfiguredTargetValue {
    // This variable is non-final because it may be clear()ed to save memory. It is null only after
    // clear(true) is called.
    var configuredTarget: T?
        private set

    // May be null after clearing; because transitive packages are not tracked; or after
    // deserialization.
    @Transient
    private var transitivePackages: NestedSet<Package.Metadata?>?

    init {
        this.configuredTarget = com.google.common.base.Preconditions.checkNotNull<T?>(configuredTarget)
        this.transitivePackages = transitivePackages
    }

    public override fun getTransitivePackages(): NestedSet<Package.Metadata?>? {
        return transitivePackages
    }

    val isCleared: Boolean
        get() = this.configuredTarget == null

    public override fun clear(clearEverything: Boolean) {
        if (clearEverything) {
            this.configuredTarget = null
        }
        this.transitivePackages = null
    }
}
