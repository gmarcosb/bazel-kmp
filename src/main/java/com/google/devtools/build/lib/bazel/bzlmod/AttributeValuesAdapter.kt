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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.util.StringEncoding
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.IOException

/** Helps serialize/deserialize [AttributeValues], which contains Starlark values.  */
class AttributeValuesAdapter : TypeAdapter<com.google.devtools.build.lib.bazel.bzlmod.AttributeValues?>() {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    @Throws(IOException::class)
    override fun write(out: JsonWriter, attributeValues: com.google.devtools.build.lib.bazel.bzlmod.AttributeValues) {
        out.beginObject()
        for (entry in attributeValues.attributes().entrySet()) {
            out.name(entry.getKey())
            gson.toJson(serializeObject(entry.getValue()), out)
        }
        out.endObject()
    }

    @Throws(IOException::class)
    override fun read(`in`: JsonReader): com.google.devtools.build.lib.bazel.bzlmod.AttributeValues {
        val jsonObject: JsonObject = com.google.gson.JsonParser.parseReader(`in`).getAsJsonObject()
        val dict: net.starlark.java.eval.Dict.Builder<String?, Any?> =
            net.starlark.java.eval.Dict.builder<String?, Any?>()
        for (entry in jsonObject.entrySet()) {
            // The set of valid attribute names per repo rule is small and static, so interning them
            // helps reduce memory usage.
            dict.put(entry.getKey().intern(), deserializeObject(entry.getValue()))
        }
        return com.google.devtools.build.lib.bazel.bzlmod.AttributeValues.Companion.create(dict.buildImmutable())
    }

    /**
     * Starlark Object Types Bool Integer String Label List (Int, label, string) Dict (String,list) &
     * (Label, String)
     */
    private fun serializeObject(obj: Any): JsonElement {
        if (obj == net.starlark.java.eval.Starlark.NONE) {
            return JsonNull.INSTANCE
        } else if (obj is Boolean) {
            return JsonPrimitive(obj)
        } else if (obj is net.starlark.java.eval.StarlarkInt) {
            try {
                return JsonPrimitive((obj as net.starlark.java.eval.StarlarkInt).toInt("serialization into the lockfile"))
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalArgumentException("Unable to parse StarlarkInt to Integer: " + e)
            }
        } else if (obj is String || obj is com.google.devtools.build.lib.cmdline.Label) {
            return JsonPrimitive(serializeObjToString(obj))
        } else if (obj is net.starlark.java.eval.Dict<*, *>) {
            val jsonObject: JsonObject = JsonObject()
            for (entry in (obj as net.starlark.java.eval.Dict<*, *>).entrySet()) {
                jsonObject.add(serializeObjToString(entry.getKey()), serializeObject(entry.getValue()))
            }
            return jsonObject
        } else if (obj is Iterable<*>) {
            // ListType supports any kind of Iterable, including Tuples and StarlarkLists. All of them
            // are converted to an equivalent StarlarkList during deserialization.
            val jsonArray: JsonArray = JsonArray()
            for (item in obj) {
                jsonArray.add(serializeObject(item!!))
            }
            return jsonArray
        } else {
            throw java.lang.IllegalArgumentException("Unsupported type: " + obj.getClass())
        }
    }

    private fun deserializeObject(json: JsonElement?): Any? {
        if (json == null || json.isJsonNull()) {
            return net.starlark.java.eval.Starlark.NONE
        } else if (json.isJsonPrimitive()) {
            val jsonPrimitive: JsonPrimitive = json.getAsJsonPrimitive()
            if (jsonPrimitive.isBoolean()) {
                return jsonPrimitive.getAsBoolean()
            } else if (jsonPrimitive.isNumber()) {
                return net.starlark.java.eval.StarlarkInt.of(jsonPrimitive.getAsInt())
            } else if (jsonPrimitive.isString()) {
                return deserializeStringToObject(jsonPrimitive.getAsString())
            } else {
                throw java.lang.IllegalArgumentException("Unsupported JSON primitive: " + jsonPrimitive)
            }
        } else if (json.isJsonObject()) {
            val jsonObject: JsonObject = json.getAsJsonObject()
            val dict: net.starlark.java.eval.Dict.Builder<Any?, Any?> =
                net.starlark.java.eval.Dict.builder<Any?, Any?>()
            for (entry in jsonObject.entrySet()) {
                dict.put(deserializeStringToObject(entry.getKey()), deserializeObject(entry.getValue()))
            }
            return dict.buildImmutable()
        } else if (json.isJsonArray()) {
            val jsonArray: JsonArray = json.getAsJsonArray()
            val list: MutableList<Any?> = java.util.ArrayList<Any?>()
            for (item in jsonArray) {
                list.add(deserializeObject(item))
            }
            return net.starlark.java.eval.StarlarkList.copyOf<Any?>(net.starlark.java.eval.Mutability.IMMUTABLE, list)
        } else {
            throw java.lang.IllegalArgumentException("Unsupported JSON element: " + json)
        }
    }

    /**
     * Serializes an object (Label or String) to String. A label is converted to a String as it is. A
     * String that looks like a label is escaped so that it can be differentiated from a label when
     * deserializing, otherwise it is emitted as is.
     * 
     * @param obj String or Label
     * @return serialized object
     */
    private fun serializeObjToString(obj: Any?): String? {
        if (obj is com.google.devtools.build.lib.cmdline.Label) {
            val labelString: String = obj.getUnambiguousCanonicalForm()
            com.google.common.base.Preconditions.checkState(labelString.startsWith("@@"))
            return StringEncoding.internalToUnicode(labelString)
        }
        val string = obj as String
        // Strings that start with "@@" need to be escaped to avoid being interpreted as a label. We
        // escape by wrapping the string in the escape sequence and strip one layer of this sequence
        // during deserialization, so strings that happen to already start and end with the escape
        // sequence also have to be escaped.
        if (string.startsWith("@@")
            || (string.startsWith(STRING_ESCAPE_SEQUENCE) && string.endsWith(STRING_ESCAPE_SEQUENCE))
        ) {
            return StringEncoding.internalToUnicode(STRING_ESCAPE_SEQUENCE + string + STRING_ESCAPE_SEQUENCE)
        }
        return StringEncoding.internalToUnicode(string)
    }

    /**
     * Deserializes a string to either a label or a String depending on the prefix and presence of the
     * escape sequence.
     * 
     * @param unicodeValue String to be deserialized
     * @return Object of type String of Label
     */
    private fun deserializeStringToObject(unicodeValue: String?): Any? {
        val value: String = StringEncoding.unicodeToInternal(unicodeValue)
        // A string represents a label if and only if it starts with "@@".
        if (value.startsWith("@@")) {
            return com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked(value)
        }
        // Strings that start and end with the escape sequence always require one layer to be stripped.
        if (value.startsWith(STRING_ESCAPE_SEQUENCE) && value.endsWith(STRING_ESCAPE_SEQUENCE)) {
            return value.substring(
                STRING_ESCAPE_SEQUENCE.length(), value.length() - STRING_ESCAPE_SEQUENCE.length()
            )
        }
        return value
    }

    companion object {
        @com.google.common.annotations.VisibleForTesting
        const val STRING_ESCAPE_SEQUENCE: String = "'"
    }
}
