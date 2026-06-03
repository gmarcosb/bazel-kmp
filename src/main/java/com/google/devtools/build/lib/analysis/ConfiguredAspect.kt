// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/**
 * Extra information about a configured target computed on request of a dependent.
 * 
 * 
 * Analogous to [ConfiguredTarget]: contains a bunch of transitive info providers, which
 * are merged with the providers of the associated configured target before they are passed to the
 * configured target factories that depend on the configured target to which this aspect is added.
 * 
 * 
 * Aspects are created alongside configured targets on request from dependents.
 * 
 * 
 * For more information about aspects, see [ ].
 * 
 * @see com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory
 * 
 * @see com.google.devtools.build.lib.packages.AspectClass
 */
interface ConfiguredAspect : ProviderCollection {
    fun getActions(): com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?

    /** Returns the providers created by the aspect.  */
    fun getProviders(): TransitiveInfoProviderMap?

    public override fun <P : TransitiveInfoProvider?> getProvider(providerClass: java.lang.Class<P?>): P? {
        AnalysisUtils.Companion.checkProvider<P?>(providerClass)
        return getProviders().getProvider(providerClass)
    }

    public override fun get(key: Provider.Key?): Info {
        return getProviders().get(key)
    }

    public override fun get(legacyKey: String?): Any? {
        if (OutputGroupInfo.Companion.STARLARK_NAME == legacyKey) {
            return get(OutputGroupInfo.Companion.STARLARK_CONSTRUCTOR.getKey())
        }
        return getProviders().get(legacyKey)
    }

    /** Builder for [ConfiguredAspect].  */
    class Builder(ruleContext: RuleContext) {
        private val providers: TransitiveInfoProviderMapBuilder = TransitiveInfoProviderMapBuilder()
        private val outputGroupBuilders: TreeMap<String?, NestedSetBuilder<Artifact?>?> =
            TreeMap<String?, NestedSetBuilder<Artifact?>?>()
        private val ruleContext: RuleContext

        init {
            this.ruleContext = ruleContext
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun <T : TransitiveInfoProvider?> addProvider(
            providerClass: java.lang.Class<out T?>?, provider: T?
        ): Builder {
            com.google.common.base.Preconditions.checkNotNull<T?>(provider)
            com.google.devtools.build.lib.analysis.ConfiguredAspect.Builder.Companion.checkProviderClass(providerClass)
            providers.put(providerClass, provider)
            return this
        }

        /** Adds a provider to the aspect.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addProvider(provider: TransitiveInfoProvider?): Builder {
            com.google.common.base.Preconditions.checkNotNull<TransitiveInfoProvider?>(provider)
            addProvider<T?>(TransitiveInfoProviderEffectiveClassHelper.get(provider), provider)
            return this
        }

        /** Adds a set of files to an output group.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addOutputGroup(name: String?, artifacts: NestedSet<Artifact?>?): Builder {
            outputGroupBuilders
                .computeIfAbsent(name, java.util.function.Function { k: String? -> NestedSetBuilder.stableOrder() })
                .addTransitive(artifacts)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addStarlarkTransitiveInfo(name: String?, value: Any?): Builder {
            providers.put(name, value)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        fun addStarlarkDeclaredProvider(declaredProvider: Info): Builder {
            val constructor: Provider = declaredProvider.getProvider()
            if (!constructor.isExported()) {
                throw Starlark.errorf(
                    "aspect function returned an instance of a provider (defined at %s) that is not a"
                            + " global",
                    constructor.getLocation()
                )
            }
            addDeclaredProvider(declaredProvider)
            return this
        }

        private fun addDeclaredProvider(declaredProvider: Info?) {
            providers.put(declaredProvider)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addNativeDeclaredProvider(declaredProvider: Info): Builder {
            val constructor: Provider = declaredProvider.getProvider()
            com.google.common.base.Preconditions.checkState(constructor.isExported())
            addDeclaredProvider(declaredProvider)
            return this
        }

        @Throws(ActionConflictException::class, java.lang.InterruptedException::class)
        fun build(): ConfiguredAspect? {
            if (!outputGroupBuilders.isEmpty()) {
                check(!providers.contains(OutputGroupInfo.Companion.STARLARK_CONSTRUCTOR.getKey())) { "OutputGroupInfo was provided explicitly; do not use addOutputGroup" }
                addDeclaredProvider(OutputGroupInfo.Companion.fromBuilders(outputGroupBuilders))
            }

            // Only add {@link ExtraActionProvider} if extra action listeners are applied
            if (!ruleContext.getConfiguration().getActionListeners().isEmpty()) {
                addProvider(
                    ExtraActionUtils.createExtraActionProvider( /* actionsWithoutExtraAction= */
                        com.google.common.collect.ImmutableSet.of<ActionAnalysisMetadata?>(), ruleContext
                    )
                )
            }

            val analysisEnvironment: AnalysisEnvironment = ruleContext.getAnalysisEnvironment()
            val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> =
                analysisEnvironment.getRegisteredActions()
            try {
                Actions.assignOwnersAndThrowIfConflictToleratingSharedActions(
                    analysisEnvironment.getActionKeyContext(), actions, ruleContext.getOwner()
                )
            } catch (e: Actions.ArtifactGeneratedByOtherRuleException) {
                ruleContext.ruleError(e.getMessage())
                return null
            }

            maybeAddRequiredConfigFragmentsProvider()

            val providerMap: TransitiveInfoProviderMap = providers.build()

            // Initialize every StarlarkApiProvider
            for (i in 0..<providerMap.getProviderCount()) {
                val obj: Any? = providerMap.getProviderInstanceAt(i)
                if (obj is StarlarkApiProvider) {
                    obj.init(providerMap)
                }
            }

            if (actions.isEmpty() && providerMap.getProviderCount() === 0) {
                return BasicConfiguredAspect.Companion.EMPTY
            }
            return BasicConfiguredAspect(actions, providerMap)
        }

        /**
         * Adds [RequiredConfigFragmentsProvider] if [ ][CoreOptions.includeRequiredConfigFragmentsProvider] isn't [ ][CoreOptions.IncludeConfigFragmentsEnum.OFF] and if the provider was not already added.
         * 
         * 
         * See [RequiredFragmentsUtil] for a description of the meaning of this provider's
         * content. That class contains methods that populate the results of [ ][RuleContext.getRequiredConfigFragments].
         */
        private fun maybeAddRequiredConfigFragmentsProvider() {
            if (ruleContext.shouldIncludeRequiredConfigFragmentsProvider()
                && !providers.contains(RequiredConfigFragmentsProvider::class.java)
            ) {
                addProvider(ruleContext.getRequiredConfigFragments())
            }
        }

        companion object {
            private fun checkProviderClass(providerClass: java.lang.Class<out TransitiveInfoProvider?>?) {
                com.google.common.base.Preconditions.checkNotNull(providerClass)
            }
        }
    }

    /** Basic implementation of [ConfiguredAspect].  */
    class BasicConfiguredAspect private constructor(
        actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?,
        providers: TransitiveInfoProviderMap?
    ) : ConfiguredAspect {
        private val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?

        private val providers: TransitiveInfoProviderMap?

        init {
            this.actions = actions
            this.providers = providers
        }

        override fun getActions(): com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>? {
            return actions
        }

        override fun getProviders(): TransitiveInfoProviderMap? {
            return providers
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("actions", actions)
                .add("providers", providers).toString()
        }

        companion object {
            private val EMPTY = BasicConfiguredAspect(
                com.google.common.collect.ImmutableList.of<ActionAnalysisMetadata?>(),
                TransitiveInfoProviderMapImpl.empty()
            )
        }
    }

    /**
     * Implementation of [ConfiguredAspect] that represents aspect that could not be applied to
     * a target.
     */
    class NonApplicableAspect private constructor() : ConfiguredAspect {
        override fun getActions(): com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> {
            return ACTIONS
        }

        override fun getProviders(): TransitiveInfoProviderMap? {
            return PROVIDERS
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).toString()
        }

        companion object {
            val INSTANCE: ConfiguredAspect = NonApplicableAspect()

            private val ACTIONS: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> =
                com.google.common.collect.ImmutableList.of<ActionAnalysisMetadata?>()
            private val PROVIDERS: TransitiveInfoProviderMap? = TransitiveInfoProviderMapBuilder().build()
        }
    }

    companion object {
        fun forAlias(real: ConfiguredAspect): ConfiguredAspect {
            // Aspect on aliases don't have actions, so don't return the actions of the
            // aliased target. They still propagate providers from the real aspect,
            // though.
            return BasicConfiguredAspect(
                com.google.common.collect.ImmutableList.of<ActionAnalysisMetadata?>(),
                real.getProviders()
            )
        }

        fun builder(ruleContext: RuleContext): Builder {
            return com.google.devtools.build.lib.analysis.ConfiguredAspect.Builder(ruleContext)
        }
    }
}
