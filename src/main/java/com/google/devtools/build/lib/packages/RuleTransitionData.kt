// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider

/**
 * Helper class which contains data used by a [TransitionFactory] to create a transition for
 * rules.
 */
@kotlin.jvm.JvmRecord
data class RuleTransitionData(
    rule: com.google.devtools.build.lib.packages.Rule?,
    configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?,
    configHash: String?
) : TransitionFactory.Data {
    val rule: com.google.devtools.build.lib.packages.Rule?
    val configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?
    val configHash: String?

    init {
        this.configHash = configHash
        this.configConditions = configConditions
        this.rule = rule
        java.util.Objects.requireNonNull<com.google.devtools.build.lib.packages.Rule?>(rule, "rule")
        java.util.Objects.requireNonNull<String?>(configHash, "configHash")
    }

    companion object {
        fun create(
            rule: com.google.devtools.build.lib.packages.Rule?,
            configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?,
            configHash: String?
        ): RuleTransitionData {
            return RuleTransitionData(rule, configConditions, configHash)
        }
    }
}
