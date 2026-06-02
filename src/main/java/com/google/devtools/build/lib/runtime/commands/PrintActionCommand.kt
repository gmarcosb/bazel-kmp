// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.runtime.Command.BuildPhase.ANALYZES

/** Implements 'blaze print_action' by finding the Configured target[s] for the file[s] listed.  */
@Command(
    name = "print_action",
    buildPhase = ANALYZES,
    inheritsOptionsFrom = [BuildCommand::class],
    options = [PrintActionOptions::class],
    help = "resource:print_action.txt",
    shortDescription = "Prints the command line args for compiling a file.",
    completion = "label",
    allowResidue = true
)
class PrintActionCommand : BlazeCommand {
    /** Options for print_action, used to parse command-line arguments.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class PrintActionOptions : com.google.devtools.common.options.OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "print_action_mnemonics",
            allowMultiple = true,
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = ("Lists which mnemonics to filter print_action data by, no filtering takes place "
                    + "when left empty.")
        )
        abstract val printActionMnemonics: MutableList<String?>?
    }

    public override fun exec(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult {
        val loadingOptions: LoadingOptions? =
            options.getOptions<O?>(LoadingOptions::class.java)

        val printActionOptions: PrintActionOptions? =
            options.getOptions<PrintActionOptions?>(PrintActionOptions::class.java)
        val runner =
            PrintActionRunner(
                loadingOptions.getCompileOneDependency(),
                options,
                env.getReporter().getOutErr(),
                options.getResidue(),
                HashSet<String?>(printActionOptions!!.printActionMnemonics)
            )
        return BlazeCommandResult.detailedExitCode(runner.printActionsForTargets(env))
    }

    /**
     * Contains all the logic to get extra_action information for print actions.
     * Maintains requires state to perform required analyses.
     */
    private inner class PrintActionRunner(
        private val compileOneDependency: Boolean,
        options: com.google.devtools.common.options.OptionsParsingResult,
        outErr: OutErr,
        requestedTargets: MutableList<String?>,
        printActionMnemonics: MutableSet<String?>
    ) {
        private val options: com.google.devtools.common.options.OptionsParsingResult?
        private val outErr: OutErr
        private val requestedTargets: MutableList<String?>
        private val keepGoing: Boolean
        private val summaryBuilder: ExtraActionSummary.Builder
        private val actionMnemonicMatcher: com.google.common.base.Predicate<ActionAnalysisMetadata?>

        init {
            this.options = options
            this.outErr = outErr
            this.requestedTargets = requestedTargets
            keepGoing = options.getOptions<KeepGoingOption?>(KeepGoingOption::class.java).getKeepGoing()
            summaryBuilder = ExtraActionSummary.newBuilder()
            actionMnemonicMatcher = object : com.google.common.base.Predicate<ActionAnalysisMetadata?> {
                override fun apply(action: ActionAnalysisMetadata): Boolean {
                    return printActionMnemonics.isEmpty()
                            || printActionMnemonics.contains(action.getMnemonic())
                }
            }
        }

        fun printActionsForTargets(env: CommandEnvironment): DetailedExitCode? {
            val result: BuildResult
            try {
                result = gatherActionsForTargets(env, requestedTargets)
            } catch (e: PrintActionException) {
                return DetailedExitCode.of(e.createFailureDetail())
            } catch (e: java.lang.InterruptedException) {
                val message = "print_action: action gathering interrupted"
                env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
                return InterruptedFailureDetails.detailedExitCode(message)
            }
            if (hasFatalBuildFailure(result)) {
                env.getReporter()
                    .handle(com.google.devtools.build.lib.events.Event.error("Build failed when printing actions"))
                return result.getDetailedExitCode()
            }
            val action: String = TextFormat.printer().printToString(summaryBuilder)
            if (!action.isEmpty()) {
                outErr.printOut(action)
                return result.getDetailedExitCode()
            } else {
                val message = "no actions to print were found"
                env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
                return DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setPrintActionCommand(
                            FailureDetails.PrintActionCommand.newBuilder().setCode(Code.ACTIONS_NOT_FOUND)
                        )
                        .build()
                )
            }
        }

        @Throws(PrintActionException::class, java.lang.InterruptedException::class)
        fun gatherActionsForTargets(env: CommandEnvironment, targets: MutableList<String?>): BuildResult {
            val runtime: BlazeRuntime = env.getRuntime()
            val commandName: String? = this@PrintActionCommand.javaClass.getAnnotation<A?>(Command::class.java).name()

            val request: BuildRequest? =
                BuildRequest.builder()
                    .setCommandName(commandName)
                    .setId(env.getCommandId())
                    .setOptions(options)
                    .setStartupOptions(runtime.getStartupOptionsProvider())
                    .setOutErr(outErr)
                    .setTargets(targets)
                    .setStartTimeMillis(env.commandStartTime)
                    .build()
            val result: BuildResult = BuildTool(env).processRequest(request, null, options)
            if (hasFatalBuildFailure(result)) {
                return result
            }

            val actionGraph: ActionGraph = env.getSkyframeExecutor().getActionGraph(env.getReporter())

            for (configuredTarget in result.getActualTargets()) {
                var filesToCompile: NestedSet<Artifact?> = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
                val outputGroupInfo: OutputGroupInfo? = OutputGroupInfo.get(configuredTarget)
                if (outputGroupInfo != null) {
                    filesToCompile =
                        outputGroupInfo.getOutputGroup(OutputGroupInfo.FILES_TO_COMPILE)
                }
                if (!filesToCompile.isEmpty()) {
                    try {
                        if (compileOneDependency) {
                            gatherActionsForFiles(
                                configuredTarget,
                                env,
                                actionGraph,
                                env.getSkyframeExecutor().getActionKeyContext(),
                                targets
                            )
                        } else {
                            val target: Target?
                            try {
                                target =
                                    env.getPackageManager()
                                        .getTarget(env.getReporter(), configuredTarget.getLabel())
                            } catch (e: NoSuchTargetException) {
                                val message = "Failed to find target to gather actions: " + e.getMessage()
                                env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
                                throw PrintActionException(message, Code.TARGET_NOT_FOUND)
                            } catch (e: NoSuchPackageException) {
                                val message = "Failed to find target to gather actions: " + e.getMessage()
                                env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
                                throw PrintActionException(message, Code.TARGET_NOT_FOUND)
                            }
                            gatherActionsForTarget(
                                configuredTarget,
                                target,
                                actionGraph,
                                env.getSkyframeExecutor().getActionKeyContext()
                            )
                        }
                    } catch (e: CommandLineExpansionException) {
                        val message = "Error expanding command line: " + e
                        env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(null, message))
                        throw PrintActionException(message, Code.COMMAND_LINE_EXPANSION_FAILURE)
                    }
                } else {
                    val message = configuredTarget.toString() + " is not a supported target kind"
                    env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(null, message))
                    throw PrintActionException(message, Code.TARGET_KIND_UNSUPPORTED)
                }
            }
            return result
        }

        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        fun gatherActionsForFiles(
            configuredTarget: ConfiguredTarget,
            env: CommandEnvironment,
            actionGraph: ActionGraph,
            actionKeyContext: ActionKeyContext?,
            files: MutableList<String?>
        ) {
            val filesDesired: MutableSet<String?> = LinkedHashSet<String?>(files)
            val filter = ActionFilter(filesDesired, actionMnemonicMatcher)
            gatherActionsForFile(configuredTarget, filter, env, actionGraph, actionKeyContext)
        }

        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        fun gatherActionsForTarget(
            configuredTarget: ConfiguredTarget,
            target: Target?,
            actionGraph: ActionGraph?,
            actionKeyContext: ActionKeyContext?
        ) {
            if (target !is Rule) {
                return
            }

            val visitor: PrintActionVisitor = PrintActionVisitor(
                actionGraph, configuredTarget,
                actionMnemonicMatcher
            )

            // TODO(jvg): do we want to support ruleConfiguredTarget.getOutputArtifacts()?
            // We do for extra actions, but as we're past the action graph building phase,
            // we cannot call it without risking to trigger creation of OutputArtifacts post
            // graph building phase (not allowed). Right now we do not need them for our scenarios.
            visitor.visitWhiteNodes(
                configuredTarget.getProvider(FileProvider::class.java).getFilesToBuild().toList()
            )

            val actions: Iterable<ActionAnalysisMetadata> = visitor.getActions()
            for (action in actions) {
                if (action is Action) {
                    val detail: DetailedExtraActionInfo.Builder = DetailedExtraActionInfo.newBuilder()
                    detail.setAction((action as Action).getExtraActionInfo(actionKeyContext))
                    summaryBuilder.addAction(detail)
                }
            }
        }

        /**
         * Looks for files to compile in the given configured target and outputs the corresponding
         * extra_action if the filter evaluates to `true`.
         */
        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        fun gatherActionsForFile(
            configuredTarget: ConfiguredTarget,
            filter: ActionFilter,
            env: CommandEnvironment,
            actionGraph: ActionGraph,
            actionKeyContext: ActionKeyContext?
        ) {
            val artifacts: NestedSet<Artifact?> = OutputGroupInfo.get(configuredTarget)
                .getOutputGroup(OutputGroupInfo.FILES_TO_COMPILE)

            if (artifacts.isEmpty()) {
                return
            }

            for (artifact in artifacts.toList()) {
                val action: ActionAnalysisMetadata? = actionGraph.getGeneratingAction(artifact)
                if (filter.shouldOutput(action, configuredTarget, env)) {
                    if (action is Action) {
                        val detail: DetailedExtraActionInfo.Builder = DetailedExtraActionInfo.newBuilder()
                        detail.setAction((action as Action).getExtraActionInfo(actionKeyContext))
                        summaryBuilder.addAction(detail)
                    }
                }
            }
        }

        fun hasFatalBuildFailure(result: BuildResult): Boolean {
            return result.getActualTargets() == null || (!result.getSuccess() && !keepGoing)
        }
    }

    /**
     * A stateful filter that keeps track of which files have already been covered. This makes it such
     * that blaze only prints out one action protobuf per file. This is important for headers. In
     * addition, this also handles C++ header files, which are not considered to be action inputs by
     * blaze (due to include scanning).
     * 
     * 
     * As caveats, this only works for files that are given as proper relative paths, rather than
     * using target syntax, and only if the current working directory is the client root.
     */
    private class ActionFilter(
        private val filesDesired: MutableSet<String?>,
        actionMnemonicMatcher: com.google.common.base.Predicate<ActionAnalysisMetadata?>
    ) {
        private val actionMnemonicMatcher: com.google.common.base.Predicate<ActionAnalysisMetadata?>

        init {
            this.actionMnemonicMatcher = actionMnemonicMatcher
        }

        @Throws(java.lang.InterruptedException::class)
        fun shouldOutput(
            action: ActionAnalysisMetadata?, configuredTarget: ConfiguredTarget, env: CommandEnvironment
        ): Boolean {
            if (action == null) {
                return false
            }
            // Check all the inputs for the configured target against the file we want argv for.
            val artifacts: LinkedHashSet<Artifact> = LinkedHashSet<Artifact>()
            artifacts.addAll(action.getInputs().toList())
            artifacts.addAll(action.getSchedulingDependencies().toList())

            for (input in artifacts) {
                if (filesDesired.remove(input.getRootRelativePath().getSafePathString())) {
                    return actionMnemonicMatcher.apply(action)
                }
            }

            // C++ header files show up in the dependency on the Target, but not the ConfiguredTarget, so
            // we also check the target's header files there.
            val rule: Rule
            try {
                rule =
                    env.getPackageManager().getTarget(env.getReporter(), configuredTarget.getLabel()) as Rule
            } catch (e: NoSuchTargetException) {
                env.getReporter()
                    .handle(com.google.devtools.build.lib.events.Event.error("Failed to find target to determine output."))
                return false
            } catch (e: NoSuchPackageException) {
                env.getReporter()
                    .handle(com.google.devtools.build.lib.events.Event.error("Failed to find target to determine output."))
                return false
            }
            if (!rule.isAttrDefined("hdrs", BuildType.LABEL_LIST)) {
                return false
            }

            val hdrs: MutableList<Label>? =
                ConfiguredAttributeMapper.of(
                    rule,
                    configuredTarget.getConfigConditions(),
                    configuredTarget.getConfigurationChecksum(),  /*alwaysSucceed=*/
                    false
                )
                    .get("hdrs", BuildType.LABEL_LIST)
            if (hdrs != null) {
                for (hdrLabel in hdrs) {
                    if (filesDesired.remove(hdrLabel.toPathFragment().getPathString())) {
                        return actionMnemonicMatcher.apply(action)
                    }
                }
            }
            return false // no match
        }
    }

    private class PrintActionException(message: String?, detailedCode: Code?) : java.lang.Exception(message) {
        private val detailedCode: FailureDetails.PrintActionCommand.Code?

        init {
            this.detailedCode = detailedCode
        }

        fun createFailureDetail(): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(message)
                .setPrintActionCommand(
                    FailureDetails.PrintActionCommand.newBuilder().setCode(detailedCode)
                )
                .build()
        }
    }
}
