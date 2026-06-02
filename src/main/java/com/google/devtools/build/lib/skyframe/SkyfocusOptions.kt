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
package com.google.devtools.build.lib.skyframe

/** The set of options for the Skyfocus feature.  */
@com.google.devtools.common.options.OptionsClass
abstract class SkyfocusOptions : com.google.devtools.common.options.OptionsBase() {
    @get:com.google.devtools.common.options.Option(
        name = "experimental_enable_skyfocus",
        defaultValue = "false",
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        help = ("If true, enable the use of --experimental_active_directories to reduce Bazel's memory"
                + " footprint for incremental builds. This feature is known as Skyfocus.")
    )
    abstract val skyfocusEnabled: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_active_directories",
        defaultValue = "",
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter::class,
        help = ("Active directories for Skyfocus and remote analysis caching. Specify as comma-separated"
                + " workspace root-relative paths. This is a stateful flag. Defining one persists it"
                + " for subsequent invocations, until it is redefined with a new set.")
    )
    abstract val activeDirectories: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_skyfocus_dump_keys",
        defaultValue = "none",
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        converter = SkyfocusDumpEnumConverter::class,
        help = ("For debugging Skyfocus. Dump the focused SkyKeys (roots, leafs, focused deps, focused"
                + " rdeps).")
    )
    abstract val dumpKeys: SkyfocusDumpOption?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_skyfocus_dump_post_gc_stats",
        defaultValue = "false",
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        help = ("For debugging Skyfocus. If enabled, trigger manual GC before/after focusing to report"
                + " heap sizes reductions. This will increase the Skyfocus latency.")
    )
    abstract val dumpPostGcStats: Boolean

    /** Options to dump the state of the Skyframe graph before/after Skyfocus.  */
    enum class SkyfocusDumpOption {
        NONE,

        /**
         * Dump the counts and reductions of SkyKeys in the graph, active directories, and verification
         * set.
         */
        COUNT,

        /** Dump the string representation of SkyKeys in the active directories and verification set.  */
        VERBOSE,
    }

    /** Enum converter for SkyframeDumpOption.  */
    private class SkyfocusDumpEnumConverter : com.google.devtools.common.options.EnumConverter<SkyfocusDumpOption?>(
        SkyfocusDumpOption::class.java,
        "Skyframe Dump option"
    )

    @get:com.google.devtools.common.options.Option(
        name = "experimental_frontier_violation_check",
        defaultValue = "strict",
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EAGERNESS_TO_EXIT],
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        converter = FrontierViolationCheckConverter::class,
        help = ("Strategies to handle potential incorrectness from changes beyond the frontier (i.e."
                + " outside the active directories)")
    )
    abstract val frontierViolationCheck: FrontierViolationCheck?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_frontier_violation_verbose",
        defaultValue = "false",
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        help = "If true, Bazel will print instructions for fixing Skycache violations"
    )
    abstract val frontierViolationVerbose: Boolean

    /**
     * Strategies for handing the "sad path" in Skyfocus and analysis caching, where it needs to
     * handle changes outside of the active directories. This usually requires some reanalysis to
     * rebuild the dropped Skyframe nodes.
     */
    enum class FrontierViolationCheck {
        /**
         * Strict mode. Makes the "sad path" explicit to the user, and how to avoid it. Errors out when
         * Skyfocus detects a change outside of an active active directories. Avoids automatic/graceful
         * handling for the user.
         */
        STRICT,

        /**
         * Warn mode. Attempt to handle the "sad path" gracefully. Emits warning messages when the user
         * makes a change outside of the active directories.
         */
        WARN,

        /**
         * Disabled. Only used for testing.
         * 
         * 
         * TODO: b/367284400 - replace this with a barebones diffawareness check that works in Bazel
         * integration tests (e.g. making LocalDiffAwareness supported and not return
         * EVERYTHING_MODIFIED) for baseline diffs.
         */
        DISABLED_FOR_TESTING,
    }

    /** Enum converter for FrontierViolationCheck  */
    private class FrontierViolationCheckConverter

        : com.google.devtools.common.options.EnumConverter<FrontierViolationCheck?>(
        FrontierViolationCheck::class.java,
        "Skyfocus handling strategy option"
    )
}
