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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.util.Fingerprint

/** A representation of a list of arguments.  */
abstract class CommandLine {
    /** Implementation of [ArgChunk] that delegates to an [Iterable].  */
    class SimpleArgChunk(private val args: Iterable<String>) : ArgChunk {
        override fun arguments(pathMapper: PathMapper?): Iterable<String> {
            return args
        }

        override fun totalArgLength(pathMapper: PathMapper?): Int {
            var total = 0
            for (arg in args) {
                total += arg.length() + 1
            }
            return total
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("args", args).toString()
        }
    }

    /** Returns the expanded command line.  */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    abstract fun expand(): ArgChunk?

    /**
     * Returns the expanded command line, expanding the referenced artifacts using the provided [ ].
     */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    abstract fun expand(
        inputMetadataProvider: InputMetadataProvider?, pathMapper: PathMapper?
    ): ArgChunk?

    /** Identical to calling `expand().arguments()`.  */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    abstract fun arguments(): Iterable<String?>?

    /** Identical to calling `expand(inputMetadataProvider, pathMapper).arguments()`.  */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    abstract fun arguments(
        inputMetadataProvider: InputMetadataProvider?, pathMapper: PathMapper?
    ): Iterable<String?>?

    /** Adds this command line to the provided [Fingerprint].  */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    abstract fun addToFingerprint(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        effectiveOutputPathsMode: OutputPathsMode?,
        fingerprint: Fingerprint?
    )

    /**
     * A command line backed by a simple `ImmutableList<String>`.
     * 
     * 
     * [.arguments] can be retrieved exception-free.
     */
    class FlatCommandLine private constructor(args: com.google.common.collect.ImmutableList<String?>?) :
        AbstractCommandLine() {
        private val args: com.google.common.collect.ImmutableList<String?>?

        init {
            this.args = args
        }

        override fun arguments(): com.google.common.collect.ImmutableList<String?>? {
            return args
        }

        companion object {
            private val EMPTY_INSTANCE = FlatCommandLine(com.google.common.collect.ImmutableList.of<String?>())
        }
    }

    private class SuffixedCommandLine(
        executableArgs: com.google.common.collect.ImmutableList<String?>,
        commandLine: CommandLine
    ) : AbstractCommandLine() {
        private val executableArgs: com.google.common.collect.ImmutableList<String?>
        private val commandLine: CommandLine

        init {
            this.executableArgs = executableArgs
            this.commandLine = commandLine
        }

        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        override fun arguments(): Iterable<String?> {
            return com.google.common.collect.Iterables.concat<String?>(commandLine.arguments(), executableArgs)
        }

        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        override fun arguments(
            inputMetadataProvider: InputMetadataProvider?, pathMapper: PathMapper?
        ): Iterable<String?> {
            return com.google.common.collect.Iterables.concat<String?>(
                commandLine.arguments(inputMetadataProvider, pathMapper), executableArgs
            )
        }
    }

    /**
     * This helps when debugging Blaze code that uses [CommandLine]s, as you can see their
     * content directly in the variable inspector.
     */
    override fun toString(): String {
        try {
            return com.google.common.base.Joiner.on(' ').join(arguments())
        } catch (e: CommandLineExpansionException) {
            return "Error in expanding command line"
        } catch (unused: java.lang.InterruptedException) {
            java.lang.Thread.currentThread().interrupt()
            return "Interrupted while expanding command line"
        }
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun empty(): FlatCommandLine {
            return FlatCommandLine.Companion.EMPTY_INSTANCE
        }

        /** Returns a [CommandLine] backed by the given list of arguments.  */
        fun of(arguments: com.google.common.collect.ImmutableList<String?>): FlatCommandLine? {
            return if (arguments.isEmpty()) com.google.devtools.build.lib.actions.CommandLine.Companion.empty() else FlatCommandLine(
                arguments
            )
        }

        /**
         * Returns a [CommandLine] that is constructed by appending the `args` to `commandLine`.
         */
        fun concat(commandLine: CommandLine, args: com.google.common.collect.ImmutableList<String?>): CommandLine? {
            if (args.isEmpty()) {
                return commandLine
            }
            if (commandLine === FlatCommandLine.Companion.EMPTY_INSTANCE) {
                return com.google.devtools.build.lib.actions.CommandLine.Companion.of(args)
            }
            return SuffixedCommandLine(args, commandLine)
        }
    }
}
