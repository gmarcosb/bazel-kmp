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
package com.google.devtools.build.lib.collect

import com.google.devtools.build.lib.vfs.PathFragment
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap


/**
 * A thread-safe PathFragment segment-based trie for inclusion checks.
 * 
 * 
 * The `put` operation is synchronized on the object, whereas the `includes`
 * retrieval operation do not block, and may overlap with `` put, and will reflect the results
 * of the most recently completed `put` operation. That is, if a `put` overlaps with an
 * `includes`, `includes` will return consistent results, either with the state before
 * or after the `put`.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class PathFragmentPrefixTrie {
    private val includedPaths: MutableSet<PathFragment?> = HashSet<PathFragment?>()
    private val excludedPaths: MutableSet<PathFragment?> = HashSet<PathFragment?>()

    private abstract class Segment {
        val segmentMap: MutableMap<String?, Segment>?

        private constructor() {
            this.segmentMap = ConcurrentHashMap<String?, Segment>()
        }

        private constructor(segmentMap: MutableMap<String?, Segment>?) {
            this.segmentMap = segmentMap
        }
    }

    /** An interim segment. This segment has not been explicitly marked as included or excluded.  */
    private class InterimSegment : Segment()

    /** A segment that has been explicitly marked as excluded.  */
    private class ExcludedSegment : Segment {
        private constructor() : super()

        private constructor(segmentMap: MutableMap<String?, Segment>?) : super(segmentMap)
    }

    /** A segment that has been explicitly marked as included.  */
    private class IncludedSegment : Segment {
        private constructor() : super()

        private constructor(segmentMap: MutableMap<String?, Segment>?) : super(segmentMap)
    }

    private val root: Segment

    init {
        root = InterimSegment()
    }

    /** Puts the explicit inclusion or exclusion state for a [PathFragment] into the trie.  */
    @kotlin.jvm.Synchronized
    @Throws(PathFragmentPrefixTrieException::class)
    fun put(pathFragment: PathFragment, included: Boolean) {
        com.google.common.base.Preconditions.checkArgument(
            pathFragment != PathFragment.EMPTY_FRAGMENT,
            "path fragment cannot be the empty fragment."
        )

        var current = root

        val segments: MutableIterator<String> = pathFragment.segments().iterator()
        while (segments.hasNext()) {
            val nextSegment = segments.next()
            if (segments.hasNext()) {
                current =
                    current.segmentMap
                        .computeIfAbsent(nextSegment.intern()) { unused: String? -> InterimSegment() }
                continue
            }

            // This is the last segment.
            val newChild =
                when (current.segmentMap!!.get(nextSegment)) {
                    -> if (included)
                        IncludedSegment(segment.getSegmentMap())
                    else
                        ExcludedSegment(segment.getSegmentMap())

                    null -> if (included) IncludedSegment() else ExcludedSegment()
                    -> throw PathFragmentAlreadyAddedException(pathFragment, false, toString())
                    -> throw PathFragmentAlreadyAddedException(pathFragment, true, toString())
                }
            current.segmentMap.put(nextSegment.intern(), newChild)
        }

        if (included) {
            includedPaths.add(pathFragment)
        } else {
            excludedPaths.add(pathFragment)
        }
    }

    /**
     * Checks if a PathFragment is included, after applying exclusion checks.
     * 
     * 
     * If there is an exact match, its inclusion state will be returned.
     * 
     * 
     * Otherwise, the result corresponds to the longest prefix's inclusion state explicitly defined
     * in the trie. If the state is inconclusive (i.e. none of its ancestors are explicitly defined),
     * then the default is false / excluded.
     */
    fun includes(pathFragment: PathFragment): Boolean {
        if (pathFragment == PathFragment.EMPTY_FRAGMENT) {
            return false
        }

        var current: Segment? = root
        var lastSegment = current

        for (nextSegment in pathFragment.segments()) {
            current = current!!.segmentMap!!.get(nextSegment)
            if (current == null) {
                break
            }

            if (current !is InterimSegment) {
                lastSegment = current // either Included or Excluded
            }
        }
        return lastSegment is IncludedSegment
    }

    override fun toString(): String {
        return ("[included: "
                + includedPaths.stream().sorted().toList()
                + ", excluded: "
                + excludedPaths.stream().sorted().toList()
                + "]")
    }

    /** Exception thrown by [PathFragmentPrefixTrie] methods.  */
    open class PathFragmentPrefixTrieException internal constructor(message: String?) : java.lang.Exception(message)


    /** Exception thrown when a path fragment is added that has already been explicitly added.  */
    class PathFragmentAlreadyAddedException
    internal constructor(pathFragment: PathFragment?, included: Boolean, trieString: String?) :
        PathFragmentPrefixTrieException(
            java.lang.String.format(
                "%s has already been explicitly marked as %s. Current state: %s",
                pathFragment, if (included) "included" else "excluded", trieString
            )
        )

    /** Returns true if there are any included paths in the trie.  */
    fun hasIncludedPaths(): Boolean {
        return !includedPaths.isEmpty()
    }

    companion object {
        @Throws(PathFragmentPrefixTrieException::class)
        fun of(paths: MutableCollection<String>): PathFragmentPrefixTrie {
            val trie = PathFragmentPrefixTrie()
            for (p in paths) {
                if (p.startsWith("-")) {
                    // Exclusion
                    trie.put(PathFragment.create(p.substring(1)), false)
                } else {
                    // Inclusion
                    trie.put(PathFragment.create(p), true)
                }
            }
            return trie
        }

        @Throws(PathFragmentPrefixTrieException::class)
        fun transformValues(
            map: MutableMap<String?, MutableCollection<String?>?>
        ): com.google.common.collect.ImmutableMap<String?, PathFragmentPrefixTrie?> {
            val builder: com.google.common.collect.ImmutableMap.Builder<String?, PathFragmentPrefixTrie?> =
                com.google.common.collect.ImmutableMap.builder<String?, PathFragmentPrefixTrie?>()
            for (entry in map.entrySet()) {
                builder.put(entry.getKey(), of(entry.getValue()))
            }
            return builder.buildOrThrow()
        }
    }
}
