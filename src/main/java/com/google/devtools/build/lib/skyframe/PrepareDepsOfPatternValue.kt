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

import com.google.devtools.build.lib.cmdline.SignedTargetPattern

/**
 * The value returned by [PrepareDepsOfPatternFunction]. Because that function is invoked only
 * for its side effect (i.e. ensuring the graph contains targets matching the pattern and its
 * transitive dependencies), this value carries no information.
 * 
 * 
 * Because the returned value is always equal to objects that share its type, this value and the
 * [PrepareDepsOfPatternFunction] which computes it are incompatible with change pruning. It
 * should only be requested by consumers who do not require reevaluation when [ ] is reevaluated. Safe consumers include, e.g., top-level consumers,
 * and other functions which invoke [PrepareDepsOfPatternFunction] solely for its
 * side-effects.
 */
class PrepareDepsOfPatternValue private constructor() : SkyValue {
    override fun equals(o: Any?): Boolean {
        return o is PrepareDepsOfPatternValue
    }

    override fun hashCode(): Int {
        return 42
    }

    /**
     * A pair of [<] and [ ][<].
     */
    class PrepareDepsOfPatternSkyKeysAndExceptions(
      @kotlin.jvm.JvmField val values: Iterable<PrepareDepsOfPatternSkyKeyValue?>?,
      @kotlin.jvm.JvmField val exceptions: Iterable<PrepareDepsOfPatternSkyKeyException?>?
    )

    /** Represents a [TargetParsingException] when parsing a target pattern string.  */
    class PrepareDepsOfPatternSkyKeyException(exception: TargetParsingException?, originalPattern: String?) {
        private val exception: TargetParsingException?
        @kotlin.jvm.JvmField
        val originalPattern: String?

        init {
            this.exception = exception
            this.originalPattern = originalPattern
        }

        fun getException(): TargetParsingException? {
            return exception
        }
    }

    /**
     * Represents the successful parsing of a target pattern string into a [TargetPatternKey].
     */
    class PrepareDepsOfPatternSkyKeyValue internal constructor(targetPatternKey: TargetPatternKey?) {
        private val targetPatternKey: TargetPatternKey?

        init {
            this.targetPatternKey = targetPatternKey
        }

        val skyKey: Key
            get() = com.google.devtools.build.lib.skyframe.PrepareDepsOfPatternValue.PrepareDepsOfPatternSkyKeyValue.Key.Companion.create(
                targetPatternKey
            )

        @AutoCodec
        internal class Key private constructor(arg: TargetPatternKey?) : AbstractSkyKey<TargetPatternKey?>(arg) {
            override fun functionName(): SkyFunctionName {
                return SkyFunctions.PREPARE_DEPS_OF_PATTERN
            }

            val skyKeyInterner: SkyKeyInterner<Key?>
                get() = com.google.devtools.build.lib.skyframe.PrepareDepsOfPatternValue.PrepareDepsOfPatternSkyKeyValue.Key.Companion.interner

            companion object {
                private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

                @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
                @AutoCodec.Instantiator
                fun create(arg: TargetPatternKey?): Key {
                    return com.google.devtools.build.lib.skyframe.PrepareDepsOfPatternValue.PrepareDepsOfPatternSkyKeyValue.Key.Companion.interner.intern(
                        com.google.devtools.build.lib.skyframe.PrepareDepsOfPatternValue.PrepareDepsOfPatternSkyKeyValue.Key(
                            arg
                        )
                    )
                }
            }
        }
    }

    companion object {
        // Note that this value does not guarantee singleton-like reference equality because we use Java
        // deserialization. Java deserialization can create other instances.
        @kotlin.jvm.JvmField
        @SerializationConstant
        val INSTANCE: PrepareDepsOfPatternValue = PrepareDepsOfPatternValue()

        /**
         * Returns a [PrepareDepsOfPatternSkyKeysAndExceptions], containing [ ] and [PrepareDepsOfPatternSkyKeyException] instances that
         * have [TargetPatternKey] arguments. Negative target patterns of type other than [ ][Type.TARGETS_BELOW_DIRECTORY] are not permitted. If a provided pattern fails to parse or is
         * negative but not a [Type.TARGETS_BELOW_DIRECTORY], there will be a corresponding [ ] in the iterable returned by [ ][PrepareDepsOfPatternSkyKeysAndExceptions.getExceptions] whose [ ][PrepareDepsOfPatternSkyKeyException.getException] and [ ][PrepareDepsOfPatternSkyKeyException.getOriginalPattern] methods return the [ ] and original pattern, respectively.
         * 
         * 
         * There may be fewer returned elements in [ ][PrepareDepsOfPatternSkyKeysAndExceptions.getValues] than patterns provided as input. This
         * function will combine negative [Type.TARGETS_BELOW_DIRECTORY] patterns with preceding
         * patterns to return an iterable of SkyKeys that avoids loading excluded directories during
         * evaluation.
         * 
         * @param patterns The list of patterns, e.g. [//foo/..., -//foo/biz/...]. If a pattern's first
         * character is "-", it is treated as a negative pattern.
         * @param mainRepoTargetParser The target pattern parser configured with the specified offset and
         * the main repository mapping.
         */
        @ThreadSafe
        fun keys(
            patterns: MutableList<String?>, mainRepoTargetParser: TargetPattern.Parser?
        ): PrepareDepsOfPatternSkyKeysAndExceptions {
            val resultValuesBuilder: com.google.common.collect.ImmutableList.Builder<PrepareDepsOfPatternSkyKeyValue?> =
                com.google.common.collect.ImmutableList.builder<PrepareDepsOfPatternSkyKeyValue?>()
            val resultExceptionsBuilder: com.google.common.collect.ImmutableList.Builder<PrepareDepsOfPatternSkyKeyException?> =
                com.google.common.collect.ImmutableList.builder<PrepareDepsOfPatternSkyKeyException?>()
            val targetPatternKeysBuilder: com.google.common.collect.ImmutableList.Builder<TargetPatternKey?> =
                com.google.common.collect.ImmutableList.builder<TargetPatternKey?>()
            for (pattern in patterns) {
                try {
                    targetPatternKeysBuilder.add(
                        TargetPatternValue.key(
                            SignedTargetPattern.parse(pattern, mainRepoTargetParser),
                            FilteringPolicies.NO_FILTER
                        )
                    )
                } catch (e: TargetParsingException) {
                    resultExceptionsBuilder.add(PrepareDepsOfPatternSkyKeyException(e, pattern))
                }
            }
            // This code path is evaluated only for query universe preloading, and the quadratic cost of
            // the code below (i.e. for each pattern, consider each later pattern as a candidate for
            // subdirectory exclusion) is only acceptable because all the use cases for query universe
            // preloading involve short (<10 items) pattern sequences.
            val combinedTargetPatternKeys: Iterable<TargetPatternKey> =
                TargetPatternValue.combineTargetsBelowDirectoryWithNegativePatterns(
                    targetPatternKeysBuilder.build(),  /*excludeSingleTargets=*/false
                )
            for (targetPatternKey in combinedTargetPatternKeys) {
                if (targetPatternKey.isNegative()
                    && !targetPatternKey
                        .getParsedPattern().type
                        .equals(TargetPattern.Type.TARGETS_BELOW_DIRECTORY)
                ) {
                    resultExceptionsBuilder.add(
                        PrepareDepsOfPatternSkyKeyException(
                            TargetParsingException(
                                "Negative target patterns of types other than \"targets below directory\""
                                        + " are not permitted.",
                                TargetPatterns.Code.NEGATIVE_TARGET_PATTERN_NOT_ALLOWED
                            ),
                            targetPatternKey.toString()
                        )
                    )
                } else {
                    resultValuesBuilder.add(PrepareDepsOfPatternSkyKeyValue(targetPatternKey))
                }
            }
            return PrepareDepsOfPatternSkyKeysAndExceptions(
                resultValuesBuilder.build(), resultExceptionsBuilder.build()
            )
        }
    }
}
