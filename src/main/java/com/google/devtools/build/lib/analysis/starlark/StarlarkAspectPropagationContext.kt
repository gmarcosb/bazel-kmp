// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.analysis.DependencyKind

/** Implementation of [StarlarkAspectPropagationContextApi].  */
class StarlarkAspectPropagationContext(aspectPublicParams: StructImpl?, rule: StarlarkAspectPropagationRule?) :
    StarlarkAspectPropagationContextApi {
    public override fun getRule(): StarlarkAspectPropagationRuleApi? {
        return rule
    }

    public override fun getAttr(): StructApi? {
        return aspectPublicParams
    }

    private class StarlarkAspectPropagationRule(
        label: Label?,
        qualifiedRuleKind: QualifiedRuleKindApi?,
        attr: StructApi?
    ) : StarlarkAspectPropagationRuleApi {
        public override fun getLabel(): Label? {
            return label
        }

        public override fun getQualifiedKind(): QualifiedRuleKindApi? {
            return qualifiedRuleKind
        }

        public override fun getAttr(): StructApi? {
            return attr
        }

        val label: Label?
        val qualifiedRuleKind: QualifiedRuleKindApi?
        val attr: StructApi?

        init {
            this.label = label
            this.qualifiedRuleKind = qualifiedRuleKind
            this.attr = attr
        }
    }

    private class QualifiedRuleKind(fileLabel: Label?, ruleName: String?) : QualifiedRuleKindApi {
        public override fun getFileLabel(): Label? {
            return fileLabel
        }

        public override fun getRuleName(): String? {
            return ruleName
        }

        val fileLabel: Label?
        val ruleName: String?

        init {
            this.fileLabel = fileLabel
            this.ruleName = ruleName
        }
    }

    @kotlin.jvm.JvmRecord
    private data class RuleAttribute(val value: Any?, val isTool: Boolean) : RuleAttributeApi {
        public override fun getValue(): Any? {
            return value
        }

        public override fun isTool(): Boolean {
            return isTool
        }
    }

    val aspectPublicParams: StructImpl?
    val rule: StarlarkAspectPropagationRule?

    init {
        this.aspectPublicParams = aspectPublicParams
        this.rule = rule
    }

    companion object {
        /** Creates a [StarlarkAspectPropagationContext] for the propagation predicate.  */
        fun createForPropagationPredicate(
            aspect: Aspect,
            label: Label?,
            ruleDefinitionEnvironmentLabel: Label?,
            ruleClassName: String?,
            tags: com.google.common.collect.ImmutableList<String?>?
        ): StarlarkAspectPropagationContext {
            val ruleAttributes: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                StructProvider.STRUCT.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "tags", RuleAttribute(StarlarkList.immutableCopyOf<String?>(tags),  /* isTool= */false)
                    ),
                    "Only rule's \"tags\" are available in propagation_predicate function."
                )

            return StarlarkAspectPropagationContext(
                createAspectPublicParams(aspect),
                StarlarkAspectPropagationRule(
                    label,
                    createQualifiedRuleKind(ruleDefinitionEnvironmentLabel, ruleClassName),
                    ruleAttributes
                )
            )
        }

        /**
         * Creates a [StarlarkAspectPropagationContext] for `attr_aspects` and `toolchains_aspects`.
         */
        fun createForPropagationEdges(
            aspect: Aspect,
            rule: Rule,
            attributeMap: ConfiguredAttributeMapper,
            dependencyLabels: OrderedSetMultimap<DependencyKind?, Label?>
        ): StarlarkAspectPropagationContext {
            val ruleAttributesBuilder: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builder<String?, Any?>()
            for (attr in rule.getAttributes()) {
                val starlarkValue: Any? =
                    com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder.Companion.convertAttributeValueForAspectPropagationFunc(
                        java.util.function.Supplier {
                            dependencyLabels.get(
                                DependencyKind.AttributeDependencyKind.forRule(
                                    attr
                                )
                            )
                        },
                        attr,
                        attributeMap.get(attr.getName(), attr.getType())
                    )

                if (starlarkValue != null) {
                    ruleAttributesBuilder.put(
                        attr.getPublicName(), RuleAttribute(starlarkValue, attr.isToolDependency())
                    )
                }
            }
            val ruleAttributes: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                StructProvider.STRUCT.create(
                    ruleAttributesBuilder.buildOrThrow(), "'%s' is not an attribute of target: " + rule
                )

            return StarlarkAspectPropagationContext(
                createAspectPublicParams(aspect),
                StarlarkAspectPropagationRule(
                    rule.getLabel(), createQualifiedRuleKind(rule.getRuleClassObject()), ruleAttributes
                )
            )
        }

        private fun createAspectPublicParams(aspect: Aspect): StructImpl {
            return StructProvider.STRUCT.create(
                aspect.getParameters().getAttributes().keySet().stream()
                    .map(aspect.getDefinition().getAttributes()::get)
                    .collect(
                        com.google.common.collect.ImmutableMap.toImmutableMap<T?, K?, V?>(
                            Attribute::getPublicName,
                            java.util.function.Function { attr: T? ->
                                Attribute.valueToStarlark(
                                    attr.getDefaultValue(
                                        null
                                    )
                                )
                            })
                    ),
                " '%s' is not a public parameter of the aspect."
            )
        }

        private fun createQualifiedRuleKind(ruleClass: RuleClass): QualifiedRuleKind {
            return createQualifiedRuleKind(
                ruleClass.getRuleDefinitionEnvironmentLabel(), ruleClass.getName()
            )
        }

        private fun createQualifiedRuleKind(
            ruleDefinitionEnvironmentLabel: Label?, ruleClassName: String?
        ): QualifiedRuleKind {
            return QualifiedRuleKind(ruleDefinitionEnvironmentLabel, ruleClassName)
        }
    }
}
