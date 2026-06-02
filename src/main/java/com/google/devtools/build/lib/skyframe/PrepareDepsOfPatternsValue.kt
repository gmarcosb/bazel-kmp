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

import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable

/**
 * The value returned by [PrepareDepsOfPatternsFunction]. Although that function is invoked
 * primarily for its side effect (i.e. ensuring the graph contains targets matching the pattern
 * sequence and their transitive dependencies), this value contains the [TargetPatternKey]
 * arguments of the [PrepareDepsOfPatternFunction]s evaluated in service of it.
 * 
 * 
 * Because the returned value may remain the same when the side-effects of this function
 * evaluation change, this value and the [PrepareDepsOfPatternsFunction] which computes it are
 * incompatible with change pruning. It should only be requested by consumers who do not require
 * reevaluation when [PrepareDepsOfPatternsFunction] is reevaluated. Safe consumers include,
 * e.g., top-level consumers, and other functions which invoke [PrepareDepsOfPatternsFunction]
 * solely for its side-effects and which do not behave differently depending on those side-effects.
 */
@Immutable
@ThreadSafe
class PrepareDepsOfPatternsValue(targetPatternKeys: com.google.common.collect.ImmutableList<TargetPatternKey?>?) :
    SkyValue {
    private val targetPatternKeys: com.google.common.collect.ImmutableList<TargetPatternKey?>

    init {
        this.targetPatternKeys =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<TargetPatternKey?>>(
                targetPatternKeys
            )
    }

    fun getTargetPatternKeys(): com.google.common.collect.ImmutableList<TargetPatternKey?> {
        return targetPatternKeys
    }

    /** The argument value for [SkyKey]s of [PrepareDepsOfPatternsFunction].  */
    @ThreadSafe
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    internal class TargetPatternSequence private constructor(
        patterns: com.google.common.collect.ImmutableList<String?>?,
        offset: PathFragment?
    ) : UniverseSkyKey {
        private val patterns: com.google.common.collect.ImmutableList<String?>
        private val offset: PathFragment

        init {
            this.patterns =
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<String?>>(
                    patterns
                )
            this.offset = com.google.common.base.Preconditions.checkNotNull<PathFragment>(offset)
        }

        public override fun getPatterns(): com.google.common.collect.ImmutableList<String?> {
            return patterns
        }

        fun getOffset(): PathFragment {
            return offset
        }

        val skyKeyInterner: SkyKeyInterner<TargetPatternSequence?>
            get() = interner

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is TargetPatternSequence) {
                return false
            }
            return offset == o.offset && patterns == o.patterns
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(patterns, offset)
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("patterns", patterns)
                .add("offset", offset)
                .toString()
        }

        public override fun functionName(): SkyFunctionName {
            return SkyFunctions.PREPARE_DEPS_OF_PATTERNS
        }

        companion object {
            private val interner: SkyKeyInterner<TargetPatternSequence?> = SkyKey.newInterner<TargetPatternSequence?>()

            @com.google.common.annotations.VisibleForTesting
            fun create(
                patterns: com.google.common.collect.ImmutableList<String?>?,
                offset: PathFragment?
            ): TargetPatternSequence {
                return interner.intern(TargetPatternSequence(patterns, offset))
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(targetPatternSequence: TargetPatternSequence?): TargetPatternSequence {
                return interner.intern(targetPatternSequence)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is PrepareDepsOfPatternsValue
                && targetPatternKeys == other.getTargetPatternKeys()
    }

    override fun hashCode(): Int {
        return targetPatternKeys.hashCode()
    }

    companion object {
        @ThreadSafe
        fun key(
            patterns: com.google.common.collect.ImmutableList<String?>?,
            offset: PathFragment?
        ): TargetPatternSequence {
            return TargetPatternSequence.Companion.create(patterns, offset)
        }
    }
}
