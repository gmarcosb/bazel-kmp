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
package com.google.devtools.build.lib.analysis.allowlisting

import com.google.devtools.build.lib.actions.ActionConflictException

/** Definition of a test rule that uses allowlists.  */
object AllowlistDummyRule {
    val DEFINITION: MockRule = MockRule {
        MockRule.factory(RuleFactory::class.java)
            .define(
                "rule_with_allowlist",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder.add(
                        Allowlist.getAttributeFromAllowlistName("dummy")
                            .value(Label.parseCanonicalUnchecked("//allowlist:allowlist"))
                    )
                })
    }

    /** Has to be public to make factory initialization logic happy.  */
    class RuleFactory : RuleConfiguredTargetFactory {
        @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
        public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
            if (!Allowlist.isAvailable(ruleContext, "dummy")) {
                ruleContext.ruleError("Dummy is not available.")
            }
            return RuleConfiguredTargetBuilder(ruleContext)
                .setFilesToBuild(NestedSetBuilder.emptySet(Order.STABLE_ORDER))
                .addProvider(RunfilesProvider.EMPTY)
                .build()
        }
    }
}
