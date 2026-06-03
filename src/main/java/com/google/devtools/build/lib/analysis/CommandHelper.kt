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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/**
 * Provides shared functionality for parameterized command-line launching. Also used by [ ].
 * 
 * 
 * Two largely independent separate sets of functionality are provided: 1- string interpolation
 * for `$(location[s] ...)` and `$(MakeVariable)` 2- a utility to build potentially
 * large command lines (presumably made of multiple commands), that if presumed too large for the
 * kernel's taste can be dumped into a shell script that will contain the same commands, at which
 * point the shell script is added to the list of inputs.
 */
class CommandHelper private constructor(
    ruleContext: RuleContext,
    toolsList: com.google.common.collect.ImmutableList<Iterable<out TransitiveInfoCollection?>>,
    labelMap: com.google.common.collect.ImmutableMap<Label?, out Iterable<Artifact?>?>
) {
    /**
     * Builder class to assist with creating an instance of [CommandHelper]. The Builder can
     * optionally add additional tools as dependencies, and a map of labels to be resolved.
     */
    class Builder private constructor(ruleContext: RuleContext) {
        private val ruleContext: RuleContext
        private val toolDependencies: com.google.common.collect.ImmutableList.Builder<Iterable<out TransitiveInfoCollection?>?> =
            com.google.common.collect.ImmutableList.builder<Iterable<out TransitiveInfoCollection?>?>()
        private val labelMap: com.google.common.collect.ImmutableMap.Builder<Label?, Iterable<Artifact?>?> =
            com.google.common.collect.ImmutableMap.builder<Label?, Iterable<Artifact?>?>()

        init {
            this.ruleContext = ruleContext
        }

        /**
         * Adds tools, as a set of executable binaries, by fetching them from the given attribute on the
         * `ruleContext`. Populates manifests, remoteRunfiles and label map where required.
         */
        fun addToolDependencies(toolAttributeName: String?): Builder {
            val dependencies: MutableList<out TransitiveInfoCollection?> =
                ruleContext.getPrerequisites(toolAttributeName)
            return addToolDependencies(dependencies)
        }

        /**
         * Adds tools, as a set of executable binaries. Populates manifests, remoteRunfiles and label
         * map where required.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addToolDependencies(
            toolDependencies: Iterable<out TransitiveInfoCollection?>
        ): Builder {
            this.toolDependencies.add(toolDependencies)
            return this
        }

        /** Adds files to set of known files of label. Used for resolving $(location) variables.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addLabelMap(labelMap: MutableMap<Label?, out Iterable<Artifact?>?>): Builder {
            this.labelMap.putAll(labelMap)
            return this
        }

        /** Returns the built [CommandHelper].  */
        fun build(): CommandHelper {
            return CommandHelper(ruleContext, toolDependencies.build(), labelMap.buildOrThrow())
        }
    }

    /**
     * Use labelMap for heuristically expanding labels (does not include "outs") This is similar to
     * heuristic location expansion in LocationExpander and should be kept in sync.
     */
    private val labelMap: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>

    /** The ruleContext this helper works on  */
    private val ruleContext: RuleContext

    /** Output executable files from the 'tools' attribute.  */
    private val resolvedTools: NestedSet<Artifact?>?

    fun getResolvedTools(): NestedSet<Artifact?>? {
        return resolvedTools
    }

    fun getLabelMap(): com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?> {
        return labelMap
    }

    /**
     * Creates a [CommandHelper].
     * 
     * @param toolsList resolves sets of tools into set of executable binaries. Populates manifests,
     * remoteRunfiles and label map where required.
     * @param labelMap adds files to set of known files of label. Used for resolving $(location)
     * variables.
     */
    init {
        this.ruleContext = ruleContext

        val resolvedToolsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        val tempLabelMap: MutableMap<Label?, MutableCollection<Artifact?>> =
            HashMap<Label?, MutableCollection<Artifact?>>()

        for (entry in labelMap.entrySet()) {
            com.google.common.collect.Iterables.addAll<Artifact?>(
                mapGet(tempLabelMap, entry.getKey()),
                entry.getValue()
            )
        }

        for (tools in toolsList) {
            for (dep in tools) { // (Note: exec configuration)

                val tool: FilesToRunProvider? = dep.getProvider(FilesToRunProvider::class.java)
                if (tool == null) {
                    continue
                }

                val filesToBuild: NestedSet<Artifact?> = dep.getProvider(FileProvider::class.java).getFilesToBuild()
                resolvedToolsBuilder.addTransitive(filesToBuild)

                val executableArtifact: Artifact? = tool.getExecutable()
                val label: Label? = AliasProvider.Companion.getDependencyLabel(dep)

                // If the label has an executable artifact add that to the multimaps.
                if (executableArtifact != null) {
                    mapGet(tempLabelMap, label).add(executableArtifact)
                    // Also send the runfiles if needed.
                    val runfilesSupport: RunfilesSupport? = tool.getRunfilesSupport()
                    if (runfilesSupport != null) {
                        resolvedToolsBuilder.add(runfilesSupport.getRunfilesTreeArtifact())
                        // It's possible that getExecutable() returns an artifact that is not in
                        // getFilesToBuild(). It is not nice, but it happens
                        // (see test_executable_without_default_files)
                        resolvedToolsBuilder.add(tool.getRunfilesSupport().getExecutable())
                    }
                } else {
                    // Map all depArtifacts to the respective label using the multimaps.
                    mapGet(tempLabelMap, label).addAll(filesToBuild.toList())
                }
            }
        }

        this.resolvedTools = resolvedToolsBuilder.build()
        val labelMapBuilder: com.google.common.collect.ImmutableMap.Builder<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?> =
            com.google.common.collect.ImmutableMap.builder<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>()
        for (entry in tempLabelMap.entrySet()) {
            labelMapBuilder.put(
                entry.getKey(),
                com.google.common.collect.ImmutableList.copyOf<Artifact?>(entry.getValue())
            )
        }
        this.labelMap = labelMapBuilder.buildOrThrow()
    }

    /** Resolves a command, and expands known locations for $(location) variables.  */
    @Deprecated("") // Only exists to support a legacy Starlark API.
    fun resolveCommandAndExpandLabels(
        command: String?, attribute: String?, allowDataInLabel: Boolean
    ): String? {
        var command = command
        val expander: LocationExpander?
        if (allowDataInLabel) {
            expander = LocationExpander.Companion.withExecPathsAndData(ruleContext, labelMap)
        } else {
            expander = LocationExpander.Companion.withExecPaths(ruleContext, labelMap)
        }
        if (attribute != null) {
            command = expander.expandAttribute(attribute, command)
        } else {
            command = expander.expand(command)
        }
        return command
    }

    /**
     * Expands labels occurring in the string "expr" in the rule 'cmd'. Each label must be valid, be a
     * declared prerequisite, and expand to a unique path.
     * 
     * 
     * If the expansion fails, an attribute error is reported and the original expression is
     * returned.
     */
    fun expandLabelsHeuristically(expr: String?): String? {
        try {
            return LabelExpander.expand(expr, labelMap, ruleContext.getLabel())
        } catch (nuee: LabelExpander.NotUniqueExpansionException) {
            ruleContext.attributeError("cmd", nuee.getMessage())
            return expr
        }
    }

    /**
     * Builds the set of command-line arguments using the specified shell path. Creates a bash script
     * if the command line is longer than the allowed maximum [.maxCommandLength]. Fixes up the
     * input artifact list with the created bash script when required.
     */
    fun buildCommandLine(
        command: String,
        inputs: NestedSetBuilder<Artifact?>,
        constructor: CommandConstructor,
        executionOs: OS?
    ): com.google.common.collect.ImmutableList<String?> {
        val argvAndScriptFile: Pair<com.google.common.collect.ImmutableList<String?>?, Artifact?> =
            buildCommandLineMaybeWithScriptFile(ruleContext, command, constructor, executionOs)
        if (argvAndScriptFile.second != null) {
            inputs.add(argvAndScriptFile.second)
        }
        return argvAndScriptFile.first
    }

    /**
     * Builds the set of command-line arguments. Creates a bash script if the command line is longer
     * than the allowed maximum [.maxCommandLength]. Fixes up the input artifact list with the
     * created bash script when required.
     */
    fun buildCommandLine(
        command: String, inputs: MutableList<Artifact?>, constructor: CommandConstructor, executionOs: OS?
    ): MutableList<String?> {
        val argvAndScriptFile: Pair<com.google.common.collect.ImmutableList<String?>?, Artifact?> =
            buildCommandLineMaybeWithScriptFile(ruleContext, command, constructor, executionOs)
        if (argvAndScriptFile.second != null) {
            inputs.add(argvAndScriptFile.second)
        }
        return argvAndScriptFile.first
    }

    companion object {
        /**
         * Returns a new [Builder] to create a [CommandHelper] based on the given [ ].
         */
        fun builder(ruleContext: RuleContext): Builder {
            return com.google.devtools.build.lib.analysis.CommandHelper.Builder(ruleContext)
        }

        // Returns the value in the specified corresponding to 'key', creating and
        // inserting an empty container if absent.  We use Map not Multimap because
        // we need to distinguish the cases of "empty value" and "absent key".
        private fun mapGet(
            map: MutableMap<Label?, MutableCollection<Artifact?>>,
            key: Label?
        ): MutableCollection<Artifact?> {
            // We use sets not lists, because it's conceivable that the same artifact
            // could appear twice, e.g. in "srcs" and "deps".
            return map.computeIfAbsent(
                key,
                java.util.function.Function { k: Label? -> com.google.common.collect.Sets.newHashSet<Artifact?>() })
        }

        private val maxCommandLengthOverride = intArrayOf(-1)

        @com.google.common.annotations.VisibleForTesting
        fun setMaxCommandLengthForTesting(length: OptionalInt) {
            maxCommandLengthOverride[0] = if (length.isPresent()) length.getAsInt() else -1
        }

        /**
         * Maximum total command-line length, in bytes, not counting "/bin/bash -c ". If the command is
         * very long, then we write the command to a script file, to avoid overflowing any limits on
         * command-line length. For short commands, we just use /bin/bash -c command.
         * 
         * 
         * Maximum command line length on Windows is 32767[1], but for cmd.exe it is 8192[2]. [1]
         * https://msdn.microsoft.com/en-us/library/ms682425(VS.85).aspx [2]
         * https://support.microsoft.com/en-us/kb/830473.
         */
        @com.google.common.annotations.VisibleForTesting
        fun maxCommandLength(executionOs: OS?): Int {
            if (maxCommandLengthOverride[0] != -1) {
                return maxCommandLengthOverride[0]
            }
            return if (executionOs === OS.WINDOWS) 8000 else 64000
        }

        private fun buildCommandLineMaybeWithScriptFile(
            ruleContext: RuleContext?, command: String, constructor: CommandConstructor, executionOs: OS?
        ): Pair<com.google.common.collect.ImmutableList<String?>?, Artifact?> {
            val argv: com.google.common.collect.ImmutableList<String?>?
            var scriptFileArtifact: Artifact? = null
            if (command.length() <= maxCommandLength(executionOs)) {
                argv = constructor.asExecArgv(command)
            } else {
                // Use script file.
                scriptFileArtifact = constructor.commandAsScript(ruleContext, command)
                argv = constructor.asExecArgv(scriptFileArtifact)
            }
            return Pair.of(argv, scriptFileArtifact)
        }

        /**
         * If `command` is too long, creates a helper shell script that runs that command.
         * 
         * 
         * Returns the [Artifact] corresponding to that script.
         * 
         * 
         * Otherwise, when `command` is shorter than the platform's shell's command length limit,
         * this method does nothing and returns null.
         */
        fun commandHelperScriptMaybe(
            ruleCtx: RuleContext?, command: String, constructor: CommandConstructor, executionOs: OS?
        ): Artifact? {
            if (command.length() <= maxCommandLength(executionOs)) {
                return null
            } else {
                return constructor.commandAsScript(ruleCtx, command)
            }
        }

        /** Returns the path to the shell for an action with the given execution requirements.  */
        private fun shellPath(
            executionInfo: MutableMap<String?, String?>, shExecutable: PathFragment?
        ): PathFragment? {
            // Use vanilla /bin/bash for actions running on mac machines.
            return if (executionInfo.containsKey(ExecutionRequirements.REQUIRES_DARWIN))
                PathFragment.create("/bin/bash")
            else
                shExecutable
        }

        fun buildBashCommandConstructor(
            executionInfo: MutableMap<String?, String?>, shExecutable: PathFragment?, scriptPostFix: String?
        ): BashCommandConstructor {
            return BashCommandConstructor(shellPath(executionInfo, shExecutable), scriptPostFix)
        }

        fun buildWindowsBatchCommandConstructor(
            scriptPostFix: String?
        ): WindowsBatchCommandConstructor {
            return WindowsBatchCommandConstructor(scriptPostFix)
        }

        fun buildWindowsPowershellCommandConstructor(
            scriptPostFix: String?
        ): WindowsPowershellCommandConstructor {
            return WindowsPowershellCommandConstructor(scriptPostFix)
        }
    }
}
