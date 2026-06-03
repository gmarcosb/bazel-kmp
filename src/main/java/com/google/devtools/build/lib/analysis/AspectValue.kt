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

/** An aspect in the context of the Skyframe graph.  */
@AutoCodec(deserializedInterface = DeserializedSkyValue::class)
open class AspectValue @AutoCodec.Instantiator internal constructor(
    actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?,
    aspect: Aspect?,
    providers: TransitiveInfoProviderMap?,
    writesOutputToMasterLog: Boolean
) : BasicActionLookupValue(actions), ConfiguredAspect, RuleConfiguredObjectValue {
    // These variables are only non-final because they may be clear()ed to save memory. They are null
    // only after they are cleared except for transitivePackagesForPackageRootResolution.
    private var aspect: Aspect?
    private var providers: TransitiveInfoProviderMap?

    // We store this in a boolean because the aspect variable from which it comes may be cleared to
    // save memory.
    private val writesOutputToMasterLog: Boolean

    private constructor(aspect: Aspect?, configuredAspect: ConfiguredAspect) : this(
        configuredAspect.getActions(),
        com.google.common.base.Preconditions.checkNotNull<Aspect?>(aspect),
        configuredAspect.getProviders(),
        aspect.getDefinition().getAttributes().containsKey("\$print_to_master_log")
    )

    init {
        this.aspect = aspect
        this.providers = providers
        this.writesOutputToMasterLog = writesOutputToMasterLog
    }

    open fun getKeyForTransitivePackageTracking(): AspectKey? {
        throw java.lang.UnsupportedOperationException("Only supported if transitive packages are tracked.")
    }

    fun getAspect(): Aspect? {
        return com.google.common.base.Preconditions.checkNotNull<Aspect?>(aspect)
    }

    override fun getProviders(): TransitiveInfoProviderMap? {
        return com.google.common.base.Preconditions.checkNotNull<TransitiveInfoProviderMap?>(providers)
    }

    fun getWritesOutputToMasterLog(): Boolean {
        return writesOutputToMasterLog
    }

    public override fun isCleared(): Boolean {
        return this.aspect == null
    }

    /**
     * Clears data from this value.
     * 
     * 
     * Should only be used when user specifies --discard_analysis_cache. Must be called at most
     * once per value, after which this object's other methods cannot be called.
     */
    open fun clear(clearEverything: Boolean) {
        if (clearEverything) {
            aspect = null
            providers = null
        }
    }

    public override fun getTransitivePackages(): NestedSet<Package.Metadata?>? {
        return null
    }

    public override fun getConfiguredObject(): ProviderCollection? {
        return this
    }

    protected override fun getStringHelper(): com.google.common.base.MoreObjects.ToStringHelper {
        return super.getStringHelper().add("aspect", aspect).add("providers", providers)
    }

    override fun toString(): String {
        return getStringHelper().toString()
    }

    private open class AspectValueWithTransitivePackages(
        key: AspectKey?,
        aspect: Aspect?,
        configuredAspect: ConfiguredAspect,
        transitivePackages: NestedSet<Package.Metadata?>?
    ) : AspectValue(aspect, configuredAspect) {
        @Transient
        private var transitivePackages: NestedSet<Package.Metadata?>? // Null after clear().

        private var key: AspectKey?

        init {
            this.transitivePackages =
                com.google.common.base.Preconditions.checkNotNull<NestedSet<Package.Metadata?>?>(transitivePackages)
            this.key = com.google.common.base.Preconditions.checkNotNull<AspectKey?>(key)
        }

        override fun getTransitivePackages(): NestedSet<Package.Metadata?>? {
            return transitivePackages
        }

        override fun getKeyForTransitivePackageTracking(): AspectKey? {
            return com.google.common.base.Preconditions.checkNotNull<AspectKey?>(key)
        }

        override fun clear(clearEverything: Boolean) {
            super.clear(clearEverything)
            transitivePackages = null
            key = null
        }

        override fun getStringHelper(): com.google.common.base.MoreObjects.ToStringHelper {
            return super.getStringHelper().add("key", key).add("transitivePackages", transitivePackages)
        }
    }

    @AutoCodec(deserializedInterface = DeserializedSkyValue::class)
    @VisibleForSerialization
    internal class AspectValueForAlias : AspectValue {
        private constructor(aspect: Aspect?, configuredAspect: ConfiguredAspect) : super(aspect, configuredAspect)

        @AutoCodec.Instantiator
        constructor(
            actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?,
            aspect: Aspect?,
            providers: TransitiveInfoProviderMap?,
            writesOutputToMasterLog: Boolean
        ) : super(actions, aspect, providers, writesOutputToMasterLog)
    }

    private class AspectValueWithTransitivePackagesForAlias
        (
        key: AspectKey?,
        aspect: Aspect?,
        configuredAspect: ConfiguredAspect,
        transitivePackages: NestedSet<Package.Metadata?>?
    ) : AspectValueWithTransitivePackages(key, aspect, configuredAspect, transitivePackages)

    companion object {
        fun create(
            key: AspectKey?,
            aspect: Aspect?,
            configuredAspect: ConfiguredAspect,
            transitivePackages: NestedSet<Package.Metadata?>?
        ): AspectValue {
            return if (transitivePackages == null)
                AspectValue(aspect, configuredAspect)
            else
                AspectValueWithTransitivePackages(key, aspect, configuredAspect, transitivePackages)
        }

        fun createForAlias(
            key: AspectKey?,
            aspect: Aspect?,
            configuredAspect: ConfiguredAspect,
            transitivePackages: NestedSet<Package.Metadata?>?
        ): AspectValue {
            return if (transitivePackages == null)
                AspectValueForAlias(aspect, configuredAspect)
            else
                AspectValueWithTransitivePackagesForAlias(
                    key, aspect, configuredAspect, transitivePackages
                )
        }

        fun isForAliasTarget(aspectValue: AspectValue?): Boolean {
            return aspectValue is AspectValueForAlias
                    || aspectValue is AspectValueWithTransitivePackagesForAlias
        }
    }
}
