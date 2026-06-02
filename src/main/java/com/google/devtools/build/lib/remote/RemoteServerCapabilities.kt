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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.CacheCapabilities

/** Fetches the ServerCapabilities of the remote execution/cache server.  */
internal class RemoteServerCapabilities(
    private val buildRequestId: String?,
    private val commandId: String?,
    private val instanceName: String?,
    callCredentials: CallCredentials?,
    callTimeoutSecs: Long,
    retrier: RemoteRetrier
) {
    private val callCredentials: CallCredentials?
    private val callTimeoutSecs: Long
    private val retrier: RemoteRetrier

    init {
        this.callCredentials = callCredentials
        this.callTimeoutSecs = callTimeoutSecs
        this.retrier = retrier
    }

    private fun capabilitiesFutureStub(
        context: RemoteActionExecutionContext, channel: io.grpc.Channel?
    ): CapabilitiesFutureStub {
        return CapabilitiesGrpc.newFutureStub(channel)
            .withInterceptors(
                TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata())
            )
            .withCallCredentials(callCredentials)
            .withDeadlineAfter(callTimeoutSecs, TimeUnit.SECONDS)
    }

    fun get(channel: ManagedChannel?): com.google.common.util.concurrent.ListenableFuture<ServerCapabilities?>? {
        val metadata: RequestMetadata? =
            TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "capabilities", null)
        val context: RemoteActionExecutionContext = RemoteActionExecutionContext.Companion.create(metadata)
        val request: GetCapabilitiesRequest? =
            if (instanceName == null)
                GetCapabilitiesRequest.getDefaultInstance()
            else
                GetCapabilitiesRequest.newBuilder().setInstanceName(instanceName).build()
        return retrier.executeAsync<T?>(
            com.google.common.util.concurrent.AsyncCallable {
                capabilitiesFutureStub(context, channel).getCapabilities(
                    request
                )
            })
    }

    internal class ClientServerCompatibilityStatus private constructor(
        val warnings: MutableList<String?>,
        val errors: MutableList<String?>
    ) {
        internal class Builder {
            private val warnings: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            private val errors: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()

            fun addWarning(message: String) {
                warnings.add(message)
            }

            fun addError(message: String) {
                errors.add(message)
            }

            fun build(): ClientServerCompatibilityStatus {
                return ClientServerCompatibilityStatus(warnings.build(), errors.build())
            }
        }

        val isOk: Boolean
            get() = warnings.isEmpty() && errors.isEmpty()
    }

    enum class ServerCapabilitiesRequirement {
        NONE,
        CACHE,
        EXECUTION,
        EXECUTION_AND_CACHE,
    }

    companion object {
        private fun checkPriorityInRange(
            priority: Int,
            optionName: String?,
            prCap: PriorityCapabilities,
            result: ClientServerCompatibilityStatus.Builder
        ) {
            if (priority != 0) {
                var found = false
                val rangeBuilder: java.lang.StringBuilder = java.lang.StringBuilder()
                for (pr in prCap.getPrioritiesList()) {
                    rangeBuilder.append(java.lang.String.format("%d-%d,", pr.getMinPriority(), pr.getMaxPriority()))
                    if (pr.getMinPriority() <= priority && priority <= pr.getMaxPriority()) {
                        found = true
                        break
                    }
                }
                if (!found) {
                    var range = rangeBuilder.toString()
                    if (!range.isEmpty()) {
                        range = range.substring(0, range.length() - 1)
                    }
                    result.addError(
                        java.lang.String.format(
                            "--%s %d is outside of server supported range %s.", optionName, priority, range
                        )
                    )
                }
            }
        }

        /** Compare the remote server capabilities with those requested by current execution.  */
        fun checkClientServerCompatibility(
            capabilities: ServerCapabilities,
            remoteOptions: RemoteOptions,
            digestFunction: DigestFunction.Value?,
            requirement: ServerCapabilitiesRequirement?
        ): ClientServerCompatibilityStatus {
            val result: ClientServerCompatibilityStatus.Builder =
                com.google.devtools.build.lib.remote.RemoteServerCapabilities.ClientServerCompatibilityStatus.Builder()
            val shouldCheckExecutionCapabilities =
                (requirement == ServerCapabilitiesRequirement.EXECUTION
                        || requirement == ServerCapabilitiesRequirement.EXECUTION_AND_CACHE)
            val shouldCheckCacheCapabilities =
                (requirement == ServerCapabilitiesRequirement.CACHE
                        || requirement == ServerCapabilitiesRequirement.EXECUTION_AND_CACHE)
            if (!(shouldCheckCacheCapabilities || shouldCheckExecutionCapabilities)) {
                return result.build()
            }

            // Check API version.
            val st: ServerSupportedStatus =
                ClientApiVersion.Companion.current.checkServerSupportedVersions(capabilities)
            if (st.isUnsupported()) {
                result.addError(st.getMessage())
            }
            if (st.isDeprecated()) {
                result.addWarning(st.getMessage())
            }

            if (shouldCheckExecutionCapabilities) {
                // Check remote execution is enabled.
                val execCap: ExecutionCapabilities = capabilities.getExecutionCapabilities()
                if (!execCap.getExecEnabled()) {
                    result.addError(
                        "Remote execution is not supported by the remote server, or the current "
                                + "account is not authorized to use remote execution."
                    )
                    return result.build() // No point checking other execution fields.
                }

                // Check execution digest function. The protocol only later added
                // support for multiple digest functions for remote execution, so
                // check both the singular and repeated field.
                if (execCap.getDigestFunctionsList().isEmpty()
                    && execCap.getDigestFunction() !== DigestFunction.Value.UNKNOWN
                ) {
                    if (execCap.getDigestFunction() !== digestFunction) {
                        result.addError(
                            java.lang.String.format(
                                "Cannot use hash function %s with remote execution. "
                                        + "Server supported function is %s",
                                digestFunction, execCap.getDigestFunction()
                            )
                        )
                    }
                } else if (!execCap.getDigestFunctionsList().contains(digestFunction)) {
                    result.addError(
                        java.lang.String.format(
                            "Cannot use hash function %s with remote execution. "
                                    + "Server supported functions are: %s",
                            digestFunction, execCap.getDigestFunctionsList()
                        )
                    )
                }

                // Check execution priority is in the supported range.
                checkPriorityInRange(
                    remoteOptions.getRemoteExecutionPriority(),
                    "remote_execution_priority",
                    execCap.getExecutionPriorityCapabilities(),
                    result
                )
            }

            if (shouldCheckCacheCapabilities) {
                // Check cache digest function.
                val cacheCap: CacheCapabilities = capabilities.getCacheCapabilities()
                if (!cacheCap.getDigestFunctionsList().contains(digestFunction)) {
                    result.addError(
                        java.lang.String.format(
                            "Cannot use hash function %s with remote cache. "
                                    + "Server supported functions are: %s",
                            digestFunction, cacheCap.getDigestFunctionsList()
                        )
                    )
                }

                if (remoteOptions.getRemoteUploadLocalResults()
                    && !cacheCap.getActionCacheUpdateCapabilities().getUpdateEnabled()
                ) {
                    result.addWarning(
                        ("--remote_upload_local_results is set, but the remote cache does not support uploading "
                                + "action results or the current account is not authorized to write local results "
                                + "to the remote cache.")
                    )
                }

                if (remoteOptions.getCacheCompression()
                    && !cacheCap.getSupportedCompressorsList().contains(Compressor.Value.ZSTD)
                ) {
                    result.addError(
                        "--remote_cache_compression requested but remote does not support compression"
                    )
                }

                if (remoteOptions.getExperimentalRemoteCacheChunking()) {
                    if (!cacheCap.getSplitBlobSupport()) {
                        result.addError(
                            "--experimental_remote_cache_chunking requested but remote does not support"
                                    + " SplitBlob"
                        )
                    }
                    if (!cacheCap.getSpliceBlobSupport()) {
                        result.addError(
                            "--experimental_remote_cache_chunking requested but remote does not support"
                                    + " SpliceBlob"
                        )
                    }
                    if (!cacheCap.hasFastCdc2020Params()) {
                        result.addError(
                            "--experimental_remote_cache_chunking requested but remote does not support"
                                    + " FastCDC 2020 chunking algorithm"
                        )
                    }
                }

                // Check result cache priority is in the supported range.
                checkPriorityInRange(
                    remoteOptions.getRemoteResultCachePriority(),
                    "remote_result_cache_priority",
                    cacheCap.getCachePriorityCapabilities(),
                    result
                )
            }

            return result.build()
        }
    }
}
