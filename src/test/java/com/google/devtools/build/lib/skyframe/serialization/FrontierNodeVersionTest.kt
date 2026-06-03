// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.skyframe.IntVersion

@RunWith(JUnit4::class)
class FrontierNodeVersionTest {
    @org.junit.Test
    fun constructor_nullClientId_throwsNullPointerException() {
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable {
                FrontierNodeVersion(
                    "checksum",
                    com.google.common.hash.HashCode.fromInt(1),
                    byteArrayOf(1),
                    IntVersion.of(1),
                    "distinguisher",  /* useFakeStampData= */
                    false,  /* clientId= */
                    null
                )
            })
    }

    @org.junit.Test
    fun constructor_nullStarlarkSemanticsFingerprint_throwsNullPointerException() {
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable {
                FrontierNodeVersion(
                    "checksum",
                    com.google.common.hash.HashCode.fromInt(1),  /* starlarkSemanticsFingerprint= */
                    null,
                    IntVersion.of(1),
                    "distinguisher",  /* useFakeStampData= */
                    false,
                    java.util.Optional.empty<T?>()
                )
            })
    }

    @org.junit.Test
    fun constructor_validArgs_success() {
        val unused: FrontierNodeVersion =
            FrontierNodeVersion(
                "checksum",
                com.google.common.hash.HashCode.fromInt(1),
                byteArrayOf(1),
                IntVersion.of(1),
                "distinguisher",  /* useFakeStampData= */
                false,
                java.util.Optional.empty<T?>()
            )
    }
}
