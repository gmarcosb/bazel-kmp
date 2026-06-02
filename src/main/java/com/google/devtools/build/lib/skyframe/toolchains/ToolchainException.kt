// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.toolchains

import com.google.devtools.build.lib.analysis.TargetAndConfiguration

/** Base class for exceptions that happen during toolchain resolution.  */
abstract class ToolchainException : java.lang.Exception, DetailedException {
    constructor(message: String?) : super(message)

    constructor(cause: Throwable?) : super(cause)

    constructor(message: String?, cause: Throwable?) : super(message, cause)

    protected abstract val detailedCode: Code?

    val detailedExitCode: DetailedExitCode
        get() {
            if (getCause() is DetailedException) {
                return (getCause() as DetailedException).detailedExitCode
            }

            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(com.google.common.base.Strings.nullToEmpty(getMessage()))
                    .setToolchain(FailureDetails.Toolchain.newBuilder().setCode(this.detailedCode))
                    .build()
            )
        }

    /**
     * Attempt to find a [ConfiguredValueCreationException] in a [ToolchainException], or
     * its causes.
     * 
     * 
     * If one cannot be found, make a new one.
     */
    fun asConfiguredValueCreationException(
        targetAndConfiguration: TargetAndConfiguration
    ): ConfiguredValueCreationException? {
        run {
            var cause: Throwable? = getCause()
            while (cause != null && cause !== cause.getCause()
            ) {
                if (cause is ConfiguredValueCreationException) {
                    return cause
                }
                cause = cause.getCause()
            }
        }
        val cause: com.google.devtools.build.lib.causes.Cause =
            AnalysisFailedCause(
                targetAndConfiguration.getLabel(),
                configurationIdMessage(targetAndConfiguration.getConfiguration()),
                createDetailedExitCode(
                    java.lang.String.format(
                        "While resolving toolchains for target %s: %s",
                        targetAndConfiguration.getLabel(), getMessage()
                    )
                )
            )
        return ConfiguredValueCreationException(
            targetAndConfiguration.getTarget(),
            targetAndConfiguration.getConfiguration().getEventId(),
            java.lang.String.format(
                "While resolving toolchains for target %s: %s", targetAndConfiguration, getMessage()
            ),
            NestedSetBuilder.create(Order.STABLE_ORDER, cause),
            this.detailedExitCode
        )
    }

    companion object {
        fun configurationIdMessage(
            configuration: BuildConfigurationValue?
        ): ConfigurationId {
            if (configuration == null) {
                return ConfigurationId.newBuilder().setId("none").build()
            }
            return ConfigurationId.newBuilder().setId(configuration.checksum()).build()
        }

        private fun createDetailedExitCode(message: String?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setAnalysis(
                        Analysis.newBuilder().setCode(Analysis.Code.CONFIGURED_VALUE_CREATION_FAILED)
                    )
                    .build()
            )
        }
    }
}
