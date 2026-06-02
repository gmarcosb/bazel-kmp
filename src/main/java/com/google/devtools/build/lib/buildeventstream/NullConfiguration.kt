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
package com.google.devtools.build.lib.buildeventstream

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId

/**
 * [BuildEvent] presenting the configuration in the build event protocol that internally is
 * just a null pointer.
 */
class NullConfiguration private constructor() : BuildEvent {
    val eventId: BuildEventId?
        get() = BuildEventIdUtil.nullConfigurationId()

    val childrenEvents: MutableCollection<BuildEventId>
        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>()

    override fun asStreamProto(converters: BuildEventContext?): BuildEvent {
        return GenericBuildEvent.Companion.protoChaining(this)
            .setConfiguration(BuildEventStreamProtos.Configuration.getDefaultInstance())
            .build()
    }

    companion object {
        @kotlin.jvm.JvmField
        val INSTANCE: NullConfiguration = NullConfiguration()
    }
}
