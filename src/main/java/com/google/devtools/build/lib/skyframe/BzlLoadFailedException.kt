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

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail

/** Exceptions from [BzlLoadFunction].  */
class BzlLoadFailedException : AbstractSaneAnalysisException {
    private val transience: Transience?
    private val detailedExitCode: DetailedExitCode?

    private constructor(errorMessage: String?, detailedExitCode: DetailedExitCode?, transience: Transience?) : super(
        errorMessage
    ) {
        this.transience = transience
        this.detailedExitCode = detailedExitCode
    }

    internal constructor(errorMessage: String?, detailedExitCode: DetailedExitCode?) : this(
        errorMessage,
        detailedExitCode,
        Transience.PERSISTENT
    )

    internal constructor(
        errorMessage: String?,
        detailedExitCode: DetailedExitCode?,
        cause: java.lang.Exception?,
        transience: Transience?
    ) : super(errorMessage, cause) {
        this.transience = transience
        this.detailedExitCode = detailedExitCode
    }

    internal constructor(errorMessage: String?, code: Code?) : this(
        errorMessage,
        createDetailedExitCode(errorMessage, code),
        Transience.PERSISTENT
    )

    internal constructor(
        errorMessage: String?,
        code: Code?,
        cause: java.lang.Exception?,
        transience: Transience?
    ) : this(errorMessage, createDetailedExitCode(errorMessage, code), cause, transience)

    fun getTransience(): Transience? {
        return transience
    }

    override fun getDetailedExitCode(): DetailedExitCode? {
        return detailedExitCode
    }

    companion object {
        fun createDetailedExitCode(message: String?, code: Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setStarlarkLoading(StarlarkLoading.newBuilder().setCode(code))
                    .build()
            )
        }
    }
}
