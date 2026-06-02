// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.worker.ErrorMessage
import java.io.IOException

/** A well-formatted error message that is easy to read and easy to create.  */
@kotlin.jvm.JvmRecord
internal data class ErrorMessage(val message: String?) {
    override fun toString(): String {
        return this.message!!
    }

    class Builder private constructor() {
        private var message = "Unknown error"
        private var logFile: com.google.devtools.build.lib.vfs.Path? = null
        private var logText = ""
        private var logSizeLimit: Int = java.lang.Integer.MAX_VALUE
        private var exception: java.lang.Exception? = null

        /** Sets the main text of this error message.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun message(message: String?): Builder {
            com.google.common.base.Preconditions.checkNotNull<String?>(message)
            this.message = if (message.isEmpty()) "Unknown error" else message.trim()
            return this
        }

        /** Sets the log file that should be printed as part of the error message.  */
        fun logFile(logFile: com.google.devtools.build.lib.vfs.Path?): Builder {
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(logFile)
            try {
                this.logFile = logFile
                return logText(
                    com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(
                        logFile,
                        java.nio.charset.StandardCharsets.UTF_8
                    )
                )
            } catch (e: IOException) {
                logSizeLimit(java.lang.Integer.MAX_VALUE)
                return logText(
                    "ERROR: IOException while trying to read log file:\n"
                            + com.google.common.base.Throwables.getStackTraceAsString(e)
                )
            }
        }

        /**
         * Sets additional text, which is to be presented as a log file in the error message.
         * 
         * 
         * If the log originally comes from a file, it is recommended to use [.logFile]
         * instead, because then the path to the log file can be printed together with the message.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun logText(logText: String?): Builder {
            com.google.common.base.Preconditions.checkNotNull<String?>(logText)
            // Set the log text to "(empty)" when the passed in string is empty, otherwise error messages
            // like "Something failed. Check below log for details:" would be pretty confusing for users.
            this.logText = if (logText.isEmpty()) "(empty)" else logText.trim()
            return this
        }

        /**
         * If the log file or text of this error message is longer than the character limit set via this
         * method, it will be truncated so that only the last X characters of the log are printed.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun logSizeLimit(logSizeLimit: Int): Builder {
            com.google.common.base.Preconditions.checkArgument(logSizeLimit > 0, "logSizeLimit must be positive")
            this.logSizeLimit = logSizeLimit
            return this
        }

        /** Lets the error message contain the details of an exception.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun exception(e: java.lang.Exception?): Builder {
            this.exception = e
            return this
        }

        /** Builds and returns the formatted error message.  */
        fun build(): ErrorMessage {
            val sb: java.lang.StringBuilder = java.lang.StringBuilder(message)

            if (exception != null) {
                sb.append("\n\n---8<---8<--- Exception details ---8<---8<---\n")
                sb.append(com.google.common.base.Throwables.getStackTraceAsString(exception).trim())
                sb.append("\n---8<---8<--- End of exception details ---8<---8<---")
            }

            if (!logText.isEmpty()) {
                sb.append("\n\n---8<---8<--- Start of log")
                if (logText.length() > logSizeLimit) {
                    sb.append(" snippet")
                }
                if (logFile != null) {
                    sb.append(", file at ")
                    sb.append(logFile.getPathString())
                }
                sb.append(" ---8<---8<---\n")

                // If the length of the log is longer than the limit, print only the last part.
                if (logText.length() > logSizeLimit) {
                    sb.append("[... truncated ...]\n")
                    sb.append(logText, logText.length() - logSizeLimit, logText.length())
                    sb.append("\n---8<---8<--- End of log snippet, ")
                    sb.append(logText.length() - logSizeLimit)
                    sb.append(" chars omitted ---8<---8<---")
                } else {
                    sb.append(logText)
                    sb.append("\n---8<---8<--- End of log ---8<---8<---")
                }
            }

            return ErrorMessage(sb.toString())
        }
    }

    init {
        java.util.Objects.requireNonNull<String?>(message, "message")
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.worker.ErrorMessage.Builder()
        }
    }
}
