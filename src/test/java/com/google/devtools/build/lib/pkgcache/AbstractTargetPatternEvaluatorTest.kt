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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.cmdline.Label

/**
 * Abstract framework for target pattern evaluation tests. The [TargetPatternEvaluatorTest]
 * contains much of the functionality that might be needed for future tests, and its methods should
 * be extracted here if they are needed by other classes.
 */
abstract class AbstractTargetPatternEvaluatorTest : PackageLoadingTestCase() {
    @kotlin.jvm.JvmField
    protected var parser: TargetPatternPreloader? = null
    @kotlin.jvm.JvmField
    protected var parsingListener: RecordingParsingListener? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun initializeParser() {
        setUpSkyframe(RuleVisibility.PRIVATE)
        parser = skyframeExecutor.newTargetPatternPreloader()
        parsingListener = RecordingParsingListener(reporter)
    }

    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    protected fun parseListKeepGoing(vararg patterns: String?): Pair<MutableSet<Label?>?, Boolean?> {
        val result: ResolvedTargets<Target?> =
            parseTargetPatternList(parser, parsingListener, java.util.Arrays.asList<String?>(*patterns), true)
        return Pair.of(targetsToLabels(result.getTargets()), result.hasError())
    }

    /** Event handler that records all parsing errors.  */
    protected class RecordingParsingListener private constructor(delegate: ExtendedEventHandler?) :
        DelegatingEventHandler(delegate) {
        protected val events: MutableList<Pair<String?, String?>?> = java.util.ArrayList<Pair<String?, String?>?>()

        override fun post(post: Postable?) {
            super.post(post)
            if (post is ParsingFailedEvent) {
                events.add(Pair.of(post.pattern, post.message))
            }
        }

        protected fun assertEmpty() {
            Truth.assertThat(events).isEmpty()
        }
    }

    companion object {
        @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
        protected fun parseTargetPatternList(
            parser: TargetPatternPreloader,
            eventHandler: ExtendedEventHandler?,
            targetPatterns: MutableList<String>,
            keepGoing: Boolean
        ): ResolvedTargets<Target?> {
            return parseTargetPatternList(
                PathFragment.EMPTY_FRAGMENT, parser, eventHandler, targetPatterns, keepGoing
            )
        }

        @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
        protected fun parseTargetPatternList(
            relativeWorkingDirectory: PathFragment?,
            parser: TargetPatternPreloader,
            eventHandler: ExtendedEventHandler?,
            targetPatterns: MutableList<String>,
            keepGoing: Boolean
        ): ResolvedTargets<Target?> {
            val positivePatterns: MutableList<String?> =
                targetPatterns.stream()
                    .map<String?> { s: String? -> if (s.startsWith("-")) s.substring(1) else s }
                    .collect(Collectors.toList())
            val resolvedTargetsMap: MutableMap<String?, MutableCollection<Target?>> =
                parser.preloadTargetPatterns(
                    eventHandler,
                    TargetPattern.mainRepoParser(relativeWorkingDirectory),
                    positivePatterns,
                    keepGoing
                )
            val result: ResolvedTargets.Builder<Target?> = ResolvedTargets.builder()
            for (pattern in targetPatterns) {
                if (pattern.startsWith("-")) {
                    val positivePattern: String = pattern.substring(1)
                    val resolvedTargets: MutableCollection<Target?> = resolvedTargetsMap.get(positivePattern)!!
                    result.filter(
                        com.google.common.base.Predicates.not<T?>(
                            com.google.common.base.Predicates.`in`<T?>(
                                resolvedTargets
                            )
                        )
                    )
                } else {
                    val resolvedTargets = resolvedTargetsMap.get(pattern)
                    result.addAll(resolvedTargets)
                }
            }
            return result.build()
        }

        /**
         * Method converts collection of targets to the new, mutable, lexicographically-ordered set of
         * corresponding labels.
         */
        fun targetsToLabels(targets: Iterable<Target>): MutableSet<Label?> {
            val labels: MutableSet<Label?> = TreeSet<Label?>()
            for (target in targets) {
                labels.add(target.getLabel())
            }
            return labels
        }

        @kotlin.jvm.JvmStatic
        @Throws(LabelSyntaxException::class)
        protected fun labels(vararg labelStrings: String?): MutableSet<Label?> {
            val labels: MutableSet<Label?> = HashSet<Label?>()
            for (labelString in labelStrings) {
                labels.add(Label.parseCanonical(labelString))
            }
            return labels
        }
    }
}
