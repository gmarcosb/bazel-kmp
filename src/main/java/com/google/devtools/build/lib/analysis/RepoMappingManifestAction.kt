// Copyright 2022 The Bazel Authors. All rights reserved.
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

/** Creates a manifest file describing the repos and mappings relevant for a runfile tree.  */
class RepoMappingManifestAction(
    owner: ActionOwner?,
    output: Artifact?,
    transitivePackages: NestedSet<com.google.devtools.build.lib.packages.Package.Metadata?>,
    runfilesArtifacts: NestedSet<Artifact?>,
    runfilesSymlinks: NestedSet<SymlinkEntry?>,
    runfilesRootSymlinks: NestedSet<SymlinkEntry?>,
    workspaceName: String?,
    emitCompactRepoMapping: Boolean
) : AbstractFileWriteAction(
    owner,
    NestedSetBuilder.emptySet<E?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER),
    output
), AbstractFileWriteAction.FileContentsProvider {
    private val transitivePackages: NestedSet<com.google.devtools.build.lib.packages.Package.Metadata?>
    private val runfilesArtifacts: NestedSet<Artifact?>
    private val hasRunfilesSymlinks: Boolean
    private val runfilesRootSymlinks: NestedSet<SymlinkEntry?>
    private val workspaceName: String?
    private val emitCompactRepoMapping: Boolean

    val mnemonic: String
        get() = "RepoMappingManifest"

    protected val rawProgressMessage: String
        get() = "Writing repo mapping manifest for " + getOwner().getLabel()

    @Throws(
        CommandLineExpansionException::class,
        net.starlark.java.eval.EvalException::class,
        java.lang.InterruptedException::class
    )
    public override fun computeKey(
        actionKeyContext: ActionKeyContext,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addUUID(MY_UUID)
        actionKeyContext.addNestedSetToFingerprint(REPO_AND_MAPPING_DIGEST_FN, fp, transitivePackages)
        actionKeyContext.addNestedSetToFingerprint(OWNER_REPO_FN, fp, runfilesArtifacts)
        fp.addBoolean(hasRunfilesSymlinks)
        actionKeyContext.addNestedSetToFingerprint(FIRST_SEGMENT_FN, fp, runfilesRootSymlinks)
        fp.addString(workspaceName)
        fp.addBoolean(emitCompactRepoMapping)
    }

    public override fun describeKey(): String? {
        return """
    GUID: %s
    transitivePackages: %s
    runfilesArtifacts: %s
    hasRunfilesSymlinks: %s
    runfilesRootSymlinks: %s
    workspaceName: %s
    emitCompactRepoMapping: %s
    """
            .trimIndent()
            .formatted(
                MY_UUID,
                describeNestedSetFingerprint(REPO_AND_MAPPING_DIGEST_FN, transitivePackages),
                describeNestedSetFingerprint(OWNER_REPO_FN, runfilesArtifacts),
                hasRunfilesSymlinks,
                describeNestedSetFingerprint(FIRST_SEGMENT_FN, runfilesRootSymlinks),
                workspaceName,
                emitCompactRepoMapping
            )
    }

    /**
     * Get the contents of a file internally using an in memory output stream.
     * 
     * @return returns the file contents as a string.
     */
    @Throws(IOException::class)
    public override fun getFileContents(eventHandler: com.google.devtools.build.lib.events.EventHandler?): String? {
        val stream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        newDeterministicWriter().writeTo(stream)
        return stream.toString(java.nio.charset.StandardCharsets.ISO_8859_1)
    }

    init {
        this.transitivePackages = transitivePackages
        this.runfilesArtifacts = runfilesArtifacts
        this.hasRunfilesSymlinks = !runfilesSymlinks.isEmpty()
        this.runfilesRootSymlinks = runfilesRootSymlinks
        this.workspaceName = workspaceName
        this.emitCompactRepoMapping = emitCompactRepoMapping
    }

    // LINT.ThenChange(//src/main/java/com/google/devtools/build/lib/bazel/bzlmod/BazelDepGraphFunction.java)
    public override fun newDeterministicWriter(ctx: ActionExecutionContext?): DeterministicWriter {
        return newDeterministicWriter()
    }

    fun newDeterministicWriter(): DeterministicWriter {
        return DeterministicWriter { out: java.io.OutputStream? ->
            val writer: PrintWriter =
                PrintWriter(out,  /* autoFlush= */false, java.nio.charset.StandardCharsets.ISO_8859_1)
            val reposInRunfilesPathsBuilder: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.builder<String?>()
            // The runfiles paths of symlinks are always prefixed with the main workspace name, *not* the
            // name of the repository adding the symlink.
            if (hasRunfilesSymlinks) {
                reposInRunfilesPathsBuilder.add(RepositoryName.MAIN.getName())
            }

            // Since root symlinks are the only way to stage a runfile at a specific path under the
            // current repository's runfiles directory, recognize canonical repository names that appear
            // as the first segment of their runfiles paths.
            for (symlink in runfilesRootSymlinks.toList()) {
                reposInRunfilesPathsBuilder.add(symlink.getPath().getSegment(0))
            }

            for (artifact in runfilesArtifacts.toList()) {
                val owner: com.google.devtools.build.lib.cmdline.Label? = artifact.getOwner()
                if (owner != null) {
                    reposInRunfilesPathsBuilder.add(owner.getRepository().getName())
                }
            }
            val reposInRunfilesPaths: com.google.common.collect.ImmutableSet<String?> =
                reposInRunfilesPathsBuilder.build()

            val sortedRepoMappings: com.google.common.collect.ImmutableSortedMap<RepositoryName?, com.google.devtools.build.lib.cmdline.RepositoryMapping?> =
                transitivePackages.toList().stream()
                    .collect()
            TODO(
                """
            |Cannot convert element
            |With text:
            |Package.Metadata, RepositoryName, RepositoryMapping>toImmutableSortedMap(
            |                      <RepositoryName, String>comparing(RepositoryName::getName),
            |                      pkgMetadata -> pkgMetadata.packageIdentifier().getRepository(),
            |                      Package.Metadata::repositoryMapping,
            |                      // All packages in a given repository have the same repository mapping, so the
            |                      // particular way of resolving duplicates does not matter.
            |                      (first, second) -> first
            """.trimMargin()
            )

            if (emitCompactRepoMapping) {
                val repoAndMappings: com.google.common.collect.PeekingIterator<MutableMap.MutableEntry<RepositoryName?, com.google.devtools.build.lib.cmdline.RepositoryMapping?>?> =
                    com.google.common.collect.Iterators.peekingIterator<MutableMap.MutableEntry<RepositoryName?, com.google.devtools.build.lib.cmdline.RepositoryMapping?>?>(
                        sortedRepoMappings.entries.iterator()
                    )
                while (repoAndMappings.hasNext()) {
                    // If multiple (consecutive in sort order) repositories have identical repo mappings, we
                    // merge them into a single entry in the manifest, with the source repo name being the
                    // common prefix of the individual names, followed by a wildcard '*'. This is meant to
                    // reduce the size of the manifest entries for module extension repos from quadratic to
                    // linear in the number of repos, so we limit ourselves to those repositories.
                    val firstRepoAndMapping: MutableMap.MutableEntry<RepositoryName?, com.google.devtools.build.lib.cmdline.RepositoryMapping?>? =
                        repoAndMappings.next()
                    var groupSize = 1
                    while (repoAndMappings.hasNext()
                        && Companion.shouldMerge(firstRepoAndMapping, repoAndMappings.peek())
                    ) {
                        groupSize++
                        repoAndMappings.next()
                    }
                    val firstRepoName: String = firstRepoAndMapping!!.key.getName()
                    val source: String? =
                        if (groupSize == 1) firstRepoName else replaceLastSegmentWithAsterisk(firstRepoName)
                    computeRelevantEntries(reposInRunfilesPaths, firstRepoAndMapping.value.entries())
                        .forEach { mappingEntry: MutableMap.MutableEntry<String?, RepositoryName?>? ->
                            writeEntry(
                                writer,
                                source,
                                mappingEntry!!.key,
                                mappingEntry.value
                            )
                        }
                }
            } else {
                // All repositories generated by a module extension have the same Map instance as the
                // entries of their RepositoryMapping, with every repo appearing as an entry. If a module
                // extension generates N repos and all of them are in transitivePackages, iterating over the
                // packages and then over each mapping's entries would thus require time quadratic in N. We
                // prevent this by caching the relevant (target apparent name, target canonical name) pairs
                // per entry map instance.
                val cachedRelevantEntries: IdentityHashMap<com.google.common.collect.ImmutableMap<String?, RepositoryName?>?, com.google.common.collect.ImmutableList<MutableMap.MutableEntry<String?, RepositoryName?>?>?> =
                    IdentityHashMap<com.google.common.collect.ImmutableMap<String?, RepositoryName?>?, com.google.common.collect.ImmutableList<MutableMap.MutableEntry<String?, RepositoryName?>?>?>()
                for (repoAndMapping in sortedRepoMappings.entries) {
                    cachedRelevantEntries
                        .computeIfAbsent(
                            repoAndMapping.value.entries(),
                            java.util.function.Function { entries: com.google.common.collect.ImmutableMap<kotlin.String?, RepositoryName?>? ->
                                computeRelevantEntries(reposInRunfilesPaths, entries)
                                    .collect(TODO("Cannot convert element")) < Entry < String
                            }, RepositoryName shr com.google.common.collect.ImmutableList.toImmutableList<Any?>()
                        )
                    forEach(
                        { mappingEntry ->
                            writeEntry(
                                writer,
                                repoAndMapping.key.getName(),
                                mappingEntry.getKey(),
                                mappingEntry.getValue()
                            )
                        })
                }
            }
            writer.flush()
        }
    }

    private fun writeEntry(
        writer: PrintWriter,
        source: String?,
        targetApparentName: String?,
        targetCanonicalName: RepositoryName
    ) {
        // The canonical name of the main repo is the empty string, which is not a valid
        // name for a directory, so the "workspace name" is used the name of the
        // directory under the runfiles tree for it.
        val targetRepoDirectoryName =
            if (targetCanonicalName.isMain()) workspaceName else targetCanonicalName.getName()
        writer.format("%s,%s,%s\n", source, targetApparentName, targetRepoDirectoryName)
    }

    companion object {
        private val MY_UUID: UUID = UUID.fromString("458e351c-4d30-433d-b927-da6cddd4737f")

        private val repoMappingFingerprintCache: com.github.benmanes.caffeine.cache.LoadingCache<com.google.common.collect.ImmutableMap<String?, RepositoryName?>?, String?> =
            Caffeine.newBuilder()
                .weakKeys()
                .build<com.google.common.collect.ImmutableMap<String?, RepositoryName?>?, String?>(
                    com.github.benmanes.caffeine.cache.CacheLoader { repoMapping: com.google.common.collect.ImmutableMap<kotlin.String?, RepositoryName?>? ->
                        val fp: Fingerprint = Fingerprint()
                        fp.addInt(repoMapping.size)
                        repoMapping.forEach { (apparentName: String?, canonicalName: RepositoryName?) ->
                            fp.addString(apparentName)
                            fp.addString(canonicalName.getName())
                        }
                        fp.hexDigestAndReset()
                    })

        // Uses MapFn's args parameter just like Fingerprint#addString to compute a cacheable fingerprint
        // of just the repo name and mapping of a given Package.
        private val REPO_AND_MAPPING_DIGEST_FN: CommandLineItem.ExceptionlessMapFn<com.google.devtools.build.lib.packages.Package.Metadata?> =
            CommandLineItem.ExceptionlessMapFn { pkgMetadata, args ->
                args.accept(pkgMetadata.packageIdentifier().getRepository().getName())
                args.accept(repoMappingFingerprintCache.get(pkgMetadata.repositoryMapping().entries()))
            }

        private val OWNER_REPO_FN: CommandLineItem.ExceptionlessMapFn<Artifact?> =
            CommandLineItem.ExceptionlessMapFn { artifact, args ->
                args.accept(
                    if (artifact.getOwner() != null) artifact.getOwner().getRepository().getName() else ""
                )
            }

        private val FIRST_SEGMENT_FN: CommandLineItem.ExceptionlessMapFn<SymlinkEntry?> =
            CommandLineItem.ExceptionlessMapFn { symlink, args -> args.accept(symlink.getPath().getSegment(0)) }

        // The separator character used to combine the segments of a canonical repository name.
        // LINT.IfChange
        private const val REPO_NAME_SEPARATOR = '+'

        private fun shouldMerge(
            first: MutableMap.MutableEntry<RepositoryName?, com.google.devtools.build.lib.cmdline.RepositoryMapping?>,
            second: MutableMap.MutableEntry<RepositoryName?, com.google.devtools.build.lib.cmdline.RepositoryMapping?>
        ): Boolean {
            // Mappings of repos generated by the same extension are reference equal due to
            // ModuleExtensionRepoMappingEntriesFunction, so we can avoid the cost of equals. Similar
            // but not identical repo mappings could otherwise result in quadratic runtime.
            return first.value.entries() === second.value.entries()
                    && haveSamePrefix(first.key.getName(), second.key.getName())
        }

        /** Returns whether the two repository names agree up to the last segment of their names.  */
        private fun haveSamePrefix(first: String, second: String): Boolean {
            val firstSeparatorPos: Int = first.lastIndexOf(REPO_NAME_SEPARATOR)
            val secondSeparatorPos: Int = second.lastIndexOf(REPO_NAME_SEPARATOR)
            if (firstSeparatorPos == -1 || firstSeparatorPos != secondSeparatorPos) {
                return false
            }
            return first.regionMatches(0, second, 0, firstSeparatorPos)
        }

        private fun replaceLastSegmentWithAsterisk(repoName: String): String {
            return repoName.substring(0, repoName.lastIndexOf(REPO_NAME_SEPARATOR) + 1) + "*"
        }

        private fun computeRelevantEntries(
            reposInRunfilesPaths: com.google.common.collect.ImmutableSet<String?>,
            mappingEntries: com.google.common.collect.ImmutableMap<String?, RepositoryName?>
        ): java.util.stream.Stream<MutableMap.MutableEntry<String?, RepositoryName?>?>? {
            // TODO: If this becomes a hotspot, consider iterating over reposInRunfilesPaths and looking
            //  up the apparent name in the inverse of mappingEntries, which ensures that the runtime is
            //  always linear in the number of entries ultimately emitted into the manifest and independent
            //  of the size of the individual mappings. This requires making RepositoryMapping#entries() an
            //  ImmutableBiMap, or even ImmutableMultimap since repositories can have multiple apparent
            //  names.
            return mappingEntries.entries.stream() // The apparent repo name can only be empty for the main repo. We skip this line as
                // Rlocation paths can't reference an empty apparent name anyway.
                .filter { mappingEntry: MutableMap.MutableEntry<String?, RepositoryName?>? -> !mappingEntry!!.key.isEmpty() }  // We only write entries for repos whose canonical names appear in runfiles paths.
                .filter { entry: MutableMap.MutableEntry<String?, RepositoryName?>? ->
                    reposInRunfilesPaths.contains(
                        entry!!.value.getName()
                    )
                }
                .sorted(java.util.Map.Entry.comparingByKey<String?, RepositoryName?>())
        }
    }
}
