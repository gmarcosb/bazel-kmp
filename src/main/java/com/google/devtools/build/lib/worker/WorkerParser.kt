// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.actions.ExecException

/**
 * A helper class to process a [Spawn] into a [WorkerKey], which is used to select a
 * persistent worker process (actions with equal keys are allowed to use the same worker process),
 * and a separate list of flag files. The result is encapsulated as a [WorkerConfig].
 */
class WorkerParser(
    execRoot: com.google.devtools.build.lib.vfs.Path?,
    workerOptions: WorkerOptions,
    localEnvProvider: LocalEnvProvider,
    binTools: BinTools?
) {
    /** The global execRoot.  */
    private val execRoot: com.google.devtools.build.lib.vfs.Path?

    private val workerOptions: WorkerOptions
    private val localEnvProvider: LocalEnvProvider
    private val binTools: BinTools?

    init {
        this.execRoot = execRoot
        this.workerOptions = workerOptions
        this.localEnvProvider = localEnvProvider
        this.binTools = binTools
    }

    /**
     * Processes the given [Spawn] and [SpawnExecutionContext] to compute the worker key.
     * This involves splitting the command line into the worker startup command and the separate list
     * of flag files. Returns a pair of the [WorkerKey] and list of flag files.
     */
    @Throws(ExecException::class, IOException::class, java.lang.InterruptedException::class)
    fun compute(spawn: Spawn, context: SpawnExecutionContext): WorkerConfig {
        // We assume that the spawn to be executed always gets at least one @flagfile.txt or
        // --flagfile=flagfile.txt argument, which contains the flags related to the work itself (as
        // opposed to start-up options for the executed tool). Thus, we can extract those elements from
        // its args and put them into the WorkRequest instead.
        val flagFiles: MutableList<String?> = java.util.ArrayList<String?>()
        val workerArgs: com.google.common.collect.ImmutableList<String?> =
            splitSpawnArgsIntoWorkerArgsAndFlagFiles(spawn, flagFiles)
        val env: com.google.common.collect.ImmutableMap<String?, String?>? =
            localEnvProvider.rewriteLocalEnv(spawn.getEnvironment(), binTools, "/tmp")

        val workerFiles: SortedMap<PathFragment?, ByteArray?> =
            WorkerFilesHash.getWorkerFilesWithDigests(spawn, context.inputMetadataProvider)

        val workerFilesCombinedHash: com.google.common.hash.HashCode = WorkerFilesHash.getCombinedHash(workerFiles)

        val key: WorkerKey =
            createWorkerKey(
                spawn,
                workerArgs,
                env,
                execRoot,
                workerFilesCombinedHash,
                workerFiles,
                workerOptions,
                context.speculating(),
                Spawns.getWorkerProtocolFormat(spawn)
            )
        return WorkerConfig(key, flagFiles)
    }

    /**
     * Splits the command-line arguments of the `Spawn` into the part that is used to start the
     * persistent worker (`workerArgs`) and the part that goes into the `WorkRequest`
     * protobuf (`flagFiles`).
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(UserExecException::class)
    fun splitSpawnArgsIntoWorkerArgsAndFlagFiles(
        spawn: Spawn, flagFiles: MutableList<String?>
    ): com.google.common.collect.ImmutableList<String?> {
        val workerArgs: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        val args: com.google.common.collect.ImmutableList<String> = spawn.getArguments()
        if (args.isEmpty()) {
            throwFlagFileFailure(REASON_NO_FLAGFILE, spawn)
        }
        if (workerOptions.getStrictFlagfiles()) {
            if (!isFlagFileArg(com.google.common.collect.Iterables.getLast<String?>(args))) {
                throwFlagFileFailure(REASON_NO_FINAL_FLAGFILE, spawn)
            }
            flagFiles.add(com.google.common.collect.Iterables.getLast<String?>(args))
            for (i in 0..<args.size() - 1) {
                if (isFlagFileArg(args.get(i))) {
                    throwFlagFileFailure(REASON_EXCESS_FLAGFILE, spawn)
                } else {
                    workerArgs.add(args.get(i))
                }
            }
        } else {
            for (arg in args) {
                if (isLegacyFlagFileArg(arg)) {
                    flagFiles.add(arg)
                } else {
                    workerArgs.add(arg)
                }
            }
            if (flagFiles.isEmpty()) {
                throwFlagFileFailure(REASON_NO_FLAGFILE, spawn)
            }
        }

        val mnemonicFlags: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()

        workerOptions.getWorkerExtraFlags().stream()
            .filter(java.util.function.Predicate { entry: MutableMap.MutableEntry<String?, String?>? ->
                entry.getKey() == Spawns.getWorkerKeyMnemonic(
                    spawn
                )
            })
            .forEach(java.util.function.Consumer { entry: MutableMap.MutableEntry<String?, String?>? ->
                mnemonicFlags.add(
                    entry.getValue()
                )
            })

        return workerArgs.add("--persistent_worker").addAll(mnemonicFlags.build()).build()
    }

    @Throws(UserExecException::class)
    private fun throwFlagFileFailure(reason: String?, spawn: Spawn) {
        val message: String? =
            java.lang.String.format(
                ERROR_MESSAGE_PREFIX + reason + "%n%s", spawn.getMnemonic(), spawn.getArguments()
            )
        throw UserExecException(
            FailureDetails.FailureDetail.newBuilder()
                .setMessage(message)
                .setWorker(
                    FailureDetails.Worker.newBuilder().setCode(FailureDetails.Worker.Code.NO_FLAGFILE)
                )
                .build()
        )
    }

    /** A pair of the [WorkerKey] and the list of flag files.  */
    class WorkerConfig(workerKey: WorkerKey?, flagFiles: MutableList<String?>) {
        private val workerKey: WorkerKey?
        val flagFiles: MutableList<String?>

        init {
            this.workerKey = workerKey
            this.flagFiles = com.google.common.collect.ImmutableList.copyOf<String?>(flagFiles)
        }

        fun getWorkerKey(): WorkerKey? {
            return workerKey
        }
    }

    companion object {
        private const val ERROR_MESSAGE_PREFIX = "Worker strategy cannot execute this %s action, "
        private const val REASON_NO_FLAGFILE =
            "because the command-line arguments do not contain exactly one @flagfile or --flagfile="
        private const val REASON_EXCESS_FLAGFILE =
            "because the command-line arguments has a @flagfile or --flagfile= argument before the end"
        private const val REASON_NO_FINAL_FLAGFILE =
            "because the command-line arguments does not end with a @flagfile or --flagfile= argument"

        /**
         * Pattern for @flagfile.txt and --flagfile=flagfile.txt. This doesn't handle @@-escapes, those
         * are checked for separately.
         */
        private val FLAG_FILE_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("(?:@|--?flagfile=)(.+)")

        /**
         * Legacy pattern for @flagfile.txt and --flagfile=flagfile.txt. This doesn't handle @@-escapes.
         */
        private val LEGACY_FLAG_FILE_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("(?:@|--?flagfile=)(.+)")

        /**
         * This method handles the logic of creating a WorkerKey (e.g., if sandboxing should be enabled or
         * not, when to use multiplex-workers)
         */
        @com.google.common.annotations.VisibleForTesting
        fun createWorkerKey(
            spawn: Spawn?,
            workerArgs: com.google.common.collect.ImmutableList<String?>?,
            env: com.google.common.collect.ImmutableMap<String?, String?>?,
            execRoot: com.google.devtools.build.lib.vfs.Path?,
            workerFilesCombinedHash: com.google.common.hash.HashCode?,
            workerFiles: SortedMap<PathFragment?, ByteArray?>?,
            options: WorkerOptions,
            dynamic: Boolean,
            protocolFormat: WorkerProtocolFormat?
        ): WorkerKey {
            val workerKeyMnemonic: String? = Spawns.getWorkerKeyMnemonic(spawn)
            val mustSandbox = dynamic || Spawns.usesPathMapping(spawn)
            val shouldMultiplex =
                options.getWorkerMultiplex() && Spawns.supportsMultiplexWorkers(spawn)
            val canSandboxMultiplex =
                options.getMultiplexSandboxing() && Spawns.supportsMultiplexSandboxing(spawn)
            val sandboxed: Boolean
            val multiplex: Boolean
            if (mustSandbox) {
                sandboxed = true
                multiplex = shouldMultiplex && canSandboxMultiplex
            } else if (shouldMultiplex) {
                sandboxed = canSandboxMultiplex
                multiplex = true
            } else {
                sandboxed = options.getWorkerSandboxing()
                multiplex = false
            }
            var useInMemoryTracking = false
            if (sandboxed) {
                val mnemonics: MutableList<String?>? = options.getWorkerSandboxInMemoryTracking()
                useInMemoryTracking = mnemonics != null && mnemonics.contains(workerKeyMnemonic)
            }
            return WorkerKey(
                workerArgs,
                env,
                execRoot,
                workerKeyMnemonic,
                workerFilesCombinedHash,
                workerFiles,
                sandboxed,
                useInMemoryTracking,
                multiplex,
                Spawns.supportsWorkerCancellation(spawn),
                protocolFormat
            )
        }

        private fun isFlagFileArg(arg: String): Boolean {
            return FLAG_FILE_PATTERN.matcher(arg).matches() && !arg.startsWith("@@")
        }

        private fun isLegacyFlagFileArg(arg: String?): Boolean {
            return LEGACY_FLAG_FILE_PATTERN.matcher(arg).matches()
        }
    }
}
