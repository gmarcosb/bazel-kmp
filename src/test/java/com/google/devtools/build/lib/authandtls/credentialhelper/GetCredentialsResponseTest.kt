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

import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth
import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorDescriptor.Builder.build
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.time.Instant

/** Tests for [GetCredentialsResponse].  */
@RunWith(JUnit4::class)
class GetCredentialsResponseTest {
    @Test
    fun parseValid() {
        assertThat(GSON.fromJson<GetCredentialsResponse?>("{}", GetCredentialsResponse::class.java).headers()).isEmpty()
        assertThat(
            GSON.fromJson<GetCredentialsResponse?>("{\"headers\": {}}", GetCredentialsResponse::class.java).headers()
        )
            .isEmpty()

        val expectedResponseBuilder: GetCredentialsResponse.Builder = GetCredentialsResponse.newBuilder()
        expectedResponseBuilder.headersBuilder().put("a", ImmutableList.of<E?>())
        expectedResponseBuilder.headersBuilder().put("b", ImmutableList.of<E?>("b"))
        expectedResponseBuilder.headersBuilder().put("c", ImmutableList.of<E?>("c", "c"))
        val expectedResponse: GetCredentialsResponse? = expectedResponseBuilder.build()

        assertThat(
            GSON.fromJson<GetCredentialsResponse?>(
                "{\"headers\": {\"c\": [\"c\", \"c\"], \"a\": [], \"b\": [\"b\"]}}",
                GetCredentialsResponse::class.java
            )
        )
            .isEqualTo(expectedResponse)
    }

    @Test
    fun parseWithExtraFields() {
        assertThat(
            GSON.fromJson<GetCredentialsResponse?>("{\"foo\": 123}", GetCredentialsResponse::class.java).headers()
        ).isEmpty()
        assertThat(
            GSON.fromJson<GetCredentialsResponse?>(
                "{\"foo\": 123, \"bar\": []}",
                GetCredentialsResponse::class.java
            ).headers()
        )
            .isEmpty()

        val expectedResponseBuilder: GetCredentialsResponse.Builder = GetCredentialsResponse.newBuilder()
        expectedResponseBuilder.headersBuilder().put("a", ImmutableList.of<E?>())
        expectedResponseBuilder.headersBuilder().put("b", ImmutableList.of<E?>("b"))
        expectedResponseBuilder.headersBuilder().put("c", ImmutableList.of<E?>("c", "c"))
        val expectedResponse: GetCredentialsResponse? = expectedResponseBuilder.build()

        assertThat(
            GSON.fromJson<GetCredentialsResponse?>(
                "{\"foo\": 123, \"headers\": {\"c\": [\"c\", \"c\"], \"a\": [], \"b\": [\"b\"]},"
                        + " \"bar\": 123}",
                GetCredentialsResponse::class.java
            )
        )
            .isEqualTo(expectedResponse)
    }

    @Test
    fun parseInvalid() {
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable { GSON.fromJson<GetCredentialsResponse?>("[]", GetCredentialsResponse::class.java) })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable { GSON.fromJson<GetCredentialsResponse?>("\"foo\"", GetCredentialsResponse::class.java) })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable { GSON.fromJson<GetCredentialsResponse?>("1", GetCredentialsResponse::class.java) })
    }

    @Test
    fun parseInvalidHeadersEnvelope() {
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": null}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": \"foo\"}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": []}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": 1}",
                    GetCredentialsResponse::class.java
                )
            })
    }

    @Test
    fun parseInvalidHeadersValue() {
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": {\"a\": null}}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": {\"a\": 1}}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": {\"a\": {}}}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": {\"a\": \"a\"}}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": {\"a\": [null]}}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": {\"a\": [\"a\", null]}}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": {\"a\": [null, \"a\"]}}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": {\"a\": [\"a\", 1]}}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": {\"a\": [1, \"a\"]}}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": {\"a\": [\"a\", []]}}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": {\"a\": [[], \"a\"]}}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": {\"a\": [\"a\", {}]}}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"headers\": {\"a\": [{}, \"a\"]}}",
                    GetCredentialsResponse::class.java
                )
            })
    }

    @Test
    fun parseExpires() {
        assertThat(
            GSON.fromJson<GetCredentialsResponse?>(
                "{\"expires\": \"1970-09-29T11:46:29Z\"}",
                GetCredentialsResponse::class.java
            )
                .expires()
        )
            .hasValue(Instant.ofEpochSecond(23456789))
        assertThat(
            GSON.fromJson<GetCredentialsResponse?>(
                "{\"expires\": \"1970-09-29T11:46:29+00:00\"}", GetCredentialsResponse::class.java
            )
                .expires()
        )
            .hasValue(Instant.ofEpochSecond(23456789))
        assertThat(
            GSON.fromJson<GetCredentialsResponse?>(
                "{\"expires\": \"1970-09-29T13:46:29+02:00\"}", GetCredentialsResponse::class.java
            )
                .expires()
        )
            .hasValue(Instant.ofEpochSecond(23456789))
        assertThat(
            GSON.fromJson<GetCredentialsResponse?>(
                "{\"expires\": \"1970-09-28T23:46:29-12:00\"}", GetCredentialsResponse::class.java
            )
                .expires()
        )
            .hasValue(Instant.ofEpochSecond(23456789))
    }

    @Test
    fun parseInvalidExpires() {
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"expires\": null}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"expires\": \"foo\"}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"expires\": []}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"expires\": 1}",
                    GetCredentialsResponse::class.java
                )
            })
        Assert.assertThrows<JsonSyntaxException?>(
            JsonSyntaxException::class.java,
            ThrowingRunnable {
                GSON.fromJson<GetCredentialsResponse?>(
                    "{\"expires\": {}}",
                    GetCredentialsResponse::class.java
                )
            })
    }

    @Test
    fun serializeEmptyHeaders() {
        val expectedResponse: GetCredentialsResponse? = GetCredentialsResponse.newBuilder().build()
        Truth.assertThat(GSON.toJson(expectedResponse)).isEqualTo("{}")
    }

    @Test
    fun roundTrip() {
        val expectedResponseBuilder: GetCredentialsResponse.Builder =
            GetCredentialsResponse.newBuilder().setExpires(Instant.ofEpochSecond(123456789))
        expectedResponseBuilder.headersBuilder().put("a", ImmutableList.of<E?>())
        expectedResponseBuilder.headersBuilder().put("b", ImmutableList.of<E?>("b"))
        expectedResponseBuilder.headersBuilder().put("c", ImmutableList.of<E?>("c", "c"))
        val expectedResponse: GetCredentialsResponse? = expectedResponseBuilder.build()

        assertThat(
            GSON.fromJson<GetCredentialsResponse?>(
                GSON.toJson(expectedResponse),
                GetCredentialsResponse::class.java
            )
        )
            .isEqualTo(expectedResponse)
    }

    companion object {
        private val GSON = Gson()
    }
}
