// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.common

import com.google.devtools.build.lib.actions.ActionInput

/**
 * A [RemotePathResolver] is used to resolve input/output paths for remote execution from
 * Bazel's internal path, or vice versa.
 */
interface RemotePathResolver {
    /**
     * Returns the `workingDirectory` for a remote action. Empty if working directory is the
     * input root.
     */
    val workingDirectory: PathFragment?

    /**
     * Returns a [SortedMap] which maps from input paths for remote action to [ ].
     */
    fun getInputMapping(
        context: SpawnExecutionContext, willAccessRepeatedly: Boolean
    ): SortedMap<PathFragment?, ActionInput?> {
        return context.getInputMapping(this.workingDirectory, willAccessRepeatedly)
    }

    /** Resolves the output path relative to input root for the given [Path].  */
    fun localPathToOutputPath(path: com.google.devtools.build.lib.vfs.Path?): String?

    /**
     * Resolves the output path relative to input root for the given [PathFragment].
     * 
     * @param execPath a path fragment relative to `execRoot`.
     */
    fun localPathToOutputPath(execPath: PathFragment?): String?

    /** Resolves the output path relative to input root for the [ActionInput].  */
    fun localPathToOutputPath(actionInput: ActionInput): String? {
        return localPathToOutputPath(actionInput.getExecPath())
    }

    /**
     * Resolves the local [Path] of an output file.
     * 
     * @param outputPath the return value of [.localPathToOutputPath].
     */
    fun outputPathToLocalPath(outputPath: String?): com.google.devtools.build.lib.vfs.Path?

    /** Returns the exec path for the given local path.  */
    fun localPathToExecPath(localPath: PathFragment?): PathFragment?

    /**
     * The default [RemotePathResolver] which use `execRoot` as input root and do NOT set
     * `workingDirectory` for remote actions.
     */
    class DefaultRemotePathResolver(execRoot: com.google.devtools.build.lib.vfs.Path) : RemotePathResolver {
        private val execRoot: com.google.devtools.build.lib.vfs.Path

        init {
            this.execRoot = execRoot
        }

        override fun getWorkingDirectory(): PathFragment {
            return PathFragment.EMPTY_FRAGMENT
        }

        override fun localPathToOutputPath(path: com.google.devtools.build.lib.vfs.Path): String? {
            return path.relativeTo(execRoot).getPathString()
        }

        override fun localPathToOutputPath(execPath: PathFragment): String? {
            return execPath.getPathString()
        }

        override fun outputPathToLocalPath(outputPath: String?): com.google.devtools.build.lib.vfs.Path? {
            return execRoot.getRelative(outputPath)
        }

        override fun localPathToExecPath(localPath: PathFragment): PathFragment? {
            return localPath.relativeTo(execRoot.asFragment())
        }
    }

    /**
     * A [RemotePathResolver] used when `--experimental_sibling_repository_layout` is set.
     * Use parent directory of `execRoot` and set `workingDirectory` to the base name of
     * `execRoot`.
     * 
     * 
     * The paths of outputs are relative to `workingDirectory`.
     */
    class SiblingRepositoryLayoutResolver(execRoot: com.google.devtools.build.lib.vfs.Path) : RemotePathResolver {
        private val execRoot: com.google.devtools.build.lib.vfs.Path
        private val workingDirectory: PathFragment?

        init {
            this.execRoot = execRoot
            // The "root directory" of the action from the point of view of RBE is the parent directory of
            // the execroot locally. This is so that paths of artifacts in external repositories don't
            // start with an uplevel reference.
            this.workingDirectory =
                PathFragment.create(com.google.common.base.Preconditions.checkNotNull<String?>(execRoot.getBaseName()))
        }

        override fun getWorkingDirectory(): PathFragment? {
            return workingDirectory
        }

        private val base: com.google.devtools.build.lib.vfs.Path
            get() = execRoot

        override fun localPathToOutputPath(path: com.google.devtools.build.lib.vfs.Path): String? {
            return path.relativeTo(this.base).getPathString()
        }

        override fun localPathToOutputPath(execPath: PathFragment?): String? {
            return localPathToOutputPath(execRoot.getRelative(execPath))
        }

        override fun outputPathToLocalPath(outputPath: String?): com.google.devtools.build.lib.vfs.Path? {
            return this.base.getRelative(outputPath)
        }

        override fun localPathToExecPath(localPath: PathFragment): PathFragment? {
            return localPath.relativeTo(this.base.asFragment())
        }
    }

    companion object {
        /** Creates the default [RemotePathResolver].  */
        fun createDefault(execRoot: com.google.devtools.build.lib.vfs.Path): RemotePathResolver {
            return DefaultRemotePathResolver(execRoot)
        }

        /**
         * Adapts a given base [RemotePathResolver] to also apply a [PathMapper] to map (and
         * inverse map) paths.
         */
        fun createMapped(
            base: RemotePathResolver, execRoot: com.google.devtools.build.lib.vfs.Path, pathMapper: PathMapper
        ): RemotePathResolver? {
            if (pathMapper.isNoop()) {
                return base
            }
            return object : RemotePathResolver {
                private val inverse: ConcurrentHashMap<PathFragment?, PathFragment?> =
                    ConcurrentHashMap<PathFragment?, PathFragment?>()

                override fun getWorkingDirectory(): PathFragment? {
                    return base.workingDirectory
                }

                override fun getInputMapping(
                    context: SpawnExecutionContext, willAccessRepeatedly: Boolean
                ): SortedMap<PathFragment?, ActionInput?> {
                    return base.getInputMapping(context, willAccessRepeatedly)
                }

                override fun localPathToOutputPath(path: com.google.devtools.build.lib.vfs.Path): String? {
                    return localPathToOutputPath(path.relativeTo(execRoot))
                }

                override fun localPathToOutputPath(execPath: PathFragment?): String? {
                    return base.localPathToOutputPath(map(execPath))
                }

                override fun outputPathToLocalPath(outputPath: String?): com.google.devtools.build.lib.vfs.Path? {
                    return execRoot.getRelative(
                        inverseMap(base.outputPathToLocalPath(outputPath).relativeTo(execRoot))
                    )
                }

                override fun localPathToExecPath(localPath: PathFragment?): PathFragment? {
                    return base.localPathToExecPath(localPath)
                }

                fun map(path: PathFragment?): PathFragment? {
                    val mappedPath: PathFragment? = pathMapper.map(path)
                    val previousPath: PathFragment? = inverse.put(mappedPath, path)
                    com.google.common.base.Preconditions.checkState(
                        previousPath == null || previousPath == path,
                        "Two different paths %s and %s map to the same path %s",
                        previousPath,
                        path,
                        mappedPath
                    )
                    return mappedPath
                }

                fun inverseMap(path: PathFragment?): PathFragment {
                    return com.google.common.base.Preconditions.checkNotNull<PathFragment>(
                        inverse.get(path), "Failed to find original path for mapped path %s", path
                    )
                }
            }
        }
    }
}
