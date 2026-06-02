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

import com.google.common.base.Functions
import com.google.common.collect.ImmutableMap
import com.google.common.truth.Subject
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement

/** A Truth [Subject] for [StarlarkDefinedAspect].  */
class StarlarkDefinedAspectSubject protected constructor(
    failureMetadata: FailureMetadata?,
    subject: StarlarkDefinedAspect
) : Subject(failureMetadata, subject) {
    private val toolchainTypesMap: MutableMap<Label?, ToolchainTypeRequirement?>

    init {
        this.toolchainTypesMap = makeToolchainTypesMap(subject)
    }

    fun toolchainType(toolchainTypeLabel: String?): ToolchainTypeRequirementSubject? {
        return toolchainType(Label.parseCanonicalUnchecked(toolchainTypeLabel))
    }

    fun toolchainType(toolchainType: Label): ToolchainTypeRequirementSubject? {
        return check("toolchainType(%s)", toolchainType)
            .about<ToolchainTypeRequirementSubject?, ToolchainTypeRequirement?>(ToolchainTypeRequirementSubject.Companion.toolchainTypeRequirements())
            .that(toolchainTypesMap.get(toolchainType))
    }

    fun hasToolchainType(toolchainTypeLabel: String?) {
        toolchainType(toolchainTypeLabel).isNotNull()
    } // TODO(blaze-team): Add more useful methods.

    companion object {
        // Static data.
        /** Entry point for test assertions related to [StarlarkDefinedAspect].  */
        fun assertThat(
            starlarkDefinedAspect: StarlarkDefinedAspect?
        ): StarlarkDefinedAspectSubject? {
            return Truth.assertAbout<StarlarkDefinedAspectSubject?, StarlarkDefinedAspect?>(Subject.Factory { failureMetadata: FailureMetadata?, subject: StarlarkDefinedAspect? ->
                StarlarkDefinedAspectSubject(
                    failureMetadata,
                    subject
                )
            }).that(starlarkDefinedAspect)
        }

        private fun makeToolchainTypesMap(
            subject: StarlarkDefinedAspect
        ): ImmutableMap<Label?, ToolchainTypeRequirement?> {
            return subject.getToolchainTypes().stream()
                .collect(
                    ImmutableMap.toImmutableMap<T?, K?, V?>(
                        ToolchainTypeRequirement::toolchainType,
                        Functions.identity<E?>()
                    )
                )
        }
    }
}
