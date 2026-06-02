// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.skyframe.SkyFunction.LookupEnvironment
import com.google.devtools.build.skyframe.SkyFunctionEnvironment
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.SkyframeLookupResult
import com.google.devtools.build.skyframe.SkyframeLookupResult.QueryDepCallback

/**
 * Used when multiple bazel State Machines' [com.google.devtools.build.skyframe.state.Driver]s
 * need to concurrently access [LookupEnvironment] and [SkyframeLookupResult] to query
 * and fetch dependency values.
 * 
 * 
 * In the bazel State Machine logic, we assume that [LookupEnvironment] and [ ] refer to the same instance. So [ConcurrentSkyFunctionEnvironment]
 * implements methods from both interfaces and all relevant operations are thread-safe.
 */
class ConcurrentSkyFunctionEnvironment
    (delegate: SkyFunctionEnvironment) : LookupEnvironment, SkyframeLookupResult {
    private val delegate: SkyFunctionEnvironment

    init {
        this.delegate = delegate
    }

    @kotlin.jvm.Synchronized
    @Throws(java.lang.InterruptedException::class)
    override fun getValue(valueName: SkyKey?): SkyValue? {
        return delegate.getValue(valueName)
    }

    @kotlin.jvm.Synchronized
    @Throws(E::class, java.lang.InterruptedException::class)
    override fun <E : java.lang.Exception?> getValueOrThrow(
        depKey: SkyKey?, exceptionClass: java.lang.Class<E?>?
    ): SkyValue? {
        return delegate.getValueOrThrow<E?>(depKey, exceptionClass)
    }

    @kotlin.jvm.Synchronized
    @Throws(E1::class, E2::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?> getValueOrThrow(
        depKey: SkyKey?, exceptionClass1: java.lang.Class<E1?>?, exceptionClass2: java.lang.Class<E2?>?
    ): SkyValue? {
        return delegate.getValueOrThrow<E1?, E2?>(depKey, exceptionClass1, exceptionClass2)
    }

    @kotlin.jvm.Synchronized
    @Throws(E1::class, E2::class, E3::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?>
            getValueOrThrow(
        depKey: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?
    ): SkyValue? {
        return delegate.getValueOrThrow<E1?, E2?, E3?>(depKey, exceptionClass1, exceptionClass2, exceptionClass3)
    }

    @kotlin.jvm.Synchronized
    @Throws(E1::class, E2::class, E3::class, E4::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?, E4 : java.lang.Exception?>
            getValueOrThrow(
        depKey: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?,
        exceptionClass4: java.lang.Class<E4?>?
    ): SkyValue? {
        return delegate.getValueOrThrow<E1?, E2?, E3?, E4?>(
            depKey, exceptionClass1, exceptionClass2, exceptionClass3, exceptionClass4
        )
    }

    @kotlin.jvm.Synchronized
    @Throws(java.lang.InterruptedException::class)
    override fun getValuesAndExceptions(
        depKeys: Iterable<out SkyKey?>
    ): SkyframeLookupResult {
        // When calling `SkyFunctionEnvironment#getValuesAndExceptions`, `delegate` looks up deps in the
        // dependency graph and returns itself as a `SkyframeLookupResult` instance. The `checkState`
        // verifies the returned instance does not change.
        //
        // `getLookupHandleForPreviouslyRequestedDeps` below follows the same intuition.
        com.google.common.base.Preconditions.checkState(delegate.getValuesAndExceptions(depKeys) === delegate)
        return this
    }

    @get:kotlin.jvm.Synchronized
    val lookupHandleForPreviouslyRequestedDeps: SkyframeLookupResult
        get() {
            com.google.common.base.Preconditions.checkState(delegate.getLookupHandleForPreviouslyRequestedDeps() === delegate)
            return this
        }

    @kotlin.jvm.Synchronized
    @Throws(E1::class, E2::class, E3::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?>
            getOrThrow(
        skyKey: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?
    ): SkyValue? {
        return delegate.getOrThrow<E1?, E2?, E3?>(skyKey, exceptionClass1, exceptionClass2, exceptionClass3)
    }

    @kotlin.jvm.Synchronized
    override fun queryDep(key: SkyKey?, resultCallback: QueryDepCallback?): Boolean {
        return delegate.queryDep(key, resultCallback)
    }
}
