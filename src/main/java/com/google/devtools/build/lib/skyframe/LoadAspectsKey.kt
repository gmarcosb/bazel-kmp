// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.AspectClass

/** [SkyKey] for building top-level aspects details.  */
@AutoCodec
class LoadAspectsKey private constructor(
    topLevelAspectsClasses: com.google.common.collect.ImmutableList<AspectClass?>,
    topLevelAspectsParameters: com.google.common.collect.ImmutableMap<String?, String?>?,
    hashCode: Int
) : SkyKey {
    private val topLevelAspectsClasses: com.google.common.collect.ImmutableList<AspectClass?>
    private val topLevelAspectsParameters: com.google.common.collect.ImmutableMap<String?, String?>?
    private val hashCode: Int

    init {
        com.google.common.base.Preconditions.checkArgument(!topLevelAspectsClasses.isEmpty(), "No aspects")
        this.topLevelAspectsClasses = topLevelAspectsClasses
        this.topLevelAspectsParameters = topLevelAspectsParameters
        this.hashCode = hashCode
    }

    override fun functionName(): SkyFunctionName {
        return SkyFunctions.LOAD_ASPECTS
    }

    fun getTopLevelAspectsClasses(): com.google.common.collect.ImmutableList<AspectClass?> {
        return topLevelAspectsClasses
    }

    fun getTopLevelAspectsParameters(): com.google.common.collect.ImmutableMap<String?, String?>? {
        return topLevelAspectsParameters
    }

    override fun hashCode(): Int {
        return hashCode
    }

    override fun equals(o: Any?): Boolean {
        if (o === this) {
            return true
        }
        if (o !is LoadAspectsKey) {
            return false
        }
        return hashCode == o.hashCode && topLevelAspectsClasses == o.topLevelAspectsClasses
                && topLevelAspectsParameters == o.topLevelAspectsParameters
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("topLevelAspectsClasses", topLevelAspectsClasses)
            .add("topLevelAspectsParameters", topLevelAspectsParameters)
            .toString()
    }

    val skyKeyInterner: SkyKeyInterner<LoadAspectsKey?>
        get() = interner

    companion object {
        private val interner: SkyKeyInterner<LoadAspectsKey?> = SkyKey.newInterner<LoadAspectsKey?>()

        fun create(
            topLevelAspectsClasses: com.google.common.collect.ImmutableList<AspectClass?>,
            topLevelAspectsParameters: com.google.common.collect.ImmutableMap<String?, String?>?
        ): LoadAspectsKey {
            return interner.intern(
                LoadAspectsKey(
                    topLevelAspectsClasses,
                    topLevelAspectsParameters,
                    com.google.common.base.Objects.hashCode(topLevelAspectsClasses, topLevelAspectsParameters)
                )
            )
        }

        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        @AutoCodec.Interner
        fun intern(key: LoadAspectsKey?): LoadAspectsKey {
            return interner.intern(key)
        }
    }
}
