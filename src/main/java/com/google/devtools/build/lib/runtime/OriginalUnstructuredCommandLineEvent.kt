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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildeventstream.BuildEventContext

/** A build event reporting the original commandline by which bazel was invoked.  */
class OriginalUnstructuredCommandLineEvent internal constructor(args: MutableList<String?>) :
    BuildEventWithOrderConstraint {
    private val args: com.google.common.collect.ImmutableList<String?>

    init {
        this.args = com.google.common.collect.ImmutableList.copyOf<String?>(args)
    }

    val eventId: BuildEventId
        get() = BuildEventIdUtil.unstructuredCommandlineId()

    val childrenEvents: MutableCollection<BuildEventId>
        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>()

    public override fun postedAfter(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<E?>(BuildEventIdUtil.buildStartedId())
    }

    public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
        return GenericBuildEvent.protoChaining(this)
            .setUnstructuredCommandLine(
                BuildEventStreamProtos.UnstructuredCommandLine.newBuilder().addAllArgs(args).build()
            )
            .build()
    }

    companion object {
        val REDACTED_UNSTRUCTURED_COMMAND_LINE_EVENT: OriginalUnstructuredCommandLineEvent =
            OriginalUnstructuredCommandLineEvent(com.google.common.collect.ImmutableList.of<String?>("REDACTED"))
    }
}
