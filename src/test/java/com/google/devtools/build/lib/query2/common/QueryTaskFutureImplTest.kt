// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.common

import com.google.devtools.build.lib.query2.common.AbstractBlazeQueryEnvironment.QueryTaskFutureImpl

@RunWith(JUnit4::class)
class QueryTaskFutureImplTest {
    @org.junit.Test
    @Throws(java.lang.InterruptedException::class, ExecutionException::class)
    fun whenSucceedsOrIsCancelledCall_inputFutureSuccess() {
        val inputFuture: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()

        val nextFuture: QueryTaskFuture<String?> =
            QueryTaskFutureImpl.whenSucceedsOrIsCancelledCall(
                QueryTaskFutureImpl.ofDelegate(inputFuture),
                { "Callback Return" },
                com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
            )

        inputFuture.setFuture(com.google.common.util.concurrent.Futures.immediateVoidFuture())
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            (nextFuture as QueryTaskFutureImpl<String?>).get()
        Truth.assertThat(nextFuture.ifSuccessful).isEqualTo("Callback Return")
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class, ExecutionException::class)
    fun whenSucceedsOrCancelsCall_inputFutureCancels() {
        val inputQueryTaskFutureImpl: QueryTaskFutureImpl<java.lang.Void?> =
            QueryTaskFutureImpl.ofDelegate(com.google.common.util.concurrent.SettableFuture.create<V?>())

        val nextFuture: QueryTaskFuture<String?> =
            QueryTaskFutureImpl.whenSucceedsOrIsCancelledCall(
                inputQueryTaskFutureImpl,
                { "Callback Return" },
                com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
            )

        val unused1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            inputQueryTaskFutureImpl.cancel(true)
        val unused2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            (nextFuture as QueryTaskFutureImpl<String?>).get()
        Truth.assertThat(nextFuture.ifSuccessful).isEqualTo("Callback Return")
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class, ExecutionException::class)
    fun whenSucceedsOrCancelsCall_inputFutureFails() {
        val inputFuture: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()

        val nextFuture: QueryTaskFuture<String?> =
            QueryTaskFutureImpl.whenSucceedsOrIsCancelledCall(
                QueryTaskFutureImpl.ofDelegate(inputFuture),
                { "Callback Return" },
                com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
            )

        val queryException: com.google.devtools.build.lib.query2.engine.QueryException =
            com.google.devtools.build.lib.query2.engine.QueryException("Deliberate failure", Code.ACTION_QUERY_UNKNOWN)
        inputFuture.setException(queryException)

        val thrownFromDirectGet: ExecutionException =
            org.junit.Assert.assertThrows<ExecutionException>(
                ExecutionException::class.java,
                (nextFuture as QueryTaskFutureImpl<String?>?)::get
            )
        val cause: Throwable? = thrownFromDirectGet.cause
        Truth.assertThat(cause).isInstanceOf(com.google.devtools.build.lib.query2.engine.QueryException::class.java)
        Truth.assertThat(thrownFromDirectGet).hasMessageThat().contains("Deliberate failure")

        val thrownFromGetIfSuccessful: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                nextFuture::ifSuccessful
            )
        Truth.assertThat(thrownFromGetIfSuccessful)
            .hasCauseThat()
            .hasMessageThat()
            .isEqualTo(thrownFromDirectGet.message)
    }
}
