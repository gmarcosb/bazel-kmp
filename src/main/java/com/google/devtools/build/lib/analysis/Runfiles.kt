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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.ActionKeyContext.describeNestedSetFingerprint

/**
 * An object that encapsulates runfiles. Conceptually, the runfiles are a map of paths to files,
 * forming a symlink tree.
 * 
 * 
 * In order to reduce memory consumption, this map is not explicitly stored here, but instead as
 * a combination of three parts: artifacts placed at their output-dir-relative paths, source tree
 * symlinks and root symlinks (outside of the source tree).
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class Runfiles private constructor(
    /**
     * The directory to put all runfiles under.
     * 
     * 
     * Using "foo" will put runfiles under &lt;target&gt;.runfiles/foo.
     * 
     * 
     * This is either set to the workspace name, or the empty string.
     */
    private val prefix: String,
    artifacts: NestedSet<Artifact?>?,
    symlinks: NestedSet<SymlinkEntry?>?,
    rootSymlinks: NestedSet<SymlinkEntry?>?,
    emptyFilesSupplier: EmptyFilesSupplier?,
    /** Policy for this Runfiles tree  */
    private var conflictPolicy: ConflictPolicy
) : RunfilesApi {
    private class DummyEmptyFilesSupplier : EmptyFilesSupplier {
        override fun getExtraPaths(manifestPaths: MutableSet<PathFragment?>?): Iterable<PathFragment?> {
            return com.google.common.collect.ImmutableList.of<PathFragment?>()
        }

        override fun fingerprint(fp: Fingerprint) {
            fp.addUUID(GUID)
        }

        companion object {
            private val GUID: UUID = UUID.fromString("36437db7-820b-4386-85b4-f7205a2018ae")
        }
    }

    /**
     * The artifacts that should be present in the runfiles directory.
     * 
     * 
     * This collection may not include any runfiles trees. These artifacts will be placed at a
     * location that corresponds to the output-dir-relative path of each artifact. It's possible for
     * several artifacts to have the same output-dir-relative path, in which case the last one will
     * win.
     */
    private val artifacts: NestedSet<Artifact?>

    /**
     * A map of symlinks that should be present in the runfiles directory. In general, the symlink can
     * be determined from the artifact by using the output-dir-relative path, so this should only be
     * used for cases where that isn't possible.
     * 
     * 
     * This may include runfiles symlinks from the root of the runfiles tree.
     */
    private val symlinks: NestedSet<SymlinkEntry?>

    /**
     * A map of symlinks that should be present above the runfiles directory. These are useful for
     * certain rule types like AppEngine apps which have root level config files outside of the
     * regular source tree.
     */
    private val rootSymlinks: NestedSet<SymlinkEntry?>

    /**
     * A nested set of all artifacts that this Runfiles entry contains symlinks to, including those at
     * their non-canonical locations which are in `symlinks` and `rootSymlinks`.
     */
    private var allArtifacts: NestedSet<Artifact?>? = null

    /**
     * Interface used for adding empty files to the runfiles at the last minute. Mainly to support
     * python-related rules adding __init__.py files.
     */
    interface EmptyFilesSupplier {
        /** Calculate additional empty files to add based on the existing manifest paths.  */
        fun getExtraPaths(manifestPaths: MutableSet<PathFragment?>?): Iterable<PathFragment?>?

        fun fingerprint(fingerprint: Fingerprint?)
    }

    /** Generates extra (empty file) inputs.  */
    private val emptyFilesSupplier: EmptyFilesSupplier

    /**
     * Behavior upon finding a conflict between two runfile entries. A conflict means that two
     * different artifacts have the same runfiles path specified.  For example, adding artifact
     * "a.foo" at path "bar" when there is already an artifact "b.foo" at path "bar".  The policies
     * are ordered from least strict to most strict.
     * 
     * 
     * Note that conflicts are found relatively late, when the manifest file is created, not when
     * the symlinks are added to runfiles.
     */
    enum class ConflictPolicy {
        WARN,
        ERROR,
    }

    init {
        this.artifacts = com.google.common.base.Preconditions.checkNotNull<NestedSet<Artifact?>>(artifacts)
        this.symlinks = com.google.common.base.Preconditions.checkNotNull<NestedSet<SymlinkEntry?>>(symlinks)
        this.rootSymlinks = com.google.common.base.Preconditions.checkNotNull<NestedSet<SymlinkEntry?>>(rootSymlinks)
        this.emptyFilesSupplier =
            com.google.common.base.Preconditions.checkNotNull<EmptyFilesSupplier>(emptyFilesSupplier)
    }

    public override fun isImmutable(): Boolean {
        return true // immutable and Starlark-hashable
    }

    /** Returns the runfiles' prefix. This is the same as the workspace name.  */
    fun getPrefix(): String {
        return prefix
    }

    /** Returns the collection of runfiles as artifacts.  */
    public override fun  /*<Artifact>*/getArtifactsForStarlark(): Depset {
        return Depset.of(Artifact::class.java, artifacts)
    }

    fun getArtifacts(): NestedSet<Artifact?> {
        return artifacts
    }

    /** Returns the symlinks.  */
    public override fun  /*<SymlinkEntry>*/getSymlinksForStarlark(): Depset {
        return Depset.of(SymlinkEntry::class.java, symlinks)
    }

    fun getSymlinks(): NestedSet<SymlinkEntry?> {
        return symlinks
    }

    public override fun  /*<String>*/getEmptyFilenamesForStarlark(): Depset {
        return Depset.of(
            String::class.java,
            NestedSetBuilder.wrap(
                Order.STABLE_ORDER,
                com.google.common.collect.Iterables.transform<F?, T?>(getEmptyFilenames(), PathFragment::getPathString)
            )
        )
    }

    fun getEmptyFilenames(): Iterable<PathFragment?>? {
        if (emptyFilesSupplier === com.google.devtools.build.lib.analysis.Runfiles.Companion.DUMMY_EMPTY_FILES_SUPPLIER) {
            return com.google.common.collect.ImmutableList.of<PathFragment?>()
        }
        val manifestKeys: MutableSet<PathFragment?>? =
            com.google.common.collect.Streams.concat(
                symlinks.toList().stream().map(SymlinkEntry::getPath),
                artifacts.toList().stream().map(Artifact::getRunfilesPath)
            )
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        return emptyFilesSupplier.getExtraPaths(manifestKeys)
    }

    /**
     * Returns the symlinks as a map from [PathFragment] to [Artifact].
     * 
     * 
     * Any errors during the conversion are ignored.
     * 
     * @param repoMappingManifest repository mapping manifest to add as a root symlink. This manifest
     * has to be added automatically for every executable and is thus not part of the Runfiles
     * advertised by a configured target.
     * @return `Map<PathFragment, Artifact>` path fragment to artifact, of normal source tree
     * entries and elements that live outside the source tree. Null values represent empty input
     * files.
     */
    fun getRunfilesInputs(
        repoMappingManifest: Artifact?
    ): SortedMap<PathFragment?, Artifact?> {
        return getRunfilesInputs(RunfilesConflictReceiver.Companion.NO_OP, repoMappingManifest)
    }

    /**
     * Returns the symlinks as a map from PathFragment to Artifact.
     * 
     * @param receiver called for each conflict
     * @param repoMappingManifest repository mapping manifest to add as a root symlink. This manifest
     * has to be added automatically for every executable and is thus not part of the Runfiles
     * advertised by a configured target.
     * @return Map<PathFragment></PathFragment>, Artifact> path fragment to artifact, of normal source tree entries
     * and elements that live outside the source tree. Null values represent empty input files.
     */
    fun getRunfilesInputs(
        receiver: RunfilesConflictReceiver, repoMappingManifest: Artifact?
    ): SortedMap<PathFragment?, Artifact?> {
        var manifest: MutableMap<PathFragment?, Artifact?> = LinkedHashMap<PathFragment?, Artifact?>()
        for (entry in symlinks.toList()) {
            com.google.devtools.build.lib.analysis.Runfiles.Companion.checkAndPut(
                manifest,
                receiver,
                entry.getPath(),
                entry.getArtifact()
            )
        }
        for (artifact in artifacts.toList()) {
            com.google.devtools.build.lib.analysis.Runfiles.Companion.checkAndPut(
                manifest,
                receiver,
                artifact.getRunfilesPath(),
                artifact
            )
        }

        manifest =
            com.google.devtools.build.lib.analysis.Runfiles.Companion.filterListForObscuringSymlinks(receiver, manifest)

        // TODO(bazel-team): Create /dev/null-like Artifact to avoid nulls?
        for (extraPath in emptyFilesSupplier.getExtraPaths(manifest.keySet())!!) {
            manifest.put(extraPath, null)
        }

        // Copy manifest map to another manifest map, prepending the workspace name to every path.
        // E.g. for workspace "myworkspace", the runfile entry "mylib.so"->"/path/to/mylib.so" becomes
        // "myworkspace/mylib.so"->"/path/to/mylib.so".
        val workspaceName: PathFragment = PathFragment.create(prefix)
        var sawWorkspaceName = false
        val finalManifest: SortedMap<PathFragment?, Artifact?> = TreeMap<PathFragment?, Artifact?>()

        for (entry in manifest.entrySet()) {
            // Artifacts in the manifest map were already checked for nested runfiles trees, so we can add
            // them directly to finalManifest without calling checkAndAdd again.
            val path: PathFragment = entry.getKey()
            if (path.startsWith(LabelConstants.EXTERNAL_RUNFILES_PATH_PREFIX)) {
                // Always add the non-legacy .runfiles/repo/whatever path.
                val externalPath: PathFragment = path.subFragment(1)
                sawWorkspaceName = sawWorkspaceName or externalPath.startsWith(workspaceName)
                finalManifest.put(externalPath, entry.getValue())
            } else {
                sawWorkspaceName = true
                finalManifest.put(
                    com.google.devtools.build.lib.analysis.Runfiles.Companion.workspacePathInterner.intern(
                        workspaceName.getRelative(
                            path
                        )
                    ), entry.getValue()
                )
            }
        }

        for (entry in rootSymlinks.toList()) {
            sawWorkspaceName = sawWorkspaceName or entry.getPath().startsWith(workspaceName)
            com.google.devtools.build.lib.analysis.Runfiles.Companion.checkAndPut(
                finalManifest,
                receiver,
                entry.getPath(),
                entry.getArtifact()
            )
        }

        if (repoMappingManifest != null) {
            com.google.devtools.build.lib.analysis.Runfiles.Companion.checkAndPut(
                finalManifest,
                receiver,
                com.google.devtools.build.lib.analysis.Runfiles.Companion.REPO_MAPPING_PATH_FRAGMENT,
                repoMappingManifest
            )
        }

        if (!sawWorkspaceName) {
            // If we haven't seen it and we have seen other files, add the workspace name directory. It
            // might not be there if all of the runfiles are from other repos (and then running from
            // x.runfiles/ws will fail, because ws won't exist). We can't tell Runfiles to create a
            // directory, so instead this creates a hidden file inside the desired directory.
            finalManifest.put(workspaceName.getRelative(".runfile"), null)
        }

        return finalManifest
    }

    /** Returns the root symlinks.  */
    public override fun  /*<SymlinkEntry>*/getRootSymlinksForStarlark(): Depset {
        return Depset.of(SymlinkEntry::class.java, rootSymlinks)
    }

    fun getRootSymlinks(): NestedSet<SymlinkEntry?> {
        return rootSymlinks
    }

    /**
     * Returns the manifest expander specified for this runfiles tree.
     */
    private fun getEmptyFilesProvider(): EmptyFilesSupplier {
        return emptyFilesSupplier
    }

    /**
     * Returns the unified map of path fragments to artifacts, taking into account artifacts and
     * symlinks. The returned set is guaranteed to be a (not necessarily strict) superset of the
     * actual runfiles tree created at execution time.
     */
    fun getAllArtifacts(): NestedSet<Artifact?>? {
        if (isEmpty()) {
            return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        }

        if (allArtifacts == null) {
            val builder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
            builder
                .addTransitive(artifacts)
                .addAll(
                    com.google.common.collect.Iterables.transform<F?, T?>(
                        symlinks.toList(),
                        SymlinkEntry::getArtifact
                    )
                )
                .addAll(
                    com.google.common.collect.Iterables.transform<F?, T?>(
                        rootSymlinks.toList(),
                        SymlinkEntry::getArtifact
                    )
                )
            allArtifacts = builder.build()
        }

        return allArtifacts
    }

    /**
     * Returns if there are no runfiles.
     */
    fun isEmpty(): Boolean {
        return artifacts.isEmpty() && symlinks.isEmpty() && rootSymlinks.isEmpty()
    }

    /** Returns currently policy for conflicting symlink entries.  */
    fun getConflictPolicy(): ConflictPolicy {
        return this.conflictPolicy
    }

    /** Set whether we should warn about conflicting symlink entries.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setConflictPolicy(conflictPolicy: ConflictPolicy): Runfiles {
        this.conflictPolicy = conflictPolicy
        return this
    }

    /** Informed of conflicts in the runfiles tree.  */
    internal interface RunfilesConflictReceiver {
        /** Called when a runfiles tree artifact is detected inside another runfiles tree.  */
        fun nestedRunfilesTree(runfilesTree: Artifact?)

        /** Called when one runfiles entry is a prefix of another.  */
        fun prefixConflict(message: String?)

        companion object {
            val NO_OP: RunfilesConflictReceiver = object : RunfilesConflictReceiver {
                override fun nestedRunfilesTree(runfilesTree: Artifact?) {}

                override fun prefixConflict(message: String?) {}
            }
        }
    }

    /** Builder for Runfiles objects.  */
    class Builder {
        /** This is set to the workspace name  */
        private val prefix: String

        /**
         * This must be COMPILE_ORDER because [.getRunfilesInputs] overwrites earlier entries with later ones, so we want a post-order iteration.
         */
        private val artifactsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.compileOrder()

        private val symlinksBuilder: NestedSetBuilder<SymlinkEntry?> = NestedSetBuilder.stableOrder()
        private val rootSymlinksBuilder: NestedSetBuilder<SymlinkEntry?> = NestedSetBuilder.stableOrder()
        private var emptyFilesSupplier: EmptyFilesSupplier =
            com.google.devtools.build.lib.analysis.Runfiles.Companion.DUMMY_EMPTY_FILES_SUPPLIER

        /** Build the Runfiles object with this policy  */
        private var conflictPolicy = ConflictPolicy.WARN

        /**
         * Only used for Runfiles.EMPTY.
         */
        private constructor() {
            this.prefix = ""
        }

        /**
         * Creates a builder with the given suffix.
         * 
         * @param workspace is the string specified in workspace() in the WORKSPACE file.
         */
        constructor(workspace: String) {
            this.prefix = workspace
        }

        /**
         * Builds a new Runfiles object.
         */
        fun build(): Runfiles {
            return com.google.devtools.build.lib.analysis.Runfiles(
                prefix,
                artifactsBuilder.build(),
                symlinksBuilder.build(),
                rootSymlinksBuilder.build(),
                emptyFilesSupplier,
                conflictPolicy
            )
        }

        /** Adds an artifact to the internal collection of artifacts.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addArtifact(artifact: Artifact?): Builder {
            com.google.common.base.Preconditions.checkNotNull<Any?>(artifact)
            com.google.common.base.Preconditions.checkArgument(
                !artifact.isRunfilesTree(), "unexpected runfiles tree artifact: %s", artifact
            )
            artifactsBuilder.add(artifact)
            return this
        }

        /** Adds several artifacts to the internal collection.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addArtifacts(artifacts: Iterable<Artifact?>): Builder {
            for (artifact in artifacts) {
                addArtifact(artifact)
            }
            return this
        }

        /** Adds a nested set to the internal collection.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTransitiveArtifacts(artifacts: NestedSet<Artifact?>?): Builder {
            artifactsBuilder.addTransitive(artifacts)
            return this
        }

        /** Adds a symlink.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addSymlink(link: PathFragment?, target: Artifact?): Builder {
            symlinksBuilder.add(SymlinkEntry(link, target))
            return this
        }

        /** Adds several symlinks. Neither keys nor values may be null.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addSymlinks(symlinks: MutableMap<PathFragment?, Artifact?>): Builder {
            for (symlink in symlinks.entrySet()) {
                symlinksBuilder.add(SymlinkEntry(symlink.getKey(), symlink.getValue()))
            }
            return this
        }

        /** Adds several symlinks as a NestedSet.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addSymlinks(symlinks: NestedSet<SymlinkEntry?>?): Builder {
            symlinksBuilder.addTransitive(symlinks)
            return this
        }

        /** Adds a root symlink.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addRootSymlink(link: PathFragment?, target: Artifact?): Builder {
            rootSymlinksBuilder.add(SymlinkEntry(link, target))
            return this
        }

        /** Adds several root symlinks. Neither keys nor values may be null.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addRootSymlinks(symlinks: MutableMap<PathFragment?, Artifact?>): Builder {
            for (symlink in symlinks.entrySet()) {
                rootSymlinksBuilder.add(SymlinkEntry(symlink.getKey(), symlink.getValue()))
            }
            return this
        }

        /** Adds several root symlinks as a NestedSet.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addRootSymlinks(symlinks: NestedSet<SymlinkEntry?>?): Builder {
            rootSymlinksBuilder.addTransitive(symlinks)
            return this
        }

        /**
         * Specify a function that can create additional manifest entries based on the input entries,
         * see [EmptyFilesSupplier] for more details.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setEmptyFilesSupplier(supplier: EmptyFilesSupplier?): Builder {
            emptyFilesSupplier = com.google.common.base.Preconditions.checkNotNull<EmptyFilesSupplier>(supplier)
            return this
        }

        /**
         * Merges runfiles from a given runfiles support.
         * 
         * @param runfilesSupport the runfiles support to be merged in
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun merge(runfilesSupport: RunfilesSupport?): Builder {
            if (runfilesSupport == null) {
                return this
            }
            merge(runfilesSupport.getRunfiles())
            return this
        }

        /** Adds the other [Runfiles] object transitively.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun merge(runfiles: Runfiles): Builder {
            // Propagate the most strict conflict checking from merged-in runfiles.
            if (conflictPolicy == ConflictPolicy.WARN) {
                conflictPolicy = runfiles.conflictPolicy
            }
            if (runfiles.isEmpty()) {
                return this
            }
            // The prefix should be the same within any blaze build, except for the EMPTY runfiles, which
            // may have an empty prefix, but that is covered above.
            com.google.common.base.Preconditions.checkArgument(
                prefix == runfiles.prefix, "%s != %s", prefix, runfiles.prefix
            )
            artifactsBuilder.addTransitive(runfiles.getArtifacts())
            symlinksBuilder.addTransitive(runfiles.getSymlinks())
            rootSymlinksBuilder.addTransitive(runfiles.getRootSymlinks())
            if (emptyFilesSupplier === com.google.devtools.build.lib.analysis.Runfiles.Companion.DUMMY_EMPTY_FILES_SUPPLIER) {
                emptyFilesSupplier = runfiles.getEmptyFilesProvider()
            } else {
                val otherSupplier = runfiles.getEmptyFilesProvider()
                com.google.common.base.Preconditions.checkState(
                    (otherSupplier === com.google.devtools.build.lib.analysis.Runfiles.Companion.DUMMY_EMPTY_FILES_SUPPLIER)
                            || emptyFilesSupplier == otherSupplier
                )
            }
            return this
        }

        /**
         * Adds the runfiles for a particular target and visits the transitive closure of "srcs", "deps"
         * and "data", collecting all of their respective runfiles.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addRunfiles(
            ruleContext: RuleContext?, mapping: com.google.common.base.Function<TransitiveInfoCollection?, Runfiles?>?
        ): Builder {
            com.google.common.base.Preconditions.checkNotNull<com.google.common.base.Function<TransitiveInfoCollection?, Runfiles?>?>(
                mapping
            )
            com.google.common.base.Preconditions.checkNotNull<RuleContext?>(ruleContext)
            addDataDeps(ruleContext)
            addNonDataDeps(ruleContext, mapping)
            return this
        }

        /**
         * Adds the files specified by a mapping from the transitive info collection to the runfiles.
         * 
         * 
         * Dependencies in `srcs` and `deps` are considered.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun add(
            ruleContext: RuleContext?, mapping: com.google.common.base.Function<TransitiveInfoCollection?, Runfiles?>?
        ): Builder {
            com.google.common.base.Preconditions.checkNotNull<RuleContext?>(ruleContext)
            com.google.common.base.Preconditions.checkNotNull<com.google.common.base.Function<TransitiveInfoCollection?, Runfiles?>?>(
                mapping
            )
            for (dep in com.google.devtools.build.lib.analysis.Runfiles.Builder.Companion.getNonDataDeps(ruleContext)) {
                val runfiles: Runfiles? = mapping.apply(dep)
                if (runfiles != null) {
                    merge(runfiles)
                }
            }

            return this
        }

        /** Collects runfiles from data dependencies of a target.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDataDeps(ruleContext: RuleContext): Builder {
            addTargets(
                com.google.devtools.build.lib.analysis.Runfiles.Builder.Companion.getPrerequisites(ruleContext, "data"),
                RunfilesProvider.Companion.DATA_RUNFILES,
                ruleContext.getConfiguration().alwaysIncludeFilesToBuildInData()
            )
            return this
        }

        /** Collects runfiles from "srcs" and "deps" of a target.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addNonDataDeps(
            ruleContext: RuleContext, mapping: com.google.common.base.Function<TransitiveInfoCollection?, Runfiles?>
        ): Builder {
            for (target in com.google.devtools.build.lib.analysis.Runfiles.Builder.Companion.getNonDataDeps(ruleContext)) {
                addTargetExceptFileTargets(target, mapping)
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTargets(
            targets: Iterable<out TransitiveInfoCollection>,
            mapping: com.google.common.base.Function<TransitiveInfoCollection?, Runfiles?>,
            alwaysIncludeFilesToBuildInData: Boolean
        ): Builder {
            for (target in targets) {
                addTarget(target, mapping, alwaysIncludeFilesToBuildInData)
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addTarget(
            target: TransitiveInfoCollection,
            mapping: com.google.common.base.Function<TransitiveInfoCollection?, Runfiles?>,
            alwaysIncludeFilesToBuildInData: Boolean
        ): Builder {
            return addTargetIncludingFileTargets(target, mapping, alwaysIncludeFilesToBuildInData)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun addTargetExceptFileTargets(
            target: TransitiveInfoCollection?,
            mapping: com.google.common.base.Function<TransitiveInfoCollection?, Runfiles?>
        ): Builder {
            val runfiles: Runfiles? = mapping.apply(target)
            if (runfiles != null) {
                merge(runfiles)
            }

            return this
        }

        private fun addTargetIncludingFileTargets(
            target: TransitiveInfoCollection,
            mapping: com.google.common.base.Function<TransitiveInfoCollection?, Runfiles?>,
            alwaysIncludeFilesToBuildInData: Boolean
        ): Builder {
            if (target.getProvider(RunfilesProvider::class.java) == null
                && mapping === RunfilesProvider.Companion.DATA_RUNFILES
            ) {
                // RuleConfiguredTarget implements RunfilesProvider, so this will only be called on
                // FileConfiguredTarget instances.
                // TODO(bazel-team): This is a terrible hack. We should be able to make this go away
                // by implementing RunfilesProvider on FileConfiguredTarget. We'd need to be mindful
                // of the memory use, though, since we have a whole lot of FileConfiguredTarget instances.
                addTransitiveArtifacts(target.getProvider(FileProvider::class.java).getFilesToBuild())
                return this
            }

            if (alwaysIncludeFilesToBuildInData && mapping === RunfilesProvider.Companion.DATA_RUNFILES) {
                // Ensure that `DefaultInfo.files` of Starlark rules is merged in so that native rules
                // interoperate well with idiomatic Starlark rules..
                // https://bazel.build/extending/rules#runfiles_features_to_avoid
                // Internal tests fail if the order of filesToBuild is preserved.
                addTransitiveArtifacts(
                    NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                        .addTransitive(target.getProvider(FileProvider::class.java).getFilesToBuild())
                        .build()
                )
            }

            return addTargetExceptFileTargets(target, mapping)
        }

        companion object {
            private fun getNonDataDeps(ruleContext: RuleContext): Iterable<TransitiveInfoCollection?> {
                return com.google.common.collect.Iterables.concat<TransitiveInfoCollection?>( // TODO(bazel-team): This line shouldn't be here. Removing it requires that no rules have
                    // dependent rules in srcs (except for filegroups and such), but always in deps.
                    // TODO(bazel-team): DONT_CHECK is not optimal here. Rules that use split configs need to
                    // be changed not to call into here.
                    com.google.devtools.build.lib.analysis.Runfiles.Builder.Companion.getPrerequisites(
                        ruleContext,
                        "srcs"
                    ),
                    com.google.devtools.build.lib.analysis.Runfiles.Builder.Companion.getPrerequisites(
                        ruleContext,
                        "deps"
                    )
                )
            }

            /**
             * For the specified attribute "attributeName" (which must be of type list(label)), resolves all
             * the labels into ConfiguredTargets (for the same configuration as this one) and returns them
             * as a list.
             * 
             * 
             * If the rule does not have the specified attribute, returns the empty list.
             */
            private fun getPrerequisites(
                ruleContext: RuleContext, attributeName: String?
            ): Iterable<out TransitiveInfoCollection>? {
                if (ruleContext.getRule().isAttrDefined(attributeName, BuildType.LABEL_LIST)) {
                    return ruleContext.getPrerequisites(attributeName)
                } else {
                    return Collections.emptyList<TransitiveInfoCollection?>()
                }
            }
        }
    }

    /**
     * Checks that the depth of a Runfiles object's nested sets (artifacts, symlinks, root symlinks,
     * etc.) does not exceed Starlark's depset depth limit, as specified by `--nested_set_depth_limit`.
     * 
     * @param semantics Starlark semantics providing `--nested_set_depth_limit`
     * @return this object, in the fluent style
     * @throws EvalException if a nested set in the Runfiles object exceeds the depth limit
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun verifyNestedSetDepthLimit(semantics: StarlarkSemantics): Runfiles {
        val limit: Int = semantics.get<Int?>(BuildLanguageOptions.NESTED_SET_DEPTH_LIMIT)
        com.google.devtools.build.lib.analysis.Runfiles.Companion.verifyNestedSetDepthLimitHelper(
            artifacts,
            "artifacts",
            limit
        )
        com.google.devtools.build.lib.analysis.Runfiles.Companion.verifyNestedSetDepthLimitHelper(
            symlinks,
            "symlinks",
            limit
        )
        com.google.devtools.build.lib.analysis.Runfiles.Companion.verifyNestedSetDepthLimitHelper(
            rootSymlinks,
            "root symlinks",
            limit
        )
        return this
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun merge(other: RunfilesApi?, thread: StarlarkThread): Runfiles? {
        val o = other as Runfiles
        if (isEmpty()) {
            // This is not just a memory / performance optimization. The Builder requires a valid suffix,
            // but the {@code Runfiles.EMPTY} singleton has an invalid one, which must not be used to
            // construct a Runfiles.Builder.
            return o
        } else if (o.isEmpty()) {
            return this
        }
        return com.google.devtools.build.lib.analysis.Runfiles.Builder(prefix)
            .merge(this)
            .merge(o)
            .build()
            .verifyNestedSetDepthLimit(thread.getSemantics())
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun mergeAll(sequence: net.starlark.java.eval.Sequence<*>?, thread: StarlarkThread): Runfiles? {
        // The delayed initialization of the Builder is not just a memory / performance optimization.
        // The Builder requires a valid suffix, but the {@code Runfiles.EMPTY} singleton has an invalid
        // one, which must not be used to construct a Runfiles.Builder.
        var builder: Builder? = null
        // When merging exactly one non-empty Runfiles object, we want to return that object and avoid a
        // Builder. This is a memory optimization and provides identical behavior for `x.merge_all([y])`
        // and `x.merge(y)` in Starlark.
        var uniqueNonEmptyMergee: Runfiles? = null
        if (!this.isEmpty()) {
            builder = com.google.devtools.build.lib.analysis.Runfiles.Builder(prefix).merge(this)
            uniqueNonEmptyMergee = this
        }

        val runfilesSequence: net.starlark.java.eval.Sequence<Runfiles> =
            net.starlark.java.eval.Sequence.cast<Runfiles?>(
                sequence,
                com.google.devtools.build.lib.analysis.Runfiles::class.java,
                "param"
            )
        for (runfiles in runfilesSequence) {
            if (!runfiles.isEmpty()) {
                if (builder == null) {
                    builder = com.google.devtools.build.lib.analysis.Runfiles.Builder(runfiles.prefix)
                    uniqueNonEmptyMergee = runfiles
                } else {
                    uniqueNonEmptyMergee = null
                }
                builder.merge(runfiles)
            }
        }

        if (uniqueNonEmptyMergee != null) {
            return uniqueNonEmptyMergee
        } else if (builder != null) {
            return builder.build().verifyNestedSetDepthLimit(thread.getSemantics())
        } else {
            return com.google.devtools.build.lib.analysis.Runfiles.Companion.EMPTY
        }
    }

    /** Fingerprint this [Runfiles] tree, including the absolute paths of artifacts.  */
    fun fingerprint(
        actionKeyContext: ActionKeyContext, fp: Fingerprint, digestAbsolutePaths: Boolean
    ) {
        fp.addInt(conflictPolicy.ordinal())
        fp.addString(prefix)

        actionKeyContext.addNestedSetToFingerprint(
            if (digestAbsolutePaths) com.google.devtools.build.lib.analysis.Runfiles.Companion.SYMLINK_ENTRY_ABSOLUTE_PATH_MAP_FN else com.google.devtools.build.lib.analysis.Runfiles.Companion.SYMLINK_ENTRY_EXEC_PATH_MAP_FN,
            fp,
            symlinks
        )
        actionKeyContext.addNestedSetToFingerprint(
            if (digestAbsolutePaths) com.google.devtools.build.lib.analysis.Runfiles.Companion.SYMLINK_ENTRY_ABSOLUTE_PATH_MAP_FN else com.google.devtools.build.lib.analysis.Runfiles.Companion.SYMLINK_ENTRY_EXEC_PATH_MAP_FN,
            fp,
            rootSymlinks
        )
        actionKeyContext.addNestedSetToFingerprint(
            if (digestAbsolutePaths) com.google.devtools.build.lib.analysis.Runfiles.Companion.RUNFILES_AND_ABSOLUTE_PATH_MAP_FN else com.google.devtools.build.lib.analysis.Runfiles.Companion.RUNFILES_AND_EXEC_PATH_MAP_FN,
            fp,
            artifacts
        )

        emptyFilesSupplier.fingerprint(fp)
    }

    /** Describes the inputs [.fingerprint] uses to aid describeKey() descriptions.  */
    fun describeFingerprint(digestAbsolutePaths: Boolean): String {
        return (java.lang.String.format("conflictPolicy: %s\n", conflictPolicy)
                + java.lang.String.format("prefix: %s\n", prefix)
                + java.lang.String.format(
            "symlinks: %s\n",
            describeNestedSetFingerprint(
                if (digestAbsolutePaths)
                    com.google.devtools.build.lib.analysis.Runfiles.Companion.SYMLINK_ENTRY_ABSOLUTE_PATH_MAP_FN
                else
                    com.google.devtools.build.lib.analysis.Runfiles.Companion.SYMLINK_ENTRY_EXEC_PATH_MAP_FN,
                symlinks
            )
        )
                + java.lang.String.format(
            "rootSymlinks: %s\n",
            describeNestedSetFingerprint(
                if (digestAbsolutePaths)
                    com.google.devtools.build.lib.analysis.Runfiles.Companion.SYMLINK_ENTRY_ABSOLUTE_PATH_MAP_FN
                else
                    com.google.devtools.build.lib.analysis.Runfiles.Companion.SYMLINK_ENTRY_EXEC_PATH_MAP_FN,
                rootSymlinks
            )
        )
                + java.lang.String.format(
            "artifacts: %s\n",
            describeNestedSetFingerprint(
                if (digestAbsolutePaths)
                    com.google.devtools.build.lib.analysis.Runfiles.Companion.RUNFILES_AND_ABSOLUTE_PATH_MAP_FN
                else
                    com.google.devtools.build.lib.analysis.Runfiles.Companion.RUNFILES_AND_EXEC_PATH_MAP_FN,
                artifacts
            )
        )
                + java.lang.String.format("emptyFilesSupplier: %s\n", emptyFilesSupplier.getClass().getName()))
    }

    public override fun debugPrint(printer: net.starlark.java.eval.Printer, thread: StarlarkThread?) {
        printer.append("Runfiles(empty_files = ")
        printer.debugPrint(getEmptyFilenamesForStarlark(), thread)
        printer.append(", files = ")
        printer.debugPrint(getArtifactsForStarlark(), thread)
        printer.append(", root_symlinks = ")
        printer.debugPrint(getRootSymlinksForStarlark(), thread)
        printer.append(", symlinks = ")
        printer.debugPrint(getSymlinksForStarlark(), thread)
        printer.append(")")
    }

    companion object {
        @SerializationConstant
        @VisibleForSerialization
        val DUMMY_EMPTY_FILES_SUPPLIER: EmptyFilesSupplier = DummyEmptyFilesSupplier()

        // It is important to declare this *after* the DUMMY_SYMLINK_EXPANDER to avoid NPEs
        val EMPTY: Runfiles = com.google.devtools.build.lib.analysis.Runfiles.Builder().build()

        private val REPO_MAPPING_PATH_FRAGMENT: PathFragment? = PathFragment.create("_repo_mapping")

        private val SYMLINK_ENTRY_ABSOLUTE_PATH_MAP_FN: CommandLineItem.ExceptionlessMapFn<SymlinkEntry?> =
            CommandLineItem.ExceptionlessMapFn { symlink, args ->
                args.accept(symlink.getPathString())
                args.accept(symlink.getArtifact().getPath().getPathString())
            }

        private val SYMLINK_ENTRY_EXEC_PATH_MAP_FN: CommandLineItem.ExceptionlessMapFn<SymlinkEntry?> =
            CommandLineItem.ExceptionlessMapFn { symlink, args ->
                args.accept(symlink.getPathString())
                args.accept(symlink.getArtifact().getExecPathString())
            }

        private val RUNFILES_AND_ABSOLUTE_PATH_MAP_FN: CommandLineItem.ExceptionlessMapFn<Artifact?> =
            CommandLineItem.ExceptionlessMapFn { artifact, args ->
                args.accept(artifact.getRunfilesPathString())
                args.accept(artifact.getPath().getPathString())
            }

        private val RUNFILES_AND_EXEC_PATH_MAP_FN: CommandLineItem.ExceptionlessMapFn<Artifact?> =
            CommandLineItem.ExceptionlessMapFn { artifact, args ->
                args.accept(artifact.getRunfilesPathString())
                args.accept(artifact.getExecPathString())
            }

        private val workspacePathInterner: com.google.common.collect.Interner<PathFragment?> =
            BlazeInterners.newWeakInterner<PathFragment?>()

        @com.google.common.annotations.VisibleForTesting
        fun filterListForObscuringSymlinks(
            receiver: RunfilesConflictReceiver, workingManifest: MutableMap<PathFragment?, Artifact?>
        ): MutableMap<PathFragment?, Artifact?> {
            val newManifest: MutableMap<PathFragment?, Artifact?> =
                com.google.common.collect.Maps.newHashMapWithExpectedSize<PathFragment?, Artifact?>(workingManifest.size())
            val noFurtherObstructions: MutableSet<PathFragment?> = HashSet<PathFragment?>()

            outer@ for (entry in workingManifest.entrySet()) {
                val source: PathFragment = entry.getKey()
                val symlink: Artifact = entry.getValue()
                // drop nested entries; warn if this changes anything
                val n: Int = source.segmentCount()
                val parents: java.util.ArrayList<PathFragment?> = java.util.ArrayList<PathFragment?>(n)
                for (j in 1..<n) {
                    val prefix: PathFragment? = source.subFragment(0, n - j)
                    if (noFurtherObstructions.contains(prefix)) {
                        break
                    }
                    parents.add(prefix)
                    val ancestor: Artifact? = workingManifest.get(prefix)
                    if (ancestor != null) {
                        // This is an obscuring symlink, so just drop it and move on if there's no reporter.
                        if (receiver === RunfilesConflictReceiver.Companion.NO_OP) {
                            continue@outer
                        }
                        val suffix: PathFragment? = source.subFragment(n - j, n)
                        val viaAncestor: PathFragment = ancestor.getExecPath().getRelative(suffix)
                        val expected: PathFragment? = symlink.getExecPath()
                        if (!viaAncestor.equals(expected)) {
                            receiver.prefixConflict(
                                ("runfiles symlink "
                                        + source
                                        + " -> "
                                        + expected
                                        + " obscured by "
                                        + prefix
                                        + " -> "
                                        + ancestor.getExecPath())
                            )
                        }
                        continue@outer
                    }
                }
                noFurtherObstructions.addAll(parents)
                newManifest.put(entry.getKey(), entry.getValue())
            }
            return newManifest
        }

        private fun checkAndPut(
            map: MutableMap<PathFragment?, Artifact?>,
            receiver: RunfilesConflictReceiver,
            path: PathFragment?,
            artifact: Artifact?
        ) {
            if (artifact != null && artifact.isRunfilesTree()) {
                receiver.nestedRunfilesTree(artifact)
            } else {
                map.put(path, artifact)
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun verifyNestedSetDepthLimitHelper(
            nestedSet: NestedSet<*>, name: String?, limit: Int
        ) {
            if (nestedSet.getApproxDepth() > limit) {
                throw Starlark.errorf(
                    "%s depset depth %d exceeds limit (%d)", name, nestedSet.getApproxDepth(), limit
                )
            }
        }
    }
}
