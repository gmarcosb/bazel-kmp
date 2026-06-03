// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs

import com.google.common.testing.EqualsTester
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import com.google.devtools.build.lib.vfs.PathAbstractTest
import com.google.devtools.common.options.testing.ConverterTester.addEqualityGroup
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Path

/** Tests the unix implementation of [Path].  */
@RunWith(JUnit4::class)
class UnixPathTest : PathAbstractTest() {
    @org.junit.Test
    fun testEqualsAndHashCodeUnix() {
        EqualsTester()
            .addEqualityGroup(create("/something/else"))
            .addEqualityGroup(create("/"), create("//////"))
            .testEquals()
    }

    @org.junit.Test
    fun testRelativeToUnix() {
        assertThat(create("/").relativeTo(create("/")).getPathString()).isEmpty()
        assertThat(create("/foo").relativeTo(create("/foo")).getPathString()).isEmpty()
        assertThat(create("/foo/bar/baz").relativeTo(create("/foo")).getPathString())
            .isEqualTo("bar/baz")
        assertThat(create("/foo/bar/baz").relativeTo(create("/foo/bar")).getPathString())
            .isEqualTo("baz")
        assertThat(create("/foo").relativeTo(create("/")).getPathString()).isEqualTo("foo")
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("/foo/bar/baz").relativeTo(create("foo")) })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("/foo").relativeTo(create("/foo/bar/baz")) })
    }

    @org.junit.Test
    fun testGetRelativeUnix() {
        assertThat(create("/a").getRelative("b").getPathString()).isEqualTo("/a/b")
        assertThat(create("/a/b").getRelative("c/d").getPathString()).isEqualTo("/a/b/c/d")
        assertThat(create("/c/d").getRelative("/a/b").getPathString()).isEqualTo("/a/b")
        assertThat(create("/a").getRelative("").getPathString()).isEqualTo("/a")
        assertThat(create("/").getRelative("").getPathString()).isEqualTo("/")
        assertThat(create("/a/b").getRelative("../foo").getPathString()).isEqualTo("/a/foo")

        // Make sure any fast path of Path#getRelative(PathFragment) works
        assertThat(create("/a/b").getRelative(PathFragment.create("../foo")).getPathString())
            .isEqualTo("/a/foo")

        // Make sure any fast path of Path#getRelative(PathFragment) works
        assertThat(create("/c/d").getRelative(PathFragment.create("/a/b")).getPathString())
            .isEqualTo("/a/b")

        // Test normalization
        assertThat(create("/a").getRelative(".").getPathString()).isEqualTo("/a")
    }

    @org.junit.Test
    fun testEmptyPathToEmptyPathUnix() {
        // compare string forms
        assertThat(create("/").getPathString()).isEqualTo("/")
        // compare fragment forms
        assertThat(create("/")).isEqualTo(create("/"))
    }

    @org.junit.Test
    fun testRedundantSlashes() {
        // compare string forms
        assertThat(create("///").getPathString()).isEqualTo("/")
        // compare fragment forms
        assertThat(create("///")).isEqualTo(create("/"))
        // compare string forms
        assertThat(create("/foo///bar").getPathString()).isEqualTo("/foo/bar")
        // compare fragment forms
        assertThat(create("/foo///bar")).isEqualTo(create("/foo/bar"))
        // compare string forms
        assertThat(create("////foo//bar").getPathString()).isEqualTo("/foo/bar")
        // compare fragment forms
        assertThat(create("////foo//bar")).isEqualTo(create("/foo/bar"))
    }

    @org.junit.Test
    fun testSimpleNameToSimpleNameUnix() {
        // compare string forms
        assertThat(create("/foo").getPathString()).isEqualTo("/foo")
        // compare fragment forms
        assertThat(create("/foo")).isEqualTo(create("/foo"))
    }

    @org.junit.Test
    fun testSimplePathToSimplePathUnix() {
        // compare string forms
        assertThat(create("/foo/bar").getPathString()).isEqualTo("/foo/bar")
        // compare fragment forms
        assertThat(create("/foo/bar")).isEqualTo(create("/foo/bar"))
    }

    @org.junit.Test
    fun testGetParentDirectoryUnix() {
        assertThat(create("/foo/bar/wiz").getParentDirectory()).isEqualTo(create("/foo/bar"))
        assertThat(create("/foo/bar").getParentDirectory()).isEqualTo(create("/foo"))
        assertThat(create("/foo").getParentDirectory()).isEqualTo(create("/"))
        assertThat(create("/").getParentDirectory()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasenameUnix() {
        assertThat(create("/foo/bar").getBaseName()).isEqualTo("bar")
        assertThat(create("/foo/").getBaseName()).isEqualTo("foo")
        assertThat(create("/foo").getBaseName()).isEqualTo("foo")
        assertThat(create("/").getBaseName()).isEmpty()
    }

    @org.junit.Test
    fun testStartsWithUnix() {
        val foobar: Path = create("/foo/bar")

        // (path, prefix) => true
        assertThat(foobar.startsWith(foobar)).isTrue()
        assertThat(foobar.startsWith(create("/"))).isTrue()
        assertThat(foobar.startsWith(create("/foo"))).isTrue()
        assertThat(foobar.startsWith(create("/foo/"))).isTrue()
        assertThat(foobar.startsWith(create("/foo/bar/"))).isTrue() // Includes trailing slash.

        // (prefix, path) => false
        assertThat(create("/foo").startsWith(foobar)).isFalse()
        assertThat(create("/").startsWith(foobar)).isFalse()

        // (path, sibling) => false
        assertThat(create("/foo/wiz").startsWith(foobar)).isFalse()
        assertThat(foobar.startsWith(create("/foo/wiz"))).isFalse()
    }

    @org.junit.Test
    fun testNormalizeUnix() {
        assertThat(create("/a/b")).isEqualTo(create("/a/b"))
        assertThat(create("/a/b/")).isEqualTo(create("/a/b"))
        assertThat(create("/a/./b")).isEqualTo(create("/a/b"))
        assertThat(create("/a/../b")).isEqualTo(create("/b"))
        assertThat(create("/..")).isEqualTo(create("/.."))
    }

    @org.junit.Test
    fun testParentOfRootIsRootUnix() {
        assertThat(create("/..")).isEqualTo(create("/"))
        assertThat(create("/../../../../../..")).isEqualTo(create("/"))
        assertThat(create("/../../../foo")).isEqualTo(create("/foo"))
    }
}
