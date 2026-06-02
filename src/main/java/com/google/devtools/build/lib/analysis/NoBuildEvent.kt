// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos

/** This event raised to indicate that no build will be happening for the given command.  */
class NoBuildEvent @kotlin.jvm.JvmOverloads constructor(
    private val command: String? = null,
    private val startTimeMillis: Long? = null,
    private val separateFinishedEvent: Boolean = false,
    private val showProgress: Boolean = false,
    private val id: String? = null
) : BuildEvent {
    override fun getChildrenEvents(): com.google.common.collect.ImmutableList<BuildEventId?> {
        if (separateFinishedEvent) {
            return com.google.common.collect.ImmutableList.of<BuildEventId?>(
                ProgressEvent.INITIAL_PROGRESS_UPDATE, BuildEventIdUtil.buildFinished()
            )
        } else {
            return com.google.common.collect.ImmutableList.of<BuildEventId?>(ProgressEvent.INITIAL_PROGRESS_UPDATE)
        }
    }

    override fun getEventId(): BuildEventId? {
        return BuildEventIdUtil.buildStartedId()
    }

    override fun asStreamProto(converters: BuildEventContext?): BuildEvent {
        val started: BuildEventStreamProtos.BuildStarted.Builder =
            BuildEventStreamProtos.BuildStarted.newBuilder()
                .setBuildToolVersion(BlazeVersionInfo.instance().getVersion())
        if (command != null) {
            started.setCommand(command)
        }
        if (startTimeMillis != null) {
            started
                .setStartTimeMillis(startTimeMillis)
                .setStartTime(Timestamps.fromMillis(startTimeMillis))
        }
        if (id != null) {
            started.setUuid(id)
        }
        started.setServerPid(java.lang.ProcessHandle.current().pid())
        return GenericBuildEvent.protoChaining(this).setStarted(started.build()).build()
    }

    /**
     * Iff true, clients will expect to a receive a separate [ ].
     */
    fun separateFinishedEvent(): Boolean {
        return separateFinishedEvent
    }

    fun showProgress(): Boolean {
        return showProgress
    }
}
