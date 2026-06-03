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

import com.google.common.truth.Fact
import com.google.common.truth.FailureMetadata
import com.google.common.truth.IterableSubject
import com.google.common.truth.MapSubject
import com.google.devtools.build.skyframe.ErrorInfoSubject
import com.google.devtools.build.skyframe.ErrorInfoSubjectFactory

/**
 * [Subject] for [EvaluationResult]. Please add to this class if you need more
 * functionality!
 */
class EvaluationResultSubject(failureMetadata: FailureMetadata?, evaluationResult: EvaluationResult<*>) :
    com.google.common.truth.Subject(failureMetadata, evaluationResult) {
    private val actual: EvaluationResult<*>

    init {
        this.actual = evaluationResult
    }

    fun hasError() {
        if (!actual.hasError()) {
            failWithActual(Fact.simpleFact("expected to have error"))
        }
    }

    fun hasNoError() {
        if (actual.hasError()) {
            failWithActual(Fact.simpleFact("expected to have no error"))
        }
    }

    fun hasEntryThat(key: SkyKey): com.google.common.truth.Subject? {
        return check("get(%s)", key).that(actual.get(key))
    }

    fun hasErrorEntryForKeyThat(key: SkyKey): ErrorInfoSubject? {
        return check("getError(%s)", key)
            .about<ErrorInfoSubject?, ErrorInfo?>(ErrorInfoSubjectFactory())
            .that(actual.getError(key))
    }

    @Throws(java.lang.InterruptedException::class)
    fun hasDirectDepsInGraphThat(parent: SkyKey): IterableSubject? {
        return check("directDeps(%s)", parent)
            .that(
                actual.getWalkableGraph().getDirectDeps(com.google.common.collect.ImmutableList.of<E?>(parent))
                    .get(parent)
            )
    }

    @Throws(java.lang.InterruptedException::class)
    fun hasReverseDepsInGraphThat(child: SkyKey): IterableSubject? {
        return check("reverseDeps(%s)", child)
            .that(
                actual.getWalkableGraph().getReverseDeps(com.google.common.collect.ImmutableList.of<E?>(child))
                    .get(child)
            )
    }

    fun hasErrorMapThat(): MapSubject? {
        return check("errorMap()").that(actual.errorMap())
    }

    fun hasSingletonErrorThat(key: SkyKey): ErrorInfoSubject? {
        hasError()
        hasErrorMapThat().hasSize(1)
        check("keyNames()").that(actual.keyNames()).isEmpty()
        return hasErrorEntryForKeyThat(key)
    }
}
