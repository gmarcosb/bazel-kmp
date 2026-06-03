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
package com.google.devtools.build.lib.actions


import com.google.devtools.build.lib.buildeventstream.BuildEvent

/**
 * This event is fired during the build, when an action is executed. It contains information about
 * the action: the Action itself, and the output file names its stdout and stderr are recorded in.
 */
class ActionExecutedEvent(
    actionId: PathFragment?,
    action: com.google.devtools.build.lib.actions.Action,
    exception: ActionExecutionException?,
    primaryOutput: Path?,
    outputArtifact: Artifact?,
    primaryOutputMetadata: FileArtifactValue?,
    stdout: Path?,
    stderr: Path?,
    timing: ErrorTiming?,
    startTime: Instant?,
    endTime: Instant?
) : BuildEventWithConfiguration {
    private val actionId: PathFragment?
    private val action: com.google.devtools.build.lib.actions.Action
    private val exception: ActionExecutionException?
    private val primaryOutput: Path?
    private val outputArtifact: Artifact?
    private val primaryOutputMetadata: FileArtifactValue?
    private val stdout: Path?
    private val stderr: Path?
    private val timing: ErrorTiming?

    /** Timestamp of the action starting; if no timestamp is available will be `null`.  */
    private val startTime: Instant?

    /** Timestamp of the action finishing; if no timestamp is available will be `null`.  */
    private val endTime: Instant?

    init {
        this.actionId = actionId
        this.action = action
        this.exception = exception
        this.primaryOutput = primaryOutput
        this.outputArtifact = outputArtifact
        this.primaryOutputMetadata = primaryOutputMetadata
        this.stdout = stdout
        this.stderr = stderr
        this.timing = timing
        this.startTime = startTime
        this.endTime = endTime
        com.google.common.base.Preconditions.checkState(
            (this.exception == null) == (this.timing == ErrorTiming.NO_ERROR), this
        )
        com.google.common.base.Preconditions.checkState(
            (this.exception == null) != (this.primaryOutputMetadata == null), this
        )
    }

    fun getAction(): com.google.devtools.build.lib.actions.Action {
        return action
    }

    // null if action succeeded
    fun getException(): ActionExecutionException? {
        return exception
    }

    fun errorTiming(): ErrorTiming? {
        return timing
    }

    fun getStdout(): String? {
        if (stdout == null) {
            return null
        }
        return stdout.toString()
    }

    fun getStderr(): String? {
        if (stderr == null) {
            return null
        }
        return stderr.toString()
    }

    fun getPrimaryOutputMetadata(): FileArtifactValue? {
        return primaryOutputMetadata
    }

    public override fun getEventId(): BuildEventId {
        if (action.getOwner() == null) {
            return BuildEventIdUtil.actionCompleted(actionId)
        } else {
            return BuildEventIdUtil.actionCompleted(
                actionId, action.getOwner().getLabel(), action.getOwner().getConfigurationChecksum()
            )
        }
    }

    public override fun getChildrenEvents(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<BuildEventId?>()
    }

    public override fun getConfigurations(): MutableCollection<BuildEvent?> {
        if (action.getOwner() != null) {
            var configuration: BuildEvent? = action.getOwner().getBuildConfigurationEvent()
            if (configuration == null) {
                configuration = NullConfiguration.INSTANCE
            }
            return com.google.common.collect.ImmutableList.of<BuildEvent?>(configuration)
        } else {
            return com.google.common.collect.ImmutableList.of<BuildEvent?>()
        }
    }

    public override fun referencedLocalFiles(): MutableCollection<LocalFile?> {
        val localFiles: com.google.common.collect.ImmutableList.Builder<LocalFile?> =
            com.google.common.collect.ImmutableList.builder<LocalFile?>()
        // TODO(b/199940216): thread file metadata through here when possible.
        if (stdout != null) {
            localFiles.add(LocalFile(stdout, LocalFileType.STDOUT,  /* artifactMetadata= */null))
        }
        if (stderr != null) {
            localFiles.add(LocalFile(stderr, LocalFileType.STDERR,  /* artifactMetadata= */null))
        }
        if (exception == null) {
            localFiles.add(
                LocalFile(
                    primaryOutput,
                    LocalFileType.forArtifact(outputArtifact, primaryOutputMetadata),
                    primaryOutputMetadata
                )
            )
        }
        return localFiles.build()
    }

    @Throws(java.lang.InterruptedException::class)
    public override fun asStreamProto(converters: BuildEventContext): BuildEventStreamProtos.BuildEvent {
        val pathConverter: PathConverter = converters.pathConverter()
        val actionBuilder: BuildEventStreamProtos.ActionExecuted.Builder =
            BuildEventStreamProtos.ActionExecuted.newBuilder()
                .setSuccess(getException() == null)
                .setType(action.getMnemonic())
        if (startTime != null) {
            actionBuilder.setStartTime(timestampProto(startTime))
            if (endTime != null) {
                actionBuilder.setEndTime(timestampProto(endTime))
            }
        }

        if (exception != null) {
            // TODO(b/150405553): This statement seems to be confused. The exit_code field of
            //  ActionExecuted is documented as "The exit code of the action, if it is available."
            //  However, the value returned by exception.getExitCode().getNumericExitCode() is intended as
            //  an exit code that this Bazel invocation might return to the user.
            actionBuilder.setExitCode(exception.getExitCode().getNumericExitCode())
            val failureDetail: FailureDetails.FailureDetail? =
                exception.getDetailedExitCode().getFailureDetail()
            if (failureDetail != null) {
                actionBuilder.setFailureDetail(failureDetail)
            }
        }
        if (stdout != null) {
            val uri: String? = pathConverter.apply(stdout)
            if (uri != null) {
                actionBuilder.setStdout(
                    BuildEventStreamProtos.File.newBuilder().setName("stdout").setUri(uri).build()
                )
            }
        }
        if (stderr != null) {
            val uri: String? = pathConverter.apply(stderr)
            if (uri != null) {
                actionBuilder.setStderr(
                    BuildEventStreamProtos.File.newBuilder().setName("stderr").setUri(uri).build()
                )
            }
        }
        if (action.getOwner() != null && action.getOwner().getLabel() != null) {
            actionBuilder.setLabel(action.getOwner().getLabel().toString())
        }
        if (action.getOwner() != null) {
            var configuration: BuildEvent? = action.getOwner().getBuildConfigurationEvent()
            if (configuration == null) {
                configuration = NullConfiguration.INSTANCE
            }
            actionBuilder.setConfiguration(configuration.getEventId().getConfiguration())
        }
        if (exception == null) {
            val uri: String? = pathConverter.apply(primaryOutput)
            if (uri != null) {
                actionBuilder.setPrimaryOutput(
                    BuildEventStreamProtos.File.newBuilder().setUri(uri).build()
                )
            }
        }
        try {
            if (action is CommandAction) {
                actionBuilder.addAllCommandLine(action.getArguments())
            }
        } catch (e: CommandLineExpansionException) {
            // Command-line not available, so just not report it
            logger.atInfo().withCause(e).log("Could not compute commandline of reported action")
        }
        return GenericBuildEvent.protoChaining(this).setAction(actionBuilder.build()).build()
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("exception", exception)
            .add("timing", timing)
            .add("stdout", stdout)
            .add("stderr", stderr)
            .add("action", action)
            .add("primaryOutput", primaryOutput)
            .add("outputArtifact", outputArtifact)
            .add("primaryOutputMetadata", primaryOutputMetadata)
            .add("startTime", startTime)
            .add("endTime", endTime)
            .toString()
    }

    /** When an error occurred that aborted action execution, if any.  */
    enum class ErrorTiming {
        NO_ERROR,
        BEFORE_EXECUTION,
        AFTER_EXECUTION
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun timestampProto(time: Instant): Timestamp {
            return Timestamp.newBuilder()
                .setSeconds(time.getEpochSecond())
                .setNanos(time.getNano())
                .build()
        }
    }
}
