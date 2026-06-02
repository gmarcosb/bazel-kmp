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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.buildtool.PathPrettyPrinter

/**
 * Encapsulates information for launching the command specified by a run invocation.
 * 
 * 
 * Notably, this class handles per-platform command-line formatting (windows vs unix).
 */
internal class RunCommandLine private constructor(
    args: com.google.common.collect.ImmutableList<String?>,
    prettyArgs: com.google.common.collect.ImmutableList<String?>,
    residue: com.google.common.collect.ImmutableList<String?>,
    runUnderPrefix: String?,
    prettyRunUnderPrefix: String?,
    runEnvironment: com.google.common.collect.ImmutableSortedMap<String?, String?>?,
    environmentVariablesToClear: com.google.common.collect.ImmutableSortedSet<String?>?,
    workingDir: com.google.devtools.build.lib.vfs.Path,
    isTestTarget: Boolean
) {
    private val args: com.google.common.collect.ImmutableList<String?>
    private val prettyArgs: com.google.common.collect.ImmutableList<String?>
    private val residue: com.google.common.collect.ImmutableList<String?>
    private val runUnderPrefix: String?
    private val prettyRunUnderPrefix: String?

    private val runEnvironment: com.google.common.collect.ImmutableSortedMap<String?, String?>?
    private val environmentVariablesToClear: com.google.common.collect.ImmutableSortedSet<String?>?
    private val workingDir: com.google.devtools.build.lib.vfs.Path

    val isTestTarget: Boolean

    init {
        this.args = args
        this.prettyArgs = prettyArgs
        this.residue = residue
        this.runUnderPrefix = runUnderPrefix
        this.prettyRunUnderPrefix = prettyRunUnderPrefix
        this.runEnvironment = runEnvironment
        this.environmentVariablesToClear = environmentVariablesToClear
        this.workingDir = workingDir
        this.isTestTarget = isTestTarget
    }

    fun getWorkingDir(): com.google.devtools.build.lib.vfs.Path {
        return workingDir
    }

    val environment: com.google.common.collect.ImmutableSortedMap<String?, String?>?
        get() = runEnvironment

    fun getEnvironmentVariablesToClear(): com.google.common.collect.ImmutableSortedSet<String?>? {
        return environmentVariablesToClear
    }

    /**
     * Returns a console-friendly (including relative paths) representation of the command line.
     * 
     * 
     * Arguments from the `run` command line are omitted as to avoid possibly leaking
     * sensitive user-provided information in logging, BEP, etc.
     */
    fun getPrettyArgs(runOmitRunArgs: Boolean): String {
        val result: java.lang.StringBuilder = java.lang.StringBuilder()
        if (prettyRunUnderPrefix != null) {
            result.append(prettyRunUnderPrefix).append(" ")
        }
        for (i in prettyArgs.indices) {
            if (i > 0) {
                result.append(" ")
            }
            result.append(ShellEscaper.escapeString(prettyArgs.get(i)))
        }
        if (!residue.isEmpty()) {
            if (runOmitRunArgs) {
                result.append(" <args omitted>")
            } else {
                for (i in residue.indices) {
                    if (i < residue.size()) {
                        result.append(" ")
                    }
                    result.append(ShellEscaper.escapeString(residue.get(i)))
                }
            }
        }
        return result.toString()
    }

    fun requiresShExecutable(): Boolean {
        return com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS || runUnderPrefix != null
    }

    /** Returns the command arguments including residue.  */
    fun getArgs(shExecutable: String?): com.google.common.collect.ImmutableList<String?>? {
        return formatter()
            .formatArgv(
                shExecutable,
                runUnderPrefix,
                com.google.common.collect.ImmutableList.builder<String?>().addAll(args).addAll(residue).build()
            )
    }

    /**
     * Returns the command arguments without residue (extra arguments from the run invocation's
     * command line). This is intended to be used in places where we don't want to include the residue
     * in case it contains sensitive information.
     */
    fun getArgsWithoutResidue(shExecutable: String?): com.google.common.collect.ImmutableList<String?>? {
        return formatter().formatArgv(shExecutable, runUnderPrefix, args)
    }

    /**
     * Returns the script form of the command, to be used as the contents of output file in
     * --script_path mode.
     */
    fun getScriptForm(shExecutable: String?): String? {
        return formatter()
            .getScriptForm(
                shExecutable,
                workingDir.getPathString(),
                environmentVariablesToClear,
                runEnvironment,
                runUnderPrefix,
                com.google.common.collect.ImmutableList.builder<String?>().addAll(args).addAll(residue).build()
            )
    }

    private interface Formatter {
        fun formatArgv(
            shExecutable: String?, runUnderPrefix: String?, args: com.google.common.collect.ImmutableList<String?>?
        ): com.google.common.collect.ImmutableList<String?>?

        fun getScriptForm(
            shExecutable: String?,
            workingDir: String?,
            environmentVarsToUnset: com.google.common.collect.ImmutableSortedSet<String?>?,
            environment: com.google.common.collect.ImmutableSortedMap<String?, String?>?,
            runUnderPrefix: String?,
            args: com.google.common.collect.ImmutableList<String?>?
        ): String?
    }

    @com.google.common.annotations.VisibleForTesting
    internal class LinuxFormatter : Formatter {
        override fun formatArgv(
            shExecutable: String?,
            runUnderPrefix: String?,
            args: com.google.common.collect.ImmutableList<String?>
        ): com.google.common.collect.ImmutableList<String?> {
            com.google.common.base.Preconditions.checkArgument(shExecutable != null, "shExecutable must be non-null")
            val command: java.lang.StringBuilder = java.lang.StringBuilder()
            if (runUnderPrefix != null) {
                command.append(runUnderPrefix).append(" ")
            }
            for (i in args.indices) {
                if (i > 0) {
                    command.append(" ")
                }
                command.append(ShellEscaper.escapeString(args.get(i)))
            }
            return com.google.common.collect.ImmutableList.of<String?>(shExecutable, "-c", command.toString())
        }

        override fun getScriptForm(
            shExecutable: String?,
            workingDir: String?,
            environmentVarsToUnset: com.google.common.collect.ImmutableSortedSet<String?>,
            environment: com.google.common.collect.ImmutableSortedMap<String?, String?>,
            runUnderPrefix: String?,
            args: com.google.common.collect.ImmutableList<String?>
        ): String {
            val unsetEnv: String? =
                environmentVarsToUnset.stream().map<String?>(java.util.function.Function { v: String? -> "-u " + v })
                    .collect(Collectors.joining(" \\\n    "))
            val setEnv: String? =
                environment.entrySet().stream()
                    .map<String?>(
                        java.util.function.Function { kv: MutableMap.MutableEntry<String?, String?>? ->
                            (ShellEscaper.escapeString(kv.getKey())
                                    + "="
                                    + ShellEscaper.escapeString(kv.getValue()))
                        })
                    .collect(Collectors.joining(" \\\n    "))
            val commandLine = getCommandLine(shExecutable, runUnderPrefix, args)

            val result: java.lang.StringBuilder = java.lang.StringBuilder()
            result.append("#!").append(shExecutable).append("\n")
            result.append("cd ").append(ShellEscaper.escapeString(workingDir)).append(" && \\\n")
            result.append("  exec env \\\n")
            result.append("    ").append(unsetEnv).append(" \\\n")
            result.append("    ").append(setEnv).append(" \\\n")
            result.append("  ").append(commandLine).append(" \"$@\"\n")

            return result.toString()
        }

        companion object {
            private fun getCommandLine(
                shExecutable: String?, runUnderPrefix: String?, args: com.google.common.collect.ImmutableList<String?>
            ): String {
                val command: java.lang.StringBuilder = java.lang.StringBuilder()
                if (runUnderPrefix != null) {
                    command.append(runUnderPrefix).append(" ")
                }
                for (i in args.indices) {
                    if (i > 0) {
                        command.append(" ")
                    }
                    command.append(ShellEscaper.escapeString(args.get(i)))
                }

                if (runUnderPrefix == null) {
                    return command.toString()
                } else {
                    return shExecutable + " -c " + ShellEscaper.escapeString(command.toString())
                }
            }
        }
    }

    @com.google.common.annotations.VisibleForTesting
    internal class WindowsFormatter : Formatter {
        override fun formatArgv(
            shExecutable: String?,
            runUnderPrefix: String?,
            args: com.google.common.collect.ImmutableList<String?>
        ): com.google.common.collect.ImmutableList<String?> {
            if (runUnderPrefix != null) {
                com.google.common.base.Preconditions.checkArgument(
                    shExecutable != null, "shExecutable must be non-null when --run_under is used"
                )
                val command: java.lang.StringBuilder = java.lang.StringBuilder()
                command.append(runUnderPrefix).append(" ")
                for (i in args.indices) {
                    if (i > 0) {
                        command.append(" ")
                    }
                    command.append(ShellEscaper.escapeString(args.get(i)))
                }
                return com.google.common.collect.ImmutableList.of<String?>(
                    shExecutable, "-c", ShellUtils.windowsEscapeArg(command.toString())
                )
            }

            val result: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            for (i in args.indices) {
                if (i == 0) {
                    // All but the first element in `cmdLine` have to be escaped. The first element is the
                    // binary, which must not be escaped.
                    result.add(args.get(i))
                } else {
                    result.add(ShellUtils.windowsEscapeArg(args.get(i)))
                }
            }
            return result.build()
        }

        override fun getScriptForm(
            shExecutable: String?,
            workingDir: String?,
            environmentVarsToUnset: com.google.common.collect.ImmutableSortedSet<String?>,
            environment: com.google.common.collect.ImmutableSortedMap<String?, String?>,
            runUnderPrefix: String?,
            args: com.google.common.collect.ImmutableList<String?>
        ): String {
            val unsetEnv: String? =
                environmentVarsToUnset.stream()
                    .map<String?>(java.util.function.Function { v: String? -> "SET " + v + "=" })
                    .collect(Collectors.joining("\n  "))
            val setEnv: String? =
                environment.entrySet().stream()
                    .map<String?>(java.util.function.Function { kv: MutableMap.MutableEntry<String?, String?>? -> "SET " + kv.getKey() + "=" + kv.getValue() })
                    .collect(Collectors.joining("\n  "))
            val commandLine = getCommandLine(shExecutable, runUnderPrefix, args)

            val result: java.lang.StringBuilder = java.lang.StringBuilder()
            result.append("@echo off\n")
            result.append("cd /d ").append(workingDir).append("\n")
            result.append("  ").append(unsetEnv).append("\n")
            result.append("  ").append(setEnv).append("\n")
            result.append("  ").append(commandLine).append(" %*\n")
            return result.toString()
        }

        companion object {
            private fun getCommandLine(
                shExecutable: String?, runUnderPrefix: String?, args: com.google.common.collect.ImmutableList<String?>
            ): String {
                val command: java.lang.StringBuilder = java.lang.StringBuilder()
                if (runUnderPrefix != null) {
                    command.append(runUnderPrefix).append(" ")
                }
                for (i in args.indices) {
                    if (i == 0) {
                        command.append(args.get(i).replace('/', '\\'))
                    } else {
                        command.append(" ").append(ShellUtils.windowsEscapeArg(args.get(i)))
                    }
                }
                if (runUnderPrefix == null) {
                    return command.toString()
                } else {
                    return shExecutable + " -c " + ShellEscaper.escapeString(command.toString())
                }
            }
        }
    }

    internal class Builder(
        runEnvironment: com.google.common.collect.ImmutableSortedMap<String?, String?>?,
        environmentVariablesToClear: com.google.common.collect.ImmutableSortedSet<String?>?,
        workingDir: com.google.devtools.build.lib.vfs.Path,
        isTestTarget: Boolean
    ) {
        private val runEnvironment: com.google.common.collect.ImmutableSortedMap<String?, String?>?
        private val environmentVariablesToClear: com.google.common.collect.ImmutableSortedSet<String?>?
        private val workingDir: com.google.devtools.build.lib.vfs.Path
        private val isTestTarget: Boolean

        private var runUnderPrefix: String? = null
        private var prettyRunUnderPrefix: String? = null

        private val args: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        private val prettyPrintArgs: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        private val residueArgs: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()

        init {
            this.runEnvironment = runEnvironment
            this.environmentVariablesToClear = environmentVariablesToClear
            this.workingDir = workingDir
            this.isTestTarget = isTestTarget
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRunUnderPrefix(runUnderPrefix: String?): Builder {
            this.runUnderPrefix = runUnderPrefix
            this.prettyRunUnderPrefix = runUnderPrefix
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRunUnderTarget(
            runUnderBinary: com.google.devtools.build.lib.vfs.Path,
            args: MutableList<String?>,
            pathPrettyPrinter: PathPrettyPrinter
        ): Builder {
            val runUnder: java.lang.StringBuilder = java.lang.StringBuilder()
            val prettyRunUnder: java.lang.StringBuilder = java.lang.StringBuilder()
            runUnder.append(ShellEscaper.escapeString(runUnderBinary.getPathString()))
            prettyRunUnder.append(
                ShellEscaper.escapeString(
                    pathPrettyPrinter.getPrettyPath(runUnderBinary.asFragment()).getPathString()
                )
            )
            for (arg in args) {
                val escapedArg: String = ShellEscaper.escapeString(arg)
                runUnder.append(" ").append(escapedArg)
                prettyRunUnder.append(" ").append(escapedArg)
            }
            this.runUnderPrefix = runUnder.toString()
            this.prettyRunUnderPrefix = prettyRunUnder.toString()
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addArg(arg: String): Builder {
            return addArgInternal(arg, arg)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addArg(path: com.google.devtools.build.lib.vfs.Path, pathPrettyPrinter: PathPrettyPrinter): Builder {
            return addArgInternal(
                path.getPathString(), pathPrettyPrinter.getPrettyPath(path.asFragment()).getPathString()
            )
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addArgs(args: Iterable<String>): Builder {
            for (arg in args) {
                addArg(arg)
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addArgsFromResidue(args: com.google.common.collect.ImmutableList<String?>): Builder {
            residueArgs.addAll(args)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun addArgInternal(arg: String, prettyPrintArg: String): Builder {
            args.add(arg)
            prettyPrintArgs.add(prettyPrintArg)
            return this
        }

        fun build(): RunCommandLine {
            return RunCommandLine(
                args.build(),
                prettyPrintArgs.build(),
                residueArgs.build(),
                runUnderPrefix,
                prettyRunUnderPrefix,
                runEnvironment,
                environmentVariablesToClear,
                workingDir,
                isTestTarget
            )
        }
    }

    companion object {
        private fun formatter(): Formatter {
            return if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) WindowsFormatter() else LinuxFormatter()
        }
    }
}
