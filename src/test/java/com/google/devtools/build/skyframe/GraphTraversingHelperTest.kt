// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.bugreport.BugReporter
import com.google.devtools.build.lib.bugreport.BugReporter.logUnexpected
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.skyframe.SimpleSkyframeLookupResult
import com.google.devtools.build.skyframe.SomeErrorException
import com.google.devtools.build.skyframe.ValueOrUntypedException
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito

/** Tests for [GraphTraversingHelper].  */
@RunWith(JUnit4::class)
class GraphTraversingHelperTest {
    private val mockEnv: SkyFunction.Environment =
        Mockito.mock<SkyFunction.Environment>(SkyFunction.Environment::class.java)
    private val keyA: SkyKey = Mockito.mock<SkyKey>(SkyKey::class.java, "keyA")
    private val keyB: SkyKey = Mockito.mock<SkyKey>(SkyKey::class.java, "keyB")
    private val exn: SomeErrorException = SomeErrorException("")
    private val value: SkyValue? = Mockito.mock<SkyValue?>(SkyValue::class.java)

    private class SomeOtherErrorException : java.lang.Exception()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declareDependenciesAndCheckIfValuesMissing_valuesMissingBeforeCompute() {
        Mockito.`when`<T?>(mockEnv.valuesMissing()).thenReturn(true)
        Mockito.`when`<T?>(mockEnv.getValuesAndExceptions(com.google.common.collect.ImmutableSet.of<E?>(keyA)))
            .thenReturn(null)
        val valuesMissing: Boolean =
            GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
                mockEnv,
                com.google.common.collect.ImmutableSet.of<E?>(keyA),
                com.google.devtools.build.skyframe.GraphTraversingHelperTest.SomeOtherErrorException::class.java
            )
        Truth.assertThat(valuesMissing).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declareDependenciesAndCheckIfValuesMissing_valuesMissingAfterCompute() {
        val mockReporter: BugReporter? = Mockito.mock<BugReporter?>(BugReporter::class.java)
        val result: SkyframeLookupResult =
            SimpleSkyframeLookupResult(
                java.lang.Runnable {},
                java.util.function.Function { key: SkyKey? ->
                    com.google.common.collect.ImmutableMap.of<Any?, ValueOrUntypedException?>(
                        keyA,
                        ValueOrUntypedException.Companion.ofExn(exn)
                    ).get(key)
                })
        Mockito.`when`<T?>(mockEnv.getValuesAndExceptions(com.google.common.collect.ImmutableSet.of<E?>(keyA)))
            .thenReturn(result)
        val valuesMissing: Boolean =
            GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
                mockEnv,
                com.google.common.collect.ImmutableSet.of<E?>(keyA),
                com.google.devtools.build.skyframe.GraphTraversingHelperTest.SomeOtherErrorException::class.java,  /*exceptionClass2=*/
                null,
                mockReporter
            )
        Mockito.verify<BugReporter?>(mockReporter)
            .logUnexpected("Value for: '%s' was missing, this should never happen", keyA)
        Mockito.verifyNoMoreInteractions(mockReporter)
        Truth.assertThat(valuesMissing).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declareDependenciesAndCheckIfValuesMissing_notValuesMissingAfterCompute() {
        val result: SkyframeLookupResult =
            SimpleSkyframeLookupResult(
                java.lang.Runnable {},
                java.util.function.Function { key: SkyKey? ->
                    com.google.common.collect.ImmutableMap.of<Any?, ValueOrUntypedException?>(
                        keyA,
                        ValueOrUntypedException.Companion.ofExn(exn),
                        keyB,
                        ValueOrUntypedException.Companion.ofValueUntyped(value)
                    )
                        .get(key)
                })
        Mockito.`when`<T?>(mockEnv.getValuesAndExceptions(com.google.common.collect.ImmutableList.of<E?>(keyA, keyB)))
            .thenReturn(result)
        val valuesMissing: Boolean =
            GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
                mockEnv, com.google.common.collect.ImmutableList.of<E?>(keyA, keyB), SomeErrorException::class.java
            )
        Truth.assertThat(valuesMissing).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declareDependenciesAndCheckIfValuesMissing_nullAfterError_hasCorrectKeyInBugReport() {
        val result: SkyframeLookupResult =
            SimpleSkyframeLookupResult(
                java.lang.Runnable {},
                java.util.function.Function { key: SkyKey? ->
                    com.google.common.collect.ImmutableMap.of<Any?, ValueOrUntypedException?>(
                        keyA,
                        ValueOrUntypedException.Companion.ofExn(exn),
                        keyB,
                        ValueOrUntypedException.Companion.ofNull()
                    )
                        .get(key)
                })
        Mockito.`when`<T?>(mockEnv.getValuesAndExceptions(com.google.common.collect.ImmutableList.of<E?>(keyA, keyB)))
            .thenReturn(result)
        val mockReporter: BugReporter? = Mockito.mock<BugReporter?>(BugReporter::class.java)

        val valuesMissing: Boolean =
            GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
                mockEnv,
                com.google.common.collect.ImmutableList.of<E?>(keyA, keyB),
                SomeErrorException::class.java,
                null,
                mockReporter
            )

        Truth.assertThat(valuesMissing).isTrue()
        Mockito.verify<BugReporter?>(mockReporter)
            .logUnexpected("Value for: '%s' was missing, this should never happen", keyB)
        Mockito.verifyNoMoreInteractions(mockReporter)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declareDependenciesAndCheckIfValuesMissingMaybeWithExceptions_beforeCompute() {
        Mockito.`when`<T?>(mockEnv.valuesMissing()).thenReturn(true)
        Mockito.`when`<T?>(mockEnv.getValuesAndExceptions(com.google.common.collect.ImmutableSet.of<E?>(keyB)))
            .thenReturn(null)

        assertThat(
            GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissingMaybeWithExceptions(
                mockEnv, com.google.common.collect.ImmutableSet.of<E?>(keyB)
            )
        )
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declareDependenciesAndCheckIfValuesMissingMaybeWithExceptions_valuesMissing() {
        Mockito.`when`<T?>(mockEnv.getValuesAndExceptions(com.google.common.collect.ImmutableSet.of<E?>(keyA)))
            .thenReturn(
                SimpleSkyframeLookupResult(
                    java.lang.Runnable {},
                    java.util.function.Function { key: SkyKey? ->
                        com.google.common.collect.ImmutableMap.of<Any?, ValueOrUntypedException?>(
                            keyA,
                            ValueOrUntypedException.Companion.ofExn(exn)
                        ).get(key)
                    })
            )

        assertThat(
            GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissingMaybeWithExceptions(
                mockEnv, com.google.common.collect.ImmutableSet.of<E?>(keyA)
            )
        )
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun declareDependenciesAndCheckIfValuesMissingMaybeWithExceptions_notValuesMissing() {
        Mockito.`when`<T?>(mockEnv.getValuesAndExceptions(com.google.common.collect.ImmutableSet.of<E?>(keyB)))
            .thenReturn(
                SimpleSkyframeLookupResult(
                    java.lang.Runnable {},
                    java.util.function.Function { key: SkyKey? ->
                        com.google.common.collect.ImmutableMap.of<Any?, ValueOrUntypedException?>(
                            keyB,
                            ValueOrUntypedException.Companion.ofValueUntyped(value)
                        ).get(key)
                    })
            )

        assertThat(
            GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissingMaybeWithExceptions(
                mockEnv, com.google.common.collect.ImmutableSet.of<E?>(keyB)
            )
        )
            .isFalse()
    }
}
