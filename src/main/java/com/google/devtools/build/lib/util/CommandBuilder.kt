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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.shell.Command

/**
 * Implements OS aware [Command] builder. At this point only Linux, Mac and Windows XP are
 * supported.
 * 
 * 
 * Builder will also apply heuristic to identify trivial cases where unix-like command lines
 * could be automatically converted into the Windows-compatible form.
 * 
 * 
 * TODO(bazel-team): (2010) Some of the code here is very similar to the [ ] class. This should be looked at.
 */
class CommandBuilder @com.google.common.annotations.VisibleForTesting internal constructor(
    system: com.google.devtools.build.lib.util.OS?,
    clientEnv: MutableMap<String?, String?>
) {
    private val system: com.google.devtools.build.lib.util.OS?
    private val clientEnv: com.google.common.collect.ImmutableMap<String?, String?>
    private val argv: MutableList<String> = java.util.ArrayList<String>()
    private val env: MutableMap<String?, String?> = HashMap<String?, String?>()
    private var workingDir: java.io.File? = null
    private var useShell = false

    constructor(clientEnv: MutableMap<String?, String?>) : this(
        com.google.devtools.build.lib.util.OS.getCurrent(),
        clientEnv
    )

    init {
        this.system = system
        this.clientEnv = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(clientEnv)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addArg(arg: String?): CommandBuilder {
        com.google.common.base.Preconditions.checkNotNull<String?>(arg, "Argument must not be null")
        argv.add(arg!!)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addArgs(args: Iterable<String?>): CommandBuilder {
        com.google.common.base.Preconditions.checkArgument(
            !com.google.common.collect.Iterables.contains(args, null),
            "Arguments must not be null"
        )
        com.google.common.collect.Iterables.addAll<String?>(argv, args)
        return this
    }

    fun addArgs(vararg args: String?): CommandBuilder {
        return addArgs(java.util.Arrays.asList<String?>(*args))
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addEnv(env: MutableMap<String?, String?>?): CommandBuilder {
        com.google.common.base.Preconditions.checkNotNull<MutableMap<String?, String?>?>(env)
        this.env.putAll(env!!)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun emptyEnv(): CommandBuilder {
        env.clear()
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setEnv(env: MutableMap<String?, String?>?): CommandBuilder {
        emptyEnv()
        addEnv(env)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setWorkingDir(path: com.google.devtools.build.lib.vfs.Path?): CommandBuilder {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(path)
        workingDir = path.getPathFile()
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun useTempDir(): CommandBuilder {
        workingDir = java.io.File(com.google.common.base.StandardSystemProperty.JAVA_IO_TMPDIR.value())
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun useShell(useShell: Boolean): CommandBuilder {
        this.useShell = useShell
        return this
    }

    private fun argvStartsWithSh(): Boolean {
        return argv.size() >= 2 && SHELLS.contains(argv.get(0)) && "-c" == argv.get(1)
    }

    private fun transformArgvForLinux(): com.google.common.collect.ImmutableList<String?> {
        // If command line already starts with "/bin/sh -c", ignore useShell attribute.
        if (useShell && !argvStartsWithSh()) {
            // c.g.io.base.shell.Shell.shellify() actually concatenates argv into the space-separated
            // string here. Not sure why, but we will do the same.
            return com.google.common.collect.ImmutableList.of<String?>(
                "/bin/sh",
                "-c",
                com.google.common.base.Joiner.on(' ').join(argv)
            )
        }
        return com.google.common.collect.ImmutableList.copyOf<String?>(argv)
    }

    private fun transformArgvForWindows(): com.google.common.collect.ImmutableList<String?> {
        val modifiedArgv: MutableList<String>?
        // Heuristic: replace "/bin/sh -c" with something more appropriate for Windows.
        if (argvStartsWithSh()) {
            useShell = true
            modifiedArgv = java.util.ArrayList<String>(argv.subList(2, argv.size()))
        } else {
            modifiedArgv = java.util.ArrayList<String>(argv)
        }

        if (!modifiedArgv!!.isEmpty()) {
            // args can contain whitespace, so figure out the first word
            val argv0 = modifiedArgv.get(0)
            val command: String = ARGV_SPLITTER.split(argv0).iterator().next()

            // Automatically enable CMD.EXE use if we are executing something else besides "*.exe" file.
            // When use CMD.EXE to invoke a bat/cmd file, the file path must have '\' instead of '/'
            if (!command.toLowerCase().endsWith(".exe")) {
                useShell = true
                modifiedArgv.set(0, argv0.replace('/', '\\'))
            }
        } else {
            // This is degenerate "/bin/sh -c" case. We ensure that Windows behavior is identical
            // to the Linux - call shell that will do nothing.
            useShell = true
        }
        if (useShell) {
            // /S - strip first and last quotes and execute everything else as is.
            // /E:ON - enable extended command set.
            // /V:ON - enable delayed variable expansion
            // /D - ignore AutoRun registry entries.
            // /C - execute command. This must be the last option before the command itself.
            return com.google.common.collect.ImmutableList.of<String?>(
                "CMD.EXE", "/S", "/E:ON", "/V:ON", "/D", "/C", com.google.common.base.Joiner.on(' ').join(modifiedArgv)
            )
        } else {
            return com.google.common.collect.ImmutableList.copyOf<String?>(modifiedArgv)
        }
    }

    fun build(): Command {
        com.google.common.base.Preconditions.checkState(
            system != com.google.devtools.build.lib.util.OS.UNKNOWN,
            "Unidentified operating system"
        )
        com.google.common.base.Preconditions.checkNotNull<java.io.File?>(workingDir, "Working directory must be set")
        com.google.common.base.Preconditions.checkState(!argv.isEmpty(), "At least one argument is expected")

        return Command(
            if (system == com.google.devtools.build.lib.util.OS.WINDOWS) transformArgvForWindows() else transformArgvForLinux(),
            env,
            workingDir,
            clientEnv
        )
    }

    companion object {
        private val SHELLS: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("/bin/sh", "/bin/bash")

        private val ARGV_SPLITTER: com.google.common.base.Splitter =
            com.google.common.base.Splitter.on(com.google.common.base.CharMatcher.anyOf(" \t"))
    }
}
