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

import com.google.common.base.Functions
import com.google.common.collect.ImmutableMap
import com.google.common.truth.Subject
import com.google.devtools.build.lib.analysis.ToolchainContext

/** A Truth [Subject] for [ToolchainContext].  */
open class ToolchainContextSubject protected constructor(failureMetadata: FailureMetadata?, subject: ToolchainContext) :
    Subject(failureMetadata, subject) {
    // Instance fields.
    private val actual: ToolchainContext
    private val toolchainTypesMap: ImmutableMap<Label?, ToolchainTypeRequirement?>

    init {
        this.actual = subject
        this.toolchainTypesMap = makeToolchainTypesMap(subject)
    }

    @Throws(LabelSyntaxException::class)
    fun hasExecutionPlatform(platformLabel: String?) {
        hasExecutionPlatform(Label.parseCanonical(platformLabel))
    }

    fun hasExecutionPlatform(platform: Label?) {
        check("executionPlatform()").that(actual.executionPlatform()).isNotNull()
        check("executionPlatform()").that(actual.executionPlatform().label()).isEqualTo(platform)
    }

    @Throws(LabelSyntaxException::class)
    fun hasTargetPlatform(platformLabel: String?) {
        hasTargetPlatform(Label.parseCanonical(platformLabel))
    }

    fun hasTargetPlatform(platform: Label?) {
        check("targetPlatform()").that(actual.targetPlatform()).isNotNull()
        check("targetPlatform()").that(actual.targetPlatform().label()).isEqualTo(platform)
    }

    fun toolchainTypes(): MapSubject {
        return check("toolchainTypes()").that(toolchainTypesMap)
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
    }

    fun hasToolchainType(toolchainType: Label) {
        toolchainType(toolchainType).isNotNull()
    }

    fun doesntHaveToolchainType(toolchainTypeLabel: String?) {
        doesntHaveToolchainType(Label.parseCanonicalUnchecked(toolchainTypeLabel))
    }

    fun doesntHaveToolchainType(toolchainType: Label) {
        check("toolchainType(%s)", toolchainType)
            .that(toolchainTypesMap.containsKey(toolchainType))
            .isFalse()
    }

    @Throws(LabelSyntaxException::class)
    fun hasResolvedToolchain(resolvedToolchainLabel: String?) {
        hasResolvedToolchain(Label.parseCanonical(resolvedToolchainLabel))
    }

    fun hasResolvedToolchain(resolvedToolchain: Label?) {
        resolvedToolchainLabels().contains(resolvedToolchain)
    }

    fun resolvedToolchainLabels(): IterableSubject? {
        return check("resolvedToolchainLabels()").that(actual.resolvedToolchainLabels())
    }

    companion object {
        // Static data.
        /** Entry point for test assertions related to [ToolchainContext].  */
        fun assertThat(toolchainContext: ToolchainContext?): ToolchainContextSubject? {
            return Truth.assertAbout<ToolchainContextSubject?, ToolchainContext?>(Subject.Factory { failureMetadata: FailureMetadata?, subject: ToolchainContext? ->
                ToolchainContextSubject(
                    failureMetadata,
                    subject
                )
            }).that(toolchainContext)
        }

        /** Static method for getting the subject factory (for use with assertAbout()).  */
        fun toolchainContexts(): Factory<ToolchainContextSubject?, ToolchainContext?> {
            return Subject.Factory { failureMetadata: FailureMetadata?, subject: ToolchainContext? ->
                ToolchainContextSubject(
                    failureMetadata,
                    subject
                )
            }
        }

        private fun makeToolchainTypesMap(
            subject: ToolchainContext
        ): ImmutableMap<Label?, ToolchainTypeRequirement?> {
            return subject.toolchainTypes().stream()
                .collect(
                    ImmutableMap.toImmutableMap<T?, K?, V?>(
                        ToolchainTypeRequirement::toolchainType,
                        Functions.identity<E?>()
                    )
                )
        }
    }
}
