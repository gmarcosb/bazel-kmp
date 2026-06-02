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
package com.google.devtools.build.lib.bazel.repository.starlark

import com.google.devtools.build.lib.vfs.DigestHashFunction
import org.junit.Test

/** Unit tests for complex functions of [StarlarkPath].  */
@RunWith(JUnit4::class)
class StarlarkPathTest {
    private val ev: BazelEvaluationTestCase = BazelEvaluationTestCase()
    private val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    private val wd: Path = FileSystemUtils.getWorkingDirectory(fs)

    @Before
    @Throws(Exception::class)
    fun setup() {
        ev.update("wd", makePath(wd))
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkPathGetChild() {
        Truth.assertThat(ev.eval("wd.get_child()")).isEqualTo(makePath(wd))
        Truth.assertThat(ev.eval("wd.get_child('foo')")).isEqualTo(makePath(wd.getChild("foo")))
        Truth.assertThat(ev.eval("wd.get_child('a','b/c','/d/')"))
            .isEqualTo(makePath(wd.getRelative("a/b/c/d")))
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkPathStringifications() {
        Truth.assertThat(ev.eval("repr(wd)"))
            .isEqualTo(Starlark.repr(wd.toString(), StarlarkSemantics.DEFAULT))
        Truth.assertThat(ev.eval("str(wd)")).isEqualTo(wd.toString())
    }

    companion object {
        private fun makePath(path: Path): StarlarkPath {
            return StarlarkPath( /* ctx= */null, path)
        }
    }
}
