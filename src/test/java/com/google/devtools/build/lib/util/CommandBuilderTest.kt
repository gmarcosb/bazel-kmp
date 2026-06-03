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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for the [CommandBuilder] class.  */
@RunWith(JUnit4::class)
class CommandBuilderTest {
    private fun linuxBuilder(): CommandBuilder {
        return CommandBuilder(
            com.google.devtools.build.lib.util.OS.LINUX,
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        ).useTempDir()
    }

    private fun winBuilder(): CommandBuilder {
        return CommandBuilder(
            com.google.devtools.build.lib.util.OS.WINDOWS,
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        ).useTempDir()
    }

    private fun assertArgv(builder: CommandBuilder, vararg expected: String?) {
        assertThat(builder.build().getArguments()).containsExactlyElementsIn(expected).inOrder()
    }

    private fun assertWinCmdArgv(builder: CommandBuilder, expected: String?) {
        assertArgv(builder, "CMD.EXE", "/S", "/E:ON", "/V:ON", "/D", "/C", expected)
    }

    private fun assertFailure(builder: CommandBuilder, expected: String?) {
        val e: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { builder.build() })
        Truth.assertThat(e).hasMessageThat().isEqualTo(expected)
    }

    @org.junit.Test
    fun linuxBuilderTest() {
        assertArgv(linuxBuilder().addArg("abc"), "abc")
        assertArgv(linuxBuilder().addArg("abc def"), "abc def")
        assertArgv(linuxBuilder().addArgs("abc", "def"), "abc", "def")
        assertArgv(linuxBuilder().addArgs(com.google.common.collect.ImmutableList.of<E?>("abc", "def")), "abc", "def")
        assertArgv(linuxBuilder().addArg("abc").useShell(true), "/bin/sh", "-c", "abc")
        assertArgv(linuxBuilder().addArg("abc def").useShell(true), "/bin/sh", "-c", "abc def")
        assertArgv(linuxBuilder().addArgs("abc", "def").useShell(true), "/bin/sh", "-c", "abc def")
        assertArgv(
            linuxBuilder().addArgs("/bin/sh", "-c", "abc").useShell(true), "/bin/sh", "-c", "abc"
        )
        assertArgv(linuxBuilder().addArgs("/bin/sh", "-c"), "/bin/sh", "-c")
        assertArgv(linuxBuilder().addArgs("/bin/bash", "-c"), "/bin/bash", "-c")
        assertArgv(linuxBuilder().addArgs("/bin/sh", "-c").useShell(true), "/bin/sh", "-c")
        assertArgv(linuxBuilder().addArgs("/bin/bash", "-c").useShell(true), "/bin/bash", "-c")
    }

    @org.junit.Test
    fun windowsBuilderTest() {
        assertArgv(winBuilder().addArg("abc.exe"), "abc.exe")
        assertArgv(winBuilder().addArg("abc.exe -o"), "abc.exe -o")
        assertArgv(winBuilder().addArg("ABC.EXE"), "ABC.EXE")
        assertWinCmdArgv(winBuilder().addArg("abc def.exe"), "abc def.exe")
        assertArgv(winBuilder().addArgs("abc.exe", "def"), "abc.exe", "def")
        assertArgv(
            winBuilder().addArgs(com.google.common.collect.ImmutableList.of<E?>("abc.exe", "def")),
            "abc.exe",
            "def"
        )
        assertWinCmdArgv(winBuilder().addArgs("abc.exe", "def").useShell(true), "abc.exe def")
        assertWinCmdArgv(winBuilder().addArg("abc"), "abc")
        assertWinCmdArgv(winBuilder().addArgs("abc", "def"), "abc def")
        assertWinCmdArgv(winBuilder().addArgs("/bin/sh", "-c", "abc", "def"), "abc def")
        assertWinCmdArgv(winBuilder().addArgs("/bin/sh", "-c"), "")
        assertWinCmdArgv(winBuilder().addArgs("/bin/bash", "-c"), "")
        assertWinCmdArgv(winBuilder().addArgs("/bin/sh", "-c").useShell(true), "")
        assertWinCmdArgv(winBuilder().addArgs("/bin/bash", "-c").useShell(true), "")
    }

    @org.junit.Test
    fun failureScenarios() {
        assertFailure(linuxBuilder(), "At least one argument is expected")
        assertFailure(
            CommandBuilder(
                com.google.devtools.build.lib.util.OS.UNKNOWN,
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            ).useTempDir().addArg("a"),
            "Unidentified operating system"
        )
        assertFailure(
            CommandBuilder(
                com.google.devtools.build.lib.util.OS.LINUX,
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            ).addArg("a"),
            "Working directory must be set"
        )
    }
}
