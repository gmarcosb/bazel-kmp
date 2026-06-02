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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionInput

/**
 * An in-memory cache to ensure we do I/O for source files only once during a single build.
 * 
 * 
 * Simply maintains a cached mapping from filename to metadata that may be populated only once.
 */
@javax.annotation.concurrent.ThreadSafe
class SingleBuildFileCache(
    cwd: String?,
    relativeOutputPath: PathFragment?,
    fs: com.google.devtools.build.lib.vfs.FileSystem,
    xattrProvider: XattrProvider?
) : InputMetadataProvider {
    private val execRoot: com.google.devtools.build.lib.vfs.Path?
    private val relativeOutputPath: PathFragment?

    // If we can't get the digest, we store the exception. This avoids extra file IO for files
    // that are allowed to be missing, as we first check a likely non-existent content file
    // first.  Further we won't need to unwrap the exception in getDigest().
    private val pathToMetadata: com.github.benmanes.caffeine.cache.Cache<PathFragment?, ActionInputMetadata?> =
        Caffeine.newBuilder() // Even small-ish builds, as of 11/21/2011 typically have over 10k artifacts, so it's
            // unlikely that this default will adversely affect memory in most cases.
            .initialCapacity(10000)
            .build<PathFragment?, ActionInputMetadata?>()
    private val xattrProvider: XattrProvider?

    init {
        this.xattrProvider = xattrProvider
        this.execRoot = fs.getPath(cwd)
        this.relativeOutputPath = relativeOutputPath
    }

    @Throws(IOException::class)
    public override fun getInputMetadataChecked(input: ActionInput): FileArtifactValue? {
        if (input is Artifact) {
            check(input.isSourceArtifact()) {
                java.lang.String.format(
                    "SingleBuildFileCache does not support derived artifact '%s'",
                    input.getExecPathString()
                )
            }
        } else check(!input.getExecPath().startsWith(relativeOutputPath)) {
            java.lang.String.format(
                "SingleBuildFileCache does not support action input '%s' in the output tree",
                input.getExecPath()
            )
        }

        return pathToMetadata
            .get(
                input.getExecPath(),
                java.util.function.Function { execPath: PathFragment? ->
                    val path: com.google.devtools.build.lib.vfs.Path = ActionInputHelper.toInputPath(input, execRoot)
                    val metadata: FileArtifactValue
                    try {
                        metadata =
                            FileArtifactValue.createFromStat(
                                path,  // TODO(b/199940216): should we use syscallCache here since caching anyway?
                                path.stat(Symlinks.FOLLOW),
                                xattrProvider
                            )
                    } catch (e: IOException) {
                        return@get ActionInputMetadata(input, e)
                    }
                    if (metadata.getType().isDirectory()) {
                        return@get ActionInputMetadata(
                            input, DigestOfDirectoryException("Input is a directory: " + execPath)
                        )
                    }
                    ActionInputMetadata(input, metadata)
                })
            .getMetadata()
    }

    public override fun getTreeMetadata(actionInput: ActionInput?): TreeArtifactValue? {
        return null
    }

    public override fun getEnclosingTreeMetadata(execPath: PathFragment?): TreeArtifactValue? {
        return null
    }

    public override fun getFileset(input: ActionInput?): FilesetOutputTree? {
        return null
    }

    val filesets: MutableMap<Artifact, FilesetOutputTree>
        get() = com.google.common.collect.ImmutableMap.of<Artifact?, FilesetOutputTree?>()

    public override fun getRunfilesMetadata(input: ActionInput?): RunfilesArtifactValue? {
        return null
    }

    val runfilesTrees: com.google.common.collect.ImmutableList<RunfilesTree?>
        get() = com.google.common.collect.ImmutableList.of<RunfilesTree?>()

    public override fun getInput(execPath: PathFragment?): ActionInput? {
        val metadata: ActionInputMetadata? = pathToMetadata.getIfPresent(execPath)
        if (metadata == null) {
            return null
        }
        return metadata.getInput()
    }

    /** Container class for caching I/O around ActionInputs.  */
    private class ActionInputMetadata {
        private val input: ActionInput?
        private val metadata: FileArtifactValue?
        private val exceptionOnAccess: IOException?

        /** Constructor for a successful lookup.  */
        internal constructor(input: ActionInput?, metadata: FileArtifactValue?) {
            this.input = input
            this.metadata = metadata
            this.exceptionOnAccess = null
        }

        /** Constructor for a failed lookup, size will be 0.  */
        internal constructor(input: ActionInput?, exceptionOnAccess: IOException?) {
            this.input = input
            this.exceptionOnAccess = exceptionOnAccess
            this.metadata = null
        }

        @Throws(IOException::class)
        fun getMetadata(): FileArtifactValue? {
            maybeRaiseException()
            return metadata
        }

        fun getInput(): ActionInput? {
            return input
        }

        @Throws(IOException::class)
        fun maybeRaiseException() {
            if (exceptionOnAccess != null) {
                throw exceptionOnAccess
            }
        }
    }
}
