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
package com.google.devtools.build.lib.query2.engine

import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetAccessor

/**
 * A predicate for targets included in the visibility list of a rule.
 * 
 * 
 * A rule's visibility is described by a set of [QueryVisibility]. Each element
 * in the set corresponds to an entry in the rule's visibility attribute, or an entry in the
 * packages attribute of an included package_group.
 */
abstract class QueryVisibility<T> {
    /** Returns true if the visibility specification includes the given target's package.  */
    abstract fun contains(target: T?): Boolean

    private class SamePackageVisibility<T>(target: T?, private val accessor: TargetAccessor<T?>) :
        QueryVisibility<T?>() {
        private val packageName: String

        init {
            this.packageName = accessor.getPackage(target)
        }

        override fun contains(target: T?): Boolean {
            val other = accessor.getPackage(target)
            if (packageName == other) {
                return true
            }

            // packages in java/ are always visible from the corresponding package in javatests/
            if (other.startsWith(JAVATESTS_PREFIX)
                && packageName == JAVA_PREFIX + other.substring(JAVATESTS_PREFIX.length)
            ) {
                return true
            }

            return false
        }

        override fun toString(): String {
            return String.format("QueryVisibility(samePackage=%s)", "<PACKAGE>")
        }

        companion object {
            private const val JAVA_PREFIX = "java/"
            private const val JAVATESTS_PREFIX = "javatests/"
        }
    }

    companion object {
        /** Global visibility.  */
        // Safe covariant cast.
        fun <T> everything(): QueryVisibility<T?> {
            return EVERYTHING as Any as QueryVisibility<T?>
        }

        private val EVERYTHING: QueryVisibility<*> = object : QueryVisibility<Any?>() {
            override fun contains(target: Any?): Boolean {
                return true
            }

            override fun toString(): String {
                return "QueryVisibility(//visibility:public)"
            }
        }

        /**
         * Same-package visibility.
         * 
         * 
         * Targets are always visible to other targets in the same package. Additionally, targets
         * under java/ are always visible to the corresponding package in javatests/.
         */
        fun <T> samePackage(from: T?, accessor: TargetAccessor<T?>): QueryVisibility<T?> {
            return SamePackageVisibility<T?>(from, accessor)
        }
    }
}
