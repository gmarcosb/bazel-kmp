// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.events.ExtendedEventHandler

/** An environment that can observe the deps requested through getValue(s) calls.  */
class RecordingSkyFunctionEnvironment(
    delegate: com.google.devtools.build.skyframe.SkyFunction.Environment,
    skyKeyReceiver: java.util.function.Consumer<SkyKey?>,
    skyKeysReceiver: java.util.function.Consumer<Iterable<SkyKey?>?>,
    exceptionReceiver: java.util.function.Consumer<java.lang.Exception?>
) : com.google.devtools.build.skyframe.SkyFunction.Environment {
    private val delegate: com.google.devtools.build.skyframe.SkyFunction.Environment
    private val skyKeyReceiver: java.util.function.Consumer<SkyKey?>
    private val skyKeysReceiver: java.util.function.Consumer<Iterable<SkyKey?>?>
    private val exceptionReceiver: java.util.function.Consumer<java.lang.Exception?>

    init {
        this.delegate = delegate
        this.skyKeyReceiver = skyKeyReceiver
        this.skyKeysReceiver = skyKeysReceiver
        this.exceptionReceiver = exceptionReceiver
    }

    private fun recordDep(key: SkyKey?) {
        skyKeyReceiver.accept(key)
    }

    // Cast Iterable<? extends SkyKey> to Iterable<SkyKey>.
    private fun recordDeps(keys: Iterable<out SkyKey?>?) {
        skyKeysReceiver.accept(keys as Iterable<SkyKey?>?)
    }

    private fun noteException(e: java.lang.Exception?) {
        exceptionReceiver.accept(e)
    }

    fun getDelegate(): com.google.devtools.build.skyframe.SkyFunction.Environment {
        return delegate
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getValue(valueName: SkyKey?): SkyValue? {
        recordDep(valueName)
        return delegate.getValue(valueName)
    }

    @Throws(E::class, java.lang.InterruptedException::class)
    override fun <E : java.lang.Exception?> getValueOrThrow(
        depKey: SkyKey?,
        exceptionClass: java.lang.Class<E?>?
    ): SkyValue? {
        recordDep(depKey)
        try {
            return delegate.getValueOrThrow<E?>(depKey, exceptionClass)
        } catch (e: java.lang.Exception) {
            noteException(e)
            throw e
        }
    }

    @Throws(E1::class, E2::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?> getValueOrThrow(
        depKey: SkyKey?, exceptionClass1: java.lang.Class<E1?>?, exceptionClass2: java.lang.Class<E2?>?
    ): SkyValue? {
        recordDep(depKey)
        try {
            return delegate.getValueOrThrow<E1?, E2?>(depKey, exceptionClass1, exceptionClass2)
        } catch (e: java.lang.Exception) {
            noteException(e)
            throw e
        }
    }

    @Throws(E1::class, E2::class, E3::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?>
            getValueOrThrow(
        depKey: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?
    ): SkyValue? {
        recordDep(depKey)
        try {
            return delegate.getValueOrThrow<E1?, E2?, E3?>(depKey, exceptionClass1, exceptionClass2, exceptionClass3)
        } catch (e: java.lang.Exception) {
            noteException(e)
            throw e
        }
    }

    @Throws(E1::class, E2::class, E3::class, E4::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?, E4 : java.lang.Exception?>
            getValueOrThrow(
        depKey: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?,
        exceptionClass4: java.lang.Class<E4?>?
    ): SkyValue? {
        recordDep(depKey)
        try {
            return delegate.getValueOrThrow<E1?, E2?, E3?, E4?>(
                depKey, exceptionClass1, exceptionClass2, exceptionClass3, exceptionClass4
            )
        } catch (e: java.lang.Exception) {
            noteException(e)
            throw e
        }
    }

    override fun valuesMissing(): Boolean {
        return delegate.valuesMissing()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getValuesAndExceptions(depKeys: Iterable<out SkyKey?>?): SkyframeLookupResult? {
        recordDeps(depKeys)
        try {
            return delegate.getValuesAndExceptions(depKeys)
        } catch (e: java.lang.Exception) {
            noteException(e)
            throw e
        }
    }

    override fun getListener(): ExtendedEventHandler? {
        return delegate.getListener()
    }

    override fun inErrorBubbling(): Boolean {
        return delegate.inErrorBubbling()
    }

    override fun getTemporaryDirectDeps(): GroupedDeps? {
        return delegate.getTemporaryDirectDeps()
    }

    override fun injectVersionForNonHermeticFunction(version: com.google.devtools.build.skyframe.Version?) {
        delegate.injectVersionForNonHermeticFunction(version)
    }

    override fun registerDependencies(keys: Iterable<SkyKey?>?) {
        delegate.registerDependencies(keys)
    }

    override fun dependOnFuture(future: com.google.common.util.concurrent.ListenableFuture<*>?) {
        delegate.dependOnFuture(future)
    }

    override fun getLookupHandleForPreviouslyRequestedDeps(): SkyframeLookupResult? {
        return delegate.getLookupHandleForPreviouslyRequestedDeps()
    }

    override fun <T : SkyKeyComputeState?> getState(stateSupplier: java.util.function.Supplier<T?>?): T? {
        return delegate.getState<T?>(stateSupplier)
    }

    override fun getMaxTransitiveSourceVersionSoFar(): com.google.devtools.build.skyframe.Version? {
        return delegate.getMaxTransitiveSourceVersionSoFar()
    }
}
