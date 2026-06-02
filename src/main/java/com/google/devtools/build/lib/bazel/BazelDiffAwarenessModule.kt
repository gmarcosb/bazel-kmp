// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel

import com.google.devtools.build.lib.analysis.BlazeDirectories

/**
 * Provides the [DiffAwareness] implementation that uses the Java watch service.
 */
class BazelDiffAwarenessModule : BlazeModule() {
    override fun workspaceInit(
        runtime: BlazeRuntime, directories: BlazeDirectories?, builder: WorkspaceBuilder
    ) {
        // Order here is important - LocalDiffAwareness creation always succeeds, so it must be last.
        builder.addDiffAwarenessFactory(
            com.google.devtools.build.lib.skyframe.LocalDiffAwareness.Factory(
                com.google.common.collect.ImmutableList.of<String?>(),
                runtime.getBlazeService<FsEventsNativeDepsService?>(FsEventsNativeDepsService::class.java)
            )
        )
    }

    val commonCommandOptions: Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase>>
        get() = com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
            com.google.devtools.build.lib.skyframe.LocalDiffAwareness.Options::class.java
        )
}
