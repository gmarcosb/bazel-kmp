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
package com.google.devtools.build.lib.buildeventstream

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId

/**
 * Class all events completing a build inherit from.
 * 
 * 
 * This class is abstract as for each particular event a specialized class should be used.
 * However, subclasses do not have to implement anything.
 */
abstract class BuildCompletingEvent : BuildEvent {
    private val detailedExitCode: DetailedExitCode?
    private val exitCode: ExitCode
    private val finishTimeMillis: Long

    private val children: MutableCollection<BuildEventId?>?

    @kotlin.jvm.JvmOverloads
    constructor(
        exitCode: ExitCode,
        finishTimeMillis: Long,
        children: MutableCollection<BuildEventId?>? = com.google.common.collect.ImmutableList.of<BuildEventId?>()
    ) {
        this.detailedExitCode = null
        this.exitCode = exitCode
        this.finishTimeMillis = finishTimeMillis
        this.children = children
    }

    constructor(
        detailedExitCode: DetailedExitCode,
        finishTimeMillis: Long,
        children: MutableCollection<BuildEventId?>?
    ) {
        this.detailedExitCode = detailedExitCode
        this.exitCode = detailedExitCode.getExitCode()
        this.finishTimeMillis = finishTimeMillis
        this.children = children
    }

    fun getExitCode(): ExitCode {
        return exitCode
    }

    val eventId: BuildEventId?
        get() = BuildEventIdUtil.buildFinished()

    val childrenEvents: MutableCollection<BuildEventId>?
        get() = children

    override fun asStreamProto(converters: BuildEventContext?): BuildEvent {
        val protoExitCode: ExitCode? =
            BuildEventStreamProtos.BuildFinished.ExitCode.newBuilder()
                .setName(exitCode.name())
                .setCode(exitCode.getNumericExitCode())
                .build()

        val finished: BuildEventStreamProtos.BuildFinished.Builder =
            BuildEventStreamProtos.BuildFinished.newBuilder()
                .setOverallSuccess(ExitCode.SUCCESS == exitCode)
                .setExitCode(protoExitCode)
                .setFinishTime(Timestamps.fromMillis(finishTimeMillis))
                .setFinishTimeMillis(finishTimeMillis)

        if (detailedExitCode != null && detailedExitCode.getFailureDetail() != null) {
            finished.setFailureDetail(detailedExitCode.getFailureDetail())
        }

        return GenericBuildEvent.Companion.protoChaining(this).setFinished(finished.build()).build()
    }
}
