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
package com.google.devtools.build.lib.rules.extra

import com.google.devtools.build.lib.actions.ActionConflictException

/**
 * Factory for 'extra_action'.
 */
class ExtraActionFactory : RuleConfiguredTargetFactory {
    @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
    public override fun create(context: RuleContext): ConfiguredTarget? {
        // This rule doesn't produce any output when listed as a build target.
        // Only when used via the --experimental_action_listener flag,
        // this rule instructs the build system to add additional outputs.
        val resolvedData: MutableList<Artifact?> = java.util.ArrayList<Artifact?>()

        val commandHelper: CommandHelper =
            CommandHelper.builder(context).addToolDependencies("tools").build()

        resolvedData.addAll(context.getPrerequisiteArtifacts("data").list())
        val outputTemplates: MutableList<String?>? = context.attributes().get("out_templates", Types.STRING_LIST)

        var command: String = context.attributes().get("cmd", Type.STRING)
        // This is a bit of a hack. We want to run the MakeVariableExpander first, so we expand $ on
        // variables that are expanded below with $$, which gets reverted to $ by the
        // MakeVariableExpander. This allows us to expand package-specific make variables in the
        // package where the extra action is defined, and then later replace the owner-specific make
        // variables when the extra action is instantiated.
        command = command.replace("$(EXTRA_ACTION_FILE)", "$$(EXTRA_ACTION_FILE)")
        command = command.replace("$(ACTION_ID)", "$$(ACTION_ID)")
        command = command.replace("$(OWNER_LABEL_DIGEST)", "$$(OWNER_LABEL_DIGEST)")
        command = command.replace("$(output ", "$$(output ")
        val makeVariableContext: ConfigurationMakeVariableContext =
            ConfigurationMakeVariableContext(
                context.getTarget().getPackageDeclarations(),
                context.getConfiguration(),
                context.getDefaultTemplateVariableProviders()
            )
        command = context
            .getExpander(makeVariableContext)
            .withDataExecLocations()
            .expand("cmd", command)

        val requiresActionOutput: Boolean =
            context.attributes().get("requires_action_output", Type.BOOLEAN)

        if (context.hasErrors()) {
            return null
        }
        val spec: ExtraActionSpec =
            ExtraActionSpec(
                commandHelper.getResolvedTools(),
                resolvedData,
                outputTemplates,
                command,
                context.getLabel(),
                TargetUtils.getExecutionInfo(context.getRule()),
                requiresActionOutput
            )
        return RuleConfiguredTargetBuilder(context)
            .addProvider(ExtraActionSpec::class.java, spec)
            .add(RunfilesProvider::class.java, RunfilesProvider.simple(Runfiles.EMPTY))
            .build()
    }
}
