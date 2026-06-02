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
package com.google.devtools.build.lib.metrics

import com.google.devtools.build.lib.buildeventstream.BuildEventContext

/** An event encapsulating build metrics collected during a build.  */
class BuildMetricsEvent(buildMetrics: BuildMetrics?) : BuildEventWithOrderConstraint {
    private val buildMetrics: BuildMetrics?

    init {
        this.buildMetrics = buildMetrics
    }

    public override fun getEventId(): BuildEventId {
        return BuildEventIdUtil.buildMetrics()
    }

    public override fun getChildrenEvents(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<BuildEventId?>()
    }

    public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
        return GenericBuildEvent.protoChaining(this).setBuildMetrics(buildMetrics).build()
    }

    fun getBuildMetrics(): BuildMetrics? {
        return buildMetrics
    }

    public override fun postedAfter(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<E?>(BuildEventIdUtil.buildFinished())
    }

    companion object {
        fun create(buildMetrics: BuildMetrics?): BuildMetricsEvent {
            return BuildMetricsEvent(buildMetrics)
        }
    }
}
