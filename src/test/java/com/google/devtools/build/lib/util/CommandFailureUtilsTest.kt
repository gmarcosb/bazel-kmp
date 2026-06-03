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

import com.google.devtools.build.lib.analysis.platform.PlatformInfo

/** Tests for [CommandFailureUtils].  */
@RunWith(JUnit4::class)
class CommandFailureUtilsTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun describeCommandFailure() {
        val target: Label? = Label.parseCanonicalUnchecked("//foo:bar")
        val args = arrayOfNulls<String>(3)
        args[0] = "/bin/sh"
        args[1] = "-c"
        args[2] = "echo Some errors 1>&2; echo Some output; exit 42"
        val env: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
        env.put("FOO", "foo")
        env.put("PATH", "/usr/bin:/bin:/sbin")
        val cwd: String? = null
        val executionPlatform: PlatformInfo =
            PlatformInfo.builder().setLabel(Label.parseCanonicalUnchecked("//platform:exec")).build()
        val message: String? =
            CommandFailureUtils.describeCommandFailure(
                false,
                "Mnemonic",
                java.util.Arrays.< T > asList < T ? > (args),
                env,
                cwd,
                "cfg12345",
                "target " + target,
                executionPlatform.label(),
                "local"
            )
        Truth.assertThat(message)
            .isEqualTo(
                "sh failed: error executing Mnemonic command (from target //foo:bar) "
                        + "/bin/sh -c 'echo Some errors 1>&2; echo Some output; exit 42'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun describeCommandFailure_verbose() {
        val target: Label? = Label.parseCanonicalUnchecked("//foo:bar")
        val args = arrayOfNulls<String>(3)
        args[0] = "/bin/sh"
        args[1] = "-c"
        args[2] = "echo Some errors 1>&2; echo Some output; exit 42"
        val env: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
        env.put("FOO", "foo")
        env.put("PATH", "/usr/bin:/bin:/sbin")
        val cwd: String? = null
        val executionPlatform: PlatformInfo =
            PlatformInfo.builder().setLabel(Label.parseCanonicalUnchecked("//platform:exec")).build()
        val message: String? =
            CommandFailureUtils.describeCommandFailure(
                true,
                "Mnemonic",
                java.util.Arrays.< T > asList < T ? > (args),
                env,
                cwd,
                "cfg12345",
                "target " + target,
                executionPlatform.label(),
                "local"
            )
        Truth.assertThat(message)
            .isEqualTo(
                """
            sh failed: error executing Mnemonic command (from target //foo:bar) 
              (exec env - \
                FOO=foo \
                PATH=/usr/bin:/bin:/sbin \
              /bin/sh -c 'echo Some errors 1>&2; echo Some output; exit 42')
            # Configuration: cfg12345
            # Execution platform: //platform:exec
            # Runner: local
            """.trimIndent()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun describeCommandFailure_longMessage() {
        val target: Label? = Label.parseCanonicalUnchecked("//foo:bar")
        val args = arrayOfNulls<String>(40)
        args[0] = "some_command"
        for (i in 1..<args.size) {
            args[i] = "arg" + i
        }
        args[7] = "with spaces" // Test embedded spaces in argument.
        args[9] = "*" // Test shell meta characters.
        val env: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
        env.put("FOO", "foo")
        env.put("PATH", "/usr/bin:/bin:/sbin")
        val cwd = "/my/working/directory"
        val executionPlatform: PlatformInfo =
            PlatformInfo.builder().setLabel(Label.parseCanonicalUnchecked("//platform:exec")).build()
        val message: String? =
            CommandFailureUtils.describeCommandFailure(
                false,
                "Mnemonic",
                java.util.Arrays.< T > asList < T ? > (args),
                env,
                cwd,
                "cfg12345",
                "target " + target,
                executionPlatform.label(),
                "local"
            )
        Truth.assertThat(message)
            .isEqualTo(
                ("some_command failed: error executing Mnemonic command (from target //foo:bar) "
                        + "some_command arg1 arg2 arg3 arg4 arg5 arg6 'with spaces' arg8 '*' arg10 "
                        + "arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 "
                        + "arg19 arg20 arg21 arg22 arg23 arg24 arg25 arg26 "
                        + "arg27 arg28 arg29 arg30 arg31 "
                        + "... (remaining 8 arguments skipped)")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun describeCommandFailure_longMessage_verbose() {
        val target: Label? = Label.parseCanonicalUnchecked("//foo:bar")
        val args = arrayOfNulls<String>(40)
        args[0] = "some_command"
        for (i in 1..<args.size) {
            args[i] = "arg" + i
        }
        args[7] = "with spaces" // Test embedded spaces in argument.
        args[9] = "*" // Test shell meta characters.
        val env: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
        env.put("FOO", "foo")
        env.put("PATH", "/usr/bin:/bin:/sbin")
        val cwd = "/my/working/directory"
        val executionPlatform: PlatformInfo =
            PlatformInfo.builder().setLabel(Label.parseCanonicalUnchecked("//platform:exec")).build()
        val message: String? =
            CommandFailureUtils.describeCommandFailure(
                true,
                "Mnemonic",
                java.util.Arrays.< T > asList < T ? > (args),
                env,
                cwd,
                "cfg12345",
                "target " + target,
                executionPlatform.label(),
                "local"
            )
        Truth.assertThat(message)
            .isEqualTo(
                """
            some_command failed: error executing Mnemonic command (from target //foo:bar) 
              (cd /my/working/directory && \
              exec env - \
                FOO=foo \
                PATH=/usr/bin:/bin:/sbin \
              some_command arg1 arg2 arg3 arg4 arg5 arg6 'with spaces' arg8 '*' arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20 arg21 arg22 arg23 arg24 arg25 arg26 arg27 arg28 arg29 arg30 arg31 arg32 arg33 arg34 arg35 arg36 arg37 arg38 arg39)
            # Configuration: cfg12345
            # Execution platform: //platform:exec
            # Runner: local
            """.trimIndent()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun describeCommandFailure_singleSkippedArgument() {
        val target: Label? = Label.parseCanonicalUnchecked("//foo:bar")
        val args = arrayOfNulls<String>(35) // Long enough to make us skip 1 argument below.
        args[0] = "some_command"
        for (i in 1..<args.size) {
            args[i] = "arg" + i
        }
        val env: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
        val cwd = "/my/working/directory"
        val executionPlatform: PlatformInfo =
            PlatformInfo.builder().setLabel(Label.parseCanonicalUnchecked("//platform:exec")).build()
        val message: String? =
            CommandFailureUtils.describeCommandFailure(
                false,
                "Mnemonic",
                java.util.Arrays.< T > asList < T ? > (args),
                env,
                cwd,
                "cfg12345",
                "target " + target,
                executionPlatform.label(),
                "local"
            )
        Truth.assertThat(message)
            .isEqualTo(
                ("some_command failed: error executing Mnemonic command (from target //foo:bar)"
                        + " some_command arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12"
                        + " arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20 arg21 arg22 arg23 arg24 arg25"
                        + " arg26 arg27 arg28 arg29 arg30 arg31 arg32 arg33 ... (remaining 1 argument"
                        + " skipped)")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun describeCommandPrettyPrintArgs() {
        val args = arrayOfNulls<String>(6)
        args[0] = "some_command"
        for (i in 1..<args.size) {
            args[i] = "arg" + i
        }
        args[3] = "with spaces" // Test embedded spaces in argument.
        args[4] = "*" // Test shell meta characters.

        val env: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
        env.put("FOO", "foo")
        env.put("PATH", "/usr/bin:/bin:/sbin")

        val envToClear: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("CLEAR", "THIS")

        val cwd = "/my/working/directory"
        val executionPlatform: PlatformInfo =
            PlatformInfo.builder().setLabel(Label.parseCanonicalUnchecked("//platform:exec")).build()
        val message: String? =
            CommandFailureUtils.describeCommand(
                CommandDescriptionForm.COMPLETE,
                true,
                java.util.Arrays.< T > asList < T ? > (args),
                env,
                envToClear,
                cwd,
                "cfg12345",
                executionPlatform.label(),
                "remote"
            )

        Truth.assertThat(message)
            .isEqualTo(
                """
            (cd /my/working/directory && \
              exec env - \
                -u CLEAR \
                -u THIS \
                FOO=foo \
                PATH=/usr/bin:/bin:/sbin \
              some_command \
                arg1 \
                arg2 \
                'with spaces' \
                '*' \
                arg5)
            # Configuration: cfg12345
            # Execution platform: //platform:exec
            # Runner: remote
            """.trimIndent()
            )
    }
}
