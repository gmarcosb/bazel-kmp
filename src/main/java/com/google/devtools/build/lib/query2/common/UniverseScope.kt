// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.common

import com.google.devtools.build.lib.query2.common.UniverseSkyKey
import com.google.devtools.build.lib.query2.engine.QueryExpression
import com.google.devtools.build.lib.skyframe.PrepareDepsOfPatternsValue
import com.google.devtools.build.lib.vfs.PathFragment
import java.util.LinkedHashSet

/** Representation of the --universe_scope option value.  */
interface UniverseScope {
    fun getUniverseKey(expr: QueryExpression?, offset: PathFragment?): UniverseSkyKey?

    val isEmpty: Boolean

    @kotlin.jvm.JvmField
    val constantValueMaybe: java.util.Optional<com.google.common.collect.ImmutableList<String?>?>?

    /** Constant universe scope.  */
    class ConstantUniverseScope private constructor(constantUniverseScope: com.google.common.collect.ImmutableList<String?>) :
        UniverseScope {
        private val constantUniverseScope: com.google.common.collect.ImmutableList<String?>

        init {
            this.constantUniverseScope = constantUniverseScope
        }

        override fun isEmpty(): Boolean {
            return constantUniverseScope.isEmpty()
        }

        override fun getConstantValueMaybe(): java.util.Optional<com.google.common.collect.ImmutableList<String?>?> {
            return java.util.Optional.of<com.google.common.collect.ImmutableList<String?>?>(constantUniverseScope)
        }

        override fun getUniverseKey(expr: QueryExpression?, offset: PathFragment?): UniverseSkyKey? {
            return PrepareDepsOfPatternsValue.key(constantUniverseScope, offset)
        }
    }

    /** Universe scope inferred from query expression.  */
    class InferredUniverseScope private constructor() : UniverseScope {
        override fun isEmpty(): Boolean {
            return false
        }

        override fun getConstantValueMaybe(): java.util.Optional<com.google.common.collect.ImmutableList<String?>?> {
            return java.util.Optional.empty<com.google.common.collect.ImmutableList<String?>?>()
        }

        override fun getUniverseKey(expr: QueryExpression?, offset: PathFragment?): UniverseSkyKey? {
            val targetPatterns: LinkedHashSet<String?> = LinkedHashSet<String?>()
            com.google.common.base.Preconditions.checkNotNull<QueryExpression?>(expr)
                .collectTargetPatterns(targetPatterns)
            return PrepareDepsOfPatternsValue.key(
                com.google.common.collect.ImmutableList.copyOf<String?>(targetPatterns),
                offset
            )
        }
    }

    companion object {
        /** To be used when --universe_scope is set.  */
        fun fromUniverseScopeList(constantUniverseScope: com.google.common.collect.ImmutableList<String?>): UniverseScope {
            return ConstantUniverseScope(constantUniverseScope)
        }

        /** To be used when --infer_universe_scope applies.  */
        @kotlin.jvm.JvmField
        val INFER_FROM_QUERY_EXPRESSION: UniverseScope = InferredUniverseScope()

        /** To be used when neither --universe_scope nor --infer_universe_scope are set.  */
        @kotlin.jvm.JvmField
        val EMPTY: UniverseScope = ConstantUniverseScope(com.google.common.collect.ImmutableList.of<String?>())
    }
}
