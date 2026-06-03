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

import com.google.devtools.build.lib.shell.Command

@RunWith(JUnit4::class)
class CommandUtilsTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun longCommand() {
        val command: Command = buildLongCommand()
        val message: String? =
            CommandFailureUtils.describeCommandFailure(false, CommandUtils.cwd(command), command)
        Truth.assertThat(message)
            .isEqualTo(
                ("this_command_will_not_be_found failed: "
                        + "error executing <shell command> command this_command_will_not_be_found arg1 "
                        + "arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 "
                        + "arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 "
                        + "arg19 arg20 arg21 arg22 arg23 arg24 arg25 arg26 "
                        + "arg27 arg28 arg29 arg30 "
                        + "... (remaining 9 arguments skipped)")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun longCommand_verbose() {
        val command: Command = buildLongCommand()
        val verboseMessage: String? =
            CommandFailureUtils.describeCommandFailure(true, CommandUtils.cwd(command), command)
        Truth.assertThat(verboseMessage)
            .isEqualTo(
                ("this_command_will_not_be_found failed: error executing <shell command> command \n"
                        + "  (cd /tmp && \\\n"
                        + "  exec env - \\\n"
                        + "    FOO=foo \\\n"
                        + "    PATH=/usr/bin:/bin:/sbin \\\n"
                        + "  this_command_will_not_be_found arg1 "
                        + "arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 "
                        + "arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 "
                        + "arg19 arg20 arg21 arg22 arg23 arg24 arg25 arg26 "
                        + "arg27 arg28 arg29 arg30 arg31 arg32 arg33 arg34 "
                        + "arg35 arg36 arg37 arg38 arg39)")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failingCommand() {
        val args: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "/bin/sh",
                "-c",
                "echo Some errors 1>&2; echo Some output; exit 42"
            )
        val env: MutableMap<String?, String?> = com.google.common.collect.Maps.newTreeMap<String?, String?>()
        env.put("FOO", "foo")
        env.put("PATH", "/usr/bin:/bin:/sbin")
        val exception: CommandException? =
            org.junit.Assert.assertThrows<T?>(
                CommandException::class.java,
                org.junit.function.ThrowingRunnable { Command(args, env, null, java.lang.System.getenv()).execute() })
        val message: String? = CommandUtils.describeCommandFailure(false, exception)
        val verboseMessage: String? = CommandUtils.describeCommandFailure(true, exception)
        Truth.assertThat(message)
            .isEqualTo(
                ("sh failed: error executing <shell command> command "
                        + "/bin/sh -c 'echo Some errors 1>&2; echo Some output; exit 42': "
                        + "Process exited with status 42\n"
                        + "Some output\n"
                        + "Some errors\n")
            )
        Truth.assertThat(verboseMessage)
            .isEqualTo(
                ("sh failed: error executing <shell command> command \n"
                        + "  (exec env - \\\n"
                        + "    FOO=foo \\\n"
                        + "    PATH=/usr/bin:/bin:/sbin \\\n"
                        + "  /bin/sh -c 'echo Some errors 1>&2; echo Some output; exit 42'): "
                        + "Process exited with status 42\n"
                        + "Some output\n"
                        + "Some errors\n")
            )
    }

    companion object {
        private fun buildLongCommand(): Command {
            val args: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
            args.add("this_command_will_not_be_found")
            for (i in 1..39) {
                args.add("arg" + i)
            }
            val env: MutableMap<String?, String?> = com.google.common.collect.Maps.newTreeMap<String?, String?>()
            env.put("PATH", "/usr/bin:/bin:/sbin")
            env.put("FOO", "foo")
            val directory: java.io.File = java.io.File("/tmp")
            return Command(args, env, directory, java.lang.System.getenv())
        }
    }
}
