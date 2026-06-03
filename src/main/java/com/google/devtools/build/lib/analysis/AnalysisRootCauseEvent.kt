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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.buildeventstream.BuildEvent

/**
 * Error message of an analysis root cause. This is separate from [AnalysisFailureEvent] to
 * avoid duplicating error messages in the stream if multiple targets fail due to the same root
 * cause. It also allows UIs to collate errors by root cause.
 */
class AnalysisRootCauseEvent private constructor(
    configuration: java.util.Optional<BuildConfigurationValue?>?,
    configurationId: ConfigurationId?,
    label: Label?,
    errorMessage: String?
) : BuildEventWithConfiguration {
    /**
     * A tri-state representation of the configuration to capture two different notions of nullness.
     * 
     * 
     *  * The contents of a non-empty value is a configuration value.
     *  * An [Optional.empty] represents the *null configuration*, used for
     * unconfigurable targets, for example source files.
     *  * A null value means an *unavailable configuration*. Sometimes errors may occur for a
     * transient [BuildConfigurationKey] for which a [BuildConfigurationValue] is
     * never computed, for example, the intermediate configuration after the attribute
     * transition occurs but before the rule transition.
     * 
     */
    private val configuration: java.util.Optional<BuildConfigurationValue?>?

    private val configurationId: ConfigurationId?
    private val label: Label?
    private val errorMessage: String?

    init {
        this.configuration = configuration
        this.configurationId = configurationId
        this.label = label
        this.errorMessage = errorMessage
    }

    @com.google.common.annotations.VisibleForTesting
    fun getLabel(): Label? {
        return label
    }

    public override fun getEventId(): BuildEventId {
        // This needs to match AnalysisFailedCause.getIdProto.
        return BuildEventIdUtil.configuredLabelId(label, configurationId)
    }

    public override fun getChildrenEvents(): com.google.common.collect.ImmutableList<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<BuildEventId?>()
    }

    public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
        return GenericBuildEvent.protoChaining(this)
            .setAborted(
                BuildEventStreamProtos.Aborted.newBuilder()
                    .setReason(BuildEventStreamProtos.Aborted.AbortReason.ANALYSIS_FAILURE)
                    .setDescription(errorMessage)
                    .build()
            )
            .build()
    }

    public override fun getConfigurations(): com.google.common.collect.ImmutableList<BuildEvent?> {
        if (configuration == null) {
            return com.google.common.collect.ImmutableList.of<BuildEvent?>()
        }
        return com.google.common.collect.ImmutableList.of<BuildEvent?>(
            BuildConfigurationValue.Companion.buildEvent(
                configuration.orElse(null)
            )
        )
    }

    public override fun storeForReplay(): Boolean {
        return true
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("configuration", configuration)
            .add("configurationId", configurationId)
            .add("label", label)
            .add("errorMessage", errorMessage)
            .toString()
    }

    companion object {
        fun withConfigurationValue(
            configuration: BuildConfigurationValue?, label: Label?, errorMessage: String?
        ): AnalysisRootCauseEvent {
            return AnalysisRootCauseEvent(
                java.util.Optional.ofNullable<BuildConfigurationValue?>(configuration),
                BuildConfigurationValue.Companion.configurationIdMessage(configuration),
                label,
                errorMessage
            )
        }

        fun withUnavailableConfiguration(
            configurationId: ConfigurationId?, label: Label?, errorMessage: String?
        ): AnalysisRootCauseEvent {
            return AnalysisRootCauseEvent( /* configuration= */
                null, configurationId, label, errorMessage
            )
        }
    }
}
