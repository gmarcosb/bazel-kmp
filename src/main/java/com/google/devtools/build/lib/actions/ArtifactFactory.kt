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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.cmdline.LabelConstants

/** A cache of Artifacts, keyed by Path.  */
@ThreadSafe
class ArtifactFactory(execRootParent: Path, derivedPathPrefix: String?) : ArtifactResolver {
    private val execRootParent: Path
    private val externalSourceBase: Path?
    private val derivedPathPrefix: PathFragment?
    private var siblingRepositoryLayout = false

    /** Cache of source artifacts.  */
    private val sourceArtifactCache = SourceArtifactCache()

    /**
     * Map of package names to source root paths so that we can create source artifact paths given
     * execPaths in the symlink forest.
     */
    private var packageRoots: PackageRootLookup? = null

    private class SourceArtifactCache {
        private class Entry(artifact: SourceArtifact?, buildId: Int) {
            fun isInvalid(currentBuildId: Int): Boolean {
                return buildId != currentBuildId
            }

            val artifact: SourceArtifact?
            val buildId: Int

            init {
                this.artifact = artifact
                this.buildId = buildId
            }
        }

        /**
         * The main Path to source artifact cache. There will always be exactly one canonical artifact
         * for a given source path.
         * 
         * 
         * Since some use cases require case-insensitive lookups, the map uses a case-insensitive key
         * lookup. A ConcurrentSkipListMap supports this without a PathFragment wrapper, which saves
         * memory. The corresponding value is either a single Entry, or a list of Entry objects if there
         * are multiple artifacts with case-insensitively equivalent paths. This structure is heavily
         * optimized for the common case of a single artifact per case-insensitive equivalence class and
         * may perform poorly if there are many artifacts with case-insensitively equivalent paths.
         */
        private val pathToSourceArtifact: ConcurrentMap<PathFragment?, Any?> =
            ConcurrentSkipListMap<PathFragment?, Any?>(
                java.util.Comparator.comparing<Any?, String?>(
                    java.util.function.Function { pathFragment: Any? -> StringEncoding.internalToUnicode(pathFragment.getPathString()) },
                    java.lang.String.CASE_INSENSITIVE_ORDER
                )
            )

        /** Id of current build. Has to be increased every time before analysis starts.  */
        private var buildId = -1

        fun unwrapCacheObject(execPath: PathFragment?, cacheObject: Any?): Entry? {
            return when (cacheObject) {
                null -> null
                -> if (entry.artifact.getExecPath().equals(execPath)) entry else null
                -> {
                    for (entryObject in entries) {
                        val entry = entryObject as Entry
                        if (entry.artifact.getExecPath().equals(execPath)) {
                            entry
                        }
                    }
                    null
                }

                else -> throw java.lang.IllegalStateException(
                    "Unexpected cache object type: %s, value: %s"
                        .formatted(cacheObject.getClass(), cacheObject)
                )
            }
        }

        fun getEntry(execPath: PathFragment?): Entry? {
            return unwrapCacheObject(execPath, pathToSourceArtifact.get(execPath))
        }

        fun computeEntry(
            execPath: PathFragment?, computeFunction: java.util.function.BiFunction<PathFragment?, Entry?, Entry?>
        ): Entry? {
            return unwrapCacheObject(
                execPath, pathToSourceArtifact.compute(execPath, liftToCacheObject(computeFunction))
            )
        }

        /** Returns artifact if it present in the cache, otherwise null.  */
        @ThreadSafe
        fun getArtifact(execPath: PathFragment?): SourceArtifact? {
            val cacheEntry = getEntry(execPath)
            return if (cacheEntry == null) null else cacheEntry.artifact
        }

        /**
         * Returns artifact if it is present in the cache and has been verified to be valid for this
         * build, otherwise null. Note that if the artifact's package is not part of the current build,
         * our differing methods of validating source roots (via [PackageRootResolver] and via
         * [.findSourceRoot]) may disagree. In that case, the artifact will be valid, but unusable
         * by any action (since no action has properly declared it as an input).
         */
        @ThreadSafe
        fun getArtifactIfValid(execPath: PathFragment?): SourceArtifact? {
            val cacheEntry = getEntry(execPath)
            return if (cacheEntry == null || cacheEntry.isInvalid(buildId)) null else cacheEntry.artifact
        }

        /**
         * Returns all entries with case-insensitively equivalent exec paths. The returned list contains
         * the raw cache entries, which may or may not be valid for the current build.
         */
        @ThreadSafe
        fun getEntriesWithAsciiCaseInsensitivePath(execPath: PathFragment?): com.google.common.collect.ImmutableList<Entry?> {
            val cacheObject: Any? = pathToSourceArtifact.get(execPath)
            return when (cacheObject) {
                null -> com.google.common.collect.ImmutableList.of<Entry?>()
                -> com.google.common.collect.ImmutableList.of<Entry?>(entry)
                -> com.google.common.collect.ImmutableList.copyOf<Entry?>(entries as CopyOnWriteArrayList<Entry?>)
                else -> throw java.lang.IllegalStateException(
                    "Unexpected cache object type: %s, value: %s"
                        .formatted(cacheObject.getClass(), cacheObject)
                )
            }
        }

        /**
         * Returns a list of artifacts with case-insensitively equivalent exec paths that are present in
         * the cache and have been verified to be valid for this build. Note that if the artifacts'
         * packages are not part of the current build, our differing methods of validating source roots
         * (via [PackageRootResolver] and via [.findSourceRoot]) may disagree. In that case,
         * the artifacts will be valid, but unusable by any action (since no action has properly
         * declared them as inputs).
         */
        @ThreadSafe
        fun getValidArtifactsWithAsciiCaseInsensitivePath(
            execPath: PathFragment?
        ): com.google.common.collect.ImmutableList<SourceArtifact?> {
            return getEntriesWithAsciiCaseInsensitivePath(execPath).stream()
                .filter(java.util.function.Predicate { entry: Entry? -> !entry!!.isInvalid(buildId) })
                .map<SourceArtifact?>(com.google.devtools.build.lib.actions.ArtifactFactory.SourceArtifactCache.Entry::artifact)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<SourceArtifact?>())
        }

        /**
         * Returns a list of all artifacts with case-insensitively equivalent exec paths that are
         * present in the cache, regardless of whether they have been verified to be valid for this
         * build. This is used to find stale artifacts from previous builds that can be revalidated
         * using their original (correct-casing) exec paths.
         */
        @ThreadSafe
        fun getAllArtifactsWithAsciiCaseInsensitivePath(
            execPath: PathFragment?
        ): com.google.common.collect.ImmutableList<SourceArtifact> {
            return getEntriesWithAsciiCaseInsensitivePath(execPath).stream()
                .map<SourceArtifact?>(com.google.devtools.build.lib.actions.ArtifactFactory.SourceArtifactCache.Entry::artifact)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<SourceArtifact?>())
        }

        fun newBuild() {
            buildId++
        }

        fun clear() {
            pathToSourceArtifact.clear()
            buildId = -1
        }

        companion object {
            private fun liftToCacheObject(
                computeFunction: java.util.function.BiFunction<PathFragment?, Entry?, Entry?>
            ): java.util.function.BiFunction<PathFragment?, Any?, Any?> {
                return java.util.function.BiFunction { execPath: PathFragment?, cacheObject: Any? ->
                    when (cacheObject) {
                        null -> computeFunction.apply(execPath, null)
                        -> if (entry.artifact.getExecPath().equals(execPath))
                            computeFunction.apply(execPath, entry)
                        else
                            CopyOnWriteArrayList<Entry?>(
                                arrayOf<Entry?>(entry, computeFunction.apply(execPath, null))
                            )

                        -> {
                            val entries: CopyOnWriteArrayList<Entry> = rawEntries as CopyOnWriteArrayList<Entry>
                            for (i in entries.indices) {
                                // Update the existing entry for this exact casing if it exists.
                                val entry: Entry = entries.get(i)
                                if (entry.artifact.getExecPath().equals(execPath)) {
                                    val newEntry: Entry? = computeFunction.apply(execPath, entry)
                                    if (newEntry !== entry) {
                                        entries.set(i, newEntry)
                                    }
                                    entries
                                }
                            }
                            // No entry for this exact casing, add a new one.
                            entries.add(computeFunction.apply(execPath, null))
                            entries
                        }

                        else -> throw java.lang.IllegalStateException(
                            "Unexpected cache object type: %s, value: %s"
                                .formatted(cacheObject.getClass(), cacheObject)
                        )
                    }
                }
            }
        }
    }

    /**
     * Constructs a new artifact factory that will use a given execution root when creating artifacts.
     * 
     * @param execRootParent the execution root's parent path. This will be [output_base]/execroot.
     */
    init {
        this.execRootParent = execRootParent
        this.externalSourceBase =
            execRootParent
                .getParentDirectory()
                .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)
        this.derivedPathPrefix = PathFragment.create(derivedPathPrefix)
    }

    /** Clear the cache.  */
    @kotlin.jvm.Synchronized
    fun clear() {
        packageRoots = null
        sourceArtifactCache.clear()
    }

    fun setSiblingRepositoryLayout(siblingRepositoryLayout: Boolean) {
        this.siblingRepositoryLayout = siblingRepositoryLayout
    }

    /**
     * Set the set of known packages and their corresponding source artifact roots. Must be called
     * exactly once after construction or clear().
     * 
     * @param packageRoots provider of a source root given a package identifier.
     */
    @kotlin.jvm.Synchronized
    fun setPackageRoots(packageRoots: PackageRootLookup?) {
        this.packageRoots = packageRoots
    }

    @kotlin.jvm.Synchronized
    fun noteAnalysisStarting() {
        sourceArtifactCache.newBuild()
    }

    override fun getSourceArtifact(execPath: PathFragment, root: Root): SourceArtifact? {
        return getSourceArtifact(execPath, root, ArtifactOwner.Companion.NULL_OWNER)
    }

    override fun getSourceArtifact(execPath: PathFragment, root: Root, owner: ArtifactOwner?): SourceArtifact? {
        // TODO(jungjw): Come up with a more reliable way to distinguish external source roots.
        val artifactRoot: ArtifactRoot =
            if (root.asPath() != null && root.asPath().startsWith(externalSourceBase))
                ArtifactRoot.Companion.asExternalSourceRoot(root)
            else
                ArtifactRoot.Companion.asSourceRoot(root)
        return getSourceArtifact(execPath, artifactRoot, owner)
    }

    fun getSourceArtifact(
        execPath: PathFragment, root: ArtifactRoot, owner: ArtifactOwner?
    ): SourceArtifact? {
        com.google.common.base.Preconditions.checkArgument(
            execPath.isAbsolute() === root.getRoot().isAbsolute(), "%s %s %s", execPath, root, owner
        )
        com.google.common.base.Preconditions.checkNotNull<ArtifactOwner?>(owner, "%s %s", execPath, root)
        return getArtifact(root, execPath, owner,  /* type= */null) as SourceArtifact?
    }

    private fun validatePath(rootRelativePath: PathFragment, root: ArtifactRoot) {
        com.google.common.base.Preconditions.checkArgument(!root.isSourceRoot())
        com.google.common.base.Preconditions.checkArgument(
            rootRelativePath.isAbsolute() === root.getRoot().isAbsolute(), rootRelativePath
        )
        com.google.common.base.Preconditions.checkArgument(
            !rootRelativePath.containsUplevelReferences(),
            rootRelativePath
        )
        com.google.common.base.Preconditions.checkArgument(
            root.getRoot().asPath().startsWith(execRootParent),
            "%s must start with %s, root = %s, root fs = %s, execRootParent fs = %s",
            root.getRoot(),
            execRootParent,
            root,
            root.getRoot().asPath().getFileSystem(),
            execRootParent.getFileSystem()
        )
        com.google.common.base.Preconditions.checkArgument(
            !root.getRoot().asPath().equals(execRootParent),
            "%s %s %s",
            root.getRoot(),
            execRootParent,
            root
        )
        // TODO(bazel-team): this should only accept roots from derivedRoots.
        // Preconditions.checkArgument(derivedRoots.contains(root), "%s not in %s", root, derivedRoots);
    }

    /**
     * Returns an artifact for a tool at the given root-relative path under the given root, creating
     * it if not found. This method only works for normalized, relative paths.
     * 
     * 
     * The root must be below the execRootParent, and the execPath of the resulting Artifact is
     * computed as `root.getRelative(rootRelativePath).relativeTo(root.execRoot)`.
     */
    // TODO(bazel-team): Don't allow root == execRootParent.
    fun getDerivedArtifact(
        rootRelativePath: PathFragment, root: ArtifactRoot, owner: ArtifactOwner?
    ): DerivedArtifact? {
        validatePath(rootRelativePath, root)
        return getArtifact(root, root.getExecPath().getRelative(rootRelativePath), owner, null) as DerivedArtifact?
    }

    /**
     * Returns an artifact that represents the output directory of a Fileset at the given
     * root-relative path under the given root, creating it if not found. This method only works for
     * normalized, relative paths.
     * 
     * 
     * The root must be below the execRootParent, and the execPath of the resulting Artifact is
     * computed as `root.getRelative(rootRelativePath).relativeTo(root.execRoot)`.
     */
    fun getFilesetArtifact(
        rootRelativePath: PathFragment, root: ArtifactRoot, owner: ArtifactOwner?
    ): DerivedArtifact? {
        validatePath(rootRelativePath, root)
        return getArtifact(
            root,
            root.getExecPath().getRelative(rootRelativePath),
            owner,
            SpecialArtifactType.FILESET
        ) as DerivedArtifact?
    }

    fun getRunfilesArtifact(
        rootRelativePath: PathFragment, root: ArtifactRoot, owner: ArtifactOwner?
    ): DerivedArtifact? {
        validatePath(rootRelativePath, root)
        return getArtifact(
            root,
            root.getExecPath().getRelative(rootRelativePath),
            owner,
            SpecialArtifactType.RUNFILES
        ) as DerivedArtifact?
    }

    /**
     * Returns an artifact that represents a TreeArtifact; that is, a directory containing some tree
     * of ArtifactFiles unknown at analysis time.
     * 
     * 
     * The root must be below the execRootParent, and the execPath of the resulting Artifact is
     * computed as `root.getRelative(rootRelativePath).relativeTo(root.execRoot)`.
     */
    fun getTreeArtifact(
        rootRelativePath: PathFragment, root: ArtifactRoot, owner: ArtifactOwner?
    ): SpecialArtifact? {
        validatePath(rootRelativePath, root)
        return getArtifact(
            root,
            root.getExecPath().getRelative(rootRelativePath),
            owner,
            SpecialArtifactType.TREE
        ) as SpecialArtifact?
    }

    /**
     * Returns an artifact that represents an unresolved symlink; that is, an artifact whose value is
     * a symlink and is never dereferenced.
     * 
     * 
     * The root must be below the execRootParent, and the execPath of the resulting Artifact is
     * computed as `root.getRelative(rootRelativePath).relativeTo(root.execRoot)`.
     */
    fun getSymlinkArtifact(
        rootRelativePath: PathFragment, root: ArtifactRoot, owner: ArtifactOwner?
    ): SpecialArtifact? {
        validatePath(rootRelativePath, root)
        return getArtifact(
            root,
            root.getExecPath().getRelative(rootRelativePath),
            owner,
            SpecialArtifactType.UNRESOLVED_SYMLINK
        ) as SpecialArtifact?
    }

    fun getConstantMetadataArtifact(
        rootRelativePath: PathFragment, root: ArtifactRoot, owner: ArtifactOwner?
    ): DerivedArtifact? {
        validatePath(rootRelativePath, root)
        return getArtifact(
            root,
            root.getExecPath().getRelative(rootRelativePath),
            owner,
            SpecialArtifactType.CONSTANT_METADATA
        ) as DerivedArtifact?
    }

    /**
     * Returns the Artifact for the specified path, creating one if not found and setting the `
     * root` and `execPath` to the specified values.
     */
    private fun getArtifact(
        root: ArtifactRoot?,
        execPath: PathFragment?,
        owner: ArtifactOwner?,
        type: SpecialArtifactType?
    ): Artifact? {
        com.google.common.base.Preconditions.checkNotNull<ArtifactRoot?>(root)
        com.google.common.base.Preconditions.checkNotNull<Any?>(execPath)

        if (!root.isSourceRoot()) {
            return createArtifact(root, execPath, owner, type)
        }

        // Double-checked locking to avoid locking cost when possible.
        val firstArtifact: SourceArtifact? = sourceArtifactCache.getArtifact(execPath)
        if (firstArtifact != null && !firstArtifact.differentOwnerOrRoot(owner, root)) {
            return firstArtifact
        }
        val newEntry =
            sourceArtifactCache.computeEntry(
                execPath,
                java.util.function.BiFunction { k: PathFragment?, entry: SourceArtifactCache.Entry? ->
                    if (entry == null || entry.artifact == null || entry.artifact.differentOwnerOrRoot(owner, root)) {
                        // There really should be a safety net that makes it impossible to create two
                        // Artifacts with the same exec path but a different Owner, but we also need to
                        // reuse Artifacts from previous builds.
                        return@computeEntry com.google.devtools.build.lib.actions.ArtifactFactory.SourceArtifactCache.Entry(
                            createArtifact(root, execPath, owner, type) as SourceArtifact,
                            sourceArtifactCache.buildId
                        )
                    }
                    entry
                })
        return newEntry.artifact
    }

    private fun isDefinitelyNotSourceExecPath(execPath: PathFragment): Boolean {
        // Source exec paths cannot escape the source root.
        if (siblingRepositoryLayout) {
            // The exec path may start with .. if using --experimental_sibling_repository_layout, so test
            // the subfragment from index 1 onwards.
            if (execPath.subFragment(1).containsUplevelReferences()) {
                return true
            }
        } else if (execPath.containsUplevelReferences()) {
            return true
        }

        return false
    }

    /**
     * Returns an [Artifact] with exec path formed by composing `baseExecPath` and `relativePath` (via `baseExecPath.getRelative(relativePath)` if baseExecPath is not null).
     * That Artifact will have root determined by the package roots of this factory if it lives in a
     * subpackage distinct from that of baseExecPath, and `baseRoot` otherwise.
     * 
     * 
     * Thread-safety: does only reads until the call to #createArtifactIfNotValid. That may perform
     * mutations, but is thread-safe. There is the potential for a race in which one thread observes
     * no matching artifact in [.sourceArtifactCache] initially, but when it goes to create it,
     * does find it there, but that is a benign race.
     */
    @ThreadSafe
    fun resolveSourceArtifactWithAncestor(
        relativePath: PathFragment,
        baseExecPath: PathFragment?,
        baseRoot: ArtifactRoot?,
        repositoryName: RepositoryName?
    ): SourceArtifact? {
        com.google.common.base.Preconditions.checkState(
            (baseExecPath == null) == (baseRoot == null),
            "%s %s %s",
            relativePath,
            baseExecPath,
            baseRoot
        )
        com.google.common.base.Preconditions.checkState(
            !relativePath.isEmpty(), "%s %s %s", relativePath, baseExecPath, baseRoot
        )
        val execPath: PathFragment =
            if (baseExecPath != null) baseExecPath.getRelative(relativePath) else relativePath

        if (isDefinitelyNotSourceExecPath(execPath)) {
            return null
        }

        // Don't create an artifact if it's derived.
        if (isDerivedArtifact(execPath)) {
            return null
        }
        val artifact: SourceArtifact? = sourceArtifactCache.getArtifactIfValid(execPath)
        if (artifact != null) {
            return artifact
        }
        val sourceRoot: Root? =
            findSourceRoot(
                execPath, baseExecPath, if (baseRoot == null) null else baseRoot.getRoot(), repositoryName
            )
        return createArtifactIfNotValid(sourceRoot, execPath)
    }

    /**
     * Probe the known packages to find the longest package prefix up until the base, or until the
     * root directory if our execPath doesn't start with baseExecPath due to uplevel references.
     */
    private fun findSourceRoot(
        execPath: PathFragment,
        baseExecPath: PathFragment?,
        baseRoot: Root?,
        repositoryName: RepositoryName?
    ): Root? {
        var repositoryName: RepositoryName? = repositoryName
        var dir: PathFragment? = execPath.getParentDirectory()
        if (dir == null) {
            return null
        }

        val repo: Pair<RepositoryName?, PathFragment?>? =
            RepositoryName.fromPathFragment(dir, siblingRepositoryLayout)
        if (repo != null) {
            repositoryName = repo.getFirst()
            dir = repo.getSecond()
        }

        while (dir != null && !dir.equals(baseExecPath)) {
            val sourceRoot: Root? =
                packageRoots.getRootForPackage(PackageIdentifier.create(repositoryName, dir))
            if (sourceRoot != null) {
                return sourceRoot
            }
            dir = dir.getParentDirectory()
        }

        return if (dir != null && dir.equals(baseExecPath)) baseRoot else null
    }

    override fun resolveSourceArtifact(
        execPath: PathFragment, repositoryName: RepositoryName?
    ): SourceArtifact? {
        return resolveSourceArtifactWithAncestor(execPath, null, null, repositoryName)
    }

    override fun resolveSourceArtifactsAsciiCaseInsensitively(
        execPath: PathFragment, repositoryName: RepositoryName?
    ): com.google.common.collect.ImmutableList<SourceArtifact?> {
        if (isDefinitelyNotSourceExecPath(execPath)) {
            return com.google.common.collect.ImmutableList.of<SourceArtifact?>()
        }

        // Don't create an artifact if it's derived.
        if (isDerivedArtifact(execPath)) {
            return com.google.common.collect.ImmutableList.of<SourceArtifact?>()
        }
        val artifacts: com.google.common.collect.ImmutableList<SourceArtifact?> =
            sourceArtifactCache.getValidArtifactsWithAsciiCaseInsensitivePath(execPath)
        if (!artifacts.isEmpty()) {
            return artifacts
        }
        // The case-insensitive cache may have artifacts from a previous build that aren't valid yet.
        // Try to revalidate them using their original (correct-casing) exec paths before falling back
        // to creating a new artifact with the queried (potentially wrong-casing) exec path.
        val staleArtifacts: com.google.common.collect.ImmutableList<SourceArtifact> =
            sourceArtifactCache.getAllArtifactsWithAsciiCaseInsensitivePath(execPath)
        if (!staleArtifacts.isEmpty()) {
            val revalidated: com.google.common.collect.ImmutableList.Builder<SourceArtifact?> =
                com.google.common.collect.ImmutableList.builder<SourceArtifact?>()
            for (stale in staleArtifacts) {
                val sourceRoot: Root? =
                    findSourceRoot(
                        stale.getExecPath(),  /* baseExecPath= */
                        null,  /* baseRoot= */
                        null,
                        repositoryName
                    )
                val valid: SourceArtifact? = createArtifactIfNotValid(sourceRoot, stale.getExecPath())
                if (valid != null) {
                    revalidated.add(valid)
                }
            }
            val result: com.google.common.collect.ImmutableList<SourceArtifact?> = revalidated.build()
            if (!result.isEmpty()) {
                return result
            }
        }
        val sourceRoot: Root? =
            findSourceRoot(execPath,  /* baseExecPath= */null,  /* baseRoot= */null, repositoryName)
        val newArtifact: SourceArtifact? = createArtifactIfNotValid(sourceRoot, execPath)
        if (newArtifact == null) {
            return com.google.common.collect.ImmutableList.of<SourceArtifact?>()
        }
        return com.google.common.collect.ImmutableList.of<SourceArtifact?>(newArtifact)
    }

    @Throws(PackageRootException::class, java.lang.InterruptedException::class)
    override fun resolveSourceArtifacts(
        execPaths: Iterable<PathFragment>, resolver: PackageRootResolver
    ): MutableMap<PathFragment?, SourceArtifact?>? {
        val result: MutableMap<PathFragment?, SourceArtifact?> = HashMap<PathFragment?, SourceArtifact?>()
        val unresolvedPaths: java.util.ArrayList<PathFragment> = java.util.ArrayList<PathFragment>()

        for (execPath in execPaths) {
            if (isDefinitelyNotSourceExecPath(execPath)) {
                result.put(execPath, null)
                continue
            }
            if (isDerivedArtifact(execPath)) {
                result.put(execPath, null)
            } else {
                // First try a quick map lookup to see if the artifact already exists.
                val a: SourceArtifact? = sourceArtifactCache.getArtifactIfValid(execPath)
                if (a != null) {
                    result.put(execPath, a)
                } else {
                    // Remember this path, maybe we can resolve it with the help of PackageRootResolver.
                    unresolvedPaths.add(execPath)
                }
            }
        }
        val sourceRoots: MutableMap<PathFragment?, Root?>? = resolver.findPackageRootsForFiles(unresolvedPaths)
        // We are missing some dependencies. We need to rerun this method later.
        if (sourceRoots == null) {
            return null
        }
        for (path in unresolvedPaths) {
            result.put(path, createArtifactIfNotValid(sourceRoots.get(path), path))
        }
        return result
    }

    override fun getPathFromSourceExecPath(execRoot: Path, execPath: PathFragment): Path {
        com.google.common.base.Preconditions.checkState(
            !execPath.startsWith(derivedPathPrefix), "%s is derived: %s", execPath, derivedPathPrefix
        )
        val sourceRoot: Root? =
            packageRoots.getRootForPackage(PackageIdentifier.create(RepositoryName.MAIN, execPath))
        if (sourceRoot != null) {
            return sourceRoot.getRelative(execPath)
        }
        return execRoot.getRelative(execPath)
    }

    @ThreadSafe
    private fun createArtifactIfNotValid(sourceRoot: Root?, execPath: PathFragment): SourceArtifact? {
        if (sourceRoot == null) {
            return null // not a path that we can find...
        }
        val artifact: SourceArtifact? = sourceArtifactCache.getArtifact(execPath)
        if (artifact != null && sourceRoot.equals(artifact.getRoot().getRoot())) {
            // Source root of existing artifact hasn't changed so we should mark corresponding entry in
            // the cache as valid.
            val unused =
                sourceArtifactCache.computeEntry(
                    execPath,
                    java.util.function.BiFunction { k: PathFragment?, cacheEntry: SourceArtifactCache.Entry? ->
                        val validArtifact: SourceArtifact? = cacheEntry.artifact
                        if (cacheEntry.isInvalid(sourceArtifactCache.buildId)) {
                            // Wasn't previously known to be valid.
                            return@computeEntry com.google.devtools.build.lib.actions.ArtifactFactory.SourceArtifactCache.Entry(
                                validArtifact,
                                sourceArtifactCache.buildId
                            )
                        }
                        com.google.common.base.Preconditions.checkState(
                            artifact == validArtifact,
                            "Mismatched artifacts: %s %s",
                            artifact,
                            validArtifact
                        )
                        cacheEntry
                    })
            return artifact
        } else {
            // Must be a new artifact or artifact in the cache is stale, so create a new one.
            return getSourceArtifact(execPath, sourceRoot, ArtifactOwner.Companion.NULL_OWNER)
        }
    }

    override fun isDerivedArtifact(execPath: PathFragment): Boolean {
        return execPath.startsWith(derivedPathPrefix)
    }

    companion object {
        private fun createArtifact(
            root: ArtifactRoot,
            execPath: PathFragment?,
            owner: ArtifactOwner?,
            type: SpecialArtifactType?
        ): Artifact {
            com.google.common.base.Preconditions.checkNotNull<ArtifactOwner?>(owner)
            if (type == null) {
                return if (root.isSourceRoot())
                    SourceArtifact(root, execPath, owner)
                else
                    DerivedArtifact.Companion.create(root, execPath, owner as ActionLookupKey)
            } else {
                return SpecialArtifact.Companion.create(root, execPath, owner as ActionLookupKey, type)
            }
        }
    }
}
