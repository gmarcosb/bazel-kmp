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
package com.google.devtools.build.remote.worker

import build.bazel.remote.execution.v2.ActionCacheUpdateCapabilities

/** A basic implementation of a Capabilities service.  */
internal class CapabilitiesServer(digestUtil: DigestUtil, execEnabled: Boolean, workerOptions: RemoteWorkerOptions) :
    CapabilitiesImplBase() {
    private val digestUtil: DigestUtil
    private val execEnabled: Boolean
    private val workerOptions: RemoteWorkerOptions

    init {
        this.digestUtil = digestUtil
        this.execEnabled = execEnabled
        this.workerOptions = workerOptions
    }

    public override fun getCapabilities(
        request: GetCapabilitiesRequest?, responseObserver: StreamObserver<ServerCapabilities?>
    ) {
        val df: DigestFunction.Value? = digestUtil.getDigestFunction()

        val builder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ServerCapabilities.newBuilder()
        if (workerOptions.getLegacyApi()) {
            builder
                .setLowApiVersion(ApiVersion.twoPointZero.toSemVer())
                .setHighApiVersion(ApiVersion.twoPointZero.toSemVer())
        } else {
            builder
                .setLowApiVersion(ApiVersion.low.toSemVer())
                .setHighApiVersion(ApiVersion.high.toSemVer())
        }
        val response: ServerCapabilities.Builder =
            builder.setCacheCapabilities(
                CacheCapabilities.newBuilder()
                    .addDigestFunctions(df)
                    .setSymlinkAbsolutePathStrategy(SymlinkAbsolutePathStrategy.Value.DISALLOWED)
                    .setActionCacheUpdateCapabilities(
                        ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build()
                    )
                    .setMaxBatchTotalSizeBytes(CasServer.Companion.MAX_BATCH_SIZE_BYTES)
                    .setSplitBlobSupport(true)
                    .setSpliceBlobSupport(true)
                    .setFastCdc2020Params(
                        FastCdc2020Params.newBuilder()
                            .setAvgChunkSizeBytes(512 * 1024)
                            .setSeed(0)
                            .build()
                    )
                    .build()
            )
        if (execEnabled) {
            response.setExecutionCapabilities(
                ExecutionCapabilities.newBuilder().setDigestFunction(df).setExecEnabled(true).build()
            )
        }
        responseObserver.onNext(response.build())
        responseObserver.onCompleted()
    }
}
