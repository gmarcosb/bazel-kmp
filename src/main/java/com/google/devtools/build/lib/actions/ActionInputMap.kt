// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.skyframe.TreeArtifactValue

/**
 * Helper for [InputMetadataProvider] implementations.
 * 
 * 
 * Allows [FileArtifactValue] lookups by exec path or [ActionInput]. *Also*
 * allows [ActionInput] to be looked up by exec path.
 * 
 * 
 * This class implements a closed hash-map with the "links" of each bucket's linked list being
 * stored in a flat array to avoid memory allocations and garbage collection.
 * 
 * 
 * This class is thread-compatible.
 */
class ActionInputMap(sizeHint: Int) : InputMetadataProvider {
    /**
     * Trie-like data structure that mimics the filesystem for tree artifacts.
     * 
     * 
     * It is too expensive to store all tree children in the input map individually, so in order to
     * find a child's metadata, we need to find the parent. Sometimes it is necessary to look up an
     * input's metadata by exec path without even knowing whether it is a [TreeFileArtifact],
     * let alone how many directory levels up its parent is. This data structure supports efficient
     * lookups in such cases.
     */
    internal class TrieArtifact {
        // Values in this map are either TrieArtifact (for intermediate directory nodes) or
        // TreeArtifactValue (for terminal nodes). This saves memory by not creating a TrieArtifact for
        // terminal nodes. This optimization is safe because nested tree artifacts are forbidden.
        //
        // We special case when we have a single child in order to save memory. This way, we do not
        // allocate hash maps for path entries with a single child (prefixes of unbranched paths, e.g.
        // [a/b/c/d]/tree{1..n}).
        // Invariant: subFolders is an immutable map iff subFolders.size() <= 1.
        private var subFolders: MutableMap<String?, Any?> = com.google.common.collect.ImmutableMap.of<String?, Any?>()

        fun add(treeExecPath: PathFragment, treeArtifactValue: TreeArtifactValue) {
            var current = this
            val it: MutableIterator<String> = treeExecPath.segments().iterator()
            while (it.hasNext()) {
                val segment = it.next()
                val next = current.subFolders.get(segment)

                if (it.hasNext()) {
                    // Intermediate node.
                    if (next == null) {
                        val newNode = TrieArtifact()
                        current.put(segment, newNode)
                        current = newNode
                    } else {
                        current = next as TrieArtifact
                    }
                } else if (next == null) {
                    // Terminal node.
                    current.put(segment, treeArtifactValue)
                }
            }
        }

        private fun put(name: String, `val`: Any) {
            // Input path segments are commonly shared among actions, so intern before storing.
            var name = name
            name = name.intern()

            when (subFolders.size) {
                0 -> subFolders = com.google.common.collect.ImmutableMap.of<String?, Any?>(name, `val`)
                1 -> {
                    val newMap: MutableMap<String?, Any?> =
                        com.google.devtools.build.lib.collect.compacthashmap.CompactHashMap.createWithExpectedSize(2)
                    newMap.putAll(subFolders)
                    newMap.put(name, `val`)
                    subFolders = newMap
                }

                else -> subFolders.put(name, `val`)
            }
        }

        fun findTreeArtifactNodeAtPrefix(execPath: PathFragment): TreeArtifactValue? {
            var current = this
            for (segment in execPath.segments()) {
                val next = current.subFolders.get(segment)
                if (next == null) {
                    break
                }
                if (next is TreeArtifactValue) {
                    return next
                }
                current = next as TrieArtifact
            }
            return null
        }

        fun forEachTreeArtifact(
            consumer: java.util.function.BiConsumer<PathFragment?, TreeArtifactValue?>, execPath: PathFragment
        ) {
            for (entry in subFolders.entries) {
                val childPath: PathFragment = execPath.getRelative(entry.key)
                when (entry.value) {
                    -> consumer.accept(childPath, val
                        )
                    -> next.forEachTreeArtifact(consumer, childPath)
                    else -> throw java.lang.AssertionError(entry)
                }
            }
        }
    }

    /** The number of elements contained in this map.  */
    private var size: Int

    /**
     * The hash buckets. Values are indexes into the four arrays. The number of buckets is always the
     * smallest power of 2 that is larger than the number of elements.
     */
    private var table: IntArray

    /** Flat array of the next pointers that make up the linked list behind each hash bucket.  */
    private var next: IntArray

    /** The [ActionInput] keys stored in this map.  */
    private var keys: Array<ActionInput?>

    /** The exec paths of the keys.  */
    private var paths: Array<String?>

    /**
     * The values stored in this map. Each value is one of [FileArtifactValue], [ ] or [RunfilesArtifactValue].
     */
    private var values: Array<Any?>

    private var treeArtifactsRoot = TrieArtifact()

    private val filesets: MutableMap<Artifact?, FilesetOutputTree?> =
        com.google.common.collect.Maps.newTreeMap<Artifact?, FilesetOutputTree?>()

    private var runfilesTrees: MutableList<RunfilesTree?> = java.util.ArrayList<RunfilesTree?>()

    init {
        var sizeHint = sizeHint
        sizeHint = java.lang.Math.max(1, sizeHint)
        val tableSize: Int = java.lang.Integer.highestOneBit(sizeHint) shl 1
        size = 0

        table = IntArray(tableSize)
        java.util.Arrays.fill(table, -1)

        next = IntArray(sizeHint)
        keys = arrayOfNulls<ActionInput>(sizeHint)
        paths = arrayOfNulls<String>(sizeHint)
        values = arrayOfNulls<Any>(sizeHint)
    }

    private fun getIndex(execPathString: String): Int {
        val hashCode = execPathString.hashCode()
        var index = hashCode and (table.size - 1)
        if (table[index] == -1) {
            return -1
        }
        index = table[index]
        while (index != -1) {
            if (hashCode == paths[index]!!.hashCode() && execPathString == paths[index]) {
                return index
            }
            index = next[index]
        }
        return -1
    }

    override fun getInputMetadata(input: ActionInput): FileArtifactValue? {
        return getInputMetadataChecked(input)
    }

    override fun getInputMetadataChecked(input: ActionInput): FileArtifactValue? {
        if (isRunfilesTree(input)) {
            val runfilesMetadata: RunfilesArtifactValue? = getRunfilesMetadata(input)
            return if (runfilesMetadata == null) null else runfilesMetadata.getMetadata()
        }

        if (input is TreeFileArtifact) {
            val parent: SpecialArtifact? = input.getParent()
            var treeIndex = getIndex(parent.getExecPathString())
            // If the parent is a subtree artifact, and the subtree is not found, fallback to check the
            // top-level tree artifact.
            // This distinction is necessary to handle two scenarios:
            // 1. When an action is passed a subtree artifact, the subtree and its TreeArtifactValue are
            //    stored in the treeArtifactsRoot. In this case, when requesting the metadata for a
            //    TreeFileArtifact under a subtree, we are able to resolve the correct tree artifact
            //    directly.
            // 2. When an action is passed a top-level tree artifact (that itself contains a subtree of
            //    files), the top-level tree is stored with a flattened TreeArtifactValue containing all
            //    children under the top-level tree. In this case, when requesting the metadata for a
            //    TreeFileArtifact under a subtree, we need to need search for the top-level tree artifact
            //    rather than the file's parent (subtree).
            // tree artifact or a subdirectory (tree artifact) as its input.
            if (treeIndex == -1 && parent.isSubTreeArtifact()) {
                treeIndex = getIndex(parent.getParent().getExecPathString())
            }
            if (treeIndex != -1) {
                com.google.common.base.Preconditions.checkArgument(
                    values[treeIndex] is TreeArtifactValue,
                    "Requested tree file artifact under non-tree/omitted tree artifact: %s",
                    input
                )
                return (values[treeIndex] as TreeArtifactValue).getChildValues().get(input)
            }
        }
        val index = getIndex(input.getExecPathString())
        if (index != -1) {
            val value = values[index]
            return if (value is TreeArtifactValue)
                value.getMetadata()
            else
                value as FileArtifactValue?
        }
        if (input is Artifact) {
            // Non tree artifacts cannot overlap with tree files, therefore we can skip searching the
            // parents.
            return null
        }

        // Check the trees in case input is a non-Artifact ActionInput pointing to a tree artifact file.
        // This can happen if both a TreeArtifact and a Fileset containing the TreeArtifact are inputs
        // to the same action.
        return getMetadataFromTreeArtifacts(input.getExecPath())
    }

    override fun getFileset(input: ActionInput?): FilesetOutputTree? {
        com.google.common.base.Preconditions.checkArgument(isFileset(input), input)

        return filesets.get(input)
    }

    override fun getFilesets(): MutableMap<Artifact?, FilesetOutputTree?> {
        return Collections.unmodifiableMap<Artifact?, FilesetOutputTree?>(filesets)
    }

    override fun getRunfilesMetadata(input: ActionInput?): RunfilesArtifactValue? {
        com.google.common.base.Preconditions.checkArgument(isRunfilesTree(input), input)

        val index = getIndex(input.getExecPathString())
        if (index == -1) {
            return null
        }

        return values[index] as RunfilesArtifactValue?
    }

    override fun getRunfilesTrees(): com.google.common.collect.ImmutableList<RunfilesTree?> {
        return com.google.common.collect.ImmutableList.copyOf<RunfilesTree?>(runfilesTrees)
    }

    /**
     * For each tree artifact in this input map, invokes the given callback with its exec path and its
     * metadata.
     */
    fun forEachTreeArtifact(consumer: java.util.function.BiConsumer<PathFragment?, TreeArtifactValue?>) {
        treeArtifactsRoot.forEachTreeArtifact(consumer, PathFragment.EMPTY_FRAGMENT)
    }

    /**
     * Returns metadata for given path.
     * 
     * 
     * This method is less efficient than [.getInputMetadata], please use that
     * method instead of this one when looking up [action inputs][ActionInput].
     */
    fun getMetadata(execPath: PathFragment): FileArtifactValue? {
        val index = getIndex(execPath.getPathString())
        if (index != -1) {
            val value = values[index]
            return if (value is TreeArtifactValue)
                value.getMetadata()
            else
                value as FileArtifactValue?
        }

        // Fall back to searching the tree artifacts.
        return getMetadataFromTreeArtifacts(execPath)
    }

    private fun getMetadataFromTreeArtifacts(execPath: PathFragment): FileArtifactValue? {
        val tree: TreeArtifactValue? = treeArtifactsRoot.findTreeArtifactNodeAtPrefix(execPath)
        if (tree == null) {
            return null
        }

        val entry: MutableMap.MutableEntry<*, FileArtifactValue?>? = tree.findChildEntryByExecPath(execPath)
        return if (entry != null) entry.getValue() else null
    }

    override fun getTreeMetadata(input: ActionInput?): TreeArtifactValue? {
        com.google.common.base.Preconditions.checkArgument(isTreeArtifact(input), input)
        return getTreeMetadata(input.getExecPath())
    }

    fun getTreeMetadata(execPath: PathFragment): TreeArtifactValue? {
        val index = getIndex(execPath.getPathString())
        if (index < 0) {
            return null
        }
        val value = values[index]
        return if (value is TreeArtifactValue) value else null
    }

    override fun getEnclosingTreeMetadata(execPath: PathFragment): TreeArtifactValue? {
        return treeArtifactsRoot.findTreeArtifactNodeAtPrefix(execPath)
    }

    override fun getInput(execPath: PathFragment): ActionInput? {
        val index = getIndex(execPath.getPathString())
        if (index != -1) {
            return keys[index]
        }

        // Search ancestor paths since execPathString may point to a TreeFileArtifact within one of the
        // tree artifacts.
        val tree: TreeArtifactValue? = treeArtifactsRoot.findTreeArtifactNodeAtPrefix(execPath)
        if (tree == null) {
            return null
        }

        // We must return an entry from the map since a duplicate would not have the generating action
        // key set.
        val entry: MutableMap.MutableEntry<TreeFileArtifact?, *>? = tree.findChildEntryByExecPath(execPath)
        return if (entry != null) entry.getKey() else null
    }

    /**
     * Returns count of unique, top-level [action inputs][ActionInput] in the map.
     * 
     * 
     * Top-level means that each tree artifact, counts as 1, irrespective of the number of children
     * it has.
     */
    fun sizeForDebugging(): Int {
        return size
    }

    fun put(input: ActionInput?, metadata: FileArtifactValue?) {
        com.google.common.base.Preconditions.checkArgument(
            !isTreeArtifact(input),
            "Can't add tree artifact: %s using put -- please use putTreeArtifact for that",
            input
        )
        com.google.common.base.Preconditions.checkArgument(
            !isRunfilesTree(input),
            "Can't add runfiles tree: %s using put -- please use putRunfilesMetadata for that",
            input
        )

        val oldIndex = putIfAbsent(input, metadata)
        com.google.common.base.Preconditions.checkArgument(
            oldIndex == -1 || !isTreeArtifact(keys[oldIndex]),
            "Tried to overwrite tree artifact with a file: '%s' with the same exec path",
            input
        )
    }

    fun putFileset(input: Artifact, outputTree: FilesetOutputTree?) {
        com.google.common.base.Preconditions.checkArgument(input.isFileset(), input)

        filesets.put(input, outputTree)
    }

    fun putRunfilesMetadata(input: Artifact, metadata: RunfilesArtifactValue) {
        com.google.common.base.Preconditions.checkArgument(input.isRunfilesTree(), input)

        val oldIndex = putIfAbsent(input, metadata)
        com.google.common.base.Preconditions.checkState(oldIndex == -1)

        runfilesTrees.add(metadata.getRunfilesTree())
    }

    fun putTreeArtifact(tree: Artifact, treeArtifactValue: TreeArtifactValue) {
        com.google.common.base.Preconditions.checkArgument(tree.isTreeArtifact(), tree)
        // Use a placeholder value so that we don't have to create a new trie entry if the entry is
        // already in the map.
        val oldIndex = putIfAbsent(tree, PLACEHOLDER)
        if (oldIndex != -1) {
            com.google.common.base.Preconditions.checkArgument(
                isTreeArtifact(keys[oldIndex]),
                "Tried to overwrite file with a tree artifact: '%s' with the same exec path",
                tree
            )
            return
        }

        treeArtifactsRoot.add(tree.getExecPath(), treeArtifactValue)
        values[size - 1] = treeArtifactValue
    }

    private fun putIfAbsent(input: ActionInput?, metadata: Any?): Int {
        com.google.common.base.Preconditions.checkNotNull<ActionInput?>(input)
        if (size >= keys.size) {
            resize()
        }
        val path: String = input.getExecPathString()
        val hashCode = path.hashCode()
        var index = hashCode and (table.size - 1)
        var nextIndex = table[index]
        if (nextIndex == -1) {
            table[index] = size
        } else {
            do {
                index = nextIndex
                if (hashCode == paths[index]!!.hashCode() && com.google.common.base.Objects.equal(path, paths[index])) {
                    return index
                }
                nextIndex = next[index]
            } while (nextIndex != -1)
            next[index] = size
        }
        next[size] = -1
        keys[size] = input
        paths[size] = input.getExecPathString()
        values[size] = metadata
        size++
        return -1
    }

    @com.google.common.annotations.VisibleForTesting
    fun clear() {
        java.util.Arrays.fill(table, -1)
        java.util.Arrays.fill(next, -1)
        java.util.Arrays.fill(keys, null)
        java.util.Arrays.fill(paths, null)
        java.util.Arrays.fill(values, null)
        size = 0
        treeArtifactsRoot = TrieArtifact()
        runfilesTrees = java.util.ArrayList<RunfilesTree?>()
    }

    private fun resize() {
        // Resize the data containers.
        keys = java.util.Arrays.copyOf<ActionInput?>(keys, size * 2)
        paths = java.util.Arrays.copyOf<String?>(paths, size * 2)
        values = java.util.Arrays.copyOf<Any?>(values, size * 2)
        next = java.util.Arrays.copyOf(next, size * 2)

        // Resize and recreate the table and links if necessary. We can take shortcuts here as we know
        // there are no duplicate keys.
        if (table.size < size * 2) {
            table = IntArray(table.size * 2)
            java.util.Arrays.fill(table, -1)
            for (i in 0..<size) {
                val index = paths[i]!!.hashCode() and (table.size - 1)
                next[i] = table[index]
                table[index] = i
            }
        }
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("size", size)
            .add("all-files", sizeForDebugging())
            .add("first-fifty-keys", java.util.Arrays.stream<ActionInput?>(keys).limit(50).collect(Collectors.toList()))
            .add("first-fifty-values", java.util.Arrays.stream<Any?>(values).limit(50).collect(Collectors.toList()))
            .add("first-fifty-paths", java.util.Arrays.stream<String?>(paths).limit(50).collect(Collectors.toList()))
            .toString()
    }

    companion object {
        private val PLACEHOLDER = Any()

        private fun isTreeArtifact(input: ActionInput?): Boolean {
            return input is Artifact && input.isTreeArtifact()
        }

        private fun isRunfilesTree(input: ActionInput?): Boolean {
            return input is Artifact && input.isRunfilesTree()
        }

        private fun isFileset(input: ActionInput?): Boolean {
            return input is Artifact && input.isFileset()
        }
    }
}
