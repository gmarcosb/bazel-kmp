// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.actions.ActionInput

/**
 * A specialized concurrent trie that stores paths of artifacts and allows checking whether a given
 * path is contained in (in the case of a tree artifact) or exactly matches (in any other case) an
 * artifact in the trie.
 */
internal class ConcurrentArtifactPathTrie {
    // Invariant: no path in this set is a prefix of another path.
    private val paths: ConcurrentSkipListSet<PathFragment?> =
        ConcurrentSkipListSet<PathFragment?>(PathFragment.HIERARCHICAL_COMPARATOR)

    /**
     * Adds the given [ActionInput] to the trie.
     * 
     * 
     * The caller must ensure that no object's path passed to this method is a prefix of any
     * previously added object's path. Bazel enforces this for non-aggregate artifacts. Callers must
     * not pass in [Artifact.TreeFileArtifact]s (which have exec paths that have their parent
     * tree artifact's exec path as a prefix) or non-Artifact [ActionInput]s that violate this
     * invariant.
     */
    fun add(input: ActionInput) {
        com.google.common.base.Preconditions.checkArgument(
            input !is Artifact.TreeFileArtifact,
            "TreeFileArtifacts should not be added to the trie: %s",
            input
        )
        paths.add(input.getExecPath())
    }

    /** Checks whether the given [PathFragment] is contained in an artifact in the trie.  */
    fun contains(execPath: PathFragment): Boolean {
        // By the invariant of this set, there is at most one prefix of execPath in the set. Since the
        // comparator sorts all children of a path right after the path itself, if such a prefix
        // exists, it must thus sort right before execPath (or be equal to it).
        val floorPath: PathFragment? = paths.floor(execPath)
        return floorPath != null && execPath.startsWith(floorPath)
    }
}
