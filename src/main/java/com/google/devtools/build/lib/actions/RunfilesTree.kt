// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.SymlinkEntry

/** Lazy wrapper for a single runfiles tree.  */ // TODO(bazel-team): Ideally we could refer to Runfiles objects directly here, but current package
// structure makes this difficult. Consider moving things around to make this possible.
interface RunfilesTree {
    /** Returns the exec path of the root directory of the runfiles tree.  */
    fun getExecPath(): PathFragment?

    /** Returns the mapping from the location in the runfiles tree to the artifact that's there.  */
    fun getMapping(): SortedMap<PathFragment?, Artifact?>?

    /**
     * Returns artifacts the runfiles tree contain symlinks to.
     * 
     * 
     * This includes artifacts that the symlinks and root symlinks point to, not just artifacts at
     * their canonical location.
     */
    fun getArtifacts(): NestedSet<Artifact?>?

    /** Returns the [RunfileSymlinksMode] for this runfiles tree.  */
    fun getSymlinksMode(): RunfileSymlinksMode?

    /** Returns whether the runfile symlinks should be materialized during the build.  */
    fun isBuildRunfileLinks(): Boolean

    /** Returns the name of the workspace that the build is occurring in.  */
    fun getWorkspaceName(): String?

    /**
     * Returns artifacts the runfiles tree contain symlinks to at their canonical locations.
     * 
     * 
     * This does **not** include artifacts that only the symlinks and root symlinks point to.
     */
    fun getArtifactsAtCanonicalLocationsForLogging(): NestedSet<Artifact?>?

    /**
     * Returns the set of names of implicit empty files to materialize.
     * 
     * 
     * If this runfiles tree does not implicitly add empty files, implementations should have a
     * dedicated fast path that returns an empty set without traversing the tree.
     */
    fun getEmptyFilenamesForLogging(): Iterable<PathFragment?>?

    /** Returns the set of custom symlink entries.  */
    fun getSymlinksForLogging(): NestedSet<SymlinkEntry?>?

    /** Returns the set of root symlinks.  */
    fun getRootSymlinksForLogging(): NestedSet<SymlinkEntry?>?

    /** Returns the repo mapping manifest if it exists.  */
    fun getRepoMappingManifestForLogging(): Artifact?

    /** Whether [.getMapping] is cached due to potential reuse within a single build.  */
    fun isMappingCached(): Boolean

    /** Fingerprints this runfiles tree.  */
    fun fingerprint(actionKeyContext: ActionKeyContext?, fp: Fingerprint?, digestAbsolutePaths: Boolean)

    /**
     * Whether the runfiles tree contains any [Artifact.SpecialArtifactType.CONSTANT_METADATA]
     * artifacts.
     */
    fun containsConstantMetadata(): Boolean {
        return getArtifacts().toList().stream().anyMatch(Artifact::isConstantMetadata)
    }
}
