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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * Exporter for a callback metric instrumenting a singleton that may not be created, and when
 * created, may be discarded and re-created.
 * 
 * 
 * Lazily registers a callback-metric with a thread-safe [Supplier] of the latest value of
 * that reference. Lazily registering the callback metric reduces metric pollution when the
 * instrumented codepaths are never executed.
 * 
 * 
 * Weak/soft references must be used to allow the instrumented object to be GCed; callbacks must
 * expect `null` values. Note that in some instrumentation libraries it is impossible to stop
 * exporting a given metric.
 * 
 * 
 * Simple usage example based on the open-source `io.opentelemetry.api.metrics` API:
 * 
 * <pre>
 * class FooManager {
 * private static final ObservableLongMeasurement fooMetric =
 * MyMeterProvider.get().gaugeBuilder("foo").ofLongs().buildObserver();
 * private static final ObservableLongMeasurement barMetric =
 * MyMeterProvider.get().gaugeBuilder("bar").ofLongs().buildObserver();
 * static void updateMetric(FooManager manager) {
 * fooMetric.record(manager == null ? 0L : manager.getFoo());
 * barMetric.record(manager == null ? 0L : manager.getBar());
 * }
 * private static final LatestObjectMetricExporter&lt;FooManager&gt; FOO_MANAGER_EXPORTER =
 * new LatestObjectMetricExporter&lt;&gt;(
 * LatestObjectMetricExporter.Strength.WEAK,
 * (supplier) -> MyMeterProvider.get().batchCallback(
 * () -> updateMetric(supplier.get()),
 * fooMetric,
 * barMetric));
 * 
 * // Need some state fields to export.
 * \@GuardedBy("this") private Foo foo;
 * \@GuardedBy("this") private Bar bar;
 * FooManager(Foo foo, Bar bar) {
 * // Initialize state fields before exporting the FooManager.
 * this.bar = bar;
 * this.bar = bar;
 * FOO_MANAGER_EXPORTER.setLatestInstance(this);
 * }
 * // Measurements must be thread-safe.
 * synchronized long getFoo() {
 * return bar.getFooSize();
 * }
 * synchronized long getBar() {
 * return bar.getBarSize();
 * }
 * }
</pre> * 
 * 
 * @param <T> Type of the *latest object* being tracked.
</T> */
@ThreadSafe
class LatestObjectMetricExporter<T>(
    /** The reference strength used for the latest object.  */
    private val strength: Strength,
    /**
     * Registration callback that will be invoked at most once, the first time [ ][LatestObjectMetricExporter.setLatestInstance] is called.
     */
    private val registration: CallbackRegistration<T?>
) {
    /**
     * Metric-specific callback, run once the first time a [LatestObjectMetricExporter] is used.
     */
    interface CallbackRegistration<T> {
        /**
         * One-time setup method expected to register callback metrics with the instrumentation
         * library's metric registry.
         * 
         * 
         * Callbacks are expected to use the given [Supplier] to get the latest instance (or
         * `null` if the latest instance has been GCed).
         */
        fun register(refSupplier: java.util.function.Supplier<T?>?)
    }

    /** Kind of reference held by the exporter.  */
    enum class Strength {
        /** Creates [WeakReference] instances.  */
        WEAK,

        /** Creates [SoftReference] instances.  */
        SOFT;

        /** Create a new Reference for the given value, which may be `null`.  */
        fun <T> makeRef(value: T?): java.lang.ref.Reference<T?> {
            when (this) {
                com.google.devtools.build.lib.util.LatestObjectMetricExporter.Strength.WEAK -> return java.lang.ref.WeakReference<T?>(
                    value
                )

                com.google.devtools.build.lib.util.LatestObjectMetricExporter.Strength.SOFT -> return java.lang.ref.SoftReference<T?>(
                    value
                )
            }
            throw java.lang.IllegalStateException("unexpected reference strength: " + name)
        }
    }

    /** Flag that is set after the callback registration method has been called.  */
    @javax.annotation.concurrent.GuardedBy("this")
    private var callbackRegistered = false

    /**
     * Reference to the last [object][T] created by Blaze; as a weak/soft reference, will be null
     * if it has been GCed.
     * 
     * 
     * We don't use an [java.util.concurrent.atomic.AtomicReference] because we don't know
     * (other than a finalizer) when to clear the reference to avoid leaking memory.
     */
    @javax.annotation.concurrent.GuardedBy("this")
    private var reference: java.lang.ref.Reference<T?>

    /** Create a singleton exporter with the given reference strength and registration callback.  */
    init {
        reference = strength.makeRef<T?>(null)
    }

    /**
     * Sets the latest instance of the instrumented singleton (through the Supplier passed to the
     * exporter's [CallbackRegistration]).
     * 
     * 
     * If this is the first time the method has been called, `registration#register()` will
     * be called after changing [.reference].
     */
    @kotlin.jvm.Synchronized
    fun setLatestInstance(value: T?) {
        reference = strength.makeRef<T?>(value)
        if (!callbackRegistered) {
            registration.register(
                java.util.function.Supplier {
                    synchronized(this@LatestObjectMetricExporter) {
                        return@register reference.get()
                    }
                })
            callbackRegistered = true
        }
    }
}
