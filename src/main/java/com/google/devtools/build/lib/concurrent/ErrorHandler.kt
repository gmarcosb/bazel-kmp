// Copyright 2016 The Bazel Authors. All rights reserved.
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

/** A way to inject custom handling of errors encountered by [AbstractQueueVisitor].  */
interface ErrorHandler {
    /**
     * Called by [AbstractQueueVisitor] right after using [ErrorClassifier] to classify
     * the error, but right before actually acting on the classification.
     * 
     * 
     * Note that [Error]s are always classified as
     * [ErrorClassification.CRITICAL_AND_LOG].
     */
    fun handle(
        t: Throwable?,
        classification: com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification?
    )

    /** An [ErrorHandler] that does nothing.  */
    class NullHandler private constructor() : ErrorHandler {
        override fun handle(
            t: Throwable?,
            classification: com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification?
        ) {
        }

        companion object {
            val INSTANCE: NullHandler = com.google.devtools.build.lib.concurrent.ErrorHandler.NullHandler()
        }
    }
}

