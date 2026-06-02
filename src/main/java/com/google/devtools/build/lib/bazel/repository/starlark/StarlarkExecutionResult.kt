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
package com.google.devtools.build.lib.bazel.repository.starlark

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.docgen.annot.DocCategory
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.shell.AbnormalTerminationException
import com.google.devtools.build.lib.shell.BadExitStatusException
import com.google.devtools.build.lib.shell.Command
import com.google.devtools.build.lib.shell.CommandException
import com.google.devtools.build.lib.util.io.DelegatingOutErr
import com.google.devtools.build.lib.util.io.OutErr
import com.google.devtools.build.lib.util.io.RecordingOutErr
import com.google.errorprone.annotations.CanIgnoreReturnValue
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.StarlarkValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.function.Consumer
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet

/**
 * A structure callable from Starlark that stores the result of repository_ctx.execute() method. It
 * contains the standard output stream content, the standard error stream content and the execution
 * return code.
 */
@ThreadSafety.Immutable
@StarlarkBuiltin(
    name = "exec_result", category = DocCategory.BUILTIN, doc = """
        A structure storing result of repository_ctx.execute() method. It contains the standard output stream content, the standard error stream content and the execution return code.
        
        """.trimIndent()
)
internal class StarlarkExecutionResult(
  @kotlin.jvm.JvmField @get:StarlarkMethod(
        name = "return_code", structField = true, doc = """
          The return code returned after the execution of the program. 256 if the process was terminated by a time out; values larger than 128 indicate termination by a signal.
          
          """.trimIndent()
    ) val returnCode: Int, @kotlin.jvm.JvmField @get:StarlarkMethod(
        name = "stdout",
        structField = true,
        doc = "The content of the standard output returned by the execution."
    ) val stdout: String?, @kotlin.jvm.JvmField @get:StarlarkMethod(
        name = "stderr",
        structField = true,
        doc = "The content of the standard error output returned by the execution."
    ) val stderr: String?
) : StarlarkValue {
    override fun isImmutable(): Boolean {
        return true // immutable and Starlark-hashable
    }

    /** A Builder class to build a [StarlarkExecutionResult] object by executing a command.  */
    internal class Builder private constructor(environment: MutableMap<String?, String?>) {
        private val args: MutableList<String?> = ArrayList<String?>()
        private var directory: File? = null
        private val envBuilder: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
        private val clientEnv: ImmutableMap<String?, String?>
        private var timeout: Long = -1
        private var executed = false
        private var quiet = false

        init {
            clientEnv = ImmutableMap.copyOf<String?, String?>(environment)
            envBuilder.putAll(environment)
        }

        /**
         * Adds arguments to the list of arguments to pass to the command. The first argument is
         * expected to be the binary to execute. The subsequent arguments are the arguments passed to
         * the binary.
         */
        @CanIgnoreReturnValue
        fun addArguments(args: MutableList<String?>?): Builder {
            this.args.addAll(args!!)
            return this
        }

        /**
         * Set the path to the directory to execute the result process. This method must be called
         * before calling [.execute].
         */
        @CanIgnoreReturnValue
        fun setDirectory(path: File?): Builder {
            this.directory = path
            return this
        }

        /**
         * Add an environment variables to be added to the list of environment variables. For all key
         * `k` of `variables`, the resulting process will have the variable `
         * k=variables.get(k)` defined.
         */
        @CanIgnoreReturnValue
        fun addEnvironmentVariables(variables: MutableMap<String?, String?>?): Builder {
            this.envBuilder.putAll(variables!!)
            return this
        }

        /** Ensure that an environment variable is not passed to the process.  */
        @CanIgnoreReturnValue
        fun removeEnvironmentVariables(removeEnvVariables: MutableSet<String?>): Builder {
            removeEnvVariables.forEach(Consumer { key: String? -> envBuilder.remove(key) })
            return this
        }

        /** Sets the timeout, in milliseconds, after which the executed command will be terminated.  */
        @CanIgnoreReturnValue
        fun setTimeout(timeout: Long): Builder {
            Preconditions.checkArgument(timeout > 0, "Timeout must be a positive number.")
            this.timeout = timeout
            return this
        }

        @CanIgnoreReturnValue
        fun setQuiet(quiet: Boolean): Builder {
            this.quiet = quiet
            return this
        }

        /** Execute the command specified by [.addArguments].  */
        @Throws(InterruptedException::class)
        fun execute(): StarlarkExecutionResult {
            Preconditions.checkArgument(timeout > 0, "Timeout must be set prior to calling execute().")
            Preconditions.checkArgument(!args.isEmpty(), "No command specified.")
            Preconditions.checkState(!executed, "Command was already executed, cannot re-use builder.")
            Preconditions.checkNotNull<File?>(directory, "Directory must be set before calling execute().")
            executed = true

            val delegator = DelegatingOutErr()
            val recorder = RecordingOutErr()
            // TODO(dmarting): if a lot of data is sent to stdout, this will use all the memory and
            // Bazel will crash. Maybe we should use custom output streams that throw an appropriate
            // exception when reaching a specific size.
            delegator.addSink(recorder)
            if (!quiet) {
                delegator.addSink(OutErr.create(System.err, System.err))
            }
            try {
                val command =
                    Command(args, envBuilder, directory, Duration.ofMillis(timeout), clientEnv)
                val result =
                    command.execute(delegator.getOutputStream(), delegator.getErrorStream())
                return StarlarkExecutionResult(
                    result.terminationStatus.getExitCode(),
                    recorder.outAsLatin1(),
                    recorder.errAsLatin1()
                )
            } catch (e: BadExitStatusException) {
                return StarlarkExecutionResult(
                    e.getResult().terminationStatus.getExitCode(),
                    recorder.outAsLatin1(),
                    recorder.errAsLatin1()
                )
            } catch (e: AbnormalTerminationException) {
                val status = e.getResult().terminationStatus
                if (status.timedOut()) {
                    // Signal a timeout by an exit code outside the normal range
                    return StarlarkExecutionResult(256, "", e.getMessage())
                } else if (status.exited()) {
                    return StarlarkExecutionResult(
                        status.getExitCode(),
                        toString(e.getResult().stdoutStream),
                        toString(e.getResult().stderrStream)
                    )
                } else if (status.getTerminatingSignal() == 15) {
                    // We have a bit of a problem here: we cannot distingusih between the case where
                    // the SIGTERM was sent by something that the calling rule wants to legitimately handle,
                    // and the case where it was sent by bazel to abort the build, e.g., because something
                    // else failed.
                    //
                    // We just assume the latter to correctly handle aborts, accepting that rule authors have
                    // to write their rules without relying on the ability to handle termination by signal 15.
                    throw InterruptedException()
                } else {
                    return StarlarkExecutionResult(
                        status.getRawExitCode(),
                        toString(e.getResult().stdoutStream),
                        toString(e.getResult().stderrStream)
                    )
                }
            } catch (e: CommandException) {
                // 256 is outside of the standard range for exit code on Unixes. We are not guaranteed that
                // on all system it would be outside of the standard range.
                return StarlarkExecutionResult(256, "", e.getMessage())
            }
        }

        companion object {
            private fun toString(stream: ByteArrayOutputStream): String? {
                try {
                    return stream.toString(StandardCharsets.ISO_8859_1)
                } catch (e: IllegalStateException) {
                    return ""
                }
            }
        }
    }

    companion object {
        /**
         * Returns a Builder that can be used to execute a command and build an execution result.
         * 
         * @param environment pass through the list of environment variables from the client to be passed
         * to the execution environment.
         */
        fun builder(environment: MutableMap<String?, String?>): Builder {
            return StarlarkExecutionResult.Builder(environment)
        }
    }
}
