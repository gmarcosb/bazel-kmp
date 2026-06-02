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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.server.FailureDetails

/**
 * Thrown if the configuration options lead to an invalid configuration, or if any of the
 * configuration labels cannot be loaded.
 */
class InvalidConfigurationException : java.lang.Exception, DetailedException {
    private val detailedExitCode: DetailedExitCode?

    constructor(message: String?) : super(message) {
        this.detailedExitCode = null
    }

    constructor(message: String?, code: Code?) : super(message) {
        this.detailedExitCode = createDetailedExitCode(message, code)
    }

    constructor(message: String?, cause: Throwable?) : super(message, cause) {
        this.detailedExitCode = null
    }

    constructor(message: String?, code: Code?, cause: Throwable?) : super(message, cause) {
        this.detailedExitCode = createDetailedExitCode(message, code)
    }

    constructor(cause: Throwable) : super(cause.message, cause) {
        this.detailedExitCode = null
    }

    constructor(code: Code?, cause: Throwable) : super(cause.message, cause) {
        this.detailedExitCode = createDetailedExitCode(cause.message, code)
    }

    constructor(detailedExitCode: DetailedExitCode?, cause: Throwable) : super(cause.message, cause) {
        this.detailedExitCode = detailedExitCode
    }

    override fun getDetailedExitCode(): DetailedExitCode {
        return if (detailedExitCode != null)
            detailedExitCode
        else
            createDetailedExitCode(message, Code.INVALID_CONFIGURATION)
    }

    companion object {
        private fun createDetailedExitCode(message: String?, code: Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(com.google.common.base.Strings.nullToEmpty(message))
                    .setBuildConfiguration(FailureDetails.BuildConfiguration.newBuilder().setCode(code))
                    .build()
            )
        }
    }
}
