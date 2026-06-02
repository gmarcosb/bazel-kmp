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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionContext

/**
 * Registry that collects spawn strategies and rules about their applicability and makes them
 * available for querying through various registry interfaces.
 * 
 * 
 * An instance of this registry can be created using its [builder][Builder], which is
 * available to Blaze modules during server startup.
 */
class SpawnStrategyRegistry
private constructor(
    mnemonicToStrategies: com.google.common.collect.ImmutableListMultimap<String?, SpawnStrategy?>,
    strategyRegexFilter: StrategyRegexFilter,
    strategyPlatformFilter: StrategyPlatformFilter,
    defaultStrategies: com.google.common.collect.ImmutableList<out SpawnStrategy>,
    mnemonicToRemoteDynamicStrategies: com.google.common.collect.ImmutableMultimap<String?, SandboxedSpawnStrategy?>,
    mnemonicToLocalDynamicStrategies: com.google.common.collect.ImmutableMultimap<String?, SandboxedSpawnStrategy?>,
    remoteLocalFallbackStrategy: AbstractSpawnStrategy?
) : DynamicStrategyRegistry, ActionContext, RemoteLocalFallbackRegistry {
    private val mnemonicToStrategies: com.google.common.collect.ImmutableListMultimap<String?, SpawnStrategy?>
    private val strategyRegexFilter: StrategyRegexFilter
    private val strategyPlatformFilter: StrategyPlatformFilter
    private val defaultStrategies: com.google.common.collect.ImmutableList<out SpawnStrategy>
    private val mnemonicToRemoteDynamicStrategies: com.google.common.collect.ImmutableMultimap<String?, SandboxedSpawnStrategy?>
    private val mnemonicToLocalDynamicStrategies: com.google.common.collect.ImmutableMultimap<String?, SandboxedSpawnStrategy?>
    private val remoteLocalFallbackStrategy: AbstractSpawnStrategy?

    init {
        this.mnemonicToStrategies = mnemonicToStrategies
        this.strategyRegexFilter = strategyRegexFilter
        this.strategyPlatformFilter = strategyPlatformFilter
        this.defaultStrategies = defaultStrategies
        this.mnemonicToRemoteDynamicStrategies = mnemonicToRemoteDynamicStrategies
        this.mnemonicToLocalDynamicStrategies = mnemonicToLocalDynamicStrategies
        this.remoteLocalFallbackStrategy = remoteLocalFallbackStrategy
        logger.atInfo().log("Default strategies: %s", defaultStrategies)
        logger.atInfo().log("Regex filter strategies: %s", strategyRegexFilter)
        logger.atInfo().log("Platform filter strategies: %s", strategyPlatformFilter)
        logger.atInfo().log("Mnemonic strategies: %s", mnemonicToStrategies)
        logger.atInfo().log("Remote strategies: %s", mnemonicToRemoteDynamicStrategies)
        logger.atInfo().log("Local strategies: %s", mnemonicToLocalDynamicStrategies)
        logger.atInfo().log("Fallback strategies: %s", remoteLocalFallbackStrategy)
    }

    /**
     * Returns the strategies applying to the given spawn, in priority order.
     * 
     * 
     * Which strategies are returned is based on the precedence as documented on the construction
     * methods of [this registry&#39;s builder][Builder].
     * 
     * 
     * If the reason for selecting the context is worth mentioning to the user, logs a message
     * using the given [Reporter].
     */
    fun getStrategies(
        spawn: Spawn,
        reporter: com.google.devtools.build.lib.events.EventHandler?
    ): MutableList<out SpawnStrategy?> {
        return strategyPlatformFilter.getStrategies(
            spawn, getStrategies(spawn.getResourceOwner(), spawn.getMnemonic(), reporter)
        )
    }

    /**
     * Returns the strategies applying to the given action, in priority order.
     * 
     * 
     * Which strategies are returned is based on the precedence as documented on the construction
     * methods of [this registry&#39;s builder][Builder].
     * 
     * 
     * If the reason for selecting the context is worth mentioning to the user, logs a message
     * using the given [Reporter].
     * 
     * 
     * NOTE: This method is public for Blaze, Bazel must use `getStrategies(Spawn, EventHandler)`.
     */
    fun getStrategies(
        resourceOwner: ActionExecutionMetadata,
        mnemonic: String?,
        reporter: com.google.devtools.build.lib.events.EventHandler?
    ): MutableList<out SpawnStrategy>? {
        // Don't override test strategies by --strategy_regexp for backwards compatibility.
        if ("TestRunner" != mnemonic) {
            val description: String? = resourceOwner.getProgressMessage()
            if (description != null) {
                val regexStrategies: com.google.common.collect.ImmutableList<out SpawnStrategy> =
                    strategyRegexFilter.getStrategies(mnemonic, description, reporter)
                if (!regexStrategies.isEmpty()) {
                    return regexStrategies
                }
            }
        }
        if (mnemonicToStrategies.containsKey(mnemonic)) {
            return mnemonicToStrategies.get(mnemonic)
        }
        return defaultStrategies
    }

    public override fun notifyUsedDynamic(actionContextRegistry: ActionContext.ActionContextRegistry?) {
        for (strategy in mnemonicToLocalDynamicStrategies.values()) {
            strategy.usedContext(actionContextRegistry)
        }
        for (strategy in mnemonicToRemoteDynamicStrategies.values()) {
            strategy.usedContext(actionContextRegistry)
        }
    }

    public override fun getDynamicSpawnActionContexts(
        spawn: Spawn, dynamicMode: DynamicMode?
    ): com.google.common.collect.ImmutableCollection<SandboxedSpawnStrategy?>? {
        val mnemonicToDynamicStrategies: com.google.common.collect.ImmutableMultimap<String?, SandboxedSpawnStrategy?> =
            if (dynamicMode === DynamicStrategyRegistry.DynamicMode.REMOTE)
                mnemonicToRemoteDynamicStrategies
            else
                mnemonicToLocalDynamicStrategies
        if (mnemonicToDynamicStrategies.containsKey(spawn.getMnemonic())) {
            return strategyPlatformFilter.getStrategies(
                spawn, mnemonicToDynamicStrategies.get(spawn.getMnemonic())
            )
        }
        if (mnemonicToDynamicStrategies.containsKey("")) {
            return strategyPlatformFilter.getStrategies(spawn, mnemonicToDynamicStrategies.get(""))
        }
        return com.google.common.collect.ImmutableList.of<SandboxedSpawnStrategy?>()
    }

    override fun getRemoteLocalFallbackStrategy(spawn: Spawn?): AbstractSpawnStrategy? {
        val strategies: MutableCollection<out Any?> =
            strategyPlatformFilter.getStrategies(
                spawn, com.google.common.collect.Lists.< E > newArrayList < E ? > (remoteLocalFallbackStrategy)
            )
        if (strategies.isEmpty()) {
            return null
        }
        return strategies.getFirst()
    }

    /**
     * Notifies all (non-dynamic) strategies stored in this registry that they are [ ][SpawnStrategy.usedContext].
     */
    fun notifyUsed(actionContextRegistry: ActionContext.ActionContextRegistry?) {
        for (strategy in strategyRegexFilter.getFilterToStrategies().values()) {
            strategy.usedContext(actionContextRegistry)
        }
        for (strategy in mnemonicToStrategies.values()) {
            strategy.usedContext(actionContextRegistry)
        }
        for (strategy in defaultStrategies) {
            strategy.usedContext(actionContextRegistry)
        }
        if (remoteLocalFallbackStrategy != null) {
            remoteLocalFallbackStrategy.usedContext(actionContextRegistry)
        }
    }

    /**
     * Records the list of all spawn strategies that can be returned by the various query methods of
     * this registry to the given reporter.
     */
    fun logSpawnStrategies() {
        for (entry in mnemonicToStrategies.asMap().entrySet()) {
            logger.atInfo().log(
                "MnemonicToStrategyImplementations: \"%s\" = [%s]",
                entry.getKey(), toImplementationNames(entry.getValue())
            )
        }

        for (entry in strategyRegexFilter.getFilterToStrategies().asMap().entrySet()) {
            val value: MutableCollection<SpawnStrategy> = entry.getValue()
            logger.atInfo().log(
                "FilterDescriptionToStrategyImplementations: \"%s\" = [%s]",
                entry.getKey(), toImplementationNames(value)
            )
        }
        for (entry in strategyPlatformFilter.filterToStrategies.asMap().entrySet()) {
            val value: MutableCollection<SpawnStrategy> = entry.getValue()
            logger.atInfo().log(
                "FilterPlatformToStrategyImplementations: \"%s\" = [%s]",
                entry.getKey().getCanonicalForm(), toImplementationNames(value)
            )
        }

        logger.atInfo().log(
            "DefaultStrategyImplementations: [%s]", toImplementationNames(defaultStrategies)
        )

        if (remoteLocalFallbackStrategy != null) {
            logger.atInfo().log(
                "RemoteLocalFallbackImplementation: [%s]",
                remoteLocalFallbackStrategy.getClass().getSimpleName()
            )
        }

        for (entry in mnemonicToRemoteDynamicStrategies.asMap().entrySet()) {
            logger.atInfo().log(
                "MnemonicToRemoteDynamicStrategyImplementations: \"%s\" = [%s]",
                entry.getKey(), toImplementationNames(entry.getValue())
            )
        }

        for (entry in mnemonicToLocalDynamicStrategies.asMap().entrySet()) {
            logger.atInfo().log(
                "MnemonicToLocalDynamicStrategyImplementations: \"%s\" = [%s]",
                entry.getKey(), toImplementationNames(entry.getValue())
            )
        }
    }

    /**
     * Builder collecting the strategies and restrictions thereon for a [SpawnStrategyRegistry].
     * 
     * 
     * To [match a strategy to a spawn][SpawnStrategyRegistry.getStrategies] it needs to
     * be both [registered][.registerStrategy] and its registered command-line identifier
     * has to match [a filter on the spawn&#39;s progress message][.addDescriptionFilter],
     * [a filter on the spawn&#39;s mnemonic][.addMnemonicFilter] or be part of the default
     * strategies (see below).
     * 
     * 
     * **Default strategies** are either [set][.setDefaultStrategies] or, if [.setDefaultStrategies] is not called on this builder, comprised of
     * all registered strategies, in registration order (i.e. the earliest strategy registered will be
     * first in the list of strategies returned by [SpawnStrategyRegistry.getStrategies]).
     */
    class Builder private constructor(
        strategyPolicy: SpawnStrategyPolicy,
        dynamicRemotePolicy: SpawnStrategyPolicy,
        dynamicLocalPolicy: SpawnStrategyPolicy
    ) {
        private val strategyMapper = StrategyMapper()
        private val strategiesInRegistrationOrder: java.util.ArrayList<String?> = java.util.ArrayList<String?>()

        private var explicitDefaultStrategies: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>()

        private val strategyPolicy: SpawnStrategyPolicy
        private val dynamicRemotePolicy: SpawnStrategyPolicy
        private val dynamicLocalPolicy: SpawnStrategyPolicy

        // TODO(schmitt): Using a list and autovalue so as to be able to reverse order while legacy sort
        //  is supported. Can be converted to same as mnemonics once legacy behavior is removed.
        private val filterAndIdentifiers: MutableList<FilterAndIdentifiers?> =
            java.util.ArrayList<FilterAndIdentifiers?>()

        // Using List values here rather than multimaps as there is no need for the latter's
        // functionality: The values are always replaced as a whole, no adding/creation required.
        private val mnemonicToIdentifiers: HashMap<String?, MutableList<String?>?> =
            HashMap<String?, MutableList<String?>?>()
        private val mnemonicToRemoteDynamicIdentifiers: HashMap<String?, MutableList<String?>?> =
            HashMap<String?, MutableList<String?>?>()
        private val mnemonicToLocalDynamicIdentifiers: HashMap<String?, MutableList<String?>?> =
            HashMap<String?, MutableList<String?>?>()
        private val execPlatformFilters: HashMap<Label?, MutableList<String?>?> =
            HashMap<Label?, MutableList<String?>?>()

        private var remoteLocalFallbackStrategyIdentifier: String? = null

        init {
            this.strategyPolicy = strategyPolicy
            this.dynamicRemotePolicy = dynamicRemotePolicy
            this.dynamicLocalPolicy = dynamicLocalPolicy
        }

        /**
         * Adds a filter limiting any spawn whose [ ][com.google.devtools.build.lib.actions.ActionExecutionMetadata.getProgressMessage] matches the regular expression to only use strategies with the given
         * command-line identifiers, in order.
         * 
         * 
         * If multiple filters match the same spawn (including an identical filter) the order of last
         * applicable filter registered by this method will be used.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDescriptionFilter(
            filter: com.google.devtools.build.lib.util.RegexFilter?,
            identifiers: MutableList<String?>
        ): Builder {
            filterAndIdentifiers.add(
                FilterAndIdentifiers(
                    filter,
                    com.google.common.collect.ImmutableList.copyOf<String?>(identifiers)
                )
            )
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecPlatformFilter(execPlatform: Label?, identifiers: MutableList<String?>?): Builder {
            this.execPlatformFilters.put(execPlatform, identifiers)
            return this
        }

        /**
         * Adds a filter limiting any spawn whose [mnemonic][Spawn.getMnemonic]
         * (case-sensitively) matches the given mnemonic to only use strategies with the given
         * command-line identifiers, in order.
         * 
         * 
         * If the same mnemonic is registered multiple times the last such call will take precedence.
         * Or in other words, last one wins.
         * 
         * 
         * Note that if a spawn matches a [registered description][.addDescriptionFilter] that filter will take precedence over any mnemonic-based filters.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addMnemonicFilter(mnemonic: String?, identifiers: MutableList<String?>?): Builder {
            mnemonicToIdentifiers.put(mnemonic, identifiers)
            return this
        }

        /**
         * Sets the strategy names to use in the remote branch of dynamic execution for a set of action
         * mnemonics.
         * 
         * 
         * During execution, each strategy is [asked][SpawnStrategy.canExec] whether it can execute a given Spawn. The first strategy in the
         * list that says so will get the job.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDynamicRemoteStrategies(strategies: MutableMap<String?, MutableList<String?>?>?): Builder {
            mnemonicToRemoteDynamicIdentifiers.putAll(strategies)
            return this
        }

        /**
         * Sets the strategy names to use in the local branch of dynamic execution for a number of
         * action mnemonics.
         * 
         * 
         * During execution, each strategy is [asked][SpawnStrategy.canExec] whether it can execute a given Spawn. The first strategy in the
         * list that says so will get the job.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDynamicLocalStrategies(strategies: MutableMap<String?, MutableList<String?>?>?): Builder {
            mnemonicToLocalDynamicIdentifiers.putAll(strategies)
            return this
        }

        /**
         * Registers a strategy implementation with this collector, distinguishing it from other
         * strategies with the given command-line identifiers (of which at least one is required).
         * 
         * 
         * If multiple strategies are registered with the same command-line identifier the last one
         * so registered will take precedence.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun registerStrategy(strategy: SpawnStrategy?, vararg commandlineIdentifiers: String?): Builder {
            com.google.common.base.Preconditions.checkArgument(
                commandlineIdentifiers.length >= 1, "At least one commandLineIdentifier must be given"
            )
            for (identifier in commandlineIdentifiers) {
                strategyMapper.registerStrategy(identifier, strategy)
                strategiesInRegistrationOrder.add(identifier)
            }
            return this
        }

        /**
         * Explicitly sets the identifiers of default strategies to use if a spawn matches no filters.
         * 
         * 
         * Note that if this method is not called on the builder, all registered strategies are
         * considered default strategies, in registration order. See also the [class][Builder].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setDefaultStrategies(defaultStrategies: MutableList<String?>): Builder {
            // Ensure there are actual strategies and the contents are not empty.
            com.google.common.base.Preconditions.checkArgument(!defaultStrategies.isEmpty())
            com.google.common.base.Preconditions.checkArgument(
                defaultStrategies.stream()
                    .anyMatch(java.util.function.Predicate { strategy: String? -> "" != strategy })
            )
            this.explicitDefaultStrategies = com.google.common.collect.ImmutableList.copyOf<String?>(defaultStrategies)
            return this
        }

        /**
         * Reset the default strategies (see [.setDefaultStrategies]) to the reverse of the order
         * they were registered in.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun resetDefaultStrategies(): Builder {
            this.explicitDefaultStrategies = com.google.common.collect.ImmutableList.of<String?>()
            return this
        }

        /**
         * Sets the commandline identifier of the strategy to be used when falling back from remote to
         * local execution.
         * 
         * 
         * Note that this is an optional setting, if not provided [ ][SpawnStrategyRegistry.getRemoteLocalFallbackStrategy] will return `null`. If the
         * value **is** provided it must match the commandline identifier of a registered strategy
         * (at [build][.build] time).
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRemoteLocalFallbackStrategyIdentifier(commandlineIdentifier: String?): Builder {
            this.remoteLocalFallbackStrategyIdentifier = commandlineIdentifier
            return this
        }

        fun isStrategyRegistered(strategy: String?): Boolean {
            return strategiesInRegistrationOrder.contains(strategy)
        }

        /**
         * Finalizes the construction of the registry.
         * 
         * @throws AbruptExitException if a strategy command-line identifier was used in a filter or the
         * default strategies but no strategy for that identifier was registered
         */
        @Throws(AbruptExitException::class)
        fun build(): SpawnStrategyRegistry {
            val orderedFilterAndIdentifiers: MutableList<FilterAndIdentifiers> =
                com.google.common.collect.Lists.reverse<FilterAndIdentifiers?>(filterAndIdentifiers)

            val filterToIdentifiers: com.google.common.collect.ListMultimap<com.google.devtools.build.lib.util.RegexFilter?, String?> =
                com.google.common.collect.LinkedListMultimap.create<com.google.devtools.build.lib.util.RegexFilter?, String?>()
            val filterToStrategies: com.google.common.collect.ListMultimap<com.google.devtools.build.lib.util.RegexFilter?, SpawnStrategy> =
                com.google.common.collect.LinkedListMultimap.create<com.google.devtools.build.lib.util.RegexFilter?, SpawnStrategy>()
            for (filterAndIdentifier in orderedFilterAndIdentifiers) {
                val filter: com.google.devtools.build.lib.util.RegexFilter? = filterAndIdentifier.filter
                if (!filterToIdentifiers.containsKey(filter)) {
                    filterToIdentifiers.putAll(filter, filterAndIdentifier.identifiers)
                    filterToStrategies.putAll(
                        filter,
                        strategyMapper.toStrategies(filterAndIdentifier.identifiers, "filter " + filter)
                    )
                }
            }

            val platformToStrategies: com.google.common.collect.ImmutableSetMultimap.Builder<Label?, SpawnStrategy?> =
                com.google.common.collect.ImmutableSetMultimap.builder<Label?, SpawnStrategy?>()
            for (entry in execPlatformFilters.entrySet()) {
                val platform: Label = entry.getKey()
                platformToStrategies.putAll(
                    platform,
                    strategyMapper.toStrategies(
                        entry.getValue(), "platform " + platform.getCanonicalForm()
                    )
                )
            }

            val mnemonicToStrategies: com.google.common.collect.ImmutableListMultimap.Builder<String?, SpawnStrategy?> =
                com.google.common.collect.ImmutableListMultimap.Builder<String?, SpawnStrategy?>()
            for (entry in mnemonicToIdentifiers.entrySet()) {
                val mnemonic: String = entry.getKey()
                val sanitizedStrategies: com.google.common.collect.ImmutableList<String> =
                    strategyPolicy.apply(mnemonic, entry.getValue())
                mnemonicToStrategies.putAll(
                    mnemonic, strategyMapper.toStrategies(sanitizedStrategies, "mnemonic " + mnemonic)
                )
            }

            val mnemonicToLocalStrategies: com.google.common.collect.ImmutableListMultimap.Builder<String?, SandboxedSpawnStrategy?> =
                com.google.common.collect.ImmutableListMultimap.Builder<String?, SandboxedSpawnStrategy?>()
            for (entry in mnemonicToLocalDynamicIdentifiers.entrySet()) {
                val mnemonic: String = entry.getKey()
                val sanitizedStrategies: com.google.common.collect.ImmutableList<String> =
                    dynamicLocalPolicy.apply(mnemonic, entry.getValue())
                mnemonicToLocalStrategies.putAll(
                    mnemonic,
                    strategyMapper.toSandboxedStrategies(
                        sanitizedStrategies, "local mnemonic " + mnemonic
                    )
                )
            }

            val mnemonicToRemoteStrategies: com.google.common.collect.ImmutableListMultimap.Builder<String?, SandboxedSpawnStrategy?> =
                com.google.common.collect.ImmutableListMultimap.Builder<String?, SandboxedSpawnStrategy?>()
            for (entry in mnemonicToRemoteDynamicIdentifiers.entrySet()) {
                val mnemonic: String = entry.getKey()
                val sanitizedStrategies: com.google.common.collect.ImmutableList<String> =
                    dynamicRemotePolicy.apply(mnemonic, entry.getValue())
                mnemonicToRemoteStrategies.putAll(
                    mnemonic,
                    strategyMapper.toSandboxedStrategies(
                        sanitizedStrategies, "remote mnemonic " + mnemonic
                    )
                )
            }

            var remoteLocalFallbackStrategy: AbstractSpawnStrategy? = null
            if (remoteLocalFallbackStrategyIdentifier != null) {
                val strategy: SpawnStrategy =
                    strategyMapper.toStrategy(
                        remoteLocalFallbackStrategyIdentifier, "remote fallback strategy"
                    )
                if (strategy !is AbstractSpawnStrategy) {
                    // TODO(schmitt): Check if all strategies can use the same base and remove check if so.
                    throw createExitException(
                        java.lang.String.format(
                            ("'%s' was requested for the remote fallback strategy but is not an"
                                    + " abstract spawn strategy (which is required for remote"
                                    + " fallback execution)."),
                            strategy.getClass().getSimpleName()
                        ),
                        Code.REMOTE_FALLBACK_STRATEGY_NOT_ABSTRACT_SPAWN
                    )
                }

                remoteLocalFallbackStrategy = strategy as AbstractSpawnStrategy
            }

            val defaultStrategies: com.google.common.collect.ImmutableList<out SpawnStrategy>?
            if (explicitDefaultStrategies.isEmpty()) {
                // Use the strategies as registered, in reverse order.
                defaultStrategies =
                    strategyMapper.toStrategies(
                        strategyPolicy.apply(
                            com.google.common.collect.Lists.reverse<String?>(
                                strategiesInRegistrationOrder
                            )
                        ),
                        "implicit default strategies"
                    )
            } else {
                defaultStrategies =
                    strategyMapper.toStrategies(
                        strategyPolicy.apply(explicitDefaultStrategies), "explicit default strategies"
                    )
            }

            return SpawnStrategyRegistry(
                mnemonicToStrategies.build(),
                StrategyRegexFilter(
                    strategyMapper, strategyPolicy, filterToIdentifiers, filterToStrategies
                ),
                StrategyPlatformFilter(platformToStrategies.build()),
                defaultStrategies,
                mnemonicToRemoteStrategies.build(),
                mnemonicToLocalStrategies.build(),
                remoteLocalFallbackStrategy
            )
        }

        @com.google.common.annotations.VisibleForTesting
        @Throws(AbruptExitException::class)
        fun toStrategy(identifier: String?, requestName: Any?): SpawnStrategy {
            return strategyMapper.toStrategy(identifier, requestName)
        }
    }

    /** Filter that applies strategy_regexp while respecting the command's strategy-policy.  */
    private class StrategyRegexFilter(
        strategyMapper: StrategyMapper,
        strategyPolicy: SpawnStrategyPolicy,
        filterToIdentifiers: com.google.common.collect.ListMultimap<com.google.devtools.build.lib.util.RegexFilter?, String?>,
        filterToStrategies: com.google.common.collect.ListMultimap<com.google.devtools.build.lib.util.RegexFilter?, SpawnStrategy>
    ) {
        private val strategyPolicy: SpawnStrategyPolicy
        private val filterToIdentifiers: com.google.common.collect.ListMultimap<com.google.devtools.build.lib.util.RegexFilter?, String?>
        private val filterToStrategies: com.google.common.collect.ListMultimap<com.google.devtools.build.lib.util.RegexFilter?, SpawnStrategy>
        private val strategyMapper: StrategyMapper

        init {
            this.strategyPolicy = strategyPolicy
            this.filterToIdentifiers = filterToIdentifiers
            this.filterToStrategies = filterToStrategies
            this.strategyMapper = strategyMapper
        }

        fun getStrategies(
            mnemonic: String?, description: String?, reporter: com.google.devtools.build.lib.events.EventHandler?
        ): com.google.common.collect.ImmutableList<out SpawnStrategy> {
            for (filterToIdentifiers in com.google.common.collect.Multimaps.asMap<com.google.devtools.build.lib.util.RegexFilter?, String?>(
                filterToIdentifiers
            ).entrySet()) {
                if (filterToIdentifiers.getKey().isIncluded(description)) {
                    if (reporter != null) {
                        // TODO(schmitt): Why is this done here and not after running canExec?
                        reporter.handle(
                            com.google.devtools.build.lib.events.Event.Companion.progress(description + " with context " + filterToIdentifiers.getValue())
                        )
                    }
                    // Apply the policy to the identifiers.
                    val sanitizedStrategies: com.google.common.collect.ImmutableList<String> =
                        strategyPolicy.apply(mnemonic, filterToIdentifiers.getValue())
                    try {
                        val strategies: com.google.common.collect.ImmutableList<out SpawnStrategy> =
                            strategyMapper.toStrategies(
                                sanitizedStrategies, "filter " + filterToIdentifiers.getKey()
                            )
                        if (strategies.isEmpty()) {
                            // If after sanitizing we get the empty list of strategies, we should return null
                            // to indicate that default strategies should be used.
                            return com.google.common.collect.ImmutableList.of<SpawnStrategy?>()
                        }
                        return strategies
                    } catch (e: AbruptExitException) {
                        // We should not reach this code because the mapping to strategies already applied
                        // while building filterToStrategies
                        throw java.lang.IllegalStateException(
                            java.lang.String.format(
                                "Failed to apply policy for to strategies that were already applied for"
                                        + " mnemonic %s and filter %s",
                                mnemonic, filterToIdentifiers.getKey()
                            ),
                            e
                        )
                    }
                }
            }

            // Return the empty list if no filter matches.
            return com.google.common.collect.ImmutableList.of<SpawnStrategy?>()
        }

        fun getFilterToStrategies(): com.google.common.collect.ListMultimap<com.google.devtools.build.lib.util.RegexFilter?, SpawnStrategy> {
            return filterToStrategies
        }

        override fun toString(): String {
            return filterToStrategies.toString()
        }
    }

    private class StrategyPlatformFilter(platformToStrategies: com.google.common.collect.ImmutableSetMultimap<Label?, SpawnStrategy?>) {
        private val platformToStrategies: com.google.common.collect.ImmutableSetMultimap<Label?, SpawnStrategy?>

        init {
            this.platformToStrategies = platformToStrategies
        }

        /**
         * Gets strategies for the given spawn that are allowed by the execution platform.
         * 
         * @param spawn Spawn to pick strategies for. Must have an execution platform.
         * @param candidateStrategies Strategies ordered by priority to pick from. The contents of this
         * list vary depending on the context but are always the result of an initial strategy
         * selection pass. e.g. [SpawnStrategyRegistry.getStrategies],
         * [SpawnStrategyRegistry.getDynamicSpawnActionContexts] and
         * [SpawnStrategyRegistry.getRemoteLocalFallbackStrategy].
         * @return A subset of `candidateStrategies` that are allowed by the spawn's execution
         * platform or all if no restrictions are in place. Order from `candidateStrategies`
         * is preserved.
         */
        fun <T : SpawnStrategy?> getStrategies(spawn: Spawn, candidateStrategies: MutableList<T?>): MutableList<T?> {
            val platformLabel: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                spawn.getExecutionPlatformLabel()
            com.google.common.base.Preconditions.checkNotNull<Any?>(
                platformLabel, "Attempting to spawn action without an execution platform."
            )

            if (platformToStrategies.containsKey(platformLabel)) {
                val allowedStrategies: com.google.common.collect.ImmutableSet<SpawnStrategy?> =
                    platformToStrategies.get(platformLabel)
                val filteredStrategies: MutableList<T?> = java.util.ArrayList<T?>()
                for (strategy in candidateStrategies) {
                    if (allowedStrategies.contains(strategy)) {
                        filteredStrategies.add(strategy)
                    }
                }
                return filteredStrategies
            }

            return candidateStrategies
        }

        fun <T : SpawnStrategy?> getStrategies(
            spawn: Spawn?, candidateStrategies: com.google.common.collect.ImmutableCollection<T?>?
        ): com.google.common.collect.ImmutableCollection<T?> {
            return com.google.common.collect.ImmutableList.copyOf(
                getStrategies(spawn, CopyOnWriteArrayList<E?>(candidateStrategies))
            )
        }

        val filterToStrategies: com.google.common.collect.ImmutableSetMultimap<Label?, SpawnStrategy?>
            get() = platformToStrategies

        override fun toString(): String {
            return platformToStrategies.toString()
        }
    }

    /* Maps the strategy identifier (e.g. "local", "worker"..) to the real strategy. */
    private class StrategyMapper {
        private val identifierToStrategy: MutableMap<String?, SpawnStrategy> = HashMap<String?, SpawnStrategy>()

        fun registerStrategy(identifier: String?, strategy: SpawnStrategy?) {
            identifierToStrategy.put(identifier, strategy)
        }

        @Throws(AbruptExitException::class)
        fun toStrategies(
            identifiers: MutableList<String>,
            requestName: Any?
        ): com.google.common.collect.ImmutableList<SpawnStrategy> {
            val strategies: com.google.common.collect.ImmutableList.Builder<SpawnStrategy?> =
                com.google.common.collect.ImmutableList.builder<SpawnStrategy?>()
            for (identifier in identifiers) {
                if (identifier.isEmpty()) {
                    continue
                }
                strategies.add(toStrategy(identifier, requestName))
            }
            return strategies.build()
        }

        @Throws(AbruptExitException::class)
        fun toStrategy(identifier: String?, requestName: Any?): SpawnStrategy {
            val strategy: SpawnStrategy = identifierToStrategy.get(identifier)
            if (strategy == null) {
                throw createExitException(
                    java.lang.String.format(
                        "'%s' was requested for %s but no strategy with that identifier was registered. "
                                + "Valid values are: [%s]",
                        identifier,
                        requestName,
                        com.google.common.base.Joiner.on(", ").join(identifierToStrategy.keySet())
                    ),
                    Code.STRATEGY_NOT_FOUND
                )
            }
            return strategy
        }

        @Throws(AbruptExitException::class)
        fun toSandboxedStrategies(
            identifiers: MutableList<String>, requestName: Any?
        ): Iterable<out SandboxedSpawnStrategy> {
            val strategies: Iterable<out SpawnStrategy> = toStrategies(identifiers, requestName)
            for (strategy in strategies) {
                if (strategy !is SandboxedSpawnStrategy) {
                    throw createExitException(
                        java.lang.String.format(
                            "'%s' was requested for %s but is not a sandboxed strategy (which is required for"
                                    + " dynamic execution).",
                            strategy.getClass().getSimpleName(), requestName
                        ),
                        Code.DYNAMIC_STRATEGY_NOT_SANDBOXED
                    )
                }
            }

            val sandboxedStrategies:  // Each element of the iterable was checked to fulfil this.
                    Iterable<out SandboxedSpawnStrategy> =
                strategies as Iterable<out SandboxedSpawnStrategy>
            return sandboxedStrategies
        }
    }

    internal class FilterAndIdentifiers(
        filter: com.google.devtools.build.lib.util.RegexFilter?,
        identifiers: com.google.common.collect.ImmutableList<String>?
    ) {
        val filter: com.google.devtools.build.lib.util.RegexFilter?
        val identifiers: com.google.common.collect.ImmutableList<String>?

        init {
            this.identifiers = identifiers
            this.filter = filter
            java.util.Objects.requireNonNull<com.google.devtools.build.lib.util.RegexFilter?>(filter, "filter")
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<String?>?>(
                identifiers,
                "identifiers"
            )
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private val ALLOW_ALL_STRATEGIES: SpawnStrategyPolicy =
            SpawnStrategyPolicy.Companion.create(MnemonicPolicy.getDefaultInstance())

        private fun toImplementationNames(strategies: MutableCollection<*>): String? {
            return strategies.stream()
                .map<String?> { strategy: Any? -> strategy.getClass().getSimpleName() }
                .collect(Collectors.joining(", "))
        }

        /** Returns a new [Builder] suitable for creating instances of SpawnStrategyRegistry.  */
        @com.google.common.annotations.VisibleForTesting
        fun builder(): Builder {
            return com.google.devtools.build.lib.exec.SpawnStrategyRegistry.Builder( /* strategyPolicy= */
                ALLOW_ALL_STRATEGIES,  /* dynamicRemotePolicy= */
                ALLOW_ALL_STRATEGIES,  /* dynamicLocalPolicy= */
                ALLOW_ALL_STRATEGIES
            )
        }

        /** Returns a new [Builder] suitable for creating instances of SpawnStrategyRegistry.  */
        @kotlin.jvm.JvmStatic
        fun builder(strategyPolicyProto: StrategyPolicy): Builder {
            return com.google.devtools.build.lib.exec.SpawnStrategyRegistry.Builder(
                SpawnStrategyPolicy.Companion.create(strategyPolicyProto.getMnemonicPolicy()),
                SpawnStrategyPolicy.Companion.create(strategyPolicyProto.getDynamicRemotePolicy()),
                SpawnStrategyPolicy.Companion.create(strategyPolicyProto.getDynamicLocalPolicy())
            )
        }

        private fun createExitException(message: String?, detailedCode: Code?): AbruptExitException {
            return AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setExecutionOptions(
                            FailureDetails.ExecutionOptions.newBuilder().setCode(detailedCode)
                        )
                        .build()
                )
            )
        }
    }
}
