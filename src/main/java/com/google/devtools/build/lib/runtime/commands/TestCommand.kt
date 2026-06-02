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

import com.google.devtools.build.lib.runtime.Command.BuildPhase.EXECUTES

/** Handles the 'test' command on the Blaze command line.  */
@Command(
    name = "test",
    buildPhase = EXECUTES,
    inheritsOptionsFrom = [BuildCommand::class],
    options = [TestSummaryOptions::class],
    shortDescription = "Builds and runs the specified test targets.",
    help = "resource:test.txt",
    completion = "label-test",
    allowResidue = true
)
open class TestCommand : BlazeCommand {
    /** Returns the name of the command to ask the project file for.  */ // TODO(hdm): move into BlazeRuntime?  It feels odd to duplicate the annotation here.
    protected open fun commandName(): String? {
        return "test"
    }

    public override fun editOptions(optionsParser: com.google.devtools.common.options.OptionsParser) {
        val testOutput: TestOutputFormat? = optionsParser.getOptions<O?>(ExecutionOptions::class.java).testOutput
        try {
            if (testOutput === TestOutputFormat.STREAMED) {
                optionsParser.parse(
                    com.google.devtools.common.options.OptionPriority.PriorityCategory.SOFTWARE_REQUIREMENT,
                    "streamed output requires locally run tests, without sharding",
                    com.google.common.collect.ImmutableList.of<String?>(
                        "--test_sharding_strategy=disabled",
                        "--test_strategy=exclusive"
                    )
                )
            }
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            throw java.lang.IllegalStateException("Known options failed to parse", e)
        }
    }

    public override fun exec(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult {
        val testOutput: TestOutputFormat? = options.getOptions<O?>(ExecutionOptions::class.java).testOutput
        if (testOutput === TestOutputFormat.STREAMED) {
            env.getReporter()
                .handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        "Streamed test output requested. All tests will be run without sharding, "
                                + "one at a time"
                    )
                )
        }

        val printer: AnsiTerminalPrinter =
            AnsiTerminalPrinter(
                env.getReporter().getOutErr().getOutputStream(),
                options.getOptions<UiOptions?>(UiOptions::class.java).useColor()
            )

        // Initialize test handler.
        val testListener: AggregatingTestListener =
            AggregatingTestListener(
                options.getOptions<O?>(TestSummaryOptions::class.java),
                options.getOptions<O?>(ExecutionOptions::class.java),
                env.getEventBus()
            )

        env.getEventBus().register(testListener)
        return doTest(env, options, testListener, printer)
    }

    private fun doTest(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult,
        testListener: AggregatingTestListener,
        printer: AnsiTerminalPrinter
    ): BlazeCommandResult {
        val runtime: BlazeRuntime = env.getRuntime()
        // Run simultaneous build and test.
        val targets: MutableList<String?>?
        try {
            targets = TargetPatternsHelper.readFrom(env, options)
        } catch (e: TargetPatternsHelperException) {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
            return BlazeCommandResult.failureDetail(e.getFailureDetail())
        }
        val mainRepoMapping: RepositoryMapping?
        try {
            mainRepoMapping = env.getSkyframeExecutor().getMainRepoMapping(env.getReporter())
        } catch (e: java.lang.InterruptedException) {
            val message = "test command interrupted"
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return BlazeCommandResult.detailedExitCode(
                InterruptedFailureDetails.detailedExitCode(message)
            )
        } catch (e: RepositoryMappingResolutionException) {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
            return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode())
        }

        val builder: BuildRequest.Builder =
            BuildRequest.builder()
                .setCommandName(getClass().getAnnotation<A?>(Command::class.java).name())
                .setId(env.getCommandId())
                .setOptions(options)
                .setStartupOptions(runtime.getStartupOptionsProvider())
                .setOutErr(env.getReporter().getOutErr())
                .setTargets(targets)
                .setStartTimeMillis(env.commandStartTime)
                .setRunTests(true)
        if (options.getOptions<O?>(CoreOptions::class.java).getCollectCodeCoverage()
            && !options.containsExplicitOption(
                InstrumentationFilterSupport.INSTRUMENTATION_FILTER_FLAG
            )
        ) {
            builder.setNeedsInstrumentationFilter(true)
        }
        val request: BuildRequest = builder.build()

        val buildResult: BuildResult = BuildTool(env).processRequest(request, null, options)

        val testTargets: MutableCollection<ConfiguredTarget?> = buildResult.getTestTargets()
        // TODO(bazel-team): don't handle isEmpty here or fix up a bunch of tests
        if (buildResult.getSuccessfulTargets() == null) {
            // This can happen if there were errors in the target parsing or loading phase
            // (original exitcode=BUILD_FAILURE) or if there weren't but --noanalyze was given
            // (original exitcode=SUCCESS).
            val message = "Couldn't start the build. Unable to run tests"
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            val detailedExitCode: DetailedExitCode =
                if (buildResult.getSuccess())
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(message)
                            .setTestCommand(
                                FailureDetails.TestCommand.newBuilder().setCode(Code.TEST_WITH_NOANALYZE)
                            )
                            .build()
                    )
                else
                    buildResult.getDetailedExitCode()
            env.getEventBus()
                .post(
                    TestingCompleteEvent(detailedExitCode.getExitCode(), buildResult.getStopTime())
                )
            return BlazeCommandResult.detailedExitCode(detailedExitCode)
        }
        // TODO(bazel-team): the check above shadows NO_TESTS_FOUND, but switching the conditions breaks
        // more tests
        if (testTargets.isEmpty()) {
            val message = "No test targets were found, yet testing was requested"
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(null, message))

            val detailedExitCode: DetailedExitCode =
                if (buildResult.getSuccess())
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(message)
                            .setTestCommand(
                                FailureDetails.TestCommand.newBuilder().setCode(Code.NO_TEST_TARGETS)
                            )
                            .build()
                    )
                else
                    buildResult.getDetailedExitCode()
            env.getEventBus()
                .post(NoTestsFound(detailedExitCode.getExitCode(), buildResult.getStopTime()))
            return BlazeCommandResult.detailedExitCode(detailedExitCode)
        }

        val testResults: DetailedExitCode =
            com.google.devtools.build.lib.runtime.commands.TestCommand.Companion.analyzeTestResults(
                request, buildResult, testListener, options, env, printer, mainRepoMapping
            )

        if (testResults.isSuccess() && !buildResult.getSuccess()) {
            // If all tests run successfully, test summary should include warning if
            // there were build errors not associated with the test targets.
            printer.printLn(
                (AnsiTerminalPrinter.Mode.ERROR
                    .toString() + "All tests passed but there were other errors during the build.\n"
                        + AnsiTerminalPrinter.Mode.DEFAULT)
            )
        }

        val detailedExitCode: DetailedExitCode? =
            DetailedExitCode.DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                buildResult.getDetailedExitCode(), testResults
            )
        env.getEventBus()
            .post(TestingCompleteEvent(detailedExitCode.getExitCode(), buildResult.getStopTime()))
        return BlazeCommandResult.detailedExitCode(detailedExitCode)
    }

    companion object {
        /**
         * Analyzes test results and prints summary information. Returns a [DetailedExitCode]
         * summarizing those test results.
         */
        private fun analyzeTestResults(
            buildRequest: BuildRequest,
            buildResult: BuildResult,
            listener: AggregatingTestListener,
            options: com.google.devtools.common.options.OptionsParsingResult,
            env: CommandEnvironment,
            printer: AnsiTerminalPrinter?,
            mainRepoMapping: RepositoryMapping?
        ): DetailedExitCode {
            val validatedTargets: com.google.common.collect.ImmutableSet<ConfiguredTargetKey?>?
            if (buildRequest.useValidationAspect()) {
                validatedTargets =
                    buildResult.getSuccessfulAspects().stream()
                        .filter({ key -> AspectCollection.VALIDATION_ASPECT_NAME.equals(key.getAspectName()) })
                        .map({ obj: AspectKey? -> obj.getBaseConfiguredTargetKey() })
                        .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
            } else {
                validatedTargets = null
            }

            val notifier: TestResultNotifier =
                TerminalTestResultNotifier(
                    printer,
                    com.google.devtools.build.lib.runtime.commands.TestCommand.Companion.makeTestLogPathFormatter(
                        buildResult.getConvenienceSymlinks(),
                        options,
                        env
                    ),
                    options,
                    mainRepoMapping
                )
            return listener.differentialAnalyzeAndReport(
                buildResult.getTestTargets(), buildResult.getSkippedTargets(), validatedTargets, notifier
            )
        }

        private fun makeTestLogPathFormatter(
            convenienceSymlinks: com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>?,
            options: com.google.devtools.common.options.OptionsParsingResult,
            env: CommandEnvironment
        ): TestLogPathFormatter {
            val runtime: BlazeRuntime = env.getRuntime()
            val summaryOptions: TestSummaryOptions? =
                options.getOptions<TestSummaryOptions?>(TestSummaryOptions::class.java)
            if (!summaryOptions.getPrintRelativeTestLogPaths()) {
                return TestLogPathFormatter { obj: com.google.devtools.build.lib.vfs.Path? -> obj.getPathString() }
            }
            val productName: String? = runtime.productName
            val requestOptions: BuildRequestOptions = env.getOptions().getOptions(BuildRequestOptions::class.java)
            val pathPrettyPrinter: PathPrettyPrinter =
                PathPrettyPrinter(
                    env.getRelativeWorkingDirectory(),
                    requestOptions.getSymlinkPrefix(productName),
                    convenienceSymlinks
                )
            return TestLogPathFormatter { path: com.google.devtools.build.lib.vfs.Path? ->
                pathPrettyPrinter.getPrettyPath(
                    path.asFragment()
                ).getPathString()
            }
        }
    }
}
