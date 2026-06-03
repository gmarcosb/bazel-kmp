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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.buildeventstream.BuildEvent

/** Interface for [BuildEvent]s reporting artifacts as named sets  */
interface EventReportingArtifacts : BuildEvent {
    /** Pair of artifacts and a [CompletionContext].  */
    class ReportedArtifacts(
        artifacts: MutableCollection<NestedSet<Artifact?>?>?,
        completionContext: CompletionContext?
    ) {
        val artifacts: MutableCollection<NestedSet<Artifact?>?>?
        val completionContext: CompletionContext?

        init {
            this.artifacts = artifacts
            this.completionContext = completionContext
        }
    }

    /** The sets of artifacts this build event assumes already known in the build event stream.  */
    fun reportedArtifacts(outputGroupFileModes: OutputGroupFileModes?): ReportedArtifacts?
}
