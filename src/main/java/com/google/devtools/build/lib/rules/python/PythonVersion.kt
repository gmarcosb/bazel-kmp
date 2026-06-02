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
package com.google.devtools.build.lib.rules.python

import com.google.common.base.Functions
import com.google.common.collect.ImmutableList
import java.lang.String
import kotlin.Boolean
import kotlin.IllegalArgumentException

/**
 * An enum representing Python major versions.
 * 
 * 
 * This enum has two interpretations. The "target" interpretation is when this enum is used in a
 * command line flag or in a rule attribute to denote a particular version of the Python language.
 * Only `PY2` and `PY3` can be used as target values. The "sources" interpretation is
 * when this enum is used to denote the degree of compatibility of source code with the target
 * values.
 */
enum class PythonVersion {
    // TODO(#6445): Remove PY2ONLY and PY3ONLY.
    /**
     * Target value Python 2. Represents source code that is naturally compatible with Python 2.
     * 
     * 
     * *Deprecated meaning:* Also indicates that source code is compatible with Python 3 under
     * 2to3 transformation. 2to3 transformation is not implemented in Bazel and this meaning will be
     * removed from Bazel (#1393).
     */
    PY2,

    /**
     * Target value Python 3. Represents source code that is naturally compatible with Python 3.
     * 
     * 
     * *Deprecated meaning:* Also indicates that source code is compatible with Python 2 under
     * 3to2 transformation. 3to2 transformation was never implemented and this meaning should not be
     * relied on.
     */
    PY3,

    /**
     * Represents source code that is naturally compatible with both Python 2 and Python 3, i.e. code
     * that lies in the intersection of both languages.
     */
    PY2AND3,

    /**
     * Alias for `PY2`. Deprecated in Bazel; prefer `PY2`.
     * 
     * 
     * *Deprecated meaning:* Indicates code that cannot be processed by 2to3.
     */
    PY2ONLY,

    /**
     * Deprecated alias for `PY3`.
     * 
     * 
     * *Deprecated meaning:* Indicates code that cannot be processed by 3to2.
     */
    PY3ONLY,

    /**
     * Internal sentinel value used as the default value of the `python_version` and `default_python_version` attributes.
     * 
     * 
     * This should not be referenced by the user. But since we can't actually hide it from Starlark
     * (`native.existing_rules()`) or bazel query, we give it the scary "_internal" prefix
     * instead.
     */
    _INTERNAL_SENTINEL;

    val isTargetValue: Boolean
        /** Returns whether or not this value is a distinct Python version.  */
        get() = TARGET_VALUES.contains(this)

    companion object {
        private fun convertToStrings(values: MutableList<PythonVersion?>): ImmutableList<String?> {
            return values.stream()
                .map<String?>(Functions.toStringFunction())
                .collect(ImmutableList.toImmutableList<String?>())
        }

        /** Enum values representing a distinct Python version.  */
        val TARGET_VALUES: ImmutableList<PythonVersion?> =
            ImmutableList.of<PythonVersion?>(PythonVersion.PY2, PythonVersion.PY3)

        /** String names of enum values representing a distinct Python version.  */
        val TARGET_STRINGS: ImmutableList<String?> = convertToStrings(TARGET_VALUES)

        /** Target values plus the sentinel value.  */
        val TARGET_AND_SENTINEL_VALUES: ImmutableList<PythonVersion?> =
            ImmutableList.of<PythonVersion?>(PythonVersion.PY2, PythonVersion.PY3, PythonVersion._INTERNAL_SENTINEL)

        /** String names of target values plus the sentinel value.  */
        val TARGET_AND_SENTINEL_STRINGS: ImmutableList<String?> = convertToStrings(TARGET_AND_SENTINEL_VALUES)

        /** All values not including the sentinel.  */
        val SRCS_VALUES: ImmutableList<PythonVersion?> = ImmutableList.of<PythonVersion?>(
            PythonVersion.PY2,
            PythonVersion.PY3,
            PythonVersion.PY2AND3,
            PythonVersion.PY2ONLY,
            PythonVersion.PY3ONLY
        )

        /** String names of all enum values not including the sentinel.  */
        val SRCS_STRINGS: ImmutableList<String?> = convertToStrings(SRCS_VALUES)

        /** Enum values that do not imply running a transpiler to convert between versions.  */
        val NON_CONVERSION_VALUES: ImmutableList<PythonVersion?> =
            ImmutableList.of<PythonVersion?>(PythonVersion.PY2AND3, PythonVersion.PY2ONLY, PythonVersion.PY3ONLY)

        /**
         * String names of enum values that do not imply running a transpiler to convert between versions.
         */
        val NON_CONVERSION_STRINGS: ImmutableList<String?> = convertToStrings(NON_CONVERSION_VALUES)

        val DEFAULT_SRCS_VALUE: PythonVersion = PythonVersion.PY2AND3

        /**
         * Converts the string to a target `PythonVersion` value (case-sensitive).
         * 
         * @throws IllegalArgumentException if the string is not "PY2" or "PY3".
         */
        fun parseTargetValue(str: String?): PythonVersion {
            require(TARGET_STRINGS.contains(str)) {
                String.format(
                    "'%s' is not a valid Python major version. Expected 'PY2' or 'PY3'.",
                    str
                )
            }
            return PythonVersion.valueOf(str!!)
        }

        /**
         * Converts the string to a target or sentinel `PythonVersion` value (case-sensitive).
         * 
         * @throws IllegalArgumentException if the string is not "PY2", "PY3", or "_INTERNAL_SENTINEL".
         */
        @kotlin.jvm.JvmStatic
        fun parseTargetOrSentinelValue(str: kotlin.String?): PythonVersion {
            require(TARGET_AND_SENTINEL_STRINGS.contains(str)) {
                String.format(
                    "'%s' is not a valid Python major version. Expected 'PY2' or 'PY3'.",
                    str
                )
            }
            return PythonVersion.valueOf(str!!)
        }

        /**
         * Converts the string to a sources `PythonVersion` value (case-sensitive).
         * 
         * @throws IllegalArgumentException if the string is not an enum name or is the sentinel value.
         */
        @kotlin.jvm.JvmStatic
        fun parseSrcsValue(str: kotlin.String?): PythonVersion {
            require(SRCS_STRINGS.contains(str)) { String.format("'%s' is not a valid Python srcs_version value.", str) }
            return PythonVersion.valueOf(str!!)
        }
    }
}
