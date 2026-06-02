// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.actions.Artifact

/**
 * [SkyKey] for requesting all artifacts in a [NestedSet].
 * 
 * 
 * [com.google.devtools.build.lib.skyframe.ArtifactNestedSetFunction] requests the keys
 * returned by [.getDirectDepKeys] to ensure that the Skyframe graph mirrors the [ ] structure. This class is only declared in the `nestedset` package to give it
 * low-level access to the backing `Object[]`.
 * 
 * 
 * An [ArtifactNestedSetKey] must only be created from a [NestedSet] with multiple
 * elements. Singletons should be translated into a direct request for the corresponding [ ][Artifact.key].
 * 
 * 
 * Instances are compared using identity equality on the corresponding `Object[]` (note
 * that to save memory, this class does not retain a [NestedSet.Node]). This means that two
 * instances are not guaranteed to compare equal even if they contain the same set of artifacts.
 */
class ArtifactNestedSetKey private constructor(private val children: Array<Any?>) : ExecutionPhaseSkyKey {
    val directDepKeys: com.google.common.collect.ImmutableList<SkyKey?>
        /**
         * Returns a list of this key's direct dependencies, including [Artifact.key] for leaves and
         * [ArtifactNestedSetKey] for non-leaves.
         */
        get() {
            val depKeys: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<SkyKey?>(children.length)
            for (child in children) {
                if (child is Artifact) {
                    depKeys.add(Artifact.key(child))
                } else {
                    depKeys.add(Companion.createInternal((child as kotlin.Array<kotlin.Any?>?)!!))
                }
            }
            return depKeys.build()
        }

    /** Applies a consumer function to the direct artifacts of this nested set.  */
    @Throws(java.lang.InterruptedException::class)
    fun applyToDirectArtifacts(function: DirectArtifactConsumer) {
        for (child in children) {
            if (child is Artifact) {
                function.accept(child)
            }
        }
    }

    override fun functionName(): SkyFunctionName {
        return SkyFunctions.ARTIFACT_NESTED_SET
    }

    /**
     * Like [NestedSet.toList], returns a duplicate-free list of the artifacts contained beneath
     * the node represented by this key.
     * 
     * 
     * Order of the returned list is undefined.
     */
    fun expandToArtifacts(): com.google.common.collect.ImmutableList<Artifact?>? {
        // Depth is not accurate, but doesn't matter.
        return NestedSet<Artifact?>(
            com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER,  /* depth= */
            3,
            children
        ).toList()
    }

    override fun valueIsShareable(): Boolean {
        // ArtifactNestedSetValue is just a promise that data is available in memory. Not meant for
        // cross-server sharing.
        return false
    }

    val skyKeyInterner: SkyKeyInterner<*>
        get() = interner

    override fun hashCode(): Int {
        return java.lang.System.identityHashCode(children)
    }

    override fun equals(that: Any?): Boolean {
        if (this === that) {
            return true
        }
        return that is ArtifactNestedSetKey
                && children == that.children
    }

    override fun toString(): String {
        return java.lang.String.format("ArtifactNestedSetKey[%s]@%s", children.length, hashCode())
    }

    /** A consumer to be applied to each direct artifact.  */
    fun interface DirectArtifactConsumer {
        @Throws(java.lang.InterruptedException::class)
        fun accept(artifact: Artifact?)
    }

    companion object {
        private val interner: SkyKeyInterner<ArtifactNestedSetKey> = SkyKey.newInterner<ArtifactNestedSetKey?>()

        fun create(set: NestedSet<Artifact?>): ArtifactNestedSetKey {
            val children: Any? = set.getChildren()
            com.google.common.base.Preconditions.checkArgument(
                children is Array<Any>,
                "ArtifactNestedSetKey cannot represent empty or singleton set: %s",
                set
            )
            return createInternal(children as Array<Any?>)
        }

        private fun createInternal(children: Array<Any?>): ArtifactNestedSetKey {
            return interner.intern(ArtifactNestedSetKey(children))
        }

        /**
         * Augments the given rewind graph with all chains of [ArtifactNestedSetKey] nodes reachable
         * from `failedKeyDeps` and non source artifacts in them.
         * 
         * 
         * The walk is terminated when a node is already in the rewind graph.
         */
        fun addNestedSetChainsToRewindGraph(
            rewindGraph: com.google.common.graph.MutableGraph<SkyKey?>, key: ArtifactNestedSetKey
        ) {
            if (rewindGraph.nodes().contains(key)) {
                return
            }
            for (child in key.children) {
                if (child is Artifact) {
                    if (!child.isSourceArtifact()) {
                        rewindGraph.putEdge(key, Artifact.key(child))
                    }
                } else {
                    val nextNode = Companion.createInternal((child as kotlin.Array<kotlin.Any?>?)!!)
                    addNestedSetChainsToRewindGraph(rewindGraph, nextNode)
                    rewindGraph.putEdge(key, nextNode)
                }
            }
        }

        /**
         * Augments the given rewind graph with paths from `failedKey` to `lostArtifacts`
         * discoverable by following the [ArtifactNestedSetKey] nodes in `failedKeyDeps`.
         * 
         * 
         * `rewindGraph` must not contain any [ArtifactNestedSetKey] nodes prior to calling
         * this method.
         */
        fun addNestedSetPathsToRewindGraph(
            rewindGraph: com.google.common.graph.MutableGraph<SkyKey?>,
            failedKey: SkyKey?,
            failedKeyDeps: MutableSet<SkyKey?>,
            lostArtifacts: MutableSet<out Artifact?>
        ) {
            val seen: HashSet<ArtifactNestedSetKey?> = HashSet<ArtifactNestedSetKey?>()
            for (nestedSetDep in com.google.common.collect.Iterables.filter<ArtifactNestedSetKey?>(
                failedKeyDeps,
                ArtifactNestedSetKey::class.java
            )) {
                if (searchForLostArtifacts(nestedSetDep, rewindGraph, lostArtifacts, seen)) {
                    rewindGraph.putEdge(failedKey, nestedSetDep)
                }
            }
        }

        private fun searchForLostArtifacts(
            node: ArtifactNestedSetKey,
            rewindGraph: com.google.common.graph.MutableGraph<SkyKey?>,
            lostArtifacts: MutableSet<out Artifact?>,
            seen: MutableSet<ArtifactNestedSetKey?>
        ): Boolean {
            if (rewindGraph.nodes().contains(node)) {
                return true
            }
            if (!seen.add(node)) {
                return false
            }
            var anyFound = false
            for (child in node.children) {
                if (child is Artifact) {
                    if (lostArtifacts.contains(child)) {
                        rewindGraph.putEdge(node, Artifact.key(child))
                        anyFound = true
                    }
                } else {
                    val nextNode = Companion.createInternal((child as kotlin.Array<kotlin.Any?>?)!!)
                    if (searchForLostArtifacts(nextNode, rewindGraph, lostArtifacts, seen)) {
                        rewindGraph.putEdge(node, nextNode)
                        anyFound = true
                    }
                }
            }
            return anyFound
        }
    }
}
