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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/** Skyframe-based target pattern parsing.  */
class SkyframeTargetPatternEvaluator(skyframeExecutor: SkyframeExecutor) : TargetPatternPreloader {
    private val skyframeExecutor: SkyframeExecutor

    init {
        this.skyframeExecutor = skyframeExecutor
    }

    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    public override fun preloadTargetPatterns(
        eventHandler: ExtendedEventHandler,
        mainRepoTargetParser: TargetPattern.Parser?,
        patterns: MutableCollection<String>,
        keepGoing: Boolean
    ): MutableMap<String?, MutableCollection<Target?>?> {
        val resultBuilder: com.google.common.collect.ImmutableMap.Builder<String?, MutableCollection<Target?>?> =
            com.google.common.collect.ImmutableMap.builder<String?, MutableCollection<Target?>?>()
        val patternLookups: MutableList<PatternLookup> = java.util.ArrayList<PatternLookup>()
        val allKeys: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
        for (pattern in patterns) {
            com.google.common.base.Preconditions.checkArgument(!pattern.startsWith("-"))
            val patternLookup =
                createPatternLookup(mainRepoTargetParser, eventHandler, pattern, keepGoing)
            if (patternLookup == null) {
                resultBuilder.put(pattern, com.google.common.collect.ImmutableSet.of<Target?>())
            } else {
                patternLookups.add(patternLookup)
                allKeys.add(patternLookup.skyKey)
            }
        }

        val result: EvaluationResult<SkyValue?> =
            skyframeExecutor.targetPatterns(
                allKeys, SkyframeExecutor.Companion.DEFAULT_THREAD_COUNT, keepGoing, eventHandler
            )
        val catastrophe: java.lang.Exception? = result.getCatastrophe()
        if (catastrophe != null) {
            com.google.common.base.Throwables.throwIfInstanceOf<X?>(catastrophe, TargetParsingException::class.java)
            com.google.common.base.Throwables.throwIfUnchecked(catastrophe)
            throw wrapException(catastrophe, result)
        }
        val walkableGraph: WalkableGraph =
            com.google.common.base.Preconditions.checkNotNull<WalkableGraph>(result.getWalkableGraph(), result)
        for (patternLookup in patternLookups) {
            val key: SkyKey? = patternLookup.skyKey
            val resultValue: SkyValue? = result.get(key)
            if (resultValue != null) {
                try {
                    val resolvedTargets =
                        patternLookup.process(eventHandler, resultValue, walkableGraph, keepGoing)
                    resultBuilder.put(patternLookup.pattern, resolvedTargets)
                } catch (e: TargetParsingException) {
                    if (!keepGoing) {
                        throw e
                    }
                    eventHandler.handle(createPatternParsingError(e, patternLookup.pattern))
                    eventHandler.post(PatternExpandingError.skipped(patternLookup.pattern, e.getMessage()))
                    resultBuilder.put(patternLookup.pattern, com.google.common.collect.ImmutableSet.of<Target?>())
                }
            } else {
                val rawPattern = patternLookup.pattern
                val error: com.google.devtools.build.skyframe.ErrorInfo? = result.errorMap().get(key)
                if (error == null) {
                    if (keepGoing) {
                        BugReport.sendBugReport(
                            java.lang.IllegalStateException(
                                "No error for a non-catastrophic keep-going build: " + key + ", " + result
                            )
                        )
                    }
                    continue
                }
                val errorMessage: String?
                val targetParsingException: TargetParsingException?
                if (error.getException() != null) {
                    // This exception could be a TargetParsingException for a target pattern, a
                    // NoSuchPackageException for a label (or package wildcard), or potentially a lower-level
                    // exception if there is a bug in error handling.
                    val exception: java.lang.Exception? = error.getException()
                    errorMessage = exception.getMessage()
                    if (exception is TargetParsingException) {
                        targetParsingException = exception
                    } else {
                        targetParsingException = wrapException(exception, key)
                    }
                } else {
                    com.google.common.base.Preconditions.checkState(
                        !error.getCycleInfo().isEmpty(),
                        "No exception or cycle %s %s %s",
                        key,
                        error,
                        result
                    )
                    errorMessage = "cycles detected during target parsing"
                    targetParsingException =
                        TargetParsingException(errorMessage, TargetPatterns.Code.CYCLE)
                    skyframeExecutor
                        .getCyclesReporter()
                        .reportCycles(error.getCycleInfo(), key, eventHandler)
                }
                if (keepGoing) {
                    eventHandler.handle(createPatternParsingError(targetParsingException, rawPattern))
                    eventHandler.post(PatternExpandingError.skipped(rawPattern, errorMessage))
                } else {
                    eventHandler.post(PatternExpandingError.failed(patternLookup.pattern, errorMessage))
                    throw targetParsingException
                }
                resultBuilder.put(patternLookup.pattern, com.google.common.collect.ImmutableSet.of<Target?>())
            }
        }
        return resultBuilder.buildOrThrow()
    }

    private abstract class PatternLookup(val pattern: String?, skyKey: SkyKey?) {
        private val skyKey: SkyKey?

        init {
            this.skyKey = skyKey
        }

        @Throws(java.lang.InterruptedException::class, TargetParsingException::class)
        abstract fun process(
            eventHandler: ExtendedEventHandler?,
            value: SkyValue?,
            walkableGraph: WalkableGraph?,
            keepGoing: Boolean
        ): MutableCollection<Target?>?
    }

    private class NormalLookup(targetPattern: String?, key: TargetPatternKey?) : PatternLookup(targetPattern, key) {
        private val resultBuilder: TargetPatternsResultBuilder

        init {
            this.resultBuilder = TargetPatternsResultBuilder()
        }

        @Throws(java.lang.InterruptedException::class, TargetParsingException::class)
        override fun process(
            eventHandler: ExtendedEventHandler?,
            value: SkyValue?,
            walkableGraph: WalkableGraph?,
            keepGoing: Boolean
        ): MutableCollection<Target?>? {
            val resultValue: TargetPatternValue = value as TargetPatternValue
            val results: ResolvedTargets<Label?> = resultValue.getTargets()
            resultBuilder.addLabelsOfPositivePattern(results)
            return resultBuilder.build(walkableGraph)
        }
    }

    private class SimpleLookup(pattern: String?, key: PackageIdentifier?, targetPattern: TargetPattern) :
        PatternLookup(pattern, key) {
        private val targetPattern: TargetPattern

        private constructor(pattern: String?, key: TargetPatternKey) : this(
            pattern,
            key.getParsedPattern().getDirectory(),
            key.getParsedPattern()
        )

        init {
            this.targetPattern = targetPattern
        }

        @Throws(java.lang.InterruptedException::class, TargetParsingException::class)
        override fun process(
            eventHandler: ExtendedEventHandler?,
            value: SkyValue,
            walkableGraph: WalkableGraph?,
            keepGoing: Boolean
        ): MutableCollection<Target?>? {
            val pkg: Package = (value as PackageValue).getPackage()
            val resolver: RecursivePackageProviderBackedTargetPatternResolver =
                RecursivePackageProviderBackedTargetPatternResolver(
                    PackageBackedRecursivePackageProvider(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(pkg.getPackageIdentifier(), pkg)
                    ),
                    eventHandler,
                    FilteringPolicies.NO_FILTER,  /* packageSemaphore= */
                    null,  /* maxConcurrentGetTargetsTasks= */
                    java.util.Optional.empty<T?>(),
                    { SimplePackageIdentifierBatchingCallback() })
            val result: AtomicReference<MutableCollection<Target?>?> = AtomicReference<MutableCollection<Target?>?>()
            try {
                targetPattern.eval(
                    resolver,  /* ignoredSubdirectories= */
                    { IgnoredSubdirectories.EMPTY },  /* excludedSubdirectories= */
                    com.google.common.collect.ImmutableSet.of<E?>(),
                    { partialResult ->
                        result.set(
                            if (partialResult is MutableCollection<*>)
                                partialResult as MutableCollection<Target?>
                            else
                                com.google.common.collect.ImmutableSet.copyOf(partialResult)
                        )
                    },
                    MarkerRuntimeException::class.java
                )
            } catch (e: ProcessPackageDirectoryException) {
                throw java.lang.IllegalStateException(
                    "PackageBackedRecursivePackageProvider doesn't throw for " + targetPattern, e
                )
            } catch (e: InconsistentFilesystemException) {
                throw java.lang.IllegalStateException(
                    "PackageBackedRecursivePackageProvider doesn't throw for " + targetPattern, e
                )
            }
            return result.get()
        }
    }

    companion object {
        private fun wrapException(exception: java.lang.Exception, debugging: Any?): TargetParsingException {
            if (exception is NoSuchPackageException) {
                // Transform NoSuchPackageException into TargetParsingException to avoid triggering
                // non-fatal bug reports on user errors (e.g. broken BUILD files).
                return TargetParsingException(
                    exception.getMessage(), exception, exception.getDetailedExitCode()
                )
            }
            if (exception is DetailedIOException) {
                return TargetParsingException(
                    exception.getMessage(), exception, exception.getDetailedExitCode()
                )
            }
            BugReport.sendNonFatalBugReport(
                java.lang.IllegalStateException("Unexpected exception: " + debugging, exception)
            )
            val message = "Target parsing failed due to unexpected exception: " + exception.getMessage()
            val detailedExitCode: DetailedExitCode? = DetailedException.getDetailedExitCode(exception)
            return if (detailedExitCode != null)
                TargetParsingException(message, exception, detailedExitCode)
            else
                TargetParsingException(message, exception, TargetPatterns.Code.CANNOT_PRELOAD_TARGET)
        }

        @Throws(TargetParsingException::class)
        private fun createPatternLookup(
            mainRepoTargetParser: TargetPattern.Parser?,
            eventHandler: ExtendedEventHandler,
            targetPattern: String?,
            keepGoing: Boolean
        ): PatternLookup? {
            try {
                val key: TargetPatternKey =
                    TargetPatternValue.Companion.key(
                        SignedTargetPattern.parse(targetPattern, mainRepoTargetParser),
                        FilteringPolicies.NO_FILTER
                    )
                return if (isSimple(key.getParsedPattern()))
                    SimpleLookup(targetPattern, key)
                else
                    NormalLookup(targetPattern, key)
            } catch (e: TargetParsingException) {
                // We report a parsing failed exception to the event bus here in case the pattern did not
                // successfully parse (which happens before the SkyKey is created). Otherwise the
                // TargetPatternFunction posts the event.
                eventHandler.post(ParsingFailedEvent(targetPattern, e.getMessage()))
                if (!keepGoing) {
                    throw e
                }
                eventHandler.handle(createPatternParsingError(e, targetPattern))
                return null
            }
        }

        /** Returns true for patterns that can be resolved from a single PackageValue.  */
        private fun isSimple(targetPattern: TargetPattern): Boolean {
            when (targetPattern.type) {
                SINGLE_TARGET, TARGETS_IN_PACKAGE -> return true
                PATH_AS_TARGET, TARGETS_BELOW_DIRECTORY ->         // Both of these require multiple package lookups. PATH_AS_TARGET needs to find the
                    // enclosing package, and TARGETS_BELOW_DIRECTORY recursively looks for all packages under a
                    // specified directory.
                    return false
            }
            throw java.lang.AssertionError()
        }

        private fun createPatternParsingError(
            e: TargetParsingException,
            pattern: String?
        ): com.google.devtools.build.lib.events.Event? {
            return com.google.devtools.build.lib.events.Event.error("Skipping '" + pattern + "': " + e.getMessage())
                .withProperty<T?>(DetailedExitCode::class.java, e.getDetailedExitCode())
        }
    }
}
