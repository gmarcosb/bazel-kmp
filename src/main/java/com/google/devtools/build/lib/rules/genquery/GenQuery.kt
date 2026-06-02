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
package com.google.devtools.build.lib.rules.genquery

import com.google.devtools.build.lib.actions.ActionConflictException

/** An implementation of the 'genquery' rule.  */
class GenQuery : RuleConfiguredTargetFactory {
    @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
        val outputArtifact: Artifact = ruleContext.createOutputArtifact()

        // The query string
        val query: String? = ruleContext.attributes().get("expression", Type.STRING)

        val optionsParser: com.google.devtools.common.options.OptionsParser =
            com.google.devtools.common.options.OptionsParser.builder()
                .optionsClasses(QueryOptions::class.java, KeepGoingOption::class.java)
                .allowResidue(false)
                .withConversionContext(
                    Label.RepoContext.of(
                        ruleContext.getRepository(),
                        ruleContext.getRule().getPackageMetadata().repositoryMapping()
                    )
                )
                .build()
        try {
            optionsParser.parse(ruleContext.attributes().get("opts", Types.STRING_LIST))
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            ruleContext.attributeError("opts", "error while parsing query options: " + e.getMessage())
            return null
        }

        // Parsed query options
        val queryOptions: QueryOptions? = optionsParser.getOptions<O?>(QueryOptions::class.java)
        // If you change the list of options here, also change the documentation of genquery.opts in
        // GenQueryRule.java .
        if (optionsParser.getOptions<KeepGoingOption?>(KeepGoingOption::class.java).getKeepGoing()) {
            ruleContext.attributeError("opts", "option --keep_going is not allowed")
            return null
        }
        if (!queryOptions.getUniverseScope().isEmpty()) {
            ruleContext.attributeError("opts", "option --universe_scope is not allowed")
            return null
        }
        if (optionsParser.containsExplicitOption("order_results")) {
            ruleContext.attributeError("opts", "option --order_results is not allowed")
            return null
        }
        if (optionsParser.containsExplicitOption("noorder_results")) {
            ruleContext.attributeError("opts", "option --noorder_results is not allowed")
            return null
        }
        if (optionsParser.containsExplicitOption("order_output")) {
            ruleContext.attributeError("opts", "option --order_output is not allowed")
            return null
        }
        if (optionsParser.containsExplicitOption("experimental_graphless_query")) {
            ruleContext.attributeError("opts", "option --experimental_graphless_query is not allowed")
            return null
        }
        // Genquery should always use AUTO, while build isn't affected by query options, .
        queryOptions.useGraphlessQuery = com.google.devtools.common.options.TriState.AUTO

        // force relative_locations to true so it has a deterministic output across machines.
        queryOptions.setRelativeLocations(true)

        if (!optionsParser.containsExplicitOption("nodep_deps")) {
            // Have GenQuery *not* include "nodep" deps by default. This is an unfortunate divergence from
            // `query` which is necessary to maintain legacy behavior.
            // TODO(b/123122592): Complete the migration and remove this divergence.
            queryOptions.setIncludeNoDepDeps(false)
        }

        val result: GenQueryResult?
        Profiler.instance().profile("GenQuery.executeQuery " + ruleContext.getLabel()).use { c ->
            val scope: MutableList<Label?>? = ruleContext.attributes().get("scope", BuildType.GENQUERY_SCOPE_TYPE_LIST)
            result =
                executeQuery(
                    ruleContext,
                    queryOptions,
                    if (scope != null) com.google.common.collect.ImmutableList.copyOf<Label?>(scope) else com.google.common.collect.ImmutableList.of<Label?>(),
                    query,
                    outputArtifact.getPath().getFileSystem().getDigestFunction().getHashFunction()
                )
        }
        if (result == null || ruleContext.hasErrors()) {
            return null
        }

        if (result.size() > 50000000) {
            logger.atInfo().atMostEvery(1, TimeUnit.SECONDS).log(
                "Genquery %s had large output %s", ruleContext.getLabel(), result.size()
            )
        }
        ruleContext.registerAction(
            QueryResultAction(ruleContext.getActionOwner(), outputArtifact, result)
        )

        val filesToBuild: NestedSet<Artifact?>? = NestedSetBuilder.create(Order.STABLE_ORDER, outputArtifact)
        return RuleConfiguredTargetBuilder(ruleContext)
            .setFilesToBuild(filesToBuild)
            .addProvider(
                RunfilesProvider::class.java,
                RunfilesProvider.simple(
                    Builder(ruleContext.getWorkspaceName())
                        .addTransitiveArtifacts(filesToBuild)
                        .build()
                )
            )
            .addOutputGroup(
                OutputGroupInfo.VALIDATION_TRANSITIVE, NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )
            .build()
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable // assuming no other reference to result
    private class QueryResultAction(owner: ActionOwner?, output: Artifact?, result: GenQueryResult) :
        AbstractFileWriteAction(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), output) {
        private val result: GenQueryResult

        init {
            this.result = result
        }

        public override fun newDeterministicWriter(ctx: ActionExecutionContext?): DeterministicWriter {
            return GenQueryResultWriter(result)
        }

        protected override fun computeKey(
            actionKeyContext: ActionKeyContext?,
            inputMetadataProvider: InputMetadataProvider?,
            fp: Fingerprint?
        ) {
            result.fingerprint(fp)
        }
    }

    /**
     * Provide target pattern evaluation to the query operations using Skyframe dep lookup. For thread
     * safety, we must synchronize access to the SkyFunction.Environment.
     */
    private class SkyframeEnvTargetPatternEvaluator(env: SkyFunction.Environment) : TargetPatternPreloader {
        private val env: SkyFunction.Environment

        init {
            this.env = env
        }

        @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
        public override fun preloadTargetPatterns(
            eventHandler: ExtendedEventHandler?,
            targetParser: TargetPattern.Parser,
            patterns: MutableCollection<String?>,
            keepGoing: Boolean
        ): MutableMap<String?, MutableCollection<Target?>?> {
            com.google.common.base.Preconditions.checkArgument(!keepGoing)
            com.google.common.base.Preconditions.checkArgument(targetParser.getRelativeDirectory().isEmpty())
            var ok = true
            val preloadedPatterns: MutableMap<String?, MutableCollection<Target?>?> =
                com.google.common.collect.Maps.newHashMapWithExpectedSize<String?, MutableCollection<Target?>?>(patterns.size())
            val targetBuilder: com.google.common.collect.ImmutableMap.Builder<TargetPatternKey?, String?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<TargetPatternKey?, String?>(patterns.size())
            for (pattern in patterns) {
                checkValidPatternType(pattern, targetParser)
                targetBuilder.put(
                    TargetPatternValue.key(
                        SignedTargetPattern.parse(pattern, targetParser), FilteringPolicies.NO_FILTER
                    ),
                    pattern
                )
            }
            val patternKeys: com.google.common.collect.ImmutableMap<TargetPatternKey?, String?> =
                targetBuilder.buildOrThrow()
            val packageKeys: MutableSet<SkyKey> = HashSet<SkyKey>()
            val resolvedLabelsMap: MutableMap<String?, ResolvedTargets<Label?>> =
                com.google.common.collect.Maps.newHashMapWithExpectedSize<String?, ResolvedTargets<Label?>>(patterns.size())
            synchronized(this) {
                val patternKeysResult: SkyframeLookupResult = env.getValuesAndExceptions(patternKeys.keySet())
                for (entry in patternKeys.entrySet()) {
                    val patternValue: TargetPatternValue? =
                        patternKeysResult.getOrThrow<E?>(
                            entry.getKey(),
                            TargetParsingException::class.java
                        ) as TargetPatternValue?
                    if (patternValue == null) {
                        ok = false
                    } else {
                        val resolvedLabels: ResolvedTargets<Label?> = patternValue.getTargets()
                        resolvedLabelsMap.put(entry.getValue(), resolvedLabels)
                        for (label in com.google.common.collect.Iterables.concat(
                            resolvedLabels.getTargets(), resolvedLabels.getFilteredTargets()
                        )) {
                            packageKeys.add(label.getPackageIdentifier())
                        }
                    }
                }
            }
            if (!ok) {
                throw SkyframeRestartQueryException()
            }
            val packages: MutableMap<PackageIdentifier?, Package?> =
                com.google.common.collect.Maps.newHashMapWithExpectedSize<PackageIdentifier?, Package?>(packageKeys.size())
            synchronized(this) {
                val packageKeysResult: SkyframeLookupResult = env.getValuesAndExceptions(packageKeys)
                // packageKeys is not mutated, the iteration order is the same.
                for (depKey in packageKeys) {
                    val pkgName: PackageIdentifier? = depKey.argument() as PackageIdentifier?
                    val pkg: Package?
                    try {
                        val packageValue: PackageValue? =
                            packageKeysResult.getOrThrow<E?>(
                                depKey,
                                NoSuchPackageException::class.java
                            ) as PackageValue?
                        if (packageValue == null) {
                            ok = false
                            continue
                        }
                        pkg = packageValue.getPackage()
                    } catch (nspe: NoSuchPackageException) {
                        continue
                    }
                    com.google.common.base.Preconditions.checkNotNull<Any?>(pkg, pkgName)
                    packages.put(pkgName, pkg)
                }
            }
            if (!ok) {
                throw SkyframeRestartQueryException()
            }
            for (entry in resolvedLabelsMap.entrySet()) {
                val pattern: String? = entry.getKey()
                val resolvedLabels: ResolvedTargets<Label?> = resolvedLabelsMap.get(pattern)
                val builder: MutableSet<Target?> =
                    com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<Target?>()
                for (label in resolvedLabels.getTargets()) {
                    builder.add(getExistingTarget(label, packages))
                }
                preloadedPatterns.put(pattern, builder)
            }
            return preloadedPatterns
        }

        companion object {
            private fun getExistingTarget(label: Label, packages: MutableMap<PackageIdentifier?, Package?>): Target {
                try {
                    return packages.get(label.getPackageIdentifier()).getTarget(label.name)
                } catch (e: NoSuchTargetException) {
                    // Unexpected since the label was part of the TargetPatternValue.
                    throw java.lang.IllegalStateException(e)
                }
            }

            @Throws(TargetParsingException::class)
            private fun checkValidPatternType(pattern: String?, parser: TargetPattern.Parser) {
                val type: TargetPattern.Type? = parser.parse(pattern).type
                if (type === TargetPattern.Type.PATH_AS_TARGET) {
                    throw TargetParsingException(
                        java.lang.String.format("couldn't determine target from filename '%s'", pattern),
                        TargetPatterns.Code.CANNOT_DETERMINE_TARGET_FROM_FILENAME
                    )
                } else if (type === TargetPattern.Type.TARGETS_BELOW_DIRECTORY) {
                    throw TargetParsingException(
                        java.lang.String.format("recursive target patterns are not permitted: '%s'", pattern),
                        TargetPatterns.Code.RECURSIVE_TARGET_PATTERNS_NOT_ALLOWED
                    )
                }
            }
        }
    }

    private class GenQueryResultWriter(genQueryResult: GenQueryResult) : DeterministicWriter {
        private val genQueryResult: GenQueryResult

        init {
            this.genQueryResult = genQueryResult
        }

        @Throws(IOException::class)
        override fun writeTo(out: java.io.OutputStream?) {
            genQueryResult.writeTo(out)
        }

        @get:Throws(IOException::class)
        val bytes: ByteString?
            get() = genQueryResult.getBytes()
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        private val QUERY_ENVIRONMENT_FACTORY: QueryEnvironmentFactory = QueryEnvironmentFactory()

        /**
         * DO NOT USE! We should get rid of this method: errors reported directly to this object don't set
         * the error flag in [ConfiguredTarget].
         */
        private fun getEventHandler(ruleContext: RuleContext): ExtendedEventHandler {
            return ruleContext.getAnalysisEnvironment().getEventHandler()
        }

        @Throws(java.lang.InterruptedException::class)
        private fun executeQuery(
            ruleContext: RuleContext,
            queryOptions: QueryOptions,
            scope: com.google.common.collect.ImmutableList<Label?>?,
            query: String?,
            hashFunction: com.google.common.hash.HashFunction?
        ): GenQueryResult? {
            val env: SkyFunction.Environment = ruleContext.getAnalysisEnvironment().getSkyframeEnv()

            val packageProvider: GenQueryPackageProvider?
            try {
                packageProvider = GenQueryPackageProviderFactory.constructPackageMap(env, scope)
                if (packageProvider == null) {
                    return null
                }
            } catch (e: BrokenQueryScopeException) {
                ruleContext.ruleError(e.getMessage())
                return null
            }

            return doQuery(
                queryOptions,
                packageProvider,
                SkyframeEnvTargetPatternEvaluator(env),
                query,
                ruleContext,
                hashFunction
            )
        }

        @Throws(java.lang.InterruptedException::class)
        private fun doQuery(
            queryOptions: QueryOptions,
            packageProvider: GenQueryPackageProvider,
            preloader: TargetPatternPreloader?,
            query: String?,
            ruleContext: RuleContext,
            hashFunction: com.google.common.hash.HashFunction?
        ): GenQueryResult? {
            val queryResult: QueryEvalResult
            val formatter: com.google.devtools.build.lib.query2.query.output.OutputFormatter?
            val targets: AggregateAllOutputFormatterCallback<Target?, *>?
            val graphlessQuery: Boolean
            val queryEnvironment: AbstractBlazeQueryEnvironment<Target?>
            try {
                val settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?> =
                    queryOptions.toSettings()

                formatter =
                    com.google.devtools.build.lib.query2.query.output.OutputFormatters.getFormatter(
                        com.google.devtools.build.lib.query2.query.output.OutputFormatters.defaultFormatters,
                        queryOptions.outputFormat
                    )
                if (formatter == null) {
                    ruleContext.ruleError(
                        java.lang.String.format(
                            "Invalid output format '%s'. Valid values are: %s",
                            queryOptions.outputFormat,
                            com.google.devtools.build.lib.query2.query.output.OutputFormatters.formatterNames(com.google.devtools.build.lib.query2.query.output.OutputFormatters.defaultFormatters)
                        )
                    )
                    return null
                }
                graphlessQuery = formatter is StreamedFormatter
                if (graphlessQuery) {
                    queryOptions.orderOutput = OrderOutput.NO
                } else {
                    // Force results to be deterministic.
                    queryOptions.orderOutput = OrderOutput.FULL
                }

                queryEnvironment =
                    QUERY_ENVIRONMENT_FACTORY.create( /* queryTransitivePackagePreloader= */
                        null,  /* graphFactory= */
                        null,
                        packageProvider,
                        packageProvider,
                        preloader,
                        Parser(
                            PathFragment.EMPTY_FRAGMENT,
                            ruleContext.getRepository(),
                            ruleContext.getRule().getPackageMetadata().repositoryMapping()
                        ),
                        PathFragment.EMPTY_FRAGMENT,  /* keepGoing= */
                        false,
                        ruleContext.attributes().get("strict", Type.BOOLEAN),  /* orderedResults= */
                        !graphlessQuery,
                        UniverseScope.EMPTY,  // Use a single thread to prevent race conditions causing nondeterministic output
                        // (b/127644784). All the packages are already loaded at this point, so there is
                        // no need to start up multiple threads anyway.
                        /* loadingPhaseThreads= */
                        1,  // Passing true is safe because GenQuery passes UniverseScope.EMPTY.
                        /* trackIncrementalState= */
                        true,
                        packageProvider.getValidTargetPredicate(),
                        getEventHandler(ruleContext),
                        settings,  /* extraFunctions= */
                        com.google.common.collect.ImmutableList.of<E?>(),  /* packagePath= */
                        null,  /* useGraphlessQuery= */
                        graphlessQuery,
                        queryOptions.getLabelPrinterLegacy(
                            ruleContext.getAnalysisEnvironment().getStarlarkSemantics()
                        )
                    )
                val expr: QueryExpression = QueryExpression.parse(query, queryEnvironment)
                formatter.verifyCompatible(queryEnvironment, expr)
                targets =
                    if (graphlessQuery && !expr.isTopLevelSomePathFunction)
                        QueryUtil.newLexicographicallySortedTargetAggregator()
                    else
                        QueryUtil.newOrderedAggregateAllOutputFormatterCallback<Target?>(queryEnvironment)
                queryResult = queryEnvironment.evaluateQuery(expr, targets)
            } catch (e: SkyframeRestartQueryException) {
                // Do not emit errors for skyframe restarts. They make output of the ConfiguredTargetFunction
                // inconsistent from run to run, and make detecting legitimate errors more difficult.
                return null
            } catch (e: com.google.devtools.build.lib.query2.engine.QuerySyntaxException) {
                ruleContext.ruleError("query syntax error: " + e.getMessage())
                return null
            } catch (e: com.google.devtools.build.lib.query2.engine.QueryException) {
                ruleContext.ruleError("query failed: " + e.getMessage())
                return null
            } catch (e: IOException) {
                throw java.lang.RuntimeException(e)
            }

            try {
                val compressedOutputRequested: Boolean =
                    ruleContext.attributes().get("compressed_output", Type.BOOLEAN)
                val outputStream: GenQueryOutputStream = GenQueryOutputStream(compressedOutputRequested)
                val result: MutableSet<Target?>? = targets.result
                QueryOutputUtils.output(
                    queryOptions,
                    queryResult,
                    result,
                    formatter,
                    outputStream,
                    queryOptions
                        .getAspectDeps()
                        .createResolver(packageProvider, getEventHandler(ruleContext)),
                    getEventHandler(ruleContext),
                    hashFunction,
                    queryEnvironment.getLabelPrinter()
                )
                outputStream.close()
                return outputStream.getResult()
            } catch (e: ClosedByInterruptException) {
                throw java.lang.InterruptedException(e.getMessage())
            } catch (e: IOException) {
                throw java.lang.RuntimeException(e)
            }
        }
    }
}
