// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2

import com.google.devtools.build.lib.cmdline.ParallelVisitor

/** Utilities for [ParallelVisitor] with QueryException/Target type parameters.  */
object ParallelVisitorUtils {
    /** All visitors share a single global fixed thread pool.  */
    val FIXED_THREAD_POOL_EXECUTOR: ExecutorService = ThreadPoolExecutor( /*corePoolSize=*/
        max(1, SkyQueryEnvironment.Companion.DEFAULT_THREAD_COUNT),  /*maximumPoolSize=*/
        max(1, SkyQueryEnvironment.Companion.DEFAULT_THREAD_COUNT),  /*keepAliveTime=*/
        1,  /*units=*/
        TimeUnit.SECONDS,  /*workQueue=*/
        BlockingStack<java.lang.Runnable?>(),
        com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("parallel-visitor %d").build()
    )

    /**
     * Returns a [Callback] which kicks off a parallel visitation when [Callback.process]
     * is invoked.
     */
    fun <OutputResultT : Target?, CallbackT : com.google.devtools.build.lib.query2.engine.Callback<OutputResultT?>?>
            createParallelVisitorCallback(
        visitorFactory: Factory<SkyKey?, *, *, OutputResultT?, com.google.devtools.build.lib.query2.engine.QueryException?, CallbackT?>
    ): com.google.devtools.build.lib.query2.engine.Callback<OutputResultT?> {
        return ParallelTargetVisitorCallback<OutputResultT?, com.google.devtools.build.lib.query2.engine.Callback<OutputResultT?>?>(
            visitorFactory
        )
    }

    /** Factory for creating ParallelVisitors used during Query execution.  */
    interface QueryVisitorFactory<VisitKeyT, OutputKeyT, OutputResultT>
        :
        Factory<SkyKey?, VisitKeyT?, OutputKeyT?, OutputResultT?, com.google.devtools.build.lib.query2.engine.QueryException?, com.google.devtools.build.lib.query2.engine.Callback<OutputResultT?>?>

    /**
     * A [Callback] whose [Callback.process] method kicks off a visitation via a fresh
     * [ParallelVisitor] instance.
     */
    class ParallelTargetVisitorCallback<OutputResultT : Target?, CallbackT : com.google.devtools.build.lib.query2.engine.Callback<OutputResultT?>?>
        (visitorFactory: ParallelVisitor.Factory<SkyKey?, *, *, OutputResultT?, com.google.devtools.build.lib.query2.engine.QueryException?, CallbackT?>) :
        com.google.devtools.build.lib.query2.engine.Callback<OutputResultT?> {
        private val visitorFactory: ParallelVisitor.Factory<SkyKey?, *, *, OutputResultT?, com.google.devtools.build.lib.query2.engine.QueryException?, CallbackT?>


        init {
            this.visitorFactory = visitorFactory
        }

        @Throws(
            com.google.devtools.build.lib.query2.engine.QueryException::class,
            java.lang.InterruptedException::class
        )
        override fun process(partialResult: Iterable<OutputResultT?>?) {
            val visitor: ParallelVisitor<SkyKey?, *, *, OutputResultT?, com.google.devtools.build.lib.query2.engine.QueryException?, CallbackT?> =
                visitorFactory.create()
            // TODO(b/131109214): It's not ideal to have an operation like this in #process that blocks on
            // another, potentially expensive computation. Refactor to something like "processAsync".
            visitor.visitAndWaitForCompletion(SkyQueryEnvironment.Companion.makeLabelsStrict<T?>(partialResult))
        }
    }

    /** A ParallelVisitor suitable for use during query execution.  */
    abstract class ParallelQueryVisitor<VisitKeyT, OutputKeyT, OutputResultT>
        (
        callback: com.google.devtools.build.lib.query2.engine.Callback<OutputResultT?>?,
        visitBatchSize: Int,
        processResultsBatchSize: Int,
        visitTaskStatusCallback: VisitTaskStatusCallback?
    ) : ParallelVisitor<SkyKey?, VisitKeyT?, OutputKeyT?, OutputResultT?, com.google.devtools.build.lib.query2.engine.QueryException?, com.google.devtools.build.lib.query2.engine.Callback<OutputResultT?>?>(
        callback,
        com.google.devtools.build.lib.query2.engine.QueryException::class.java,
        visitBatchSize,
        processResultsBatchSize,
        3L * SkyQueryEnvironment.Companion.DEFAULT_THREAD_COUNT,
        SkyQueryEnvironment.Companion.BATCH_CALLBACK_SIZE,
        FIXED_THREAD_POOL_EXECUTOR,
        visitTaskStatusCallback
    )
}
