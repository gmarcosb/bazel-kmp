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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.Facts
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.IOException

/** Helps serialize/deserialize [Facts], which contains JSON-like Starlark values.  */
class FactsAdapter : TypeAdapter<Facts?>() {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val semantics: net.starlark.java.eval.StarlarkSemantics? =
        net.starlark.java.eval.StarlarkSemantics.builder() // Ensure that UTF-8 strings are encoded correctly, matching the default semantics derived
            // from BuildLanguageOptions.
            .setBool(net.starlark.java.eval.StarlarkSemantics.INTERNAL_BAZEL_ONLY_UTF_8_BYTE_STRINGS, true)
            .build()

    @Throws(IOException::class)
    override fun write(out: JsonWriter, facts: Facts) {
        val json: String?
        try {
            net.starlark.java.eval.Mutability.create("FactsAdapter").use { mu ->
                json = net.starlark.java.lib.json.Json.INSTANCE.encode(
                    facts.value(),
                    net.starlark.java.eval.StarlarkThread.createTransient(mu, semantics)
                )
            }
        } catch (e: net.starlark.java.eval.EvalException) {
            throw java.lang.IllegalStateException(
                "Unexpected error while serializing facts (%s): %s"
                    .formatted(facts.value(), e.getMessage()),
                e
            )
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException(
                "Unexpected error while serializing facts (%s): %s"
                    .formatted(facts.value(), e.getMessage()),
                e
            )
        }
        // Round-trip the JSON through Gson to ensure it is properly indented.
        gson.toJson(gson.fromJson<JsonElement?>(json, JsonElement::class.java), out)
    }

    @Throws(IOException::class)
    override fun read(`in`: JsonReader): Facts {
        val jsonString: String? = gson.toJson(com.google.gson.JsonParser.parseReader(`in`))
        try {
            net.starlark.java.eval.Mutability.create("FactsAdapter").use { mu ->
                val starlarkThread: net.starlark.java.eval.StarlarkThread =
                    net.starlark.java.eval.StarlarkThread.createTransient(mu, semantics)
                return Facts.Companion.validateAndCreate(
                    net.starlark.java.lib.json.Json.INSTANCE.decode(
                        jsonString,
                        net.starlark.java.eval.Starlark.UNBOUND,
                        starlarkThread
                    )
                )
            }
        } catch (e: net.starlark.java.eval.EvalException) {
            throw IOException("Failed to decode facts JSON: " + e.getMessage(), e)
        }
    }
}
