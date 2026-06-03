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

// Tests for {@link ApiVersion}.
@RunWith(org.junit.runners.Parameterized::class)
class ClientApiVersionTest(
    name: String?,
    clientApiVersion: ClientApiVersion,
    serverCapabilities: ServerCapabilities?,
    expectedHighestSupportedVersion: ClientApiVersion.ServerSupportedStatus,
    expectedMessages: MutableList<String?>
) {
    private val clientApiVersion: ClientApiVersion
    private val serverCapabilities: ServerCapabilities?
    private val expectedHighestSupportedVersion: ClientApiVersion.ServerSupportedStatus
    private val expectedMessages: MutableList<String?>

    init {
        this.clientApiVersion = clientApiVersion
        this.serverCapabilities = serverCapabilities
        this.expectedHighestSupportedVersion = expectedHighestSupportedVersion
        this.expectedMessages = expectedMessages
    }

    @org.junit.Test
    fun testClientApiVersion() {
        val serverSupportedStatus: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            clientApiVersion.checkServerSupportedVersions(serverCapabilities)
        assertThat(serverSupportedStatus.isSupported())
            .isEqualTo(expectedHighestSupportedVersion.isSupported())
        assertThat(serverSupportedStatus.isUnsupported())
            .isEqualTo(expectedHighestSupportedVersion.isUnsupported())
        assertThat(serverSupportedStatus.isDeprecated())
            .isEqualTo(expectedHighestSupportedVersion.isDeprecated())
        assertThat(serverSupportedStatus.getMessage())
            .isEqualTo(expectedHighestSupportedVersion.getMessage())

        for (expectedMessage in expectedMessages) {
            com.google.common.truth.Subject.contains(expectedMessage)
        }
        if (expectedMessages.isEmpty()) {
            assertThat(serverSupportedStatus.getMessage()).isEmpty()
        }

        val expectedHigh: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            expectedHighestSupportedVersion.getHighestSupportedVersion()
        if (expectedHigh != null) {
            val high: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                serverSupportedStatus.getHighestSupportedVersion()

            assertThat(high).isNotNull()
            assertThat(high.compareTo(expectedHigh)).isEqualTo(0)
        }
    }

    companion object {
        @org.junit.runners.Parameterized.Parameters(name = "{0}")
        fun testCases(): MutableList<Array<Any?>?> {
            return java.util.Arrays.asList<Array<Any?>?>(
                *arrayOf<Array<Any?>?>(
                    arrayOf<Any?>(
                        "noSupportedVersion",
                        ClientApiVersion(
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(0).build()),
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(0).build())
                        ),
                        ServerCapabilities.newBuilder()
                            .setLowApiVersion(SemVer.newBuilder().setMajor(2).setMinor(1).build())
                            .setHighApiVersion(SemVer.newBuilder().setMajor(2).setMinor(2).build())
                            .build(),
                        ClientApiVersion.ServerSupportedStatus.unsupported(
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(0).build()),
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(0).build()),
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(1).build()),
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(2).build())
                        ),
                        mutableListOf<String?>("not supported", "2.0 to 2.0", "2.1 to 2.2")
                    ),
                    arrayOf<Any?>(
                        "deprecated",
                        ClientApiVersion(
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(0).build()),
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(0).build())
                        ),
                        ServerCapabilities.newBuilder()
                            .setDeprecatedApiVersion(SemVer.newBuilder().setMajor(2).setMinor(0).build())
                            .setLowApiVersion(SemVer.newBuilder().setMajor(2).setMinor(1).build())
                            .setHighApiVersion(SemVer.newBuilder().setMajor(2).setMinor(2).build())
                            .build(),
                        ClientApiVersion.ServerSupportedStatus.deprecated(
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(0).build()),
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(1).build()),
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(2).build())
                        ),
                        mutableListOf<String?>("2.0 is deprecated", "2.1 to 2.2")
                    ),
                    arrayOf<Any?>(
                        "clientHigh",
                        ClientApiVersion(
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(0).build()),
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(3).build())
                        ),
                        ServerCapabilities.newBuilder()
                            .setLowApiVersion(SemVer.newBuilder().setMajor(2).setMinor(0).build())
                            .setHighApiVersion(SemVer.newBuilder().setMajor(2).setMinor(4).build())
                            .build(),
                        ClientApiVersion.ServerSupportedStatus.supported(
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(3).build())
                        ),
                        mutableListOf<Any?>()
                    ),
                    arrayOf<Any?>(
                        "serverHigh",
                        ClientApiVersion(
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(0).build()),
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(3).build())
                        ),
                        ServerCapabilities.newBuilder()
                            .setLowApiVersion(SemVer.newBuilder().setMajor(2).setMinor(0).build())
                            .setHighApiVersion(SemVer.newBuilder().setMajor(2).setMinor(1).build())
                            .build(),
                        ClientApiVersion.ServerSupportedStatus.supported(
                            ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(1).build())
                        ),
                        mutableListOf<Any?>()
                    ),
                )
            )
        }
    }
}
