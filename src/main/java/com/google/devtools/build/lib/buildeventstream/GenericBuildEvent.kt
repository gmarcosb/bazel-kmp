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

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId

/**
 * Class for a generic [BuildEvent].
 * 
 * 
 * This class implements a basic [BuildEvent]. The main purpose of this class is to provide
 * the common infrastructure for the infrastructural events.
 */
open class GenericBuildEvent(id: BuildEventId?, children: MutableCollection<BuildEventId?>?) : BuildEvent {
    private val id: BuildEventId?
    private val children: MutableCollection<BuildEventId?>?

    init {
        this.id = id
        this.children = children
    }

    val eventId: BuildEventId?
        get() = id

    val childrenEvents: MutableCollection<BuildEventId>?
        get() = children

    override fun asStreamProto(converters: BuildEventContext?): BuildEvent {
        return protoChaining(this).build()
    }

    companion object {
        fun protoChaining(event: ChainableEvent): BuildEventStreamProtos.BuildEvent.Builder {
            val builder: BuildEventStreamProtos.BuildEvent.Builder =
                BuildEventStreamProtos.BuildEvent.newBuilder()
            builder.setId(event.getEventId())
            for (childId in event.getChildrenEvents()) {
                builder.addChildren(childId)
            }
            return builder
        }
    }
}
