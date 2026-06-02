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

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.actions.ActionConflictException

/**
 * Defines a category of constraint that can be fulfilled by a constraint_value rule in a platform
 * definition.
 */
class ConstraintSetting : RuleConfiguredTargetFactory {
    @Throws(InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget {
        val constraintSetting: Label = ruleContext.getLabel()
        val defaultConstraintValue: Label? =
            ruleContext
                .attributes()
                .get(ConstraintSettingRule.Companion.DEFAULT_CONSTRAINT_VALUE_ATTR, BuildType.NODEP_LABEL)

        validateDefaultConstraintValue(ruleContext, constraintSetting, defaultConstraintValue)

        return RuleConfiguredTargetBuilder(ruleContext)
            .addProvider(RunfilesProvider::class.java, RunfilesProvider.EMPTY)
            .addProvider(FileProvider::class.java, FileProvider.EMPTY)
            .addProvider(FilesToRunProvider::class.java, FilesToRunProvider.EMPTY)
            .addNativeDeclaredProvider(
                ConstraintSettingInfo.create(constraintSetting, defaultConstraintValue)
            )
            .build()
    }

    @Throws(RuleErrorException::class, InterruptedException::class)
    private fun validateDefaultConstraintValue(
        ruleContext: RuleContext, constraintSetting: Label, defaultConstraintValue: Label?
    ) {
        if (defaultConstraintValue == null) {
            return
        }

        // Make sure the default value is in the same package.
        if (!constraintSetting
                .getPackageIdentifier()
                .equals(defaultConstraintValue.getPackageIdentifier())
        ) {
            throw ruleContext.throwWithAttributeError(
                ConstraintSettingRule.Companion.DEFAULT_CONSTRAINT_VALUE_ATTR,
                "The default constraint value must be defined in the same package "
                        + "as the constraint setting itself."
            )
        }

        // Verify that the target actually exists, even though we cannot have a direct dependency
        // because it will cause a cycle.
        val env: SkyFunction.Environment = ruleContext.getAnalysisEnvironment().getSkyframeEnv()
        val packageNode: PackageValue? =
            env.getValue(constraintSetting.getPackageIdentifier()) as PackageValue?
        Preconditions.checkNotNull(
            packageNode,
            "Package '%s' is the package for the current target, and so must have already been loaded.",
            defaultConstraintValue.getPackageIdentifier()
        )
        val pkg: Package = packageNode.getPackage()
        try {
            pkg.getTarget(defaultConstraintValue.name)
        } catch (e: NoSuchTargetException) {
            throw ruleContext.throwWithAttributeError(
                ConstraintSettingRule.Companion.DEFAULT_CONSTRAINT_VALUE_ATTR,
                "The default constraint value '" + defaultConstraintValue + "' does not exist"
            )
        }
    }
}
