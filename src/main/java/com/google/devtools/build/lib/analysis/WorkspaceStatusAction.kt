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

import com.google.devtools.build.lib.actions.AbstractAction

/**
 * An action writing the workspace status files.
 * 
 * 
 * These files represent information about the environment the build was run in. They are used by
 * language-specific build info factories to make the data in them available for individual
 * languages (e.g. by turning them into .h files for C++)
 * 
 * 
 * The format of these files a list of key-value pairs, one for each line. The key and the value
 * are separated by a space.
 * 
 * 
 * There are two of these files: volatile and stable. Changes in the volatile file do not cause
 * rebuilds if no other file is changed. This is useful for frequently-changing information that
 * does not significantly affect the build, e.g. the current time.
 * 
 * 
 * For more information, see [Factory].
 */
abstract class WorkspaceStatusAction protected constructor(
    owner: ActionOwner?,
    inputs: NestedSet<Artifact?>?,
    outputs: com.google.common.collect.ImmutableSet<Artifact?>?,
    private val workspaceStatusDescription: String
) : AbstractAction(owner, inputs, outputs) {
    /** Options controlling the workspace status command.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class Options : com.google.devtools.common.options.OptionsBase() {
        @kotlin.jvm.JvmField
        @get:com.google.devtools.common.options.Option(
            name = "embed_label",
            defaultValue = "",
            converter = OneLineStringConverter::class,
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = "Embed source control revision or release label in binary"
        )
        abstract var embedLabel: String?

        @kotlin.jvm.JvmField
        @get:com.google.devtools.common.options.Option(
            name = "workspace_status_command",
            defaultValue = "",
            converter = OptionsUtils.PathFragmentConverter::class,
            valueHelp = "<path>",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = """
            A command invoked at the beginning of the build to provide status
            information about the workspace in the form of key/value pairs.
            See the User's Manual for the full specification. Also see
            [`tools/buildstamp/get_workspace_status`][wksp-stat] for an example.

            [wksp-stat]: https://github.com/bazelbuild/bazel/blob/master/tools/buildstamp/get_workspace_status
            
            """.trimIndent()
        )
        abstract val workspaceStatusCommand: PathFragment?

        abstract fun setWorkspaceStatusCommand(value: PathFragment?)
    }

    /**
     * Action context required by the workspace status action as well as language-specific actions
     * that write workspace status artifacts.
     */
    interface Context : ActionContext {
        // TODO(ulfjack): Maybe move these to a separate ActionContext interface?
        @kotlin.jvm.JvmField
        val options: Options?

        @kotlin.jvm.JvmField
        val clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?

        val command: com.google.devtools.build.lib.shell.Command?
    }

    /** Environment for the [Factory] to create the workspace status action.  */
    interface Environment {
        fun createStableArtifact(name: String?): Artifact?

        fun createVolatileArtifact(name: String?): Artifact?
    }

    /** Factory for [WorkspaceStatusAction].  */
    interface Factory {
        /**
         * Creates the workspace status action.
         * 
         * 
         * The action is never re-created, but the same action object is executed on every build. Use
         * [Context] to access any non-hermetic data.
         */
        fun createWorkspaceStatusAction(env: Environment?): WorkspaceStatusAction?

        /**
         * Returns a map containing any available workspace status information.
         * 
         * 
         * Used to construct a [BuildInfoEvent] at the end of builds in which no such event was
         * posted.
         */
        fun createDummyWorkspaceStatus(
            workspaceInfoFromDiff: WorkspaceInfoFromDiff?
        ): com.google.common.collect.ImmutableSortedMap<String?, String?>?
    }

    /**
     * The volatile status artifact containing items that may change even if nothing changed between
     * the two builds, e.g. current time.
     */
    @kotlin.jvm.JvmField
    abstract val volatileStatus: Artifact?

    /**
     * The stable status artifact containing items that change only if information relevant to the
     * build changes, e.g. the name of the user running the build or the hostname.
     */
    @kotlin.jvm.JvmField
    abstract val stableStatus: Artifact?

    public override fun executeUnconditionally(): Boolean {
        return true
    }

    val isVolatile: Boolean
        get() = true

    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint?
    ) {
        // Since executeUnconditionally() is true (and this action is special-cased anyway), there is no
        // point in calculating a fingerprint.
    }

    protected fun createExecutionException(e: java.lang.Exception, detailedCode: Code?): ActionExecutionException? {
        val message = "Failed to determine " + workspaceStatusDescription + ": " + e.message
        val code: DetailedExitCode = createDetailedExitCode(message, detailedCode)
        return ActionExecutionException(message, e, this, false, code)
    }

    /** Converter for `--embed_label` which rejects strings that span multiple lines.  */
    class OneLineStringConverter : com.google.devtools.common.options.Converter.Contextless<String?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): String {
            if (input.contains("\n")) {
                throw com.google.devtools.common.options.OptionsParsingException("Value must not contain multiple lines")
            }
            return input
        }

        val typeDescription: String
            get() = "a one-line string"
    }

    companion object {
        /**
         * Parses the output of the workspace status action.
         * 
         * 
         * The output is a text file with each line representing a workspace status info key. The key
         * is the part of the line before the first space and should consist of the characters [A-Z_]
         * (although this is not checked). Everything after the first space is the value.
         */
        @Throws(IOException::class)
        fun parseValues(file: com.google.devtools.build.lib.vfs.Path?): MutableMap<String?, String?> {
            val result: HashMap<String?, String?> = HashMap<String?, String?>()
            val lineSplitter: com.google.common.base.Splitter = com.google.common.base.Splitter.on(' ').limit(2)
            for (line in com.google.common.base.Splitter.on('\n')
                .split(String(com.google.devtools.build.lib.vfs.FileSystemUtils.readContentAsLatin1(file)))) {
                val items: MutableList<String?> = lineSplitter.splitToList(line)
                if (items.size != 2) {
                    continue
                }

                result.put(items.get(0), items.get(1))
            }

            return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(result)
        }

        fun createDetailedExitCode(message: String?, detailedCode: Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setWorkspaceStatus(WorkspaceStatus.newBuilder().setCode(detailedCode))
                    .build()
            )
        }
    }
}
