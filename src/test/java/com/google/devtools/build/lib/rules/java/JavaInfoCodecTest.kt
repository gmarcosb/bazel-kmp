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
package com.google.devtools.build.lib.rules.java

import com.google.devtools.build.lib.skyframe.serialization.AutoRegistry

@RunWith(JUnit4::class)
class JavaInfoCodecTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyJavaInfo_canBeSerializedAndDeserialized() {
        SerializationTester(JavaInfo.Companion.EMPTY_JAVA_INFO_FOR_TESTING)
            .makeMemoizingAndAllowFutureBlocking( /* allowFutureBlocking= */true)
            .setVerificationFunction({ `in`, out -> assertThat(`in`).isEqualTo(out) })
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaInfo_canBeSerializedAndDeserialized() {
        scratch.file(
            "java/com/google/test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "a",
            srcs = ["a.java"],
            deps = [":b", ":c"],
        )
        java_library(
            name = "b",
            srcs = ["b.java"],
            deps = [":d"],
        )
        java_library(
            name = "c",
            srcs = ["c.java"],
            deps = [":d"],
        )
        java_library(
            name = "d",
            srcs = ["d.java"],
        )
        
        """.trimIndent()
        )

        val inInfo: JavaInfo = JavaInfo.Companion.getJavaInfo(getConfiguredTarget("//java/com/google/test:a"))
        val outInfo: JavaInfo = roundTripWithSkyframe(inInfo) as JavaInfo

        Truth.assertThat(inInfo.getDirectRuntimeJars()).isNotEmpty()
        Truth.assertThat(inInfo.getDirectRuntimeJars()).isEqualTo(outInfo.getDirectRuntimeJars())

        val inProvider: JavaCompilationArgsProvider? =
            inInfo.getProvider<JavaCompilationArgsProvider?>(JavaCompilationArgsProvider::class.java)
        val outProvider: JavaCompilationArgsProvider? =
            outInfo.getProvider<JavaCompilationArgsProvider?>(JavaCompilationArgsProvider::class.java)
        assertThat(inProvider.runtimeJars.toList()).hasSize(4)
        assertThat(Dumper.dumpStructureWithEquivalenceReduction(inProvider.runtimeJars))
            .isEqualTo(Dumper.dumpStructureWithEquivalenceReduction(outProvider.runtimeJars))
    }

    @Throws(SerializationException::class, SkyframeDependencyException::class, MissingResultException::class)
    private fun roundTripWithSkyframe(subject: Any?): Any {
        return RoundTripping.roundTripWithSkyframe(
            createObjectCodecs(
                com.google.common.collect.ImmutableClassToInstanceMap.builder<Any?>()
                    .putAll<T?>(getCommonSerializationDependencies())
                    .putAll<Any?>(SerializationDepsUtils.SERIALIZATION_DEPS_FOR_TEST)
                    .build()
            ),
            FingerprintValueService.createForTesting(),  // Uses memoized skyframe values for resultProvider
            { k ->
                try {
                    return@roundTripWithSkyframe skyframeExecutor.getEvaluator().getExistingValue(k)
                } catch (e: java.lang.InterruptedException) {
                    throw java.lang.RuntimeException(e)
                }
            },
            subject
        )
    }

    private fun createObjectCodecs(dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>): ObjectCodecs {
        val registry: ObjectCodecRegistry = AutoRegistry.get()
        val registryBuilder: ObjectCodecRegistry.Builder = registry.getBuilder()
        for (`val` in dependencies.values) {
            registryBuilder.addReferenceConstant(`val`)
        }
        registryBuilder.addReferenceConstant(SymbolGenerator.CONSTANT_SYMBOL)
        return ObjectCodecs(registryBuilder.build(), dependencies)
    }
}
