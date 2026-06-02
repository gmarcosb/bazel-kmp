// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec

/**
 * Scope of a [BuildOptions] is defined by the [Scope.ScopeType] and [ ].
 */
class Scope(scopeType: ScopeType?, scopeDefinition: ScopeDefinition?) {
    /** Type of supported scopes.  */
    @AutoCodec
    @kotlin.jvm.JvmRecord
    data class ScopeType(scopeType: String?) {
        val scopeType: String?

        init {
            this.scopeType = scopeType
            require(
                scopeType == DEFAULT
                        || scopeType == UNIVERSAL
                        || scopeType == TARGET
                        || scopeType == PROJECT
                        || scopeType.startsWith("exec:")
            ) { "Invalid scope type: " + scopeType }
        }

        companion object {
            /** The flag's value never changes except explicitly by a configuration transition.  */
            const val UNIVERSAL: String = "universal"

            /** The flag's value resets on exec transitions.  */
            const val TARGET: String = "target"

            /** The flag resets on targets outside the flag's project. See PROJECT.scl.  */
            const val PROJECT: String = "project"

            /** Placeholder for flags that don't explicitly specify scope. Shouldn't be set directly.  */
            const val DEFAULT: String = "default"

            /** Which values can a rule's `scope` attribute have?  */
            @kotlin.jvm.JvmStatic
            fun allowedAttributeValues(): com.google.common.collect.ImmutableList<String?> {
                return com.google.common.collect.ImmutableList.of<String?>(UNIVERSAL, TARGET, PROJECT)
            }
        }
    }

    /**
     * Definition of a scope. Users can define this in their PROJECT.scl file that is in the same
     * directory as the BUILD file where the scoped flags are defined or in a parent directory. This
     * is only relevant if the scope type is PROJECT.
     */
    class ScopeDefinition(ownedCodePaths: com.google.common.collect.ImmutableSet<String?>?) {
        private val ownedCodePaths: com.google.common.collect.ImmutableSet<String?>?

        init {
            this.ownedCodePaths = ownedCodePaths
        }

        fun getOwnedCodePaths(): com.google.common.collect.ImmutableSet<String?>? {
            return ownedCodePaths
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("ownedCodePaths", ownedCodePaths)
                .toString()
        }
    }

    @kotlin.jvm.JvmField
    var scopeType: ScopeType?
    @kotlin.jvm.JvmField
    var scopeDefinition: ScopeDefinition?

    init {
        this.scopeType = scopeType
        this.scopeDefinition = scopeDefinition
    }

    fun getScopeType(): ScopeType? {
        return scopeType
    }

    fun getScopeDefinition(): ScopeDefinition? {
        return scopeDefinition
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("scopeType", scopeType)
            .add("scopeDefinition", scopeDefinition)
            .toString()
    }

    companion object {
        const val CUSTOM_EXEC_SCOPE_PREFIX: String = "exec:--"
    }
}
