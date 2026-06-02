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
 * A [BuildEvent] reporting about progress.
 * 
 * 
 * Events of this type are used to report updates on the progress of the build. They are also
 * used to chain in failure events where the canonical parents (e.g., test suites) can only be
 * reported later.
 */
class ProgressEvent private constructor(
    id: BuildEventId?,
    children: MutableCollection<BuildEventId?>?,
    private val out: String?,
    private val err: String?
) : GenericBuildEvent(id, children) {
    override fun asStreamProto(converters: BuildEventContext?): BuildEvent {
        val builder: BuildEventStreamProtos.Progress.Builder = BuildEventStreamProtos.Progress.newBuilder()
        if (out != null) {
            builder.setStdout(out)
        }
        if (err != null) {
            builder.setStderr(err)
        }
        return GenericBuildEvent.Companion.protoChaining(this).setProgress(builder.build()).build()
    }

    companion object {
        /** The [BuildEventId] of the first progress event to be reported.  */
        @kotlin.jvm.JvmField
        val INITIAL_PROGRESS_UPDATE: BuildEventId = BuildEventIdUtil.progressId(0)

        /** Create a regular progress update with the given running number.  */
        @kotlin.jvm.JvmStatic
        @kotlin.jvm.JvmOverloads
        fun progressUpdate(number: Int, out: String? = null, err: String? = null): BuildEvent {
            val id: BuildEventId = BuildEventIdUtil.progressId(number)
            val next: BuildEventId = BuildEventIdUtil.progressId(number + 1)
            return ProgressEvent(id, com.google.common.collect.ImmutableList.of<BuildEventId?>(next), out, err)
        }

        /** Create a progress update event also chaining in a given id.  */
        fun progressChainIn(
            number: Int, chainIn: BuildEventId?, out: String?, err: String?
        ): BuildEvent {
            val id: BuildEventId = BuildEventIdUtil.progressId(number)
            val next: BuildEventId = BuildEventIdUtil.progressId(number + 1)
            return ProgressEvent(id, com.google.common.collect.ImmutableList.of<BuildEventId?>(next, chainIn), out, err)
        }

        fun progressChainIn(number: Int, chainIn: BuildEventId?): BuildEvent {
            return progressChainIn(number, chainIn, null, null)
        }

        /**
         * A progress update event with a given id, that has no children (and hence usually is the last
         * progress event in the stream).
         */
        @kotlin.jvm.JvmStatic
        @kotlin.jvm.JvmOverloads
        fun finalProgressUpdate(
            number: Int, out: String? = null, err: String? = null
        ): BuildEvent {
            val id: BuildEventId = BuildEventIdUtil.progressId(number)
            return ProgressEvent(id, com.google.common.collect.ImmutableList.of<BuildEventId?>(), out, err)
        }
    }
}
