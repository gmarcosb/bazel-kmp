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

import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant

/**
 * A value that represents "error transience", i.e. anything which may have caused an unexpected
 * failure. Is not equal to anything, including itself, in order to force re-evaluation.
 */
class ErrorTransienceValue private constructor() : SkyValue {
    override fun hashCode(): Int {
        // Not the prettiest, but since we always return false for equals throw exception here to catch
        // any errors related to hash-based collections quickly.
        throw java.lang.UnsupportedOperationException()
    }

    override fun equals(other: Any?): Boolean {
        return false
    }

    override fun toString(): String {
        return "ErrorTransienceValue"
    }

    companion object {
        private val FUNCTION_NAME: SkyFunctionName = SkyFunctionName.Companion.createNonHermetic("ERROR_TRANSIENCE")

        @kotlin.jvm.JvmField
        @SerializationConstant
        val KEY: SkyKey = object : SkyKey {
            override fun functionName(): SkyFunctionName {
                return FUNCTION_NAME
            }

            override fun valueIsShareable(): Boolean {
                return false
            }

            override fun toString(): String {
                return "ErrorTransienceValue.KEY"
            }
        }

        @kotlin.jvm.JvmField
        @SerializationConstant
        val INSTANCE: ErrorTransienceValue = ErrorTransienceValue()
    }
}
