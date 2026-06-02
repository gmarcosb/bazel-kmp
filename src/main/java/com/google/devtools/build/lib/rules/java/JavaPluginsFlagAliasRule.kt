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
package com.google.devtools.build.lib.rules.java

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.packages.Attribute.attr

/**
 * Rule definition for 'java_plugins_flag_alias' rule. It is only to be used by the Starlark
 * implementation of 'java_library' while other native rules that also rely on the option have not
 * been migrated.
 */
class JavaPluginsFlagAliasRule : RuleDefinition {
    public override fun build(builder: RuleClass.Builder, environment: RuleDefinitionEnvironment?): RuleClass {
        return builder
            .add(
                attr(":java_plugins", LABEL_LIST)
                    .cfg(ExecutionTransitionFactory.createFactory())
                    .mandatoryProvidersList(
                        ImmutableList.of<E?>(
                            ImmutableList.of<E?>(JavaPluginInfo.Companion.PROVIDER.id()),
                            ImmutableList.of<E?>(JavaPluginInfo.Companion.RULES_JAVA_PROVIDER.id())
                        )
                    )
                    .silentRuleClassFilter()
                    .value(JavaSemantics.Companion.JAVA_PLUGINS)
            )
            .build()
    }

    val metadata: Metadata
        get() = Metadata.builder()
            .name("java_plugins_flag_alias")
            .ancestors(BaseRuleClasses.NativeBuildRule::class.java)
            .factoryClass(JavaPluginsFlagAlias::class.java)
            .build()

    /**
     * Implementation of the 'java_plugins_flag_alias' rule. Provides plugins specified by the
     * --plugin flag.
     */
    class JavaPluginsFlagAlias : RuleConfiguredTargetFactory {
        @Throws(InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
        public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
            if (!ALLOWLISTED_LABELS.contains(ruleContext.getLabel())) {
                ruleContext.ruleError("Rule " + ruleContext.getLabel() + " cannot use private rule")
                return null
            }

            var plugins: ImmutableList<JavaPluginInfo?> =
                ruleContext
                    .getRulePrerequisitesCollection()
                    .getPrerequisites(":java_plugins", JavaPluginInfo.Companion.PROVIDER)
            if (plugins.isEmpty()) {
                plugins =
                    ruleContext
                        .getRulePrerequisitesCollection()
                        .getPrerequisites(":java_plugins", JavaPluginInfo.Companion.RULES_JAVA_PROVIDER)
            }
            val javaPluginInfo: JavaPluginInfo? =
                mergeWithoutJavaOutputs(plugins, JavaPluginInfo.Companion.PROVIDER)
            val rulesJavaProviderInfo: JavaPluginInfo? =
                mergeWithoutJavaOutputs(plugins, JavaPluginInfo.Companion.RULES_JAVA_PROVIDER)

            return RuleConfiguredTargetBuilder(ruleContext)
                .addStarlarkDeclaredProvider(javaPluginInfo)
                .addStarlarkDeclaredProvider(rulesJavaProviderInfo)
                .addProvider(RunfilesProvider::class.java, RunfilesProvider.EMPTY)
                .build()
        }
    }

    companion object {
        private val ALLOWLISTED_LABELS: ImmutableSet<Label?> = ImmutableSet.of<E?>(
            Label.parseCanonicalUnchecked("//tools/jdk:java_plugins_flag_alias"),
            Label.parseCanonicalUnchecked("@bazel_tools//tools/jdk:java_plugins_flag_alias")
        )
    }
}
