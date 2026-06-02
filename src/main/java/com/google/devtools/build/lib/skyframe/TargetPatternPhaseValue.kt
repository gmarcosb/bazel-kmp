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
 * A value referring to a computed set of resolved targets. This is used for the results of target
 * pattern parsing.
 */
@Immutable
@ThreadSafe
@com.google.common.annotations.VisibleForTesting
class TargetPatternPhaseValue internal constructor(
    targetLabels: com.google.common.collect.ImmutableSet<Label>,
    testsToRunLabels: com.google.common.collect.ImmutableSet<Label>?,
    nonExpandedLabels: com.google.common.collect.ImmutableSet<Label?>?,
    hasError: Boolean,
    hasPostExpansionError: Boolean
) : SkyValue {
    private val targetLabels: com.google.common.collect.ImmutableSet<Label>
    private val testsToRunLabels: com.google.common.collect.ImmutableSet<Label>?
    private val nonExpandedLabels: com.google.common.collect.ImmutableSet<Label?>?
    private val hasError: Boolean
    private val hasPostExpansionError: Boolean

    init {
        this.targetLabels = targetLabels
        this.testsToRunLabels = testsToRunLabels
        this.nonExpandedLabels = nonExpandedLabels
        this.hasError = hasError
        this.hasPostExpansionError = hasPostExpansionError
    }

    @Throws(java.lang.InterruptedException::class)
    fun getTargets(
        eventHandler: ExtendedEventHandler?, packageManager: PackageManager
    ): com.google.common.collect.ImmutableSet<Target?> {
        return getTargetsFromLabels(targetLabels, eventHandler, packageManager)
    }

    fun getNonExpandedLabels(): com.google.common.collect.ImmutableSet<Label?>? {
        return nonExpandedLabels
    }

    @Throws(java.lang.InterruptedException::class)
    fun getTestsToRun(
        eventHandler: ExtendedEventHandler?, packageManager: PackageManager
    ): com.google.common.collect.ImmutableSet<Target?> {
        return getTargetsFromLabels(testsToRunLabels, eventHandler, packageManager)
    }

    fun getTargetLabels(): com.google.common.collect.ImmutableSet<Label> {
        return targetLabels
    }

    fun getTestsToRunLabels(): com.google.common.collect.ImmutableSet<Label>? {
        return testsToRunLabels
    }

    fun hasError(): Boolean {
        return hasError
    }

    fun hasPostExpansionError(): Boolean {
        return hasPostExpansionError
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is TargetPatternPhaseValue) {
            return false
        }
        return this.targetLabels == obj.targetLabels
                && this.testsToRunLabels == obj.testsToRunLabels
                && this.hasError == obj.hasError && this.hasPostExpansionError == obj.hasPostExpansionError
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(
            this.targetLabels,
            this.testsToRunLabels,
            this.hasError,
            this.hasPostExpansionError
        )
    }

    /** The configuration needed to run the target pattern evaluation phase.  */
    @ThreadSafe
    @Immutable
    internal class TargetPatternPhaseKey private constructor(
        targetPatterns: com.google.common.collect.ImmutableList<String?>?,
        offset: PathFragment?,
        val compileOneDependency: Boolean,
        val buildTestsOnly: Boolean,
        val determineTests: Boolean,
        buildTargetFilter: com.google.common.collect.ImmutableList<String?>?,
        val buildManualTests: Boolean,
        val isExpandTestSuites: Boolean,
        testFilter: TestFilter?
    ) : SkyKey {
        private val targetPatterns: com.google.common.collect.ImmutableList<String?>
        private val offset: PathFragment
        private val buildTargetFilter: com.google.common.collect.ImmutableList<String?>
        private val testFilter: TestFilter?

        init {
            this.targetPatterns =
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<String?>>(
                    targetPatterns
                )
            this.offset = com.google.common.base.Preconditions.checkNotNull<PathFragment>(offset)
            this.buildTargetFilter =
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<String?>>(
                    buildTargetFilter
                )
            this.testFilter = testFilter
            if (buildTestsOnly || determineTests) {
                com.google.common.base.Preconditions.checkNotNull<Any?>(testFilter)
            }
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.TARGET_PATTERN_PHASE
        }

        fun getTargetPatterns(): com.google.common.collect.ImmutableList<String?> {
            return targetPatterns
        }

        fun getOffset(): PathFragment {
            return offset
        }

        fun getBuildTargetFilter(): com.google.common.collect.ImmutableList<String?> {
            return buildTargetFilter
        }

        fun getTestFilter(): TestFilter? {
            return testFilter
        }

        override fun toString(): String {
            val result: java.lang.StringBuilder = java.lang.StringBuilder()
            result.append(targetPatterns)
            if (!offset.isEmpty()) {
                result.append(" OFFSET=").append(offset)
            }
            result.append(if (compileOneDependency) " COMPILE_ONE_DEPENDENCY" else "")
            result.append(if (buildTestsOnly) " BUILD_TESTS_ONLY" else "")
            result.append(if (determineTests) " DETERMINE_TESTS" else "")
            result.append(if (this.isExpandTestSuites) " EXPAND_TEST_SUITES" else "")
            result.append(if (testFilter != null) " " + testFilter else "")
            return result.toString()
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(
                targetPatterns,
                offset,
                compileOneDependency,
                buildTestsOnly,
                determineTests,
                buildManualTests,
                this.isExpandTestSuites,
                testFilter
            )
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is TargetPatternPhaseKey) {
                return false
            }
            return obj.targetPatterns == this.targetPatterns
                    && obj.offset == this.offset
                    && obj.compileOneDependency == compileOneDependency && obj.buildTestsOnly == buildTestsOnly && obj.determineTests == determineTests && obj.buildTargetFilter == buildTargetFilter
                    && obj.buildManualTests == buildManualTests && obj.isExpandTestSuites == this.isExpandTestSuites && obj.testFilter == testFilter
        }
    }

    companion object {
        /** Expensive. Results in a Skyframe evaluation.  */
        @Throws(java.lang.InterruptedException::class)
        private fun getTargetsFromLabels(
            labels: MutableCollection<Label>, eventHandler: ExtendedEventHandler?, packageManager: PackageManager
        ): com.google.common.collect.ImmutableSet<Target?> {
            val result: com.google.common.collect.ImmutableSet.Builder<Target?> =
                com.google.common.collect.ImmutableSet.builderWithExpectedSize<Target?>(labels.size())
            for (label in labels) {
                try {
                    result.add(
                        packageManager
                            .getPackage(eventHandler, label.getPackageIdentifier())
                            .getTarget(label.name)
                    )
                } catch (e: NoSuchTargetException) {
                    throw java.lang.IllegalStateException(
                        "Failed to get preloaded package from TargetPatternPhaseValue for " + label, e
                    )
                } catch (e: NoSuchPackageException) {
                    throw java.lang.IllegalStateException(
                        "Failed to get preloaded package from TargetPatternPhaseValue for " + label, e
                    )
                }
            }
            return result.build()
        }

        /** Create a target pattern phase value key.  */
        @ThreadSafe
        fun key(
            targetPatterns: com.google.common.collect.ImmutableList<String?>?,
            offset: PathFragment?,
            compileOneDependency: Boolean,
            buildTestsOnly: Boolean,
            determineTests: Boolean,
            buildTargetFilter: com.google.common.collect.ImmutableList<String?>?,
            buildManualTests: Boolean,
            expandTestSuites: Boolean,
            testFilter: TestFilter?
        ): TargetPatternPhaseKey {
            return TargetPatternPhaseKey(
                targetPatterns,
                offset,
                compileOneDependency,
                buildTestsOnly,
                determineTests,
                buildTargetFilter,
                buildManualTests,
                expandTestSuites,
                testFilter
            )
        }

        /**
         * Creates a new target pattern sky key which represents the given target patterns without
         * attempting to filter them in any way (for example, ignores options such as only loading tests).
         * 
         * @param targetPatterns list of targets to evaluate
         * @param offset relative path to the working directory
         */
        @ThreadSafe
        fun keyWithoutFilters(
            targetPatterns: com.google.common.collect.ImmutableList<String?>?, offset: PathFragment?
        ): SkyKey {
            return TargetPatternPhaseKey(
                targetPatterns,
                offset,
                false,
                false,
                false,
                com.google.common.collect.ImmutableList.of<String?>(),
                false,
                false,
                null
            )
        }
    }
}
