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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.vfs.DigestHashFunction

@RunWith(JUnit4::class)
class DependencySetWindowsTest {
    private val scratch: Scratch = Scratch()
    private val fileSystem: FileSystem = WindowsFileSystem(DigestHashFunction.SHA256,  /* createSymbolicLinks= */false)
    private val root: Path = fileSystem.getPath("C:/")

    private fun newDependencySet(): DependencySet {
        return DependencySet(root)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_windowsPaths() {
        val dotd: Path =
            scratch.file(
                "/tmp/foo.d",
                "bazel-out/hello-lib/cpp/hello-lib.o: \\",
                " cpp/hello-lib.cc cpp/hello-lib.h c:\\mingw\\include\\stdio.h \\",
                " c:\\mingw\\include\\_mingw.h \\",
                " c:\\mingw\\lib\\gcc\\mingw32\\4.8.1\\include\\stdarg.h"
            )

        val expected: MutableSet<Path?> =
            com.google.common.collect.Sets.newHashSet<E?>(
                root.getRelative("cpp/hello-lib.cc"),
                root.getRelative("cpp/hello-lib.h"),
                fileSystem.getPath("C:/mingw/include/stdio.h"),
                fileSystem.getPath("C:/mingw/include/_mingw.h"),
                fileSystem.getPath("C:/mingw/lib/gcc/mingw32/4.8.1/include/stdarg.h")
            )

        assertThat(newDependencySet().read(dotd).getDependencies()).containsExactlyElementsIn(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_windowsPathsWithSpaces() {
        val dotd: Path =
            scratch.file(
                "/tmp/foo.d",
                "bazel-out/hello-lib/cpp/hello-lib.o: \\",
                "C:\\Program\\ Files\\ (x86)\\LLVM\\stddef.h"
            )
        assertThat(newDependencySet().read(dotd).getDependencies())
            .containsExactlyElementsIn(
                com.google.common.collect.Sets.newHashSet(fileSystem.getPath("C:/Program Files (x86)/LLVM/stddef.h"))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dotDParser_mixedWindowsPaths() {
        // This is (slightly simplified) actual output from clang. Yes, clang will happily mix
        // forward slashes and backslashes in a single path, not to mention using backslashes as
        // separators next to backslashes as escape characters.
        val dotd: Path =
            scratch.file(
                "/tmp/foo.d",
                "bazel-out/hello-lib/cpp/hello-lib.o: \\",
                "cpp/hello-lib.cc cpp/hello-lib.h /mingw/include\\stdio.h \\",
                "/mingw/include\\_mingw.h \\",
                "C:\\Program\\ Files\\ (x86)\\LLVM\\bin\\..\\lib\\clang\\3.5.0\\include\\stddef.h \\",
                "C:\\Program\\ Files\\ (x86)\\LLVM\\bin\\..\\lib\\clang\\3.5.0\\include\\stdarg.h"
            )

        val expected: MutableSet<Path?> =
            com.google.common.collect.Sets.newHashSet<E?>(
                root.getRelative("cpp/hello-lib.cc"),
                root.getRelative("cpp/hello-lib.h"),
                fileSystem.getPath("C:/fake/msys/mingw/include/stdio.h"),
                fileSystem.getPath("C:/fake/msys/mingw/include/_mingw.h"),
                fileSystem.getPath("C:/Program Files (x86)/LLVM/lib/clang/3.5.0/include/stddef.h"),
                fileSystem.getPath("C:/Program Files (x86)/LLVM/lib/clang/3.5.0/include/stdarg.h")
            )

        assertThat(newDependencySet().read(dotd).getDependencies()).containsExactlyElementsIn(expected)
    }
}
