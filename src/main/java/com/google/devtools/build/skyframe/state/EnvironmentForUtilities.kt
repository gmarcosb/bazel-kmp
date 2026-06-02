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
package com.google.devtools.build.skyframe.state

import com.google.devtools.build.skyframe.SkyFunction.LookupEnvironment
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.SkyframeLookupResult
import com.google.devtools.build.skyframe.SkyframeLookupResult.QueryDepCallback

/** An environment for post-evaluation queries and tests.  */
class EnvironmentForUtilities
    (private val resultProvider: ResultProvider) : LookupEnvironment, SkyframeLookupResult {
    /** Provides results.  */
    interface ResultProvider {
        /**
         * Returns [SkyValue] or [Exception] for `key`.
         * 
         * 
         * May return null for the following reasons.
         * 
         * 
         *  * The result is not yet determined, possibly due to failing fast.
         *  * The result was part of a cycle error.
         * 
         */
        fun getValueOrException(key: SkyKey?): Any?
    }

    override fun getValue(depKey: SkyKey?): SkyValue? {
        return getValueOrThrow<java.lang.RuntimeException?, java.lang.RuntimeException?, java.lang.RuntimeException?, java.lang.RuntimeException?>(
            depKey,
            null,
            null,
            null,
            null
        )
    }

    @Throws(E::class)
    override fun <E : java.lang.Exception?> getValueOrThrow(
        depKey: SkyKey?,
        exceptionClass1: java.lang.Class<E?>?
    ): SkyValue? {
        return getValueOrThrow<E?, java.lang.RuntimeException?, java.lang.RuntimeException?>(
            depKey,
            exceptionClass1,
            null,
            null
        )
    }

    @Throws(E1::class, E2::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?> getValueOrThrow(
        depKey: SkyKey?, exceptionClass1: java.lang.Class<E1?>?, exceptionClass2: java.lang.Class<E2?>?
    ): SkyValue? {
        return getValueOrThrow<E1?, E2?, java.lang.RuntimeException?, java.lang.RuntimeException?>(
            depKey,
            exceptionClass1,
            exceptionClass2,
            null,
            null
        )
    }

    @Throws(E1::class, E2::class, E3::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?>
            getValueOrThrow(
        depKey: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?
    ): SkyValue? {
        return getValueOrThrow<E1?, E2?, E3?, java.lang.RuntimeException?>(
            depKey,
            exceptionClass1,
            exceptionClass2,
            exceptionClass3,
            null
        )
    }

    @Throws(E1::class, E2::class, E3::class, E4::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?, E4 : java.lang.Exception?>
            getValueOrThrow(
        depKey: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?,
        exceptionClass4: java.lang.Class<E4?>?
    ): SkyValue? {
        val result = resultProvider.getValueOrException(depKey)
        if (result == null) {
            return null
        }
        if (result is SkyValue) {
            return result
        }
        if (exceptionClass1 != null && exceptionClass1.isInstance(result)) {
            throw exceptionClass1.cast(result)
        }
        if (exceptionClass2 != null && exceptionClass2.isInstance(result)) {
            throw exceptionClass2.cast(result)
        }
        if (exceptionClass3 != null && exceptionClass3.isInstance(result)) {
            throw exceptionClass3.cast(result)
        }
        if (exceptionClass4 != null && exceptionClass4.isInstance(result)) {
            throw exceptionClass4.cast(result)
        }
        return null
    }

    override fun getValuesAndExceptions(unused: Iterable<out SkyKey?>?): SkyframeLookupResult {
        return this
    }

    override fun getLookupHandleForPreviouslyRequestedDeps(): SkyframeLookupResult {
        return this
    }

    // -------------------- SkyframeLookupResult Implementation --------------------
    @Throws(E1::class, E2::class, E3::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?> getOrThrow(
        skyKey: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?
    ): SkyValue? {
        return getValueOrThrow<E1?, E2?, E3?, java.lang.RuntimeException?>(
            skyKey,
            exceptionClass1,
            exceptionClass2,
            exceptionClass3,
            null
        )
    }

    override fun queryDep(key: SkyKey?, resultCallback: QueryDepCallback): Boolean {
        val result = resultProvider.getValueOrException(key)
        if (result == null) {
            return false
        }
        if (result is SkyValue) {
            resultCallback.acceptValue(key, result)
            return true
        }
        if (resultCallback.tryHandleException(key, result as java.lang.Exception)) {
            return true
        }
        return false
    }
}
