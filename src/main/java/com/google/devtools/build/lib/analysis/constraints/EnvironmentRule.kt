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

import com.google.devtools.build.lib.analysis.BaseRuleClasses

/**
 * Rule definition for environment rules (for Bazel's constraint enforcement system).
 */
class EnvironmentRule : RuleDefinition {
    public override fun build(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?): RuleClass {
        return builder
            .cfg(NoConfigTransition.Companion.getFactory<RuleTransitionData?>())
            .toolchainResolutionMode(ToolchainResolutionMode.DISABLED)
            .override<MutableList<String?>?>(
                com.google.devtools.build.lib.packages.Attribute.attr<MutableList<String?>?>(
                    "tags",
                    com.google.devtools.build.lib.packages.Types.STRING_LIST
                ) // No need to show up in ":all", etc. target patterns.
                    .value(com.google.common.collect.ImmutableList.of<String?>("manual"))
                    .nonconfigurable("low-level attribute, used in TargetUtils without configurations")
            ) /* <!-- #BLAZE_RULE(environment).ATTRIBUTE(fulfills) -->
        The set of environments this one is considered a valid "standin" for.
        <p>
          If rule A depends on rule B, A declares compatibility with environment <code>:foo</code>,
          and B declares compatibility with environment <code>:bar</code>, this is normally not
          considered a valid dependency. But if <code>:bar</code> fulfills <code>:foo</code>, the
          dependency is considered valid. B's own dependencies are subsequently expected to support
          <code>:bar</code> (the original expectation for <code>:foo</code> is dropped).
        </p>
        <p>
          Environments may only fulfill other environments in the same environment group.
        </p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
            .add<MutableList<com.google.devtools.build.lib.cmdline.Label?>?>(
                com.google.devtools.build.lib.packages.Attribute.attr<MutableList<com.google.devtools.build.lib.cmdline.Label?>?>(
                    FULFILLS_ATTRIBUTE, BuildType.LABEL_LIST
                )
                    .allowedRuleClasses(ConstraintConstants.ENVIRONMENT_RULE)
                    .allowedFileTypes(FileTypeSet.NO_FILE)
                    .nonconfigurable(
                        "used for defining constraint models - this shouldn't be configured"
                    )
            )
            .exemptFromConstraintChecking("this rule *defines* a constraint")
            .setUndocumented()
            .build()
    }

    public override fun getMetadata(): Metadata {
        return RuleDefinition.Metadata.builder()
            .name(ConstraintConstants.ENVIRONMENT_RULE) // Not allowed in symbolic macros: lazy expansion of symbolic macros could hide environment
            // targets from environment groups.
            .type(RuleClassType.BUILD_ONLY)
            .ancestors(BaseRuleClasses.NativeBuildRule::class.java)
            .factoryClass(com.google.devtools.build.lib.analysis.constraints.Environment::class.java)
            .build()
    }

    companion object {
        const val FULFILLS_ATTRIBUTE: String = "fulfills"
    }
}
