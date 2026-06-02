// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.Artifact

/**
 * Handles --show_result and --experimental_show_artifacts.
 */
internal class BuildResultPrinter(env: CommandEnvironment) {
    private val env: CommandEnvironment

    init {
        this.env = env
    }

    /**
     * Shows the result of the build. Information includes the list of up-to-date and failed targets
     * and list of output artifacts for successful targets
     * 
     * 
     * This corresponds to the --show_result flag.
     */
    fun showBuildResult(
        request: BuildRequest,
        result: BuildResult,
        configuredTargets: MutableCollection<ConfiguredTarget>,
        configuredTargetsToSkip: MutableCollection<ConfiguredTarget?>,
        aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>
    ) {
        // NOTE: be careful what you print!  We don't want to create a consistency
        // problem where the summary message and the exit code disagree.  The logic
        // here is already complex.
        val ok =
            outputTargets(request, result, configuredTargets, configuredTargetsToSkip, aspects)
        if (!ok && !request.getOptions<ExecutionOptions?>(ExecutionOptions::class.java).getVerboseFailures()) {
            request
                .getOutErr()
                .printErr("Use --verbose_failures to see the command lines of failed build steps.\n")
        }
    }

    /**
     * Outputs the targets, omitting values with `(nothing to build)` when it allows staying
     * under the --show_result limit.
     * 
     * 
     * This method exits early if there are too many results.
     * 
     * @return `true` if no errors were detected among the results inspected, this can be a
     * false positive on early exit.
     */
    private fun outputTargets(
        request: BuildRequest,
        result: BuildResult,
        configuredTargets: MutableCollection<ConfiguredTarget>,
        configuredTargetsToSkip: MutableCollection<ConfiguredTarget?>,
        aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>
    ): Boolean {
        val runtime: BlazeRuntime = env.getRuntime()
        val productName: String? = runtime.getProductName()
        val prettyPrinter: PathPrettyPrinter =
            PathPrettyPrinter(
                env.getRelativeWorkingDirectory(),
                request.getBuildOptions().getSymlinkPrefix(productName),
                result.getConvenienceSymlinks()
            )
        val outErr: OutErr = request.getOutErr()

        // Filter and split aspects to display.
        val aspectsToIgnore: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.copyOf<String?>(request.getBuildOptions().getHideAspectResults())
        val partitionedAspectKeys =
            partitionAspectKeys(
                request.useValidationAspect(),
                aspects.keySet().stream()
                    .filter(java.util.function.Predicate { k: AspectKey? ->
                        !aspectsToIgnore.contains(
                            k.getAspectClass().getName()
                        )
                    })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<AspectKey?>())
            )

        val targetsToPrint: MutableCollection<ConfiguredTarget> = filterTargetsToPrint(configuredTargets)
        val context: TopLevelArtifactContext? = request.getTopLevelArtifactContext()

        // `essentialBudget` tracks the number of non-empty results that can be printed.
        var essentialBudget: Int = request.getBuildOptions().getMaxResultTargets()

        // Splits the targets we care about into three buckets. Targets are only considered successful
        // if they and their validation aspects succeeded.
        val skipped: java.util.ArrayList<ConfiguredTarget> = java.util.ArrayList<ConfiguredTarget>()
        val succeeded: java.util.ArrayList<ConfiguredTarget> = java.util.ArrayList<ConfiguredTarget>()
        val artifactsToPrintPerTarget: java.util.ArrayList<java.util.ArrayList<Artifact>> =
            java.util.ArrayList<java.util.ArrayList<Artifact>>()
        val failed: java.util.ArrayList<ConfiguredTarget> = java.util.ArrayList<ConfiguredTarget>()
        essentialBudget =
            splitConfiguredTargetsByResultReturnRemaining(
                targetsToPrint,
                result,
                context,
                configuredTargetsToSkip,
                partitionedAspectKeys.validationAspects,
                skipped,
                succeeded,
                artifactsToPrintPerTarget,
                failed,
                essentialBudget
            )
        if (essentialBudget < 0) {
            return failed.isEmpty()
        }

        // Splits the aspects we care about into two buckets.
        val successfulAspects: java.util.ArrayList<AspectKey> = java.util.ArrayList<AspectKey>()
        val failedAspects: java.util.ArrayList<AspectKey> = java.util.ArrayList<AspectKey>()
        val artifactsToPrintPerAspect: java.util.ArrayList<java.util.ArrayList<Artifact>> =
            java.util.ArrayList<java.util.ArrayList<Artifact>>(successfulAspects.size())
        essentialBudget =
            splitAspectsByResultReturnRemaining(
                partitionedAspectKeys.aspectsToPrint,
                aspects,
                context,
                result.getSuccessfulAspects(),
                successfulAspects,
                artifactsToPrintPerAspect,
                failedAspects,
                essentialBudget
            )
        if (essentialBudget < 0) {
            return failed.isEmpty() && failedAspects.isEmpty()
        }

        // Omits "nothing to build" values if it enables staying under --show_result.
        val omitNothingToBuild =
            ((targetsToPrint.size() + partitionedAspectKeys.aspectsToPrint.size())
                    > request.getBuildOptions().getMaxResultTargets())

        outputConfiguredTargets(
            outErr,
            prettyPrinter,
            succeeded,
            artifactsToPrintPerTarget,
            failed,
            skipped,
            omitNothingToBuild
        )
        outputAspects(
            outErr,
            prettyPrinter,
            successfulAspects,
            artifactsToPrintPerAspect,
            failedAspects,
            omitNothingToBuild
        )

        return failed.isEmpty() && failedAspects.isEmpty()
    }

    /**
     * Returns a list of configured targets that should participate in printing.
     * 
     * 
     * Hidden rules and other inserted targets are ignored.
     */
    private fun filterTargetsToPrint(
        configuredTargets: MutableCollection<ConfiguredTarget>
    ): MutableCollection<ConfiguredTarget> {
        val result: com.google.common.collect.ImmutableList.Builder<ConfiguredTarget?> =
            com.google.common.collect.ImmutableList.builder<ConfiguredTarget?>()
        for (configuredTarget in configuredTargets) {
            if (!TopLevelArtifactHelper.shouldConsiderForDisplay(configuredTarget)) {
                continue
            }
            if (configuredTarget is OutputFileConfiguredTarget) {
                // Suppress display of generated files (because they appear underneath
                // their generating rule), EXCEPT those ones which are not part of the
                // filesToBuild of their generating rule (e.g. .par, _deploy.jar
                // files), OR when a user explicitly requests an output file but not
                // its rule.
                val generatingRule: TransitiveInfoCollection =
                    (configuredTarget as OutputFileConfiguredTarget).getGeneratingRule()
                if (generatingRule
                        .getProvider(FileProvider::class.java)
                        .getFilesToBuild()
                        .toSet()
                        .containsAll(
                            configuredTarget.getProvider(FileProvider::class.java).getFilesToBuild().toList()
                        )
                    && configuredTargets.contains(generatingRule)
                ) {
                    continue
                }
            }

            result.add(configuredTarget)
        }
        return result.build()
    }

    private class PartitionedAspectKeys(
        aspectsToPrint: com.google.common.collect.ImmutableSet<AspectKey>,
        validationAspects: com.google.common.collect.ImmutableList<AspectKey?>
    ) {
        private val aspectsToPrint: com.google.common.collect.ImmutableSet<AspectKey>

        private val validationAspects: com.google.common.collect.ImmutableList<AspectKey?>

        init {
            this.aspectsToPrint = aspectsToPrint
            this.validationAspects = validationAspects
        }
    }

    companion object {
        private fun splitConfiguredTargetsByResultReturnRemaining(
            configuredTargets: MutableCollection<ConfiguredTarget>,
            result: BuildResult,
            context: TopLevelArtifactContext?,
            configuredTargetsToSkip: MutableCollection<ConfiguredTarget?>,
            validationAspects: com.google.common.collect.ImmutableList<AspectKey?>,
            skipped: java.util.ArrayList<ConfiguredTarget>,
            succeeded: java.util.ArrayList<ConfiguredTarget>,
            artifactsToPrintPerTarget: java.util.ArrayList<java.util.ArrayList<Artifact>>,
            failed: java.util.ArrayList<ConfiguredTarget>,
            essentialBudget: Int
        ): Int {
            var essentialBudget = essentialBudget
            val validationFailures: com.google.common.collect.ImmutableSet<ConfiguredTargetKey?> =
                validationAspects.stream()
                    .filter(java.util.function.Predicate { k: AspectKey? ->
                        !result.getSuccessfulAspects().contains(k)
                    })
                    .map<ConfiguredTargetKey?>(java.util.function.Function { obj: AspectKey? -> obj.getBaseConfiguredTargetKey() })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<ConfiguredTargetKey?>())
            val successfulTargets: MutableCollection<ConfiguredTarget?> = result.getSuccessfulTargets()
            for (target in configuredTargets) {
                if (configuredTargetsToSkip.contains(target)) {
                    skipped.add(target)
                    if (--essentialBudget < 0) {
                        return essentialBudget
                    }
                } else if (successfulTargets.contains(target)
                    && !validationFailures.contains(ConfiguredTargetKey.fromConfiguredTarget(target))
                ) {
                    succeeded.add(target)
                    val artifactsToPrint: java.util.ArrayList<Artifact?> = getArtifactsToPrint(target, context)
                    artifactsToPrintPerTarget.add(artifactsToPrint)
                    if (!artifactsToPrint.isEmpty()) {
                        if (--essentialBudget < 0) {
                            return essentialBudget
                        }
                    }
                } else {
                    failed.add(target)
                    if (--essentialBudget < 0) {
                        return essentialBudget
                    }
                }
            }
            return essentialBudget
        }

        private fun getArtifactsToPrint(
            target: ProviderCollection?, context: TopLevelArtifactContext?
        ): java.util.ArrayList<Artifact?> {
            val artifacts: java.util.ArrayList<Artifact?> = java.util.ArrayList<Artifact?>()
            // For up-to-date targets report generated artifacts, but only if they have associated action
            // and not runfiles trees.
            for (artifact in TopLevelArtifactHelper.getAllArtifactsToBuild(target, context)
                .getImportantArtifacts()
                .toList()) {
                if (TopLevelArtifactHelper.shouldDisplay(artifact)) {
                    artifacts.add(artifact)
                }
            }
            return artifacts
        }

        private fun splitAspectsByResultReturnRemaining(
            aspectsToPrint: MutableCollection<AspectKey>,
            aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>,
            context: TopLevelArtifactContext?,
            successfulAspects: com.google.common.collect.ImmutableSet<AspectKey?>,
            succeeded: java.util.ArrayList<AspectKey>,
            artifactsToPrintPerAspect: java.util.ArrayList<java.util.ArrayList<Artifact>>,
            failed: java.util.ArrayList<AspectKey>,
            essentialBudget: Int
        ): Int {
            var essentialBudget = essentialBudget
            for (aspect in aspectsToPrint) {
                if (successfulAspects.contains(aspect)) {
                    succeeded.add(aspect)
                    val artifactsToPrint: java.util.ArrayList<Artifact?> =
                        getArtifactsToPrint(aspects.get(aspect), context)
                    artifactsToPrintPerAspect.add(artifactsToPrint)
                    if (!artifactsToPrint.isEmpty()) {
                        if (--essentialBudget < 0) {
                            return essentialBudget
                        }
                    }
                } else {
                    failed.add(aspect)
                    if (--essentialBudget < 0) {
                        return essentialBudget
                    }
                }
            }
            return essentialBudget
        }

        private fun outputConfiguredTargets(
            outErr: OutErr,
            prettyPrinter: PathPrettyPrinter,
            succeeded: java.util.ArrayList<ConfiguredTarget>,
            artifactsToPrintPerTarget: java.util.ArrayList<java.util.ArrayList<Artifact>>,
            failed: java.util.ArrayList<ConfiguredTarget>,
            skipped: java.util.ArrayList<ConfiguredTarget>,
            omitNothingToBuild: Boolean
        ) {
            for (target in skipped) {
                outErr.printErr("Target " + target.getOriginalLabel() + " was skipped\n")
            }
            for (i in succeeded.indices) {
                val target: ConfiguredTarget = succeeded.get(i)
                val label: com.google.devtools.build.lib.cmdline.Label? = target.getLabel()
                val artifacts: java.util.ArrayList<Artifact> = artifactsToPrintPerTarget.get(i)
                if (artifacts.isEmpty()) {
                    if (!omitNothingToBuild) {
                        outErr.printErr("Target " + label + " up-to-date (nothing to build)\n")
                    }
                    continue
                }
                outErr.printErr("Target " + label + " up-to-date:\n")
                for (artifact in artifacts) {
                    outErr.printErrLn(formatArtifactForShowResults(prettyPrinter, artifact))
                }
            }
            for (target in failed) {
                outErr.printErr("Target " + target.getLabel() + " failed to build\n")

                // For failed compilation, it is still useful to examine temp artifacts, (ie, preprocessed and
                // assembler files).
                val topLevelProvider: OutputGroupInfo? = OutputGroupInfo.get(target)
                if (topLevelProvider != null) {
                    for (temp in topLevelProvider.getOutputGroup(OutputGroupInfo.TEMP_FILES).toList()) {
                        if (temp.getPath().exists()) {
                            outErr.printErrLn(
                                "  See temp at " + prettyPrinter.getPrettyPath(temp.getPath().asFragment())
                            )
                        }
                    }
                }
            }
        }

        private fun outputAspects(
            outErr: OutErr,
            prettyPrinter: PathPrettyPrinter,
            succeeded: java.util.ArrayList<AspectKey>,
            artifactsToPrintPerAspect: java.util.ArrayList<java.util.ArrayList<Artifact>>,
            failed: java.util.ArrayList<AspectKey>,
            omitNothingToBuild: Boolean
        ) {
            for (i in succeeded.indices) {
                val aspect: AspectKey = succeeded.get(i)
                val label: com.google.devtools.build.lib.cmdline.Label? = aspect.getLabel()
                val aspectName: String? = aspect.getAspectClass().getName()
                val artifacts: java.util.ArrayList<Artifact> = artifactsToPrintPerAspect.get(i)
                if (artifacts.isEmpty()) {
                    if (!omitNothingToBuild) {
                        outErr.printErr(
                            "Aspect " + aspectName + " of " + label + " up-to-date (nothing to build)\n"
                        )
                    }
                    continue
                }
                outErr.printErr("Aspect " + aspectName + " of " + label + " up-to-date:\n")
                for (artifact in artifacts) {
                    outErr.printErrLn(formatArtifactForShowResults(prettyPrinter, artifact))
                }
            }
            for (aspect in failed) {
                val label: com.google.devtools.build.lib.cmdline.Label? = aspect.getLabel()
                val aspectName: String? = aspect.getAspectClass().getName()
                outErr.printErr("Aspect " + aspectName + " of " + label + " failed to build\n")
            }
        }

        private fun formatArtifactForShowResults(
            prettyPrinter: PathPrettyPrinter, artifact: Artifact
        ): String {
            return "  " + prettyPrinter.getPrettyPath(artifact.getPath().asFragment())
        }

        /** Splits aspects based on whether they are validation aspects.  */
        private fun partitionAspectKeys(
            useValidationAspects: Boolean, keys: com.google.common.collect.ImmutableSet<AspectKey>
        ): PartitionedAspectKeys {
            if (!useValidationAspects) {
                return PartitionedAspectKeys(keys, com.google.common.collect.ImmutableList.of<AspectKey?>())
            }

            val aspectsToPrintBuilder: com.google.common.collect.ImmutableSet.Builder<AspectKey?> =
                com.google.common.collect.ImmutableSet.builder<AspectKey?>()
            val validationAspectsBuilder: com.google.common.collect.ImmutableList.Builder<AspectKey?> =
                com.google.common.collect.ImmutableList.builder<AspectKey?>()
            for (key in keys) {
                if (key.getAspectClass().getName() == AspectCollection.VALIDATION_ASPECT_NAME) {
                    validationAspectsBuilder.add(key)
                } else {
                    aspectsToPrintBuilder.add(key)
                }
            }
            return PartitionedAspectKeys(
                aspectsToPrintBuilder.build(), validationAspectsBuilder.build()
            )
        }
    }
}
