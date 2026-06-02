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
package com.google.devtools.build.lib.remote.util

import build.bazel.remote.execution.v2.RequestMetadata

/** Utility functions to handle Metadata for remote Grpc calls.  */
object TracingMetadataUtils {
    private val CONTEXT_KEY: io.grpc.Context.Key<RequestMetadata> =
        io.grpc.Context.key<RequestMetadata>("remote-grpc-metadata")

    @kotlin.jvm.JvmField
    @com.google.common.annotations.VisibleForTesting
    val METADATA_KEY: io.grpc.Metadata.Key<RequestMetadata?> =
        ProtoUtils.keyForProto(RequestMetadata.getDefaultInstance())

    fun buildMetadata(
        buildRequestId: String?,
        commandId: String?,
        actionId: String?,
        actionMetadata: ActionExecutionMetadata?
    ): RequestMetadata {
        return buildMetadata(
            buildRequestId,
            commandId,
            actionId,
            if (actionMetadata != null) actionMetadata.getMnemonic() else null,
            if (actionMetadata != null && actionMetadata.getOwner().getLabel() != null)
                actionMetadata.getOwner().getLabel().getCanonicalForm()
            else
                null,
            if (actionMetadata != null) actionMetadata.getOwner().getConfigurationChecksum() else null
        )
    }

    fun buildMetadata(
        buildRequestId: String?,
        commandId: String?,
        actionId: String?,
        mnemonic: String?,
        label: String?,
        configurationId: String?
    ): RequestMetadata {
        com.google.common.base.Preconditions.checkNotNull<String?>(buildRequestId)
        com.google.common.base.Preconditions.checkNotNull<String?>(commandId)
        com.google.common.base.Preconditions.checkNotNull<String?>(actionId)
        val builder: RequestMetadata.Builder =
            RequestMetadata.newBuilder()
                .setCorrelatedInvocationsId(buildRequestId)
                .setToolInvocationId(commandId)
                .setActionId(actionId)
                .setToolDetails(
                    ToolDetails.newBuilder()
                        .setToolName("bazel")
                        .setToolVersion(BlazeVersionInfo.instance().getVersion())
                )
        if (mnemonic != null) {
            builder.setActionMnemonic(mnemonic)
        }
        if (label != null) {
            builder.setTargetId(label)
        }
        if (configurationId != null) {
            builder.setConfigurationId(configurationId)
        }
        return builder.build()
    }

    /**
     * Fetches a [RequestMetadata] defined on the current context.
     * 
     * @throws IllegalStateException when the metadata is not defined in the current context.
     */
    @kotlin.jvm.JvmStatic
    fun fromCurrentContext(): RequestMetadata {
        val metadata: RequestMetadata = CONTEXT_KEY.get()
        checkNotNull(metadata) { "RequestMetadata not set in current context." }
        return metadata
    }

    /** Creates a [Metadata] containing the [RequestMetadata].  */
    fun headersFromRequestMetadata(requestMetadata: RequestMetadata): io.grpc.Metadata {
        val headers: io.grpc.Metadata = io.grpc.Metadata()
        headers.put<RequestMetadata?>(METADATA_KEY, requestMetadata)
        return headers
    }

    /**
     * Extracts a [RequestMetadata] from a [Metadata] and returns it if it exists. If it
     * does not exist, returns `null`.
     */
    fun requestMetadataFromHeaders(headers: io.grpc.Metadata): RequestMetadata? {
        return headers.get<RequestMetadata?>(METADATA_KEY)
    }

    fun attachMetadataInterceptor(requestMetadata: RequestMetadata): ClientInterceptor {
        return MetadataUtils.newAttachHeadersInterceptor(headersFromRequestMetadata(requestMetadata))
    }

    private fun newMetadataForHeaders(headers: MutableList<MutableMap.MutableEntry<String?, String?>?>): io.grpc.Metadata {
        val metadata: io.grpc.Metadata = io.grpc.Metadata()
        headers.forEach(
            java.util.function.Consumer { header: MutableMap.MutableEntry<String?, String?>? ->
                metadata.put<String?>(
                    io.grpc.Metadata.Key.of<String?>(header!!.key, io.grpc.Metadata.ASCII_STRING_MARSHALLER),
                    header.value
                )
            })
        return metadata
    }

    fun newCacheHeadersInterceptor(options: RemoteOptions): ClientInterceptor {
        val metadata: io.grpc.Metadata = newMetadataForHeaders(options.remoteHeaders)
        metadata.merge(newMetadataForHeaders(options.remoteCacheHeaders))
        return MetadataUtils.newAttachHeadersInterceptor(metadata)
    }

    fun newDownloaderHeadersInterceptor(options: RemoteOptions): ClientInterceptor {
        val metadata: io.grpc.Metadata = newMetadataForHeaders(options.remoteHeaders)
        metadata.merge(newMetadataForHeaders(options.remoteDownloaderHeaders))
        return MetadataUtils.newAttachHeadersInterceptor(metadata)
    }

    fun newExecHeadersInterceptor(options: RemoteOptions): ClientInterceptor {
        val metadata: io.grpc.Metadata = newMetadataForHeaders(options.remoteHeaders)
        metadata.merge(newMetadataForHeaders(options.remoteExecHeaders))
        return MetadataUtils.newAttachHeadersInterceptor(metadata)
    }

    /** GRPC interceptor to add logging metadata to the GRPC context.  */
    class ServerHeadersInterceptor : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT?, RespT?>, headers: io.grpc.Metadata, next: ServerCallHandler<ReqT?, RespT?>
        ): io.grpc.ServerCall.Listener<ReqT?> {
            val meta: RequestMetadata? = requestMetadataFromHeaders(headers)
            if (meta == null) {
                throw io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(
                        "RequestMetadata not received from the client for "
                                + call.getMethodDescriptor().getFullMethodName()
                    )
                    .asRuntimeException()
            }
            val ctx: io.grpc.Context = io.grpc.Context.current().withValue<RequestMetadata?>(CONTEXT_KEY, meta)
            return Contexts.interceptCall<ReqT?, RespT?>(ctx, call, headers, next)
        }
    }
}
