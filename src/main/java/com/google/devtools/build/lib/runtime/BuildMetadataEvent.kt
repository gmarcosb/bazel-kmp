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

import com.google.devtools.build.lib.buildeventstream.BuildEventContext

/**
 * Build event announcing supplementary metadata accompanying the build in the form of key-value
 * string pairs.
 */
class BuildMetadataEvent
/**
 * Construct the build metadata event.
 * 
 * @param buildMetadata the supplementary build metadata for a single build.
 */(private val buildMetadata: MutableMap<String?, String?>) : BuildEventWithOrderConstraint {
    val eventId: BuildEventId
        get() = BuildEventIdUtil.buildMetadataId()

    val childrenEvents: MutableCollection<BuildEventId>
        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>()

    public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
        val metadataBuilder: BuildEventStreamProtos.BuildMetadata.Builder =
            BuildEventStreamProtos.BuildMetadata.newBuilder()
        for (entry in buildMetadata.entrySet()) {
            metadataBuilder.putMetadata(entry.getKey(), entry.getValue())
        }
        return GenericBuildEvent.protoChaining(this).setBuildMetadata(metadataBuilder.build()).build()
    }

    public override fun postedAfter(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<E?>(BuildEventIdUtil.buildStartedId())
    }
}
