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


import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId

/**
 * An exception indicating that there was a problem during the construction of a
 * ConfiguredTargetValue.
 */
class ConfiguredValueCreationException(
    location: net.starlark.java.syntax.Location?,
    message: String?,
    label: Label,
    configuration: BuildEventId?,
    rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?,
    detailedExitCode: DetailedExitCode?
) : AbstractSaneAnalysisException(message) {
    private val location: net.starlark.java.syntax.Location?
    private val configuration: BuildEventId?
    private val rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?

    // TODO(b/138456686): if warranted by a need for finer-grained details, replace the constructors
    //  that specify the general Code.CONFIGURED_VALUE_CREATION_FAILED
    private val detailedExitCode: DetailedExitCode

    init {
        this.location = location
        this.configuration = configuration
        val exitCode: DetailedExitCode =
            if (detailedExitCode != null) detailedExitCode else createDetailedExitCode(message)
        this.detailedExitCode = exitCode
        this.rootCauses =
            if (rootCauses != null)
                rootCauses
            else
                NestedSetBuilder.create(
                    Order.STABLE_ORDER, createRootCause(label, configuration, exitCode)
                )
    }

    constructor(
        target: Target?,
        configuration: BuildEventId?,
        message: String?,
        rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?,
        detailedExitCode: DetailedExitCode?
    ) : this(
        if (target == null) null else target.getLocation(),
        message,
        target.getLabel(),
        configuration,
        rootCauses,
        detailedExitCode
    )

    constructor(target: Target?, message: String?) : this(
        target,  /* configuration= */
        null,
        message,  /* rootCauses= */
        null,  /* detailedExitCode= */
        null
    )

    fun getLocation(): net.starlark.java.syntax.Location? {
        return location
    }

    fun getRootCauses(): NestedSet<com.google.devtools.build.lib.causes.Cause?>? {
        return rootCauses
    }

    fun getConfiguration(): BuildEventId? {
        return configuration
    }

    override fun getDetailedExitCode(): DetailedExitCode {
        return detailedExitCode
    }

    companion object {
        private fun createDetailedExitCode(message: String?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setAnalysis(Analysis.newBuilder().setCode(Code.CONFIGURED_VALUE_CREATION_FAILED))
                    .build()
            )
        }

        private fun createRootCause(
            label: Label, configuration: BuildEventId?, detailedExitCode: DetailedExitCode
        ): AnalysisFailedCause {
            return AnalysisFailedCause(
                label,
                if (configuration == null)
                    ConfigurationId.newBuilder().setId("none").build()
                else
                    configuration.getConfiguration(),
                detailedExitCode
            )
        }
    }
}
