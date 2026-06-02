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
package com.google.devtools.build.lib.causes

import com.google.common.base.MoreObjects
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos
import com.google.devtools.build.lib.cmdline.Label
import java.util.*

/**
 * Class describing a [Cause] that can uniquely be described by a [Label] and [ ].
 */
class AnalysisFailedCause(
    private val label: Label,
    configurationId: ConfigurationId?,
    detailedExitCode: DetailedExitCode
) : Cause {
    private val configurationId: ConfigurationId?
    private val detailedExitCode: DetailedExitCode

    init {
        this.configurationId = configurationId
        this.detailedExitCode = detailedExitCode
    }

    override fun toString(): String {
        // TODO(mschaller): Tests expect non-escaped message strings, and protobuf (the FailureDetail in
        //  detailedExitCode) escapes them. Better versions of tests would check structured data, and
        //  doing that requires unwinding test infrastructure. Note the "inTest" blocks in
        //  SkyframeBuildView#processAnalysisErrors.
        return MoreObjects.toStringHelper(this)
            .add("label", label)
            .add("configurationId", configurationId)
            .add("detailedExitCode", detailedExitCode)
            .add("msg", detailedExitCode.getFailureDetail().getMessage())
            .toString()
    }

    override fun getLabel(): Label {
        return label
    }

    override fun getIdProto(): BuildEventStreamProtos.BuildEventId? {
        // This needs to match AnalysisRootCauseEvent.getEventId.
        return BuildEventIdUtil.configuredLabelId(label, configurationId)
    }

    override fun getDetailedExitCode(): DetailedExitCode {
        return detailedExitCode
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        } else if (o !is AnalysisFailedCause) {
            return false
        }
        val a = o
        return label == a.label
                && configurationId == a.configurationId
                && detailedExitCode == a.detailedExitCode
    }

    override fun hashCode(): Int {
        return Objects.hash(label, configurationId, detailedExitCode)
    }
}
