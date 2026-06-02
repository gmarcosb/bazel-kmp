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

import com.google.bytestream.ByteStreamProto.ReadRequest

/** LoggingHandler for [google.bytestream.Read] gRPC call.  */
class ReadHandler : LoggingHandler<ReadRequest?, ReadResponse?> {
    private val builder: ReadDetails.Builder = ReadDetails.newBuilder()
    private var numReads: Long = 0
    private var bytesRead: Long = 0

    override fun handleReq(message: ReadRequest?) {
        builder.setRequest(message)
    }

    override fun handleResp(message: ReadResponse) {
        numReads++
        bytesRead += message.getData().size()
    }

    override fun getDetails(): RpcCallDetails {
        builder.setNumReads(numReads)
        builder.setBytesRead(bytesRead)
        return RpcCallDetails.newBuilder().setRead(builder).build()
    }
}
