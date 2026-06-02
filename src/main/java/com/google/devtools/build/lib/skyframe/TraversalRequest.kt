// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.Artifact

/** A request for [RecursiveFilesystemTraversalFunction].  */
abstract class TraversalRequest : ExecutionPhaseSkyKey {
    // TODO(cmita): This class is only implemented outside of tests by
    // DirectoryArtifactTraversalRequest. These should probably be consolidated and simplified.
    /** The path to start the traversal from; may be a file, a directory or a symlink.  */
    @com.google.common.annotations.VisibleForTesting
    abstract fun root(): DirectTraversalRoot?

    /**
     * Whether the path is in the output tree.
     * 
     * 
     * Such paths and all their subdirectories are assumed not to define packages, so package
     * lookup for them is skipped.
     */
    @kotlin.jvm.JvmField
    abstract val isRootGenerated: Boolean

    /** Whether Fileset assumes that output artifacts are regular files.  */
    abstract fun strictOutputFiles(): Boolean

    /**
     * Whether to skip checking if the root (if it's a directory) contains a BUILD file.
     * 
     * 
     * Such directories are not considered to be packages when this flag is true. This needs to be
     * true in order to traverse directories of packages, but should be false for *their*
     * subdirectories.
     */
    abstract fun skipTestingForSubpackage(): Boolean

    /**
     * Whether to emit nodes for empty directories.
     * 
     * 
     * If this returns false, empty directories will not be represented in the result of the
     * traversal.
     */
    abstract fun emitEmptyDirectoryNodes(): Boolean

    /**
     * Returns information to be attached to any error messages that may be reported.
     * 
     * 
     * This is purely informational and is not considered in equality.
     */
    abstract fun errorInfo(): String?

    /**
     * Creates a new traversal request identical to this one except with the given new values for
     * [.root] and [.skipTestingForSubpackage].
     */
    @com.google.errorprone.annotations.ForOverride
    protected abstract fun duplicateWithOverrides(
        root: DirectTraversalRoot?, skipTestingForSubpackage: Boolean
    ): TraversalRequest?

    /** Creates a new request to traverse a child element in the current directory (the root).  */
    fun forChildEntry(child: String?): TraversalRequest? {
        val newTraversalRoot =
            DirectTraversalRoot.Companion.forRootAndPath(
                root()!!.rootPart, root()!!.relativePart.getRelative(child)
            )
        return duplicateWithOverrides(newTraversalRoot,  /*skipTestingForSubpackage=*/false)
    }

    /**
     * Creates a new request for a changed root.
     * 
     * 
     * This method can be used when a package is found out to be under a different root path than
     * originally assumed.
     */
    fun forChangedRootPath(newRoot: Root?): TraversalRequest? {
        val newTraversalRoot =
            DirectTraversalRoot.Companion.forRootAndPath(newRoot, root()!!.relativePart)
        return duplicateWithOverrides(newTraversalRoot, skipTestingForSubpackage())
    }

    override fun functionName(): SkyFunctionName {
        return SkyFunctions.RECURSIVE_FILESYSTEM_TRAVERSAL
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("root", root())
            .add("isRootGenerated", this.isRootGenerated)
            .add("strictOutputFiles", strictOutputFiles())
            .add("skipTestingForSubpackage", skipTestingForSubpackage())
            .add("errorInfo", errorInfo())
            .toString()
    }

    /** The root directory of a [TraversalRequest].  */
    @AutoValue
    internal abstract class DirectTraversalRoot {
        /**
         * Returns the output Artifact corresponding to this traversal, if present. Only present when
         * traversing a generated output.
         */
        abstract val outputArtifact: Artifact?

        /**
         * Returns the root part of the full path.
         * 
         * 
         * This is typically the workspace root or some output tree's root (e.g. genfiles, binfiles).
         */
        abstract val rootPart: Root?

        /**
         * Returns the [root][.getRootPart]-relative part of the path.
         * 
         * 
         * This is typically the source directory under the workspace or the output file under an
         * output directory.
         */
        abstract val relativePart: PathFragment?

        /** Returns a [Path] composed of the root and relative parts.  */
        fun asPath(): com.google.devtools.build.lib.vfs.Path? {
            return this.rootPart.getRelative(this.relativePart)
        }

        /** Returns a [RootedPath] composed of the root and relative parts.  */
        fun asRootedPath(): RootedPath? {
            return RootedPath.toRootedPath(this.rootPart, this.relativePart)
        }

        override fun equals(o: Any?): Boolean {
            if (o === this) {
                return true
            }
            if (o is DirectTraversalRoot) {
                return this.outputArtifact == o.outputArtifact
                        && this.rootPart == o.rootPart
                        && this.relativePart == o.relativePart
            }
            return false
        }

        @Memoized
        abstract override fun hashCode(): Int

        companion object {
            fun forFileOrDirectory(fileOrDirectory: Artifact): DirectTraversalRoot {
                return create(
                    if (fileOrDirectory.isSourceArtifact()) null else fileOrDirectory,
                    fileOrDirectory.getRoot().getRoot(),
                    fileOrDirectory.getRootRelativePath()
                )
            }

            fun forRootedPath(rootedPath: RootedPath): DirectTraversalRoot {
                return forRootAndPath(rootedPath.getRoot(), rootedPath.getRootRelativePath())
            }

            fun forRootAndPath(rootPart: Root?, relativePart: PathFragment?): DirectTraversalRoot {
                return create( /* outputArtifact= */null, rootPart, relativePart)
            }

            @AutoCodec.Instantiator
            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            fun create(
                outputArtifact: Artifact?, rootPart: Root?, relativePart: PathFragment?
            ): DirectTraversalRoot {
                return AutoValue_TraversalRequest_DirectTraversalRoot(
                    outputArtifact, rootPart, relativePart
                )
            }
        }
    }
}
