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

import com.google.devtools.build.lib.skyframe.serialization.ErrorMessageHelper.MAX_ERRORS_TO_REPORT

@RunWith(JUnit4::class)
class ErrorMessageHelperTest {
    @get:org.junit.Test
    val errorMessage_noExceptions: Unit
        get() {
            val message: String? = getErrorMessage(com.google.common.collect.ImmutableList.of<E?>())
            Truth.assertThat(message).isEmpty()
        }

    @get:org.junit.Test
    val errorMessage_singleException: Unit
        get() {
            val message: String? =
                getErrorMessage(com.google.common.collect.ImmutableList.of<E?>(java.lang.RuntimeException("Test exception")))
            Truth.assertThat(message).contains("Test exception")
        }

    @get:org.junit.Test
    val errorMessage_multipleExceptions: Unit
        get() {
            val message: String? =
                getErrorMessage(
                    com.google.common.collect.ImmutableList.of<E?>(
                        java.lang.RuntimeException("Test exception 1"),
                        java.lang.RuntimeException("Test exception 2")
                    )
                )
            Truth.assertThat(message).contains("Test exception 1")
            Truth.assertThat(message).contains("Test exception 2")
        }

    @get:org.junit.Test
    val errorMessage_exactLimitExceptions: Unit
        get() {
            val exceptions: com.google.common.collect.ImmutableList.Builder<Throwable?> =
                com.google.common.collect.ImmutableList.builder<Throwable?>()
            for (i in 0..<MAX_ERRORS_TO_REPORT) {
                exceptions.add(java.lang.RuntimeException("Error " + i))
            }
            val message: String? = getErrorMessage(exceptions.build())
            Truth.assertThat(message).contains("There were 5 write errors.")
            Truth.assertThat(message).doesNotContain("Only the first")
            for (i in 0..<MAX_ERRORS_TO_REPORT) {
                Truth.assertThat(message).contains("Error " + i)
            }
        }

    @get:org.junit.Test
    val errorMessage_moreThanLimitExceptions: Unit
        get() {
            val exceptions: com.google.common.collect.ImmutableList.Builder<Throwable?> =
                com.google.common.collect.ImmutableList.builder<Throwable?>()
            for (i in 0..5) {
                exceptions.add(java.lang.RuntimeException("Error " + i))
            }
            val message: String? = getErrorMessage(exceptions.build())
            Truth.assertThat(message).contains("There were 6 write errors. Only the first 5 will be reported.")
            for (i in 0..<MAX_ERRORS_TO_REPORT) {
                Truth.assertThat(message).contains("Error " + i)
            }
            Truth.assertThat(message).doesNotContain("Error 5")
        }
}
