// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime.commands.info

import com.google.common.base.Supplier
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue
import com.google.devtools.common.options.OptionsParsingResult
import net.starlark.java.eval.StarlarkSemantics

/**
 * Info item for the effective current set of Starlark semantics option values.
 * 
 * 
 * This is hidden because its output is verbose and may be multiline.
 */
class StarlarkSemanticsInfoItem(private val commandOptions: OptionsParsingResult) : InfoItem( /*name=*/
    "starlark-semantics",  /*description=*/
    "The effective set of Starlark semantics option values.",  /*hidden=*/
    true
) {
    public override fun get(
        configurationSupplier: Supplier<BuildConfigurationValue?>?, env: CommandEnvironment
    ): ByteArray {
        val buildLanguageOptions: BuildLanguageOptions? =
            commandOptions.getOptions<BuildLanguageOptions?>(BuildLanguageOptions::class.java)
        val skyframeExecutor: SkyframeExecutor = env.getBlazeWorkspace().getSkyframeExecutor()
        val effectiveStarlarkSemantics: StarlarkSemantics? =
            skyframeExecutor.getEffectiveStarlarkSemantics(buildLanguageOptions)
        return print(effectiveStarlarkSemantics)
    }
}
