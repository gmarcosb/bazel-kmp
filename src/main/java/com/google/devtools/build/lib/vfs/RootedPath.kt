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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization

/**
 * A [PathFragment] relative to a [Root]. Typically, the root is a package path entry.
 * 
 * 
 * Two [RootedPath]s are considered equal iff they have equal roots and equal relative
 * paths.
 * 
 * 
 * Instances are interned (except on Windows), which results in a large memory benefit (see
 * cl/516855266). In addition to being a [SkyKey] itself, [RootedPath] is used as a
 * field in several other common [SkyKey] types. Interning on the level of those keys does not
 * deduplicate referenced [RootedPath] instances which are also used as a [SkyKey]
 * directly.
 */
@AutoCodec
class RootedPath private constructor(root: Root, rootRelativePath: PathFragment) : Comparable<RootedPath?>,
    FileStateKey {
    private val root: Root
    private val rootRelativePath: PathFragment

    // Cache the hash code: RootedPath is used in several of the most common SkyKeys, and we have a
    // free field to spend on it.
    @Transient
    private val hashCode: Int

    fun asPath(): com.google.devtools.build.lib.vfs.Path? {
        return root.getRelative(rootRelativePath)
    }

    fun getRoot(): Root {
        return root
    }

    /** Returns the path fragment relative to `#getRoot`.  */
    fun getRootRelativePath(): PathFragment {
        return rootRelativePath
    }

    val parentDirectory: RootedPath?
        get() {
            val rootRelativeParentDirectory: PathFragment? = rootRelativePath.getParentDirectory()
            if (rootRelativeParentDirectory == null) {
                return null
            }
            return createInternal(root, rootRelativeParentDirectory)
        }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is RootedPath) {
            return false
        }
        return hashCode == obj.hashCode && root == obj.root
                && rootRelativePath == obj.rootRelativePath
    }

    override fun hashCode(): Int {
        return hashCode
    }

    override fun toString(): String {
        return "[" + root + "]/[" + rootRelativePath + "]"
    }

    override fun compareTo(o: RootedPath?): Int {
        return COMPARATOR.compare(this, o)
    }

    init {
        this.root = root
        this.rootRelativePath = rootRelativePath
        this.hashCode = 31 * root.hashCode() + rootRelativePath.hashCode()
    }

    override fun argument(): RootedPath {
        return this
    }

    val skyKeyInterner: SkyKeyInterner<*>?
        get() = interner

    companion object {
        private val interner: SkyKeyInterner<RootedPath?>? = SkyKey.Companion.newInterner<RootedPath?>()

        /** Constructs a [RootedPath] from a [Root] and path fragment relative to the root.  */
        @AutoCodec.Instantiator
        @VisibleForSerialization
        fun createInternal(root: Root, rootRelativePath: PathFragment): RootedPath? {
            com.google.common.base.Preconditions.checkArgument(
                rootRelativePath.isAbsolute() == root.isAbsolute(),
                "rootRelativePath: %s root: %s",
                rootRelativePath,
                root
            )
            val rootedPath = RootedPath(root, rootRelativePath)
            return if (interner != null) interner.intern(rootedPath) else rootedPath
        }

        /** Returns a rooted path representing `rootRelativePath` relative to `root`.  */
        fun toRootedPath(root: Root, rootRelativePath: PathFragment): RootedPath? {
            var rootRelativePath: PathFragment = rootRelativePath
            if (rootRelativePath.isAbsolute() && !root.isAbsolute()) {
                com.google.common.base.Preconditions.checkArgument(
                    root.contains(rootRelativePath),
                    "rootRelativePath '%s' is absolute, but it's not under root '%s'",
                    rootRelativePath,
                    root
                )
                rootRelativePath = root.relativize(rootRelativePath)
            }
            return createInternal(root, rootRelativePath)
        }

        /** Returns a rooted path representing `path` under the root `root`.  */
        fun toRootedPath(root: Root, path: com.google.devtools.build.lib.vfs.Path): RootedPath? {
            com.google.common.base.Preconditions.checkArgument(root.contains(path), "path: %s root: %s", path, root)
            return Companion.toRootedPath(root, path.asFragment())
        }

        /**
         * Returns a rooted path representing `path` under one of the specified roots, or under the
         * file system root if it's not under any of the roots in `packagePathRoots`.
         */
        fun toRootedPathMaybeUnderRoot(
            path: com.google.devtools.build.lib.vfs.Path,
            packagePathRoots: Iterable<Root>
        ): RootedPath? {
            for (root in packagePathRoots) {
                if (root.contains(path)) {
                    return Companion.toRootedPath(root, path)
                }
            }
            return Companion.toRootedPath(Root.Companion.absoluteRoot(path.getFileSystem()), path)
        }

        private val COMPARATOR: java.util.Comparator<RootedPath?> =
            java.util.Comparator.comparing<RootedPath?, Root?>(java.util.function.Function { obj: RootedPath? -> obj!!.getRoot() })
                .thenComparing<PathFragment?>(java.util.function.Function { obj: RootedPath? -> obj!!.getRootRelativePath() })
    }
}
