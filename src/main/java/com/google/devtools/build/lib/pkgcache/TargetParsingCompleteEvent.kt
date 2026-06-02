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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.buildeventstream.BuildEventContext

/** This event is fired just after target pattern evaluation is completed.  */
class TargetParsingCompleteEvent(
    targets: MutableCollection<com.google.devtools.build.lib.packages.Target?>,
    filteredTargets: MutableCollection<com.google.devtools.build.lib.packages.Target?>,
    testFilteredTargets: MutableCollection<com.google.devtools.build.lib.packages.Target?>,
    originalTargetPattern: com.google.common.collect.ImmutableList<String?>?,
    expandedTargets: MutableCollection<com.google.devtools.build.lib.packages.Target?>,
    failedTargetPatterns: com.google.common.collect.ImmutableList<String?>?,
    originalPatternsToLabels: com.google.common.collect.ImmutableSetMultimap<String?, Label?>?,
    testSuiteExpansions: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableSet<Label?>?>?
) : BuildEventWithOrderConstraint {
    /** A target-like object that is lighter than a target but has all data needed by callers.  */
    class ThinTarget private constructor(target: com.google.devtools.build.lib.packages.Target) {
        private val label: Label?
        private val ruleClass: String?
        private val targetKind: String?

        init {
            this.label = target.getLabel()
            this.targetKind = target.getTargetKind()
            this.ruleClass =
                if (target is com.google.devtools.build.lib.packages.Rule) (target as com.google.devtools.build.lib.packages.Rule).getRuleClass() else null
        }

        fun isRule(): Boolean {
            return ruleClass != null
        }

        fun getTargetKind(): String? {
            return targetKind
        }

        fun getLabel(): Label? {
            return label
        }

        /** Gets the rule class of this target. Caller must already know it [.isRule].  */
        fun getRuleClass(): String {
            return com.google.common.base.Preconditions.checkNotNull<String>(ruleClass, label)
        }

        fun isTestSuiteRule(): Boolean {
            return isRule() && TargetUtils.isTestSuiteRuleName(getRuleClass())
        }

        fun isNotATestOrTestSuite(): Boolean {
            return !isRule() || (!isTestSuiteRule() && !TargetUtils.isTestRuleName(getRuleClass()))
        }
    }

    private val originalTargetPattern: com.google.common.collect.ImmutableList<String?>
    private val failedTargetPatterns: com.google.common.collect.ImmutableList<String>
    private val targets: com.google.common.collect.ImmutableSet<ThinTarget>
    private val filteredTargets: com.google.common.collect.ImmutableSet<ThinTarget>
    private val testFilteredTargets: com.google.common.collect.ImmutableSet<ThinTarget>
    private val expandedTargets: com.google.common.collect.ImmutableSet<ThinTarget>
    private val originalPatternsToLabels: com.google.common.collect.ImmutableSetMultimap<String?, Label?>
    private val testSuiteExpansions: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableSet<Label?>?>

    init {
        this.targets = asThinTargets(targets)
        this.filteredTargets = asThinTargets(filteredTargets)
        this.testFilteredTargets = asThinTargets(testFilteredTargets)
        this.originalTargetPattern =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<String?>>(
                originalTargetPattern
            )
        this.expandedTargets = asThinTargets(expandedTargets)
        this.failedTargetPatterns =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<String>>(
                failedTargetPatterns
            )
        this.originalPatternsToLabels =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSetMultimap<String?, Label?>>(
                originalPatternsToLabels
            )
        this.testSuiteExpansions =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableSet<Label?>?>>(
                testSuiteExpansions
            )
    }

    fun getOriginalTargetPattern(): com.google.common.collect.ImmutableList<String?> {
        return originalTargetPattern
    }

    fun getFailedTargetPatterns(): com.google.common.collect.ImmutableList<String> {
        return failedTargetPatterns
    }

    /** @return the parsed targets, which will subsequently be loaded
     */
    fun getTargets(): com.google.common.collect.ImmutableSet<ThinTarget> {
        return targets
    }

    fun getLabels(): Iterable<Label?> {
        return com.google.common.collect.Iterables.transform<ThinTarget?, Label?>(
            targets,
            com.google.common.base.Function { obj: ThinTarget? -> obj!!.getLabel() })
    }

    fun getFilteredLabels(): Iterable<Label?> {
        return com.google.common.collect.Iterables.transform<ThinTarget?, Label?>(
            filteredTargets,
            com.google.common.base.Function { obj: ThinTarget? -> obj!!.getLabel() })
    }

    fun getTestFilteredLabels(): Iterable<Label?> {
        return com.google.common.collect.Iterables.transform<ThinTarget?, Label?>(
            testFilteredTargets,
            com.google.common.base.Function { obj: ThinTarget? -> obj!!.getLabel() })
    }

    /** @return the filtered targets (i.e., using -//foo:bar on the command-line)
     */
    fun getFilteredTargets(): com.google.common.collect.ImmutableSet<ThinTarget> {
        return filteredTargets
    }

    /** @return the test-filtered targets, if --build_test_only is in effect
     */
    fun getTestFilteredTargets(): com.google.common.collect.ImmutableSet<ThinTarget> {
        return testFilteredTargets
    }

    /**
     * Returns a mapping from patterns originally passed on the command line to the labels they were
     * expanded to.
     * 
     * 
     * Negative patterns are not included here. Neither are labels of targets that are skipped due
     * to matching a negative pattern (even if they also matched a positive pattern).
     * 
     * 
     * Test suite labels are included here, but not the labels of the tests that the suite expanded
     * to.
     */
    fun getOriginalPatternsToLabels(): com.google.common.collect.ImmutableSetMultimap<String?, Label?> {
        return originalPatternsToLabels
    }

    public override fun getEventId(): BuildEventId {
        return BuildEventIdUtil.targetPatternExpanded(originalTargetPattern)
    }

    public override fun postedAfter(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<E?>(BuildEventIdUtil.buildStartedId())
    }

    public override fun getChildrenEvents(): MutableCollection<BuildEventId?> {
        val childrenBuilder: com.google.common.collect.ImmutableList.Builder<BuildEventId?> =
            com.google.common.collect.ImmutableList.builder<BuildEventId?>()
        for (failedTargetPattern in failedTargetPatterns) {
            childrenBuilder.add(
                BuildEventIdUtil.targetPatternExpanded(
                    com.google.common.collect.ImmutableList.of<E?>(
                        failedTargetPattern
                    )
                )
            )
        }
        for (target in expandedTargets) {
            // Test suites won't produce target configuration and target-complete events, so do not
            // announce here completion as children.
            if (!target.isTestSuiteRule()) {
                childrenBuilder.add(BuildEventIdUtil.targetConfigured(target.getLabel()))
            }
        }
        return childrenBuilder.build()
    }

    public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
        val expanded: PatternExpanded.Builder = PatternExpanded.newBuilder()
        testSuiteExpansions.forEach(
            java.util.function.BiConsumer { suite: Label?, tests: com.google.common.collect.ImmutableSet<Label?>? ->
                expanded
                    .addTestSuiteExpansionsBuilder()
                    .setSuiteLabel(suite.toString())
                    .addAllTestLabels(com.google.common.collect.Collections2.transform<F?, T?>(tests, Label::toString))
            })

        return GenericBuildEvent.protoChaining(this).setExpanded(expanded).build()
    }

    public override fun storeForReplay(): Boolean {
        return true
    }

    companion object {
        private fun asThinTargets(targets: MutableCollection<com.google.devtools.build.lib.packages.Target?>): com.google.common.collect.ImmutableSet<ThinTarget> {
            return targets.stream()
                .map<ThinTarget?>(java.util.function.Function { target: com.google.devtools.build.lib.packages.Target? ->
                    ThinTarget(target)
                }).collect(com.google.common.collect.ImmutableSet.toImmutableSet<ThinTarget?>())
        }
    }
}
