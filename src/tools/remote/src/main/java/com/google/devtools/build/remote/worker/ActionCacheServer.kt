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
package com.google.devtools.build.remote.worker

import com.google.devtools.build.lib.remote.util.Utils.getFromFuture

/** A basic implementation of an [ActionCacheImplBase] service.  */
internal class ActionCacheServer(cache: OnDiskBlobStoreCache, digestUtil: DigestUtil) : ActionCacheImplBase() {
    private val cache: OnDiskBlobStoreCache
    private val digestUtil: DigestUtil

    init {
        this.cache = cache
        this.digestUtil = digestUtil
    }

    public override fun getActionResult(
        request: GetActionResultRequest, responseObserver: StreamObserver<ActionResult?>
    ) {
        try {
            val requestMetadata: RequestMetadata? = TracingMetadataUtils.fromCurrentContext()
            val context: RemoteActionExecutionContext? = RemoteActionExecutionContext.create(requestMetadata)

            val actionKey: ActionKey? = digestUtil.asActionKey(request.getActionDigest())
            val inlineOutputFiles: com.google.common.collect.ImmutableSet<out Any?> =
                com.google.common.collect.ImmutableSet.copyOf(request.getInlineOutputFilesList())
            val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                cache.downloadActionResult(
                    context, actionKey,  /* inlineOutErr= */false, inlineOutputFiles
                )

            if (result == null) {
                responseObserver.onError(StatusUtils.notFoundError(request.getActionDigest()))
                return
            }

            var actionResult: ActionResult = result.actionResult()
            var i = 0
            while (i < actionResult.getOutputFilesCount()) {
                val outputFile: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    actionResult.getOutputFiles(i)
                if (inlineOutputFiles.contains(outputFile.getPath())) {
                    val content: ByteString =
                        ByteString.copyFrom(cache.downloadBlob(context, outputFile.getDigest()).get())
                    actionResult =
                        actionResult.toBuilder()
                            .setOutputFiles(i, outputFile.toBuilder().setContents(content))
                            .build()
                    break
                }
                i++
            }

            responseObserver.onNext(actionResult)
            responseObserver.onCompleted()
        } catch (e: CacheNotFoundException) {
            responseObserver.onError(StatusUtils.notFoundError(request.getActionDigest()))
        } catch (e: java.lang.Exception) {
            logger.atWarning().withCause(e).log("getActionResult request failed")
            responseObserver.onError(StatusUtils.internalError(e))
        }
    }

    public override fun updateActionResult(
        request: UpdateActionResultRequest, responseObserver: StreamObserver<ActionResult?>
    ) {
        try {
            val requestMetadata: RequestMetadata? = TracingMetadataUtils.fromCurrentContext()
            val context: RemoteActionExecutionContext? = RemoteActionExecutionContext.create(requestMetadata)

            val actionDigest: Digest? = request.getActionDigest()
            val actionKey: ActionKey? = digestUtil.asActionKey(actionDigest)

            val action: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                Action.parseFrom(
                    getFromFuture(cache.downloadBlob(context, actionDigest)),
                    ExtensionRegistry.getEmptyRegistry()
                )
            val unusedCommand: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                Command.parseFrom(
                    getFromFuture(cache.downloadBlob(context, action.getCommandDigest())),
                    ExtensionRegistry.getEmptyRegistry()
                )

            getFromFuture(cache.uploadActionResult(context, actionKey, request.getActionResult()))
            responseObserver.onNext(request.getActionResult())
            responseObserver.onCompleted()
        } catch (e: CacheNotFoundException) {
            logger.atWarning().withCause(e).log("updateActionResult precondition not met")
            responseObserver.onError(StatusUtils.preconditionError(e))
        } catch (e: java.lang.Exception) {
            logger.atWarning().withCause(e).log("updateActionResult request failed")
            responseObserver.onError(StatusUtils.internalError(e))
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
