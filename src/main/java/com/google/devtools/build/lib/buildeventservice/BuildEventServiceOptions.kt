// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildeventservice

/** Options used by [BuildEventServiceModule].  */
@com.google.devtools.common.options.OptionsClass
abstract class BuildEventServiceOptions : com.google.devtools.common.options.OptionsBase() {
    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "bes_backend",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          Specifies the build event service (BES) backend endpoint in the form
          `[SCHEME://]HOST[:PORT]`. The default is to disable BES uploads. Supported schemes
          are `grpc` and `grpcs` (grpc with TLS enabled). If no scheme is provided, Bazel
          assumes `grpcs`.
          
          """.trimIndent()
    )
    abstract var besBackend: String?

    @get:com.google.devtools.common.options.Option(
        name = "bes_timeout",
        defaultValue = "0s",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          Specifies how long bazel should wait for the BES/BEP upload to complete after the
          build and tests have finished. A valid timeout is a natural number followed by a
          unit: Days (d), hours (h), minutes (m), seconds (s), and milliseconds (ms). The
          default value is `0` which means that there is no timeout.
          
          """.trimIndent()
    )
    abstract val besTimeout: java.time.Duration?

    abstract fun setBesTimeout(value: java.time.Duration?)

    @get:com.google.devtools.common.options.Option(
        name = "bes_header",
        converter = com.google.devtools.common.options.Converters.AssignmentConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          Specify a header in `NAME=VALUE` form that will be included in BES requests.
          Multiple headers can be passed by specifying the flag multiple times. Multiple
          values for the same name will be converted to a comma-separated list.
          
          """.trimIndent(),
        allowMultiple = true
    )
    abstract var besHeaders: MutableList<MutableMap.MutableEntry<String?, String?>?>?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "bes_lifecycle_events",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Specifies whether to publish BES lifecycle events. (defaults to 'true')."
    )
    abstract var besLifecycleEvents: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "bes_instance_name",
        oldName = "project_id",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Specifies the instance name under which the BES will persist uploaded BEP. Defaults "
                + "to null.")
    )
    abstract var instanceName: String?

    @get:com.google.devtools.common.options.Option(
        name = "bes_keywords",
        defaultValue = "null",
        converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        allowMultiple = true,
        help = """
          Specifies a list of notification keywords to be added the default set of keywords
          published to BES (`command_name={command_name}`, `protocol_name=BEP`).
          Defaults to none.
          
          """.trimIndent()
    )
    abstract var besKeywords: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "bes_system_keywords",
        defaultValue = "null",
        converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        allowMultiple = true,
        help = """
          Specifies a list of notification keywords to be included directly, without the
          `user_keyword=` prefix included for keywords supplied via `--bes_keywords`.
          Intended for Build service operators that set `--bes_lifecycle_events=false` and
          include keywords when calling `PublishLifecycleEvent`. Build service operators
          using this flag should prevent users from overriding the flag value.
          
          """.trimIndent()
    )
    abstract var besSystemKeywords: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "bes_outerr_buffer_size",
        defaultValue = "10240",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          Specifies the maximal size of stdout or stderr to be buffered in BEP, before it is
          reported as a progress event. Individual writes are still reported in a single
          event, even if larger than the specified value up to `--bes_outerr_chunk_size`.
          
          """.trimIndent()
    )
    abstract var besOuterrBufferSize: Int

    @get:com.google.devtools.common.options.Option(
        name = "bes_outerr_chunk_size",
        defaultValue = "1048576",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Specifies the maximal size of stdout or stderr to be sent to BEP in a single message."
    )
    abstract var besOuterrChunkSize: Int

    @get:com.google.devtools.common.options.Option(
        name = "bes_results_url",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Specifies the base URL where a user can view the information streamed to the BES"
                + " backend. Bazel will output the URL appended by the invocation id to the"
                + " terminal.")
    )
    abstract var besResultsUrl: String?

    @get:com.google.devtools.common.options.Option(
        name = "bes_upload_mode",
        defaultValue = "wait_for_upload_complete",
        converter = BesUploadModeConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EAGERNESS_TO_EXIT],
        help = """
          Specifies whether the Build Event Service upload should block the build completion or
          should end the invocation immediately and finish the upload in the
          background.
          - `wait_for_upload_complete`: blocks at the end of the current invocation until
            all events (including lifecycle events if applicable) are uploaded and
            acknowledged by the backend.
          - `nowait_for_upload_complete`: blocks at the beginning of the next invocation
            until all events (including lifecycle events if applicable) are uploaded and
            acknowledged by the backend.
          - `fully_async`: blocks at the beginning of the next invocation until all events
            are uploaded but does not wait for acknowledgements. Events may be lost in case
            of (transient) failures and backends may report streams as incomplete in this
            mode. There is no guarantee that `FinishInvocationAttempt` or `FinishBuild`
            lifecycle events are sent.
          
          """.trimIndent()
    )
    abstract var besUploadMode: BesUploadMode?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "bes_proxy",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = """
          Connect to the Build Event Service through a proxy. Currently this flag can only be
          used to configure a Unix domain socket (`unix:/path/to/socket`).
          
          """.trimIndent()
    )
    abstract var besProxy: String?

    @get:com.google.devtools.common.options.Option(
        name = "bes_check_preceding_lifecycle_events",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          Sets the field `check_preceding_lifecycle_events_present` on
          `PublishBuildToolEventStreamRequest` which tells BES to check whether it previously
          received `InvocationAttemptStarted` and `BuildEnqueued` events matching the current
          tool event.
          
          """.trimIndent()
    )
    abstract var besCheckPrecedingLifecycleEvents: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "bes_oom_finish_upload_timeout",
        defaultValue = "10m",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        converter = com.google.devtools.common.options.Converters.DurationConverter::class,
        help = ("Specifies how long bazel should wait for the BES/BEP upload to complete while OOMing. "
                + "This flag ensures termination when the JVM is severely GC thrashing and cannot "
                + "make progress on any user thread.")
    )
    abstract val besOomFinishUploadTimeout: java.time.Duration?

    abstract fun setBesOomFinishUploadTimeout(value: java.time.Duration?)

    /** Determines the mode that will be used to upload data to the Build Event Service.  */
    enum class BesUploadMode {
        /** Block at the end of the build waiting for the upload to complete  */
        WAIT_FOR_UPLOAD_COMPLETE,

        /** Block at the beginning of the next build waiting for upload completion  */
        NOWAIT_FOR_UPLOAD_COMPLETE,

        /**
         * Block at the beginning of the next build waiting for the client to finish uploading the data,
         * but possibly not blocking on the server acknowledgement.
         */
        FULLY_ASYNC,
    }

    /** Converter for [BesUploadMode]  */
    class BesUploadModeConverter : com.google.devtools.common.options.EnumConverter<BesUploadMode?>(
        BesUploadMode::class.java,
        "Mode for uploading to the Build Event Service"
    )
}
