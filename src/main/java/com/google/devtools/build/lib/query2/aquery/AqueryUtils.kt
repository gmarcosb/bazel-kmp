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
package com.google.devtools.build.lib.query2.aquery

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/** Utility class for Aquery  */
object AqueryUtils {
    /**
     * Returns the set of action inputs according to the --include_pruned_inputs flag.
     * 
     * 
     * This may differ from [ActionAnalysisMetadata.getInputs] for actions that discover
     * inputs.
     * 
     * @param action the analysis metadata of an action
     * @param includePrunedInputs the value of the --include_pruned_inputs flag
     */
    fun getActionInputs(
        action: ActionAnalysisMetadata, includePrunedInputs: Boolean
    ): NestedSet<Artifact?> {
        if (includePrunedInputs
            || (action is ActionExecutionMetadata
                    && !action.inputsKnown())
        ) {
            // getInputs() is potentially missing inputs that will be added by discovery (if the action
            // hasn't yet executed) and inputs that have been removed by discovery (if the action has
            // already executed). Instead, assemble the inputs from getOriginalInputs() and
            // getSchedulingDependencies(), which also include those added or removed by discovery. This
            // comment is not applicable for Starlark unused_input_list actions, which are always returned
            // to a pre-input-discovery state after execution.
            return NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .addTransitive(action.getOriginalInputs())
                .addTransitive(action.getSchedulingDependencies())
                .build()
        }
        return action.getInputs()
    }

    /**
     * Return true if the given `action` matches the filters specified in `actionFilters`.
     * 
     * @param action the analysis metadata of an action
     * @param actionFilters the filters parsed from the query expression
     * @param includePrunedInputs the value of the --include_pruned_inputs flag
     * @return whether the action matches the filtering patterns
     */
    fun matchesAqueryFilters(
        action: ActionAnalysisMetadata,
        actionFilters: AqueryActionFilter,
        includePrunedInputs: Boolean
    ): Boolean {
        val inputs: NestedSet<Artifact?> = getActionInputs(action, includePrunedInputs)
        val outputs: Iterable<Artifact?> = action.getOutputs()
        val mnemonic: String? = action.getMnemonic()

        if (actionFilters.hasFilterForFunction(MnemonicFunction.MNEMONIC)) {
            if (!actionFilters.matchesAllPatternsForFunction(MnemonicFunction.MNEMONIC, mnemonic)) {
                return false
            }
        }

        if (actionFilters.hasFilterForFunction(InputsFunction.INPUTS)) {
            val containsFile: Boolean =
                inputs.toList().stream()
                    .anyMatch(
                        { artifact ->
                            actionFilters.matchesAllPatternsForFunction(
                                InputsFunction.INPUTS, artifact.getExecPathString()
                            )
                        })

            if (!containsFile) {
                return false
            }
        }

        if (actionFilters.hasFilterForFunction(OutputsFunction.OUTPUTS)) {
            val containsFile: Boolean =
                com.google.common.collect.Streams.stream<Artifact?>(outputs)
                    .anyMatch(
                        java.util.function.Predicate { artifact: Artifact? ->
                            actionFilters.matchesAllPatternsForFunction(
                                OutputsFunction.OUTPUTS, artifact.getExecPathString()
                            )
                        })

            return containsFile
        }

        return true
    }

    @Throws(IOException::class)
    fun getTemplateContent(action: TemplateExpansionAction): String {
        // If the template artifact is a DerivedArtifact, it is only available during the execution
        // phase. It's therefore not possible to read its content from the FileSystem at this moment.
        if (action.getTemplate().getTemplateArtifact() is DerivedArtifact) {
            return action.getTemplate().toString()
        }
        return action.getTemplate().getContent(ArtifactPathResolver.IDENTITY)
    }
}
