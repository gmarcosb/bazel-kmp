// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.PackageSpecificationProvider
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider

/**
 * Provides the transitive visibility groups that a target belongs to. If a target belongs to a
 * transitive visibility group, it may only be depended on by other targets that also belong to the
 * same group.
 */
class TransitiveVisibilityProvider(transitiveVisibility: com.google.common.collect.ImmutableSet<PackageSpecificationProvider?>?) :
    TransitiveInfoProvider {
    private val transitiveVisibility: com.google.common.collect.ImmutableSet<PackageSpecificationProvider?>?

    /**
     * Creates a new [TransitiveVisibilityProvider] from a set of transitive visibility labels.
     */
    init {
        // We should only try to create a provider if there is a non-empty transitive visibility.
        com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSet<PackageSpecificationProvider?>?>(
            transitiveVisibility
        )
        com.google.common.base.Preconditions.checkArgument(!transitiveVisibility.isEmpty())

        this.transitiveVisibility = transitiveVisibility
    }

    /** Returns the set of transitive visibility groups for the target.  */
    fun getTransitiveVisibility(): com.google.common.collect.ImmutableSet<PackageSpecificationProvider?>? {
        return transitiveVisibility
    }
}
