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
import com.google.common.truth.IterableSubject

/**
 * [Subject] for [NodeEntry]. Please add to this class if you need more functionality!
 */
class NodeEntrySubject internal constructor(failureMetadata: FailureMetadata?, nodeEntry: NodeEntry) :
    com.google.common.truth.Subject(failureMetadata, nodeEntry) {
    private val actual: NodeEntry

    init {
        this.actual = nodeEntry
    }

    fun hasVersionThat(): com.google.common.truth.Subject? {
        return check("getVersion()").that(actual.getVersion())
    }

    fun hasTemporaryDirectDepsThat(): IterableSubject {
        return check("getTemporaryDirectDeps()")
            .that(com.google.common.collect.Iterables.concat(actual.getTemporaryDirectDeps()))
    }

    @Throws(java.lang.InterruptedException::class)
    fun addReverseDepAndCheckIfDone(reverseDep: SkyKey?): com.google.common.truth.Subject? {
        return check("addReverseDepAndCheckIfDone()")
            .that(actual.addReverseDepAndCheckIfDone(reverseDep))
    }
}
