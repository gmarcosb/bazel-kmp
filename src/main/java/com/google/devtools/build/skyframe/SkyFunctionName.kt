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

import com.google.devtools.build.lib.concurrent.BlazeInterners

/** An identifier for a `SkyFunction`.  */
class SkyFunctionName private constructor(name: String?, hermeticity: FunctionHermeticity?) {
    @kotlin.jvm.JvmField
    private val name: String
    private val hermeticity: FunctionHermeticity

    init {
        this.name = com.google.common.base.Preconditions.checkNotNull<String>(name)
        this.hermeticity = com.google.common.base.Preconditions.checkNotNull<FunctionHermeticity>(hermeticity)
    }

    fun getName(): String {
        return name
    }

    fun getHermeticity(): FunctionHermeticity {
        return hermeticity
    }

    override fun toString(): String {
        return name
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is SkyFunctionName) {
            return false
        }
        return name == obj.name
    }

    override fun hashCode(): Int {
        // Don't bother incorporating hermeticity into hashCode: should always be the same.
        return name.hashCode()
    }

    companion object {
        private val interner: com.google.common.collect.Interner<SkyFunctionName> = BlazeInterners.newStrongInterner()

        /**
         * A well-known key type intended for testing only. The associated SkyKey should have a String
         * argument.
         */
        // Needs to be after the interner is initialized.
        @kotlin.jvm.JvmField
        val FOR_TESTING: SkyFunctionName = createHermetic("FOR_TESTING")

        /**
         * Creates a SkyFunctionName identified by `name` whose evaluation is non-hermetic (its
         * value may not be a pure function of its dependencies. Only use this if the evaluation
         * explicitly consumes data outside of Skyframe, or if the node can be directly invalidated (as
         * opposed to transitively invalidated).
         */
        @kotlin.jvm.JvmStatic
        fun createNonHermetic(name: String?): SkyFunctionName {
            return create(name, FunctionHermeticity.NONHERMETIC)
        }

        /**
         * Creates a SkyFunctionName identified by `name` whose evaluation is [ ][FunctionHermeticity.SEMI_HERMETIC].
         */
        fun createSemiHermetic(name: String?): SkyFunctionName {
            return create(name, FunctionHermeticity.SEMI_HERMETIC)
        }

        /**
         * Creates a SkyFunctionName identified by `name` whose evaluation is hermetic (guaranteed
         * to be a deterministic function of its dependencies, not doing any external operations).
         */
        @kotlin.jvm.JvmStatic
        fun createHermetic(name: String?): SkyFunctionName {
            return create(name, FunctionHermeticity.HERMETIC)
        }

        private fun create(name: String?, hermeticity: FunctionHermeticity?): SkyFunctionName {
            val cached: SkyFunctionName = interner.intern(SkyFunctionName(name, hermeticity))
            com.google.common.base.Preconditions.checkState(
                cached.hermeticity == hermeticity,
                "Tried to create SkyFunctionName objects with same name (%s) but different hermeticity"
                        + " (old=%s, new=%s)",
                name,
                cached.hermeticity,
                hermeticity
            )
            return cached
        }

        /**
         * A predicate that returns true for [SkyKey]s that have the given [SkyFunctionName].
         */
        @kotlin.jvm.JvmStatic
        fun functionIs(functionName: SkyFunctionName): com.google.common.base.Predicate<SkyKey?> {
            return com.google.common.base.Predicate { skyKey: SkyKey? -> functionName == skyKey.functionName() }
        }

        /**
         * A predicate that returns true for [SkyKey]s that have the given [SkyFunctionName].
         */
        fun functionIsIn(functionNames: MutableSet<SkyFunctionName?>): com.google.common.base.Predicate<SkyKey?> {
            return com.google.common.base.Predicate { skyKey: SkyKey? -> functionNames.contains(skyKey.functionName()) }
        }
    }
}
