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
package com.google.devtools.build.lib.analysis.extra

import com.google.devtools.build.lib.analysis.constraints.ConstraintConstants.getOsFromConstraintsOrHost

/** The specification for a particular extra action type.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class ExtraActionSpec(
    resolvedTools: NestedSet<Artifact?>?,
    resolvedData: MutableList<Artifact?>,
    outputTemplates: MutableList<String?>,
    command: String,
    label: Label,
    executionInfo: MutableMap<String?, String?>,
    requiresActionOutput: Boolean
) : TransitiveInfoProvider {
    private val resolvedTools: NestedSet<Artifact?>?
    private val resolvedData: com.google.common.collect.ImmutableList<Artifact?>
    private val outputTemplates: com.google.common.collect.ImmutableList<String>
    private val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>
    private val command: String
    private val requiresActionOutput: Boolean
    private val label: Label

    init {
        this.resolvedTools = resolvedTools
        this.resolvedData = com.google.common.collect.ImmutableList.copyOf<Artifact?>(resolvedData)
        this.outputTemplates = com.google.common.collect.ImmutableList.copyOf<String?>(outputTemplates)
        this.command = command
        this.label = label
        this.executionInfo = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(executionInfo)
        this.requiresActionOutput = requiresActionOutput
    }

    fun getLabel(): Label {
        return label
    }

    /** Adds an extra_action to the action graph based on the action to shadow.  */
    @Throws(java.lang.InterruptedException::class)
    fun addExtraAction(
        owningRule: RuleContext, actionToShadow: Action
    ): MutableCollection<Artifact.DerivedArtifact?> {
        val extraActionOutputs: MutableCollection<Artifact.DerivedArtifact?> =
            LinkedHashSet<Artifact.DerivedArtifact?>()
        val protoOutputs: MutableCollection<Artifact.DerivedArtifact?> =
            java.util.ArrayList<Artifact.DerivedArtifact?>()
        val extraActionInputs: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()

        val ownerLabel: Label? = owningRule.getLabel()
        if (requiresActionOutput) {
            extraActionInputs.addAll(actionToShadow.getOutputs())
        }

        extraActionInputs.addTransitive(resolvedTools)
        extraActionInputs.addAll(resolvedData)

        var createDummyOutput = false

        for (outputTemplate in outputTemplates) {
            // We create output for the extra_action based on the 'out_template' attribute.
            // See {link #getExtraActionOutputArtifact} for supported variables.
            extraActionOutputs.add(
                getExtraActionOutputArtifact(owningRule, actionToShadow, outputTemplate)
            )
        }
        // extra_action has no output, we need to create some dummy output to keep the build up-to-date.
        if (extraActionOutputs.isEmpty()) {
            createDummyOutput = true
            extraActionOutputs.add(
                getExtraActionOutputArtifact(owningRule, actionToShadow, "$(ACTION_ID).dummy")
            )
        }

        // We generate a file containing a protocol buffer describing the action that is being shadowed.
        // It is up to each action being shadowed to decide what contents to store here.
        val extraActionInfoFile: Artifact.DerivedArtifact =
            getExtraActionOutputArtifact(owningRule, actionToShadow, "$(ACTION_ID).xa")
        owningRule.registerAction(
            ExtraActionInfoFileWriteAction(
                actionToShadow.getOwner(), extraActionInfoFile, actionToShadow
            )
        )
        extraActionInputs.add(extraActionInfoFile)
        protoOutputs.add(extraActionInfoFile)

        // Expand extra_action specific variables from the provided command-line.
        // See {@link #createExpandedCommand} for list of supported variables.
        val command = createExpandedCommand(owningRule, actionToShadow, extraActionInfoFile)

        val commandHelper: CommandHelper = CommandHelper.Companion.builder(owningRule).build()

        // Multiple actions in the same configured target need to have different names for the artifact
        // that might be created here, so we append something that should be unique for each action.
        val actionUniquifier =
            (actionToShadow.getPrimaryOutput().getExecPath().getBaseName()
                    + "."
                    + actionToShadow.getKey(
                owningRule.getActionKeyContext(),  /* inputMetadataProvider= */null
            ))

        val shExecutable: PathFragment? =
            ShToolchain.getPathForPlatform(
                owningRule.getConfiguration(), owningRule.getExecutionPlatform()
            )
        val constructor: BashCommandConstructor =
            CommandHelper.Companion.buildBashCommandConstructor(
                executionInfo, shExecutable, "." + actionUniquifier + ".extra_action_script.sh"
            )
        val argv: com.google.common.collect.ImmutableList<String?>? =
            commandHelper.buildCommandLine(
                command,
                extraActionInputs,
                constructor,
                getOsFromConstraintsOrHost(owningRule.getExecutionPlatform())
            )

        val commandMessage: String? = java.lang.String.format("Executing extra_action %s on %s", label, ownerLabel)
        owningRule.registerAction(
            ExtraAction(
                owningRule.getActionOwner(),
                extraActionInputs.build(),
                extraActionOutputs,
                actionToShadow,
                createDummyOutput,
                CommandLine.of(argv),
                owningRule.getConfiguration().getActionEnvironment(),
                owningRule.getConfiguration().modifiedExecutionInfo(executionInfo, label.getName()),
                commandMessage,
                label.getName()
            )
        )

        return com.google.common.collect.ImmutableSet.builder<Artifact.DerivedArtifact?>()
            .addAll(extraActionOutputs)
            .addAll(protoOutputs)
            .build()
    }

    /**
     * Expand extra_action specific variables: $(EXTRA_ACTION_FILE): expands to a path of the file
     * containing a protocol buffer describing the action being shadowed. $(output <out_template>):
     * expands the output template to the execPath of the file. e.g. $(output $(ACTION_ID).out) ->
     * <build_path>/extra_actions/bar/baz/devtools/build/test_A41234.out
    </build_path></out_template> */
    @Throws(java.lang.InterruptedException::class)
    private fun createExpandedCommand(
        owningRule: RuleContext, action: Action, extraActionInfoFile: Artifact
    ): String {
        var realCommand: String =
            command.replace("$(EXTRA_ACTION_FILE)", extraActionInfoFile.getExecPathString())

        for (outputTemplate in outputTemplates) {
            val outFile: String =
                getExtraActionOutputArtifact(owningRule, action, outputTemplate).getExecPathString()
            realCommand = realCommand.replace("$(output " + outputTemplate + ")", outFile)
        }
        return realCommand
    }

    /**
     * Creates an output artifact for the extra_action based on the output_template. The path will be
     * in the following form: <output dir>/<target-configuration-specific-path>/extra_actions/<extra_action_label>/ +
     * <configured_target_label>/<expanded_template>
     * 
     * 
     * The template can use the following variables: $(ACTION_ID): a unique id for the
     * extra_action.
     * 
     * 
     * Sample: extra_action: foo/bar:extra template: $(ACTION_ID).analysis target: foo/bar:main
     * expands to: output/configuration/extra_actions/\
     * foo/bar/extra/foo/bar/4683026f7ac1dd1a873ccc8c3d764132.analysis
    </expanded_template></configured_target_label></extra_action_label></target-configuration-specific-path></output> */
    @Throws(java.lang.InterruptedException::class)
    private fun getExtraActionOutputArtifact(
        ruleContext: RuleContext, action: Action, template: String
    ): Artifact.DerivedArtifact {
        var template = template
        val actionId =
            getActionId(ruleContext.getActionKeyContext(), ruleContext.getActionOwner(), action)

        template = template.replace("$(ACTION_ID)", actionId)
        template = template.replace("$(OWNER_LABEL_DIGEST)", getOwnerDigest(ruleContext))

        return getRootRelativePath(template, ruleContext)
    }

    private fun getRootRelativePath(template: String?, ruleContext: RuleContext): Artifact.DerivedArtifact {
        val extraActionPackageFragment: PathFragment = label.getPackageIdentifier().getSourceRoot()
        val extraActionPrefix: PathFragment? = extraActionPackageFragment.getRelative(label.getName())
        val rootRelativePath: PathFragment? =
            PathFragment.create("extra_actions")
                .getRelative(extraActionPrefix)
                .getRelative(ruleContext.getPackageDirectory())
                .getRelative(template)
        // We need to use getDerivedArtifact here because extra actions are at
        // <EXTRA ACTION LABEL> / <RULE LABEL> instead of <RULE LABEL> / <EXTRA ACTION LABEL>. Bummer.
        return ruleContext
            .getAnalysisEnvironment()
            .getDerivedArtifact(
                rootRelativePath,
                ruleContext
                    .getConfiguration()
                    .getOutputDirectory(ruleContext.getRule().getRepository())
            )
    }

    companion object {
        /**
         * Calculates a digest representing the rule context. We use the digest instead of the original
         * value as the original value might lead to a filename that is too long. By using a digest, tools
         * can deterministically find all extra_action outputs for a given target, without having to open
         * every file in the package.
         */
        private fun getOwnerDigest(ruleContext: RuleContext): String {
            val f: Fingerprint = Fingerprint()
            f.addString(ruleContext.getLabel().toString())
            return f.hexDigestAndReset()
        }

        /**
         * Creates a unique id for the action shadowed by this extra_action.
         * 
         * 
         * We need to have a unique id for the extra_action to use. We build this from the owner's
         * label and the shadowed action id (which is only guaranteed to be unique per target). Together
         * with the subfolder matching the original target's package name, we believe this is enough of a
         * uniqueness guarantee.
         */
        @com.google.common.annotations.VisibleForTesting
        @Throws(java.lang.InterruptedException::class)
        fun getActionId(
            actionKeyContext: ActionKeyContext?, owner: ActionOwner, action: Action
        ): String {
            val f: Fingerprint = Fingerprint()
            f.addString(owner.getLabel().toString())
            val aspectDescriptors: com.google.common.collect.ImmutableList<AspectDescriptor> =
                owner.getAspectDescriptors()
            f.addInt(aspectDescriptors.size())
            for (aspectDescriptor in aspectDescriptors) {
                f.addString(aspectDescriptor.getDescription())
            }
            f.addString(action.getKey(actionKeyContext,  /* inputMetadataProvider= */null))
            return f.hexDigestAndReset()
        }
    }
}
