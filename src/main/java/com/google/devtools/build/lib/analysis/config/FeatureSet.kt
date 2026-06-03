// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec

/**
 * Represents a set of "on" features and a set of "off" features. The two sets are guaranteed not to
 * intersect.
 */
@AutoCodec
class FeatureSet(
    on: com.google.common.collect.ImmutableSet<String?>?,
    off: com.google.common.collect.ImmutableSet<String?>?
) {
    fun toStringList(): com.google.common.collect.ImmutableList<String?> {
        return com.google.common.collect.Streams.concat<String?>(
            this.on.stream(),
            this.off.stream().map<String?>(java.util.function.Function { s: String? -> "-" + s })
        )
            .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
    }

    val on: com.google.common.collect.ImmutableSet<String?>?
    val off: com.google.common.collect.ImmutableSet<String?>?

    init {
        this.off = off
        this.on = on
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<String?>?>(on, "on")
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<String?>?>(off, "off")
    }

    companion object {
        val EMPTY: FeatureSet = of(
            com.google.common.collect.ImmutableSet.of<String?>(),
            com.google.common.collect.ImmutableSet.of<String?>()
        )

        private fun of(on: MutableSet<String?>, off: MutableSet<String?>): FeatureSet {
            return FeatureSet(
                com.google.common.collect.ImmutableSortedSet.copyOf<String?>(on),
                com.google.common.collect.ImmutableSortedSet.copyOf<String?>(off)
            )
        }

        /** Parses a [FeatureSet] instance from a list of strings.  */
        fun parse(features: Iterable<String>): FeatureSet {
            val featureToState: MutableMap<String?, Boolean?> = HashMap<String?, Boolean?>()
            for (feature in features) {
                if (feature.startsWith("-")) {
                    featureToState.put(feature.substring(1), false)
                } else if (feature == "no_layering_check") {
                    // TODO(bazel-team): Remove once we do not have BUILD files left that contain
                    // 'no_layering_check'.
                    featureToState.put("layering_check", false)
                } else {
                    // -X always trumps X.
                    featureToState.putIfAbsent(feature, true)
                }
            }
            return fromMap(featureToState)
        }

        private fun fromMap(featureToState: MutableMap<String?, Boolean?>): FeatureSet {
            return of(
                com.google.common.collect.Maps.filterValues<String?, Boolean?>(
                    featureToState,
                    com.google.common.base.Predicate { obj: Boolean? -> java.lang.Boolean.TRUE.equals(obj) }).keySet(),
                com.google.common.collect.Maps.filterValues<String?, Boolean?>(
                    featureToState,
                    com.google.common.base.Predicate { obj: Boolean? -> java.lang.Boolean.FALSE.equals(obj) }).keySet()
            )
        }

        private fun mergeSetIntoMap(
            features: MutableSet<String?>, state: Boolean, featureToState: MutableMap<String?, Boolean?>
        ) {
            for (feature in features) {
                featureToState.put(feature, state)
            }
        }

        /**
         * Merges two [FeatureSet]s into one, with `coarse` being the coarser-grained set
         * (e.g. the package default feature set), and `fine` being the finer-grained set (e.g. the
         * rule-level feature set). Note that this operation is not commutative.
         */
        fun merge(coarse: FeatureSet, fine: FeatureSet): FeatureSet {
            val featureToState: MutableMap<String?, Boolean?> = HashMap<String?, Boolean?>()
            mergeSetIntoMap(coarse.on, true, featureToState)
            mergeSetIntoMap(coarse.off, false, featureToState)
            mergeSetIntoMap(fine.on, true, featureToState)
            mergeSetIntoMap(fine.off, false, featureToState)
            return fromMap(featureToState)
        }

        /**
         * Merges a [FeatureSet] with the global feature set. This differs from [.merge] in
         * that the globally disabled features are **always** disabled.
         */
        fun mergeWithGlobalFeatures(base: FeatureSet, global: FeatureSet): FeatureSet {
            val featureToState: MutableMap<String?, Boolean?> = HashMap<String?, Boolean?>()
            mergeSetIntoMap(global.on, true, featureToState)
            mergeSetIntoMap(base.on, true, featureToState)
            mergeSetIntoMap(base.off, false, featureToState)
            mergeSetIntoMap(global.off, false, featureToState)
            return fromMap(featureToState)
        }
    }
}
