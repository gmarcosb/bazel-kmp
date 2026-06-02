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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildtool.BuildRequestOptions.MAX_JOBS

/**
 * Encapsulates thread pool options used by parallel evaluation.
 * 
 * 
 * This object has a server-scoped lifetime, but has its parameters refreshed by a call to [ ][.resetParameters] per-command.
 */
class QuiescingExecutorsImpl private constructor(
    private var analysisParallelism: Int,
    private var executionParallelism: Int,
    private var globbingParallelism: Int,
    /**
     * The size of the thread pool for CPU-heavy tasks set by
     * -experimental_skyframe_cpu_heavy_skykeys_thread_pool_size.
     * 
     * 
     * --experimental_skyframe_cpu_heavy_skykeys_thread_pool_size is not used in the execution
     * phase.
     */
    private var cpuHeavySkyKeysThreadPoolSize: Int
) : QuiescingExecutors {
    private var useAsyncExecution = false
    private var asyncExecutionMaxConcurrentActions = 0

    fun resetParameters(options: com.google.devtools.common.options.OptionsProvider) {
        // When options are missing, it is because the current command does not provide those options.
        // In that case, the values are undefined and callers should not be accessing the associated
        // executors. Having the values set to 0 causes check failures with the intention to catch such
        // errors early in tests or canary processes.
        //
        // TODO(shahan): consider whether it is better to have robust defaults instead, at the cost of
        // possibly allowing bugs here to go unnoticed.
        val loadingPhaseThreadsOption: LoadingPhaseThreadsOption? =
            options.getOptions<LoadingPhaseThreadsOption?>(LoadingPhaseThreadsOption::class.java)
        this.analysisParallelism =
            if (loadingPhaseThreadsOption != null) loadingPhaseThreadsOption.getThreads() else 0
        val buildRequestOptions: O? = options.getOptions<O?>(BuildRequestOptions::class.java)
        this.executionParallelism = if (buildRequestOptions != null) buildRequestOptions.jobs else 0
        this.useAsyncExecution =
            buildRequestOptions != null && buildRequestOptions.useAsyncExecution
        this.asyncExecutionMaxConcurrentActions =
            max(
                if (buildRequestOptions != null)
                    min(MAX_JOBS, buildRequestOptions.asyncExecutionMaxConcurrentActions)
                else
                    0,
                this.executionParallelism
            )
        val packageOptions: O? = options.getOptions<O?>(PackageOptions::class.java)
        this.globbingParallelism = if (packageOptions != null) packageOptions.getGlobbingThreads() else 0
        val analysisOptions: O? = options.getOptions<O?>(AnalysisOptions::class.java)
        this.cpuHeavySkyKeysThreadPoolSize =
            if (analysisOptions != null) analysisOptions.getCpuHeavySkyKeysThreadPoolSize() else 0
    }

    public override fun analysisParallelism(): Int {
        return analysisParallelism
    }

    public override fun executionParallelism(): Int {
        return executionParallelism
    }

    public override fun globbingParallelism(): Int {
        return globbingParallelism
    }

    val analysisExecutor: QuiescingExecutor
        get() {
            com.google.common.base.Preconditions.checkState(
                analysisParallelism > 0,
                "expected analysisParallelism > 0 : %s",
                this
            )
            if (cpuHeavySkyKeysThreadPoolSize > 0) {
                return MultiExecutorQueueVisitor.createWithExecutorServices(
                    newNamedPool(SKYFRAME_EVALUATOR, analysisParallelism),
                    AbstractQueueVisitor.createExecutorService( /* parallelism= */
                        cpuHeavySkyKeysThreadPoolSize, SKYFRAME_EVALUATOR_CPU_HEAVY
                    ),
                    ExceptionHandlingMode.FAIL_FAST,
                    ParallelEvaluatorErrorClassifier.instance()
                )
            }
            return AbstractQueueVisitor.create(
                SKYFRAME_EVALUATOR,
                analysisParallelism(),
                ParallelEvaluatorErrorClassifier.instance()
            )
        }

    val mergedAnalysisAndExecutionExecutor: QuiescingExecutor
        get() {
            com.google.common.base.Preconditions.checkState(
                analysisParallelism > 0,
                "expected analysisParallelism > 0 : %s",
                this
            )
            com.google.common.base.Preconditions.checkState(
                executionParallelism > 0,
                "expected executionParallelism > 0 : %s",
                this
            )
            com.google.common.base.Preconditions.checkState(
                cpuHeavySkyKeysThreadPoolSize > 0, "expected cpuHeavySkyKeysThreadPoolSize > 0 : %s", this
            )
            return MultiExecutorQueueVisitor.createWithExecutorServices(
                newNamedPool(SKYFRAME_EVALUATOR, analysisParallelism),
                AbstractQueueVisitor.createExecutorService( /* parallelism= */
                    cpuHeavySkyKeysThreadPoolSize, SKYFRAME_EVALUATOR_CPU_HEAVY
                ),
                if (useAsyncExecution)
                    WorkStealingThreadPoolExecutor(
                        asyncExecutionMaxConcurrentActions,
                        java.lang.Thread.ofVirtual()
                            .name(SKYFRAME_EVALUATOR_EXECUTION + "-", 0).factory()
                    )
                else
                    AbstractQueueVisitor.createExecutorService( /* parallelism= */
                        executionParallelism, SKYFRAME_EVALUATOR_EXECUTION
                    ),
                ExceptionHandlingMode.FAIL_FAST,
                ParallelEvaluatorErrorClassifier.instance()
            )
        }

    companion object {
        private const val SKYFRAME_EVALUATOR = "skyframe-evaluator"
        private const val SKYFRAME_EVALUATOR_CPU_HEAVY = "skyframe-evaluator-cpu-heavy"
        private const val SKYFRAME_EVALUATOR_EXECUTION = "skyframe-evaluator-execution"

        @com.google.common.annotations.VisibleForTesting
        fun forTesting(): QuiescingExecutors {
            return QuiescingExecutorsImpl( /* analysisParallelism= */
                6,  /* executionParallelism= */
                6,  /* globbingParallelism= */
                6,  /* cpuHeavySkyKeysThreadPoolSize= */
                4
            )
        }

        fun createDefault(): QuiescingExecutorsImpl {
            return QuiescingExecutorsImpl( /* analysisParallelism= */
                0,  /* executionParallelism= */
                0,  /* globbingParallelism= */
                0,  /* cpuHeavySkyKeysThreadPoolSize= */
                0
            )
        }
    }
}
