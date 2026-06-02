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
package com.google.devtools.build.lib.skyframe.actiongraph.v2

import com.google.devtools.build.lib.util.Pair

/**
 * Cache for RuleConfiguredTargets in the action graph.
 * 
 * 
 * The cache maps a target identifier, which is a `Pair<String, String>` of
 * (label, rule class string).
 */
class KnownTargets internal constructor(
    aqueryOutputHandler: AqueryOutputHandler?,
    private val knownRuleClassStrings: KnownRuleClassStrings
) : BaseCache<Pair<String?, String?>?, Target?>(aqueryOutputHandler) {
    @Throws(IOException::class, InterruptedException::class)
    override fun createProto(targetIdentifier: Pair<String?, String?>, id: Int): Target {
        val labelString = targetIdentifier.first
        val ruleClassString = targetIdentifier.second
        val targetBuilder: Target.Builder = Target.newBuilder().setId(id).setLabel(labelString)
        if (ruleClassString != null) {
            targetBuilder.setRuleClassId(
                knownRuleClassStrings.dataToIdAndStreamOutputProto(ruleClassString)
            )
        }
        return targetBuilder.build()
    }

    @Throws(IOException::class)
    override fun toOutput(targetProto: Target?) {
        aqueryOutputHandler.outputTarget(targetProto)
    }
}
