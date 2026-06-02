// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.Globber
import com.google.devtools.build.lib.packages.Globber.BadGlobException

/** Static functionality shared by different implementations of the Globber interface.  */
@com.google.errorprone.annotations.CheckReturnValue
object GlobberUtils {
    @Throws(BadGlobException::class)
    fun throwBadGlobExceptionEmptyResult(
        pattern: String?, globberOperation: com.google.devtools.build.lib.packages.Globber.Operation
    ) {
        when (globberOperation) {
            com.google.devtools.build.lib.packages.Globber.Operation.SUBPACKAGES -> throw BadGlobException(
                ("subpackages pattern '"
                        + pattern
                        + "' didn't match anything, but allow_empty is set to False (the default value)")
            )

            else -> throw BadGlobException(
                ("glob pattern '"
                        + pattern
                        + "' didn't match anything, but allow_empty is set to False "
                        + "(the default value of allow_empty can be set with "
                        + "--incompatible_disallow_empty_glob).")
            )
        }
    }

    @Throws(BadGlobException::class)
    fun throwBadGlobExceptionAllExcluded(globberOperation: com.google.devtools.build.lib.packages.Globber.Operation) {
        when (globberOperation) {
            com.google.devtools.build.lib.packages.Globber.Operation.SUBPACKAGES -> throw BadGlobException(
                "all subpackages in subpackages() have been excluded, but allow_empty is"
                        + " set to False "
            )

            else -> throw BadGlobException(
                ("all files in the glob have been excluded, but allow_empty is set to False "
                        + "(the default value of allow_empty can be set with "
                        + "--incompatible_disallow_empty_glob).")
            )
        }
    }
}
