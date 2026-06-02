// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlarkdocextract

import com.google.devtools.build.lib.cmdline.BazelModuleContext

/** An exception indicating that Starlark API documentation could not be extracted.  */
class ExtractionException : java.lang.Exception {
    constructor(message: String?) : super(message)

    constructor(module: net.starlark.java.eval.Module?, message: String?) : super(
        prefixWithModuleFilename(
            module,
            message,
            null
        )
    )

    constructor(module: net.starlark.java.eval.Module?, cause: Throwable?) : super(
        prefixWithModuleFilename(
            module,
            null,
            cause
        ), cause
    )

    constructor(module: net.starlark.java.eval.Module?, message: String?, cause: Throwable?) : super(
        prefixWithModuleFilename(module, message, cause),
        cause
    )

    companion object {
        private fun prefixWithModuleFilename(
            module: net.starlark.java.eval.Module?, message: String?, cause: Throwable?
        ): String? {
            var message = message
            val bazelModuleContext: BazelModuleContext? = BazelModuleContext.of(module)
            if (bazelModuleContext == null) {
                return message
            }
            if (message == null) {
                message = cause!!.message
            }
            return java.lang.String.format(
                "in %s: %s",
                bazelModuleContext.filename(),
                com.google.common.base.Strings.nullToEmpty(message)
            )
        }
    }
}
