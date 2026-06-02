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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.vfs.PathFragment

/**
 * Handles options that specify list of included/excluded directories. Validates whether path is
 * included in that filter.
 * 
 * 
 * Excluded directories always take precedence over included ones (path depth and order are not
 * important).
 */
class PathFragmentFilter(inclusions: MutableList<PathFragment?>, exclusions: MutableList<PathFragment?>) {
    private val inclusions: MutableList<PathFragment>
    private val exclusions: MutableList<PathFragment>

    /**
     * Converts from a colon-separated list of of paths with optional '-' prefix into the
     * PathFragmentFilter: [-]path1[,[-]path2]...
     * 
     * 
     * Order of paths is not important. Empty entries are ignored. '-' marks an excluded path.
     */
    class PathFragmentFilterConverter

        : com.google.devtools.common.options.Converter.Contextless<PathFragmentFilter?>() {
        override fun convert(input: String): PathFragmentFilter {
            val inclusionList: MutableList<PathFragment?> = java.util.ArrayList<PathFragment?>()
            val exclusionList: MutableList<PathFragment?> = java.util.ArrayList<PathFragment?>()

            for (piece in com.google.common.base.Splitter.on(',').split(input)) {
                if (piece.length > 1 && piece.startsWith("-")) {
                    exclusionList.add(PathFragment.Companion.create(piece.substring(1)))
                } else if (!piece.isEmpty()) {
                    inclusionList.add(PathFragment.Companion.create(piece))
                }
            }

            // TODO(bazel-team): (2010) Both lists could be optimized not to include unnecessary
            // entries - e.g.  entry 'a/b/c' is not needed if 'a/b' is also specified and 'a/b' on
            // inclusion list is not needed if 'a' or 'a/b' is on exclusion list.
            return PathFragmentFilter(inclusionList, exclusionList)
        }

        val typeDescription: String
            get() = "a comma-separated list of paths with prefix '-' specifying excluded paths"
    }

    /**
     * Creates new PathFragmentFilter using provided inclusion and exclusion path lists.
     */
    init {
        this.inclusions = com.google.common.collect.ImmutableList.copyOf<PathFragment?>(inclusions)
        this.exclusions = com.google.common.collect.ImmutableList.copyOf<PathFragment?>(exclusions)
    }

    /**
     * @return true iff path is included (it is not on the exclusion list and
     * it is either on the inclusion list or inclusion list is empty).
     */
    fun isIncluded(path: PathFragment): Boolean {
        for (excludedPath in exclusions) {
            if (path.startsWith(excludedPath)) {
                return false
            }
        }
        for (includedPath in inclusions) {
            if (path.startsWith(includedPath)) {
                return true
            }
        }
        return inclusions.isEmpty() // If inclusion filter is not specified, path is included.
    }

    override fun toString(): String {
        val list: MutableList<String?> = java.util.ArrayList<String?>(inclusions.size + exclusions.size)
        for (path in inclusions) {
            list.add(path.getPathString())
        }
        for (path in exclusions) {
            list.add("-" + path.getPathString())
        }
        return com.google.common.base.Joiner.on(',').join(list)
    }
}
