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

import com.google.devtools.build.lib.cmdline.RepositoryMapping

/**
 * PrepareDepsOfPatternsFunction ensures the graph loads targets matching the pattern sequence and
 * their transitive dependencies.
 */
class PrepareDepsOfPatternsFunction : SkyFunction {
    /**
     * Given a [SkyKey] that contains a sequence of target patterns, when this function returns
     * [PrepareDepsOfPatternsValue], then all targets matching that sequence, and those targets'
     * transitive dependencies, have been loaded.
     */
    @Throws(java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val eventHandler: ExtendedEventHandler = env.getListener()

        val repositoryMappingValue: RepositoryMappingValue? =
            env.getValue(RepositoryMappingValue.Companion.key(RepositoryName.MAIN)) as RepositoryMappingValue?
        if (repositoryMappingValue == null) {
            return null
        }
        val mainRepoMapping: RepositoryMapping? = repositoryMappingValue.repositoryMapping
        val skyKeys: com.google.common.collect.ImmutableList<SkyKey> = getSkyKeys(skyKey, eventHandler, mainRepoMapping)

        val tokensByKey: SkyframeLookupResult = env.getValuesAndExceptions(skyKeys)
        if (env.valuesMissing()) {
            return null
        }

        for (key in skyKeys) {
            try {
                val value: SkyValue? =
                    tokensByKey.getOrThrow<E1?, E2?, E3?>(
                        key,
                        TargetParsingException::class.java,
                        ProcessPackageDirectoryException::class.java,
                        InconsistentFilesystemException::class.java
                    )
                if (value == null) {
                    BugReport.sendNonFatalBugReport(
                        java.lang.IllegalStateException(
                            "SkyValue " + key + " was missing, this should never happen"
                        )
                    )
                    return null
                }
            } catch (e: TargetParsingException) {
                // If a target pattern can't be evaluated, notify the user of the problem and keep going.
                Companion.handleTargetParsingException(eventHandler, key, e)
            } catch (e: ProcessPackageDirectoryException) {
                // ProcessPackageDirectoryException indicates a catastrophic
                // InconsistentFilesystemException, which will be handled later by a caller.
                return null
            } catch (e: InconsistentFilesystemException) {
                return null
            }
        }

        return PrepareDepsOfPatternsValue(getTargetPatternKeys(skyKeys))
    }

    companion object {
        fun getSkyKeys(
            skyKey: SkyKey, eventHandler: ExtendedEventHandler, mainRepoMapping: RepositoryMapping?
        ): com.google.common.collect.ImmutableList<SkyKey> {
            val targetPatternSequence: TargetPatternSequence = skyKey.argument() as TargetPatternSequence
            val mainRepoTargetParser: TargetPattern.Parser =
                Parser(
                    targetPatternSequence.getOffset(), RepositoryName.MAIN, mainRepoMapping
                )
            val prepareDepsOfPatternSkyKeysAndExceptions: PrepareDepsOfPatternSkyKeysAndExceptions =
                PrepareDepsOfPatternValue.Companion.keys(targetPatternSequence.getPatterns(), mainRepoTargetParser)

            val skyKeyBuilder: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
                com.google.common.collect.ImmutableList.builder<SkyKey?>()
            for (skyKeyValue in prepareDepsOfPatternSkyKeysAndExceptions.getValues()) {
                skyKeyBuilder.add(skyKeyValue.getSkyKey())
            }
            for (skyKeyException in prepareDepsOfPatternSkyKeysAndExceptions.getExceptions()) {
                val e: TargetParsingException = skyKeyException.getException()
                // We post an event here rather than in handleTargetParsingException because the
                // TargetPatternFunction already posts an event unless the pattern cannot be parsed, in
                // which case the caller (i.e., us) needs to post an event.
                eventHandler.post(
                    ParsingFailedEvent(skyKeyException.getOriginalPattern(), e.getMessage())
                )
                Companion.handleTargetParsingException(eventHandler, skyKeyException.getOriginalPattern(), e)
            }

            return skyKeyBuilder.build()
        }

        private val SKY_TO_TARGET_PATTERN: com.google.common.base.Function<SkyKey?, TargetPatternKey?> =
            object : com.google.common.base.Function<SkyKey?, TargetPatternKey?> {
                override fun apply(skyKey: SkyKey): TargetPatternKey? {
                    return skyKey.argument() as TargetPatternKey?
                }
            }

        fun getTargetPatternKeys(
            skyKeys: com.google.common.collect.ImmutableList<SkyKey>
        ): com.google.common.collect.ImmutableList<TargetPatternKey?> {
            return com.google.common.collect.ImmutableList.copyOf<TargetPatternKey?>(
                com.google.common.collect.Iterables.transform<SkyKey?, TargetPatternKey?>(
                    skyKeys,
                    SKY_TO_TARGET_PATTERN
                )
            )
        }

        private fun handleTargetParsingException(
            eventHandler: ExtendedEventHandler, key: SkyKey, e: TargetParsingException
        ) {
            val patternKey: TargetPatternKey = key.argument() as TargetPatternKey
            val rawPattern: String? = patternKey.getPattern()
            Companion.handleTargetParsingException(eventHandler, rawPattern, e)
        }

        private fun handleTargetParsingException(
            eventHandler: ExtendedEventHandler, rawPattern: String?, e: TargetParsingException
        ) {
            val errorMessage: String? = e.getMessage()
            eventHandler.handle(Event.error("Skipping '" + rawPattern + "': " + errorMessage))
        }
    }
}
