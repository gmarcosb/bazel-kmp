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
package com.google.devtools.build.lib.bazel.repository.decompressor

import com.google.devtools.build.lib.clock.BlazeClock
import com.google.devtools.build.lib.util.OS
import com.google.devtools.build.lib.vfs.DigestHashFunction
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Tests [StripPrefixedPath].
 */
@RunWith(JUnit4::class)
class StripPrefixedPathTest {
    @Test
    fun testStrip() {
        var result =
            StripPrefixedPath.maybeDeprefix("foo/bar".toByteArray(StandardCharsets.UTF_8), Optional.of<T?>("foo"))
        assertThat(PathFragment.create("bar")).isEqualTo(result.pathFragment)
        Truth.assertThat(result.foundPrefix()).isTrue()
        Truth.assertThat(result.skip()).isFalse()

        result = StripPrefixedPath.maybeDeprefix("foo".toByteArray(StandardCharsets.UTF_8), Optional.of<T?>("foo"))
        Truth.assertThat(result.skip()).isTrue()

        result = StripPrefixedPath.maybeDeprefix("bar/baz".toByteArray(StandardCharsets.UTF_8), Optional.of<T?>("foo"))
        Truth.assertThat(result.foundPrefix()).isFalse()

        result = StripPrefixedPath.maybeDeprefix("foof/bar".toByteArray(StandardCharsets.UTF_8), Optional.of<T?>("foo"))
        Truth.assertThat(result.foundPrefix()).isFalse()
    }

    @Test
    fun testAbsolute() {
        var result =
            StripPrefixedPath.maybeDeprefix("/foo/bar".toByteArray(StandardCharsets.UTF_8), Optional.empty<T?>())
        assertThat(result.pathFragment).isEqualTo(PathFragment.create("foo/bar"))

        result =
            StripPrefixedPath.maybeDeprefix("///foo/bar/baz".toByteArray(StandardCharsets.UTF_8), Optional.empty<T?>())
        assertThat(result.pathFragment).isEqualTo(PathFragment.create("foo/bar/baz"))

        result =
            StripPrefixedPath.maybeDeprefix("/foo/bar/baz".toByteArray(StandardCharsets.UTF_8), Optional.of<T?>("/foo"))
        assertThat(result.pathFragment).isEqualTo(PathFragment.create("bar/baz"))
    }

    @Test
    fun testWindowsAbsolute() {
        if (OS.getCurrent() != OS.WINDOWS) {
            return
        }
        val result =
            StripPrefixedPath.maybeDeprefix("c:/foo/bar".toByteArray(StandardCharsets.UTF_8), Optional.empty<T?>())
        assertThat(result.pathFragment).isEqualTo(PathFragment.create("foo/bar"))
    }

    @Test
    fun testNormalize() {
        var result =
            StripPrefixedPath.maybeDeprefix("../bar".toByteArray(StandardCharsets.UTF_8), Optional.empty<T?>())
        assertThat(result.pathFragment).isEqualTo(PathFragment.create("../bar"))

        result = StripPrefixedPath.maybeDeprefix("foo/../baz".toByteArray(StandardCharsets.UTF_8), Optional.empty<T?>())
        assertThat(result.pathFragment).isEqualTo(PathFragment.create("baz"))

        result =
            StripPrefixedPath.maybeDeprefix("foo/../baz".toByteArray(StandardCharsets.UTF_8), Optional.of<T?>("foo"))
        assertThat(result.pathFragment).isEqualTo(PathFragment.create("baz"))
    }

    @Test
    fun testDeprefixSymlink() {
        val fileSystem: InMemoryFileSystem =
            InMemoryFileSystem(BlazeClock.instance(), DigestHashFunction.SHA256)

        val relativeNoPrefix: PathFragment? =
            StripPrefixedPath.maybeDeprefixSymlink(
                "a/b".toByteArray(StandardCharsets.UTF_8), Optional.empty<T?>(), fileSystem.getPath("/usr")
            )
        // there is no attempt to get absolute path for the relative symlinks target path
        assertThat(relativeNoPrefix).isEqualTo(PathFragment.create("a/b"))

        val absoluteNoPrefix: PathFragment? =
            StripPrefixedPath.maybeDeprefixSymlink(
                "/a/b".toByteArray(StandardCharsets.UTF_8), Optional.empty<T?>(), fileSystem.getPath("/usr")
            )
        assertThat(absoluteNoPrefix).isEqualTo(PathFragment.create("/usr/a/b"))

        val absolutePrefix: PathFragment? =
            StripPrefixedPath.maybeDeprefixSymlink(
                "/root/a/b".toByteArray(StandardCharsets.UTF_8), Optional.of<T?>("root"), fileSystem.getPath("/usr")
            )
        assertThat(absolutePrefix).isEqualTo(PathFragment.create("/usr/a/b"))

        val relativePrefix: PathFragment? =
            StripPrefixedPath.maybeDeprefixSymlink(
                "root/a/b".toByteArray(StandardCharsets.UTF_8), Optional.of<T?>("root"), fileSystem.getPath("/usr")
            )
        // there is no attempt to get absolute path for the relative symlinks target path
        assertThat(relativePrefix).isEqualTo(PathFragment.create("a/b"))
    }
}
