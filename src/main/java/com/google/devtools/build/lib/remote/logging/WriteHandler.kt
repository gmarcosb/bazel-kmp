// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.logging

import com.google.bytestream.ByteStreamProto.WriteRequest
import com.google.common.collect.Iterables
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableSet

/** LoggingHandler for [google.bytestream.Write] gRPC call.  */
class WriteHandler : LoggingHandler<WriteRequest?, WriteResponse?> {
    private val builder: WriteDetails.Builder = WriteDetails.newBuilder()
    private val resources: MutableSet<String?> = LinkedHashSet<String?>()
    private val offsets: MutableList<Long?> = ArrayList<Long?>()
    private val finishWrites: MutableList<Long?> = ArrayList<Long?>()
    private var bytesSentInSequence: Long = 0
    private var numWrites: Long = 0
    private var bytesSent: Long = 0

    override fun handleReq(message: WriteRequest) {
        resources.add(message.getResourceName())
        val writeOffset: Long = message.getWriteOffset()
        if (numWrites == 0L || Iterables.getLast<Long?>(offsets)!! + bytesSentInSequence != writeOffset) {
            offsets.add(writeOffset)
            bytesSentInSequence = 0
        }
        val size: Int = message.getData().size()
        if (message.getFinishWrite()) {
            finishWrites.add(writeOffset + size)
        }

        numWrites++
        bytesSent += size.toLong()
        bytesSentInSequence += size.toLong()
    }

    override fun handleResp(message: WriteResponse?) {
        builder.setResponse(message)
    }

    override fun getDetails(): RpcCallDetails {
        builder.addAllResourceNames(resources)
        builder.addAllOffsets(offsets)
        builder.addAllFinishWrites(finishWrites)
        builder.setNumWrites(numWrites)
        builder.setBytesSent(bytesSent)
        return RpcCallDetails.newBuilder().setWrite(builder).build()
    }
}
