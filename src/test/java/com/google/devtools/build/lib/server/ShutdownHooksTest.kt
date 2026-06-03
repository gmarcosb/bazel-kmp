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
package com.google.devtools.build.lib.server

import com.google.devtools.build.lib.vfs.DigestHashFunction
import org.junit.Test

/** Test for [ShutdownHooks].  */
@RunWith(JUnit4::class)
class ShutdownHooksTest {
    private var fileSystem: FileSystem? = null

    @Before
    fun setUp() {
        fileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    }

    @Test
    @Throws(IOException::class)
    fun testDeletesRegisteredPaths() {
        val toDelete: Path = fileSystem.getPath("/some-path-to-delete")
        toDelete.createDirectoryAndParents()

        val toKeep: Path = fileSystem.getPath("/some-path-to-keep")
        toKeep.createDirectoryAndParents()

        val underTest: ShutdownHooks = ShutdownHooks.createUnregistered()
        underTest.deleteAtExit(toDelete)
        underTest.runHooks()

        assertThat(toDelete.exists()).isFalse()
        assertThat(toKeep.exists()).isTrue()
    }

    @Test
    @Throws(IOException::class)
    fun testSkipHooksIfDisabled() {
        val toDelete: Path = fileSystem.getPath("/some-path-to-delete")
        toDelete.createDirectoryAndParents()

        val underTest: ShutdownHooks = ShutdownHooks.createUnregistered()
        underTest.deleteAtExit(toDelete)
        underTest.disable()
        underTest.runHooks()

        assertThat(toDelete.exists()).isTrue()
    }
}
