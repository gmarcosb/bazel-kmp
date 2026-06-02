// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildeventstream

import com.google.devtools.build.lib.buildeventstream.BuildEventContext.OutputGroupFileMode

/** Options used to configure the build event protocol.  */
@com.google.devtools.common.options.OptionsClass
abstract class BuildEventProtocolOptions : com.google.devtools.common.options.OptionsBase() {
    @get:com.google.devtools.common.options.Option(
        name = "legacy_important_outputs",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          Use this to suppress generation of the legacy `important_outputs` field in the
          `TargetComplete` event. `important_outputs` are required for Bazel to ResultStore/BTX
          integration.
          
          """.trimIndent()
    )
    abstract val legacyImportantOutputs: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_build_event_upload_strategy",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          Selects how to upload artifacts referenced in the build event protocol. In Bazel
          the valid options include `local` and `remote`. The default value is `local`.
          
          """.trimIndent()
    )
    abstract val buildEventUploadStrategy: String?

    @get:com.google.devtools.common.options.Option(
        name = "build_event_upload_max_retries",
        oldName = "experimental_build_event_upload_max_retries",
        defaultValue = "4",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = "The maximum number of times Bazel should retry uploading a build event."
    )
    abstract val besUploadMaxRetries: Int

    @get:com.google.devtools.common.options.Option(
        name = "experimental_build_event_upload_retry_minimum_delay",
        defaultValue = "1s",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = ("Initial, minimum delay for exponential backoff retries when BEP upload fails. (exponent:"
                + " 1.6)")
    )
    abstract val besUploadRetryInitialDelay: java.time.Duration?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_stream_log_file_uploads",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Stream log file uploads directly to the remote storage rather than writing them to"
                + " disk.")
    )
    abstract val streamingLogFileUploads: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_build_event_expand_filesets",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "If true, expand Filesets in the BEP when presenting output files."
    )
    abstract val expandFilesets: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_bep_target_summary",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = "Whether to publish `TargetSummary` events."
    )
    abstract val publishTargetSummary: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_run_bep_event_include_residue",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Whether to include the command-line residue in run build events which could contain the"
                + " residue. By default, the residue is not included in run command build events that"
                + " could contain the residue.")
    )
    abstract val includeResidueInRunBepEvent: Boolean

    /** Simple String to [OutputGroupFileMode] Converter.  */
    internal class OutputGroupFileModeConverter :
        com.google.devtools.common.options.EnumConverter<OutputGroupFileMode?>(
            OutputGroupFileMode::class.java,
            "Output group file reporting mode"
        )

    /**
     * Options converter that parses the assignment of an [OutputGroupFileMode] for an output
     * group by name, e.g. `default=fileset` or `baseline.lcov=inline`.
     */
    internal class BuildEventOutputGroupModeConverter

        :
        com.google.devtools.common.options.Converter.Contextless<MutableMap.MutableEntry<String?, OutputGroupFileMode?>?>() {
        private val assignmentConverter: com.google.devtools.common.options.Converters.AssignmentConverter =
            com.google.devtools.common.options.Converters.AssignmentConverter()
        private val modeConverter = OutputGroupFileModeConverter()

        val typeDescription: String
            get() = "an output group name followed by an OutputGroupFileMode, e.g. default=both"

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): MutableMap.MutableEntry<String?, OutputGroupFileMode?> {
            val entry: MutableMap.MutableEntry<String?, String?> = assignmentConverter.convert(input)
            val mode: OutputGroupFileMode? = modeConverter.convert(entry.getValue())
            return com.google.common.collect.Maps.immutableEntry<String?, OutputGroupFileMode?>(entry.getKey(), mode)
        }
    }

    /**
     * A mapping from output group name to the [OutputGroupFileMode] to use for that output
     * group.
     */
    fun interface OutputGroupFileModes {
        fun getMode(outputGroup: String?): OutputGroupFileMode?

        companion object {
            @kotlin.jvm.JvmField
            val DEFAULT: OutputGroupFileModes =
                OutputGroupFileModes { outputGroup: String? -> OutputGroupFileMode.NAMED_SET_OF_FILES_ONLY }
        }
    }

    val outputGroupFileModesMapping: OutputGroupFileModes
        /**
         * Collects the values in [.outputGroupFileModes] into a map and returns a [ ] backed by that map and defaulting to [ ] for out groups not in that map.
         * 
         * 
         * This also implements the default value of the `--experimental_build_event_output_group_mode` option, which as an `allowMultiple` option
         * cannot specify a default value. The default value sets the mode for coverage artifacts to BOTH:
         * `--experimental_build_event_output_group_mode=baseline.lcov=both`.
         */
        get() {
            val modeMap: com.google.common.collect.ImmutableMap<String?, OutputGroupFileMode?> =
                com.google.common.collect.ImmutableMap.builder<String?, OutputGroupFileMode?>()
                    .putAll(this.outputGroupFileModes)
                    .buildKeepingLast()
            return OutputGroupFileModes { outputGroup: String? ->
                modeMap.getOrDefault(
                    outputGroup,
                    OutputGroupFileMode.NAMED_SET_OF_FILES_ONLY
                )
            }
        }

    @get:com.google.devtools.common.options.Option(
        name = "experimental_build_event_output_group_mode",
        defaultValue = "null",
        converter = BuildEventOutputGroupModeConverter::class,
        allowMultiple = true,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = """
          Specify how an output group's files will be represented in `TargetComplete`/`AspectComplete`
          BEP events. Values are an assignment of an output group name to one of
          `NAMED_SET_OF_FILES_ONLY`, `INLINE_ONLY`, or `BOTH`. The default value is
          `NAMED_SET_OF_FILES_ONLY`. If an output group is repeated, the final value to
          appear is used. The default value sets the mode for coverage artifacts to BOTH:
          `--experimental_build_event_output_group_mode=baseline.lcov=both`
          
          """.trimIndent()
    )
    abstract val outputGroupFileModes: MutableList<MutableMap.MutableEntry<String?, OutputGroupFileMode>?>?
}
