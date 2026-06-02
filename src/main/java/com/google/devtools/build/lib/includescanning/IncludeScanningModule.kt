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
package com.google.devtools.build.lib.includescanning

import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.google.devtools.build.lib.actions.ActionExecutionContext
import com.google.devtools.build.lib.concurrent.ExecutorUtil
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.exec.ModuleActionContextRegistry
import com.google.devtools.build.lib.profiler.Profiler
import com.google.devtools.common.options.OptionsBase
import java.util.concurrent.RejectedExecutionHandler
import java.util.function.Function
import java.util.function.Supplier

/**
 * Module that provides implementations of [CppIncludeExtractionContext], [ ], and [SwigIncludeScanningContext].
 */
class IncludeScanningModule : BlazeModule() {
    private val spawnIncludeScannerSupplier: MutableSupplier<SpawnIncludeScanner?> =
        MutableSupplier<SpawnIncludeScanner?>()
    private val artifactFactory: MutableSupplier<ArtifactFactory?> = MutableSupplier<ArtifactFactory?>()
    private var lifecycleManager: IncludeScannerLifecycleManager? = null

    protected val includeHintsFilename: PathFragment?
        get() = null

    @ThreadSafety.ThreadHostile
    override fun registerActionContexts(
        registryBuilder: ModuleActionContextRegistry.Builder,
        env: CommandEnvironment,
        buildRequest: BuildRequest?
    ) {
        registryBuilder
            .register<T?>(CppIncludeExtractionContext::class.java, CppIncludeExtractionContextImpl(env))
            .register<T?>(SwigIncludeScanningContext::class.java, lifecycleManager!!.swigActionContext)
            .register<T?>(CppIncludeScanningContext::class.java, lifecycleManager!!.cppActionContext)
        registryBuilder
            .restrictTo(CppIncludeExtractionContext::class.java, "")
            .restrictTo(SwigIncludeScanningContext::class.java, "")
            .restrictTo(CppIncludeScanningContext::class.java, "")
    }

    @ThreadSafety.ThreadHostile
    override fun executorInit(env: CommandEnvironment, request: BuildRequest, builder: ExecutorBuilder) {
        lifecycleManager =
            IncludeScannerLifecycleManager(
                env, request, spawnIncludeScannerSupplier, this.includeHintsFilename != null
            )
        builder.addExecutorLifecycleListener(lifecycleManager)
    }

    override fun getCommandOptions(commandName: String): Iterable<Class<out OptionsBase?>?> {
        return if (commandName == "build")
            ImmutableList.of<Class<out OptionsBase?>?>(IncludeScanningOptions::class.java)
        else
            ImmutableList.of<Class<out OptionsBase?>?>()
    }

    override fun beforeCommand(env: CommandEnvironment) {
        artifactFactory.set(env.getSkyframeBuildView().getArtifactFactory())
    }

    override fun afterCommand() {
        spawnIncludeScannerSupplier.set(null)
        artifactFactory.set(null)
        lifecycleManager = null
    }

    override fun workspaceInit(
        runtime: BlazeRuntime?, directories: BlazeDirectories?, builder: WorkspaceBuilder
    ) {
        builder.addSkyFunctions(getSkyFunctions(this.includeHintsFilename))
    }

    /** Implementation of [CppIncludeExtractionContext].  */
    class CppIncludeExtractionContextImpl internal constructor(env: CommandEnvironment) : CppIncludeExtractionContext {
        private val env: CommandEnvironment

        init {
            this.env = env
        }

        val artifactResolver: ArtifactResolver?
            get() = env.getSkyframeBuildView().getArtifactFactory()
    }

    /** SwigIncludeScanningContextImpl implements SwigIncludeScanningContext.  */
    class SwigIncludeScanningContextImpl internal constructor(
        env: CommandEnvironment,
        spawnScannerSupplier: Supplier<SpawnIncludeScanner?>,
        includePool: Supplier<ExecutorService?>
    ) : SwigIncludeScanningContext {
        private val env: CommandEnvironment
        private val spawnScannerSupplier: Supplier<SpawnIncludeScanner?>
        private val includePool: Supplier<ExecutorService?>
        private val cache: ConcurrentMap<Artifact?, ListenableFuture<MutableCollection<Inclusion?>?>?> =
            ConcurrentHashMap<Artifact?, ListenableFuture<MutableCollection<Inclusion?>?>?>()

        init {
            this.env = env
            this.spawnScannerSupplier = spawnScannerSupplier
            this.includePool = includePool
        }

        @Throws(IOException::class, ExecException::class, InterruptedException::class)
        override fun extractSwigIncludes(
            includes: MutableSet<Artifact?>?,
            actionExecutionMetadata: ActionExecutionMetadata?,
            actionExecContext: ActionExecutionContext,
            source: Artifact,
            legalOutputPaths: ImmutableSet<Artifact?>,
            swigIncludePaths: ImmutableList<PathFragment?>?,
            grepIncludes: Artifact?,
            grepIncludesExecutionPlatform: PlatformInfo?
        ) {
            val scanner =
                SwigIncludeScanner(
                    includePool.get(),
                    shouldShuffle(env),
                    spawnScannerSupplier.get(),
                    cache,
                    swigIncludePaths,
                    env.getDirectories(),
                    env.getSkyframeBuildView().getArtifactFactory(),
                    env.getExecRoot()
                )
            // For Swig include scanning, just point to the output file in the map.
            val pathToDeclaredHeader: ImmutableMap<PathFragment?, Artifact?> =
                legalOutputPaths.stream()
                    .collect(
                        ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                            Artifact::getExecPath,
                            Function { artifact: Any? -> artifact },  // Headers may be generated by shared actions. If both shared actions' outputs
                            // are present, just use the first (b/304564144).
                            BinaryOperator { a: Any?, b: Any? -> a })
                    )
            try {
                scanner.processAsync(
                    source,
                    ImmutableList.of<Artifact?>(source),
                    IncludeScanningHeaderData.Builder(
                        pathToDeclaredHeader,  /* modularHeaders= */ImmutableSet.of<Artifact?>()
                    )
                        .build(),
                    ImmutableList.of<String?>(),
                    includes,
                    actionExecutionMetadata,
                    actionExecContext,
                    grepIncludes,
                    grepIncludesExecutionPlatform
                )
            } catch (e: UncheckedIOException) {
                throw e.getCause()
            } catch (e: NoSuchPackageException) {
                throw IllegalStateException("Swig has no hints! For " + source, e)
            }
        }
    }

    /**
     * Lifecycle manager for the include scanner. Maintains an [ supplier][IncludeScannerSupplier] which can be used to access the (potentially shared) scanners and exposes [ ][.getSwigActionContext] [contexts][.getCppActionContext] based on them.
     */
    private class IncludeScannerLifecycleManager(
        env: CommandEnvironment,
        buildRequest: BuildRequest,
        spawnScannerSupplier: MutableSupplier<SpawnIncludeScanner?>,
        useIncludeHints: Boolean
    ) : ExecutorLifecycleListener {
        private val env: CommandEnvironment
        private val options: IncludeScanningOptions
        private val useIncludeHints: Boolean

        private val spawnScannerSupplier: Supplier<SpawnIncludeScanner?>
        private var includeScannerSupplier: IncludeScannerSupplier? = null
        private var includePool: ExecutorService? = null

        init {
            this.env = env
            this.options = buildRequest.getOptions(IncludeScanningOptions::class.java)
            this.useIncludeHints = useIncludeHints

            spawnScannerSupplier.set(
                SpawnIncludeScanner(
                    env.getExecRoot(),
                    options.getExperimentalRemoteExtractionThreshold(),
                    env.getSyscallCache()
                )
            )
            this.spawnScannerSupplier = spawnScannerSupplier
            env.getEventBus().register(this)
        }

        val cppActionContext: CppIncludeScanningContextImpl
            get() = CppIncludeScanningContextImpl(Supplier { includeScannerSupplier })

        val swigActionContext: SwigIncludeScanningContextImpl
            get() = SwigIncludeScanningContextImpl(
                env,
                spawnScannerSupplier,
                Supplier { includePool })

        @Throws(AbruptExitException::class, InterruptedException::class)
        override fun executionPhaseStarting(
            unusedActionGraph: ActionGraph?,
            unusedTopLevelArtifacts: Supplier<ImmutableSet<Artifact?>?>?,
            unusedCheck: EphemeralCheckIfOutputConsumed?
        ) {
            val hintsRules: HintsRules
            if (useIncludeHints) {
                try {
                    Profiler.instance().profile("evaluateSkyKeyForExecutionSetup").use { sc ->
                        hintsRules =
                            env.getSkyframeExecutor()
                                .evaluateSkyKeyForExecutionSetup(
                                    env.getReporter(), IncludeHintsFunction.Companion.INCLUDE_HINTS_KEY
                                ) as HintsRules
                    }
                } catch (e: ExecException) {
                    throw AbruptExitException(
                        DetailedExitCode.of(
                            FailureDetail.newBuilder()
                                .setMessage("could not initialize include hints: " + e.getMessage())
                                .setIncludeScanning(
                                    IncludeScanning.newBuilder()
                                        .setCode(IncludeScanning.Code.INITIALIZE_INCLUDE_HINTS_ERROR)
                                )
                                .build()
                        ),
                        e
                    )
                }
            } else {
                hintsRules = HintsRules.Companion.EMPTY
            }
            includeScannerSupplier!!.init(
                IncludeParser(
                    Hints(
                        hintsRules,
                        env.getSyscallCache(),
                        env.getSkyframeBuildView().getArtifactFactory()
                    )
                )
            )
        }

        override fun executionPhaseEnding() {
            if (options.getExperimentalReuseIncludeScanningThreads()) {
                if (includePool != null && !includePool.isShutdown()) {
                    ExecutorUtil.uninterruptibleShutdownNow(includePool)
                }
                includePool = null
            }
        }

        override fun executorCreated() {
            val useAsyncExecution: Boolean = useAsyncExecution(env)
            val threads = options.getIncludeScanningParallelism()
            if (useAsyncExecution) {
                includePool =
                    Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("Include scanner ", 0).factory()
                    )
            } else if (threads > 0) {
                logger.atInfo().log("Include scanning configured to use a pool with %d threads", threads)
                if (options.getExperimentalReuseIncludeScanningThreads()) {
                    includePool =
                        ThreadPoolExecutor(
                            threads,
                            threads,
                            0L,
                            TimeUnit.SECONDS,
                            SynchronousQueue<Runnable?>(),
                            ThreadFactoryBuilder().setNameFormat("Include scanner %d").build(),
                            RejectedExecutionHandler { r: Runnable?, e: ThreadPoolExecutor? -> r!!.run() })
                } else {
                    includePool = ExecutorUtil.newSlackPool(threads, "Include scanner")
                }
            } else {
                logger.atInfo().log("Include scanning configured to use a direct executor")
                includePool = MoreExecutors.newDirectExecutorService()
            }
            includeScannerSupplier =
                IncludeScannerSupplier(
                    env.getDirectories(),
                    includePool,
                    shouldShuffle(env),
                    env.getSkyframeBuildView().getArtifactFactory(),
                    spawnScannerSupplier,
                    env.getExecRoot()
                )

            spawnScannerSupplier.get()!!.setOutputService(env.getOutputService())
            spawnScannerSupplier.get()!!.setInMemoryOutput(options.getInMemoryIncludesFiles())
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        @VisibleForTesting
        fun getSkyFunctions(
            includeHintsFile: PathFragment?
        ): ImmutableMap<SkyFunctionName?, SkyFunction?> {
            val skyFunctions: ImmutableMap.Builder<SkyFunctionName?, SkyFunction?> =
                ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
            if (includeHintsFile != null) {
                skyFunctions.put(
                    IncludeScanningSkyFunctions.INCLUDE_HINTS, IncludeHintsFunction(includeHintsFile)
                )
            }
            return skyFunctions.buildOrThrow()
        }

        private fun useAsyncExecution(env: CommandEnvironment): Boolean {
            val buildRequestOptions: O? = env.getOptions().getOptions<O?>(BuildRequestOptions::class.java)
            return buildRequestOptions != null && buildRequestOptions.useAsyncExecution
        }

        private fun shouldShuffle(env: CommandEnvironment): Boolean {
            // Don't shuffle if using virtual threads, otherwise it introduces high CPU regression on
            // machines with large number of cores.
            return !useAsyncExecution(env)
        }
    }
}
