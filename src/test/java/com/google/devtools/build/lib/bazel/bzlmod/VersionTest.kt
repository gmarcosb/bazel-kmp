// Copyright 2021 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException

/** Tests for [Version].  */
@RunWith(JUnit4::class)
class VersionTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyBeatsEverything() {
        assertThat(Version.parse("")).isGreaterThan(Version.parse("1.0"))
        assertThat(Version.parse("")).isGreaterThan(Version.parse("1.0+build"))
        assertThat(Version.parse("")).isGreaterThan(Version.parse("1.0-pre"))
        assertThat(Version.parse("")).isGreaterThan(Version.parse("1.0-pre+build-kek.lol"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNormalized() {
        assertThat(Version.parse("1.0").getNormalized()).isEqualTo("1.0")
        assertThat(Version.parse("1.0+build").getNormalized()).isEqualTo("1.0")
        assertThat(Version.parse("1.0-pre").getNormalized()).isEqualTo("1.0-pre")
        assertThat(Version.parse("1.0-pre+build-kek.lol").getNormalized()).isEqualTo("1.0-pre")
        assertThat(Version.parse("1.0+build-notpre").getNormalized()).isEqualTo("1.0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReleaseVersion() {
        assertThat(Version.parse("2.0")).isGreaterThan(Version.parse("1.0"))
        assertThat(Version.parse("2.0")).isGreaterThan(Version.parse("1.9"))
        assertThat(Version.parse("11.0")).isGreaterThan(Version.parse("3.0"))
        assertThat(Version.parse("1.0.1")).isGreaterThan(Version.parse("1.0"))
        assertThat(Version.parse("1.0.0")).isGreaterThan(Version.parse("1.0"))
        assertThat(Version.parse("1.0+build2")).isEqualTo(Version.parse("1.0+build3"))
        assertThat(Version.parse("1.0")).isGreaterThan(Version.parse("1.0-pre"))
        assertThat(Version.parse("1.0")).isEqualTo(Version.parse("1.0+build-notpre"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReleaseVersionWithLetters() {
        assertThat(Version.parse("1.0.patch.3")).isGreaterThan(Version.parse("1.0"))
        assertThat(Version.parse("1.0.patch.3")).isGreaterThan(Version.parse("1.0.patch.2"))
        assertThat(Version.parse("1.0.patch.3")).isLessThan(Version.parse("1.0.patch.10"))
        assertThat(Version.parse("1.0.patch3")).isGreaterThan(Version.parse("1.0.patch10"))
        assertThat(Version.parse("4")).isLessThan(Version.parse("a"))
        assertThat(Version.parse("abc")).isLessThan(Version.parse("abd"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrereleaseVersion() {
        assertThat(Version.parse("1.0-pre")).isGreaterThan(Version.parse("1.0-are"))
        assertThat(Version.parse("1.0-3")).isGreaterThan(Version.parse("1.0-2"))
        assertThat(Version.parse("1.0-pre")).isLessThan(Version.parse("1.0-pre.foo"))
        assertThat(Version.parse("1.0-pre.3")).isGreaterThan(Version.parse("1.0-pre.2"))
        assertThat(Version.parse("1.0-pre.10")).isGreaterThan(Version.parse("1.0-pre.2"))
        assertThat(Version.parse("1.0-pre.10a")).isLessThan(Version.parse("1.0-pre.2a"))
        assertThat(Version.parse("1.0-pre.99")).isLessThan(Version.parse("1.0-pre.2a"))
        assertThat(Version.parse("1.0-pre.patch.3")).isLessThan(Version.parse("1.0-pre.patch.4"))
        assertThat(Version.parse("1.0--")).isLessThan(Version.parse("1.0----"))
        assertThat(Version.parse("2.1.1-develop.bcr.20250113215904"))
            .isGreaterThan(Version.parse("2.1.1-develop.bcr.20250113215903"))
    }

    @org.junit.Test
    fun testParseException() {
        org.junit.Assert.assertThrows<T?>(
            ParseException::class.java,
            org.junit.function.ThrowingRunnable { Version.parse("-abc") })
        org.junit.Assert.assertThrows<T?>(
            ParseException::class.java,
            org.junit.function.ThrowingRunnable { Version.parse("1_2") })
        org.junit.Assert.assertThrows<T?>(
            ParseException::class.java,
            org.junit.function.ThrowingRunnable { Version.parse("ßážëł") })
        org.junit.Assert.assertThrows<T?>(
            ParseException::class.java,
            org.junit.function.ThrowingRunnable { Version.parse("1.0-pre?") })
        org.junit.Assert.assertThrows<T?>(
            ParseException::class.java,
            org.junit.function.ThrowingRunnable { Version.parse("1.0-11111111111111111111111111111111111111111") })
        org.junit.Assert.assertThrows<T?>(
            ParseException::class.java,
            org.junit.function.ThrowingRunnable { Version.parse("1.0-pre///") })
        org.junit.Assert.assertThrows<T?>(
            ParseException::class.java,
            org.junit.function.ThrowingRunnable { Version.parse("1..0") })
        org.junit.Assert.assertThrows<T?>(
            ParseException::class.java,
            org.junit.function.ThrowingRunnable { Version.parse("1.0-pre..erp") })
    }
}
