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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.vfs.FileSystem

/**
 * An indirection layer on Path resolution of [Artifact] and [Root].
 * 
 * 
 * Serves as converter interface primarily for switching the [FileSystem] underlying the
 * values.
 */
interface ArtifactPathResolver {
    /**
     * @return a resolved Path corresponding to the given actionInput.
     */
    fun toPath(actionInput: ActionInput?): Path?

    /**
     * @return a resolved Path corresponding to the given path.
     */
    fun convertPath(path: Path?): Path?

    /** @return a resolved [Root] corresponding to the given Root.
     */
    fun transformRoot(root: Root?): Root?

    /**
     * Path resolution that uses an Artifact's path directly, or looks up the input execPath relative
     * to the given execRoot.
     */
    class IdentityResolver private constructor(execRoot: Path) : ArtifactPathResolver {
        private val execRoot: Path

        init {
            this.execRoot = execRoot
        }

        override fun toPath(actionInput: ActionInput): Path? {
            if (actionInput is Artifact) {
                return actionInput.getPath()
            }
            return execRoot.getRelative(actionInput.getExecPath())
        }

        override fun transformRoot(root: Root?): Root? {
            return com.google.common.base.Preconditions.checkNotNull<Root?>(root)
        }

        override fun convertPath(path: Path?): Path? {
            return path
        }
    }

    /**
     * A resolver that transforms all results to the same filesystem as the given execRoot.
     */
    class TransformResolver private constructor(execRoot: Path) : ArtifactPathResolver {
        private val fileSystem: FileSystem
        private val execRoot: Path

        init {
            this.execRoot = execRoot
            this.fileSystem = com.google.common.base.Preconditions.checkNotNull<T>(execRoot.getFileSystem())
        }

        override fun toPath(input: ActionInput): Path {
            if (input is Artifact) {
                return fileSystem.getPath(input.getPath().asFragment())
            }
            return execRoot.getRelative(input.getExecPath())
        }

        override fun transformRoot(root: Root?): Root {
            return Root.toFileSystem(com.google.common.base.Preconditions.checkNotNull<T?>(root), fileSystem)
        }

        override fun convertPath(path: Path): Path {
            return fileSystem.getPath(path.asFragment())
        }
    }

    companion object {
        fun forExecRoot(execRoot: Path): ArtifactPathResolver {
            return IdentityResolver(execRoot)
        }

        fun withTransformedFileSystem(execRoot: Path): ArtifactPathResolver {
            return TransformResolver(execRoot)
        }

        fun createPathResolver(
            fileSystem: FileSystem?,
            execRoot: Path
        ): ArtifactPathResolver {
            if (fileSystem == null) {
                return forExecRoot(execRoot)
            } else {
                return withTransformedFileSystem(
                    fileSystem.getPath(execRoot.asFragment())
                )
            }
        }

        @kotlin.jvm.JvmField
        val IDENTITY: ArtifactPathResolver = IdentityResolver(null)
    }
}
