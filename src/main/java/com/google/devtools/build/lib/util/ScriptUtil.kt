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

import com.google.devtools.build.lib.util.ShellEscaper

/** Utils for emitting scripts cross-platform.  */
object ScriptUtil {
    private val SCRIPT_EMITTER =
        if (com.google.devtools.build.lib.util.OS.Companion.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) WindowsScriptEmitter() else LinuxScriptEmitter()

    fun emitBeginIsolate(message: java.lang.StringBuilder?) {
        SCRIPT_EMITTER.emitBeginIsolate(message)
    }

    fun emitEndIsolate(message: java.lang.StringBuilder?) {
        SCRIPT_EMITTER.emitEndIsolate(message)
    }

    /** Emits command to change directories.  */
    fun emitChangeDirectory(message: java.lang.StringBuilder?, cwd: String?) {
        SCRIPT_EMITTER.emitChangeDirectory(message, cwd)
    }

    /** Emits the "env" prefix, setting and unsetting the provided environment variables.  */
    fun emitEnvPrefix(
        message: java.lang.StringBuilder,
        ignoreEnvironment: Boolean,
        setEnv: MutableMap<String?, String?>,
        unsetEnv: MutableList<String?>?
    ) {
        SCRIPT_EMITTER.emitEnvPrefix(message, ignoreEnvironment)

        if (unsetEnv != null) {
            for (name in com.google.common.collect.Ordering.natural<Comparable<*>?>().sortedCopy<String?>(unsetEnv)) {
                message.append("  ")
                SCRIPT_EMITTER.emitUnsetEnvVar(message, name)
            }
        }

        val mapEntryComparator: java.util.Comparator<MutableMap.MutableEntry<String?, String?>?>
        TODO(
            """
            |Cannot convert element
            |With text:
            |String, String>comparingByKey();
            """.trimMargin()
        )
        for (entry in com.google.common.collect.Ordering.from<MutableMap.MutableEntry<String?, String?>?>(
            mapEntryComparator
        ).sortedCopy<MutableMap.MutableEntry<String?, String?>?>(setEnv.entries)) {
            message.append("  ")
            SCRIPT_EMITTER.emitSetEnvVar(message, entry.key, entry.value)
        }
    }

    /**
     * Formats the command element and adds it to the message.
     * 
     * @param isBinary is true if the `commandElement` is the binary to be executed
     */
    fun emitCommandElement(
        message: java.lang.StringBuilder?, commandElement: String?, isBinary: Boolean
    ) {
        SCRIPT_EMITTER.emitCommandElement(message, commandElement, isBinary)
    }

    /** Emits the prefix for "exec"-ing a command.  */
    fun emitExec(message: java.lang.StringBuilder?) {
        SCRIPT_EMITTER.emitExec(message)
    }

    private interface ScriptEmitter {
        fun emitBeginIsolate(message: java.lang.StringBuilder?)

        fun emitEndIsolate(message: java.lang.StringBuilder?)

        fun emitChangeDirectory(message: java.lang.StringBuilder?, cwd: String?)

        fun emitEnvPrefix(message: java.lang.StringBuilder?, ignoreEnvironment: Boolean)

        fun emitSetEnvVar(message: java.lang.StringBuilder?, name: String?, value: String?)

        fun emitUnsetEnvVar(message: java.lang.StringBuilder?, name: String?)

        fun emitCommandElement(message: java.lang.StringBuilder?, commandElement: String?, isBinary: Boolean)

        fun emitExec(message: java.lang.StringBuilder?)
    }

    private class LinuxScriptEmitter : ScriptEmitter {
        override fun emitBeginIsolate(message: java.lang.StringBuilder) {
            message.append("(")
        }

        override fun emitEndIsolate(message: java.lang.StringBuilder) {
            message.append(")")
        }

        override fun emitChangeDirectory(message: java.lang.StringBuilder, cwd: String?) {
            message.append("cd ").append(ShellEscaper.Companion.escapeString(cwd)).append(" && \\\n  ")
        }

        override fun emitEnvPrefix(message: java.lang.StringBuilder, ignoreEnvironment: Boolean) {
            message.append(if (ignoreEnvironment) "env - \\\n  " else "env \\\n  ")
        }

        override fun emitSetEnvVar(message: java.lang.StringBuilder, name: String?, value: String?) {
            message
                .append(ShellEscaper.Companion.escapeString(name))
                .append('=')
                .append(ShellEscaper.Companion.escapeString(value))
                .append(" \\\n  ")
        }

        override fun emitUnsetEnvVar(message: java.lang.StringBuilder, name: String?) {
            // Only the short form of --unset is supported on macOS.
            message.append("-u ").append(ShellEscaper.Companion.escapeString(name)).append(" \\\n  ")
        }

        override fun emitCommandElement(message: java.lang.StringBuilder, commandElement: String?, isBinary: Boolean) {
            message.append(ShellEscaper.Companion.escapeString(commandElement))
        }

        override fun emitExec(message: java.lang.StringBuilder) {
            message.append("exec ")
        }
    }

    // TODO(bazel-team): (2010) Add proper escaping. We can't use ShellUtils.shellEscape() as it is
    // incompatible with CMD.EXE syntax, but something else might be needed.
    private class WindowsScriptEmitter : ScriptEmitter {
        override fun emitBeginIsolate(message: java.lang.StringBuilder?) {
            // TODO(bazel-team): Implement this.
        }

        override fun emitEndIsolate(message: java.lang.StringBuilder?) {
            // TODO(bazel-team): Implement this.
        }

        override fun emitChangeDirectory(message: java.lang.StringBuilder, cwd: String?) {
            message.append("cd ").append("/d ").append(cwd).append("\n")
        }

        override fun emitEnvPrefix(message: java.lang.StringBuilder?, ignoreEnvironment: Boolean) {}

        override fun emitSetEnvVar(message: java.lang.StringBuilder, name: String?, value: String?) {
            message.append("SET ").append(name).append('=').append(value).append("\n  ")
        }

        override fun emitUnsetEnvVar(message: java.lang.StringBuilder, name: String?) {
            message.append("SET ").append(name).append('=').append("\n  ")
        }

        override fun emitCommandElement(message: java.lang.StringBuilder, commandElement: String, isBinary: Boolean) {
            // Replace the forward slashes with back slashes if the `commandElement` is the binary path
            message.append(if (isBinary) commandElement.replace('/', '\\') else commandElement)
        }

        override fun emitExec(message: java.lang.StringBuilder?) {
            // TODO(bazel-team): Implement this if possible for greater efficiency.
        }
    }
}
