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

/** Failure due to something associated with a label.  */
open class LabelCause(private val label: Label, detailedExitCode: DetailedExitCode) : Cause {
    private val detailedExitCode: DetailedExitCode

    init {
        this.detailedExitCode = detailedExitCode
    }

    override fun toString(): String {
        return MoreObjects.toStringHelper(this)
            .add("label", label)
            .add("detailedExitCode", detailedExitCode)
            .toString()
    }

    override fun getLabel(): Label {
        return label
    }

    override fun getDetailedExitCode(): DetailedExitCode {
        return detailedExitCode
    }

    val message: String
        get() = detailedExitCode.getFailureDetail().getMessage()

    override fun getIdProto(): BuildEventStreamProtos.BuildEventId {
        return BuildEventStreamProtos.BuildEventId.newBuilder()
            .setUnconfiguredLabel(
                BuildEventStreamProtos.BuildEventId.UnconfiguredLabelId.newBuilder()
                    .setLabel(label.toString())
                    .build()
            )
            .build()
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        } else if (o !is LabelCause) {
            return false
        }
        val a = o
        return label == a.label && detailedExitCode == a.detailedExitCode
    }

    override fun hashCode(): Int {
        return Objects.hash(label, detailedExitCode)
    }
}
