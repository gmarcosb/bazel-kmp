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
package com.google.devtools.build.lib.buildeventservice

import BuildEventStreamProtos.BuildEvent
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceOptions
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceOptions.BesUploadMode
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceUploader
import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient
import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient.CommandContext
import com.google.devtools.build.lib.buildeventstream.ArtifactGroupNamer
import com.google.devtools.build.lib.buildeventstream.BuildEvent
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions
import com.google.devtools.build.lib.buildeventstream.BuildEventTransport
import com.google.devtools.build.lib.util.JavaSleeper
import java.time.Instant

/** A [BuildEventTransport] that streams [BuildEvent]s to BuildEventService.  */
class BuildEventServiceTransport private constructor(
    besClient: BuildEventServiceClient?,
    localFileUploader: BuildEventArtifactUploader?,
    bepOptions: BuildEventProtocolOptions?,
    clock: com.google.devtools.build.lib.clock.Clock?,
    publishLifecycleEvents: Boolean,
    artifactGroupNamer: ArtifactGroupNamer?,
    eventBus: com.google.common.eventbus.EventBus?,
    closeTimeout: java.time.Duration?,
    sleeper: com.google.devtools.build.lib.util.Sleeper?,
    commandContext: CommandContext?,
    commandStartTime: Instant?,
    besUploadMode: BesUploadMode?
) : BuildEventTransport {
    private val besUploader: BuildEventServiceUploader
    private val besTimeout: java.time.Duration?
    private val besUploadMode: BesUploadMode?

    init {
        this.besTimeout = closeTimeout
        this.besUploader =
            com.google.devtools.build.lib.buildeventservice.BuildEventServiceUploader.Builder()
                .besClient(besClient)
                .localFileUploader(localFileUploader)
                .bepOptions(bepOptions)
                .clock(clock)
                .publishLifecycleEvents(publishLifecycleEvents)
                .sleeper(sleeper)
                .artifactGroupNamer(artifactGroupNamer)
                .eventBus(eventBus)
                .commandContext(commandContext)
                .commandStartTime(commandStartTime)
                .build()
        this.besUploadMode = besUploadMode
    }

    override fun close(): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? {
        return besUploader.close()
    }

    val halfCloseFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?
        get() = besUploader.getHalfCloseFuture()

    val uploader: BuildEventArtifactUploader?
        get() = besUploader.getBuildEventUploader()

    override fun name(): String {
        return "Build Event Service"
    }

    override fun mayBeSlow(): Boolean {
        return true
    }

    override fun getBesUploadMode(): BesUploadMode? {
        return besUploadMode
    }

    override fun sendBuildEvent(event: BuildEvent) {
        besUploader.enqueueEvent(event)
    }

    val timeout: java.time.Duration?
        get() = besTimeout

    /** A builder for [BuildEventServiceTransport].  */
    class Builder {
        private var besClient: BuildEventServiceClient? = null
        private var localFileUploader: BuildEventArtifactUploader? = null
        private var besOptions: BuildEventServiceOptions? = null
        private var bepOptions: BuildEventProtocolOptions? = null
        private var clock: com.google.devtools.build.lib.clock.Clock? = null
        private var artifactGroupNamer: ArtifactGroupNamer? = null
        private var eventBus: com.google.common.eventbus.EventBus? = null
        private var sleeper: com.google.devtools.build.lib.util.Sleeper? = null
        private var commandContext: CommandContext? = null
        private var commandStartTime: Instant? = null

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun besClient(value: BuildEventServiceClient?): Builder {
            this.besClient = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun localFileUploader(value: BuildEventArtifactUploader?): Builder {
            this.localFileUploader = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun bepOptions(value: BuildEventProtocolOptions?): Builder {
            this.bepOptions = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun besOptions(value: BuildEventServiceOptions): Builder {
            this.besOptions = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun clock(value: com.google.devtools.build.lib.clock.Clock?): Builder {
            this.clock = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun artifactGroupNamer(value: ArtifactGroupNamer?): Builder {
            this.artifactGroupNamer = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun eventBus(value: com.google.common.eventbus.EventBus?): Builder {
            this.eventBus = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @com.google.common.annotations.VisibleForTesting
        fun sleeper(value: com.google.devtools.build.lib.util.Sleeper?): Builder {
            this.sleeper = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun commandContext(value: CommandContext?): Builder {
            this.commandContext = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun commandStartTime(value: Instant?): Builder {
            this.commandStartTime = value
            return this
        }

        fun build(): BuildEventServiceTransport {
            com.google.common.base.Preconditions.checkNotNull<BuildEventServiceOptions?>(besOptions)
            return BuildEventServiceTransport(
                com.google.common.base.Preconditions.checkNotNull<BuildEventServiceClient?>(besClient),
                com.google.common.base.Preconditions.checkNotNull<BuildEventArtifactUploader?>(localFileUploader),
                com.google.common.base.Preconditions.checkNotNull<BuildEventProtocolOptions?>(bepOptions),
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.clock.Clock?>(clock),
                besOptions.getBesLifecycleEvents(),
                com.google.common.base.Preconditions.checkNotNull<ArtifactGroupNamer?>(artifactGroupNamer),
                com.google.common.base.Preconditions.checkNotNull<com.google.common.eventbus.EventBus?>(eventBus),
                if (besOptions.getBesTimeout() != null) besOptions.getBesTimeout() else java.time.Duration.ZERO,
                if (sleeper != null) sleeper else JavaSleeper(),
                com.google.common.base.Preconditions.checkNotNull<CommandContext?>(commandContext),
                com.google.common.base.Preconditions.checkNotNull<Instant?>(commandStartTime),
                besOptions.getBesUploadMode()
            )
        }
    }
}
