// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.Digest

internal class FakeImmutableCacheByteStreamImpl(contents: MutableMap<Digest?, Any?>) : ByteStreamImplBase() {
    private val cannedReplies: MutableMap<ReadRequest?, ReadResponse?>
    private val numErrors: MutableMap<ReadRequest?, Int?>

    init {
        val b: com.google.common.collect.ImmutableMap.Builder<ReadRequest?, ReadResponse?> =
            com.google.common.collect.ImmutableMap.builder<ReadRequest?, ReadResponse?>()
        for (e in contents.entries) {
            val obj: Any = e.value!!
            val data: ByteString?
            if (obj is String) {
                data = ByteString.copyFromUtf8(obj)
            } else if (obj is ByteString) {
                data = obj
            } else {
                throw java.lang.AssertionError(
                    "expected object to be either a String or a ByteString, got a "
                            + obj.javaClass.getCanonicalName()
                )
            }
            b.put(
                ReadRequest.newBuilder()
                    .setResourceName("blobs/" + e.key.getHash() + "/" + e.key.getSizeBytes())
                    .build(),
                ReadResponse.newBuilder().setData(data).build()
            )
        }
        cannedReplies = b.build()
        numErrors = HashMap<ReadRequest?, Int?>()
    }

    constructor(
        digest: Digest,
        contents: String
    ) : this(com.google.common.collect.ImmutableMap.of<Digest?, Any?>(digest, contents))

    constructor(
        d1: Digest,
        c1: String,
        d2: Digest,
        c2: String
    ) : this(com.google.common.collect.ImmutableMap.of<Digest?, Any?>(d1, c1, d2, c2))

    public override fun read(request: ReadRequest?, responseObserver: StreamObserver<ReadResponse?>) {
        Truth.assertThat(cannedReplies.keys).contains(request)
        val errCount: Int = numErrors.getOrDefault(request, 0)!!
        if (errCount < MAX_ERRORS) {
            numErrors.put(request, errCount + 1)
            responseObserver.onError(io.grpc.Status.UNAVAILABLE.asRuntimeException()) // Retriable error.
        } else {
            responseObserver.onNext(cannedReplies.get(request))
            responseObserver.onCompleted()
        }
    }

    companion object {
        // Start returning the correct response after this number of errors is reached.
        private const val MAX_ERRORS = 3
    }
}
