// Copyright 2026 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.SplitBlobRequest

/**
 * LoggingHandler for [build.bazel.remote.execution.v2.ContentAddressableStorage.SplitBlob]
 * gRPC call.
 */
class SplitBlobHandler : LoggingHandler<SplitBlobRequest?, SplitBlobResponse?> {
    private val builder: SplitBlobDetails.Builder = SplitBlobDetails.newBuilder()

    override fun handleReq(message: SplitBlobRequest?) {
        builder.setRequest(message)
    }

    override fun handleResp(message: SplitBlobResponse?) {
        builder.setResponse(message)
    }

    override fun getDetails(): RpcCallDetails {
        return RpcCallDetails.newBuilder().setSplitBlob(builder).build()
    }
}
