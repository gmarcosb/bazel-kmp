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
package com.google.devtools.build.lib.analysis.actions


import com.google.devtools.build.lib.actions.AbstractAction

/**
 * Utility methods that are the canonical way for actions to support path mapping (see [ ]).
 */
object PathMappers {
    // TODO: Remove actions from this list by adding ExecutionRequirements.SUPPORTS_PATH_MAPPING to
    //  their execution info instead.
    private val SUPPORTED_MNEMONICS: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.of<String?>(
            "AndroidLint",
            "CompileAndroidResources",
            "DeJetify",
            "DejetifySrcs",
            "Desugar",
            "DexBuilder",
            "Jetify",
            "JetifySrcs",
            "LinkAndroidResources",
            "MergeAndroidAssets",
            "MergeManifests",
            "ParseAndroidResources",
            "StarlarkAARGenerator",
            "StarlarkMergeCompiledAndroidResources",
            "StarlarkRClassGenerator",
            "Mock action"
        )

    /**
     * Actions that support path mapping should call this method from [ ][ActionAnalysisMetadata.getKey].
     * 
     * 
     * Compared to [.create], this method does not flatten nested sets and thus can't result
     * in memory regressions.
     * 
     * @param outputPathsMode the value of [CoreOptions.outputPathsMode]
     * @param fingerprint the fingerprint to add to
     */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun addToFingerprint(
        mnemonic: String?,
        executionInfo: MutableMap<String?, String?>,
        additionalArtifactsForPathMapping: NestedSet<Artifact?>?,
        actionKeyContext: ActionKeyContext,
        outputPathsMode: OutputPathsMode?,
        fingerprint: Fingerprint
    ) {
        // Creating a new PathMapper instance can be expensive, but isn't needed here: Whether and
        // how path mapping applies to the action only depends on the output paths mode and the action
        // inputs, which are already part of the action key.
        val effectiveOutputPathsMode: OutputPathsMode =
            getEffectiveOutputPathsMode(outputPathsMode, mnemonic, executionInfo)
        if (effectiveOutputPathsMode == OutputPathsMode.STRIP) {
            fingerprint.addString(StrippingPathMapper.Companion.GUID)
            // These artifacts are not part of the actual command line or inputs, but influence the
            // behavior of path mapping.
            actionKeyContext.addNestedSetToFingerprint(fingerprint, additionalArtifactsForPathMapping)
        }
    }

    /**
     * Actions that support path mapping should call this method when creating their [Spawn].
     * 
     * 
     * The returned [PathMapper] has to be passed to [ ][com.google.devtools.build.lib.actions.CommandLine.arguments], [ ][com.google.devtools.build.lib.actions.CommandLines.expand] )} or any other variants of these functions. The same instance
     * should also be passed to the [Spawn] constructor so that the executor can obtain it via
     * [Spawn.getPathMapper].
     * 
     * 
     * Note: This method flattens nested sets and should thus not be called from methods that are
     * executed in the analysis phase.
     * 
     * 
     * Actions calling this method should also call [.addToFingerprint] from [ ][ActionAnalysisMetadata.getKey] to ensure correct incremental
     * builds.
     * 
     * @param action the [AbstractAction] for which a [Spawn] is to be created
     * @param outputPathsMode the value of [CoreOptions.outputPathsMode]
     * @param isStarlarkAction whether the action is a Starlark action
     * @return a [PathMapper] that maps paths of the action's inputs and outputs. May be [     ][PathMapper.NOOP] if path mapping is not applicable to the action.
     */
    fun create(
        action: AbstractAction, outputPathsMode: OutputPathsMode?, isStarlarkAction: Boolean
    ): PathMapper? {
        if (getEffectiveOutputPathsMode(
                outputPathsMode, action.getMnemonic(), action.getExecutionInfo()
            )
            != OutputPathsMode.STRIP
        ) {
            return PathMapper.NOOP
        }
        return StrippingPathMapper.Companion.tryCreate(action, isStarlarkAction).orElse(PathMapper.NOOP)
    }

    /**
     * Helper method to simplify calling [.create] for actions that store the configuration
     * directly.
     * 
     * @param configuration the configuration
     * @return the value of
     */
    fun getOutputPathsMode(
        configuration: BuildConfigurationValue?
    ): OutputPathsMode? {
        if (configuration == null) {
            return OutputPathsMode.OFF
        }
        return configuration.getOptions().get<T?>(CoreOptions::class.java).getOutputPathsMode()
    }

    /**
     * Returns the effective [OutputPathsMode] for an action based on the action's mnemonic and
     * execution info. This may return a mode other than [OutputPathsMode.OFF] even though path
     * mapping will be disabled during execution due to path collisions.
     */
    fun getEffectiveOutputPathsMode(
        outputPathsMode: OutputPathsMode?, mnemonic: String?, executionInfo: MutableMap<String?, String?>
    ): OutputPathsMode {
        if (executionInfo.containsKey(ExecutionRequirements.LOCAL)
            || (executionInfo.containsKey(ExecutionRequirements.NO_SANDBOX)
                    && executionInfo.containsKey(ExecutionRequirements.NO_REMOTE))
        ) {
            // Path mapping requires sandboxed or remote execution.
            return OutputPathsMode.OFF
        }
        if (outputPathsMode == OutputPathsMode.STRIP
            && (SUPPORTED_MNEMONICS.contains(mnemonic)
                    || executionInfo.containsKey(ExecutionRequirements.SUPPORTS_PATH_MAPPING))
        ) {
            return OutputPathsMode.STRIP
        }
        return OutputPathsMode.OFF
    }
}
