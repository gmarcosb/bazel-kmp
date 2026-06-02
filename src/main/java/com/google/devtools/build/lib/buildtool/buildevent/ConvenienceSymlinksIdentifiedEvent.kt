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
package com.google.devtools.build.lib.buildtool.buildevent

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos

/**
 * This event is fired from ExecutionTool#handleConvenienceSymlinks() whenever convenience symlinks
 * are managed. If the value [ConvenienceSymlinksMode.NORMAL], LOG_ONLY, CLEAN is passed into
 * the build request option `--experimental_create_convenience_symlinks`, then this event will
 * be populated with convenience symlink entries. However, if [ConvenienceSymlinksMode.IGNORE]
 * is passed, then this will be an empty event.
 */
class ConvenienceSymlinksIdentifiedEvent(convenienceSymlinks: ImmutableList<ConvenienceSymlink?>?) : BuildEvent {
    private val convenienceSymlinks: ImmutableList<ConvenienceSymlink?>?

    /** Construct the ConvenienceSymlinksIdentifiedEvent.  */
    init {
        this.convenienceSymlinks = convenienceSymlinks
    }

    val eventId: BuildEventId?
        get() = BuildEventIdUtil.convenienceSymlinksIdentifiedId()

    val childrenEvents: MutableCollection<BuildEventId>
        get() = ImmutableList.of<BuildEventId?>()

    override fun asStreamProto(converters: BuildEventContext?): BuildEvent {
        val convenienceSymlinksIdentified: BuildEventStreamProtos.ConvenienceSymlinksIdentified? =
            BuildEventStreamProtos.ConvenienceSymlinksIdentified.newBuilder()
                .addAllConvenienceSymlinks(convenienceSymlinks)
                .build()
        return GenericBuildEvent.Companion.protoChaining(this)
            .setConvenienceSymlinksIdentified(convenienceSymlinksIdentified)
            .build()
    }
}
