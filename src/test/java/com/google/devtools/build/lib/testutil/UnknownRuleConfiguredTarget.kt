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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.actions.ActionConflictException

/**
 * A null implementation of ConfiguredTarget for rules we don't know how to build.
 */
class UnknownRuleConfiguredTarget : RuleConfiguredTargetFactory {
    @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
    public override fun create(context: RuleContext): ConfiguredTarget? {
        // TODO(bazel-team): (2009) why isn't this an error?  It would stop the build more promptly...
        context.ruleWarning("cannot build " + context.getRule().getRuleClass() + " rules")

        val outputArtifacts: com.google.common.collect.ImmutableList<Artifact?> = context.getOutputArtifacts()
        val filesToBuild: NestedSet<Artifact?>
        if (outputArtifacts.isEmpty()) {
            // Gotta build *something*...
            filesToBuild = NestedSetBuilder.create(
                Order.STABLE_ORDER,
                context.createOutputArtifact()
            )
        } else {
            filesToBuild = NestedSetBuilder.wrap(Order.STABLE_ORDER, outputArtifacts)
        }

        val rule: Rule = context.getRule()
        context.registerAction(
            FailAction(
                context.getActionOwner(),
                filesToBuild.toList(),
                "cannot build " + rule.getRuleClass() + " rules such as " + rule.getLabel(),
                Code.FAIL_ACTION_UNKNOWN
            )
        )
        return RuleConfiguredTargetBuilder(context)
            .setFilesToBuild(filesToBuild)
            .add(RunfilesProvider::class.java, RunfilesProvider.simple(Runfiles.EMPTY))
            .build()
    }
}
