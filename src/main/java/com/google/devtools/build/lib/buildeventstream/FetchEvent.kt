// Copyright 2017 The Bazel Authors. All rights reserved.
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
 * A [BuildEvent] reporting that an external resource was fetched.
 * 
 * 
 * Events of this class will only be generated in builds that do the actual fetch, not in ones
 * that use a cached copy of the resource to download. In way, these events allow keeping track of
 * the access of external resources.
 */
class FetchEvent(val url: String?, downloader: Downloader?, success: Boolean) : BuildEvent,
    com.google.devtools.build.lib.events.ExtendedEventHandler.Postable {
    val eventId: BuildEventId?
        get() = BuildEventIdUtil.fetchId(url, downloader)

    val childrenEvents: MutableCollection<BuildEventId>
        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>()

    override fun asStreamProto(converters: BuildEventContext?): BuildEvent {
        val fetch: BuildEventStreamProtos.Fetch? =
            BuildEventStreamProtos.Fetch.newBuilder().setSuccess(success).build()
        return GenericBuildEvent.Companion.protoChaining(this).setFetch(fetch).build()
    }

    val downloader: Downloader?
    val success: Boolean

    init {
        this.downloader = downloader
        this.success = success
    }
}
