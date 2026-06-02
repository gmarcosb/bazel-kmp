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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Action

/**
 * Accumulator for problems encountered while reading or validating inclusion
 * results.
 */
internal class IncludeProblems {
    private var problems: java.lang.StringBuilder? = null // null when no problems

    fun add(included: String?) {
        if (problems == null) {
            problems = java.lang.StringBuilder()
        }
        problems.append("\n  '").append(included).append("'")
    }

    fun hasProblems(): Boolean {
        return problems != null
    }

    @Throws(ActionExecutionException::class)
    fun assertProblemFree(message: String, action: Action?) {
        if (hasProblems()) {
            val fullMessage = message + problems
            val code: DetailedExitCode =
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(fullMessage)
                        .setCppCompile(CppCompile.newBuilder().setCode(Code.UNDECLARED_INCLUSIONS))
                        .build()
                )
            throw ActionExecutionException(fullMessage, action, false, code)
        }
    }
}
