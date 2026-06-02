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
package com.google.devtools.build.lib.causes

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos

/** Interface for classes identifying root causes for a target to fail to build.  */
interface Cause {
    /** Return the label associated with the failure.  */
    @kotlin.jvm.JvmField
    val label: Label?

    /** Return the event id for the cause in the format of the build event protocol.  */
    @kotlin.jvm.JvmField
    val idProto: BuildEventStreamProtos.BuildEventId?

    /** Return details describing the failure.  */
    @kotlin.jvm.JvmField
    val detailedExitCode: DetailedExitCode?
}
