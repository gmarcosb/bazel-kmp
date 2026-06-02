// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.ConfiguredAspect

/**
 * Listens to the various status events of the top level targets/aspects.
 * 
 * 
 * WARNING: For consistency, the getter methods should only be used after the execution phase is
 * finished.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class BuildResultListener {
    // Also includes test targets.
    private val analyzedTargets: MutableSet<ConfiguredTarget?> = ConcurrentHashMap.newKeySet<ConfiguredTarget?>()
    private val analyzedTests: MutableSet<ConfiguredTarget?> = ConcurrentHashMap.newKeySet<ConfiguredTarget?>()
    private val analyzedAspects: MutableMap<AspectKey?, ConfiguredAspect?> =
        com.google.common.collect.Maps.newConcurrentMap<AspectKey?, ConfiguredAspect?>()

    // Also includes test targets.
    private val skippedTargets: MutableSet<ConfiguredTarget?> = ConcurrentHashMap.newKeySet<ConfiguredTarget?>()

    // Also includes test targets.
    private val builtTargets: MutableSet<ConfiguredTargetKey?> = ConcurrentHashMap.newKeySet<ConfiguredTargetKey?>()
    private val builtAspects: MutableSet<AspectKey?> = ConcurrentHashMap.newKeySet<AspectKey?>()

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun addAnalyzedTarget(event: TopLevelTargetAnalyzedEvent) {
        analyzedTargets.add(event.configuredTarget)
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun addAnalyzedTest(event: TestAnalyzedEvent) {
        analyzedTests.add(event.configuredTarget)
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun addAnalyzedAspect(event: AspectAnalyzedEvent) {
        analyzedAspects.put(event.aspectKey(), event.configuredAspect())
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun addSkippedTarget(event: TopLevelTargetSkippedEvent) {
        skippedTargets.add(event.configuredTarget)
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun addBuiltTarget(event: TopLevelTargetBuiltEvent) {
        builtTargets.add(event.configuredTargetKey())
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun addBuiltAspect(event: AspectBuiltEvent) {
        builtAspects.add(event.aspectKey())
    }

    fun getAnalyzedTargets(): com.google.common.collect.ImmutableSet<ConfiguredTarget?> {
        return com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(analyzedTargets)
    }

    fun getAnalyzedTests(): com.google.common.collect.ImmutableSet<ConfiguredTarget?> {
        return com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(analyzedTests)
    }

    fun getAnalyzedAspects(): com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?> {
        return com.google.common.collect.ImmutableMap.copyOf<AspectKey?, ConfiguredAspect?>(analyzedAspects)
    }

    fun getSkippedTargets(): com.google.common.collect.ImmutableSet<ConfiguredTarget?> {
        return com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(skippedTargets)
    }

    fun getBuiltTargets(): com.google.common.collect.ImmutableSet<ConfiguredTargetKey?> {
        return com.google.common.collect.ImmutableSet.copyOf<ConfiguredTargetKey?>(builtTargets)
    }

    fun getBuiltAspects(): com.google.common.collect.ImmutableSet<AspectKey?> {
        return com.google.common.collect.ImmutableSet.copyOf<AspectKey?>(builtAspects)
    }
}
