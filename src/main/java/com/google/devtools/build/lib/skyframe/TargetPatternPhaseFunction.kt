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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/**
 * Takes a list of target patterns corresponding to a command line and turns it into a set of
 * resolved Targets.
 */
internal class TargetPatternPhaseFunction : SkyFunction {
    @Throws(java.lang.InterruptedException::class)
    override fun compute(key: SkyKey, env: SkyFunction.Environment): TargetPatternPhaseValue? {
        val options: TargetPatternPhaseKey = key.argument() as TargetPatternPhaseKey
        val repositoryMappingValue: RepositoryMappingValue? =
            env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN)) as RepositoryMappingValue?
        if (repositoryMappingValue == null) {
            return null
        }

        // Determine targets to build:
        val failedPatterns: MutableList<String?> = java.util.ArrayList<String?>()
        val expandedPatterns =
            getTargetsToBuild(env, options, repositoryMappingValue.repositoryMapping(), failedPatterns)
        var targets: ResolvedTargets<Target?>? =
            if (env.valuesMissing())
                null
            else
                mergeAll(expandedPatterns, !failedPatterns.isEmpty(), env, options)

        // Record labels before they're expanded. For example, if the build requests a test_suite //foo,
        // record //foo here instead of the tests the suite expands to.
        val nonExpandedLabels: com.google.common.collect.ImmutableSet<Label?>? =
            if (targets == null)
                com.google.common.collect.ImmutableSet.of<Label?>()
            else
                targets.getTargets().stream().map(Target::getLabel)
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())

        // If the --build_tests_only option was specified or we want to run tests, we need to determine
        // the list of targets to test. For that, we remove manual tests and apply the command-line
        // filters. Also, if --build_tests_only is specified, then the list of filtered targets will be
        // set as build list as well.
        var testTargets: ResolvedTargets<Target?>? = null
        if (options.getDetermineTests() || options.getBuildTestsOnly()) {
            testTargets =
                determineTests(
                    env,
                    options.getTargetPatterns(),
                    options.getOffset(),
                    repositoryMappingValue.repositoryMapping(),
                    options.getTestFilter()
                )
            com.google.common.base.Preconditions.checkState(env.valuesMissing() || (testTargets != null))
        }

        val testExpansionKeys: MutableMap<Label?, SkyKey?> = LinkedHashMap<Label?, SkyKey?>()
        if (targets != null) {
            for (target in targets.getTargets()) {
                if (TargetUtils.isTestSuiteRule(target) && options.isExpandTestSuites()) {
                    val label: Label = target.getLabel()
                    val testExpansionKey: SkyKey =
                        TestsForTargetPatternValue.Companion.key(com.google.common.collect.ImmutableSet.of<Label?>(label))
                    testExpansionKeys.put(label, testExpansionKey)
                }
            }
        }
        val expandedTests: SkyframeLookupResult = env.getValuesAndExceptions(testExpansionKeys.values())
        if (env.valuesMissing()) {
            return null
        }

        var filteredTargets: com.google.common.collect.ImmutableSet<Target?>? = targets.getFilteredTargets()
        var testsToRun: com.google.common.collect.ImmutableSet<Target?>? = null
        var testFilteredTargets: com.google.common.collect.ImmutableSet<Target?> =
            com.google.common.collect.ImmutableSet.of<Target?>()

        if (testTargets != null) {
            // Parse the targets to get the tests.
            if (testTargets.getTargets().isEmpty() && !testTargets.getFilteredTargets().isEmpty()) {
                env.getListener()
                    .handle(com.google.devtools.build.lib.events.Event.warn("All specified test targets were excluded by filters"))
            }

            if (options.getBuildTestsOnly()) {
                // Replace original targets to build with test targets, so that only targets that are
                // actually going to be built are loaded in the loading phase. Note that this has a side
                // effect that any test_suite target requested to be built is replaced by the set of *_test
                // targets it represents; for example, this affects the status and the summary reports.
                val allFilteredTargets: MutableSet<Target?> = HashSet<Target?>()
                allFilteredTargets.addAll(targets.getTargets())
                allFilteredTargets.addAll(targets.getFilteredTargets())
                allFilteredTargets.removeAll(testTargets.getTargets())
                allFilteredTargets.addAll(testTargets.getFilteredTargets())
                testFilteredTargets = com.google.common.collect.ImmutableSet.copyOf<Target?>(allFilteredTargets)
                filteredTargets = com.google.common.collect.ImmutableSet.of<Target?>()

                targets =
                    ResolvedTargets.< Target > builder < Target ? > ()
                        .merge(testTargets)
                        .mergeError(targets.hasError())
                        .build()
                if (options.getDetermineTests()) {
                    testsToRun = testTargets.getTargets()
                }
            } else  /*if (determineTests)*/ {
                testsToRun = testTargets.getTargets()
                targets =
                    ResolvedTargets.< Target > builder < Target ? > ()
                        .merge(targets) // Merging in all testsToRun guarantees that targets that will be built (because
                        // they are tests) are not considered to be "filtered out", even if they were
                        // initially filtered out. We can't merge in testTargets because its set of
                        // filteredTargets could include targets that we're building but not testing.
                        .merge(ResolvedTargets.< Target > builder < Target ? > ().addAll(testsToRun).build())
                        .mergeError(testTargets.hasError())
                        .build()
                filteredTargets = targets.getFilteredTargets()
            }
            if (testsToRun != null) {
                // Note that testsToRun can still be null here, if buildTestsOnly && !shouldRunTests.
                check(targets.getTargets().containsAll(testsToRun)) {
                    java.lang.String.format(
                        "Internal consistency check failed; some targets are scheduled for test execution"
                                + " but not for building (%s)",
                        com.google.common.collect.Sets.difference<E?>(testsToRun, targets.getTargets())
                    )
                }
            }
        }

        if (targets.hasError()) {
            env.getListener().handle(com.google.devtools.build.lib.events.Event.warn("Target pattern parsing failed."))
        }

        maybeReportDeprecation(env.getListener(), targets.getTargets())

        val expandedLabelsBuilder: ResolvedTargets.Builder<Label?> = ResolvedTargets.builder()
        val testSuiteExpansions: com.google.common.collect.ImmutableMap.Builder<Label?, com.google.common.collect.ImmutableSet<Label?>?> =
            com.google.common.collect.ImmutableMap.builderWithExpectedSize<Label?, com.google.common.collect.ImmutableSet<Label?>?>(
                testExpansionKeys.size()
            )
        for (target in targets.getTargets()) {
            val label: Label? = target.getLabel()
            if (TargetUtils.isTestSuiteRule(target) && options.isExpandTestSuites()) {
                val expansionKey: SkyKey =
                    com.google.common.base.Preconditions.checkNotNull<SkyKey>(testExpansionKeys.get(label))
                val value: TestsForTargetPatternValue? = expandedTests.get(expansionKey) as TestsForTargetPatternValue?
                if (value == null) {
                    return null
                }
                val testExpansion: ResolvedTargets<Label?> = value.getLabels()
                expandedLabelsBuilder.merge(testExpansion)
                testSuiteExpansions.put(label, testExpansion.getTargets())
            } else {
                expandedLabelsBuilder.add(label)
            }
        }
        val targetLabels: ResolvedTargets<Label?> = expandedLabelsBuilder.build()
        val expandedTargets: ResolvedTargets<Target?>? =
            TestsForTargetPatternFunction.Companion.labelsToTargets(
                env, targetLabels.getTargets(), targetLabels.hasError()
            )
        val testSuiteTargets: MutableSet<Target?> =
            com.google.common.collect.Sets.difference<E?>(targets.getTargets(), expandedTargets.getTargets())
        var testsToRunLabels: com.google.common.collect.ImmutableSet<Label?>? = null
        if (testsToRun != null) {
            testsToRunLabels =
                testsToRun.stream().map<Any?>(Target::getLabel)
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
        }
        val removedTargetLabels: com.google.common.collect.ImmutableSet<Label?> =
            testSuiteTargets.stream().map<Any?>(Target::getLabel)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
        val result: TargetPatternPhaseValue =
            TargetPatternPhaseValue(
                targetLabels.getTargets(),
                testsToRunLabels,
                if (nonExpandedLabels == targetLabels.getTargets())
                    targetLabels.getTargets()
                else
                    nonExpandedLabels,
                targets.hasError(),
                expandedTargets.hasError()
            )

        env.getListener()
            .post(
                TargetParsingCompleteEvent(
                    targets.getTargets(),
                    filteredTargets,
                    testFilteredTargets,
                    options.getTargetPatterns(),
                    expandedTargets.getTargets(),
                    com.google.common.collect.ImmutableList.< E > copyOf < E ? > (failedPatterns),
                    mapOriginalPatternsToLabels(expandedPatterns, targets.getTargets()),
                    testSuiteExpansions.buildOrThrow()
                )
            )
        env.getListener()
            .post(
                LoadingPhaseCompleteEvent(
                    result.getTargetLabels(),
                    removedTargetLabels,
                    repositoryMappingValue.repositoryMapping()
                )
            )
        return result
    }

    /** Represents the expansion of a single target pattern.  */
    internal class ExpandedPattern(pattern: TargetPatternKey?, resolvedTargets: ResolvedTargets<Target?>?) {
        val pattern: TargetPatternKey?
        val resolvedTargets: ResolvedTargets<Target?>?

        init {
            this.resolvedTargets = resolvedTargets
            this.pattern = pattern
            java.util.Objects.requireNonNull<TargetPatternKey?>(pattern, "pattern")
            java.util.Objects.requireNonNull<Any?>(resolvedTargets, "resolvedTargets")
        }

        companion object {
            fun of(pattern: TargetPatternKey?, resolvedTargets: ResolvedTargets<Target?>?): ExpandedPattern {
                return ExpandedPattern(pattern, resolvedTargets)
            }
        }
    }

    companion object {
        /**
         * Emit a warning when a deprecated target is mentioned on the command line.
         * 
         * 
         * Note that this does not stop us from emitting "target X depends on deprecated target Y"
         * style warnings for the same target and it is a good thing; *depending* on a target and
         * *wanting* to build it are different things.
         */
        private fun maybeReportDeprecation(
            eventHandler: ExtendedEventHandler, targets: MutableCollection<Target?>
        ) {
            for (rule in com.google.common.collect.Iterables.filter<Rule?>(targets, Rule::class.java)) {
                if (rule.isAttributeValueExplicitlySpecified("deprecation")) {
                    eventHandler.handle(
                        com.google.devtools.build.lib.events.Event.warn(
                            rule.getLocation(),
                            java.lang.String.format(
                                "target '%s' is deprecated: %s",
                                rule.getLabel(),
                                NonconfigurableAttributeMapper.of(rule).get("deprecation", Type.STRING)
                            )
                        )
                    )
                }
            }
        }

        /**
         * Interprets the command-line arguments by expanding each pattern to targets and populating the
         * list of `failedPatterns`.
         * 
         * @param env the Starlark environment
         * @param options the command-line arguments in structured form
         * @param failedPatterns a list into which failed patterns are added
         */
        @Throws(java.lang.InterruptedException::class)
        private fun getTargetsToBuild(
            env: SkyFunction.Environment,
            options: TargetPatternPhaseKey,
            repoMapping: RepositoryMapping?,
            failedPatterns: MutableList<String?>
        ): MutableList<ExpandedPattern> {
            val parser: TargetPattern.Parser =
                Parser(options.getOffset(), RepositoryName.MAIN, repoMapping)
            val policy: FilteringPolicy? =
                if (options.getBuildManualTests())
                    FilteringPolicies.NO_FILTER
                else
                    FilteringPolicies.FILTER_MANUAL
            val patternSkyKeys: MutableList<TargetPatternKey> =
                java.util.ArrayList<TargetPatternKey>(options.getTargetPatterns().size())
            for (pattern in options.getTargetPatterns()) {
                try {
                    patternSkyKeys.add(
                        TargetPatternValue.Companion.key(SignedTargetPattern.parse(pattern, parser), policy)
                    )
                } catch (e: TargetParsingException) {
                    failedPatterns.add(pattern)
                    // We post a PatternExpandingError here - the pattern could not be parsed, so we don't even
                    // get to run TargetPatternFunction.
                    env.getListener().post(PatternExpandingError.failed(pattern, e.getMessage()))
                    // We generally skip patterns that don't parse. We report a parsing failed exception to the
                    // event bus here, but not in determineTests below, which goes through the same list. Note
                    // that the TargetPatternFunction otherwise reports these events (but only if the target
                    // pattern could be parsed successfully).
                    env.getListener().post(ParsingFailedEvent(pattern, e.getMessage()))
                    try {
                        env.getValueOrThrow<E?>(
                            TargetPatternErrorFunction.Companion.key(e),
                            TargetParsingException::class.java
                        )
                    } catch (ignore: TargetParsingException) {
                        // We ignore this. Keep going is active.
                    }
                    env.getListener()
                        .handle(com.google.devtools.build.lib.events.Event.error("Skipping '" + pattern + "': " + e.getMessage()))
                }
            }

            val resolvedPatterns: SkyframeLookupResult = env.getValuesAndExceptions(patternSkyKeys)
            val expandedPatterns: MutableList<ExpandedPattern> =
                java.util.ArrayList<ExpandedPattern>(patternSkyKeys.size())

            for (pattern in patternSkyKeys) {
                val value: TargetPatternValue?
                try {
                    value =
                        resolvedPatterns.getOrThrow<E?>(
                            pattern,
                            TargetParsingException::class.java
                        ) as TargetPatternValue?
                } catch (e: TargetParsingException) {
                    val rawPattern: String? = pattern.getPattern()
                    val errorMessage: String? = e.getMessage()
                    failedPatterns.add(rawPattern)
                    env.getListener().post(PatternExpandingError.failed(rawPattern, errorMessage))
                    env.getListener()
                        .handle(com.google.devtools.build.lib.events.Event.error("Skipping '" + rawPattern + "': " + errorMessage))
                    continue
                }
                if (value == null) {
                    continue
                }
                // TODO(ulfjack): This is terribly inefficient.
                val asTargets: ResolvedTargets<Target?>? =
                    TestsForTargetPatternFunction.Companion.labelsToTargets(
                        env, value.getTargets().getTargets(), value.getTargets().hasError()
                    )
                if (asTargets == null) {
                    continue
                }
                expandedPatterns.add(ExpandedPattern.Companion.of(pattern, asTargets))
            }

            return expandedPatterns
        }

        /** Merges expansions from all patterns into a single [ResolvedTargets] instance.  */
        @Throws(java.lang.InterruptedException::class)
        private fun mergeAll(
            expandedPatterns: MutableList<ExpandedPattern>,
            hasError: Boolean,
            env: SkyFunction.Environment,
            options: TargetPatternPhaseKey
        ): ResolvedTargets<Target?>? {
            val builder: ResolvedTargets.Builder<Target?> = ResolvedTargets.builder()
            builder.mergeError(hasError)

            for (expansion in expandedPatterns) {
                if (expansion.pattern.isNegative()) {
                    builder.filter(
                        com.google.common.base.Predicates.not<T?>(
                            com.google.common.base.Predicates.`in`<T?>(
                                expansion.resolvedTargets.getTargets()
                            )
                        )
                    )
                } else {
                    builder.merge(expansion.resolvedTargets)
                }
            }

            var result: ResolvedTargets<Target?>? =
                builder.filter(TargetUtils.tagFilter(options.getBuildTargetFilter())).build()
            if (options.getCompileOneDependency()) {
                val environmentBackedRecursivePackageProvider: EnvironmentBackedRecursivePackageProvider =
                    EnvironmentBackedRecursivePackageProvider(env)
                try {
                    result =
                        CompileOneDependencyTransformer(environmentBackedRecursivePackageProvider)
                            .transformCompileOneDependency(env.getListener(), result)
                } catch (e: MissingDepException) {
                    return null
                } catch (e: TargetParsingException) {
                    try {
                        env.getValueOrThrow<E?>(
                            TargetPatternErrorFunction.Companion.key(e),
                            TargetParsingException::class.java
                        )
                    } catch (ignore: TargetParsingException) {
                        // We ignore this. Keep going is active.
                    }
                    env.getListener().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                    return ResolvedTargets.failed()
                }
                if (environmentBackedRecursivePackageProvider.encounteredPackageErrors()) {
                    result = ResolvedTargets.< Target > builder < Target ? > ().merge(result).setError().build()
                }
            }
            return result
        }

        /**
         * Interpret test target labels from the command-line arguments and return the corresponding set
         * of targets, handling the filter flags, and expanding test suites.
         * 
         * @param targetPatterns the list of command-line target patterns specified by the user
         * @param repoMapping the repository mapping to apply to repos in the patterns
         * @param testFilter the test filter
         */
        @Throws(java.lang.InterruptedException::class)
        private fun determineTests(
            env: SkyFunction.Environment,
            targetPatterns: MutableList<String?>,
            offset: PathFragment?,
            repoMapping: RepositoryMapping?,
            testFilter: TestFilter?
        ): ResolvedTargets<Target?>? {
            val parser: TargetPattern.Parser =
                Parser(offset, RepositoryName.MAIN, repoMapping)
            val patternSkyKeys: MutableList<TargetPatternKey> = java.util.ArrayList<TargetPatternKey>()
            for (pattern in targetPatterns) {
                try {
                    patternSkyKeys.add(
                        TargetPatternValue.Companion.key(
                            SignedTargetPattern.parse(pattern, parser), FilteringPolicies.FILTER_TESTS
                        )
                    )
                } catch (e: TargetParsingException) {
                    // Skip.
                }
            }
            val resolvedPatterns: SkyframeLookupResult = env.getValuesAndExceptions(patternSkyKeys)
            if (env.valuesMissing()) {
                return null
            }

            val expandedSuiteKeys: MutableList<SkyKey> = java.util.ArrayList<SkyKey>()
            for (key in patternSkyKeys) {
                val value: TargetPatternValue?
                try {
                    value =
                        resolvedPatterns.getOrThrow<E?>(key, TargetParsingException::class.java) as TargetPatternValue?
                    if (value == null) {
                        BugReport.sendBugReport(
                            java.lang.IllegalStateException(
                                "TargetPatternValue " + key + " was missing, this should never happen"
                            )
                        )
                        return null
                    }
                } catch (e: TargetParsingException) {
                    // Skip.
                    continue
                }
                expandedSuiteKeys.add(TestsForTargetPatternValue.Companion.key(value.getTargets().getTargets()))
            }
            val expandedSuites: SkyframeLookupResult = env.getValuesAndExceptions(expandedSuiteKeys)
            if (env.valuesMissing()) {
                return null
            }

            val testTargetsBuilder: ResolvedTargets.Builder<Target?> = ResolvedTargets.builder()
            var suiteKeyIndex = 0
            for (pattern in patternSkyKeys) {
                val value: TargetPatternValue?
                try {
                    value =
                        resolvedPatterns.getOrThrow<E?>(
                            pattern,
                            TargetParsingException::class.java
                        ) as TargetPatternValue?
                    if (value == null) {
                        BugReport.sendBugReport(
                            java.lang.IllegalStateException(
                                "TargetPatternValue " + pattern + " was missing, this should never happen"
                            )
                        )
                        return null
                    }
                } catch (e: TargetParsingException) {
                    // This was already reported in getTargetsToBuild (maybe merge the two code paths?).
                    continue
                }

                val expandedSuitesValue: TestsForTargetPatternValue? =
                    expandedSuites.get(expandedSuiteKeys.get(suiteKeyIndex++)) as TestsForTargetPatternValue?
                if (expandedSuitesValue == null) {
                    BugReport.logUnexpected("Value for: '%s' was missing, this should never happen", pattern)
                    return null
                }
                if (pattern.isNegative()) {
                    val negativeTargets: ResolvedTargets<Target?>? =
                        TestsForTargetPatternFunction.Companion.labelsToTargets(
                            env,
                            expandedSuitesValue.getLabels().getTargets(),
                            expandedSuitesValue.getLabels().hasError()
                        )
                    testTargetsBuilder.filter(
                        com.google.common.base.Predicates.not<T?>(
                            com.google.common.base.Predicates.`in`<T?>(
                                negativeTargets.getTargets()
                            )
                        )
                    )
                    testTargetsBuilder.mergeError(negativeTargets.hasError())
                } else {
                    val positiveTargets: ResolvedTargets<Target?>? =
                        TestsForTargetPatternFunction.Companion.labelsToTargets(
                            env,
                            expandedSuitesValue.getLabels().getTargets(),
                            expandedSuitesValue.getLabels().hasError()
                        )
                    testTargetsBuilder.addAll(positiveTargets.getTargets())
                    testTargetsBuilder.mergeError(positiveTargets.hasError())
                }
            }

            testTargetsBuilder.filter(testFilter)
            return testTargetsBuilder.build()
        }

        private fun mapOriginalPatternsToLabels(
            expandedPatterns: MutableList<ExpandedPattern>, includedTargets: MutableSet<Target?>
        ): com.google.common.collect.ImmutableSetMultimap<String?, Label?> {
            return expandedPatterns.stream()
                .filter(java.util.function.Predicate { expansion: ExpandedPattern? -> !expansion!!.pattern.isNegative() })
                .collect(
                    com.google.common.collect.ImmutableSetMultimap.flatteningToImmutableSetMultimap<Any?, String?, Any?>(
                        java.util.function.Function { expansion: Any? -> expansion.pattern().getPattern() },
                        java.util.function.Function { expansion: Any? ->
                            expansion.resolvedTargets().getTargets().stream()
                                .filter(includedTargets::contains)
                                .map(Target::getLabel)
                        })
                )
        }
    }
}
