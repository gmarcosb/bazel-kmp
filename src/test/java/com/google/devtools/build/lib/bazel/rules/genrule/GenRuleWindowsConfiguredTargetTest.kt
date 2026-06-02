// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.rules.genrule

import com.google.devtools.build.lib.actions.Artifact
import org.junit.Test
import java.util.regex.Pattern

/** Tests of [BazelGenRule] on Windows.  */
@RunWith(JUnit4::class)
class GenRuleWindowsConfiguredTargetTest : BuildViewTestCase() {
    @Before
    @Throws(Exception::class)
    fun assumeBazel() {
        // The cmd_{bash,bat,ps} attributes don't exist in Blaze.
        Assume.assumeTrue(analysisMock.isThisBazel())
    }

    @Before
    @Throws(Exception::class)
    fun createWindowsPlatform() {
        scratch.file(
            "platforms/BUILD",
            "platform(name = 'windows', constraint_values = ['@platforms//os:windows'])"
        )
        useConfiguration("--host_platform=//platforms:windows")
    }

    @Test
    @Throws(Exception::class)
    fun testCmdBatchIsPreferred() {
        scratch.file(
            "genrule1/BUILD",
            """
        genrule(
            name = "hello_world",
            outs = ["message.txt"],
            cmd = 'echo "Hello, default cmd." >${'$'}(location message.txt)',
            cmd_bash = 'echo "Hello, Bash cmd." >${'$'}(location message.txt)',
            cmd_bat = 'echo "Hello, Batch cmd." >${'$'}(location message.txt)',
        )
        
        """.trimIndent()
        )

        val messageArtifact: Artifact = getFileConfiguredTarget("//genrule1:message.txt").getArtifact()
        val shellAction: SpawnAction? = getGeneratingAction(messageArtifact) as SpawnAction?

        assertThat(shellAction).isNotNull()
        assertThat(shellAction.getOutputs()).containsExactly(messageArtifact)

        val expected = "echo \"Hello, Batch cmd.\" >" + getWindowsPath(messageArtifact)
        assertThat(shellAction.getArguments().get(0)).isEqualTo("cmd.exe")
        val last: Int = shellAction.getArguments().size() - 1
        assertThat(shellAction.getArguments().get(last - 1)).isEqualTo("/c")
        assertThat(shellAction.getArguments().get(last)).isEqualTo(expected)
    }

    @Test
    @Throws(Exception::class)
    fun testCmdPsIsPreferred() {
        scratch.file(
            "genrule1/BUILD",
            """
        genrule(
            name = "hello_world",
            outs = ["message.txt"],
            cmd = 'echo "Hello, default cmd." >${'$'}(location message.txt)',
            cmd_bash = 'echo "Hello, Bash cmd." >${'$'}(location message.txt)',
            cmd_bat = 'echo "Hello, Batch cmd." >${'$'}(location message.txt)',
            cmd_ps = 'echo "Hello, Powershell cmd." >${'$'}(location message.txt)',
        )
        
        """.trimIndent()
        )

        val messageArtifact: Artifact = getFileConfiguredTarget("//genrule1:message.txt").getArtifact()
        val shellAction: SpawnAction? = getGeneratingAction(messageArtifact) as SpawnAction?

        assertThat(shellAction).isNotNull()
        assertThat(shellAction.getOutputs()).containsExactly(messageArtifact)

        val expected = "echo \"Hello, Powershell cmd.\" >" + messageArtifact.getExecPathString()
        assertThat(shellAction.getArguments().get(0)).isEqualTo("powershell.exe")
        assertThat(shellAction.getArguments().get(1)).isEqualTo("/c")
        assertPowershellCommandEquals(expected, shellAction.getArguments().get(2))
    }

    @Test
    @Throws(Exception::class)
    fun testScriptFileIsUsedForBatchCmd() {
        scratch.file(
            "genrule1/BUILD",
            "genrule(name = 'hello_world',",
            "outs = ['message.txt'],",
            "cmd_bat  = ' && '.join([\"echo \\\"Hello, Batch cmd, %s.\\\" >$(location message.txt)\" %"
                    + " i for i in range(1, 1000)]),)"
        )
        useConfiguration(
            "--platforms=//platforms:windows",
            "--host_platform=//platforms:windows",
            "--experimental_platform_in_output_dir"
        )

        val messageArtifact: Artifact? = getFileConfiguredTarget("//genrule1:message.txt").getArtifact()
        val shellAction: SpawnAction? = getGeneratingAction(messageArtifact) as SpawnAction?

        assertThat(shellAction).isNotNull()
        assertThat(shellAction.getOutputs()).containsExactly(messageArtifact)

        val expected = "bazel-out\\windows-fastbuild\\bin\\genrule1\\hello_world.genrule_script.bat"
        assertThat(shellAction.getArguments().get(0)).isEqualTo("cmd.exe")
        val last: Int = shellAction.getArguments().size() - 1
        assertThat(shellAction.getArguments().get(last - 1)).isEqualTo("/c")
        assertPowershellCommandEquals(expected, shellAction.getArguments().get(last))
    }

    @Test
    @Throws(Exception::class)
    fun testScriptFileIsUsedForPowershellCmd() {
        scratch.file(
            "genrule1/BUILD",
            "genrule(name = 'hello_world',",
            "outs = ['message.txt'],",
            "cmd_ps  = '; '.join([\"echo \\\"Hello, Powershell cmd, %s.\\\" >$(location message.txt)\""
                    + " % i for i in range(1, 1000)]),)"
        )
        useConfiguration(
            "--platforms=//platforms:windows",
            "--host_platform=//platforms:windows",
            "--experimental_platform_in_output_dir"
        )

        val messageArtifact: Artifact? = getFileConfiguredTarget("//genrule1:message.txt").getArtifact()
        val shellAction: SpawnAction? = getGeneratingAction(messageArtifact) as SpawnAction?

        assertThat(shellAction).isNotNull()
        assertThat(shellAction.getOutputs()).containsExactly(messageArtifact)

        val expected =
            ".\\bazel-out\\windows-fastbuild\\bin\\genrule1\\hello_world.genrule_script.ps1"
        assertThat(shellAction.getArguments().get(0)).isEqualTo("powershell.exe")
        assertThat(shellAction.getArguments().get(1)).isEqualTo("/c")
        assertPowershellCommandEquals(expected, shellAction.getArguments().get(2))
    }

    @Test
    @Throws(Exception::class)
    fun testCmdBashIsPreferred() {
        scratch.file(
            "genrule1/BUILD",
            """
        genrule(
            name = "hello_world",
            outs = ["message.txt"],
            cmd = 'echo "Hello, default cmd." >${'$'}(location message.txt)',
            cmd_bash = 'echo "Hello, Bash cmd." >${'$'}(location message.txt)',
        )
        
        """.trimIndent()
        )

        val messageArtifact: Artifact = getFileConfiguredTarget("//genrule1:message.txt").getArtifact()
        val shellAction: SpawnAction? = getGeneratingAction(messageArtifact) as SpawnAction?

        assertThat(shellAction).isNotNull()
        assertThat(shellAction.getOutputs()).containsExactly(messageArtifact)

        val expected = "echo \"Hello, Bash cmd.\" >" + messageArtifact.getExecPathString()
        assertThat(shellAction.getArguments().get(0)).isEqualTo("c:/msys64/usr/bin/bash.exe")
        assertThat(shellAction.getArguments().get(1)).isEqualTo("-c")
        assertBashCommandEquals(expected, shellAction.getArguments().get(2))
    }

    @Test
    @Throws(Exception::class)
    fun testMissingCmdAttributeError() {
        checkError(
            "foo",
            "bar",
            "missing value for `cmd` attribute, you can also set `cmd_ps` or `cmd_bat` on"
                    + " Windows and `cmd_bash` on other platforms.",
            "genrule(name='bar'," + "      srcs = []," + "      outs=['out'])"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testMissingCmdAttributeErrorOnNonWindowsPlatform() {
        scratch.file(
            "newplatforms/BUILD",
            "platform(name = 'nonwindows', constraint_values = ['@platforms//os:linux'])"
        )
        useConfiguration("--host_platform=//newplatforms:nonwindows")

        checkError(
            "foo",
            "bar",
            "missing value for `cmd` attribute, you can also set `cmd_ps` or `cmd_bat` on"
                    + " Windows and `cmd_bash` on other platforms.",
            ("genrule(name='bar',"
                    + "      srcs = [],"
                    + "      outs=['out'],"
                    + "      cmd_bat='echo hello > $(@)')")
        )
    }

    companion object {
        private val POWERSHELL_COMMAND_PATTERN: Pattern = Pattern.compile(".*'utf8';\\s+(?<command>.*)")

        private val BASH_COMMAND_PATTERN: Pattern = Pattern.compile(".*/genrule-setup.sh;\\s+(?<command>.*)")

        private fun assertCommandEquals(expected: String?, command: String?, pattern: Pattern) {
            // Ensure the command after the genrule setup is correct.
            var command = command
            val m = pattern.matcher(command)
            if (m.matches()) {
                command = m.group("command")
            }
            Truth.assertThat(command).isEqualTo(expected)
        }

        private fun assertPowershellCommandEquals(expected: String?, command: String?) {
            assertCommandEquals(expected, command, POWERSHELL_COMMAND_PATTERN)
        }

        private fun assertBashCommandEquals(expected: String?, command: String?) {
            assertCommandEquals(expected, command, BASH_COMMAND_PATTERN)
        }

        private fun getWindowsPath(artifact: Artifact): String {
            return artifact.getExecPathString().replace('/', '\\')
        }
    }
}
