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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.buildeventstream.BuildEventContext

/** This event is fired once build info data is available.  */
class BuildInfoEvent
    (buildInfo: MutableMap<String?, String?>) : BuildEventWithOrderConstraint, Postable {
    private val buildInfoMap: MutableMap<String?, String?>

    /**
     * Construct the event from a map.
     */
    init {
        buildInfoMap = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(buildInfo)
    }

    /**
     * Return immutable map populated with build info key/value pairs.
     */
    fun getBuildInfoMap(): MutableMap<String?, String?> {
        return buildInfoMap
    }

    public override fun getEventId(): BuildEventId {
        return BuildEventIdUtil.workspaceStatusId()
    }

    public override fun getChildrenEvents(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<BuildEventId?>()
    }

    public override fun postedAfter(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<E?>(BuildEventIdUtil.buildStartedId())
    }

    public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
        val status: BuildEventStreamProtos.WorkspaceStatus.Builder =
            BuildEventStreamProtos.WorkspaceStatus.newBuilder()
        for (entry in getBuildInfoMap().entrySet()) {
            status.addItem(
                BuildEventStreamProtos.WorkspaceStatus.Item.newBuilder()
                    .setKey(entry.getKey())
                    .setValue(entry.getValue())
                    .build()
            )
        }
        return GenericBuildEvent.protoChaining(this).setWorkspaceStatus(status.build()).build()
    }
}
