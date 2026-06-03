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
package com.google.devtools.build.docgen

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSyntaxException
import java.io.IOException
import java.nio.file.Path

/**
 * Represents a link mapping that acts as input to [RuleLinkExpander] and [ ].
 */
class DocLinkMap( // For RuleLinkExpander
    val beRoot: String?,
    beReferences: com.google.common.collect.ImmutableMap<String?, String?>?,
    sourceUrlRoot: String?,  // For SourceUrlMapper
    repoPathRewrites: com.google.common.collect.ImmutableMap<String?, String?>?
) {
    fun toImmutableMap()
    val asString: Unit
    val beReferences: com.google.common.collect.ImmutableMap<String?, String?>?
    val sourceUrlRoot: String?
    val repoPathRewrites: com.google.common.collect.ImmutableMap<String?, String?>?

    init {
        this.beReferences = beReferences
        this.sourceUrlRoot = sourceUrlRoot
        this.repoPathRewrites = repoPathRewrites
    }

    companion object {
        fun createFromFile(filePath: String?): DocLinkMap? {
            try {
                return GSON.fromJson<DocLinkMap?>(
                    java.nio.file.Files.readString(Path.of(filePath)),
                    DocLinkMap::class.java
                )
            } catch (ex: IOException) {
                throw java.lang.IllegalArgumentException("Failed to read link map from " + filePath, ex)
            } catch (ex: JsonSyntaxException) {
                throw java.lang.IllegalArgumentException("Failed to read link map from " + filePath, ex)
            }
        }

        private val IMMUTABLE_MAP_DESERIALIZER: JsonDeserializer<com.google.common.collect.ImmutableMap<String?, String?>?> =
            JsonDeserializer { jsonElement: JsonElement?, unusedType: java.lang.reflect.Type?, unusedContext: JsonDeserializationContext? ->
                jsonElement.getAsJsonObject().entrySet().stream()
                    .collect()
            }
        private val GSON: Gson = GsonBuilder()
            .registerTypeAdapter(
                object :
                    com.google.gson.reflect.TypeToken<com.google.common.collect.ImmutableMap<String?, String?>?>() {}.getType(),
                IMMUTABLE_MAP_DESERIALIZER
            )
            .create()
    }
}
