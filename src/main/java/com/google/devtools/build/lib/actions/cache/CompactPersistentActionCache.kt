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
 * An implementation of the ActionCache interface that uses a [StringIndexer] to reduce memory
 * footprint and saves cached actions using the [PersistentMap].
 */
@ConditionallyThreadSafe // condition: each instance must be instantiated with different cache root
class CompactPersistentActionCache private constructor(
    cacheRoot: Path,
    corruptedCacheRoot: Path,
    tmpDir: Path,
    clock: com.google.devtools.build.lib.clock.Clock,
    indexer: PersistentStringIndexer,
    actionMap: ActionMap,
    timestampMap: TimestampMap,
    misses: com.google.common.collect.ImmutableMap<MissReason?, AtomicInteger?>
) : ActionCache {
    /**
     * A timestamp, represented as the number of minutes since the Unix epoch.
     * 
     * 
     * This provides adequate accuracy for garbage collection purposes while reducing storage
     * requirements.
     */
    private class Timestamp(private val epochMinutes: Int) {
        fun toEpochMinutes(): Int {
            return epochMinutes
        }

        fun toInstant(): Instant? {
            return Instant.ofEpochMilli(epochMinutes * com.google.devtools.build.lib.actions.cache.CompactPersistentActionCache.Timestamp.Companion.MINUTE_IN_MILLIS)
        }

        override fun equals(other: Any?): Boolean {
            if (other !is Timestamp) {
                return false
            }
            return epochMinutes == other.epochMinutes
        }

        override fun hashCode(): Int {
            return java.lang.Integer.hashCode(epochMinutes)
        }

        override fun toString(): String {
            return toInstant().toString()
        }

        companion object {
            // Expect many recurring values and deduplicate them.
            private val INTERNER: com.google.common.collect.Interner<Timestamp> = BlazeInterners.newWeakInterner()

            private val MINUTE_IN_MILLIS: Long = java.time.Duration.ofMinutes(1).toMillis()

            fun fromEpochMinutes(epochMinutes: Int): Timestamp {
                return com.google.devtools.build.lib.actions.cache.CompactPersistentActionCache.Timestamp.Companion.INTERNER.intern(
                    com.google.devtools.build.lib.actions.cache.CompactPersistentActionCache.Timestamp(epochMinutes)
                )
            }

            fun fromInstant(instant: Instant): Timestamp {
                return com.google.devtools.build.lib.actions.cache.CompactPersistentActionCache.Timestamp.Companion.fromEpochMinutes(
                    (instant.toEpochMilli() / com.google.devtools.build.lib.actions.cache.CompactPersistentActionCache.Timestamp.Companion.MINUTE_IN_MILLIS).toInt()
                )
            }
        }
    }

    /**
     * A [PersistentMap] mapping the string index of the action's primary output path to that
     * entry's last access time.
     */
    private class TimestampMap(
        clock: com.google.devtools.build.lib.clock.Clock,
        timestampFile: Path?,
        timestampJournalFile: Path?
    ) : PersistentMap<Int?, Timestamp?>(
        VERSION, TIMESTAMP_CODEC, ConcurrentHashMap<K?, V?>(), timestampFile, timestampJournalFile
    ) {
        private val clock: com.google.devtools.build.lib.clock.Clock
        private var nextUpdateNanos: Long

        init {
            this.clock = clock
            this.nextUpdateNanos = clock.nanoTime() + SAVE_INTERVAL.toNanos()
            load()
        }

        protected override fun shouldFlushJournal(): Boolean {
            // Use nanoTime() instead of currentTimeMillis() to get monotonic time, not wall time.
            val currentTimeNanos: Long = clock.nanoTime()
            if (currentTimeNanos > nextUpdateNanos) {
                nextUpdateNanos = currentTimeNanos + SAVE_INTERVAL.toNanos()
                return true
            }
            return false
        }

        protected override fun shouldKeepJournal(): Boolean {
            // We must first flush the journal to get an accurate measure of its size.
            flushJournal()
            try {
                return journalSize() * 100 < cacheSize()
            } catch (e: IOException) {
                return false
            }
        }

        fun flush() {
            flushJournal()
        }
    }

    /**
     * A [PersistentMap] mapping the string index of the action's primary output path to the
     * serialized [ActionCache.Entry].
     */
    private class ActionMap(
        indexer: PersistentStringIndexer,
        timestampMap: TimestampMap,
        clock: com.google.devtools.build.lib.clock.Clock,
        mapFile: Path?,
        journalFile: Path?
    ) : PersistentMap<Int?, ByteArray?>(VERSION, ACTION_CODEC, ConcurrentHashMap<K?, V?>(), mapFile, journalFile) {
        private val clock: com.google.devtools.build.lib.clock.Clock
        private val indexer: PersistentStringIndexer
        private val timestampMap: TimestampMap
        private var nextUpdateNanos: Long

        init {
            this.indexer = indexer
            this.timestampMap = timestampMap
            this.clock = clock
            // Use nanoTime() instead of currentTimeMillis() to get monotonic time, not wall time.
            nextUpdateNanos = clock.nanoTime() + SAVE_INTERVAL.toNanos()
            load()
        }

        protected override fun shouldFlushJournal(): Boolean {
            // Use nanoTime() instead of currentTimeMillis() to get monotonic time, not wall time.
            val currentTimeNanos: Long = clock.nanoTime()
            if (currentTimeNanos > nextUpdateNanos) {
                nextUpdateNanos = currentTimeNanos + SAVE_INTERVAL.toNanos()
                // Flush the PersistentStringIndexer and TimestampMap.
                // This ensures an action isn't saved to disk before its timestamp or referenced strings.
                indexer.flush()
                timestampMap.flush()
                return true
            }
            return false
        }

        protected override fun shouldKeepJournal(): Boolean {
            // We must first flush the journal to get an accurate measure of its size.
            flushJournal()
            try {
                return journalSize() * 100 < cacheSize()
            } catch (e: IOException) {
                return false
            }
        }
    }

    private val cacheRoot: Path
    private val corruptedCacheRoot: Path
    private val tmpDir: Path
    private val clock: com.google.devtools.build.lib.clock.Clock
    private val indexer: PersistentStringIndexer
    private val actionMap: ActionMap
    private val timestampMap: TimestampMap
    private val misses: com.google.common.collect.ImmutableMap<MissReason?, AtomicInteger?>
    private val hits: AtomicInteger = AtomicInteger()
    private var loadTime: java.time.Duration? = null

    override fun get(key: String?): com.google.devtools.build.lib.actions.cache.ActionCache.Entry? {
        val index: Int? = indexer.getIndex(key)
        if (index == null) {
            return null
        }
        val data: ByteArray? = actionMap.get(index)
        if (data == null) {
            return null
        }
        val entry: com.google.devtools.build.lib.actions.cache.ActionCache.Entry? = decode(data)
        if (entry != null && !entry.isCorrupted()) {
            timestampMap.put(
                index,
                com.google.devtools.build.lib.actions.cache.CompactPersistentActionCache.Timestamp.Companion.fromInstant(
                    clock.now()
                )
            )
        }
        return entry
    }

    override fun put(key: String?, entry: com.google.devtools.build.lib.actions.cache.ActionCache.Entry) {
        put(key, entry, clock.now())
    }

    private fun put(
        key: String?,
        entry: com.google.devtools.build.lib.actions.cache.ActionCache.Entry,
        timestamp: Instant
    ) {
        // Encode record. Note that both methods may create new mappings in the indexer.
        val index: Int? = indexer.getOrCreateIndex(key)
        val content: ByteArray?
        try {
            content = encode(entry)
        } catch (e: IOException) {
            logger.atWarning().withCause(e).log("Failed to save cache entry %s with key %s", entry, key)
            return
        }

        // Update validation record.
        // Note the benign race condition in which two threads might race on updating the validation
        // record: if the most recent update loses the race, a value lower than the indexer size will
        // remain in the validation record, which will still pass the integrity check.
        val buffer: java.nio.ByteBuffer = java.nio.ByteBuffer.allocate(4) // size of int in bytes
        val indexSize: Int = indexer.size()
        buffer.asIntBuffer().put(indexSize)
        actionMap.put(VALIDATION_KEY, buffer.array())

        // Update the timestamp map.
        timestampMap.put(
            index,
            com.google.devtools.build.lib.actions.cache.CompactPersistentActionCache.Timestamp.Companion.fromInstant(
                timestamp
            )
        )

        // Update the action map.
        // This is last so that, if a flush occurs, the index and timestamp also make it to disk.
        actionMap.put(index, content)
    }

    override fun remove(key: String?) {
        val index: Int? = indexer.getIndex(key)
        if (index != null) {
            actionMap.remove(index)
            timestampMap.remove(index)
        }
    }

    override fun removeIf(predicate: java.util.function.Predicate<com.google.devtools.build.lib.actions.cache.ActionCache.Entry?>) {
        // Be careful not to cause the timestamp to be updated on kept entries (i.e., don't use get()).
        for (entry in actionMap.entrySet()) {
            if (entry.getKey() == VALIDATION_KEY) {
                // Skip the validation record.
                continue
            }
            val decodedEntry: com.google.devtools.build.lib.actions.cache.ActionCache.Entry = decode(entry.getValue())
            if (decodedEntry.isCorrupted()) {
                // Skip corrupted entries.
                continue
            }
            if (predicate.test(decodedEntry)) {
                // Although this is racy (the key might be concurrently set to a different value), we don't
                // care because it's a very small window and it only impacts performance, not correctness.
                actionMap.remove(entry.getKey())
                timestampMap.remove(entry.getKey())
            }
        }
    }

    @ThreadHostile
    @Throws(IOException::class)
    override fun save(): Long {
        // TODO(b/314086729): Remove after we understand the bug.
        try {
            validateIntegrity(indexer.size(), actionMap.get(VALIDATION_KEY))
        } catch (e: IOException) {
            logger.atInfo().withCause(e).log(
                "Integrity check failed on the inmemory objects right before save"
            )
        }

        val indexSize: Long = indexer.save()
        val actionMapSize: Long = actionMap.save()
        val timestampMapSize: Long = timestampMap.save()
        return indexSize + actionMapSize + timestampMapSize
    }

    @ThreadHostile
    override fun clear() {
        indexer.clear()
        actionMap.clear()
        timestampMap.clear()
    }

    /** Returns a map from action key to last access time.  */
    fun getActionTimestampMap(): com.google.common.collect.ImmutableMap<String?, Instant?> {
        // Iterate the timestamp map, not the action map, so that the result may be used for testing
        // that an entry is removed from the timestamp map when removed from the action map. Note that
        // the indexer does not support removing entries.
        val builder: com.google.common.collect.ImmutableMap.Builder<String?, Instant?> =
            com.google.common.collect.ImmutableMap.builderWithExpectedSize<K?, V?>(timestampMap.size())
        for (entry in timestampMap.entrySet()) {
            val actionKey: String? = indexer.getStringForIndex(entry.getKey())
            if (actionKey != null) {
                builder.put(actionKey, entry.getValue().toInstant())
            }
        }
        return builder.buildKeepingLast()
    }

    @ThreadHostile
    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun trim(threshold: Float, maxAge: java.time.Duration): CompactPersistentActionCache {
        val cutoffTime: Instant = clock.now().minus(maxAge)

        val accessTimeMap: com.google.common.collect.ImmutableMap<String?, Instant?> = getActionTimestampMap()

        // Count the number of stale entries.
        var numStale = 0
        for (entry in accessTimeMap.entrySet()) {
            if (java.lang.Thread.interrupted()) {
                // If interrupted, return promptly.
                throw java.lang.InterruptedException()
            }
            if (entry.getValue().isBefore(cutoffTime)) {
                numStale++
            }
        }

        // Skip garbage collection if below the threshold.
        if (numStale == 0 || numStale < threshold * actionMap.size()) {
            return this
        }

        // Clear preexisting temporary directory contents.
        tmpDir.deleteTree()

        val newRoot: Path = tmpDir.getChild("new")
        val oldRoot: Path? = tmpDir.getChild("old")

        // Create a new cache backed by a temporary directory.
        val newCache =
            create(
                newRoot, corruptedCacheRoot, tmpDir, clock, NullEventHandler.INSTANCE
            )

        // Copy sufficiently recent entries into the new cache.
        for (entry in actionMap.entrySet()) {
            if (java.lang.Thread.interrupted()) {
                // If interrupted, return promptly but avoid leaving the temporary directory behind.
                tmpDir.deleteTree()
                throw java.lang.InterruptedException()
            }
            if (entry.getKey() == VALIDATION_KEY) {
                // Skip the validation record.
                continue
            }
            val actionKey: String = com.google.common.base.Preconditions.checkNotNull<String>(
                indexer.getStringForIndex(entry.getKey()),
                entry.getKey()
            )
            // If the timestamp is missing, assume the entry was recently added but its timestamp update
            // was lost.
            val timestamp: Instant? = accessTimeMap.getOrDefault(actionKey, clock.now())
            if (timestamp.isBefore(cutoffTime)) {
                continue
            }
            // The entry must be reencoded so that strings it references are inserted into the indexer.
            newCache.put(actionKey, decode(entry.getValue()), timestamp)
        }

        // Save the new cache to disk.
        newCache.save()

        // Replace the on-disk representation.
        cacheRoot.renameTo(oldRoot)
        newRoot.renameTo(cacheRoot)

        // Delete the temporary directory.
        tmpDir.deleteTree()

        // Reload the cache from disk and return it.
        return create(
            cacheRoot, corruptedCacheRoot, tmpDir, clock, NullEventHandler.INSTANCE
        )
    }

    /** Dumps the action cache into a human-readable format.  */
    override fun dump(out: PrintStream) {
        val sortedKeys: com.google.common.collect.ImmutableList<Int?> =
            actionMap.keySet().stream()
                .filter({ k -> !k.equals(VALIDATION_KEY) })
                .sorted()
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        out.format("Action cache (%d records):\n", sortedKeys.size())
        for (key in sortedKeys) {
            val encodedEntry: ByteArray = actionMap.get(key)
            val decodedEntry: com.google.devtools.build.lib.actions.cache.ActionCache.Entry = decode(encodedEntry)
            val timestamp: Timestamp? = timestampMap.get(key)
            out.format("  %s -> %s\n", key, indexer.getStringForIndex(key))
            out.format("  packed_len = %s\n", encodedEntry.size)
            out.format("  timestamp = %s\n", if (timestamp != null) timestamp.toString() else "unknown")
            decodedEntry.dump(out)
        }
        indexer.dump(out)
    }

    /**
     * Returns the number of entries in the action map. If non-zero, it means that the map has been
     * initialized and contains the validation record.
     */
    override fun size(): Int {
        return actionMap.size()
    }

    @Throws(IOException::class)
    private fun encodeRemoteMetadata(value: FileArtifactValue, sink: java.io.ByteArrayOutputStream?) {
        com.google.common.base.Preconditions.checkArgument(value.isRemote(), "metadata is not remote: %s", value)

        MetadataDigestUtils.write(value.getDigest(), sink)

        VarInt.putVarLong(value.getSize(), sink)

        VarInt.putVarInt(value.getLocationIndex(), sink)

        VarInt.putVarLong(
            if (value.getExpirationTime() != null) value.getExpirationTime().toEpochMilli() else -1, sink
        )

        val resolvedPath: PathFragment? = value.getResolvedPath()
        if (resolvedPath != null) {
            VarInt.putVarInt(1, sink)
            VarInt.putVarInt(indexer.getOrCreateIndex(resolvedPath.toString()), sink)
        } else {
            VarInt.putVarInt(0, sink)
        }
    }

    init {
        this.cacheRoot = cacheRoot
        this.corruptedCacheRoot = corruptedCacheRoot
        this.tmpDir = tmpDir
        this.clock = clock
        this.indexer = indexer
        this.actionMap = actionMap
        this.timestampMap = timestampMap
        this.misses = misses
    }

    @Throws(IOException::class)
    private fun decodeRemoteMetadata(source: java.nio.ByteBuffer?): FileArtifactValue {
        val digest: ByteArray = MetadataDigestUtils.read(source)

        val size: Long = VarInt.getVarLong(source)

        val locationIndex: Int = VarInt.getVarInt(source)

        val expirationTimeEpochMilli: Long = VarInt.getVarLong(source)

        var resolvedPath: PathFragment? = null
        val numResolvedPath: Int = VarInt.getVarInt(source)
        if (numResolvedPath > 0) {
            if (numResolvedPath != 1) {
                throw IOException("Invalid presence marker for resolved path")
            }
            resolvedPath = PathFragment.create(getStringForIndex(indexer, VarInt.getVarInt(source)))
        }

        var metadata: FileArtifactValue =
            FileArtifactValue.createForRemoteFileWithMaterializationData(
                digest,
                size,
                locationIndex,
                if (expirationTimeEpochMilli >= 0) Instant.ofEpochMilli(expirationTimeEpochMilli) else null
            )

        if (resolvedPath != null) {
            metadata = FileArtifactValue.createFromExistingWithResolvedPath(metadata, resolvedPath)
        }

        return metadata
    }

    /**
     * @return action data encoded as a byte[] array.
     */
    @Throws(IOException::class)
    private fun encode(entry: com.google.devtools.build.lib.actions.cache.ActionCache.Entry): ByteArray? {
        com.google.common.base.Preconditions.checkState(!entry.isCorrupted())

        var maxDiscoveredInputsSize = 1 // presence marker
        if (entry.discoversInputs()) {
            maxDiscoveredInputsSize +=
                (1 // pruned inputs presence marker
                        + VarInt.MAX_VARINT_SIZE // length
                        + (VarInt.MAX_VARINT_SIZE // execPath
                        * entry.getDiscoveredInputPaths().size()))
        }

        var estimatedOutputMetadataSize = 1 // presence marker
        if (entry.hasOutputMetadata()) {
            val maxOutputFilesSize: Int =
                (VarInt.MAX_VARINT_SIZE // entry.getOutputFiles().size()
                        + (VarInt.MAX_VARINT_SIZE // execPath
                        + MAX_REMOTE_METADATA_SIZE)
                        * entry.getOutputFiles().size())

            var estimatedOutputTreesSize: Int = VarInt.MAX_VARINT_SIZE // entry.getOutputTrees().size()
            for (tree in entry.getOutputTrees().entrySet()) {
                estimatedOutputTreesSize += VarInt.MAX_VARINT_SIZE // execPath

                val value: SerializableTreeArtifactValue = tree.getValue()

                estimatedOutputTreesSize += VarInt.MAX_VARINT_SIZE // value.childValues().size()
                estimatedOutputTreesSize +=
                    ((30 // Estimate for the length of parentRelativePath string
                            + MAX_REMOTE_METADATA_SIZE)
                            * value.childValues.size())

                estimatedOutputTreesSize +=  // value.archivedFileValue() optional
                    1 + value.archivedFileValue.map<Int?>(java.util.function.Function { ignored: FileArtifactValue? -> MAX_REMOTE_METADATA_SIZE })
                        .orElse(0)
                estimatedOutputTreesSize +=  // value.resolvedPath() optional
                    1 + value.resolvedPath.map<Any?>(java.util.function.Function { ignored: PathFragment? -> VarInt.MAX_VARINT_SIZE })
                        .orElse(0)
            }

            val maxProxyOutputsSize: Int =
                VarInt.MAX_VARINT_SIZE * (entry.getProxyOutputs().size() + 1) // +1 for the size itself.

            estimatedOutputMetadataSize +=
                maxOutputFilesSize + estimatedOutputTreesSize + maxProxyOutputsSize
        }

        // Estimate the size of the buffer.
        val estimatedSize: Int =
            ((1 + DigestUtils.ESTIMATED_SIZE) // digest length + digest
                    + maxDiscoveredInputsSize
                    + estimatedOutputMetadataSize)
        val sink: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream(estimatedSize)

        MetadataDigestUtils.write(entry.getDigest(), sink)

        VarInt.putVarInt(if (entry.discoversInputs()) 1 else 0, sink)
        if (entry.discoversInputs()) {
            VarInt.putVarInt(if (entry.prunedInputs()) 1 else 0, sink)
            val discoveredInputPaths: com.google.common.collect.ImmutableList<String?>? =
                entry.getDiscoveredInputPaths()
            VarInt.putVarInt(discoveredInputPaths.size(), sink)
            for (discoveredInputPath in discoveredInputPaths) {
                VarInt.putVarInt(indexer.getOrCreateIndex(discoveredInputPath), sink)
            }
        }

        VarInt.putVarInt(if (entry.hasOutputMetadata()) 1 else 0, sink)
        if (entry.hasOutputMetadata()) {
            VarInt.putVarInt(entry.getOutputFiles().size(), sink)
            for (file in entry.getOutputFiles().entrySet()) {
                VarInt.putVarInt(indexer.getOrCreateIndex(file.getKey()), sink)
                encodeRemoteMetadata(file.getValue(), sink)
            }

            VarInt.putVarInt(entry.getOutputTrees().size(), sink)
            for (tree in entry.getOutputTrees().entrySet()) {
                VarInt.putVarInt(indexer.getOrCreateIndex(tree.getKey()), sink)

                val serializableTreeArtifactValue: SerializableTreeArtifactValue = tree.getValue()

                VarInt.putVarInt(serializableTreeArtifactValue.childValues.size(), sink)
                for (child in serializableTreeArtifactValue.childValues.entrySet()) {
                    // Don't put tree-relative paths in the string indexer. They are unlikely to be reused.
                    // Instead, write them directly into the encoding.
                    MetadataDigestUtils.write(StringUnsafe.getByteArray(child.getKey()), sink)
                    encodeRemoteMetadata(child.getValue(), sink)
                }

                val archivedFileValue: java.util.Optional<FileArtifactValue?> =
                    serializableTreeArtifactValue.archivedFileValue
                if (archivedFileValue.isPresent()) {
                    VarInt.putVarInt(1, sink)
                    encodeRemoteMetadata(archivedFileValue.get(), sink)
                } else {
                    VarInt.putVarInt(0, sink)
                }

                val resolvedPath: java.util.Optional<PathFragment?> = serializableTreeArtifactValue.resolvedPath
                if (resolvedPath.isPresent()) {
                    VarInt.putVarInt(1, sink)
                    VarInt.putVarInt(indexer.getOrCreateIndex(resolvedPath.get().toString()), sink)
                } else {
                    VarInt.putVarInt(0, sink)
                }
            }

            VarInt.putVarInt(entry.getProxyOutputs().size(), sink)
            for (execPath in entry.getProxyOutputs()) {
                VarInt.putVarInt(indexer.getOrCreateIndex(execPath), sink)
            }
        }

        return sink.toByteArray()
    }

    /**
     * Creates a [ActionCache.Entry] from the given compressed data.
     * 
     * @throws IOException if the compressed data is corrupted.
     */
    @Throws(IOException::class)
    private fun decodeInternal(data: ByteArray): com.google.devtools.build.lib.actions.cache.ActionCache.Entry {
        try {
            val source: java.nio.ByteBuffer = java.nio.ByteBuffer.wrap(data)

            val digest: ByteArray = MetadataDigestUtils.read(source)

            var discoveredInputPaths: com.google.common.collect.ImmutableList<String?>? = null
            var prunedInputs = false
            val discoveredInputsPresenceMarker: Int = VarInt.getVarInt(source)
            if (discoveredInputsPresenceMarker != 0) {
                if (discoveredInputsPresenceMarker != 1) {
                    throw IOException(
                        "Invalid presence marker for discovered inputs: " + discoveredInputsPresenceMarker
                    )
                }
                val prunedInputsMarker: Int = VarInt.getVarInt(source)
                if (prunedInputsMarker != 0) {
                    if (prunedInputsMarker != 1) {
                        throw IOException("Invalid marker for pruned inputs: " + prunedInputsMarker)
                    }
                    prunedInputs = true
                }
                val numDiscoveredInputs: Int = VarInt.getVarInt(source)
                if (numDiscoveredInputs < 0) {
                    throw IOException("Invalid discovered input count: " + numDiscoveredInputs)
                }
                val builder: com.google.common.collect.ImmutableList.Builder<String?> =
                    com.google.common.collect.ImmutableList.builderWithExpectedSize<String?>(numDiscoveredInputs)
                for (i in 0..<numDiscoveredInputs) {
                    val id: Int = VarInt.getVarInt(source)
                    val filename = getStringForIndex(indexer, id)
                    builder.add(filename)
                }
                discoveredInputPaths = builder.build()
            }

            val outputMetadataPresenceMarker: Int = VarInt.getVarInt(source)
            if (outputMetadataPresenceMarker == 0) {
                if (source.remaining() > 0) {
                    throw IOException("serialized entry data has not been fully decoded")
                }
                return com.google.devtools.build.lib.actions.cache.ActionCache.Entry(
                    digest,
                    discoveredInputPaths,
                    prunedInputs,  /* outputFileMetadata= */
                    com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),  /* outputTreeMetadata= */
                    com.google.common.collect.ImmutableMap.of<String?, SerializableTreeArtifactValue?>(),  /* proxyOutputs= */
                    com.google.common.collect.ImmutableList.of<String?>()
                )
            }

            val numOutputFiles: Int = VarInt.getVarInt(source)
            if (numOutputFiles < 0) {
                throw IOException("Invalid output file count: " + numOutputFiles)
            }
            val outputFiles: com.google.common.collect.ImmutableMap.Builder<String?, FileArtifactValue?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, FileArtifactValue?>(
                    numOutputFiles
                )
            for (i in 0..<numOutputFiles) {
                val execPath = getStringForIndex(indexer, VarInt.getVarInt(source))
                val value: FileArtifactValue = decodeRemoteMetadata(source)
                outputFiles.put(execPath, value)
            }

            val numOutputTrees: Int = VarInt.getVarInt(source)
            if (numOutputTrees < 0) {
                throw IOException("invalid output tree count: " + numOutputTrees)
            }
            val outputTrees: com.google.common.collect.ImmutableMap.Builder<String?, SerializableTreeArtifactValue?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, SerializableTreeArtifactValue?>(
                    numOutputTrees
                )
            for (i in 0..<numOutputTrees) {
                val treeKey = getStringForIndex(indexer, VarInt.getVarInt(source))

                val childValues: com.google.common.collect.ImmutableMap.Builder<String?, FileArtifactValue?> =
                    com.google.common.collect.ImmutableMap.builder<String?, FileArtifactValue?>()
                val numChildValues: Int = VarInt.getVarInt(source)
                for (j in 0..<numChildValues) {
                    val childKey: String? =
                        StringUnsafe.newInstance(MetadataDigestUtils.read(source), StringUnsafe.LATIN1)
                    val value: FileArtifactValue = decodeRemoteMetadata(source)
                    childValues.put(childKey, value)
                }

                var archivedFileValue: java.util.Optional<FileArtifactValue?> =
                    java.util.Optional.empty<FileArtifactValue?>()
                val archivedFileValuePresenceMarker: Int = VarInt.getVarInt(source)
                if (archivedFileValuePresenceMarker != 0) {
                    if (archivedFileValuePresenceMarker != 1) {
                        throw IOException(
                            "Invalid presence marker for archived representation: "
                                    + archivedFileValuePresenceMarker
                        )
                    }
                    archivedFileValue = java.util.Optional.of<FileArtifactValue?>(decodeRemoteMetadata(source))
                }

                var resolvedPath: java.util.Optional<PathFragment?> = java.util.Optional.empty<PathFragment?>()
                val resolvedPathPresenceMarker: Int = VarInt.getVarInt(source)
                if (resolvedPathPresenceMarker != 0) {
                    if (resolvedPathPresenceMarker != 1) {
                        throw IOException(
                            "Invalid presence marker for resolved path: " + resolvedPathPresenceMarker
                        )
                    }
                    resolvedPath =
                        java.util.Optional.of<T?>(
                            PathFragment.create(getStringForIndex(indexer, VarInt.getVarInt(source)))
                        )
                }

                val value: SerializableTreeArtifactValue =
                    SerializableTreeArtifactValue(
                        childValues.buildOrThrow(), archivedFileValue, resolvedPath
                    )
                outputTrees.put(treeKey, value)
            }

            val numProxyArtifacts: Int = VarInt.getVarInt(source)
            if (numProxyArtifacts < 0) {
                throw IOException("Invalid proxy artifact count: " + numProxyArtifacts)
            }
            val proxyArtifacts: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<String?>(numProxyArtifacts)
            for (i in 0..<numProxyArtifacts) {
                val execPath = getStringForIndex(indexer, VarInt.getVarInt(source))
                proxyArtifacts.add(execPath)
            }

            if (source.remaining() > 0) {
                throw IOException("serialized entry data has not been fully decoded")
            }
            return com.google.devtools.build.lib.actions.cache.ActionCache.Entry(
                digest,
                discoveredInputPaths,
                prunedInputs,
                outputFiles.buildOrThrow(),
                outputTrees.buildOrThrow(),
                proxyArtifacts.build()
            )
        } catch (e: java.nio.BufferUnderflowException) {
            throw IOException("encoded entry data is incomplete", e)
        }
    }

    /**
     * Creates an [ActionCache.Entry] from the given compressed data, returning the special
     * value [ActionCache.Entry.CORRUPTED] if the compressed data is corrupted.
     */
    private fun decode(data: ByteArray): com.google.devtools.build.lib.actions.cache.ActionCache.Entry {
        try {
            return decodeInternal(data)
        } catch (e: IOException) {
            return com.google.devtools.build.lib.actions.cache.ActionCache.Entry.Companion.CORRUPTED
        }
    }

    override fun accountHit() {
        hits.incrementAndGet()
    }

    override fun accountMiss(reason: MissReason?) {
        val counter: AtomicInteger? = misses.get(reason)
        com.google.common.base.Preconditions.checkNotNull<AtomicInteger?>(
            counter,
            "Miss reason %s was not registered in the misses map " + "during cache construction",
            reason
        )
        counter.incrementAndGet()
    }

    override fun mergeIntoActionCacheStatistics(builder: ActionCacheStatistics.Builder) {
        builder.setHits(hits.get())

        var totalMisses = 0
        for (entry in misses.entrySet()) {
            val count: Int = entry.getValue().get()
            builder.addMissDetailsBuilder().setReason(entry.getKey()).setCount(count)
            totalMisses += count
        }
        builder.setMisses(totalMisses)
    }

    override fun resetStatistics() {
        hits.set(0)
        for (entry in misses.entrySet()) {
            entry.getValue().set(0)
        }
    }

    override fun getLoadTime(): java.time.Duration? {
        val ret: java.time.Duration? = loadTime
        // As a side effect, reset the load time, so it is only reported for the actual invocation that
        // loaded the action cache.
        loadTime = null
        return ret
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private val SAVE_INTERVAL: java.time.Duration = java.time.Duration.ofSeconds(3)

        // Key of the action cache record that holds information used to verify referential integrity
        // between action cache and string indexer. Must be < 0 to avoid conflict with real action
        // cache records.
        private val VALIDATION_KEY = -10

        private const val VERSION = 25

        private val TIMESTAMP_CODEC: MapCodec<Int?, Timestamp?> = object : MapCodec() {
            @Throws(IOException::class)
            protected override fun readKey(`in`: DataInput): Int {
                return `in`.readInt()
            }

            @Throws(IOException::class)
            protected override fun readValue(`in`: DataInput): Timestamp {
                return com.google.devtools.build.lib.actions.cache.CompactPersistentActionCache.Timestamp.Companion.fromEpochMinutes(
                    `in`.readInt()
                )
            }

            @Throws(IOException::class)
            protected override fun writeKey(key: Int, out: DataOutput) {
                out.writeInt(key)
            }

            @Throws(IOException::class)
            protected override fun writeValue(value: Timestamp, out: DataOutput) {
                out.writeInt(value.toEpochMinutes())
            }
        }

        private val ACTION_CODEC: MapCodec<Int?, ByteArray?> = object : MapCodec() {
            @Throws(IOException::class)
            protected override fun readKey(`in`: DataInput): Int {
                return `in`.readInt()
            }

            @Throws(IOException::class)
            protected override fun readValue(`in`: DataInput): ByteArray {
                val size: Int = `in`.readInt()
                if (size < 0) {
                    throw IOException("found negative array size: " + size)
                }
                val data = ByteArray(size)
                `in`.readFully(data)
                return data
            }

            @Throws(IOException::class)
            protected override fun writeKey(key: Int, out: DataOutput) {
                out.writeInt(key)
            }

            @Throws(IOException::class)
            protected override fun writeValue(value: ByteArray, out: DataOutput) {
                out.writeInt(value.size)
                out.write(value)
            }
        }

        @Throws(IOException::class)
        fun create(
            cacheRoot: Path,
            corruptedCacheRoot: Path,
            tmpDir: Path,
            clock: com.google.devtools.build.lib.clock.Clock,
            reporterForInitializationErrors: EventHandler
        ): CompactPersistentActionCache {
            val before: Instant? = clock.now()
            val compactPersistentActionCache =
                create(
                    cacheRoot,
                    corruptedCacheRoot,
                    tmpDir,
                    clock,
                    reporterForInitializationErrors,  /* retrying= */
                    false
                )
            val after: Instant? = clock.now()
            compactPersistentActionCache.loadTime = java.time.Duration.between(before, after)

            return compactPersistentActionCache
        }

        @Throws(IOException::class)
        private fun create(
            cacheRoot: Path,
            corruptedCacheRoot: Path,
            tmpDir: Path,
            clock: com.google.devtools.build.lib.clock.Clock,
            reporterForInitializationErrors: EventHandler,
            retrying: Boolean
        ): CompactPersistentActionCache {
            cacheRoot.createDirectoryAndParents()

            val cacheFile: Path = cacheFile(cacheRoot)
            val journalFile: Path = journalFile(cacheRoot)
            val indexFile: Path = indexFile(cacheRoot)
            val indexJournalFile: Path = indexJournalFile(cacheRoot)
            val timestampFile: Path = timestampFile(cacheRoot)
            val timestampJournalFile: Path = timestampJournalFile(cacheRoot)

            val indexer: PersistentStringIndexer?
            try {
                indexer = PersistentStringIndexer.Companion.create(indexFile, indexJournalFile, clock)
            } catch (e: IOException) {
                return logAndThrowOrRecurse(
                    cacheRoot,
                    corruptedCacheRoot,
                    tmpDir,
                    clock,
                    "Failed to load action cache index data",
                    e,
                    reporterForInitializationErrors,
                    retrying
                )
            }

            val timestampMap: TimestampMap?
            try {
                timestampMap = TimestampMap(clock, timestampFile, timestampJournalFile)
            } catch (e: IOException) {
                return logAndThrowOrRecurse(
                    cacheRoot,
                    corruptedCacheRoot,
                    tmpDir,
                    clock,
                    "Failed to load action cache timestamp data",
                    e,
                    reporterForInitializationErrors,
                    retrying
                )
            }

            val actionMap: ActionMap?
            try {
                actionMap = com.google.devtools.build.lib.actions.cache.CompactPersistentActionCache.ActionMap(
                    indexer,
                    timestampMap,
                    clock,
                    cacheFile,
                    journalFile
                )
            } catch (e: IOException) {
                return logAndThrowOrRecurse(
                    cacheRoot,
                    corruptedCacheRoot,
                    tmpDir,
                    clock,
                    "Failed to load action cache data",
                    e,
                    reporterForInitializationErrors,
                    retrying
                )
            }

            // Validate referential integrity between action map and indexer.
            if (!actionMap.isEmpty()) {
                try {
                    validateIntegrity(indexer.size(), actionMap.get(VALIDATION_KEY))
                } catch (e: IOException) {
                    return logAndThrowOrRecurse(
                        cacheRoot,
                        corruptedCacheRoot,
                        tmpDir,
                        clock,
                        "Failed action cache referential integrity check",
                        e,
                        reporterForInitializationErrors,
                        retrying
                    )
                }
            }

            // Delete unrecognized on-disk files left around by previous Bazel versions.
            // This is required to fix an incrementality bug that occurs when building a runfiles tree
            // while alternating between two Bazel versions (as might occur during a migration).
            // See https://github.com/bazelbuild/bazel/issues/26818 for details.
            // Note that such files can only originate from Bazel 8 and earlier, which embedded the version
            // number in filenames; Bazel 9 and later use fixed filenames. Thus this can be safely removed
            // once backcompatibility of the output tree with Bazel 8 and earlier is no longer a concern.
            deleteUnrecognizedFiles(cacheRoot)

            // Populate the map now, so that concurrent updates to the values can happen safely.
            val misses: MutableMap<MissReason?, AtomicInteger?> = java.util.EnumMap<Any?, Any?>(MissReason::class.java)
            for (reason in MissReason.values()) {
                if (reason === MissReason.UNRECOGNIZED) {
                    // The presence of this enum value is a protobuf artifact and confuses our metrics
                    // externalization code below. Just skip it.
                    continue
                }
                misses.put(reason, AtomicInteger(0))
            }

            return CompactPersistentActionCache(
                cacheRoot,
                corruptedCacheRoot,
                tmpDir,
                clock,
                indexer,
                actionMap!!,
                timestampMap,
                com.google.common.collect.Maps.immutableEnumMap<K?, V?>(misses)
            )
        }

        @Throws(IOException::class)
        private fun deleteUnrecognizedFiles(cacheRoot: Path) {
            val knownFiles: com.google.common.collect.ImmutableSet<Path?> =
                com.google.common.collect.ImmutableSet.of<Path?>(
                    cacheFile(cacheRoot),
                    journalFile(cacheRoot),
                    indexFile(cacheRoot),
                    indexJournalFile(cacheRoot),
                    timestampFile(cacheRoot),
                    timestampJournalFile(cacheRoot)
                )
            for (child in cacheRoot.getDirectoryEntries()) {
                if (!knownFiles.contains(child)) {
                    child.delete()
                }
            }
        }

        @Throws(IOException::class)
        private fun logAndThrowOrRecurse(
            cacheRoot: Path,
            corruptedCacheRoot: Path,
            tmpDir: Path,
            clock: com.google.devtools.build.lib.clock.Clock,
            message: String?,
            e: IOException,
            reporterForInitializationErrors: EventHandler,
            retrying: Boolean
        ): CompactPersistentActionCache {
            var e: IOException = e
            if (retrying) {
                // Prevent a retry loop.
                throw IOException("Action cache initialization is stuck in a retry loop", e)
            }

            if (e is IncompatibleFormatException) {
                // Format incompatibility is expected when switching between Bazel versions, so we don't treat
                // it as corruption; we simply delete the cache directory and start fresh.
                cacheRoot.deleteTree()
            } else {
                // Move the corrupted cache to a separate location so it can be analyzed later.
                // This also ensures that the next initialization attempt will create an empty cache.
                // To avoid using too much disk space, only keep the most recent corrupted cache around.
                corruptedCacheRoot.deleteTree()
                cacheRoot.renameTo(corruptedCacheRoot)

                e = IOException("%s: %s".formatted(message, e.getMessage()), e)

                logger.atWarning().withCause(e).log(
                    "Failed to load action cache, preexisting files kept in %s", corruptedCacheRoot
                )

                reporterForInitializationErrors.handle(
                    Event.warn(
                        ("Error during action cache initialization: "
                                + e.getMessage()
                                + ". Data may be incomplete, potentially causing rebuilds")
                    )
                )
            }

            return create(
                cacheRoot,
                corruptedCacheRoot,
                tmpDir,
                clock,
                reporterForInitializationErrors,  /* retrying= */
                true
            )
        }

        /** Throws IOException if indexer contains no data or integrity check has failed.  */
        @Throws(IOException::class)
        private fun validateIntegrity(indexerSize: Int, validationRecord: ByteArray) {
            if (indexerSize == 0) {
                throw IOException("empty index")
            }
            if (validationRecord == null) {
                throw IOException("missing validation record")
            }
            try {
                val validationSize: Int = java.nio.ByteBuffer.wrap(validationRecord).asIntBuffer().get()
                if (validationSize > indexerSize) {
                    throw IOException(
                        java.lang.String.format(
                            "validation record %d is too large compared to index size %d",
                            validationSize, indexerSize
                        )
                    )
                }
            } catch (e: java.nio.BufferUnderflowException) {
                throw IOException("validation record is incomplete", e)
            }
        }

        fun cacheFile(cacheRoot: Path): Path {
            return cacheRoot.getChild("action_cache.blaze")
        }

        fun journalFile(cacheRoot: Path): Path {
            return cacheRoot.getChild("action_journal.blaze")
        }

        fun indexFile(cacheRoot: Path): Path {
            return cacheRoot.getChild("filename_index.blaze")
        }

        fun indexJournalFile(cacheRoot: Path): Path {
            return cacheRoot.getChild("filename_index_journal.blaze")
        }

        fun timestampFile(cacheRoot: Path): Path {
            return cacheRoot.getChild("timestamp.blaze")
        }

        fun timestampJournalFile(cacheRoot: Path): Path {
            return cacheRoot.getChild("timestamp_journal.blaze")
        }

        private val MAX_REMOTE_METADATA_SIZE: Int = ((1 + DigestUtils.ESTIMATED_SIZE) // digest length + digest
                + VarInt.MAX_VARLONG_SIZE // size
                + VarInt.MAX_VARINT_SIZE // locationIndex
                + VarInt.MAX_VARLONG_SIZE // expirationTime
                + (1 + VarInt.MAX_VARINT_SIZE)) // resolvedPath

        @Throws(IOException::class)
        private fun getStringForIndex(indexer: StringIndexer, index: Int): String {
            val path: String = (if (index >= 0) indexer.getStringForIndex(index) else null)!!
            if (path == null) {
                throw IOException("Corrupted string index")
            }
            return path
        }
    }
}
