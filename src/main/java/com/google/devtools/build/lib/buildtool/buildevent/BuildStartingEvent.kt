// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool.buildevent

import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.analysis.BlazeDirectories
import com.google.devtools.build.lib.profiler.Profiler
import com.google.devtools.build.lib.profiler.ProfilerTask
import com.google.devtools.build.lib.util.NetUtil
import com.google.devtools.build.lib.vfs.FileSystemUtils

/**
 * This event is fired from BuildTool#startRequest(). At this point, the set of target patters are
 * known, but have yet to be parsed.
 */
@AutoValue
abstract class BuildStartingEvent internal constructor() : BuildEvent {
    /** Returns the name of output file system.  */
    abstract fun outputFileSystem(): String?

    /**
     * Returns whether the build uses in-memory [ ][com.google.devtools.build.lib.vfs.OutputService.ActionFileSystemType.inMemoryFileSystem].
     */
    abstract fun usesInMemoryFileSystem(): Boolean

    /** Returns the active BuildRequest.  */
    abstract fun request(): BuildRequest?

    abstract fun workspace(): String?

    abstract fun pwd(): String?

    val eventId: BuildEventId?
        get() = BuildEventIdUtil.buildStartedId()

    val childrenEvents: ImmutableList<BuildEventId>
        get() = ImmutableList.of<BuildEventId?>(
            ProgressEvent.Companion.INITIAL_PROGRESS_UPDATE,
            BuildEventIdUtil.unstructuredCommandlineId(),
            BuildEventIdUtil.structuredCommandlineId(CommandLineEvent.OriginalCommandLineEvent.LABEL),
            BuildEventIdUtil.structuredCommandlineId(CommandLineEvent.CanonicalCommandLineEvent.LABEL),
            BuildEventIdUtil.structuredCommandlineId(CommandLineEvent.ToolCommandLineEvent.LABEL),
            BuildEventIdUtil.buildMetadataId(),
            BuildEventIdUtil.optionsParsedId(),
            BuildEventIdUtil.workspaceStatusId(),
            BuildEventIdUtil.targetPatternExpanded(request().getTargets()),
            BuildEventIdUtil.buildFinished()
        )

    override fun asStreamProto(converters: BuildEventContext?): BuildEvent {
        val version = Runtime.version()
        val javaVersionInfo: JavaVersionInfo? =
            JavaVersionInfo.newBuilder()
                .setJavaVersion(version.toString())
                .setJavaMajorVersion(version.feature())
                .setJavaMinorVersion(version.interim())
                .build()
        val started: BuildEventStreamProtos.BuildStarted.Builder =
            BuildEventStreamProtos.BuildStarted.newBuilder()
                .setUuid(request().getId().toString())
                .setStartTime(Timestamps.fromMillis(request().getStartTime()))
                .setStartTimeMillis(request().getStartTime())
                .setBuildToolVersion(BlazeVersionInfo.instance().getVersion())
                .setOptionsDescription(request().getOptionsDescription())
                .setCommand(request().getCommandName())
                .setServerPid(ProcessHandle.current().pid())
                .setWorkingDirectory(pwd())
                .setHost(NetUtil.getCachedShortHostName())
                .setUser(UserUtils.getUserName())
                .setJavaVersionInfo(javaVersionInfo)
        if (workspace() != null) {
            started.setWorkspaceDirectory(workspace())
        }
        return GenericBuildEvent.Companion.protoChaining(this).setStarted(started.build()).build()
    }

    companion object {
        /**
         * Construct the BuildStartingEvent
         * 
         * @param directories the server directories
         * @param outputService the output service
         * @param request the build request
         */
        fun create(
            directories: BlazeDirectories, outputService: OutputService?, request: BuildRequest?
        ): BuildStartingEvent {
            return create(
                getOutputFileSystemName(directories, outputService),
                outputService != null && outputService.actionFileSystemType().inMemoryFileSystem(),
                request,
                if (directories.getWorkspace() != null) directories.getWorkspace().toString() else null,
                directories.getWorkingDirectory().toString()
            )
        }

        @VisibleForTesting
        fun create(
            outputFileSystem: String?,
            usesInMemoryFileSystem: Boolean,
            request: BuildRequest?,
            workspace: String?,
            pwd: String?
        ): BuildStartingEvent {
            return AutoValue_BuildStartingEvent(
                outputFileSystem, usesInMemoryFileSystem, request, workspace, pwd
            )
        }

        /** Returns the name of the file system we are writing output to.  */
        fun getOutputFileSystemName(
            directories: BlazeDirectories, outputService: OutputService?
        ): String? {
            if (outputService == null) {
                return ""
            }
            val outputBaseFileSystemName: String?
            Profiler.instance().profile(ProfilerTask.INFO, "Finding output file system").use { c ->
                outputBaseFileSystemName = FileSystemUtils.getFileSystem(directories.getOutputBase())
            }
            return outputService.getFileSystemName(outputBaseFileSystemName)
        }
    }
}
