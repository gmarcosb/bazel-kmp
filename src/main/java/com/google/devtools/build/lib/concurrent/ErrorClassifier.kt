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
package com.google.devtools.build.lib.concurrent

/** A classifier for [Error]s and [Exception]s. Used by [AbstractQueueVisitor].  */
abstract class ErrorClassifier {
    /**
     * Classification of an error thrown by an action.
     * 
     * 
     * N.B. - These enum values are ordered from least severe to most severe.
     */
    enum class ErrorClassification {
        /** Other running actions should be left alone.  */
        NOT_CRITICAL,

        /**
         * Other running actions should be left alone, but the error should be prioritized over [ ][.NOT_CRITICAL].
         */
        NOT_CRITICAL_HIGHER_PRIORITY,

        /** All running actions should be stopped.  */
        CRITICAL,

        /** Same as [.CRITICAL], but also log the error.  */
        CRITICAL_AND_LOG,

        /** Same as [.CRITICAL_AND_LOG], but is even worse.  */
        AS_CRITICAL_AS_POSSIBLE
    }

    /**
     * Used by [.classify] to classify [Exception]s. (Note that [Error]s
     * are always classified as `AS_CRITICAL_AS_POSSIBLE`.)
     * 
     * @param e the exception object to check
     */
    protected abstract fun classifyException(e: java.lang.Exception?): ErrorClassification?

    /**
     * Classify `e`. If `e` is an [Error], it will be classified as
     * `AS_CRITICAL_AS_POSSIBLE`. Otherwise, calls [.classifyException].
     */
    fun classify(e: Throwable?): ErrorClassification? {
        if (e is java.lang.Error) {
            return com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification.AS_CRITICAL_AS_POSSIBLE
        }
        com.google.common.base.Preconditions.checkArgument(e is java.lang.Exception, e)
        return classifyException(e as java.lang.Exception)
    }

    companion object {
        /** Always treat exceptions as `NOT_CRITICAL`.  */
        @kotlin.jvm.JvmField
        val DEFAULT: ErrorClassifier = object : ErrorClassifier() {
            override fun classifyException(e: java.lang.Exception?): ErrorClassification {
                return com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification.NOT_CRITICAL
            }
        }
    }
}
