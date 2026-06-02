// Copyright 2022 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement

/** A Truth [Subject] for [ToolchainTypeRequirement].  */
class ToolchainTypeRequirementSubject protected constructor(
    failureMetadata: FailureMetadata?,
    subject: ToolchainTypeRequirement
) : Subject(failureMetadata, subject) {
    // Instance fields.
    private val actual: ToolchainTypeRequirement

    init {
        this.actual = subject
    }

    fun toolchainType(): ComparableSubject<Label?>? {
        return check("toolchainType").that(actual.toolchainType())
    }

    val isMandatory: Unit
        get() {
            check("mandatory").that(actual.mandatory()).isTrue()
        }

    val isOptional: Unit
        get() {
            check("mandatory").that(actual.mandatory()).isFalse()
        }

    companion object {
        // Static data.
        /** Entry point for test assertions related to [ToolchainTypeRequirement].  */
        fun assertThat(
            toolchainTypeRequirement: ToolchainTypeRequirement?
        ): ToolchainTypeRequirementSubject? {
            return Truth.assertAbout<ToolchainTypeRequirementSubject?, ToolchainTypeRequirement?>(Subject.Factory { failureMetadata: FailureMetadata?, subject: ToolchainTypeRequirement? ->
                ToolchainTypeRequirementSubject(
                    failureMetadata,
                    subject
                )
            }).that(toolchainTypeRequirement)
        }

        /** Static method for getting the subject factory (for use with assertAbout()).  */
        fun toolchainTypeRequirements(): Factory<ToolchainTypeRequirementSubject?, ToolchainTypeRequirement?> {
            return Subject.Factory { failureMetadata: FailureMetadata?, subject: ToolchainTypeRequirement? ->
                ToolchainTypeRequirementSubject(
                    failureMetadata,
                    subject
                )
            }
        }
    }
}
