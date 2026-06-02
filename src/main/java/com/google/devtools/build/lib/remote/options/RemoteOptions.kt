// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.options

import com.google.common.base.Strings
import com.google.common.collect.ImmutableSortedMap
import com.google.devtools.build.lib.remote.Scrubber
import com.google.devtools.build.lib.remote.Scrubber.ConfigParseException
import com.google.devtools.build.lib.util.OptionsUtils
import com.google.devtools.build.lib.util.OptionsUtils.EmptyToNullPathFragmentConverter
import com.google.devtools.build.lib.vfs.Path
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.common.options.*
import kotlin.collections.HashMap
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/** Options for remote execution and distributed caching for Bazel only.  */
@OptionsClass
abstract class RemoteOptions : CommonRemoteOptions() {
    @get:Option(
        name = "remote_proxy",
        oldName = "remote_cache_proxy",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Connect to the remote cache through a proxy. Currently this flag can only be used to "
                + "configure a Unix domain socket (unix:/path/to/socket).")
    )
    abstract var remoteProxy: String?

    @get:Option(
        name = "remote_max_connections",
        defaultValue = "100",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        help = """
          Limit the max number of concurrent connections to remote cache/executor. By default the value is 100. Setting this to 0 means no limitation.
          For HTTP remote cache, one TCP connection could handle one request at one time, so Bazel could make up to --remote_max_connections concurrent requests.
          For gRPC remote cache/executor, one gRPC channel could usually handle 100+ concurrent requests (controlled by --remote_max_concurrency_per_connection), so Bazel could make around `--remote_max_connections * 100` concurrent requests.
          """.trimIndent()
    )
    abstract val remoteMaxConnections: Int

    @get:Option(
        name = "remote_max_concurrency_per_connection",
        defaultValue = "100",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        help = """
          Limit the max number of concurrent requests per gRPC connection. By default the value is 100.
          """.trimIndent()
    )
    abstract val remoteMaxConcurrencyPerConnection: Int

    @get:Option(
        name = "remote_executor",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("HOST or HOST:PORT of a remote execution endpoint. The supported schemes are grpc, "
                + "grpcs (grpc with TLS enabled) and unix (local UNIX sockets). If no scheme is "
                + "provided Bazel will default to grpcs. Specify grpc:// or unix: scheme to "
                + "disable TLS.")
    )
    abstract var remoteExecutor: String?

    @get:Option(
        name = "experimental_remote_execution_keepalive",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.NO_OP],
        metadataTags = [OptionMetadataTag.DEPRECATED],
        help = "No-op. Kept here for backwards compatibility."
    )
    @get:Deprecated("")
    abstract val remoteExecutionKeepalive: Boolean

    @get:Option(
        name = "experimental_remote_capture_corrupted_outputs",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        converter = OptionsUtils.PathFragmentConverter::class,
        help = "A path to a directory where the corrupted outputs will be captured to."
    )
    abstract val remoteCaptureCorruptedOutputs: PathFragment?

    @get:Option(
        name = "remote_cache_async",
        oldName = "experimental_remote_cache_async",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("If true, uploading of action results to a disk or remote cache will happen in the"
                + " background instead of blocking the completion of an action. Some actions are"
                + " incompatible with background uploads, and may still block even when this flag is"
                + " set.")
    )
    abstract val remoteCacheAsync: Boolean

    @get:Option(
        name = "remote_cache",
        oldName = "remote_http_cache",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("A URI of a caching endpoint. The supported schemes are http, https, grpc, grpcs "
                + "(grpc with TLS enabled) and unix (local UNIX sockets). If no scheme is provided "
                + "Bazel will default to grpcs. Specify grpc://, http:// or unix: scheme to disable "
                + "TLS. See https://bazel.build/remote/caching")
    )
    abstract var remoteCache: String?

    @get:Option(
        name = "remote_downloader",
        oldName = "experimental_remote_downloader",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("A Remote Asset API endpoint URI, to be used as a remote download proxy. The supported"
                + " schemes are grpc, grpcs (grpc with TLS enabled) and unix (local UNIX sockets). If"
                + " no scheme is provided Bazel will default to grpcs. See: "
                + "https://github.com/bazelbuild/remote-apis/blob/master/build/bazel/remote/asset/v1/remote_asset.proto")
    )
    abstract var remoteDownloader: String?

    @get:Option(
        name = "remote_downloader_local_fallback",
        oldName = "experimental_remote_downloader_local_fallback",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Whether to fall back to the local downloader if remote downloader fails."
    )
    abstract var remoteDownloaderLocalFallback: Boolean

    @get:Option(
        name = "remote_downloader_propagate_credentials",
        oldName = "experimental_remote_downloader_propagate_credentials",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Whether to propagate credentials from netrc and credential helper to the remote"
                + " downloader server. The server implementation needs to support the new"
                + " `http_header_url:<url-index>:<header-key>` qualifier where the `<url-index>` is a"
                + " 0-based position of the URL inside the FetchBlobRequest's `uris` field. The"
                + " URL-specific headers should take precedence over the global headers.")
    )
    abstract val remoteDownloaderPropagateCredentials: Boolean

    @get:Option(
        name = "remote_header",
        converter = Converters.AssignmentConverter::class,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Specify a header that will be included in requests: --remote_header=Name=Value. "
                + "Multiple headers can be passed by specifying the flag multiple times. Multiple "
                + "values for the same name will be converted to a comma-separated list."),
        allowMultiple = true
    )
    abstract var remoteHeaders: MutableList<MutableMap.MutableEntry<String?, String?>?>?

    @get:Option(
        name = "remote_cache_header",
        converter = Converters.AssignmentConverter::class,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Specify a header that will be included in cache requests: "
                + "--remote_cache_header=Name=Value. "
                + "Multiple headers can be passed by specifying the flag multiple times. Multiple "
                + "values for the same name will be converted to a comma-separated list."),
        allowMultiple = true
    )
    abstract var remoteCacheHeaders: MutableList<MutableMap.MutableEntry<String?, String?>?>?

    @get:Option(
        name = "remote_exec_header",
        converter = Converters.AssignmentConverter::class,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Specify a header that will be included in execution requests: "
                + "--remote_exec_header=Name=Value. "
                + "Multiple headers can be passed by specifying the flag multiple times. Multiple "
                + "values for the same name will be converted to a comma-separated list."),
        allowMultiple = true
    )
    abstract var remoteExecHeaders: MutableList<MutableMap.MutableEntry<String?, String?>?>?

    @get:Option(
        name = "remote_downloader_header",
        converter = Converters.AssignmentConverter::class,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Specify a header that will be included in remote downloader requests: "
                + "--remote_downloader_header=Name=Value. "
                + "Multiple headers can be passed by specifying the flag multiple times. Multiple "
                + "values for the same name will be converted to a comma-separated list."),
        allowMultiple = true
    )
    abstract val remoteDownloaderHeaders: MutableList<MutableMap.MutableEntry<String?, String?>?>?

    @get:Option(
        name = "remote_timeout",
        defaultValue = "60s",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        converter = RemoteDurationConverter::class,
        help = ("The maximum amount of time to wait for remote execution and cache calls. For the REST"
                + " cache, this is both the connect and the read timeout. Following units can be"
                + " used: Days (d), hours (h), minutes (m), seconds (s), and milliseconds (ms). If"
                + " the unit is omitted, the value is interpreted as seconds.")
    )
    abstract val remoteTimeout: Duration?

    @get:Option(
        name = "remote_bytestream_uri_prefix",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("The hostname and instance name to be used in bytestream:// URIs that are written into "
                + "build event streams. This option can be set when builds are performed using a "
                + "proxy, which causes the values of --remote_executor and --remote_instance_name "
                + "to no longer correspond to the canonical name of the remote execution service. "
                + "When not set, it will default to \"\${hostname}/\${instance_name}\".")
    )
    abstract val remoteBytestreamUriPrefix: String?

    @get:Option(
        name = "remote_accept_cached",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Whether to accept remotely cached action results."
    )
    abstract var remoteAcceptCached: Boolean

    @get:Option(
        name = "experimental_remote_require_cached",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("If set to true, enforce that all actions that can run remotely are cached, or else "
                + "fail the build. This is useful to troubleshoot non-determinism issues as it "
                + "allows checking whether actions that should be cached are actually cached "
                + "without spuriously injecting new results into the cache.")
    )
    abstract val remoteRequireCached: Boolean

    @get:Option(
        name = "remote_local_fallback",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Whether to fall back to standalone local execution strategy if remote execution fails."
    )
    abstract var remoteLocalFallback: Boolean

    @get:Option(
        name = "incompatible_remote_local_fallback_for_remote_cache",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Whether --remote_local_fallback applies to --remote_cache."
    )
    abstract var remoteLocalFallbackForRemoteCache: Boolean

    @get:Option(
        name = "remote_local_fallback_strategy",
        defaultValue = "local",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        metadataTags = [OptionMetadataTag.DEPRECATED],
        help = "Deprecated. See https://github.com/bazelbuild/bazel/issues/7480 for details."
    )
    @get:Deprecated("")
    abstract val remoteLocalFallbackStrategy: String?

    @get:Option(
        name = "remote_upload_local_results",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Whether to upload locally executed action results to the remote cache if the remote "
                + "cache supports it and the user is authorized to do so.")
    )
    abstract var remoteUploadLocalResults: Boolean

    @get:Option(
        name = "remote_build_event_upload",
        oldName = "experimental_remote_build_event_upload",
        defaultValue = "minimal",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        converter = RemoteBuildEventUploadModeConverter::class,
        help = ("If set to 'all', all local outputs referenced by BEP are uploaded to remote cache.\n"
                + "If set to 'minimal', local outputs referenced by BEP are not uploaded to the"
                + " remote cache, except for files that are important to the consumers of BEP (e.g."
                + " test logs and timing profile). bytestream:// scheme is always used for the uri of"
                + " files even if they are missing from remote cache.\n"
                + "Default to 'minimal'.")
    )
    abstract val remoteBuildEventUploadMode: RemoteBuildEventUploadMode?

    /** Build event upload mode flag parser  */
    class RemoteBuildEventUploadModeConverter

        :
        EnumConverter<RemoteBuildEventUploadMode?>(RemoteBuildEventUploadMode::class.java, "remote build event upload")

    @get:Option(
        name = "remote_instance_name",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Value to pass as instance_name in the remote execution API."
    )
    abstract var remoteInstanceName: String?

    @get:Option(
        name = "remote_retries",
        oldName = "experimental_remote_retry_max_attempts",
        defaultValue = "5",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("The maximum number of attempts to retry a transient error. "
                + "If set to 0, retries are disabled.")
    )
    abstract var remoteMaxRetryAttempts: Int

    @get:Option(
        name = "remote_retry_max_delay",
        defaultValue = "5s",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        converter = RemoteDurationConverter::class,
        help = ("The maximum backoff delay between remote retry attempts. Following units can be used:"
                + " Days (d), hours (h), minutes (m), seconds (s), and milliseconds (ms). If"
                + " the unit is omitted, the value is interpreted as seconds.")
    )
    abstract val remoteRetryMaxDelay: Duration?

    /**
     * Accepts a filesystem path, or boolean-like values selecting the default location (`--disk_cache`, `--disk_cache=true`, etc.) or disabling the cache (`--nodisk_cache`,
     * `--disk_cache=false`, etc.).
     * 
     * 
     * [PathFragment.EMPTY_FRAGMENT] means use the default directory under the output user
     * root; callers should use [.getDiskCachePath] to resolve this to a concrete path. `null` means the disk cache is disabled.
     */
    class DiskCacheConverter : Converter.Contextless<PathFragment?>(), BooleanStyleOption {
        override fun convert(input: String): PathFragment? {
            if (input.isEmpty()) {
                return null
            }
            try {
                return if (BOOLEAN_CONVERTER.convert(input)) PathFragment.EMPTY_FRAGMENT else null
            } catch (e: OptionsParsingException) {
                return OptionsUtils.PathFragmentConverter().convert(input)
            }
        }

        override fun getTypeDescription(): String {
            return "a path, or a boolean to use the default disk cache location"
        }

        companion object {
            private val BOOLEAN_CONVERTER = Converters.BooleanConverter()
        }
    }

    @get:Option(
        name = "disk_cache",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        converter = DiskCacheConverter::class,
        help = ("A path to a directory where Bazel can read and write actions and action outputs. "
                + "If the directory does not exist, it will be created. "
                + "Use --disk_cache with no value (or --disk_cache=true) to use a default location "
                + "under the output user root (<outputUserRoot>/cache/disk). Use --nodisk_cache or "
                + "--disk_cache=false to disable.")
    )
    abstract var diskCache: PathFragment?

    @get:Option(
        name = "experimental_disk_cache_gc_idle_delay",
        defaultValue = "5m",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        converter = Converters.DurationConverter::class,
        help = ("How long the server must remain idle before a garbage collection of the disk cache"
                + " occurs. To specify the garbage collection policy, set"
                + " --experimental_disk_cache_gc_max_size and/or"
                + " --experimental_disk_cache_gc_max_age.")
    )
    abstract var diskCacheGcIdleDelay: Duration?

    @get:Option(
        name = "experimental_disk_cache_gc_max_size",
        defaultValue = "0",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        converter = Converters.ByteSizeConverter::class,
        help = ("If set to a positive value, the disk cache will be periodically garbage collected to"
                + " stay under this size. If set in conjunction with"
                + " --experimental_disk_cache_gc_max_age, both criteria are applied. Garbage"
                + " collection occurrs in the background once the server has become idle, as"
                + " determined by the --experimental_disk_cache_gc_idle_delay flag.")
    )
    abstract var diskCacheGcMaxSize: Long

    @get:Option(
        name = "experimental_disk_cache_gc_max_age",
        defaultValue = "0",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        converter = Converters.DurationConverter::class,
        help = ("If set to a positive value, the disk cache will be periodically garbage collected to"
                + " remove entries older than this age. If set in conjunction with"
                + " --experimental_disk_cache_gc_max_size, both criteria are applied. Garbage"
                + " collection occurrs in the background once the server has become idle, as"
                + " determined by the --experimental_disk_cache_gc_idle_delay flag.")
    )
    abstract var diskCacheGcMaxAge: Duration?

    /** An enum for different levels of checks for concurrent changes.  */
    enum class ConcurrentChangesCheckLevel {
        OFF,
        LITE,
        FULL;

        /** Converts to [ConcurrentChangesCheckLevel].  */
        internal class Converter : BoolOrEnumConverter<ConcurrentChangesCheckLevel?>(
            ConcurrentChangesCheckLevel::class.java,
            "concurrent changes check level",
            ConcurrentChangesCheckLevel.FULL,
            ConcurrentChangesCheckLevel.OFF
        )
    }

    @get:Option(
        name = "guard_against_concurrent_changes",
        oldName = "experimental_guard_against_concurrent_changes",
        defaultValue = "lite",
        converter = ConcurrentChangesCheckLevel.Converter::class,
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.EXECUTION],
        help = ("Set this to 'full' to enable checking the ctime of all input files of an action before"
                + " uploading it to a remote cache. There may be cases where the Linux kernel delays"
                + " writing of files, which could cause false positives. The default is 'lite', which"
                + " only checks source files in the main repository. Setting this to 'off' disables"
                + " all checks. This is not recommended, as the cache may be polluted when a source"
                + " file is changed while an action that takes it as an input is executing.")
    )
    abstract val guardAgainstConcurrentChanges: ConcurrentChangesCheckLevel?

    @get:Option(
        name = "remote_grpc_log",
        oldName = "experimental_remote_grpc_log",
        defaultValue = "null",
        category = "remote",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        converter = EmptyToNullPathFragmentConverter::class,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("If specified, a path to a file to log gRPC call related details. This log consists of a"
                + " sequence of serialized "
                + "com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry "
                + "protobufs with each message prefixed by a varint denoting the size of the"
                + " following serialized protobuf message, as performed by the method "
                + "LogEntry.writeDelimitedTo(OutputStream).")
    )
    abstract val remoteGrpcLog: PathFragment?

    @get:Option(
        name = "remote_cache_compression",
        oldName = "experimental_remote_cache_compression",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("If enabled, compress/decompress cache blobs with zstd when their size is at least"
                + " --experimental_remote_cache_compression_threshold.")
    )
    abstract var cacheCompression: Boolean

    @get:Option(
        name = "experimental_remote_cache_compression_threshold",
        defaultValue = "100",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("The minimum blob size required to compress/decompress with zstd. Ineffectual unless"
                + " --remote_cache_compression is set.")
    )
    abstract var cacheCompressionThreshold: Int

    @get:Option(
        name = "remote_download_outputs",
        oldName = "experimental_remote_download_outputs",
        defaultValue = "toplevel",
        category = "remote",
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS],
        converter = RemoteOutputsStrategyConverter::class,
        help = ("If set to 'minimal' doesn't download any remote build outputs to the local machine, "
                + "except the ones required by local actions. If set to 'toplevel' behaves like "
                + "'minimal' except that it also downloads outputs of top level targets to the local "
                + "machine. Both options can significantly reduce build times if network bandwidth "
                + "is a bottleneck.")
    )
    abstract var remoteOutputsMode: RemoteOutputsMode?

    /** Outputs strategy flag parser  */
    class RemoteOutputsStrategyConverter :
        EnumConverter<RemoteOutputsMode?>(RemoteOutputsMode::class.java, "download remote outputs")

    @get:Option(
        name = "remote_download_minimal",
        oldName = "experimental_remote_download_minimal",
        defaultValue = "null",
        expansion = ["--remote_download_outputs=minimal"],
        category = "remote",
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Does not download any remote build outputs to the local machine. This flag is an alias"
                + " for --remote_download_outputs=minimal.")
    )
    abstract val remoteOutputsMinimal: Void?

    @get:Option(
        name = "remote_download_toplevel",
        oldName = "experimental_remote_download_toplevel",
        defaultValue = "null",
        expansion = ["--remote_download_outputs=toplevel"],
        category = "remote",
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Only downloads remote outputs of top level targets to the local machine. This flag is an"
                + " alias for --remote_download_outputs=toplevel.")
    )
    abstract val remoteOutputsToplevel: Void?

    @get:Option(
        name = "remote_download_all",
        defaultValue = "null",
        expansion = ["--remote_download_outputs=all"],
        category = "remote",
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Downloads all remote outputs to the local machine. This flag is an alias for"
                + " --remote_download_outputs=all.")
    )
    abstract val remoteOutputsAll: Void?

    @get:Option(
        name = "remote_result_cache_priority",
        defaultValue = "0",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("The relative priority of remote actions to be stored in remote cache. "
                + "The semantics of the particular priority values are server-dependent.")
    )
    abstract var remoteResultCachePriority: Int

    @get:Option(
        name = "remote_execution_priority",
        defaultValue = "0",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("The relative priority of actions to be executed remotely. "
                + "The semantics of the particular priority values are server-dependent.")
    )
    abstract var remoteExecutionPriority: Int

    @get:Option(
        name = "remote_default_exec_properties",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS],
        converter = Converters.AssignmentConverter::class,
        allowMultiple = true,
        help = ("Set the default exec properties to be used as the remote execution platform "
                + "if an execution platform does not already set exec_properties.")
    )
    abstract val remoteDefaultExecPropertiesField: MutableList<MutableMap.MutableEntry<String?, String?>>?

    abstract fun setRemoteDefaultExecPropertiesField(value: MutableList<MutableMap.MutableEntry<String?, String?>?>?)

    @get:Option(
        name = "remote_verify_downloads",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("If set to true, Bazel will compute the hash sum of all remote downloads and "
                + " discard the remotely cached values if they don't match the expected value.")
    )
    abstract var remoteVerifyDownloads: Boolean

    @get:Option(
        name = "remote_download_symlink_template",
        defaultValue = "",
        category = "remote",
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Instead of downloading remote build outputs to the local machine, create symbolic "
                + "links. The target of the symbolic links can be specified in the form of a "
                + "template string. This template string may contain {hash} and {size_bytes} that "
                + "expand to the hash of the object and the size in bytes, respectively. "
                + "These symbolic links may, for example, point to a FUSE file system "
                + "that loads objects from the CAS on demand.")
    )
    abstract val remoteDownloadSymlinkTemplate: String?

    @get:Option(
        name = "bep_maximum_open_remote_upload_files",
        defaultValue = "-1",
        documentationCategory = OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Maximum number of open files allowed during BEP artifact upload."
    )
    abstract val maximumOpenFiles: Int

    @get:Option(
        name = "remote_print_execution_messages",
        defaultValue = "failure",
        converter = ExecutionMessagePrintMode.Converter::class,
        category = "remote",
        documentationCategory = OptionDocumentationCategory.LOGGING,
        effectTags = [OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Choose when to print remote execution messages. Valid values are `failure`, "
                + "to print only on failures, `success` to print only on successes and "
                + "`all` to print always.")
    )
    abstract val remotePrintExecutionMessages: ExecutionMessagePrintMode?

    @get:Option(
        name = "experimental_remote_mark_tool_inputs",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("If set to true, Bazel will mark inputs as tool inputs for the remote executor. This "
                + "can be used to implement remote persistent workers.")
    )
    abstract var markToolInputs: Boolean

    @get:Option(
        name = "experimental_remote_discard_merkle_trees",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("If set to true, discard in-memory copies of the input root's Merkle tree and associated "
                + "input mappings during calls to GetActionResult() and Execute(). This reduces "
                + "memory usage significantly, but does require Bazel to recompute them upon remote "
                + "cache misses and retries.")
    )
    abstract var remoteDiscardMerkleTrees: Boolean

    @get:Option(
        name = "experimental_circuit_breaker_strategy",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        defaultValue = "null",
        effectTags = [OptionEffectTag.EXECUTION],
        converter = CircuitBreakerStrategy.Converter::class,
        help = ("Specifies the strategy for the circuit breaker to use. Available strategies are"
                + " \"failure\". On invalid value for the option the behavior same as the option is"
                + " not set.")
    )
    abstract var circuitBreakerStrategy: CircuitBreakerStrategy?

    @get:Option(
        name = "experimental_remote_failure_rate_threshold",
        defaultValue = "10",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.EXECUTION],
        converter = Converters.PercentageConverter::class,
        help = ("Sets the allowed number of failure rate in percentage for a specific time window after"
                + " which it stops calling to the remote cache/executor. By default the value is 10."
                + " Setting this to 0 means no limitation.")
    )
    abstract val remoteFailureRateThreshold: Int

    @get:Option(
        name = "experimental_remote_failure_window_interval",
        defaultValue = "60s",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.EXECUTION],
        converter = RemoteDurationConverter::class,
        help = ("The interval in which the failure rate of the remote requests are computed. On zero or"
                + " negative value the failure duration is computed the whole duration of the"
                + " execution.Following units can be used: Days (d), hours (h), minutes (m), seconds"
                + " (s), and milliseconds (ms). If the unit is omitted, the value is interpreted as"
                + " seconds.")
    )
    abstract val remoteFailureWindowInterval: Duration?

    @get:Option(
        name = "experimental_remote_cache_lease_extension",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("If set to true, Bazel will extend the lease for outputs of remote actions during the"
                + " build by sending `FindMissingBlobs` calls periodically to remote cache. The"
                + " frequency is based on the value of `--experimental_remote_cache_ttl`.")
    )
    abstract val remoteCacheLeaseExtension: Boolean

    @get:Option(
        name = "experimental_remote_scrubbing_config",
        converter = ScrubberConverter::class,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Enables remote cache key scrubbing with the supplied configuration file, which must be a"
                + " protocol buffer in text format (see"
                + " src/main/protobuf/remote_scrubbing.proto).\n\n"
                + "This feature is intended to facilitate sharing a remote/disk cache between actions"
                + " executing on different platforms but targeting the same platform. It should be"
                + " used with extreme care, as improper settings may cause accidental sharing of"
                + " cache entries and result in incorrect builds.\n\n"
                + "Scrubbing does not affect how an action is executed, only how its remote/disk"
                + " cache key is computed for the purpose of retrieving or storing an action result."
                + " Scrubbed actions are incompatible with remote execution, and will always be"
                + " executed locally instead.\n\n"
                + "Modifying the scrubbing configuration does not invalidate outputs present in the"
                + " local filesystem or internal caches; a clean build is required to reexecute"
                + " affected actions.\n\n"
                + "In order to successfully use this feature, you likely want to set a custom"
                + " --host_platform together with --experimental_platform_in_output_dir (to normalize"
                + " output prefixes).")
    )
    abstract var scrubber: Scrubber?

    @get:Option(
        name = "experimental_remote_cache_chunking",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("If enabled, large blobs are split into content-defined chunks using FastCDC 2020 and "
                + "uploaded/downloaded in chunks, enabling deduplication across blobs. The server "
                + "must advertise SplitBlob/SpliceBlob RPCs and FastCDC 2020 parameters in its "
                + "capabilities.")
    )
    abstract val experimentalRemoteCacheChunking: Boolean

    @get:Option(
        name = "experimental_throttle_remote_action_building",
        defaultValue = "true",
        converter = Converters.BooleanConverter::class,
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        effectTags = [OptionEffectTag.EXECUTION],
        help = ("Whether to throttle the building of remote action to avoid OOM. Defaults to true.\n\n"
                + "This is a temporary flag to allow users switch off the behaviour. Once Bazel is"
                + " smart enough about the RAM/CPU usages, this flag will be removed.")
    )
    abstract val throttleRemoteActionBuilding: Boolean

    @get:Option(
        name = "experimental_remote_output_service",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("HOST or HOST:PORT of a remote output service endpoint. The supported schemes are grpc, "
                + "grpcs (grpc with TLS enabled) and unix (local UNIX sockets). If no scheme is "
                + "provided Bazel will default to grpcs. Specify grpc:// or unix: scheme to "
                + "disable TLS.")
    )
    abstract var remoteOutputService: String?

    @get:Option(
        name = "experimental_remote_output_service_output_path_prefix",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("The path under which the contents of output directories managed by the"
                + " --experimental_remote_output_service are placed. The actual output directory used"
                + " by a build will be a descendant of this path and determined by the output"
                + " service.")
    )
    abstract val remoteOutputServiceOutputPathPrefix: String?

    private class ScrubberConverter : Converter.Contextless<Scrubber?>() {
        @Throws(OptionsParsingException::class)
        override fun convert(path: String?): Scrubber {
            try {
                return Scrubber.Companion.parse(path)
            } catch (e: ConfigParseException) {
                throw OptionsParsingException("Failed to parse ScrubbingConfig: " + e.getMessage(), e)
            }
        }

        override fun getTypeDescription(): String {
            return "Converts to a Scrubber"
        }
    }

    // The below options are not configurable by users, only tests.
    // This is part of the effort to reduce the overall number of flags.
    @get:Option(
        name = "max outbound message size",
        defaultValue = "1048576",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.UNKNOWN],
        metadataTags = [OptionMetadataTag.INTERNAL],
        help = "The maximum size of an outbound message sent via a gRPC channel."
    )
    abstract var maxOutboundMessageSize: Int

    val isRemoteCacheEnabled: Boolean
        /** Returns `true` if remote cache or disk cache is enabled.  */
        get() = !Strings.isNullOrEmpty(this.remoteCache) || this.isDiskCacheEnabled
                || this.isRemoteExecutionEnabled

    val isDiskCacheEnabled: Boolean
        /** Returns `true` if disk cache is enabled.  */
        get() = this.diskCache != null

    /**
     * Returns the resolved disk cache path, or `null` if the disk cache is disabled.
     * 
     * 
     * When the user passes `--disk_cache` without an explicit path, the default location
     * under the given `outputUserRoot` is used.
     */
    fun getDiskCachePath(outputUserRoot: Path): PathFragment? {
        if (this.diskCache == null) {
            return null
        }
        return if (this.diskCache!!.isEmpty())
            outputUserRoot.getRelative(DEFAULT_DISK_CACHE_LOCATION).asFragment()
        else
            this.diskCache
    }

    val isRemoteExecutionEnabled: Boolean
        /** Returns `true` if remote execution is enabled.  */
        get() = !Strings.isNullOrEmpty(this.remoteExecutor)

    val remoteDefaultExecProperties: ImmutableSortedMap<String?, String?>
        /**
         * Returns the default exec properties specified by the user or an empty map if nothing was
         * specified. Use this method instead of directly accessing the field.
         */
        get() {
            if (this.remoteDefaultExecPropertiesField == null) {
                return ImmutableSortedMap.of<String?, String?>()
            }
            // Don't use `ImmutableSortedMap.copyOf` directly because it crashes on duplicate keys.
            val map = HashMap<String?, String?>()
            for (entry in this.remoteDefaultExecPropertiesField) {
                map.put(entry.getKey(), entry.getValue())
            }
            return ImmutableSortedMap.copyOf<String?, String?>(map)
        }

    /** An enum for specifying different modes for printing remote execution messages.  */
    enum class ExecutionMessagePrintMode {
        FAILURE,  // Print execution messages only on failure
        SUCCESS,  // Print execution messages only on success
        ALL; // Print execution messages always

        /** Converts to [ExecutionMessagePrintMode].  */
        class Converter : EnumConverter<ExecutionMessagePrintMode?>(
            ExecutionMessagePrintMode::class.java,
            "execution message print mode"
        )

        fun shouldPrintMessages(success: Boolean): Boolean {
            return ((!success && this == ExecutionMessagePrintMode.FAILURE)
                    || (success && this == ExecutionMessagePrintMode.SUCCESS)
                    || this == ExecutionMessagePrintMode.ALL)
        }
    }

    /** An enum for specifying different strategy for circuit breaker.  */
    enum class CircuitBreakerStrategy {
        FAILURE;

        /** Converts to [CircuitBreakerStrategy].  */
        class Converter :
            EnumConverter<CircuitBreakerStrategy?>(CircuitBreakerStrategy::class.java, "CircuitBreaker strategy")
    }

    companion object {
        /** Default disk cache subdirectory under outputUserRoot/cache.  */
        private const val DEFAULT_DISK_CACHE_LOCATION = "cache/disk"
    }
}
