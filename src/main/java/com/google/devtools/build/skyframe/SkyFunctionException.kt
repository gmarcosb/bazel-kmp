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

/**
 * Base class of exceptions thrown by [SkyFunction.compute] on failure.
 * 
 * 
 * SkyFunctions should declare a subclass `C` of [SkyFunctionException] whose
 * constructors forward fine-grained exception types (e.g. `IOException`) to [ ]'s constructor, and they should also declare [SkyFunction.compute] to
 * throw `C`. This way the type system checks that no unexpected exceptions are thrown by the
 * [SkyFunction].
 * 
 * 
 * We took this approach over using a generic exception class since Java disallows it because of
 * type erasure (see
 * http://docs.oracle.com/javase/tutorial/java/generics/restrictions.html#cannotCatch).
 * 
 * 
 * Note that there are restrictions on what Exception types are allowed to be wrapped in this
 * manner. See [SkyFunctionException.validateExceptionType].
 * 
 * 
 * Failures are explicitly marked transient or persistent. Transient errors indicate that the
 * node may yield a successful result on a retry, while persistent errors are guaranteed to remain
 * if none of the inputs to the node change. An error should be marked persistent if either (1) it
 * is propagating an error from a Skyframe dependency (observed via [ ][SkyFunction.Environment.getValueOrThrow], or (2) it is the result of computation done solely on
 * the inputs received from its Skyframe dependencies. Any other errors should be marked transient.
 * For example, an I/O exception should trigger a transient error in the node that directly
 * performed the I/O, and persistent errors in its callers.
 */
abstract class SkyFunctionException protected constructor(cause: java.lang.Exception?, transience: Transience?) :
    java.lang.Exception(com.google.common.base.Preconditions.checkNotNull<java.lang.Exception?>(cause)) {
    /** The transience of the error.  */
    enum class Transience {
        /**
         * An error that may or may not occur again if the node were reevaluated, even when Skyframe
         * dependencies have not changed. If a node results in a transient error and is needed on a
         * subsequent MemoizingEvaluator#evaluate call, it will be reevaluated.
         */
        TRANSIENT,

        /**
         * An error that is completely deterministic in terms of the node's Skyframe dependencies.
         * Persistent errors may be cached.
         */
        PERSISTENT
    }

    private val transience: Transience?

    init {
        Companion.validateExceptionType(cause.getClass())
        this.transience = transience
    }

    fun isTransient(): Boolean {
        return transience == Transience.TRANSIENT
    }

    /** Catastrophic failures halt the build even when in keepGoing mode.  */
    open fun isCatastrophic(): Boolean {
        return false
    }

    @kotlin.jvm.Synchronized
    override fun getCause(): java.lang.Exception? {
        return super.getCause() as java.lang.Exception?
    }

    /** A [SkyFunctionException] with a definite root cause.  */
    class ReifiedSkyFunctionException protected constructor(
        private val originalException: SkyFunctionException,
        transience: Transience?,
        private val isCatastrophic: Boolean
    ) : SkyFunctionException(
        originalException.getCause(), transience
    ) {
        constructor(e: SkyFunctionException) : this(e, e.transience, e.isCatastrophic())

        override fun isCatastrophic(): Boolean {
            return isCatastrophic
        }

        fun getOriginalException(): SkyFunctionException {
            return originalException
        }
    }

    companion object {
        fun <E : java.lang.Exception?> validateExceptionType(exceptionClass: java.lang.Class<E?>) {
            check(!exceptionClass.isAssignableFrom(java.lang.RuntimeException::class.java)) {
                (exceptionClass.getSimpleName()
                        + " is a supertype of RuntimeException. Don't do this since then you would"
                        + " potentially swallow all RuntimeExceptions, even those from Skyframe")
            }
            check(!java.lang.RuntimeException::class.java.isAssignableFrom(exceptionClass)) {
                (exceptionClass.getSimpleName()
                        + " is a subtype of RuntimeException. You should rewrite your code to use checked"
                        + " exceptions.")
            }
            check(!java.lang.InterruptedException::class.java.isAssignableFrom(exceptionClass)) {
                (exceptionClass.getSimpleName()
                        + " is a subtype of InterruptedException. Don't do this; Skyframe handles interrupts"
                        + " separately from the general SkyFunctionException mechanism.")
            }
        }

        @Throws(E1::class, E2::class, E3::class, E4::class)
        fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?, E4 : java.lang.Exception?>
                throwIfInstanceOf(
            e: java.lang.Exception?,
            exceptionClass1: java.lang.Class<E1?>?,
            exceptionClass2: java.lang.Class<E2?>?,
            exceptionClass3: java.lang.Class<E3?>?,
            exceptionClass4: java.lang.Class<E4?>?
        ) {
            if (e == null) {
                return
            }
            if (exceptionClass1 != null && exceptionClass1.isInstance(e)) {
                throw exceptionClass1.cast(e)
            }
            if (exceptionClass2 != null && exceptionClass2.isInstance(e)) {
                throw exceptionClass2.cast(e)
            }
            if (exceptionClass3 != null && exceptionClass3.isInstance(e)) {
                throw exceptionClass3.cast(e)
            }
            if (exceptionClass4 != null && exceptionClass4.isInstance(e)) {
                throw exceptionClass4.cast(e)
            }
        }
    }
}
