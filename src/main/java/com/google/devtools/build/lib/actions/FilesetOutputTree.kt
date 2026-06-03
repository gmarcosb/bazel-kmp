// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.skyframe.TreeArtifactValue

/** A collection of [FilesetOutputSymlink]s comprising the output tree of a fileset.  */
class FilesetOutputTree private constructor(
    symlinks: com.google.common.collect.ImmutableList<FilesetOutputSymlink>?,
    treeArtifacts: com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?>?,
    private val forwarded: Boolean
) : RichArtifactData {
    private val symlinks: com.google.common.collect.ImmutableList<FilesetOutputSymlink>
    private val treeArtifacts: com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?>

    init {
        this.symlinks =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<FilesetOutputSymlink>>(
                symlinks
            )
        this.treeArtifacts =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?>>(
                treeArtifacts
            )
    }

    /** Returns the symlinks in the fileset, ordered by [FilesetOutputSymlink.name].  */
    fun symlinks(): com.google.common.collect.ImmutableList<FilesetOutputSymlink> {
        return symlinks
    }

    /**
     * Returns true if this Fileset is really created from a different action.
     * 
     * 
     * This is used to avoid double-counting the size of the fileset in metrics.
     */
    fun isForwarded(): Boolean {
        return forwarded
    }

    fun size(): Int {
        return symlinks.size
    }

    fun isEmpty(): Boolean {
        return symlinks.isEmpty()
    }

    /**
     * Returns the metadata of all tree artifacts included in this fileset.
     * 
     * 
     * Individual children of these tree artifacts each have their own entry in [ ][.symlinks], unless they were excluded by the `excludes` parameter on `FilesetEntry`.
     */
    fun getTreeArtifacts(): com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?> {
        return treeArtifacts
    }

    override fun hashCode(): Int {
        return symlinks.hashCode()
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is FilesetOutputTree) {
            return false
        }
        return symlinks == o.symlinks
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this).add("symlinks", symlinks).toString()
    }

    fun addTo(fp: Fingerprint) {
        for (symlink in symlinks) {
            fp.addPath(symlink.name)
            fp.addPath(symlink.target.getExecPath())
            fp.addBytes(symlink.metadata.getDigest())
        }
    }

    companion object {
        val EMPTY: FilesetOutputTree = FilesetOutputTree(
            com.google.common.collect.ImmutableList.of<FilesetOutputSymlink?>(),
            com.google.common.collect.ImmutableMap.of<Artifact?, TreeArtifactValue?>(),
            false
        )

        fun forward(other: FilesetOutputTree): FilesetOutputTree? {
            return if (other.isEmpty())
                EMPTY
            else
                FilesetOutputTree(other.symlinks, other.treeArtifacts, true)
        }

        fun create(
            symlinks: MutableList<FilesetOutputSymlink?>, treeArtifacts: MutableMap<Artifact?, TreeArtifactValue?>
        ): FilesetOutputTree? {
            val sortedSymlinks: com.google.common.collect.ImmutableList<FilesetOutputSymlink> =
                com.google.common.collect.ImmutableList.sortedCopyOf<E>(
                    java.util.Comparator.comparing<T?, U?>(
                        FilesetOutputSymlink::name
                    ), symlinks
                )
            return if (symlinks.isEmpty())
                EMPTY
            else
                FilesetOutputTree(
                    sortedSymlinks,
                    com.google.common.collect.ImmutableMap.copyOf<Artifact?, TreeArtifactValue?>(treeArtifacts),
                    false
                )
        }
    }
}
