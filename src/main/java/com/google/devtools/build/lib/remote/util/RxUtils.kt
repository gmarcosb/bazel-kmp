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

import com.google.devtools.build.lib.remote.common.BulkTransferException

/** Utility methods for the Rx. *  */
object RxUtils {
    /**
     * Converts the [Completable] to [Single] which will emit [TransferResult] on
     * complete or IO errors. Other errors will be propagated to downstream.
     */
    fun toTransferResult(completable: Completable): Single<TransferResult?>? {
        return completable
            .toSingleDefault<TransferResult?>(com.google.devtools.build.lib.remote.util.RxUtils.TransferResult.Companion.ok())
            .onErrorResumeNext(
                io.reactivex.rxjava3.functions.Function { error: Throwable? ->
                    if (error is IOException) {
                        return@onErrorResumeNext Single.just<TransferResult?>(
                            com.google.devtools.build.lib.remote.util.RxUtils.TransferResult.Companion.error(
                                error
                            )
                        )
                    } else if (error is java.lang.InterruptedException) {
                        return@onErrorResumeNext Single.just<TransferResult?>(com.google.devtools.build.lib.remote.util.RxUtils.TransferResult.Companion.interrupted())
                    } else {
                        return@onErrorResumeNext Single.error<TransferResult?>(error)
                    }
                })
    }

    /**
     * Returns a [Completable] which will complete when the [Flowable] complete.
     * 
     * 
     * Errors of [TransferResult.getError] are wrapped in [BulkTransferException].
     * Other errors are propagated to downstream.
     */
    fun mergeBulkTransfer(transfers: Flowable<TransferResult?>): Completable? {
        return transfers
            .collectInto<BulkTransferExceptionCollector?>(
                BulkTransferExceptionCollector(),
                io.reactivex.rxjava3.functions.BiConsumer { obj: BulkTransferExceptionCollector?, result: TransferResult ->
                    obj!!.onResult(result)
                })
            .flatMapCompletable(io.reactivex.rxjava3.functions.Function { obj: BulkTransferExceptionCollector? -> obj!!.toCompletable() })
    }

    /**
     * Returns a [Completable] which will complete when all the passed in [Completable]s
     * complete.
     * 
     * 
     * [IOException]s emitted by the passed in [Completable]s are wrapped in [ ]. Other errors are propagated to downstream.
     */
    fun mergeBulkTransfer(vararg transfers: Completable?): Completable? {
        val flowable: Flowable<TransferResult?> =
            Flowable.fromArray<Completable?>(*transfers)
                .flatMapSingle<TransferResult?>(io.reactivex.rxjava3.functions.Function { obj: RxUtils?, completable: Completable ->
                    toTransferResult(completable)
                })
        return RxUtils.mergeBulkTransfer(flowable)
    }

    /** Result of an I/O operation to remote cache.  */
    class TransferResult internal constructor(error: IOException?, interrupted: Boolean) {
        private val error: IOException?

        val isInterrupted: Boolean

        init {
            this.error = error
            this.isInterrupted = interrupted
        }

        val isOk: Boolean
            /** Returns `true` if the operation succeed.  */
            get() = error == null && !this.isInterrupted

        /** Returns `true` if the operation failed.  */
        fun isError(): Boolean {
            return error != null
        }

        /** Returns the IO error if the operation failed.  */
        fun getError(): IOException? {
            return error
        }

        companion object {
            private val OK = TransferResult(null, false)

            private val INTERRUPTED = TransferResult(null, true)

            fun ok(): TransferResult {
                return com.google.devtools.build.lib.remote.util.RxUtils.TransferResult.Companion.OK
            }

            fun interrupted(): TransferResult {
                return com.google.devtools.build.lib.remote.util.RxUtils.TransferResult.Companion.INTERRUPTED
            }

            fun error(error: IOException?): TransferResult {
                return TransferResult(error, false)
            }
        }
    }

    private class BulkTransferExceptionCollector {
        private var bulkTransferException: BulkTransferException? = null
        private var interrupted = false

        fun onResult(result: TransferResult) {
            if (result.isOk) {
                return
            }

            if (result.isInterrupted) {
                interrupted = true
                return
            }

            val error: IOException?
            IOException > com.google.common.base.Preconditions.checkNotNull<IOException?>(result.getError())
            if (bulkTransferException == null) {
                bulkTransferException = BulkTransferException()
            }

            bulkTransferException.add(error)
        }

        fun toCompletable(): Completable? {
            if (interrupted) {
                return Completable.error(java.lang.InterruptedException())
            }

            if (bulkTransferException != null) {
                return Completable.error(bulkTransferException)
            }

            return Completable.complete()
        }
    }
}
