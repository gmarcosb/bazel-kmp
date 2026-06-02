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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * [QueryEnvironment] that runs queries over the configured target (analysis) graph.
 * 
 * 
 * Aspects are partially supported. Their dependencies appear as implicit dependencies on the
 * targets they're connected to. When using the --experimental_explicit_aspects flag, the aspects
 * themselves are visible as query nodes. See https://github.com/bazelbuild/bazel/issues/16310 for
 * details.
 */
class ConfiguredTargetQueryEnvironment(
    keepGoing: Boolean,
    eventHandler: ExtendedEventHandler?,
    extraFunctions: Iterable<QueryFunction?>?,
    topLevelConfigurations: TopLevelConfigurations?,
    transitiveConfigurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationValue?>?,
    topLevelAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?,
    mainRepoTargetParser: TargetPattern.Parser?,
    pkgPath: PathPackageLocator?,
    walkableGraphSupplier: java.util.function.Supplier<WalkableGraph?>,
    settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>?,
    topLevelArtifactContext: TopLevelArtifactContext?,
    labelPrinter: LabelPrinter?
) : PostAnalysisQueryEnvironment<CqueryNode?>(
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
    private var cqueryOptions: CqueryOptions? = null

    private val topLevelArtifactContext: TopLevelArtifactContext?

    private val configuredTargetKeyExtractor: KeyExtractor<CqueryNode?, ActionLookupKey?>

    private val accessor: ConfiguredTargetAccessor

    override fun getConfiguredTargetKeyExtractor(): KeyExtractor<CqueryNode?, ActionLookupKey?> {
        return configuredTargetKeyExtractor
    }

    init {
        this.accessor =
            ConfiguredTargetAccessor(walkableGraphSupplier.get(), this, topLevelAspects)
        this.configuredTargetKeyExtractor = KeyExtractor { obj: T? -> obj.getLookupKey() }
        this.topLevelArtifactContext = topLevelArtifactContext
    }

    constructor(
        keepGoing: Boolean,
        eventHandler: ExtendedEventHandler?,
        extraFunctions: Iterable<QueryFunction?>?,
        topLevelConfigurations: TopLevelConfigurations?,
        transitiveConfigurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationValue?>?,
        topLevelAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?,
        mainRepoTargetParser: TargetPattern.Parser?,
        pkgPath: PathPackageLocator?,
        walkableGraphSupplier: java.util.function.Supplier<WalkableGraph?>,
        cqueryOptions: CqueryOptions,
        topLevelArtifactContext: TopLevelArtifactContext?,
        labelPrinter: LabelPrinter?
    ) : this(
        keepGoing,
        eventHandler,
        extraFunctions,
        topLevelConfigurations,
        transitiveConfigurations,
        topLevelAspects,
        mainRepoTargetParser,
        pkgPath,
        walkableGraphSupplier,
        cqueryOptions.toSettings(),
        topLevelArtifactContext,
        labelPrinter
    ) {
        this.cqueryOptions = cqueryOptions
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    override fun getDefaultOutputFormatters(
        accessor: TargetAccessor<CqueryNode?>?,
        eventHandler: ExtendedEventHandler?,
        out: java.io.OutputStream?,
        skyframeExecutor: SkyframeExecutor?,
        ruleClassProvider: RuleClassProvider?,
        packageManager: PackageManager?,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
    ): com.google.common.collect.ImmutableList<NamedThreadSafeOutputFormatterCallback<CqueryNode?>?> {
        val aspectResolver: AspectResolver? =
            cqueryOptions.getAspectDeps().createResolver(packageManager, eventHandler)
        return com.google.common.collect.ImmutableList.of<NamedThreadSafeOutputFormatterCallback<CqueryNode?>?>(
            LabelAndConfigurationOutputFormatterCallback(
                eventHandler, cqueryOptions, out, skyframeExecutor, accessor, true, getLabelPrinter()
            ),
            LabelAndConfigurationOutputFormatterCallback(
                eventHandler, cqueryOptions, out, skyframeExecutor, accessor, false, getLabelPrinter()
            ),
            TransitionsOutputFormatterCallback(
                eventHandler,
                cqueryOptions,
                out,
                skyframeExecutor,
                accessor,
                ruleClassProvider,
                getLabelPrinter()
            ),
            ProtoOutputFormatterCallback(
                eventHandler,
                cqueryOptions,
                out,
                skyframeExecutor,
                accessor,
                aspectResolver,
                com.google.devtools.build.lib.query2.cquery.ProtoOutputFormatterCallback.OutputType.BINARY,
                getLabelPrinter()
            ),
            ProtoOutputFormatterCallback(
                eventHandler,
                cqueryOptions,
                out,
                skyframeExecutor,
                accessor,
                aspectResolver,
                com.google.devtools.build.lib.query2.cquery.ProtoOutputFormatterCallback.OutputType.DELIMITED_BINARY,
                labelPrinter
            ),
            ProtoOutputFormatterCallback(
                eventHandler,
                cqueryOptions,
                out,
                skyframeExecutor,
                accessor,
                aspectResolver,
                com.google.devtools.build.lib.query2.cquery.ProtoOutputFormatterCallback.OutputType.TEXT,
                getLabelPrinter()
            ),
            ProtoOutputFormatterCallback(
                eventHandler,
                cqueryOptions,
                out,
                skyframeExecutor,
                accessor,
                aspectResolver,
                com.google.devtools.build.lib.query2.cquery.ProtoOutputFormatterCallback.OutputType.JSON,
                getLabelPrinter()
            ),
            com.google.devtools.build.lib.query2.cquery.BuildOutputFormatterCallback(
                eventHandler, cqueryOptions, out, skyframeExecutor, accessor, getLabelPrinter()
            ),
            GraphOutputFormatterCallback(
                eventHandler,
                cqueryOptions,
                out,
                skyframeExecutor,
                accessor,
                DepsRetriever { kct: CqueryNode? ->
                    getFwdDeps(
                        com.google.common.collect.ImmutableList.of<CqueryNode?>(
                            kct
                        )
                    )
                },
                getLabelPrinter()
            ),
            StarlarkOutputFormatterCallback(
                eventHandler, cqueryOptions, out, skyframeExecutor, accessor, starlarkSemantics
            ),
            FilesOutputFormatterCallback(
                eventHandler, cqueryOptions, out, skyframeExecutor, accessor, topLevelArtifactContext
            )
        )
    }

    val outputFormat: String?
        get() = cqueryOptions.getOutputFormat()

    override fun getAccessor(): ConfiguredTargetAccessor {
        return accessor
    }

    override fun getTargetsMatchingPattern(
        owner: QueryExpression?,
        pattern: String?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<CqueryNode?>
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
                        val transformedResult: MutableList<CqueryNode?> = java.util.ArrayList<CqueryNode?>()
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
     * Returns the [CqueryNode] for the given label and configuration if it exists, else null.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun getConfiguredTarget(
        label: Label?, configuration: BuildConfigurationValue?
    ): CqueryNode? {
        val configurationKey: BuildConfigurationKey? = if (configuration == null) null else configuration.getKey()
        val target: CqueryNode? =
            getValueFromKey(
                ConfiguredTargetKey.builder()
                    .setLabel(label)
                    .setConfigurationKey(configurationKey)
                    .build()
            )
        // The configurations might not match if the target's configuration changed due to a transition
        // or trimming. Filters such targets.
        if (target == null || configurationKey != target.getConfigurationKey()) {
            return null
        }
        return target
    }

    /**
     * Returns the [CqueryNode] for the given key if its value is a supported instance of
     * CqueryNode. This function can only receive keys of node types that the calling logic can
     * support. For example, if the caller does not support handling of AspectKey types of
     * CqueryNodes, then this function should not be called with an AspectKey key.
     */
    @Throws(java.lang.InterruptedException::class)
    override fun getValueFromKey(key: SkyKey?): CqueryNode? {
        val value: SkyValue? = getConfiguredTargetValue(key)
        return when (value) {
            -> configuredTargetValue.getConfiguredTarget()
            -> aspectKey
            null -> null
            else -> throw java.lang.IllegalStateException("unknown value type for CqueryNode")
        }
    }

    /**
     * Returns all configured targets in Skyframe with the given label.
     * 
     * 
     * If there are no matches, returns an empty list.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun getConfiguredTargetsForLabel(label: Label?): com.google.common.collect.ImmutableList<CqueryNode?> {
        val ans: com.google.common.collect.ImmutableList.Builder<CqueryNode?> =
            com.google.common.collect.ImmutableList.builder<CqueryNode?>()
        var extraConfiguredTargetKeys: HashSet<ConfiguredTargetKey?>? = null
        for (configurationValue in transitiveConfigurations.values()) {
            val configurationKey: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                configurationValue.getKey()
            val target: CqueryNode? =
                getValueFromKey(
                    ConfiguredTargetKey.builder()
                        .setLabel(label)
                        .setConfigurationKey(configurationKey)
                        .build()
                )
            if (target == null) {
                continue
            }
            // The configurations might not match if the target's configuration changed due to a
            // transition or trimming. Filter such targets, with one exception: if the target is subject
            // to a non-idempotent rule transition, we have to keep it once if the keys requested above,
            // which never have shouldApplyRuleTransition set to false, don't cover it. This case is rare,
            // so we optimize for it not being hit.
            if (configurationKey != target.getConfigurationKey()) {
                val targetKey: ConfiguredTargetKey = ConfiguredTargetKey.fromConfiguredTarget(target)
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
            ans.add(target)
        }
        val nullConfiguredTarget: CqueryNode? = getNullConfiguredTarget(label)
        if (nullConfiguredTarget != null) {
            ans.add(nullConfiguredTarget)
        }
        return ans.build()
    }

    /**
     * Processes the targets in `targets` with the requested `configuration`
     * 
     * @param pattern the original pattern that `targets` were parsed from. Used for error
     * message.
     * @param targetsFuture the set of [ConfiguredTarget]s whose labels represent the targets
     * being requested.
     * @param configPrefix the configuration to request `targets` in. This can be the
     * configuration's checksum, any prefix of its checksum, or the special identifiers "target"
     * "anyexec", or "null".
     * @param callback the callback to receive the results of this method.
     * @return [QueryTaskCallable] that returns the correctly configured targets.
     */
    fun <T> getConfiguredTargetsForConfigFunction(
        pattern: String?,
        targetsFuture: QueryTaskFuture<ThreadSafeMutableSet<T?>?>,
        configPrefix: String,
        callback: com.google.devtools.build.lib.query2.engine.Callback<CqueryNode?>
    ): QueryTaskCallable<java.lang.Void?> {
        // There's no technical reason other callers beside ConfigFunction can't call this. But they'd
        // need to adjust the error messaging below to not make it config()-specific. Please don't just
        // remove that line: the counter-priority is making error messages as clear, precise, and
        // actionable as possible.
        return QueryTaskCallable {
            val targets: ThreadSafeMutableSet<CqueryNode> =
                targetsFuture.getIfSuccessful() as ThreadSafeMutableSet<CqueryNode>
            val transformedResult: MutableList<CqueryNode?> = java.util.ArrayList<CqueryNode?>()
            var userFriendlyConfigName = true
            for (target in targets) {
                val label: Label? = getCorrectLabel(target)
                var keyedConfiguredTarget: CqueryNode? = null
                when (configPrefix) {
                    "host" -> throw com.google.devtools.build.lib.query2.engine.QueryException(
                        "'host' configuration no longer exists. Use a specific configuration hash"
                                + " instead",
                        ConfigurableQuery.Code.INCORRECT_CONFIG_ARGUMENT_ERROR
                    )

                    "target" -> keyedConfiguredTarget = getTargetConfiguredTarget(label)
                    "null" -> keyedConfiguredTarget = getNullConfiguredTarget(label)
                    "anyexec" -> {
                        val matchingConfigs: com.google.common.collect.ImmutableList<BuildConfigurationValue?> =
                            transitiveConfigurations.values().stream()
                                .filter(BuildConfigurationValue::isExecConfiguration)
                                .sorted(
                                    java.util.Comparator.comparing<BuildConfigurationValue?, Any?>(
                                        BuildConfigurationValue::checksum
                                    )
                                )
                                .collect(com.google.common.collect.ImmutableList.toImmutableList<BuildConfigurationValue?>())
                        if (!matchingConfigs.isEmpty()) {
                            for (cfg in matchingConfigs) {
                                keyedConfiguredTarget = getConfiguredTarget(label, cfg)
                                if (keyedConfiguredTarget != null) {
                                    break
                                }
                            }
                        } else {
                            throw com.google.devtools.build.lib.query2.engine.QueryException(
                                (java.lang.String.format("Unable to identify 'exec' configuration for %s\n", label)
                                        + "config()'s second argument must identify a unique configuration.\n"
                                        + "\n"
                                        + "Valid values:\n"
                                        + " 'target' for the default configuration\n"
                                        + " 'null' for source files (which have no configuration)\n"
                                        + " 'anyexec' for identifying any path to a exec tool configuration\n"
                                        + " an arbitrary configuration's full or short ID\n"
                                        + "\n"
                                        + "A short ID is any prefix of a full ID. cquery shows short IDs. 'bazel "
                                        + "config' shows full IDs.\n"
                                        + "\n"
                                        + "For more help, see https://bazel.build/docs/cquery."),
                                ConfigurableQuery.Code.INCORRECT_CONFIG_ARGUMENT_ERROR
                            )
                        }
                    }

                    else -> {
                        val matchingConfigs: com.google.common.collect.ImmutableList<String?> =
                            transitiveConfigurations.keySet().stream()
                                .filter(java.util.function.Predicate { fullConfig: String? ->
                                    fullConfig.startsWith(
                                        configPrefix
                                    )
                                })
                                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
                        if (matchingConfigs.size() == 1) {
                            keyedConfiguredTarget =
                                getConfiguredTarget(
                                    label,
                                    com.google.common.base.Verify.verifyNotNull<BuildConfigurationValue?>(
                                        transitiveConfigurations.get(matchingConfigs.get(0))
                                    )
                                )
                            userFriendlyConfigName = false
                        } else if (matchingConfigs.size() >= 2) {
                            throw com.google.devtools.build.lib.query2.engine.QueryException(
                                java.lang.String.format(
                                    ("Configuration ID '%s' is ambiguous.\n"
                                            + "'%s' is a prefix of multiple configurations:\n %s\n\n"
                                            + "Use a longer prefix to uniquely identify one configuration."),
                                    configPrefix,
                                    configPrefix,
                                    com.google.common.base.Joiner.on("\n ").join(matchingConfigs)
                                ),
                                ConfigurableQuery.Code.INCORRECT_CONFIG_ARGUMENT_ERROR
                            )
                        } else {
                            throw com.google.devtools.build.lib.query2.engine.QueryException(
                                (java.lang.String.format("Unknown configuration ID '%s'.\n", configPrefix)
                                        + "config()'s second argument must identify a unique configuration.\n"
                                        + "\n"
                                        + "Valid values:\n"
                                        + " 'target' for the default configuration\n"
                                        + " 'null' for source files (which have no configuration)\n"
                                        + " an arbitrary configuration's full or short ID\n"
                                        + "\n"
                                        + "A short ID is any prefix of a full ID. cquery shows short IDs. 'bazel "
                                        + "config' shows full IDs.\n"
                                        + "\n"
                                        + "For more help, see https://bazel.build/docs/cquery."),
                                ConfigurableQuery.Code.INCORRECT_CONFIG_ARGUMENT_ERROR
                            )
                        }
                    }
                }
                if (keyedConfiguredTarget != null) {
                    transformedResult.add(keyedConfiguredTarget)
                }
            }
            if (transformedResult.isEmpty()) {
                throw com.google.devtools.build.lib.query2.engine.QueryException(
                    java.lang.String.format(
                        "No target (in) %s could be found in the %s",
                        pattern,
                        if (userFriendlyConfigName)
                            "'" + configPrefix + "' configuration"
                        else
                            "configuration with checksum '" + configPrefix + "'"
                    ),
                    ConfigurableQuery.Code.TARGET_MISSING
                )
            }
            callback.process(transformedResult)
            null
        }
    }

    /**
     * This method has to exist because [AliasConfiguredTarget.getLabel] returns the label of
     * the "actual" target instead of the alias target. Grr.
     */
    override fun getCorrectLabel(target: CqueryNode): Label? {
        // Dereference any aliases that might be present.
        return target.getOriginalLabel()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getTargetConfiguredTarget(label: Label?): CqueryNode? {
        if (topLevelConfigurations.isTopLevelTarget(label)) {
            return getConfiguredTarget(
                label, topLevelConfigurations.getConfigurationForTopLevelTarget(label)
            )
        } else {
            var toReturn: CqueryNode?
            for (configuration in topLevelConfigurations.getConfigurations()) {
                toReturn = getConfiguredTarget(label, configuration)
                if (toReturn != null) {
                    return toReturn
                }
            }
            return null
        }
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getNullConfiguredTarget(label: Label?): CqueryNode? {
        return getConfiguredTarget(label, null)
    }

    override fun getRuleConfiguredTarget(configuredTarget: CqueryNode?): RuleConfiguredTarget? {
        if (configuredTarget is RuleConfiguredTarget) {
            return configuredTarget
        }
        return null
    }

    override fun getOwningRuleforOutputConfiguredTarget(
        configuredTarget: CqueryNode?
    ): RuleConfiguredTarget? {
        if (configuredTarget is OutputFileConfiguredTarget) {
            return configuredTarget.getGeneratingRule()
        }
        return null
    }

    override fun isAliasConfiguredTarget(configuredTarget: CqueryNode?): Boolean {
        return configuredTarget is AliasConfiguredTarget
    }

    override fun getConfiguration(target: CqueryNode): BuildConfigurationValue? {
        try {
            return if (target.getConfigurationKey() == null)
                null
            else
                graph.getValue(target.getConfigurationKey()) as BuildConfigurationValue?
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException("Unexpected interruption during configured target query", e)
        }
    }

    override fun getConfiguredTargetKey(target: CqueryNode): ActionLookupKey? {
        return target.getLookupKey()
    }

    override fun createThreadSafeMutableSet(): ThreadSafeMutableSet<CqueryNode?> {
        return ThreadSafeMutableKeyExtractorBackedSetImpl<CqueryNode?, ActionLookupKey?>(
            configuredTargetKeyExtractor, CqueryNode::class.java, SkyQueryEnvironment.Companion.DEFAULT_THREAD_COUNT
        )
    }

    companion object {
        /** Common query functions and cquery specific functions.  */
        val FUNCTIONS: com.google.common.collect.ImmutableList<QueryFunction?> = populateFunctions()

        /** Cquery specific functions.  */
        val CQUERY_FUNCTIONS: com.google.common.collect.ImmutableList<QueryFunction?> =
            cqueryFunctions

        private fun populateFunctions(): com.google.common.collect.ImmutableList<QueryFunction?> {
            return com.google.common.collect.ImmutableList.Builder<QueryFunction?>()
                .addAll(QueryEnvironment.Companion.DEFAULT_QUERY_FUNCTIONS)
                .addAll(cqueryFunctions)
                .build()
        }

        private val cqueryFunctions: com.google.common.collect.ImmutableList<QueryFunction?>
            get() = com.google.common.collect.ImmutableList.of<QueryFunction?>(ConfigFunction())
    }
}
