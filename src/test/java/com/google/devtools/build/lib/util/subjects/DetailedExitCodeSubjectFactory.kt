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

/**
 * [Subject.Factory] for [DetailedExitCode] objects, providing [ ]s.
 */
class DetailedExitCodeSubjectFactory

    : Subject.Factory<DetailedExitCodeSubject?, DetailedExitCode?> {
    override fun createSubject(metadata: FailureMetadata?, actual: DetailedExitCode?): DetailedExitCodeSubject {
        return DetailedExitCodeSubject(metadata, actual)
    }

    companion object {
        fun assertThatDetailedExitCode(code: DetailedExitCode?): DetailedExitCodeSubject? {
            return Truth.assertAbout<DetailedExitCodeSubject?, DetailedExitCode?>(DetailedExitCodeSubjectFactory())
                .that(code)
        }
    }
}
