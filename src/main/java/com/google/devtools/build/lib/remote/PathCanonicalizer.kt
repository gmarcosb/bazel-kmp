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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.vfs.FileSymlinkLoopException
import com.google.devtools.build.lib.vfs.PathFragment
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Canonicalizes paths like [FileSystem.resolveSymbolicLinks], while storing the intermediate
 * results in a trie so they can be reused by future canonicalizations.
 * 
 * 
 * This is an implementation detail of [RemoteActionFileSystem], factored out for testing.
 * Because [RemoteActionFileSystem] implements a union filesystem and must account for the
 * possibility of symlinks straddling the underlying filesystems, the performance of large
 * filesystem scans can be greatly improved with a custom [FileSystem.resolveSymbolicLinks]
 * implementation that leverages the trie to avoid repeated work.
 * 
 * 
 * On case-insensitive filesystems, accessing the same path through different case variations
 * will produce distinct trie entries. This could be fixed, but it's a performance rather than a
 * correctness concern, and shouldn't matter most of the time.
 * 
 * 
 * Thread-safe: concurrent calls to [.resolveSymbolicLinks] are supported. As with [ ][FileSystem.resolveSymbolicLinks], the result is undefined if the filesystem is mutated
 * concurrently.
 */
internal class PathCanonicalizer(private val resolver: Resolver) {
    internal interface Resolver {
        /**
         * Returns the result of [FileSystem.readSymbolicLink] if the path is a symlink, otherwise
         * null. All but the last path segment must be canonical.
         * 
         * @throws IOException if the file type or symlink target path could not be determined
         */
        @Throws(IOException::class)
        fun resolveOneLink(path: PathFragment?): PathFragment?
    }

    /** A trie node.  */
    private interface Node

    /** A trie node corresponding to a symlink.  */
    private class SymlinkNode(targetPath: PathFragment?) : Node {
        val targetPath: PathFragment?

        init {
            this.targetPath = targetPath
        }
    }

    /** A trie node not corresponding to a symlink.  */
    private class NonSymlinkNode : ConcurrentHashMap<String?, Node?>( /* initialCapacity= */1), Node

    private val root = NonSymlinkNode()

    /** Returns the root node for an absolute path.  */
    private fun getRootNode(path: PathFragment): NonSymlinkNode {
        com.google.common.base.Preconditions.checkArgument(path.isAbsolute())
        // Unix has a single root. Windows has one root per drive.
        if (path.getDriveStrLength() > 1) {
            return root.computeIfAbsent(
                path.getDriveStr(),
                java.util.function.Function { unused: String? -> NonSymlinkNode() }) as NonSymlinkNode
        }
        return root
    }

    /**
     * Canonicalizes a path, reusing cached information if possible.
     * 
     * @param path the path to canonicalize.
     * @param maxLinks the maximum number of symlinks that can be followed in the process of
     * canonicalizing the path.
     * @throws FileSymlinkLoopException if too many symlinks had to be followed.
     * @throws IOException if an I/O error occurs
     * @return the canonical path.
     */
    @Throws(IOException::class)
    private fun resolveSymbolicLinks(path: PathFragment, maxLinks: Int): PathFragment {
        // This code is carefully written to be as fast as possible when the path is already canonical
        // and has been previously cached. Avoid making changes without benchmarking. A tree artifact
        // with hundreds of thousands of files makes for a good benchmark.

        var maxLinks = maxLinks
        var node = getRootNode(path)
        val segments: Iterable<String?> = path.segments()
        var segmentIndex = 0

        // Loop invariants:
        // - `segmentIndex` is the index of the current `segment` relative to the start of `path`. The
        //   first segment has index 0.
        // - `path` is the absolute path to canonicalize. If `segmentIndex` > 0, `path` is already
        //    canonical up to and including `segmentIndex` - 1.
        // - `node` is the trie node corresponding to the `path` prefix ending with `segmentIndex` - 1,
        //   or to the root path when `segmentIndex` is 0.
        for (segment in segments) {
            var nextNode: Node? = node.get(segment)
            if (nextNode == null) {
                val naivePath: PathFragment? = path.subFragment(0, segmentIndex + 1)
                val targetPath: PathFragment? = resolver.resolveOneLink(naivePath)
                nextNode =
                    node.computeIfAbsent(
                        segment,
                        java.util.function.Function { unused: String? -> if (targetPath != null) SymlinkNode(targetPath) else NonSymlinkNode() })
            }

            when (nextNode) {
                -> {
                    if (maxLinks == 0) {
                        throw FileSymlinkLoopException(
                            path.getPathString() + com.google.devtools.build.lib.vfs.FileSystem.ERR_TOO_MANY_SYMLINKS
                        )
                    }
                    maxLinks--

                    // Compute the path obtained by resolving the symlink.
                    // Note that path normalization already handles uplevel references.
                    val newPath: PathFragment
                    if (targetPath.isAbsolute()) {
                        newPath = targetPath.getRelative(path.subFragment(segmentIndex + 1))
                    } else {
                        newPath =
                            path.subFragment(0, segmentIndex)
                                .getRelative(targetPath)
                                .getRelative(path.subFragment(segmentIndex + 1))
                    }

                    // For absolute symlinks, we must start over.
                    // For relative symlinks, it would have been possible to restart after the already
                    // canonicalized prefix, but they're too rare to be worth optimizing for.
                    return resolveSymbolicLinks(newPath, maxLinks)
                }

                -> {
                    node = nonSymlinkNode
                    segmentIndex++
                }
            }
        }

        return path
    }

    /**
     * Canonicalizes a path, reusing cached information if possible.
     * 
     * 
     * See [FileSystem.resolveSymbolicLinks] for the full specification.
     * 
     * @param path the path to canonicalize.
     * @throws FileSymlinkLoopException if too many symlinks had to be followed.
     * @throws IOException if an I/O error occurs
     * @return the canonical path.
     */
    @Throws(IOException::class)
    fun resolveSymbolicLinks(path: PathFragment): PathFragment {
        return resolveSymbolicLinks(path, com.google.devtools.build.lib.vfs.FileSystem.MAX_SYMLINKS)
    }

    /** Removes cached information for a path prefix.  */
    fun clearPrefix(pathPrefix: PathFragment) {
        var node: Node? = getRootNode(pathPrefix)
        var parent: NonSymlinkNode? = null
        var parentSegment: String? = null
        val segments: MutableIterator<String?> = pathPrefix.segments().iterator()
        var hasNext = segments.hasNext()

        while (node != null && hasNext) {
            val segment = segments.next()
            hasNext = segments.hasNext()

            when (node) {
                -> {
                    // Invalidate all intermediate symlinks.
                    if (parent != null) {
                        parent.remove(parentSegment)
                    }
                    return
                }

                -> {
                    if (!hasNext) {
                        // Found the path prefix.
                        nonSymlinkNode.remove(segment)
                    } else {
                        parent = nonSymlinkNode
                        parentSegment = segment
                        node = nonSymlinkNode.get(segment)
                    }
                }
            }
        }
    }
}
