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
import com.google.devtools.build.lib.authandtls.credentialhelper.GetCredentialsRequest
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.util.Locale

/**
 * Request for the `get` command of the [Credential
 * Helper Protocol](https://github.com/bazelbuild/proposals/blob/main/designs/2022-06-07-bazel-credential-helpers.md#proposal).
 * 
 * @param uri Returns the [URI] this request is for.
 */
@CopyAnnotations
@com.google.errorprone.annotations.Immutable
@JsonAdapter(com.google.devtools.build.lib.authandtls.credentialhelper.GetCredentialsRequest.GsonTypeAdapter::class)
class GetCredentialsRequest(uri: java.net.URI?) {
    /** Builder for [GetCredentialsRequest].  */
    @AutoBuilder
    abstract class Builder {
        /** Sets the [URI] this request is for.  */
        abstract fun setUri(uri: java.net.URI?): Builder?

        /** Returns the newly constructed [GetCredentialsRequest].  */
        abstract fun build(): GetCredentialsRequest?
    }

    /** GSON adapter for GetCredentialsRequest.  */
    class GsonTypeAdapter : TypeAdapter<GetCredentialsRequest?>() {
        @Throws(IOException::class)
        override fun write(writer: JsonWriter?, value: GetCredentialsRequest?) {
            com.google.common.base.Preconditions.checkNotNull<JsonWriter?>(writer)
            com.google.common.base.Preconditions.checkNotNull<GetCredentialsRequest?>(value)

            writer.beginObject()
            writer.name("uri").value(value!!.uri.toString())
            writer.endObject()
        }

        @Throws(IOException::class)
        override fun read(reader: JsonReader?): GetCredentialsRequest? {
            com.google.common.base.Preconditions.checkNotNull<JsonReader?>(reader)

            val request = newBuilder()

            if (reader.peek() != com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
                throw JsonSyntaxException(
                    java.lang.String.format(Locale.US, "Expected object, got %s", reader.peek())
                )
            }
            reader.beginObject()
            while (reader.hasNext()) {
                val name: String = reader.nextName()
                when (name) {
                    "uri" -> {
                        if (reader.peek() != com.google.gson.stream.JsonToken.STRING) {
                            throw JsonSyntaxException(
                                java.lang.String.format(
                                    Locale.US, "Expected value of 'url' to be a string, got %s", reader.peek()
                                )
                            )
                        }
                        request.setUri(java.net.URI.create(reader.nextString()))
                    }

                    else ->  // We intentionally ignore unknown keys to achieve forward compatibility with requests
                        // coming from newer tools.
                        reader.skipValue()
                }
            }
            reader.endObject()
            return request.build()
        }
    }

    val uri: java.net.URI?

    init {
        this.uri = uri
        java.util.Objects.requireNonNull<java.net.URI?>(uri, "uri")
    }

    companion object {
        /** Returns a new builder for [GetCredentialsRequest].  */
        fun newBuilder(): Builder {
            return AutoBuilder_GetCredentialsRequest_Builder()
        }
    }
}
