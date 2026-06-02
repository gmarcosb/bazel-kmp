// Copyright 2010 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.packages.BuildType

/** Tests for link stamping.  */
@RunWith(JUnit4::class)
class StampTest : BuildViewTestCase() {
    /** Tests that link stamping is disabled for all tests that support it.  */
    @org.junit.Test
    fun testNoStampingForTests() {
        for (e in analysisMock.createRuleClassProvider().getRuleClassMap().entrySet()) {
            val name: String? = e.getKey()
            val ruleClass: RuleClass = e.getValue()
            if (TargetUtils.isTestRuleName(name)
                && ruleClass.getAttributeProvider().hasAttr("stamp", BuildType.TRISTATE)
            ) {
                Truth.assertWithMessage(name)
                    .that(
                        ruleClass.getAttributeProvider().getAttributeByName("stamp").getDefaultValue(null)
                    )
                    .isEqualTo(TriState.NO)
            }
        }
    }
}
