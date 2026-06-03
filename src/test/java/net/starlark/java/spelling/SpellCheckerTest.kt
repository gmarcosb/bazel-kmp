// Copyright 2016 The Bazel Authors. All rights reserved.
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
package net.starlark.java.spelling

import com.google.common.collect.Lists
import com.google.common.truth.Truth
import net.starlark.java.spelling.SpellChecker.editDistance
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Tests for [SpellChecker].
 */
@RunWith(JUnit4::class)
class SpellCheckerTest {
    private fun assertDistance(s1: String, s2: String, distance: Int) {
        Truth.assertThat(editDistance(s1, s2, 100)).isEqualTo(distance)
        Truth.assertThat(editDistance(s1, s2, distance)).isEqualTo(distance)

        // Symmetry
        Truth.assertThat(editDistance(s2, s1, 100)).isEqualTo(distance)
        Truth.assertThat(editDistance(s2, s1, distance)).isEqualTo(distance)
    }

    @Test
    @Throws(Exception::class)
    fun editDistance_1() {
        // Deletion
        assertDistance("abcdef", "abdef", 1)
        assertDistance("abcdef", "abcde", 1)
        assertDistance("abcdef", "bcdef", 1)

        // Replacement
        assertDistance("abcdef", "_bcdef", 1)
        assertDistance("abcdef", "abc_ef", 1)
        assertDistance("abcdef", "abcde_", 1)

        // Insertion
        assertDistance("abcdef", "_abcdef", 1)
        assertDistance("abcdef", "abcd_ef", 1)
        assertDistance("abcdef", "abcdef_", 1)
    }

    @Test
    @Throws(Exception::class)
    fun editDistance_general() {
        assertDistance("", "", 0)
        assertDistance("abcd", "abcd", 0)
        assertDistance("abcde", "", 5)
        assertDistance("abcde", "12345", 5)
        assertDistance("ab", "ba", 2)
        assertDistance("abba", "acca", 2)
        assertDistance("abaa", "aaca", 2)
        assertDistance("kitten", "sitting", 3)
        assertDistance("kitten kitten", "sitting sitting", 6)
        assertDistance("flaw", "lawn", 2)
    }

    @Test
    @Throws(Exception::class)
    fun editDistance_maxDistance() {
        Truth.assertThat(editDistance("kitten", "sitting", 0)).isEqualTo(-1)
        Truth.assertThat(editDistance("kitten", "sitting", 1)).isEqualTo(-1)
        Truth.assertThat(editDistance("kitten", "sitting", 2)).isEqualTo(-1)
        Truth.assertThat(editDistance("kitten", "sitting", 3)).isEqualTo(3)
        Truth.assertThat(editDistance("kitten", "sitting", 4)).isEqualTo(3)

        Truth.assertThat(editDistance("abcdefg", "s", 2)).isEqualTo(-1)
    }

    @Test
    @Throws(Exception::class)
    fun suggest() {
        val dict: MutableList<String?> = Lists.newArrayList<String?>(
            "isalnum", "isalpha", "isdigit", "islower", "isupper", "find", "join", "range",
            "rsplit", "rstrip", "split", "splitlines", "startswith", "strip", "title", "upper",
            "x", "xyz"
        )

        Truth.assertThat(SpellChecker.suggest("isdfit", dict)).isEqualTo("isdigit")
        Truth.assertThat(SpellChecker.suggest("rspit", dict)).isEqualTo("rsplit")
        Truth.assertThat(SpellChecker.suggest("IS_LOWER", dict)).isEqualTo("islower")
        Truth.assertThat(SpellChecker.suggest("sartwigh", dict)).isEqualTo("startswith")
        Truth.assertThat(SpellChecker.suggest("SplitAllLines", dict)).isEqualTo("splitlines")
        Truth.assertThat(SpellChecker.suggest("fird", dict)).isEqualTo("find")
        Truth.assertThat(SpellChecker.suggest("stip", dict)).isEqualTo("strip")
        Truth.assertThat(SpellChecker.suggest("isAln", dict)).isEqualTo("isalnum")
        Truth.assertThat(SpellChecker.suggest("targe", dict)).isEqualTo("range")
        Truth.assertThat(SpellChecker.suggest("rarget", dict)).isEqualTo("range")
        Truth.assertThat(SpellChecker.suggest("xyw", dict)).isEqualTo("xyz")

        Truth.assertThat(SpellChecker.suggest("target", dict)).isNull()
        Truth.assertThat(SpellChecker.suggest("isAl", dict)).isNull()
        Truth.assertThat(SpellChecker.suggest("", dict)).isNull()
        Truth.assertThat(SpellChecker.suggest("f", dict)).isNull()
        Truth.assertThat(SpellChecker.suggest("fir", dict)).isNull()
        Truth.assertThat(SpellChecker.suggest("wqevxc", dict)).isNull()
        Truth.assertThat(SpellChecker.suggest("ialsnuaip", dict)).isNull()
        Truth.assertThat(SpellChecker.suggest("xy", dict)).isNull()
    }
}
