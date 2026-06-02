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

import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor
import com.google.devtools.build.lib.vfs.SyscallCache
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import java.io.IOException

/**
 * Given a [SkyKey] and the previous [SkyValue] it had, returns whether this value is
 * up to date.
 */
abstract class SkyValueDirtinessChecker {
    /**
     * Returns `true` iff the checker can handle `key`. Can only be true if `key.functionName().getHermeticity() == FunctionHermeticity.NONHERMETIC`.
     */
    abstract fun applies(key: SkyKey?): Boolean

    /** If `applies(key)`, returns the new value for `key`.  */
    @Throws(IOException::class)
    abstract fun createNewValue(
        key: SkyKey?, syscallCache: SyscallCache?, tsgm: TimestampGranularityMonitor?
    ): SkyValue?

    /**
     * Returns whether directory listings should be invalidated even if file types do not change.
     * 
     * 
     * Handles MTSV changes on directory listings when files are modified without changing type.
     */
    fun invalidateListingsOnFileModification(): Boolean {
        return false
    }

    /**
     * Returns the max transitive source version (mtsv) of a [SkyKey] for its new [ ].
     */
    @Throws(IOException::class)
    open fun getMaxTransitiveSourceVersionForNewValue(
        key: SkyKey?,
        value: SkyValue?
    ): com.google.devtools.build.skyframe.Version? {
        return null
    }

    /**
     * If `applies(key)`, returns the result of checking whether this key's value is up to date.
     */
    @Throws(IOException::class)
    open fun check(
        key: SkyKey?,
        oldValue: SkyValue?,
        oldMtsv: com.google.devtools.build.skyframe.Version?,
        syscallCache: SyscallCache?,
        tsgm: TimestampGranularityMonitor?
    ): DirtyResult? {
        val newValue: SkyValue? = createNewValue(key, syscallCache, tsgm)
        return if (newValue == oldValue)
            DirtyResult.Companion.notDirty()
        else
            DirtyResult.Companion.dirtyWithNewValue(newValue)
    }

    /** An encapsulation of the result of checking to see if a value is up to date.  */ // TODO(b/228090733) - support old source versions for dirtiness checking
    class DirtyResult private constructor(
        private val isDirty: Boolean,
        newValue: SkyValue?,
        newMaxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?
    ) {
        private val newValue: SkyValue?
        private val newMaxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?

        init {
            this.newValue = newValue
            this.newMaxTransitiveSourceVersion = newMaxTransitiveSourceVersion
        }

        fun isDirty(): Boolean {
            return isDirty
        }

        /**
         * If `isDirty()`, then either returns the new value for the value or `null` if
         * the new value wasn't computed. In the case where the value is dirty and a new value is
         * available, then the new value can be injected into the skyframe graph. Otherwise, the value
         * should simply be invalidated.
         */
        fun getNewValue(): SkyValue? {
            com.google.common.base.Preconditions.checkState(isDirty, newValue)
            return newValue
        }

        /**
         * Returns the max transitive source version for the new value or `null`.
         * 
         * 
         * Can only be called if the result `isDirty()`.
         */
        fun getNewMaxTransitiveSourceVersion(): com.google.devtools.build.skyframe.Version? {
            com.google.common.base.Preconditions.checkState(isDirty, newValue)
            return newMaxTransitiveSourceVersion
        }

        override fun hashCode(): Int {
            return (java.util.Objects.hashCode(newValue)
                    + (if (isDirty) 13 else 0)
                    + java.util.Objects.hashCode(newMaxTransitiveSourceVersion))
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is DirtyResult) {
                return false
            }
            return this.isDirty == obj.isDirty && this.newValue == obj.newValue
                    && this.newMaxTransitiveSourceVersion == obj.newMaxTransitiveSourceVersion
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("isDirty", isDirty)
                .add("newValue", newValue)
                .add("newMaxTransitiveSourceVersion", newMaxTransitiveSourceVersion)
                .toString()
        }

        companion object {
            private val NOT_DIRTY = DirtyResult( /* isDirty= */
                false,  /* newValue= */null,  /* newMaxTransitiveSourceVersion= */null
            )
            private val DIRTY = DirtyResult( /* isDirty= */
                true,  /* newValue= */null,  /* newMaxTransitiveSourceVersion= */null
            )

            /**
             * Creates a DirtyResult indicating that the external value is the same as the value in the
             * graph.
             */
            @kotlin.jvm.JvmStatic
            fun notDirty(): DirtyResult {
                return NOT_DIRTY
            }

            /**
             * Creates a DirtyResult indicating that external value is different from the value in the
             * graph, but this new value is not known.
             */
            @kotlin.jvm.JvmStatic
            fun dirty(): DirtyResult {
                return DIRTY
            }

            /**
             * Creates a DirtyResult indicating that the external value is `newValue`, which is
             * different from the value in the graph,
             */
            fun dirtyWithNewValue(newValue: SkyValue?): DirtyResult {
                return DirtyResult( /* isDirty= */
                    true, newValue,  /* newMaxTransitiveSourceVersion= */null
                )
            }

            fun dirtyWithNewValueAndMaxTransitiveSourceVersion(
                newValue: SkyValue?, newMaxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?
            ): DirtyResult {
                return DirtyResult( /* isDirty= */true, newValue, newMaxTransitiveSourceVersion)
            }
        }
    }
}
