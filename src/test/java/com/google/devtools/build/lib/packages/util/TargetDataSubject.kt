// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages.util

import com.google.common.collect.ImmutableMap
import com.google.common.truth.Subject
import com.google.devtools.build.lib.packages.TargetData
import java.util.*

/** Truth subject for [TargetData].  */
class TargetDataSubject private constructor(failureMetadata: FailureMetadata?, targetData: TargetData) :
    Subject(failureMetadata, targetData) {
    private val targetData: TargetData

    init {
        this.targetData = targetData
    }

    fun hasSamePropertiesAs(that: TargetData) {
        Truth.assertThat(toMap(targetData)).isEqualTo(toMap(that))
    }

    companion object {
        fun assertThat(targetData: TargetData?): TargetDataSubject? {
            return Truth.assertAbout<TargetDataSubject?, TargetData?>(Subject.Factory { failureMetadata: FailureMetadata?, targetData: TargetData? ->
                TargetDataSubject(
                    failureMetadata,
                    targetData
                )
            }).that(targetData)
        }

        /** A test helper to help verify that two [TargetData] instances are the same.  */
        private fun toMap(targetData: TargetData): ImmutableMap<String?, Any?> {
            return ImmutableMap.builder<String?, Any?>()
                .put("targetKind", targetData.getTargetKind())
                .put("ruleClass", targetData.getRuleClass())
                .put("label", targetData.getLabel())
                .put("isRule", targetData.isRule())
                .put("isFile", targetData.isFile())
                .put("isInputFile", targetData.isInputFile())
                .put("isOutputFile", targetData.isOutputFile())
                .put("generatingRuleLabel", Optional.ofNullable<T?>(targetData.getGeneratingRuleLabel()))
                .put("inputPath", Optional.ofNullable<T?>(targetData.getInputPath()))
                .put("deprecationWarning", Optional.ofNullable<T?>(targetData.getDeprecationWarning()))
                .put("isTestOnly", targetData.isTestOnly())
                .put("testTimeout", Optional.ofNullable<T?>(targetData.getTestTimeout()))
                .put("advertisedProviders", targetData.getAdvertisedProviders())
                .buildOrThrow()
        }
    }
}
