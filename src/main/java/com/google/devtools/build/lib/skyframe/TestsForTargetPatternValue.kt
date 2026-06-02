// Copyright 2015 The Bazel Authors. All rights reserved.
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
 * A value referring to a computed set of resolved targets. This is used for the results of target
 * pattern parsing.
 */
@Immutable
@ThreadSafe
@com.google.common.annotations.VisibleForTesting
class TestsForTargetPatternValue internal constructor(labels: ResolvedTargets<Label?>?) : SkyValue {
    private val labels: ResolvedTargets<Label?>

    init {
        this.labels = com.google.common.base.Preconditions.checkNotNull<ResolvedTargets<Label?>>(labels)
    }

    fun getLabels(): ResolvedTargets<Label?> {
        return labels
    }

    /** A list of targets of which all test suites should be expanded.  */
    @ThreadSafe
    internal class TestsForTargetPatternKey(targets: com.google.common.collect.ImmutableSortedSet<Label?>) : SkyKey {
        private val targets: com.google.common.collect.ImmutableSortedSet<Label?>

        init {
            this.targets = targets
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.TEST_SUITE_EXPANSION
        }

        fun getTargets(): com.google.common.collect.ImmutableSet<Label?> {
            return targets
        }

        override fun toString(): String {
            return "ExpandTestSuites(" + targets.toString() + ")"
        }

        override fun hashCode(): Int {
            return targets.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is TestsForTargetPatternKey) {
                return false
            }
            return obj.targets == targets
        }
    }

    companion object {
        /**
         * Create a target pattern value key.
         * 
         * @param targets the set of targets to be expanded
         */
        @ThreadSafe
        fun key(targets: MutableCollection<Label?>): SkyKey {
            return TestsForTargetPatternKey(com.google.common.collect.ImmutableSortedSet.copyOf<Label?>(targets))
        }
    }
}
