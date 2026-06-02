// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction.name

/**
 * An option class for `--keep_state_after_build`.
 * 
 * 
 * This needs to be separate from [CommonCommandOptions] because it's accessed from `
 * SkyframeExecutor` and referencing [CommonCommandOptions] would cause a dependency
 * cycle.
 */
@com.google.devtools.common.options.OptionsClass
abstract class KeepStateAfterBuildOption : com.google.devtools.common.options.OptionsBase() {
    @get:com.google.devtools.common.options.Option(
        name = "keep_state_after_build",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("If false, Blaze will discard the inmemory state from this build when the build "
                + "finishes. Subsequent builds will not have any incrementality with respect to this "
                + "one.")
    )
    abstract var keepStateAfterBuild: Boolean
}
