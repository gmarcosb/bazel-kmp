// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.configurationIdMessage

/** An exception indicating that there was a problem creating an aspect.  */
class AspectCreationException(
    message: String?,
    causes: NestedSet<com.google.devtools.build.lib.causes.Cause?>?,
    detailedExitCode: DetailedExitCode?
) : AbstractSaneAnalysisException(message) {
    private val causes: NestedSet<com.google.devtools.build.lib.causes.Cause?>?

    // TODO(b/138456686): if warranted by a need for finer-grained details, replace the constructors
    //  that specify the general Code.ASPECT_CREATION_FAILED
    private val detailedExitCode: DetailedExitCode?

    init {
        this.causes = causes
        this.detailedExitCode = detailedExitCode
    }

    constructor(
        message: String?,
        currentTarget: Label,
        configuration: BuildConfigurationValue?,
        detailedExitCode: DetailedExitCode
    ) : this(
        message,
        NestedSetBuilder.< Cause > stableOrder < com . google . devtools . build . lib . causes . Cause ? > ()
            .add(
                AnalysisFailedCause(
                    currentTarget, configurationIdMessage(configuration), detailedExitCode
                )
            )
            .build(),
        detailedExitCode
    )

    constructor(message: String?, currentTarget: Label, configuration: BuildConfigurationValue?) : this(
        message,
        currentTarget,
        configuration,
        createDetailedExitCode(message, Code.ASPECT_CREATION_FAILED)
    )

    constructor(message: String?, currentTarget: Label, detailedExitCode: DetailedExitCode) : this(
        message,
        currentTarget,
        null,
        detailedExitCode
    )

    constructor(message: String?, currentTarget: Label) : this(
        message, currentTarget, null, createDetailedExitCode(message, Code.ASPECT_CREATION_FAILED)
    )

    constructor(message: String?, cause: LabelCause) : this(
        message,
        NestedSetBuilder.< Cause > stableOrder < com . google . devtools . build . lib . causes . Cause ? > ().add(cause)
            .build(),
        cause.getDetailedExitCode()
    )

    fun getCauses(): NestedSet<com.google.devtools.build.lib.causes.Cause?>? {
        return causes
    }

    override fun getDetailedExitCode(): DetailedExitCode? {
        return detailedExitCode
    }

    companion object {
        private fun createDetailedExitCode(message: String?, code: Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setAnalysis(Analysis.newBuilder().setCode(code))
                    .build()
            )
        }
    }
}
