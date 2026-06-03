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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.io.InconsistentFilesystemException

/**
 * Represents logic that evaluates the root of the package containing an exec path, for constructing
 * [Artifact] objects out of the action cache.
 */
interface PackageRootResolver {
    /**
     * Returns mapping from execPath to Root. Root will be null if the path has no containing package.
     * 
     * @param execPaths the paths to find [Root]s for. The search for a containing package will
     * start with the path's parent directory, since the path is assumed to be a file.
     * @return mappings from `execPath` to [Root], or null if for some reason we cannot
     * determine the result at this time (such as when used within a SkyFunction)
     */
    @Throws(PackageRootException::class, java.lang.InterruptedException::class)
    fun findPackageRootsForFiles(execPaths: Iterable<PathFragment?>?): MutableMap<PathFragment?, Root?>?

    /**
     * Exception encapsulating a failure to find a package root in [ ] (via [ ]). Contains a [ ] error for use in a [DetailedExitCode].
     */
    class PackageRootException private constructor(
        execPath: PathFragment,
        error: FailureDetails.IncludeScanning?,
        e: java.lang.Exception
    ) : java.lang.Exception(
        "Unable to resolve " + execPath.getPathString() + " as an artifact: " + e.message,
        e
    ) {
        private val error: FailureDetails.IncludeScanning?

        init {
            this.error = error
        }

        fun getError(): FailureDetails.IncludeScanning? {
            return error
        }

        companion object {
            fun create(execPath: PathFragment, e: BuildFileNotFoundException): PackageRootException {
                val failureDetail: FailureDetails.FailureDetail = e.getDetailedExitCode().getFailureDetail()
                val code: FailureDetails.IncludeScanning.Code? =
                    if (e.hasExplicitDetailedExitCode())
                        if (DetailedExitCode.getExitCode(failureDetail).isInfrastructureFailure())
                            FailureDetails.IncludeScanning.Code.SYSTEM_PACKAGE_LOAD_FAILURE
                        else
                            FailureDetails.IncludeScanning.Code.USER_PACKAGE_LOAD_FAILURE
                    else
                        FailureDetails.IncludeScanning.Code.UNDIFFERENTIATED_PACKAGE_LOAD_FAILURE
                val packageLoadingCode: FailureDetails.PackageLoading.Code? =
                    failureDetail.getPackageLoading().getCode()
                if (packageLoadingCode === FailureDetails.PackageLoading.Code.PACKAGE_LOADING_UNKNOWN) {
                    BugReport.sendBugReport(
                        java.lang.IllegalStateException(
                            "Exception for " + execPath + " had no PackageLoading.Code: " + failureDetail, e
                        )
                    )
                }
                return PackageRootException(
                    execPath,
                    FailureDetails.IncludeScanning.newBuilder()
                        .setCode(code)
                        .setPackageLoadingCode(packageLoadingCode)
                        .build(),
                    e
                )
            }

            fun create(
                execPath: PathFragment, e: InconsistentFilesystemException
            ): PackageRootException {
                return PackageRootException(
                    execPath,
                    FailureDetails.IncludeScanning.newBuilder()
                        .setCode(FailureDetails.IncludeScanning.Code.SYSTEM_PACKAGE_LOAD_FAILURE)
                        .setPackageLoadingCode(
                            FailureDetails.PackageLoading.Code.PERSISTENT_INCONSISTENT_FILESYSTEM_ERROR
                        )
                        .build(),
                    e
                )
            }
        }
    }
}
