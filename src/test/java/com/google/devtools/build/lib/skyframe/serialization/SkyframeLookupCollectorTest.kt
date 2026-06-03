// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.PeerFailedException

@RunWith(JUnit4::class)
class SkyframeLookupCollectorTest {
    private val collector: SkyframeLookupCollector = SkyframeLookupCollector()

    @org.junit.Test
    fun exceptionNotification_marksAllLookupsAbandoned() {
        val parent1: AtomicReference<Any?> = AtomicReference<Any?>()
        val lookup1: SkyframeLookup<AtomicReference<Any?>?> =
            SkyframeLookup<AtomicReference<Any?>?>(
                createDummyKey(), parent1, { obj: AtomicReference<*>?, newValue: V? -> obj.set(newValue) })

        val parent2: AtomicReference<Any?> = AtomicReference<Any?>()
        val lookup2: SkyframeLookup<AtomicReference<Any?>?> =
            SkyframeLookup<AtomicReference<Any?>?>(
                createDummyKey(), parent2, { obj: AtomicReference<*>?, newValue: V? -> obj.set(newValue) })

        collector.addLookup(lookup1)
        collector.addLookup(lookup2)

        val exception: java.lang.Exception = java.lang.Exception("failed")
        collector.notifyFetchException(exception)

        val thrown: ExecutionException? =
            org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, collector::get)
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(exception)

        // Verifies that enqueued lookups are abandoned.
        assertHasPeerFailure(lookup1, exception)
        assertHasPeerFailure(lookup2, exception)

        // Verifies that subsequently added lookups are abandoned.
        val parent3: AtomicReference<Any?> = AtomicReference<Any?>()
        val lookup3: SkyframeLookup<AtomicReference<Any?>?> =
            SkyframeLookup<AtomicReference<Any?>?>(
                createDummyKey(), parent3, { obj: AtomicReference<*>?, newValue: V? -> obj.set(newValue) })
        collector.addLookup(lookup3)
        assertHasPeerFailure(lookup3, exception)
    }

    companion object {
        private val DUMMY_NAME: SkyFunctionName? = SkyFunctionName.createHermetic("FOR_TESTING")

        private fun assertHasPeerFailure(lookup: SkyframeLookup<*>, exception: java.lang.Exception?) {
            assertThat(lookup.isFailed()).isTrue()
            val thrown: ExecutionException =
                org.junit.Assert.assertThrows<ExecutionException>(ExecutionException::class.java, lookup::get)
            val cause: Throwable? = thrown.getCause()
            Truth.assertThat(cause).isInstanceOf(PeerFailedException::class.java)
            Truth.assertThat(cause).hasCauseThat().isSameInstanceAs(exception)
        }

        private fun createDummyKey(): SkyKey {
            return SkyKey { DUMMY_NAME }
        }
    }
}
