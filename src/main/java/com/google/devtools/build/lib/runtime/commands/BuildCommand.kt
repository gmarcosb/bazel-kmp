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

/**
 * Handles the 'build' command on the Blaze command line, including targets named by arguments
 * passed to Blaze.
 */
@Command(
    name = "build",
    buildPhase = EXECUTES,
    options = [BuildRequestOptions::class, ExecutionOptions::class, LocalExecutionOptions::class, PackageOptions::class, AnalysisOptions::class, LoadingOptions::class, KeepGoingOption::class, LoadingPhaseThreadsOption::class, BuildEventProtocolOptions::class, SkyfocusOptions::class, RemoteAnalysisCachingOptions::class
    ],
    usesConfigurationOptions = true,
    shortDescription = "Builds the specified targets.",
    allowResidue = true,
    completion = "label",
    help = "resource:build.txt"
)
class BuildCommand : BlazeCommand {
    public override fun exec(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult {
        val runtime: BlazeRuntime = env.getRuntime()
        val targets: MutableList<String?>
        try {
            targets = TargetPatternsHelper.readFrom(env, options)
        } catch (e: TargetPatternsHelperException) {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.message))
            return BlazeCommandResult.failureDetail(e.getFailureDetail())
        }
        if (targets.isEmpty()) {
            env.getReporter()
                .handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        ("Usage: "
                                + runtime.productName
                                + " build <options> <targets>."
                                + "\nInvoke `"
                                + runtime.productName
                                + " help build` for full description of usage and options."
                                + "\nYour request is correct, but requested an empty set of targets."
                                + " Nothing will be built.")
                    )
                )
        }

        val request: BuildRequest?
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("BuildRequest.create").use { closeable ->
            request =
                BuildRequest.builder()
                    .setCommandName(javaClass.getAnnotation<A?>(Command::class.java).name())
                    .setId(env.getCommandId())
                    .setOptions(options)
                    .setStartupOptions(runtime.getStartupOptionsProvider())
                    .setOutErr(env.getReporter().getOutErr())
                    .setTargets(targets)
                    .setStartTimeMillis(env.commandStartTime)
                    .build()
        }
        val detailedExitCode: DetailedExitCode? =
            BuildTool(env).processRequest(request, null, options).getDetailedExitCode()
        return BlazeCommandResult.detailedExitCode(detailedExitCode)
    }
}
