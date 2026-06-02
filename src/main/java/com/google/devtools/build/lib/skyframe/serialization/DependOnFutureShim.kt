// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.skyframe.SkyFunction

/**
 * Encapsulates [Environment.dependOnFuture], [Environment.valuesMissing] in a single
 * method.
 * 
 * 
 * Decoupling this from [Environment] simplifies testing and the API.
 */
interface DependOnFutureShim {
    /** Returned status of [.dependOnFuture].  */
    enum class ObservedFutureStatus {
        /** If the future was already done.  */
        DONE,

        /**
         * If the future was not done.
         * 
         * 
         * Indicates that a Skyframe restart is needed.
         */
        NOT_DONE
    }

    /**
     * Outside of testing, delegates to [Environment.dependOnFuture] then [ ][Environment.valuesMissing] to determine the return value.
     * 
     * @throws IllegalStateException if called when an underlying environment's [     ][Environment.valuesMissing] is already true.
     */
    fun dependOnFuture(future: com.google.common.util.concurrent.ListenableFuture<*>?): ObservedFutureStatus?

    /** A convenience implementation used with an [Environment] instance.  */
    class DefaultDependOnFutureShim(env: SkyFunction.Environment) : DependOnFutureShim {
        private val env: SkyFunction.Environment

        init {
            this.env = env
        }

        override fun dependOnFuture(future: com.google.common.util.concurrent.ListenableFuture<*>?): ObservedFutureStatus {
            com.google.common.base.Preconditions.checkState(!env.valuesMissing())
            env.dependOnFuture(future)
            return if (env.valuesMissing()) ObservedFutureStatus.NOT_DONE else ObservedFutureStatus.DONE
        }
    }
}
