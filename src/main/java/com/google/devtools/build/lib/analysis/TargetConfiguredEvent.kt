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

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/** Event reporting about the configuration associated with a given target  */
class TargetConfiguredEvent(
    target: com.google.devtools.build.lib.packages.Target,
    configuration: BuildConfigurationValue?,
    actual: com.google.devtools.build.lib.cmdline.Label?
) : BuildEventWithConfiguration {
    private val target: com.google.devtools.build.lib.packages.Target
    private val configuration: BuildConfigurationValue?
    private val actual: com.google.devtools.build.lib.cmdline.Label?

    init {
        this.target = target
        this.configuration = configuration
        this.actual = actual
    }

    val configurations: com.google.common.collect.ImmutableList<BuildEvent?>
        get() = com.google.common.collect.ImmutableList.of<E?>(BuildConfigurationValue.buildEvent(configuration))

    val eventId: BuildEventId?
        get() = BuildEventIdUtil.targetConfigured(target.getLabel())

    val childrenEvents: com.google.common.collect.ImmutableList<BuildEventId?>
        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>(
            BuildEventIdUtil.targetCompleted(
                target.getLabel(), BuildConfigurationValue.configurationId(configuration)
            )
        )

    override fun asStreamProto(converters: BuildEventContext?): BuildEvent {
        val builder: BuildEventStreamProtos.TargetConfigured.Builder =
            BuildEventStreamProtos.TargetConfigured.newBuilder().setTargetKind(target.getTargetKind())
        val rule: com.google.devtools.build.lib.packages.Rule? = target.getAssociatedRule()
        if (rule != null && RawAttributeMapper.of(rule).has("tags")) {
            // Not every rule has tags, as, due to the "external" package we also have to expect
            // repository rules at this place.
            builder.addAllTag(
                RawAttributeMapper.of(rule)
                    .getMergedValues<T?>("tags", com.google.devtools.build.lib.packages.Types.STRING_LIST)
            )
        }
        if (TargetUtils.isTestRule(target)) {
            builder.setTestSize(
                bepTestSize(target.getName(), TestSize.getTestSize(target.getAssociatedRule()))
            )
        }
        if (actual != null) {
            builder.setActual(actual.toString())
        }
        return GenericBuildEvent.protoChaining(this).setConfigured(builder.build()).build()
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        private fun bepTestSize(targetName: String?, size: TestSize?): TestSize? {
            if (size != null) {
                return when (size) {
                    TestSize.SMALL -> BuildEventStreamProtos.TestSize.SMALL
                    TestSize.MEDIUM -> BuildEventStreamProtos.TestSize.MEDIUM
                    TestSize.LARGE -> BuildEventStreamProtos.TestSize.LARGE
                    TestSize.ENORMOUS -> BuildEventStreamProtos.TestSize.ENORMOUS
                }
            }
            logger.atInfo().log("Target %s has a test size of: %s", targetName, size)
            return BuildEventStreamProtos.TestSize.UNKNOWN
        }
    }
}
