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
package com.google.devtools.build.lib.bazel.coverage

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import com.google.devtools.build.lib.actions.ActionKeyContext
import com.google.devtools.build.lib.events.EventHandler
import com.google.devtools.common.options.*

/** Adds support for coverage report generation.  */
class BazelCoverageReportModule : BlazeModule() {
    /** Options that affect coverage report generation.  */
    @OptionsClass
    abstract class Options : OptionsBase() {
        @Option(
            name = "combined_report",
            converter = ReportTypeConverter::class,
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.UNKNOWN],
            defaultValue = "lcov",
            help = ("Specifies desired cumulative coverage report type. At this point only LCOV "
                    + "is supported.")
        )
        abstract fun getCombinedReport(): ReportType?
    }

    /** Possible values for the --combined_report option.  */
    enum class ReportType {
        NONE,
        LCOV,
    }

    /** Converter for the --combined_report option.  */
    class ReportTypeConverter : EnumConverter<ReportType?>(ReportType::class.java, "combined coverage report type")

    override fun getCommandOptions(commandName: String): ImmutableList<Class<out OptionsBase?>?> {
        return if (commandName == "build") ImmutableList.of<Class<out OptionsBase?>?>(Options::class.java) else ImmutableList.of<Class<out OptionsBase?>?>()
    }

    override fun getCoverageReportFactory(commandOptions: OptionsProvider): CoverageReportActionFactory? {
        val options = commandOptions.getOptions<Options?>(Options::class.java)
        return object : CoverageReportActionFactory {
            @Throws(InterruptedException::class)
            override fun createCoverageReportActionsWrapper(
                eventHandler: EventHandler?,
                eventBus: EventBus,
                directories: BlazeDirectories?,
                configuredTargets: MutableCollection<ConfiguredTarget?>?,
                targetsToTest: MutableCollection<ConfiguredTarget?>?,
                artifactFactory: ArtifactFactory?,
                actionKeyContext: ActionKeyContext?,
                actionLookupKey: ActionLookupKey?,
                workspaceName: String?
            ): CoverageReportActionsWrapper? {
                if (options == null || options.getCombinedReport() == ReportType.NONE) {
                    return null
                }
                Preconditions.checkArgument(options.getCombinedReport() == ReportType.LCOV)
                val builder = CoverageReportActionBuilder()
                val wrapper: CoverageReportActionsWrapper? =
                    builder.createCoverageActionsWrapper(
                        eventHandler,
                        directories,
                        configuredTargets,
                        targetsToTest,
                        artifactFactory,
                        actionKeyContext,
                        actionLookupKey,
                        workspaceName,
                        BazelCoverageHelper(),  /* htmlReport= */
                        null
                    )
                if (wrapper == null) {
                    return null
                }
                eventBus.register(CoverageReportCollector(wrapper))
                return wrapper
            }
        }
    }

    private class BazelCoverageHelper : CoverageHelper {
        override fun getArgs(args: CoverageArgs, lcovOutput: Artifact): ImmutableList<String?> {
            val argsBuilder =
                ImmutableList.builder<String?>()
                    .add(
                        args.reportGenerator.getExecutable()
                            .getExecPathString(),  // A file that contains all the exec paths to the coverage artifacts
                        "--reports_file=" + args.lcovArtifact.getExecPathString(),
                        "--output_file=" + lcovOutput.getExecPathString()
                    )
            return argsBuilder.build()
        }

        override fun getLocationMessage(args: CoverageArgs?, lcovOutput: Artifact): String {
            return ("LCOV coverage report is located at "
                    + lcovOutput.getPath().getPathString()
                    + "\n and execpath is "
                    + lcovOutput.getExecPathString())
        }
    }

    private class CoverageReportCollector(wrapper: CoverageReportActionsWrapper?) {
        @Subscribe
        fun buildComplete(event: BuildCompleteEvent) {
            event
                .getResult()
                .getBuildToolLogCollection()
                .addLocalFile("coverage_report.lcov", wrapper.getCoverageReportArtifact().getPath())
                .addLocalFile("baseline_report.lcov", wrapper.getBaselineReportArtifact().getPath())
        }

        val wrapper: CoverageReportActionsWrapper?

        init {
            this.wrapper = wrapper
        }
    }
}
