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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader

/** Creates different types of [InstrumentationOutputBuilder].  */
class InstrumentationOutputFactory private constructor(
    localInstrumentationOutputBuilderSupplier: java.util.function.Supplier<com.google.devtools.build.lib.runtime.LocalInstrumentationOutput.Builder?>,
    buildEventArtifactInstrumentationOutputBuilderSupplier: java.util.function.Supplier<com.google.devtools.build.lib.runtime.BuildEventArtifactInstrumentationOutput.Builder?>,
    redirectInstrumentationOutputBuilderSupplier: java.util.function.Supplier<InstrumentationOutputBuilder?>?,
    localTempLoggingDirPathStr: String?
) {
    private val localInstrumentationOutputBuilderSupplier: java.util.function.Supplier<com.google.devtools.build.lib.runtime.LocalInstrumentationOutput.Builder?>


    private val buildEventArtifactInstrumentationOutputBuilderSupplier: java.util.function.Supplier<com.google.devtools.build.lib.runtime.BuildEventArtifactInstrumentationOutput.Builder?>


    val redirectInstrumentationOutputBuilderSupplier: java.util.function.Supplier<InstrumentationOutputBuilder?>?

    private val localTempLoggingDirPathStr: String?

    init {
        this.localInstrumentationOutputBuilderSupplier = localInstrumentationOutputBuilderSupplier
        this.buildEventArtifactInstrumentationOutputBuilderSupplier =
            buildEventArtifactInstrumentationOutputBuilderSupplier
        this.redirectInstrumentationOutputBuilderSupplier =
            redirectInstrumentationOutputBuilderSupplier
        this.localTempLoggingDirPathStr = localTempLoggingDirPathStr
    }

    /**
     * Creates a [LocalInstrumentationOutput] located at `path`, which could future call
     * [LocalInstrumentationOutput.makeConvenienceLink] to make a symlink with the simplified
     * `convenienceName` pointing to the local output. The symlink locates under the same
     * directory as the output.
     * 
     * 
     * Should only be used when an output MUST be written locally or is otherwise incompatible with
     * the flexible destinations supported by the preferred generic [ ][.createInstrumentationOutput].
     */
    fun createLocalOutputWithConvenientName(
        name: String?, path: com.google.devtools.build.lib.vfs.Path?, convenienceName: String?
    ): LocalInstrumentationOutput? {
        return localInstrumentationOutputBuilderSupplier
            .get()
            .setName(name)
            .setPath(path)
            .setConvenienceName(convenienceName)
            .build()
    }

    /** Defines types of directory the [InstrumentationOutput] path is relative to.  */ // TODO: b/379723545 - Eventually, we want to deprecate WORKSPACE_OR_HOME and make path always
    // relative to user's current working directory.
    enum class DestinationRelativeTo {
        /** Output is relative to the bazel workspace or user's home directory.  */
        WORKSPACE_OR_HOME,

        /** Output is relative to user's current working or home directory  */
        WORKING_DIRECTORY_OR_HOME,

        /** Output is relative to the `output_base` directory.  */
        OUTPUT_BASE,

        /**
         * Output is relative to the specified system logging directory.
         * 
         * 
         * Used only when [.localTempLoggingDirPathStr] is set.
         */
        TEMP_LOGGING_DIRECTORY
    }

    /**
     * Creates [LocalInstrumentationOutput] or an [InstrumentationOutput] object
     * redirecting outputs to be written on a different machine.
     * 
     * 
     * If [.redirectInstrumentationOutputBuilderSupplier] is not provided but `--redirect_local_instrumentation_output_writes` is set, this method will default to return
     * [LocalInstrumentationOutput].
     * 
     * @param append Whether to open the [LocalInstrumentationOutput] file in append mode
     * @param internal Whether the [LocalInstrumentationOutput] file is a Bazel internal file.
     * @param createParent Whether to recursively create parent directories when the file path's
     * parent directory does not exist.
     */
    @kotlin.jvm.JvmOverloads
    fun createInstrumentationOutput(
        name: String?,
        destination: PathFragment?,
        destinationRelativeTo: DestinationRelativeTo?,
        env: CommandEnvironment,
        eventHandler: com.google.devtools.build.lib.events.EventHandler,
        append: Boolean?,
        internal: Boolean?,
        createParent: Boolean = false
    ): InstrumentationOutput? {
        val isRedirect: Boolean =
            env.getOptions()
                .getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java)
                .getRedirectLocalInstrumentationOutputWrites()
        if (isRedirect) {
            if (redirectInstrumentationOutputBuilderSupplier != null) {
                return redirectInstrumentationOutputBuilderSupplier
                    .get()
                    .setName(name)
                    .setDestination(destination)
                    .setDestinationRelatedToType(destinationRelativeTo)
                    .setCommandEnvironment(env)
                    .setCreateParent(createParent)
                    .build()
            }
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.warn(
                    "Redirecting to write Instrumentation Output on a different machine is not"
                            + " supported. Defaulting to writing output locally."
                )
            )
        }

        // Since PathFragmentConverter for flag value replaces prefixed `~/` with user's home path, the
        // destination path could be (1) an absolute path, or (2) a path relative to
        // output_base, workspace, cwd or some temporary logging directory.
        val localOutputPath: com.google.devtools.build.lib.vfs.Path? =
            (when (destinationRelativeTo) {
                DestinationRelativeTo.OUTPUT_BASE -> env.getOutputBase()
                DestinationRelativeTo.WORKSPACE_OR_HOME -> env.getWorkspace()
                DestinationRelativeTo.WORKING_DIRECTORY_OR_HOME -> env.getWorkingDirectory()
                DestinationRelativeTo.TEMP_LOGGING_DIRECTORY -> env.getRuntime().getFileSystem()
                    .getPath(localTempLoggingDirPathStr)
            })
                .getRelative(destination)
        return localInstrumentationOutputBuilderSupplier
            .get()
            .setName(name)
            .setPath(localOutputPath)
            .setAppend(append)
            .setInternal(internal)
            .setCreateParent(createParent)
            .build()
    }

    fun createBuildEventArtifactInstrumentationOutput(
        name: String?, uploader: BuildEventArtifactUploader?
    ): BuildEventArtifactInstrumentationOutput? {
        return buildEventArtifactInstrumentationOutputBuilderSupplier
            .get()
            .setName(name)
            .setUploader(uploader)
            .build()
    }

    /** Builder for [InstrumentationOutputFactory].  */
    class Builder {
        private var localInstrumentationOutputBuilderSupplier: java.util.function.Supplier<com.google.devtools.build.lib.runtime.LocalInstrumentationOutput.Builder?>? =
            null

        private var buildEventArtifactInstrumentationOutputBuilderSupplier: java.util.function.Supplier<com.google.devtools.build.lib.runtime.BuildEventArtifactInstrumentationOutput.Builder?>? =
            null

        private var redirectInstrumentationOutputBuilderSupplier: java.util.function.Supplier<InstrumentationOutputBuilder?>? =
            null

        private var localTempLoggingDirPathStr: String? = null

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setLocalInstrumentationOutputBuilderSupplier(
            localInstrumentationOutputBuilderSupplier: java.util.function.Supplier<com.google.devtools.build.lib.runtime.LocalInstrumentationOutput.Builder?>?
        ): Builder {
            this.localInstrumentationOutputBuilderSupplier = localInstrumentationOutputBuilderSupplier
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setBuildEventArtifactInstrumentationOutputBuilderSupplier(
            buildEventArtifactInstrumentationOutputBuilderSupplier: java.util.function.Supplier<com.google.devtools.build.lib.runtime.BuildEventArtifactInstrumentationOutput.Builder?>?
        ): Builder {
            this.buildEventArtifactInstrumentationOutputBuilderSupplier =
                buildEventArtifactInstrumentationOutputBuilderSupplier
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRedirectInstrumentationOutputBuilderSupplier(
            redirectInstrumentationOutputBuilderSupplier: java.util.function.Supplier<InstrumentationOutputBuilder?>?
        ): Builder {
            this.redirectInstrumentationOutputBuilderSupplier =
                redirectInstrumentationOutputBuilderSupplier
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setLocalTempLoggingDirPathStr(localTempLoggingDirPathStr: String?): Builder {
            this.localTempLoggingDirPathStr = localTempLoggingDirPathStr
            return this
        }

        fun build(): InstrumentationOutputFactory {
            return InstrumentationOutputFactory(
                com.google.common.base.Preconditions.checkNotNull<java.util.function.Supplier<com.google.devtools.build.lib.runtime.LocalInstrumentationOutput.Builder?>?>(
                    localInstrumentationOutputBuilderSupplier,
                    "Cannot create InstrumentationOutputFactory without localOutputBuilderSupplier"
                ),
                com.google.common.base.Preconditions.checkNotNull<java.util.function.Supplier<com.google.devtools.build.lib.runtime.BuildEventArtifactInstrumentationOutput.Builder?>?>(
                    buildEventArtifactInstrumentationOutputBuilderSupplier,
                    "Cannot create InstrumentationOutputFactory without bepOutputBuilderSupplier"
                ),
                redirectInstrumentationOutputBuilderSupplier,
                if (localTempLoggingDirPathStr != null) localTempLoggingDirPathStr else com.google.common.base.StandardSystemProperty.JAVA_IO_TMPDIR.value()
            )
        }
    }
}
