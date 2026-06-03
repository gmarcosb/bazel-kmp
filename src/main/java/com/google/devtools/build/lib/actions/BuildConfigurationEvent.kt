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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.buildeventstream.BuildEvent

/**
 * Encapsulation of [BuildEvent] info associated with a [ ].
 */
class BuildConfigurationEvent(eventId: BuildEventId?, eventProto: BuildEventStreamProtos.BuildEvent?) : BuildEvent {
    private val eventId: BuildEventId?
    private val eventProto: BuildEventStreamProtos.BuildEvent?

    init {
        this.eventId = eventId
        this.eventProto = eventProto
    }

    public override fun asStreamProto(unusedConverters: BuildEventContext?): BuildEventStreamProtos.BuildEvent? {
        return eventProto
    }

    public override fun getEventId(): BuildEventId? {
        return eventId
    }

    public override fun getChildrenEvents(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<BuildEventId?>()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is BuildConfigurationEvent) {
            return false
        }
        return eventId == other.eventId && eventProto == other.eventProto
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(eventId, eventProto)
    }
}
