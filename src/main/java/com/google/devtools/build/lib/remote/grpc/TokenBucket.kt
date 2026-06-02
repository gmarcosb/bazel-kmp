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
package com.google.devtools.build.lib.remote.grpc

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
import io.reactivex.rxjava3.core.Observer
import java.io.Closeable

/** A container for tokens which is used for rate limiting.  */
@ThreadSafe
class TokenBucket<T> @kotlin.jvm.JvmOverloads constructor(initialTokens: MutableCollection<T?> = ImmutableList.of<T?>()) :
    Closeable {
    private val tokens: ConcurrentLinkedDeque<T?>
    private val tokenBehaviorSubject: BehaviorSubject<T?>

    init {
        tokens = ConcurrentLinkedDeque<T?>(initialTokens)
        tokenBehaviorSubject = BehaviorSubject.create<T?>()

        if (!tokens.isEmpty()) {
            tokenBehaviorSubject.onNext(tokens.getFirst())
        }
    }

    /** Add a token to the bucket.  */
    fun addToken(token: T?) {
        tokens.addLast(token)
        tokenBehaviorSubject.onNext(token)
    }

    /** Returns current number of tokens in the bucket.  */
    fun size(): Int {
        return tokens.size()
    }

    /**
     * Returns a cold [Single] which will start the token acquisition process upon subscription.
     */
    fun acquireToken(): Single<T?>? {
        return Single.create<T?>(
            SingleOnSubscribe { downstream: SingleEmitter<T?>? ->
                tokenBehaviorSubject.subscribe(
                    object : Observer<T?>() {
                        var upstream: Disposable? = null

                        override fun onSubscribe(d: Disposable) {
                            upstream = d
                            downstream.setDisposable(d)
                        }

                        override fun onNext(ignored: T) {
                            if (!downstream.isDisposed()) {
                                val token = tokens.pollFirst()
                                if (token != null) {
                                    downstream.onSuccess(token)
                                }
                            }
                        }

                        override fun onError(e: Throwable) {
                            downstream.onError(IllegalStateException(e))
                        }

                        override fun onComplete() {
                            if (!downstream.isDisposed()) {
                                downstream.onError(IllegalStateException("closed"))
                            }
                        }
                    })
            })
    }

    /**
     * Closes the bucket and release all the tokens.
     * 
     * 
     * Subscriptions after closed to the Single returned by [TokenBucket.acquireToken] will
     * emit error.
     */
    @Throws(IOException::class)
    override fun close() {
        tokens.clear()
        tokenBehaviorSubject.onComplete()
    }
}
