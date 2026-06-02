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
package com.google.devtools.build.skyframe.state

import com.google.devtools.build.skyframe.SkyFunction.LookupEnvironment
import com.google.devtools.build.skyframe.state.StateMachine

/**
 * A state machine that outputs a value or one of three possible exceptions.
 * 
 * 
 * This class serves as a bridge between a [StateMachine] and a [SkyFunction].
 * 
 * 
 * Subclasses should call [.setValue], [.setException1], [.setException2] or
 * [.setException3] to emit results.
 */
abstract class ValueOrException3Producer<V, E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?>
    : StateMachine {
    private val driver: com.google.devtools.build.skyframe.state.Driver =
        com.google.devtools.build.skyframe.state.Driver(this)

    private var value: V? = null
    @kotlin.jvm.JvmField
    private var exception1: E1? = null
    @kotlin.jvm.JvmField
    private var exception2: E2? = null
    @kotlin.jvm.JvmField
    private var exception3: E3? = null

    /**
     * Tries to produce the result of the underlying state machine.
     * 
     * 
     * See comment of [ValueOrExceptionProducer.tryProduceValue]. If multiple exceptions are
     * set, they are prioritized by number.
     */
    @Throws(java.lang.InterruptedException::class, E1::class, E2::class, E3::class)
    fun tryProduceValue(env: LookupEnvironment?): V? {
        val done: Boolean = driver.drive(env)
        if (exception1 != null) {
            throw exception1
        }
        if (exception2 != null) {
            throw exception2
        }
        if (exception3 != null) {
            throw exception3
        }
        if (done) {
            return com.google.common.base.Preconditions.checkNotNull<V?>(value)
        }
        return null
    }

    protected fun setValue(value: V?) {
        this.value = value
    }

    protected fun setException1(exception: E1?) {
        this.exception1 = exception
    }

    protected fun getException1(): E1? {
        return exception1
    }

    protected fun setException2(exception: E2?) {
        this.exception2 = exception
    }

    protected fun getException2(): E2? {
        return exception2
    }

    protected fun setException3(exception: E3?) {
        this.exception3 = exception
    }

    protected fun getException3(): E3? {
        return exception3
    }
}
