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

import com.google.devtools.build.skyframe.NodeVersion

/**
 * A Version defines a value in a version tree used in persistent data structures. See
 * http://en.wikipedia.org/wiki/Persistent_data_structure.
 * 
 * 
 * Extends [NodeVersion] so that a single instance can be reused to save memory in the
 * common case that [.lastEvaluated] and [.lastChanged] are the same version.
 */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
interface Version : NodeVersion {
    /**
     * Defines a partial order relation on versions. Returns true if this object is at most `other` in that partial order. If x.equals(y), then x.atMost(y).
     * 
     * 
     * If x.atMost(y) returns false, then there are two possibilities: y < x in the partial order,
     * so y.atMost(x) returns true and !x.equals(y), or x and y are incomparable in this partial
     * order. This may be because x and y are instances of different Version implementations (although
     * it is legal for different Version implementations to be comparable as well). See
     * http://en.wikipedia.org/wiki/Partially_ordered_set.
     */
    fun atMost(other: Version?): Boolean

    /**
     * Returns whether `this < other` in the partial order of versions, similarly to [ ][.atMost].
     * 
     * 
     * Returns true iff the 2 versions are comparable in the partial order and `this` is
     * strictly lower than `other`. False result means that either the elements are comparable
     * and `this >= other` or the versions are not comparable in the partial order.
     */
    fun lowerThan(other: Version?): Boolean {
        return atMost(other) && !equals(other)
    }

    override fun lastEvaluated(): Version {
        return this
    }

    override fun lastChanged(): Version {
        return this
    }

    /** A version [.lowerThan] all other versions, other than itself.  */
    class MinimalVersion private constructor() : Version {
        override fun atMost(other: Version?): Boolean {
            return true
        }

        companion object {
            private val INSTANCE = MinimalVersion()
        }
    }

    /** A version that is not comparable to any version other than itself.  */
    class ConstantVersion private constructor() : Version {
        override fun atMost(other: Version?): Boolean {
            return this == other
        }

        companion object {
            private val INSTANCE = ConstantVersion()
        }
    }

    companion object {
        /**
         * Returns a version indicating that a [NodeEntry] has never been built.
         * 
         * 
         * The minimal version is never used as the graph version for Skyframe evaluations. It is only
         * used to indicatie that a node has never been built, since it is always [.lowerThan] the
         * graph version.
         */
        @kotlin.jvm.JvmStatic
        fun minimal(): MinimalVersion {
            return MinimalVersion.Companion.INSTANCE
        }

        /**
         * Returns a version indicating that a [NodeEntry] has been built without incrementality.
         * 
         * 
         * The constant version is used as the graph version for all non-incremental Skyframe
         * evaluations. Without incrementality, it makes sense to use a version with no defined "previous"
         * or "next" version.
         */
        @kotlin.jvm.JvmStatic
        fun constant(): ConstantVersion {
            return ConstantVersion.Companion.INSTANCE
        }
    }
}
