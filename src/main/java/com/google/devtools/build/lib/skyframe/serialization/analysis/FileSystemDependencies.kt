// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

/**
 * Union type for [FileDependencies], [ListingDependencies] and [ ].
 * 
 * 
 * At a structural level [FileDependencies] and [ListingDependencies] are very
 * similar. [ListingDependencies] could be modeled plainly as [FileDependencies]. The
 * crucial difference is that [&lt;][FileDependencies.containsMatch] takes a
 * set of files and [&lt;][ListingDependencies.matchesAnyDirectories] takes a
 * set of directory names so the two types are deliberately separated.
 */
internal interface FileSystemDependencies {
    /** Dependencies, excluding nested dependencies.  */
    interface FileOpDependency : FileSystemDependencies


    /**
     * True if data was missing for this dependency.
     * 
     * 
     * Dependencies are fetched from a remote cache without durability guarantees. It's possible
     * for the corresponding data to be missing. Any missing data induces missing data on anything
     * that references it. From an invalidation perspective, if [isMissingData] is true, the
     * dependency should never allow a cache hit and always signal matching everything.
     */
    val isMissingData: Boolean
}
