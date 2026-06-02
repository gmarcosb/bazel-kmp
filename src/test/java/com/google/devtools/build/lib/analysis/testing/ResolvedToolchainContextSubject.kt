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
import com.google.devtools.build.lib.analysis.ResolvedToolchainContext

/** A Truth [Subject] for [ResolvedToolchainContext].  */
class ResolvedToolchainContextSubject private constructor(
    failureMetadata: FailureMetadata?,
    subject: ResolvedToolchainContext
) : ToolchainContextSubject(failureMetadata, subject) {
    // Instance fields.
    private val actual: ResolvedToolchainContext

    init {
        this.actual = subject
    }

    fun forToolchainType(toolchainType: Label): ToolchainInfoSubject? {
        return check("forToolchainType(%s)", toolchainType)
            .about<ToolchainInfoSubject?, ToolchainInfo?>(ToolchainInfoSubject.Companion.toolchainInfos())
            .that(actual.forToolchainType(toolchainType))
    }

    companion object {
        // Static data.
        /** Entry point for test assertions related to [ResolvedToolchainContext].  */
        fun assertThat(
            resolvedToolchainContext: ResolvedToolchainContext?
        ): ResolvedToolchainContextSubject? {
            return Truth.assertAbout<ResolvedToolchainContextSubject?, ResolvedToolchainContext?>(
                RESOLVED_TOOLCHAIN_CONTEXT_SUBJECT_FACTORY
            ).that(resolvedToolchainContext)
        }

        val RESOLVED_TOOLCHAIN_CONTEXT_SUBJECT_FACTORY: Factory<ResolvedToolchainContextSubject?, ResolvedToolchainContext?> =
            Subject.Factory { failureMetadata: FailureMetadata?, subject: ResolvedToolchainContext? ->
                ResolvedToolchainContextSubject(
                    failureMetadata,
                    subject
                )
            }
    }
}
