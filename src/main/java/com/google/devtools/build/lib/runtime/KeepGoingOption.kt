// Copyright 2017 The Bazel Authors. All rights reserved.
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

/** Defines the --keep_going option which is used by multiple commands.  */
@com.google.devtools.common.options.OptionsClass
abstract class KeepGoingOption : com.google.devtools.common.options.OptionsBase() {
    @get:com.google.devtools.common.options.Option(
        name = "keep_going",
        abbrev = 'k',
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EAGERNESS_TO_EXIT],
        help = ("Continue as much as possible after an error.  While the target that failed and those"
                + " that depend on it cannot be analyzed, other prerequisites of these targets can"
                + " be.")
    )
    abstract val keepGoing: Boolean
}
