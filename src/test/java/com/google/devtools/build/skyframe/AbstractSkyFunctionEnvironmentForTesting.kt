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

import com.google.devtools.build.skyframe.SimpleSkyframeLookupResult
import com.google.devtools.build.skyframe.ValueOrUntypedException

/**
 * Partial [SkyFunction.Environment] implementation that allows tests to deal with simple
 * types like [ValueOrUntypedException] and [ImmutableMap].
 */
abstract class AbstractSkyFunctionEnvironmentForTesting

    : AbstractSkyFunctionEnvironment() {
    /**
     * Gets a map of values or exceptions.
     * 
     * 
     * Implementations should set [.valuesMissing] as necessary.
     */
    @com.google.errorprone.annotations.ForOverride
    @Throws(java.lang.InterruptedException::class)
    protected abstract fun getValueOrUntypedExceptions(
        depKeys: Iterable<out SkyKey?>?
    ): com.google.common.collect.ImmutableMap<SkyKey?, ValueOrUntypedException?>

    @Throws(E1::class, E2::class, E3::class, E4::class, java.lang.InterruptedException::class)
    public override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?, E4 : java.lang.Exception?>
            getValueOrThrowInternal(
        depKey: SkyKey,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?,
        exceptionClass4: java.lang.Class<E4?>?
    ): SkyValue? {
        val voe: ValueOrUntypedException? =
            getValueOrUntypedExceptions(com.google.common.collect.ImmutableList.of<SkyKey?>(depKey)).get(depKey)
        val value: SkyValue? = voe.getValue()
        if (value != null) {
            return value
        }
        SkyFunctionException.throwIfInstanceOf(
            voe.getException(), exceptionClass1, exceptionClass2, exceptionClass3, exceptionClass4
        )
        valuesMissing = true
        return null
    }

    @Throws(java.lang.InterruptedException::class)
    public override fun getValuesAndExceptions(depKeys: Iterable<out SkyKey?>?): SkyframeLookupResult {
        val valuesOrExceptions: MutableMap<SkyKey?, ValueOrUntypedException?> = getValueOrUntypedExceptions(depKeys)
        return SimpleSkyframeLookupResult(
            java.lang.Runnable { valuesMissing = true },
            java.util.function.Function { key: SkyKey? -> valuesOrExceptions.get(key) })
    }

    val lookupHandleForPreviouslyRequestedDeps: SkyframeLookupResult?
        get() {
            throw java.lang.UnsupportedOperationException()
        }

    public override fun registerDependencies(keys: Iterable<SkyKey?>?) {
        throw java.lang.UnsupportedOperationException()
    }

    public override fun dependOnFuture(future: com.google.common.util.concurrent.ListenableFuture<*>?) {
        throw java.lang.UnsupportedOperationException()
    }

    val maxTransitiveSourceVersionSoFar: Version?
        get() {
            throw java.lang.UnsupportedOperationException()
        }

    public override fun inErrorBubbling(): Boolean {
        return false
    }

    public override fun <T : SkyKeyComputeState?> getState(stateSupplier: java.util.function.Supplier<T?>): T? {
        return stateSupplier.get()
    }
}
