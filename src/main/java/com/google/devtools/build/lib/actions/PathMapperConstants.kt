// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.vfs.PathFragment

/** Holder class for symbols used by the PathMapper interface that shouldn't be public.  */
internal object PathMapperConstants {
    val SEMANTICS_KEY: StarlarkSemantics.Key<PathMapper?> = Key("path_mapper", PathMapper.Companion.NOOP)
    val mappedSourceRoots: com.github.benmanes.caffeine.cache.LoadingCache<ArtifactRoot?, MappedArtifactRoot?> =
        Caffeine.newBuilder()
            .weakKeys()
            .build<ArtifactRoot?, MappedArtifactRoot?>(com.github.benmanes.caffeine.cache.CacheLoader { sourceRoot: ArtifactRoot? ->
                MappedArtifactRoot(
                    sourceRoot.getExecPath()
                )
            })

    private val BAZEL_OUT: PathFragment? = PathFragment.create("bazel-out")
    private val BLAZE_OUT: PathFragment? = PathFragment.create("blaze-out")

    /**
     * A special instance for use in [AbstractAction.computeKey] when path mapping is generally
     * enabled for an action.
     * 
     * 
     * When computing an action key, the following approaches to taking path mapping into account
     * do **not** work:
     * 
     * 
     *  * Using the actual path mapper is prohibitive since constructing it requires checking for
     * collisions among the action input's paths when computing the action key, which flattens
     * the input depsets of all actions that opt into path mapping and also increases CPU usage.
     *  * Unconditionally using [       ] can result in stale
     * action keys when an action is opted out of path mapping at execution time due to input
     * path collisions after stripping. See path_mapping_test for an example.
     *  * Using [PathMapper.NOOP] does not distinguish between map_each results built from
     * strings and those built from [       ][com.google.devtools.build.lib.starlarkbuildapi.FileApi.getExecPathStringForStarlark].
     * While the latter will be mapped at execution time, the former won't, resulting in the
     * same digest for actions that behave differently at execution time. This is covered by
     * tests in StarlarkRuleImplementationFunctionsTest.
     * 
     * 
     * 
     * Instead, we use a special path mapping instance that preserves the equality relations
     * between the original config segments, but prepends a fixed string to distinguish hard-coded
     * path strings from mapped paths. This relies on actions using path mapping to be "root
     * agnostic": they must not contain logic that depends on any particular (output) root path.
     */
    val FOR_FINGERPRINTING: PathMapper = PathMapper { execPath: PathFragment? ->
        if (!execPath.startsWith(BAZEL_OUT) && !execPath.startsWith(BLAZE_OUT)) {
            // This is not an output path.
            return@PathMapper execPath
        }
        val execPathString: String = execPath.getPathString()
        val startOfConfigSegment: Int = execPathString.indexOf('/'.code) + 1
        if (startOfConfigSegment == 0) {
            return@PathMapper execPath
        }
        PathFragment.createAlreadyNormalized(
            (execPathString.substring(0, startOfConfigSegment)
                    + "pm-"
                    + execPathString.substring(startOfConfigSegment))
        )
    }
}
