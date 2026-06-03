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
package com.google.devtools.build.lib.util.subjects

import com.google.common.truth.Subject
import com.google.devtools.build.lib.util.DetailedExitCode

/** A Truth-compatible [Subject] for [DetailedExitCode].  */
class DetailedExitCodeSubject(failureMetadata: FailureMetadata?, exitCode: DetailedExitCode) :
    Subject(failureMetadata, exitCode) {
    private val actual: DetailedExitCode

    init {
        this.actual = exitCode
    }

    fun hasExitCode(exitCode: ExitCode?) {
        isNotNull()
        check("getExitCode()").that(actual.getExitCode()).isEqualTo(exitCode)
    }

    val isSuccessful: Unit
        get() {
            if (!actual.isSuccess()) {
                failWithActual(Fact.simpleFact("expected to be SUCCESS"))
            }
        }

    val isNotSuccessful: Unit
        get() {
            if (actual.isSuccess()) {
                failWithActual(Fact.simpleFact("expected *not* to be SUCCESS"))
            }
        }
}
