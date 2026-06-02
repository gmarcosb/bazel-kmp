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
package com.google.devtools.build.lib.exec

import com.github.luben.zstd.ZstdInputStream

/** Reconstructs an execution log in expanded format from the compact format representation.  */
class SpawnLogReconstructor(`in`: java.io.InputStream?) : MessageInputStream<SpawnExec?> {
    private val `in`: ZstdInputStream

    /** Represents a reconstructed input file, symlink, or directory.  */
    private interface Input {
        fun path(): String?

        class File(file: Protos.File?) : Input {
            override fun path(): String {
                return file.getPath()
            }

            val file: Protos.File?

            init {
                this.file = file
            }
        }

        class Symlink(symlink: Protos.File?) : Input {
            override fun path(): String {
                return symlink.getPath()
            }

            val symlink: Protos.File?

            init {
                this.symlink = symlink
            }
        }

        class Directory(val path: String?, files: MutableCollection<Protos.File?>?) : Input {
            val files: MutableCollection<Protos.File?>?

            init {
                this.files = files
            }
        }
    }

    // Stores both Inputs and InputSets. Bazel uses consecutive IDs starting from 1, so we can use
    // an ArrayList to store them together efficiently.
    private val inputMap: java.util.ArrayList<Any?> = java.util.ArrayList<Any?>()
    private var hashFunctionName: String? = ""
    private var workspaceRunfilesDirectory: String? = ""
    private var siblingRepositoryLayout = false

    init {
        this.`in` = ZstdInputStream(`in`)
        // Add a null entry for the 0th index as IDs are 1-based.
        inputMap.add(null)
    }

    @Throws(IOException::class)
    override fun read(): SpawnExec? {
        var entry: ExecLogEntry?
        while ((ExecLogEntry.parseDelimitedFrom(`in`).also { entry = it }) != null) {
            when (entry.getTypeCase()) {
                INVOCATION -> {
                    hashFunctionName = entry.getInvocation().getHashFunctionName()
                    workspaceRunfilesDirectory = entry.getInvocation().getWorkspaceRunfilesDirectory()
                    siblingRepositoryLayout = entry.getInvocation().getSiblingRepositoryLayout()
                }

                FILE -> putInput(entry.getId(), reconstructFile(entry.getFile()))
                DIRECTORY -> putInput(entry.getId(), reconstructDir(entry.getDirectory()))
                UNRESOLVED_SYMLINK -> putInput(entry.getId(), reconstructSymlink(entry.getUnresolvedSymlink()))
                RUNFILES_TREE -> putInput(entry.getId(), reconstructRunfilesDir(entry.getRunfilesTree()))
                INPUT_SET -> putInputSet(entry.getId(), entry.getInputSet())
                SYMLINK_ENTRY_SET -> putSymlinkEntrySet(entry.getId(), entry.getSymlinkEntrySet())
                SPAWN -> {
                    return reconstructSpawnExec(entry.getSpawn())
                }

                SYMLINK_ACTION -> {
                    // Symlink actions are not represented in the expanded format.
                }

                else -> throw IOException(
                    java.lang.String.format("unknown entry type %d", entry.getTypeCase().getNumber())
                )
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun reconstructSpawnExec(entry: ExecLogEntry.Spawn): SpawnExec {
        val builder: SpawnExec.Builder =
            SpawnExec.newBuilder()
                .addAllCommandArgs(entry.getArgsList())
                .addAllEnvironmentVariables(entry.getEnvVarsList())
                .setTargetLabel(entry.getTargetLabel())
                .setMnemonic(entry.getMnemonic())
                .setExitCode(entry.getExitCode())
                .setStatus(entry.getStatus())
                .setRunner(entry.getRunner())
                .setCacheHit(entry.getCacheHit())
                .setRemotable(entry.getRemotable())
                .setCacheable(entry.getCacheable())
                .setRemoteCacheable(entry.getRemoteCacheable())
                .setTimeoutMillis(entry.getTimeoutMillis())

        if (entry.hasMetrics()) {
            builder.setMetrics(entry.getMetrics())
        }

        if (entry.hasPlatform()) {
            builder.setPlatform(entry.getPlatform())
        }

        val inputs: SortedMap<String?, File?> = TreeMap<String?, File?>()
        visitInputSet(
            entry.getInputSetId(),
            java.util.function.Consumer { file: File? -> inputs.put(file.getPath(), file) },
            java.util.function.Consumer { input: Input? -> })
        val toolInputs: HashSet<String?> = HashSet<String?>()
        visitInputSet(
            entry.getToolSetId(),
            java.util.function.Consumer { file: File? -> toolInputs.add(file.getPath()) },
            java.util.function.Consumer { input: Input? -> })

        for (e in inputs.entrySet()) {
            var file: File = e.getValue()
            if (toolInputs.contains(e.getKey())) {
                file = file.toBuilder().setIsTool(true).build()
            }
            builder.addInputs(file)
        }

        val listedOutputs: SortedSet<String?> = TreeSet<String?>()

        for (output in entry.getOutputsList()) {
            when (output.getTypeCase()) {
                OUTPUT_ID -> {
                    val input = getInput(output.getOutputId())
                    listedOutputs.add(input.path())
                    when (input) {
                        -> builder.addActualOutputs(file)
                        -> builder.addActualOutputs(symlink)
                        -> builder.addAllActualOutputs(files)
                    }
                }

                INVALID_OUTPUT_PATH -> listedOutputs.add(output.getInvalidOutputPath())
                else -> throw IOException(
                    "unknown output type %d".formatted(output.getTypeCase().getNumber())
                )
            }
        }

        builder.addAllListedOutputs(listedOutputs)

        if (entry.hasDigest()) {
            builder.setDigest(entry.getDigest().toBuilder().setHashFunctionName(hashFunctionName))
        }

        return builder.build()
    }

    @Throws(IOException::class)
    private fun visitInputSet(
        inputSetId: Int,
        visitFile: java.util.function.Consumer<File?>,
        visitInput: java.util.function.Consumer<Input?>
    ) {
        if (inputSetId == 0) {
            return
        }
        val setsToVisit: ArrayDeque<Int?> = ArrayDeque<Int?>()
        val previousVisitCount: HashMap<Int?, Int?> = HashMap<Int?, Int?>()
        setsToVisit.push(inputSetId)
        while (!setsToVisit.isEmpty()) {
            val currentSetId: Int = setsToVisit.pop()
            // In case order matters (it does for runfiles, but not for inputs), we visit the set in
            // post-order (corresponds to Order#COMPILE_ORDER). Transitive sets are visited before direct
            // children; both are visited in left-to-right order.
            when (previousVisitCount.merge(
                currentSetId,
                0,
                java.util.function.BiFunction { oldValue: Int?, newValue: Int? -> 1 })) {
                0 -> {
                    // First visit, queue transitive sets for visit before revisiting the current set.
                    setsToVisit.push(currentSetId)
                    for (transitiveSetId in getInputSet(currentSetId).getTransitiveSetIdsList().reversed()) {
                        if (!previousVisitCount.containsKey(transitiveSetId)) {
                            setsToVisit.push(transitiveSetId)
                        }
                    }
                }

                1 -> {
                    // Second visit, visit the direct inputs only.
                    for (inputId in getInputSet(currentSetId).getInputIdsList()) {
                        if (previousVisitCount.put(inputId, 1) != null) {
                            continue
                        }
                        val input = getInput(inputId)
                        visitInput.accept(input)
                        when (input) {
                            -> visitFile.accept(file)
                            -> visitFile.accept(symlink)
                            -> files.forEach(visitFile)
                        }
                    }
                }

                else -> throw java.lang.IllegalStateException(
                    "expected visit count to be 0 or 1, was " + previousVisitCount.get(currentSetId)
                )
            }
        }
    }

    @Throws(IOException::class)
    private fun visitSymlinkEntries(
        runfilesTree: ExecLogEntry.RunfilesTree,
        rootSymlinks: Boolean,
        entryConsumer: java.util.function.BiConsumer<String?, MutableCollection<File>?>
    ) {
        val symlinkEntrySetId: Int =
            if (rootSymlinks) runfilesTree.getRootSymlinksId() else runfilesTree.getSymlinksId()
        if (symlinkEntrySetId == 0) {
            return
        }
        val setsToVisit: ArrayDeque<Int?> = ArrayDeque<Int?>()
        val previousVisitCount: HashMap<Int?, Int?> = HashMap<Int?, Int?>()
        setsToVisit.push(symlinkEntrySetId)
        while (!setsToVisit.isEmpty()) {
            val currentSetId: Int = setsToVisit.pop()
            // As order matters, we visit the set in post-order (corresponds to Order#COMPILE_ORDER).
            // Transitive sets are visited before direct children; both are visited in left-to-right
            // order.
            when (previousVisitCount.merge(
                currentSetId,
                0,
                java.util.function.BiFunction { oldValue: Int?, newValue: Int? -> 1 })) {
                0 -> {
                    // First visit, queue transitive sets for visit before revisiting the current set.
                    setsToVisit.push(currentSetId)
                    for (transitiveSetId in getSymlinkEntrySet(currentSetId).getTransitiveSetIdsList().reversed()) {
                        if (!previousVisitCount.containsKey(transitiveSetId)) {
                            setsToVisit.push(transitiveSetId)
                        }
                    }
                }

                1 -> {
                    // Second visit, visit the direct entries only.
                    for (pathAndInputId in getSymlinkEntrySet(currentSetId).getDirectEntriesMap().entrySet()) {
                        val runfilesTreeRelativePath: String?
                        if (rootSymlinks) {
                            runfilesTreeRelativePath = pathAndInputId.getKey()
                        } else if (pathAndInputId.getKey().startsWith("../")) {
                            runfilesTreeRelativePath = pathAndInputId.getKey().substring(3)
                        } else {
                            runfilesTreeRelativePath = workspaceRunfilesDirectory + "/" + pathAndInputId.getKey()
                        }
                        val path = runfilesTree.getPath() + "/" + runfilesTreeRelativePath
                        entryConsumer.accept(
                            runfilesTreeRelativePath,
                            reconstructRunfilesSymlinkTarget(path, pathAndInputId.getValue())
                        )
                    }
                }

                else -> throw java.lang.IllegalStateException(
                    "expected visit count to be 0 or 1, was " + previousVisitCount.get(currentSetId)
                )
            }
        }
    }

    private fun reconstructDir(dir: ExecLogEntry.Directory): Input.Directory {
        val builder: com.google.common.collect.ImmutableList.Builder<File?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<E?>(dir.getFilesCount())
        for (dirFile in dir.getFilesList()) {
            builder.add(reconstructFile(dir, dirFile))
        }
        return com.google.devtools.build.lib.exec.SpawnLogReconstructor.Input.Directory(dir.getPath(), builder.build())
    }

    private fun reconstructFile(entry: ExecLogEntry.File): Input.File {
        return com.google.devtools.build.lib.exec.SpawnLogReconstructor.Input.File(reconstructFile(null, entry))
    }

    private fun reconstructFile(
        parentDir: ExecLogEntry.Directory?, entry: ExecLogEntry.File
    ): File {
        val builder: File.Builder = File.newBuilder()
        builder.setPath(
            if (parentDir != null) parentDir.getPath() + "/" + entry.getPath() else entry.getPath()
        )
        if (entry.hasDigest()) {
            builder.setDigest(entry.getDigest().toBuilder().setHashFunctionName(hashFunctionName))
        }
        return builder.build()
    }

    @Throws(IOException::class)
    private fun reconstructRunfilesDir(runfilesTree: ExecLogEntry.RunfilesTree): Input.Directory {
        // In case of path collisions, runfiles should be collected in the following order, with
        // later sources overriding earlier ones (see
        // com.google.devtools.build.lib.analysis.Runfiles#getRunfilesInputs):
        //
        // 1. symlinks
        // 2. artifacts at canonical locations
        // 3. empty files
        // 4. root symlinks
        // 5. the _repo_mapping file with the repo mapping manifest
        // 6. the <workspace runfiles directory>/.runfile file (if the workspace runfiles directory
        //    wouldn't exist otherwise)
        //
        // Within each group represented by a nested set, the entries are traversed in postorder (i.e.
        // the transitive sets are visited before the direct children). This is important to resolve
        // conflicts in the same order as the real Runfiles implementation.
        val runfiles: LinkedHashMap<String?, File?> = LinkedHashMap<String?, File?>()
        val hasWorkspaceRunfilesDirectory = booleanArrayOf(false)

        visitSymlinkEntries(
            runfilesTree,  /* rootSymlinks= */
            false,
            java.util.function.BiConsumer { rootRelativePath: String?, files: MutableCollection<File>? ->
                hasWorkspaceRunfilesDirectory[0] = hasWorkspaceRunfilesDirectory[0] or
                        rootRelativePath.startsWith(workspaceRunfilesDirectory + "/")
                for (file in files!!) {
                    runfiles.put(file.getPath(), file)
                }
            })

        val flattenedArtifacts: LinkedHashSet<File?> = LinkedHashSet<File?>()
        visitInputSet(
            runfilesTree.getInputSetId(),
            java.util.function.Consumer { e: File? -> flattenedArtifacts.add(e) },  // This is bug-for-bug compatible with the implementation in Runfiles by considering
            // an empty non-external directory as a runfiles entry under the workspace runfiles
            // directory even though it won't be materialized as one.
            java.util.function.Consumer { input: Input? ->
                hasWorkspaceRunfilesDirectory[0] =
                    hasWorkspaceRunfilesDirectory[0] or hasWorkspaceRunfilesDirectory(input!!.path())
            })
        flattenedArtifacts.stream()
            .flatMap<Any?>(
                java.util.function.Function { file: File? ->
                    getRunfilesPaths(file.getPath())
                        .map<Any?>(
                            java.util.function.Function { relativePath: String? ->
                                file.toBuilder()
                                    .setPath(runfilesTree.getPath() + "/" + relativePath)
                                    .build()
                            })
                })
            .forEach(java.util.function.Consumer { file: Any? -> runfiles.put(file.getPath(), file) })

        for (emptyFile in runfilesTree.getEmptyFilesList()) {
            // Empty files are only created as siblings or parents of existing files, so they can't
            // by themselves create a workspace runfiles directory if it wouldn't exist otherwise.
            val newPath: String?
            if (emptyFile.startsWith("../")) {
                newPath = runfilesTree.getPath() + "/" + emptyFile.substring(3)
            } else {
                newPath = runfilesTree.getPath() + "/" + workspaceRunfilesDirectory + "/" + emptyFile
            }
            runfiles.put(newPath, File.newBuilder().setPath(newPath).build())
        }

        visitSymlinkEntries(
            runfilesTree,  /* rootSymlinks= */
            true,
            java.util.function.BiConsumer { rootRelativePath: String?, files: MutableCollection<File>? ->
                hasWorkspaceRunfilesDirectory[0] = hasWorkspaceRunfilesDirectory[0] or
                        rootRelativePath.startsWith(workspaceRunfilesDirectory + "/")
                for (file in files!!) {
                    runfiles.put(file.getPath(), file)
                }
            })

        if (runfilesTree.hasRepoMappingManifest()) {
            runfiles.put(
                REPO_MAPPING_MANIFEST,
                File.newBuilder()
                    .setPath(runfilesTree.getPath() + "/" + REPO_MAPPING_MANIFEST)
                    .setDigest(runfilesTree.getRepoMappingManifest().getDigest())
                    .build()
            )
        }

        if (!hasWorkspaceRunfilesDirectory[0]) {
            val dotRunfilePath: String? =
                "%s/%s/.runfile".formatted(runfilesTree.getPath(), workspaceRunfilesDirectory)
            runfiles.put(dotRunfilePath, File.newBuilder().setPath(dotRunfilePath).build())
        }
        // Copy to avoid retaining the entire runfiles map.
        return com.google.devtools.build.lib.exec.SpawnLogReconstructor.Input.Directory(
            runfilesTree.getPath(),
            com.google.common.collect.ImmutableList.copyOf<Protos.File?>(runfiles.values())
        )
    }

    private fun hasWorkspaceRunfilesDirectory(path: String?): Boolean {
        return extractRunfilesPath(path, siblingRepositoryLayout).group("repo") == null
    }

    private fun getRunfilesPaths(execPath: String?): java.util.stream.Stream<String?>? {
        val matchResult: java.util.regex.MatchResult = extractRunfilesPath(execPath, siblingRepositoryLayout)
        val repo: String? = matchResult.group("repo")
        val repoRelativePath: String? = matchResult.group("path")
        if (repo == null) {
            return java.util.stream.Stream.of<String?>(workspaceRunfilesDirectory + "/" + repoRelativePath)
        } else {
            val paths: java.util.stream.Stream.Builder<String?> = java.util.stream.Stream.builder<String?>()
            paths.add(repo + "/" + repoRelativePath)
            return paths.build()
        }
    }

    @Throws(IOException::class)
    private fun reconstructRunfilesSymlinkTarget(newPath: String?, targetId: Int): MutableCollection<File?> {
        if (targetId == 0) {
            return com.google.common.collect.ImmutableList.of<E?>(File.newBuilder().setPath(newPath).build())
        }
        return when (getInput(targetId)) {
            -> com.google.common.collect.ImmutableList.of<E?>(file.toBuilder().setPath(newPath).build())
            -> com.google.common.collect.ImmutableList.of<E?>(symlink.toBuilder().setPath(newPath).build())
            -> files.stream()
                .map<Any?>(
                    java.util.function.Function { file: File? ->
                        file.toBuilder()
                            .setPath(newPath + file.getPath().substring(path.length()))
                            .build()
                    })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        }
    }

    @Throws(IOException::class)
    private fun putInput(id: Int, input: Input?) {
        putEntry(id, input)
    }

    @Throws(IOException::class)
    private fun putInputSet(id: Int, inputSet: ExecLogEntry.InputSet?) {
        putEntry(id, inputSet)
    }

    @Throws(IOException::class)
    private fun putSymlinkEntrySet(id: Int, symlinkEntries: ExecLogEntry.SymlinkEntrySet?) {
        putEntry(id, symlinkEntries)
    }

    @Throws(IOException::class)
    private fun putEntry(id: Int, entry: Any?) {
        if (id == 0) {
            // The entry won't be referenced, so we don't need to store it.
            return
        }
        // Bazel emits consecutive non-zero IDs.
        if (id != inputMap.size()) {
            throw IOException(
                "ids must be consecutive, got %d after %d".formatted(id, inputMap.size())
            )
        }
        inputMap.add(
            when (entry) {
                -> file.file
                -> symlink.symlink
                else -> entry
            }
        )
    }

    @Throws(IOException::class)
    private fun getInput(id: Int): Input {
        val value: Any? = inputMap.get(id)
        return when (value) {
            -> input
            -> if (file.getSymlinkTargetPath()
                    .isEmpty()
            ) com.google.devtools.build.lib.exec.SpawnLogReconstructor.Input.File(file) else com.google.devtools.build.lib.exec.SpawnLogReconstructor.Input.Symlink(
                file
            )

            null -> throw IOException("referenced input %d is missing".formatted(id))
            else -> throw IOException("entry %d is not an input: %s".formatted(id, value))
        }
    }

    @Throws(IOException::class)
    private fun getInputSet(id: Int): ExecLogEntry.InputSet? {
        val value: Any? = inputMap.get(id)
        return when (value) {
            -> inputSet
            null -> throw IOException("referenced input set %d is missing".formatted(id))
            else -> throw IOException("entry %d is not an input set: %s".formatted(id, value))
        }
    }

    @Throws(IOException::class)
    private fun getSymlinkEntrySet(id: Int): ExecLogEntry.SymlinkEntrySet? {
        val value: Any? = inputMap.get(id)
        return when (value) {
            -> symlinkEntries
            null -> throw IOException("referenced set of symlink entries %d is missing".formatted(id))
            else -> throw IOException(
                "entry %d is not a set of symlink entries: %s".formatted(id, value)
            )
        }
    }

    @Throws(IOException::class)
    override fun close() {
        `in`.close()
    }

    companion object {
        // The path of the repo mapping manifest file under the runfiles tree.
        private const val REPO_MAPPING_MANIFEST = "_repo_mapping"

        // Examples:
        // * bazel-out/k8-fastbuild/bin/pkg/file.txt (repo: null, path: "pkg/file.txt")
        // * bazel-out/k8-fastbuild/bin/external/some_repo/pkg/file.txt (repo: "some_repo", path:
        //   "pkg/file.txt")
        private val DEFAULT_GENERATED_FILE_RUNFILES_PATH_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("(?:bazel|blaze)-out/[^/]+/[^/]+/(?:external/(?<repo>[^/]+)/)?(?<path>.+)")

        // Examples:
        // * pkg/file.txt (repo: null, path: "pkg/file.txt")
        // * external/some_repo/pkg/file.txt (repo: "some_repo", path: "pkg/file.txt")
        private val DEFAULT_SOURCE_FILE_RUNFILES_PATH_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("(?:external/(?<repo>[^/]+)/)?(?<path>.+)")

        // Examples:
        // * bazel-out/k8-fastbuild/bin/pkg/file.txt (repo: null, path: "pkg/file.txt")
        // * bazel-out/some_repo/k8-fastbuild/bin/pkg/file.txt (repo: "some_repo", path: "pkg/file.txt")
        // * bazel-out/k8-fastbuild/k8-fastbuild/bin/pkg/file.txt (repo: "k8-fastbuild", path:
        //   "pkg/file.txt")
        //
        // Repo names are distinguished from mnemonics via a positive lookahead on the following segment,
        // which in the case of a repo name is a mnemonic and thus contains a hyphen, whereas a mnemonic
        // is followed by an output directory name, which does not contain a hyphen unless it is
        // "coverage-metadata" (which in turn is not likely to be a mnemonic).
        private val SIBLING_LAYOUT_GENERATED_FILE_RUNFILES_PATH_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile(
                "(?:bazel|blaze)-out/(?:(?<repo>[^/]+(?=/[^/]+-[^/]+/)(?!/coverage-metadata/))/)?[^/]+/[^/]+/(?<path>.+)"
            )

        // Examples:
        // * pkg/file.txt (repo: null, path: "pkg/file.txt")
        // * ../some_repo/pkg/file.txt (repo: "some_repo", path: "pkg/file.txt")
        private val SIBLING_LAYOUT_SOURCE_FILE_RUNFILES_PATH_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("(?:\\.\\./(?<repo>[^/]+)/)?(?<path>.+)")

        private fun reconstructSymlink(entry: ExecLogEntry.UnresolvedSymlink): Input.Symlink {
            return com.google.devtools.build.lib.exec.SpawnLogReconstructor.Input.Symlink(
                File.newBuilder()
                    .setPath(entry.getPath())
                    .setSymlinkTargetPath(entry.getTargetPath())
                    .build()
            )
        }

        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        fun extractRunfilesPath(execPath: String?, siblingRepositoryLayout: Boolean): java.util.regex.MatchResult {
            var matcher: java.util.regex.Matcher =
                (if (siblingRepositoryLayout)
                    SIBLING_LAYOUT_GENERATED_FILE_RUNFILES_PATH_PATTERN
                else
                    DEFAULT_GENERATED_FILE_RUNFILES_PATH_PATTERN)
                    .matcher(execPath)
            if (matcher.matches()) {
                return matcher
            }
            matcher =
                (if (siblingRepositoryLayout)
                    SIBLING_LAYOUT_SOURCE_FILE_RUNFILES_PATH_PATTERN
                else
                    DEFAULT_SOURCE_FILE_RUNFILES_PATH_PATTERN)
                    .matcher(execPath)
            com.google.common.base.Preconditions.checkState(matcher.matches())
            return matcher
        }
    }
}
