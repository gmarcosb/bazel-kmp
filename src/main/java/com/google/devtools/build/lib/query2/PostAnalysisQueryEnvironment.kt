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
package com.google.devtools.build.lib.query2

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * [QueryEnvironment] that runs queries based on results from the analysis phase.
 * 
 * 
 * This environment can theoretically be used for multiple queries, but currently is only ever
 * used for one over the course of its lifetime. If this ever changed to be used for multiple, the
 * [TargetAccessor] field should be initialized on a per-query basis not a per-environment
 * basis.
 * 
 * 
 * Aspects are followed if [ ][com.google.devtools.build.lib.query2.common.CommonQueryOptions.useAspects] is on.
 */
abstract class PostAnalysisQueryEnvironment<T>(
    keepGoing: Boolean,
    eventHandler: ExtendedEventHandler?,
    extraFunctions: Iterable<QueryFunction?>?,
    protected val topLevelConfigurations: TopLevelConfigurations?,
    transitiveConfigurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationValue?>?,
    mainRepoTargetParser: TargetPattern.Parser,
    pkgPath: PathPackageLocator,
    walkableGraphSupplier: java.util.function.Supplier<WalkableGraph>,
    settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>?,
    labelPrinter: LabelPrinter?
) : AbstractBlazeQueryEnvironment<T?>(
    keepGoing,
    true,
    Rule.ALL_LABELS,
    eventHandler,
    settings,
    extraFunctions,
    labelPrinter
) {
    private val mainRepoTargetParser: TargetPattern.Parser
    private val pkgPath: PathPackageLocator
    private val walkableGraphSupplier: java.util.function.Supplier<WalkableGraph>
    protected var graph: WalkableGraph? = null

    /**
     * Stores every configuration in the transitive closure of the build graph as a map from its
     * user-friendly hash to the configuration itself.
     * 
     * 
     * This is used to find configured targets in, e.g. `somepath` queries. Given `somepath(//foo, //bar)`, cquery finds the configured targets for `//foo` and `//bar` by creating a [ConfiguredTargetKey] from their labels and *some*
     * configuration, then querying the [WalkableGraph] to find the matching configured target.
     * 
     * 
     * Having this map lets cquery choose from all available configurations in the graph,
     * particularly including configurations that aren't the top-level.
     * 
     * 
     * This can also be used in cquery's `config` function to match against explicitly
     * specified configs. This, in particular, is where having user-friendly hashes is invaluable.
     */
    protected val transitiveConfigurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationValue?>?

    protected var resolver: RecursivePackageProviderBackedTargetPatternResolver? = null

    init {
        this.transitiveConfigurations = transitiveConfigurations
        this.mainRepoTargetParser = mainRepoTargetParser
        this.pkgPath = pkgPath
        this.walkableGraphSupplier = walkableGraphSupplier
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    abstract fun getDefaultOutputFormatters(
        accessor: TargetAccessor<T?>?,
        eventHandler: ExtendedEventHandler?,
        outputStream: java.io.OutputStream?,
        skyframeExecutor: SkyframeExecutor?,
        ruleClassProvider: RuleClassProvider?,
        packageManager: PackageManager?,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
    ): com.google.common.collect.ImmutableList<NamedThreadSafeOutputFormatterCallback<T?>?>?

    abstract val outputFormat: String?

    protected abstract val configuredTargetKeyExtractor: KeyExtractor<T?, ActionLookupKey?>?

    @Throws(
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        java.lang.InterruptedException::class,
        IOException::class
    )
    override fun evaluateQuery(
        expr: QueryExpression, callback: ThreadSafeOutputFormatterCallback<T?>?
    ): QueryEvalResult? {
        beforeEvaluateQuery()
        return evaluateQueryInternal(expr, callback)
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    private fun beforeEvaluateQuery() {
        graph = walkableGraphSupplier.get()
        val graphBackedRecursivePackageProvider: GraphBackedRecursivePackageProvider =
            GraphBackedRecursivePackageProvider(
                graph,
                UniverseTargetPattern.all(),
                pkgPath,
                RecursivePkgValueRootPackageExtractor()
            )
        resolver =
            RecursivePackageProviderBackedTargetPatternResolver(
                graphBackedRecursivePackageProvider,
                eventHandler,
                FilteringPolicies.NO_FILTER,
                MultisetSemaphore.unbounded<Any?>(),  /* maxConcurrentGetTargetsTasks= */
                java.util.Optional.empty<Int?>(),
                com.google.devtools.build.lib.skyframe.PackageIdentifierBatchingCallback.Factory { batchResults: SafeBatchCallback<PackageIdentifier?>?, batchSize: Int ->
                    SimplePackageIdentifierBatchingCallback(
                        batchResults,
                        batchSize
                    )
                })
        checkSettings(settings)
    }

    // Check to make sure the settings requested are currently supported by this class
    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    private fun checkSettings(settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>) {
        var settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?> = settings
        if (settings.contains(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_NODEP_DEPS)
            || settings.contains(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.TESTS_EXPRESSION_STRICT)
        ) {
            settings =
                com.google.common.collect.Sets.difference<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>(
                    settings,
                    com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>(
                        com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.ONLY_TARGET_DEPS,
                        com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS
                    )
                )
            throw com.google.devtools.build.lib.query2.engine.QueryException(
                String.format(
                    "The following filter(s) are not currently supported by configured query: %s",
                    settings
                ),
                ConfigurableQuery.Code.FILTERS_NOT_SUPPORTED
            )
        }
    }

    // TODO(bazel-team): It's weird that this untemplated function exists. Fix? Or don't implement?
    @Throws(TargetNotFoundException::class, java.lang.InterruptedException::class)
    override fun getTarget(label: Label): Target {
        try {
            return (walkableGraphSupplier.get().getValue(label.getPackageIdentifier()) as PackageValue)
                .getPackage()
                .getTarget(label.name)
        } catch (e: NoSuchTargetException) {
            throw TargetNotFoundException(e, e.getDetailedExitCode())
        }
    }

    override fun getOrCreate(target: T?): T? {
        return target
    }

    /**
     * This method has to exist because [AliasConfiguredTarget.getLabel] returns the label of
     * the "actual" target instead of the alias target. Grr.
     */
    abstract fun getCorrectLabel(target: T?): Label?

    @Throws(java.lang.InterruptedException::class)
    protected abstract fun getTargetConfiguredTarget(label: Label?): T?

    @Throws(java.lang.InterruptedException::class)
    protected abstract fun getNullConfiguredTarget(label: Label?): T?

    @Throws(java.lang.InterruptedException::class)
    fun getConfiguredTargetValue(key: SkyKey?): SkyValue? {
        return walkableGraphSupplier.get().getValue(key)
    }

    @Throws(java.lang.InterruptedException::class)
    fun getAspectValue(key: SkyKey?): AspectValue? {
        return walkableGraphSupplier.get().getValue(key) as AspectValue?
    }

    @Throws(java.lang.InterruptedException::class)
    private fun isAliasConfiguredTarget(key: ConfiguredTargetKey?): Boolean {
        return AliasProvider.isAlias(
            (getConfiguredTargetValue(key) as ConfiguredTargetValue).getConfiguredTarget()
        )
    }

    protected abstract fun isAliasConfiguredTarget(target: T?): Boolean

    fun getIgnoredSubdirectories(
        repositoryName: RepositoryName?
    ): InterruptibleSupplier<IgnoredSubdirectories?> {
        return InterruptibleSupplier {
            val ignoredSubdirectoriesValue: IgnoredSubdirectoriesValue? =
                walkableGraphSupplier.get()
                    .getValue(IgnoredSubdirectoriesValue.key(repositoryName)) as IgnoredSubdirectoriesValue?
            if (ignoredSubdirectoriesValue == null)
                IgnoredSubdirectories.EMPTY
            else
                ignoredSubdirectoriesValue.asIgnoredSubdirectories()
        }
    }

    @Throws(java.lang.InterruptedException::class)
    protected abstract fun getValueFromKey(key: SkyKey?): T?

    @Throws(TargetParsingException::class)
    protected fun getPattern(pattern: String?): TargetPattern {
        return mainRepoTargetParser.parse(pattern)
    }

    val labelPrinter: LabelPrinter?

    @Throws(java.lang.InterruptedException::class)
    fun getFwdDeps(targets: Iterable<T?>): ThreadSafeMutableSet<T?> {
        val targetsByKey: MutableMap<SkyKey?, T?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<SkyKey?, T?>(
                com.google.common.collect.Iterables.size(targets)
            )
        for (target in targets) {
            targetsByKey.put(getConfiguredTargetKey(target), target)
        }
        val directDeps: MutableMap<SkyKey?, com.google.common.collect.ImmutableList<ClassifiedDependency<T?>?>?> =
            targetifyValues(targetsByKey, graph.getDirectDeps(targetsByKey.keys))
        if (targetsByKey.size != directDeps.size) {
            val missingTargets: Iterable<ConfiguredTargetKey?> =
                com.google.common.collect.Sets.difference<SkyKey?>(targetsByKey.keys, directDeps.keys).stream()
                    .map<ConfiguredTargetKey?>(SKYKEY_TO_CTKEY)
                    .collect(Collectors.toList())
            eventHandler.handle(com.google.devtools.build.lib.events.Event.warn("Targets were missing from graph: " + missingTargets))
        }
        val result: ThreadSafeMutableSet<T?> = createThreadSafeMutableSet()
        for (entry in directDeps.entries) {
            result.addAll(filterFwdDeps(targetsByKey.get(entry.key), entry.value))
        }
        return result
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getFwdDeps(targets: Iterable<T?>, context: QueryExpressionContext<T?>?): ThreadSafeMutableSet<T?> {
        return getFwdDeps(targets)
    }

    private fun filterFwdDeps(
        configTarget: T?, rawFwdDeps: com.google.common.collect.ImmutableList<ClassifiedDependency<T?>?>
    ): com.google.common.collect.ImmutableList<T?> {
        if (settings.isEmpty()) {
            return getDependencies<T?>(rawFwdDeps)
        }
        return getAllowedDeps(configTarget, rawFwdDeps)
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getReverseDeps(targets: Iterable<T?>, context: QueryExpressionContext<T?>?): MutableCollection<T?>? {
        val targetsByKey: MutableMap<SkyKey?, T?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<SkyKey?, T?>(
                com.google.common.collect.Iterables.size(targets)
            )
        for (target in targets) {
            targetsByKey.put(getConfiguredTargetKey(target), target)
        }
        val reverseDepsByKey: MutableMap<SkyKey?, com.google.common.collect.ImmutableList<ClassifiedDependency<T?>?>?> =
            targetifyValues(
                targetsByKey,
                skipDelegatingAncestors(graph.getReverseDeps(targetsByKey.keys)).asMap()
            )
        if (targetsByKey.size != reverseDepsByKey.size) {
            val missingTargets: Iterable<ConfiguredTargetKey?> =
                com.google.common.collect.Sets.difference<SkyKey?>(targetsByKey.keys, reverseDepsByKey.keys).stream()
                    .map<ConfiguredTargetKey?>(SKYKEY_TO_CTKEY)
                    .collect(Collectors.toList())
            eventHandler.handle(com.google.devtools.build.lib.events.Event.warn("Targets were missing from graph: " + missingTargets))
        }
        val reverseDepsByCT: MutableMap<T?, com.google.common.collect.ImmutableList<ClassifiedDependency<T?>?>?> =
            HashMap<T?, com.google.common.collect.ImmutableList<ClassifiedDependency<T?>?>?>()
        for (entry in reverseDepsByKey.entries) {
            reverseDepsByCT.put(targetsByKey.get(entry.key), entry.value)
        }
        return if (reverseDepsByCT.isEmpty()) mutableListOf<T?>() else filterReverseDeps(reverseDepsByCT)
    }

    private fun filterReverseDeps(
        rawReverseDeps: MutableMap<T?, com.google.common.collect.ImmutableList<ClassifiedDependency<T?>?>?>
    ): MutableCollection<T?> {
        val result: MutableSet<T?> = com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<T?>()
        for (targetAndRdeps in rawReverseDeps.entries) {
            val ruleDeps: com.google.common.collect.ImmutableList.Builder<ClassifiedDependency<T?>?> =
                com.google.common.collect.ImmutableList.builder<ClassifiedDependency<T?>?>()
            for (parent in targetAndRdeps.value) {
                val dependency: T? = parent.dependency
                if (parent.dependency is RuleConfiguredTarget
                    && dependencyFilter !== DependencyFilter.ALL_DEPS
                ) {
                    ruleDeps.add(parent)
                } else {
                    result.add(dependency)
                }
            }
            result.addAll(getAllowedDeps(targetAndRdeps.key, ruleDeps.build()))
        }
        return result
    }

    /**
     * Expands any delegating ancestors when computing reverse dependencies.
     * 
     * 
     * The [ConfiguredTargetKey] graph contains *delegation* entries where instead of
     * computing its own value, it delegates to a child with the same labels but a different
     * configuration. This causes problems in reverse dependency traversal because traversal stops at
     * duplicate values. The delegating parent has the same value as the delegate child.
     * 
     * 
     * This method replaces any delegating ancestor in the set of reverse dependencies with the
     * reverse dependencies of the ancestor.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun skipDelegatingAncestors(
        reverseDeps: MutableMap<SkyKey?, Iterable<SkyKey>>
    ): com.google.common.collect.ImmutableListMultimap<SkyKey?, SkyKey?> {
        val result: com.google.common.collect.ImmutableListMultimap.Builder<SkyKey?, SkyKey?> =
            com.google.common.collect.ImmutableListMultimap.builder<SkyKey?, SkyKey?>()
        for (entry in reverseDeps.entries) {
            val child: SkyKey = entry.key
            val rdeps: Iterable<SkyKey> = entry.value
            val unwoundRdeps: MutableSet<SkyKey?>? = unwindReverseDependencyDelegationLayersIfFound(child, rdeps)
            result.putAll(child, if (unwoundRdeps == null) rdeps else unwoundRdeps)
        }
        return result.build()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun unwindReverseDependencyDelegationLayersIfFound(
        child: SkyKey?, rdeps: Iterable<SkyKey>
    ): MutableSet<SkyKey?>? {
        // Most rdeps will not be delegating. Performs an optimistic pass that avoids copying.
        var foundDelegatingRdep = false
        for (rdepKey in rdeps) {
            if (rdepKey.functionName() != SkyFunctions.CONFIGURED_TARGET) {
                continue
            }
            val rdepValue = getValueFromKey(rdepKey)
            if (rdepValue == null) {
                // Cannot find the actual value, possibly because it failed during analysis.
                // TODO: b/324419258 - Add a test for this case
                continue
            }
            val actualParentKey: ActionLookupKey = getConfiguredTargetKey(rdepValue)
            if (actualParentKey.equals(child)) {
                // The parent has the same value as the child because it is delegating.
                foundDelegatingRdep = true
                break
            }
        }
        if (!foundDelegatingRdep) {
            return null
        }
        val logicalParents: HashSet<SkyKey?> = HashSet<SkyKey?>()
        unwindReverseDependencyDelegationLayers(child, rdeps, logicalParents)
        return logicalParents
    }

    @Throws(java.lang.InterruptedException::class)
    private fun unwindReverseDependencyDelegationLayers(
        child: SkyKey?, rdeps: Iterable<SkyKey>, output: MutableSet<SkyKey?>
    ) {
        // Checks the value of each rdep to see if it is delegating to `child`. If so, fetches its rdeps
        // and processes those, applying the same expansion as needed.
        for (rdepKey in rdeps) {
            if (rdepKey.functionName() != SkyFunctions.CONFIGURED_TARGET) {
                output.add(rdepKey)
                continue
            }
            val rdepValue = getValueFromKey(rdepKey)
            if (rdepValue == null) {
                // Cannot find the actual value, possibly because it failed during analysis.
                // TODO: b/324419258 - Add a test for this case
                continue
            }
            val actualParentKey: ActionLookupKey = getConfiguredTargetKey(rdepValue)
            if (!actualParentKey.equals(child)) {
                output.add(rdepKey)
                continue
            }
            // Otherwise `rdepKey` is delegating to child and needs to be unwound.
            val rdepParents: Iterable<SkyKey> =
                graph.getReverseDeps(com.google.common.collect.ImmutableList.of<SkyKey?>(rdepKey)).get(rdepKey)
            // Applies this recursively in case there are multiple layers of delegation.
            unwindReverseDependencyDelegationLayers(child, rdepParents, output)
        }
    }

    /**
     * @param target source target
     * @param deps next level of deps to filter
     */
    private fun getAllowedDeps(
        target: T?,
        deps: MutableCollection<ClassifiedDependency<T?>?>
    ): com.google.common.collect.ImmutableList<T?> {
        // It's possible to query on a target that's configured in an exec configuration. In those
        // cases if --notool_deps is turned on, we only allow reachable targets that are ALSO in an
        // exec config. This is somewhat counterintuitive and subject to change in the future but seems
        // like the best option right now.
        var deps = deps
        if (settings.contains(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.ONLY_TARGET_DEPS)) {
            val currentConfig: BuildConfigurationValue? = getConfiguration(target)
            if (currentConfig != null && currentConfig.isToolConfiguration()) {
                deps =
                    deps.stream()
                        .filter { dep: ClassifiedDependency<T?>? ->
                            getConfiguration(dep!!.dependency) != null
                                    && getConfiguration(dep.dependency).isToolConfiguration()
                        }
                        .collect(Collectors.toList())
            } else {
                deps =
                    deps.stream()
                        .filter { dep: ClassifiedDependency<T?>? ->  // We include source files, which have null configuration, even though
                            // they can also appear on exec-configured attributes like genrule#tools.
                            // While this may not be strictly correct, it's better to overapproximate
                            // than underapproximate the results.
                            getConfiguration(dep!!.dependency) == null
                                    || !getConfiguration(dep.dependency).isToolConfiguration()
                        }
                        .collect(Collectors.toList())
            }
        }
        if (settings.contains(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS)) {
            deps =
                deps.stream().filter { dep: ClassifiedDependency<T?>? -> !dep!!.implicit }.collect(Collectors.toList())
        }
        return getDependencies<T?>(deps)
    }

    protected abstract fun getRuleConfiguredTarget(target: T?): RuleConfiguredTarget?

    /**
     * If `target` is an [OutputFileConfiguredTarget], return the rule that produces it.
     * Else returns null.
     */
    protected abstract fun getOwningRuleforOutputConfiguredTarget(target: T?): RuleConfiguredTarget?

    /**
     * Returns targetified dependencies wrapped as [ClassifiedDependency] objects which include
     * information on if the target is an implicit or explicit dependency.
     * 
     * 
     * A target may have toolchain dependencies and aspects attached to its deps that declare their
     * own dependencies through private attributes. All of these are considered implicit dependencies
     * of the target.
     * 
     * @param parent Parent target that knows about its attribute-attached implicit deps. If this is
     * null, that is a signal from the caller that all dependencies should be considered implicit.
     * @param dependencies dependencies to targetify
     * @param knownCtDeps the keys of configured target deps already added to the deps list. Outside
     * callers should pass an empty set. This is used for recursive calls to prevent aspect and
     * toolchain deps from duplicating the target's direct deps.
     * @param resolvedAspectClasses aspect classes that have already been examined for dependencies.
     * Aspects can add dependencies through privately declared label-based attributes. Aspects may
     * also propagate down the target's deps. So if an aspect of type C is attached to target T
     * that depends on U and V, the aspect may depend on more type C aspects attached to U and V
     * that themselves depend on type C aspects attached to U and V's deps and so on. Since C
     * defines the aspect's deps, all of those aspect instances have the same deps, which makes
     * examinining each of them down T's transitive deps very wasteful. This parameter lets us
     * avoid that redundancy.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun targetifyValues(
        parent: T?,
        dependencies: Iterable<SkyKey>,
        knownCtDeps: MutableSet<SkyKey?>,
        resolvedAspectClasses: MutableSet<AspectClass?>
    ): com.google.common.collect.ImmutableList<ClassifiedDependency<T?>?> {
        var implicitDeps: MutableCollection<ConfiguredTargetKey?>? = null
        if (parent != null) {
            val ruleConfiguredTarget: RuleConfiguredTarget? = getRuleConfiguredTarget(parent)
            if (ruleConfiguredTarget != null) {
                implicitDeps = ruleConfiguredTarget.getImplicitDeps()
            }
        }

        val explicitAspects: Boolean =
            settings.containsAll(
                com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>(
                    com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.INCLUDE_ASPECTS,
                    com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.EXPLICIT_ASPECTS
                )
            )

        val values: com.google.common.collect.ImmutableList.Builder<ClassifiedDependency<T?>?> =
            com.google.common.collect.ImmutableList.builder<ClassifiedDependency<T?>?>()
        // TODO(bazel-team): The end-goal approach is to treat aspects and toolchains as
        // first-class query nodes just like targets. In other words, let query expressions reference
        // them (they also have identifying labels) and make the graph connections between targets,
        // aspects, and toolchains explicit. That would permit more detailed queries and eliminate the
        // per-key-type special casing below.
        // This is being experimentally implemented in phases. Currently support for aspects has been
        // implemented behind the --experimental_explicit_aspects flag.
        // See https://github.com/bazelbuild/bazel/issues/16310 for details.
        for (key in dependencies) {
            if (knownCtDeps.contains(key)) {
                continue
            }
            if (key.functionName() == SkyFunctions.CONFIGURED_TARGET
                || (explicitAspects && key.functionName() == SkyFunctions.ASPECT)
            ) {
                val dependency = getValueFromKey(key)
                if (dependency == null && keepGoing) {
                    eventHandler.handle(
                        com.google.devtools.build.lib.events.Event.warn(
                            String.format(
                                ("Dependency %s is unavaillable for querying. This may be because this query"
                                        + " sets --keep_going, which continues analysis even some of the build"
                                        + " graph  fails to analyze. For more accurate results ensure this"
                                        + " request can build."),
                                key
                            )
                        )
                    )
                    continue
                }
                // If --keep_going wasn't applied and a build node is missing, something's wrong with the
                // build graph.
                com.google.common.base.Preconditions.checkState(
                    dependency != null,
                    ("query-requested node '%s' was unavailable in the query environment graph. If you come"
                            + " across this error, please file an issue at https://github.com/bazelbuild/bazel"
                            + " or contact the bazel configurability team."),
                    key
                )

                val implicitDep: Boolean
                if (key.argument() !is ConfiguredTargetKey) {
                    // All non-configured target deps are implicit.
                    implicitDep = true
                } else if (parent != null && isAliasConfiguredTarget(parent)) {
                    // Aliases have only one configured target dep: the ":actual" parameter.
                    implicitDep = false
                } else if (parent != null && getOwningRuleforOutputConfiguredTarget(parent) != null) {
                    // An output file's generating target is an explicit dep.
                    implicitDep =
                        getRuleConfiguredTarget(dependency) != null
                                && !getRuleConfiguredTarget(dependency)
                            .equals(getOwningRuleforOutputConfiguredTarget(parent))
                } else if (implicitDeps == null) {
                    // No set of implicit deps available. Assume they're all implicit. This implies the parent
                    // isn't a rule configured target.
                    com.google.common.base.Verify.verify(parent !is RuleConfiguredTarget)
                    implicitDep = true
                } else {
                    // Check both the original guess key and the second correct key. In the case of the
                    // target platform, Util.findImplicitDeps also uses the original guess key.
                    implicitDep =
                        implicitDeps.contains(key)
                                || implicitDeps.contains(getConfiguredTargetKey(dependency))
                }

                values.add(ClassifiedDependency<T?>(dependency, implicitDep))
                knownCtDeps.add(key)
            } else if (settings.contains(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.INCLUDE_ASPECTS)
                && key.functionName() == SkyFunctions.ASPECT
            ) {
                com.google.common.base.Preconditions.checkState(!settings.contains(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.EXPLICIT_ASPECTS))
                if (resolvedAspectClasses.contains((key as AspectKey).getAspectClass())) {
                    continue
                }
                // When an aspect is attached to an alias configured target, it bypasses standard dependency
                // resolution and just Skyframe-loads the same aspect for the alias' referent. That means
                // the original aspect's attribute deps aren't Skyframe-resolved through AspectFunction's
                // usual call to ConfiguredTargetFunction.computeDependencies, so graph.getDirectDeps()
                // won't include them. So we defer "resolving" the aspect class to the non-alias version,
                // which properly reflects all dependencies. See AspectFunction for details.
                if (!isAliasConfiguredTarget((key as AspectKey).getBaseConfiguredTargetKey())) {
                    // Make sure we don't examine aspects of this type again. This saves us from unnecessarily
                    // traversing a target's transitive deps because it propagates an aspect down those deps.
                    // The deps added by the aspect are a function of the aspect's class, not the target it's
                    // attached to. And they can't be configured because aspects have no UI for overriding
                    // attribute defaults. So it's sufficient to examine only a single instance of a given
                    // aspect class. This has real memory and performance consequences: see b/163052263.
                    // Note the aspect could attach *another* aspect type to its deps. That will still get
                    // examined through the recursive call.
                    resolvedAspectClasses.add((key as AspectKey).getAspectClass())
                }
                values.addAll(
                    targetifyValues(null, graph.getDirectDeps(key), knownCtDeps, resolvedAspectClasses)
                )
            } else if (key.functionName() == SkyFunctions.TOOLCHAIN_RESOLUTION) {
                values.addAll(
                    targetifyValues(null, graph.getDirectDeps(key), knownCtDeps, resolvedAspectClasses)
                )
            }
        }
        return values.build()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun targetifyValues(
        fromTargetsByKey: MutableMap<SkyKey?, T?>, input: MutableMap<SkyKey?, out Iterable<SkyKey?>?>
    ): MutableMap<SkyKey?, com.google.common.collect.ImmutableList<ClassifiedDependency<T?>?>?> {
        val result: MutableMap<SkyKey?, com.google.common.collect.ImmutableList<ClassifiedDependency<T?>?>?> =
            HashMap<SkyKey?, com.google.common.collect.ImmutableList<ClassifiedDependency<T?>?>?>()
        for (entry in input.entries) {
            val fromKey: SkyKey? = entry.key
            result.put(
                fromKey,
                targetifyValues(
                    fromTargetsByKey.get(fromKey),
                    entry.value,  /* knownCtDeps= */
                    HashSet<SkyKey?>(),  /* resolvedAspectClasses= */
                    HashSet<AspectClass?>()
                )
            )
        }
        return result
    }

    /** A class to store a dependency with some information.  */
    private class ClassifiedDependency<T>(
        var dependency: T?, // True if this dependency is attached implicitly.
        var implicit: Boolean
    ) {
        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("implicit", implicit)
                .add("dependency", dependency)
                .toString()
        }
    }

    protected abstract fun getConfiguration(target: T?): BuildConfigurationValue?

    protected abstract fun getConfiguredTargetKey(target: T?): ActionLookupKey

    @Throws(java.lang.InterruptedException::class)
    override fun getTransitiveClosure(
        targets: ThreadSafeMutableSet<T?>?, context: QueryExpressionContext<T?>?
    ): ThreadSafeMutableSet<T?>? {
        return SkyQueryUtils.getTransitiveClosure<T?>(
            targets,
            GetFwdDeps { targets1: Iterable<T?>? -> getFwdDeps(targets1!!, context) },
            createThreadSafeMutableSet()
        )
    }

    override fun buildTransitiveClosure(
        caller: QueryExpression?, targetNodes: ThreadSafeMutableSet<T?>?, maxDepth: OptionalInt?
    ) {
        // TODO(bazel-team): implement this. Just needed for error-checking.
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getNodesOnPath(
        from: T?,
        to: T?,
        context: QueryExpressionContext<T?>?
    ): com.google.common.collect.ImmutableList<T?>? {
        return SkyQueryUtils.getNodesOnPath<T?, ActionLookupKey?>(
            from,
            to,
            GetFwdDeps { targets: Iterable<T?>? -> getFwdDeps(targets!!, context) },
            java.util.function.Function { element: T? -> this.configuredTargetKeyExtractor.extractKey(element) })
    }

    override fun createUniquifier(): Uniquifier<T?> {
        return UniquifierImpl<T?, ActionLookupKey?>(this.configuredTargetKeyExtractor)
    }

    override fun createMinDepthUniquifier(): MinDepthUniquifier<T?> {
        return MinDepthUniquifierImpl<T?, ActionLookupKey?>(
            this.configuredTargetKeyExtractor, SkyQueryEnvironment.Companion.DEFAULT_THREAD_COUNT
        )
    }

    /** Target patterns are resolved on the fly so no pre-work to be done here.  */
    override fun preloadOrThrow(caller: QueryExpression?, patterns: MutableCollection<String?>?) {}

    @get:Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    val transitiveLoadFilesHelper: TransitiveLoadFilesHelper<T?>?
        get() {
            throw com.google.devtools.build.lib.query2.engine.QueryException(
                "buildfiles() doesn't make sense for the configured target graph",
                ConfigurableQuery.Code.BUILDFILES_FUNCTION_NOT_SUPPORTED
            )
        }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    override fun getSiblingTargetsInPackage(target: T?): MutableCollection<T?>? {
        throw com.google.devtools.build.lib.query2.engine.QueryException(
            "siblings() not supported for post analysis queries",
            ConfigurableQuery.Code.SIBLINGS_FUNCTION_NOT_SUPPORTED
        )
    }

    override fun close() {}

    /** A wrapper class for the set of top-level configurations in a query.  */
    class TopLevelConfigurations(topLevelTargetsAndConfigurations: MutableCollection<TargetAndConfiguration>) {
        /** A map of non-null configured top-level targets sorted by configuration checksum.  */
        private val nonNulls: com.google.common.collect.ImmutableMap<Label?, BuildConfigurationValue?>

        /**
         * `nonNulls` may often have many duplicate values in its value set so we store a sorted
         * set of all the non-null configurations here.
         */
        private val nonNullConfigs: com.google.common.collect.ImmutableSortedSet<BuildConfigurationValue?>

        /** A list of null configured top-level targets.  */
        private val nulls: com.google.common.collect.ImmutableList<Label?>

        init {
            val nonNullsBuilder: com.google.common.collect.ImmutableMap.Builder<Label?, BuildConfigurationValue?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<Label?, BuildConfigurationValue?>(
                    topLevelTargetsAndConfigurations.size
                )
            val nullsBuilder: com.google.common.collect.ImmutableList.Builder<Label?> =
                com.google.common.collect.ImmutableList.Builder<Label?>()
            for (targetAndConfiguration in topLevelTargetsAndConfigurations) {
                if (targetAndConfiguration.getConfiguration() == null) {
                    nullsBuilder.add(targetAndConfiguration.getLabel())
                } else {
                    nonNullsBuilder.put(
                        targetAndConfiguration.getLabel(), targetAndConfiguration.getConfiguration()
                    )
                }
            }
            nonNulls = nonNullsBuilder.buildOrThrow()
            nonNullConfigs =
                com.google.common.collect.ImmutableSortedSet.copyOf<BuildConfigurationValue?>(
                    java.util.Comparator.comparing<Any?, Any?>(BuildConfigurationValue::checksum), nonNulls.values
                )
            nulls = nullsBuilder.build()
        }

        fun isTopLevelTarget(label: Label?): Boolean {
            return nonNulls.containsKey(label) || nulls.contains(label)
        }

        // This method returns the configuration of a top-level target if it's not null-configured and
        // otherwise returns null (signifying it is null configured).
        fun getConfigurationForTopLevelTarget(label: Label?): BuildConfigurationValue? {
            com.google.common.base.Preconditions.checkArgument(
                isTopLevelTarget(label),
                "Attempting to get top-level configuration for non-top-level target %s.",
                label
            )
            return nonNulls.get(label)
        }

        val configurations: Iterable<BuildConfigurationValue>?
            get() {
                if (nulls.isEmpty()) {
                    return nonNullConfigs
                } else {
                    return com.google.common.collect.Iterables.concat<BuildConfigurationValue?>(
                        nonNullConfigs,
                        mutableListOf<BuildConfigurationValue?>(null)
                    )
                }
            }
    }

    companion object {
        private val SKYKEY_TO_CTKEY: java.util.function.Function<SkyKey?, ConfiguredTargetKey?> =
            java.util.function.Function { skyKey: SkyKey? -> skyKey.argument() as ConfiguredTargetKey? }

        private fun <T> getDependencies(
            classifiedDependencies: MutableCollection<ClassifiedDependency<T?>?>
        ): com.google.common.collect.ImmutableList<T?> {
            return classifiedDependencies.stream()
                .map<T?> { dep: ClassifiedDependency<T?>? -> dep!!.dependency }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<T?>())
        }
    }
}
