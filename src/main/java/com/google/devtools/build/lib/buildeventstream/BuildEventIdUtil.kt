// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildeventstream

import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil.configurationIdMessage
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId

/**
 * Utilities for working with [BuildEventId].
 * 
 * 
 * Since event identifiers need to be created before the actual event, the event IDs are highly
 * structured so that equal identifiers can easily be generated. The main way of pregenerating event
 * identifiers that do not accidentally coincide is by providing a target or a target pattern;
 * therefore, those (if provided) are made specially visible.
 */
@javax.annotation.concurrent.Immutable
object BuildEventIdUtil {
    private val NULL_CONFIGURATION_ID_MESSAGE: ConfigurationId = BuildEventIdUtil.configurationIdMessage("none")
    private val NULL_CONFIGURATION_ID: BuildEventId = BuildEventIdUtil.configurationId(NULL_CONFIGURATION_ID_MESSAGE)

    @kotlin.jvm.JvmStatic
    fun unknownBuildEventId(details: String?): BuildEventId {
        val id: BuildEventId.UnknownBuildEventId? =
            BuildEventId.UnknownBuildEventId.newBuilder().setDetails(details).build()
        return BuildEventId.newBuilder().setUnknown(id).build()
    }

    @kotlin.jvm.JvmStatic
    fun progressId(count: Int): BuildEventId {
        val id: BuildEventId.ProgressId? = BuildEventId.ProgressId.newBuilder().setOpaqueCount(count).build()
        return BuildEventId.newBuilder().setProgress(id).build()
    }

    @kotlin.jvm.JvmStatic
    fun buildStartedId(): BuildEventId {
        val startedId: BuildEventId.BuildStartedId? = BuildEventId.BuildStartedId.getDefaultInstance()
        return BuildEventId.newBuilder().setStarted(startedId).build()
    }

    @kotlin.jvm.JvmStatic
    fun unstructuredCommandlineId(): BuildEventId {
        val commandLineId: BuildEventId.UnstructuredCommandLineId? =
            BuildEventId.UnstructuredCommandLineId.getDefaultInstance()
        return BuildEventId.newBuilder().setUnstructuredCommandLine(commandLineId).build()
    }

    @kotlin.jvm.JvmStatic
    fun structuredCommandlineId(commandLineLabel: String?): BuildEventId {
        val commandLineId: BuildEventId.StructuredCommandLineId? =
            BuildEventId.StructuredCommandLineId.newBuilder()
                .setCommandLineLabel(commandLineLabel)
                .build()
        return BuildEventId.newBuilder().setStructuredCommandLine(commandLineId).build()
    }

    @kotlin.jvm.JvmStatic
    fun optionsParsedId(): BuildEventId {
        val optionsParsedId: BuildEventId.OptionsParsedId? =
            BuildEventId.OptionsParsedId.getDefaultInstance()
        return BuildEventId.newBuilder().setOptionsParsed(optionsParsedId).build()
    }

    fun workspaceStatusId(): BuildEventId {
        return BuildEventId.newBuilder()
            .setWorkspaceStatus(BuildEventId.WorkspaceStatusId.getDefaultInstance())
            .build()
    }

    @kotlin.jvm.JvmStatic
    fun buildMetadataId(): BuildEventId {
        val buildMetadataId: BuildEventId.BuildMetadataId? =
            BuildEventId.BuildMetadataId.getDefaultInstance()
        return BuildEventId.newBuilder().setBuildMetadata(buildMetadataId).build()
    }

    @kotlin.jvm.JvmStatic
    fun workspaceConfigId(): BuildEventId {
        val workspaceConfigId: BuildEventId.WorkspaceConfigId? =
            BuildEventId.WorkspaceConfigId.getDefaultInstance()
        return BuildEventId.newBuilder().setWorkspace(workspaceConfigId).build()
    }

    fun fetchId(url: String?, downloader: Downloader?): BuildEventId {
        val fetchId: BuildEventId.FetchId? =
            BuildEventId.FetchId.newBuilder().setUrl(url).setDownloader(downloader).build()
        return BuildEventId.newBuilder().setFetch(fetchId).build()
    }

    fun configurationId(key: BuildConfigurationKey?): BuildEventId {
        return BuildEventIdUtil.configurationId(BuildEventIdUtil.configurationIdMessage(key))
    }

    private fun configurationId(id: ConfigurationId?): BuildEventId {
        return BuildEventId.newBuilder().setConfiguration(id).build()
    }

    @kotlin.jvm.JvmStatic
    fun configurationId(id: String?): BuildEventId {
        return BuildEventIdUtil.configurationId(BuildEventIdUtil.configurationIdMessage(id))
    }

    fun configurationIdMessage(key: BuildConfigurationKey?): ConfigurationId? {
        return if (key == null)
            nullConfigurationIdMessage()
        else
            configurationIdMessage(key.getOptions().checksum())
    }

    @kotlin.jvm.JvmStatic
    fun configurationIdMessage(checksum: String?): ConfigurationId {
        return ConfigurationId.newBuilder().setId(checksum).build()
    }

    @kotlin.jvm.JvmStatic
    fun execRequestId(): BuildEventId {
        return BuildEventId.newBuilder()
            .setExecRequest(BuildEventId.ExecRequestId.getDefaultInstance())
            .build()
    }

    @kotlin.jvm.JvmStatic
    fun nullConfigurationId(): BuildEventId {
        return NULL_CONFIGURATION_ID
    }

    fun nullConfigurationIdMessage(): ConfigurationId {
        return NULL_CONFIGURATION_ID_MESSAGE
    }

    private fun targetPatternExpanded(targetPattern: MutableList<String?>?, skipped: Boolean): BuildEventId {
        val patternId: BuildEventId.PatternExpandedId? =
            BuildEventId.PatternExpandedId.newBuilder().addAllPattern(targetPattern).build()
        val builder: BuildEventId.Builder = BuildEventId.newBuilder()
        if (skipped) {
            builder.setPatternSkipped(patternId)
        } else {
            builder.setPattern(patternId)
        }
        return builder.build()
    }

    fun targetPatternExpanded(targetPattern: MutableList<String?>?): BuildEventId {
        return targetPatternExpanded(targetPattern, false)
    }

    fun targetPatternSkipped(targetPattern: MutableList<String?>?): BuildEventId {
        return targetPatternExpanded(targetPattern, true)
    }

    fun targetConfigured(label: com.google.devtools.build.lib.cmdline.Label): BuildEventId {
        val configuredId: BuildEventId.TargetConfiguredId? =
            BuildEventId.TargetConfiguredId.newBuilder().setLabel(label.toString()).build()
        return BuildEventId.newBuilder().setTargetConfigured(configuredId).build()
    }

    fun aspectConfigured(label: com.google.devtools.build.lib.cmdline.Label, aspect: String?): BuildEventId {
        val configuredId: BuildEventId.TargetConfiguredId? =
            BuildEventId.TargetConfiguredId.newBuilder()
                .setLabel(label.toString())
                .setAspect(aspect)
                .build()
        return BuildEventId.newBuilder().setTargetConfigured(configuredId).build()
    }

    fun targetCompleted(
        target: com.google.devtools.build.lib.cmdline.Label,
        configuration: BuildEventId
    ): BuildEventId {
        val configId: BuildEventId.ConfigurationId? = configuration.getConfiguration()
        val targetId: BuildEventId.TargetCompletedId? =
            BuildEventId.TargetCompletedId.newBuilder()
                .setLabel(target.toString())
                .setConfiguration(configId)
                .build()
        return BuildEventId.newBuilder().setTargetCompleted(targetId).build()
    }

    fun configuredLabelId(
        label: com.google.devtools.build.lib.cmdline.Label, configurationId: BuildEventId.ConfigurationId?
    ): BuildEventId {
        val labelId: BuildEventId.ConfiguredLabelId? =
            BuildEventId.ConfiguredLabelId.newBuilder()
                .setLabel(label.toString())
                .setConfiguration(configurationId)
                .build()
        return BuildEventId.newBuilder().setConfiguredLabel(labelId).build()
    }

    fun unconfiguredLabelId(label: com.google.devtools.build.lib.cmdline.Label): BuildEventId {
        val labelId: BuildEventId.UnconfiguredLabelId? =
            BuildEventId.UnconfiguredLabelId.newBuilder().setLabel(label.toString()).build()
        return BuildEventId.newBuilder().setUnconfiguredLabel(labelId).build()
    }

    fun aspectCompleted(
        target: com.google.devtools.build.lib.cmdline.Label, configuration: BuildEventId, aspect: String?
    ): BuildEventId {
        val configId: BuildEventId.ConfigurationId? = configuration.getConfiguration()
        val targetId: BuildEventId.TargetCompletedId? =
            BuildEventId.TargetCompletedId.newBuilder()
                .setLabel(target.toString())
                .setConfiguration(configId)
                .setAspect(aspect)
                .build()
        return BuildEventId.newBuilder().setTargetCompleted(targetId).build()
    }

    fun actionCompleted(path: PathFragment): BuildEventId {
        return actionCompleted(path, null, null)
    }

    fun actionCompleted(
        path: PathFragment, label: com.google.devtools.build.lib.cmdline.Label?, configurationChecksum: String?
    ): BuildEventId {
        val actionId: ActionCompletedId.Builder =
            ActionCompletedId.newBuilder().setPrimaryOutput(path.toString())
        if (label != null) {
            actionId.setLabel(label.toString())
        }
        if (configurationChecksum != null) {
            actionId.setConfiguration(ConfigurationId.newBuilder().setId(configurationChecksum))
        }
        return BuildEventId.newBuilder().setActionCompleted(actionId).build()
    }

    @kotlin.jvm.JvmStatic
    fun fromArtifactGroupName(name: String?): BuildEventId {
        val namedSetId: BuildEventId.NamedSetOfFilesId? =
            BuildEventId.NamedSetOfFilesId.newBuilder().setId(name).build()
        return BuildEventId.newBuilder().setNamedSet(namedSetId).build()
    }

    fun testResult(
        target: com.google.devtools.build.lib.cmdline.Label,
        run: Int,
        shard: Int,
        attempt: Int,
        configuration: BuildEventId
    ): BuildEventId {
        val configId: BuildEventId.ConfigurationId? = configuration.getConfiguration()
        val resultId: BuildEventId.TestResultId? =
            BuildEventId.TestResultId.newBuilder()
                .setLabel(target.toString())
                .setConfiguration(configId)
                .setRun(run + 1)
                .setShard(shard + 1)
                .setAttempt(attempt)
                .build()
        return BuildEventId.newBuilder().setTestResult(resultId).build()
    }

    fun testResult(
        target: com.google.devtools.build.lib.cmdline.Label, run: Int, shard: Int, configuration: BuildEventId
    ): BuildEventId {
        return testResult(target, run, shard, 1, configuration)
    }

    fun testProgressId(
        label: String?,
        configId: BuildEventId.ConfigurationId?,
        run: Int,
        shard: Int,
        attempt: Int,
        opaqueCount: Int
    ): BuildEventId {
        return BuildEventId.newBuilder()
            .setTestProgress(
                BuildEventId.TestProgressId.newBuilder()
                    .setLabel(label)
                    .setConfiguration(configId)
                    .setRun(run)
                    .setShard(shard)
                    .setAttempt(attempt)
                    .setOpaqueCount(opaqueCount)
            )
            .build()
    }

    fun testSummary(target: com.google.devtools.build.lib.cmdline.Label, configuration: BuildEventId): BuildEventId {
        val configId: BuildEventId.ConfigurationId? = configuration.getConfiguration()
        val summaryId: BuildEventId.TestSummaryId? =
            BuildEventId.TestSummaryId.newBuilder()
                .setLabel(target.toString())
                .setConfiguration(configId)
                .build()
        return BuildEventId.newBuilder().setTestSummary(summaryId).build()
    }

    fun targetSummary(target: com.google.devtools.build.lib.cmdline.Label, configuration: BuildEventId): BuildEventId {
        val configId: BuildEventId.ConfigurationId? = configuration.getConfiguration()
        val summaryId: BuildEventId.TargetSummaryId? =
            BuildEventId.TargetSummaryId.newBuilder()
                .setLabel(target.toString())
                .setConfiguration(configId)
                .build()
        return BuildEventId.newBuilder().setTargetSummary(summaryId).build()
    }

    @kotlin.jvm.JvmStatic
    fun buildFinished(): BuildEventId {
        val finishedId: BuildEventId.BuildFinishedId? = BuildEventId.BuildFinishedId.getDefaultInstance()
        return BuildEventId.newBuilder().setBuildFinished(finishedId).build()
    }

    @kotlin.jvm.JvmStatic
    fun buildToolLogs(): BuildEventId {
        return BuildEventId.newBuilder()
            .setBuildToolLogs(BuildEventId.BuildToolLogsId.getDefaultInstance())
            .build()
    }

    @kotlin.jvm.JvmStatic
    fun buildMetrics(): BuildEventId {
        return BuildEventId.newBuilder()
            .setBuildMetrics(BuildEventId.BuildMetricsId.getDefaultInstance())
            .build()
    }

    fun convenienceSymlinksIdentifiedId(): BuildEventId {
        return BuildEventId.newBuilder()
            .setConvenienceSymlinksIdentified(
                BuildEventId.ConvenienceSymlinksIdentifiedId.getDefaultInstance()
            )
            .build()
    }
}
