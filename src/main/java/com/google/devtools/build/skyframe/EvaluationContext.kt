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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.concurrent.QuiescingExecutor

/**
 * Includes options and states used by [MemoizingEvaluator.evaluate], [ ][MemoizingEvaluator.evaluate] and [WalkableGraphFactory.prepareAndGet]
 */
class EvaluationContext protected constructor(
    val parallelism: Int,
    executor: QuiescingExecutor?,
    keepGoing: Boolean,
    eventHandler: ExtendedEventHandler?,
    isExecutionPhase: Boolean,
    mergingSkyframeAnalysisExecutionPhases: Boolean,
    storeExactCycles: Boolean,
    unnecessaryTemporaryStateDropperReceiver: UnnecessaryTemporaryStateDropperReceiver?,
    detectCycles: Boolean
) {
    private val executor: QuiescingExecutor?
    val keepGoing: Boolean
    private val eventHandler: ExtendedEventHandler
    val isExecutionPhase: Boolean
    private val mergingSkyframeAnalysisExecutionPhases: Boolean
    private val storeExactCycles: Boolean
    val unnecessaryTemporaryStateDropperReceiver: UnnecessaryTemporaryStateDropperReceiver?

    private val detectCycles: Boolean

    init {
        this.executor = executor
        this.keepGoing = keepGoing
        this.eventHandler = com.google.common.base.Preconditions.checkNotNull<ExtendedEventHandler>(eventHandler)
        this.isExecutionPhase = isExecutionPhase
        this.mergingSkyframeAnalysisExecutionPhases = mergingSkyframeAnalysisExecutionPhases
        this.storeExactCycles = storeExactCycles
        this.unnecessaryTemporaryStateDropperReceiver = unnecessaryTemporaryStateDropperReceiver
        this.detectCycles = detectCycles
    }

    fun getExecutor(): java.util.Optional<QuiescingExecutor?> {
        return java.util.Optional.ofNullable<QuiescingExecutor?>(executor)
    }

    fun getEventHandler(): ExtendedEventHandler {
        return eventHandler
    }

    fun mergingSkyframeAnalysisExecutionPhases(): Boolean {
        return mergingSkyframeAnalysisExecutionPhases
    }

    fun storeExactCycles(): Boolean {
        return storeExactCycles
    }

    /**
     * Drops unnecessary temporary state used internally by the current evaluation.
     * 
     * 
     * If the current evaluation is slow because of GC thrashing, and the GC thrashing is partially
     * caused by this temporary state, dropping it may reduce the wall time of the current evaluation.
     * On the other hand, if the current evaluation is not GC thrashing, then dropping this temporary
     * state will probably increase the wall time.
     */
    interface UnnecessaryTemporaryStateDropper {
        @ThreadSafe
        fun drop()
    }

    /**
     * A receiver of a [UnnecessaryTemporaryStateDropper] instance tied to the current
     * evaluation.
     */
    interface UnnecessaryTemporaryStateDropperReceiver {
        fun onEvaluationStarted(dropper: UnnecessaryTemporaryStateDropper?)

        fun onEvaluationFinished()

        companion object {
            @kotlin.jvm.JvmField
            val NULL: UnnecessaryTemporaryStateDropperReceiver = object : UnnecessaryTemporaryStateDropperReceiver {
                override fun onEvaluationStarted(dropper: UnnecessaryTemporaryStateDropper?) {}

                override fun onEvaluationFinished() {}
            }
        }
    }

    fun detectCycles(): Boolean {
        return detectCycles
    }

    fun builder(): Builder {
        return com.google.devtools.build.skyframe.EvaluationContext.Companion.newBuilder().copyFrom(this)
    }

    /** Builder for [EvaluationContext].  */
    class Builder {
        protected var parallelism: Int = 0
        protected var executor: QuiescingExecutor? = null
        protected var keepGoing: Boolean = false
        protected var eventHandler: ExtendedEventHandler? = null
        protected var isExecutionPhase: Boolean = false
        protected var mergingSkyframeAnalysisExecutionPhases: Boolean = false
        protected var storeExactCycles: Boolean = true
        protected var unnecessaryTemporaryStateDropperReceiver: UnnecessaryTemporaryStateDropperReceiver? =
            UnnecessaryTemporaryStateDropperReceiver.Companion.NULL

        protected var detectCycles: Boolean = true

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun copyFrom(evaluationContext: EvaluationContext): Builder {
            this.parallelism = evaluationContext.parallelism
            this.executor = evaluationContext.executor
            this.keepGoing = evaluationContext.keepGoing
            this.eventHandler = evaluationContext.eventHandler
            this.isExecutionPhase = evaluationContext.isExecutionPhase
            this.mergingSkyframeAnalysisExecutionPhases =
                evaluationContext.mergingSkyframeAnalysisExecutionPhases
            this.storeExactCycles = evaluationContext.storeExactCycles
            this.unnecessaryTemporaryStateDropperReceiver =
                evaluationContext.unnecessaryTemporaryStateDropperReceiver
            this.detectCycles = evaluationContext.detectCycles
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setParallelism(parallelism: Int): Builder {
            this.parallelism = parallelism
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutor(executor: QuiescingExecutor?): Builder {
            this.executor = executor
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setKeepGoing(keepGoing: Boolean): Builder {
            this.keepGoing = keepGoing
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setEventHandler(eventHandler: ExtendedEventHandler?): Builder {
            this.eventHandler = eventHandler
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutionPhase(): Builder {
            this.isExecutionPhase = true
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMergingSkyframeAnalysisExecutionPhases(
            mergingSkyframeAnalysisExecutionPhases: Boolean
        ): Builder {
            this.mergingSkyframeAnalysisExecutionPhases = mergingSkyframeAnalysisExecutionPhases
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setUnnecessaryTemporaryStateDropperReceiver(
            unnecessaryTemporaryStateDropperReceiver: UnnecessaryTemporaryStateDropperReceiver?
        ): Builder {
            this.unnecessaryTemporaryStateDropperReceiver = unnecessaryTemporaryStateDropperReceiver
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setStoreExactCycles(storeExactCycles: Boolean): Builder {
            this.storeExactCycles = storeExactCycles
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setDetectCycles(detectCycles: Boolean): Builder {
            this.detectCycles = detectCycles
            return this
        }

        fun build(): EvaluationContext {
            return com.google.devtools.build.skyframe.EvaluationContext(
                parallelism,
                executor,
                keepGoing,
                eventHandler,
                isExecutionPhase,
                mergingSkyframeAnalysisExecutionPhases,
                storeExactCycles,
                unnecessaryTemporaryStateDropperReceiver,
                detectCycles
            )
        }
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun newBuilder(): Builder {
            return com.google.devtools.build.skyframe.EvaluationContext.Builder()
        }
    }
}
