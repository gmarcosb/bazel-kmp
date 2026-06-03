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
package com.google.devtools.build.lib.actions.cache

import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics

/**
 * An interface defining a cache of already-executed Actions.
 * 
 * 
 * The name of this class is misleading; it doesn't cache the actual actions, only a fingerprint
 * of all action properties that matter for cache invalidation (action key, path and contents of
 * input and outputs files, environment variables, execution properties, and certain flags), so we
 * can tell if we need to rerun an action given the current state of the file system.
 * 
 * 
 * Each action entry uses the path of the action's primary output as the key.
 */
@ThreadCompatible
interface ActionCache {
    /** Updates the cache entry for the specified key.  */
    fun put(key: String?, entry: Entry?)

    /**
     * Returns the cache entry for the specified key, or null if not found.
     * 
     * 
     * If an entry exists but is corrupted, returns [ActionCache.Entry.CORRUPTED]. Callers
     * should check [ActionCache.Entry.isCorrupted] before inspecting anything else on the
     * entry.
     */
    fun get(key: String?): Entry?

    /** Removes entry from cache  */
    fun remove(key: String?)

    /** Removes entry from cache that matches the predicate.  */
    fun removeIf(predicate: java.util.function.Predicate<Entry?>?)

    /** An action cache entry.  */
    class Entry internal constructor(
        digest: ByteArray?,
        discoveredInputPaths: com.google.common.collect.ImmutableList<String?>?,
        prunedInputs: Boolean,
        outputFileMetadata: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?>,
        outputTreeMetadata: com.google.common.collect.ImmutableMap<String?, SerializableTreeArtifactValue?>,
        proxyOutputs: com.google.common.collect.ImmutableList<String?>
    ) {
        // Digest of all relevant properties of the action for cache invalidation purposes.
        // Null if the entry is corrupted.
        private val digest: ByteArray?

        // List of input paths discovered by the action.
        // Null if the action does not discover inputs.
        private val discoveredInputPaths: com.google.common.collect.ImmutableList<String?>?

        private val prunedInputs: Boolean

        // Output metadata.
        // Only present when building without the bytes, and even then, only for remotely stored files.
        private val outputFileMetadata: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?>
        private val outputTreeMetadata: com.google.common.collect.ImmutableMap<String?, SerializableTreeArtifactValue?>
        private val proxyOutputs: com.google.common.collect.ImmutableList<String?>

        init {
            com.google.common.base.Preconditions.checkArgument(
                !prunedInputs || discoveredInputPaths != null,
                "Action had unused inputs but no discovered inputs"
            )
            this.digest = digest
            this.discoveredInputPaths = discoveredInputPaths
            this.prunedInputs = prunedInputs
            this.outputFileMetadata = outputFileMetadata
            this.outputTreeMetadata = outputTreeMetadata
            this.proxyOutputs = proxyOutputs
        }

        /** Returns whether this cache entry is corrupted and should be ignored.  */
        fun isCorrupted(): Boolean {
            return digest == null
        }

        /**
         * Returns a digest encoding all relevant properties of the action for cache invalidation
         * purposes.
         */
        fun getDigest(): ByteArray? {
            com.google.common.base.Preconditions.checkState(!isCorrupted())
            return digest
        }

        /** Returns whether the action discovers inputs.  */
        fun discoversInputs(): Boolean {
            com.google.common.base.Preconditions.checkState(!isCorrupted())
            return discoveredInputPaths != null
        }

        /**
         * Whether the action detected unused inputs.
         * 
         * 
         * If true, implies [.discoversInputs], and [.getDiscoveredInputPaths]
         * returns the used inputs.
         */
        fun prunedInputs(): Boolean {
            com.google.common.base.Preconditions.checkState(!isCorrupted())
            return prunedInputs
        }

        /**
         * Returns the list of discovered input paths, or null if the action does not discover inputs.
         */
        fun getDiscoveredInputPaths(): com.google.common.collect.ImmutableList<String?>? {
            com.google.common.base.Preconditions.checkState(!isCorrupted())
            return discoveredInputPaths
        }

        /** Gets the metadata of an output file.  */
        fun getOutputFile(output: Artifact): FileArtifactValue? {
            com.google.common.base.Preconditions.checkState(!isCorrupted())
            return outputFileMetadata.get(output.getExecPathString())
        }

        /** Gets the metadata of all output files.  */
        fun getOutputFiles(): com.google.common.collect.ImmutableMap<String?, FileArtifactValue?> {
            com.google.common.base.Preconditions.checkState(!isCorrupted())
            return outputFileMetadata
        }

        /** Gets the metadata of an output tree.  */
        fun getOutputTree(output: SpecialArtifact): SerializableTreeArtifactValue? {
            com.google.common.base.Preconditions.checkState(!isCorrupted())
            return outputTreeMetadata.get(output.getExecPathString())
        }

        /** Gets the metadata of all output trees.  */
        fun getOutputTrees(): com.google.common.collect.ImmutableMap<String?, SerializableTreeArtifactValue?> {
            com.google.common.base.Preconditions.checkState(!isCorrupted())
            return outputTreeMetadata
        }

        /**
         * Returns a list of exec path strings for [proxied][ProxyFileArtifactValue] outputs.
         */
        fun getProxyOutputs(): com.google.common.collect.ImmutableList<String?> {
            com.google.common.base.Preconditions.checkState(!isCorrupted())
            return proxyOutputs
        }

        /** Returns whether this entry stores any output metadata.  */
        fun hasOutputMetadata(): Boolean {
            com.google.common.base.Preconditions.checkState(!isCorrupted())
            return !outputFileMetadata.isEmpty() || !outputTreeMetadata.isEmpty() || !proxyOutputs.isEmpty()
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("digest", digest)
                .add("discoveredInputPaths", discoveredInputPaths)
                .add("outputFileMetadata", outputFileMetadata)
                .add("outputTreeMetadata", outputTreeMetadata)
                .add("proxyOutputs", proxyOutputs)
                .toString()
        }

        fun dump(out: PrintStream) {
            if (isCorrupted()) {
                out.println("  CORRUPTED")
                return
            }
            out.format(
                "  digest = %s\n",
                com.google.devtools.build.lib.actions.cache.ActionCache.Entry.Companion.formatDigest(digest)
            )
            if (discoveredInputPaths != null) {
                out.println("  discoveredInputPaths =")
                for (path in com.google.common.collect.ImmutableList.sortedCopyOf<String?>(discoveredInputPaths)) {
                    out.format("    %s\n", path)
                }
            }

            if (!outputFileMetadata.isEmpty()) {
                out.println("  outputFileMetadata =")
                for (path in com.google.common.collect.ImmutableList.sortedCopyOf<String?>(outputFileMetadata.keySet())) {
                    out.format("    %s = %s\n", path, outputFileMetadata.get(path))
                }
            }

            if (!outputTreeMetadata.isEmpty()) {
                out.println("  outputTreeMetadata =")
                for (path in com.google.common.collect.ImmutableList.sortedCopyOf<String?>(outputTreeMetadata.keySet())) {
                    out.format("    %s = %s\n", path, outputTreeMetadata.get(path))
                }
            }
        }

        /** Serializable representation of [TreeArtifactValue].  */
        class SerializableTreeArtifactValue(
            childValues: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?>?,
            archivedFileValue: java.util.Optional<FileArtifactValue?>?,
            resolvedPath: java.util.Optional<PathFragment?>?
        ) {
            val childValues: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?>?
            val archivedFileValue: java.util.Optional<FileArtifactValue?>?
            val resolvedPath: java.util.Optional<PathFragment?>?

            init {
                this.resolvedPath = resolvedPath
                this.archivedFileValue = archivedFileValue
                this.childValues = childValues
                java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<String?, FileArtifactValue?>?>(
                    childValues,
                    "childValues"
                )
                java.util.Objects.requireNonNull<java.util.Optional<FileArtifactValue?>?>(
                    archivedFileValue,
                    "archivedFileValue"
                )
                java.util.Objects.requireNonNull<java.util.Optional<PathFragment?>?>(resolvedPath, "resolvedPath")
            }

            companion object {
                /**
                 * Creates [SerializableTreeArtifactValue] from [TreeArtifactValue] by collecting
                 * children and archived artifact which are remote.
                 */
                fun create(treeMetadata: TreeArtifactValue): SerializableTreeArtifactValue {
                    val childValues: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?>? =
                        treeMetadata.getChildValues().entrySet().stream() // Only save remote tree file
                            .filter({ e -> e.getValue().isRemote() })
                            .collect(
                                com.google.common.collect.ImmutableMap.toImmutableMap<T?, K?, V?>(
                                    java.util.function.Function { e: T? -> e.getKey().getTreeRelativePathString() },
                                    java.util.function.Function { java.util.Map.Entry.getValue() })
                            )

                    // Only save remote archived artifact
                    val archivedFileValue: java.util.Optional<FileArtifactValue?>? =
                        treeMetadata
                            .getArchivedRepresentation()
                            .filter({ ar -> ar.archivedFileValue().isRemote() })
                            .map(ArchivedRepresentation::archivedFileValue)

                    val resolvedPath: java.util.Optional<PathFragment?>? = treeMetadata.getResolvedPath()

                    return SerializableTreeArtifactValue(childValues, archivedFileValue, resolvedPath)
                }
            }
        }

        /** A builder for an action cache entry.  */
        class Builder(
            private val actionKey: String?,
            discoversInputs: Boolean,
            clientEnv: com.google.common.collect.ImmutableMap<String?, String?>,
            actionExecutionSalt: String?,
            outputPermissions: OutputPermissions,
            useArchivedTreeArtifacts: Boolean
        ) {
            // Combined input and output metadata.
            private val metadataMap: HashMap<String?, FileArtifactValue?> = HashMap<String?, FileArtifactValue?>()

            private val clientEnv: com.google.common.collect.ImmutableMap<String?, String?>

            private val actionExecutionSalt: String?

            // Discovered inputs.
            // Null if the action does not discover inputs.
            private val discoveredInputPaths: com.google.common.collect.ImmutableList.Builder<String?>?
            private var prunedInputs = false

            private val outputFileMetadata: com.google.common.collect.ImmutableMap.Builder<String?, FileArtifactValue?> =
                com.google.common.collect.ImmutableMap.builder<String?, FileArtifactValue?>()
            private val outputTreeMetadata: com.google.common.collect.ImmutableMap.Builder<String?, SerializableTreeArtifactValue?> =
                com.google.common.collect.ImmutableMap.builder<String?, SerializableTreeArtifactValue?>()

            private val proxyOutputs: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()

            // Settings that affect the outcome of an action but aren't captured in the file metadata.
            private val outputPermissions: OutputPermissions
            private val useArchivedTreeArtifacts: Boolean

            /**
             * Creates a new builder.
             * 
             * @param discoversInputs whether the action discovers inputs.
             * @param outputPermissions the requested output permissions.
             * @param useArchivedTreeArtifacts whether archived tree artifacts are enabled.
             */
            init {
                this.clientEnv = clientEnv
                this.actionExecutionSalt = actionExecutionSalt
                this.discoveredInputPaths =
                    if (discoversInputs) com.google.common.collect.ImmutableList.builder<String?>() else null
                this.outputPermissions = outputPermissions
                this.useArchivedTreeArtifacts = useArchivedTreeArtifacts
            }

            /** Adds metadata of an input file.  */
            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun addInputFile(artifact: Artifact, metadata: FileArtifactValue?): Builder {
                addInputFile(artifact, metadata,  /* saveExecPath= */false)
                return this
            }

            /** Adds metadata of an input file.  */
            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun addInputFile(
                artifact: Artifact, metadata: FileArtifactValue?, saveExecPath: Boolean
            ): Builder {
                val execPath: String = artifact.getExecPathString()
                if (discoveredInputPaths != null && saveExecPath) {
                    discoveredInputPaths.add(execPath)
                }
                metadataMap.put(execPath, metadata)
                return this
            }

            /** Adds an output file.  */
            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun addOutputFile(output: Artifact, metadata: FileArtifactValue): Builder {
                return addOutputFile(output, metadata,  /* saveFileMetadata= */false)
            }

            /** Adds an output file.  */
            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun addOutputFile(
                output: Artifact, metadata: FileArtifactValue, saveFileMetadata: Boolean
            ): Builder {
                com.google.common.base.Preconditions.checkArgument(
                    !output.isTreeArtifact() && !output.isChildOfDeclaredDirectory(),
                    "Must use addOutputTree to save tree artifacts and their children: %s",
                    output
                )
                val execPath: String = output.getExecPathString()
                // Only save remote and proxy file metadata.
                if (saveFileMetadata) {
                    if (metadata.isRemote()) {
                        outputFileMetadata.put(execPath, metadata)
                    } else if (metadata is ProxyFileArtifactValue) {
                        proxyOutputs.add(execPath)
                    }
                }
                metadataMap.put(execPath, metadata)
                return this
            }

            /** Adds an output tree.  */
            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun addOutputTree(output: SpecialArtifact, metadata: TreeArtifactValue): Builder {
                return addOutputTree(output, metadata,  /* saveTreeMetadata= */false)
            }

            /** Adds an output tree.  */
            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun addOutputTree(
                output: SpecialArtifact, metadata: TreeArtifactValue, saveTreeMetadata: Boolean
            ): Builder {
                com.google.common.base.Preconditions.checkArgument(
                    output.isTreeArtifact(),
                    "artifact must be a tree artifact: %s",
                    output
                )
                val execPath: String = output.getExecPathString()
                if (saveTreeMetadata) {
                    if (!metadata.getChildValues().isEmpty()
                        && metadata.getChildValues().values().stream()
                            .allMatch({ obj: Any? -> ProxyFileArtifactValue::class.java.isInstance(obj) })
                    ) {
                        proxyOutputs.add(output.getExecPathString())
                    } else {
                        outputTreeMetadata.put(execPath, SerializableTreeArtifactValue.Companion.create(metadata))
                    }
                }
                metadataMap.put(execPath, metadata.getMetadata())
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setPrunedInputs(prunedInputs: Boolean): Builder {
                this.prunedInputs = prunedInputs
                return this
            }

            fun build(): Entry {
                return com.google.devtools.build.lib.actions.cache.ActionCache.Entry(
                    com.google.devtools.build.lib.actions.cache.ActionCache.Entry.Builder.Companion.computeDigest(
                        actionKey,
                        discoveredInputPaths != null,
                        metadataMap,
                        clientEnv,
                        actionExecutionSalt,
                        outputPermissions,
                        useArchivedTreeArtifacts
                    ),
                    if (discoveredInputPaths != null) discoveredInputPaths.build() else null,
                    prunedInputs,
                    outputFileMetadata.buildOrThrow(),
                    outputTreeMetadata.buildOrThrow(),
                    proxyOutputs.build()
                )
            }

            companion object {
                private fun computeDigest(
                    actionKey: String?,
                    discoversInputs: Boolean,
                    metadataMap: MutableMap<String?, FileArtifactValue?>,
                    clientEnv: MutableMap<String?, String?>,
                    actionExecutionSalt: String?,
                    outputPermissions: OutputPermissions,
                    useArchivedTreeArtifacts: Boolean
                ): ByteArray {
                    val fp: Fingerprint = Fingerprint()
                    fp.addString(actionKey)
                    fp.addBoolean(discoversInputs)
                    fp.addBytes(MetadataDigestUtils.fromMetadata(metadataMap))
                    fp.addBytes(
                        com.google.devtools.build.lib.actions.cache.ActionCache.Entry.Builder.Companion.computeMapDigest(
                            clientEnv
                        )
                    )
                    fp.addString(actionExecutionSalt)
                    fp.addInt(outputPermissions.getPermissionsMode())
                    fp.addBoolean(useArchivedTreeArtifacts)
                    return fp.digestAndReset()
                }

                private fun computeMapDigest(map: MutableMap<String?, String?>): ByteArray? {
                    var result: ByteArray? = ByteArray(0)
                    val fp: Fingerprint = Fingerprint()
                    for (entry in map.entrySet()) {
                        fp.addString(entry.getKey())
                        fp.addString(entry.getValue())
                        result = DigestUtils.combineUnordered(result, fp.digestAndReset())
                    }
                    return result
                }
            }
        }

        companion object {
            /** Unique instance standing for a corrupted cache entry.  */
            val CORRUPTED: Entry = com.google.devtools.build.lib.actions.cache.ActionCache.Entry(
                null,
                null,
                false,
                com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),
                com.google.common.collect.ImmutableMap.of<String?, SerializableTreeArtifactValue?>(),
                com.google.common.collect.ImmutableList.of<String?>()
            )

            private fun formatDigest(digest: ByteArray): String {
                return com.google.common.io.BaseEncoding.base16().lowerCase().encode(digest)
            }
        }
    }

    /**
     * Give persistent cache implementations a notification to write to disk.
     * 
     * @return size in bytes of the serialized cache.
     */
    @Throws(IOException::class)
    fun save(): Long

    /** Clear the action cache, closing all opened file handle.  */
    fun clear()

    /**
     * Returns an [ActionCache] with the same backing directory, but whose contents may have
     * been garbage collected.
     * 
     * 
     * May be safely interrupted. Upon interruption, this instance, including its backing
     * directory, remains valid. Otherwise, the return value may be the current instance or a
     * different one, depending on whether garbage collection was deemed necessary. If a different
     * instance is returned, the current instance must not be used further. Thus, safe usage of this
     * method looks like `actionCache = actionCache.trim(threshold, maxAge)`.
     * 
     * @param threshold the fraction of stale entries required to trigger garbage collection
     * @param maxAge the age at which entries are considered stale
     * @return either the current instance, or a fresh instance that replaces it
     * @throws IOException if an I/O error occurs
     * @throws InterruptedException in case of interruption
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun trim(threshold: Float, maxAge: java.time.Duration?): ActionCache?

    /** Dumps the action cache into a human-readable format.  */
    fun dump(out: PrintStream?)

    /** The number of entries in the cache.  */
    fun size(): Int

    /** Accounts one cache hit.  */
    fun accountHit()

    /** Accounts one cache miss for the given reason.  */
    fun accountMiss(reason: MissReason?)

    /**
     * Populates the given builder with statistics.
     * 
     * 
     * The extracted values are not guaranteed to be a consistent snapshot of the metrics tracked
     * by the action cache. Therefore, even if it is safe to call this function at any point in time,
     * this should only be called once there are no actions running.
     */
    fun mergeIntoActionCacheStatistics(builder: ActionCacheStatistics.Builder?)

    /** Resets the current statistics to zero.  */
    fun resetStatistics()

    /** Duration it took to load the action cache. Might be null if not loaded in this invocation.  */
    fun getLoadTime(): java.time.Duration? {
        return null
    }
}
