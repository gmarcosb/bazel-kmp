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

import com.google.devtools.build.lib.cmdline.RepositoryName

/**
 * An interface for resolving artifact names to [Artifact] objects. Should only be used in the
 * internal machinery of Blaze: rule implementations are not allowed to do this.
 */
interface ArtifactResolver {
    /**
     * Returns the [SourceArtifact] for the specified path, creating it if not found and setting
     * its root and execPath.
     * 
     * @param execPath the path of the source artifact relative to the source root
     * @param root the source root prefix of the path
     * @param owner the artifact owner.
     * @return the canonical source artifact for the given path
     */
    fun getSourceArtifact(execPath: PathFragment?, root: Root?, owner: ArtifactOwner?): SourceArtifact?

    /**
     * Returns the [SourceArtifact] for the specified path, creating it if not found and setting
     * its root and execPath.
     * 
     * @see .getSourceArtifact
     */
    fun getSourceArtifact(execPath: PathFragment?, root: Root?): SourceArtifact?

    /**
     * Resolves a [SourceArtifact] given an execRoot-relative path.
     * 
     * 
     * Note: this method should only be used when the roots are unknowable, such as from the
     * post-compile .d or manifest scanning methods.
     * 
     * @param execPath the exec path of the artifact to resolve
     * @param repositoryName the name of repository this artifact belongs to
     * @return an existing or new source Artifact for the given execPath. Returns null if the root can
     * not be determined and the artifact did not exist before.
     */
    fun resolveSourceArtifact(execPath: PathFragment?, repositoryName: RepositoryName?): SourceArtifact?

    fun resolveSourceArtifactsAsciiCaseInsensitively(
        execPath: PathFragment?, repositoryName: RepositoryName?
    ): com.google.common.collect.ImmutableList<SourceArtifact?>?

    /**
     * Resolves source Artifacts given execRoot-relative paths.
     * 
     * 
     * Never creates or returns derived artifacts, only source artifacts.
     * 
     * 
     * Note: this method should only be used when the roots are unknowable, such as from the
     * post-compile .d or manifest scanning methods.
     * 
     * @param execPaths list of exec paths of the artifacts to resolve
     * @param resolver object that helps to resolve package root of given paths
     * @return map which contains list of execPaths and corresponding Artifacts. Map can contain
     * existing or new source Artifacts for the given execPaths. The artifact is null if the root
     * cannot be determined and the artifact did not exist before. Return null if any dependencies
     * are missing.
     */
    @Throws(PackageRootException::class, java.lang.InterruptedException::class)
    fun resolveSourceArtifacts(
        execPaths: Iterable<PathFragment?>?, resolver: PackageRootResolver?
    ): MutableMap<PathFragment?, SourceArtifact?>?

    fun getPathFromSourceExecPath(execRoot: Path?, execPath: PathFragment?): Path?

    /**
     * Determines if an artifact is derived, that is, its root is a derived root or its exec path
     * starts with the bazel-out prefix.
     * 
     * @param execPath The artifact's exec path.
     */
    fun isDerivedArtifact(execPath: PathFragment?): Boolean
}
