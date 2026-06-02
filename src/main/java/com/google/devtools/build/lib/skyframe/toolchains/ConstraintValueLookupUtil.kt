// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.toolchains

import com.google.devtools.build.lib.actions.ActionConflictException

/** Helper class that looks up [ConstraintValueInfo] data.  */
object ConstraintValueLookupUtil {
    @Throws(java.lang.InterruptedException::class, InvalidConstraintValueException::class)
    fun getConstraintValueInfo(
        constraintValueKeys: Iterable<ConfiguredTargetKey>, env: SkyFunction.Environment
    ): MutableList<ConstraintValueInfo?>? {
        val values: SkyframeLookupResult = env.getValuesAndExceptions(constraintValueKeys)
        val valuesMissing: Boolean = env.valuesMissing()
        val constraintValues: MutableList<ConstraintValueInfo?>? =
            if (valuesMissing) null else java.util.ArrayList<ConstraintValueInfo?>()
        for (key in constraintValueKeys) {
            val constraintValueInfo: ConstraintValueInfo? = findConstraintValueInfo(key, values)
            if (!valuesMissing && constraintValueInfo != null) {
                constraintValues!!.add(constraintValueInfo)
            }
        }
        if (valuesMissing) {
            return null
        }
        return constraintValues
    }

    /**
     * Returns the [ConstraintValueInfo] provider from the [ConfiguredTarget] in the
     * [SkyframeLookupResult], or `null` if the [ConfiguredTarget] is not present.
     * If the [ConfiguredTarget] does not have a [ConstraintValueInfo] provider, a [ ] is thrown.
     */
    @Throws(InvalidConstraintValueException::class)
    private fun findConstraintValueInfo(
        key: ConfiguredTargetKey, values: SkyframeLookupResult
    ): ConstraintValueInfo? {
        try {
            val ctv: ConfiguredTargetValue? =
                values.getOrThrow<E1?, E2?, E3?>(
                    key,
                    ConfiguredValueCreationException::class.java,
                    NoSuchThingException::class.java,
                    ActionConflictException::class.java
                ) as ConfiguredTargetValue?
            if (ctv == null) {
                return null
            }

            val configuredTarget: ConfiguredTarget = ctv.getConfiguredTarget()
            val constraintValueInfo: ConstraintValueInfo =
                PlatformProviderUtils.constraintValue(configuredTarget)
            if (constraintValueInfo == null) {
                throw InvalidConstraintValueException(configuredTarget.getLabel())
            }

            return constraintValueInfo
        } catch (e: ConfiguredValueCreationException) {
            throw InvalidConstraintValueException(key.getLabel(), e)
        } catch (e: NoSuchThingException) {
            throw InvalidConstraintValueException(key.getLabel(), e)
        } catch (e: ActionConflictException) {
            throw InvalidConstraintValueException(key.getLabel(), e)
        }
    }

    /** Exception used when a constraint value label is not a valid constraint value.  */
    class InvalidConstraintValueException : ToolchainException {
        internal constructor(label: Label?) : super(formatError(label))

        internal constructor(label: Label?, e: ConfiguredValueCreationException?) : super(formatError(label), e)

        constructor(label: Label?, e: NoSuchThingException?) : super(e)

        constructor(label: Label?, e: ActionConflictException?) : super(formatError(label), e)

        val detailedCode: Code
            get() = Code.INVALID_CONSTRAINT_VALUE

        companion object {
            private fun formatError(label: Label?): String? {
                return java.lang.String.format(
                    "Target %s was referenced as a constraint_value, "
                            + "but does not provide ConstraintValueInfo",
                    label
                )
            }
        }
    }
}
