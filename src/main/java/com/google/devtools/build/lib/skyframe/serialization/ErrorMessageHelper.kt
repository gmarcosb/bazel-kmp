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
package com.google.devtools.build.lib.skyframe.serialization

/** Utility for formatting error messages.  */
object ErrorMessageHelper {
    @com.google.common.annotations.VisibleForTesting
    const val MAX_ERRORS_TO_REPORT: Int = 5

    fun getErrorMessage(errors: com.google.common.collect.ImmutableList<Throwable?>): String {
        val message: java.lang.StringBuilder = java.lang.StringBuilder()
        if (errors.size() > 1) {
            message.append("There were ").append(errors.size()).append(" write errors.")
            if (errors.size() > MAX_ERRORS_TO_REPORT) {
                message
                    .append(" Only the first ")
                    .append(MAX_ERRORS_TO_REPORT)
                    .append(" will be reported.")
            }
            message.append('\n')
        }
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return message.toString()
    }
}
