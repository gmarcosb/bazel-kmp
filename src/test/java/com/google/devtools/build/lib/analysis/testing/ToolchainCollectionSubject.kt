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
import com.google.devtools.build.lib.analysis.ToolchainCollection

/** A Truth [Subject] for [ToolchainCollection].  */
class ToolchainCollectionSubject private constructor(
    failureMetadata: FailureMetadata?,
    subject: ToolchainCollection<*>
) : Subject(failureMetadata, subject) {
    // Instance fields.
    private val actual: ToolchainCollection<*>

    init {
        this.actual = subject
    }

    fun hasDefaultExecGroup() {
        check("hasToolchainContext()")
            .that(actual.hasToolchainContext(DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME))
            .isTrue()
    }

    fun defaultToolchainContext(): ToolchainContextSubject? {
        return check("defaultToolchainContext()")
            .about<ToolchainContextSubject?, ToolchainContext?>(ToolchainContextSubject.Companion.toolchainContexts())
            .that(actual.getDefaultToolchainContext())
    }

    fun hasExecGroup(execGroup: String) {
        check("hasToolchainContext(%s)", execGroup)
            .that(actual.getToolchainContext(execGroup))
            .isNotNull()
    }

    fun execGroup(execGroup: String): ToolchainContextSubject? {
        return check("getToolchainContext(%s)", execGroup)
            .about<ToolchainContextSubject?, ToolchainContext?>(ToolchainContextSubject.Companion.toolchainContexts())
            .that(actual.getToolchainContext(execGroup))
    }

    companion object {
        // Static data.
        /** Entry point for test assertions related to [ToolchainCollection].  */
        fun assertThat(toolchainCollection: ToolchainCollection<*>?): ToolchainCollectionSubject? {
            return Truth.assertAbout<ToolchainCollectionSubject?, ToolchainCollection<*>?>(Subject.Factory { failureMetadata: FailureMetadata?, subject: ToolchainCollection<*>? ->
                ToolchainCollectionSubject(
                    failureMetadata,
                    subject
                )
            }).that(toolchainCollection)
        }
    }
}
