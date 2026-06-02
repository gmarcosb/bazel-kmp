// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.BzlLoadValue
import com.google.devtools.build.lib.skyframe.CachedBzlLoadData
import com.google.devtools.build.skyframe.SkyKey
import java.util.concurrent.atomic.AtomicReference

/**
 * A saved [BzlLoadFunction] computation, used when inlining that Skyfunction.
 * 
 * 
 * This holds a requested key, its computed value, and the direct and transitive Skyframe
 * dependencies that are needed to compute it from scratch (i.e. if it weren't cached). Here
 * "transitive" means "underneath another `BzlLoadFunction` computation"; we split them into
 * other `CachedBzlLoadData` objects so they can be shared by other requesting bzls.
 */
internal class CachedBzlLoadData private constructor(
    key: com.google.devtools.build.lib.skyframe.BzlLoadValue.Key,
    value: BzlLoadValue?,
    directDeps: com.google.common.collect.ImmutableList<Iterable<SkyKey?>?>,
    transitiveDeps: com.google.common.collect.ImmutableList<CachedBzlLoadData>
) {
    private val key: com.google.devtools.build.lib.skyframe.BzlLoadValue.Key
    private val value: BzlLoadValue?
    private val directDeps: com.google.common.collect.ImmutableList<Iterable<SkyKey?>?>
    private val transitiveDeps: com.google.common.collect.ImmutableList<CachedBzlLoadData>

    init {
        this.key = key
        this.value = value
        this.directDeps = directDeps
        this.transitiveDeps = transitiveDeps
    }

    /**
     * Adds all deps (direct and transitive) of this value to the `visitedDeps` set and passes
     * them to the consumer (with unspecified order and grouping). The traversal does not include
     * nodes already contained in `visitedDeps`.
     */
    @Throws(java.lang.InterruptedException::class)
    fun traverse(
        depGroupConsumer: java.util.function.Consumer<Iterable<SkyKey?>?>,
        visitedDeps: MutableMap<com.google.devtools.build.lib.skyframe.BzlLoadValue.Key?, CachedBzlLoadData?>
    ) {
        if (visitedDeps.putIfAbsent(key, this) != null) {
            return
        }

        for (directDepGroup in directDeps) {
            depGroupConsumer.accept(directDepGroup)
        }
        for (indirectDeps in transitiveDeps) {
            indirectDeps.traverse(depGroupConsumer, visitedDeps)
        }
    }

    fun getValue(): BzlLoadValue? {
        return value
    }

    override fun equals(obj: Any?): Boolean {
        if (obj is CachedBzlLoadData) {
            // With the interner, force there to be exactly one cached value per key at any given point
            // in time.
            return this.key == obj.key
        }
        return false
    }

    override fun hashCode(): Int {
        return key.hashCode()
    }

    internal class Builder(interner: com.google.common.collect.Interner<CachedBzlLoadData>) {
        private val interner: com.google.common.collect.Interner<CachedBzlLoadData>
        private val directDeps: MutableList<Iterable<SkyKey?>?> = java.util.ArrayList<Iterable<SkyKey?>?>()
        private val transitiveDeps: MutableList<CachedBzlLoadData?> = java.util.ArrayList<CachedBzlLoadData?>()
        private val exceptionSeen: AtomicReference<java.lang.Exception?> = AtomicReference<java.lang.Exception?>(null)
        private var value: BzlLoadValue? = null
        private var key: com.google.devtools.build.lib.skyframe.BzlLoadValue.Key? = null

        init {
            this.interner = interner
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDep(key: SkyKey): Builder {
            directDeps.add(com.google.common.collect.ImmutableList.of<SkyKey?>(key))
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDeps(keys: Iterable<SkyKey?>?): Builder {
            directDeps.add(keys)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun noteException(e: java.lang.Exception?): Builder {
            exceptionSeen.set(e)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTransitiveDeps(transitiveDeps: CachedBzlLoadData?): Builder {
            this.transitiveDeps.add(transitiveDeps)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setValue(value: BzlLoadValue?): Builder {
            this.value = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setKey(key: com.google.devtools.build.lib.skyframe.BzlLoadValue.Key?): Builder {
            this.key = key
            return this
        }

        fun build(): CachedBzlLoadData {
            // We expect that we don't handle any exceptions in BzlLoadFunction directly.
            com.google.common.base.Preconditions.checkState(
                exceptionSeen.get() == null,
                "Caching a value in error?: %s",
                this
            )
            com.google.common.base.Preconditions.checkNotNull<BzlLoadValue?>(
                value,
                "Expected value to be set: %s",
                this
            )
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.skyframe.BzlLoadValue.Key?>(
                key,
                "Expected key to be set: %s",
                this
            )
            return interner.intern(
                CachedBzlLoadData(
                    key,
                    value,
                    com.google.common.collect.ImmutableList.copyOf<Iterable<SkyKey?>?>(directDeps),
                    com.google.common.collect.ImmutableList.copyOf<CachedBzlLoadData?>(transitiveDeps)
                )
            )
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(com.google.devtools.build.lib.skyframe.CachedBzlLoadData.Builder::class.java)
                .add("key", key)
                .add("value", value)
                .add("directDeps", directDeps)
                .add("transitiveDeps", transitiveDeps)
                .add("exceptionSeen", exceptionSeen)
                .toString()
        }
    }
}
