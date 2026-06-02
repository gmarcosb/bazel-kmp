// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [ExecutionRequirements].  */
@RunWith(JUnit4::class)
class ExecutionRequirementsTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseResources_empty() {
        assertThat(ExecutionRequirements.parseResources(com.google.common.collect.ImmutableMap.of<K?, V?>())).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseResources_ignoresUnrelatedKeys() {
        assertThat(
            ExecutionRequirements.parseResources(
                com.google.common.collect.ImmutableMap.of<K?, V?>("pool", "default", "no-sandbox", "", "local", "")
            )
        )
            .isEmpty()
    }

    // exec_properties format: key = "resources:name", value = "amount"
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseResources_execProp_cpu() {
        assertThat(
            ExecutionRequirements.parseResources(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "resources:cpu",
                    "4"
                )
            )
        )
            .containsExactly("cpu", 4.0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseResources_execProp_memory() {
        assertThat(
            ExecutionRequirements.parseResources(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "resources:memory",
                    "2000"
                )
            )
        )
            .containsExactly("memory", 2000.0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseResources_execProp_multiple() {
        assertThat(
            ExecutionRequirements.parseResources(
                com.google.common.collect.ImmutableMap.of<K?, V?>("resources:cpu", "8", "resources:memory", "4000")
            )
        )
            .containsExactly("cpu", 8.0, "memory", 4000.0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseResources_execProp_customResource() {
        assertThat(
            ExecutionRequirements.parseResources(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "resources:gpu",
                    "2"
                )
            )
        )
            .containsExactly("gpu", 2.0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseResources_execProp_floatingPoint() {
        assertThat(
            ExecutionRequirements.parseResources(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "resources:cpu",
                    "2.5"
                )
            )
        )
            .containsExactly("cpu", 2.5)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseResources_execProp_mixedKeys() {
        assertThat(
            ExecutionRequirements.parseResources(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "pool", "default",
                    "resources:cpu", "4",
                    "container-image", "docker://foo",
                    "resources:gpu", "1"
                )
            )
        )
            .containsExactly("cpu", 4.0, "gpu", 1.0)
    }

    // Tag format: key = "resources:name:amount" or "cpu:amount", value = ""
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseResources_tag_resources() {
        assertThat(
            ExecutionRequirements.parseResources(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "resources:cpu:4",
                    ""
                )
            )
        )
            .containsExactly("cpu", 4.0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseResources_tag_cpu() {
        assertThat(ExecutionRequirements.parseResources(com.google.common.collect.ImmutableMap.of<K?, V?>("cpu:2", "")))
            .containsExactly("cpu", 2.0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseResources_tag_customResource() {
        assertThat(
            ExecutionRequirements.parseResources(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "resources:gpu:3",
                    ""
                )
            )
        )
            .containsExactly("gpu", 3.0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseResources_tag_multiple() {
        assertThat(
            ExecutionRequirements.parseResources(
                com.google.common.collect.ImmutableMap.of<K?, V?>("resources:gpu:2", "", "cpu:4", "")
            )
        )
            .containsExactly("gpu", 2.0, "cpu", 4.0)
    }

    // Validation
    @org.junit.Test
    fun parseResources_throwsOnTagMissingAmount() {
        org.junit.Assert.assertThrows<T?>(
            UserExecException::class.java,
            org.junit.function.ThrowingRunnable {
                ExecutionRequirements.parseResources(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "resources:cpu:",
                        ""
                    )
                )
            })
    }

    @org.junit.Test
    fun parseResources_throwsOnExecPropMissingAmount() {
        org.junit.Assert.assertThrows<T?>(
            UserExecException::class.java,
            org.junit.function.ThrowingRunnable {
                ExecutionRequirements.parseResources(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "resources:cpu",
                        ""
                    )
                )
            })
    }

    @org.junit.Test
    fun parseResources_throwsOnInvalidTagValue() {
        org.junit.Assert.assertThrows<T?>(
            UserExecException::class.java,
            org.junit.function.ThrowingRunnable {
                ExecutionRequirements.parseResources(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "resources:cpu:notanumber",
                        ""
                    )
                )
            })
    }

    @org.junit.Test
    fun parseResources_throwsOnInvalidExecPropValue() {
        org.junit.Assert.assertThrows<T?>(
            UserExecException::class.java,
            org.junit.function.ThrowingRunnable {
                ExecutionRequirements.parseResources(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "resources:cpu",
                        "notanumber"
                    )
                )
            })
    }

    @org.junit.Test
    fun parseResources_throwsOnNegativeTagValue() {
        org.junit.Assert.assertThrows<T?>(
            UserExecException::class.java,
            org.junit.function.ThrowingRunnable {
                ExecutionRequirements.parseResources(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "resources:cpu:-1",
                        ""
                    )
                )
            })
    }

    @org.junit.Test
    fun parseResources_throwsOnDuplicateResource() {
        org.junit.Assert.assertThrows<T?>(
            UserExecException::class.java,
            org.junit.function.ThrowingRunnable {
                ExecutionRequirements.parseResources(
                    com.google.common.collect.ImmutableMap.of<K?, V?>("resources:cpu:4", "", "cpu:2", "")
                )
            })
    }
}
