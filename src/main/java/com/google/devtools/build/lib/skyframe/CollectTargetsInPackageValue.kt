// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/** Singleton result of [CollectTargetsInPackageFunction].  */
object CollectTargetsInPackageValue : SkyValue {
    @SerializationConstant
    val INSTANCE: CollectTargetsInPackageValue = CollectTargetsInPackageValue()

    /**
     * Creates a key for evaluation of [CollectTargetsInPackageFunction]. See that class's
     * comment for what callers should have done beforehand.
     */
    fun key(
        packageId: PackageIdentifier?, filteringPolicy: FilteringPolicy?
    ): CollectTargetsInPackageKey {
        return CollectTargetsInPackageKey.Companion.create(packageId, filteringPolicy)
    }

    /** [SkyKey] argument.  */
    @AutoCodec
    class CollectTargetsInPackageKey(packageId: PackageIdentifier?, filteringPolicy: FilteringPolicy?) : SkyKey {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.COLLECT_TARGETS_IN_PACKAGE
        }

        val skyKeyInterner: SkyKeyInterner<CollectTargetsInPackageKey?>
            get() = interner
        val packageId: PackageIdentifier?
        val filteringPolicy: FilteringPolicy?

        init {
            this.filteringPolicy = filteringPolicy
            this.packageId = packageId
            java.util.Objects.requireNonNull<Any?>(packageId, "packageId")
            java.util.Objects.requireNonNull<Any?>(filteringPolicy, "filteringPolicy")
        }

        companion object {
            private val interner: SkyKeyInterner<CollectTargetsInPackageKey?> =
                SkyKey.newInterner<CollectTargetsInPackageKey?>()

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Instantiator
            fun create(
                packageId: PackageIdentifier?, filteringPolicy: FilteringPolicy?
            ): CollectTargetsInPackageKey {
                return interner.intern(CollectTargetsInPackageKey(packageId, filteringPolicy))
            }
        }
    }
}
