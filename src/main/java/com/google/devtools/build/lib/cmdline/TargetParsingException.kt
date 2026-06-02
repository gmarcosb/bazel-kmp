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
package com.google.devtools.build.lib.cmdline

import com.google.devtools.build.lib.server.FailureDetails

/** An exception indicating a target label that cannot be parsed.  */
class TargetParsingException : java.lang.Exception, DetailedException {
    private val detailedExitCode: DetailedExitCode

    constructor(
        message: String?,
        code: TargetPatterns.Code?
    ) : super(com.google.common.base.Preconditions.checkNotNull<String?>(message)) {
        this.detailedExitCode = DetailedExitCode.of(createFailureDetail(message, code))
    }

    constructor(
        message: String?,
        cause: Throwable?,
        code: TargetPatterns.Code?
    ) : super(com.google.common.base.Preconditions.checkNotNull<String?>(message), cause) {
        this.detailedExitCode = DetailedExitCode.of(createFailureDetail(message, code))
    }

    constructor(
        message: String?,
        cause: Throwable?,
        detailedExitCode: DetailedExitCode?
    ) : super(com.google.common.base.Preconditions.checkNotNull<String?>(message), cause) {
        this.detailedExitCode = com.google.common.base.Preconditions.checkNotNull<DetailedExitCode>(detailedExitCode)
    }

    constructor(
        message: String?,
        detailedExitCode: DetailedExitCode?
    ) : super(com.google.common.base.Preconditions.checkNotNull<String?>(message)) {
        this.detailedExitCode = com.google.common.base.Preconditions.checkNotNull<DetailedExitCode>(detailedExitCode)
    }

    constructor(cause: InconsistentFilesystemException) : super(cause.getMessage(), cause) {
        this.detailedExitCode =
            DetailedExitCode.of(
                FailureDetails.FailureDetail.newBuilder()
                    .setPackageLoading(
                        FailureDetails.PackageLoading.newBuilder()
                            .setCode(
                                FailureDetails.PackageLoading.Code
                                    .TRANSIENT_INCONSISTENT_FILESYSTEM_ERROR
                            )
                    )
                    .setMessage(getMessage())
                    .build()
            )
    }

    /**
     * Returns the detailed exit code that contains the failure detail associated with the error
     * during parsing.
     */
    override fun getDetailedExitCode(): DetailedExitCode {
        return detailedExitCode
    }

    companion object {
        private fun createFailureDetail(message: String?, code: TargetPatterns.Code?): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(message)
                .setTargetPatterns(TargetPatterns.newBuilder().setCode(code).build())
                .build()
        }
    }
}
