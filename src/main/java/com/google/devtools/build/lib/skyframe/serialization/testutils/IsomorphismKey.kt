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
package com.google.devtools.build.lib.skyframe.serialization.testutils

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Concise description of an object, used for comparison with other objects.
 * 
 * 
 * Each instance corresponds to a partition found by partition refinement. It represents a set of
 * objects that have equivalent local fingerprints and equivalent links to other partitions.
 * 
 * 
 * Preserving the key can help with debugging and testing. It is possible to reduce this key to a
 * fingerprint if needed.
 */
class IsomorphismKey internal constructor(
    /**
     * The local fingerprint of a partition.
     * 
     * 
     * See [Canonizer.Node.localFingerprint].
     */
    private val fingerprint: String
) {
    /**
     * The edges to other partitions.
     * 
     * 
     * This is necessarily mutable because the underlying graphs are cyclic.
     */
    private val links: java.util.ArrayList<IsomorphismKey?> = java.util.ArrayList<IsomorphismKey?>()

    fun fingerprint(): String {
        return fingerprint
    }

    fun getLinksCount(): Int {
        return links.size()
    }

    fun getLink(i: Int): IsomorphismKey? {
        return links.get(i)
    }

    /**
     * Adds a link.
     * 
     * 
     * Only used by [Canonizer] during construction (and testing).
     */
    fun addLink(key: IsomorphismKey?) {
        links.add(key)
    }

    companion object {
        /**
         * Compares two [IsomorphismKey]s using joint depth-first-search.
         * 
         * 
         * Depth first search is sufficient because [IsomorphismKey]s are canonically structured.
         * 
         * @return true if the objects that the keys are derived from are equivalent.
         */
        @kotlin.jvm.JvmStatic
        fun areIsomorphismKeysEqual(objA: IsomorphismKey, objB: IsomorphismKey): Boolean {
            return areKeysEqual(
                objA,
                objB,
                Collections.newSetFromMap<IsomorphismKey?>(IdentityHashMap<IsomorphismKey?, Boolean?>()),
                Collections.newSetFromMap<IsomorphismKey?>(IdentityHashMap<IsomorphismKey?, Boolean?>())
            )
        }

        private fun areKeysEqual(
            objA: IsomorphismKey,
            objB: IsomorphismKey,
            visitedA: MutableSet<IsomorphismKey?>,
            visitedB: MutableSet<IsomorphismKey?>
        ): Boolean {
            val objAIsNew = visitedA.add(objA)
            val objBIsNew = visitedB.add(objB)
            if (objAIsNew != objBIsNew) {
                return false
            }
            if (!objAIsNew) {
                return true
            }

            if (objA.fingerprint != objB.fingerprint) {
                return false
            }

            val size: Int = objA.links.size()
            if (objB.links.size() != size) {
                return false
            }

            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return true
        }
    }
}
