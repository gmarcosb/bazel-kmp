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

/** Event reporting about the configurations associated with a given apect for a target  */
class AspectConfiguredEvent(
    target: Label?,
    aspectClassName: String?,
    aspectDescription: String?,
    configuration: BuildConfigurationValue?
) : BuildEventWithConfiguration {
    private val target: Label?
    private val aspectClassName: String?
    private val aspectDescription: String?
    private val configuration: BuildConfigurationValue?

    init {
        this.target = target
        this.aspectClassName = aspectClassName
        this.aspectDescription = aspectDescription
        this.configuration = configuration
    }

    public override fun getConfigurations(): com.google.common.collect.ImmutableList<BuildEvent?> {
        return com.google.common.collect.ImmutableList.of<BuildEvent?>(
            BuildConfigurationValue.Companion.buildEvent(
                configuration
            )
        )
    }

    public override fun getEventId(): BuildEventId {
        return BuildEventIdUtil.aspectConfigured(target, aspectClassName)
    }

    public override fun getChildrenEvents(): com.google.common.collect.ImmutableList<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<E?>(
            BuildEventIdUtil.aspectCompleted(
                target, BuildConfigurationValue.Companion.configurationId(configuration), aspectDescription
            )
        )
    }

    fun getAspectClassName(): String? {
        return aspectClassName
    }

    fun getTarget(): Label? {
        return target
    }

    public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
        val builder: BuildEventStreamProtos.TargetConfigured.Builder =
            BuildEventStreamProtos.TargetConfigured.newBuilder()
        return GenericBuildEvent.protoChaining(this).setConfigured(builder.build()).build()
    }
}
