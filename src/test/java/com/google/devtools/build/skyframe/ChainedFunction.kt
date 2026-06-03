// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.skyframe.SkyFunctionException.Transience

/**
 * [ValueComputer] that can be chained together with others of its type to synchronize the
 * order in which builders finish.
 */
class ChainedFunction private constructor(
    notifyStart: java.lang.Runnable,
    waitToFinish: CountDownLatch?,
    notifyFinish: java.lang.Runnable,
    waitForException: Boolean,
    value: SkyValue?,
    deps: Iterable<SkyKey?>
) : SkyFunction {
    private val value: SkyValue?
    private val notifyStart: java.lang.Runnable
    private val waitToFinish: CountDownLatch?
    private val notifyFinish: java.lang.Runnable
    private val waitForException: Boolean
    private val deps: Iterable<SkyKey?>

    /** Do not use! Use [Builder] instead.  */
    internal constructor(
        notifyStart: CountDownLatch?,
        waitToFinish: CountDownLatch?,
        notifyFinish: CountDownLatch?,
        waitForException: Boolean,
        value: SkyValue?,
        deps: Iterable<SkyKey?>
    ) : this(
        makeRunnable(notifyStart),
        waitToFinish,
        makeRunnable(notifyFinish),
        waitForException,
        value,
        deps
    )

    init {
        this.notifyStart = notifyStart
        this.waitToFinish = waitToFinish
        this.notifyFinish = notifyFinish
        this.waitForException = waitForException
        com.google.common.base.Preconditions.checkState(this.waitToFinish != null || !this.waitForException, value)
        this.value = value
        this.deps = deps
    }

    @Throws(GenericFunctionException::class, java.lang.InterruptedException::class)
    public override fun compute(key: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        try {
            notifyStart.run()
            if (waitToFinish != null) {
                TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                    waitToFinish, key.toString() + " timed out waiting to finish"
                )
                if (waitForException) {
                    val skyEnv: SkyFunctionEnvironment = env as SkyFunctionEnvironment
                    TrackingAwaiter.Companion.INSTANCE.awaitLatchAndTrackExceptions(
                        skyEnv.getExceptionLatchForTesting(), key.toString() + " timed out waiting for exception"
                    )
                }
            }
            for (dep in deps) {
                env.getValue(dep)
            }
            if (value == null) {
                throw GenericFunctionException(
                    SomeErrorException("oops"),
                    Transience.PERSISTENT
                )
            }
            if (env.valuesMissing()) {
                return null
            }
            return value
        } finally {
            notifyFinish.run()
        }
    }

    /** Builder for [ChainedFunction] objects.  */
    class Builder {
        private var value: SkyValue? = null
        var notifyStart: java.lang.Runnable = makeRunnable(null)
        private var waitToFinish: CountDownLatch? = null
        private var notifyFinish: java.lang.Runnable = makeRunnable(null)
        private var waitForException = false
        private var deps: Iterable<SkyKey?> = com.google.common.collect.ImmutableList.of<SkyKey?>()

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setValue(value: SkyValue?): Builder {
            this.value = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setNotifyStart(notifyStart: java.lang.Runnable): Builder {
            this.notifyStart = notifyStart
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setWaitToFinish(waitToFinish: CountDownLatch?): Builder {
            this.waitToFinish = waitToFinish
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setNotifyFinish(notifyFinish: java.lang.Runnable): Builder {
            this.notifyFinish = notifyFinish
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setWaitForException(waitForException: Boolean): Builder {
            this.waitForException = waitForException
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setDeps(deps: Iterable<SkyKey?>?): Builder {
            this.deps = com.google.common.base.Preconditions.checkNotNull<Iterable<SkyKey?>>(deps)
            return this
        }

        fun build(): SkyFunction? {
            return ChainedFunction(
                notifyStart, waitToFinish, notifyFinish, waitForException, value, deps
            )
        }
    }

    companion object {
        private fun makeRunnable(latch: CountDownLatch?): java.lang.Runnable {
            return if (latch != null) java.lang.Runnable { latch.countDown() } else java.lang.Runnable {}
        }
    }
}
