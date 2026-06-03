// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization

import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.Executors

@RunWith(JUnit4::class)
class FingerprintValueServiceTest {
    @org.junit.Test
    fun fingerprint_isConsistent() {
        val service: FingerprintValueService =
            FingerprintValueService(
                Executors.newSingleThreadExecutor(),
                FingerprintValueStore.inMemoryStore(),
                FingerprintValueCache(),
                FingerprintValueService.NONPROD_FINGERPRINTER
            )

        assertThat(service.fingerprintPlaceholder().toBytes().length).isEqualTo(16)
        assertThat(service.fingerprintLength()).isEqualTo(16)

        val testValue = byteArrayOf(0, 1, 2)
        val testFingerprint: PackedFingerprint = service.fingerprint(testValue)

        assertThat(testFingerprint).isNotEqualTo(service.fingerprintPlaceholder())
        assertThat(testFingerprint.toBytes().length).isEqualTo(16)
    }

    @org.junit.Test
    fun executor_passesThrough() {
        val executor: java.util.concurrent.Executor = Executors.newSingleThreadExecutor()
        val service: FingerprintValueService =
            FingerprintValueService(
                executor,
                FingerprintValueStore.inMemoryStore(),
                FingerprintValueCache(),
                FingerprintValueService.NONPROD_FINGERPRINTER
            )
        assertThat(service.getExecutor()).isSameInstanceAs(executor)
    }
}
