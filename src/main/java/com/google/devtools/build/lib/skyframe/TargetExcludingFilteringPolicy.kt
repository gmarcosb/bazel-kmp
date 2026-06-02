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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/**
 * A filtering policy that excludes multiple single targets. These are not expected to be a part of
 * any SkyKey and it's expected that the number of targets is not too large.
 */
internal class TargetExcludingFilteringPolicy(excludedSingleTargets: com.google.common.collect.ImmutableSet<Label?>) :
    FilteringPolicy {
    private val excludedSingleTargets: com.google.common.collect.ImmutableSet<Label?>

    init {
        this.excludedSingleTargets = excludedSingleTargets
    }

    public override fun shouldRetain(target: Target, explicit: Boolean): Boolean {
        return !excludedSingleTargets.contains(target.getLabel())
    }

    override fun toString(): String {
        return java.lang.String.format("excludedTargets%s", excludedSingleTargets)
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is TargetExcludingFilteringPolicy) {
            return false
        }
        return excludedSingleTargets == o.excludedSingleTargets
    }

    override fun hashCode(): Int {
        return java.util.Objects.hashCode(excludedSingleTargets)
    }
}
