// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.testutils

import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * A [FingerprintValueStore] implementation that queues [FingerprintValueStore.get]
 * operations and makes their completion controllable by the caller.
 */
class GetRecordingStore : FingerprintValueStore {
    private val fingerprintToContents: ConcurrentHashMap<KeyBytesProvider?, ByteArray?> =
        ConcurrentHashMap<KeyBytesProvider?, ByteArray?>()

    private val requestQueue: LinkedBlockingQueue<GetRequest?> = LinkedBlockingQueue<GetRequest?>()

    override fun put(fingerprint: KeyBytesProvider?, serializedBytes: ByteArray?): WriteStatus? {
        fingerprintToContents.put(fingerprint, serializedBytes)
        return WriteStatuses.immediateWriteStatus()
    }

    override fun get(fingerprint: KeyBytesProvider?): com.google.common.util.concurrent.ListenableFuture<ByteArray?> {
        val response: com.google.common.util.concurrent.SettableFuture<ByteArray?> =
            com.google.common.util.concurrent.SettableFuture.create<ByteArray?>()
        requestQueue.offer(GetRequest(this, fingerprint, response))
        return response
    }

    @Throws(java.lang.InterruptedException::class)
    fun takeFirstRequest(): GetRequest? {
        return requestQueue.take()
    }

    fun getFingerprintToContents(): MutableMap<KeyBytesProvider?, ByteArray?> {
        return fingerprintToContents
    }

    fun pollRequest(): GetRequest? {
        return requestQueue.poll()
    }

    /** Encapsulates a [.get] operation.  */
    class GetRequest(
        val parent: GetRecordingStore?,
        fingerprint: KeyBytesProvider?,
        response: com.google.common.util.concurrent.SettableFuture<ByteArray?>?
    ) {
        /**
         * Completes the [.response] by looking up the [.fingerprint] in the [ ][.parent]'s in-memory map.
         */
        fun complete() {
            this.response.set(
                com.google.common.base.Preconditions.checkNotNull<ByteArray?>(
                    this.parent!!.fingerprintToContents.get(
                        this.fingerprint
                    )
                )
            )
        }

        /**
         * Simulates returning null bytes.
         * 
         * 
         * In certain setups, null bytes are used to signal missing data for the given key.
         */
        fun completeWithNullBytes() {
            this.response.set(null)
        }

        val fingerprint: KeyBytesProvider?
        val response: com.google.common.util.concurrent.SettableFuture<ByteArray?>?

        init {
            this.fingerprint = fingerprint
            this.response = response
        }
    }
}
