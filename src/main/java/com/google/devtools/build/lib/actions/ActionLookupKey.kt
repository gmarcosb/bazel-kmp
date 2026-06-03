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

import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey

/**
 * [SkyKey] for an "analysis object": either an [ActionLookupValue] or a [ ].
 * 
 * 
 * Whether a configured target creates actions cannot be inferred from its [ ] without performing analysis, so this class is used
 * for both types. Only [ActionLookupValue] nodes are accessed during the execution phase.
 * 
 * 
 * All subclasses of [ActionLookupValue] "own" artifacts with [ArtifactOwner]s that
 * are subclasses of [ActionLookupKey]. This allows callers to easily find the value key,
 * while remaining agnostic to what action lookup values actually exist.
 */
interface ActionLookupKey : ArtifactOwner, CPUHeavySkyKey {
    /**
     * Returns the [BuildConfigurationKey] for the configuration associated with this key, or
     * `null` if this key has no associated configuration.
     */
    fun getConfigurationKey(): BuildConfigurationKey?

    /**
     * Returns `true` if the actions *may* own shareable actions, as determined by [ ][ActionLookupData.valueIsShareable].
     * 
     * 
     * Returns `false` for some non-standard keys such as the build info key and coverage
     * report key.
     * 
     * 
     * A return of `true` still requires checking [ActionLookupData.valueIsShareable]
     * to determine whether the individual action can be shared - notably, for a test target,
     * compilation actions are shareable, but test actions are not.
     */
    fun mayOwnShareableActions(): Boolean {
        return getLabel() != null
    }
}
