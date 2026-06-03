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

import com.google.common.truth.Truth
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicReference

/** Unit test for [LatestObjectMetricExporter].  */
@RunWith(JUnit4::class)
class LatestObjectMetricExporterTest {
    @org.junit.Test
    fun weakReferencesAreGarbageCollected() {
        // Create an exporter with the given strength and whose registration stores the Supplier<Object>
        // in an AtomicReference.
        val strength: LatestObjectMetricExporter.Strength? = LatestObjectMetricExporter.Strength.WEAK
        val registeredSupplierRef: AtomicReference<java.util.function.Supplier<Any?>?> =
            AtomicReference<java.util.function.Supplier<Any?>?>(null)
        val registration: LatestObjectMetricExporter.CallbackRegistration<Any?> =
            LatestObjectMetricExporter.CallbackRegistration { newValue: V? -> registeredSupplierRef.set(newValue) }
        val exporter: LatestObjectMetricExporter<Any?> =
            LatestObjectMetricExporter(strength, registration)
        Truth.assertThat(registeredSupplierRef.get()).isNull()
        // Set up three Objects to serve as dummy "latest objects" to pass through the exporter.
        var first: Any? = Any()
        var second: Any? = Any()
        var third: Any? = Any()
        // Set the first value, at which point the registration will run and we will get the Supplier
        // that tells us the currently exported object.
        exporter.setLatestInstance(first)
        val latestObjectSupplier: java.util.function.Supplier<Any?>? = registeredSupplierRef.get()
        Truth.assertThat(latestObjectSupplier).isNotNull()
        Truth.assertThat(latestObjectSupplier.get()).isSameInstanceAs(first)

        // Remove only reference to the latest object and run the GC. The supplier should start
        // producing null, not `first`.
        first = null
        val runtime: java.lang.Runtime = java.lang.Runtime.getRuntime()
        runtime.gc()
        Truth.assertThat(latestObjectSupplier.get()).isNull()

        // Remove only reference to the latest object but don't run the GC. The supplier should still
        // return `second` until we change the latest inatance to `third`, at which point GC has no
        // observable effect..
        exporter.setLatestInstance(second)
        Truth.assertThat(latestObjectSupplier.get()).isSameInstanceAs(second)
        second = null
        exporter.setLatestInstance(third)
        Truth.assertThat(latestObjectSupplier.get()).isSameInstanceAs(third)
        runtime.gc()
        Truth.assertThat(latestObjectSupplier.get()).isSameInstanceAs(third)

        // Repeat the first assertion: removing the reference and GCing will cause the Supplier
        // to produce null, not `third`.
        third = null
        runtime.gc()
        Truth.assertThat(latestObjectSupplier.get()).isNull()
    }
}
