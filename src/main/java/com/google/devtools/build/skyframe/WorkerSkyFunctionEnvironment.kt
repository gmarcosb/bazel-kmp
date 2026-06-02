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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.events.Event

/**
 * A [SkyFunction.Environment] implementation designed to be used in a different thread (the
 * "worker thread") than the corresponding SkyFunction runs in. It relies on a delegate Environment
 * object to do underlying work. Its [.getValue] and [.getValueOrThrow] methods do not
 * return `null` when the [SkyValue] in question is not available. Instead, it blocks
 * and waits for the host Skyframe thread to restart, and replaces the delegate Environment with a
 * fresh one from the restarted SkyFunction before continuing. (Note that those methods *do*
 * return `null` if the SkyValue was evaluated but found to be in error.)
 * 
 * 
 * Crucially, the delegate Environment object must not be used by multiple threads at the same
 * time. In effect, this is guaranteed by only one of the worker thread and host thread being active
 * at any given time.
 */
internal class WorkerSkyFunctionEnvironment
    (
    initialDelegate: com.google.devtools.build.skyframe.SkyFunction.Environment?,
    newDelegateSupplier: InterruptibleSupplier<com.google.devtools.build.skyframe.SkyFunction.Environment?>
) : com.google.devtools.build.skyframe.SkyFunction.Environment, ExtendedEventHandler, SkyframeLookupResult {
    private var delegate: com.google.devtools.build.skyframe.SkyFunction.Environment?
    private val newDelegateSupplier: InterruptibleSupplier<com.google.devtools.build.skyframe.SkyFunction.Environment?>

    init {
        this.delegate = initialDelegate
        this.newDelegateSupplier = newDelegateSupplier
    }

    override fun valuesMissing(): Boolean {
        return delegate.valuesMissing()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getValuesAndExceptions(depKeys: Iterable<out SkyKey?>?): SkyframeLookupResult {
        delegate.getValuesAndExceptions(depKeys)
        if (!delegate.valuesMissing()) {
            // Do NOT just return the return value of `delegate.getValuesAndExceptions` here! That would
            // cause anyone holding onto the returned result object to potentially use a stale version
            // of it after a skyfunction restart.
            return this
        }
        // We null out `delegate` before blocking for the fresh env so that the old one becomes
        // eligible for GC.
        delegate = null
        delegate = newDelegateSupplier.get()
        delegate.getValuesAndExceptions(depKeys)
        return this
    }

    @Throws(E1::class, E2::class, E3::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?> getOrThrow(
        skyKey: SkyKey?, e1: java.lang.Class<E1?>?, e2: java.lang.Class<E2?>?, e3: java.lang.Class<E3?>?
    ): SkyValue? {
        return delegate.getLookupHandleForPreviouslyRequestedDeps().getOrThrow<E1?, E2?, E3?>(skyKey, e1, e2, e3)
    }

    override fun queryDep(key: SkyKey?, resultCallback: QueryDepCallback?): Boolean {
        return delegate.getLookupHandleForPreviouslyRequestedDeps().queryDep(key, resultCallback)
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getValue(depKey: SkyKey): SkyValue? {
        return getValuesAndExceptions(com.google.common.collect.ImmutableList.of<SkyKey?>(depKey)).get(depKey)
    }

    @Throws(E1::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?> getValueOrThrow(depKey: SkyKey, e1: java.lang.Class<E1?>?): SkyValue? {
        return getValuesAndExceptions(com.google.common.collect.ImmutableList.of<SkyKey?>(depKey)).getOrThrow<E1?>(
            depKey,
            e1
        )
    }

    @Throws(E1::class, E2::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?> getValueOrThrow(
        depKey: SkyKey, e1: java.lang.Class<E1?>?, e2: java.lang.Class<E2?>?
    ): SkyValue? {
        return getValuesAndExceptions(com.google.common.collect.ImmutableList.of<SkyKey?>(depKey)).getOrThrow<E1?, E2?>(
            depKey,
            e1,
            e2
        )
    }

    @Throws(E1::class, E2::class, E3::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?>
            getValueOrThrow(
        depKey: SkyKey,
        e1: java.lang.Class<E1?>?,
        e2: java.lang.Class<E2?>?,
        e3: java.lang.Class<E3?>?
    ): SkyValue? {
        return getValuesAndExceptions(com.google.common.collect.ImmutableList.of<SkyKey?>(depKey)).getOrThrow<E1?, E2?, E3?>(
            depKey,
            e1,
            e2,
            e3
        )
    }

    @Throws(E1::class, E2::class, E3::class, E4::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?, E4 : java.lang.Exception?>
            getValueOrThrow(
        depKey: SkyKey?,
        e1: java.lang.Class<E1?>?,
        e2: java.lang.Class<E2?>?,
        e3: java.lang.Class<E3?>?,
        e4: java.lang.Class<E4?>?
    ): SkyValue? {
        val value: SkyValue? = delegate.getValueOrThrow<E1?, E2?, E3?, E4?>(depKey, e1, e2, e3, e4)
        if (value != null) {
            return value
        }
        // We null out `delegate` before blocking for the fresh env so that the old one becomes
        // eligible for GC.
        delegate = null
        delegate = newDelegateSupplier.get()
        return delegate.getValueOrThrow<E1?, E2?, E3?, E4?>(depKey, e1, e2, e3, e4)
    }

    override fun getListener(): ExtendedEventHandler? {
        // Do NOT just return `delegate.getListener()` here! That would cause anyone holding onto the
        // returned listener to potentially post events to a stale listener.
        return this
    }

    public override fun post(obj: Postable?) {
        delegate.getListener().post(obj)
    }

    public override fun handle(event: Event?) {
        delegate.getListener().handle(event)
    }

    override fun registerDependencies(keys: Iterable<SkyKey?>?) {
        delegate.registerDependencies(keys)
    }

    override fun inErrorBubbling(): Boolean {
        return delegate.inErrorBubbling()
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
