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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.TargetParsingException

/**
 * SkyFunction that throws a [TargetParsingException] for target pattern that could not be
 * parsed. Must only be requested when a SkyFunction wishes to ignore the errors in a target pattern
 * in keep_going mode, but to shut down the build in nokeep_going mode.
 * 
 * 
 * This SkyFunction never returns a value, only throws a [TargetParsingException], and
 * should never return null, since all of its dependencies should already be present.
 */
class TargetPatternErrorFunction : SkyFunction {
    @VisibleForSerialization
    @AutoCodec
    internal class Key private constructor(private val message: String, detailedExitCode: DetailedExitCode) : SkyKey {
        private val detailedExitCode: DetailedExitCode

        init {
            this.detailedExitCode = detailedExitCode
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.TARGET_PATTERN_ERROR
        }

        override fun hashCode(): Int {
            return 43 * message.hashCode() + detailedExitCode.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is Key) {
                return false
            }
            return this.message == obj.message
                    && this.detailedExitCode == obj.detailedExitCode
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.TargetPatternErrorFunction.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()
            private fun create(message: String, detailedExitCode: DetailedExitCode): Key {
                return com.google.devtools.build.lib.skyframe.TargetPatternErrorFunction.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.TargetPatternErrorFunction.Key(message, detailedExitCode)
                )
            }

            @VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.TargetPatternErrorFunction.Key.Companion.interner.intern(
                    key
                )
            }
        }
    }

    @Throws(TargetErrorFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment?): SkyValue? {
        throw TargetErrorFunctionException(
            TargetParsingException((skyKey as Key).message, skyKey.detailedExitCode),
            Transience.PERSISTENT
        )
    }

    private class TargetErrorFunctionException(cause: TargetParsingException?, transience: Transience?) :
        SkyFunctionException(cause, transience)

    companion object {
        fun key(e: TargetParsingException): Key {
            return com.google.devtools.build.lib.skyframe.TargetPatternErrorFunction.Key.Companion.create(
                e.getMessage(),
                e.getDetailedExitCode()
            )
        }
    }
}
