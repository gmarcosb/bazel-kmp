// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.vfs.DigestHashFunction

/** Tests for [LocationPrinter] static methods.  */
@RunWith(JUnit4::class)
class LocationPrinterTest {
    private val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

    @get:org.junit.Test
    val relativeLocationString_PathIsAlreadyRelative: Unit
        get() {
            assertThat(
                LocationPrinter.getRelativeLocationString(
                    net.starlark.java.syntax.Location.fromFileLineColumn("relative/path", 4, 2),
                    PathFragment.create("/this/is/the/workspace"),
                    com.google.common.collect.ImmutableList.of<E?>(
                        Root.fromPath(fileSystem.getPath("/this/is/a/package/path/root"))
                    )
                )
            )
                .isEqualTo("relative/path:4:2")
        }

    @get:org.junit.Test
    val relativeLocationString_PathIsAbsoluteAndWorkspaceIsNull: Unit
        get() {
            assertThat(
                LocationPrinter.getRelativeLocationString(
                    net.starlark.java.syntax.Location.fromFileLineColumn("/absolute/path", 4, 2),
                    null,
                    com.google.common.collect.ImmutableList.of<E?>(
                        Root.fromPath(fileSystem.getPath("/this/is/a/package/path/root"))
                    )
                )
            )
                .isEqualTo("/absolute/path:4:2")
        }

    @get:org.junit.Test
    val relativeLocationString_PathIsAbsoluteButNotUnderWorkspaceOrPackagePathRoots: Unit
        get() {
            assertThat(
                LocationPrinter.getRelativeLocationString(
                    net.starlark.java.syntax.Location.fromFileLineColumn("/absolute/path", 4, 2),
                    PathFragment.create("/this/is/the/workspace"),
                    com.google.common.collect.ImmutableList.of<E?>(
                        Root.fromPath(fileSystem.getPath("/this/is/a/package/path/root"))
                    )
                )
            )
                .isEqualTo("/absolute/path:4:2")
        }

    @get:org.junit.Test
    val relativeLocationString_PathIsAbsoluteAndUnderWorkspace: Unit
        get() {
            assertThat(
                LocationPrinter.getRelativeLocationString(
                    net.starlark.java.syntax.Location.fromFileLineColumn("/this/is/the/workspace/blah.txt", 4, 2),
                    PathFragment.create("/this/is/the/workspace"),
                    com.google.common.collect.ImmutableList.of<E?>(
                        Root.fromPath(fileSystem.getPath("/this/is/a/package/path/root"))
                    )
                )
            )
                .isEqualTo("blah.txt:4:2")
        }

    @get:org.junit.Test
    val relativeLocationString_PathIsAbsoluteAndUnderPackagePathRoot: Unit
        get() {
            assertThat(
                LocationPrinter.getRelativeLocationString(
                    net.starlark.java.syntax.Location.fromFileLineColumn(
                        "/this/is/a/package/path/root3/blah.txt",
                        4,
                        2
                    ),
                    PathFragment.create("/this/is/the/workspace"),
                    com.google.common.collect.ImmutableList.of<E?>(
                        Root.fromPath(fileSystem.getPath("/this/is/a/package/path/root1")),
                        Root.fromPath(fileSystem.getPath("/this/is/a/package/path/root2")),
                        Root.fromPath(fileSystem.getPath("/this/is/a/package/path/root3"))
                    )
                )
            )
                .isEqualTo("blah.txt:4:2")
        }
}
