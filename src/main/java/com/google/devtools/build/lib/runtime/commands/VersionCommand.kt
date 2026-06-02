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

/** The 'blaze version' command, which informs users about the blaze version information.  */
@Command(
    name = "version",
    buildPhase = NONE,
    options = [VersionOptions::class],
    allowResidue = false,
    mustRunInWorkspace = false,
    help = "resource:version.txt",
    shortDescription = "Prints version information for %{product}."
)
class VersionCommand : BlazeCommand {
    /** Options for the "version" command.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class VersionOptions : com.google.devtools.common.options.OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "gnu_format",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
            help = ("If set, write the version to stdout using the conventions described in the GNU"
                    + " standards.")
        )
        abstract val gnuFormat: Boolean
    }

    public override fun editOptions(optionsParser: com.google.devtools.common.options.OptionsParser?) {}

    public override fun exec(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult {
        env.getEventBus().post(NoBuildEvent())

        val info: java.util.Optional<String?> =
            getInfo(
                env.getRuntime().productName,
                BlazeVersionInfo.instance(),
                options.getOptions<VersionOptions?>(VersionOptions::class.java).getGnuFormat()
            )
        if (info.isPresent()) {
            env.getReporter().getOutErr().printOutLn(info.get())
            return BlazeCommandResult.success()
        }
        val message = "Version information not available"
        env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
        return BlazeCommandResult.failureDetail(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setVersionCommand(
                    FailureDetails.VersionCommand.newBuilder().setCode(Code.NOT_AVAILABLE)
                )
                .build()
        )
    }

    companion object {
        @com.google.common.annotations.VisibleForTesting
        fun getInfo(productName: String?, info: BlazeVersionInfo, gnuFormat: Boolean): java.util.Optional<String?> {
            if (info.getSummary() == null) {
                return java.util.Optional.empty<String?>()
            }
            if (gnuFormat) {
                return java.util.Optional.of<String?>(
                    productName + " " + (if (info.isReleasedBlaze()) info.getVersion() else "no_version")
                )
            }
            return java.util.Optional.of<T?>(info.getSummary())
        }
    }
}
