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
package com.google.devtools.build.lib.testutil

import com.google.common.flogger.GoogleLogger
import java.util.concurrent.atomic.AtomicReference

/**
 * A class that wraps Runnables and records the first Throwable thrown by the wrapped Runnables when
 * they are run. Only for use in testing.
 */
open class ThrowableRecordingRunnableWrapper(name: String?) {
    private val name: String
    private val errorRef: AtomicReference<Throwable?> = AtomicReference<Throwable?>()

    init {
        this.name = com.google.common.base.Preconditions.checkNotNull<String>(name)
    }

    val firstThrownError: Throwable?
        get() = errorRef.get()

    open fun wrap(runnable: java.lang.Runnable): java.lang.Runnable? {
        return java.lang.Runnable {
            try {
                runnable.run()
            } catch (error: Throwable) {
                errorRef.compareAndSet(null, error)
                logger.atSevere().withCause(error).log("Error thrown by runnable in %s", name)
            }
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
