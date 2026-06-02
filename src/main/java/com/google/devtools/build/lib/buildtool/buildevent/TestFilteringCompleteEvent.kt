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
package com.google.devtools.build.lib.buildtool.buildevent

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.analysis.ConfiguredTarget
import javax.annotation.concurrent.Immutable

/**
 * This event is fired after test filtering.
 * 
 * 
 * The test filtering phase always expands test_suite rules, so the set of active targets should
 * never contain test_suites.
 */
@Immutable
class TestFilteringCompleteEvent(
    targets: MutableCollection<out ConfiguredTarget>,
    testTargets: MutableCollection<out ConfiguredTarget>?,
    targetsToSkip: MutableCollection<out ConfiguredTarget>,
    configurationMap: MutableMap<BuildConfigurationKey?, BuildConfigurationValue?>
) {
    private val targets: MutableCollection<ConfiguredTarget?>
    private val testTargets: MutableCollection<ConfiguredTarget?>?
    private val skippedTests: MutableCollection<ConfiguredTarget?>
    private val configurationMap: MutableMap<BuildConfigurationKey?, BuildConfigurationValue?>

    /**
     * Construct the event.
     * 
     * @param targets The set of active targets that remain.
     * @param testTargets The collection of tests to be run. May be null.
     * @param targetsToSkip The collection of tests that are to be skipped.
     * @param configurationMap A map from configuration keys of all targets to the configurations.
     */
    init {
        this.targets = ImmutableList.copyOf<ConfiguredTarget?>(targets)
        this.testTargets = if (testTargets == null) null else ImmutableList.copyOf<ConfiguredTarget?>(testTargets)
        this.skippedTests = ImmutableList.copyOf<ConfiguredTarget?>(targetsToSkip)
        this.configurationMap = configurationMap
        if (testTargets == null) {
            return
        }

        for (testTarget in testTargets) {
            Preconditions.checkState(testTarget.getProvider(TestProvider::class.java) != null)
        }
    }

    /**
     * @return The set of active targets remaining. This is a subset of
     * the targets that passed analysis, after test_suite expansion.
     */
    fun getTargets(): MutableCollection<ConfiguredTarget?> {
        return targets
    }

    /**
     * @return The set of test targets to be run. May be null.
     */
    fun getTestTargets(): MutableCollection<ConfiguredTarget?>? {
        return testTargets
    }

    /** Returns the set of tests that should be skipped.  */
    fun getSkippedTests(): MutableCollection<ConfiguredTarget?> {
        return skippedTests
    }

    fun getConfigurationForTarget(target: ConfiguredTarget): BuildConfigurationValue? {
        return Preconditions.checkNotNull<BuildConfigurationValue?>(configurationMap.get(target.getConfigurationKey()))
    }
}
