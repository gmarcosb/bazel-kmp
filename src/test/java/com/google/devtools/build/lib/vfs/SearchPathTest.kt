// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase.write
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Path

@RunWith(JUnit4::class)
class SearchPathTest {
    private val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNull() {
        assertThat(SearchPath.parse(fs, null)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasic() {
        fs.getPath("/bin").createDirectory()
        val searchPath: MutableList<Path?> =
            com.google.common.collect.ImmutableList.of<E?>(fs.getPath("/"), fs.getPath("/bin"))
        assertThat(SearchPath.parse(fs, "/:/bin")).isEqualTo(searchPath)
        assertThat(SearchPath.parse(fs, ".:/:/bin")).isEqualTo(searchPath)

        fs.getPath("/bin/exe").getOutputStream().use { out ->
            out.write(ByteArray(5))
        }
        assertThat(SearchPath.which(searchPath, "exe")).isNull()

        fs.getPath("/bin/exe").setExecutable(true)
        assertThat(SearchPath.which(searchPath, "exe")).isEqualTo(fs.getPath("/bin/exe"))

        assertThat(SearchPath.which(searchPath, "bin/exe")).isNull()
        assertThat(SearchPath.which(searchPath, "/bin/exe")).isNull()
    }
}
