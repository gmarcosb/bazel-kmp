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
package com.google.devtools.build.lib.vfs

import com.google.common.testing.EqualsTester
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.clock.BlazeClock.instance
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem
import com.google.devtools.common.options.testing.ConverterTester.addEqualityGroup
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Path

/**
 * Tests for [RootedPath].
 */
@RunWith(JUnit4::class)
class RootedPathTest {
    private var filesystem: FileSystem? = null
    private var root: Path? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun initializeFileSystem() {
        filesystem =
            InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)
        root = filesystem.getPath("/")
    }

    @org.junit.Test
    fun testEqualsAndHashCodeContract() {
        val pkgRoot1: Path? = root.getRelative("pkgroot1")
        val pkgRoot2: Path? = root.getRelative("pkgroot2")
        val rootedPathA1: RootedPath? =
            RootedPath.toRootedPath(Root.fromPath(pkgRoot1), PathFragment.create("foo/bar"))
        val rootedPathA2: RootedPath? =
            RootedPath.toRootedPath(Root.fromPath(pkgRoot1), PathFragment.create("foo/bar"))
        val absolutePath1: RootedPath? =
            RootedPath.toRootedPath(Root.fromPath(root), PathFragment.create("pkgroot1/foo/bar"))
        val rootedPathB1: RootedPath? =
            RootedPath.toRootedPath(Root.fromPath(pkgRoot2), PathFragment.create("foo/bar"))
        val rootedPathB2: RootedPath? =
            RootedPath.toRootedPath(Root.fromPath(pkgRoot2), PathFragment.create("foo/bar"))
        val absolutePath2: RootedPath? =
            RootedPath.toRootedPath(Root.fromPath(root), PathFragment.create("pkgroot2/foo/bar"))
        EqualsTester()
            .addEqualityGroup(rootedPathA1, rootedPathA2)
            .addEqualityGroup(rootedPathB1, rootedPathB2)
            .addEqualityGroup(absolutePath1)
            .addEqualityGroup(absolutePath2)
            .testEquals()
    }

    @org.junit.Test
    fun testGetParentDirectory() {
        val path: RootedPath = createRootedPath("root/folder", "folder1/folder2")

        var parent: RootedPath = path.getParentDirectory()
        assertThat(parent).isNotNull()
        assertThat(parent.asPath().getPathString()).isEqualTo("/root/folder/folder1")
        assertThat(parent.getRootRelativePath().getPathString()).isEqualTo("folder1")

        parent = parent.getParentDirectory()
        assertThat(parent).isNotNull()
        assertThat(parent.asPath().getPathString()).isEqualTo("/root/folder")
        assertThat(parent.getRootRelativePath().getPathString()).isEmpty()

        assertThat(parent.getParentDirectory()).isNull()
    }

    @org.junit.Test
    fun testGetParentDirectoryOfRoot() {
        val path: RootedPath = createRootedPath("root", "")
        assertThat(path.getParentDirectory()).isNull()
    }

    private fun createRootedPath(relativeRootPath: String?, relativePath: String?): RootedPath {
        return RootedPath.toRootedPath(
            Root.fromPath(root.getRelative(relativeRootPath)), PathFragment.create(relativePath)
        )
    }
}
