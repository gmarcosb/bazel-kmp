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
package com.google.devtools.build.lib.rules.platform

import com.google.devtools.build.lib.actions.ActionConflictException

/** Defines a potential value of a constraint.  */
class ConstraintValue : RuleConfiguredTargetFactory {
    @Throws(InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
        val constraint: ConstraintSettingInfo? =
            PlatformProviderUtils.constraintSetting(
                ruleContext.getPrerequisite(ConstraintValueRule.Companion.CONSTRAINT_SETTING_ATTR)
            )

        return RuleConfiguredTargetBuilder(ruleContext)
            .addProvider(RunfilesProvider::class.java, RunfilesProvider.EMPTY)
            .addProvider(FileProvider::class.java, FileProvider.EMPTY)
            .addProvider(FilesToRunProvider::class.java, FilesToRunProvider.EMPTY)
            .addNativeDeclaredProvider(ConstraintValueInfo.create(constraint, ruleContext.getLabel()))
            .build()
    }
}
