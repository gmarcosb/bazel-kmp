// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildeventstream.BuildEvent

/** An event that receives the execRoot of this blaze invocation.  */
class ExecRootEvent(execRoot: com.google.devtools.build.lib.vfs.Path) : BuildEvent {
    private val execRoot: com.google.devtools.build.lib.vfs.Path

    init {
        this.execRoot = execRoot
    }

    public override fun asStreamProto(context: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
        val workspaceConfigEvent: BuildEventStreamProtos.WorkspaceConfig? =
            BuildEventStreamProtos.WorkspaceConfig.newBuilder()
                .setLocalExecRoot(execRoot.getPathString())
                .build()
        return BuildEventStreamProtos.BuildEvent.newBuilder()
            .setId(this.eventId)
            .setWorkspaceInfo(workspaceConfigEvent)
            .build()
    }

    val eventId: BuildEventId
        get() = BuildEventIdUtil.workspaceConfigId()

    val childrenEvents: MutableCollection<BuildEventId>
        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>()
}
