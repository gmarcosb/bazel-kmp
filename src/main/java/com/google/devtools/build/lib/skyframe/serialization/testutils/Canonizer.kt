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
package com.google.devtools.build.lib.skyframe.serialization.testutils

import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry
import com.google.devtools.build.lib.skyframe.serialization.testutils.Canonizer
import com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.PrimitiveInfo
import com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector
import com.google.devtools.build.lib.skyframe.serialization.testutils.GraphTraverser
import com.google.devtools.build.lib.skyframe.serialization.testutils.IsomorphismKey
import java.util.ArrayDeque
import java.util.HashMap
import java.util.HexFormat
import java.util.IdentityHashMap

/**
 * A utility for determining compact, canonical representations of arbitrary objects.
 * 
 * 
 * The `Canonizer` provides a more robust solution for generating consistent fingerprints,
 * even for complex, potentially cyclic data structures.
 * 
 * 
 * The core of this utility is a new object canonization algorithm that enables consistent
 * fingerprinting by transforming objects into a canonical form. The algorithm leverages the ability
 * to consistently order edges within the object graph by their labels. This ordered-edge property
 * is crucial for mitigating the combinatorial explosion that typically makes general graph
 * canonization intractable. It is assumed that all data is ordered, which is a valid assumption in
 * the context of serialization where this utility is intended to be used.
 * 
 * 
 * While graph canonization is generally considered a very difficult problem (often associated
 * with the complexity classes NP-complete or Graph Isomorphism), the implemented algorithm exhibits
 * surprisingly good performance in practice. This is due, in part, to a preprocessing step that
 * uses local fingerprinting to partition the object graph into smaller components. Empirical
 * observations suggest that these partitions are usually very small.
 * 
 * 
 * **Algorithm Description: Object Canonization via Partition Refinement**
 * 
 * 
 * *Graph Traversal and Node Creation:* uses the [GraphDataCollector] to traverse the
 * graph and create [Node] instances for non-inlined objects. Inlined objects are fully
 * incorporated into [Node.localFingerprint]. [Node.completeAggregate] triggers
 * computation of the local fingerprint, a hash of its type and its children's representations
 * (either fingerprints or placeholders for child nodes).
 * 
 * 
 * *Partition Refinement:* starts with an initial [Partition] containing all the
 * nodes. It repeatedly calls [Partition.refine] on partitions from [.dirtyPartitions]
 * until it is empty. The [Partition.refine] method computes a [PartitionKey] for each
 * node in the partition and uses it for splitting. Splitting moves nodes to new partitions, which
 * causes any node that was previously pointing at it to mark its partition dirty.
 * 
 * 
 * **Key Concepts:**
 * 
 * 
 *  * *Partition Refinement:* The core of the algorithm is the iterative refinement of
 * partitions. Each partition starts as a set of potentially equivalent objects. The `refine()` method splits partitions based on the [PartitionKey] of their members.
 *  * *Partition Key:* The [PartitionKey] of a [Node] is computed from its
 * `localFingerprint` and the set of [Partition]s its children belong to. This
 * ensures that nodes with different structures or that are connected to different equivalence
 * classes will have different keys.
 *  * *Local Fingerprint:* The `localFingerprint` of a [Node] is a hash that
 * summarizes the node's type and its immediate children's representations (either
 * fingerprints for leaf nodes or placeholders for other nodes). It does not depend on the
 * specific identities of the child nodes, only their equivalence classes (partitions).
 *  * *Ordered Edges:* The algorithm assumes that edges are ordered (e.g., based on field
 * names or array indices). This ordering is crucial for consistently computing the `localFingerprint` and [PartitionKey].
 *  * *Dirty Queue:* The `dirtyPartitions` queue ensures that partitions are
 * re-examined whenever one of their members' keys might have changed due to changes in child
 * partitions.
 * 
 * 
 * 
 * **Complexity:**
 * 
 * 
 * The current implementation has a theoretical worst-case time complexity of O(|N|^2 log |N|),
 * where |N| is the number of nodes in the object graph. This is because, in the worst case, the
 * `refine()` method might re-process all nodes in a large partition in each iteration.
 * However, due to the use of local fingerprinting in the preprocessing step, the practical
 * performance is closer to O(|N|^2 log |P|), where |P| is the size of the largest partition.
 */
class Canonizer private constructor(identifiers: IdentityHashMap<Any?, Any?>) : GraphDataCollector<Canonizer.Node?> {
    /**
     * Reference-based identifiers for all objects.
     * 
     * 
     * The key is the traversed object. The value is a [String] if the object is one of the
     * special cased inline objects, [.outputByteArray], [.outputInlineArray] or [ ][.outputEmptyAggregate]. Otherwise, the value is a [Partition].
     */
    private val identifiers: IdentityHashMap<Any?, Any?>

    private val nodes: IdentityHashMap<Any?, Node?> = IdentityHashMap<Any?, Node?>()

    /** A special sentinel node.  */
    private val rootNode: Node =
        com.google.devtools.build.lib.skyframe.serialization.testutils.Canonizer.Node( /* obj= */null, "root")

    /** A partition of [.nodes].  */
    private val partitions: java.util.ArrayList<Partition> = java.util.ArrayList<Partition>()

    private val dirtyPartitions: ArrayDeque<Partition?> = ArrayDeque<Partition?>()

    init {
        this.identifiers = identifiers
    }

    private fun runPartitionRefinement() {
        var partition: Partition? = com.google.devtools.build.lib.skyframe.serialization.testutils.Canonizer.Partition(
            com.google.common.collect.ImmutableList.copyOf<Node?>(nodes.values())
        )
        partitions.add(partition)
        do {
            partition.enqueued = false
            partition.refine()
        } while ((dirtyPartitions.poll().also { partition = it }) != null)
    }

    private fun updateIdentifiersFromPartitions(identifiers: IdentityHashMap<Any?, Any?>) {
        for (partition in partitions) {
            for (node in partition.members) {
                identifiers.put(node.obj, partition)
            }
        }
    }

    fun computeIsomorphismKey(root: Partition?): IsomorphismKey? {
        // Initializes all the IsomorphismKeys.
        val keys: IdentityHashMap<Partition?, IsomorphismKey?> = IdentityHashMap<Partition?, IsomorphismKey?>()
        for (partition in partitions) {
            keys.put(partition, IsomorphismKey(partition.getLocalFingerprint()))
        }

        // Populates the connections between keys, now that all the keys have been constructed.
        for (entry in keys.entrySet()) {
            // By definition, partition members must be equivalent in their connections so it sufficies to
            // take one arbitrarily.
            val representative: Node = entry.getKey().members.get(0)
            for (child in representative.children) {
                when (child) {
                    -> {}
                    -> entry.getValue().addLink(keys.get(node.getPartition()))
                }
            }
        }

        return keys.get(root) // Returns the root key.
    }

    @com.google.common.annotations.VisibleForTesting
    internal inner class Partition private constructor(members: com.google.common.collect.ImmutableList<Node>) {
        private var members: com.google.common.collect.ImmutableList<Node>

        /** Status bit to avoid double-enqueuing.  */
        private var enqueued = false

        init {
            this.members = members
            for (node in members) {
                node.setPartition(this)
            }
        }

        @com.google.common.annotations.VisibleForTesting
        fun getLocalFingerprint(): String? {
            // By definition, all members have the same local fingerprint, so taking the first suffices.
            return members.get(0).localFingerprint
        }

        /**
         * Refines a partition, splitting its members by key.
         * 
         * 
         * Procedes in the following two phases.
         * 
         * 
         *  1. Recomputes the keys of all members. If a node's key changes, moves it to the correct
         * partition, creating a new one if needed.
         *  1. Notifies parents of all the changed members, marking the partitions of those parents
         * dirty.
         * 
         */
        // used to track largest group when iterating
        private fun refine() {
            // The asymptotic bounds of the implementation can be improved here by carefully tracking
            // which specific nodes require a partition key computation.

            // Splits the nodes by key.

            val groups: HashMap<PartitionKey?, java.util.ArrayList<Node?>> =
                HashMap<PartitionKey?, java.util.ArrayList<Node?>>()
            var largestGroup: java.util.ArrayList<Node?>? = null
            for (node in members) {
                val group: java.util.ArrayList<Node?> =
                    groups.computeIfAbsent(
                        node.computePartitionKey(),
                        java.util.function.Function { unused: PartitionKey? -> java.util.ArrayList<Node?>() })
                group.add(node)
                if (largestGroup == null || group.size() > largestGroup.size()) {
                    largestGroup = group
                }
            }

            if (groups.size() == 1) {
                return  // This partition is still valid because no splitting occurred.
            }

            // Creates partitions for all the groups and transfers nodes to the new partitions.
            for (group in groups.values()) {
                if (group === largestGroup) {
                    members =
                        com.google.common.collect.ImmutableList.copyOf<Node?>(group) // This partition becomes the largest group.
                    continue
                }
                partitions.add(
                    com.google.devtools.build.lib.skyframe.serialization.testutils.Canonizer.Partition(
                        com.google.common.collect.ImmutableList.copyOf<Node?>(
                            group
                        )
                    )
                )
            }

            // Notifies parents of changes in a second phase, after all partitions have been updated.
            for (group in groups.values()) {
                if (group === largestGroup) {
                    continue
                }
                for (node in group) {
                    node.notifyParentsOfPartitionChange()
                }
            }
        }

        private fun enqueue() {
            if (enqueued) {
                return  // Already enqueued.
            }
            if (members.size() < 2) {
                return  // This partition can't be split further.
            }
            dirtyPartitions.offer(this)
            enqueued = true
        }

        override fun toString(): String {
            return "Partition(id=" + hashCode() + ", size=" + members.size() + ")"
        }
    }

    /** An ephemeral object serving as the basis for splitting partitions.  */
    internal class PartitionKey(
        val localFingerprint: String?,
        edges: com.google.common.collect.ImmutableList<Partition?>?
    ) {
        val edges: com.google.common.collect.ImmutableList<Partition?>?

        init {
            this.edges = edges
        }
    }

    /**
     * Corresponds to an object reached by traversal from the initial root object.
     * 
     * 
     * Maintains connections to parents to update them on partition change, which will modifies the
     * parent's [PartitionKey].
     * 
     * 
     * Maintains connections to children in order to compute a [PartitionKey].
     */
    internal class Node private constructor(// null only for the root sentinel node
        private val obj: Any?, private val descriptor: String
    ) : com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Sink {
        /**
         * The local fingerprint of [.obj].
         * 
         * 
         * This incorporates a full description of [.obj]'s inline fields, and labels and
         * placeholders for where other objects connect. It does not contain any information about the
         * specific target of those connections, so remains independent of child changes.
         */
        // initialized in `completeAggregate`
        private var localFingerprint: String? = null

        private val parents: java.util.ArrayList<Node> = java.util.ArrayList<Node>()
        private val children: java.util.ArrayList<ChildEdge> = java.util.ArrayList<ChildEdge>()

        private var partition: Partition? = null

        override fun toString(): String {
            return descriptor + '(' + partition + ')'
        }

        private fun addChild(label: String?, childNode: Node) {
            if (obj == null) { // The root sentinel node has no edges.
                return
            }

            children.add(NodeChild(label, childNode))
            childNode.parents.add(this)
        }

        private fun addLeafChild(label: String?, representation: String?) {
            children.add(LeafChild(prependLabel(label, representation)))
        }

        private fun computePartitionKey(): PartitionKey {
            val childPartitions: com.google.common.collect.ImmutableList.Builder<Partition?> =
                com.google.common.collect.ImmutableList.builder<Partition?>()
            for (child in children) {
                when (child) {
                    -> {}
                    -> childPartitions.add(node.getPartition())
                }
            }
            return PartitionKey(localFingerprint, childPartitions.build())
        }

        private fun setPartition(partition: Partition) {
            this.partition = partition
        }

        private fun notifyParentsOfPartitionChange() {
            for (parent in parents) {
                parent.markDirty()
            }
        }

        private fun markDirty() {
            partition.enqueue() // Enqueues this node's partition.
        }

        override fun completeAggregate() {
            // With all the children added, it is possible to determine `localFingerprint`.
            val description: java.lang.StringBuilder = java.lang.StringBuilder(descriptor)
            for (child in children) {
                description.append(", ")
                when (child) {
                    -> description.append(leaf.labeledRepresentation)
                    -> {
                        if (node.label != null) {
                            description.append(node.label)
                        }
                        description.append(com.google.devtools.build.lib.skyframe.serialization.testutils.Canonizer.Node.Companion.CHILD_NODE_PLACEHOLDER)
                    }
                }
            }
            this.localFingerprint = fingerprintString(description.toString())
        }

        companion object {
            private const val CHILD_NODE_PLACEHOLDER = "TESTUTILS_CANONIZER_PLACEHOLDER"
        }
    }

    private interface ChildEdge

    /**
     * Child having an immediate representation, like an inline value or constant.
     * 
     * @param labeledRepresentation the leaf's label, together with its representation, often a
     * fingerprint, but sometimes a simple inline string representation.
     */
    @kotlin.jvm.JvmRecord
    private data class LeafChild(val labeledRepresentation: String?) : ChildEdge

    private class NodeChild(private val label: String?, private val node: Node) : ChildEdge {
        fun getPartition(): Partition {
            return node.partition!!
        }
    }

    override fun outputNull(label: String?, parentNode: Node) {
        parentNode.addLeafChild(label, "null")
    }

    override fun outputSerializationConstant(
        label: String?, type: java.lang.Class<*>?, tag: Int, parentNode: Node
    ) {
        val representation = Dumper.Companion.getTypeName(type) + "[SERIALIZATION_CONSTANT:" + tag + ']'
        parentNode.addLeafChild(label, representation)
    }

    override fun outputWeakReference(label: String?, parentNode: Node) {
        parentNode.addLeafChild(label, java.lang.ref.WeakReference::class.java.getCanonicalName())
    }

    override fun outputInlineObject(
        label: String?, type: java.lang.Class<*>?, obj: Any?, parentNode: Node
    ) {
        // Emits the type, even for inline values. This avoids a possible ambiguities. For example, "-1"
        // could be a backreference, String, Integer, or other things if there were no type prefix.
        parentNode.addLeafChild(label, Dumper.Companion.getTypeName(type) + ':' + obj)
    }

    override fun outputPrimitive(info: PrimitiveInfo, parent: Any?, parentNode: Node) {
        parentNode.addLeafChild(info.name() + '=', info.getText(parent))
    }

    override fun checkCache(
        label: String?,
        type: java.lang.Class<*>?,
        obj: Any?,
        parentNode: Node
    ): com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor? {
        // During traversal, only String fingerprints are added to identifiers.
        val fingerprint = identifiers.get(obj) as String?
        if (fingerprint != null) {
            parentNode.addLeafChild(label, fingerprint)
            return null
        }

        val node: Node? = nodes.get(obj)
        if (node != null) {
            parentNode.addChild(label, node)
            return null
        }

        return com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor(
            Dumper.Companion.getTypeName(
                type
            ), nodes.size()
        )
    }

    override fun outputByteArray(
        label: String?,
        descriptor: com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor,
        bytes: ByteArray,
        parentNode: Node
    ) {
        val representation = descriptor.description + ": [" + HEX_FORMAT.formatHex(bytes) + ']'
        val fingerprint = fingerprintString(representation)
        identifiers.put(bytes, fingerprint)
        parentNode.addLeafChild(label, fingerprint)
    }

    override fun outputInlineArray(
        label: String?,
        descriptor: com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor,
        arr: Any?,
        parentNode: Node
    ) {
        val representation: java.lang.StringBuilder = java.lang.StringBuilder(descriptor.description).append(": [")
        val length: Int = java.lang.reflect.Array.getLength(arr)
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        representation.append(']')
        val fingerprint = fingerprintString(representation.toString())
        identifiers.put(arr, fingerprint)
        parentNode.addLeafChild(label, fingerprint)
    }

    override fun outputEmptyAggregate(
        label: String?,
        descriptor: com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor,
        obj: Any?,
        parentNode: Node
    ) {
        val representation = descriptor.description + " []"
        val fingerprint = fingerprintString(representation)
        identifiers.put(obj, fingerprint)
        parentNode.addLeafChild(label, fingerprint)
    }

    override fun initAggregate(
        label: String?,
        descriptor: com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor,
        obj: Any?,
        parentNode: Node
    ): Node {
        val node: Node =
            com.google.devtools.build.lib.skyframe.serialization.testutils.Canonizer.Node(obj, descriptor.description)
        nodes.put(obj, node)
        parentNode.addChild(label, node)
        return node
    }

    companion object {
        private val HEX_FORMAT: HexFormat = HexFormat.of().withUpperCase()

        /**
         * Reflectively traverses `obj` and determines identifiers for traversed objects.
         * 
         * @param registry if provided, used to lookup serialization constants
         * @param identifiers (output parameter) map from traversed objects to an object with identity
         * equality semantics. When two objects have the same identifier, they are equivalent. (See
         * [.identifiers] for implementation details of the identifier values).
         * @return a succinct, representation of `obj`, suitable for comparison. Null if no
         * partitions are created, which can be the case if `obj` is completely handled as an
         * inline object, or by [.outputByteArray], [.outputInlineArray] or [     ][.outputEmptyAggregate].
         */
        fun computePartitions(
            registry: ObjectCodecRegistry?,
            obj: Any?,
            identifiers: IdentityHashMap<Any?, Any?>
        ): IsomorphismKey? {
            val canonizer = Canonizer(identifiers)
            GraphTraverser<Node?>(registry, canonizer)
                .traverseObject( /* label= */null, obj, canonizer.rootNode)
            canonizer.runPartitionRefinement()
            canonizer.updateIdentifiersFromPartitions(identifiers)

            val rootNode: Node? = canonizer.nodes.get(obj)
            if (rootNode == null) {
                return null // Purely inline input.
            }
            val rootPartition: Partition? = canonizer.nodes.get(obj).partition
            return canonizer.computeIsomorphismKey(rootPartition)
        }

        fun computeIdentifiers(
            registry: ObjectCodecRegistry?, obj: Any?
        ): IdentityHashMap<Any?, Any?> {
            val identifiers: IdentityHashMap<Any?, Any?> = IdentityHashMap<Any?, Any?>()
            val unusedKey: IsomorphismKey? = computePartitions(registry, obj, identifiers)
            return identifiers
        }

        private fun prependLabel(label: String?, description: String?): String? {
            return if (label == null) description else label + description
        }

        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        fun fingerprintString(text: String): String? {
            // Dumper relies on reference equality of these strings.
            return com.google.common.hash.Hashing.murmur3_128().hashUnencodedChars(text).toString().intern()
        }
    }
}
