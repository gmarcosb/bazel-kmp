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
package com.google.devtools.build.lib.buildeventstream

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId

/** Event reporting on statistics about the build.  */
class BuildToolLogs(
    directValues: MutableList<com.google.devtools.build.lib.util.Pair<String?, ByteString?>>,
    futureUris: MutableList<com.google.devtools.build.lib.util.Pair<String?, com.google.common.util.concurrent.ListenableFuture<String?>?>>,
    logFiles: MutableList<LogFileEntry>
) : BuildEventWithOrderConstraint {
    /** These values are posted as byte strings to the BEP.  */
    private val directValues: MutableList<com.google.devtools.build.lib.util.Pair<String?, ByteString?>>

    /** These values are posted as Future URIs to the BEP.  */
    private val futureUris: MutableList<com.google.devtools.build.lib.util.Pair<String?, com.google.common.util.concurrent.ListenableFuture<String?>?>>

    /**
     * These values are local files that are uploaded if required, and turned into URIs as part of the
     * process.
     */
    private val logFiles: MutableList<LogFileEntry>

    init {
        this.directValues = directValues
        this.futureUris = futureUris
        this.logFiles = logFiles
    }

    val eventId: BuildEventId?
        get() = BuildEventIdUtil.buildToolLogs()

    val childrenEvents: com.google.common.collect.ImmutableList<BuildEventId?>
        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>()

    override fun referencedLocalFiles(): MutableList<LocalFile?> {
        return com.google.common.collect.Lists.transform<LogFileEntry?, LocalFile?>(logFiles, LogFileEntry::localFile)
    }

    override fun remoteUploads(): MutableList<com.google.common.util.concurrent.ListenableFuture<String?>?> {
        return com.google.common.collect.Lists.transform<com.google.devtools.build.lib.util.Pair<String?, com.google.common.util.concurrent.ListenableFuture<String?>?>?, com.google.common.util.concurrent.ListenableFuture<String?>?>(
            futureUris,
            com.google.common.base.Function { obj: com.google.devtools.build.lib.util.Pair<kotlin.String?, com.google.common.util.concurrent.ListenableFuture<kotlin.String?>?>? -> obj.getSecond() })
    }

    override fun asStreamProto(converters: BuildEventContext): BuildEvent {
        val toolLogs: BuildEventStreamProtos.BuildToolLogs.Builder =
            BuildEventStreamProtos.BuildToolLogs.newBuilder()
        for (direct in directValues) {
            toolLogs.addLog(
                BuildEventStreamProtos.File.newBuilder()
                    .setName(direct.getFirst())
                    .setContents(direct.getSecond())
                    .build()
            )
        }
        for (directFuturePair in futureUris) {
            val name: String? = directFuturePair.getFirst()
            val directFuture: com.google.common.util.concurrent.ListenableFuture<String?>? =
                directFuturePair.getSecond()
            try {
                val uri: String? =
                    if (directFuture.isDone() && !directFuture.isCancelled())
                        com.google.common.util.concurrent.Futures.getDone<String?>(directFuture)
                    else
                        null
                if (uri != null) {
                    toolLogs.addLog(
                        BuildEventStreamProtos.File.newBuilder().setName(name).setUri(uri).build()
                    )
                } else {
                    logger.atInfo().log("Dropped unfinished upload: %s (%s)", name, directFuture)
                }
            } catch (e: ExecutionException) {
                logger.atWarning().withCause(e).log("Skipping build tool log upload %s", name)
            }
        }
        for (logFile in logFiles) {
            val uri: String? = converters.pathConverter().apply(logFile.localFile.path)
            if (uri != null) {
                toolLogs.addLog(
                    BuildEventStreamProtos.File.newBuilder().setName(logFile.name).setUri(uri).build()
                )
            }
        }
        return GenericBuildEvent.Companion.protoChaining(this).setBuildToolLogs(toolLogs.build()).build()
    }

    override fun postedAfter(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<BuildEventId?>(BuildEventIdUtil.buildFinished())
    }

    /** A local log file.  */
    class LogFileEntry(val name: String?, localFile: LocalFile?) {
        val localFile: LocalFile?

        init {
            this.localFile = localFile
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
