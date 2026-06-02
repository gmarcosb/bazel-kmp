// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.aquery

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * [QueryEnvironment] that is specialized for running action graph queries over the configured
 * target graph.
 */
class ActionGraphQueryEnvironment
    (
    keepGoing: Boolean,
    eventHandler: ExtendedEventHandler?,
    extraFunctions: Iterable<QueryFunction?>?,
    topLevelConfigurations: TopLevelConfigurations?,
    transitiveConfigurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationValue?>?,
    mainRepoTargetParser: TargetPattern.Parser?,
    pkgPath: PathPackageLocator?,
    walkableGraphSupplier: java.util.function.Supplier<WalkableGraph?>,
    settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>?,
    labelPrinter: LabelPrinter?
) : PostAnalysisQueryEnvironment<ConfiguredTargetValue?>(
    keepGoing,
    eventHandler,
    extraFunctions,
    topLevelConfigurations,
    transitiveConfigurations,
    mainRepoTargetParser,
    pkgPath,
    walkableGraphSupplier,
    settings,
    labelPrinter
) {
    private var aqueryOptions: AqueryOptions? = null

    private var actionFilters: AqueryActionFilter? = null
    private val configuredTargetKeyExtractor: KeyExtractor<ConfiguredTargetValue?, ActionLookupKey?>
    private val accessor: ConfiguredTargetValueAccessor

    init {
        this.configuredTargetKeyExtractor = KeyExtractor { targetValue: T? -> getConfiguredTargetKeyImpl(targetValue) }
        this.accessor =
            ConfiguredTargetValueAccessor(
                walkableGraphSupplier.get(),
                TargetLookup { label: Label? -> this.getTarget(label) },
                this.configuredTargetKeyExtractor
            )
    }

    constructor(
        keepGoing: Boolean,
        eventHandler: ExtendedEventHandler?,
        extraFunctions: Iterable<QueryFunction?>?,
        topLevelConfigurations: TopLevelConfigurations?,
        transitiveConfigurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationValue?>?,
        mainRepoTargetParser: TargetPattern.Parser?,
        pkgPath: PathPackageLocator?,
        walkableGraphSupplier: java.util.function.Supplier<WalkableGraph?>,
        aqueryOptions: AqueryOptions,
        labelPrinter: LabelPrinter?
    ) : this(
        keepGoing,
        eventHandler,
        extraFunctions,
        topLevelConfigurations,
        transitiveConfigurations,
        mainRepoTargetParser,
        pkgPath,
        walkableGraphSupplier,
        aqueryOptions.toSettings(),
        labelPrinter
    ) {
        this.aqueryOptions = aqueryOptions
    }

    override fun getAccessor(): ConfiguredTargetValueAccessor {
        return accessor
    }

    override fun getDefaultOutputFormatters(
        accessor: TargetAccessor<ConfiguredTargetValue?>?,
        eventHandler: ExtendedEventHandler?,
        out: java.io.OutputStream?,
        skyframeExecutor: SkyframeExecutor?,
        ruleClassProvider: RuleClassProvider?,
        packageManager: PackageManager?,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
    ): com.google.common.collect.ImmutableList<NamedThreadSafeOutputFormatterCallback<ConfiguredTargetValue?>?> {
        return com.google.common.collect.ImmutableList.of<NamedThreadSafeOutputFormatterCallback<ConfiguredTargetValue?>?>(
            ActionGraphProtoOutputFormatterCallback(
                eventHandler,
                aqueryOptions,
                out,
                accessor,
                AqueryOutputHandler.OutputType.BINARY,
                actionFilters
            ),
            ActionGraphProtoOutputFormatterCallback(
                eventHandler,
                aqueryOptions,
                out,
                accessor,
                AqueryOutputHandler.OutputType.DELIMITED_BINARY,
                actionFilters
            ),
            ActionGraphProtoOutputFormatterCallback(
                eventHandler,
                aqueryOptions,
                out,
                accessor,
                AqueryOutputHandler.OutputType.TEXT,
                actionFilters
            ),
            ActionGraphProtoOutputFormatterCallback(
                eventHandler,
                aqueryOptions,
                out,
                accessor,
                AqueryOutputHandler.OutputType.JSON,
                actionFilters
            ),
            ActionGraphTextOutputFormatterCallback(
                eventHandler,
                aqueryOptions,
                out,
                accessor,
                com.google.devtools.build.lib.query2.aquery.ActionGraphTextOutputFormatterCallback.OutputType.TEXT,
                actionFilters,
                getLabelPrinter()
            ),
            ActionGraphTextOutputFormatterCallback(
                eventHandler,
                aqueryOptions,
                out,
                accessor,
                com.google.devtools.build.lib.query2.aquery.ActionGraphTextOutputFormatterCallback.OutputType.COMMANDS,
                actionFilters,
                getLabelPrinter()
            ),
            ActionGraphSummaryOutputFormatterCallback(
                eventHandler, aqueryOptions, out, accessor, actionFilters
            )
        )
    }

    val outputFormat: String?
        get() = aqueryOptions.getOutputFormat()

    override fun getConfiguredTargetKeyExtractor(): KeyExtractor<ConfiguredTargetValue?, ActionLookupKey?> {
        return configuredTargetKeyExtractor
    }

    override fun getCorrectLabel(configuredTargetValue: ConfiguredTargetValue): Label {
        val target: ConfiguredTarget = configuredTargetValue.getConfiguredTarget()
        // Dereference any aliases that might be present.
        return target.getOriginalLabel()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun createConfiguredTargetValueFromKey(key: ConfiguredTargetKey): ConfiguredTargetValue? {
        val value: ConfiguredTargetValue? = getConfiguredTargetValue(key) as ConfiguredTargetValue?
        if (value == null
            || value.getConfiguredTarget().getConfigurationKey() != key.getConfigurationKey()
        ) {
            // The configurations might not match if the target's configuration changed due to a
            // transition or trimming. Filters such targets.
            return null
        }
        return value
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getTargetConfiguredTarget(label: Label?): ConfiguredTargetValue? {
        if (topLevelConfigurations.isTopLevelTarget(label)) {
            return createConfiguredTargetValueFromKey(
                ConfiguredTargetKey.builder()
                    .setLabel(label)
                    .setConfiguration(topLevelConfigurations.getConfigurationForTopLevelTarget(label))
                    .build()
            )
        } else {
            var toReturn: ConfiguredTargetValue?
            for (configuration in topLevelConfigurations.getConfigurations()) {
                toReturn =
                    createConfiguredTargetValueFromKey(
                        ConfiguredTargetKey.builder()
                            .setLabel(label)
                            .setConfiguration(configuration)
                            .build()
                    )
                if (toReturn != null) {
                    return toReturn
                }
            }
            return null
        }
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getNullConfiguredTarget(label: Label?): ConfiguredTargetValue? {
        return createConfiguredTargetValueFromKey(
            ConfiguredTargetKey.builder().setLabel(label).build()
        )
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getValueFromKey(key: SkyKey?): ConfiguredTargetValue? {
        com.google.common.base.Preconditions.checkState(key is ConfiguredTargetKey)
        return getConfiguredTargetValue(key) as ConfiguredTargetValue?
    }

    override fun getRuleConfiguredTarget(
        configuredTargetValue: ConfiguredTargetValue
    ): RuleConfiguredTarget? {
        val configuredTarget: ConfiguredTarget? = configuredTargetValue.getConfiguredTarget()
        if (configuredTarget is RuleConfiguredTarget) {
            return configuredTarget
        }
        return null
    }

    override fun getOwningRuleforOutputConfiguredTarget(
        configuredTargetValue: ConfiguredTargetValue
    ): RuleConfiguredTarget? {
        val configuredTarget: ConfiguredTarget? = configuredTargetValue.getConfiguredTarget()
        if (configuredTarget is OutputFileConfiguredTarget) {
            return configuredTarget.getGeneratingRule()
        }
        return null
    }

    override fun isAliasConfiguredTarget(configuredTargetValue: ConfiguredTargetValue): Boolean {
        return configuredTargetValue.getConfiguredTarget() is AliasConfiguredTarget
    }

    override fun getConfiguration(configuredTargetValue: ConfiguredTargetValue): BuildConfigurationValue? {
        val target: ConfiguredTarget = configuredTargetValue.getConfiguredTarget()
        try {
            return if (target.getConfigurationKey() == null)
                null
            else
                graph.getValue(target.getConfigurationKey()) as BuildConfigurationValue?
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException("Unexpected interruption during aquery", e)
        }
    }

    override fun getConfiguredTargetKey(
        configuredTargetValue: ConfiguredTargetValue
    ): ConfiguredTargetKey {
        return getConfiguredTargetKeyImpl(configuredTargetValue)
    }

    override fun getTargetsMatchingPattern(
        owner: QueryExpression?,
        pattern: String?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<ConfiguredTargetValue?>
    ): QueryTaskFuture<java.lang.Void?>? {
        val patternToEval: TargetPattern
        try {
            patternToEval = getPattern(pattern)
        } catch (tpe: TargetParsingException) {
            try {
                handleError(owner, tpe.getMessage(), tpe.getDetailedExitCode())
            } catch (qe: com.google.devtools.build.lib.query2.engine.QueryException) {
                return immediateFailedFuture<java.lang.Void?>(qe)
            }
            return immediateSuccessfulFuture<java.lang.Void?>(null)
        }

        val reportBuildFileErrorAsyncFunction: com.google.common.util.concurrent.AsyncFunction<TargetParsingException?, java.lang.Void?> =
            com.google.common.util.concurrent.AsyncFunction { exn: TargetParsingException? ->
                handleError(owner, exn.getMessage(), exn.getDetailedExitCode())
                com.google.common.util.concurrent.Futures.immediateFuture<java.lang.Void?>(null)
            }
        return QueryTaskFutureImpl.Companion.ofDelegate<R?>(
            com.google.common.util.concurrent.Futures.catchingAsync<V?, X?>(
                patternToEval.evalAdaptedForAsync(
                    resolver,
                    getIgnoredSubdirectories(patternToEval.repository),  /* excludedSubdirectories= */
                    com.google.common.collect.ImmutableSet.of<E?>(),
                    com.google.devtools.build.lib.query2.engine.Callback { partialResult: Iterable<Target>? ->
                        val transformedResult: MutableList<ConfiguredTargetValue?> =
                            java.util.ArrayList<ConfiguredTargetValue?>()
                        for (target in partialResult!!) {
                            transformedResult.addAll(getConfiguredTargetsForLabel(target.getLabel()))
                        }
                        callback.process(transformedResult)
                    } as com.google.devtools.build.lib.query2.engine.Callback<Target>,
                    com.google.devtools.build.lib.query2.engine.QueryException::class.java),
                TargetParsingException::class.java,
                reportBuildFileErrorAsyncFunction,
                com.google.common.util.concurrent.MoreExecutors.directExecutor()))
    }

    /**
     * Returns all configured targets in Skyframe with the given label.
     * 
     * 
     * If there are no matches, returns an empty list.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun getConfiguredTargetsForLabel(label: Label?): com.google.common.collect.ImmutableList<ConfiguredTargetValue?> {
        val ans: com.google.common.collect.ImmutableList.Builder<ConfiguredTargetValue?> =
            com.google.common.collect.ImmutableList.builder<ConfiguredTargetValue?>()
        var extraConfiguredTargetKeys: HashSet<ConfiguredTargetKey?>? = null
        for (configurationValue in transitiveConfigurations.values) {
            val configurationKey: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                configurationValue.getKey()
            val targetValue: ConfiguredTargetValue? =
                getValueFromKey(
                    ConfiguredTargetKey.builder()
                        .setLabel(label)
                        .setConfigurationKey(configurationKey)
                        .build()
                )
            if (targetValue == null) {
                continue
            }
            // The configurations might not match if the target's configuration changed due to a
            // transition or trimming. Filter such targets, with one exception: if the target is subject
            // to a non-idempotent rule transition, we have to keep it once if the keys requested above,
            // which never have shouldApplyRuleTransition set to false, don't cover it. This case is rare,
            // so we optimize for it not being hit.
            if (configurationKey != targetValue.getConfiguredTarget().getConfigurationKey()) {
                val targetKey: ConfiguredTargetKey =
                    ConfiguredTargetKey.fromConfiguredTarget(targetValue.getConfiguredTarget())
                if (targetKey.shouldApplyRuleTransition()
                    || (getValueFromKey(
                        ConfiguredTargetKey.builder()
                            .setLabel(label)
                            .setConfigurationKey(targetKey.getConfigurationKey())
                            .build()
                    )
                            != null)
                ) {
                    continue
                }
                if (extraConfiguredTargetKeys == null) {
                    extraConfiguredTargetKeys = HashSet<ConfiguredTargetKey?>()
                }
                if (!extraConfiguredTargetKeys.add(targetKey)) {
                    continue
                }
            }
            ans.add(targetValue)
        }
        val nullConfiguredTarget: ConfiguredTargetValue? = getNullConfiguredTarget(label)
        if (nullConfiguredTarget != null) {
            ans.add(nullConfiguredTarget)
        }
        return ans.build()
    }

    override fun createThreadSafeMutableSet(): ThreadSafeMutableSet<ConfiguredTargetValue?> {
        return ThreadSafeMutableKeyExtractorBackedSetImpl<ConfiguredTargetValue?, ActionLookupKey?>(
            configuredTargetKeyExtractor,
            ConfiguredTargetValue::class.java,
            SkyQueryEnvironment.Companion.DEFAULT_THREAD_COUNT
        )
    }

    fun setActionFilters(actionFilters: AqueryActionFilter?) {
        this.actionFilters = actionFilters
    }

    companion object {
        val AQUERY_FUNCTIONS: com.google.common.collect.ImmutableList<QueryFunction?> = populateAqueryFunctions()
        val FUNCTIONS: com.google.common.collect.ImmutableList<QueryFunction?> = populateFunctions()
        private fun populateFunctions(): com.google.common.collect.ImmutableList<QueryFunction?> {
            return com.google.common.collect.ImmutableList.copyOf<QueryFunction?>(QueryEnvironment.Companion.DEFAULT_QUERY_FUNCTIONS)
        }

        private fun populateAqueryFunctions(): com.google.common.collect.ImmutableList<QueryFunction?> {
            return com.google.common.collect.ImmutableList.of<QueryFunction?>(
                InputsFunction(),
                OutputsFunction(),
                MnemonicFunction()
            )
        }

        private fun getConfiguredTargetKeyImpl(targetValue: ConfiguredTargetValue): ConfiguredTargetKey {
            return ConfiguredTargetKey.fromConfiguredTarget(targetValue.getConfiguredTarget())
        }
    }
}
