// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/**
 * A map-like result of getting Skyframe dependencies via [ ][SkyFunction.Environment.getValuesAndExceptions]. Callers can use the [.get], [ ][.getOrThrow] and [.queryDep] methods to obtain elements by key.
 * 
 * 
 * Note that a [SkyFunction] cannot guarantee that [ ][SkyFunction.Environment.valuesMissing] will be true upon receipt of a `SkyframeLookupResult`. The elements must all be fetched. If [.get] or [.getOrThrow]
 * returns `null`, or [.queryDep] returns false, only then will [ ][SkyFunction.Environment.valuesMissing] be guaranteed to return true.
 */
interface SkyframeLookupResult {
    /**
     * Returns a direct dependency. If the dependency is not in the set of already evaluated direct
     * dependencies, returns `null`. Also returns `null` if the dependency has already
     * been evaluated and found to be in error. In either of these cases, [ ][SkyFunction.Environment.valuesMissing] will subsequently return true.
     */
    fun get(skyKey: SkyKey?): SkyValue? {
        return getOrThrow<java.lang.RuntimeException?, java.lang.RuntimeException?, java.lang.RuntimeException?>(
            skyKey,
            null,
            null,
            null
        )
    }

    /**
     * Returns a direct dependency. If the dependency is not in the set of already evaluated direct
     * dependencies, returns `null`. If the dependency has already been evaluated and found to
     * be in error, throws the exception coming from the error, so long as the exception is of one of
     * the specified types. SkyFunction implementations may use this method to catch and rethrow a
     * more informative exception or to continue evaluation. The caller must specify the exception
     * type(s) that might be thrown using the `exceptionClass` argument(s). If the dependency's
     * exception is not an instance of `exceptionClass`, `null` is returned. In this case,
     * [SkyFunction.Environment.valuesMissing] will subsequently return true.
     * 
     * 
     * The exception class given cannot be a supertype or a subtype of [RuntimeException], or
     * a subtype of [InterruptedException]. See [ ][SkyFunctionException.validateExceptionType] for details.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(E::class)
    fun <E : java.lang.Exception?> getOrThrow(skyKey: SkyKey?, exceptionClass: java.lang.Class<E?>?): SkyValue? {
        return getOrThrow<E?, java.lang.RuntimeException?, java.lang.RuntimeException?>(
            skyKey,
            exceptionClass,
            null,
            null
        )
    }

    /** Similar to [.getOrThrow], but takes two exception class parameters.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(E1::class, E2::class)
    fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?> getOrThrow(
        skyKey: SkyKey?, exceptionClass1: java.lang.Class<E1?>?, exceptionClass2: java.lang.Class<E2?>?
    ): SkyValue? {
        return getOrThrow<E1?, E2?, java.lang.RuntimeException?>(skyKey, exceptionClass1, exceptionClass2, null)
    }

    /** Similar to [.getOrThrow], but takes three exception class parameters.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(E1::class, E2::class, E3::class)
    fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?> getOrThrow(
        skyKey: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?
    ): SkyValue?

    /**
     * Similar to [.getOrThrow], but supports more flexible control flows.
     * 
     * 
     * This method provides a more generic exception handling interface than [.getOrThrow],
     * for cases where the caller cannot pass specific exception types.
     * 
     * @return true if either a value was passed to [QueryDepCallback.acceptValue] or an
     * exception was successfully handled by [QueryDepCallback.tryHandleException]. False
     * indicates that either the key is unavailable or an exception was unhandled by [     ][QueryDepCallback.tryHandleException].
     */
    fun queryDep(key: SkyKey?, resultCallback: QueryDepCallback?): Boolean

    /**
     * An interface specifying a dependency query.
     * 
     * 
     * [.queryDep] calls one of [QueryDepCallback.acceptValue] or [ ][QueryDepCallback.tryHandleException] if a result is available.
     */
    fun interface QueryDepCallback {
        /**
         * Accepts a value.
         * 
         * @param key the key associated with the value.
         */
        fun acceptValue(key: SkyKey?, value: SkyValue?)

        /**
         * Offers an exception to this query.
         * 
         * @param key the key associated with the exception.
         * @return true if the exception was handled.
         */
        fun tryHandleException(key: SkyKey?, e: java.lang.Exception?): Boolean {
            return false // Default implementation that handles no exceptions.
        }
    }
}
