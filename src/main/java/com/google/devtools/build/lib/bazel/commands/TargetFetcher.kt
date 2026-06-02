// Copyright 2024 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.buildtool.BuildRequest
import com.google.devtools.build.lib.buildtool.BuildResult
import com.google.devtools.build.lib.buildtool.BuildTool
import com.google.devtools.build.lib.runtime.CommandEnvironment
import com.google.devtools.common.options.OptionPriority
import com.google.devtools.common.options.OptionsParser
import com.google.devtools.common.options.OptionsParsingException
import com.google.devtools.common.options.OptionsParsingResult

/** Fetches all repos needed for building a given set of targets.  */
class TargetFetcher private constructor(private val env: CommandEnvironment) {
    @Throws(TargetFetcherException::class)
    private fun fetchTargets(options: OptionsParsingResult?, targets: MutableList<String?>?): BuildResult {
        val request =
            BuildRequest.builder()
                .setCommandName(env.getCommandName())
                .setId(env.getCommandId())
                .setOptions(options)
                .setStartupOptions(env.getRuntime().getStartupOptionsProvider())
                .setOutErr(env.getReporter().getOutErr())
                .setTargets(targets)
                .setStartTimeMillis(env.getCommandStartTime())
                .build()

        val result = BuildTool(env).processRequest(request, null, options)
        if (!result.getSuccess()) {
            throw TargetFetcherException(
                "Fetching some target dependencies failed with errors: "
                        + result.getDetailedExitCode().getFailureDetail().getMessage()
            )
        }
        return result
    }

    internal class TargetFetcherException(message: String?) : Exception(message)
    companion object {
        /** Creates a no-build build request to fetch all repos needed to build these targets  */
        @Throws(TargetFetcherException::class)
        fun fetchTargets(
            env: CommandEnvironment, options: OptionsParsingResult?, targets: MutableList<String?>?
        ): BuildResult {
            return TargetFetcher(env).fetchTargets(options, targets)
        }

        fun injectNoBuildOption(optionsParser: OptionsParser) {
            try {
                optionsParser.parse(
                    OptionPriority.PriorityCategory.COMPUTED_DEFAULT,
                    "Options required to fetch target",
                    ImmutableList.of<String?>("--nobuild")
                )
            } catch (e: OptionsParsingException) {
                throw IllegalStateException("Fetch target needed option failed to parse", e)
            }
        }
    }
}
