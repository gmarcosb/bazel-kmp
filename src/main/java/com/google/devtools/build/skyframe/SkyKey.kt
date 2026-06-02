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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.concurrent.PooledInterner

/**
 * A [SkyKey] is effectively a pair (type, name) that identifies a Skyframe value.
 * 
 * 
 * SkyKey implementations are heavily used as map keys. Thus, they should have fast [ ][.hashCode] implementations (cached if necessary). The same SkyKey may be created multiple times
 * by different `SkyFunction`s requesting it, and so it should have effective interning. There
 * will likely be more SkyKeys on the JVM heap than any other non-native type, so be mindful of
 * memory usage (in particular object wrapper size and memory alignment)! Typically the
 * implementation should have a fixed [.functionName] implementation and return itself as the
 * [.argument] in order to reduce the cost of wrapper objects.
 */
interface SkyKey : java.io.Serializable {
    /** Returns the canonical representation of the key as a string.  */
    fun getCanonicalName(): String? {
        return java.lang.String.format("%s:%s", functionName(), argument().toString().replace('\n', '_'))
    }

    fun functionName(): SkyFunctionName?

    fun argument(): Any? {
        return this
    }

    /**
     * Returns `true` if this key produces a [SkyValue] that can be reused across builds.
     * 
     * 
     * Values may be unshareable because they are just not serializable, or because they contain
     * data that cannot safely be reused as-is by another invocation, such as stamping information or
     * "flaky" values like test statuses.
     * 
     * 
     * Unshareable data should not be serialized, since it will never be reused. Attempts to fetch
     * a key's serialized data will call this method and only perform the fetch if it returns `true`.
     * 
     * 
     * The result of this method only applies to non-error values. In case of an error, [ ][ErrorInfo.isTransitivelyTransient] can be used to determine shareability.
     */
    fun valueIsShareable(): Boolean {
        return true
    }

    /**
     * Returns `true` if previously requested deps values are not eagerly batch prefetched when
     * the [SkyFunctionEnvironment] to evaluate this [SkyKey] is created.
     * 
     * 
     * Please note that [SkyKey]s which supports partial reevaluation should always skip
     * batch prefetch.
     */
    // TODO: b/324948927#comment8 - Remove this method in the future when skipping batch prefetching
    // is determined during environment creation.
    fun skipsBatchPrefetch(): Boolean {
        return supportsPartialReevaluation()
    }

    /**
     * Returns `true` if this key's [SkyFunction] would like Skyframe to schedule its
     * reevaluation when any of its previously requested unfinished deps completes. Otherwise,
     * Skyframe will schedule reevaluation only when all previously requested unfinished deps
     * complete.
     */
    fun supportsPartialReevaluation(): Boolean {
        return false
    }

    fun getSkyKeyInterner(): SkyKeyInterner<*>? {
        return null
    }

    /** [PooledInterner] for [SkyKey]s.  */
    class SkyKeyInterner<T : SkyKey?> : PooledInterner<T?>() {
        protected override fun getPool(): Pool<T?>? {
            return globalPool as Pool<T?>?
        }

        /**
         * Call [.weakInternUnchecked] on [SkyKeyInterner] returned by `key.getSkyKeyInterner`. This method is created to remove casts and
         * `@SuppressWarnings("unchecked")` in callers and put them in one place.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun weakInternUnchecked(sample: SkyKey?): T? {
            return weakIntern(sample as T?)
        }

        companion object {
            var globalPool: Pool<out SkyKey?>? = null

            /**
             * Sets the [Pool] to be used for interning.
             * 
             * 
             * The pool is strongly retained until it is cleared, which can be accomplished by passing
             * `null` to this method.
             */
            @ThreadCompatible
            fun setGlobalPool(pool: Pool<SkyKey?>?) {
                // No synchronization is needed. Setting global pool is guaranteed to happen sequentially
                // since only one build can happen at the same time.
                if (pool != null && globalPool != null && (!TestType.Companion.isInTest() || TestType.Companion.getTestType() == TestType.SHELL_INTEGRATION)) {
                    BugReport.sendNonFatalBugReport(
                        java.lang.IllegalStateException("Global SkyKey pool not cleared before setting another")
                    )
                }
                globalPool = pool
            }
        }
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun <T : SkyKey?> newInterner(): SkyKeyInterner<T?> {
            return SkyKeyInterner<T?>()
        }
    }
}
