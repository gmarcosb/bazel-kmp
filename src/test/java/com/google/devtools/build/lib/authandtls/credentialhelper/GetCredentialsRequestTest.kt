// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.authandtls.credentialhelper

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.net.URI

/** Tests for [GetCredentialsRequest].  */
@RunWith(JUnit4::class)
class GetCredentialsRequestTest {
    @Test
    fun parseValid() {
        assertThat(
            GSON.fromJson<GetCredentialsRequest?>(
                "{\"uri\": \"http://example.com\"}",
                GetCredentialsRequest::class.java
            ).uri()
        )
            .isEqualTo(URI.create("http://example.com"))
        assertThat(
            GSON.fromJson<GetCredentialsRequest?>(
                "{\"uri\": \"https://example.com\"}",
                GetCredentialsRequest::class.java
            ).uri()
        )
            .isEqualTo(URI.create("https://example.com"))
        assertThat(
            GSON.fromJson<GetCredentialsRequest?>(
                "{\"uri\": \"grpc://example.com\"}",
                GetCredentialsRequest::class.java
            ).uri()
        )
            .isEqualTo(URI.create("grpc://example.com"))
        assertThat(
            GSON.fromJson<GetCredentialsRequest?>(
                "{\"uri\": \"grpcs://example.com\"}",
                GetCredentialsRequest::class.java
            ).uri()
        )
            .isEqualTo(URI.create("grpcs://example.com"))

        assertThat(
            GSON.fromJson<GetCredentialsRequest?>(
                "{\"uri\": \"uri-without-protocol\"}",
                GetCredentialsRequest::class.java
            ).uri()
        )
            .isEqualTo(URI.create("uri-without-protocol"))
    }

    @Test
    fun parseMissingUri() {
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable { GSON.fromJson<GetCredentialsRequest?>("{}", GetCredentialsRequest::class.java) })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsRequest?>(
                    "{\"foo\": 1}",
                    GetCredentialsRequest::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsRequest?>(
                    "{\"foo\": 1, \"bar\": 2}",
                    GetCredentialsRequest::class.java
                )
            })
    }

    @Test
    fun parseNonStringUri() {
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable { GSON.fromJson<GetCredentialsRequest?>("[]", GetCredentialsRequest::class.java) })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable { GSON.fromJson<GetCredentialsRequest?>("\"foo\"", GetCredentialsRequest::class.java) })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable { GSON.fromJson<GetCredentialsRequest?>("1", GetCredentialsRequest::class.java) })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsRequest?>(
                    "{\"uri\": 1}",
                    GetCredentialsRequest::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsRequest?>(
                    "{\"uri\": {}}",
                    GetCredentialsRequest::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsRequest?>(
                    "{\"uri\": []}",
                    GetCredentialsRequest::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsRequest?>(
                    "{\"uri\": [\"https://example.com\"]}",
                    GetCredentialsRequest::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsRequest?>(
                    "{\"uri\": null}",
                    GetCredentialsRequest::class.java
                )
            })
    }

    @Test
    fun parseWithExtraFields() {
        assertThat(
            GSON.fromJson<GetCredentialsRequest?>(
                "{\"uri\": \"http://example.com\", \"foo\": 1}", GetCredentialsRequest::class.java
            )
                .uri()
        )
            .isEqualTo(URI.create("http://example.com"))
        assertThat(
            GSON.fromJson<GetCredentialsRequest?>(
                "{\"foo\": 1, \"uri\": \"http://example.com\"}", GetCredentialsRequest::class.java
            )
                .uri()
        )
            .isEqualTo(URI.create("http://example.com"))
        assertThat(
            GSON.fromJson<GetCredentialsRequest?>(
                "{\"uri\": \"http://example.com\", \"foo\": 1, \"bar\": {}}",
                GetCredentialsRequest::class.java
            )
                .uri()
        )
            .isEqualTo(URI.create("http://example.com"))
        assertThat(
            GSON.fromJson<GetCredentialsRequest?>(
                "{\"foo\": 1, \"uri\": \"http://example.com\", \"bar\": []}",
                GetCredentialsRequest::class.java
            )
                .uri()
        )
            .isEqualTo(URI.create("http://example.com"))
    }

    companion object {
        private val GSON = Gson()
    }
}
