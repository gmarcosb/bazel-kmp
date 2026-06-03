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
 * A test for [PathFragmentFilter].
 */
@RunWith(JUnit4::class)
class PathFragmentFilterTest {
    protected var filter: PathFragmentFilter? = null

    protected fun createFilter(filterString: String?) {
        filter = PathFragmentFilterConverter().convert(filterString)
    }

    protected fun assertIncluded(path: String?) {
        assertThat(filter.isIncluded(PathFragment.create(path))).isTrue()
    }

    protected fun assertExcluded(path: String?) {
        assertThat(filter.isIncluded(PathFragment.create(path))).isFalse()
    }

    @org.junit.Test
    fun emptyFilter() {
        createFilter("")
        assertIncluded("a/b/c")
        assertIncluded("d")
    }

    @org.junit.Test
    fun inclusions() {
        createFilter("a/b,c")
        assertIncluded("a/b")
        assertIncluded("a/b/c")
        assertIncluded("c")
        assertIncluded("c/d")
        assertExcluded("a")
        assertExcluded("a/c")
        assertExcluded("d")
        assertExcluded("e/f/g")
    }

    @org.junit.Test
    fun exclusions() {
        createFilter("-a/b,-c")
        assertExcluded("a/b")
        assertExcluded("a/b/c")
        assertExcluded("c")
        assertExcluded("c/d")
        assertIncluded("a")
        assertIncluded("a/c")
        assertIncluded("d")
        assertIncluded("e/f/g")
    }

    @org.junit.Test
    fun inclusionsAndExclusions() {
        createFilter("a,-c,,d,a/b/c,-a/b,a/b/d")
        assertIncluded("a")
        assertIncluded("a/c")
        assertExcluded("a/b")
        assertExcluded("a/b/c") // Exclusions take precedence over inclusions. Order is not important.
        assertExcluded("a/b/d") // Exclusions take precedence over inclusions. Order is not important.
        assertExcluded("c")
        assertExcluded("c/d")
        assertIncluded("d/e")
        assertExcluded("e")
        // When converted back to string, inclusion entries will be put first, followed by exclusion
        // entries.
        assertThat(filter.toString()).isEqualTo("a,d,a/b/c,a/b/d,-c,-a/b")
    }
}
