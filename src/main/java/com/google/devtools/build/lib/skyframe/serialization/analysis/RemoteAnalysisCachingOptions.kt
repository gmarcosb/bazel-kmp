// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheStorageType

/** Options for caching analysis results remotely.  */
@com.google.devtools.common.options.OptionsClass
abstract class RemoteAnalysisCachingOptions : com.google.devtools.common.options.OptionsBase() {
    /** A converter for MD5 checksums.  */
    class Md5Converter : com.google.devtools.common.options.Converter<com.google.common.hash.HashCode?> {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?, conversionContext: Any?): com.google.common.hash.HashCode? {
            if (com.google.common.base.Strings.isNullOrEmpty(input)) {
                return null
            }

            var result: com.google.common.hash.HashCode? = null
            try {
                result = com.google.common.hash.HashCode.fromString(input)
            } catch (e: java.lang.IllegalArgumentException) {
                // Handled just below in the if (result == null) branch
            }

            if (result == null || result.bits() != com.google.common.hash.Hashing.md5().bits()) {
                throw com.google.devtools.common.options.OptionsParsingException("Blaze checksum must be exactly 32 hex characters")
            }

            return result
        }

        val typeDescription: String
            get() = ""
    }

    @get:com.google.devtools.common.options.Option(
        name = "serialized_frontier_profile",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = "Dump a profile of serialized frontier bytes. Specifies the output path."
    )
    abstract val serializedFrontierProfile: String?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_remote_analysis_cache_mode",
        defaultValue = "off",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        converter = RemoteAnalysisCacheModeConverter::class,
        help = "The transport direction for the remote analysis cache."
    )
    abstract var mode: RemoteAnalysisCacheMode?

    /** * The transport direction for the remote analysis cache.  */
    enum class RemoteAnalysisCacheMode {
        /** Serializes and uploads Skyframe analysis nodes after the build command finishes.  */
        UPLOAD,

        /**
         * Dumps the manifest of SkyKeys computed in the frontier and the active set. This mode does not
         * serialize and upload the keys.
         */
        DUMP_UPLOAD_MANIFEST_ONLY,

        /** Fetches and deserializes the Skyframe analysis nodes during the build.  */
        DOWNLOAD,

        /** Disabled.  */
        OFF;

        /** Returns true if the selected mode needs to connect to a backend.  */
        fun requiresBackendConnectivity(): Boolean {
            return when (this) {
                RemoteAnalysisCacheMode.UPLOAD, RemoteAnalysisCacheMode.DOWNLOAD -> true
                RemoteAnalysisCacheMode.DUMP_UPLOAD_MANIFEST_ONLY, RemoteAnalysisCacheMode.OFF -> false
            }
        }

        val isRetrievalEnabled: Boolean
            get() = this == RemoteAnalysisCacheMode.DOWNLOAD

        /**
         * Returns true if the mode serializes *values*.
         * 
         * 
         * [DOWNLOAD] serializes keys, but not values.
         */
        fun serializesValues(): Boolean {
            return when (this) {
                RemoteAnalysisCacheMode.UPLOAD, RemoteAnalysisCacheMode.DUMP_UPLOAD_MANIFEST_ONLY -> true
                RemoteAnalysisCacheMode.DOWNLOAD, RemoteAnalysisCacheMode.OFF -> false
            }
        }
    }

    /** Enum converter for [RemoteAnalysisCacheMode].  */
    private class RemoteAnalysisCacheModeConverter

        : com.google.devtools.common.options.EnumConverter<RemoteAnalysisCacheMode?>(
        RemoteAnalysisCacheMode::class.java,
        "Remote analysis cache mode"
    )

    @get:com.google.devtools.common.options.Option(
        name = "experimental_remote_analysis_cache_max_batch_size",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        defaultValue = "4095",
        help = "Batch size limit for remote analysis caching RPCs."
    )
    abstract val maxBatchSize: Int

    @get:com.google.devtools.common.options.Option(
        name = "experimental_remote_analysis_cache_concurrency",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        defaultValue = "4",
        help = "Target concurrency for remote analysis caching RPCs."
    )
    abstract val concurrency: Int

    @get:com.google.devtools.common.options.Option(
        name = "experimental_remote_analysis_cache_deadline",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        defaultValue = "45s",
        converter = com.google.devtools.common.options.Converters.DurationConverter::class,
        help = "Deadline to use for remote analysis cache operations."
    )
    abstract val deadline: java.time.Duration?

    abstract fun setDeadline(value: java.time.Duration?)

    @get:com.google.devtools.common.options.Option(
        name = "experimental_analysis_cache_service",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = "Locator for the AnalysisCacheService instance."
    )
    abstract var analysisCacheService: String?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_remote_analysis_cache_storage",
        defaultValue = "RAM",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        converter = RemoteAnalysisCacheStorageTypeConverter::class,
        help = "The storage type for the remote analysis cache."
    )
    abstract val storageType: RemoteAnalysisCacheStorageType?

    /** Enum converter for [RemoteAnalysisCacheStorageType].  */
    class RemoteAnalysisCacheStorageTypeConverter

        : com.google.devtools.common.options.EnumConverter<RemoteAnalysisCacheStorageType?>(
        RemoteAnalysisCacheStorageType::class.java,
        "Remote analysis cache storage type"
    )

    @get:com.google.devtools.common.options.Option(
        name = "experimental_remote_analysis_write_proxy",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = ("The address of the SkycacheStorageWriteProxyService. If set, this service will be used "
                + "for uploading analysis cache data.")
    )
    abstract var remoteAnalysisWriteProxy: String?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_analysis_cache_key_distinguisher_for_testing",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = "An opaque string used as part of the cache key. Should only be used for testing."
    )
    abstract val analysisCacheKeyDistinguisherForTesting: String?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_analysis_cache_enable_metadata_queries",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = "A flag to switch on/off inserting and querying the metadata db (b/425247333)."
    )
    abstract var analysisCacheEnableMetadataQueries: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_analysis_cache_server_checksum_override",
        converter = Md5Converter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = ("If set, Blaze will use this checksum to look up entries in the remote analysis cache"
                + " and not its own. WARNING: this might result in incorrect behavior. Only for"
                + " debugging. It's best if the difference between the writer and the reader is only"
                + " additional logging. In particular, the data structures that are being serialized "
                + " and the observable behavior of the serialization machinery must not change.")
    )
    abstract val serverChecksumOverride: com.google.common.hash.HashCode?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_skycache_minimize_memory",
        defaultValue = "false",
        oldName = "experimental_discard_package_values_post_analysis",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = ("DO NOT USE: This flag is currently in development and does not work with every target."
                + " If enabled, Blaze will discard values after the analysis phase is"
                + " complete to provide Skycache writers with more headroom.")
    )
    abstract val skycacheMinimizeMemory: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_analysis_cache_bail_on_missing_fingerprint",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = ("If true, bails out from remote analysis cache retrieval if a single fingerprint is"
                + " missing.")
    )
    abstract val analysisCacheBailOnMissingFingerprint: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_skycache_analysis_only",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = "If true, Skycache will only be used for analysis phase."
    )
    abstract val skycacheAnalysisOnly: Boolean
}
