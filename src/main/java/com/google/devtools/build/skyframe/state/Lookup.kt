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
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.SkyframeLookupResult.QueryDepCallback
import com.google.devtools.build.skyframe.state.StateMachine.ValueOrException2Sink
import com.google.devtools.build.skyframe.state.StateMachine.ValueOrException3Sink
import com.google.devtools.build.skyframe.state.StateMachine.ValueOrExceptionSink
import com.google.devtools.build.skyframe.state.TaskTreeNode

/** Captures information about a lookup requested by a state machine.  */
internal abstract class Lookup private constructor(parent: TaskTreeNode, key: SkyKey?) : QueryDepCallback {
    private val parent: TaskTreeNode
    val key: SkyKey?

    init {
        this.parent = parent
        this.key = key
    }

    fun key(): SkyKey? {
        return key
    }

    /**
     * Performs a lookup directly against the environment.
     * 
     * 
     * This is more efficient than [LookupEnvironment.getValuesAndExceptions] when there is
     * only one key at a time.
     * 
     * @return true if a value was available or an exception was handled. Note: this is false for
     * unhandled exceptions.
     */
    @Throws(java.lang.InterruptedException::class)
    abstract fun doLookup(env: LookupEnvironment?): Boolean

    override fun acceptValue(unusedKey: SkyKey?, value: SkyValue?) {
        acceptValueInternal(value)
        parent.signalChildDoneAndEnqueueIfReady()
    }

    @com.google.errorprone.annotations.ForOverride
    protected abstract fun acceptValueInternal(value: SkyValue?)

    override fun tryHandleException(unusedKey: SkyKey?, exception: java.lang.Exception?): Boolean {
        val handled = tryHandleExceptionInternal(exception)
        if (handled) {
            parent.signalChildDoneAndEnqueueIfReady()
        }
        return handled
    }

    @com.google.errorprone.annotations.ForOverride
    protected abstract fun tryHandleExceptionInternal(exception: java.lang.Exception?): Boolean

    internal class ConsumerLookup(parent: TaskTreeNode, key: SkyKey?, sink: java.util.function.Consumer<SkyValue?>) :
        Lookup(parent, key) {
        private val sink: java.util.function.Consumer<SkyValue?>

        init {
            this.sink = sink
        }

        @Throws(java.lang.InterruptedException::class)
        override fun doLookup(env: LookupEnvironment): Boolean {
            val value: SkyValue? = env.getValue(key)
            if (value == null) {
                return false
            }
            acceptValue(key, value)
            return true
        }

        override fun acceptValueInternal(value: SkyValue?) {
            sink.accept(value)
        }

        override fun tryHandleExceptionInternal(unusedException: java.lang.Exception?): Boolean {
            return false
        }
    }

    internal class ValueOrExceptionLookup<E : java.lang.Exception?>(
        parent: TaskTreeNode,
        key: SkyKey?,
        exceptionClass: java.lang.Class<E?>,
        sink: ValueOrExceptionSink<E?>
    ) : Lookup(parent, key) {
        private val exceptionClass: java.lang.Class<E?>
        private val sink: ValueOrExceptionSink<E?>

        init {
            this.exceptionClass = exceptionClass
            this.sink = sink
        }

        @Throws(java.lang.InterruptedException::class)
        override fun doLookup(env: LookupEnvironment): Boolean {
            val value: SkyValue?
            try {
                if ((env.getValueOrThrow<E?>(key(), exceptionClass).also { value = it }) == null) {
                    return false
                }
                acceptValue(key, value)
            } catch (e: java.lang.Exception) {
                if (e is java.lang.InterruptedException) {
                    throw e
                }
                if (!tryHandleException(key, e)) {
                    throw java.lang.IllegalArgumentException("Unexpected exception for " + key(), e)
                }
            }
            return true
        }

        override fun acceptValueInternal(value: SkyValue?) {
            sink.acceptValueOrException(value,  /* exception= */null)
        }

        override fun tryHandleExceptionInternal(exception: java.lang.Exception?): Boolean {
            if (exceptionClass.isInstance(exception)) {
                sink.acceptValueOrException( /* value= */null, exceptionClass.cast(exception))
                return true
            }
            return false
        }
    }

    internal class ValueOrException2Lookup<E1 : java.lang.Exception?, E2 : java.lang.Exception?>
        (
        parent: TaskTreeNode,
        key: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>,
        exceptionClass2: java.lang.Class<E2?>,
        sink: ValueOrException2Sink<E1?, E2?>
    ) : Lookup(parent, key) {
        private val exceptionClass1: java.lang.Class<E1?>
        private val exceptionClass2: java.lang.Class<E2?>
        private val sink: ValueOrException2Sink<E1?, E2?>

        init {
            this.exceptionClass1 = exceptionClass1
            this.exceptionClass2 = exceptionClass2
            this.sink = sink
        }

        @Throws(java.lang.InterruptedException::class)
        override fun doLookup(env: LookupEnvironment): Boolean {
            val value: SkyValue?
            try {
                if ((env.getValueOrThrow<E1?, E2?>(key(), exceptionClass1, exceptionClass2)
                        .also { value = it }) == null
                ) {
                    return false
                }
                acceptValue(key, value)
            } catch (e: java.lang.Exception) {
                if (e is java.lang.InterruptedException) {
                    throw e
                }
                if (!tryHandleException(key, e)) {
                    throw java.lang.IllegalArgumentException("Unexpected exception for " + key(), e)
                }
            }
            return true
        }

        override fun acceptValueInternal(value: SkyValue?) {
            sink.acceptValueOrException2(value,  /* e1= */null,  /* e2= */null)
        }

        override fun tryHandleExceptionInternal(exception: java.lang.Exception?): Boolean {
            if (exceptionClass1.isInstance(exception)) {
                sink.acceptValueOrException2( /* value= */
                    null, exceptionClass1.cast(exception),  /* e2= */null
                )
                return true
            }
            if (exceptionClass2.isInstance(exception)) {
                sink.acceptValueOrException2( /* value= */
                    null,  /* e1= */null, exceptionClass2.cast(exception)
                )
                return true
            }
            return false
        }
    }

    internal class ValueOrException3Lookup<E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?>
        (
        parent: TaskTreeNode,
        key: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>,
        exceptionClass2: java.lang.Class<E2?>,
        exceptionClass3: java.lang.Class<E3?>,
        sink: ValueOrException3Sink<E1?, E2?, E3?>
    ) : Lookup(parent, key) {
        private val exceptionClass1: java.lang.Class<E1?>
        private val exceptionClass2: java.lang.Class<E2?>
        private val exceptionClass3: java.lang.Class<E3?>
        private val sink: ValueOrException3Sink<E1?, E2?, E3?>

        init {
            this.exceptionClass1 = exceptionClass1
            this.exceptionClass2 = exceptionClass2
            this.exceptionClass3 = exceptionClass3
            this.sink = sink
        }

        @Throws(java.lang.InterruptedException::class)
        override fun doLookup(env: LookupEnvironment): Boolean {
            val value: SkyValue?
            try {
                if ((env.getValueOrThrow<E1?, E2?, E3?>(key(), exceptionClass1, exceptionClass2, exceptionClass3)
                        .also { value = it })
                    == null
                ) {
                    return false
                }
                acceptValue(key, value)
            } catch (e: java.lang.Exception) {
                if (e is java.lang.InterruptedException) {
                    throw e
                }
                if (!tryHandleException(key, e)) {
                    throw java.lang.IllegalArgumentException("Unexpected exception for " + key(), e)
                }
            }
            return true
        }

        override fun acceptValueInternal(value: SkyValue?) {
            sink.acceptValueOrException3(value,  /* e1= */null,  /* e2= */null,  /* e3= */null)
        }

        override fun tryHandleExceptionInternal(exception: java.lang.Exception?): Boolean {
            if (exceptionClass1.isInstance(exception)) {
                sink.acceptValueOrException3( /* value= */
                    null, exceptionClass1.cast(exception),  /* e2= */null,  /* e3= */null
                )
                return true
            }
            if (exceptionClass2.isInstance(exception)) {
                sink.acceptValueOrException3( /* value= */
                    null,  /* e1= */null, exceptionClass2.cast(exception),  /* e3= */null
                )
                return true
            }
            if (exceptionClass3.isInstance(exception)) {
                sink.acceptValueOrException3( /* value= */
                    null,  /* e1= */null,  /* e2= */null, exceptionClass3.cast(exception)
                )
                return true
            }
            return false
        }
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this).add("parent", parent).add("key", key).toString()
    }
}
