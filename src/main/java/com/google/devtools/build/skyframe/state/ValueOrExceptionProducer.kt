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
package com.google.devtools.build.skyframe.state

import com.google.devtools.build.skyframe.SkyFunction.LookupEnvironment
import com.google.devtools.build.skyframe.state.StateMachine

/**
 * A state machine that outputs a value or exception.
 * 
 * 
 * This class serves as a bridge between a [StateMachine] and a [SkyFunction].
 * 
 * 
 * Subclasses should call [.setValue] or [.setException] to emit results.
 * 
 * 
 * The parameter `V` must not be an exception type.
 */
abstract class ValueOrExceptionProducer<V, E : java.lang.Exception?> : StateMachine {
    private val driver: com.google.devtools.build.skyframe.state.Driver =
        com.google.devtools.build.skyframe.state.Driver(this)

    /** Will be of type `V` or `E`.  */
    private var result: Any? = null

    /**
     * Tries to produce the result of the underlying state machine.
     * 
     * 
     * Note that during error bubbling, the machine may discover and process an input error, even
     * with missing inputs, meaning that exceptions may be thrown before the machine considers itself
     * complete.
     * 
     * 
     * If both an error and value are set, the exception will take priority.
     * 
     * @return null if the underlying state machine did not complete (due to missing inputs).
     */
    @Throws(java.lang.InterruptedException::class, E::class)
    fun tryProduceValue(env: LookupEnvironment?): V? {
        val done: Boolean = driver.drive(env)
        if (result is java.lang.Exception) {
            throw result as E
        }
        if (done) {
            return com.google.common.base.Preconditions.checkNotNull<V?>(result as V?)
        }
        return null
    }

    protected fun setValue(value: V?) {
        this.result = value
    }

    protected fun setException(exception: E?) {
        this.result = exception
    }

    protected fun getException(): E? {
        if (result is java.lang.Exception) {
            return result as E
        }
        return null
    }
}
