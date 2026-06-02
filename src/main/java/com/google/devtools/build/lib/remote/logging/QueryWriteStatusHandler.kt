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
package com.google.devtools.build.lib.remote.logging

import com.google.bytestream.ByteStreamProto.QueryWriteStatusRequest

/** LoggingHandler for [google.bytestream.QueryWriteStatus] gRPC call.  */
class QueryWriteStatusHandler

    : LoggingHandler<QueryWriteStatusRequest?, QueryWriteStatusResponse?> {
    private val builder: QueryWriteStatusDetails.Builder = QueryWriteStatusDetails.newBuilder()

    override fun handleReq(message: QueryWriteStatusRequest?) {
        builder.setRequest(message)
    }

    override fun handleResp(message: QueryWriteStatusResponse?) {
        builder.setResponse(message)
    }

    override fun getDetails(): RpcCallDetails {
        return RpcCallDetails.newBuilder().setQueryWriteStatus(builder).build()
    }
}
