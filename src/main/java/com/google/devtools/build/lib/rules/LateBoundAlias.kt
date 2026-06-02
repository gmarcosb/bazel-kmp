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
package com.google.devtools.build.lib.rules

import com.google.devtools.build.lib.packages.Attribute.attr

/** Implements template for creating custom alias rules.  */
class LateBoundAlias : RuleConfiguredTargetFactory {
    @Throws(ActionConflictException::class, java.lang.InterruptedException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
        val actual: ConfiguredTarget? = ruleContext.getPrerequisite(ATTRIBUTE_NAME) as ConfiguredTarget?
        if (actual == null) {
            return createEmptyConfiguredTarget(ruleContext)
        }

        val overrides: com.google.common.collect.ImmutableClassToInstanceMap.Builder<TransitiveInfoProvider?> =
            com.google.common.collect.ImmutableClassToInstanceMap.builder<TransitiveInfoProvider?>()
        overrides.put<T?>(
            AliasProvider.LateBoundAliasProvider::class.java, AliasProvider.LATE_BOUND_ALIAS_PROVIDER
        )

        if (!ruleContext.getRule().isBuildSetting()) {
            return AliasConfiguredTarget.Companion.createWithOverrides(
                ruleContext, actual, ruleContext.getVisibility(), overrides.build()
            )
        }

        // This makes label_setting and label_flag work with select().
        val buildSetting: BuildSetting = ruleContext.getRule().getRuleClassObject().getBuildSetting()
        val defaultValue: Any? =
            ruleContext
                .attributes()
                .get(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, buildSetting.getType())
        return AliasConfiguredTarget.Companion.createWithOverrides(
            ruleContext,
            actual,
            ruleContext.getVisibility(),
            overrides
                .put<T?>(
                    BuildSettingProvider::class.java,
                    BuildSettingProvider(buildSetting, defaultValue, ruleContext.getLabel())
                )
                .build()
        )
    }

    /** Rule definition for custom alias rules.  */
    abstract class CommonAliasRule<FragmentT : Fragment?>
    protected constructor(
        ruleName: String?,
        labelResolver: java.util.function.Function<RuleDefinitionEnvironment?, LabelLateBoundDefault<FragmentT?>?>?,
        fragmentClass: java.lang.Class<FragmentT?>?
    ) : AbstractAliasRule(ruleName) {
        private val labelResolver: java.util.function.Function<RuleDefinitionEnvironment?, LabelLateBoundDefault<FragmentT?>?>

        private val fragmentClass: java.lang.Class<FragmentT?>

        init {
            this.labelResolver =
                com.google.common.base.Preconditions.checkNotNull<java.util.function.Function<RuleDefinitionEnvironment?, LabelLateBoundDefault<FragmentT?>?>>(
                    labelResolver
                )
            this.fragmentClass =
                com.google.common.base.Preconditions.checkNotNull<java.lang.Class<FragmentT?>>(fragmentClass)
        }

        protected fun makeAttribute(environment: RuleDefinitionEnvironment?): Attribute.Builder<Label?> {
            return attr(ATTRIBUTE_NAME, LABEL).value(labelResolver.apply(environment))
        }

        public override fun build(builder: RuleClass.Builder, environment: RuleDefinitionEnvironment?): RuleClass {
            val attribute: Attribute = makeAttribute(environment).build()
            com.google.common.base.Preconditions.checkArgument(attribute.name.equals(ATTRIBUTE_NAME))
            com.google.common.base.Preconditions.checkArgument(attribute.getType().equals(LABEL))

            return builder
                .requiresConfigurationFragments(fragmentClass)
                .removeAttribute("licenses")
                .removeAttribute("distribs")
                .advertiseProvider(AliasProvider.LateBoundAliasProvider::class.java)
                .addAttribute(attribute)
                .build()
        }

        companion object {
            private const val ATTRIBUTE_NAME = ":alias"
        }
    }

    internal abstract class AbstractAliasRule(ruleName: String?) : RuleDefinition {
        private val ruleName: String

        init {
            this.ruleName = com.google.common.base.Preconditions.checkNotNull<String>(ruleName)
        }

        val metadata: Metadata
            get() = Metadata.builder()
                .name(ruleName)
                .ancestors(BaseRuleClasses.NativeBuildRule::class.java)
                .factoryClass(LateBoundAlias::class.java)
                .build()
    }

    companion object {
        private const val ATTRIBUTE_NAME = ":alias"

        @Throws(ActionConflictException::class, java.lang.InterruptedException::class)
        private fun createEmptyConfiguredTarget(ruleContext: RuleContext?): ConfiguredTarget {
            return RuleConfiguredTargetBuilder(ruleContext)
                .addProvider(RunfilesProvider::class.java, RunfilesProvider.simple(Runfiles.EMPTY))
                .addProvider(
                    AliasProvider.LateBoundAliasProvider::class.java, AliasProvider.LATE_BOUND_ALIAS_PROVIDER
                )
                .build()
        }
    }
}
