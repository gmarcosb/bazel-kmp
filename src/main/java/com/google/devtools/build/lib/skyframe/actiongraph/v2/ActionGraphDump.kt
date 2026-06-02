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
package com.google.devtools.build.lib.skyframe.actiongraph.v2

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.query2.aquery.AqueryUtils.getActionInputs
import com.google.devtools.build.lib.util.Pair
import net.starlark.java.eval.EvalException

/**
 * Encapsulates necessary functionality to dump the current skyframe state of the action graph to
 * proto format.
 */
class ActionGraphDump(
    actionGraphTargets: MutableList<String?>,
    private val includeActionCmdLine: Boolean,
    private val includeArtifacts: Boolean,
    private val includePrunedInputs: Boolean,
    actionFilters: AqueryActionFilter?,
    includeParamFiles: Boolean,
    includeFileWriteContents: Boolean,
    aqueryOutputHandler: AqueryOutputHandler,
    eventHandler: ExtendedEventHandler?
) {
    val actionKeyContext: ActionKeyContext = ActionKeyContext()
    private val actionGraphTargets: MutableSet<String?>
    private val knownArtifacts: KnownArtifacts
    private val knownConfigurations: KnownConfigurations
    private val knownNestedSets: KnownNestedSets
    private val knownAspectDescriptors: KnownAspectDescriptors
    private val knownTargets: KnownTargets
    private val actionFilters: AqueryActionFilter?
    private val includeParamFiles: Boolean
    private val includeFileWriteContents: Boolean
    private val aqueryOutputHandler: AqueryOutputHandler
    private val eventHandler: ExtendedEventHandler?

    private var paramFileNameToContentMap: MutableMap<String?, Iterable<String?>?>? = null
        /** Lazy initialization of paramFileNameToContentMap.  */
        get() {
            if (field == null) {
                field = HashMap<String?, Iterable<String?>?>()
            }
            return field
        }

    constructor(
        includeActionCmdLine: Boolean,
        includeArtifacts: Boolean,
        includePrunedInputs: Boolean,
        actionFilters: AqueryActionFilter?,
        includeParamFiles: Boolean,
        includeFileWriteContents: Boolean,
        aqueryOutputHandler: AqueryOutputHandler,
        eventHandler: ExtendedEventHandler?
    ) : this( /* actionGraphTargets= */
        ImmutableList.of<String?>("..."),
        includeActionCmdLine,
        includeArtifacts,
        includePrunedInputs,
        actionFilters,
        includeParamFiles,
        includeFileWriteContents,
        aqueryOutputHandler,
        eventHandler
    )

    init {
        this.actionGraphTargets = ImmutableSet.copyOf<String?>(actionGraphTargets)
        this.actionFilters = actionFilters
        this.includeParamFiles = includeParamFiles
        this.includeFileWriteContents = includeFileWriteContents
        this.aqueryOutputHandler = aqueryOutputHandler
        this.eventHandler = eventHandler

        val knownRuleClassStrings = KnownRuleClassStrings(aqueryOutputHandler)
        knownArtifacts = KnownArtifacts(aqueryOutputHandler)
        knownConfigurations = KnownConfigurations(aqueryOutputHandler)
        knownNestedSets = KnownNestedSets(aqueryOutputHandler, knownArtifacts)
        knownAspectDescriptors = KnownAspectDescriptors(aqueryOutputHandler)
        knownTargets = KnownTargets(aqueryOutputHandler, knownRuleClassStrings)
    }

    private fun includeInActionGraph(labelString: String?): Boolean {
        if (actionGraphTargets.size == 1
            && Iterables.getOnlyElement<String?>(actionGraphTargets) == "..."
        ) {
            return true
        }
        return actionGraphTargets.contains(labelString)
    }

    @Throws(
        CommandLineExpansionException::class,
        InterruptedException::class,
        IOException::class,
        TemplateExpansionException::class
    )
    private fun dumpSingleAction(configuredTarget: ConfiguredTarget, action: ActionAnalysisMetadata) {
        // Store the content of param files.

        var configuredTarget: ConfiguredTarget = configuredTarget
        if (includeParamFiles
            && (action is ParameterFileWriteAction)
        ) {
            val fileContent: Iterable<String?>? = action.getArguments()
            val paramFileExecPath: String? = action.getPrimaryOutput().getExecPathString()
            this.paramFileNameToContentMap!!.put(paramFileExecPath, fileContent)
        }

        if (actionFilters != null
            && !AqueryUtils.matchesAqueryFilters(action, actionFilters, includePrunedInputs)
        ) {
            return
        }

        // Dereference any aliases that might be present.
        configuredTarget = configuredTarget.getActual()

        Preconditions.checkState(configuredTarget is RuleConfiguredTarget)
        val targetIdentifier: Pair<String?, String?> =
            Pair<A?, B?>(
                configuredTarget.getLabel().toString(),
                (configuredTarget as RuleConfiguredTarget).getRuleClassString()
            )
        val actionBuilder: AnalysisProtosV2.Action.Builder =
            AnalysisProtosV2.Action.newBuilder()
                .setMnemonic(action.getMnemonic())
                .setTargetId(knownTargets.dataToIdAndStreamOutputProto(targetIdentifier))

        if (action is ActionExecutionMetadata) {
            actionBuilder
                .setActionKey(
                    action.getKey(
                        this.actionKeyContext,  /* inputMetadataProvider= */null
                    )
                )
                .setDiscoversInputs(action.discoversInputs())
        }

        // store environment
        if (action is AbstractAction && action is CommandAction) {
            // Some actions (e.g. CppCompileAction) don't override getEnvironment, but only
            // getEffectiveEnvironment. Since calling the latter with an empty client env returns the
            // fixed part of the full ActionEnvironment with the default implementations provided by
            // AbstractAction, we can call getEffectiveEnvironment here to handle these actions as well.
            // TODO(twerth): This handles the fixed environment. We probably want to output the inherited
            // environment as well.
            val fixedEnvironment: ImmutableMap<String?, String?> =
                action.getEffectiveEnvironment(ImmutableMap.of<K?, V?>())
            for (environmentVariable in fixedEnvironment.entries) {
                actionBuilder.addEnvironmentVariables(
                    AnalysisProtosV2.KeyValuePair.newBuilder()
                        .setKey(environmentVariable.key)
                        .setValue(environmentVariable.value)
                        .build()
                )
            }
        }

        if (includeActionCmdLine && action is CommandAction) {
            actionBuilder.addAllArguments(action.getArguments())
        }

        if (action is AbstractFileWriteAction.FileContentsProvider) {
            actionBuilder.setIsExecutable(
                (action as AbstractFileWriteAction.FileContentsProvider).makeExecutable()
            )
            if (includeFileWriteContents) {
                val contents: String? =
                    (action as AbstractFileWriteAction.FileContentsProvider).getFileContents(eventHandler)
                actionBuilder.setFileContents(contents)
            }
        }


        if (action is UnresolvedSymlinkAction) {
            actionBuilder.setUnresolvedSymlinkTarget((action as UnresolvedSymlinkAction).getTarget())
        }

        // Include the content of param files in output.
        if (includeParamFiles) {
            // Assumption: if an Action takes a params file as an input, it will be used
            // to provide params to the command.
            for (input in getActionInputs(action, includePrunedInputs).toList()) {
                val inputFileExecPath: String? = input.getExecPathString()
                if (this.paramFileNameToContentMap!!.containsKey(inputFileExecPath)) {
                    val paramFile: AnalysisProtosV2.ParamFile? =
                        AnalysisProtosV2.ParamFile.newBuilder()
                            .setExecPath(inputFileExecPath)
                            .addAllArguments(this.paramFileNameToContentMap!!.get(inputFileExecPath))
                            .build()
                    actionBuilder.addParamFiles(paramFile)
                }
            }
        }
        val executionInfo: MutableMap<String?, String?> = action.getExecutionInfo()
        for (info in executionInfo.entries) {
            actionBuilder.addExecutionInfo(
                AnalysisProtosV2.KeyValuePair.newBuilder()
                    .setKey(info.key)
                    .setValue(info.value)
            )
        }

        val actionOwner: ActionOwner? = action.getOwner()
        if (actionOwner != null) {
            val event: BuildEvent? = actionOwner.getBuildConfigurationEvent()
            actionBuilder.setConfigurationId(knownConfigurations.dataToIdAndStreamOutputProto(event))
            if (actionOwner.getExecutionPlatform() != null) {
                actionBuilder.setExecutionPlatform(actionOwner.getExecutionPlatform().label().toString())
            }

            // Store aspects.
            // Iterate through the aspect path and dump the aspect descriptors.
            // In the case of aspect-on-aspect, AspectDescriptors are listed in topological order
            // of the configured target graph.
            // e.g. [A, B] would imply that aspect A is applied on top of aspect B.
            for (aspectDescriptor in actionOwner.getAspectDescriptors().reverse()) {
                actionBuilder.addAspectDescriptorIds(
                    knownAspectDescriptors.dataToIdAndStreamOutputProto(aspectDescriptor)
                )
            }
        }

        if (includeArtifacts) {
            // Store inputs.
            val inputs: NestedSet<Artifact?> = getActionInputs(action, includePrunedInputs)
            if (!inputs.isEmpty()) {
                actionBuilder.addInputDepSetIds(knownNestedSets.dataToIdAndStreamOutputProto(inputs))
            }

            // Store outputs.
            for (artifact in action.getOutputs()) {
                actionBuilder.addOutputIds(knownArtifacts.dataToIdAndStreamOutputProto(artifact))
            }

            actionBuilder.setPrimaryOutputId(
                knownArtifacts.dataToIdAndStreamOutputProto(action.getPrimaryOutput())
            )
        }

        if (action is TemplateExpansionAction) {
            actionBuilder.setTemplateContent(AqueryUtils.getTemplateContent(action))

            for (substitution in action.getSubstitutions()) {
                try {
                    actionBuilder.addSubstitutions(
                        AnalysisProtosV2.KeyValuePair.newBuilder()
                            .setKey(substitution.getKey())
                            .setValue(substitution.getValue())
                    )
                } catch (e: EvalException) {
                    throw TemplateExpansionException("Failed to expand template", e)
                }
            }
        }

        aqueryOutputHandler.outputAction(actionBuilder.build())
    }

    @Throws(
        CommandLineExpansionException::class,
        InterruptedException::class,
        IOException::class,
        TemplateExpansionException::class
    )
    fun dumpAspect(
        aspectValue: AspectValue?, configuredTargetValue: ConfiguredTargetValue
    ) {
        // It's possible for a value from a previous build on the same server to be missing
        // e.g. after having cleared the analysis cache.
        if (aspectValue == null) {
            return
        }

        val configuredTarget: ConfiguredTarget = configuredTargetValue.getConfiguredTarget()
        if (!includeInActionGraph(configuredTarget.getLabel().toString())) {
            return
        }
        for (action in aspectValue.getActions()) {
            dumpSingleAction(configuredTarget, action)
        }
    }

    @Throws(
        CommandLineExpansionException::class,
        InterruptedException::class,
        IOException::class,
        TemplateExpansionException::class
    )
    fun dumpConfiguredTarget(configuredTargetValue: RuleConfiguredTargetValue) {
        val configuredTarget: ConfiguredTarget? = configuredTargetValue.getConfiguredTarget()
        if (!includeInActionGraph(configuredTarget.getLabel().toString())) {
            return
        }
        for (action in configuredTargetValue.getActions()) {
            dumpSingleAction(configuredTarget, action)
        }
    }
}
