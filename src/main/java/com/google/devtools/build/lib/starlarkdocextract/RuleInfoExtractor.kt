// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlarkdocextract

import com.google.devtools.build.lib.packages.Rule

/** API documentation extractor for a rule.  */
object RuleInfoExtractor {
    @kotlin.jvm.JvmField
    @com.google.common.annotations.VisibleForTesting
    val IMPLICIT_RULE_ATTRIBUTES: com.google.common.collect.ImmutableMap<String?, AttributeInfo?> =
        com.google.common.collect.ImmutableMap.of<K?, V?>(
            "name",
            AttributeInfo.newBuilder()
                .setName("name")
                .setType(AttributeType.NAME)
                .setMandatory(true)
                .setDocString("A unique name for this target.")
                .build()
        )

    /**
     * Extracts API documentation for a rule in the form of a [RuleInfo] proto.
     * 
     * @param qualifiedName a human-readable name for the rule to use in documentation output; for a
     * Starlark rule defined in a .bzl file, this would typically be the name under which users of
     * the module would use the rule
     * @param ruleClass a rule class; if it is a repository rule class, it must be an exported one
     */
    fun buildRuleInfo(
        context: ExtractorContext, qualifiedName: String?, ruleClass: RuleClass
    ): RuleInfo {
        val ruleInfoBuilder: RuleInfo.Builder = RuleInfo.newBuilder()
        // Record the name under which this symbol is made accessible, which may differ from the
        // symbol's exported name
        ruleInfoBuilder.setRuleName(StringEncoding.internalToUnicode(qualifiedName))
        // ... but record the origin rule key for cross references.
        val originKeyBuilder: OriginKey.Builder =
            OriginKey.newBuilder().setName(StringEncoding.internalToUnicode(ruleClass.getName()))
        if (ruleClass.isStarlark) {
            if (ruleClass.getStarlarkExtensionLabel() != null) {
                // Most common case: exported Starlark-defined rule class
                originKeyBuilder.setFile(
                    StringEncoding.internalToUnicode(
                        context.labelRenderer.render(ruleClass.getStarlarkExtensionLabel())
                    )
                )
            } else {
                // Unexported Starlark-defined rule class; this only possible for a repository rule. Fall
                // back to the rule definition environment label. (Note that we cannot unconditionally call
                // getRuleDefinitionEnvironmentLabel() because for an analysis test rule class, the rule
                // definition environment label is a dummy value; see b/366027483.)
                originKeyBuilder.setFile(
                    StringEncoding.internalToUnicode(
                        context.labelRenderer.render(ruleClass.getRuleDefinitionEnvironmentLabel())
                    )
                )
            }
        } else {
            // Non-Starlark-defined rule class
            originKeyBuilder.setFile("<native>")
        }
        ruleInfoBuilder.setOriginKey(originKeyBuilder.build())

        if (ruleClass.starlarkDocumentation != null) {
            ruleInfoBuilder.setDocString(StringEncoding.internalToUnicode(ruleClass.starlarkDocumentation))
        }

        if (ruleClass.getRuleClassType() === RuleClassType.TEST) {
            ruleInfoBuilder.setTest(true)
        }
        if (ruleClass.getAttributeProvider().hasAttr(Rule.IS_EXECUTABLE_ATTRIBUTE_NAME, Type.BOOLEAN)) {
            ruleInfoBuilder.setExecutable(true)
        }

        AttributeInfoExtractor.addDocumentableAttributes(
            context,
            IMPLICIT_RULE_ATTRIBUTES,
            ruleClass.getAttributeProvider().getAttributes(),
            ruleInfoBuilder::addAttribute
        )
        val advertisedProviders: com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?> =
            ruleClass.getAdvertisedProviders().getStarlarkProviders()
        if (!advertisedProviders.isEmpty()) {
            ruleInfoBuilder.setAdvertisedProviders(
                ProviderNameGroupExtractor.buildProviderNameGroup(context, advertisedProviders)
            )
        }
        return ruleInfoBuilder.build()
    }
}
