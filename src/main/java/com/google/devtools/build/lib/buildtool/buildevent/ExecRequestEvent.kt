// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool.buildevent


import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos

/**
 * Event triggered after building of the run command has completed and the [ExecRequest] has
 * been constructed.
 */
class ExecRequestEvent(execRequest: ExecRequest, redactedArgv: ImmutableList<ByteString?>?) : BuildEvent {
    private val execRequest: ExecRequest
    private val redactedArgv: ImmutableList<ByteString?>?

    init {
        this.execRequest = execRequest
        this.redactedArgv = redactedArgv
    }

    override fun asStreamProto(context: BuildEventContext?): BuildEvent {
        val builder: BuildEventStreamProtos.ExecRequestConstructed.Builder =
            BuildEventStreamProtos.ExecRequestConstructed.newBuilder()
        builder.setWorkingDirectory(execRequest.getWorkingDirectory())
        for (environmentVariable in execRequest.getEnvironmentVariableList()) {
            builder.addEnvironmentVariable(
                EnvironmentVariable.newBuilder()
                    .setName(environmentVariable.getName())
                    .setValue(environmentVariable.getValue())
            )
        }
        for (envVarToClear in execRequest.getEnvironmentVariableToClearList()) {
            builder.addEnvironmentVariableToClear(envVarToClear)
        }
        builder.setShouldExec(execRequest.getShouldExec())
        // Use the event's redacted argv instead of the ExecRequest's argv.
        builder.addAllArgv(redactedArgv)
        return GenericBuildEvent.Companion.protoChaining(this).setExecRequest(builder.build()).build()
    }

    val eventId: BuildEventId?
        get() = BuildEventIdUtil.execRequestId()

    val childrenEvents: MutableCollection<BuildEventId>
        get() = ImmutableList.of<BuildEventId?>()
}
