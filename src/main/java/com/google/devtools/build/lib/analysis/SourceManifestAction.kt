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

/**
 * Creates a manifest file describing a symlink tree.
 * 
 * 
 * In addition to symlink trees (whose manifests are a tree position -> exec path map), this
 * action can also create manifest consisting of just exec paths for historical reasons.
 * 
 * 
 * This action carefully avoids building the manifest content in memory because it can be large.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable // if all ManifestWriter implementations are immutable
class SourceManifestAction(
    /** The strategy we use to write manifest entries.  */
    private val manifestWriter: ManifestWriter,
    owner: ActionOwner?,
    primaryOutput: Artifact?,
    runfiles: com.google.devtools.build.lib.analysis.Runfiles,
    repoMappingManifest: Artifact?,
    remotableSourceManifestActions: Boolean
) : AbstractFileWriteAction(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), primaryOutput), FileContentsProvider {
    private val repoMappingManifest: Artifact?

    /**
     * Interface for defining manifest formatting and reporting specifics. Implementations must be
     * immutable.
     */
    internal interface ManifestWriter {
        /**
         * Writes a single line of manifest output.
         * 
         * @param manifestWriter the output stream
         * @param rootRelativePath path of an entry relative to the manifest's root
         * @param symlinkTarget target of the entry at `rootRelativePath` if it is a symlink,
         * otherwise `null`
         */
        @Throws(IOException::class)
        fun writeEntry(
            manifestWriter: java.io.Writer?, rootRelativePath: PathFragment?, symlinkTarget: PathFragment?
        )

        /** Fulfills [com.google.devtools.build.lib.actions.AbstractAction.getMnemonic]  */
        fun getMnemonic(): String?

        /**
         * Fulfills [com.google.devtools.build.lib.actions.AbstractAction.getRawProgressMessage]
         */
        fun getRawProgressMessage(): String?

        /**
         * Fulfills [AbstractFileWriteAction.isRemotable].
         * 
         * @return
         */
        fun isRemotable(): Boolean

        /** Whether the manifest includes absolute paths to artifacts.  */
        fun emitsAbsolutePaths(): Boolean
    }

    /** The runfiles for which to create the symlink tree.  */
    private val runfiles: com.google.devtools.build.lib.analysis.Runfiles

    private val remotableSourceManifestActions: Boolean

    private var symlinkArtifacts: NestedSet<Artifact?>? = null

    /**
     * Creates a new AbstractSourceManifestAction instance using latin1 encoding to write the manifest
     * file and with a specified root path for manifest entries.
     * 
     * @param manifestWriter the strategy to use to write manifest entries
     * @param owner the action owner
     * @param primaryOutput the file to which to write the manifest
     * @param runfiles runfiles
     */
    @com.google.common.annotations.VisibleForTesting
    internal constructor(
        manifestWriter: ManifestWriter,
        owner: ActionOwner?,
        primaryOutput: Artifact?,
        runfiles: com.google.devtools.build.lib.analysis.Runfiles
    ) : this(manifestWriter, owner, primaryOutput, runfiles, null, false)

    /**
     * Creates a new AbstractSourceManifestAction instance using latin1 encoding to write the manifest
     * file and with a specified root path for manifest entries.
     * 
     * @param manifestWriter the strategy to use to write manifest entries
     * @param owner the action owner
     * @param primaryOutput the file to which to write the manifest
     * @param runfiles runfiles
     * @param repoMappingManifest the repository mapping manifest for runfiles
     */
    init {
        // The real set of inputs is computed in #getInputs().
        this.runfiles = runfiles
        this.repoMappingManifest = repoMappingManifest
        this.remotableSourceManifestActions = remotableSourceManifestActions
    }

    /**
     * The manifest entry for a symlink artifact should contain the target of the symlink rather than
     * its exec path. Reading the symlink target requires that the symlink artifact is declared as an
     * input of this action. Since declaring all runfiles as inputs of the manifest action would
     * unnecessarily delay its execution, this action exceptionally overrides [ ][AbstractAction.getInputs] and filters out the non-symlink runfiles by flattening the nested
     * set of runfiles. Benchmarks confirmed that this does not regress performance.
     * 
     * 
     * Alternatives considered:
     * 
     * 
     *  * Having users separate normal artifacts from symlink artifacts during analysis: Makes it
     * impossible to pass symlink artifacts to rules that aren't aware of them and requires the
     * use of custom providers to pass symlinks to stage as inputs to actions.
     *  * Reaching into [ActionExecutionContext] to look up the generating action of symlink
     * artifacts and retrieving the target from [UnresolvedSymlinkAction]: This would not
     * work for symlinks whose target is determined in the execution phase.
     *  * Input discovery: Complex and error-prone in general and conceptually not necessary here -
     * we already know what the inputs will be during analysis, we just want to delay the
     * required computations.
     * 
     */
    @kotlin.jvm.Synchronized
    public override fun getInputs(): NestedSet<Artifact?>? {
        if (symlinkArtifacts == null) {
            val symlinks: com.google.common.collect.ImmutableList<Artifact?>? =
                runfiles.getArtifacts().toList().stream()
                    .filter(Artifact::isSymlink)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            symlinkArtifacts = NestedSetBuilder.wrap(Order.STABLE_ORDER, symlinks)
        }
        return symlinkArtifacts
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class)
    fun writeTo(out: java.io.OutputStream, eventHandler: com.google.devtools.build.lib.events.EventHandler?) {
        writeFile(
            out, runfiles.getRunfilesInputs(repoMappingManifest),  /* inputMetadataProvider= */null
        )
    }

    /**
     * Get the contents of a file internally using an in memory output stream.
     * 
     * @return returns the file contents as a string.
     */
    @Throws(IOException::class)
    public override fun getFileContents(eventHandler: com.google.devtools.build.lib.events.EventHandler?): String? {
        val stream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        writeTo(stream, eventHandler)
        return stream.toString(java.nio.charset.StandardCharsets.ISO_8859_1)
    }

    @Throws(IOException::class)
    public override fun getStarlarkContent(): String? {
        return getFileContents(null)
    }

    @Throws(ExecException::class)
    override fun newDeterministicWriter(ctx: ActionExecutionContext): DeterministicWriter {
        val eventHandler: StoredEventHandler = StoredEventHandler()
        val seenNestedRunfilesTree = booleanArrayOf(false)
        val receiver: RunfilesConflictReceiver =
            object : RunfilesConflictReceiver {
                override fun nestedRunfilesTree(runfilesTree: Artifact?) {
                    seenNestedRunfilesTree[0] = true
                    eventHandler.handle(
                        com.google.devtools.build.lib.events.Event.error(
                            getOwner().getLocation(),
                            "Runfiles must not contain runfiles tree artifacts: " + runfilesTree
                        )
                    )
                }

                override fun prefixConflict(message: String?) {
                    val eventKind: com.google.devtools.build.lib.events.EventKind =
                        when (runfiles.getConflictPolicy()) {
                            ConflictPolicy.ERROR -> com.google.devtools.build.lib.events.EventKind.ERROR
                            ConflictPolicy.WARN -> com.google.devtools.build.lib.events.EventKind.WARNING
                        }
                    eventHandler.handle(
                        com.google.devtools.build.lib.events.Event.of(
                            eventKind,
                            getOwner().getLocation(),
                            message
                        )
                    )
                }
            }

        val runfilesInputs: MutableMap<PathFragment?, Artifact?> =
            runfiles.getRunfilesInputs(receiver, repoMappingManifest)
        eventHandler.replayOn(ctx.getEventHandler())
        if (seenNestedRunfilesTree[0]) {
            val failureDetail: FailureDetail? =
                FailureDetail.newBuilder()
                    .setMessage("Cannot create input manifest for runfiles tree")
                    .setAnalysis(
                        FailureDetails.Analysis.newBuilder()
                            .setCode(FailureDetails.Analysis.Code.INVALID_RUNFILES_TREE)
                    )
                    .build()
            throw UserExecException(failureDetail)
        }
        return DeterministicWriter { out -> writeFile(out, runfilesInputs, ctx.getInputMetadataProvider()) }
    }

    override fun isRemotable(): Boolean {
        return remotableSourceManifestActions || manifestWriter.isRemotable()
    }

    /**
     * Sort the entries in both the normal and root manifests and write the output file.
     * 
     * @param out is the message stream to write errors to.
     * @param output The actual mapping of the output manifest.
     * @param inputMetadataProvider The input metadata provider if available.
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun writeFile(
        out: java.io.OutputStream,
        output: MutableMap<PathFragment?, Artifact?>,
        inputMetadataProvider: InputMetadataProvider?
    ) {
        val manifestFile: java.io.Writer =
            BufferedWriter(OutputStreamWriter(out, java.nio.charset.StandardCharsets.ISO_8859_1))
        val sortedManifest: MutableList<MutableMap.MutableEntry<PathFragment?, Artifact?>> =
            java.util.ArrayList<MutableMap.MutableEntry<PathFragment?, Artifact?>>(output.entrySet())
        sortedManifest.sort(ENTRY_COMPARATOR)
        for (line in sortedManifest) {
            val artifact: Artifact? = line.getValue()
            val symlinkTarget: PathFragment?
            if (artifact == null) {
                symlinkTarget = null
            } else if (artifact.isSymlink()) {
                if (inputMetadataProvider != null) {
                    val metadata: FileArtifactValue =
                        checkNotNull(
                            inputMetadataProvider.getInputMetadata(artifact),
                            "missing metadata for %s",
                            artifact
                        )
                    symlinkTarget =
                        PathFragment.createAlreadyNormalized(metadata.getUnresolvedSymlinkTarget())
                } else {
                    symlinkTarget = artifact.getPath().readSymbolicLink()
                }
            } else {
                symlinkTarget = artifact.getPath().asFragment()
            }
            manifestWriter.writeEntry(manifestFile, line.getKey(), symlinkTarget)
        }

        manifestFile.flush()
    }

    override fun getMnemonic(): String? {
        return manifestWriter.getMnemonic()
    }

    override fun getRawProgressMessage(): String {
        return manifestWriter.getRawProgressMessage() + " for " + getOwner().getLabel()
    }

    protected override fun computeKey(
        actionKeyContext: ActionKeyContext,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addString(GUID)
        fp.addBoolean(remotableSourceManifestActions)
        runfiles.fingerprint(actionKeyContext, fp, manifestWriter.emitsAbsolutePaths())
        fp.addBoolean(repoMappingManifest != null)
        if (repoMappingManifest != null) {
            fp.addPath(repoMappingManifest.getExecPath())
        }
    }

    public override fun describeKey(): String? {
        return java.lang.String.format(
            "GUID: %s\nremotableSourceManifestActions: %s\nrunfiles: %s\n",
            GUID,
            remotableSourceManifestActions,
            runfiles.describeFingerprint(manifestWriter.emitsAbsolutePaths())
        )
    }

    /** Supported manifest writing strategies.  */
    enum class ManifestType : ManifestWriter {
        /**
         * Writes each line as:
         * 
         * 
         * [rootRelativePath] [resolvingSymlink]
         * 
         * 
         * If rootRelativePath contains spaces, then each backslash is replaced with '\b', each space
         * is replaced with '\s' and the line is prefixed with a space.
         * 
         * 
         * This strategy is suitable for creating an input manifest to a source view tree. Its output
         * is a valid input to [com.google.devtools.build.lib.analysis.actions.SymlinkTreeAction].
         */
        SOURCE_SYMLINKS {
            @Throws(IOException::class)
            override fun writeEntry(
                manifestWriter: java.io.Writer,
                rootRelativePath: PathFragment,
                symlinkTarget: PathFragment?
            ) {
                val rootRelativePathString: String = rootRelativePath.getPathString()
                // Source paths with spaces require escaping. Target paths with spaces don't as consumers
                // are expected to split on the first space. Newlines always need to be escaped.
                // Note that if any of these characters are present, then we also need to escape the escape
                // character (backslash) in both paths. We avoid doing so if none of the problematic
                // characters are present for backwards compatibility with existing runfiles libraries. In
                // particular, entries with a source path that contains neither spaces nor newlines and
                // target paths that contain both spaces and backslashes require no escaping.
                val needsEscaping =
                    rootRelativePathString.indexOf(' '.code) != -1 || rootRelativePathString.indexOf('\n'.code) != -1 || (symlinkTarget != null && symlinkTarget.getPathString()
                        .indexOf('\n') !== -1)
                if (needsEscaping) {
                    manifestWriter.append(' ')
                    manifestWriter.append(ROOT_RELATIVE_PATH_ESCAPER.escape(rootRelativePathString))
                } else {
                    manifestWriter.append(rootRelativePathString)
                }
                // This trailing whitespace is REQUIRED to process the single entry line correctly.
                manifestWriter.append(' ')
                if (symlinkTarget != null) {
                    if (needsEscaping) {
                        manifestWriter.append(TARGET_PATH_ESCAPER.escape(symlinkTarget.getPathString()))
                    } else {
                        manifestWriter.append(symlinkTarget.getPathString())
                    }
                }
                manifestWriter.append('\n')
            }

            override fun getMnemonic(): String {
                return "SourceSymlinkManifest"
            }

            override fun getRawProgressMessage(): String {
                return "Creating source manifest"
            }

            override fun isRemotable(): Boolean {
                // There is little gain to remoting these, since they include absolute path names inline.
                return false
            }

            override fun emitsAbsolutePaths(): Boolean {
                return true
            }
        },

        /**
         * Writes each line as:
         * 
         * 
         * [rootRelativePath]
         * 
         * 
         * This strategy is suitable for an input into a packaging system (notably .par) that
         * consumes a list of all source files but needs that list to be constant with respect to how
         * the user has their client laid out on local disk.
         */
        SOURCES_ONLY {
            @Throws(IOException::class)
            override fun writeEntry(
                manifestWriter: java.io.Writer,
                rootRelativePath: PathFragment,
                symlinkTarget: PathFragment?
            ) {
                manifestWriter.append(rootRelativePath.getPathString())
                manifestWriter.append('\n')
                manifestWriter.flush()
            }

            override fun getMnemonic(): String {
                return "PackagingSourcesManifest"
            }

            override fun getRawProgressMessage(): String {
                return "Creating file sources list"
            }

            override fun isRemotable(): Boolean {
                // Source-only symlink manifest has root-relative paths and does not include absolute paths.
                return true
            }

            override fun emitsAbsolutePaths(): Boolean {
                return false
            }
        }
    }

    companion object {
        private const val GUID = "07459553-a3d0-4d37-9d78-18ed942470f4"

        private val ENTRY_COMPARATOR: java.util.Comparator<MutableMap.MutableEntry<PathFragment?, Artifact?>?>? =
            java.util.Comparator.comparing<MutableMap.MutableEntry<PathFragment?, Artifact?>?, Any?>(java.util.function.Function { path: MutableMap.MutableEntry<PathFragment?, Artifact?>? ->
                path.getKey().getPathString()
            })
        private val ROOT_RELATIVE_PATH_ESCAPER: com.google.common.escape.Escaper =
            com.google.common.escape.CharEscaperBuilder()
                .addEscape(' ', "\\s")
                .addEscape('\n', "\\n")
                .addEscape('\\', "\\b")
                .toEscaper()
        private val TARGET_PATH_ESCAPER: com.google.common.escape.Escaper =
            com.google.common.escape.CharEscaperBuilder().addEscape('\n', "\\n").addEscape('\\', "\\b").toEscaper()
    }
}
