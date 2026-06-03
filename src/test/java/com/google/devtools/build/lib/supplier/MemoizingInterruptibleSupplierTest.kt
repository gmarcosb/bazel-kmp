// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.supplier

import com.google.common.testing.GcFinalization
import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.lang.ref.WeakReference

/** Tests for [MemoizingInterruptibleSupplier].  */
@RunWith(JUnit4::class)
class MemoizingInterruptibleSupplierTest {
    private var callCount = 0
    private var returnVal = ""
    private var callCounter: CallCounter? = MemoizingInterruptibleSupplierTest.CallCounter()

    private inner class CallCounter {
        fun call(): String {
            ++callCount
            return returnVal
        }
    }

    @Test
    @Throws(Exception::class)
    fun getReturnsCorrectResult() {
        val supplier =
            MemoizingInterruptibleSupplier.of({ callCounter!!.call() })
        returnVal = "abc"

        val result = supplier.get()

        Truth.assertThat(result).isEqualTo("abc")
    }

    @Test
    @Throws(Exception::class)
    fun subsequentCallToGetReturnsCorrectResult() {
        val supplier =
            MemoizingInterruptibleSupplier.of({ callCounter!!.call() })
        returnVal = "abc"

        supplier.get()
        val result = supplier.get()

        Truth.assertThat(result).isEqualTo("abc")
    }

    @Test
    @Throws(Exception::class)
    fun onlyCallsDelegateOnce() {
        val supplier =
            MemoizingInterruptibleSupplier.of({ callCounter!!.call() })

        supplier.get()
        supplier.get()

        Truth.assertThat(callCount).isEqualTo(1)
    }

    @Test
    @Throws(Exception::class)
    fun freesReferenceToDelegeteAfterGet() {
        val supplier =
            MemoizingInterruptibleSupplier.of({ callCounter!!.call() })
        val ref = WeakReference<Any?>(callCounter)
        callCounter = null

        supplier.get()

        GcFinalization.awaitClear(ref)
    }

    @Test
    fun notInitializedBeforeCallingGet() {
        val supplier =
            MemoizingInterruptibleSupplier.of({ callCounter!!.call() })

        val initialized = supplier.isInitialized

        Truth.assertThat(initialized).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun isInitializedAfterCallingGet() {
        val supplier =
            MemoizingInterruptibleSupplier.of({ callCounter!!.call() })

        supplier.get()
        val initialized = supplier.isInitialized

        Truth.assertThat(initialized).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun isStillInitializedAfterSubsequentCallToGet() {
        val supplier =
            MemoizingInterruptibleSupplier.of({ callCounter!!.call() })

        supplier.get()
        supplier.get()
        val initialized = supplier.isInitialized

        Truth.assertThat(initialized).isTrue()
    }

    @Test
    fun of_returnsSameInstanceIfAlreadyMemoizing() {
        val supplier: InterruptibleSupplier<String?>? = MemoizingInterruptibleSupplier.of({ callCounter!!.call() })
        assertThat(MemoizingInterruptibleSupplier.of(supplier)).isSameInstanceAs(supplier)
    }
}
