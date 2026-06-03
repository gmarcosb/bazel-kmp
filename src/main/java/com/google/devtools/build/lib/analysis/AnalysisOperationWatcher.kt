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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelEntityAnalysisConcludedEvent

/**
 * A watcher for analysis-related work that sends out a signal when all such work in the build is
 * done. There's one instance of this class per build.
 */
class AnalysisOperationWatcher private constructor(
    threadSafeExpectedKeys: MutableSet<SkyKey?>,
    eventBus: com.google.common.eventbus.EventBus,
    lowerThresholdToSignalForExecution: Float,
    finisher: AnalysisOperationWatcherFinisher,
// Since the events are fired from within a SkyFunction, it's possible that the same event is
    // fired multiple times. A simple counter would therefore not suffice.
    private val executionGoAheadCallback: ExecutionGoAheadCallback
) : java.lang.AutoCloseable {
    private val threadSafeExpectedKeys: MutableSet<SkyKey?>
    private val eventBus: com.google.common.eventbus.EventBus
    private val finisher: AnalysisOperationWatcherFinisher

    // When there's not more than this amount of top level target/aspect left to analyze, we can start
    // with execution.
    private val lowerThresholdToSignalForExecution: Float
    private var signalledExecutionGoAhead = false

    init {
        this.threadSafeExpectedKeys = threadSafeExpectedKeys
        this.lowerThresholdToSignalForExecution = lowerThresholdToSignalForExecution
        this.eventBus = eventBus
        this.finisher = finisher
    }

    @com.google.common.eventbus.Subscribe
    fun handleTopLevelEntityAnalysisConcluded(e: TopLevelEntityAnalysisConcludedEvent) {
        if (threadSafeExpectedKeys.isEmpty()) {
            return
        }
        threadSafeExpectedKeys.remove(e.getAnalyzedTopLevelKey())

        if (!signalledExecutionGoAhead
            && threadSafeExpectedKeys.size() <= lowerThresholdToSignalForExecution
        ) {
            signalledExecutionGoAhead = true
            executionGoAheadCallback.goAhead()
        }

        if (threadSafeExpectedKeys.isEmpty()) {
            try {
                finisher.analysisFinishedCallback()
            } catch (exception: java.lang.InterruptedException) {
                // Subscribers in general shouldn't throw exceptions. We therefore try to preserve the
                // interrupted status here.
                java.lang.Thread.currentThread().interrupt()
            }
        }
    }

    override fun close() {
        eventBus.unregister(this)
    }

    /** A callback to be called when all the expected keys have been analyzed.  */
    fun interface AnalysisOperationWatcherFinisher {
        @Throws(java.lang.InterruptedException::class)
        fun analysisFinishedCallback()
    }

    /** A callback to signal that the delayed execution tasks can now go ahead.  */
    fun interface ExecutionGoAheadCallback {
        fun goAhead()
    }

    companion object {
        /** Creates an AnalysisOperationWatcher and registers it with the provided eventBus.  */
        fun createAndRegisterWithEventBus(
            threadSafeExpectedKeys: MutableSet<SkyKey?>,
            eventBus: com.google.common.eventbus.EventBus,
            lowerThresholdToSignalForExecution: Float,
            finisher: AnalysisOperationWatcherFinisher,
            executionGoAheadCallback: ExecutionGoAheadCallback
        ): AnalysisOperationWatcher {
            val watcher =
                AnalysisOperationWatcher(
                    threadSafeExpectedKeys,
                    eventBus,
                    lowerThresholdToSignalForExecution,
                    finisher,
                    executionGoAheadCallback
                )
            eventBus.register(watcher)
            return watcher
        }
    }
}
