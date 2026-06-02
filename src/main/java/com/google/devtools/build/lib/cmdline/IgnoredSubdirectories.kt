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
package com.google.devtools.build.lib.cmdline

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.lib.vfs.UnixGlob
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/** A set of subdirectories to ignore during target pattern matching or globbing.  */
class IgnoredSubdirectories private constructor(
    prefixes: com.google.common.collect.ImmutableSet<PathFragment>,
    patterns: com.google.common.collect.ImmutableList<String>,
    traversalExclusions: com.google.common.collect.ImmutableSet<PathFragment>
) {
    private val prefixes: com.google.common.collect.ImmutableSet<PathFragment>

    // String[] is mutable; we keep the split version because that's faster to match and the non-split
    // one because that allows for simpler equality checking and then matchingEntry() doesn't need to
    // allocate new objects.
    private val patterns: com.google.common.collect.ImmutableList<String>
    private val splitPatterns: com.google.common.collect.ImmutableList<Array<String?>?>
    private val traversalExclusions: com.google.common.collect.ImmutableSet<PathFragment>

    private class Codec : ObjectCodec<IgnoredSubdirectories?> {
        val encodedClass: java.lang.Class<out IgnoredSubdirectories?>
            get() = IgnoredSubdirectories::class.java

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: SerializationContext, obj: IgnoredSubdirectories, codedOut: CodedOutputStream?
        ) {
            context.serialize(obj.prefixes, codedOut)
            context.serialize(obj.patterns, codedOut)
            context.serialize(obj.traversalExclusions, codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserialize(
            context: DeserializationContext, codedIn: CodedInputStream?
        ): IgnoredSubdirectories {
            val prefixes: com.google.common.collect.ImmutableSet<PathFragment>? =
                context.deserialize<com.google.common.collect.ImmutableSet<PathFragment>?>(codedIn)
            val patterns: com.google.common.collect.ImmutableList<String>? =
                context.deserialize<com.google.common.collect.ImmutableList<String>?>(codedIn)
            val traversalExclusions: com.google.common.collect.ImmutableSet<PathFragment>? =
                context.deserialize<com.google.common.collect.ImmutableSet<PathFragment>?>(codedIn)

            return IgnoredSubdirectories(prefixes, patterns, traversalExclusions)
        }

        companion object {
            private val INSTANCE: Codec = com.google.devtools.build.lib.cmdline.IgnoredSubdirectories.Codec()
        }
    }

    init {
        this.prefixes = prefixes
        this.patterns = patterns
        this.splitPatterns =
            patterns.stream()
                .map<Array<String?>?>(java.util.function.Function { p: String? ->
                    com.google.common.collect.Iterables.toArray<String?>(
                        SLASH_SPLITTER.split(p), String::class.java
                    )
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Array<String?>?>())
        this.traversalExclusions = traversalExclusions
    }

    fun withPrefix(prefix: PathFragment): IgnoredSubdirectories {
        com.google.common.base.Preconditions.checkArgument(!prefix.isAbsolute())

        val prefixedPrefixes: com.google.common.collect.ImmutableSet<PathFragment> =
            prefixes.stream()
                .map<PathFragment?>(java.util.function.Function { other: PathFragment? -> prefix.getRelative(other) })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<PathFragment?>())

        val prefixedPatterns: com.google.common.collect.ImmutableList<String> =
            patterns.stream().map<String?>(java.util.function.Function { p: String? -> prefix.toString() + "/" + p })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())

        val prefixedTraversalExclusions: com.google.common.collect.ImmutableSet<PathFragment> =
            traversalExclusions.stream()
                .map<PathFragment?>(java.util.function.Function { other: PathFragment? -> prefix.getRelative(other) })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<PathFragment?>())

        return IgnoredSubdirectories(
            prefixedPrefixes, prefixedPatterns, prefixedTraversalExclusions
        )
    }

    fun union(other: IgnoredSubdirectories): IgnoredSubdirectories {
        return IgnoredSubdirectories(
            com.google.common.collect.ImmutableSet.builder<PathFragment?>().addAll(prefixes).addAll(other.prefixes)
                .build(),
            com.google.common.collect.ImmutableSet.builder<String?>().addAll(patterns).addAll(other.patterns).build()
                .asList(),
            com.google.common.collect.ImmutableSet.builder<PathFragment?>()
                .addAll(traversalExclusions)
                .addAll(other.traversalExclusions)
                .build()
        )
    }

    fun withTraversalExclusions(
        traversalExclusions: com.google.common.collect.ImmutableSet<PathFragment>
    ): IgnoredSubdirectories {
        return IgnoredSubdirectories(this.prefixes, this.patterns, traversalExclusions)
    }

    /** Filters out entries that cannot match anything under `directory`.  */
    fun filterForDirectory(directory: PathFragment): IgnoredSubdirectories {
        val filteredPrefixes: com.google.common.collect.ImmutableSet<PathFragment> =
            prefixes.stream().filter(java.util.function.Predicate { p: PathFragment? -> p.startsWith(directory) })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<PathFragment?>())

        val filteredTraversalExclusions: com.google.common.collect.ImmutableSet<PathFragment> =
            traversalExclusions.stream()
                .filter(java.util.function.Predicate { p: PathFragment? -> p.startsWith(directory) })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<PathFragment?>())

        val splitDirectory: Array<String?> =
            com.google.common.collect.Iterables.toArray<String?>(
                SLASH_SPLITTER.split(directory.getPathString()),
                String::class.java
            )
        val filteredPatterns: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        for (i in patterns.indices) {
            if (UnixGlob.canMatchChild(splitPatterns.get(i), splitDirectory)) {
                filteredPatterns.add(patterns.get(i))
            }
        }

        return IgnoredSubdirectories(
            filteredPrefixes, filteredPatterns.build(), filteredTraversalExclusions
        )
    }

    fun prefixes(): com.google.common.collect.ImmutableSet<PathFragment> {
        return prefixes
    }

    val isEmpty: Boolean
        get() = this.prefixes.isEmpty() && this.patterns.isEmpty()

    /**
     * Checks whether every path in this instance can conceivably match something under `directory`.
     */
    fun allPathsAreUnder(directory: PathFragment?): Boolean {
        for (prefix in prefixes) {
            if (!prefix.startsWith(directory)) {
                return false
            }

            if (prefix == directory) {
                return false
            }
        }

        return true
    }

    /** Returns the entry that matches a given directory or `null` if none.  */
    fun matchingEntry(directory: PathFragment): String? {
        for (prefix in prefixes) {
            if (directory.startsWith(prefix)) {
                return prefix.getPathString()
            }
        }

        val segmentArray: Array<String?> =
            com.google.common.collect.Iterables.toArray<String?>(directory.segments(), String::class.java)
        for (i in patterns.indices) {
            if (UnixGlob.matchesPrefix(splitPatterns.get(i), segmentArray)) {
                return patterns.get(i)
            }
        }

        return null
    }

    /** Returns true if the directory matches any traversal exclusion or standard ignored entry.  */
    fun matchingEntryForTraversal(directory: PathFragment): Boolean {
        if (matchingEntry(directory) != null) {
            return true
        }
        for (exclusion in traversalExclusions) {
            if (directory.startsWith(exclusion)) {
                return true
            }
        }
        return false
    }

    override fun equals(other: Any?): Boolean {
        if (other !is IgnoredSubdirectories) {
            return false
        }

        // splitPatterns is a function of patterns so it's enough to check if patterns is equal
        val that = other
        return this.prefixes == that.prefixes
                && this.patterns == that.patterns
                && this.traversalExclusions == that.traversalExclusions
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(prefixes, patterns, traversalExclusions)
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper("IgnoredSubdirectories")
            .add("prefixes", prefixes)
            .add("patterns", patterns)
            .add("traversalExclusions", traversalExclusions)
            .toString()
    }

    companion object {
        @kotlin.jvm.JvmField
        val EMPTY: IgnoredSubdirectories = IgnoredSubdirectories(
            com.google.common.collect.ImmutableSet.of<PathFragment?>(),
            com.google.common.collect.ImmutableList.of<String?>(),
            com.google.common.collect.ImmutableSet.of<PathFragment?>()
        )

        private val SLASH_SPLITTER: com.google.common.base.Splitter = com.google.common.base.Splitter.on("/")

        @kotlin.jvm.JvmOverloads
        fun of(
            prefixes: com.google.common.collect.ImmutableSet<PathFragment>,
            patterns: com.google.common.collect.ImmutableList<String> = com.google.common.collect.ImmutableList.of<String?>(),
            traversalExclusions: com.google.common.collect.ImmutableSet<PathFragment> = com.google.common.collect.ImmutableSet.of<PathFragment?>()
        ): IgnoredSubdirectories? {
            if (prefixes.isEmpty() && patterns.isEmpty() && traversalExclusions.isEmpty()) {
                return EMPTY
            }

            for (prefix in prefixes) {
                com.google.common.base.Preconditions.checkArgument(!prefix.isAbsolute())
            }
            for (exclusion in traversalExclusions) {
                com.google.common.base.Preconditions.checkArgument(!exclusion.isAbsolute())
            }

            return IgnoredSubdirectories(prefixes, patterns, traversalExclusions)
        }
    }
}
