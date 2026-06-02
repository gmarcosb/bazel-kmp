// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionInput

/**
 * Value for TreeArtifacts, which contains a digest and the [FileArtifactValue]s of its child
 * [TreeFileArtifact]s.
 */
@AutoCodec(deserializedInterface = DeserializedSkyValue::class, autoRegister = false)
open class TreeArtifactValue @VisibleForSerialization internal constructor(
    private val digest: ByteArray,
    childData: com.google.common.collect.ImmutableSortedMap<TreeFileArtifact?, FileArtifactValue?>,
    totalChildSize: Long,
    archivedRepresentation: ArchivedRepresentation?,
    resolvedPath: PathFragment?,
    entirelyRemote: Boolean
) : HasDigest, SkyValue {
    /** Builder for constructing multiple instances of [TreeArtifactValue] at once.  */
    class MultiBuilder private constructor() {
        private val map: MutableMap<SpecialArtifact?, Builder?> = HashMap<SpecialArtifact?, Builder?>()

        /**
         * Adds an empty tree artifact into this builder.
         * 
         * @return `this` for convenience
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTree(tree: SpecialArtifact?): MultiBuilder {
            map.computeIfAbsent(tree) { parent: SpecialArtifact? -> Builder(parent) }
            return this
        }

        /**
         * Puts a child tree file into this builder under its [ parent][TreeFileArtifact.getParent], inserting the latter into the builder if not already present.
         * 
         * @return `this` for convenience
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun putChild(child: TreeFileArtifact, metadata: FileArtifactValue?): MultiBuilder {
            map.computeIfAbsent(child.getParent()) { parent: SpecialArtifact? -> Builder(parent) }
                .putChild(child, metadata)
            return this
        }

        /**
         * Sets the archived representation and its metadata for the [ ][ArchivedTreeArtifact.getParent] of the provided tree artifact.
         * 
         * 
         * Setting an archived representation is only allowed once per [ tree artifact][SpecialArtifact].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setArchivedRepresentation(
            archivedArtifact: ArchivedTreeArtifact, metadata: FileArtifactValue?
        ): MultiBuilder {
            map.computeIfAbsent(archivedArtifact.getParent()) { parent: SpecialArtifact? -> Builder(parent) }
                .setArchivedRepresentation(ArchivedRepresentation.Companion.create(archivedArtifact, metadata))
            return this
        }

        /**
         * For each unique parent seen by this builder, passes the aggregated metadata to the specified
         * [BiConsumer].
         */
        fun forEach(consumer: java.util.function.BiConsumer<SpecialArtifact?, TreeArtifactValue?>) {
            map.forEach { (parent: SpecialArtifact?, builder: Builder?) -> consumer.accept(parent, builder!!.build()) }
        }
    }

    /**
     * Archived representation of a tree artifact which contains a representation of the filesystem
     * tree starting with the tree artifact directory.
     * 
     * 
     * Contains both the [artifact][ArchivedTreeArtifact] for the archived file and the
     * metadata for it.
     */
    @AutoCodec
    class ArchivedRepresentation(
        archivedTreeFileArtifact: ArchivedTreeArtifact?,
        archivedFileValue: FileArtifactValue?
    ) {
        val archivedTreeFileArtifact: ArchivedTreeArtifact?
        val archivedFileValue: FileArtifactValue?

        init {
            this.archivedFileValue = archivedFileValue
            this.archivedTreeFileArtifact = archivedTreeFileArtifact
            java.util.Objects.requireNonNull<Any?>(archivedTreeFileArtifact, "archivedTreeFileArtifact")
            java.util.Objects.requireNonNull<Any?>(archivedFileValue, "archivedFileValue")
        }

        companion object {
            fun create(
                archivedTreeFileArtifact: ArchivedTreeArtifact?, fileArtifactValue: FileArtifactValue?
            ): ArchivedRepresentation {
                return ArchivedRepresentation(archivedTreeFileArtifact, fileArtifactValue)
            }
        }
    }

    private val childData: com.google.common.collect.ImmutableSortedMap<TreeFileArtifact?, FileArtifactValue?>
    val totalChildBytes: Long

    /**
     * Optional archived representation of the entire tree artifact which can be sent instead of all
     * the items in the directory.
     */
    private val archivedRepresentation: ArchivedRepresentation?

    /**
     * Optional resolved path.
     * 
     * 
     * See [FileArtifactValue.getResolvedPath] for semantics.
     */
    private val resolvedPath: PathFragment?

    /** Returns true if the [TreeFileArtifact]s are only stored remotely.  */
    val isEntirelyRemote: Boolean

    /** A FileArtifactValue used to stand in for a TreeArtifactValue.  */
    private class TreeArtifactCompositeFileArtifactValue(
        val digest: ByteArray,
        val isRemote: Boolean,
        resolvedPath: PathFragment?
    ) : FileArtifactValue() {
        private val resolvedPath: PathFragment?

        init {
            this.resolvedPath = resolvedPath
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is TreeArtifactCompositeFileArtifactValue) {
                return false
            }
            return digest.contentEquals(o.digest) && resolvedPath == o.resolvedPath
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(digest.contentHashCode(), resolvedPath)
        }

        val type: FileStateType
            get() = FileStateType.DIRECTORY

        val contentsProxy: FileContentsProxy?
            get() = null

        val size: Long
            get() = 0

        public override fun wasModifiedSinceDigest(path: com.google.devtools.build.lib.vfs.Path?): Boolean {
            return false
        }

        val modifiedTime: Long
            get() {
                throw java.lang.UnsupportedOperationException()
            }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("digest", com.google.common.io.BaseEncoding.base16().lowerCase().encode(digest))
                .add("resolvedPath", resolvedPath)
                .toString()
        }

        protected override fun couldBeModifiedByMetadata(o: FileArtifactValue?): Boolean {
            return false
        }

        public override fun getResolvedPath(): PathFragment? {
            return resolvedPath
        }
    }

    open val metadata: FileArtifactValue?
        get() = TreeArtifactCompositeFileArtifactValue(digest, this.isEntirelyRemote, resolvedPath)

    open val childPaths: com.google.common.collect.ImmutableSet<PathFragment?>?
        get() = childData.keys.stream()
            .map<Any?>(TreeFileArtifact::getParentRelativePath)
            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())

    public override fun getDigest(): ByteArray? {
        return digest.clone()
    }

    open val children: com.google.common.collect.ImmutableSortedSet<TreeFileArtifact?>?
        get() = childData.keys

    /** Returns the archived representation of the tree artifact, if present.  */
    fun getArchivedRepresentation(): java.util.Optional<ArchivedRepresentation?> {
        return java.util.Optional.ofNullable<ArchivedRepresentation?>(archivedRepresentation)
    }

    /**
     * Returns the resolved path, if present.
     * 
     * 
     * See [FileArtifactValue.getResolvedPath] for semantics.
     */
    fun getResolvedPath(): java.util.Optional<PathFragment?> {
        return java.util.Optional.ofNullable<PathFragment?>(resolvedPath)
    }

    val archivedArtifact: ArchivedTreeArtifact?
        get() = if (archivedRepresentation != null)
            archivedRepresentation.archivedTreeFileArtifact
        else
            null

    open val childValues: com.google.common.collect.ImmutableSortedMap<TreeFileArtifact?, FileArtifactValue?>
        get() = childData

    /** Returns an entry for child with given exec path or null if no such child is present.  */
    fun findChildEntryByExecPath(
        execPath: PathFragment?
    ): MutableMap.MutableEntry<TreeFileArtifact?, FileArtifactValue?>? {
        val searchToken: ActionInput = ActionInputHelper.fromPath(execPath)
        // Not really a copy -- original map is already an ImmutableSortedMap using the same comparator.
        val casted: com.google.common.collect.ImmutableSortedMap<ActionInput?, FileArtifactValue?> =
            com.google.common.collect.ImmutableSortedMap.copyOf(childData, EXEC_PATH_COMPARATOR)
        com.google.common.base.Preconditions.checkState(
            casted === childData as Any?,
            "Casting children resulted with a copy"
        )
        val entry: MutableMap.MutableEntry<out ActionInput?, FileArtifactValue?>? = casted.floorEntry(searchToken)
        return if (entry != null && entry.key.getExecPath().equals(execPath))
            entry as MutableMap.MutableEntry<TreeFileArtifact?, FileArtifactValue?>
        else
            null
    }

    override fun hashCode(): Int {
        return HashCodes.hashObjects(digest.contentHashCode(), archivedRepresentation, resolvedPath)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (other !is TreeArtifactValue) {
            return false
        }

        return digest.contentEquals(other.digest) && childData == other.childData
                && archivedRepresentation == other.archivedRepresentation
                && resolvedPath == other.resolvedPath
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("digest", digest)
            .add("childData", childData)
            .add("archivedRepresentation", archivedRepresentation)
            .add("resolvedPath", resolvedPath)
            .toString()
    }

    init {
        this.childData = childData
        this.totalChildBytes = totalChildSize
        this.archivedRepresentation = archivedRepresentation
        this.resolvedPath = resolvedPath
        this.isEntirelyRemote = entirelyRemote
    }

    /** Visitor for use in [.visitTree].  */
    fun interface TreeArtifactVisitor {
        /**
         * Called for every directory entry encountered during tree traversal, in a nondeterministic
         * order.
         * 
         * 
         * Regular files and directories are reported as [Dirent.Type.FILE] or [ ][Dirent.Type.DIRECTORY], respectively. Directories are traversed recursively.
         * 
         * 
         * Symlinks that resolve to an existing file or directory are followed and reported as the
         * regular files or directories they point to, recursively for directories. Symlinks that fail
         * to resolve to an existing path cause an [IOException] to be immediately thrown without
         * invoking the visitor. Thus, the visitor is never called with a [Dirent.Type.SYMLINK]
         * type.
         * 
         * 
         * Special files or files whose type could not be determined, regardless of whether they are
         * encountered directly or indirectly through symlinks, cause an [IOException] to be
         * immediately thrown without invoking the visitor. Thus, the visitor is never called with a
         * [Dirent.Type.UNKNOWN] type.
         * 
         * 
         * The `parentRelativePath` argument is always set to the apparent path relative to the
         * tree directory root, without resolving any intervening symlinks. The `traversedSymlink`
         * argument is true if at least one symlink was traversed on the way to the entry being
         * reported.
         * 
         * 
         * If the visitor throws [IOException], traversal is immediately halted and the
         * exception is propagated.
         * 
         * 
         * This method can be called from multiple threads in parallel during a single call of [ ][TreeArtifactVisitor.visitTree].
         */
        @ThreadSafe
        @Throws(IOException::class)
        fun visit(
            parentRelativePath: PathFragment?,
            type: com.google.devtools.build.lib.vfs.Dirent.Type?,
            traversedSymlink: Boolean
        )
    }

    /** An [AbstractQueueVisitor] that visits every file in the tree artifact.  */
    internal class Visitor(parentDir: com.google.devtools.build.lib.vfs.Path?, visitor: TreeArtifactVisitor?) :
        AbstractQueueVisitor(
            VISITOR_POOL,
            ExecutorOwnership.SHARED,
            ExceptionHandlingMode.FAIL_FAST,
            ErrorClassifier.DEFAULT
        ) {
        private val parentDir: com.google.devtools.build.lib.vfs.Path
        private val visitor: TreeArtifactVisitor

        init {
            this.parentDir =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path>(parentDir)
            this.visitor = com.google.common.base.Preconditions.checkNotNull<TreeArtifactVisitor>(visitor)
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun run() {
            execute(
                {
                    visit(
                        PathFragment.EMPTY_FRAGMENT,
                        com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY,  /* traversedSymlink= */
                        false
                    )
                })
            try {
                awaitQuiescence(true)
            } catch (e: UncheckedIOException) {
                throw e.cause
            }
        }

        private fun visit(
            parentRelativePath: PathFragment,
            type: com.google.devtools.build.lib.vfs.Dirent.Type?,
            traversedSymlink: Boolean
        ) {
            var type: com.google.devtools.build.lib.vfs.Dirent.Type? = type
            var traversedSymlink = traversedSymlink
            try {
                val path: com.google.devtools.build.lib.vfs.Path = parentDir.getRelative(parentRelativePath)

                if (type == com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK) {
                    traversedSymlink = true

                    val statFollow: FileStatus = path.statIfFound(Symlinks.FOLLOW)

                    if (statFollow == null) {
                        throw IOException(
                            String.format("child %s is a dangling symbolic link", parentRelativePath)
                        )
                    }

                    if (statFollow.isFile() && !statFollow.isSpecialFile()) {
                        type = com.google.devtools.build.lib.vfs.Dirent.Type.FILE
                    } else if (statFollow.isDirectory()) {
                        type = com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY
                    } else {
                        type = com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN
                    }
                }

                if (type == com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN) {
                    throw IOException(
                        String.format("child %s has an unsupported type", parentRelativePath)
                    )
                }

                visitor.visit(parentRelativePath, type, traversedSymlink)

                if (type == com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY) {
                    for (dirent in path.readdir(Symlinks.NOFOLLOW)) {
                        val childPath: PathFragment = parentRelativePath.getChild(dirent.getName())
                        val childType: com.google.devtools.build.lib.vfs.Dirent.Type? = dirent.getType()
                        val finalTraversedSymlink = traversedSymlink
                        execute({ visit(childPath, childType, finalTraversedSymlink) })
                    }
                }
            } catch (e: IOException) {
                // We can't throw checked exceptions here since AQV expects Runnables
                throw UncheckedIOException(e)
            }
        }
    }

    /** Builder for a [TreeArtifactValue].  */
    class Builder internal constructor(parent: SpecialArtifact) {
        private val childData: com.google.common.collect.ImmutableSortedMap.Builder<TreeFileArtifact?, FileArtifactValue?> =
            childDataBuilder()
        private var archivedRepresentation: ArchivedRepresentation? = null
        private var resolvedPath: PathFragment? = null
        private val parent: SpecialArtifact

        init {
            checkArgument(parent.isTreeArtifact(), "%s is not a tree artifact", parent)
            this.parent = parent
        }

        /**
         * Adds a child to this builder.
         * 
         * 
         * The child's [parent][TreeFileArtifact.getParent] *must* match the parent
         * with which this builder was initialized.
         * 
         * 
         * Children may be added in any order. The children are sorted prior to constructing the
         * final [TreeArtifactValue].
         * 
         * @return `this` for convenience
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun putChild(child: TreeFileArtifact, metadata: FileArtifactValue?): Builder {
            checkArgument(
                child.isChildOf(parent),
                "While building TreeArtifactValue for %s, got %s with parent %s",
                parent,
                child,
                child.getParent()
            )
            childData.put(child, metadata)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setArchivedRepresentation(
            archivedTreeArtifact: ArchivedTreeArtifact?, metadata: FileArtifactValue?
        ): Builder {
            return setArchivedRepresentation(
                ArchivedRepresentation.Companion.create(archivedTreeArtifact, metadata)
            )
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setArchivedRepresentation(archivedRepresentation: ArchivedRepresentation?): Builder {
            com.google.common.base.Preconditions.checkNotNull<ArchivedRepresentation?>(archivedRepresentation)
            com.google.common.base.Preconditions.checkState(
                this.archivedRepresentation == null,
                "Tried to add 2 archived representations for: %s",
                parent
            )
            checkArgument(
                parent.equals(archivedRepresentation!!.archivedTreeFileArtifact.getParent()),
                "Cannot add archived representation: %s for a mismatching tree artifact: %s",
                archivedRepresentation,
                parent
            )
            this.archivedRepresentation = archivedRepresentation
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setResolvedPath(resolvedPath: PathFragment): Builder {
            com.google.common.base.Preconditions.checkArgument(resolvedPath.isAbsolute(), resolvedPath)
            com.google.common.base.Preconditions.checkState(
                this.resolvedPath == null, "Tried to set resolved path multiple times for: %s", parent
            )
            this.resolvedPath = resolvedPath
            return this
        }

        /** Builds the final [TreeArtifactValue].  */
        fun build(): TreeArtifactValue {
            val finalChildData: com.google.common.collect.ImmutableSortedMap<TreeFileArtifact?, FileArtifactValue?> =
                childData.buildOrThrow()
            if (finalChildData.isEmpty() && archivedRepresentation == null && resolvedPath == null) {
                return EMPTY
            }

            val fingerprint: Fingerprint = Fingerprint()
            var entirelyRemote =
                archivedRepresentation == null || archivedRepresentation!!.archivedFileValue.isRemote()

            var totalChildSize: Long = 0
            for (childData in finalChildData.entries) {
                // Digest will be deterministic because children are sorted.
                fingerprint.addPath(childData.key.getParentRelativePath())
                val metadata: FileArtifactValue = childData.value
                metadata.addTo(fingerprint)

                // Tolerate a mix of local and remote children (b/152496153#comment80).
                entirelyRemote = entirelyRemote and metadata.isRemote()

                if (metadata.getType() === FileStateType.REGULAR_FILE) {
                    totalChildSize += metadata.getSize()
                }
            }

            if (archivedRepresentation != null) {
                archivedRepresentation!!.archivedFileValue.addTo(fingerprint)
            }

            return TreeArtifactValue(
                fingerprint.digestAndReset(),
                finalChildData,
                totalChildSize,
                archivedRepresentation,
                resolvedPath,
                entirelyRemote
            )
        }
    }

    companion object {
        private val VISITOR_POOL: ForkJoinPool? = NamedForkJoinPool.newNamedPool(
            "tree-artifact-visitor", java.lang.Runtime.getRuntime().availableProcessors()
        )

        /**
         * Comparator based on exec path which works on [ActionInput] as opposed to [ ]. This way, we can use an [ActionInput] to
         * search [.childData].
         */
        @SerializationConstant
        @VisibleForSerialization
        val EXEC_PATH_COMPARATOR: java.util.Comparator<ActionInput?> =
            java.util.Comparator.comparing<ActionInput?, Any?>(ActionInput::getExecPath)

        val EMPTY_MAP: com.google.common.collect.ImmutableSortedMap<TreeFileArtifact?, FileArtifactValue?> =
            childDataBuilder().buildOrThrow()

        private fun childDataBuilder(): com.google.common.collect.ImmutableSortedMap.Builder<TreeFileArtifact?, FileArtifactValue?> {
            return com.google.common.collect.ImmutableSortedMap.Builder<TreeFileArtifact?, FileArtifactValue?>(
                EXEC_PATH_COMPARATOR
            )
        }

        /** Returns an empty [TreeArtifactValue].  */
        @kotlin.jvm.JvmStatic
        fun empty(): TreeArtifactValue {
            return EMPTY
        }

        /**
         * Returns a new [Builder] for the given parent tree artifact.
         * 
         * 
         * The returned builder only supports adding children under this parent. To build multiple tree
         * artifacts at once, use [MultiBuilder].
         */
        fun newBuilder(parent: SpecialArtifact): Builder {
            return com.google.devtools.build.lib.skyframe.TreeArtifactValue.Builder(parent)
        }

        /** Returns a new [MultiBuilder].  */
        @kotlin.jvm.JvmStatic
        fun newMultiBuilder(): MultiBuilder {
            return MultiBuilder()
        }

        // Note that this is not marked as a @SerializationConstant because we need the deserialized value
        // to implement DeserializedSkyValue. As a result, the deserialized value must be of a different
        // class. We make this work by using a custom codec (see TreeArtifactValueCodec).
        private val EMPTY = TreeArtifactValue(
            MetadataDigestUtils.fromMetadata(com.google.common.collect.ImmutableMap.of<K?, V?>()),
            EMPTY_MAP,
            0L,  /* archivedRepresentation= */
            null,  /* resolvedPath= */
            null,  /* entirelyRemote= */
            false
        )

        /**
         * A TreeArtifactValue that represents a missing TreeArtifact. This is occasionally useful because
         * Java's concurrent collections disallow null members.
         */
        val MISSING_TREE_ARTIFACT: TreeArtifactValue = createMarker("MISSING_TREE_ARTIFACT")

        private fun createMarker(toStringRepresentation: String): TreeArtifactValue {
            return object : TreeArtifactValue(
                null,
                EMPTY_MAP,
                0L,  /* archivedRepresentation= */
                null,  /* resolvedPath= */
                null,  /* entirelyRemote= */
                false
            ) {
                override fun getChildren(): com.google.common.collect.ImmutableSortedSet<TreeFileArtifact?>? {
                    throw java.lang.UnsupportedOperationException(toString())
                }

                override fun getChildValues(): com.google.common.collect.ImmutableSortedMap<TreeFileArtifact?, FileArtifactValue?>? {
                    throw java.lang.UnsupportedOperationException(toString())
                }

                override fun getMetadata(): FileArtifactValue? {
                    throw java.lang.UnsupportedOperationException(toString())
                }

                override fun getChildPaths(): com.google.common.collect.ImmutableSet<PathFragment?>? {
                    throw java.lang.UnsupportedOperationException(toString())
                }

                override fun getDigest(): ByteArray? {
                    throw java.lang.UnsupportedOperationException(toString())
                }

                override fun hashCode(): Int {
                    return java.lang.System.identityHashCode(this)
                }

                override fun equals(other: Any?): Boolean {
                    return this === other
                }

                override fun toString(): String {
                    return toStringRepresentation
                }
            }
        }

        /**
         * Recursively visits all descendants under a directory.
         * 
         * 
         * [TreeArtifactVisitor.visit] is invoked on `visitor` for each directory, file,
         * and symlink under the given `parentDir`, including `parentDir` itself.
         * 
         * 
         * This method is intended to provide uniform semantics for constructing a tree artifact,
         * including special logic that validates directory entries. Invalid directory entries include a
         * symlink that traverses outside of the tree artifact and any entry of [ ][Dirent.Type.UNKNOWN], such as a named pipe.
         * 
         * 
         * The visitor will be called on multiple threads in parallel. Accordingly, it must be
         * thread-safe.
         * 
         * @throws IOException if there is any problem reading or validating outputs under the given tree
         * artifact directory, or if [TreeArtifactVisitor.visit] throws [IOException]
         */
        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun visitTree(parentDir: com.google.devtools.build.lib.vfs.Path?, treeArtifactVisitor: TreeArtifactVisitor?) {
            val visitor: Visitor =
                com.google.devtools.build.lib.skyframe.TreeArtifactValue.Visitor(parentDir, treeArtifactVisitor)
            visitor.run()
        }
    }
}
