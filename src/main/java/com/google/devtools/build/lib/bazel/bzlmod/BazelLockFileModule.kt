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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue
import com.google.devtools.build.lib.bazel.bzlmod.BazelLockFileFunction
import com.google.devtools.build.lib.bazel.bzlmod.BazelLockFileValue
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionValue
import com.google.devtools.build.lib.bazel.bzlmod.Facts
import com.google.devtools.build.lib.bazel.bzlmod.GsonTypeAdapterUtil
import com.google.devtools.build.lib.bazel.bzlmod.LockFileModuleExtension
import com.google.devtools.build.lib.bazel.bzlmod.LockFileModuleExtension.WithFactors
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionEvalFactors
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionValue
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode
import com.google.devtools.build.lib.cmdline.LabelConstants
import com.google.devtools.build.lib.runtime.BlazeModule
import com.google.devtools.build.lib.runtime.CommandEnvironment
import com.google.devtools.build.lib.skyframe.PrecomputedValue
import com.google.devtools.build.lib.skyframe.SkyframeExecutor
import com.google.devtools.build.lib.vfs.Root
import com.google.devtools.build.lib.vfs.RootedPath
import com.google.devtools.build.skyframe.MemoizingEvaluator
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import java.io.IOException
import java.util.HashMap

/**
 * Module collecting Bazel module and module extensions resolution results and updating the
 * lockfile.
 */
class BazelLockFileModule : BlazeModule() {
    private var executor: SkyframeExecutor? = null
    private var workspaceRoot: com.google.devtools.build.lib.vfs.Path? = null
    private var outputBase: com.google.devtools.build.lib.vfs.Path? = null
    private var optionsLockfileMode: LockfileMode? = null

    override fun beforeCommand(env: CommandEnvironment) {
        executor = env.getSkyframeExecutor()
        workspaceRoot = env.getWorkspace()
        outputBase = env.getOutputBase()
        optionsLockfileMode =
            env.getOptions().getOptions<RepositoryOptions?>(RepositoryOptions::class.java).getLockfileMode()
    }

    override fun afterCommand() {
        val evaluator: MemoizingEvaluator = executor.getEvaluator()
        val moduleResolutionValue: BazelModuleResolutionValue?
        val depGraphValue: BazelDepGraphValue?
        val oldLockfile: BazelLockFileValue?
        var oldHiddenLockfile: BazelLockFileValue?
        try {
            val lockfileModeValue: PrecomputedValue? =
                evaluator.getExistingValue(BazelLockFileFunction.Companion.LOCKFILE_MODE.getKey()) as PrecomputedValue?
            if (lockfileModeValue == null) {
                // No command run on this server has triggered module resolution yet.
                return
            }
            // Check the Skyframe value in addition to the option since some commands (e.g. shutdown)
            // don't propagate the options to Skyframe, but we can only operate on Skyframe values that
            // were generated in UPDATE mode.
            val skyframeLockfileMode: LockfileMode = lockfileModeValue.get() as LockfileMode
            if (!(ENABLED_IN_MODES.contains(optionsLockfileMode)
                        && ENABLED_IN_MODES.contains(skyframeLockfileMode))
            ) {
                return
            }
            moduleResolutionValue =
                evaluator.getExistingValue(BazelModuleResolutionValue.Companion.KEY) as BazelModuleResolutionValue?
            depGraphValue = evaluator.getExistingValue(BazelDepGraphValue.Companion.KEY) as BazelDepGraphValue?
            oldLockfile = evaluator.getExistingValue(BazelLockFileValue.Companion.KEY) as BazelLockFileValue?
            oldHiddenLockfile =
                evaluator.getExistingValue(BazelLockFileValue.Companion.HIDDEN_KEY) as BazelLockFileValue?
        } catch (e: java.lang.InterruptedException) {
            // Not thrown in Bazel.
            throw java.lang.IllegalStateException(e)
        }
        if (moduleResolutionValue == null || depGraphValue == null || oldLockfile == null) {
            // Since these values are required to compute the main repo mapping, which happens in every
            // build, an error must have occurred that prevented the evaluation of these values and that
            // has already been reported at this point.
            return
        }
        if (oldHiddenLockfile == null) {
            oldHiddenLockfile = BazelLockFileValue.Companion.EMPTY_LOCKFILE
        }

        // All nodes corresponding to module extensions that have been evaluated in the current build
        // are done at this point. Look up entries by eval keys to record results even if validation
        // later fails due to invalid imports.
        // Note: This also picks up up-to-date results from previous builds that are not in the
        // transitive closure of the current build. Since extensions are potentially costly to evaluate,
        // this is seen as an advantage. Full reproducibility can be ensured by running 'bazel shutdown'
        // first if needed.
        val numExtensions: Int = depGraphValue.getExtensionUsagesTable().rowKeySet().size()
        val newExtensionInfos: HashMap<ModuleExtensionId?, WithFactors?> =
            HashMap<ModuleExtensionId?, WithFactors?>(numExtensions)
        val combinedFacts: HashMap<ModuleExtensionId?, Facts?> = HashMap<ModuleExtensionId?, Facts?>(numExtensions)
        combinedFacts.putAll(oldLockfile.getFacts())
        val combinedFactsVersions: HashMap<ModuleExtensionId?, Int?> = HashMap<ModuleExtensionId?, Int?>(numExtensions)
        combinedFactsVersions.putAll(oldLockfile.getFactsVersions())
        val doneValues: MutableMap<SkyKey?, SkyValue?> = evaluator.getDoneValues()
        for (extensionId in depGraphValue.getExtensionUsagesTable().rowKeySet()) {
            if (extensionId.isInnate()) {
                // The innate extensions are implemented in Java and don't benefit from a lockfile entry.
                continue
            }
            val value: SingleExtensionValue? =
                doneValues.get(SingleExtensionValue.Companion.evalKey(extensionId)) as SingleExtensionValue?
            if (value != null) {
                newExtensionInfos.put(extensionId, value.lockFileInfo.get())
                combinedFacts.put(extensionId, value.facts)
                combinedFactsVersions.put(extensionId, value.factsVersion)
            }
        }
        val relevantFacts: com.google.common.collect.ImmutableSortedMap<ModuleExtensionId?, Facts?> =
            com.google.common.collect.ImmutableSortedMap.copyOf<ModuleExtensionId?, Facts?>(
                com.google.common.collect.Maps.filterEntries<ModuleExtensionId?, Facts?>(
                    combinedFacts,
                    com.google.common.base.Predicate { entry: MutableMap.MutableEntry<ModuleExtensionId?, Facts?>? ->
                        depGraphValue.getExtensionUsagesTable().containsRow(entry.getKey())
                                && entry.getValue() != Facts.Companion.EMPTY
                    }),
                ModuleExtensionId.Companion.LEXICOGRAPHIC_COMPARATOR
            )
        // Only store non-zero versions for extensions that have facts persisted; the default is 0.
        val relevantFactsVersions: com.google.common.collect.ImmutableSortedMap<ModuleExtensionId?, Int?> =
            com.google.common.collect.ImmutableSortedMap.copyOf<ModuleExtensionId?, Int?>(
                com.google.common.collect.Maps.filterEntries<ModuleExtensionId?, Int?>(
                    combinedFactsVersions,
                    com.google.common.base.Predicate { entry: MutableMap.MutableEntry<ModuleExtensionId?, Int?>? ->
                        relevantFacts.containsKey(entry.getKey())
                                && entry.getValue() != null && entry.getValue() != 0
                    }),
                ModuleExtensionId.Companion.LEXICOGRAPHIC_COMPARATOR
            )

        val updateLockfile: java.lang.Thread =
            java.lang.Thread.startVirtualThread(
                java.lang.Runnable {
                    val notReproducibleExtensionInfos: com.google.common.collect.ImmutableMap<ModuleExtensionId?, com.google.common.collect.ImmutableMap<ModuleExtensionEvalFactors?, LockFileModuleExtension?>?> =
                        combineModuleExtensions(
                            oldLockfile.getModuleExtensions(),
                            newExtensionInfos,  /* hasUsages= */
                            java.util.function.Predicate { rowKey: ModuleExtensionId? ->
                                depGraphValue.getExtensionUsagesTable().containsRow(rowKey)
                            },  /* reproducible= */
                            false
                        )
                    // Bazel may track the hashes of files fetched from local registries for internal
                    // purposes, but those should never show up in the lockfile for two reasons:
                    // - they are not needed for reproducibility, as local registries are assumed to be
                    //   under the user's control, just like CLI flags;
                    // - they would contribute absolute paths and thus aren't portable.
                    val remoteRegistryFileHashes: com.google.common.collect.ImmutableSortedMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> =
                        com.google.common.collect.ImmutableSortedMap.copyOf<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>(
                            com.google.common.collect.Maps.filterKeys<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>(
                                moduleResolutionValue.getRegistryFileHashes(),
                                com.google.common.base.Predicate { url: String? -> !url.startsWith("file:") })
                        )

                    // Create an updated version of the lockfile, keeping only the extension results from
                    // the old lockfile that are still up-to-date and adding the newly resolved
                    // extension results, as long as any of them are not known to be reproducible.
                    val newLockfile: BazelLockFileValue =
                        BazelLockFileValue.Companion.builder()
                            .setRegistryFileHashes(remoteRegistryFileHashes)
                            .setSelectedYankedVersions(moduleResolutionValue.getSelectedYankedVersions())
                            .setModuleExtensions(notReproducibleExtensionInfos)
                            .setFacts(relevantFacts)
                            .setFactsVersions(relevantFactsVersions)
                            .build()

                    // Write the new values to the files, but only if needed. This is not just a
                    // performance optimization: whenever the lockfile is updated, most Skyframe nodes
                    // will be marked as dirty on the next build, which breaks commands such as `bazel
                    // config` that rely on
                    // com.google.devtools.build.skyframe.MemoizingEvaluator#getDoneValues.
                    if (newLockfile != oldLockfile) {
                        updateLockfile(workspaceRoot, newLockfile)
                    }
                })

        val oldHiddenLockfileFinal: BazelLockFileValue? = oldHiddenLockfile
        val updateHiddenLockfile: java.lang.Thread =
            java.lang.Thread.startVirtualThread(
                java.lang.Runnable {
                    // Results of reproducible extensions do not need to be stored for reproducibility,
                    // but avoiding reevaluations on server startups helps cold build performance.
                    val reproducibleExtensionInfos: com.google.common.collect.ImmutableMap<ModuleExtensionId?, com.google.common.collect.ImmutableMap<ModuleExtensionEvalFactors?, LockFileModuleExtension?>?> =
                        combineModuleExtensions(
                            oldHiddenLockfileFinal.getModuleExtensions(),
                            newExtensionInfos,  /* hasUsages= */
                            java.util.function.Predicate { rowKey: ModuleExtensionId? ->
                                depGraphValue.getExtensionUsagesTable().containsRow(rowKey)
                            },  /* reproducible= */
                            true
                        )
                    val newHiddenLockfile: BazelLockFileValue =
                        BazelLockFileValue.Companion.builder()
                            .setSelectedYankedVersions(com.google.common.collect.ImmutableMap.of<ModuleKey?, String?>())
                            .setModuleExtensions(reproducibleExtensionInfos)
                            .setFacts(relevantFacts)
                            .setFactsVersions(relevantFactsVersions)
                            .build()
                    if (newHiddenLockfile != oldHiddenLockfileFinal) {
                        updateLockfile(outputBase, newHiddenLockfile)
                    }
                })

        try {
            updateLockfile.join()
            updateHiddenLockfile.join()
        } catch (e: java.lang.InterruptedException) {
            java.lang.Thread.currentThread().interrupt()
            logger.atSevere().withCause(e).log(
                "Interrupted while updating MODULE.bazel.lock file: %s", e.getMessage()
            )
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private val ENABLED_IN_MODES: com.google.common.collect.ImmutableSet<LockfileMode?> =
            com.google.common.collect.Sets.immutableEnumSet<LockfileMode?>(LockfileMode.UPDATE, LockfileMode.REFRESH)

        /**
         * Combines the old extensions stored in the lockfile -if they are still used and have the same
         * dependence on os/arch - with the new extensions from the events (if any)
         */
        @com.google.common.annotations.VisibleForTesting
        fun combineModuleExtensions(
            oldExtensionInfos: MutableMap<ModuleExtensionId?, com.google.common.collect.ImmutableMap<ModuleExtensionEvalFactors?, LockFileModuleExtension?>?>,
            newExtensionInfos: MutableMap<ModuleExtensionId?, WithFactors?>,
            hasUsages: java.util.function.Predicate<ModuleExtensionId?>,
            reproducible: Boolean
        ): com.google.common.collect.ImmutableMap<ModuleExtensionId?, com.google.common.collect.ImmutableMap<ModuleExtensionEvalFactors?, LockFileModuleExtension?>?> {
            val updatedExtensionMap: MutableMap<ModuleExtensionId?, com.google.common.collect.ImmutableMap<ModuleExtensionEvalFactors?, LockFileModuleExtension?>?> =
                HashMap<ModuleExtensionId?, com.google.common.collect.ImmutableMap<ModuleExtensionEvalFactors?, LockFileModuleExtension?>?>()

            // Keep those per factor extension results that are still used according to the static
            // information given in the extension declaration (dependence on os and arch).
            // Other information such as transitive .bzl hash and usages hash are *not* checked here.
            for (entry in oldExtensionInfos.entrySet()) {
                val moduleExtensionId: ModuleExtensionId? = entry.getKey()
                if (!hasUsages.test(moduleExtensionId)) {
                    // Extensions without any usages are not needed anymore.
                    continue
                }
                val newExtensionInfo: WithFactors? = newExtensionInfos.get(moduleExtensionId)
                if (newExtensionInfo == null) {
                    // No information based on which we could invalidate old entries, keep all of them.
                    updatedExtensionMap.put(moduleExtensionId, entry.getValue())
                    continue
                }
                val newFactors: ModuleExtensionEvalFactors = newExtensionInfo.extensionFactors
                // Prefer the new result for its particular set of factors.
                val perFactorsResultsToKeep: com.google.common.collect.ImmutableSortedMap<ModuleExtensionEvalFactors?, LockFileModuleExtension?> =
                    com.google.common.collect.ImmutableSortedMap.copyOf<ModuleExtensionEvalFactors?, LockFileModuleExtension?>(
                        com.google.common.collect.Maps.filterKeys<ModuleExtensionEvalFactors?, LockFileModuleExtension?>(
                            entry.getValue(),
                            com.google.common.base.Predicate { oldFactors: ModuleExtensionEvalFactors? ->
                                oldFactors.hasSameDependenciesAs(newFactors)
                                        && oldFactors != newFactors
                            })
                    )
                if (perFactorsResultsToKeep.isEmpty()) {
                    continue
                }
                updatedExtensionMap.put(moduleExtensionId, perFactorsResultsToKeep)
            }

            // Add the new resolved extensions
            for (extensionIdAndInfo in newExtensionInfos.entrySet()) {
                val extension: LockFileModuleExtension = extensionIdAndInfo.getValue().moduleExtension
                if (extension.isReproducible() != reproducible) {
                    continue
                }

                val oldExtensionEntries: com.google.common.collect.ImmutableMap<ModuleExtensionEvalFactors?, LockFileModuleExtension?>? =
                    updatedExtensionMap.get(extensionIdAndInfo.getKey())
                val extensionEntries: com.google.common.collect.ImmutableMap<ModuleExtensionEvalFactors?, LockFileModuleExtension?>?
                val factors: ModuleExtensionEvalFactors = extensionIdAndInfo.getValue().extensionFactors
                if (oldExtensionEntries != null) {
                    // extension exists, add the new entry to the existing map
                    extensionEntries =
                        com.google.common.collect.ImmutableSortedMap.copyOf<ModuleExtensionEvalFactors?, LockFileModuleExtension?>(
                            com.google.common.collect.ImmutableMap.builder<ModuleExtensionEvalFactors?, LockFileModuleExtension?>()
                                .putAll(oldExtensionEntries)
                                .put(factors, extension)
                                .buildKeepingLast()
                        )
                } else {
                    // new extension
                    extensionEntries =
                        com.google.common.collect.ImmutableMap.of<ModuleExtensionEvalFactors?, LockFileModuleExtension?>(
                            factors,
                            extension
                        )
                }
                updatedExtensionMap.put(extensionIdAndInfo.getKey(), extensionEntries)
            }

            // The order in which extensions are added to extensionResolutionEvents depends on the order
            // in which their Skyframe evaluations finish, which is non-deterministic. We ensure a
            // deterministic lockfile by sorting.
            return com.google.common.collect.ImmutableSortedMap.copyOf<ModuleExtensionId?, com.google.common.collect.ImmutableMap<ModuleExtensionEvalFactors?, LockFileModuleExtension?>?>(
                updatedExtensionMap, ModuleExtensionId.Companion.LEXICOGRAPHIC_COMPARATOR
            )
        }

        /**
         * Updates the data stored in the lockfile (MODULE.bazel.lock)
         * 
         * @param lockfileRoot Root under which the lockfile is located
         * @param updatedLockfile The updated lockfile data to save
         */
        private fun updateLockfile(
            lockfileRoot: com.google.devtools.build.lib.vfs.Path?,
            updatedLockfile: BazelLockFileValue?
        ) {
            val lockfilePath: RootedPath =
                RootedPath.toRootedPath(Root.fromPath(lockfileRoot), LabelConstants.MODULE_LOCKFILE_NAME)
            try {
                com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(
                    lockfilePath.asPath(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    GsonTypeAdapterUtil.LOCKFILE_GSON.toJson(updatedLockfile) + "\n"
                )
            } catch (e: IOException) {
                logger.atSevere().withCause(e).log(
                    "Error while updating MODULE.bazel.lock file: %s", e.getMessage()
                )
            }
        }
    }
}
