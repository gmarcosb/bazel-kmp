// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/**
 * A [SkyFunction] for [GlobsValue], which drives the glob matching process for all
 * globs within a package.
 * 
 * 
 * [GlobsFunction] has two benefits over [GlobFunction]:
 * 
 * 
 *  * The multiple GLOB nodes rdeping on the package are aggregated into a single one. This
 * reduces some memory overhead, especially when number of globs defined in the BUILD file is
 * very large.
 *  * Evaluating all globs within a package starts to have some structured logical concurrency,
 * thus reducing the number of Skyframe restarts.
 * 
 * 
 * 
 * [GlobsFunction] is the only [SkyFunction] taking advantage of [ ][SkyFunctionEnvironment.getParallelEvaluationExecutor]. State Machines are driven in-parallel on
 * both [.compute] and the "skyframe-evaluator" ForkJoinPool's threads.
 * 
 * 
 * Skyframe globbing was previously performed via multiple [GlobFunction]s. Each glob
 * expression of the package leads to at least one GLOB node in the dependency graph. These glob
 * nodes evaluation are also done on the "skyframe-evaluator" FJP. So when skyframe globbing is done
 * by this [GlobsFunction], there is no increase in the actual workload. As a result, we
 * consider it reasonable to introduce the existing "skyframe-evaluator" parallelism to [ ].
 */
class GlobsFunction : SkyFunction {
    protected var regexPatternCache: ConcurrentHashMap<String?, java.util.regex.Pattern?> =
        ConcurrentHashMap<String?, java.util.regex.Pattern?>()

    private class State : SkyKeyComputeState,
        com.google.devtools.build.lib.packages.producers.GlobComputationProducer.ResultSink {
        private var globDrivers: MutableList<com.google.devtools.build.skyframe.state.Driver>? = null
        var ignoredSubdirectories: IgnoredSubdirectories? = null

        private val matchings: MutableSet<PathFragment?> =
            com.google.common.collect.Sets.newConcurrentHashSet<PathFragment?>()

        @kotlin.concurrent.Volatile
        private var error: GlobError? = null

        /**
         * This method does not necessarily need to be a synchronized one. As long as some error was
         * captured, the [GlobsFunction.compute] will ignore [.matchings] and throws the
         * captured [.error]. However, any operation [.matchings] has to be thread-safe.
         */
        public override fun acceptPathFragmentsWithoutPackageFragment(
            pathFragments: com.google.common.collect.ImmutableSet<PathFragment?>?
        ) {
            if (error == null) {
                // If an exception has already been discovered and accepted during previous computation, we
                // should not accept any matching result.
                matchings.addAll(pathFragments)
            }
        }

        @kotlin.jvm.Synchronized
        override fun acceptGlobError(globError: GlobError?) {
            if (error == null) {
                // Keeps the first reported error if there are multiple.
                this.error = globError
            }
        }
    }

    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val globsKey: com.google.devtools.build.lib.skyframe.GlobsValue.Key =
            skyKey as com.google.devtools.build.lib.skyframe.GlobsValue.Key
        val state: State =
            env.getState<State>(java.util.function.Supplier { com.google.devtools.build.lib.skyframe.GlobsFunction.State() })

        if (state.ignoredSubdirectories == null) {
            val repositoryName: RepositoryName? = globsKey.getPackageIdentifier().getRepository()
            val ignoredSubdirectories: IgnoredSubdirectoriesValue? =
                env.getValue(IgnoredSubdirectoriesValue.Companion.key(repositoryName)) as IgnoredSubdirectoriesValue?
            if (env.valuesMissing()) {
                return null
            }
            state.ignoredSubdirectories = ignoredSubdirectories.asIgnoredSubdirectories()
        }

        if (state.globDrivers == null) {
            state.globDrivers = java.util.ArrayList<com.google.devtools.build.skyframe.state.Driver>()
            for (globRequest in globsKey.getGlobRequests()) {
                val globDescriptor: GlobDescriptor =
                    GlobDescriptor.Companion.create(
                        globsKey.getPackageIdentifier(),
                        globsKey.getPackageRoot(),  // TODO(b/290998109): Support non-empty subdir when replacing Glob with Globs in
                        // IncludeParser.
                        PathFragment.EMPTY_FRAGMENT,
                        globRequest.getPattern(),
                        globRequest.getGlobOperation()
                    )
                state.globDrivers!!.add(
                    com.google.devtools.build.skyframe.state.Driver(
                        GlobComputationProducer(
                            globDescriptor, state.ignoredSubdirectories, regexPatternCache, state
                        )
                    )
                )
            }
        }

        val concurrentEnvironment: ConcurrentSkyFunctionEnvironment =
            ConcurrentSkyFunctionEnvironment(env as SkyFunctionEnvironment)
        val allComplete: AtomicBoolean = AtomicBoolean(true)
        val possibleInterruptedExceptionRef: AtomicReference<java.lang.InterruptedException?> =
            AtomicReference<java.lang.InterruptedException?>()
        val stateMachineRunnablesQueue: BlockingQueue<java.lang.Runnable?> = LinkedBlockingQueue<java.lang.Runnable?>()
        val countDownLatch: CountDownLatch = CountDownLatch(state.globDrivers!!.size)
        for (driver in state.globDrivers!!) {
            stateMachineRunnablesQueue.put(
                java.lang.Runnable {
                    try {
                        if (!driver.drive(concurrentEnvironment)) {
                            allComplete.set(false)
                        }
                    } catch (e: java.lang.InterruptedException) {
                        possibleInterruptedExceptionRef.compareAndSet( /* expectedValue= */null, e)
                    } finally {
                        countDownLatch.countDown()
                    }
                })
        }

        // This allows work to be shared with the current Skyframe thread.
        val drainStateMachineQueue: java.lang.Runnable =
            java.lang.Runnable {
                var next: java.lang.Runnable?
                var isInterrupted = false
                while ((stateMachineRunnablesQueue.poll().also { next = it }) != null) {
                    if (isInterrupted) {
                        countDownLatch.countDown()
                        continue
                    }
                    next.run()
                    if (java.lang.Thread.interrupted()) {
                        isInterrupted = true
                        possibleInterruptedExceptionRef.compareAndSet( /* expectedValue= */
                            null, java.lang.InterruptedException()
                        )
                    }
                }
            }

        // Schedule the State Machines to be driven on "skyframe-evaluator" threads.
        val executor: QuiescingExecutor? = env.getParallelEvaluationExecutor()
        if (executor != null) {
            for (i in 0..<state.globDrivers!!.size - 1) {
                // When executor is a MultiExecutorQueueVisitor, calling execute without providing the
                // threadPoolType will execute the runnable on the regular "skyframe-evaluator" threads.
                executor.execute(drainStateMachineQueue)
            }
        }

        // Also take advantage of the current thread to drive some State Machines.
        drainStateMachineQueue.run()

        // It is possible State Machines run on external threads finish later than the ones on current
        // thread. So we need to wait for all State Machine `Runnable`s to complete before proceeding.
        // Using `Uninterruptibles.awaitUninterruptibly` is necessary in that all State Machine workers
        // threads should complete before GlobsFunction#compute() re-throws the InterruptedException.
        // Otherwise, downstream logic on the main thread could race with unfinished State Machine
        // workers threads.
        com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly(countDownLatch)
        if (java.lang.Thread.interrupted()) {
            possibleInterruptedExceptionRef.compareAndSet( /* expectedValue= */
                null, java.lang.InterruptedException()
            )
        }
        if (possibleInterruptedExceptionRef.get() != null) {
            throw possibleInterruptedExceptionRef.get()
        }

        if (!allComplete.get()) {
            GlobException.Companion.handleExceptions(state.error)
            return null
        }

        GlobException.Companion.handleExceptions(state.error)
        return GlobsValue(com.google.common.collect.ImmutableSet.copyOf<PathFragment?>(state.matchings))
    }
}
