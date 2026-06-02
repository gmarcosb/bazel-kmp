// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.actions.Artifact

/**
 * Cquery output formatter that prints the set of output files advertised by the matched targets.
 */
class FilesOutputFormatterCallback internal constructor(
    eventHandler: ExtendedEventHandler?,
    options: CqueryOptions?,
    out: java.io.OutputStream?,
    skyframeExecutor: SkyframeExecutor?,
    accessor: TargetAccessor<CqueryNode?>?,
    topLevelArtifactContext: TopLevelArtifactContext?
) : CqueryThreadsafeCallback(eventHandler, options, out, skyframeExecutor, accessor,  /* uniquifyResults= */true) {
    private val topLevelArtifactContext: TopLevelArtifactContext?

    init {
        // Different targets may provide the same artifact, so we deduplicate the collection of all
        // results at the end.
        this.topLevelArtifactContext = topLevelArtifactContext
    }

    val name: String
        get() = "files"

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun processOutput(partialResult: Iterable<CqueryNode?>) {
        for (target in partialResult) {
            if (target !is ConfiguredTarget || (!TopLevelArtifactHelper.shouldConsiderForDisplay(target)
                        && target !is InputFileConfiguredTarget)
            ) {
                continue
            }

            for (configuredObject in com.google.common.collect.Iterables.concat<ConfiguredAspect?>(
                com.google.common.collect.ImmutableList.of<ConfiguredAspect?>(
                    target
                ), accessor.getTopLevelAspects(target)
            )) {
                TopLevelArtifactHelper.getAllArtifactsToBuild(configuredObject, topLevelArtifactContext)
                    .getImportantArtifacts()
                    .toList()
                    .stream()
                    .filter(
                        { artifact -> TopLevelArtifactHelper.shouldDisplay(artifact) || artifact.isSourceArtifact() })
                    .map(Artifact::getExecPathString)
                    .forEach({ string: String? -> this.addResult(string) })
            }
        }
    }
}
