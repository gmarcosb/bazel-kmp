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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo

/**
 * A toolchain context for the aspect's base target toolchains. It is used to represent the result
 * of applying the aspects propagation to the base target toolchains.
 */
@AutoValue
abstract class AspectBaseTargetResolvedToolchainContext

    : ResolvedToolchainsDataInterface<ToolchainAspectsProviders?> {
    abstract fun toolchains(): com.google.common.collect.ImmutableMap<ToolchainTypeInfo?, ToolchainAspectsProviders?>?

    public override fun forToolchainType(toolchainTypeLabel: Label?): ToolchainAspectsProviders? {
        if (requestedToolchainTypeLabels().containsKey(toolchainTypeLabel)) {
            return toolchains().get(requestedToolchainTypeLabels().get(toolchainTypeLabel))
        }

        return null
    }

    fun templateVariableProviders(): com.google.common.collect.ImmutableList<TemplateVariableInfo?> {
        return toolchains().values.stream()
            .map<TemplateVariableInfo?> { obj: ToolchainAspectsProviders? -> obj!!.templateVariableProvider() }
            .filter(com.google.common.base.Predicates.notNull<TemplateVariableInfo?>())
            .collect(com.google.common.collect.ImmutableList.toImmutableList<TemplateVariableInfo?>())
    }

    /**
     * A Starlark-indexable wrapper used to represent the providers of the aspects applied on the base
     * target toolchains.
     */
    class ToolchainAspectsProviders
    private constructor(
        aspectsProviders: TransitiveInfoProviderMap,
        templateVariableInfo: TemplateVariableInfo?,
        label: Label?
    ) : StarlarkIndexable, Structure, ResolvedToolchainData {
        private val aspectsProviders: TransitiveInfoProviderMap
        private val templateVariableInfo: TemplateVariableInfo?
        private val label: Label?

        init {
            this.aspectsProviders = aspectsProviders
            this.templateVariableInfo = templateVariableInfo
            this.label = label
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun getIndex(semantics: StarlarkSemantics?, key: Any?): Any {
            val constructor: Provider = selectExportedProvider(key, semantics, "index")
            val declaredProvider: Any? = aspectsProviders.get(constructor.getKey())
            if (declaredProvider != null) {
                return declaredProvider
            }
            throw Starlark.errorf(
                "%s doesn't contain declared provider '%s'",
                Starlark.repr(this, semantics), constructor.getPrintableName()
            )
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun containsKey(semantics: StarlarkSemantics?, key: Any?): Boolean {
            return aspectsProviders.get(selectExportedProvider(key, semantics, "query").getKey()) != null
        }

        /**
         * Selects the provider identified by `key`, throwing a Starlark error if the key is not a
         * provider or not exported.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun selectExportedProvider(
            key: Any?, semantics: StarlarkSemantics?, operation: String?
        ): Provider {
            if (key !is Provider) {
                throw Starlark.errorf(
                    "This type only supports %sing by object constructors, got %s instead",
                    operation, Starlark.type(key)
                )
            }
            if (!key.isExported()) {
                throw Starlark.errorf(
                    "%s only supports %sing by exported providers. Assign the provider a name "
                            + "in a top-level assignment statement.",
                    Starlark.repr(this, semantics), operation
                )
            }
            return key
        }

        override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
            printer.append("<ToolchainAspectsProviders for toolchain target: " + label + ">")
        }

        override fun getValue(name: String): Any? {
            if (name == LABEL_FIELD) {
                return label
            }
            return null
        }

        override fun getFieldNames(): com.google.common.collect.ImmutableList<String?> {
            return com.google.common.collect.ImmutableList.of<String?>(LABEL_FIELD)
        }

        override fun getErrorMessageForUnknownField(field: String?): String? {
            // Use the default error message.
            return null
        }

        fun templateVariableProvider(): TemplateVariableInfo? {
            return this.templateVariableInfo
        }
    }

    companion object {
        @Throws(MergingException::class)
        fun load(
            unloadedToolchainContext: UnloadedToolchainContext,
            targetDescription: String?,
            toolchainTargets: com.google.common.collect.ImmutableMultimap<ToolchainTypeInfo?, ConfiguredTargetAndData?>
        ): AspectBaseTargetResolvedToolchainContext {
            val toolchainsBuilder: com.google.common.collect.ImmutableMap.Builder<ToolchainTypeInfo?, ToolchainAspectsProviders?> =
                com.google.common.collect.ImmutableMap.Builder<ToolchainTypeInfo?, ToolchainAspectsProviders?>()

            for (toolchainType in unloadedToolchainContext.toolchainTypeToResolved().keySet()) {
                com.google.common.base.Preconditions.checkArgument(toolchainTargets.get(toolchainType).size == 1)

                val toolchainTarget: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    com.google.common.collect.Iterables.getOnlyElement<ConfiguredTargetAndData?>(
                        toolchainTargets.get(
                            toolchainType
                        )
                    ).getConfiguredTarget()

                if (toolchainTarget is MergedConfiguredTarget) {
                    // Only add the aspects providers from the toolchains that the aspects applied to.
                    val templateVariableInfo: TemplateVariableInfo? =
                        toolchainTarget
                            .getBaseConfiguredTarget()
                            .get(TemplateVariableInfo.PROVIDER.id()) as TemplateVariableInfo?
                    toolchainsBuilder.put(
                        toolchainType,
                        ToolchainAspectsProviders(
                            toolchainTarget.getAspectsProviders(),
                            templateVariableInfo,
                            toolchainTarget.getLabel()
                        )
                    )
                } else {
                    // Add empty providers for the toolchains that the aspects did not apply to.
                    val templateVariableInfo: TemplateVariableInfo? =
                        toolchainTarget.get(TemplateVariableInfo.PROVIDER.id()) as TemplateVariableInfo?
                    toolchainsBuilder.put(
                        toolchainType,
                        ToolchainAspectsProviders(
                            TransitiveInfoProviderMapBuilder().build(),
                            templateVariableInfo,
                            toolchainTarget.getLabel()
                        )
                    )
                }
            }
            val toolchains: com.google.common.collect.ImmutableMap<ToolchainTypeInfo?, ToolchainAspectsProviders?> =
                toolchainsBuilder.buildOrThrow()

            return AutoValue_AspectBaseTargetResolvedToolchainContext( // ToolchainContext:
                unloadedToolchainContext.key(),
                unloadedToolchainContext.executionPlatform(),
                unloadedToolchainContext.targetPlatform(),
                unloadedToolchainContext.toolchainTypes(),
                unloadedToolchainContext.resolvedToolchainLabels(),  // ResolvedToolchainsDataInterface:
                targetDescription,
                unloadedToolchainContext.requestedLabelToToolchainType(),  // this:
                toolchains
            )
        }
    }
}
