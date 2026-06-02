// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.java


import com.github.benmanes.caffeine.cache.CacheLoader
import com.github.benmanes.caffeine.cache.LoadingCache
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.actions.Artifact
import net.starlark.java.eval.EvalException

/** An executable tool that is part of `java_toolchain`.  */
@AutoValue
abstract class JavaToolchainTool {
    /** The executable, possibly a `_deploy.jar`.  */
    abstract fun tool(): FilesToRunProvider?

    /** Additional inputs required by the tool, e.g. a Class Data Sharing archive.  */
    abstract fun data(): NestedSet<Artifact?>?

    /**
     * JVM flags to invoke the tool with. Location expansion is performed on these flags using the
     * inputs in [.data].
     */
    abstract fun jvmOpts(): NestedSet<String?>?

    /** The `java_toolchain` this tool belongs to.  */
    abstract fun toolchain(): JavaToolchainProvider?

    private class CommandLineKey(
        executable: Artifact?,
        jvmOpts: ImmutableList<String?>?,
        javaBinary: PathFragment?,
        toolchainJvmOpts: ImmutableList<String?>?
    ) {
        val executable: Artifact?
        val jvmOpts: ImmutableList<String?>?
        val javaBinary: PathFragment?
        val toolchainJvmOpts: ImmutableList<String?>?

        init {
            this.executable = executable
            this.jvmOpts = jvmOpts
            this.javaBinary = javaBinary
            this.toolchainJvmOpts = toolchainJvmOpts
        }

        companion object {
            @Throws(RuleErrorException::class)
            fun from(
                executable: Artifact, jvmOpts: NestedSet<String?>, toolchain: JavaToolchainProvider
            ): CommandLineKey {
                val jvmOptsList: ImmutableList<String?>? = jvmOpts.toList()
                if (!executable.getExtension().equals("jar")) {
                    return CommandLineKey(executable, jvmOptsList, null, null)
                }
                return CommandLineKey(
                    executable,
                    jvmOptsList,
                    toolchain.getJavaRuntime().javaBinaryExecPathFragment(),
                    toolchain.getJvmOptions().toList()
                )
            }
        }
    }

    @get:Throws(RuleErrorException::class)
    val commandLine: CustomCommandLine?
        /**
         * Returns the executable command line for the tool.
         * 
         * 
         * For a Java command, the executable command line will include `java -jar deploy.jar` as
         * well as any JVM flags.
         * 
         * @param toolchain `java_toolchain` for the action being constructed
         */
        get() = commandLineCache.get(
            CommandLineKey.Companion.from(tool().getExecutable(), jvmOpts(), toolchain()!!)
        )

    /** Adds its inputs for the tool to provided input builder.  */
    @Throws(RuleErrorException::class)
    fun addInputs(inputs: NestedSetBuilder<Artifact?>) {
        inputs.addTransitive(data())
        val executable: Artifact = tool().getExecutable()
        // The runfiles of the tool are not added. If this is desired, add getFilesToRun() to inputs
        // instead.
        inputs.add(executable)
        if (executable.getExtension().equals("jar")) {
            inputs.addTransitive(toolchain()!!.getJavaRuntime().javaBaseInputs())
        }
    }

    fun withAdditionalJvmFlags(additionalJvmFlags: NestedSet<String?>): JavaToolchainTool {
        if (additionalJvmFlags.isEmpty()) {
            return this
        }
        return create(
            tool(),
            data(),
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ()
                .addTransitive(jvmOpts())
                .addTransitive(additionalJvmFlags)
                .build(),
            toolchain()
        )
    }

    companion object {
        @Throws(RuleErrorException::class)
        fun fromStarlark(
            struct: StructImpl?, toolchain: JavaToolchainProvider?
        ): JavaToolchainTool? {
            if (struct == null) {
                return null
            }
            try {
                return create(
                    struct.getValue("tool", FilesToRunProvider::class.java),
                    Depset.noneableCast(struct.getValue("data"), Artifact::class.java, "data"),
                    Depset.noneableCast(struct.getValue("jvm_opts"), String::class.java, "jvm_opts"),
                    toolchain
                )
            } catch (e: EvalException) {
                throw RuleErrorException(e)
            }
        }

        /**
         * Cache for the [CustomCommandLine] for a given tool.
         * 
         * 
         * Using weak values since the main benefit is to share the command line between different
         * actions, in which case the [CustomCommandLine] object remains strongly reachable anyway.
         */
        private val commandLineCache: LoadingCache<CommandLineKey?, CustomCommandLine?> =
            Caffeine.newBuilder().weakValues().build<CommandLineKey?, CustomCommandLine?>(
                CacheLoader { key: CommandLineKey? -> Companion.buildCommandLine(key!!) })

        private fun create(
            tool: FilesToRunProvider?,
            data: NestedSet<Artifact?>?,
            jvmOpts: NestedSet<String?>?,
            toolchain: JavaToolchainProvider?
        ): JavaToolchainTool {
            return AutoValue_JavaToolchainTool(tool, data, jvmOpts, toolchain)
        }

        private fun buildCommandLine(key: CommandLineKey): CustomCommandLine {
            var command: CustomCommandLine.Builder = CustomCommandLine.builder()

            if (key.javaBinary == null) {
                command = command.addExecPath(key.executable).addAll(key.jvmOpts)
            } else {
                command
                    .addPath(key.javaBinary)
                    .addAll(key.toolchainJvmOpts)
                    .addAll(key.jvmOpts)
                    .add("-jar")
                    .addPath(key.executable.getExecPath())
            }

            return command.build()
        }
    }
}
