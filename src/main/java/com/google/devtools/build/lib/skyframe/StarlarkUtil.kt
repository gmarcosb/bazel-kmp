// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.events.Event

/** Helper functions for Bazel's use of Starlark.  */
object StarlarkUtil {
    val INVALID_UTF_8_MESSAGE: String = ("not a valid UTF-8 encoded file; this can lead to inconsistent behavior and"
            + " will be disallowed in a future version of Bazel")

    /**
     * Produces a [ParserInput] from the raw bytes of a file while optionally enforcing that the
     * contents are valid UTF-8.
     * 
     * 
     * **Warnings and errors are reported to the [EventHandler].**
     * 
     * @throws InvalidUtf8Exception if the bytes are not valid UTF-8 and the enforcement mode is
     * [Utf8EnforcementMode.ERROR].
     */
    // This method is the only one that is supposed to use the deprecated ParserInput.fromLatin1
    // method.
    @Suppress("deprecation") // See https://github.com/bazelbuild/bazel/issues/374
    @Throws(InvalidUtf8Exception::class)
    fun createParserInput(
        bytes: ByteArray, file: String?, utf8EnforcementMode: Utf8EnforcementMode, reporter: EventHandler
    ): net.starlark.java.syntax.ParserInput {
        when (utf8EnforcementMode) {
            Utf8EnforcementMode.OFF -> {}
            Utf8EnforcementMode.WARNING -> {
                if (!com.google.common.base.Utf8.isWellFormed(bytes)) {
                    reporter.handle(
                        Event.warn(
                            net.starlark.java.syntax.Location.fromFile(file),
                            com.google.devtools.build.lib.skyframe.StarlarkUtil.INVALID_UTF_8_MESSAGE
                        )
                    )
                }
            }

            Utf8EnforcementMode.ERROR -> {
                if (!com.google.common.base.Utf8.isWellFormed(bytes)) {
                    reporter.handle(
                        Event.error(
                            net.starlark.java.syntax.Location.fromFile(file),
                            java.lang.String.format(
                                "%s. For a temporary workaround, see the --%s flag.",
                                com.google.devtools.build.lib.skyframe.StarlarkUtil.INVALID_UTF_8_MESSAGE,
                                BuildLanguageOptions.INCOMPATIBLE_ENFORCE_STARLARK_UTF8
                            )
                        )
                    )
                    throw InvalidUtf8Exception(file + ": " + com.google.devtools.build.lib.skyframe.StarlarkUtil.INVALID_UTF_8_MESSAGE)
                }
            }
        }
        return net.starlark.java.syntax.ParserInput.fromLatin1(bytes, file)
    }

    /** Exception thrown when a Starlark file is not valid UTF-8.  */
    class InvalidUtf8Exception(message: String?) : java.lang.Exception(message)
}
