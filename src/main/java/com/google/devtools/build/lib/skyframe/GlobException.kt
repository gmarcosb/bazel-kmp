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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionException

/**
 * Used to declare all the exception types that can be wrapped in the exception thrown by [ ][GlobFunction.compute].
 */
internal class GlobException : SkyFunctionException {
    constructor(e: InconsistentFilesystemException?, transience: Transience?) : super(e, transience)

    constructor(e: FileSymlinkInfiniteExpansionException?, transience: Transience?) : super(e, transience)

    companion object {
        /**
         * If any exception are caught and stored in [ ] in [ ], wrap it inside a [GlobException] and throw.
         */
        @Throws(GlobException::class)
        fun handleExceptions(error: GlobError?) {
            if (error == null) {
                return
            }
            when (error.kind()) {
                com.google.devtools.build.lib.packages.producers.GlobError.Kind.INCONSISTENT_FILESYSTEM -> throw GlobException(
                    error.inconsistentFilesystem(),
                    Transience.TRANSIENT
                )

                com.google.devtools.build.lib.packages.producers.GlobError.Kind.FILE_SYMLINK_INFINITE_EXPANSION -> throw GlobException(
                    error.fileSymlinkInfiniteExpansion(),
                    Transience.PERSISTENT
                )
            }
        }
    }
}
