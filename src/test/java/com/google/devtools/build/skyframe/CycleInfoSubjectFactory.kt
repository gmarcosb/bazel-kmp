// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe

import com.google.common.truth.FailureMetadata
import com.google.common.truth.Truth
import com.google.devtools.build.skyframe.CycleInfoSubject

/** [Subject.Factory] for [CycleInfo], providing [CycleInfoSubject].  */
class CycleInfoSubjectFactory : com.google.common.truth.Subject.Factory<CycleInfoSubject?, CycleInfo?> {
    override fun createSubject(failureMetadata: FailureMetadata?, cycleInfo: CycleInfo?): CycleInfoSubject {
        return CycleInfoSubject(failureMetadata, cycleInfo)
    }

    companion object {
        fun assertThat(cycleInfo: CycleInfo?): CycleInfoSubject? {
            return Truth.assertAbout<CycleInfoSubject?, CycleInfo?>(CycleInfoSubjectFactory()).that(cycleInfo)
        }
    }
}
