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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.AttributeValuesAdapter
import com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory
import com.google.devtools.build.lib.bazel.bzlmod.Facts
import com.google.devtools.build.lib.bazel.bzlmod.FactsAdapter
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionEvalFactors
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId.IsolationKey
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.RepoRuleId
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum.InvalidChecksumException
import com.google.devtools.build.lib.cmdline.LabelSyntaxException
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput.NeverUpToDateRepoRecordedInput
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput.WithValue
import com.google.devtools.build.lib.util.StringEncoding
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.ryanharter.auto.value.gson.GenerateTypeAdapter
import java.io.IOException

/**
 * Utility class to hold type adapters and helper methods to get gson registered with type adapters
 */
object GsonTypeAdapterUtil {
    // This is needed because Bazel uses a custom String encoding internally, see StringEncoding for
    // details.
    val STRING_ADAPTER: TypeAdapter<String?> = object : TypeAdapter<String?>() {
        @Throws(IOException::class)
        override fun write(jsonWriter: JsonWriter, s: String?) {
            jsonWriter.value(StringEncoding.internalToUnicode(s))
        }

        @Throws(IOException::class)
        override fun read(jsonReader: JsonReader): String {
            return StringEncoding.unicodeToInternal(jsonReader.nextString())
        }
    }

    val VERSION_TYPE_ADAPTER: TypeAdapter<com.google.devtools.build.lib.bazel.bzlmod.Version?> =
        object : TypeAdapter<com.google.devtools.build.lib.bazel.bzlmod.Version?>() {
            @Throws(IOException::class)
            override fun write(jsonWriter: JsonWriter, version: com.google.devtools.build.lib.bazel.bzlmod.Version) {
                jsonWriter.value(version.toString())
            }

            @Throws(IOException::class)
            override fun read(jsonReader: JsonReader): com.google.devtools.build.lib.bazel.bzlmod.Version? {
                val version: com.google.devtools.build.lib.bazel.bzlmod.Version?
                val versionString: String = StringEncoding.unicodeToInternal(jsonReader.nextString())
                try {
                    version = com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.parse(versionString)
                } catch (e: com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException) {
                    throw JsonParseException(
                        java.lang.String.format("Unable to parse Version %s from the lockfile", versionString), e
                    )
                }
                return version
            }
        }

    val MODULE_KEY_TYPE_ADAPTER: TypeAdapter<ModuleKey?> = object : TypeAdapter<ModuleKey?>() {
        @Throws(IOException::class)
        override fun write(jsonWriter: JsonWriter, moduleKey: ModuleKey) {
            jsonWriter.value(StringEncoding.internalToUnicode(moduleKey.toString()))
        }

        @Throws(IOException::class)
        override fun read(jsonReader: JsonReader): ModuleKey? {
            val jsonString: String = StringEncoding.unicodeToInternal(jsonReader.nextString())
            try {
                return ModuleKey.Companion.fromString(jsonString)
            } catch (e: com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException) {
                throw JsonParseException(
                    java.lang.String.format("Unable to parse ModuleKey %s version from the lockfile", jsonString),
                    e
                )
            }
        }
    }

    val LABEL_TYPE_ADAPTER: TypeAdapter<com.google.devtools.build.lib.cmdline.Label?> =
        object : TypeAdapter<com.google.devtools.build.lib.cmdline.Label?>() {
            @Throws(IOException::class)
            override fun write(jsonWriter: JsonWriter, label: com.google.devtools.build.lib.cmdline.Label) {
                jsonWriter.value(StringEncoding.internalToUnicode(label.getUnambiguousCanonicalForm()))
            }

            @Throws(IOException::class)
            override fun read(jsonReader: JsonReader): com.google.devtools.build.lib.cmdline.Label? {
                return com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked(
                    StringEncoding.unicodeToInternal(
                        jsonReader.nextString()
                    )
                )
            }
        }

    val REPO_RULE_ID_TYPE_ADAPTER: TypeAdapter<RepoRuleId?> = object : TypeAdapter<RepoRuleId?>() {
        @Throws(IOException::class)
        override fun write(jsonWriter: JsonWriter, repoRuleId: RepoRuleId) {
            jsonWriter.value(StringEncoding.internalToUnicode(repoRuleId.toString()))
        }

        @Throws(IOException::class)
        override fun read(jsonReader: JsonReader): RepoRuleId {
            val s: String = StringEncoding.unicodeToInternal(jsonReader.nextString())
            val percent: Int = s.indexOf('%'.code)
            if (percent == -1) {
                return RepoRuleId(null, s)
            }
            return RepoRuleId(
                com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked(s.substring(0, percent)),
                s.substring(percent + 1)
            )
        }
    }

    // Repository names are always ASCII and thus don't require special encoding handling.
    val REPOSITORY_NAME_TYPE_ADAPTER: TypeAdapter<RepositoryName?> = object : TypeAdapter<RepositoryName?>() {
        @Throws(IOException::class)
        override fun write(jsonWriter: JsonWriter, repoName: RepositoryName) {
            jsonWriter.value(repoName.getName())
        }

        @Throws(IOException::class)
        override fun read(jsonReader: JsonReader): RepositoryName? {
            return RepositoryName.createUnvalidated(jsonReader.nextString())
        }
    }

    val MODULE_EXTENSION_ID_TYPE_ADAPTER: TypeAdapter<ModuleExtensionId?> = object : TypeAdapter<ModuleExtensionId?>() {
        @Throws(IOException::class)
        override fun write(jsonWriter: JsonWriter, moduleExtId: ModuleExtensionId) {
            val isolationKeyPart: String =
                moduleExtId.isolationKey.map<String>(java.util.function.Function { key: IsolationKey? -> "%" + key })
                    .orElse("")
            jsonWriter.value(
                StringEncoding.internalToUnicode(
                    (moduleExtId.bzlFileLabel
                        .toString() + "%"
                            + moduleExtId.extensionName
                            + isolationKeyPart)
                )
            )
        }

        @Throws(IOException::class)
        override fun read(jsonReader: JsonReader): ModuleExtensionId {
            val jsonString: String = StringEncoding.unicodeToInternal(jsonReader.nextString())
            val extIdParts: MutableList<String?> = com.google.common.base.Splitter.on('%').splitToList(jsonString)
            val isolationKey: java.util.Optional<IsolationKey?>?
            if (extIdParts.size() > 2) {
                try {
                    isolationKey =
                        java.util.Optional.of<IsolationKey?>(IsolationKey.Companion.fromString(extIdParts.get(2)))
                } catch (e: com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException) {
                    throw JsonParseException(
                        java.lang.String.format(
                            "Unable to parse ModuleExtensionID isolation key: '%s' from the lockfile",
                            extIdParts.get(2)
                        ),
                        e
                    )
                }
            } else {
                isolationKey = java.util.Optional.empty<IsolationKey?>()
            }
            try {
                return ModuleExtensionId.Companion.create(
                    com.google.devtools.build.lib.cmdline.Label.parseCanonical(extIdParts.get(0)),
                    extIdParts.get(1),
                    isolationKey
                )
            } catch (e: LabelSyntaxException) {
                throw JsonParseException(
                    java.lang.String.format(
                        "Unable to parse ModuleExtensionID bzl file label: '%s' from the lockfile",
                        extIdParts.get(0)
                    ),
                    e
                )
            }
        }
    }

    val MODULE_EXTENSION_FACTORS_TYPE_ADAPTER: TypeAdapter<ModuleExtensionEvalFactors?> =
        object : TypeAdapter<ModuleExtensionEvalFactors?>() {
            @Throws(IOException::class)
            override fun write(jsonWriter: JsonWriter, extFactors: ModuleExtensionEvalFactors) {
                jsonWriter.value(StringEncoding.internalToUnicode(extFactors.toString()))
            }

            @Throws(IOException::class)
            override fun read(jsonReader: JsonReader): ModuleExtensionEvalFactors {
                return ModuleExtensionEvalFactors.Companion.parse(StringEncoding.unicodeToInternal(jsonReader.nextString()))
            }
        }

    val ISOLATION_KEY_TYPE_ADAPTER: TypeAdapter<IsolationKey?> = object : TypeAdapter<IsolationKey?>() {
        @Throws(IOException::class)
        override fun write(jsonWriter: JsonWriter, isolationKey: IsolationKey) {
            jsonWriter.value(StringEncoding.internalToUnicode(isolationKey.toString()))
        }

        @Throws(IOException::class)
        override fun read(jsonReader: JsonReader): IsolationKey {
            val jsonString: String = StringEncoding.unicodeToInternal(jsonReader.nextString())
            try {
                return IsolationKey.Companion.fromString(jsonString)
            } catch (e: com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException) {
                throw JsonParseException(
                    java.lang.String.format("Unable to parse isolation key: '%s' from the lockfile", jsonString),
                    e
                )
            }
        }
    }

    val BYTE_ARRAY_TYPE_ADAPTER: TypeAdapter<ByteArray?> = object : TypeAdapter<ByteArray?>() {
        @Throws(IOException::class)
        override fun write(jsonWriter: JsonWriter, value: ByteArray?) {
            jsonWriter.value(java.util.Base64.getEncoder().encodeToString(value))
        }

        @Throws(IOException::class)
        override fun read(jsonReader: JsonReader): ByteArray? {
            return java.util.Base64.getDecoder().decode(jsonReader.nextString())
        }
    }

    val OPTIONAL: TypeAdapterFactory = object : TypeAdapterFactory {
        override fun <T> create(gson: Gson, typeToken: com.google.gson.reflect.TypeToken<T?>): TypeAdapter<T?>? {
            if (typeToken.getRawType() != java.util.Optional::class.java) {
                return null
            }
            val type: java.lang.reflect.Type? = typeToken.getType()
            if (type !is java.lang.reflect.ParameterizedType) {
                return null
            }
            val elementType: java.lang.reflect.Type =
                (typeToken.getType() as java.lang.reflect.ParameterizedType).getActualTypeArguments()[0]
            val elementTypeAdapter: TypeAdapter<*>? =
                gson.getAdapter(com.google.gson.reflect.TypeToken.get(elementType))
            if (elementTypeAdapter == null) {
                return null
            }
            // Explicit nulls for Optional.empty are required for env variable tracking, but are too
            // noisy and unnecessary for other types.
            return OptionalTypeAdapter<Any?>(
                elementTypeAdapter,  /* serializeNulls= */elementType == String::class.java
            ) as TypeAdapter<T?>
        }
    }

    private val REPO_RECORDED_INPUT_WITH_VALUE_TYPE_ADAPTER: TypeAdapter<WithValue?> =
        object : TypeAdapter<WithValue?>() {
            @Throws(IOException::class)
            override fun write(jsonWriter: JsonWriter, value: WithValue) {
                jsonWriter.value(StringEncoding.internalToUnicode(value.toString()))
            }

            @Throws(IOException::class)
            override fun read(jsonReader: JsonReader): WithValue {
                return RepoRecordedInput.WithValue.parse(StringEncoding.unicodeToInternal(jsonReader.nextString()))
                    .orElseGet(java.util.function.Supplier {
                        WithValue(
                            NeverUpToDateRepoRecordedInput.PARSE_FAILURE,
                            ""
                        )
                    })
            }
        }

    val LOCKFILE_GSON: Gson = newGsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Facts::class.java, FactsAdapter())
        .registerTypeAdapterFactory(OptionalChecksumTypeAdapterFactory())
        .create()

    @kotlin.jvm.JvmField
    val SINGLE_EXTENSION_USAGES_VALUE_GSON: Gson = newGsonBuilder().create()

    private fun newGsonBuilder(): GsonBuilder {
        return GsonBuilder()
            .disableHtmlEscaping()
            .enableComplexMapKeySerialization()
            .registerTypeAdapterFactory(GenerateTypeAdapter.FACTORY)
            .registerTypeAdapterFactory(DelegateTypeAdapterFactory.Companion.DICT)
            .registerTypeAdapterFactory(DelegateTypeAdapterFactory.Companion.IMMUTABLE_MAP)
            .registerTypeAdapterFactory(DelegateTypeAdapterFactory.Companion.IMMUTABLE_SORTED_MAP)
            .registerTypeAdapterFactory(DelegateTypeAdapterFactory.Companion.IMMUTABLE_LIST)
            .registerTypeAdapterFactory(DelegateTypeAdapterFactory.Companion.IMMUTABLE_BIMAP)
            .registerTypeAdapterFactory(DelegateTypeAdapterFactory.Companion.IMMUTABLE_SET)
            .registerTypeAdapterFactory(OPTIONAL)
            .registerTypeAdapter(String::class.java, STRING_ADAPTER)
            .registerTypeAdapter(com.google.devtools.build.lib.cmdline.Label::class.java, LABEL_TYPE_ADAPTER)
            .registerTypeAdapter(RepoRuleId::class.java, REPO_RULE_ID_TYPE_ADAPTER)
            .registerTypeAdapter(RepositoryName::class.java, REPOSITORY_NAME_TYPE_ADAPTER)
            .registerTypeAdapter(com.google.devtools.build.lib.bazel.bzlmod.Version::class.java, VERSION_TYPE_ADAPTER)
            .registerTypeAdapter(ModuleKey::class.java, MODULE_KEY_TYPE_ADAPTER)
            .registerTypeAdapter(ModuleExtensionId::class.java, MODULE_EXTENSION_ID_TYPE_ADAPTER)
            .registerTypeAdapter(
                ModuleExtensionEvalFactors::class.java, MODULE_EXTENSION_FACTORS_TYPE_ADAPTER
            )
            .registerTypeAdapter(IsolationKey::class.java, ISOLATION_KEY_TYPE_ADAPTER)
            .registerTypeAdapter(
                com.google.devtools.build.lib.bazel.bzlmod.AttributeValues::class.java,
                AttributeValuesAdapter()
            )
            .registerTypeAdapter(ByteArray::class.java, BYTE_ARRAY_TYPE_ADAPTER)
            .registerTypeAdapter(
                WithValue::class.java, REPO_RECORDED_INPUT_WITH_VALUE_TYPE_ADAPTER
            )
    }

    private class OptionalTypeAdapter<T>(elementTypeAdapter: TypeAdapter<T?>, serializeNulls: Boolean) :
        TypeAdapter<java.util.Optional<T?>?>() {
        private val elementTypeAdapter: TypeAdapter<T?>
        private val serializeNulls: Boolean

        init {
            this.elementTypeAdapter = elementTypeAdapter
            this.serializeNulls = serializeNulls
        }

        @Throws(IOException::class)
        override fun write(jsonWriter: JsonWriter, t: java.util.Optional<T?>?) {
            com.google.common.base.Preconditions.checkNotNull<java.util.Optional<T?>?>(t)
            if (t.isEmpty()) {
                val oldSerializeNulls: Boolean = jsonWriter.getSerializeNulls()
                jsonWriter.setSerializeNulls(serializeNulls)
                try {
                    jsonWriter.nullValue()
                } finally {
                    jsonWriter.setSerializeNulls(oldSerializeNulls)
                }
            } else {
                elementTypeAdapter.write(jsonWriter, t.get())
            }
        }

        @Throws(IOException::class)
        override fun read(jsonReader: JsonReader): java.util.Optional<T?> {
            if (jsonReader.peek() == com.google.gson.stream.JsonToken.NULL) {
                jsonReader.nextNull()
                return java.util.Optional.empty<T?>()
            } else {
                return java.util.Optional.of<T?>(elementTypeAdapter.read(jsonReader))
            }
        }
    }

    // This can't reuse the existing type adapter factory for Optional as we need to explicitly
    // serialize null values but don't want to rely on GSON's serializeNulls.
    private class OptionalChecksumTypeAdapterFactory : TypeAdapterFactory {
        override fun <T> create(gson: Gson?, typeToken: com.google.gson.reflect.TypeToken<T?>): TypeAdapter<T?>? {
            if (typeToken.getRawType() != java.util.Optional::class.java) {
                return null
            }
            val type: java.lang.reflect.Type? = typeToken.getType()
            if (type !is java.lang.reflect.ParameterizedType) {
                return null
            }
            val elementType: java.lang.reflect.Type? =
                (type as java.lang.reflect.ParameterizedType).getActualTypeArguments()[0]
            if (elementType !== com.google.devtools.build.lib.bazel.repository.downloader.Checksum::class.java) {
                return null
            }
            val typeAdapter: TypeAdapter<T?> = OptionalChecksumTypeAdapter() as TypeAdapter<T?>
            return typeAdapter
        }

        // Checksums are always ASCII and thus don't require special encoding handling.
        private class OptionalChecksumTypeAdapter :
            TypeAdapter<java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>() {
            @Throws(IOException::class)
            override fun write(
                jsonWriter: JsonWriter,
                checksum: java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>
            ) {
                if (checksum.isPresent()) {
                    jsonWriter.value(checksum.get().toString())
                } else {
                    jsonWriter.value(NOT_FOUND_MARKER)
                }
            }

            @Throws(IOException::class)
            override fun read(jsonReader: JsonReader): java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?> {
                val checksumString: String = jsonReader.nextString()
                if (checksumString == NOT_FOUND_MARKER) {
                    return java.util.Optional.empty<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>()
                }
                try {
                    return java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(
                        com.google.devtools.build.lib.bazel.repository.downloader.Checksum.fromString(
                            DownloadCache.KeyType.SHA256,
                            checksumString
                        )
                    )
                } catch (e: InvalidChecksumException) {
                    throw JsonParseException(java.lang.String.format("Invalid checksum: %s", checksumString), e)
                }
            }

            companion object {
                // This value must not be a valid checksum string.
                private const val NOT_FOUND_MARKER = "not found"
            }
        }
    }
}
