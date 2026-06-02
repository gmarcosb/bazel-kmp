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
package com.google.devtools.build.lib.includescanning

import com.github.benmanes.caffeine.cache.CacheLoader
import com.github.benmanes.caffeine.cache.LoadingCache
import com.google.common.base.Preconditions
import com.google.common.util.concurrent.ListenableFuture
import com.google.devtools.build.lib.actions.Artifact
import com.google.devtools.build.lib.vfs.Path
import java.util.*
import java.util.function.Supplier

/**
 * Creates include scanner instances.
 * 
 * 
 * Each include scanner is specific to a given triplet (-I, -isystem, -iquote) of include paths.
 */
class IncludeScannerSupplier(
    directories: BlazeDirectories,
    includePool: ExecutorService?,
    shouldShuffle: Boolean,
    artifactFactory: ArtifactFactory?,
    spawnIncludeScannerSupplier: Supplier<SpawnIncludeScanner?>?,
    execRoot: Path
) {
    private class IncludeScannerParams(
        quoteIncludePaths: MutableList<PathFragment?>,
        includePaths: MutableList<PathFragment?>,
        frameworkIncludePaths: MutableList<PathFragment?>
    ) {
        val quoteIncludePaths: MutableList<PathFragment?>
        val includePaths: MutableList<PathFragment?>
        val frameworkIncludePaths: MutableList<PathFragment?>

        init {
            this.quoteIncludePaths = quoteIncludePaths
            this.includePaths = includePaths
            this.frameworkIncludePaths = frameworkIncludePaths
        }

        override fun hashCode(): Int {
            return Objects.hash(quoteIncludePaths, includePaths, frameworkIncludePaths)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is IncludeScannerParams) {
                return false
            }
            return this.quoteIncludePaths == other.quoteIncludePaths
                    && this.includePaths == other.includePaths
                    && this.frameworkIncludePaths == other.frameworkIncludePaths
        }
    }

    private var includeParser: IncludeParser? = null

    /**
     * Cache of include scan results mapping source paths to sets of scanned inclusions. Shared by all
     * scanner instances.
     */
    private val includeParseCache: ConcurrentMap<Artifact?, ListenableFuture<MutableCollection<Inclusion?>?>?> =
        ConcurrentHashMap<Artifact?, ListenableFuture<MutableCollection<Inclusion?>?>?>()

    /** Cache of include scanner instances mapped by include-path hashes.  */
    private val scanners: LoadingCache<IncludeScannerParams?, IncludeScanner?>

    init {
        // Map of grepped include files from input (.cc or .h) to a header-grepped file.
        val pathCache = PathExistenceCache(execRoot, artifactFactory)
        scanners =
            Caffeine.newBuilder() // We choose to make cache values weak referenced due to LegacyIncludeScanner can hold
                // on to a memory expensive InclusionCache. However, a lot of IncludeScannerParams are
                // not in use so they are eligible for garbage collection. As a matter of fact, this
                // reduces peak heap on an example cpp-heavy build by ~5%.
                //
                // We could also choose to use softValues() but avoid doing so. The reason is that we
                // want to keep blaze memory usage deterministic and to guarantee collection before
                // blaze initiated-OOMs.

                .weakValues()
                .build<IncludeScannerParams?, IncludeScanner?>(
                    CacheLoader { key: IncludeScannerParams? ->
                        LegacyIncludeScanner(
                            includeParser,
                            includePool,
                            shouldShuffle,
                            includeParseCache,
                            pathCache,
                            key!!.quoteIncludePaths,
                            key.includePaths,
                            key.frameworkIncludePaths,
                            directories.getOutputPath(execRoot.getBaseName()),
                            execRoot,
                            artifactFactory,
                            spawnIncludeScannerSupplier
                        )
                    })
    }

    /**
     * Returns the possibly shared scanner to be used for a given triplet of include paths. The paths
     * are specified as PathFragments relative to the execution root.
     */
    fun scannerFor(
        quoteIncludePaths: MutableList<PathFragment?>,
        includePaths: MutableList<PathFragment?>,
        frameworkPaths: MutableList<PathFragment?>
    ): IncludeScanner? {
        Preconditions.checkNotNull<IncludeParser?>(includeParser)
        return scanners.get(IncludeScannerParams(quoteIncludePaths, includePaths, frameworkPaths))
    }

    fun init(includeParser: IncludeParser?) {
        Preconditions.checkState(
            this.includeParser == null,
            "Must only be initialized once: %s %s",
            this.includeParser,
            includeParser
        )
        Preconditions.checkState(includeParseCache.isEmpty(), includeParseCache)
        Preconditions.checkState(scanners.asMap().isEmpty(), scanners)
        this.includeParser = Preconditions.checkNotNull<IncludeParser?>(includeParser)
    }
}
