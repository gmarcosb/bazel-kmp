// Copyright 2024 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.analysis.BlazeDirectories

/**
 * A regular module extension defined with `module_extension` and used with `use_extension`.
 */
internal class RegularRunnableExtension(
    bzlLoadValue: BzlLoadValue,
    extension: ModuleExtension,
    staticEnvVars: com.google.common.collect.ImmutableMap<String?, java.util.Optional<String?>?>?,
    directories: BlazeDirectories,
    repoEnv: com.google.common.collect.ImmutableMap<String?, String?>?,
    nonstrictRepoEnv: com.google.common.collect.ImmutableMap<String?, String?>?,
    timeoutScaling: Double,
    processWrapper: ProcessWrapper?,
    repositoryRemoteExecutor: RepositoryRemoteExecutor?,
    downloadManager: DownloadManager?
) : RunnableExtension {
    private val bzlLoadValue: BzlLoadValue
    private val extension: ModuleExtension
    private val staticEnvVars: com.google.common.collect.ImmutableMap<String?, java.util.Optional<String?>?>?
    private val directories: BlazeDirectories
    private val repoEnv: com.google.common.collect.ImmutableMap<String?, String?>?
    private val nonstrictRepoEnv: com.google.common.collect.ImmutableMap<String?, String?>?
    private val timeoutScaling: Double
    private val processWrapper: ProcessWrapper?
    private val repositoryRemoteExecutor: RepositoryRemoteExecutor?
    private val downloadManager: DownloadManager?

    init {
        this.bzlLoadValue = bzlLoadValue
        this.extension = extension
        this.staticEnvVars = staticEnvVars
        this.directories = directories
        this.repoEnv = repoEnv
        this.nonstrictRepoEnv = nonstrictRepoEnv
        this.timeoutScaling = timeoutScaling
        this.processWrapper = processWrapper
        this.repositoryRemoteExecutor = repositoryRemoteExecutor
        this.downloadManager = downloadManager
    }

    val evalFactors: ModuleExtensionEvalFactors
        get() = ModuleExtensionEvalFactors.Companion.create(
            if (extension.osDependent) com.google.devtools.build.lib.util.OS.getCurrent().toString() else "",
            if (extension.archDependent) com.google.common.base.StandardSystemProperty.OS_ARCH.value() else ""
        )

    val bzlTransitiveDigest: ByteArray?
        get() = BazelModuleContext.of(bzlLoadValue.getModule()).bzlTransitiveDigest()

    val factsVersion: Int
        get() = extension.factsVersion

    @Throws(java.lang.InterruptedException::class, ExternalDepsException::class)
    override fun run(
        env: SkyFunction.Environment,
        usagesValue: SingleExtensionUsagesValue,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
        extensionId: ModuleExtensionId,
        mainRepositoryMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
        facts: Facts?
    ): RunModuleExtensionResult? {
        // See below (the `catch CancellationException` clause) for why there's a `while` loop here.
        while (true) {
            val state: WorkerSkyKeyComputeState<RunModuleExtensionResult?> =
                env.getState<WorkerSkyKeyComputeState<RunModuleExtensionResult?>>(java.util.function.Supplier { WorkerSkyKeyComputeState<Any?>() })
            try {
                return state.startOrContinueWork(
                    env,
                    "module-extension-" + extensionId,
                    WorkerCallable { workerEnv: SkyFunction.Environment? ->
                        runInternal(
                            workerEnv,
                            usagesValue,
                            starlarkSemantics,
                            extensionId,
                            mainRepositoryMapping,
                            facts
                        )
                    })
            } catch (e: ExecutionException) {
                com.google.common.base.Throwables.throwIfInstanceOf<ExternalDepsException?>(
                    e.getCause(),
                    ExternalDepsException::class.java
                )
                com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                    e.getCause(),
                    java.lang.InterruptedException::class.java
                )
                com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
                throw java.lang.IllegalStateException(
                    "unexpected exception type: " + e.getCause().getClass(), e.getCause()
                )
            } catch (e: CancellationException) {
                // This can only happen if the state object was invalidated due to memory pressure, in
                // which case we can simply reattempt eval.
            }
        }
    }

    @Throws(java.lang.InterruptedException::class, ExternalDepsException::class)
    private fun runInternal(
        env: SkyFunction.Environment,
        usagesValue: SingleExtensionUsagesValue,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
        extensionId: ModuleExtensionId,
        mainRepositoryMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
        facts: Facts?
    ): RunModuleExtensionResult? {
        env.getListener().post(ModuleExtensionEvaluationProgress.Companion.ongoing(extensionId, "starting"))
        val threadContext: ModuleExtensionEvalStarlarkThreadContext =
            ModuleExtensionEvalStarlarkThreadContext(
                extensionId,
                usagesValue.getExtensionUniqueName() + "+",
                extensionId.bzlFileLabel.getPackageIdentifier(),
                BazelModuleContext.of(bzlLoadValue.getModule()).repoMapping(),
                usagesValue.getRepoOverrides(),
                mainRepositoryMapping,
                env.getListener()
            )
        val moduleExtensionMetadata: ModuleExtensionMetadata?
        try {
            net.starlark.java.eval.Mutability.create("module extension", usagesValue.getExtensionUniqueName())
                .use { mu ->
                    createContext(
                        env,
                        usagesValue,
                        starlarkSemantics,
                        extensionId,
                        facts,
                        bzlLoadValue
                    ).use { moduleContext ->
                        val thread: net.starlark.java.eval.StarlarkThread =
                            net.starlark.java.eval.StarlarkThread.create(
                                mu,
                                starlarkSemantics,
                                "module extension " + extensionId,
                                net.starlark.java.eval.SymbolGenerator.create<ModuleExtensionId?>(extensionId)
                            )
                        thread.setPrintHandler(com.google.devtools.build.lib.events.Event.makeDebugPrintHandler(env.getListener()))
                        threadContext.storeInThread(thread)
                        moduleContext.storeRepoMappingRecorderInThread(thread)
                        try {
                            com.google.devtools.build.lib.profiler.Profiler.instance()
                                .profile(
                                    com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD,
                                    java.util.function.Supplier { "evaluate module extension: " + extensionId })
                                .use { c ->
                                    val returnValue: Any =
                                        net.starlark.java.eval.Starlark.positionalOnlyCall(
                                            thread,
                                            extension.implementation,
                                            moduleContext
                                        )
                                    if (returnValue !== net.starlark.java.eval.Starlark.NONE && returnValue !is ModuleExtensionMetadata) {
                                        throw ExternalDepsException.Companion.withMessage(
                                            ExternalDeps.Code.EXTENSION_EVAL_ERROR,
                                            "expected module extension %s to return None or extension_metadata, got %s",
                                            extensionId,
                                            net.starlark.java.eval.Starlark.type(returnValue)
                                        )
                                    }
                                    if (returnValue is ModuleExtensionMetadata) {
                                        moduleExtensionMetadata = returnValue
                                    } else {
                                        moduleExtensionMetadata = ModuleExtensionMetadata.Companion.DEFAULT
                                    }
                                }
                        } catch (e: NeedsSkyframeRestartException) {
                            // Restart by returning null.
                            return null
                        }
                        moduleContext.markSuccessful()
                        env.getListener().post(ModuleExtensionEvaluationProgress.Companion.finished(extensionId))
                        return RunModuleExtensionResult(
                            moduleContext.getRecordedInputs(), threadContext.createRepos(), moduleExtensionMetadata
                        )
                    }
                }
        } catch (e: net.starlark.java.eval.EvalException) {
            if (e.getCause() !is ExternalDepsException) {
                // ExternalDepsException events should already have been reported.
                env.getListener().handle(
                    com.google.devtools.build.lib.events.Event.error(
                        e.getInnermostLocation(),
                        e.getMessageWithStack()
                    )
                )
            }
            throw withMessage(
                ExternalDeps.Code.EXTENSION_EVAL_ERROR,
                "error evaluating module extension %s",
                extensionId
            )
        } catch (e: IOException) {
            throw ExternalDepsException.Companion.withCauseAndMessage(
                ExternalDeps.Code.EXTERNAL_DEPS_UNKNOWN,
                e,
                "Failed to clean up module context directory"
            )
        }
    }

    @Throws(ExternalDepsException::class)
    private fun createContext(
        env: SkyFunction.Environment?,
        usagesValue: SingleExtensionUsagesValue,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
        extensionId: ModuleExtensionId?,
        facts: Facts?,
        bzlLoadValue: BzlLoadValue
    ): ModuleExtensionContext {
        val staticRepoMappingRecorder: SimpleRepoMappingRecorder = SimpleRepoMappingRecorder()
        staticRepoMappingRecorder.record(bzlLoadValue.getRecordedRepoMappings())
        val workingDirectory: com.google.devtools.build.lib.vfs.Path? =
            directories
                .getOutputBase()
                .getRelative(LabelConstants.MODULE_EXTENSION_WORKING_DIRECTORY_LOCATION)
                .getRelative(usagesValue.getExtensionUniqueName())
        val modules: java.util.ArrayList<StarlarkBazelModule?> = java.util.ArrayList<StarlarkBazelModule?>()
        val abridgedModules: com.google.common.collect.ImmutableList<AbridgedModule> = usagesValue.getAbridgedModules()
        for (i in abridgedModules.indices) {
            val abridgedModule: AbridgedModule = abridgedModules.get(i)
            val moduleKey: ModuleKey? = abridgedModule.getKey()
            modules.add(
                StarlarkBazelModule.Companion.create(
                    abridgedModule,
                    extension,
                    usagesValue.getRepoMappings().get(moduleKey),
                    usagesValue.getExtensionUsages().get(moduleKey),
                    staticRepoMappingRecorder,
                    i
                )
            )
        }
        val rootUsage: ModuleExtensionUsage? = usagesValue.getExtensionUsages().get(ModuleKey.Companion.ROOT)
        val rootModuleHasNonDevDependency =
            rootUsage != null && rootUsage.getHasNonDevUseExtension()
        return ModuleExtensionContext(
            workingDirectory,
            directories,
            env,
            repoEnv,
            nonstrictRepoEnv,
            downloadManager,
            timeoutScaling,
            processWrapper,
            starlarkSemantics,
            repositoryRemoteExecutor,
            extensionId,
            net.starlark.java.eval.StarlarkList.immutableCopyOf<StarlarkBazelModule?>(modules),
            facts,
            rootModuleHasNonDevDependency,
            staticEnvVars,
            staticRepoMappingRecorder.recordedEntries()
        )
    }

    companion object {
        @Throws(ExternalDepsException::class, java.lang.InterruptedException::class)
        private fun loadBzlFile(
            bzlFileLabel: com.google.devtools.build.lib.cmdline.Label?,
            sampleUsageLocation: net.starlark.java.syntax.Location?,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics,
            env: SkyFunction.Environment
        ): BzlLoadValue? {
            // Check that the .bzl label isn't crazy.
            try {
                BzlLoadFunction.checkValidLoadLabel(bzlFileLabel, starlarkSemantics)
            } catch (e: LabelSyntaxException) {
                throw ExternalDepsException.Companion.withCauseAndMessage(
                    Code.BAD_MODULE, e, "invalid module extension label"
                )
            }

            // Load the .bzl file pointed to by the label.
            val bzlLoadValue: BzlLoadValue?
            try {
                bzlLoadValue =
                    env.getValueOrThrow<BzlLoadFailedException?>(
                        BzlLoadValue.keyForBzlmod(bzlFileLabel), BzlLoadFailedException::class.java
                    ) as BzlLoadValue?
            } catch (e: BzlLoadFailedException) {
                throw ExternalDepsException.Companion.withCauseAndMessage(
                    Code.BAD_MODULE,
                    e,
                    "Error loading '%s' for module extensions, requested by %s: %s",
                    bzlFileLabel,
                    sampleUsageLocation,
                    e.getMessage()
                )
            }
            return bzlLoadValue
        }

        /** Returns null if a Skyframe restart is required.  */
        @Throws(java.lang.InterruptedException::class, ExternalDepsException::class)
        fun load(
            extensionId: ModuleExtensionId,
            usagesValue: SingleExtensionUsagesValue,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics,
            env: SkyFunction.Environment,
            directories: BlazeDirectories,
            repoEnv: com.google.common.collect.ImmutableMap<String?, String?>?,
            nonstrictRepoEnv: com.google.common.collect.ImmutableMap<String?, String?>?,
            timeoutScaling: Double,
            processWrapper: ProcessWrapper?,
            repositoryRemoteExecutor: RepositoryRemoteExecutor?,
            downloadManager: DownloadManager?
        ): RegularRunnableExtension? {
            val sampleUsage: ModuleExtensionUsage = usagesValue.getExtensionUsages().values().iterator().next()
            val sampleUsageLocation: net.starlark.java.syntax.Location? =
                sampleUsage.getProxies().getFirst().getLocation()
            val bzlLoadValue: BzlLoadValue? =
                loadBzlFile(extensionId.bzlFileLabel, sampleUsageLocation, starlarkSemantics, env)
            if (bzlLoadValue == null) {
                return null
            }

            // TODO(wyv): Consider whether there's a need to check .bzl load visibility
            // (BzlLoadFunction#checkLoadVisibilities).
            // TODO(wyv): Consider refactoring to use PackageFunction#loadBzlModules, or the simpler API
            // that may be created by b/237658764.

            // Check that the .bzl file actually exports a module extension by our name.
            val exported: Any? = bzlLoadValue.getModule().getGlobal(extensionId.extensionName)
            if (exported !is ModuleExtension) {
                val exportedExtensions: com.google.common.collect.ImmutableSet<String?> =
                    bzlLoadValue.getModule().getGlobals().entrySet().stream()
                        .filter(java.util.function.Predicate { e: MutableMap.MutableEntry<String?, Any?>? -> e.getValue() is ModuleExtension })
                        .map<String?>(java.util.function.Function { obj: MutableMap.MutableEntry<String?, Any?>? -> obj.getKey() })
                        .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())
                throw ExternalDepsException.Companion.withMessage(
                    Code.BAD_MODULE,
                    "%s does not export a module extension called %s, yet its use is requested at %s%s",
                    extensionId.bzlFileLabel,
                    extensionId.extensionName,
                    sampleUsageLocation,
                    net.starlark.java.spelling.SpellChecker.didYouMean(extensionId.extensionName, exportedExtensions)
                )
            }

            val staticEnvVars: com.google.common.collect.ImmutableMap<String?, java.util.Optional<String?>?>? =
                RepoEnvironmentFunction.getEnvironmentView(
                    env, com.google.common.collect.ImmutableSet.copyOf<String?>(exported.envVariables)
                )
            if (staticEnvVars == null) {
                return null
            }
            return RegularRunnableExtension(
                bzlLoadValue,
                exported,
                staticEnvVars,
                directories,
                repoEnv,
                nonstrictRepoEnv,
                timeoutScaling,
                processWrapper,
                repositoryRemoteExecutor,
                downloadManager
            )
        }
    }
}
