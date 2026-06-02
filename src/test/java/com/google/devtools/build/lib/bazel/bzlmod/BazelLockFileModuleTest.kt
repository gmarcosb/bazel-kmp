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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.cmdline.Label

/** Tests for [BazelLockFileModule].  */
@RunWith(JUnit4::class)
class BazelLockFileModuleTest {
    private var extensionId: ModuleExtensionId? = null
    private var nonReproducibleResult: LockFileModuleExtension? = null
    private var reproducibleResult: LockFileModuleExtension? = null
    private var evalFactors: ModuleExtensionEvalFactors? = null
    private var otherEvalFactors: ModuleExtensionEvalFactors? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        extensionId =
            ModuleExtensionId.create(
                Label.parseCanonicalUnchecked("//:ext.bzl"), "ext", java.util.Optional.empty<T?>()
            )
        nonReproducibleResult =
            LockFileModuleExtension.builder()
                .setBzlTransitiveDigest(byteArrayOf(1, 2, 3))
                .setUsagesDigest(byteArrayOf(4, 5, 6))
                .setRecordedInputs(com.google.common.collect.ImmutableList.of<E?>())
                .setGeneratedRepoSpecs(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .build()
        reproducibleResult =
            LockFileModuleExtension.builder()
                .setBzlTransitiveDigest(byteArrayOf(1, 2, 3))
                .setUsagesDigest(byteArrayOf(4, 5, 6))
                .setRecordedInputs(com.google.common.collect.ImmutableList.of<E?>())
                .setGeneratedRepoSpecs(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .setModuleExtensionMetadata(
                    LockfileModuleExtensionMetadata.of(
                        ModuleExtensionMetadata.create(
                            Starlark.NONE,
                            Starlark.NONE,  /* reproducible= */
                            true,  /* factsObj= */
                            Dict.empty<K?, V?>()
                        )
                    )
                )
                .build()
        evalFactors = ModuleExtensionEvalFactors.create("linux", "x86_64")
        otherEvalFactors = ModuleExtensionEvalFactors.create("linux", "aarch64")
    }

    @org.junit.Test
    fun combineModuleExtensionsReproducibleFactorAdded() {
        val oldExtensionInfos: com.google.common.collect.ImmutableMap<Any?, com.google.common.collect.ImmutableMap<Any?, Any?>?> =
            com.google.common.collect.ImmutableMap.of<Any?, com.google.common.collect.ImmutableMap<Any?, Any?>?>(
                extensionId,
                com.google.common.collect.ImmutableMap.of<Any?, Any?>(evalFactors, nonReproducibleResult)
            )
        val newExtensionInfos: com.google.common.collect.ImmutableMap<Any?, Any?> =
            com.google.common.collect.ImmutableMap.of<Any?, Any?>(
                extensionId,
                WithFactors(otherEvalFactors, reproducibleResult)
            )

        assertThat(
            BazelLockFileModule.combineModuleExtensions(
                oldExtensionInfos, newExtensionInfos, { id -> true },  /* reproducible= */false
            )
        )
            .isEqualTo(oldExtensionInfos)
    }

    @org.junit.Test
    fun combineModuleExtensionsFactorBecomesReproducible() {
        val oldExtensionInfos: com.google.common.collect.ImmutableMap<Any?, com.google.common.collect.ImmutableMap<Any?, Any?>?> =
            com.google.common.collect.ImmutableMap.of<Any?, com.google.common.collect.ImmutableMap<Any?, Any?>?>(
                extensionId,
                com.google.common.collect.ImmutableMap.of<Any?, Any?>(evalFactors, nonReproducibleResult)
            )
        val newExtensionInfos: com.google.common.collect.ImmutableMap<Any?, Any?> =
            com.google.common.collect.ImmutableMap.of<Any?, Any?>(
                extensionId, WithFactors(evalFactors, reproducibleResult)
            )

        assertThat(
            BazelLockFileModule.combineModuleExtensions(
                oldExtensionInfos, newExtensionInfos, { id -> true },  /* reproducible= */false
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    fun combineModuleExtensionsFactorBecomesNonReproducible() {
        val oldExtensionInfos: com.google.common.collect.ImmutableMap<Any?, com.google.common.collect.ImmutableMap<Any?, Any?>?> =
            com.google.common.collect.ImmutableMap.of<Any?, com.google.common.collect.ImmutableMap<Any?, Any?>?>(
                extensionId,
                com.google.common.collect.ImmutableMap.of<Any?, Any?>(evalFactors, reproducibleResult)
            )
        val newExtensionInfos: com.google.common.collect.ImmutableMap<Any?, Any?> =
            com.google.common.collect.ImmutableMap.of<Any?, Any?>(
                extensionId,
                WithFactors(evalFactors, nonReproducibleResult)
            )

        assertThat(
            BazelLockFileModule.combineModuleExtensions(
                oldExtensionInfos, newExtensionInfos, { id -> true },  /* reproducible= */false
            )
        )
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<Any?, com.google.common.collect.ImmutableMap<Any?, Any?>?>(
                    extensionId,
                    com.google.common.collect.ImmutableMap.of<Any?, Any?>(evalFactors, nonReproducibleResult)
                )
            )
    }
}
