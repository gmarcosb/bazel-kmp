// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.java

import com.github.benmanes.caffeine.cache.CacheLoader
import com.github.benmanes.caffeine.cache.LoadingCache
import com.google.devtools.build.lib.actions.Artifact
import com.google.devtools.build.lib.concurrent.ThreadSafety
import java.util.*

/** Information about the system APIs for a Java compilation.  */
@ThreadSafety.Immutable
open class BootClassPathInfo private constructor(underlying: StructImpl?) : StarlarkInfoWrapper(underlying) {
    /** The jar files containing classes for system APIs, i.e. a Java <= 8 bootclasspath.  */
    @Throws(RuleErrorException::class)
    open fun bootclasspath(): NestedSet<Artifact?>? {
        return getUnderlyingNestedSet<Artifact?>("bootclasspath", Artifact::class.java)
    }

    /**
     * The jar files containing extra classes for system APIs that should not be put in the system
     * image to support split-package compilation scenarios.
     */
    @Throws(RuleErrorException::class)
    open fun auxiliary(): NestedSet<Artifact?>? {
        return getUnderlyingNestedSet<Artifact?>("_auxiliary", Artifact::class.java)
    }

    /** Contents of the directory that is passed to the javac >= 9 `--system` flag.  */
    @Throws(RuleErrorException::class)
    open fun systemInputs(): NestedSet<Artifact?>? {
        return getUnderlyingNestedSet<Artifact?>("_system_inputs", Artifact::class.java)
    }

    /** An argument to the javac >= 9 `--system` flag.  */
    @Throws(RuleErrorException::class)
    open fun systemPath(): Optional<PathFragment?>? {
        val s = getUnderlyingValue<String?>("_system_path", String::class.java)
        return if (s != null) systemPathCache.get(s) else Optional.empty<PathFragment?>()
    }

    @get:Throws(RuleErrorException::class)
    open val isEmpty: Boolean
        get() = bootclasspath().isEmpty()
                && auxiliary().isEmpty()
                && systemInputs().isEmpty()
                && systemPath()!!.isEmpty()

    companion object {
        private val EMPTY: BootClassPathInfo = object : BootClassPathInfo(null) {
            override fun bootclasspath(): NestedSet<Artifact?> {
                return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            }

            override fun auxiliary(): NestedSet<Artifact?> {
                return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            }

            override fun systemInputs(): NestedSet<Artifact?> {
                return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            }

            override fun systemPath(): Optional<PathFragment?> {
                return Optional.empty<PathFragment?>()
            }

            override fun isEmpty(): Boolean {
                return true
            }
        }

        // Ensures that we use a canonical Optional<PathFragment> instance per system path to save memory.
        private val systemPathCache: LoadingCache<String?, Optional<PathFragment?>?> =
            Caffeine.newBuilder().weakKeys().build<String?, Optional<PathFragment?>?>(
                CacheLoader { s: String? -> Optional.of<PathFragment?>(PathFragment.create(s)) })

        fun empty(): BootClassPathInfo {
            return EMPTY
        }

        @Throws(RuleErrorException::class)
        fun wrap(info: Info?): BootClassPathInfo {
            return BootClassPathInfo(info as StructImpl?)
        }
    }
}
