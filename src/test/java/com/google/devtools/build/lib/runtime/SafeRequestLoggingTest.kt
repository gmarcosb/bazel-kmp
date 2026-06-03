// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [SafeRequestLogging].  */
@RunWith(JUnit4::class)
class SafeRequestLoggingTest {
    @org.junit.Test
    fun testGetRequestLogStringPassesThroughNonSensitiveClientEnv() {
        assertThat(
            SafeRequestLogging.getRequestLogString(
                com.google.common.collect.ImmutableList.of<E?>("--client_env=A=B", "--client_env=C=D")
            )
        )
            .isEqualTo("[--client_env=A=B, --client_env=C=D]")
    }

    @org.junit.Test
    fun testGetRequestLogStringToleratesNonsensicalClientEnv() {
        // Client env is key=value pairs, no '=' is silly, but shouldn't break anything.
        assertThat(SafeRequestLogging.getRequestLogString(com.google.common.collect.ImmutableList.of<E?>("--client_env=BROKEN")))
            .isEqualTo("[--client_env=BROKEN]")
    }

    @org.junit.Test
    fun testGetRequestLogStringStripsApparentAuthValues() {
        assertThat(
            SafeRequestLogging.getRequestLogString(
                com.google.common.collect.ImmutableList.of<E?>(
                    "--client_env=auth=notprinted",
                    "--client_env=other=isprinted"
                )
            )
        )
            .isEqualTo("[--client_env=auth=__private_value_removed__, --client_env=other=isprinted]")
    }

    @org.junit.Test
    fun testGetRequestLogStringStripsApparentCookieValues() {
        assertThat(
            SafeRequestLogging.getRequestLogString(
                com.google.common.collect.ImmutableList.of<E?>(
                    "--client_env=MY_COOKIE=notprinted", "--client_env=other=isprinted"
                )
            )
        )
            .isEqualTo(
                "[--client_env=MY_COOKIE=__private_value_removed__, --client_env=other=isprinted]"
            )
    }

    @org.junit.Test
    fun testGetRequestLogStringStripsApparentPasswordValues() {
        assertThat(
            SafeRequestLogging.getRequestLogString(
                com.google.common.collect.ImmutableList.of<E?>(
                    "--client_env=dont_paSS_ME=notprinted", "--client_env=other=isprinted"
                )
            )
        )
            .isEqualTo(
                "[--client_env=dont_paSS_ME=__private_value_removed__, --client_env=other=isprinted]"
            )
    }

    @org.junit.Test
    fun testGetRequestLogStringStripsApparentTokenValues() {
        assertThat(
            SafeRequestLogging.getRequestLogString(
                com.google.common.collect.ImmutableList.of<E?>(
                    "--client_env=service_ToKEn=notprinted", "--client_env=other=isprinted"
                )
            )
        )
            .isEqualTo(
                "[--client_env=service_ToKEn=__private_value_removed__, --client_env=other=isprinted]"
            )
    }

    @org.junit.Test
    fun testGetRequestLogStringStripsApparentApiKeyValues() {
        assertThat(
            SafeRequestLogging.getRequestLogString(
                com.google.common.collect.ImmutableList.of<E?>(
                    "--client_env=MY_API_KEY=notprinted", "--client_env=other=isprinted"
                )
            )
        )
            .isEqualTo(
                "[--client_env=MY_API_KEY=__private_value_removed__, --client_env=other=isprinted]"
            )
    }

    @org.junit.Test
    fun testGetRequestLogIgnoresSensitiveTermsInValues() {
        assertThat(SafeRequestLogging.getRequestLogString(com.google.common.collect.ImmutableList.of<E?>("--client_env=ok=COOKIE")))
            .isEqualTo("[--client_env=ok=COOKIE]")
    }

    @org.junit.Test
    fun testGetRequestLogForStandardCommandLine() {
        val complexCommandLine: MutableList<String?> = com.google.common.collect.ImmutableList.of<String?>(
            "blaze",
            "build",
            "--client_env=FOO=BAR",
            "--client_env=FOOPASS=mypassword",
            "--package_path=./MY_PASSWORD/foo",
            "--client_env=SOMEAuThCode=something"
        )
        assertThat(SafeRequestLogging.getRequestLogString(complexCommandLine))
            .isEqualTo(
                ("[blaze, build, --client_env=FOO=BAR, --client_env=FOOPASS=__private_value_removed__, "
                        + "--package_path=./MY_PASSWORD/foo, "
                        + "--client_env=SOMEAuThCode=__private_value_removed__]")
            )
    }
}
