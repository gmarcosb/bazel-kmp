// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildeventstream

import BuildEventStreamProtos.BuildEvent
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceOptions.BesUploadMode
import com.google.devtools.build.lib.buildeventstream.BuildEvent
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader

/**
 * Transport interface for the build event protocol.
 * 
 * 
 * All implementations need to be thread-safe. All methods are expected to return quickly.
 * 
 * 
 * Notice that this interface does not provide any error handling API. A transport may choose to
 * log interesting errors to the command line and/or abort the whole build.
 */
@javax.annotation.concurrent.ThreadSafe
interface BuildEventTransport {
    /** The name of this transport as can be displayed to a user.  */
    fun name(): String?

    /**
     * Writes a build event to an endpoint. This method will always return quickly and will not wait
     * for the write to complete.
     * 
     * 
     * In case the transport is in error, this method still needs to be able to accept build
     * events. It may choose to ignore them, though.
     * 
     * @param event the event to sendBuildEvent.
     */
    fun sendBuildEvent(event: BuildEvent?)

    /**
     * Initiates a close. Callers may listen to the returned future to be notified when the close is
     * complete i.e. wait for all build events to be sent and acknowledged. The future may contain any
     * information about possible transport errors.
     * 
     * 
     * This method might be called multiple times without any effect after the first call.
     * 
     * 
     * This method should not throw any exceptions, but the returned Future might.
     */
    fun close(): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?

    val halfCloseFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?
        /**
         * Returns the status of half-close. Callers may listen to the return future to be notified when
         * the half-close is complete
         * 
         * 
         * Half-close indicates that all client-side data is transmitted but still waiting on
         * server-side acknowledgement. The client must buffer the information in case the server fails to
         * acknowledge.
         * 
         * 
         * Implementations may choose to return the full close Future via [.close] if there is
         * no sensible half-close state.
         * 
         * 
         * This should be only called after [.close].
         */
        get() = close()

    val timeout: java.time.Duration?
        /**
         * Returns how long a caller should wait for the transport to finish uploading events and closing
         * gracefully. Setting the timeout to [Duration.ZERO] means that there's no timeout.
         */
        get() = java.time.Duration.ZERO

    /**
     * Return true if the transport upload may be "slow". Examples of slowness include writes to
     * remote services or use of a "slow" [BuildEventArtifactUploader].
     */
    fun mayBeSlow(): Boolean

    /** Returns the desired [BesUploadMode] for the transport.  */
    val besUploadMode: BesUploadMode?

    @kotlin.jvm.JvmField
    val uploader: BuildEventArtifactUploader?
}
