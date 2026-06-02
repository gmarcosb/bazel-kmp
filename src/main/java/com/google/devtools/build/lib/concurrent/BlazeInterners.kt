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
package com.google.devtools.build.lib.concurrent

import java.util.concurrent.ConcurrentHashMap

/** Wrapper around [Interners], with Blaze-specific predetermined concurrency levels.  */
object BlazeInterners {
    private val DEFAULT_CONCURRENCY_LEVEL: Int = java.lang.Runtime.getRuntime().availableProcessors()
    private val CONCURRENCY_LEVEL: Int

    init {
        val `val`: String? = java.lang.System.getenv("BLAZE_INTERNER_CONCURRENCY_LEVEL")
        com.google.devtools.build.lib.concurrent.BlazeInterners.CONCURRENCY_LEVEL =
            if (`val` == null) com.google.devtools.build.lib.concurrent.BlazeInterners.DEFAULT_CONCURRENCY_LEVEL else java.lang.Integer.parseInt(
                `val`
            )
    }

    @kotlin.jvm.JvmStatic
    fun concurrencyLevel(): Int {
        return com.google.devtools.build.lib.concurrent.BlazeInterners.CONCURRENCY_LEVEL
    }

    /**
     * Creates an interner which retains a weak reference to each instance it has interned.
     * 
     * 
     * It is preferred to use `SkyKey#SkyKeyInterner` instead for interning `SkyKey`
     * types.
     */
    @kotlin.jvm.JvmStatic
    fun <T> newWeakInterner(): com.google.common.collect.Interner<T?> {
        return com.google.common.collect.Interners.newBuilder()
            .concurrencyLevel(com.google.devtools.build.lib.concurrent.BlazeInterners.CONCURRENCY_LEVEL).weak()
            .build<T?>()
    }

    @kotlin.jvm.JvmStatic
    fun <T> newStrongInterner(): com.google.common.collect.Interner<T?> {
        return com.google.devtools.build.lib.concurrent.BlazeInterners.StrongInterner<T?>()
    }

    /**
     * Interner based on [ConcurrentHashMap], which offers faster lookups than Guava's strong
     * interner.
     */
    private class StrongInterner<T> : com.google.common.collect.Interner<T?> {
        private val map: MutableMap<T?, T?> =
            ConcurrentHashMap<T?, T?>(com.google.devtools.build.lib.concurrent.BlazeInterners.CONCURRENCY_LEVEL)

        override fun intern(sample: T?): T? {
            val existing: T? = map.putIfAbsent(sample, sample)
            return com.google.common.base.MoreObjects.firstNonNull<T?>(existing, sample)
        }
    }
}
