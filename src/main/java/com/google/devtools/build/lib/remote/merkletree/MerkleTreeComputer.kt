// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.merkletree

import com.github.benmanes.caffeine.cache.Cache
import com.google.common.base.Preconditions
import com.google.common.base.Predicates
import com.google.common.base.Throwables
import com.google.common.collect.*
import com.google.common.util.concurrent.AsyncCallable
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.devtools.build.lib.util.TestType
import com.google.devtools.build.lib.vfs.Dirent
import com.google.devtools.build.lib.vfs.Path
import java.util.concurrent.Future
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier
import kotlin.Any
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Comparator
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.Long
import kotlin.RuntimeException
import kotlin.String
import kotlin.Throwable
import kotlin.Unit
import kotlin.UnsupportedOperationException
import kotlin.collections.ArrayList
import kotlin.collections.MutableCollection
import kotlin.collections.MutableIterator
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.plus

/**
 * Computes a Merkle tree for a set of inputs, as expected by [Action.getInputRootDigest]
 * 
 * 
 * Remote execution should allow a developer to run up to thousands of actions remotely in
 * parallel on a regular machine. As a result, this class is optimized with the following goals in
 * the order of decreasing importance:
 * 
 * 
 *  * Above all else, keep peak memory usage as low as possible so that Bazel doesn't OOM when
 * running many remote execution actions in parallel. Allocations with sizes linear in the
 * number of spawn inputs should be avoided if possible and kept as small as possible.
 *  * Make incremental builds as fast as possible.
 *  * Keep the size of caches kept between builds as small as possible.
 */
class MerkleTreeComputer(
    digestUtil: DigestUtil,
    remoteExecutionCache: MerkleTreeUploader?,
    buildRequestId: String?,
    commandId: String?,
    workspaceName: String?
) {
    fun of()
    private val digestUtil: DigestUtil
    private val merkleTreeUploader: MerkleTreeUploader?
    private val buildRequestId: String?
    private val commandId: String?
    private val workspaceName: String?
    private val emptyDigest: Digest
    private val emptyTree: Uploadable
    private val inFlightComputations: TaskDeduplicator<InFlightCacheKey?, RootOnly?> = TaskDeduplicator()

    /** Specifies which blobs should be retained in the Merkle tree.  */
    enum class BlobPolicy {
        /**
         * No blobs are retained and the returned MerkleTree is a [MerkleTree.RootOnly].
         * 
         * 
         * This is the most lightweight policy. It always suffices when checking for a remote cache
         * hit and may suffice for remote execution when a cache hit is expected.
         */
        DISCARD,

        /**
         * Retains all blobs in the tree that aren't contained in subtrees that have already been
         * uploaded.
         * 
         * 
         * Only blobs that have been uploaded to the remote cache during the lifetime of the Bazel
         * server are omitted from this tree. This usually suffices for remote execution unless the
         * remote cache loses entries.
         */
        KEEP,

        /**
         * Retains all blobs in the tree and also forces the reupload of all subtrees.
         * 
         * 
         * This is only needed in exceptional cases, such as when the remote cache has lost entries
         * while the Bazel server is running.
         */
        KEEP_AND_REUPLOAD,
    }

    /**
     * The key type for the cache used to deduplicate ongoing computations and possibly uploading of
     * sub-Merkle trees.
     * 
     * @param metadata the metadata of the aggregate [ActionInput] that forms the subtree
     * @param isTool whether the subtree consists of tool inputs
     * @param uploadBlobs whether the blobs in this tree will be uploaded
     */
    private class InFlightCacheKey(metadata: FileArtifactValue?, isTool: Boolean, uploadBlobs: Boolean) {
        val metadata: FileArtifactValue?
        val isTool: Boolean
        val uploadBlobs: Boolean

        init {
            this.metadata = metadata
            this.isTool = isTool
            this.uploadBlobs = uploadBlobs
        }
    }

    /**
     * Builds a Merkle tree for the inputs of a [Spawn].
     * 
     * @param toolInputs the set of paths of inputs that are considered tools. Note that these paths
     * are not exec paths, but those returned as keys by [     ][com.google.devtools.build.lib.exec.SpawnInputExpander.getInputMapping], i.e., they have
     * already been subject to path mapping and runfiles tree as well as tree artifact expansion.
     * Callers have to ensure that paths within an aggregate artifact are either all tools or all
     * non-tools.
     * @param scrubber the invocation-global scrubber, or null if no scrubbing should be performed
     * @param blobPolicy used to decide which blobs should be retained in the returned Merkle tree. If
     * `KEEP_AND_REUPLOAD` is used, all blobs in the tree are retained and the resulting
     * Merkle tree will be a [MerkleTree.Uploadable].
     * @throws LostInputsExecException if inputs to this spawn that are remote-only have been
     * discovered to be missing from the remote cache. Action or build rewinding may be able to
     * recover from this.
     */
    @Throws(IOException::class, InterruptedException::class, LostInputsExecException::class)
    fun buildForSpawn(
        spawn: Spawn,
        toolInputs: MutableSet<PathFragment?>,
        scrubber: Scrubber?,
        spawnExecutionContext: SpawnExecutionContext,
        remotePathResolver: RemotePathResolver,
        blobPolicy: BlobPolicy?
    ): MerkleTree? {
        Profiler.instance().profile("MerkleTreeComputer.buildForSpawn").use { c ->
            return doBuildForSpawn(
                spawn, toolInputs, scrubber, spawnExecutionContext, remotePathResolver, blobPolicy
            )
        }
    }

    @Throws(IOException::class, InterruptedException::class, LostInputsExecException::class)
    private fun doBuildForSpawn(
        spawn: Spawn,
        toolInputs: MutableSet<PathFragment?>,
        scrubber: Scrubber?,
        spawnExecutionContext: SpawnExecutionContext,
        remotePathResolver: RemotePathResolver,
        blobPolicy: BlobPolicy?
    ): MerkleTree? {
        // The scrubber is a per-invocation setting and invocations do not overlap, so it can be tracked
        // in a static variable.
        if (scrubber != lastScrubber) {
            synchronized(MerkleTreeComputer::class.java) {
                if (scrubber != lastScrubber) {
                    persistentToolSubTreeCache.invalidateAll()
                    persistentNonToolSubTreeCache.invalidateAll()
                    lastScrubber = scrubber
                }
            }
        }
        val spawnInputs: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            spawn.getInputFiles().toList()
        // Add output directories to inputs so that they are created as empty directories by the
        // executor. The spec only requires the executor to create the parent directory of an output
        // directory, which differs from the behavior of both local and sandboxed execution.
        val outputDirectories: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            spawn.getOutputFiles().stream()
                .filter({ output -> output is Artifact && output.isTreeArtifact() })
                .map({ outputDir -> EmptyInputDirectory(outputDir as Artifact?) })
                .collect(ImmutableList.toImmutableList<E?>())
        // Reduce peak memory usage by avoiding the allocation of intermediate arrays and sorted map, as
        // well as the prolonged retention of mapped paths. All of these can be reconstructed on-the-fly
        // while iterating over the inputs, only the sorted order has to be retained.
        val allInputs =
            ImmutableList.sortedCopyOf<Any?>(
                Comparator.comparing<Any?, PathFragment?>(
                    Function { input: Any? -> getOutputPath(input, remotePathResolver, spawn.getPathMapper()) },
                    PathFragment.HIERARCHICAL_COMPARATOR
                ),
                Companion.concat<Any?>(spawnInputs, outputDirectories)
            )
        val metadata: RequestMetadata? =
            TracingMetadataUtils.buildMetadata(
                buildRequestId, commandId, "subtree", spawn.getResourceOwner()
            )
        val remoteActionExecutionContext: RemoteActionExecutionContext =
            RemoteActionExecutionContext.Companion.create(
                spawn,
                spawnExecutionContext,
                metadata,
                CachePolicy.REMOTE_CACHE_ONLY,
                CachePolicy.NO_CACHE
            )
        val isToolInput: Predicate<PathFragment?>?
        if (toolInputs.isEmpty() || remotePathResolver.getWorkingDirectory().isEmpty()) {
            isToolInput = Predicate { o: PathFragment? -> toolInputs.contains(o) }
        } else {
            isToolInput =
                Predicate { path: PathFragment? -> toolInputs.contains(path.relativeTo(remotePathResolver.getWorkingDirectory())) }
        }
        try {
            return getFromFuture<MerkleTree?>(
                build(
                    Lists.transform<Any?, MutableMap.MutableEntry<K18106759?, V18106759?>?>(
                        allInputs,
                        com.google.common.base.Function { input: Any? ->
                            java.util.Map.entry<PathFragment?, Any?>(
                                getOutputPath(input, remotePathResolver, spawn.getPathMapper()), input
                            )
                        }),
                    isToolInput,
                    if (scrubber != null) scrubber.forSpawn(spawn) else null,
                    spawnExecutionContext.inputMetadataProvider,
                    spawnExecutionContext.getPathResolver(),
                    remoteActionExecutionContext,
                    remotePathResolver,
                    blobPolicy
                )
            )
        } catch (e: BulkTransferException) {
            e.getLostArtifacts(spawnExecutionContext.inputMetadataProvider::getInput)
                .throwIfNotEmpty()
            throw e
        }
    }

    /**
     * An [ActionInput] that is a child of another one at a given relative path.
     * 
     * 
     * This is used as a memory optimization as it avoids storing full absolute paths for children
     * of a source directory.
     */
    private class ChildActionInput(parent: ActionInput, relativePath: PathFragment) : BasicActionInput() {
        private val parent: ActionInput
        private val relativePath: String?

        init {
            this.parent = parent
            // Unwrap the PathFragment to save memory - it is not retained elsewhere.
            this.relativePath = relativePath.getPathString()
        }

        val execPath: PathFragment
            get() = parent.getExecPath().getRelative(relativePath)

        val execPathString: String?
            get() = this.execPath.getPathString()
    }

    /**
     * Adapts a [Path] to an [ActionInput].
     * 
     * 
     * This is only used for remote repository execution and tests and thus its memory usage
     * doesn't matter.
     */
    private class PathActionInput(val path: Path) : BasicActionInput() {
        val execPath: PathFragment?
            get() = path.asFragment()

        val execPathString: String?
            get() = path.asFragment().getPathString()
    }

    init {
        this.digestUtil = digestUtil
        this.merkleTreeUploader = remoteExecutionCache
        this.buildRequestId = buildRequestId
        this.commandId = commandId
        this.workspaceName = workspaceName
        val emptyBlob = ByteArray(0)
        this.emptyDigest = digestUtil.compute(emptyBlob)
        this.emptyTree =
            Uploadable(
                BlobsUploaded(emptyDigest, 0, 0), ImmutableSortedMap.of<Any?, Any?>()
            )
    }

    /**
     * Builds a Merkle tree for a set of files and their logical paths.
     * 
     * 
     * The only use outside testing is by repository rules. Use [.buildForSpawn] for
     * everything else.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun buildForFiles(inputs: MutableMap<PathFragment?, Path?>): Uploadable {
        Profiler.instance().profile("MerkleTreeComputer.buildForFiles").use { c ->
            // BlobPolicy.KEEP_AND_REUPLOAD always results in a MerkleTree.Uploadable.
            return ((Uploadable)
            <MerkleTree> MerkleTreeComputer . Companion . getFromFuture < T ? > (
                    build(
                        Lists.transform<MutableMap.MutableEntry<PathFragment?, Path?>?, MutableMap.MutableEntry<K18106812?, V18106812?>?>(
                            ImmutableList.sortedCopyOf<MutableMap.MutableEntry<PathFragment?, Path?>?>(
                                java.util.Map.Entry.comparingByKey<PathFragment?, Any?>(PathFragment.HIERARCHICAL_COMPARATOR),
                                inputs.entrySet()
                            ),
                            { e -> }<PathFragment, PathActionInput> java . util . Map . entry < K ?, V? > (e.getKey(),
                        PathActionInput(e.getValue())
                    )),
            TODO("Cannot convert element")
            ) < PathFragment > com.google.common.base.Predicates.alwaysFalse<kotlin.Any?>(),  /* spawnScrubber= */
            null,
            StaticInputMetadataProvider.empty(),
            PATH_ACTION_INPUT_RESOLVER,  /* remoteActionExecutionContext= */
            null,  /* remotePathResolver= */
            null,
            BlobPolicy.KEEP_AND_REUPLOAD))
        }
    }

    @Throws(IOException::class)
    private fun build(
        sortedInputs: MutableCollection<out MutableMap.MutableEntry<PathFragment?, out ActionInput?>>,
        isToolInput: Predicate<PathFragment?>,
        spawnScrubber: SpawnScrubber?,
        metadataProvider: InputMetadataProvider,
        artifactPathResolver: ArtifactPathResolver,
        remoteActionExecutionContext: RemoteActionExecutionContext?,
        remotePathResolver: RemotePathResolver?,
        blobPolicy: BlobPolicy?
    ): ListenableFuture<MerkleTree?> {
        return
        MerkleTree > Futures.transform<ImmutableMap<out MutableMap.MutableEntry<PathFragment?, out ActionInput?>?, RootOnly?>?, MerkleTree?>(
            precomputeSubTrees(
                sortedInputs,
                isToolInput,
                metadataProvider,
                artifactPathResolver,
                remoteActionExecutionContext,
                remotePathResolver,
                blobPolicy
            ),
            com.google.common.base.Function { subTreeRoots: ImmutableMap<out MutableMap.MutableEntry<PathFragment?, out ActionInput?>?, RootOnly?>? ->
                try {
                    return@transform buildWithPrecomputedSubTrees(
                        subTreeRoots,
                        sortedInputs,
                        isToolInput,
                        spawnScrubber,
                        metadataProvider,
                        artifactPathResolver,
                        blobPolicy
                    )
                } catch (e: IOException) {
                    throw MerkleTreeComputer.WrappedException(e)
                } catch (e: InterruptedException) {
                    throw MerkleTreeComputer.WrappedException(e)
                }
            },
            MERKLE_TREE_BUILD_POOL
        )
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun buildWithPrecomputedSubTrees(
        subTreeRoots: ImmutableMap<out MutableMap.MutableEntry<PathFragment?, out ActionInput?>?, RootOnly?>,
        sortedInputs: MutableCollection<out MutableMap.MutableEntry<PathFragment?, out ActionInput?>>,
        isToolInput: Predicate<PathFragment?>,
        spawnScrubber: SpawnScrubber?,
        metadataProvider: InputMetadataProvider,
        artifactPathResolver: ArtifactPathResolver,
        blobPolicy: BlobPolicy?
    ): MerkleTree? {
        if (sortedInputs.isEmpty()) {
            return emptyTree
        }

        var inputFiles: Long = 0
        var inputBytes: Long = 0
        val blobs: TreeMap<Any?, Any?> =
            TreeMap<Any?, Any?>(
                Uploadable.Companion.DIGEST_AND_METADATA_COMPARATOR
            )
        val directoryStack: Deque<Directory.Builder?> = ArrayDeque<Directory.Builder?>()
        directoryStack.push(Directory.newBuilder())

        var currentParent: PathFragment? = PathFragment.EMPTY_FRAGMENT
        var lastSourceDirPath: PathFragment? = null
        var lastEntry: MutableMap.MutableEntry<PathFragment, out ActionInput>? = null
        for (entry in Iterables.concat<MutableMap.MutableEntry<PathFragment, out Any?>>(
            sortedInputs,
            END_OF_INPUTS_SENTINEL
        )) {
            if (Thread.interrupted()) {
                throw InterruptedException()
            }

            val path: PathFragment = entry.getKey()
            // The same path may appear multiple times if the inputs are outputs of shared actions. Only
            // stage the first one.
            if (lastEntry != null && path == lastEntry.getKey()) {
                val previousInput: ActionInput = lastEntry.getValue()
                val currentInput: Any? = entry.getValue()
                Preconditions.checkState(
                    previousInput is Artifact
                            && currentInput is Artifact
                            && !previousInput.equals(currentInput) && OwnerlessArtifactWrapper(previousInput)
                        .equals(OwnerlessArtifactWrapper(currentInput)),
                    "Duplicate paths are only allowed for distinct shared artifacts, got: %s and %s at %s",
                    previousInput,
                    currentInput,
                    path
                )
                continue
            }
            lastEntry = entry
            if (spawnScrubber != null && spawnScrubber.shouldOmitInput(path)) {
                continue
            }
            if (lastSourceDirPath != null) {
                if (path.startsWith(lastSourceDirPath)) {
                    // The input is part of a source directory that has already been added to the tree.
                    continue
                }
                lastSourceDirPath = null
            }
            val input: ActionInput = entry.getValue()
            val newParent: PathFragment? = path.getParentDirectory()
            if (currentParent != newParent) {
                val commonPrefix: PathFragment?
                val fragmentToPop: PathFragment
                if (newParent != null) {
                    commonPrefix = findCommonPrefix(currentParent, newParent)
                    fragmentToPop = currentParent.relativeTo(commonPrefix)
                } else {
                    fragmentToPop = ROOT_FAKE_PATH_SEGMENT.getRelative(currentParent)
                    // Unused.
                    commonPrefix = null
                }
                for (dirToPop in fragmentToPop.splitToListOfSegments().reverse()) {
                    val directoryBlob: ByteArray? = directoryStack.pop().build().toByteArray()
                    val directoryBlobDigest: Digest = digestUtil.compute(directoryBlob)
                    if (blobPolicy != BlobPolicy.DISCARD && directoryBlobDigest.getSizeBytes() !== 0) {
                        blobs.putIfAbsent(directoryBlobDigest, directoryBlob)
                    }
                    inputBytes += directoryBlobDigest.getSizeBytes()
                    val topDirectory: Directory.Builder? = directoryStack.peek()
                    if (topDirectory == null) {
                        if (blobPolicy == BlobPolicy.DISCARD) {
                            // Make sure that we didn't unnecessarily retain any blobs.
                            Preconditions.checkState(blobs.isEmpty())
                            return BlobsDiscarded(
                                directoryBlobDigest, inputFiles, inputBytes
                            )
                        } else {
                            return Uploadable(
                                BlobsUploaded(
                                    directoryBlobDigest, inputFiles, inputBytes
                                ),
                                blobs
                            )
                        }
                    }
                    topDirectory
                        .addDirectoriesBuilder()
                        .setName(StringEncoding.internalToUnicode(dirToPop))
                        .setDigest(directoryBlobDigest)
                }
                for (i in 0..<newParent.segmentCount() - commonPrefix.segmentCount()) {
                    directoryStack.push(Directory.newBuilder())
                }
                currentParent = newParent
            }

            val currentDirectory: Directory.Builder =
                Preconditions.checkNotNull<Directory.Builder>(directoryStack.peek())
            val name: String? = StringEncoding.internalToUnicode(path.getBaseName())
            val nodeProperties: NodeProperties? = if (isToolInput.test(path)) TOOL_NODE_PROPERTIES else null

            when (input) {
                -> {
                    val subTreeRoot: RootOnly =
                        Preconditions.checkNotNull<RootOnly>(subTreeRoots.get(entry), "missing subtree for %s", input)
                    currentDirectory.addDirectoriesBuilder().setName(name).setDigest(subTreeRoot.digest())
                    inputFiles += subTreeRoot.inputFiles()
                    inputBytes += subTreeRoot.inputBytes()
                }

                -> {
                    val metadata: Any =
                        checkNotNull(
                            metadataProvider.getInputMetadata(symlink), "missing metadata: %s", symlink
                        )
                    val builder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        currentDirectory
                            .addSymlinksBuilder()
                            .setName(name)
                            .setTarget(StringEncoding.internalToUnicode(metadata.getUnresolvedSymlinkTarget()))
                    if (nodeProperties != null) {
                        builder.setNodeProperties(nodeProperties)
                    }
                    inputFiles++
                }

                -> {
                    val metadata: Any =
                        checkNotNull(
                            metadataProvider.getInputMetadata(fileOrSourceDirectory),
                            "missing metadata: %s",
                            fileOrSourceDirectory
                        )
                    if (metadata.getType() === FileStateType.DIRECTORY) {
                        val subTreeRoot: RootOnly =
                            Preconditions.checkNotNull<RootOnly>(
                                subTreeRoots.get(entry), "missing subtree for %s", input
                            )
                        currentDirectory.addDirectoriesBuilder().setName(name).setDigest(subTreeRoot.digest())
                        inputFiles += subTreeRoot.inputFiles()
                        inputBytes += subTreeRoot.inputBytes()
                        // The source directory subsumes all children paths, which may be staged separately as
                        // individual files or subdirectories. We rely on the inputs being sorted such that a
                        // path is directly succeeded by all its children.
                        // Note that this has subtle implications for the distinction between tool/non-tool
                        // inputs:
                        // - If a directory is added as a tool, then all files in it will be considered as tools
                        //   by the worker logic and are included in the combined worker hash. This applies even
                        //   if a file in that directory is also added as a non-tool input and thus skipping
                        //   over that file here is correct.
                        // - If a directory is added as a non-tool and a file in it is added as a tool, then
                        //   the file (but not the rest of the directory) will be considered as a tool and
                        //   included in the combined worker hash by the worker logic. However, since the file
                        //   is skipped over in the current method, its FileNode is not marked as a tool. Since
                        //   the worker hash still tracks the file, this doesn't cause staleness issues.
                        //   However, if the RE backend relies on the bazel_tool_input NodeProperty, it may
                        //   attempt to replace the file while the worker is still running, which can cause
                        //   issues.
                        //   TODO: Improve test coverage for remote persistent workers to catch these edge cases
                        //    and either fix them or prevent an action from having a directory as well as a file
                        //    in it as separate inputs.
                        lastSourceDirPath = path
                    } else {
                        val digest: Digest = DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize())
                        addFile(currentDirectory, name, digest, nodeProperties)
                        if (blobPolicy != BlobPolicy.DISCARD && digest.getSizeBytes() !== 0) {
                            // If there is both a Digest and a FileArtifactValue key for the same content, prefer
                            // the FileArtifactValue as it is retained anyway.
                            blobs.put(metadata, fileOrSourceDirectory)
                        }
                        inputFiles++
                        inputBytes += digest.getSizeBytes()
                    }
                }

                -> {
                    val digest: Digest = digestUtil.compute(virtualActionInput)
                    addFile(currentDirectory, name, digest, nodeProperties)
                    if (blobPolicy != BlobPolicy.DISCARD && digest.getSizeBytes() !== 0) {
                        blobs.putIfAbsent(digest, virtualActionInput)
                    }
                    inputFiles++
                    inputBytes += digest.getSizeBytes()
                }

                -> currentDirectory.addDirectoriesBuilder().setName(name).setDigest(emptyDigest)
                null -> {
                    // This is a sentinel value for an empty file. This case only occurs when this method is
                    // called from computeForRunfilesTreeIfAbsent.
                    addFile(currentDirectory, name, emptyDigest, nodeProperties)
                    inputFiles++
                }

                else -> {
                    // The input is not represented by a known subtype of ActionInput. Bare ActionInputs
                    // arise from exploded source directories, repository rules or tests.
                    val digest: Digest = digestUtil.compute(artifactPathResolver.toPath(input))
                    addFile(currentDirectory, name, digest, nodeProperties)
                    if (blobPolicy != BlobPolicy.DISCARD && digest.getSizeBytes() !== 0) {
                        blobs.putIfAbsent(digest, input)
                    }
                    inputFiles++
                    inputBytes += digest.getSizeBytes()
                }
            }
        }

        throw IllegalStateException("not reached")
    }

    @Throws(IOException::class)
    private fun precomputeSubTrees(
        sortedInputs: MutableCollection<out MutableMap.MutableEntry<PathFragment?, out ActionInput?>>,
        isToolInput: Predicate<PathFragment?>,
        metadataProvider: InputMetadataProvider,
        artifactPathResolver: ArtifactPathResolver,
        remoteActionExecutionContext: RemoteActionExecutionContext?,
        remotePathResolver: RemotePathResolver?,
        blobPolicy: BlobPolicy?
    ): ListenableFuture<ImmutableMap<out MutableMap.MutableEntry<PathFragment?, out ActionInput?>?, RootOnly?>?> {
        val subTreeFutures: ArrayList<ListenableFuture<MutableMap.MutableEntry<MutableMap.MutableEntry<PathFragment?, out ActionInput?>?, RootOnly?>?>?> =
            ArrayList<ListenableFuture<MutableMap.MutableEntry<MutableMap.MutableEntry<PathFragment?, out ActionInput?>?, RootOnly?>?>?>()
        for (entry in sortedInputs) {
            val future: ListenableFuture<RootOnly?>? =
                maybeCacheSubtree(
                    entry.getValue(),
                    entry.getKey(),
                    isToolInput,
                    metadataProvider,
                    artifactPathResolver,
                    remoteActionExecutionContext,
                    remotePathResolver,
                    blobPolicy
                )
            if (future != null) {
                TODO(
                    """
                    |Cannot convert element
                    |With text:
                    |subTreeFutures.add(<MerkleTree.RootOnly, Map.Entry<Map.Entry<PathFragment
                    """.trimMargin()
                )
                TODO(
                    """
                    |Cannot convert element
                    |With text:
                    |ActionInput>, MerkleTree.RootOnly>>transform(future, subTree -> <Map.Entry<PathFragment,?
                    """.trimMargin()
                )
                TODO(
                    """
                    |Cannot convert element
                    |With text:
                    |ActionInput>, MerkleTree.RootOnly>entry(entry, subTree), directExecutor()
                    """.trimMargin()
                )
            }
        }
        return
        Futures.transform<Any?, Any?>(TODO("Cannot convert element")) < java.util.Map.Entry < java.util.Map.Entry < PathFragment
        TODO(
            """
            |Cannot convert element
            |With text:
            |ActionInput>, MerkleTree.RootOnly>>allAsList(subTreeFutures), ImmutableMap::copyOf, directExecutor()
            """.trimMargin()
        )
    }

    @Throws(IOException::class)
    private fun maybeCacheSubtree(
        input: ActionInput?,
        mappedExecPath: PathFragment,
        isToolInput: Predicate<PathFragment?>,
        metadataProvider: InputMetadataProvider,
        artifactPathResolver: ArtifactPathResolver,
        remoteActionExecutionContext: RemoteActionExecutionContext?,
        remotePathResolver: RemotePathResolver?,
        blobPolicy: BlobPolicy?
    ): ListenableFuture<RootOnly?>? {
        return when (input) {
            -> computeForTreeArtifactIfAbsent(
                metadataProvider.getTreeMetadata(artifact),
                mappedExecPath,
                isToolInput,
                metadataProvider,
                artifactPathResolver,
                remoteActionExecutionContext,
                remotePathResolver,
                blobPolicy
            )

            -> computeForRunfilesTreeIfAbsent(
                metadataProvider.getRunfilesMetadata(artifact),
                mappedExecPath,
                isToolInput,
                metadataProvider,
                artifactPathResolver,
                remoteActionExecutionContext,
                remotePathResolver,
                blobPolicy
            )

            -> {
                val metadata: Any =
                    checkNotNull(
                        metadataProvider.getInputMetadata(artifact), "missing metadata: %s", artifact
                    )
                if (metadata.getType() !== FileStateType.DIRECTORY) {
                    null
                }
                computeIfAbsent(
                    metadata,
                    SortedInputsSupplier { explodeDirectory(artifact, artifactPathResolver).entrySet() },
                    isToolInput.test(mappedExecPath),
                    metadataProvider,
                    artifactPathResolver,
                    remoteActionExecutionContext,
                    remotePathResolver,
                    blobPolicy
                )
            }

            null -> null
        }
    }

    private fun computeForRunfilesTreeIfAbsent(
        runfilesArtifactValue: RunfilesArtifactValue,
        mappedExecPath: PathFragment,
        isToolInput: Predicate<PathFragment?>,
        metadataProvider: InputMetadataProvider,
        artifactPathResolver: ArtifactPathResolver,
        remoteActionExecutionContext: RemoteActionExecutionContext?,
        remotePathResolver: RemotePathResolver?,
        blobPolicy: BlobPolicy?
    ): ListenableFuture<RootOnly?>? {
        // A runfiles tree contains either only tool inputs or only non-tool inputs. It always contains
        // at least one artifact at its canonical location: the executable for which it has been
        // created.
        val artifactAtCanonicalLocation: Any =
            Preconditions.checkNotNull<Any?>(
                Companion.getOneElement<T?>(
                    runfilesArtifactValue
                        .getRunfilesTree()
                        .getArtifactsAtCanonicalLocationsForLogging()
                ),
                "runfiles tree contains no artifacts at canonical location: %s",
                mappedExecPath
            )
        val fullPath: PathFragment? =
            mappedExecPath
                .getChild(workspaceName)
                .getRelative(artifactAtCanonicalLocation.getRunfilesPath())
        val isTool = isToolInput.test(fullPath)
        // mappedExecPath and isToolInput must not be used below as they aren't part of the cache key -
        // use isTool instead.
        return computeIfAbsent(
            runfilesArtifactValue.getMetadata(),
            SortedInputsSupplier {
                ImmutableList.sortedCopyOf<E?>(
                    java.util.Map.Entry.comparingByKey<PathFragment?, Any?>(PathFragment.HIERARCHICAL_COMPARATOR),  // Values in this entry set may be null, which represents an empty runfile.
                    runfilesArtifactValue.getRunfilesTree().getMapping().entrySet()
                )
            },
            isTool,
            metadataProvider,
            artifactPathResolver,
            remoteActionExecutionContext,
            remotePathResolver,
            blobPolicy
        )
    }

    private fun computeForTreeArtifactIfAbsent(
        treeArtifactValue: TreeArtifactValue,
        mappedExecPath: PathFragment,
        isToolInput: Predicate<PathFragment?>,
        metadataProvider: InputMetadataProvider,
        artifactPathResolver: ArtifactPathResolver,
        remoteActionExecutionContext: RemoteActionExecutionContext?,
        remotePathResolver: RemotePathResolver?,
        blobPolicy: BlobPolicy?
    ): ListenableFuture<RootOnly?>? {
        // A tree artifact contains either only tool inputs or only non-tool inputs.
        val isTool =
            !treeArtifactValue.getChildren().isEmpty()
                    && isToolInput.test(
                mappedExecPath.getRelative(
                    treeArtifactValue.getChildren().first().getParentRelativePath()
                )
            )
        // mappedExecPath and isToolInput must not be used below as they aren't part of the cache key -
        // use isTool instead.
        return computeIfAbsent(
            treeArtifactValue.getMetadata(),
            SortedInputsSupplier {
                Lists.transform<TreeFileArtifact?, MutableMap.MutableEntry<PathFragment?, out ActionInput?>?>(
                    ImmutableList.sortedCopyOf<TreeFileArtifact?>(
                        Comparator.comparing<TreeFileArtifact?, PathFragment?>(
                            Artifact.TreeFileArtifact::getParentRelativePath, PathFragment.HIERARCHICAL_COMPARATOR
                        ),
                        treeArtifactValue.getChildren()
                    ),
                    com.google.common.base.Function { child: TreeFileArtifact? ->
                        java.util.Map.entry<K?, V?>(
                            child.getParentRelativePath(),
                            child
                        )
                    })
            },
            isTool,
            metadataProvider,
            artifactPathResolver,
            remoteActionExecutionContext,
            remotePathResolver,
            blobPolicy
        )
    }

    private interface SortedInputsSupplier {
        @Throws(IOException::class, InterruptedException::class)
        fun compute(): MutableCollection<out MutableMap.MutableEntry<PathFragment?, out ActionInput?>>?
    }

    private fun computeIfAbsent(
        metadata: FileArtifactValue?,
        sortedInputsSupplier: SortedInputsSupplier,
        isTool: Boolean,
        metadataProvider: InputMetadataProvider,
        artifactPathResolver: ArtifactPathResolver,
        remoteActionExecutionContext: RemoteActionExecutionContext?,
        remotePathResolver: RemotePathResolver?,
        blobPolicy: BlobPolicy?
    ): ListenableFuture<RootOnly?>? {
        val persistentCache: Cache<FileArtifactValue?, RootOnly?> =
            if (isTool) persistentToolSubTreeCache else persistentNonToolSubTreeCache
        if (blobPolicy == BlobPolicy.KEEP_AND_REUPLOAD) {
            persistentCache.invalidate(metadata)
        } else {
            val cachedRoot: RootOnly? = persistentCache.getIfPresent(metadata)
            if (cachedRoot != null
                && (blobPolicy == BlobPolicy.DISCARD
                        || cachedRoot is BlobsUploaded)
            ) {
                return Futures.immediateFuture<RootOnly?>(cachedRoot)
            }
        }
        val key = InFlightCacheKey(metadata, isTool, blobPolicy != BlobPolicy.DISCARD)
        val buildMerkleTreeTask: AsyncCallable<RootOnly?> =
            AsyncCallable {
                // There is a window in which a concurrent call may have removed the in-flight cache entry
                // while this one had already passed the check above. Recheck the persistent cache to
                // avoid unnecessary work.
                val cachedRoot: RootOnly? = persistentCache.getIfPresent(metadata)
                if (cachedRoot != null
                    && (blobPolicy == BlobPolicy.DISCARD
                            || cachedRoot is BlobsUploaded)
                ) {
                    return@AsyncCallable Futures.immediateFuture<RootOnly?>(cachedRoot)
                }
                // An ongoing computation with blobs can be reused for one that doesn't require them.
                if (blobPolicy == BlobPolicy.DISCARD) {
                    val inFlightComputation: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        inFlightComputations.maybeJoinExecution(
                            InFlightCacheKey(metadata, isTool,  /* uploadBlobs= */true)
                        )
                    if (inFlightComputation != null) {
                        return@AsyncCallable inFlightComputation
                    }
                }
                val merkleTreeFuture: ListenableFuture<MerkleTree?>
                try {
                    // Subtrees either consist entirely of tool inputs or don't contain any. The same
                    // applies to scrubbed inputs.
                    merkleTreeFuture =
                        build(
                            sortedInputsSupplier.compute(),
                            if (isTool) Predicates.alwaysTrue<PathFragment?>() else Predicates.alwaysFalse<PathFragment?>(),  /* spawnScrubber= */
                            null,
                            metadataProvider,
                            artifactPathResolver,
                            remoteActionExecutionContext,
                            remotePathResolver,
                            blobPolicy
                        )
                } catch (e: IOException) {
                    throw MerkleTreeComputer.WrappedException(e)
                } catch (e: InterruptedException) {
                    throw MerkleTreeComputer.WrappedException(e)
                }
                Futures.transform<MerkleTree?, RootOnly?>(
                    merkleTreeFuture,
                    com.google.common.base.Function { merkleTree: MerkleTree? ->
                        if (merkleTree is Uploadable) {
                            try {
                                if (merkleTreeUploader != null) {
                                    merkleTreeUploader.ensureInputsPresent(
                                        remoteActionExecutionContext,
                                        merkleTree,
                                        blobPolicy == BlobPolicy.KEEP_AND_REUPLOAD,
                                        remotePathResolver
                                    )
                                }
                            } catch (e: IOException) {
                                throw MerkleTreeComputer.WrappedException(e)
                            } catch (e: InterruptedException) {
                                throw MerkleTreeComputer.WrappedException(e)
                            }
                        }
                        // Move the computed root to the persistent cache so that it can be reused by later
                        // builds.
                        persistentCache
                            .asMap()
                            .compute(
                                metadata,
                                BiFunction { unused: FileArtifactValue?, oldRoot: RootOnly? ->
                                    if (oldRoot is BlobsUploaded)
                                        oldRoot
                                    else
                                        merkleTree!!.root()
                                })
                        merkleTree!!.root()
                    },
                    MERKLE_TREE_UPLOAD_POOL
                )
            }
        val buildMerkleTreeTaskSupplier: Supplier<ListenableFuture<RootOnly?>?> =
            Supplier { Futures.submitAsync<RootOnly?>(buildMerkleTreeTask, MERKLE_TREE_BUILD_POOL) }
        if (blobPolicy == BlobPolicy.KEEP_AND_REUPLOAD) {
            return inFlightComputations.executeUnconditionally(key, buildMerkleTreeTaskSupplier)
        } else {
            return inFlightComputations.executeIfNew(key, buildMerkleTreeTaskSupplier)
        }
    }

    private class EmptyInputDirectory(outputDir: Artifact) : BasicActionInput() {
        private val outputDir: Artifact

        init {
            Preconditions.checkArgument(outputDir.isTreeArtifact())
            this.outputDir = outputDir
        }

        val execPathString: String
            get() = outputDir.getExecPathString()

        val execPath: PathFragment
            get() = outputDir.getExecPath()
    }

    private class WrappedException : RuntimeException {
        private constructor(cause: IOException?) : super(cause)

        private constructor(cause: InterruptedException?) : super(cause)

        @Throws(IOException::class, InterruptedException::class)
        fun unwrapAndThrow() {
            Throwables.throwIfInstanceOf<IOException?>(getCause(), IOException::class.java)
            Throwables.throwIfInstanceOf<InterruptedException?>(getCause(), InterruptedException::class.java)
            throw IllegalStateException(getCause())
        }

        override fun fillInStackTrace(): Throwable {
            // Don't fill in the stack trace to avoid unnecessary overhead.
            return this
        }
    }

    companion object {
        // This class achieves its goals via the following observations:
        //
        // * Incremental builds typically have a large number of cache hits and the only information about
        //   a Merkle tree needed to check for a cache hit is the root digest. We can thus avoid
        //   materializing the full tree unless the cache check fails.
        // * Certain special artifacts are known to form self-contained Merkle trees that never intersect
        //   with any other Merkle tree. This includes tree artifacts, runfiles directories and source
        //   directories. The Merkle trees for such artifacts can be computed and uploaded to the remote
        //   cache independently. Only their root digest has to be kept around for inclusion in other
        //   Merkle trees.
        // * FileArtifactValue's fully describe their contents and can thus be used as cache keys for
        //   inter-build caches. By using a weak reference for the key, they can be cleaned up
        //   automatically as soon as their contents change or they are no longer relevant.
        // * Instead of basing caching decisions on a particular remote cache TTL, we can optimistically
        //   assume that every blob that has been uploaded is still in the cache if we support a mode in
        //   which all blobs can be forcibly recomputed and re-uploaded on missing digests.
        // * While the inputs of a spawn naturally form a map of paths to contents, this map doesn't have
        //   to be materialized in memory. Instead, it suffices to maintain a list of inputs that is
        //   sorted by lazily computed paths. This drastically reduces peak memory usage.
        // * When visiting the inputs of a spawn in hierarchical order, once a directory is left once,
        //   it will never be entered again. At that point, the proto describing it can be built and
        //   digested and intermediate structures are no longer needed.
        private val TOOL_NODE_PROPERTIES: NodeProperties? = NodeProperties.newBuilder()
            .addProperties(NodeProperty.newBuilder().setName("bazel_tool_input"))
            .build()
        private val END_OF_INPUTS_SENTINEL: ImmutableList<MutableMap.MutableEntry<PathFragment?, ActionInput?>?>? = null
        private val ROOT_FAKE_PATH_SEGMENT: PathFragment = PathFragment.create("root")

        // Building Merkle trees mostly involves computing hashes of protos and is thus CPU-bound.
        // TODO: Source directories are also visited on this pool in a single-threaded manner.
        private val MERKLE_TREE_BUILD_POOL: ExecutorService =
            Executors.newFixedThreadPool( // Run with reduced parallelism in tests to reproduce potential deadlocks more easily.
                Math.min(
                    if (TestType.isInTest()) 4 else Integer.MAX_VALUE,
                    Runtime.getRuntime().availableProcessors()
                ),
                Thread.ofPlatform().name("merkle-tree-build-", 0).factory()
            )

        // Uploading Merkle trees mostly involves waiting on networking futures, for which virtual threads
        // are ideal.
        private val MERKLE_TREE_UPLOAD_POOL: ExecutorService = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("merkle-tree-upload-", 0).factory()
        )

        private val persistentToolSubTreeCache: Cache<FileArtifactValue?, RootOnly?> =
            Caffeine.newBuilder().weakKeys().build<FileArtifactValue?, RootOnly?>()
        private val persistentNonToolSubTreeCache: Cache<FileArtifactValue?, RootOnly?> =
            Caffeine.newBuilder().weakKeys().build<FileArtifactValue?, RootOnly?>()

        // @GuardedBy("MerkleTreeComputer.class") for writes, reads use double-checked locking.
        @kotlin.concurrent.Volatile
        private var lastScrubber: Scrubber? = null

        private fun getOutputPath(
            input: ActionInput, remotePathResolver: RemotePathResolver, pathMapper: PathMapper
        ): PathFragment? {
            return remotePathResolver
                .getWorkingDirectory()
                .getRelative(pathMapper.map(input.getExecPath()))
        }

        val PATH_ACTION_INPUT_RESOLVER: ArtifactPathResolver = object : ArtifactPathResolver() {
            public override fun toPath(actionInput: ActionInput): Path {
                return (actionInput as PathActionInput).path
            }

            public override fun convertPath(path: Path?): Path? {
                throw UnsupportedOperationException()
            }

            public override fun transformRoot(root: Root?): Root? {
                throw UnsupportedOperationException()
            }
        }

        @Throws(IOException::class, InterruptedException::class)
        private fun <T> getFromFuture(future: Future<T?>): T? {
            try {
                return future.get()
            } catch (e: InterruptedException) {
                future.cancel( /* mayInterruptIfRunning= */true)
                throw e
            } catch (e: CancellationException) {
                // TODO(b/173153395): Drop the cause when the crashes with dynamic execution have been
                // diagnosed.
                val interruptedException = InterruptedException()
                interruptedException.initCause(e)
                throw interruptedException
            } catch (e: ExecutionException) {
                if (e.getCause() is WrappedException) {
                    wrappedException.unwrapAndThrow()
                    // Not reached.
                }
                Throwables.throwIfUnchecked(e.getCause())
                throw IllegalStateException(e)
            }
        }

        private fun addFile(
            directory: Directory.Builder,
            name: String?,
            digest: Digest?,
            nodeProperties: NodeProperties?
        ) {
            val builder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                directory
                    .addFilesBuilder()
                    .setName(name)
                    .setDigest(digest) // We always treat files as executable since Bazel will `chmod 555` on the output
                    // files of an action within ActionOutputMetadataStore#getMetadata after action
                    // execution if no metadata was injected. We can't use real executable bit of the
                    // file until this behavior is changed. See
                    // https://github.com/bazelbuild/bazel/issues/13262 for more details.
                    .setIsExecutable(true)
            if (nodeProperties != null) {
                builder.setNodeProperties(nodeProperties)
            }
        }

        private fun findCommonPrefix(path1: PathFragment, path2: PathFragment): PathFragment? {
            var commonSegments = 0
            val segments2: MutableIterator<String?> = path2.segments().iterator()
            for (segment in path1.segments()) {
                if (!segments2.hasNext()) {
                    break
                }
                val segment2 = segments2.next()
                if (segment != segment2) {
                    break
                }
                commonSegments++
            }
            return path1.subFragment(0, commonSegments)
        }

        @Throws(IOException::class, InterruptedException::class)
        private fun explodeDirectory(
            dir: Artifact, pathResolver: ArtifactPathResolver
        ): ImmutableSortedMap<PathFragment?, ActionInput?> {
            val inputs: ImmutableSortedMap.Builder<PathFragment?, ActionInput?> =
                ImmutableSortedMap.orderedBy<PathFragment?, ActionInput?>(PathFragment.HIERARCHICAL_COMPARATOR)
            val dirPath: Path = pathResolver.toPath(dir)
            explodeDirectory(dir, PathFragment.EMPTY_FRAGMENT, dirPath, inputs)
            return inputs.buildOrThrow()
        }

        @Throws(IOException::class, InterruptedException::class)
        private fun explodeDirectory(
            dir: Artifact,
            relPath: PathFragment,
            dirPath: Path,
            inputs: ImmutableMap.Builder<PathFragment?, ActionInput?>
        ) {
            if (Thread.interrupted()) {
                throw InterruptedException()
            }
            val entries: MutableCollection<Dirent> = dirPath.getRelative(relPath).readdir(Symlinks.FOLLOW)
            for (entry in entries) {
                val basename = entry.name
                val path: PathFragment = relPath.getChild(basename)
                when (entry.type) {
                    Dirent.Type.FILE -> inputs.put(path, ChildActionInput(dir, path))
                    Dirent.Type.DIRECTORY -> explodeDirectory(dir, path, dirPath, inputs)
                    else -> throw IOException(
                        "The file type of '%s' is not supported.".formatted(dirPath.getRelative(path))
                    )
                }
            }
        }

        private fun <T> getOneElement(nestedSet: NestedSet<T?>): T? {
            val leaves: ImmutableList<T?> = nestedSet.getLeaves()
            if (!leaves.isEmpty()) {
                return leaves.getFirst()
            }
            val nonLeaves: ImmutableList<NestedSet<T?>> = nestedSet.getNonLeaves()
            for (nonLeaf in nonLeaves) {
                val leaf: T? = getOneElement<T?>(nonLeaf)
                if (leaf != null) {
                    return leaf
                }
            }
            return null
        }

        /**
         * Returns an immutable view of the concatenation of two collections.
         * 
         * 
         * Use this over the unsized [Iterators.concat] to avoid intermediate allocations of
         * ArrayLists in methods such as [ImmutableList.sortedCopyOf].
         */
        private fun <T> concat(
            first: MutableCollection<out T?>, second: MutableCollection<out T?>
        ): MutableCollection<T?>? {
            if (first.isEmpty()) {
                return second as MutableCollection<T?>?
            }
            if (second.isEmpty()) {
                return first as MutableCollection<T?>
            }
            return object : AbstractCollection<T?>() {
                override fun iterator(): MutableIterator<T?> {
                    return Iterators.concat<T?>(first.iterator(), second.iterator())
                }

                override fun size(): Int {
                    return first.size() + second.size()
                }
            }
        }
    }
}
