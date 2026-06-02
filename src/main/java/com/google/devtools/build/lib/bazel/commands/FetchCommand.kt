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
package com.google.devtools.build.lib.bazel.commands

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.primitives.Booleans
import com.google.devtools.build.lib.analysis.NoBuildEvent
import com.google.devtools.build.lib.bazel.commands.FetchCommand.Companion.createFailedBlazeCommandResult
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.Reporter
import com.google.devtools.build.lib.runtime.Command
import com.google.devtools.build.lib.runtime.commands.TestCommand
import com.google.devtools.build.skyframe.EvaluationContext
import com.google.devtools.common.options.OptionsParser
import com.google.devtools.common.options.OptionsParsingResult
import java.util.function.Function
import java.util.stream.Stream

/** Fetches external repositories. Which is so fetch.  */
@Command(
    name = FetchCommand.Companion.NAME,
    buildPhase = BuildPhase.ANALYZES,
    inheritsOptionsFrom = [TestCommand::class],
    options = [FetchOptions::class, PackageOptions::class, KeepGoingOption::class, LoadingPhaseThreadsOption::class
    ],
    usesConfigurationOptions = true,
    allowResidue = true,
    shortDescription = "Fetches external repositories that are prerequisites to the targets.",
    help = "resource:fetch.txt",
    completion = "label"
)
class FetchCommand : BlazeCommand {
    override fun editOptions(optionsParser: OptionsParser) {
        TargetFetcher.Companion.injectNoBuildOption(optionsParser)
    }

    override fun exec(env: CommandEnvironment, options: OptionsParsingResult): BlazeCommandResult {
        val invalidResult: BlazeCommandResult? = validateOptions(env, options)
        if (invalidResult != null) {
            return invalidResult
        }

        env.getEventBus()
            .post(
                NoBuildEvent(
                    env.getCommandName(),
                    env.getCommandStartTime(),  /* separateFinishedEvent= */
                    true,  /* showProgress= */
                    true,
                    env.getCommandId().toString()
                )
            )

        val fetchOptions = options.getOptions<FetchOptions?>(FetchOptions::class.java)
        if (fetchOptions!!.getForce()) {
            // Using commandId as the value -instead of true/false- to make sure to invalidate skyframe
            // and to actually force fetch each time
            env.getSkyframeExecutor()
                .injectExtraPrecomputedValues(
                    ImmutableList.of<Injected?>(
                        PrecomputedValue.injected<String?>(
                            if (fetchOptions.getConfigure())
                                RepositoryDirectoryValue.FORCE_FETCH_CONFIGURE
                            else
                                RepositoryDirectoryValue.FORCE_FETCH,
                            env.getCommandId().toString()
                        )
                    )
                )
        }

        val result: BlazeCommandResult
        val threadsOption: LoadingPhaseThreadsOption? =
            options.getOptions<LoadingPhaseThreadsOption?>(LoadingPhaseThreadsOption::class.java)
        val targets: MutableList<String?>
        try {
            targets = TargetPatternsHelper.readFrom(env, options)
        } catch (e: TargetPatternsHelperException) {
            env.getReporter().handle(Event.error(e.getMessage()))
            return BlazeCommandResult.failureDetail(e.getFailureDetail())
        }
        try {
            if (!targets.isEmpty()) {
                if (!fetchOptions.getRepos().isEmpty()) {
                    return createFailedBlazeCommandResult(
                        env.getReporter(), "Target patterns and --repo cannot both be specified"
                    )
                }
                result = fetchTarget(env, options, targets)
            } else if (!fetchOptions.getRepos().isEmpty()) {
                result = fetchRepos(env, threadsOption, fetchOptions.getRepos())
            } else { // --all or just 'fetch' (equivalent) or --configure
                result = fetchAll(env, threadsOption, fetchOptions.getConfigure())
            }
        } catch (e: InterruptedException) {
            return createFailedBlazeCommandResult(
                env.getReporter(), "Fetch interrupted: " + e.getMessage()
            )
        }

        env.getEventBus()
            .post(
                NoBuildRequestFinishedEvent(
                    result.getExitCode(), env.getRuntime().getClock().currentTimeMillis()
                )
            )
        return result
    }

    private fun validateOptions(env: CommandEnvironment, options: OptionsParsingResult): BlazeCommandResult? {
        val pkgOptions: PackageOptions? = options.getOptions<PackageOptions?>(PackageOptions::class.java)
        if (!pkgOptions.getFetch()) {
            return createFailedBlazeCommandResult(
                env.getReporter(), Code.OPTIONS_INVALID, "You cannot run fetch with --nofetch"
            )
        }
        val fetchOptions = options.getOptions<FetchOptions?>(FetchOptions::class.java)
        val optionsCount =
            Booleans.countTrue(
                fetchOptions!!.getAll(),
                fetchOptions.getConfigure(),
                !fetchOptions.getRepos().isEmpty(),
                !options.getResidue().isEmpty()
            )
        if (optionsCount > 1) {
            return createFailedBlazeCommandResult(
                env.getReporter(),
                Code.OPTIONS_INVALID,
                "Only one fetch option can be provided for fetch command"
            )
        }
        return null
    }

    @Throws(InterruptedException::class)
    private fun fetchAll(
        env: CommandEnvironment, threadsOption: LoadingPhaseThreadsOption, configureEnabled: Boolean
    ): BlazeCommandResult {
        val evaluationContext =
            EvaluationContext.newBuilder()
                .setParallelism(threadsOption.getThreads())
                .setEventHandler(env.getReporter())
                .build()

        val evaluationResult: EvaluationResult<SkyValue?> =
            env.getSkyframeExecutor()
                .prepareAndGet(
                    ImmutableSet.of<SkyKey?>(BazelFetchAllValue.Companion.key(configureEnabled)), evaluationContext
                )
        if (evaluationResult.hasError()) {
            val e: Exception? = evaluationResult.getError().getException()
            return createFailedBlazeCommandResult(
                env.getReporter(),
                if (e != null) e.getMessage() else "Unexpected error during fetching all external deps."
            )
        }

        env.getReporter().handle(Event.info("All external dependencies fetched successfully."))
        return BlazeCommandResult.success()
    }

    @Throws(InterruptedException::class)
    private fun fetchRepos(
        env: CommandEnvironment, threadsOption: LoadingPhaseThreadsOption?, repos: MutableList<String?>?
    ): BlazeCommandResult {
        val repositoryNamesAndValues: ImmutableMap<RepositoryName?, RepositoryDirectoryValue?>
        try {
            repositoryNamesAndValues = RepositoryFetcher.Companion.fetchRepos(repos, env, threadsOption)
        } catch (e: RepositoryMappingResolutionException) {
            return Companion.createFailedBlazeCommandResult(
                env.getReporter(), "Invalid repo name: " + e.getMessage(), e.getDetailedExitCode()
            )
        } catch (e: RepositoryFetcherException) {
            return createFailedBlazeCommandResult(env.getReporter(), e.getMessage())
        }

        val notFoundRepos: String =
            repositoryNamesAndValues.values().stream()
                .flatMap<String?>(
                    Function { value: RepositoryDirectoryValue? ->
                        if (value is)
                            Stream.of<String?>(errorMsg)
                        else
                            Stream.of<String?>()
                    })
                .collect(Collectors.joining("; "))
        if (!notFoundRepos.isEmpty()) {
            return createFailedBlazeCommandResult(
                env.getReporter(), "Fetching some repos failed with errors: " + notFoundRepos
            )
        }
        env.getReporter().handle(Event.info("All requested repos fetched successfully."))
        return BlazeCommandResult.success()
    }

    private fun fetchTarget(
        env: CommandEnvironment, options: OptionsParsingResult?, targets: MutableList<String?>?
    ): BlazeCommandResult {
        try {
            val unused: BuildResult = TargetFetcher.Companion.fetchTargets(env, options, targets)
        } catch (e: TargetFetcherException) {
            return createFailedBlazeCommandResult(
                env.getReporter(), Code.QUERY_EVALUATION_ERROR, e.getMessage()
            )
        }
        env.getReporter()
            .handle(
                Event.info(
                    "All external dependencies for the requested targets fetched successfully."
                )
            )
        return BlazeCommandResult.success()
    }

    companion object {
        const val NAME: String = "fetch"

        private fun createFailedBlazeCommandResult(
            reporter: Reporter, fetchCommandCode: Code?, message: String?
        ): BlazeCommandResult {
            return Companion.createFailedBlazeCommandResult(
                reporter,
                message,
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setFetchCommand(
                            FailureDetails.FetchCommand.newBuilder().setCode(fetchCommandCode).build()
                        )
                        .build()
                )
            )
        }

        private fun createFailedBlazeCommandResult(
            reporter: Reporter, errorMessage: String?
        ): BlazeCommandResult {
            return Companion.createFailedBlazeCommandResult(
                reporter, errorMessage, InterruptedFailureDetails.detailedExitCode(errorMessage)
            )
        }

        private fun createFailedBlazeCommandResult(
            reporter: Reporter, message: String?, exitCode: DetailedExitCode?
        ): BlazeCommandResult {
            reporter.handle(Event.error(message))
            return BlazeCommandResult.detailedExitCode(exitCode)
        }
    }
}
