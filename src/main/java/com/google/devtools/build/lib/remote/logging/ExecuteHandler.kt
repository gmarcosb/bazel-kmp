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

import build.bazel.remote.execution.v2.ExecuteRequest

/** LoggingHandler for google.devtools.remoteexecution.v1test.Execution.Execute gRPC call.  */
class ExecuteHandler : LoggingHandler<ExecuteRequest?, Operation?> {
    private val builder: ExecuteDetails.Builder

    init {
        builder = ExecuteDetails.newBuilder()
    }

    override fun handleReq(message: ExecuteRequest?) {
        builder.setRequest(message)
    }

    override fun handleResp(message: Operation?) {
        builder.addResponses(message)
    }

    override fun getDetails(): RpcCallDetails {
        return RpcCallDetails.newBuilder().setExecute(builder).build()
    }
}
