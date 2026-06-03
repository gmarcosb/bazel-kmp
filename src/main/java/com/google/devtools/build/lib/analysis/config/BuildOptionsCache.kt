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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.events.EventHandler

/**
 * Protects against excessive memory consumption when the same transition applies multiple times.
 * 
 * 
 * For example: an exec transition to `//my:exec_platform` for a tool that every rule in
 * the target configuration depends on.
 * 
 * 
 * Specifically, if `(origOptions1, context1)` produces `toOptions1`, `(origOptions2, context2)` produces `toOptions2`, `origOptions1.equals(origOptions2)`,
 * and `context1.equals(context2)`, this guarantees that `toOptions1 == toOptions2`,
 * assuming the cache entry has not been evicted.
 * 
 * 
 * This means applying the same transition to the same source multiple times always returns the
 * same reference.
 * 
 * 
 * [BuildOptions] references are stored softly.
 */
class BuildOptionsCache<T>(transition: CacheRetrievalFunction<BuildOptionsView?, T?, EventHandler?, BuildOptions?>?) {
    private val cache: com.github.benmanes.caffeine.cache.Cache<CacheKey<T?>?, BuildOptions?> =
        Caffeine.newBuilder().softValues().build<CacheKey<T?>?, BuildOptions?>()

    /** An interface describing a function representing the transition used in this cache.  */
    fun interface CacheRetrievalFunction<A, T, B, C> {
        @Throws(java.lang.InterruptedException::class)
        fun apply(fromOptions: A?, context: T?, eventHandler: B?): C?
    }

    private val transition: CacheRetrievalFunction<BuildOptionsView?, T?, EventHandler?, BuildOptions?>? = null

    init {
        TODO(
            """
            |Cannot convert element
            |With text:
            |this.transition = <CacheRetrievalFunction<BuildOptionsView,T,EventHandler, BuildOptions>>checkNotNull(transition);
            """.trimMargin()
        )
    }

    /**
     * Applies the given transition to the given `(fromOptions, context)` pair. Returns an
     * existing [BuildOptions] instance if one is already associated with that key. Else
     * constructs and caches a new [BuildOptions] instance using the given transition function.
     * 
     * @param fromOptions the starting options
     * @param context an additional object that affects the transition's result
     */
    @Throws(java.lang.InterruptedException::class)
    fun applyTransition(
        fromOptions: BuildOptionsView, context: T?, eventHandler: EventHandler?
    ): BuildOptions? {
        val interruptedException: AtomicReference<java.lang.InterruptedException?> =
            AtomicReference<java.lang.InterruptedException?>()
        val ans: BuildOptions? =
            cache.get(
                com.google.devtools.build.lib.analysis.config.BuildOptionsCache.CacheKey.Companion.create<T?>(
                    fromOptions.underlying().checksum(),
                    context
                ),
                java.util.function.Function { unused: CacheKey<T?>? ->
                    try {
                        return@get transition!!.apply(fromOptions, context, eventHandler)
                    } catch (e: java.lang.InterruptedException) {
                        interruptedException.set(e)
                        return@get null
                    }
                })
        if (interruptedException.get() != null) {
            throw interruptedException.get()
        }
        return ans
    }

    /**
     * Helper class for matching ([BuildOptions], [T]) cache keys by [ ][BuildOptions.checksum].
     * 
     * @param <T> the type of the context object
    </T> */
    @kotlin.jvm.JvmRecord
    internal data class CacheKey<T>(val checksum: String?, val context: T?) {
        init {
            java.util.Objects.requireNonNull<String?>(checksum, "checksum")
            java.util.Objects.requireNonNull<T?>(context, "context")
        }

        companion object {
            fun <T> create(checksum: String?, context: T?): CacheKey<T?> {
                return com.google.devtools.build.lib.analysis.config.BuildOptionsCache.CacheKey<T?>(checksum, context)
            }
        }
    }
}
