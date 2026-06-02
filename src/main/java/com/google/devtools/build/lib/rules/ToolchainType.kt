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
package com.google.devtools.build.lib.rules

import com.google.devtools.build.lib.packages.Attribute.attr

/**
 * Implementation of `toolchain_type`.
 */
class ToolchainType : RuleConfiguredTargetFactory {
    @Throws(ActionConflictException::class, java.lang.InterruptedException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
        val noMatchError: String = ruleContext.attributes().get("no_match_error", Type.STRING)
        val toolchainTypeInfo: ToolchainTypeInfo? =
            ToolchainTypeInfo.create(
                ruleContext.getLabel(), if (noMatchError.isEmpty()) null else noMatchError
            )

        return RuleConfiguredTargetBuilder(ruleContext)
            .addProvider(RunfilesProvider.simple(Runfiles.EMPTY))
            .addNativeDeclaredProvider(toolchainTypeInfo)
            .build()
    }

    /** Definition for `toolchain_type`.  */
    class ToolchainTypeRule : RuleDefinition {
        public override fun build(builder: RuleClass.Builder, environment: RuleDefinitionEnvironment?): RuleClass {
            return builder
                .toolchainResolutionMode(ToolchainResolutionMode.DISABLED)
                .advertiseStarlarkProvider(ToolchainTypeInfo.PROVIDER.id())
                .removeAttribute("licenses")
                .removeAttribute("distribs")
                .removeAttribute(":action_listener") /*<!-- #BLAZE_RULE(toolchain_type).ATTRIBUTE(no_match_error) -->
          A custom error message to display when no matching toolchain is found for this type.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
                .add(
                    attr("no_match_error", Type.STRING)
                        .nonconfigurable("low-level attribute, used in platform configuration")
                )
                .build()
        }

        val metadata: Metadata
            get() = Metadata.builder()
                .name("toolchain_type")
                .factoryClass(com.google.devtools.build.lib.rules.ToolchainType::class.java)
                .ancestors(BaseRuleClasses.NativeBuildRule::class.java)
                .build()
    }
} /*<!-- #BLAZE_RULE (NAME = toolchain_type, FAMILY = Platforms and Toolchains)[GENERIC_RULE] -->

<p>
  This rule defines a new type of toolchain -- a simple target that represents a class of tools that
  serve the same role for different platforms.
</p>

<p>
  See the <a href="${link toolchains}">Toolchains</a> page for more details.
</p>

<h4 id="toolchain_type_examples">Example</h4>
<p>
  This defines a toolchain type for a custom rule.
</p>
<pre class="code">
toolchain_type(
    name = "bar_toolchain_type",
)
</pre>

<p>
  This can be used in a bzl file.
</p>
<pre class="code">
bar_binary = rule(
    implementation = _bar_binary_impl,
    attrs = {
        "srcs": attr.label_list(allow_files = True),
        ...
        # No `_compiler` attribute anymore.
    },
    toolchains = ["//bar_tools:toolchain_type"]
)
</pre>
<!-- #END_BLAZE_RULE -->*/
