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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.runtime.Command.BuildPhase.NONE

/** The 'blaze canonicalize-flags' command.  */
@Command(
    name = "canonicalize-flags",
    buildPhase = NONE,
    options = [com.google.devtools.build.lib.runtime.commands.CanonicalizeCommand.Options::class, PackageOptions::class],
    inheritsOptionsFrom = [BuildCommand::class],
    allowResidue = true,
    mustRunInWorkspace = false,
    shortDescription = "Canonicalizes a list of %{product} options.",
    help = ("This command canonicalizes a list of %{product} options. Don't forget to prepend  '--' to"
            + " end option parsing before the flags to canonicalize. This command doesn't support"
            + " the strategy policies under --invocation_policy flag.\n%{options}")
)
class CanonicalizeCommand : BlazeCommand {
    /** Options for the `canonicalize-flags` command.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class Options : com.google.devtools.common.options.OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "for_command",
            defaultValue = "build",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.GENERIC_INPUTS,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
            help = "The command for which the options should be canonicalized."
        )
        abstract val forCommand: String?

        @get:com.google.devtools.common.options.Option(
            name = "invocation_policy",
            defaultValue = "",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.GENERIC_INPUTS,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
            help = "Applies an invocation policy to the options to be canonicalized."
        )
        abstract val invocationPolicy: String?

        @get:com.google.devtools.common.options.Option(
            name = "canonicalize_policy",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
            help = ("Output the canonical policy, after expansion and filtering. To keep the output clean,"
                    + " the canonicalized command arguments will NOT be shown when this option is set"
                    + " to true. Note that the command specified by --for_command affects the filtered"
                    + " policy, and if none is specified, the default command is 'build'.")
        )
        abstract val canonicalizePolicy: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "experimental_include_default_values",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
            help = "Whether Starlark options set to their default values are included in the output."
        )
        abstract val includeDefaultValues: Boolean
    }

    /**
     * These options are used by the incompatible_changes_conflict_test.sh integration test, which
     * confirms that the warning for conflicting expansion options is working correctly. These flags
     * are undocumented no-ops, and are not to be used by anything outside of that test.
     */
    @com.google.devtools.common.options.OptionsClass
    abstract class FlagClashCanaryOptions : com.google.devtools.common.options.OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "flag_clash_canary",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN]
        )
        abstract val flagClashCanary: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "flag_clash_canary_expander1",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
            expansion = ["--flag_clash_canary=1"]
        )
        abstract val flagClashCanaryExpander1: java.lang.Void?

        @get:com.google.devtools.common.options.Option(
            name = "flag_clash_canary_expander2",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
            expansion = ["--flag_clash_canary=0"]
        )
        abstract val flagClashCanaryExpander2: java.lang.Void?
    }

    public override fun exec(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult {
        val runtime: BlazeRuntime = env.getRuntime()
        val canonicalizeOptions: Options? =
            options.getOptions<Options?>(com.google.devtools.build.lib.runtime.commands.CanonicalizeCommand.Options::class.java)
        val commandName = canonicalizeOptions!!.forCommand
        val command: BlazeCommand? = runtime.getCommandMap().get(commandName)
        if (command == null) {
            val message: String? = String.format(
                "Not a valid command: '%s' (should be one of %s)",
                commandName, com.google.common.base.Joiner.on(", ").join(runtime.getCommandMap().keySet())
            )
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return BlazeCommandResult.detailedExitCode(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setCanonicalizeFlags(
                            CanonicalizeFlags.newBuilder().setCode(Code.FOR_COMMAND_INVALID)
                        )
                        .build()
                )
            )
        }
        val optionsClasses: MutableCollection<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> =
            com.google.common.collect.ImmutableList.builder<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()
                .addAll(
                    BlazeCommandUtils.getOptions(
                        command.getClass(),
                        runtime.getOptionsSuppliers(),
                        runtime.getRuleClassProvider()
                    )
                )
                .add(FlagClashCanaryOptions::class.java)
                .build()

        // set up the command environment for starlark options parsing
        val mainRepoMapping: RepositoryMapping?
        try {
            env.syncPackageLoading(options)
            mainRepoMapping = env.getSkyframeExecutor().getMainRepoMapping(env.getReporter())
        } catch (e: java.lang.InterruptedException) {
            return handleInterruptedException(env)
        } catch (e: RepositoryMappingResolutionException) {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.message))
            return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode())
        } catch (e: AbruptExitException) {
            env.getReporter()
                .handle(com.google.devtools.build.lib.events.Event.error(null, "Unknown error: " + e.message))
            return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode())
        }

        val parser: com.google.devtools.common.options.OptionsParser =
            com.google.devtools.common.options.OptionsParser.builder()
                .optionsClasses(optionsClasses)
                .skipStarlarkOptionPrefixes()
                .allowResidue(true)
                .withAliasFlag(CoreOptionConverters.BLAZE_ALIASING_FLAG)
                .withAliases(options.getAliases())
                .withConversionContext(mainRepoMapping)
                .build()

        try {
            parser.parse(options.getResidue())
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            return reportAndCreateCommandFailure(
                env, e.message, FailureDetails.Command.Code.OPTIONS_PARSE_FAILURE
            )
        }

        val buildSettingLoader: BuildSettingLoader = SkyframeExecutorTargetLoader(env)
        val starlarkOptionsParser: StarlarkOptionsParser =
            StarlarkOptionsParser.Companion.builder()
                .buildSettingLoader(buildSettingLoader)
                .nativeOptionsParser(parser)
                .includeDefaultValues(canonicalizeOptions.includeDefaultValues)
                .build()
        try {
            com.google.common.base.Preconditions.checkState(starlarkOptionsParser.parse())
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            return reportAndCreateCommandFailure(
                env, e.message, FailureDetails.Command.Code.STARLARK_OPTIONS_PARSE_FAILURE
            )
        } catch (e: java.lang.InterruptedException) {
            return handleInterruptedException(env)
        }

        if (!parser.getResidue().isEmpty()) {
            return reportAndCreateCommandFailure(
                env,
                "Unrecognized arguments: " + com.google.common.base.Joiner.on(' ').join(parser.getResidue()),
                FailureDetails.Command.Code.ARGUMENTS_NOT_RECOGNIZED
            )
        }

        val policy: InvocationPolicy?
        try {
            policy = InvocationPolicyParser.parsePolicy(canonicalizeOptions.invocationPolicy)
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            return reportAndCreateCommandFailure(
                env, e.message, FailureDetails.Command.Code.INVOCATION_POLICY_PARSE_FAILURE
            )
        }

        try {
            val invocationPolicyEnforcer: InvocationPolicyEnforcer =
                InvocationPolicyEnforcer(policy, java.util.logging.Level.INFO, mainRepoMapping)
            invocationPolicyEnforcer.enforce(
                parser,
                commandName,  /* invocationPolicyFlagListBuilder= */
                com.google.common.collect.ImmutableList.builder<com.google.devtools.common.options.OptionAndRawValue?>()
            )

            // Print out the canonical invocation policy if requested.
            if (canonicalizeOptions.canonicalizePolicy) {
                val effectivePolicy: InvocationPolicy =
                    InvocationPolicyEnforcer.getEffectiveInvocationPolicy(
                        policy, parser, commandName, java.util.logging.Level.INFO
                    )
                env.getReporter()
                    .getOutErr()
                    .printOutLn(
                        effectivePolicy.toString()
                    )
            } else {
                // Otherwise, print out the canonical command line
                val nativeResult: MutableList<String?> = parser.canonicalize()
                val starlarkResult: MutableList<String?> = starlarkOptionsParser.canonicalize()
                val result: com.google.common.collect.ImmutableList.Builder<String?> =
                    com.google.common.collect.ImmutableList.builder<String?>().addAll(nativeResult)
                        .addAll(starlarkResult)
                for (piece in result.build()) {
                    env.getReporter().getOutErr().printOutLn(piece)
                }
            }
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            return reportAndCreateCommandFailure(
                env, e.message, FailureDetails.Command.Code.INVOCATION_POLICY_INVALID
            )
        }

        return BlazeCommandResult.success()
    }

    private fun handleInterruptedException(env: CommandEnvironment): BlazeCommandResult {
        val message = "canonicalization interrupted"
        env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
        return BlazeCommandResult.detailedExitCode(InterruptedFailureDetails.detailedExitCode(message))
    }

    companion object {
        private fun reportAndCreateCommandFailure(
            env: CommandEnvironment, message: String?, detailedCode: FailureDetails.Command.Code?
        ): BlazeCommandResult {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return BlazeCommandResult.detailedExitCode(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setCommand(FailureDetails.Command.newBuilder().setCode(detailedCode))
                        .build()
                )
            )
        }
    }
}
