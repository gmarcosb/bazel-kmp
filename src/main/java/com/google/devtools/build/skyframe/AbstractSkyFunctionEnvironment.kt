// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.skyframe.SkyFunction
import com.google.devtools.build.skyframe.SkyFunctionException
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/**
 * Basic implementation of [SkyFunction.Environment] in which all convenience methods delegate
 * to a single abstract method.
 */
internal abstract class AbstractSkyFunctionEnvironment : com.google.devtools.build.skyframe.SkyFunction.Environment {
    @kotlin.jvm.JvmField
    protected var valuesMissing: Boolean = false
    var externalDeps: MutableList<com.google.common.util.concurrent.ListenableFuture<*>?>? = null

    @Throws(java.lang.InterruptedException::class)
    override fun getValue(depKey: SkyKey?): SkyValue? {
        return getValueOrThrowInternal<java.lang.RuntimeException?, java.lang.RuntimeException?, java.lang.RuntimeException?, java.lang.RuntimeException?>(
            depKey,
            null,
            null,
            null,
            null
        )
    }

    @Throws(E::class, java.lang.InterruptedException::class)
    override fun <E : java.lang.Exception?> getValueOrThrow(
        depKey: SkyKey?, exceptionClass: java.lang.Class<E?>
    ): SkyValue? {
        SkyFunctionException.Companion.validateExceptionType<E?>(exceptionClass)
        return getValueOrThrowInternal<E?, java.lang.RuntimeException?, java.lang.RuntimeException?, java.lang.RuntimeException?>(
            depKey,
            exceptionClass,
            null,
            null,
            null
        )
    }

    @Throws(E1::class, E2::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?> getValueOrThrow(
        depKey: SkyKey?, exceptionClass1: java.lang.Class<E1?>, exceptionClass2: java.lang.Class<E2?>
    ): SkyValue? {
        SkyFunctionException.Companion.validateExceptionType<E1?>(exceptionClass1)
        SkyFunctionException.Companion.validateExceptionType<E2?>(exceptionClass2)
        return getValueOrThrowInternal<E1?, E2?, java.lang.RuntimeException?, java.lang.RuntimeException?>(
            depKey,
            exceptionClass1,
            exceptionClass2,
            null,
            null
        )
    }

    @Throws(E1::class, E2::class, E3::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?>
            getValueOrThrow(
        depKey: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>,
        exceptionClass2: java.lang.Class<E2?>,
        exceptionClass3: java.lang.Class<E3?>
    ): SkyValue? {
        SkyFunctionException.Companion.validateExceptionType<E1?>(exceptionClass1)
        SkyFunctionException.Companion.validateExceptionType<E2?>(exceptionClass2)
        SkyFunctionException.Companion.validateExceptionType<E3?>(exceptionClass3)
        return getValueOrThrowInternal<E1?, E2?, E3?, java.lang.RuntimeException?>(
            depKey,
            exceptionClass1,
            exceptionClass2,
            exceptionClass3,
            null
        )
    }

    @Throws(E1::class, E2::class, E3::class, E4::class, java.lang.InterruptedException::class)
    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?, E4 : java.lang.Exception?>
            getValueOrThrow(
        depKey: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>,
        exceptionClass2: java.lang.Class<E2?>,
        exceptionClass3: java.lang.Class<E3?>,
        exceptionClass4: java.lang.Class<E4?>
    ): SkyValue? {
        SkyFunctionException.Companion.validateExceptionType<E1?>(exceptionClass1)
        SkyFunctionException.Companion.validateExceptionType<E2?>(exceptionClass2)
        SkyFunctionException.Companion.validateExceptionType<E3?>(exceptionClass3)
        SkyFunctionException.Companion.validateExceptionType<E4?>(exceptionClass4)
        return getValueOrThrowInternal<E1?, E2?, E3?, E4?>(
            depKey, exceptionClass1, exceptionClass2, exceptionClass3, exceptionClass4
        )
    }

    @com.google.errorprone.annotations.ForOverride
    @Throws(E1::class, E2::class, E3::class, E4::class, java.lang.InterruptedException::class)
    abstract fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?, E4 : java.lang.Exception?>
            getValueOrThrowInternal(
        depKey: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?,
        exceptionClass4: java.lang.Class<E4?>?
    ): SkyValue?

    override fun valuesMissing(): Boolean {
        return valuesMissing || externalDeps != null
    }

    override fun dependOnFuture(future: com.google.common.util.concurrent.ListenableFuture<*>) {
        if (future.isDone()) {
            // No need to track a dependency on something that's already done.
            return
        }
        if (externalDeps == null) {
            externalDeps = java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<*>?>()
        }
        externalDeps!!.add(future)
    }
}
