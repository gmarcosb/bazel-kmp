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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.query2.proto.proto2api.Build

/** Serialize a [Rule] as its protobuf representation.  */
object RuleFormatter {
    // Starlark doesn't support defining rule classes with ComputedDefault attributes. Therefore, the
    // only ComputedDefault attributes we expect to see for Starlark-defined rule classes are
    // those declared in those rule classes' natively defined base rule classes, which are:
    //
    // 1. The "timeout" attribute in StarlarkRuleClassFunctions.testBaseRule
    // 2. The "deprecation" attribute in BaseRuleClasses.commonCoreAndStarlarkAttributes
    // 3. The "testonly" attribute in BaseRuleClasses.commonCoreAndStarlarkAttributes
    private val STARLARK_RULE_CLASS_COMPUTED_DEFAULT_ATTRIBUTES: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.of<String?>("timeout", "deprecation", "testonly")

    fun serializeRule(rule: com.google.devtools.build.lib.packages.Rule): Build.Rule.Builder {
        val builder: Build.Rule.Builder = Build.Rule.newBuilder()
        builder.setName(rule.getLabel().name)
        builder.setRuleClass(rule.getRuleClass())

        val rawAttributeMapper: RawAttributeMapper = RawAttributeMapper.Companion.of(rule)
        val isStarlark: Boolean = rule.getRuleClassObject().isStarlark()

        if (isStarlark) {
            builder.setSkylarkEnvironmentHashCode( // hexify
                com.google.common.io.BaseEncoding.base16()
                    .lowerCase()
                    .encode(
                        com.google.common.base.Preconditions.checkNotNull<ByteArray?>(
                            rule.getRuleClassObject().getRuleDefinitionEnvironmentDigest(), rule
                        )
                    )
            )
        }
        for (attr in rule.getAttributes()) {
            val rawAttributeValue: Any? = rawAttributeMapper.getRawAttributeValue(rule, attr)
            val isExplicit: Boolean = rule.isAttributeValueExplicitlySpecified(attr)

            if (!isStarlark && !isExplicit) {
                // If the rule class is native (i.e. not Starlark-defined), then we can skip serialization
                // of implicit attribute values. The native rule class can provide the same default value
                // for the attribute after deserialization.
                continue
            }

            val valueToSerialize: Any?
            if (isExplicit) {
                valueToSerialize = rawAttributeValue
            } else if (rawAttributeValue is ComputedDefault) {
                // If the rule class is Starlark-defined (i.e. rule.getRuleClassObject().isStarlark() is
                // true), and the attribute has a ComputedDefault value, then we must serialize what it
                // evaluates to. The Starlark-defined ComputedDefault function won't be available after
                // deserialization due to Starlark's non-serializability.
                valueToSerialize = evaluateStarlarkComputedDefault(rawAttributeMapper, attr)
                if (valueToSerialize == null) {
                    continue
                }
            } else {
                // If the rule class is Starlark-defined and the attribute value is implicit, then we
                // must serialize it. The Starlark-defined rule class won't be available after
                // deserialization due to Starlark's non-serializability.
                valueToSerialize = rawAttributeValue
            }

            builder.addAttribute(
                AttributeFormatter.getAttributeProto(
                    attr,
                    valueToSerialize,
                    isExplicit,  /*encodeBooleanAndTriStateAsIntegerAndString=*/
                    false
                )
            )
        }
        return builder
    }

    /**
     * Evaluates a [ComputedDefault] attribute value for a [Rule] with a Starlark-defined
     * [RuleClass].
     * 
     * 
     * We can't serialize ComputedDefault attributes defined in Starlark, because those can depend
     * on other attributes which are configurable.
     * 
     * 
     * For a few attributes ([.STARLARK_RULE_CLASS_COMPUTED_DEFAULT_ATTRIBUTES]), we know for
     * certain that they don't have dependencies on other attributes which are configurable, so they
     * can be evaluated here without loss of fidelity.
     * 
     * 
     * The [RawAttributeMapper.get] method, inherited from [AbstractAttributeMapper],
     * evaluates the [ComputedDefault] function, so we use that, after verifying the attribute's
     * name is expected.
     * 
     * @return the attribute's default value if we know it can be safely serialized, or null
     * otherwise.
     */
    private fun evaluateStarlarkComputedDefault(
        rawAttributeMapper: RawAttributeMapper, attr: com.google.devtools.build.lib.packages.Attribute
    ): Any? {
        if (STARLARK_RULE_CLASS_COMPUTED_DEFAULT_ATTRIBUTES.contains(attr.getName())) {
            return rawAttributeMapper.get(attr.getName(), attr.getType())
        }
        return null
    }
}
