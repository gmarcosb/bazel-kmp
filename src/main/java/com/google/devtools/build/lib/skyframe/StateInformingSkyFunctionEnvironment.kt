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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.events.ExtendedEventHandler

/** An environment that wraps each call to its delegate by informing injected [Informee]s.  */
internal class StateInformingSkyFunctionEnvironment(
    delegate: SkyFunction.Environment,
    preFetch: Informee,
    postFetch: Informee
) : SkyFunction.Environment {
    private val delegate: SkyFunction.Environment
    private val preFetch: Informee
    private val postFetch: Informee

    init {
        this.delegate = delegate
        this.preFetch = preFetch
        this.postFetch = postFetch
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getValue(valueName: SkyKey?): SkyValue? {
        preFetch.inform()
        try {
            return delegate.getValue(valueName)
        } finally {
            postFetch.inform()
        }
    }

    @Throws(E::class, java.lang.InterruptedException::class)
    override fun <E : java.lang.Exception?> getValueOrThrow(
        depKey: SkyKey?,
        exceptionClass: java.lang.Class<E?>?
    ): SkyValue? {
        preFetch.inform()
        try {
            return delegate.getValueOrThrow<E?>(depKey, exceptionClass)
        } finally {
            postFetch.inform()
        }
    }

    @Throws(E1::class, E2::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?> getValueOrThrow(
        depKey: SkyKey?, exceptionClass1: java.lang.Class<E1?>?, exceptionClass2: java.lang.Class<E2?>?
    ): SkyValue? {
        preFetch.inform()
        try {
            return delegate.getValueOrThrow<E1?, E2?>(depKey, exceptionClass1, exceptionClass2)
        } finally {
            postFetch.inform()
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
        preFetch.inform()
        try {
            return delegate.getValueOrThrow<E1?, E2?, E3?>(depKey, exceptionClass1, exceptionClass2, exceptionClass3)
        } finally {
            postFetch.inform()
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
        preFetch.inform()
        try {
            return delegate.getValueOrThrow<E1?, E2?, E3?, E4?>(
                depKey, exceptionClass1, exceptionClass2, exceptionClass3, exceptionClass4
            )
        } finally {
            postFetch.inform()
        }
    }

    override fun valuesMissing(): Boolean {
        return delegate.valuesMissing()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getValuesAndExceptions(depKeys: Iterable<out SkyKey?>?): SkyframeLookupResult? {
        preFetch.inform()
        try {
            return delegate.getValuesAndExceptions(depKeys)
        } finally {
            postFetch.inform()
        }
    }

    val listener: ExtendedEventHandler?
        get() = delegate.getListener()

    override fun inErrorBubbling(): Boolean {
        return delegate.inErrorBubbling()
    }

    val temporaryDirectDeps: GroupedDeps?
        get() = delegate.getTemporaryDirectDeps()

    override fun injectVersionForNonHermeticFunction(version: com.google.devtools.build.skyframe.Version?) {
        delegate.injectVersionForNonHermeticFunction(version)
    }

    override fun registerDependencies(keys: Iterable<SkyKey?>?) {
        delegate.registerDependencies(keys)
    }

    override fun dependOnFuture(future: com.google.common.util.concurrent.ListenableFuture<*>?) {
        delegate.dependOnFuture(future)
    }

    val lookupHandleForPreviouslyRequestedDeps: SkyframeLookupResult?
        get() = delegate.getLookupHandleForPreviouslyRequestedDeps()

    override fun <T : SkyKeyComputeState?> getState(stateSupplier: java.util.function.Supplier<T?>?): T? {
        return delegate.getState<T?>(stateSupplier)
    }

    internal interface Informee {
        @Throws(java.lang.InterruptedException::class)
        fun inform()
    }

    val maxTransitiveSourceVersionSoFar: com.google.devtools.build.skyframe.Version?
        get() = delegate.getMaxTransitiveSourceVersionSoFar()
}
