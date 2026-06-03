// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.util.Fingerprint

/**
 * A class that keeps a list of command lines and optional associated parameter file info.
 * 
 * 
 * This class is used by [com.google.devtools.build.lib.exec.SpawnRunner] implementations
 * to expand the command lines into a master argument list + any param files needed to be written.
 */
abstract class CommandLines private constructor() {
    /** A simple tuple of a [CommandLine] and a [ParamFileInfo].  */
    class CommandLineAndParamFileInfo(
        commandLine: com.google.devtools.build.lib.actions.CommandLine,
        paramFileInfo: ParamFileInfo?
    ) {
        @kotlin.jvm.JvmField
        val commandLine: com.google.devtools.build.lib.actions.CommandLine
        val paramFileInfo: ParamFileInfo?

        init {
            this.commandLine = commandLine
            this.paramFileInfo = paramFileInfo
        }
    }

    /**
     * Expands this object into a single primary command line and (0-N) param files. The spawn runner
     * is expected to write these param files prior to execution of an action.
     * 
     * @param inputMetadataProvider the metadata provider to expand composite artifacts
     * @param paramFileBasePath Used to derive param file names. Often the first output of an action
     * @param pathMapper function to map configuration prefixes in output paths to more cache-friendly
     * identifiers
     * @param limits The command line limits the host OS can support.
     * @return The expanded command line and its param files (if any).
     */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun expand(
        inputMetadataProvider: InputMetadataProvider?,
        paramFileBasePath: PathFragment,
        pathMapper: PathMapper,
        limits: CommandLineLimits
    ): ExpandedCommandLines {
        return expand(
            inputMetadataProvider,
            paramFileBasePath,
            limits,
            pathMapper,
            PARAM_FILE_ARG_LENGTH_ESTIMATE
        )
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun expand(
        inputMetadataProvider: InputMetadataProvider?,
        paramFileBasePath: PathFragment,
        limits: CommandLineLimits,
        pathMapper: PathMapper,
        paramFileArgLengthEstimate: Int
    ): ExpandedCommandLines {
        val commandLines: com.google.common.collect.ImmutableList<CommandLineAndParamFileInfo> = unpack()
        val arguments: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        val paramFiles: java.util.ArrayList<ParamFileActionInput?> =
            java.util.ArrayList<ParamFileActionInput?>(commandLines.size())
        val conservativeMaxLength: Int = limits.maxLength - commandLines.size() * paramFileArgLengthEstimate
        var cmdLineLength = 0
        // We name based on the output, starting at <output>-0.params and then incrementing
        var paramFileNameSuffix = 0
        for (pair in commandLines) {
            val commandLine: com.google.devtools.build.lib.actions.CommandLine = pair.commandLine
            val paramFileInfo: ParamFileInfo? = pair.paramFileInfo
            val chunk: ArgChunk = commandLine.expand(inputMetadataProvider, pathMapper)
            if (paramFileInfo == null) {
                arguments.addAll(chunk.arguments(pathMapper))
                cmdLineLength += chunk.totalArgLength(pathMapper)
            } else {
                var useParamFile = true
                if (!paramFileInfo.always()) {
                    val tentativeCmdLineLength: Int = cmdLineLength + chunk.totalArgLength(pathMapper)
                    if (tentativeCmdLineLength <= conservativeMaxLength) {
                        arguments.addAll(chunk.arguments(pathMapper))
                        cmdLineLength = tentativeCmdLineLength
                        useParamFile = false
                    }
                }
                if (useParamFile) {
                    val paramFileExecPath: PathFragment =
                        ParameterFile.derivePath(paramFileBasePath, java.lang.Integer.toString(paramFileNameSuffix))
                    ++paramFileNameSuffix

                    val paramArg: String =
                        SingleStringArgFormatter.format(
                            paramFileInfo.getFlagFormatString(),
                            pathMapper.map(paramFileExecPath).getPathString()
                        )
                    arguments.add(paramArg)
                    cmdLineLength += paramArg.length() + 1

                    if (paramFileInfo.flagsOnly()) {
                        // Move just the flags into the file, and keep the positional parameters on the command
                        // line.
                        paramFiles.add(
                            ParamFileActionInput(
                                paramFileExecPath,
                                ParameterFile.flagsOnly(chunk.arguments(pathMapper)),
                                paramFileInfo.getFileType()
                            )
                        )
                        for (positionalArg in ParameterFile.nonFlags(chunk.arguments(pathMapper))) {
                            arguments.add(positionalArg)
                            cmdLineLength += positionalArg.length() + 1
                        }
                    } else {
                        paramFiles.add(
                            ParamFileActionInput(
                                paramFileExecPath, chunk.arguments(pathMapper), paramFileInfo.getFileType()
                            )
                        )
                    }
                }
            }
        }
        return ExpandedCommandLines(arguments.build(), paramFiles)
    }

    /** Variation of [.allArguments] that supports output path stripping.  */
    /**
     * Returns all arguments, including ones inside of param files.
     * 
     * 
     * Suitable for debugging and printing messages to users. This expands all command lines, so it
     * is potentially expensive.
     */
    @kotlin.jvm.JvmOverloads
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun allArguments(pathMapper: PathMapper? = PathMapper.Companion.NOOP): com.google.common.collect.ImmutableList<String?> {
        val arguments: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        for (pair in unpack()) {
            arguments.addAll(pair.commandLine.arguments( /* inputMetadataProvider= */null, pathMapper))
        }
        return arguments.build()
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun addToFingerprint(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        effectiveOutputPathsMode: OutputPathsMode?,
        fingerprint: Fingerprint
    ) {
        val commandLines: com.google.common.collect.ImmutableList<CommandLineAndParamFileInfo> = unpack()
        for (pair in commandLines) {
            val commandLine: com.google.devtools.build.lib.actions.CommandLine = pair.commandLine
            val paramFileInfo: ParamFileInfo? = pair.paramFileInfo
            commandLine.addToFingerprint(
                actionKeyContext, inputMetadataProvider, effectiveOutputPathsMode, fingerprint
            )
            if (paramFileInfo != null) {
                addParamFileInfoToFingerprint(paramFileInfo, fingerprint)
            }
        }
    }

    /**
     * Expanded command lines.
     * 
     * 
     * The spawn runner implementation is expected to ensure the param files are available once the
     * spawn is executed.
     */
    class ExpandedCommandLines internal constructor(
        arguments: com.google.common.collect.ImmutableList<String?>?,
        paramFiles: MutableList<ParamFileActionInput?>?
    ) {
        private val arguments: com.google.common.collect.ImmutableList<String?>?
        @kotlin.jvm.JvmField
        private val paramFiles: MutableList<ParamFileActionInput?>?

        init {
            this.arguments = arguments
            this.paramFiles = paramFiles
        }

        /** Returns the primary command line of the command.  */
        fun arguments(): com.google.common.collect.ImmutableList<String?>? {
            return arguments
        }

        /** Returns the param file action inputs needed to execute the command.  */
        fun getParamFiles(): MutableList<ParamFileActionInput?>? {
            return paramFiles
        }
    }

    /** An in-memory param file virtual action input.  */
    class ParamFileActionInput(
        paramFileExecPath: PathFragment,
        arguments: Iterable<String?>?,
        type: ParameterFileType
    ) : VirtualActionInput() {
        private val paramFileExecPath: PathFragment
        private val arguments: Iterable<String?>?
        private val type: ParameterFileType

        init {
            this.paramFileExecPath = paramFileExecPath
            this.arguments = arguments
            this.type = type
        }

        @Throws(IOException::class)
        public override fun writeTo(out: java.io.OutputStream?) {
            ParameterFile.writeParameterFile(out, arguments, type)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(IOException::class)
        public override fun atomicallyWriteTo(outputPath: Path): ByteArray? {
            // This is needed for internal path wrangling reasons :(
            return super.atomicallyWriteTo(outputPath)
        }

        override fun getExecPathString(): String {
            return paramFileExecPath.getPathString()
        }

        override fun getExecPath(): PathFragment {
            return paramFileExecPath
        }

        fun getArguments(): Iterable<String?>? {
            return arguments
        }
    }

    /**
     * Unpacks the optimized storage format into a list of [CommandLineAndParamFileInfo].
     * 
     * 
     * The returned [ImmutableList] and its [CommandLineAndParamFileInfo] elements are
     * not part of the optimized storage representation. Retaining them in an action would defeat the
     * memory optimizations made by [CommandLines].
     */
    abstract fun unpack(): com.google.common.collect.ImmutableList<CommandLineAndParamFileInfo>

    /**
     * Builder for [CommandLines].
     * 
     * 
     * Attempts to build the most memory-efficient [CommandLines] instance possible. Most
     * command lines are composed of 1-3 parts. Additionally, the first part is typically just an
     * executable or shell command and does not have an associated params file. If both of these
     * criteria are met, memory is saved by using one of the array-free subclasses. Otherwise, uses
     * [NPartCommandLines] which handles any arbitrary case.
     */
    class Builder {
        private var part1: Any? = null // Set to null when we need to use NPartCommandLines.
        private var part2: Any? = null
        private var part2ParamFileInfo: ParamFileInfo? = null
        private var part3: Any? = null
        private var part3ParamFileInfo: ParamFileInfo? = null
        private var parts = 0
        private val commandLines: MutableList<Any?> = java.util.ArrayList<Any?>()

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addSingleArgument(argument: Any?): Builder {
            com.google.common.base.Preconditions.checkArgument(
                argument !is ParamFileInfo && argument !is CommandLineAndParamFileInfo,
                argument
            )
            return addInternal(argument, null)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addCommandLine(commandLine: com.google.devtools.build.lib.actions.CommandLine?): Builder {
            return addInternal(commandLine, null)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addCommandLine(
            commandLine: com.google.devtools.build.lib.actions.CommandLine?,
            paramFileInfo: ParamFileInfo?
        ): Builder {
            return addInternal(commandLine, paramFileInfo)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addCommandLine(pair: CommandLineAndParamFileInfo): Builder {
            return addInternal(pair.commandLine, pair.paramFileInfo)
        }

        private fun addInternal(part: Any?, paramFileInfo: ParamFileInfo?): Builder {
            parts++
            if (parts == 1) {
                if (paramFileInfo == null) {
                    part1 = part
                }
            } else if (parts == 2) {
                part2 = part
                part2ParamFileInfo = paramFileInfo
            } else if (parts == 3) {
                part3 = part
                part3ParamFileInfo = paramFileInfo
            } else if (parts == 4) {
                part1 = null // Destined to build an NPartCommandLines.
            }
            commandLines.add(part)
            if (paramFileInfo != null) {
                commandLines.add(paramFileInfo)
            }
            return this
        }

        fun build(): CommandLines {
            if (part1 == null) {
                return NPartCommandLines(commandLines.toArray())
            }
            if (parts == 1) {
                return OnePartCommandLines(part1)
            }
            if (parts == 2) {
                return TwoPartCommandLines(part1, part2, part2ParamFileInfo)
            }
            if (part2ParamFileInfo == null && part3ParamFileInfo == null) {
                return ThreePartCommandLinesWithoutParamsFiles(part1, part2, part3)
            }
            return ThreePartCommandLines(part1, part2, part2ParamFileInfo, part3, part3ParamFileInfo)
        }
    }

    private class OnePartCommandLines(private val part1: Any?) : CommandLines() {
        override fun unpack(): com.google.common.collect.ImmutableList<CommandLineAndParamFileInfo?> {
            return com.google.common.collect.ImmutableList.of<CommandLineAndParamFileInfo?>(
                CommandLineAndParamFileInfo(toExecutableCommandLine(part1), null)
            )
        }
    }

    private class TwoPartCommandLines(
        private val part1: Any?,
        private val part2: Any?,
        part2ParamFileInfo: ParamFileInfo?
    ) : CommandLines() {
        private val part2ParamFileInfo: ParamFileInfo?

        init {
            this.part2ParamFileInfo = part2ParamFileInfo
        }

        override fun unpack(): com.google.common.collect.ImmutableList<CommandLineAndParamFileInfo?> {
            return com.google.common.collect.ImmutableList.of<CommandLineAndParamFileInfo?>(
                CommandLineAndParamFileInfo(toExecutableCommandLine(part1), null),
                CommandLineAndParamFileInfo(toNonExecutableCommandLine(part2), part2ParamFileInfo)
            )
        }
    }

    private class ThreePartCommandLinesWithoutParamsFiles(
        private val part1: Any?,
        private val part2: Any?,
        private val part3: Any?
    ) : CommandLines() {
        override fun unpack(): com.google.common.collect.ImmutableList<CommandLineAndParamFileInfo?> {
            return com.google.common.collect.ImmutableList.of<CommandLineAndParamFileInfo?>(
                CommandLineAndParamFileInfo(toExecutableCommandLine(part1), null),
                CommandLineAndParamFileInfo(toNonExecutableCommandLine(part2), null),
                CommandLineAndParamFileInfo(toNonExecutableCommandLine(part3), null)
            )
        }
    }

    private class ThreePartCommandLines(
        private val part1: Any?,
        private val part2: Any?,
        part2ParamFileInfo: ParamFileInfo?,
        part3: Any?,
        part3ParamFileInfo: ParamFileInfo?
    ) : CommandLines() {
        private val part2ParamFileInfo: ParamFileInfo?
        private val part3: Any?
        private val part3ParamFileInfo: ParamFileInfo?

        init {
            this.part2ParamFileInfo = part2ParamFileInfo
            this.part3 = part3
            this.part3ParamFileInfo = part3ParamFileInfo
        }

        override fun unpack(): com.google.common.collect.ImmutableList<CommandLineAndParamFileInfo?> {
            return com.google.common.collect.ImmutableList.of<CommandLineAndParamFileInfo?>(
                CommandLineAndParamFileInfo(toExecutableCommandLine(part1), null),
                CommandLineAndParamFileInfo(toNonExecutableCommandLine(part2), part2ParamFileInfo),
                CommandLineAndParamFileInfo(toNonExecutableCommandLine(part3), part3ParamFileInfo)
            )
        }
    }

    private class NPartCommandLines(
        /**
         * Stored as an `Object[]` to save memory. Elements in this array are either:
         * 
         * 
         *  * A [CommandLine], optionally followed by a [ParamFileInfo].
         *  * An arbitrary [Object] to be wrapped in a [SingletonCommandLine].
         * 
         */
        private val commandLines: Array<Any?>
    ) : CommandLines() {
        override fun unpack(): com.google.common.collect.ImmutableList<CommandLineAndParamFileInfo?> {
            val result: com.google.common.collect.ImmutableList.Builder<CommandLineAndParamFileInfo?> =
                com.google.common.collect.ImmutableList.builder<CommandLineAndParamFileInfo?>()
            var i = 0
            while (i < commandLines.size) {
                val obj = commandLines[i]
                val commandLine: com.google.devtools.build.lib.actions.CommandLine?
                var paramFileInfo: ParamFileInfo? = null

                if (obj is com.google.devtools.build.lib.actions.CommandLine) {
                    commandLine = obj
                    if (i + 1 < commandLines.size && commandLines[i + 1] is ParamFileInfo) {
                        paramFileInfo = commandLines[++i] as ParamFileInfo?
                    }
                } else {
                    commandLine = SingletonCommandLine(obj,  /* hasExecutablePath= */i == 0)
                }

                result.add(CommandLineAndParamFileInfo(commandLine, paramFileInfo))
                i++
            }
            return result.build()
        }
    }

    private class SingletonCommandLine(private val arg: Any?, private val hasExecutablePath: Boolean) :
        AbstractCommandLine() {
        override fun arguments(): Iterable<String?> {
            return arguments(null, PathMapper.Companion.NOOP)
        }

        override fun arguments(
            inputMetadataProvider: InputMetadataProvider?, pathMapper: PathMapper
        ): Iterable<String?> {
            return com.google.common.collect.ImmutableList.of<E?>(
                when (arg) {
                    -> ps.expand({ execPath: PathFragment? -> pathMapper.map(execPath) })
                    -> {
                        val pathFragment: PathFragment = PathFragment.create(s)
                        if (!pathFragment.getPathString().equals(s)) {
                            s
                        }
                        pathMapper.map(pathFragment).getPathString()
                    }

                    else -> CommandLineItem.Companion.expandToCommandLine(arg)
                }
            )
        }
    }

    companion object {
        // A (hopefully) conservative estimate of how much long each param file arg would be
        // eg. the length of '@path/to/param_file'.
        private const val PARAM_FILE_ARG_LENGTH_ESTIMATE = 512
        private val PARAM_FILE_UUID: UUID = UUID.fromString("106c1389-88d7-4cc1-8f05-f8a61fd8f7b1")

        private fun addParamFileInfoToFingerprint(
            paramFileInfo: ParamFileInfo, fingerprint: Fingerprint
        ) {
            fingerprint.addUUID(PARAM_FILE_UUID)
            fingerprint.addString(paramFileInfo.getFlagFormatString())
            fingerprint.addString(paramFileInfo.getFileType().toString())
        }

        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.actions.CommandLines.Builder()
        }

        /** Returns an instance with a single command line.  */
        fun of(commandLine: com.google.devtools.build.lib.actions.CommandLine?): CommandLines {
            return OnePartCommandLines(commandLine)
        }

        /** Returns an instance with a single trivial command line.  */
        fun of(args: com.google.common.collect.ImmutableList<String?>): CommandLines {
            return OnePartCommandLines(com.google.devtools.build.lib.actions.CommandLine.Companion.of(args))
        }

        fun concat(
            commandLine: com.google.devtools.build.lib.actions.CommandLine?,
            commandLines: CommandLines
        ): CommandLines {
            val builder = builder()
            builder.addCommandLine(commandLine)
            for (pair in commandLines.unpack()) {
                builder.addCommandLine(pair)
            }
            return builder.build()
        }

        private fun toExecutableCommandLine(obj: Any?): com.google.devtools.build.lib.actions.CommandLine {
            return toCommandLine(obj,  /* hasExecutablePath= */true)
        }

        private fun toNonExecutableCommandLine(obj: Any?): com.google.devtools.build.lib.actions.CommandLine {
            return toCommandLine(obj,  /* hasExecutablePath= */false)
        }

        private fun toCommandLine(
            obj: Any?,
            hasExecutablePath: Boolean
        ): com.google.devtools.build.lib.actions.CommandLine {
            return if (obj is com.google.devtools.build.lib.actions.CommandLine)
                obj
            else
                SingletonCommandLine(obj, hasExecutablePath)
        }
    }
}
