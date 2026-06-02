// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction.name
import com.google.devtools.build.lib.query2.engine.QueryEvalResult.isEmpty
import com.google.devtools.build.lib.runtime.UiOptions.EventFiltersConverter.EventKindFilters
import java.util.HashSet

/** Command-line UI options.  */
@com.google.devtools.common.options.OptionsClass
abstract class UiOptions : com.google.devtools.common.options.OptionsBase() {
    /** Enum to select whether color output is enabled or not.  */
    enum class UseColor {
        YES,
        NO,
        AUTO
    }

    /** Enum to select whether curses output is enabled or not.  */
    enum class UseCurses {
        YES,
        NO,
        AUTO
    }

    /** Converter for [EventKind] filters *  */
    class EventFiltersConverter

        : com.google.devtools.common.options.Converter.Contextless<EventKindFilters?>() {
        /** Container for an EventKind input filter.  */
        class EventKindFilters(
            filteredEventKinds: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?>?,
            unfilteredEventKinds: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?>?
        ) {
            val filteredEventKinds: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?>?
            val unfilteredEventKinds: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?>?

            init {
                this.unfilteredEventKinds = unfilteredEventKinds
                this.filteredEventKinds = filteredEventKinds
                java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?>?>(
                    filteredEventKinds,
                    "filteredEventKinds"
                )
                java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?>?>(
                    unfilteredEventKinds,
                    "unfilteredEventKinds"
                )
            }

            companion object {
                fun from(
                    filtered: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?>?,
                    unfiltered: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?>?
                ): EventKindFilters {
                    return EventKindFilters(filtered, unfiltered)
                }
            }
        }

        private val commaSeparatedListConverter: com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter
        private val eventKindConverter: com.google.devtools.common.options.EnumConverter<com.google.devtools.build.lib.events.EventKind?>

        init {
            this.commaSeparatedListConverter =
                com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter()
            this.eventKindConverter = object :
                com.google.devtools.common.options.EnumConverter<com.google.devtools.build.lib.events.EventKind?>(
                    com.google.devtools.build.lib.events.EventKind::class.java,
                    "event kind"
                ) {}
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): EventKindFilters {
            if (input.isEmpty()) {
                // This method is not called to convert the default value
                // Empty list means that the user wants to filter all events
                return EventKindFilters.Companion.from(
                    com.google.devtools.build.lib.events.EventKind.ALL_EVENTS,
                    com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.events.EventKind?>()
                )
            }
            val filters: com.google.common.collect.ImmutableList<String> =
                commaSeparatedListConverter.convert(input,  /* conversionContext= */null)

            val filteredEventKinds: HashSet<com.google.devtools.build.lib.events.EventKind?> =
                HashSet<com.google.devtools.build.lib.events.EventKind?>()
            val unfilteredEventKinds: HashSet<com.google.devtools.build.lib.events.EventKind?> =
                HashSet<com.google.devtools.build.lib.events.EventKind?>()

            for (filter in filters) {
                if (!filter.startsWith("+") && !filter.startsWith("-")) {
                    filteredEventKinds.addAll(com.google.devtools.build.lib.events.EventKind.ALL_EVENTS)
                    unfilteredEventKinds.clear()
                }
                if (!filter.isEmpty()) {
                    val kind: com.google.devtools.build.lib.events.EventKind? =
                        eventKindConverter.convert(
                            filter.replaceFirst("^[+-]", ""),  /* conversionContext= */null
                        )
                    if (filter.startsWith("-")) {
                        filteredEventKinds.add(kind)
                        unfilteredEventKinds.remove(kind)
                    } else {
                        unfilteredEventKinds.add(kind)
                        filteredEventKinds.remove(kind)
                    }
                }
            }
            return EventKindFilters.Companion.from(
                com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.events.EventKind?>(
                    filteredEventKinds
                ),
                com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.events.EventKind?>(
                    unfilteredEventKinds
                )
            )
        }

        val typeDescription: String
            get() = "Convert list of comma separated event kind to list of filters"
    }

    /** Converter for [UseColor].  */
    class UseColorConverter :
        com.google.devtools.common.options.EnumConverter<UseColor?>(UseColor::class.java, "--color setting")

    /** Converter for [UseCurses].  */
    class UseCursesConverter :
        com.google.devtools.common.options.EnumConverter<UseCurses?>(UseCurses::class.java, "--curses setting")

    @get:com.google.devtools.common.options.Option(
        name = "show_progress",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = "Display progress messages during a build."
    )
    abstract var showProgress: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "show_progress_rate_limit",
        defaultValue = "0.2",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = "Minimum number of seconds between progress messages in the output."
    )
    abstract var showProgressRateLimit: Double

    @get:com.google.devtools.common.options.Option(
        name = "color",
        defaultValue = "auto",
        converter = UseColorConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = "Use terminal controls to colorize output."
    )
    abstract val useColorEnum: UseColor?

    @get:com.google.devtools.common.options.Option(
        name = "curses",
        defaultValue = "auto",
        converter = UseCursesConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = "Use terminal cursor controls to minimize scrolling output."
    )
    abstract var useCursesEnum: UseCurses?

    @get:com.google.devtools.common.options.Option(
        name = "terminal_columns",
        defaultValue = "80",
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = "A system-generated parameter which specifies the terminal width in columns."
    )
    abstract val terminalColumns: Int

    @get:com.google.devtools.common.options.Option(
        name = "isatty",
        defaultValue = "false",
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("A system-generated parameter which is used to notify the "
                + "server whether this client is running in a terminal. "
                + "If this is set to false, then '--color=auto' will be treated as '--color=no'. "
                + "If this is set to true, then '--color=auto' will be treated as '--color=yes'.")
    )
    abstract val isATty: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "emacs",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("A system-generated parameter which is true iff EMACS=t or INSIDE_EMACS is set "
                + "in the environment of the client.  This option controls certain display "
                + "features.")
    )
    abstract val runningInEmacs: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "show_timestamps",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = "Include timestamps in messages"
    )
    abstract val showTimestamp: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "progress_in_terminal_title",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Show the command progress in the terminal title. "
                + "Useful to see what bazel is doing when having multiple terminal tabs.")
    )
    abstract val progressInTermTitle: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "attempt_to_print_relative_paths",
        oldName = "experimental_ui_attempt_to_print_relative_paths",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("When printing the location part of messages, attempt to use a path relative to the "
                + "workspace directory or one of the directories specified by --package_path.")
    )
    abstract val attemptToPrintRelativePaths: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_ui_debug_all_events",
        defaultValue = "false",
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = "Report all events known to the Bazel UI."
    )
    abstract val experimentalUiDebugAllEvents: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "ui_event_filters",
        converter = EventFiltersConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Specifies which events to show in the UI. It is possible to add or remove events "
                + "to the default ones using leading +/-, or override the default "
                + "set completely with direct assignment. The set of supported event kinds "
                + "include INFO, DEBUG, ERROR and more."),
        allowMultiple = true
    )
    abstract val eventKindFilters: MutableList<EventKindFilters>?

    abstract fun setEventKindFilters(value: MutableList<EventKindFilters?>?)

    @get:com.google.devtools.common.options.Option(
        name = "ui_actions_shown",
        oldName = "experimental_ui_actions_shown",
        defaultValue = "8",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Number of concurrent actions shown in the detailed progress bar; each "
                + "action is shown on a separate line. The progress bar always shows "
                + "at least one one, all numbers less than 1 are mapped to 1.")
    )
    abstract var uiActionsShown: Int

    @get:com.google.devtools.common.options.Option(
        name = "experimental_ui_max_stdouterr_bytes",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        defaultValue = "1048576",
        converter = MaxStdoutErrBytesConverter::class,
        help = ("The maximum size of the stdout / stderr files that will be printed to the console. "
                + "-1 implies no limit.")
    )
    abstract val maxStdoutErrBytes: Int

    fun useColor(): Boolean {
        return this.useColorEnum == UseColor.YES || (this.useColorEnum == UseColor.AUTO && this.isATty)
    }

    fun useCursorControl(): Boolean {
        return this.useCursesEnum == UseCurses.YES
                || (this.useCursesEnum == UseCurses.AUTO && this.isATty)
    }

    val filteredEventKinds: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?>
        get() {
            val filtered: HashSet<com.google.devtools.build.lib.events.EventKind?> =
                HashSet<com.google.devtools.build.lib.events.EventKind?>()
            for (filters in this.eventKindFilters!!) {
                filtered.addAll(filters.filteredEventKinds)
                filtered.removeAll(filters.unfilteredEventKinds)
            }
            return com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.events.EventKind?>(
                filtered
            )
        }

    /** A converter for --experimental_ui_max_stdouterr_bytes.  */
    class MaxStdoutErrBytesConverter :
        com.google.devtools.common.options.Converters.RangeConverter(-1, (java.lang.Integer.MAX_VALUE - 8) shr 1) {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?): Int {
            val value: Int = super.convert(input)
            return if (value >= 0) value else MAX_VALUE
        }

        companion object {
            /**
             * The maximum value of the flag must be limited to ensure conversions to UTF-8 do not trigger
             * integer overflows. In JDK9+, if the message buffer contains a byte whose high bit is set, a
             * UTF-8 decoding path is taken that allocates a new byte[] buffer twice as large as the message
             * byte[] buffer.
             */
            private val MAX_VALUE: Int = (java.lang.Integer.MAX_VALUE - 8) shr 1
        }
    }
}
