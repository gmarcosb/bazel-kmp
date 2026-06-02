// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.actions.Artifact

class CoverageArtifactsKnownEvent(coverageArtifacts: com.google.common.collect.ImmutableSet<Artifact?>?) :
    com.google.devtools.build.lib.events.ExtendedEventHandler.Postable {
    val coverageArtifacts: com.google.common.collect.ImmutableSet<Artifact?>?

    init {
        this.coverageArtifacts = coverageArtifacts
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<Artifact?>?>(
            coverageArtifacts,
            "coverageArtifacts"
        )
    }

    companion object {
        fun create(coverageArtifacts: com.google.common.collect.ImmutableSet<Artifact?>?): CoverageArtifactsKnownEvent {
            return CoverageArtifactsKnownEvent(coverageArtifacts)
        }
    }
}
