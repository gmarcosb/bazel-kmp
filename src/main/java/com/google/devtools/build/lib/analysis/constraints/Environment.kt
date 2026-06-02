// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.constraints

import com.google.devtools.build.lib.actions.ActionConflictException

/**
 * Implementation for the environment rule.
 */
class Environment : RuleConfiguredTargetFactory {
    @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
        // The main analysis work to do here is to simply fill in SupportedEnvironmentsProvider to
        // pass the environment itself to depending rules.

        val label: com.google.devtools.build.lib.cmdline.Label? = ruleContext.getLabel()

        val group: EnvironmentGroup?
        try {
            group = ConstraintSemantics.Companion.getEnvironmentGroup(ruleContext.getRule())
        } catch (e: EnvironmentLookupException) {
            ruleContext.ruleError(e.message)
            return null
        }

        val env: EnvironmentCollection =
            com.google.devtools.build.lib.analysis.constraints.EnvironmentCollection.Builder()
                .put(group.getEnvironmentLabels(), label).build()
        return RuleConfiguredTargetBuilder(ruleContext)
            .addProvider(
                SupportedEnvironmentsProvider::class.java,
                SupportedEnvironments.Companion.create(
                    env,
                    env,
                    com.google.common.collect.ImmutableMap.of<com.google.devtools.build.lib.cmdline.Label?, RemovedEnvironmentCulprit?>()
                )
            )
            .addProvider(RunfilesProvider::class.java, RunfilesProvider.EMPTY)
            .addProvider(FileProvider::class.java, FileProvider.Companion.EMPTY)
            .addProvider(FilesToRunProvider::class.java, FilesToRunProvider.EMPTY)
            .build()
    }
}
