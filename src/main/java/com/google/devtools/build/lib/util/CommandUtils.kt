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

import com.google.devtools.build.lib.shell.AbnormalTerminationException

/** Utility methods relating to the [Command] class.  */
object CommandUtils {
    @com.google.common.annotations.VisibleForTesting
    fun cwd(command: Command): String? {
        return if (command.getWorkingDirectory() == null) null else command.getWorkingDirectory().getPath()
    }

    /**
     * Construct an error message that describes a failed command invocation.
     * Currently this returns a message of the form "foo failed: error executing
     * command /dir/foo bar baz: exception message", with the
     * command's stdout and stderr output appended if available.
     */
    fun describeCommandFailure(verbose: Boolean, exception: CommandException): String {
        val command: Command = exception.getCommand()
        val message =
            (CommandFailureUtils.describeCommandFailure(verbose, cwd(command), command)
                    + ": "
                    + exception.getMessage())
        if (exception is AbnormalTerminationException) {
            val result: CommandResult = exception.getResult()
            try {
                return (message + "\n"
                        + String(result.getStdout())
                        + String(result.getStderr()))
            } catch (e: java.lang.IllegalStateException) {
                // This can happen if the command didn't save stdout/stderr,
                // so ignore this exception and fall through to the ordinary case.
            }
        }
        return message
    }
}
