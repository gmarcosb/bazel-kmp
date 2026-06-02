// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.skyframe.SkyFunctionException.ReifiedSkyFunctionException
import com.google.devtools.build.skyframe.SkyKey

/** Used by [ParallelEvaluator] to produce and consume [ErrorInfo] instances.  */
interface ErrorInfoManager {
    fun fromException(
        key: SkyKey?,
        skyFunctionException: ReifiedSkyFunctionException?,
        isTransitivelyTransient: Boolean
    ): com.google.devtools.build.skyframe.ErrorInfo?

    /**
     * Returns the [ErrorInfo] to use when there isn't currently one because [ ][SkyFunction.compute] didn't throw a [SkyFunctionException].
     */
    fun getErrorInfoToUse(
        skyKey: SkyKey?,
        hasValue: Boolean,
        childErrorInfos: MutableSet<com.google.devtools.build.skyframe.ErrorInfo?>?
    ): com.google.devtools.build.skyframe.ErrorInfo?

    /**
     * Trivial [ErrorInfoManager] implementation whose [.fromException] simply uses
     * [ErrorInfo.fromException] and whose [.getErrorInfoToUse] makes an [ErrorInfo]
     * from the given `childErrorInfos`.
     */
    class UseChildErrorInfoIfNecessary private constructor() : ErrorInfoManager {
        override fun fromException(
            key: SkyKey?,
            skyFunctionException: ReifiedSkyFunctionException,
            isTransitivelyTransient: Boolean
        ): com.google.devtools.build.skyframe.ErrorInfo {
            return com.google.devtools.build.skyframe.ErrorInfo.Companion.fromException(
                skyFunctionException,
                isTransitivelyTransient
            )
        }

        override fun getErrorInfoToUse(
            skyKey: SkyKey?,
            hasValue: Boolean,
            childErrorInfos: MutableSet<com.google.devtools.build.skyframe.ErrorInfo?>
        ): com.google.devtools.build.skyframe.ErrorInfo? {
            if (childErrorInfos.isEmpty()) {
                return null
            }
            val errorInfo: com.google.devtools.build.skyframe.ErrorInfo =
                com.google.devtools.build.skyframe.ErrorInfo.Companion.fromChildErrors(skyKey, childErrorInfos)

            return if (hasValue) com.google.devtools.build.skyframe.ErrorInfo.Companion.withValue(errorInfo) else errorInfo
        }

        companion object {
            @kotlin.jvm.JvmField
            val INSTANCE: UseChildErrorInfoIfNecessary = UseChildErrorInfoIfNecessary()
        }
    }
}
