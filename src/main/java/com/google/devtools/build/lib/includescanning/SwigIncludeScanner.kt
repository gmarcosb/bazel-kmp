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

import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.devtools.build.lib.actions.Artifact
import com.google.devtools.build.lib.vfs.Path
import java.util.function.Supplier

/** Include scanner for swig files.  */
class SwigIncludeScanner
/**
 * Constructs a new SwigIncludeScanner used to parse swig include statements (%include / %extern /
 * %import).
 * 
 * @param spawnIncludeScanner
 * @param cache externally scoped cache of file-path to inclusion-set mappings
 * @param includePaths the list of search path dirs
 * @param execRoot
 */
    (
    includePool: ExecutorService?,
    shouldShuffle: Boolean,
    spawnIncludeScanner: SpawnIncludeScanner?,
    cache: ConcurrentMap<Artifact?, ListenableFuture<MutableCollection<Inclusion?>?>?>?,
    includePaths: MutableList<PathFragment?>?,
    directories: BlazeDirectories,
    artifactFactory: ArtifactFactory?,
    execRoot: Path
) : LegacyIncludeScanner(
    SwigIncludeParser(),
    includePool,
    shouldShuffle,
    cache,
    PathExistenceCache(execRoot, artifactFactory),  /* quoteIncludePaths= */
    ImmutableList.of<PathFragment?>(),
    includePaths,  /* frameworkIncludePaths= */
    ImmutableList.of<PathFragment?>(),
    directories.getOutputPath(execRoot.getBaseName()),
    execRoot,
    artifactFactory,
    Supplier { spawnIncludeScanner })
