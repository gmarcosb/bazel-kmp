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

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.actions.ActionConflictException

/** Defines a platform for execution contexts.  */
class Platform : RuleConfiguredTargetFactory {
    @Throws(InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget {
        val platformBuilder: PlatformInfo.Builder = PlatformInfo.builder().setLabel(ruleContext.getLabel())

        val parentPlatforms: ImmutableList<PlatformInfo?> =
            PlatformProviderUtils.platforms(
                ruleContext.getPrerequisites(PlatformRule.Companion.PARENTS_PLATFORM_ATTR)
            )

        if (parentPlatforms.size() > 1) {
            throw ruleContext.throwWithAttributeError(
                PlatformRule.Companion.PARENTS_PLATFORM_ATTR,
                PlatformRule.Companion.PARENTS_PLATFORM_ATTR + " attribute must have a single value"
            )
        }
        val parentPlatform: PlatformInfo? = Iterables.getFirst<PlatformInfo?>(parentPlatforms, null)
        platformBuilder.setParent(parentPlatform)

        // Add the declared constraints. Because setting the host_platform or target_platform attribute
        // to true on a platform automatically includes the detected CPU and OS constraints, if the
        // constraint_values attribute tries to add those, this will throw an error.
        platformBuilder.addConstraints(
            PlatformProviderUtils.constraintValues(
                ruleContext.getPrerequisites(PlatformRule.Companion.CONSTRAINT_VALUES_ATTR)
            )
        )

        val execProperties: MutableMap<String?, String?>? =
            ruleContext.attributes().get(PlatformRule.Companion.EXEC_PROPS_ATTR, Types.STRING_DICT)
        if (execProperties != null && !execProperties.isEmpty()) {
            platformBuilder.setExecProperties(ImmutableMap.< K, V > copyOf<K?, V?>(execProperties))
        }

        val flags: MutableList<String?>? =
            ruleContext.attributes().get(PlatformRule.Companion.FLAGS_ATTR, Types.STRING_LIST)
        if (flags != null && !flags.isEmpty()) {
            platformBuilder.addFlags(flags)
        }
        val requiredSettings: ImmutableList<ConfigMatchingProvider?>? =
            ruleContext.getPrerequisites(PlatformRule.Companion.REQUIRED_SETTINGS_ATTR).stream()
                .map({ target -> target.getProvider(ConfigMatchingProvider::class.java) })
                .collect(ImmutableList.toImmutableList<E?>())
        platformBuilder.addRequiredSettings(requiredSettings)

        if (ruleContext.attributes().get("check_toolchain_types", Type.BOOLEAN)) {
            val allowedToolchainTypes: MutableList<Label?>? =
                ruleContext.attributes().get("allowed_toolchain_types", BuildType.NODEP_LABEL_LIST)
            platformBuilder.checkToolchainTypes(true)
            platformBuilder.addAllowedToolchainTypes(allowedToolchainTypes)
        } else {
            platformBuilder.checkToolchainTypes(false)
        }

        val missingToolchainErrorMessage: String? =
            ruleContext.attributes().get(PlatformRule.Companion.MISSING_TOOLCHAIN_ERROR_ATTR, Type.STRING)
        platformBuilder.setMissingToolchainErrorMessage(missingToolchainErrorMessage)

        val platformInfo: PlatformInfo?
        try {
            platformInfo = platformBuilder.build()
        } catch (e: ConstraintCollection.DuplicateConstraintException) {
            throw ruleContext.throwWithAttributeError(
                PlatformRule.Companion.CONSTRAINT_VALUES_ATTR, e.getMessage()
            )
        } catch (e: PlatformInfo.ExecPropertiesException) {
            throw ruleContext.throwWithAttributeError(PlatformRule.Companion.EXEC_PROPS_ATTR, e.getMessage())
        }

        return RuleConfiguredTargetBuilder(ruleContext)
            .addProvider(RunfilesProvider::class.java, RunfilesProvider.EMPTY)
            .addProvider(FileProvider::class.java, FileProvider.EMPTY)
            .addProvider(FilesToRunProvider::class.java, FilesToRunProvider.EMPTY)
            .addNativeDeclaredProvider(platformInfo)
            .build()
    }
}
