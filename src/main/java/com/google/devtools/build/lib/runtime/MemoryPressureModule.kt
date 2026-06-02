// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.pkgcache.TargetParsingCompleteEvent

/**
 * A [BlazeModule] that installs a [MemoryPressureListener] that reacts to memory
 * pressure events.
 */
class MemoryPressureModule : BlazeModule() {
    private val memoryPressureListener: MemoryPressureListener = MemoryPressureListener.Companion.create()

    // Null between commands.
    private var highWaterMarkLimiter: HighWaterMarkLimiter? = null
    private var eventBus: com.google.common.eventbus.EventBus? = null

    val commonCommandOptions: com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>
        get() = com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
            MemoryPressureOptions::class.java
        )

    public override fun beforeCommand(env: CommandEnvironment) {
        eventBus = env.getEventBus()
        val options: MemoryPressureOptions = env.getOptions().getOptions(MemoryPressureOptions::class.java)
        memoryPressureListener.initForInvocation(
            eventBus,
            GcThrashingDetector.Companion.createForCommand(options),
            GcChurningDetector.Companion.createForCommand(options)
        )
        highWaterMarkLimiter =
            HighWaterMarkLimiter(env.getSkyframeExecutor(), env.getSyscallCache(), options)
        eventBus.register(this)
        eventBus.register(highWaterMarkLimiter)
    }

    @com.google.common.eventbus.Subscribe
    fun targetParsingComplete(event: TargetParsingCompleteEvent) {
        memoryPressureListener.targetParsingComplete(event.getTargets().size())
    }

    public override fun afterCommand() {
        postStats()
        memoryPressureListener.reset()
        eventBus = null
        highWaterMarkLimiter = null
    }

    @com.google.common.eventbus.Subscribe
    @com.google.errorprone.annotations.Keep
    fun onCrash(@Suppress("unused") event: CrashEvent?) {
        postStats()
    }

    private fun postStats() {
        // Guard against crashes between commands or an async crash racing with afterCommand().
        val highWaterMarkLimiter: HighWaterMarkLimiter? = this.highWaterMarkLimiter
        val eventBus: com.google.common.eventbus.EventBus? = this.eventBus
        if (highWaterMarkLimiter == null || eventBus == null) {
            return
        }
        val memoryPressureStatsBuilder: MemoryPressureStats.Builder = MemoryPressureStats.newBuilder()
        highWaterMarkLimiter.populateStats(memoryPressureStatsBuilder)
        memoryPressureListener.populateStats(memoryPressureStatsBuilder)
        eventBus.post(memoryPressureStatsBuilder.build())
    }
}
