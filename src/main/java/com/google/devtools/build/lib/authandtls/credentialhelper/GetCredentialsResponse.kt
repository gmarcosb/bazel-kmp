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

import com.google.auto.value.AutoBuilder
import com.google.auto.value.AutoValue.CopyAnnotations
import com.google.devtools.build.lib.authandtls.credentialhelper.GetCredentialsResponse
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * Response from the `get` command of the [Credential
 * Helper Protocol](https://github.com/bazelbuild/proposals/blob/main/designs/2022-06-07-bazel-credential-helpers.md#proposal).
 * 
 * 
 * See the [specification](https://github.com/EngFlow/credential-helper-spec/blob/main/schemas/get-credentials-response.schema.json).
 * 
 * @param headers Returns the headers to attach to the request.
 * @param expires Returns the time the credentials expire and must be revalidated.
 */
@CopyAnnotations
@com.google.errorprone.annotations.Immutable
@JsonAdapter(com.google.devtools.build.lib.authandtls.credentialhelper.GetCredentialsResponse.GsonTypeAdapter::class)
class GetCredentialsResponse(
    headers: com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableList<String?>?>,
    expires: java.util.Optional<Instant?>
) {
    /** Builder for [GetCredentialsResponse].  */
    @AutoBuilder
    abstract class Builder {
        abstract fun headersBuilder(): com.google.common.collect.ImmutableMap.Builder<String?, com.google.common.collect.ImmutableList<String?>?>?

        abstract fun setExpires(instant: Instant?): Builder?

        /** Returns the newly constructed [GetCredentialsResponse].  */
        abstract fun build(): GetCredentialsResponse?
    }

    /** GSON adapter for GetCredentialsResponse.  */
    class GsonTypeAdapter : TypeAdapter<GetCredentialsResponse?>() {
        @Throws(IOException::class)
        override fun write(writer: JsonWriter?, response: GetCredentialsResponse?) {
            com.google.common.base.Preconditions.checkNotNull<JsonWriter?>(writer)
            com.google.common.base.Preconditions.checkNotNull<GetCredentialsResponse?>(response)

            writer.beginObject()

            val headers: com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableList<String?>?> =
                response!!.headers
            if (!headers.isEmpty()) {
                writer.name("headers")
                writer.beginObject()
                for (entry in headers.entrySet()) {
                    writer.name(entry.getKey())

                    writer.beginArray()
                    for (value in entry.getValue()) {
                        writer.value(value)
                    }
                    writer.endArray()
                }
                writer.endObject()
            }

            val expires: java.util.Optional<Instant?> = response.expires
            if (expires.isPresent()) {
                writer.name("expires")
                writer.value(RFC_3339_FORMATTER.format(expires.get()))
            }

            writer.endObject()
        }

        @Throws(IOException::class)
        override fun read(reader: JsonReader?): GetCredentialsResponse? {
            com.google.common.base.Preconditions.checkNotNull<JsonReader?>(reader)

            val response = newBuilder()

            if (reader.peek() != com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
                throw JsonSyntaxException(
                    java.lang.String.format(Locale.US, "Expected object, got %s", reader.peek())
                )
            }
            reader.beginObject()

            while (reader.hasNext()) {
                val name: String = reader.nextName()
                when (name) {
                    "headers" -> {
                        if (reader.peek() != com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
                            throw JsonSyntaxException(
                                java.lang.String.format(
                                    Locale.US,
                                    "Expected value of 'headers' to be an object, got %s",
                                    reader.peek()
                                )
                            )
                        }
                        reader.beginObject()

                        while (reader.hasNext()) {
                            val headerName: String = reader.nextName()
                            val headerValues: com.google.common.collect.ImmutableList.Builder<String?> =
                                com.google.common.collect.ImmutableList.builder<String?>()

                            if (reader.peek() != com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                                throw JsonSyntaxException(
                                    java.lang.String.format(
                                        Locale.US,
                                        "Expected value of '%s' header to be an array of strings, got %s",
                                        headerName,
                                        reader.peek()
                                    )
                                )
                            }
                            reader.beginArray()
                            var i = 0
                            while (reader.hasNext()) {
                                if (reader.peek() != com.google.gson.stream.JsonToken.STRING) {
                                    throw JsonSyntaxException(
                                        java.lang.String.format(
                                            Locale.US,
                                            "Expected value %s of '%s' header to be a string, got %s",
                                            i,
                                            headerName,
                                            reader.peek()
                                        )
                                    )
                                }
                                headerValues.add(reader.nextString())
                                i++
                            }
                            reader.endArray()

                            response.headersBuilder().put(headerName, headerValues.build())
                        }

                        reader.endObject()
                    }

                    "expires" -> {
                        if (reader.peek() != com.google.gson.stream.JsonToken.STRING) {
                            throw JsonSyntaxException(
                                java.lang.String.format(
                                    Locale.US,
                                    "Expected value of 'expires' to be a string, got %s",
                                    reader.peek()
                                )
                            )
                        }
                        try {
                            response.setExpires(Instant.from(RFC_3339_FORMATTER.parse(reader.nextString())))
                        } catch (e: DateTimeException) {
                            throw JsonSyntaxException(
                                java.lang.String.format(
                                    Locale.US,
                                    "Expected value of 'expires' to be a RFC 3339 formatted timestamp: %s",
                                    e.getMessage()
                                )
                            )
                        }
                    }

                    else ->  // We intentionally ignore unknown keys to achieve forward compatibility with
                        // responses
                        // coming from newer tools.
                        reader.skipValue()
                }
            }
            reader.endObject()
            return response.build()
        }
    }

    val headers: com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableList<String?>?>
    val expires: java.util.Optional<Instant?>

    init {
        this.expires = expires
        this.headers = headers
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableList<String?>?>?>(
            headers,
            "headers"
        )
        java.util.Objects.requireNonNull<java.util.Optional<Instant?>?>(expires, "expires")
    }

    companion object {
        val RFC_3339_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
            .withZone(ZoneId.from(ZoneOffset.UTC))
            .withResolverStyle(ResolverStyle.LENIENT)

        /** Returns a new builder for [GetCredentialsRequest].  */
        @kotlin.jvm.JvmStatic
        fun newBuilder(): Builder {
            return AutoBuilder_GetCredentialsResponse_Builder()
        }
    }
}
