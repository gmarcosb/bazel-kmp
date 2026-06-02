// Copyright 2021 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.cmdline.Label

/** A Truth [Subject] for [DeclaredExecGroup].  */
class DeclaredExecGroupSubject protected constructor(failureMetadata: FailureMetadata?, subject: DeclaredExecGroup) :
    Subject(failureMetadata, subject) {
    // Instance fields.
    private val actual: DeclaredExecGroup

    init {
        this.actual = subject
    }

    fun toolchainType(toolchainTypeLabel: String?): ToolchainTypeRequirementSubject? {
        return toolchainType(Label.parseCanonicalUnchecked(toolchainTypeLabel))
    }

    fun toolchainType(toolchainType: Label): ToolchainTypeRequirementSubject? {
        return check("toolchainType(%s)", toolchainType)
            .about<ToolchainTypeRequirementSubject?, ToolchainTypeRequirement?>(ToolchainTypeRequirementSubject.Companion.toolchainTypeRequirements())
            .that(actual.toolchainType(toolchainType))
    }

    fun hasToolchainType(toolchainTypeLabel: String?) {
        toolchainType(toolchainTypeLabel).isNotNull()
    }

    fun hasToolchainType(toolchainType: Label) {
        toolchainType(toolchainType).isNotNull()
    }

    fun execCompatibleWith(): IterableSubject? {
        return check("execCompatibleWith()")
            .that(actual.execCompatibleWith().stream().collect(Collectors.toList()))
    }

    fun hasExecCompatibleWith(constraintLabel: String?) {
        hasExecCompatibleWith(Label.parseCanonicalUnchecked(constraintLabel))
    }

    fun hasExecCompatibleWith(constraintLabel: Label?) {
        execCompatibleWith().contains(constraintLabel)
    }

    fun copiesFromDefault() {
        check("copyFromDefault()").that(actual.copyFromDefault).isTrue()
    }

    companion object {
        // Static data.
        /** Entry point for test assertions related to [DeclaredExecGroup].  */
        fun assertThat(declaredExecGroup: DeclaredExecGroup?): DeclaredExecGroupSubject? {
            return Truth.assertAbout<DeclaredExecGroupSubject?, DeclaredExecGroup?>(Subject.Factory { failureMetadata: FailureMetadata?, subject: DeclaredExecGroup? ->
                DeclaredExecGroupSubject(
                    failureMetadata,
                    subject
                )
            }).that(declaredExecGroup)
        }
    }
}
