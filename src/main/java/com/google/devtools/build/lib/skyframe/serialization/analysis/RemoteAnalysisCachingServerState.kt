// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.skyframe.serialization.FrontierNodeVersion

/**
 * State to track information pertinent to Skyframe nodes that were deserialized from the remote
 * analysis cache through the lifetime of the Bazel server (until clean/shutdown), that is, across
 * multiple invocations.
 */
class RemoteAnalysisCachingServerState(version: FrontierNodeVersion?, clientId: ClientId?) {
    /** The [FrontierNodeVersion]  */
    private var latestInvocationVersion: FrontierNodeVersion?

    private var latestInvocationClientId: ClientId?

    init {
        this.latestInvocationVersion = version
        this.latestInvocationClientId = clientId
    }

    /** Returns [FrontierNodeVersion] of the latest (previous) invocation, if any.  */
    fun version(): FrontierNodeVersion? {
        return latestInvocationVersion
    }

    /**
     * Sets the [FrontierNodeVersion] of the remote analysis cache keys used in the current
     * invocation.
     * 
     * 
     * This will be used to determine invalidation during the next invocation.
     */
    fun setVersion(version: FrontierNodeVersion?) {
        this.latestInvocationVersion = version
    }

    /** Returns the [ClientId] of the latest (previous) invocation, if any.  */
    fun clientId(): ClientId? {
        return latestInvocationClientId
    }

    /** Sets the [ClientId] of the current invocation.  */
    fun setClientId(clientId: ClientId?) {
        this.latestInvocationClientId = clientId
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is RemoteAnalysisCachingServerState) {
            return false
        }
        return latestInvocationVersion == o.latestInvocationVersion
                && latestInvocationClientId == o.latestInvocationClientId
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(latestInvocationVersion, latestInvocationClientId)
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("version", latestInvocationVersion)
            .add("clientId", latestInvocationClientId)
            .toString()
    }

    companion object {
        /** Returns a [RemoteAnalysisCachingServerState] with empty/null fields.  */
        @kotlin.jvm.JvmStatic
        fun initializeEmpty(): RemoteAnalysisCachingServerState {
            return RemoteAnalysisCachingServerState( /* version= */null,  /* clientId= */null)
        }
    }
}
