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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.analysis.BlazeDirectories

/**
 * Evaluates a single module extension. This function loads the .bzl file containing the extension,
 * runs its implementation function with a module_ctx object containing all relevant information,
 * and returns the generated repos.
 */
class SingleExtensionEvalFunction(
    directories: BlazeDirectories?,
    repoEnvSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableMap<String?, String?>?>,
    nonstrictRepoEnvSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableMap<String?, String?>?>
) : SkyFunction {
    private val directories: BlazeDirectories?
    private val repoEnvSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableMap<String?, String?>?>
    private val nonstrictRepoEnvSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableMap<String?, String?>?>

    private var timeoutScaling = 1.0
    private var processWrapper: ProcessWrapper? = null
    private var repositoryRemoteExecutor: RepositoryRemoteExecutor? = null
    private var downloadManager: DownloadManager? = null

    init {
        this.directories = directories
        this.repoEnvSupplier = repoEnvSupplier
        this.nonstrictRepoEnvSupplier = nonstrictRepoEnvSupplier
    }

    fun setDownloadManager(downloadManager: DownloadManager?) {
        this.downloadManager = downloadManager
    }

    fun setTimeoutScaling(timeoutScaling: Double) {
        this.timeoutScaling = timeoutScaling
    }

    fun setProcessWrapper(processWrapper: ProcessWrapper?) {
        this.processWrapper = processWrapper
    }

    fun setRepositoryRemoteExecutor(repositoryRemoteExecutor: RepositoryRemoteExecutor?) {
        this.repositoryRemoteExecutor = repositoryRemoteExecutor
    }

    @Throws(SingleExtensionEvalFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? = PrecomputedValue.STARLARK_SEMANTICS.get(env)
        if (starlarkSemantics == null) {
            return null
        }
        val mainRepoMappingValue: RepositoryMappingValue? =
            env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN)) as RepositoryMappingValue?
        if (mainRepoMappingValue == null) {
            return null
        }

        val extensionId: ModuleExtensionId = skyKey.argument() as ModuleExtensionId
        val usagesValue: SingleExtensionUsagesValue? =
            env.getValue(SingleExtensionUsagesValue.Companion.key(extensionId)) as SingleExtensionUsagesValue?
        if (usagesValue == null) {
            return null
        }
        val extension: RunnableExtension?
        try {
            if (extensionId.isInnate()) {
                extension = InnateRunnableExtension.Companion.load(extensionId, usagesValue, starlarkSemantics, env)
            } else {
                extension =
                    RegularRunnableExtension.Companion.load(
                        extensionId,
                        usagesValue,
                        starlarkSemantics,
                        env,
                        directories,
                        repoEnvSupplier.get(),
                        nonstrictRepoEnvSupplier.get(),
                        timeoutScaling,
                        processWrapper,
                        repositoryRemoteExecutor,
                        downloadManager
                    )
            }
        } catch (e: ExternalDepsException) {
            throw SingleExtensionEvalFunctionException(e)
        }
        if (extension == null) {
            return null
        }

        // Check the lockfile first for that module extension
        val lockfileMode: LockfileMode? = BazelLockFileFunction.Companion.LOCKFILE_MODE.get(env)
        val currentFactsVersion: Int = extension.getFactsVersion()
        var lockfileFacts: Facts? = Facts.Companion.EMPTY
        // Store workspace lockfile facts separately for validation in ERROR mode
        var workspaceLockfileFacts: Facts? = Facts.Companion.EMPTY
        if (lockfileMode != LockfileMode.OFF) {
            val lockfiles: SkyframeLookupResult =
                env.getValuesAndExceptions(
                    com.google.common.collect.ImmutableList.of<SkyKey?>(
                        BazelLockFileValue.Companion.KEY,
                        BazelLockFileValue.Companion.HIDDEN_KEY
                    )
                )
            val workspaceLockfile: BazelLockFileValue? =
                lockfiles.get(BazelLockFileValue.Companion.KEY) as BazelLockFileValue?
            val hiddenLockfile: BazelLockFileValue? =
                lockfiles.get(BazelLockFileValue.Companion.HIDDEN_KEY) as BazelLockFileValue?
            if (workspaceLockfile == null || hiddenLockfile == null) {
                return null
            }
            // Prefer the workspace lockfile facts when present, falling back to the hidden lockfile.
            // In both cases, facts whose stored factsVersion differs from the current extension's
            // facts_version are discarded: the extension's schema may have changed.
            if (workspaceLockfile.getFacts().containsKey(extensionId)) {
                val workspaceFactsVersion: Int =
                    workspaceLockfile.getFactsVersions().getOrDefault(extensionId, 0)
                if (workspaceFactsVersion == currentFactsVersion) {
                    workspaceLockfileFacts = workspaceLockfile.getFacts().get(extensionId)
                    lockfileFacts = workspaceLockfileFacts
                }
            } else {
                val hiddenFactsVersion: Int = hiddenLockfile.getFactsVersions().getOrDefault(extensionId, 0)
                if (hiddenFactsVersion == currentFactsVersion) {
                    lockfileFacts = hiddenLockfile.getFacts().getOrDefault(extensionId, Facts.Companion.EMPTY)
                }
            }
            var lockedExtensionMap: com.google.common.collect.ImmutableMap<ModuleExtensionEvalFactors?, LockFileModuleExtension?>? =
                workspaceLockfile.getModuleExtensions().get(extensionId)
            var lockedExtension: LockFileModuleExtension? =
                if (lockedExtensionMap == null) null else lockedExtensionMap.get(extension.getEvalFactors())
            if (lockedExtension == null) {
                lockedExtensionMap = hiddenLockfile.getModuleExtensions().get(extensionId)
                lockedExtension =
                    if (lockedExtensionMap == null) null else lockedExtensionMap.get(extension.getEvalFactors())
            }
            if (lockedExtension != null) {
                try {
                    com.google.devtools.build.lib.profiler.Profiler.instance()
                        .profile(
                            com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD,
                            java.util.function.Supplier { "check lockfile for " + extensionId }).use { c ->
                            val singleExtensionValue: SingleExtensionValue? =
                                tryGettingValueFromLockFile(
                                    env,
                                    extensionId,
                                    extension,
                                    usagesValue,
                                    extension.getEvalFactors(),
                                    lockedExtension,
                                    lockfileFacts
                                )
                            if (singleExtensionValue != null) {
                                return singleExtensionValue
                            }
                        }
                } catch (e: NeedsSkyframeRestartException) {
                    return null
                }
            }
        }

        // Run that extension!
        val moduleExtensionResult: RunModuleExtensionResult?
        try {
            moduleExtensionResult =
                extension.run(
                    env,
                    usagesValue,
                    starlarkSemantics,
                    extensionId,
                    mainRepoMappingValue.repositoryMapping,
                    lockfileFacts
                )
        } catch (e: ExternalDepsException) {
            throw SingleExtensionEvalFunctionException(e)
        }
        if (moduleExtensionResult == null) {
            return null
        }
        val generatedRepoSpecs: com.google.common.collect.ImmutableMap<String?, RepoSpec?> =
            moduleExtensionResult.generatedRepoSpecs
        val moduleExtensionMetadata: ModuleExtensionMetadata =
            moduleExtensionResult.moduleExtensionMetadata

        if (lockfileMode != LockfileMode.OFF) {
            val nonVisibleRepoNames: String =
                moduleExtensionResult.recordedInputs.stream()
                    .filter(
                        java.util.function.Predicate { inputAndValue: WithValue? ->
                            inputAndValue.input is RecordedRepoMapping
                                    && inputAndValue.value == null
                        })
                    .map<RecordedRepoMapping?>(java.util.function.Function { entry: WithValue? -> entry.input as RecordedRepoMapping })
                    .map<String?>(java.util.function.Function { RepoRecordedInput.RecordedRepoMapping.apparentName() })
                    .map<String?>(java.util.function.Function { apparentName: String? -> "@" + apparentName })
                    .collect(Collectors.joining(", "))
            if (!nonVisibleRepoNames.isEmpty()) {
                env.getListener()
                    .handle(
                        com.google.devtools.build.lib.events.Event.warn(
                            java.lang.String.format(
                                "The module extension %s produced an invalid lockfile entry because it"
                                        + " referenced %s. Please report this issue to its maintainers.",
                                extensionId, nonVisibleRepoNames
                            )
                        )
                    )
            }
        }
        if (lockfileMode == LockfileMode.ERROR && !moduleExtensionMetadata.getReproducible()) {
            // The extension is not reproducible and can't be in the lockfile, since an existing (but
            // possibly out-of-date) entry would have been handled by tryGettingValueFromLockFile above.
            throw SingleExtensionEvalFunctionException(
                ExternalDepsException.Companion.withMessage(
                    Code.BAD_LOCKFILE,
                    "The module extension '%s'%s does not exist in the lockfile",
                    extensionId,
                    if (extension.getEvalFactors().isEmpty())
                        ""
                    else
                        " for platform " + extension.getEvalFactors()
                )
            )
        }
        val newFacts: Facts = moduleExtensionMetadata.getFacts()
        // In ERROR mode, validate facts only against the workspace lockfile, not the hidden lockfile.
        // The hidden lockfile may contain stale facts from a different version (e.g., after a
        // rollback), which would cause false-positive validation errors.
        if (lockfileMode == LockfileMode.ERROR && newFacts != workspaceLockfileFacts) {
            val reason: String? =
                "the extension '%s' has changed its facts: %s != %s"
                    .formatted(
                        extensionId,
                        net.starlark.java.eval.Starlark.repr(newFacts.value(), starlarkSemantics),
                        net.starlark.java.eval.Starlark.repr(workspaceLockfileFacts.value(), starlarkSemantics)
                    )
            throw createOutdatedLockfileException(reason)
        }

        val lockfileModuleExtensionMetadata: java.util.Optional<LockfileModuleExtensionMetadata?> =
            LockfileModuleExtensionMetadata.Companion.of(moduleExtensionMetadata)
        val lockFileInfo: java.util.Optional<WithFactors?>?
        // At this point the extension has been evaluated successfully, but SingleExtensionEvalFunction
        // may still fail if imported repositories were not generated. However, since imports do not
        // influence the evaluation of the extension and the validation also runs when the extension
        // result is taken from the lockfile, we can already populate the lockfile info. This is
        // necessary to prevent the extension from rerunning when only the imports change.
        if (lockfileMode == LockfileMode.UPDATE || lockfileMode == LockfileMode.REFRESH) {
            lockFileInfo =
                java.util.Optional.of<WithFactors?>(
                    WithFactors(
                        extension.getEvalFactors(),
                        LockFileModuleExtension.Companion.builder()
                            .setBzlTransitiveDigest(extension.getBzlTransitiveDigest())
                            .setUsagesDigest(
                                SingleExtensionUsagesValue.Companion.hashForEvaluation(
                                    GsonTypeAdapterUtil.SINGLE_EXTENSION_USAGES_VALUE_GSON, usagesValue
                                )
                            )
                            .setRecordedInputs(moduleExtensionResult.recordedInputs)
                            .setGeneratedRepoSpecs(generatedRepoSpecs)
                            .setModuleExtensionMetadata(lockfileModuleExtensionMetadata)
                            .build()
                    )
                )
        } else {
            lockFileInfo = java.util.Optional.empty<WithFactors?>()
        }
        return createSingleExtensionValue(
            generatedRepoSpecs,
            lockfileModuleExtensionMetadata,
            extensionId,
            usagesValue,
            lockFileInfo,
            newFacts,
            currentFactsVersion,
            env
        )
    }

    /**
     * Tries to get the evaluation result from the lockfile, if it's still up-to-date. Otherwise,
     * returns `null`.
     * 
     * @throws NeedsSkyframeRestartException in case we need a skyframe restart. Note that we
     * *don't* return `null` in this case!
     */
    @Throws(
        SingleExtensionEvalFunctionException::class,
        java.lang.InterruptedException::class,
        NeedsSkyframeRestartException::class
    )
    private fun tryGettingValueFromLockFile(
        env: SkyFunction.Environment,
        extensionId: ModuleExtensionId,
        extension: RunnableExtension,
        usagesValue: SingleExtensionUsagesValue,
        evalFactors: ModuleExtensionEvalFactors?,
        lockedExtension: LockFileModuleExtension,
        facts: Facts?
    ): SingleExtensionValue? {
        val lockfileMode: LockfileMode? = BazelLockFileFunction.Companion.LOCKFILE_MODE.get(env)
        val diffRecorder =
            DiffRecorder( /* recordMessages= */lockfileMode == LockfileMode.ERROR)
        try {
            // Put faster diff detections earlier, so that we can short-circuit in UPDATE mode.
            if (!java.util.Arrays.equals(
                    extension.getBzlTransitiveDigest(), lockedExtension.getBzlTransitiveDigest()
                )
            ) {
                diffRecorder.record(
                    ("the implementation of the extension '"
                            + extensionId
                            + "' or one of its transitive .bzl files has changed")
                )
            }
            // Check extension data in lockfile is still valid, disregarding usage information that is not
            // relevant for the evaluation of the extension.
            if (!java.util.Arrays.equals(
                    SingleExtensionUsagesValue.Companion.hashForEvaluation(
                        GsonTypeAdapterUtil.SINGLE_EXTENSION_USAGES_VALUE_GSON, usagesValue
                    ),
                    lockedExtension.getUsagesDigest()
                )
            ) {
                diffRecorder.record("the usages of the extension '" + extensionId + "' have changed")
            }
            val reason: java.util.Optional<String?> =
                didRecordedInputsChange(env, directories, lockedExtension.getRecordedInputs())
            if (reason.isPresent()) {
                diffRecorder.record(
                    "an input to the extension '" + extensionId + "' changed: " + reason.get()
                )
            }
        } catch (ignored: DiffFoundEarlyExitException) {
            // ignored
        }
        // There is intentionally no diff check for facts - they are never invalidated by Bazel.
        if (!diffRecorder.anyDiffsDetected()) {
            return createSingleExtensionValue(
                lockedExtension.getGeneratedRepoSpecs(),
                lockedExtension.getModuleExtensionMetadata(),
                extensionId,
                usagesValue,
                java.util.Optional.of<WithFactors?>(WithFactors(evalFactors, lockedExtension)),
                facts,
                extension.getFactsVersion(),
                env
            )
        }
        // Reproducible extensions are always locked in the hidden lockfile to provide best-effort
        // speedups, but should never result in an error if out-of-date.
        if (lockfileMode == LockfileMode.ERROR && !lockedExtension.isReproducible()) {
            throw createOutdatedLockfileException(diffRecorder.recordedDiffMessages)
        }
        return null
    }

    private class DiffFoundEarlyExitException : java.lang.Exception()

    private class DiffRecorder(recordMessages: Boolean) {
        private var diffDetected = false
        private val diffMessages: com.google.common.collect.ImmutableList.Builder<String?>?

        init {
            diffMessages = if (recordMessages) com.google.common.collect.ImmutableList.builder<String?>() else null
        }

        @Throws(DiffFoundEarlyExitException::class)
        fun record(message: String) {
            diffDetected = true
            if (diffMessages != null) {
                diffMessages.add(message)
            } else {
                throw DiffFoundEarlyExitException()
            }
        }

        fun anyDiffsDetected(): Boolean {
            return diffDetected
        }

        val recordedDiffMessages: String
            get() = java.lang.String.join(",", diffMessages.build())
    }

    @Throws(SingleExtensionEvalFunctionException::class)
    private fun createSingleExtensionValue(
        generatedRepoSpecs: com.google.common.collect.ImmutableMap<String?, RepoSpec?>,
        moduleExtensionMetadata: java.util.Optional<LockfileModuleExtensionMetadata?>,
        extensionId: ModuleExtensionId,
        usagesValue: SingleExtensionUsagesValue,
        lockFileInfo: java.util.Optional<WithFactors?>?,
        facts: Facts?,
        factsVersion: Int,
        env: SkyFunction.Environment
    ): SingleExtensionValue {
        var fixup: java.util.Optional<RootModuleFileFixup?>? = java.util.Optional.empty<RootModuleFileFixup?>()
        if (moduleExtensionMetadata.isPresent()
            && usagesValue.getExtensionUsages().containsKey(ModuleKey.Companion.ROOT)
        ) {
            try {
                // TODO: ModuleExtensionMetadata#generateFixup should throw ExternalDepsException instead of
                // EvalException.
                fixup =
                    moduleExtensionMetadata
                        .get()
                        .generateFixup(
                            usagesValue.getExtensionUsages().get(ModuleKey.Companion.ROOT),
                            generatedRepoSpecs.keySet()
                        )
            } catch (e: net.starlark.java.eval.EvalException) {
                env.getListener().handle(
                    com.google.devtools.build.lib.events.Event.error(
                        e.getInnermostLocation(),
                        e.getMessageWithStack()
                    )
                )
                throw SingleExtensionEvalFunctionException(
                    ExternalDepsException.Companion.withMessage(
                        Code.BAD_MODULE,
                        "error evaluating module extension %s in %s",
                        extensionId.extensionName,
                        extensionId.bzlFileLabel
                    )
                )
            }
        }

        return SingleExtensionValue(
            generatedRepoSpecs,
            generatedRepoSpecs.keySet().stream()
                .collect(
                    com.google.common.collect.ImmutableBiMap.toImmutableBiMap<String?, RepositoryName?, String?>(
                        java.util.function.Function { e: String? ->
                            SingleExtensionValue.Companion.repositoryName(
                                usagesValue.getExtensionUniqueName(), e
                            )
                        },
                        java.util.function.Function.identity<String?>()
                    )
                ),
            lockFileInfo,
            fixup,
            facts,
            factsVersion
        )
    }

    private class SingleExtensionEvalFunctionException(cause: ExternalDepsException?) :
        SkyFunctionException(cause, Transience.PERSISTENT)

    companion object {
        @Throws(java.lang.InterruptedException::class, NeedsSkyframeRestartException::class)
        private fun didRecordedInputsChange(
            env: SkyFunction.Environment,
            directories: BlazeDirectories?,
            recordedInputs: MutableList<WithValue?>
        ): java.util.Optional<String?> {
            // Check inputs in batches to prevent Skyframe cycles caused by outdated dependencies.
            for (batch in RepoRecordedInput.WithValue.splitIntoBatches(recordedInputs)) {
                val outdated: java.util.Optional<String?> =
                    RepoRecordedInput.isAnyValueOutdated(env, directories, batch)
                if (env.valuesMissing()) {
                    throw NeedsSkyframeRestartException()
                }
                if (outdated.isPresent()) {
                    return outdated
                }
            }
            return java.util.Optional.empty<String?>()
        }

        private fun createOutdatedLockfileException(
            reason: String?
        ): SingleExtensionEvalFunctionException {
            return SingleExtensionEvalFunctionException(
                withMessage(
                    Code.BAD_LOCKFILE,
                    "MODULE.bazel.lock is no longer up-to-date because %s. Please run `bazel mod deps"
                            + " --lockfile_mode=update` to update your lockfile.",
                    reason
                )
            )
        }
    }
}
