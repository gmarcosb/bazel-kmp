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
package com.google.devtools.build.lib.bazel.coverage

import com.google.devtools.build.lib.actions.ActionOwner
import java.util.*

/**
 * A value class that holds arguments for [CoverageReportActionBuilder.CoverageHelper]
 * methods.
 */
class CoverageArgs(
    directories: BlazeDirectories?,
    coverageArtifacts: NestedSet<Artifact?>?,
    lcovArtifact: Artifact?,
    factory: ArtifactFactory?,
    artifactOwner: ArtifactOwner?,
    reportGenerator: FilesToRunProvider?,
    workspaceName: String?,
    htmlReport: Artifact?,
    actionOwner: ActionOwner?
) {
    val directories: BlazeDirectories?
    val coverageArtifacts: NestedSet<Artifact?>?
    val lcovArtifact: Artifact?
    val factory: ArtifactFactory?
    val artifactOwner: ArtifactOwner?
    val reportGenerator: FilesToRunProvider?
    val workspaceName: String?
    val htmlReport: Artifact?
    val actionOwner: ActionOwner?

    init {
        this.actionOwner = actionOwner
        this.htmlReport = htmlReport
        this.workspaceName = workspaceName
        this.reportGenerator = reportGenerator
        this.artifactOwner = artifactOwner
        this.factory = factory
        this.lcovArtifact = lcovArtifact
        this.coverageArtifacts = coverageArtifacts
        this.directories = directories
        Objects.requireNonNull<Any?>(directories, "directories")
        Objects.requireNonNull<NestedSet<Artifact?>?>(coverageArtifacts, "coverageArtifacts")
        Objects.requireNonNull<Any?>(lcovArtifact, "lcovArtifact")
        Objects.requireNonNull<Any?>(factory, "factory")
        Objects.requireNonNull<Any?>(artifactOwner, "artifactOwner")
        Objects.requireNonNull<Any?>(reportGenerator, "reportGenerator")
        Objects.requireNonNull<String?>(workspaceName, "workspaceName")
        Objects.requireNonNull<Any?>(actionOwner)
    }
}
