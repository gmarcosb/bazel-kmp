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
package com.google.devtools.build.lib.analysis.util

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.actions.ActionKeyContext

/** [RunfilesTree] implementation wrapping a single [Runfiles] directory mapping.  */
@AutoCodec
class FakeRunfilesTree @AutoCodec.Instantiator constructor(
    runfilesDir: PathFragment,
    runfiles: Runfiles?,
    repoMappingManifest: Artifact?,
    runfileSymlinksMode: RunfileSymlinksMode?,
    buildRunfileLinks: Boolean
) : RunfilesTree {
    private val runfilesDir: PathFragment
    private val runfiles: Runfiles
    private val repoMappingManifest: Artifact?
    private val runfileSymlinksMode: RunfileSymlinksMode?
    val isBuildRunfileLinks: Boolean

    /**
     * Create an instance mapping `runfiles` to `runfilesDir`.
     * 
     * @param runfilesDir the desired runfiles directory. Should be relative.
     * @param runfiles the runfiles for runilesDir.
     * @param runfileSymlinksMode how to create runfile symlinks
     * @param buildRunfileLinks whether runfile symlinks should be created during the build
     */
    init {
        Preconditions.checkArgument(!runfilesDir.isAbsolute())
        this.runfilesDir = Preconditions.checkNotNull<PathFragment>(runfilesDir)
        this.runfiles = Preconditions.checkNotNull<Runfiles>(runfiles)
        this.repoMappingManifest = repoMappingManifest
        this.runfileSymlinksMode = runfileSymlinksMode
        this.isBuildRunfileLinks = buildRunfileLinks
    }

    val artifacts: NestedSet<Artifact?>
        get() = runfiles.getAllArtifacts()

    val execPath: PathFragment
        get() = runfilesDir

    val mapping: SortedMap<PathFragment?, Artifact?>
        get() = runfiles.getRunfilesInputs(repoMappingManifest)

    val symlinksMode: RunfileSymlinksMode?
        get() = runfileSymlinksMode

    val workspaceName: String
        get() = runfiles.getPrefix()

    val artifactsAtCanonicalLocationsForLogging: NestedSet<Artifact?>
        get() = runfiles.getArtifacts()

    val emptyFilenamesForLogging: Iterable<PathFragment>
        get() = runfiles.getEmptyFilenames()

    val symlinksForLogging: NestedSet<SymlinkEntry?>
        get() = runfiles.getSymlinks()

    val rootSymlinksForLogging: NestedSet<SymlinkEntry?>
        get() = runfiles.getRootSymlinks()

    val repoMappingManifestForLogging: Artifact?
        get() = repoMappingManifest

    val isMappingCached: Boolean
        get() = false

    public override fun fingerprint(
        actionKeyContext: ActionKeyContext?, fp: Fingerprint?, digestAbsolutePaths: Boolean
    ) {
        runfiles.fingerprint(actionKeyContext, fp, digestAbsolutePaths)
    }
}
