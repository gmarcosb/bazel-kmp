// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.AbstractAction

/**
 * Action used by LtoBackendArtifacts to create an LtoBackendAction. Similar to [SpawnAction],
 * except that inputs are discovered from the imports file created by the ThinLTO indexing step for
 * each backend artifact.
 * 
 * 
 * See [LtoBackendArtifacts] for a high level description of the ThinLTO build process. The
 * LTO indexing step takes all bitcode .o files and decides which other .o file symbols can be
 * imported/inlined. The additional input files for each backend action are then written to an
 * imports file. Therefore, these new inputs must be discovered here by subsetting the imports paths
 * from the set of all bitcode artifacts, before executing the backend action.
 * 
 * 
 * For more information on ThinLTO see
 * http://blog.llvm.org/2016/06/thinlto-scalable-and-incremental-lto.html.
 */
@AutoCodec
class LtoBackendAction : SpawnAction {
    private val mandatoryInputs: NestedSet<Artifact?>
    private val bitcodeFiles: BitcodeFiles?
    private val imports: Artifact?
    private var inputsDiscovered = false

    constructor(
        owner: ActionOwner?,
        inputs: NestedSet<Artifact?>,
        allBitcodeFiles: BitcodeFiles?,
        importsFile: Artifact?,
        outputs: com.google.common.collect.ImmutableSet<Artifact?>?,
        argv: CommandLines?,
        env: ActionEnvironment?,
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?
    ) : super(
        owner,  /* tools= */
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        inputs,
        outputs,
        AbstractAction.DEFAULT_RESOURCE_SET,
        argv,
        env,
        executionInfo,
        "LTO Backend Compile %{output}",
        MNEMONIC,
        OutputPathsMode.OFF
    ) {
        mandatoryInputs = inputs
        com.google.common.base.Preconditions.checkState(
            (allBitcodeFiles == null) == (importsFile == null),
            "Either both or neither bitcodeFiles and imports files should be null"
        )
        bitcodeFiles = allBitcodeFiles
        imports = importsFile
    }

    /** Constructor for serialization.  */
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec.Instantiator
    internal constructor(
        owner: ActionOwner?,
        mandatoryInputs: NestedSet<Artifact?>,
        rawOutputs: Any?,
        commandLines: CommandLines?,
        environment: ActionEnvironment?,
        sortedExecutionInfo: com.google.common.collect.ImmutableSortedMap<String?, String?>?,
        bitcodeFiles: BitcodeFiles?,
        imports: Artifact?
    ) : super(
        owner,  /* tools= */
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        mandatoryInputs,
        rawOutputs,
        AbstractAction.DEFAULT_RESOURCE_SET,
        commandLines,
        environment,
        sortedExecutionInfo,
        "LTO Backend Compile %{output}",
        MNEMONIC,
        OutputPathsMode.OFF
    ) {
        this.mandatoryInputs = mandatoryInputs
        this.bitcodeFiles = bitcodeFiles
        this.imports = imports
    }

    public override fun discoversInputs(): Boolean {
        return imports != null
    }

    protected override fun inputsDiscovered(): Boolean {
        return inputsDiscovered
    }

    protected override fun setInputsDiscovered(inputsDiscovered: Boolean) {
        this.inputsDiscovered = inputsDiscovered
    }

    /**
     * Given a map of path to artifact, and a path, returns the artifact whose key is in the map, or
     * if none, an artifact whose key matches a prefix of the path. Assumes that artifacts whose paths
     * are directories are tree artifacts. Assumes that no artifact key is a sub directory of another
     * artifact key. For example, "path/file1" may return the artifact whose path is "path/file1" or
     * whose path is "path/". Returns empty if there are no matches.
     */
    private fun getArtifactOrTreeArtifact(
        path: PathFragment?, pathToArtifact: MutableMap<PathFragment?, Artifact?>
    ): java.util.Optional<Artifact?> {
        var currentPath: PathFragment? = path
        while (!currentPath.isEmpty()) {
            if (pathToArtifact.containsKey(currentPath)) {
                return java.util.Optional.of<Artifact?>(pathToArtifact.get(currentPath))
            } else {
                currentPath = currentPath.getParentDirectory()
            }
        }
        return java.util.Optional.empty<Artifact?>()
    }

    /**
     * Throws an error if any of the input paths is not in the bitcodeFiles or in a subdirecorty of a
     * file in bitcodeFiles
     */
    @Throws(ActionExecutionException::class)
    private fun computeBitcodeInputs(
        inputPaths: HashSet<PathFragment?>, actionExecutionContext: ActionExecutionContext
    ): NestedSet<Artifact?> {
        val bitcodeInputs: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        val execPathToArtifact: MutableMap<PathFragment?, Artifact?> = bitcodeFiles.getFilesArtifactPathMap()
        val missingInputs: MutableSet<PathFragment?> = HashSet<PathFragment?>()
        for (inputPath in inputPaths) {
            val maybeArtifact: java.util.Optional<Artifact?> = getArtifactOrTreeArtifact(inputPath, execPathToArtifact)
            if (maybeArtifact.isPresent()) {
                bitcodeInputs.add(maybeArtifact.get())
            } else {
                // One of the inputs is not present. We add it to missingInputs and will fail.
                missingInputs.add(inputPath)
            }
        }
        if (!missingInputs.isEmpty()) {
            val message: String? =
                java.lang.String.format(
                    "error computing inputs from imports file: %s, missing bitcode files (first 10): %s",
                    actionExecutionContext.getInputPath(imports),  // Limit the reported count to protect against a large error message.
                    missingInputs.stream()
                        .map<String?>(java.util.function.Function { obj: PathFragment? -> obj.toString() })
                        .sorted()
                        .limit(10)
                        .collect(Collectors.joining(", "))
                )
            val code: DetailedExitCode = createDetailedExitCode(message, Code.MISSING_BITCODE_FILES)
            throw ActionExecutionException(message, this, false, code)
        }
        return bitcodeInputs.build()
    }

    @Throws(ActionExecutionException::class)
    public override fun discoverInputs(actionExecutionContext: ActionExecutionContext): NestedSet<Artifact?>? {
        val importsFilePath: com.google.devtools.build.lib.vfs.Path? = actionExecutionContext.getInputPath(imports)
        val lines: com.google.common.collect.ImmutableList<String>?
        try {
            lines = com.google.devtools.build.lib.vfs.FileSystemUtils.readLinesAsLatin1(importsFilePath)
        } catch (e: IOException) {
            val message: String? =
                java.lang.String.format(
                    "error reading imports file %s: %s",
                    actionExecutionContext.getInputPath(imports), e.getMessage()
                )
            val code: DetailedExitCode = createDetailedExitCode(message, Code.IMPORTS_READ_IO_EXCEPTION)
            throw ActionExecutionException(message, e, this, false, code)
        }

        // Build set of files this LTO backend artifact will import from.
        val importSet: HashSet<PathFragment?> = HashSet<PathFragment?>()
        for (line in lines) {
            if (line.isEmpty()) {
                continue
            }
            val execPath: PathFragment = PathFragment.create(line)
            if (execPath.isAbsolute()) {
                val message: String? =
                    java.lang.String.format(
                        "Absolute paths not allowed in imports file %s: %s",
                        actionExecutionContext.getInputPath(imports), execPath
                    )
                val code: DetailedExitCode =
                    createDetailedExitCode(message, Code.INVALID_ABSOLUTE_PATH_IN_IMPORTS)
                throw ActionExecutionException(message, this, false, code)
            }
            importSet.add(execPath)
        }

        // Convert the import set of paths to the set of bitcode file artifacts.
        // Throws an error if there is any path in the importset that is not pat of any artifact
        val bitcodeInputSet: NestedSet<Artifact?> = computeBitcodeInputs(importSet, actionExecutionContext)
        updateInputs(
            NestedSetBuilder.fromNestedSet(bitcodeInputSet).addTransitive(mandatoryInputs).build()
        )
        return bitcodeInputSet
    }

    val originalInputs: NestedSet<Artifact?>
        get() = mandatoryInputs

    public override fun getMandatoryInputs(): NestedSet<Artifact?> {
        return mandatoryInputs
    }

    val allowedDerivedInputs: NestedSet<Artifact?>?
        get() = bitcodeFiles.getFiles()

    @Throws(java.lang.InterruptedException::class)
    public override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addString(GUID)
        try {
            fp.addStrings(getArguments())
        } catch (e: CommandLineExpansionException) {
            throw java.lang.AssertionError("LtoBackendAction command line expansion cannot fail", e)
        }
        fp.addString(getMnemonic())
        for (input in mandatoryInputs.toList()) {
            fp.addPath(input.getExecPath())
        }
        if (imports != null) {
            bitcodeFiles.addToFingerprint(fp)
            fp.addPath(imports.getExecPath())
        }
        getEnvironment().addTo(fp)
        fp.addStringMap(getExecutionInfo())
    }

    companion object {
        private const val GUID = "72ce1eca-4625-4e24-a0d8-bb91bb8b0e0e"
        private const val MNEMONIC = "CcLtoBackendCompile"

        private fun createDetailedExitCode(message: String?, detailedCode: Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setLtoAction(LtoAction.newBuilder().setCode(detailedCode))
                    .build()
            )
        }

        @com.google.common.annotations.VisibleForTesting
        fun create(
            actionOwner: ActionOwner?,
            configuration: BuildConfigurationValue?,
            inputs: NestedSet<Artifact?>?,
            allBitcodeFiles: BitcodeFiles?,
            importsFile: Artifact?,
            outputs: com.google.common.collect.ImmutableSet<Artifact?>?,
            argv: CommandLines?,
            env: ActionEnvironment?
        ): LtoBackendAction {
            return LtoBackendAction(
                actionOwner,
                inputs,
                allBitcodeFiles,
                importsFile,
                outputs,
                argv,
                env,
                if (configuration == null)
                    com.google.common.collect.ImmutableMap.of<K?, V?>()
                else
                    configuration.modifiedExecutionInfo(com.google.common.collect.ImmutableMap.of<K?, V?>(), MNEMONIC)
            )
        }
    }
}
