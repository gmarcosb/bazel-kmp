// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.util

import com.google.devtools.build.lib.remote.util.RxUtils.mergeBulkTransfer

/** Tests for [RxUtils].  */
@RunWith(JUnit4::class)
class RxUtilsTest {
    @org.junit.Rule
    val rxNoGlobalErrorsRule: RxNoGlobalErrorsRule = RxNoGlobalErrorsRule()

    internal class SettableCompletable : Completable() {
        private val emitterRef: AtomicReference<CompletableEmitter?> = AtomicReference<CompletableEmitter?>(null)
        private val cancelled: AtomicBoolean = AtomicBoolean(false)
        private val completed: AtomicBoolean = AtomicBoolean(false)
        private val completable: Completable = Completable.create(
            CompletableOnSubscribe { emitter: CompletableEmitter? ->
                emitterRef.set(emitter)
                emitter.setCancellable(
                    io.reactivex.rxjava3.functions.Cancellable {
                        if (!completed.get()) {
                            cancelled.set(true)
                        }
                    })
            })

        override fun subscribeActual(observer: CompletableObserver) {
            completable.subscribe(observer)
        }

        fun setComplete() {
            completed.set(true)
            emitterRef.get().onComplete()
        }

        fun setError(error: Throwable?) {
            completed.set(true)
            emitterRef.get().onError(error)
        }

        fun cancelled(): Boolean {
            return cancelled.get()
        }

        companion object {
            fun create(): SettableCompletable {
                return SettableCompletable()
            }
        }
    }

    @org.junit.Test
    fun toTransferResult_onComplete_isOk() {
        val transfer = SettableCompletable.Companion.create()
        val ob: TestObserver<TransferResult?> = toTransferResult(transfer).test()

        transfer.setComplete()

        ob.assertValue(
            io.reactivex.rxjava3.functions.Predicate { result: TransferResult? ->
                assertThat(result.isOk()).isTrue()
                assertThat(result.isError()).isFalse()
                true
            })
    }

    @org.junit.Test
    fun toTransferResult_onIOException_isError() {
        val transfer = SettableCompletable.Companion.create()
        val ob: TestObserver<TransferResult?> = toTransferResult(transfer).test()
        val error: IOException = IOException("IO error")

        transfer.setError(error)

        ob.assertValue(
            io.reactivex.rxjava3.functions.Predicate { result: TransferResult? ->
                assertThat(result.isOk()).isFalse()
                assertThat(result.isError()).isTrue()
                assertThat(result.getError()).isEqualTo(error)
                true
            })
    }

    @org.junit.Test
    fun toTransferResult_onOtherError_propagateError() {
        val transfer = SettableCompletable.Companion.create()
        val ob: TestObserver<TransferResult?> = toTransferResult(transfer).test()
        val error: java.lang.Exception = java.lang.Exception("other error")

        transfer.setError(error)

        ob.assertError(error)
    }

    @org.junit.Test
    fun mergeBulkTransfer_allComplete_complete() {
        val transfer1 = SettableCompletable.Companion.create()
        val transfer2 = SettableCompletable.Companion.create()
        val transfer3 = SettableCompletable.Companion.create()
        val ob: TestObserver<java.lang.Void?> = mergeBulkTransfer(transfer1, transfer2, transfer3).test()

        transfer1.setComplete()
        transfer2.setComplete()
        transfer3.setComplete()

        ob.assertComplete()
    }

    @org.junit.Test
    fun mergeBulkTransfer_hasPendingTransfer_pending() {
        val transfer1 = SettableCompletable.Companion.create()
        val transfer2 = SettableCompletable.Companion.create()
        val transfer3 = SettableCompletable.Companion.create()
        val ob: TestObserver<java.lang.Void?> = mergeBulkTransfer(transfer1, transfer2, transfer3).test()

        transfer1.setComplete()
        transfer2.setComplete()

        ob.assertNotComplete()
        ob.assertNoErrors()
    }

    @org.junit.Test
    fun mergeBulkTransfer_onIOErrors_keepOtherTransfers() {
        val transfer1 = SettableCompletable.Companion.create()
        val transfer2 = SettableCompletable.Companion.create()
        val transfer3 = SettableCompletable.Companion.create()
        val ob: TestObserver<java.lang.Void?> = mergeBulkTransfer(transfer1, transfer2, transfer3).test()
        val error: IOException = IOException("IO error")

        transfer1.setError(error)
        transfer2.setComplete()
        transfer3.setComplete()

        ob.assertError(BulkTransferException::class.java)
        Truth.assertThat(transfer2.cancelled()).isFalse()
        Truth.assertThat(transfer3.cancelled()).isFalse()
    }

    @org.junit.Test
    fun mergeBulkTransfer_onIOErrors_wrapsIOErrorsInBulkTransferExceptions() {
        val transfer1 = SettableCompletable.Companion.create()
        val transfer2 = SettableCompletable.Companion.create()
        val transfer3 = SettableCompletable.Companion.create()
        val ob: TestObserver<java.lang.Void?> = mergeBulkTransfer(transfer1, transfer2, transfer3).test()
        val error1: IOException = IOException("IO error 1")
        val error2: IOException = IOException("IO error 2")

        transfer1.setError(error1)
        transfer2.setError(error2)
        transfer3.setComplete()

        ob.assertError(
            io.reactivex.rxjava3.functions.Predicate { e: Throwable? ->
                Truth.assertThat(e).isInstanceOf(BulkTransferException::class.java)
                Truth.assertThat(com.google.common.collect.ImmutableList.copyOf<Throwable?>(e.getSuppressed()))
                    .containsExactly(error1, error2)
                true
            })
    }

    @org.junit.Test
    fun mergeBulkTransfer_onOtherError_cancelOtherTransfers() {
        val transfer1 = SettableCompletable.Companion.create()
        val transfer2 = SettableCompletable.Companion.create()
        val transfer3 = SettableCompletable.Companion.create()
        val ob: TestObserver<java.lang.Void?> = mergeBulkTransfer(transfer1, transfer2, transfer3).test()
        val error: java.lang.Exception = java.lang.Exception("error")

        transfer1.setError(error)

        ob.assertError(error)
        Truth.assertThat(transfer2.cancelled()).isTrue()
        Truth.assertThat(transfer3.cancelled()).isTrue()
    }

    @org.junit.Test
    fun mergeBulkTransfer_onInterruption_cancelOtherTransfers() {
        val transfer1 = SettableCompletable.Companion.create()
        val transfer2 = SettableCompletable.Companion.create()
        val transfer3 = SettableCompletable.Companion.create()

        java.lang.Thread.currentThread().interrupt()
        var error: java.lang.RuntimeException? = null
        try {
            mergeBulkTransfer(transfer1, transfer2, transfer3).blockingAwait()
        } catch (e: java.lang.RuntimeException) {
            error = e
        }

        Truth.assertThat(error).hasCauseThat().isInstanceOf(java.lang.InterruptedException::class.java)
        Truth.assertThat(transfer1.cancelled()).isTrue()
        Truth.assertThat(transfer2.cancelled()).isTrue()
        Truth.assertThat(transfer3.cancelled()).isTrue()
    }
}
