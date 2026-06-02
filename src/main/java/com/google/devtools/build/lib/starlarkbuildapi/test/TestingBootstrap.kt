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
package com.google.devtools.build.lib.starlarkbuildapi.test

import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.starlarkbuildapi.core.Bootstrap
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi
import com.google.devtools.build.lib.starlarkbuildapi.test.AnalysisFailureInfoApi.AnalysisFailureInfoProviderApi
import com.google.devtools.build.lib.starlarkbuildapi.test.AnalysisTestResultInfoApi.AnalysisTestResultInfoProviderApi

/** [Bootstrap] for Starlark objects related to testing.  */
class TestingBootstrap(
    private val testingModule: TestingModuleApi?,
    private val coverageCommon: CoverageCommonApi<*, *>?,
    private val instrumentedFilesInfoProvider: ProviderApi?,
    private val analysisFailureInfoProvider: AnalysisFailureInfoProviderApi?,
    private val testResultInfoProvider: AnalysisTestResultInfoProviderApi?
) : Bootstrap {
    override fun addBindingsToBuilder(builder: ImmutableMap.Builder<String?, Any?>) {
        builder.put("testing", testingModule)
        builder.put("coverage_common", coverageCommon)
        builder.put("InstrumentedFilesInfo", instrumentedFilesInfoProvider)
        builder.put("AnalysisFailureInfo", analysisFailureInfoProvider)
        builder.put("AnalysisTestResultInfo", testResultInfoProvider)
    }
}
