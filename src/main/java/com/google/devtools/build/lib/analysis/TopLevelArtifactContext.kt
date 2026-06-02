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
package com.google.devtools.build.lib.analysis

/** Contains options which control the set of artifacts to build for top-level targets.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class TopLevelArtifactContext(
    private val runTestsExclusively: Boolean,
    private val expandFilesets: Boolean,
    outputGroups: com.google.common.collect.ImmutableSortedSet<String?>
) {
    private val outputGroups: com.google.common.collect.ImmutableSortedSet<String?>

    init {
        this.outputGroups = outputGroups
    }

    /** Whether to run tests in exclusive mode.  */
    fun runTestsExclusively(): Boolean {
        return runTestsExclusively
    }

    fun expandFilesets(): Boolean {
        return expandFilesets
    }


    /** Returns the value of the --output_groups flag.  */
    fun outputGroups(): MutableSet<String?> {
        return outputGroups
    }


    // TopLevelArtifactContexts are stored in maps in BuildView,
    // so equals() and hashCode() need to work.
    override fun equals(other: Any?): Boolean {
        if (other is TopLevelArtifactContext) {
            return runTestsExclusively == other.runTestsExclusively && expandFilesets == other.expandFilesets && outputGroups == other.outputGroups
        } else {
            return false
        }
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(runTestsExclusively, expandFilesets, outputGroups)
    }
}
