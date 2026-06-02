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
package com.google.devtools.build.lib.rules.core

import com.google.devtools.build.lib.actions.ActionConflictException

/**
 * Non-recursive aspect that promotes [OutputGroupInfo.VALIDATION] outputs to [ ][OutputGroupInfo.VALIDATION_TOP_LEVEL]. By requesting the latter but not the former output group,
 * validations avoid blocking test execution. (Using [OutputGroupInfo.DEFAULT] would
 * accomplish that as well but would be overrideable with `--output_groups` flag.)
 * 
 * 
 * Name is chosen to make for semi-sensible "ValidateTarget" aspect events.
 */
internal class ValidateTarget : NativeAspectClass(), ConfiguredAspectFactory {
    public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition {
        return AspectDefinition.builder(this)
            .applyToFiles(true) // to grab validation outputs from file targets
            .build()
    }

    @Throws(ActionConflictException::class, InterruptedException::class)
    public override fun create(
        targetLabel: Label?,
        ct: ConfiguredTarget?,
        context: RuleContext?,
        parameters: AspectParameters?,
        toolsRepository: RepositoryName?
    ): ConfiguredAspect {
        val outputGroupInfo: OutputGroupInfo? = OutputGroupInfo.get(ct)
        if (outputGroupInfo != null) {
            val validations: NestedSet<Artifact?> = outputGroupInfo.getOutputGroup(OutputGroupInfo.VALIDATION)
            if (!validations.isEmpty()) {
                return ConfiguredAspect.builder(context)
                    .addOutputGroup(OutputGroupInfo.VALIDATION_TOP_LEVEL, validations)
                    .build()
            }
        }
        return ConfiguredAspect.NonApplicableAspect.INSTANCE
    }
}
