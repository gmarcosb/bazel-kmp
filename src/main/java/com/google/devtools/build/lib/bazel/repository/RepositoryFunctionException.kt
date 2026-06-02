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
package com.google.devtools.build.lib.bazel.repository

import com.google.devtools.build.lib.packages.NoSuchPackageException
import com.google.devtools.build.lib.repository.ExternalPackageException
import com.google.devtools.build.lib.skyframe.AlreadyReportedException
import com.google.devtools.build.skyframe.SkyFunctionException
import com.google.devtools.build.skyframe.SkyFunctionException.Transience
import java.io.IOException

/**
 * Exception thrown when something goes wrong accessing a remote repository.
 * 
 * 
 * This exception should be used by child classes to limit the types of exceptions [ ] has to know how to catch.
 */
class RepositoryFunctionException : SkyFunctionException {
    /** Error reading or writing to the filesystem.  */
    constructor(cause: IOException?, transience: Transience?) : super(cause, transience)

    constructor(cause: net.starlark.java.eval.EvalException?, transience: Transience?) : super(cause, transience)

    constructor(cause: AlreadyReportedRepositoryAccessException?, transience: Transience?) : super(cause, transience)

    /**
     * Encapsulates the exceptions that arise when accessing a repository. Error reporting should ONLY
     * be handled in [RepositoryFetchFunction].
     */
    class AlreadyReportedRepositoryAccessException(e: java.lang.Exception) :
        AlreadyReportedException(e.getMessage(), e.getCause()) {
        init {
            com.google.common.base.Preconditions.checkState(
                e is NoSuchPackageException
                        || e is IOException
                        || e is net.starlark.java.eval.EvalException
                        || e is ExternalPackageException,
                e
            )
        }
    }
}
