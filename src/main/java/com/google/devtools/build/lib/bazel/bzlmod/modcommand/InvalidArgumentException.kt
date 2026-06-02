// Copyright 2023 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod.modcommand

import com.google.devtools.build.lib.server.FailureDetails.ModCommand.Code

/**
 * Exception thrown when a user-input argument is invalid (wrong number of arguments or the
 * specified modules do not exist).
 */
class InvalidArgumentException : Exception {
    private val code: Code?

    constructor(message: String?, code: Code?, cause: Exception?) : super(message, cause) {
        this.code = code
    }

    @kotlin.jvm.JvmOverloads
    constructor(message: String?, code: Code? = Code.INVALID_ARGUMENTS) : super(message) {
        this.code = code
    }

    fun getCode(): Code? {
        return code
    }
}
