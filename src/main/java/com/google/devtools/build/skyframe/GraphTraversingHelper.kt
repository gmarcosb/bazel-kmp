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

import com.google.devtools.build.lib.bugreport.BugReporter
import com.google.devtools.build.lib.bugreport.BugReporter.logUnexpected
import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.skyframe.SkyFunction
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.SkyframeLookupResult

/**
 * Helper to check if requested nodes are done in the graph. The only method [ ][.declareDependenciesAndCheckIfValuesMissing] calls [ ][SkyFunction.Environment.getValuesAndExceptions] and checks that all nodes are done, either with
 * values or expected exceptions.
 * 
 * 
 * Only use this helper if you know what you are doing! Most Skyframe users should not need to
 * call it.
 */
object GraphTraversingHelper {
    /**
     * Returns false iff for each key in `skyKeys`, corresponding node is done with values or
     * specified `exceptionClass` errors in the Skyframe graph.
     * 
     * 
     * The exception class given cannot be a supertype or a subtype of [RuntimeException], or
     * a subtype of [InterruptedException]. See [ ][SkyFunctionException.validateExceptionType] for details.
     */
    @Throws(java.lang.InterruptedException::class)
    fun <E : java.lang.Exception?> declareDependenciesAndCheckIfValuesMissing(
        env: com.google.devtools.build.skyframe.SkyFunction.Environment,
        skyKeys: Iterable<out SkyKey?>,
        exceptionClass: java.lang.Class<E?>?
    ): Boolean {
        return GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing<E?, java.lang.Exception?>(
            env, skyKeys, exceptionClass,  /*exceptionClass2=*/null
        )
    }

    /**
     * Returns false iff for each key in `skyKeys`, the corresponding node is done with values
     * in the Skyframe graph.
     */
    @kotlin.jvm.JvmOverloads
    @Throws(java.lang.InterruptedException::class)
    fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?>
            declareDependenciesAndCheckIfValuesMissing(
        env: com.google.devtools.build.skyframe.SkyFunction.Environment,
        skyKeys: Iterable<out SkyKey?>,
        exceptionClass1: java.lang.Class<E1?>? = null,
        exceptionClass2: java.lang.Class<E2?>? = null
    ): Boolean {
        return GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing<E1?, E2?>(
            env, skyKeys, exceptionClass1, exceptionClass2, BugReporter.defaultInstance()
        )
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(java.lang.InterruptedException::class)
    fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?>
            declareDependenciesAndCheckIfValuesMissing(
        env: com.google.devtools.build.skyframe.SkyFunction.Environment,
        skyKeys: Iterable<out SkyKey?>,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        bugReporter: BugReporter
    ): Boolean {
        val result: SkyframeLookupResult = env.getValuesAndExceptions(skyKeys)
        if (env.valuesMissing()) {
            return true
        }
        for (key in skyKeys) {
            try {
                val value: SkyValue? = result.getOrThrow<E1?, E2?>(key, exceptionClass1, exceptionClass2)
                if (value == null) {
                    bugReporter.logUnexpected("Value for: '%s' was missing, this should never happen", key)
                    return true
                }
            } catch (e: java.lang.Exception) {
                if ((exceptionClass1 != null && exceptionClass1.isInstance(e))
                    || (exceptionClass2 != null && exceptionClass2.isInstance(e))
                ) {
                    continue
                }
                com.google.common.base.Throwables.throwIfUnchecked(e)
                throw java.lang.IllegalStateException(
                    "unexpected exception from " + com.google.common.collect.Iterables.toString(skyKeys), e
                )
            }
        }
        return false
    }

    /**
     * Returns false iff for each key in `skyKeys`, the corresponding node is done with values
     * in the Skyframe graph, and every node evaluated successfully without an exception.
     * 
     * 
     * Prefer [.declareDependenciesAndCheckIfValuesMissing] when possible. This method is for
     * [SkyFunction] callers that don't handle child exceptions themselves, and just want to
     * propagate child exceptions upwards via Skyframe.
     */
    @Throws(java.lang.InterruptedException::class)
    fun declareDependenciesAndCheckIfValuesMissingMaybeWithExceptions(
        env: com.google.devtools.build.skyframe.SkyFunction.Environment, skyKeys: Iterable<out SkyKey?>
    ): Boolean {
        val result: SkyframeLookupResult = env.getValuesAndExceptions(skyKeys)
        if (env.valuesMissing()) {
            return true
        }
        for (key in skyKeys) {
            if (result.get(key) == null) {
                return true
            }
        }
        return false
    }
}
