// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime.commands

import com.google.common.truth.Truth
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class RunCommandLineTest {
    @org.junit.Test
    fun linuxFormatter_formatArgv_requiresShExecutable() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                LinuxFormatter()
                    .formatArgv( /* shExecutable= */
                        null, "run under prefix", com.google.common.collect.ImmutableList.of<E?>("argv")
                    )
            })
    }

    @org.junit.Test
    fun windowsFormatter_formatArgv_runUnderRequiresShExecutable() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                WindowsFormatter()
                    .formatArgv( /* shExecutable= */
                        null, "run under prefix", com.google.common.collect.ImmutableList.of<E?>("argv")
                    )
            })
    }

    @org.junit.Test
    fun linuxFormatter_formatArgv_returnsEscapedCommandLine() {
        val underTest: RunCommandLine.LinuxFormatter = LinuxFormatter()
        val result: com.google.common.collect.ImmutableList<String?>? =
            underTest.formatArgv(
                "/bin/bash",  /* runUnderPrefix= */
                null,
                com.google.common.collect.ImmutableList.of<E?>("executable", "argv1", "arg w spaces")
            )
        Truth.assertThat(result)
            .containsExactly("/bin/bash", "-c", "executable argv1 'arg w spaces'")
            .inOrder()
    }

    @org.junit.Test
    fun windowsFormatter_formatArgv_returnsEscapedCommandLine() {
        val underTest: RunCommandLine.WindowsFormatter = WindowsFormatter()
        val result: com.google.common.collect.ImmutableList<String?>? =
            underTest.formatArgv( /* shExecutable= */
                null,  /* runUnderPrefix= */
                null,
                com.google.common.collect.ImmutableList.of<E?>("C:/unescaped executable", "argv1", "arg w spaces")
            )
        Truth.assertThat(result)
            .containsExactly("C:/unescaped executable", "argv1", "\"arg w spaces\"")
            .inOrder()
    }

    @org.junit.Test
    fun linuxFormatter_formatArgv_runUnderPrefixPrependedToEscapedCommandLine() {
        val underTest: RunCommandLine.LinuxFormatter = LinuxFormatter()
        val result: com.google.common.collect.ImmutableList<String?>? =
            underTest.formatArgv(
                "/bin/bash",
                "unescaped run-under prefix &&",
                com.google.common.collect.ImmutableList.of<E?>("executable", "argv1", "arg w spaces")
            )
        Truth.assertThat(result)
            .containsExactly(
                "/bin/bash", "-c", "unescaped run-under prefix && executable argv1 'arg w spaces'"
            )
            .inOrder()
    }

    @org.junit.Test
    fun windowsFormatter_formatArgv_runUnderPrefixPrependedToEscapedCommandLine() {
        val underTest: RunCommandLine.WindowsFormatter = WindowsFormatter()
        val result: com.google.common.collect.ImmutableList<String?>? =
            underTest.formatArgv(
                "unescaped /bin/bash",
                "unescaped run-under prefix &&",
                com.google.common.collect.ImmutableList.of<E?>("C:/unescaped executable", "argv1", "arg w spaces")
            )
        Truth.assertThat(result)
            .containsExactly(
                "unescaped /bin/bash",
                "-c",
                "\"unescaped run-under prefix && 'C:/unescaped executable' argv1 'arg w spaces'\""
            )
            .inOrder()
    }

    @org.junit.Test
    fun linuxFormatter_formatScriptPathCommandLine_returnsConcatenatedEscapedCommand() {
        val underTest: RunCommandLine.LinuxFormatter = LinuxFormatter()
        val result: String? =
            underTest.getScriptForm(
                "/bin/bash",
                "workingDir",
                com.google.common.collect.ImmutableSortedSet.< E > of < E ? > ("UNSET_ME", "UNSET_ME_TOO"
            ),
        com.google.common.collect.ImmutableSortedMap.< K, V>of<K?, V?>("ENV_VAR", "val", "ENV_VAR_WITH_SPACES", "foo bar"),  /* runUnderPrefix= */
        null,
        com.google.common.collect.ImmutableList.of<E?>("executable", "argv1", "arg w spaces"))
        Truth.assertThat(result)
            .isEqualTo(
                """
            #!/bin/bash
            cd workingDir && \
              exec env \
                -u UNSET_ME \
                -u UNSET_ME_TOO \
                ENV_VAR=val \
                ENV_VAR_WITH_SPACES='foo bar' \
              executable argv1 'arg w spaces' "${'$'}@"
            
            """.trimIndent()
            )
    }

    @org.junit.Test
    fun linuxFormatter_formatScriptPathCommandLine_runUnderPrefixPrependedToEscapedCommand() {
        val underTest: RunCommandLine.LinuxFormatter = LinuxFormatter()
        val result: String? =
            underTest.getScriptForm(
                "/bin/bash",
                "workingDir",
                com.google.common.collect.ImmutableSortedSet.< E > of < E ? > ("UNSET_ME", "UNSET_ME_TOO"
            ),
        com.google.common.collect.ImmutableSortedMap.< K, V>of<K?, V?>("ENV_VAR", "val", "ENV_VAR_WITH_SPACES", "foo bar"),
        "unescaped run-under prefix &&",
        com.google.common.collect.ImmutableList.of<E?>("executable", "argv1", "arg w spaces"))
        Truth.assertThat(result)
            .isEqualTo(
                """
#!/bin/bash
cd workingDir && \
  exec env \
    -u UNSET_ME \
    -u UNSET_ME_TOO \
    ENV_VAR=val \
    ENV_VAR_WITH_SPACES='foo bar' \
  /bin/bash -c 'unescaped run-under prefix && executable argv1 '\''arg w spaces'\''' "${'$'}@"

""".trimIndent()
            )
    }

    @org.junit.Test
    fun windowsFormatter_formatScriptPathCommandLine_runUnderPrefixPrependedToEscapedCommand() {
        val underTest: RunCommandLine.WindowsFormatter = WindowsFormatter()
        val result: String? =
            underTest.getScriptForm(
                "/bin/bash",
                "workingDir",
                com.google.common.collect.ImmutableSortedSet.< E > of < E ? > ("UNSET_ME", "UNSET_ME_TOO"
            ),
        com.google.common.collect.ImmutableSortedMap.< K, V>of<K?, V?>("ENV_VAR", "val", "ENV_VAR_WITH_SPACES", "foo bar"),  /* runUnderPrefix= */

        "echo hello &&",
        com.google.common.collect.ImmutableList.of<E?>("C:/executable", "argv1", "arg w spaces"))
        Truth.assertThat(result)
            .isEqualTo(
                """
            @echo off
            cd /d workingDir
              SET UNSET_ME=
              SET UNSET_ME_TOO=
              SET ENV_VAR=val
              SET ENV_VAR_WITH_SPACES=foo bar
              /bin/bash -c 'echo hello && C:\executable argv1 "arg w spaces"' %*
            
            """.trimIndent()
            )
    }

    @org.junit.Test
    fun windowsFormatter_formatScriptPathCommandLine_returnsConcatenatedEscapedCommand() {
        val underTest: RunCommandLine.WindowsFormatter = WindowsFormatter()
        val result: String? =
            underTest.getScriptForm(
                "/bin/bash",
                "workingDir",
                com.google.common.collect.ImmutableSortedSet.< E > of < E ? > ("UNSET_ME", "UNSET_ME_TOO"
            ),
        com.google.common.collect.ImmutableSortedMap.< K, V>of<K?, V?>("ENV_VAR", "val", "ENV_VAR_WITH_SPACES", "foo bar"),  /* runUnderPrefix= */
        null,
        com.google.common.collect.ImmutableList.of<E?>("C:/executable", "argv1", "arg w spaces"))
        Truth.assertThat(result)
            .isEqualTo(
                """
            @echo off
            cd /d workingDir
              SET UNSET_ME=
              SET UNSET_ME_TOO=
              SET ENV_VAR=val
              SET ENV_VAR_WITH_SPACES=foo bar
              C:\executable argv1 "arg w spaces" %*
            
            """.trimIndent()
            )
    }
}
