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

import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException
import java.nio.file.Path

/**
 * A test for [Path].
 */
@RunWith(JUnit4::class)
class PathGetParentTest {
    private var fs: FileSystem? = null
    private var testRoot: Path? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createTestRoot() {
        fs = com.google.devtools.build.lib.vfs.util.FileSystems.getNativeFileSystem()
        testRoot =
            fs.getPath(com.google.devtools.build.lib.testutil.TestUtils.tmpDir()).getRelative("UnixPathGetParentTest")
        testRoot.createDirectoryAndParents()
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun deleteTestRoot() {
        testRoot.deleteTree() // (comment out during debugging)
    }

    private fun getParent(path: String?): Path {
        return fs.getPath(path).getParentDirectory()
    }

    @org.junit.Test
    fun testAbsoluteRootHasNoParent() {
        assertThat(getParent("/")).isNull()
    }

    @org.junit.Test
    fun testParentOfSimpleDirectory() {
        assertThat(getParent("/foo/bar").getPathString()).isEqualTo("/foo")
    }

    @org.junit.Test
    fun testParentOfDotDotInMiddleOfPathname() {
        assertThat(getParent("/foo/../bar").getPathString()).isEqualTo("/")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testGetPathDoesNormalizationWithoutIO() {
        val tmp: Path = testRoot.getChild("tmp")
        val tmpWiz: Path = tmp.getChild("wiz")

        tmp.createDirectory()

        // ln -sf /tmp /tmp/wiz
        tmpWiz.createSymbolicLink(tmp)

        assertThat(tmp.getParentDirectory()).isEqualTo(testRoot)

        assertThat(tmpWiz.getParentDirectory()).isEqualTo(tmp)

        // Under UNIX, inode(/tmp/wiz/..) == inode(/).  However getPath() does not
        // perform I/O, only string operations, so it disagrees:
        assertThat(tmp.getRelative(PathFragment.create("wiz/.."))).isEqualTo(tmp)
    }
}
