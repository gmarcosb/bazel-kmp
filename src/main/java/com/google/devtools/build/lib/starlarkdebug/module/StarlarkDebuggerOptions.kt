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
package com.google.devtools.build.lib.starlarkdebug.module

/** Configuration options for Starlark debugging.  */ // TODO(laurentlb): Rename the flags (remove 'experimental' and replace 'skylark' with 'starlark')
// when the interpreter code is more stable.
@com.google.devtools.common.options.OptionsClass
abstract class StarlarkDebuggerOptions : com.google.devtools.common.options.OptionsBase() {
    @get:com.google.devtools.common.options.Option(
        name = "experimental_skylark_debug",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = ("If true, Blaze will open the Starlark debug server at the start of the build "
                + "invocation, and wait for a debugger to attach before running the build.")
    )
    abstract val debugStarlark: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_skylark_debug_server_port",
        defaultValue = "7300",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = "The port on which the Starlark debug server will listen for connections."
    )
    abstract val debugServerPort: Int

    @get:com.google.devtools.common.options.Option(
        name = "experimental_skylark_debug_verbose_logging",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = "Show verbose logs for the debugger."
    )
    abstract val verboseLogs: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_skylark_debug_reset_analysis",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = ("If true, resets analysis before executing the build. Has no effect without"
                + " --experimental_skylark_debug")
    )
    abstract val resetAnalysis: Boolean
}
