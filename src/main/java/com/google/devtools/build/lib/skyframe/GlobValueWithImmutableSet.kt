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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.skyframe.GlobValue
import com.google.devtools.build.lib.vfs.PathFragment

/**
 * A value corresponding to a glob which uses [ImmutableSet] as the container to store
 * matching [PathFragment]s.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class GlobValueWithImmutableSet(matches: com.google.common.collect.ImmutableSet<PathFragment?>?) : GlobValue() {
    private val matches: com.google.common.collect.ImmutableSet<PathFragment?>

    /** Creates a [GlobValueWithImmutableSet] wrapping `matches`.  */
    init {
        this.matches =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSet<PathFragment?>>(
                matches
            )
    }

    /** Returns an unordered [ImmutableSet] containing all glob matches.  */
    override fun getMatches(): com.google.common.collect.ImmutableSet<PathFragment?> {
        return matches
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }
        if (other !is GlobValueWithImmutableSet) {
            return false
        }
        return matches == other.matches
    }

    override fun hashCode(): Int {
        return matches.hashCode()
    }

    companion object {
        val EMPTY: GlobValueWithImmutableSet =
            GlobValueWithImmutableSet(com.google.common.collect.ImmutableSet.of<PathFragment?>())
    }
}
