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
internal class TestExpansionValue(labels: ResolvedTargets<Label?>?) : SkyValue {
    private val labels: ResolvedTargets<Label?>

    init {
        this.labels = com.google.common.base.Preconditions.checkNotNull<ResolvedTargets<Label?>>(labels)
    }

    fun getLabels(): ResolvedTargets<Label?> {
        return labels
    }

    /** A list of targets of which all test suites should be expanded.  */
    @ThreadSafe
    internal class TestExpansionKey(label: Label, strict: Boolean) : SkyKey {
        private val label: Label
        val isStrict: Boolean

        init {
            this.label = label
            this.isStrict = strict
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.TESTS_IN_SUITE
        }

        fun getLabel(): Label {
            return label
        }

        override fun toString(): String {
            return "TestsInSuite(" + label + ", strict=" + this.isStrict + ")"
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(label, this.isStrict)
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is TestExpansionKey) {
                return false
            }
            return obj.label.equals(label) && obj.isStrict == this.isStrict
        }
    }

    companion object {
        /**
         * Create a target pattern value key.
         * 
         * @param target the target to be expanded
         */
        @ThreadSafe
        fun key(target: Target, strict: Boolean): SkyKey {
            com.google.common.base.Preconditions.checkState(TargetUtils.isTestSuiteRule(target))
            return TestExpansionKey(target.getLabel(), strict)
        }
    }
}
