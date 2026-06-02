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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.cmdline.Label

/**
 * An in-memory graph implementation. All operations are thread-safe with ConcurrentMap semantics.
 * Also see [NodeEntry].
 * 
 * 
 * This class is public only for use in alternative graph implementations.
 */
open class InMemoryGraphImpl private constructor(initialCapacity: Int, usePooledInterning: Boolean) : InMemoryGraph {
    // Use ForwardingConcurrentMap as a live reference to nodeMap so that it's safe to mutate
    // nodeMap and not have callers see the stale map. See getValues, getDoneValues,
    // getAllNodeEntries.
    private val liveView: ConcurrentMap<SkyKey?, InMemoryNodeEntry?> =
        object : com.google.common.collect.ForwardingConcurrentMap<SkyKey?, InMemoryNodeEntry?>() {
            override fun delegate(): ConcurrentMap<SkyKey?, InMemoryNodeEntry?> {
                return nodeMap
            }
        }

    protected var nodeMap: ConcurrentHashMap<SkyKey?, InMemoryNodeEntry?>
    private val getBatch: NodeBatch
    private val createIfAbsentBatch: NodeBatch
    private val usePooledInterning: Boolean

    internal constructor() : this( /* initialCapacity= */1 shl 10)

    /**
     * For some shell integration tests, we don't want to apply [SkyKeyInterner] created and
     * bind `SkyKeyInterner#globalPool` to the second [InMemoryGraph].
     */
    internal constructor(usePooledInterning: Boolean) : this( /* initialCapacity= */1 shl 10, usePooledInterning)

    protected constructor(initialCapacity: Int) : this(initialCapacity,  /* usePooledInterning= */true)

    init {
        this.nodeMap = ConcurrentHashMap<SkyKey?, InMemoryNodeEntry?>(initialCapacity)
        this.getBatch = NodeBatch { key: SkyKey? -> this.getIfPresent(key) }
        this.createIfAbsentBatch = NodeBatch { skyKey: SkyKey? -> this.createIfAbsent(skyKey) }
        this.usePooledInterning = usePooledInterning
        if (usePooledInterning) {
            SkyKeyInterner.Companion.setGlobalPool(SkyKeyPool())
            LabelInterner.setGlobalPool(LabelPool())
        }
    }

    override fun remove(skyKey: SkyKey) {
        weakInternSkyKey(skyKey)
        val nodeEntry: InMemoryNodeEntry? = nodeMap.remove(skyKey)
        if ((skyKey is PackageIdentifier || skyKey is PackagePieceIdentifier)
            && nodeEntry != null
        ) {
            weakInternPackageTargetsLabels(
                nodeEntry.toValue() as PackageoidValue?
            ) // Dirty or changed value are needed.
        }
    }

    override fun removeIfDone(key: SkyKey?) {
        nodeMap.computeIfPresent(
            key,
            java.util.function.BiFunction { k: SkyKey?, e: InMemoryNodeEntry? ->
                if (e.isDone()) {
                    weakInternSkyKey(k)
                    if (k is PackageIdentifier || k is PackagePieceIdentifier) {
                        weakInternPackageTargetsLabels(e.toValue() as PackageoidValue?)
                    }
                    return@computeIfPresent null
                }
                e
            })
    }

    private fun weakInternSkyKey(skyKey: SkyKey) {
        if (!usePooledInterning) {
            return
        }
        val interner: SkyKeyInterner<*>? = skyKey.getSkyKeyInterner()
        if (interner != null) {
            interner.weakInternUnchecked(skyKey)
        }
    }

    private fun weakInternPackageTargetsLabels(packageoidValue: PackageoidValue?) {
        if (!usePooledInterning || packageoidValue == null) {
            return
        }
        val interner: LabelInterner = Label.getLabelInterner()

        val targets: com.google.common.collect.ImmutableSortedMap<String?, Target?> =
            packageoidValue.packageoid.getTargets()
        targets.values().forEach(java.util.function.Consumer { t: Target? -> interner.weakIntern(t.getLabel()) })
    }

    override fun get(
        requestor: SkyKey?,
        reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?,
        skyKey: SkyKey?
    ): NodeEntry? {
        return nodeMap.get(skyKey)
    }

    override fun getBatch(
        requestor: SkyKey?,
        reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?,
        keys: Iterable<out SkyKey>
    ): NodeBatch {
        if (reason == com.google.devtools.build.skyframe.QueryableGraph.Reason.REWINDING) {
            // When rewinding, nodes are typically expected to be in the graph. However, systems with
            // remote caching might not have loaded all dependencies into the local graph if a value was
            // fetched from the cache.
            //
            // Tree artifacts are a key example. Their value (TreeArtifactValue) contains dependency keys.
            // If the TreeArtifactValue is a cache hit, its child dependencies might not exist in the
            // local graph. If a lost input is later discovered to be one of these children, we need to
            // ensure the node entries exist for the rewinding process to analyze them.
            //
            // createIfAbsentBatch ensures that such nodes are present in the graph.
            return createIfAbsentBatch(requestor, reason, keys)
        }
        return getBatch
    }

    override fun getBatchAsync(
        requestor: SkyKey?,
        reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?,
        keys: Iterable<out SkyKey?>?
    ): InterruptibleSupplier<NodeBatch?> {
        return InterruptibleSupplier { getBatch }
    }

    override fun getBatchMap(
        requestor: SkyKey?,
        reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?,
        keys: Iterable<out SkyKey?>
    ): MutableMap<SkyKey?, NodeEntry?> {
        // Use a HashMap, not an ImmutableMap.Builder, because we have not yet deduplicated these keys
        // and ImmutableMap.Builder does not tolerate duplicates. The map will be thrown away shortly.
        val result: HashMap<SkyKey?, NodeEntry?> = HashMap<SkyKey?, NodeEntry?>()
        for (key in keys) {
            val entry: NodeEntry? = get(null, com.google.devtools.build.skyframe.QueryableGraph.Reason.OTHER, key)
            if (entry != null) {
                result.put(key, entry)
            }
        }
        return result
    }

    @com.google.errorprone.annotations.ForOverride
    protected open fun newNodeEntry(key: SkyKey?): InMemoryNodeEntry? {
        return IncrementalInMemoryNodeEntry(key)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    override fun createIfAbsentBatch(
        requestor: SkyKey?,
        reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?,
        keys: Iterable<out SkyKey>
    ): NodeBatch {
        // As per the ProcessableGraph contract, ensures that every node is created, even if it is not
        // consumed from the batch.
        for (key in keys) {
            createIfAbsent(key)
        }
        // Returns `createIfAbsentBatch` instead of `getBatch` because by contract, retrieving a node
        // from the batch should not result in null, even if the corresponding key was removed from the
        // graph.
        return createIfAbsentBatch
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    private fun createIfAbsent(skyKey: SkyKey): InMemoryNodeEntry? {
        val interner: SkyKeyInterner<*>? = skyKey.getSkyKeyInterner()
        if (!usePooledInterning || interner == null) {
            return nodeMap.computeIfAbsent(
                skyKey,
                java.util.function.Function { key: SkyKey? -> this.newNodeEntry(key) })
        }

        // The key is typically already present. Record whether this thread newly created a node so that
        // we can skip calling removeWeak if it was already present.
        val newlyCreated = BooleanArray(1)
        val nodeEntry: InMemoryNodeEntry? =
            nodeMap.computeIfAbsent(
                skyKey,
                java.util.function.Function { k: SkyKey? ->
                    newlyCreated[0] = true
                    newNodeEntry(k)
                })
        if (newlyCreated[0]) {
            interner.removeWeak(skyKey)
        }
        return nodeEntry
    }

    override fun analyzeDepsDoneness(parent: SkyKey?, deps: MutableList<SkyKey?>?): DepsReport {
        return DepsReport.Companion.NO_INFORMATION
    }

    override fun valuesSize(): Int {
        return nodeMap.size()
    }

    val values: MutableMap<SkyKey, SkyValue>
        get() = Collections.unmodifiableMap<SkyKey?, SkyValue?>(
            com.google.common.collect.Maps.transformValues<SkyKey?, InMemoryNodeEntry?, SkyValue?>(
                liveView,
                com.google.common.base.Function { obj: InMemoryNodeEntry? -> obj.toValue() })
        )

    val doneValues: MutableMap<SkyKey, SkyValue>
        get() = Collections.unmodifiableMap<SkyKey?, SkyValue?>(
            com.google.common.collect.Maps.filterValues<SkyKey?, SkyValue?>(
                com.google.common.collect.Maps.transformValues<SkyKey?, InMemoryNodeEntry?, SkyValue?>(
                    liveView,
                    com.google.common.base.Function { entry: InMemoryNodeEntry? -> if (entry.isDone()) entry.getValue() else null }),
                com.google.common.base.Predicate { obj: Any? -> java.util.Objects.nonNull(obj) })
        )

    val allNodeEntries: MutableCollection<InMemoryNodeEntry>
        get() = Collections.unmodifiableCollection<InMemoryNodeEntry?>(nodeMap.values())

    override fun parallelForEach(consumer: java.util.function.Consumer<InMemoryNodeEntry?>?) {
        nodeMap.forEachValue(PARALLELISM_THRESHOLD.toLong(), consumer)
    }

    override fun cleanupInterningPools() {
        if (!usePooledInterning) {
            return
        }
        GoogleAutoProfilerUtils.logged("Cleaning up interning pools", java.time.Duration.ofMillis(2L)).use { ignored ->
            parallelForEach(
                java.util.function.Consumer { e: InMemoryNodeEntry? ->
                    weakInternSkyKey(e.getKey())
                    // TODO(https://github.com/bazelbuild/bazel/issues/23852): support
                    // PackagePieceValue.ForMacro.
                    if (e.getValueMaybeWithMetadata() !== IncrementalInMemoryNodeEntry.Companion.CLEARED_SKY_VALUE && e.isDone()
                        && e.getKey().functionName() == SkyFunctions.PACKAGE
                    ) {
                        weakInternPackageTargetsLabels(e.toValue() as PackageoidValue?)
                    }

                    // The graph is about to be thrown away. Remove as we go to avoid temporarily storing
                    // everything in both the weak interner and the graph.
                    nodeMap.remove(e.getKey())
                })
        }
        SkyKeyInterner.Companion.setGlobalPool(null)
        LabelInterner.setGlobalPool(null)
    }

    override fun getIfPresent(key: SkyKey?): InMemoryNodeEntry? {
        return nodeMap.get(key)
    }

    /** Minimizes the size of the ConcurrentHashMap backing the graph. May be costly to run (O(n)).  */
    override fun shrinkNodeMap() {
        nodeMap = ConcurrentHashMap<SkyKey?, InMemoryNodeEntry?>(nodeMap)
    }

    internal class EdgelessInMemoryGraphImpl(usePooledInterning: Boolean) : InMemoryGraphImpl(usePooledInterning) {
        override fun newNodeEntry(key: SkyKey?): InMemoryNodeEntry {
            return NonIncrementalInMemoryNodeEntry(key)
        }
    }

    /** [PooledInterner.Pool] for [SkyKey]s.  */
    internal inner class SkyKeyPool : PooledInterner.Pool<SkyKey?> {
        public override fun getOrWeakIntern(sample: SkyKey?): SkyKey? {
            // Use computeIfAbsent not to mutate the map, but to call weakIntern under synchronization.
            // This ensures that the canonical instance isn't being transferred to the node map
            // concurrently in createIfAbsent. In the common case that the key is already present in the
            // node map, this is a lock-free lookup.
            val weakInterned: Array<SkyKey?> = arrayOfNulls<SkyKey>(1)
            val nodeEntry: InMemoryNodeEntry? =
                nodeMap.computeIfAbsent(
                    sample,
                    java.util.function.Function { k: SkyKey? ->
                        weakInterned[0] = k.getSkyKeyInterner().weakInternUnchecked(k)
                        null // Don't actually store a mapping.
                    })
            return if (nodeEntry != null) nodeEntry.getKey() else weakInterned[0]
        }
    }

    /** [PooledInterner.Pool] for [Label]s.  */
    internal inner class LabelPool : PooledInterner.Pool<Label?> {
        public override fun getOrWeakIntern(sample: Label): Label? {
            val interner: LabelInterner = com.google.common.base.Preconditions.checkNotNull<T>(Label.getLabelInterner())

            val packageIdentifier: PackageIdentifier? = sample.getPackageIdentifier()

            // Return pooled instance if sample is present in the pool.
            var inMemoryNodeEntry: InMemoryNodeEntry? = nodeMap.get(packageIdentifier)
            if (inMemoryNodeEntry != null) {
                val pooledInstance: Label? = getLabelFromInMemoryNodeEntry(inMemoryNodeEntry, sample)
                if (pooledInstance != null) {
                    return pooledInstance
                }
            }

            val readLock: java.util.concurrent.locks.Lock = interner.getLockForLabelLookup(sample)
            readLock.lock()

            try {
                // Check again whether sample is already present in the pool inside critical section.
                if (inMemoryNodeEntry == null) {
                    inMemoryNodeEntry = nodeMap.get(packageIdentifier)
                }

                if (inMemoryNodeEntry != null) {
                    val pooledInstance: Label? = getLabelFromInMemoryNodeEntry(inMemoryNodeEntry, sample)
                    if (pooledInstance != null) {
                        return pooledInstance
                    }
                }
                return interner.weakIntern(sample)
            } finally {
                readLock.unlock()
            }
        }
    }

    companion object {
        private const val PARALLELISM_THRESHOLD = 1024

        private fun getLabelFromInMemoryNodeEntry(
            inMemoryNodeEntry: InMemoryNodeEntry?, sample: Label
        ): Label? {
            com.google.common.base.Preconditions.checkNotNull<InMemoryNodeEntry?>(inMemoryNodeEntry)
            val value: SkyValue? = inMemoryNodeEntry.toValue()
            if (value == null) {
                return null
            }
            com.google.common.base.Preconditions.checkState(value is PackageoidValue, value)
            val targets: com.google.common.collect.ImmutableSortedMap<String?, Target?> =
                (value as PackageoidValue).packageoid.getTargets()
            val target: Target? = targets.get(sample.name)
            return if (target != null) target.getLabel() else null
        }
    }
}
