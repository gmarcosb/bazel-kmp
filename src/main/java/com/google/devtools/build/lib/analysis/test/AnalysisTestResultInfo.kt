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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.packages.BuiltinProvider
import com.google.devtools.build.lib.starlarkbuildapi.test.AnalysisTestResultInfoApi
import com.google.devtools.build.lib.starlarkbuildapi.test.AnalysisTestResultInfoApi.AnalysisTestResultInfoProviderApi

/**
 * Encapsulates the result of analyis-phase testing. Build targets which return an instance of this
 * provider signal to the build system that it should generate 'stub' test executable.
 */
class AnalysisTestResultInfo(@kotlin.jvm.JvmField val success: Boolean?, @kotlin.jvm.JvmField val message: String?) : com.google.devtools.build.lib.packages.Info,
    AnalysisTestResultInfoApi {
    /**
     * Provider implementation for [AnalysisTestResultInfo].
     */
    class TestResultInfoProvider

        : BuiltinProvider<AnalysisTestResultInfo?>("AnalysisTestResultInfo", AnalysisTestResultInfo::class.java),
        AnalysisTestResultInfoProviderApi {
        override fun testResultInfo(success: Boolean?, message: String?): AnalysisTestResultInfoApi {
            return AnalysisTestResultInfo(success, message)
        }
    }

    companion object {
        /** Singleton provider instance for [AnalysisTestResultInfo].  */
        val provider: TestResultInfoProvider = TestResultInfoProvider()
            get() = Companion.field
    }
}
