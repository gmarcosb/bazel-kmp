// Copyright 2023 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.ServerCapabilities

/** Represents a range of the Remote Execution API that client supports.  */
class ClientApiVersion(low: ApiVersion, high: ApiVersion) {
    private val low: ApiVersion
    private val high: ApiVersion

    init {
        this.low = low
        this.high = high
    }

    fun getLow(): ApiVersion {
        return low
    }

    fun getHigh(): ApiVersion {
        return high
    }

    fun isSupported(version: ApiVersion?): Boolean {
        return low.compareTo(version) <= 0 && high.compareTo(version) >= 0
    }

    internal class ServerSupportedStatus private constructor(
        private val state: State?,
        val message: String?,
        highestSupportedVersion: ApiVersion?
    ) {
        private enum class State {
            SUPPORTED,
            UNSUPPORTED,
            DEPRECATED,
        }

        private val highestSupportedVersion: ApiVersion?

        init {
            this.highestSupportedVersion = highestSupportedVersion
        }

        fun getHighestSupportedVersion(): ApiVersion? {
            return highestSupportedVersion
        }

        val isSupported: Boolean
            get() = state == com.google.devtools.build.lib.remote.ClientApiVersion.ServerSupportedStatus.State.SUPPORTED

        val isDeprecated: Boolean
            get() = state == com.google.devtools.build.lib.remote.ClientApiVersion.ServerSupportedStatus.State.DEPRECATED

        val isUnsupported: Boolean
            get() = state == com.google.devtools.build.lib.remote.ClientApiVersion.ServerSupportedStatus.State.UNSUPPORTED

        companion object {
            fun supported(highestSupportedVersion: ApiVersion?): ServerSupportedStatus {
                return ServerSupportedStatus(
                    com.google.devtools.build.lib.remote.ClientApiVersion.ServerSupportedStatus.State.SUPPORTED,
                    "",
                    highestSupportedVersion
                )
            }

            fun unsupported(
                clientLow: ApiVersion?, clientHigh: ApiVersion?, serverLow: ApiVersion?, serverHigh: ApiVersion?
            ): ServerSupportedStatus {
                return ServerSupportedStatus(
                    com.google.devtools.build.lib.remote.ClientApiVersion.ServerSupportedStatus.State.UNSUPPORTED,
                    java.lang.String.format(
                        "The client supported API versions, %s to %s, is not supported by the server, %s to"
                                + " %s. Please switch to a different server or upgrade Bazel.",
                        clientLow, clientHigh, serverLow, serverHigh
                    ),
                    null
                )
            }

            fun deprecated(
                clientHigh: ApiVersion?, serverLow: ApiVersion?, serverHigh: ApiVersion?
            ): ServerSupportedStatus {
                return ServerSupportedStatus(
                    com.google.devtools.build.lib.remote.ClientApiVersion.ServerSupportedStatus.State.DEPRECATED,
                    java.lang.String.format(
                        "The highest API version Bazel support %s is deprecated by the server. "
                                + "Please upgrade to server's recommended version: %s to %s.",
                        clientHigh, serverLow, serverHigh
                    ),
                    clientHigh
                )
            }
        }
    }

    // highestSupportedVersion compares the client's supported versions against the input low and high
    // versions and returns the highest supported version. If the client's supported versions are not
    // supported by the server, it returns null.
    private fun highestSupportedVersion(serverLow: ApiVersion?, serverHigh: ApiVersion?): ApiVersion? {
        val higestLow: ApiVersion = com.google.common.collect.Comparators.max<ApiVersion>(this.low, serverLow)
        val lowestHigh: ApiVersion = com.google.common.collect.Comparators.min<ApiVersion>(this.high, serverHigh)

        return if (higestLow.compareTo(lowestHigh) <= 0) lowestHigh else null
    }

    fun checkServerSupportedVersions(cap: ServerCapabilities): ServerSupportedStatus {
        val serverLow: ApiVersion = ApiVersion(cap.getLowApiVersion())
        val serverHigh: ApiVersion = ApiVersion(cap.getHighApiVersion())

        var highest: ApiVersion? = highestSupportedVersion(serverLow, serverHigh)
        if (highest != null) {
            return ServerSupportedStatus.Companion.supported(highest)
        }

        val deprecated: ApiVersion? =
            if (cap.hasDeprecatedApiVersion()) ApiVersion(cap.getDeprecatedApiVersion()) else null
        if (deprecated == null) {
            return ServerSupportedStatus.Companion.unsupported(this.low, this.high, serverLow, serverHigh)
        }

        highest = highestSupportedVersion(deprecated, serverHigh)
        if (highest != null) {
            return ServerSupportedStatus.Companion.deprecated(highest, serverLow, serverHigh)
        }

        return ServerSupportedStatus.Companion.unsupported(this.low, this.high, serverLow, serverHigh)
    }

    companion object {
        val current: ClientApiVersion = ClientApiVersion(ApiVersion.Companion.low, ApiVersion.Companion.high)
    }
}
