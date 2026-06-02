// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.io

import com.google.devtools.build.lib.server.FailureDetails

/**
 * Exception indicating that [com.google.devtools.build.lib.skyframe.ProcessPackageDirectory]
 * failed due to an [InconsistentFilesystemException]. Wraps [ ] so that we have a [DetailedException] for top-level
 * reporting.
 */
class ProcessPackageDirectoryException(directory: RootedPath, e: InconsistentFilesystemException) : java.lang.Exception(
    ("Directory '"
            + directory.asPath().getPathString()
            + "' could not be processed: "
            + e.message),
    e
), DetailedException {
    private val detailedExitCode: DetailedExitCode

    init {
        this.detailedExitCode =
            DetailedExitCode.of(
                FailureDetails.FailureDetail.newBuilder()
                    .setMessage(message)
                    .setPackageLoading(
                        FailureDetails.PackageLoading.newBuilder()
                            .setCode(
                                FailureDetails.PackageLoading.Code
                                    .TRANSIENT_INCONSISTENT_FILESYSTEM_ERROR
                            )
                    )
                    .build()
            )
    }

    override fun getDetailedExitCode(): DetailedExitCode {
        return detailedExitCode
    }
}
