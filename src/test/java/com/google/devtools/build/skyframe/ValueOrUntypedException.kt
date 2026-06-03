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

/** Wrapper for a value or the untyped exception thrown when trying to compute it.  */
abstract class ValueOrUntypedException {
    /** Returns the stored value, if there was one.  */
    abstract val value: SkyValue?

    /** Returns the stored exception, if there was one.  */
    abstract val exception: java.lang.Exception?

    private class ValueOrUntypedExceptionImpl(value: SkyValue?) : ValueOrUntypedException() {
        private val value: SkyValue?

        init {
            this.value = value
        }

        public override fun getValue(): SkyValue? {
            return value
        }

        public override fun getException(): java.lang.Exception? {
            return null
        }

        override fun toString(): String {
            return "ValueOrUntypedExceptionValueImpl:" + value
        }

        companion object {
            val NULL: ValueOrUntypedExceptionImpl = ValueOrUntypedExceptionImpl(null)
        }
    }

    private class ValueOrUntypedExceptionExnImpl(exception: java.lang.Exception?) : ValueOrUntypedException() {
        private val exception: java.lang.Exception?

        init {
            this.exception = exception
        }

        public override fun getValue(): SkyValue? {
            return null
        }

        public override fun getException(): java.lang.Exception? {
            return exception
        }
    }

    companion object {
        fun ofValueUntyped(value: SkyValue?): ValueOrUntypedException {
            return ValueOrUntypedExceptionImpl(value)
        }

        fun ofNull(): ValueOrUntypedException {
            return ValueOrUntypedExceptionImpl.Companion.NULL
        }

        fun ofExn(e: java.lang.Exception?): ValueOrUntypedException {
            return ValueOrUntypedExceptionExnImpl(e)
        }
    }
}
