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
package com.google.devtools.build.lib.cmdline

import com.google.devtools.build.lib.vfs.PathFragment

/** Tests for [IgnoredSubdirectories].  */
@RunWith(JUnit4::class)
class IgnoredSubdirectoriesTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleUnion() {
        val one: IgnoredSubdirectories = IgnoredSubdirectories.of(prefixes("prefix1"), patterns("pattern1"))
        val two: IgnoredSubdirectories? = IgnoredSubdirectories.of(prefixes("prefix2"), patterns("pattern2"))
        val union: IgnoredSubdirectories? = one.union(two)
        assertThat(union)
            .isEqualTo(
                IgnoredSubdirectories.of(
                    prefixes("prefix1", "prefix2"), patterns("pattern1", "pattern2")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelfUnionNoop() {
        val ignored: IgnoredSubdirectories = IgnoredSubdirectories.of(prefixes("pre"), patterns("pat"))
        assertThat(ignored.union(ignored)).isEqualTo(ignored)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun filterPrefixes() {
        val original: IgnoredSubdirectories =
            IgnoredSubdirectories.of(prefixes("foo", "bar", "barbaz", "bar/qux"))
        val filtered: IgnoredSubdirectories? = original.filterForDirectory(PathFragment.create("bar"))
        assertThat(filtered).isEqualTo(IgnoredSubdirectories.of(prefixes("bar", "bar/qux")))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun filterPatterns() {
        val original: IgnoredSubdirectories =
            IgnoredSubdirectories.of(
                prefixes(),
                patterns(
                    "**/sub",
                    "foo",
                    "bar/*/onesub",
                    "bar/qux/**",
                    "bar/ba*",
                    "bar/not/*/twosub",
                    "bar/**/barsub",
                    "bar/sub/subsub"
                )
            )
        val filtered: IgnoredSubdirectories? = original.filterForDirectory(PathFragment.create("bar/sub"))
        assertThat(filtered)
            .isEqualTo(
                IgnoredSubdirectories.of(
                    prefixes(), patterns("**/sub", "bar/*/onesub", "bar/**/barsub", "bar/sub/subsub")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun filterPatternsForHiddenFiles() {
        val original: IgnoredSubdirectories =
            IgnoredSubdirectories.of(
                prefixes(),
                patterns("not/sub", "*dden/sub", ".hidden/**/sub", ".hi*/*/sub", "*/sub", "**/sub")
            )
        val filtered: IgnoredSubdirectories? = original.filterForDirectory(PathFragment.create(".hidden"))
        // Glob semantics say that "*dden" is not supposed to match ".hidden".
        // "**" and "*" and ".hi*" should, though.
        assertThat(filtered)
            .isEqualTo(
                IgnoredSubdirectories.of(
                    prefixes(), patterns(".hidden/**/sub", ".hi*/*/sub", "*/sub", "**/sub")
                )
            )
    }

    companion object {
        private fun prefixes(vararg prefixes: String?): com.google.common.collect.ImmutableSet<PathFragment?> {
            return java.util.Arrays.stream<String?>(prefixes).map<Any?>(PathFragment::create)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
        }

        private fun patterns(vararg patterns: String?): com.google.common.collect.ImmutableList<String?> {
            return com.google.common.collect.ImmutableList.copyOf<String?>(patterns)
        }
    }
}
