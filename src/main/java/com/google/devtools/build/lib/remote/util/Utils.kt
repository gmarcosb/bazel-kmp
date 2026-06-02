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
package com.google.devtools.build.lib.remote.util

import build.bazel.remote.execution.v2.Action

/** Utility methods for the remote package. *  */
object Utils {
    /**
     * Returns the result of a [Future] if successful, or throws any checked [Exception]
     * directly if it's an [IOException] or else wraps it in an [IOException].
     * 
     * 
     * Cancel the future on [InterruptedException]
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun <T> getFromFuture(f: java.util.concurrent.Future<T?>): T? {
        return
        T > com.google.devtools.build.lib.remote.util.Utils.getFromFuture<T?>(f,  /* cancelOnInterrupt */true)
    }

    /**
     * Returns the result of a [Future] if successful, or throws any checked [Exception]
     * directly if it's an [IOException] or else wraps it in an [IOException].
     * 
     * @param cancelOnInterrupt cancel the future on [InterruptedException] if `true`.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun <T> getFromFuture(f: java.util.concurrent.Future<T?>, cancelOnInterrupt: Boolean): T? {
        try {
            return f.get()
        } catch (e: CancellationException) {
            throw java.lang.InterruptedException()
        } catch (e: ExecutionException) {
            InterruptedException > com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                e.cause,
                java.lang.InterruptedException::class.java
            )
            IOException > com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(
                e.cause,
                IOException::class.java
            )
            com.google.common.base.Throwables.throwIfUnchecked(e.cause)
            throw IOException(e.cause)
        } catch (e: java.lang.InterruptedException) {
            if (cancelOnInterrupt) {
                f.cancel(true)
            }
            throw e
        }
    }

    /** Constructs a [SpawnResult].  */
    fun createSpawnResult(
        digestUtil: DigestUtil,
        actionKey: ActionKey,
        exitCode: Int,
        cacheHit: Boolean,
        runnerName: String?,
        inMemoryOutput: InMemoryOutput?,
        executionStartTimestamp: Timestamp,
        executionCompletedTimestamp: Timestamp,
        spawnMetrics: SpawnMetrics?,
        mnemonic: String?
    ): SpawnResult {
        val builder: SpawnResult.Builder =
            Builder()
                .setStatus(
                    if (exitCode == 0) SpawnResult.Status.SUCCESS else SpawnResult.Status.NON_ZERO_EXIT
                )
                .setExitCode(exitCode)
                .setRunnerName(if (cacheHit) runnerName + " cache hit" else runnerName)
                .setCacheHit(cacheHit)
                .setStartTime(com.google.devtools.build.lib.remote.util.Utils.timestampToInstant(executionStartTimestamp))
                .setWallTimeInMs(
                    java.time.Duration.between(
                        com.google.devtools.build.lib.remote.util.Utils.timestampToInstant(executionStartTimestamp),
                        com.google.devtools.build.lib.remote.util.Utils.timestampToInstant(executionCompletedTimestamp)
                    )
                        .toMillis().toInt()
                )
                .setSpawnMetrics(spawnMetrics)
                .setRemote(true)
                .setDigest(digestUtil.asSpawnLogProto(actionKey))
        if (exitCode != 0) {
            builder.setFailureDetail(
                FailureDetail.newBuilder()
                    .setMessage(mnemonic + " returned a non-zero exit code when running remotely")
                    .setSpawn(
                        FailureDetails.Spawn.newBuilder()
                            .setCode(FailureDetails.Spawn.Code.NON_ZERO_EXIT)
                    )
                    .build()
            )
        }
        if (inMemoryOutput != null) {
            builder.setInMemoryOutput(inMemoryOutput.getOutput(), inMemoryOutput.getContents())
        }
        return builder.build()
    }

    fun timestampToInstant(timestamp: Timestamp): Instant? {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
    }

    private fun statusName(code: Int): String? {
        // 'convert_underscores' to 'Convert Underscores'
        val name: String = Code.forNumber(code).getValueDescriptor().getName()
        return java.util.Arrays.stream<String?>(name.split("_".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray())
            .map<String?> { word: String? ->
                com.google.common.base.Ascii.toUpperCase(
                    word.substring(
                        0,
                        1
                    )
                ) + com.google.common.base.Ascii.toLowerCase(word.substring(1))
            }
            .collect(Collectors.joining(" "))
    }

    @Throws(InvalidProtocolBufferException::class)
    private fun errorDetailsMessage(details: Iterable<Any>): String {
        var messages = ""
        for (detail in details) {
            messages += "  " + com.google.devtools.build.lib.remote.util.Utils.errorDetailMessage(detail) + "\n"
        }
        return messages
    }

    private fun durationMessage(duration: Duration?): String {
        // this will give us seconds, might want to consider something nicer (graduating ms, s, m, h, d,
        // w?)
        return Durations.toString(duration)
    }

    private fun retryInfoMessage(retryInfo: RetryInfo): String {
        return "Retry delay recommendation of " + com.google.devtools.build.lib.remote.util.Utils.durationMessage(
            retryInfo.getRetryDelay()
        )
    }

    private fun debugInfoMessage(debugInfo: DebugInfo): String {
        var message = ""
        if (debugInfo.getStackEntriesCount() > 0) {
            message +=
                "Debug Stack Information:\n  " + java.lang.String.join("\n  ", debugInfo.getStackEntriesList())
        }
        if (!debugInfo.getDetail().isEmpty()) {
            if (debugInfo.getStackEntriesCount() > 0) {
                message += "\n"
            }
            message += "Debug Details: " + debugInfo.getDetail()
        }
        return message
    }

    private fun quotaFailureMessage(quotaFailure: QuotaFailure): String {
        var message = "Quota Failure"
        if (quotaFailure.getViolationsCount() > 0) {
            message += ":"
        }
        for (violation in quotaFailure.getViolationsList()) {
            message += "\n    " + violation.getSubject() + ": " + violation.getDescription()
        }
        return message
    }

    private fun preconditionFailureMessage(preconditionFailure: PreconditionFailure): String {
        var message = "Precondition Failure"
        if (preconditionFailure.getViolationsCount() > 0) {
            message += ":"
        }
        for (violation in preconditionFailure.getViolationsList()) {
            message +=
                ("\n    ("
                        + violation.getType()
                        + ") "
                        + violation.getSubject()
                        + ": "
                        + violation.getDescription())
        }
        return message
    }

    private fun badRequestMessage(badRequest: BadRequest): String {
        var message = "Bad Request"
        if (badRequest.getFieldViolationsCount() > 0) {
            message += ":"
        }
        for (fieldViolation in badRequest.getFieldViolationsList()) {
            message += "\n    " + fieldViolation.getField() + ": " + fieldViolation.getDescription()
        }
        return message
    }

    private fun requestInfoMessage(requestInfo: RequestInfo): String {
        return "Request Info: " + requestInfo.getRequestId() + " => " + requestInfo.getServingData()
    }

    private fun resourceInfoMessage(resourceInfo: ResourceInfo): String {
        var message =
            ("Resource Info: "
                    + resourceInfo.getResourceType()
                    + ": name='"
                    + resourceInfo.getResourceName()
                    + "', owner='"
                    + resourceInfo.getOwner()
                    + "'")
        if (!resourceInfo.getDescription().isEmpty()) {
            message += ", description: " + resourceInfo.getDescription()
        }
        return message
    }

    private fun helpMessage(help: Help): String {
        var message = "Help"
        if (help.getLinksCount() > 0) {
            message += ":"
        }
        for (link in help.getLinksList()) {
            message += "\n    " + link.getDescription() + ": " + link.getUrl()
        }
        return message
    }

    @Throws(InvalidProtocolBufferException::class)
    private fun errorDetailMessage(detail: Any): String? {
        if (detail.`is`(RetryInfo::class.java)) {
            return com.google.devtools.build.lib.remote.util.Utils.retryInfoMessage(detail.unpack(RetryInfo::class.java))
        }
        if (detail.`is`(DebugInfo::class.java)) {
            return com.google.devtools.build.lib.remote.util.Utils.debugInfoMessage(detail.unpack(DebugInfo::class.java))
        }
        if (detail.`is`(QuotaFailure::class.java)) {
            return com.google.devtools.build.lib.remote.util.Utils.quotaFailureMessage(detail.unpack(QuotaFailure::class.java))
        }
        if (detail.`is`(PreconditionFailure::class.java)) {
            return com.google.devtools.build.lib.remote.util.Utils.preconditionFailureMessage(
                detail.unpack(
                    PreconditionFailure::class.java
                )
            )
        }
        if (detail.`is`(BadRequest::class.java)) {
            return com.google.devtools.build.lib.remote.util.Utils.badRequestMessage(detail.unpack(BadRequest::class.java))
        }
        if (detail.`is`(RequestInfo::class.java)) {
            return com.google.devtools.build.lib.remote.util.Utils.requestInfoMessage(detail.unpack(RequestInfo::class.java))
        }
        if (detail.`is`(ResourceInfo::class.java)) {
            return com.google.devtools.build.lib.remote.util.Utils.resourceInfoMessage(detail.unpack(ResourceInfo::class.java))
        }
        if (detail.`is`(Help::class.java)) {
            return com.google.devtools.build.lib.remote.util.Utils.helpMessage(detail.unpack(Help::class.java))
        }
        return "Unrecognized error detail: " + detail
    }

    @Throws(InvalidProtocolBufferException::class)
    private fun localizedStatusMessage(status: Status): String {
        val languageTag: String? = Locale.getDefault().toLanguageTag()
        for (detail in status.getDetailsList()) {
            if (detail.`is`(LocalizedMessage::class.java)) {
                val message: LocalizedMessage = detail.unpack(LocalizedMessage::class.java)
                if (message.getLocale().equals(languageTag)) {
                    return message.getMessage()
                }
            }
        }
        return status.getMessage()
    }

    @Throws(InvalidProtocolBufferException::class)
    private fun executionStatusExceptionErrorMessage(e: ExecutionStatusException): String {
        val status: Status = e.getOriginalStatus()
        return (com.google.devtools.build.lib.remote.util.Utils.statusName(status.getCode())
                + ": "
                + com.google.devtools.build.lib.remote.util.Utils.localizedStatusMessage(status)
                + "\n"
                + com.google.devtools.build.lib.remote.util.Utils.errorDetailsMessage(status.getDetailsList()))
    }

    private fun grpcAwareErrorMessage(e: IOException): String? {
        val errStatus: io.grpc.Status = io.grpc.Status.fromThrowable(e)
        if (e.cause is ExecutionStatusException) {
            // Display error message returned by the remote service.
            try {
                return ("Remote Execution Failure:\n"
                        + com.google.devtools.build.lib.remote.util.Utils.executionStatusExceptionErrorMessage(e.cause as ExecutionStatusException?))
            } catch (protoEx: InvalidProtocolBufferException) {
                return ("Error occurred attempting to format an error message for "
                        + errStatus
                        + ": "
                        + com.google.common.base.Throwables.getStackTraceAsString(protoEx))
            }
        }
        if (errStatus.getCode() != io.grpc.Status.UNKNOWN.getCode()) {
            // Display error message returned by the gRPC library, prefixed by the status code.
            val sb: java.lang.StringBuilder = java.lang.StringBuilder()
            sb.append(errStatus.getCode().name)
            sb.append(": ")
            sb.append(errStatus.getDescription())
            // If the error originated from a credential helper, print additional debugging information.
            var t: Throwable? = errStatus.getCause()
            while (t != null) {
                if (t is CredentialHelperException) {
                    sb.append(": ")
                    sb.append(t.message)
                    break
                }
                t = t.cause
            }
            return sb.toString()
        }
        return e.message
    }

    @kotlin.jvm.JvmStatic
    fun grpcAwareErrorMessage(error: Throwable, verboseFailures: Boolean): String? {
        var errorMessage: String?
        if (error is IOException) {
            errorMessage = com.google.devtools.build.lib.remote.util.Utils.grpcAwareErrorMessage(error)
        } else {
            errorMessage = error.message
        }

        if (com.google.common.base.Strings.isNullOrEmpty(errorMessage)) {
            errorMessage = error.javaClass.getSimpleName()
        }

        if (verboseFailures) {
            // On --verbose_failures print the whole stack trace
            errorMessage += "\n" + com.google.common.base.Throwables.getStackTraceAsString(error)
        }

        return errorMessage
    }

    fun downloadAsActionResult(
        actionDigest: ActionKey,
        downloadFunction: java.util.function.BiFunction<Digest?, java.io.OutputStream?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>>
    ): com.google.common.util.concurrent.ListenableFuture<ActionResult?> {
        val data: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream( /* size= */1024)
        val download: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            downloadFunction.apply(actionDigest.digest(), data)
        return com.google.common.util.concurrent.FluentFuture.from<java.lang.Void?>(download)
            .transformAsync<Any?>(
                com.google.common.util.concurrent.AsyncFunction { v: java.lang.Void? ->
                    try {
                        return@transformAsync com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                            ActionResult.parseFrom(
                                data.toByteArray()
                            )
                        )
                    } catch (e: InvalidProtocolBufferException) {
                        return@transformAsync
                        Object > com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(e)
                    }
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
            .catching<X?>(
                CacheNotFoundException::class.java,
                com.google.common.base.Function { e: X? -> null },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
    }

    @Throws(IOException::class)
    fun verifyBlobContents(expected: Digest, actual: Digest?) {
        if (!expected.equals(actual)) {
            throw OutputDigestMismatchException(expected, actual)
        }
    }

    fun buildAction(
        command: Digest?,
        inputRoot: Digest?,
        platform: Platform?,
        timeout: java.time.Duration,
        cacheable: Boolean,
        salt: ByteString?
    ): Action {
        val action: Action.Builder = Action.newBuilder()
        action.setCommandDigest(command)
        action.setInputRootDigest(inputRoot)
        if (!timeout.isZero()) {
            action.setTimeout(Duration.newBuilder().setSeconds(timeout.toSeconds()))
        }
        if (!cacheable) {
            action.setDoNotCache(true)
        }
        if (platform != null) {
            action.setPlatform(platform)
        }
        if (salt != null) {
            action.setSalt(salt)
        }
        return action.build()
    }

    /**
     * Call an asynchronous code block. If the block throws unauthenticated error, refresh the
     * credentials using [CallCredentialsProvider] and call it again.
     * 
     * 
     * If any other exception thrown by the code block, it will be caught and wrapped in the
     * returned [ListenableFuture].
     */
    fun <V> refreshIfUnauthenticatedAsync(
        call: com.google.common.util.concurrent.AsyncCallable<V?>, callCredentialsProvider: CallCredentialsProvider
    ): com.google.common.util.concurrent.ListenableFuture<V?> {
        com.google.common.base.Preconditions.checkNotNull<com.google.common.util.concurrent.AsyncCallable<V?>?>(call)
        Object > com.google.common.base.Preconditions.checkNotNull<Any?>(callCredentialsProvider)

        try {
            return com.google.common.util.concurrent.Futures.catchingAsync<V?, Throwable?>(
                call.call(),
                Throwable::class.java,
                { e -> }<V> com . google . devtools . build . lib . remote . util . Utils . refreshIfUnauthenticatedAsyncOnException < V ? > (e,
                call,
                callCredentialsProvider
            ),
            com.google.common.util.concurrent.MoreExecutors.directExecutor())
        } catch (t: Throwable) {
            return
            V > com.google.devtools.build.lib.remote.util.Utils.refreshIfUnauthenticatedAsyncOnException<V?>(
                t,
                call,
                callCredentialsProvider
            )
        }
    }

    private fun <V> refreshIfUnauthenticatedAsyncOnException(
        t: Throwable,
        call: com.google.common.util.concurrent.AsyncCallable<V?>,
        callCredentialsProvider: CallCredentialsProvider
    ): com.google.common.util.concurrent.ListenableFuture<V?> {
        val status: io.grpc.Status? = io.grpc.Status.fromThrowable(t)
        if (status != null
            && (status.getCode() == io.grpc.Status.Code.UNAUTHENTICATED
                    || status.getCode() == io.grpc.Status.Code.PERMISSION_DENIED)
        ) {
            try {
                callCredentialsProvider.refresh()
                return call.call()
            } catch (tt: Throwable) {
                t.addSuppressed(tt)
            }
        }

        return
        V > com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(t)
    }

    /** Same as [.refreshIfUnauthenticatedAsync] but calling a synchronous code block.  */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun <V> refreshIfUnauthenticated(
        call: java.util.concurrent.Callable<V?>?, callCredentialsProvider: CallCredentialsProvider?
    ): V? {
        com.google.common.base.Preconditions.checkNotNull<java.util.concurrent.Callable<V?>?>(call)
        Object > com.google.common.base.Preconditions.checkNotNull<Any?>(callCredentialsProvider)

        try {
            return call.call()
        } catch (e: java.lang.Exception) {
            val status: io.grpc.Status? = io.grpc.Status.fromThrowable(e)
            if (status != null
                && (status.getCode() == io.grpc.Status.Code.UNAUTHENTICATED
                        || status.getCode() == io.grpc.Status.Code.PERMISSION_DENIED)
            ) {
                try {
                    callCredentialsProvider.refresh()
                    return call.call()
                } catch (ex: java.lang.Exception) {
                    e.addSuppressed(ex)
                }
            }

            com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(e, IOException::class.java)
            com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                e,
                java.lang.InterruptedException::class.java
            )
            com.google.common.base.Throwables.throwIfUnchecked(e)
            throw java.lang.AssertionError(e)
        }
    }

    fun shouldUploadLocalResultsToRemoteCache(
        remoteOptions: RemoteOptions, executionInfo: MutableMap<String?, String?>
    ): Boolean {
        return remoteOptions.remoteUploadLocalResults
                && Spawns.mayBeCachedRemotely(executionInfo)
                && !executionInfo.containsKey(ExecutionRequirements.NO_REMOTE_CACHE_UPLOAD)
    }

    /**
     * Waits for all transfers to finish.
     * 
     * 
     * If interrupted, all remaining transfers are canceled.
     */
    @Throws(BulkTransferException::class, java.lang.InterruptedException::class)
    fun waitForBulkTransfer(transfers: Iterable<out com.google.common.util.concurrent.ListenableFuture<*>>) {
        var bulkTransferException: BulkTransferException? = null
        var interruptedException: java.lang.InterruptedException? = null
        var interrupted: Boolean = java.lang.Thread.currentThread().isInterrupted()
        for (transfer in transfers) {
            try {
                if (interruptedException == null) {
                    // Wait for all transfers to finish.
                    val unused: Any? = com.google.devtools.build.lib.remote.util.Utils.getFromFuture(
                        transfer,  /* cancelOnInterrupt= */
                        true
                    )
                } else {
                    transfer.cancel(true)
                }
            } catch (e: IOException) {
                if (bulkTransferException == null) {
                    bulkTransferException = BulkTransferException()
                }
                bulkTransferException.add(e)
            } catch (e: java.lang.InterruptedException) {
                interrupted = java.lang.Thread.interrupted() || interrupted
                interruptedException = e
            }
        }
        if (interrupted) {
            java.lang.Thread.currentThread().interrupt()
        }
        if (interruptedException != null) {
            if (bulkTransferException != null) {
                interruptedException.addSuppressed(bulkTransferException)
            }
            throw interruptedException
        }
        if (bulkTransferException != null) {
            throw bulkTransferException
        }
    }

    fun mergeBulkTransfer(
        transfers: Iterable<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>>
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        return com.google.common.util.concurrent.Futures.whenAllComplete<java.lang.Void?>(transfers)
            .callAsync<C?>(
                com.google.common.util.concurrent.AsyncCallable {
                    val bulkTransferException: BulkTransferException? = null
                    for (transfer in transfers) {
                        val error: IOException? = null
                        try {
                            transfer.get()
                        } catch (e: CancellationException) {
                            return@callAsync
                            Object > com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(java.lang.InterruptedException())
                        } catch (e: java.lang.InterruptedException) {
                            return@callAsync
                            Object > com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(e)
                        } catch (e: ExecutionException) {
                            val cause: Throwable = e.cause
                            if (cause is java.lang.InterruptedException) {
                                return@callAsync
                                Object > com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(cause)
                            } else if (cause is IOException) {
                                error = cause
                            } else {
                                error = IOException(cause)
                            }
                        }

                        if (error == null) {
                            continue
                        }

                        if (bulkTransferException == null) {
                            bulkTransferException = BulkTransferException()
                        }
                        bulkTransferException.add(error)
                    }

                    if (bulkTransferException != null) {
                        return@callAsync
                        V > com.google.common.util.concurrent.Futures.immediateFailedFuture<V?>(bulkTransferException)
                    }
                    com.google.common.util.concurrent.Futures.immediateVoidFuture()
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
    }

    fun createExecExceptionForCredentialHelperException(
        e: CredentialHelperException?
    ): ExecException {
        return EnvironmentalExecException(
            e,
            FailureDetail.newBuilder()
                .setRemoteOptions(
                    FailureDetails.RemoteOptions.newBuilder()
                        .setCode(FailureDetails.RemoteOptions.Code.CREDENTIALS_READ_FAILURE)
                        .build()
                )
                .setMessage("Exec failed due to CredentialHelperException")
                .build()
        )
    }

    fun createExecExceptionFromRemoteExecutionCapabilitiesException(
        e: RemoteExecutionCapabilitiesException
    ): ExecException {
        return EnvironmentalExecException(
            e.getCause(),
            FailureDetail.newBuilder()
                .setRemoteExecution(
                    RemoteExecution.newBuilder()
                        .setCode(RemoteExecution.Code.CAPABILITIES_QUERY_FAILURE)
                        .build()
                )
                .setMessage("Failed to query remote execution capabilities")
                .build()
        )
    }

    /** An in-memory output file.  */
    class InMemoryOutput(output: ActionInput?, contents: ByteString?) {
        private val output: ActionInput?
        private val contents: ByteString?

        init {
            this.output = output
            this.contents = contents
        }

        fun getOutput(): ActionInput? {
            return output
        }

        fun getContents(): ByteString? {
            return contents
        }
    }
}
