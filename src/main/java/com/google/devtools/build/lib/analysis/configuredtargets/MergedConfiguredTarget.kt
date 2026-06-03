// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.configuredtargets

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/**
 * A single dependency with its configured target and aspects merged together.
 * 
 * 
 * This is an ephemeral object created only for the analysis of a single configured target. After
 * that configured target is analyzed, this is thrown away.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@AutoCodec
class MergedConfiguredTarget @VisibleForSerialization internal constructor(
    base: ConfiguredTarget,
    aspects: Iterable<ConfiguredAspect>,
    nonBaseProviders: TransitiveInfoProviderMap
) : AbstractConfiguredTarget( // TODO(b/281522692): it's unsound to pass a null key here, but the type system doesn't
    // currently provide a better way to do this.
    /* actionLookupKey= */
    null,  // Placeholder visibility because this target isn't consumed.
    VisibilityProvider.PRIVATE_VISIBILITY
) {
    /**
     * This exception is thrown when the providers of a configured target and the aspects applied to
     * it cannot be merged.
     */
    class MergingException : java.lang.Exception {
        constructor(message: String?) : super(message)

        constructor(message: String?, cause: Throwable?) : super(message, cause)
    }

    private val base: ConfiguredTarget
    private val aspects: com.google.common.collect.ImmutableList<ConfiguredAspect>

    /**
     * Providers that come from any source that isn't a pure pointer to the base rule's providers.
     * 
     * 
     * Examples include providers from aspects and merged providers that appear in both the base
     * rule and aspects.
     */
    private val nonBaseProviders: TransitiveInfoProviderMap

    init {
        this.base = base
        this.aspects = com.google.common.collect.ImmutableList.copyOf<ConfiguredAspect?>(aspects)
        this.nonBaseProviders = nonBaseProviders
    }

    public override fun isCreatedInSymbolicMacro(): Boolean {
        // Technically, like visibility, we can return anything here because this target isn't consumed.
        // If we wanted the correct answer we could obtain it from the base CT's visibility provider.
        return false
    }

    override fun getLookupKey(): ActionLookupKey? {
        throw java.lang.IllegalStateException(
            "MergedConfiguredTarget is ephemeral. It does not exist in the Skyframe graph and it does"
                    + " not have a key."
        )
    }

    public override fun getLabel(): Label {
        return base.getLabel()
    }

    public override fun getConfigurationKey(): BuildConfigurationKey? {
        return base.getConfigurationKey()
    }

    override fun <P : TransitiveInfoProvider?> getProvider(providerClass: java.lang.Class<P?>): P? {
        AnalysisUtils.Companion.checkProvider<P?>(providerClass)

        var provider: P? = nonBaseProviders.getProvider(providerClass)
        if (provider != null) {
            return provider
        }
        provider = base.getProvider(providerClass)
        if (provider != null) {
            return provider
        }
        if (providerClass.isAssignableFrom(getClass())) {
            return providerClass.cast(this)
        }
        return null
    }

    protected override fun addExtraStarlarkKeys(result: java.util.function.Consumer<String?>) {
        if (base is AbstractConfiguredTarget) {
            base.addExtraStarlarkKeys(result)
        }
        for (i in 0..<nonBaseProviders.getProviderCount()) {
            val classAt: Any? = nonBaseProviders.getProviderKeyAt(i)
            if (classAt is String) {
                result.accept(classAt)
            }
        }
        result.accept(AbstractConfiguredTarget.Companion.ACTIONS_FIELD_NAME)
    }

    override fun rawGetStarlarkProvider(providerKey: Provider.Key?): Info? {
        var provider: Info? = nonBaseProviders.get(providerKey)
        if (provider == null) {
            provider = base.get(providerKey)
        }
        return provider
    }

    override fun rawGetStarlarkProvider(providerKey: String): Any? {
        if (providerKey == AbstractConfiguredTarget.Companion.ACTIONS_FIELD_NAME) {
            val actions: com.google.common.collect.ImmutableList.Builder<ActionAnalysisMetadata?> =
                com.google.common.collect.ImmutableList.builder<ActionAnalysisMetadata?>()
            // Only expose actions which are StarlarkValues.
            // TODO(cparsons): Expose all actions to Starlark.
            for (aspect in aspects) {
                actions.addAll(
                    aspect.getActions().stream()
                        .filter(java.util.function.Predicate { action: ActionAnalysisMetadata? -> action is ActionApi })
                        .iterator()
                )
            }
            if (base is RuleConfiguredTarget) {
                actions.addAll(
                    (base as RuleConfiguredTarget)
                        .getActions().stream()
                        .filter(java.util.function.Predicate { action: ActionAnalysisMetadata? -> action is ActionApi })
                        .iterator()
                )
            }
            return actions.build()
        }
        var provider: Any? = nonBaseProviders.get(providerKey)
        if (provider == null) {
            provider = base.get(providerKey)
        }
        return provider
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
        printer.append("<merged target " + getLabel() + ">")
    }

    public override fun getProvidersDictForQuery(): Dict<String?, Any?>? {
        return toProvidersDictForQuery(nonBaseProviders)
    }

    override fun getRuleClassString(): String? {
        if (base !is AbstractConfiguredTarget) {
            return super.getRuleClassString()
        }
        return base.getRuleClassString()
    }

    fun getBaseConfiguredTarget(): ConfiguredTarget {
        return base
    }

    public override fun unwrapIfMerged(): ConfiguredTarget {
        return base.unwrapIfMerged()
    }

    /** Returns only the providers from the aspects.  */
    @Throws(MergingException::class)
    fun getAspectsProviders(): TransitiveInfoProviderMap {
        val aspectsProviders: TransitiveInfoProviderMapBuilder = TransitiveInfoProviderMapBuilder()

        // Merge output group providers of aspects only. Filtering the base target output
        // groups from `nonBaseProviders` does not work because some groups like
        // `OutputGroupInfo#Validation` contains artifacts from both base
        // target and aspects.
        val outputGroups: OutputGroupInfo? = mergeOutputGroupProviders( /* base= */null, aspects)
        if (outputGroups != null) {
            aspectsProviders.put(outputGroups)
        }

        // Merge other aspects providers.
        for (i in 0..<nonBaseProviders.getProviderCount()) {
            val providerKey: Any? = nonBaseProviders.getProviderKeyAt(i)
            if (OutputGroupInfo.Companion.STARLARK_CONSTRUCTOR.getKey().equals(providerKey)
                || AnalysisFailureInfo.STARLARK_CONSTRUCTOR.getKey().equals(providerKey)
                || ExtraActionArtifactsProvider::class.java == providerKey
                || RequiredConfigFragmentsProvider::class.java == providerKey
            ) {
                continue
            }

            if (providerKey is java.lang.Class<*>) {
                val providerClass: java.lang.Class<out TransitiveInfoProvider?> =
                    providerKey as java.lang.Class<out TransitiveInfoProvider?>
                aspectsProviders.put(
                    providerClass, nonBaseProviders.getProviderInstanceAt(i) as TransitiveInfoProvider?
                )
            } else if (providerKey is String) {
                aspectsProviders.put(providerKey, nonBaseProviders.getProviderInstanceAt(i))
            } else if (providerKey is Provider.Key) {
                aspectsProviders.put(nonBaseProviders.getProviderInstanceAt(i) as Info?)
            }
        }

        return aspectsProviders.build()
    }

    companion object {
        /** Creates an instance based on a configured target and a set of aspects.  */
        @Throws(MergingException::class)
        fun of(base: ConfiguredTarget, aspects: MutableCollection<ConfiguredAspect>): ConfiguredTarget? {
            if (aspects.isEmpty()) {
                return base // If there are no aspects, don't bother with creating a proxy object.
            }

            val nonBaseProviders: TransitiveInfoProviderMapBuilder = TransitiveInfoProviderMapBuilder()

            // filesToBuild is special: for native aspects, it is returned in a FileProvider, for Starlark
            // ones, in a DefaultInfo. Furthermore, DefaultInfo is not a "normal" provider on configured
            // targets but is created on demand from FileProvider, etc. So we need to jump through some
            // hoops here.
            val filesToBuild: MutableList<NestedSet<Artifact?>?> = java.util.ArrayList<NestedSet<Artifact?>?>()
            filesToBuild.add(base.getProvider(FileProvider::class.java).getFilesToBuild())
            for (aspect in aspects) {
                if (aspect.getProvider<P?>(FileProvider::class.java) != null) {
                    filesToBuild.add(aspect.getProvider<P?>(FileProvider::class.java).getFilesToBuild())
                } else if (aspect.get(DefaultInfo.Companion.PROVIDER.getKey()) != null) {
                    val defaultInfo: DefaultInfo = aspect.get(DefaultInfo.Companion.PROVIDER.getKey()) as DefaultInfo
                    if (defaultInfo.getDataRunfiles() != null || defaultInfo.getDefaultRunfiles() != null || defaultInfo.getExecutable() != null || defaultInfo.getFilesToRun() != null) {
                        throw MergingException(
                            "Provider 'DefaultInfo' returned by an aspect not at top level must only have the "
                                    + "'files' field set"
                        )
                    }

                    if (defaultInfo.getFiles() != null) {
                        try {
                            filesToBuild.add(defaultInfo.getFiles().getSet(Artifact::class.java))
                        } catch (e: TypeException) {
                            throw MergingException(
                                "'files' field of 'DefaultInfo' should contain a depset of files", e
                            )
                        }
                    }
                }
            }

            if (filesToBuild.size() > 1) {
                nonBaseProviders.put(
                    FileProvider::class.java,
                    FileProvider.of(NestedSetBuilder.fromNestedSets(filesToBuild).build())
                )
            }

            // Merge output group providers.
            val mergedOutputGroupInfo: OutputGroupInfo? = mergeOutputGroupProviders(base, aspects)
            if (mergedOutputGroupInfo != null) {
                nonBaseProviders.put(mergedOutputGroupInfo)
            }

            // Merge analysis failures.
            val analysisFailures: com.google.common.collect.ImmutableList<NestedSet<AnalysisFailure?>?> =
                getAnalysisFailures(base, aspects)
            if (!analysisFailures.isEmpty()) {
                nonBaseProviders.put(AnalysisFailureInfo.forAnalysisFailureSets(analysisFailures))
            }

            // Merge extra-actions provider.
            val mergedExtraActionProviders: ExtraActionArtifactsProvider? = ExtraActionArtifactsProvider.merge(
                Companion.getAllProviders<T?>(base, aspects, ExtraActionArtifactsProvider::class.java)
            )
            if (mergedExtraActionProviders != null) {
                nonBaseProviders.add(mergedExtraActionProviders)
            }

            // Merge required config fragments provider.
            val requiredConfigFragmentProviders: MutableList<RequiredConfigFragmentsProvider?> =
                Companion.getAllProviders<T?>(base, aspects, RequiredConfigFragmentsProvider::class.java)
            if (!requiredConfigFragmentProviders.isEmpty()) {
                nonBaseProviders.add(RequiredConfigFragmentsProvider.merge(requiredConfigFragmentProviders))
            }

            for (aspect in aspects) {
                val providers: TransitiveInfoProviderMap = aspect.getProviders()
                for (i in 0..<providers.getProviderCount()) {
                    val providerKey: Any = providers.getProviderKeyAt(i)
                    if (OutputGroupInfo.Companion.STARLARK_CONSTRUCTOR.getKey().equals(providerKey)
                        || AnalysisFailureInfo.STARLARK_CONSTRUCTOR.getKey().equals(providerKey)
                        || FileProvider::class.java == providerKey
                        || ExtraActionArtifactsProvider::class.java == providerKey
                        || RequiredConfigFragmentsProvider::class.java == providerKey
                    ) {
                        continue
                    }

                    if (providerKey == DefaultInfo.Companion.PROVIDER.getKey()) {
                        // This was handled when creating FileProvider above.
                        continue
                    } else if (providerKey is java.lang.Class<*>) {
                        val providerClass: java.lang.Class<out TransitiveInfoProvider?> =
                            providerKey as java.lang.Class<out TransitiveInfoProvider?>
                        if (base.getProvider(providerClass) != null || nonBaseProviders.contains(providerClass)) {
                            throw MergingException("Provider " + providerKey + " provided twice")
                        }
                        nonBaseProviders.put(
                            providerClass, providers.getProviderInstanceAt(i) as TransitiveInfoProvider?
                        )
                    } else if (providerKey is String) {
                        if (base.get(providerKey) != null || nonBaseProviders.contains(providerKey)) {
                            throw MergingException("Provider " + providerKey + " provided twice")
                        }
                        nonBaseProviders.put(providerKey, providers.getProviderInstanceAt(i))
                    } else if (providerKey is Provider.Key) {
                        // If InstrumentedFilesInfo is on both the base target and an aspect, ignore the one from
                        // the base. Otherwise, sharing implementation between a rule which returns
                        // InstrumentedFilesInfo (e.g. *_library) and a related aspect (e.g. *_proto_library) can
                        // add an implicit brittle assumption that the underlying rule (e.g. proto_library) does
                        // not return InstrumentedFilesInfo.
                        if ((!InstrumentedFilesInfo.STARLARK_CONSTRUCTOR.getKey().equals(providerKey)
                                    && base.get(providerKey) != null)
                            || nonBaseProviders.contains(providerKey)
                        ) {
                            throw MergingException("Provider " + providerKey + " provided twice")
                        }
                        nonBaseProviders.put(providers.getProviderInstanceAt(i) as Info?)
                    }
                }
            }
            return MergedConfiguredTarget(base, aspects, nonBaseProviders.build())
        }

        @Throws(MergingException::class)
        private fun mergeOutputGroupProviders(
            base: ConfiguredTarget?, aspects: Iterable<ConfiguredAspect>
        ): OutputGroupInfo? {
            val providers: com.google.common.collect.ImmutableList.Builder<OutputGroupInfo?> =
                com.google.common.collect.ImmutableList.builder<OutputGroupInfo?>()

            if (base != null) {
                val baseProvider: OutputGroupInfo? = OutputGroupInfo.Companion.get(base)
                if (baseProvider != null) {
                    providers.add(baseProvider)
                }
            }

            for (configuredAspect in aspects) {
                val aspectProvider: OutputGroupInfo? = OutputGroupInfo.Companion.get(configuredAspect)
                if (aspectProvider == null) {
                    continue
                }
                providers.add(aspectProvider)
            }
            return OutputGroupInfo.Companion.merge(providers.build())
        }

        private fun getAnalysisFailures(
            base: ConfiguredTarget, aspects: Iterable<ConfiguredAspect>
        ): com.google.common.collect.ImmutableList<NestedSet<AnalysisFailure?>?> {
            val analysisFailures: com.google.common.collect.ImmutableList.Builder<NestedSet<AnalysisFailure?>?> =
                com.google.common.collect.ImmutableList.builder<NestedSet<AnalysisFailure?>?>()
            val baseFailureInfo: AnalysisFailureInfo? = base.get(AnalysisFailureInfo.STARLARK_CONSTRUCTOR)
            if (baseFailureInfo != null) {
                analysisFailures.add(baseFailureInfo.getCausesNestedSet())
            }
            for (configuredAspect in aspects) {
                val aspectFailureInfo: AnalysisFailureInfo? =
                    configuredAspect.get(AnalysisFailureInfo.STARLARK_CONSTRUCTOR)
                if (aspectFailureInfo != null) {
                    analysisFailures.add(aspectFailureInfo.getCausesNestedSet())
                }
            }
            return analysisFailures.build()
        }

        private fun <T : TransitiveInfoProvider?> getAllProviders(
            base: ConfiguredTarget, aspects: Iterable<ConfiguredAspect>, providerClass: java.lang.Class<T?>?
        ): MutableList<T?> {
            val baseProvider: T? = base.getProvider(providerClass)
            val providers: MutableList<T?> = java.util.ArrayList<T?>()
            if (baseProvider != null) {
                providers.add(baseProvider)
            }

            for (configuredAspect in aspects) {
                val aspectProvider: T? = configuredAspect.getProvider<T?>(providerClass)
                if (aspectProvider == null) {
                    continue
                }
                providers.add(aspectProvider)
            }
            return providers
        }
    }
}
