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
package com.google.devtools.build.lib.analysis.testing

import com.google.common.truth.Subject
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo

/** A Truth [Subject] for [ToolchainInfo].  */
class ToolchainInfoSubject private constructor(failureMetadata: FailureMetadata?, subject: ToolchainInfo) :
    Subject(failureMetadata, subject) {
    // Instance fields.
    private val actual: ToolchainInfo

    init {
        this.actual = subject
    }

    @Throws(EvalException::class)
    fun getValue(name: String): Subject? {
        return check("getValue(%s)", name).that(actual.getValue(name))
    }

    companion object {
        // Static data.
        /** Entry point for test assertions related to [ToolchainInfo].  */
        fun assertThat(toolchainInfo: ToolchainInfo?): ToolchainInfoSubject? {
            return Truth.assertAbout<ToolchainInfoSubject?, ToolchainInfo?>(Subject.Factory { failureMetadata: FailureMetadata?, subject: ToolchainInfo? ->
                ToolchainInfoSubject(
                    failureMetadata,
                    subject
                )
            }).that(toolchainInfo)
        }

        /** Static method for getting the subject factory (for use with assertAbout()).  */
        fun toolchainInfos(): Factory<ToolchainInfoSubject?, ToolchainInfo?> {
            return Subject.Factory { failureMetadata: FailureMetadata?, subject: ToolchainInfo? ->
                ToolchainInfoSubject(
                    failureMetadata,
                    subject
                )
            }
        }
    }
}
