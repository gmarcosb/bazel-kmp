// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.buildeventstream.BuildEvent

/** Event reporting about failure to expand a target pattern properly.  */
class PatternExpandingError private constructor(
  @kotlin.jvm.JvmField val pattern: MutableList<String?>?,
  private val message: String?,
  private val skipped: Boolean
) : BuildEvent {
    val eventId: BuildEventId
        get() {
            if (skipped) {
                return BuildEventIdUtil.targetPatternSkipped(pattern)
            } else {
                return BuildEventIdUtil.targetPatternExpanded(pattern)
            }
        }

    val childrenEvents: MutableCollection<BuildEventId>
        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>()

    public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
        val failure: BuildEventStreamProtos.Aborted? =
            BuildEventStreamProtos.Aborted.newBuilder()
                .setReason(BuildEventStreamProtos.Aborted.AbortReason.LOADING_FAILURE)
                .setDescription(message)
                .build()
        return GenericBuildEvent.protoChaining(this).setAborted(failure).build()
    }

    public override fun storeForReplay(): Boolean {
        return true
    }

    companion object {
        fun failed(pattern: MutableList<String?>?, message: String?): PatternExpandingError {
            return PatternExpandingError(pattern, message, false)
        }

        @kotlin.jvm.JvmStatic
        fun failed(term: String, message: String?): PatternExpandingError {
            return PatternExpandingError(com.google.common.collect.ImmutableList.of<String?>(term), message, false)
        }

        // This is unused right now - when we generate the error, we don't know if we're in keep_going
        // mode or not.
        @kotlin.jvm.JvmStatic
        fun skipped(term: String, message: String?): PatternExpandingError {
            return PatternExpandingError(com.google.common.collect.ImmutableList.of<String?>(term), message, true)
        }
    }
}
