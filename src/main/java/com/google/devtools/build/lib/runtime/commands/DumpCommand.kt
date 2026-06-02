// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.runtime.Command.BuildPhase.NONE

/** Implementation of the dump command.  */
@Command(
    name = "dump",
    mustRunInWorkspace = false,
    buildPhase = NONE,
    options = [DumpOptions::class],
    help = ("Usage: %{product} dump <options>\n"
            + "Dumps the internal state of the %{product} server process.  This command is provided"
            + " as an aid to debugging, not as a stable interface, so users should not try to parse"
            + " the output; instead, use 'query' or 'info' for this purpose.\n"
            + "%{options}"),
    shortDescription = "Dumps the internal state of the %{product} server process.",
    binaryStdOut = true
)
class DumpCommand : BlazeCommand {
    /** How to dump Skyframe memory.  */
    private enum class MemoryCollectionMode {
        /** Dump the objects owned by a single SkyValue  */
        SHALLOW,

        /** Dump objects reachable from a single SkyValue  */
        DEEP,

        /** Dump objects in the Skyframe transitive closure of a SkyValue  */
        TRANSITIVE,

        /** Dump every object in Skyframe in "shallow" mode.  */
        FULL,
    }

    /** Whose memory use we should measure.  */
    private enum class MemorySubjectType {
        /** Starlark module  */
        STARLARK_MODULE,

        /* Build package */
        PACKAGE,

        /* Configured target */
        CONFIGURED_TARGET,
    }

    /** What exactly to dump about the memory use of Bazel.  */
    class MemoryMode(
        val collectionMode: MemoryCollectionMode?,
        displayMode: DisplayMode?,
        type: MemorySubjectType?,
        needle: String?,
        reportTransient: Boolean,
        reportConfiguration: Boolean,
        reportPrecomputed: Boolean,
        reportWorkspaceStatus: Boolean,
        subject: String?
    ) {
        val displayMode: DisplayMode?
        val type: MemorySubjectType?
        val needle: String?
        val reportTransient: Boolean
        val reportConfiguration: Boolean
        val reportPrecomputed: Boolean
        val reportWorkspaceStatus: Boolean
        val subject: String?

        init {
            this.displayMode = displayMode
            this.type = type
            this.needle = needle
            this.reportTransient = reportTransient
            this.reportConfiguration = reportConfiguration
            this.reportPrecomputed = reportPrecomputed
            this.reportWorkspaceStatus = reportWorkspaceStatus
            this.subject = subject
        }
    }

    /** Converter for [MemoryCollectionMode].  */
    class MemoryModeConverter : com.google.devtools.common.options.Converter.Contextless<MemoryMode?>() {
        val typeDescription: String
            get() = "memory mode"

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): MemoryMode {
            // The SkyKey designator is frequently a Label, which usually contains a colon so we must not
            // split the argument into an unlimited number of elements
            val items: Array<String?> = input.split(":".toRegex(), limit = 3).toTypedArray()
            if (items.size > 3) {
                throw com.google.devtools.common.options.OptionsParsingException("Should contain at most three segments separated by ':'")
            }

            var collectionMode: MemoryCollectionMode? = null
            var displayMode: DisplayMode? = null
            var needle: String? = null
            var reportTransient = true
            var reportConfiguration = true
            var reportPrecomputed = true
            var reportWorkspaceStatus = true

            for (word in com.google.common.base.Splitter.on(",").split(items[0])) {
                if (word.startsWith("needle=")) {
                    needle = word.split("=".toRegex(), limit = 2).toTypedArray()[1]
                    continue
                }

                when (word) {
                    "shallow" -> collectionMode = MemoryCollectionMode.SHALLOW
                    "deep" -> collectionMode = MemoryCollectionMode.DEEP
                    "transitive" -> collectionMode = MemoryCollectionMode.TRANSITIVE
                    "full" -> collectionMode = MemoryCollectionMode.FULL
                    "summary" -> displayMode = DisplayMode.SUMMARY
                    "count" -> displayMode = DisplayMode.COUNT
                    "bytes" -> displayMode = DisplayMode.BYTES
                    "notransient" -> reportTransient = false
                    "noconfig" -> reportConfiguration = false
                    "noprecomputed" -> reportPrecomputed = false
                    "noworkspacestatus" -> reportWorkspaceStatus = false
                    else -> throw com.google.devtools.common.options.OptionsParsingException("Unrecognized word '" + word + "'")
                }
            }

            if (collectionMode == null) {
                throw com.google.devtools.common.options.OptionsParsingException("No collection type specified")
            }

            if (displayMode == null) {
                throw com.google.devtools.common.options.OptionsParsingException("No display mode specified")
            }

            if (collectionMode == MemoryCollectionMode.FULL) {
                return MemoryMode(
                    collectionMode,
                    displayMode,
                    null,
                    needle,
                    reportTransient,
                    reportConfiguration,
                    reportPrecomputed,
                    reportWorkspaceStatus,
                    null
                )
            }

            if (items.size != 3) {
                throw com.google.devtools.common.options.OptionsParsingException("Should be in the form: <flags>:<node type>:<node>")
            }

            val subjectType: MemorySubjectType?

            try {
                subjectType =
                    com.google.devtools.build.lib.runtime.commands.DumpCommand.MemorySubjectType.valueOf(items[1].uppercase())
            } catch (e: java.lang.IllegalArgumentException) {
                throw com.google.devtools.common.options.OptionsParsingException("Invalid subject type", e)
            }

            return MemoryMode(
                collectionMode,
                displayMode,
                subjectType,
                needle,
                reportTransient,
                reportConfiguration,
                reportPrecomputed,
                reportWorkspaceStatus,
                items[2]
            )
        }
    }

    /**
     * NB! Any changes to this class must be kept in sync with anyOutput variable value in the [ ][DumpCommand.exec] method below.
     */
    @com.google.devtools.common.options.OptionsClass
    abstract class DumpOptions : com.google.devtools.common.options.OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "packages",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
            help = "Dump package cache content."
        )
        abstract val dumpPackages: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "action_cache",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
            help = "Dump action cache content."
        )
        abstract val dumpActionCache: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "rule_classes",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
            help = "Dump rule classes."
        )
        abstract val dumpRuleClasses: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "rules",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
            help = "Dump rules, including counts and memory usage (if memory is tracked)."
        )
        abstract val dumpRules: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "skylark_memory",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
            help = ("Dumps a pprof-compatible memory profile to the specified path. To learn more please"
                    + " see https://github.com/google/pprof.")
        )
        abstract val starlarkMemory: String?

        @get:com.google.devtools.common.options.Option(
            name = "skyframe",
            defaultValue = "off",
            converter = SkyframeDumpEnumConverter::class,
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
            help = "Dump the Skyframe graph."
        )
        abstract val dumpSkyframe: SkyframeDumpOption?

        @get:com.google.devtools.common.options.Option(
            name = "skykey_filter",
            defaultValue = ".*",
            converter = RegexFilterConverter::class,
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
            help = ("Regex filter of SkyKey names to output. Only used with --skyframe=deps, rdeps,"
                    + " function_graph.")
        )
        abstract val skyKeyFilter: com.google.devtools.build.lib.util.RegexFilter?

        @get:com.google.devtools.common.options.Option(
            name = "memory",
            defaultValue = "null",
            converter = MemoryModeConverter::class,
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
            help = "Dump the memory use of the given Skyframe node."
        )
        abstract val memory: MemoryMode?
    }

    /** Different ways to dump information about Skyframe.  */
    enum class SkyframeDumpOption {
        OFF,
        SUMMARY,
        COUNT,
        VALUE,
        DEPS,
        RDEPS,
        FUNCTION_GRAPH,
        ACTIVE_DIRECTORIES,
        ACTIVE_DIRECTORIES_FRONTIER_DEPS,
    }

    /** Enum converter for SkyframeDumpOption.  */
    class SkyframeDumpEnumConverter : com.google.devtools.common.options.EnumConverter<SkyframeDumpOption?>(
        SkyframeDumpOption::class.java,
        "Skyframe Dump option"
    )

    public override fun exec(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult? {
        val runtime: BlazeRuntime = env.getRuntime()
        val dumpOptions: DumpOptions? = options.getOptions<DumpOptions?>(DumpOptions::class.java)

        val anyOutput =
            dumpOptions!!.dumpPackages
                    || dumpOptions.dumpActionCache
                    || dumpOptions.dumpRuleClasses
                    || dumpOptions.dumpRules
                    || dumpOptions.starlarkMemory != null || dumpOptions.dumpSkyframe != SkyframeDumpOption.OFF || dumpOptions.memory != null
        if (!anyOutput) {
            val optionList: MutableCollection<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> =
                java.util.ArrayList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()
            optionList.add(DumpOptions::class.java)

            env.getReporter()
                .getOutErr()
                .printErrLn(
                    BlazeCommandUtils.expandHelpTopic(
                        javaClass.getAnnotation<A?>(Command::class.java).name(),
                        javaClass.getAnnotation<A?>(Command::class.java).help(),
                        javaClass,
                        optionList,
                        com.google.devtools.common.options.HelpVerbosity.LONG,
                        runtime.productName
                    )
                )
            return createFailureResult("no output specified", Code.NO_OUTPUT_SPECIFIED)
        }
        val out: PrintStream =
            PrintStream(
                BufferedOutputStream(env.getReporter().getOutErr().getOutputStream(), 1024 * 1024)
            )
        try {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.warn(WARNING_MESSAGE))
            var failure: java.util.Optional<BlazeCommandResult?> = java.util.Optional.empty<BlazeCommandResult?>()

            if (dumpOptions.dumpPackages) {
                env.getPackageManager().dump(out)
                out.println()
            }

            if (dumpOptions.dumpActionCache) {
                if (!dumpActionCache(env, out)) {
                    failure =
                        java.util.Optional.of<BlazeCommandResult?>(
                            createFailureResult("action cache dump failed", Code.ACTION_CACHE_DUMP_FAILED)
                        )
                }
                out.println()
            }

            if (dumpOptions.dumpRuleClasses) {
                dumpRuleClasses(runtime, out)
                out.println()
            }

            if (dumpOptions.dumpRules) {
                dumpRuleStats(env.getBlazeWorkspace(), env.getSkyframeExecutor(), out)
                out.println()
            }

            if (dumpOptions.starlarkMemory != null) {
                try {
                    val starlarkHeapOutput: InstrumentationOutput =
                        runtime
                            .getInstrumentationOutputFactory()
                            .createInstrumentationOutput( /* name= */
                                "starlark_heap",
                                PathFragment.create(dumpOptions.starlarkMemory),
                                DestinationRelativeTo.WORKSPACE_OR_HOME,
                                env,
                                env.getReporter(),  /* append= */
                                null,  /* internal= */
                                null
                            )
                    dumpStarlarkHeap(
                        env.getBlazeWorkspace(), starlarkHeapOutput, dumpOptions.starlarkMemory, out
                    )
                } catch (e: IOException) {
                    val message = "Could not dump Starlark memory"
                    env.getReporter().error(null, message, e)
                    failure = java.util.Optional.of<BlazeCommandResult?>(
                        createFailureResult(
                            message,
                            Code.STARLARK_HEAP_DUMP_FAILED
                        )
                    )
                }
            }

            if (dumpOptions.memory != null) {
                failure = Companion.dumpSkyframeMemory(env, dumpOptions, out)
            }

            val evaluator: MemoizingEvaluator = env.getSkyframeExecutor().getEvaluator()
            when (dumpOptions.dumpSkyframe) {
                SkyframeDumpOption.SUMMARY -> evaluator.dumpSummary(out)
                SkyframeDumpOption.COUNT -> evaluator.dumpCount(out)
                SkyframeDumpOption.VALUE -> evaluator.dumpValues(out, dumpOptions.skyKeyFilter)
                SkyframeDumpOption.DEPS -> evaluator.dumpDeps(out, dumpOptions.skyKeyFilter)
                SkyframeDumpOption.RDEPS -> evaluator.dumpRdeps(out, dumpOptions.skyKeyFilter)
                SkyframeDumpOption.FUNCTION_GRAPH -> evaluator.dumpFunctionGraph(out, dumpOptions.skyKeyFilter)
                SkyframeDumpOption.ACTIVE_DIRECTORIES -> env.getSkyframeExecutor().getSkyfocusState()
                    .dumpActiveDirectories(out)

                SkyframeDumpOption.ACTIVE_DIRECTORIES_FRONTIER_DEPS -> env.getSkyframeExecutor().getSkyfocusState()
                    .dumpFrontierSet(out)

                SkyframeDumpOption.OFF -> {}
            }

            return failure.orElse(BlazeCommandResult.success())
        } catch (e: java.lang.InterruptedException) {
            env.getReporter().error(null, "Interrupted", e)
            return BlazeCommandResult.failureDetail(
                FailureDetail.newBuilder()
                    .setInterrupted(
                        FailureDetails.Interrupted.newBuilder()
                            .setCode(FailureDetails.Interrupted.Code.INTERRUPTED)
                    )
                    .build()
            )
        } finally {
            out.flush()
        }
    }

    companion object {
        val WARNING_MESSAGE: String = ("This information is intended for consumption by developers "
                + "only, and may change at any time. Script against it at your own risk!")

        private fun dumpActionCache(env: CommandEnvironment, out: PrintStream?): Boolean {
            val reporter: com.google.devtools.build.lib.events.Reporter = env.getReporter()
            try {
                env.getBlazeWorkspace().getOrLoadPersistentActionCache(reporter).dump(out)
            } catch (e: IOException) {
                reporter.handle(com.google.devtools.build.lib.events.Event.error("Cannot dump action cache: " + e.message))
                return false
            }
            return true
        }

        private fun dumpRuleClasses(runtime: BlazeRuntime, out: PrintStream) {
            val ruleClassMap: com.google.common.collect.ImmutableMap<String?, RuleClass?> =
                runtime.getRuleClassProvider().getRuleClassMap()
            val ruleClassNames: MutableList<String> = java.util.ArrayList<String>(ruleClassMap.keys)
            Collections.sort<String?>(ruleClassNames)
            for (name in ruleClassNames) {
                if (name.startsWith("$")) {
                    continue
                }
                val ruleClass: RuleClass? = ruleClassMap.get(name)
                out.print(ruleClass.toString() + "(")
                var first = true
                for (attribute in ruleClass.getAttributeProvider().getAttributes()) {
                    if (attribute.isImplicit()) {
                        continue
                    }
                    if (first) {
                        first = false
                    } else {
                        out.print(", ")
                    }
                    out.print(attribute.name)
                }
                out.println(")")
            }
        }

        @Throws(java.lang.InterruptedException::class)
        private fun dumpRuleStats(
            workspace: BlazeWorkspace,
            executor: SkyframeExecutor,
            out: PrintStream
        ) {
            val skyframeStats: SkyframeStats? = executor.getSkyframeStats()
            if (skyframeStats.ruleStats.isEmpty()) {
                out.print("No rules in Bazel server, please run a build command first.")
                return
            }
            val rules: com.google.common.collect.ImmutableList<SkyKeyStats> = skyframeStats.ruleStats
            val aspects: com.google.common.collect.ImmutableList<SkyKeyStats> = skyframeStats.aspectStats
            val ruleBytes: MutableMap<String?, RuleBytes?> = HashMap<String?, RuleBytes?>()
            val aspectBytes: MutableMap<String?, RuleBytes?> = HashMap<String?, RuleBytes?>()
            val allocationTracker: AllocationTracker? = workspace.getAllocationTracker()
            if (allocationTracker != null) {
                allocationTracker.getRuleMemoryConsumption(ruleBytes, aspectBytes)
            }
            printRuleStatsOfType(rules, "RULE", out, ruleBytes, allocationTracker != null, false)
            printRuleStatsOfType(aspects, "ASPECT", out, aspectBytes, allocationTracker != null, true)
        }

        private fun printRuleStatsOfType(
            ruleStats: com.google.common.collect.ImmutableList<SkyKeyStats>,
            type: String,
            out: PrintStream,
            ruleToBytes: MutableMap<String?, RuleBytes?>,
            bytesEnabled: Boolean,
            trimKey: Boolean
        ) {
            if (ruleStats.isEmpty()) {
                return
            }
            // ruleStats are already sorted.
            val longestName: Int =
                ruleStats.stream().map<Int?> { r: SkyKeyStats? -> r.getName().length }
                    .max(java.util.Comparator { obj: Int?, anotherInteger: Int? -> obj!!.compareTo(anotherInteger!!) })
                    .get()
            val maxNameWidth = 30
            val nameColumnWidth: Int = min(longestName, maxNameWidth)
            val numberColumnWidth = 10
            val bytesColumnWidth = 13
            val eachColumnWidth = 11
            printWithPadding(out, type, nameColumnWidth)
            printWithPaddingBefore(out, "COUNT", numberColumnWidth)
            printWithPaddingBefore(out, "ACTIONS", numberColumnWidth)
            if (bytesEnabled) {
                printWithPaddingBefore(out, "BYTES", bytesColumnWidth)
                printWithPaddingBefore(out, "EACH", eachColumnWidth)
            }
            out.println()
            for (ruleStat in ruleStats) {
                printWithPadding(
                    out, truncateName(ruleStat.getName(), trimKey, maxNameWidth), nameColumnWidth
                )
                Companion.printWithPaddingBefore(out, formatLong(ruleStat.getCount())!!, numberColumnWidth)
                Companion.printWithPaddingBefore(out, formatLong(ruleStat.getActionCount())!!, numberColumnWidth)
                if (bytesEnabled) {
                    val ruleBytes: RuleBytes? = ruleToBytes.get(ruleStat.getKey())
                    val bytes = if (ruleBytes != null) ruleBytes.bytes else 0L
                    Companion.printWithPaddingBefore(out, formatLong(bytes)!!, bytesColumnWidth)
                    Companion.printWithPaddingBefore(out, formatLong(bytes / ruleStat.getCount())!!, eachColumnWidth)
                }
                out.println()
            }
            out.println()
        }

        private fun truncateName(name: String, trimKey: Boolean, maxNameWidth: Int): String {
            // If this is an aspect, we'll chop off everything except the aspect name
            var name = name
            if (trimKey) {
                val dividerIndex: Int = name.lastIndexOf('%')
                if (dividerIndex >= 0) {
                    name = name.substring(dividerIndex + 1)
                }
            }
            if (name.length <= maxNameWidth) {
                return name
            }
            val starti = name.length - maxNameWidth + "...".length
            return "..." + name.substring(starti)
        }

        private fun printWithPadding(out: PrintStream, str: String, columnWidth: Int) {
            out.print(str)
            pad(out, columnWidth + 2, str.length)
        }

        private fun printWithPaddingBefore(out: PrintStream, str: String, columnWidth: Int) {
            pad(out, columnWidth, str.length)
            out.print(str)
            pad(out, 2, 0)
        }

        private fun pad(out: PrintStream, columnWidth: Int, consumed: Int) {
            for (i in 0..<columnWidth - consumed) {
                out.print(' ')
            }
        }

        private fun formatLong(number: Long): String? {
            return String.format("%,d", number)
        }

        private fun getConfigurationKey(env: CommandEnvironment, hash: String?): BuildConfigurationKey? {
            if (hash == null) {
                // Use the target configuration
                return env.getSkyframeBuildView().getBuildConfiguration().getKey()
            }

            val candidates: com.google.common.collect.ImmutableList<BuildConfigurationKey?> =
                env.getSkyframeExecutor().getEvaluator().getDoneValues().entrySet().stream()
                    .filter({ e -> e.getKey().functionName().equals(SkyFunctions.BUILD_CONFIGURATION) })
                    .map({ e -> e.getKey() as BuildConfigurationKey? })
                    .filter({ k -> k.getOptions().checksum().startsWith(hash) })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())

            if (candidates.size != 1) {
                env.getReporter().error(null, "ambiguous configuration, use 'blaze config' to list them")
                return null
            }

            return candidates.get(0)
        }

        private fun getMemoryDumpSkyKey(env: CommandEnvironment, memoryMode: MemoryMode): SkyKey? {
            try {
                when (memoryMode.type) {
                    MemorySubjectType.PACKAGE -> {
                        return PackageIdentifier.parse(memoryMode.subject)
                    }

                    MemorySubjectType.STARLARK_MODULE -> {
                        return BzlLoadValue.keyForBuild(Label.parseCanonical(memoryMode.subject))
                    }

                    MemorySubjectType.CONFIGURED_TARGET -> {
                        val labelAndConfig: Array<String?> =
                            memoryMode.subject.split("@".toRegex(), limit = 2).toTypedArray()
                        val configurationKey: BuildConfigurationKey? =
                            getConfigurationKey(env, if (labelAndConfig.size == 2) labelAndConfig[1] else null)
                        return ConfiguredTargetKey.builder()
                            .setConfigurationKey(configurationKey)
                            .setLabel(Label.parseCanonical(labelAndConfig[0]))
                            .build()
                    }
                }
            } catch (e: LabelSyntaxException) {
                env.getReporter().error(null, "Cannot parse label: " + e.getMessage())
                return null
            }

            throw java.lang.IllegalStateException()
        }

        @Throws(java.lang.InterruptedException::class)
        private fun dumpSkyframeMemory(
            env: CommandEnvironment, dumpOptions: DumpOptions, out: PrintStream
        ): java.util.Optional<BlazeCommandResult?> {
            val graph: InMemoryGraph = env.getSkyframeExecutor().getEvaluator().getInMemoryGraph()
            val dumper: SkyframeMemoryDumper =
                SkyframeMemoryDumper(
                    dumpOptions.memory!!.displayMode,
                    dumpOptions.memory!!.needle,
                    env.getRuntime().getRuleClassProvider(),
                    graph,
                    dumpOptions.memory!!.reportTransient,
                    dumpOptions.memory!!.reportConfiguration,
                    dumpOptions.memory!!.reportPrecomputed,
                    dumpOptions.memory!!.reportWorkspaceStatus
                )

            if (dumpOptions.memory!!.collectionMode == MemoryCollectionMode.FULL) {
                try {
                    // FULL mode doesn't have SkyKey as an argument, nor does it need a NodeEntry.
                    dumper.dumpFull(out)
                    return java.util.Optional.empty<BlazeCommandResult?>()
                } catch (e: DumpFailedException) {
                    return java.util.Optional.of<BlazeCommandResult?>(
                        createFailureResult(e.getMessage(), Code.SKYFRAME_MEMORY_DUMP_FAILED)
                    )
                }
            }

            val skyKey: SkyKey? = Companion.getMemoryDumpSkyKey(env, dumpOptions.memory!!)
            if (skyKey == null) {
                return java.util.Optional.of<BlazeCommandResult?>(
                    createFailureResult("Cannot dump Skyframe memory", Code.SKYFRAME_MEMORY_DUMP_FAILED)
                )
            }

            val nodeEntry: NodeEntry? = graph.get(null, QueryableGraph.Reason.OTHER, skyKey)
            if (nodeEntry == null) {
                env.getReporter().error(null, "The requested node is not present.")
                return java.util.Optional.of<BlazeCommandResult?>(
                    createFailureResult(
                        "The requested node is not present", Code.SKYFRAME_MEMORY_DUMP_FAILED
                    )
                )
            }

            val stats: com.google.devtools.build.lib.util.MemoryAccountant.Stats =
                when (dumpOptions.memory!!.collectionMode) {
                    MemoryCollectionMode.DEEP -> dumper.dumpReachable(nodeEntry)
                    MemoryCollectionMode.SHALLOW -> dumper.dumpShallow(nodeEntry)
                    MemoryCollectionMode.TRANSITIVE -> dumper.dumpTransitive(skyKey)
                    MemoryCollectionMode.FULL -> throw java.lang.IllegalStateException()
                }

            when (dumpOptions.memory!!.displayMode) {
                SUMMARY -> out.printf("%d objects, %d bytes retained", stats.getObjectCount(), stats.getMemoryUse())
                COUNT -> SkyframeMemoryDumper.printByClass("", stats.getObjectCountByClass(), out)
                BYTES -> SkyframeMemoryDumper.printByClass("", stats.getMemoryByClass(), out)
            }

            out.println()
            return java.util.Optional.empty<BlazeCommandResult?>()
        }

        @Throws(IOException::class)
        private fun dumpStarlarkHeap(
            workspace: BlazeWorkspace,
            starlarkHeapOutput: InstrumentationOutput,
            path: String?,
            out: PrintStream
        ) {
            val allocationTracker: AllocationTracker? = workspace.getAllocationTracker()
            if (allocationTracker == null) {
                out.println(
                    ("Cannot dump Starlark heap without running in memory tracking mode. "
                            + "Please refer to the user manual for the dump commnd "
                            + "for information how to turn on memory tracking.")
                )
                return
            }
            out.println("Dumping Starlark heap to: " + path)

            // OutputStream is expected to be closed when allocationTracker.dumpStarlarkAllocations()
            // returns.
            allocationTracker.dumpStarlarkAllocations(starlarkHeapOutput.createOutputStream())
        }

        fun createFailureResult(message: String?, detailedCode: Code?): BlazeCommandResult {
            return BlazeCommandResult.failureDetail(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setDumpCommand(FailureDetails.DumpCommand.newBuilder().setCode(detailedCode))
                    .build()
            )
        }
    }
}
