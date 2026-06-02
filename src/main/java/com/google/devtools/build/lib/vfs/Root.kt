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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext

/**
 * A root path used in [RootedPath] and in artifact roots.
 * 
 * 
 * A typical root could be the exec path, a package root, or an output root specific to some
 * configuration. We also support absolute roots for non-hermetic paths outside the user workspace.
 */
abstract class Root : Comparable<Root?> {
    /** Returns a path by concatenating the root and the root-relative path.  */
    abstract fun getRelative(rootRelativePath: PathFragment?): com.google.devtools.build.lib.vfs.Path?

    /** Returns a path by concatenating the root and the root-relative path.  */
    abstract fun getRelative(rootRelativePath: String?): com.google.devtools.build.lib.vfs.Path?

    /** Returns the relative path between the root and the given path.  */
    abstract fun relativize(path: com.google.devtools.build.lib.vfs.Path?): PathFragment?

    /** Returns the relative path between the root and the given absolute path fragment.  */
    abstract fun relativize(absolutePathFragment: PathFragment?): PathFragment?

    /** Returns whether the given path is under this root.  */
    abstract fun contains(path: com.google.devtools.build.lib.vfs.Path?): Boolean

    /** Returns whether the given absolute path fragment is under this root.  */
    abstract fun contains(absolutePathFragment: PathFragment?): Boolean

    /**
     * Returns the underlying path. Please avoid using this method.
     * 
     * 
     * Not all roots are backed by paths, so this may return null.
     */
    abstract fun asPath(): com.google.devtools.build.lib.vfs.Path?

    /** Returns the underlying FileSystem this Root is on.  */
    abstract val fileSystem: com.google.devtools.build.lib.vfs.FileSystem?

    @kotlin.jvm.JvmField
    abstract val isAbsolute: Boolean

    /** Implementation of Root that is backed by a [Path].  */
    class PathRoot private constructor(path: com.google.devtools.build.lib.vfs.Path) : Root() {
        private val path: com.google.devtools.build.lib.vfs.Path

        init {
            this.path = path
        }

        override fun getRelative(rootRelativePath: PathFragment?): com.google.devtools.build.lib.vfs.Path? {
            return path.getRelative(rootRelativePath)
        }

        override fun getRelative(rootRelativePath: String?): com.google.devtools.build.lib.vfs.Path? {
            return path.getRelative(rootRelativePath)
        }

        override fun relativize(path: com.google.devtools.build.lib.vfs.Path): PathFragment? {
            return path.relativeTo(this.path)
        }

        override fun relativize(absolutePathFragment: PathFragment): PathFragment? {
            com.google.common.base.Preconditions.checkArgument(absolutePathFragment.isAbsolute())
            return absolutePathFragment.relativeTo(path.asFragment())
        }

        override fun contains(path: com.google.devtools.build.lib.vfs.Path): Boolean {
            return path.startsWith(this.path)
        }

        override fun contains(absolutePathFragment: PathFragment): Boolean {
            return absolutePathFragment.isAbsolute()
                    && absolutePathFragment.startsWith(path.asFragment())
        }

        override fun asPath(): com.google.devtools.build.lib.vfs.Path {
            return path
        }

        override fun getFileSystem(): com.google.devtools.build.lib.vfs.FileSystem? {
            return path.getFileSystem()
        }

        override fun isAbsolute(): Boolean {
            return false
        }

        override fun toString(): String {
            return path.toString()
        }

        override fun compareTo(o: Root): Int {
            if (o is AbsoluteRoot) {
                return 1
            } else if (o is PathRoot) {
                return path.compareTo(o.path)
            } else {
                throw java.lang.AssertionError("Unknown Root subclass: " + o.getClass().getName())
            }
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || getClass() != o.getClass()) {
                return false
            }
            val pathRoot = o as PathRoot
            return path == pathRoot.path
        }

        override fun hashCode(): Int {
            return path.hashCode()
        }
    }

    /** An absolute root of a file system. Can only resolve absolute path fragments.  */
    class AbsoluteRoot internal constructor(fileSystem: com.google.devtools.build.lib.vfs.FileSystem?) : Root() {
        private val fileSystem: com.google.devtools.build.lib.vfs.FileSystem

        init {
            this.fileSystem =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.FileSystem>(
                    fileSystem
                )
        }

        override fun getRelative(rootRelativePath: PathFragment): com.google.devtools.build.lib.vfs.Path? {
            com.google.common.base.Preconditions.checkArgument(rootRelativePath.isAbsolute())
            return fileSystem.getPath(rootRelativePath)
        }

        override fun getRelative(rootRelativePath: String?): com.google.devtools.build.lib.vfs.Path? {
            return getRelative(PathFragment.Companion.create(rootRelativePath))
        }

        override fun relativize(path: com.google.devtools.build.lib.vfs.Path): PathFragment? {
            return path.asFragment()
        }

        override fun relativize(absolutePathFragment: PathFragment): PathFragment {
            com.google.common.base.Preconditions.checkArgument(absolutePathFragment.isAbsolute())
            return absolutePathFragment
        }

        override fun contains(path: com.google.devtools.build.lib.vfs.Path?): Boolean {
            return true
        }

        override fun contains(absolutePathFragment: PathFragment): Boolean {
            return absolutePathFragment.isAbsolute()
        }

        override fun isAbsolute(): Boolean {
            return true
        }

        override fun asPath(): com.google.devtools.build.lib.vfs.Path? {
            return null
        }

        override fun getFileSystem(): com.google.devtools.build.lib.vfs.FileSystem {
            return fileSystem
        }

        override fun toString(): String {
            return "<absolute root>"
        }

        override fun compareTo(o: Root): Int {
            if (o is AbsoluteRoot) {
                return java.lang.Integer.compare(hashCode(), o.hashCode())
            } else if (o is PathRoot) {
                return -1
            } else {
                throw java.lang.AssertionError("Unknown Root subclass: " + o.getClass().getName())
            }
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is AbsoluteRoot) {
                return false
            }
            return fileSystem == o.fileSystem
        }

        override fun hashCode(): Int {
            return 31 + fileSystem.hashCode()
        }
    }

    /** Serialization dependencies for [RootCodec].  */
    class RootCodecDependencies @kotlin.jvm.JvmOverloads constructor(likelyPopularRoots: Iterable<Root?> = com.google.common.collect.ImmutableList.of<Root?>()) {
        private val likelyPopularRoots: com.google.common.collect.ImmutableList<Root>

        /** Convenience constructor for an instance with one likely root.  */
        constructor(likelyPopularRoot: Root) : this(com.google.common.collect.ImmutableList.of<Root?>(likelyPopularRoot))

        /**
         * Creates an instance with the given likely roots.
         * 
         * 
         * When the RootCodec serializes any Root that compares equal to one of the likely roots, it
         * will be emitted as a single byte. Upon deserializing, that exact Root will be returned
         * (thereby canonicalizing to that Root instance).
         * 
         * 
         * Up to 255 likely roots may be specified. In practice, there should only be very few of
         * them; each serialization event may incur an equality comparison with all the likely roots.
         * Since the likely roots are checked in order, they should be ordered with the most likely ones
         * coming first.
         */
        /** Convenience constructor for an instance with no likely roots.  */
        init {
            this.likelyPopularRoots = com.google.common.collect.ImmutableList.copyOf<Root?>(likelyPopularRoots)
            // max length 255; value at index i encoded as number i + 1; value 0 means "not one of these".
            com.google.common.base.Preconditions.checkArgument(this.likelyPopularRoots.size() < 256)
        }
    }

    @Suppress("unused") // Used at run-time via classpath scanning + reflection.
    private class RootCodec : AsyncObjectCodec<Root?>() {
        val encodedClass: java.lang.Class<out Root?>
            get() = Root::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(context: SerializationContext, root: Root, codedOut: CodedOutputStream) {
            // Common case of a common root.
            val codecDeps: RootCodecDependencies = context.getDependency(RootCodecDependencies::class.java)
            for (i in codecDeps.likelyPopularRoots.indices) {
                val likely: Root = codecDeps.likelyPopularRoots.get(i)
                if (root == likely) {
                    codedOut.write((i + 1).toByte())
                    return
                }
            }

            // Everything else.
            codedOut.write(0.toByte())

            if (root is PathRoot) {
                codedOut.writeBoolNoTag(true)
                PATH_ROOT_CODEC.serialize(context, root, codedOut)
            } else if (root is AbsoluteRoot) {
                codedOut.writeBoolNoTag(false)
                ABSOLUTE_ROOT_CODEC.serialize(context, root, codedOut)
            } else {
                throw java.lang.IllegalStateException("Unexpected Root: " + root)
            }
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeAsync(context: AsyncDeserializationContext, codedIn: CodedInputStream): Root? {
            val likelyIndicator: Int = codedIn.readRawByte().toInt()
            if (likelyIndicator != 0) {
                val codecDeps: RootCodecDependencies = context.getDependency(RootCodecDependencies::class.java)
                val popularRoot: Root = codecDeps.likelyPopularRoots.get(likelyIndicator - 1)
                context.registerInitialValue(popularRoot)
                return popularRoot
            }

            return (if (codedIn.readBool()) PATH_ROOT_CODEC else ABSOLUTE_ROOT_CODEC)
                .deserializeAsync(context, codedIn) as Root?
        }

        companion object {
            private val PATH_ROOT_CODEC: DynamicCodec = DynamicCodec(PathRoot::class.java)
            private val ABSOLUTE_ROOT_CODEC: DynamicCodec = DynamicCodec(AbsoluteRoot::class.java)
        }
    }

    companion object {
        /** Constructs a root from a path.  */
        fun fromPath(path: com.google.devtools.build.lib.vfs.Path): Root {
            return PathRoot(path)
        }

        /** Returns an absolute root. Can only be used with absolute path fragments.  */
        fun absoluteRoot(fileSystem: com.google.devtools.build.lib.vfs.FileSystem): Root {
            return fileSystem.getAbsoluteRoot()
        }

        fun toFileSystem(root: Root, fileSystem: com.google.devtools.build.lib.vfs.FileSystem): Root {
            return if (root.isAbsolute)
                AbsoluteRoot(fileSystem)
            else
                PathRoot(fileSystem.getPath(root.asPath().asFragment()))
        }
    }
}
