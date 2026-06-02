// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionConflictException

/** Exceptions thrown by [ConfiguredTargetFunction].  */
class ConfiguredTargetEvaluationExceptions private constructor() {
    /**
     * [ConfiguredTargetFunction.compute] exception that has already had its error reported to
     * the user. Callers (like [com.google.devtools.build.lib.buildtool.BuildTool]) won't also
     * report the error.
     */
    class ReportedException internal constructor(e: ConfiguredValueCreationException) : SkyFunctionException(
        withoutMessage(e), Transience.PERSISTENT
    ) {
        companion object {
            /** Clones a [ConfiguredValueCreationException] with its `message` field removed.  */
            private fun withoutMessage(
                orig: ConfiguredValueCreationException
            ): ConfiguredValueCreationException {
                return ConfiguredValueCreationException(
                    orig.getLocation(),
                    "",  /* label= */
                    null,
                    orig.getConfiguration(),
                    orig.getRootCauses(),
                    orig.getDetailedExitCode()
                )
            }
        }
    }

    /**
     * [ConfiguredTargetFunction.compute] exception that has not had its error reported to the
     * user. Callers (like [com.google.devtools.build.lib.buildtool.BuildTool]) are responsible
     * for reporting the error.
     */
    class UnreportedException : SkyFunctionException {
        internal constructor(e: ConfiguredValueCreationException?) : super(e, Transience.PERSISTENT)

        internal constructor(e: ActionConflictException?) : super(e, Transience.PERSISTENT)
    }

    /** A dependency error that should be caught and rethrown by the parent with more context.  */
    internal class DependencyException : SkyFunctionException {
        internal enum class Kind {
            INCONSISTENT_NULL_CONFIG,
            NO_SUCH_THING
        }

        private val kind: Kind

        fun kind(): Kind {
            return kind
        }

        fun inconsistentNullConfig(): InconsistentNullConfigException? {
            return getCause() as InconsistentNullConfigException?
        }

        fun noSuchThing(): NoSuchThingException? {
            return getCause() as NoSuchThingException?
        }

        constructor(e: InconsistentNullConfigException?) : super(e, Transience.PERSISTENT) {
            this.kind =
                com.google.devtools.build.lib.skyframe.ConfiguredTargetEvaluationExceptions.DependencyException.Kind.INCONSISTENT_NULL_CONFIG
        }

        constructor(e: NoSuchThingException?) : super(e, Transience.PERSISTENT) {
            this.kind =
                com.google.devtools.build.lib.skyframe.ConfiguredTargetEvaluationExceptions.DependencyException.Kind.NO_SUCH_THING
        }
    }
}
