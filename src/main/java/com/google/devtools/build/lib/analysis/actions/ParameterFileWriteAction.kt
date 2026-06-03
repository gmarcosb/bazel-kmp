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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.ActionExecutionContext

/** Action to write a parameter file for a [CommandLine].  */
@Immutable // if commandLine is immutable
class ParameterFileWriteAction(
    owner: ActionOwner?,
    inputs: NestedSet<Artifact?>?,
    output: Artifact?,
    commandLine: CommandLine,
    type: ParameterFileType,
    makeExecutable: Boolean,
    mnemonic: String?,
    executionInfo: com.google.common.collect.ImmutableMap<String?, String?>,
    outputPathsMode: OutputPathsMode?
) : AbstractFileWriteAction(owner, inputs, output) {
    private val commandLine: CommandLine
    private val type: ParameterFileType
    private val makeExecutable: Boolean
    private val mnemonic: String?
    private val usePathStripping: Boolean

    /**
     * Creates a new instance.
     * 
     * @param owner the action owner
     * @param output the Artifact that will be created by executing this Action
     * @param commandLine the contents to be written to the file
     * @param type the type of the file
     * @param makeExecutable whether the output file should be made executable
     */
    constructor(
        owner: ActionOwner?,
        output: Artifact?,
        commandLine: CommandLine,
        type: ParameterFileType,
        makeExecutable: Boolean
    ) : this(
        owner,
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        output,
        commandLine,
        type,
        makeExecutable,
        AbstractFileWriteAction.Companion.MNEMONIC,  /* executionInfo= */
        com.google.common.collect.ImmutableMap.of<String?, String?>(),
        OutputPathsMode.OFF
    )

    /**
     * Creates a new instance.
     * 
     * @param owner the action owner
     * @param inputs the list of TreeArtifacts that must be resolved and expanded before evaluating
     * the contents of [CommandLine].
     * @param output the Artifact that will be created by executing this Action
     * @param commandLine the contents to be written to the file
     * @param type the type of the file
     * @param makeExecutable whether the output file should be made executable
     * @param mnemonic the mnemonic for this action, or null if the default should be used
     * @param executionInfo the execution info for this action (only supports-path-mapping is used)
     * @param outputPathsMode the output paths mode obtained via [     ][PathMappers.getOutputPathsMode]
     */
    init {
        this.commandLine = commandLine
        this.type = type
        this.makeExecutable = makeExecutable
        this.mnemonic = mnemonic
        // Save memory by not storing the full execution info, but only what matters for this particular
        // action.
        this.usePathStripping =
            (PathMappers.getEffectiveOutputPathsMode(outputPathsMode, getMnemonic(), executionInfo)
                    == OutputPathsMode.STRIP)
    }

    override fun makeExecutable(): Boolean {
        return makeExecutable
    }

    override fun getMnemonic(): String? {
        return mnemonic
    }

    public override fun getExecutionInfo(): com.google.common.collect.ImmutableMap<String?, String?> {
        return if (usePathStripping)
            com.google.common.collect.ImmutableMap.of<String?, String?>(ExecutionRequirements.SUPPORTS_PATH_MAPPING, "")
        else
            com.google.common.collect.ImmutableMap.of<String?, String?>()
    }

    private fun getOutputPathsMode(): OutputPathsMode {
        return if (usePathStripping) OutputPathsMode.STRIP else OutputPathsMode.OFF
    }

    @com.google.common.annotations.VisibleForTesting
    fun getCommandLine(): CommandLine {
        return commandLine
    }

    /**
     * Returns the list of options written to the parameter file. Don't use this method outside tests
     * - the list is often huge, resulting in significant garbage collection overhead.
     * 
     * 
     * 2019-01-10, @leba: Using this method for aquery since it's not performance-critical and the
     * includeParamFile option is flag-guarded with warning regarding output size to user.
     * 
     * 
     * TODO(b/161359171): The list of arguments will be incorrect if the arguments contain tree
     * artifacts or path mapping is used.
     */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun getArguments(): Iterable<String?> {
        return commandLine.arguments()
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class, IOException::class)
    fun getStringContents(): String? {
        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        ParameterFile.writeParameterFile(out, getArguments(), type)
        return out.toString(java.nio.charset.StandardCharsets.ISO_8859_1)
    }

    @Throws(IOException::class, EvalException::class, java.lang.InterruptedException::class)
    public override fun getStarlarkContent(): String? {
        if (!getInputs().isEmpty()) {
            // Tree artifact information isn't available at analysis time.
            return null
        }
        try {
            return getStringContents()
        } catch (e: CommandLineExpansionException) {
            throw Starlark.errorf("Error expanding command line: %s", e.getMessage())
        }
    }

    @Throws(ExecException::class, java.lang.InterruptedException::class)
    override fun newDeterministicWriter(ctx: ActionExecutionContext): DeterministicWriter {
        val arguments: ArgChunk?
        // Other actions consuming this parameter file may have path mapping disabled due to inputs
        // conflicting across configurations, in which case paths written to the file will not match.
        // Since this depends on the consumer but the decision is only made at execution time, it is not
        // clear how to improve that situation. Actions that are prone to such collisions should avoid
        // depending on parameter files.
        val pathMapper: PathMapper? = PathMappers.create(this, getOutputPathsMode(),  /* isStarlarkAction= */false)
        try {
            val inputMetadataProvider: InputMetadataProvider =
                com.google.common.base.Preconditions.checkNotNull<T>(ctx.getInputMetadataProvider())
            arguments = commandLine.expand(inputMetadataProvider, pathMapper)
        } catch (e: CommandLineExpansionException) {
            throw UserExecException(
                e,
                FailureDetail.newBuilder()
                    .setMessage(com.google.common.base.Strings.nullToEmpty(e.getMessage()))
                    .setSpawn(Spawn.newBuilder().setCode(Code.COMMAND_LINE_EXPANSION_FAILURE))
                    .build()
            )
        }
        return ParamFileWriter(arguments, pathMapper, type)
    }

    private class ParamFileWriter(arguments: ArgChunk?, pathMapper: PathMapper?, type: ParameterFileType?) :
        DeterministicWriter {
        @Throws(IOException::class)
        public override fun writeTo(out: java.io.OutputStream?) {
            ParameterFile.writeParameterFile(out, arguments.arguments(pathMapper), type)
        }

        val arguments: ArgChunk?
        val pathMapper: PathMapper?
        val type: ParameterFileType?

        init {
            this.arguments = arguments
            this.pathMapper = pathMapper
            this.type = type
        }
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addString(GUID)
        fp.addString(type.toString())
        commandLine.addToFingerprint(
            actionKeyContext,
            inputMetadataProvider,
            PathMappers.getEffectiveOutputPathsMode(
                getOutputPathsMode(), getMnemonic(), getExecutionInfo()
            ),
            fp
        )
    }

    public override fun describeKey(): String {
        val message: java.lang.StringBuilder = java.lang.StringBuilder()
        message.append("GUID: ")
        message.append(GUID)
        message.append("\nParam File Type: ")
        message.append(type)
        message.append("\nContent digest (approximate): ")
        try {
            // The full contents can be huge, which makes the final error message
            // incomprehensible. Instead, just give a digest, which makes it easy to
            // tell if two contents are equal or not.
            val fp: Fingerprint = Fingerprint()
            commandLine.addToFingerprint(
                ActionKeyContext(),
                null,
                PathMappers.getEffectiveOutputPathsMode(
                    getOutputPathsMode(), getMnemonic(), getExecutionInfo()
                ),
                fp
            )
            message.append(com.google.common.io.BaseEncoding.base16().lowerCase().encode(fp.digestAndReset()))
            message.append(
                ("\n"
                        + "NOTE: Content digest reflects approximate, analysis-time data; it does not account"
                        + " for data available during execution (e.g. tree artifact expansions)")
            )
        } catch (ex: java.lang.InterruptedException) {
            java.lang.Thread.currentThread().interrupt()
            message.append("Interrupted while expanding command line")
        } catch (e: CommandLineExpansionException) {
            message.append("Could not expand contents: ")
            message.append(e)
        }
        return message.toString()
    }

    companion object {
        private const val GUID = "45f678d8-e395-401e-8446-e795ccc6361f"
    }
}
