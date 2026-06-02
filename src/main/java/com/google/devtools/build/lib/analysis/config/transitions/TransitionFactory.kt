// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config.transitions

import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition

/**
 * Factory interface for transitions that are created dynamically, instead of being created as
 * singletons.
 * 
 * 
 * This class allows for cases where the general *type* of a transition is known, but the
 * specifics of the transition itself cannot be determined until the target is configured. Examples
 * of this are transitions that depend on other (non-configured) attributes from the same target, or
 * transitions that depend on state determined during configuration, such as the execution platform
 * or resolved toolchains.
 * 
 * 
 * Implementations must override [Object.equals] and [Object.hashCode] unless
 * exclusively accessed as singletons.
 * 
 * @param <T> the type of data object passed to the [.create] method, used to create the
 * actual [ConfigurationTransition] instance
</T> */
interface TransitionFactory<T : TransitionFactory.Data?> {
    /** Used to report exceptions during transition creation.  */
    class TransitionCreationException(message: String?) : java.lang.RuntimeException(message)

    /** Enum that describes what type of transition a TransitionFactory creates.  */
    enum class TransitionType {
        /** A transition that can be used for rules or attributes.  */
        ANY,

        /** A transition that can be used for rules only.  */
        RULE,

        /** A transition that can be used for attributes only.  */
        ATTRIBUTE;

        fun isCompatibleWith(other: TransitionType?): Boolean {
            if (this == TransitionType.ANY) {
                return true
            }
            if (other == TransitionType.ANY) {
                return true
            }
            return this == other
        }
    }

    /** A marker interface for classes that provide data to TransitionFactory instances.  */
    interface Data

    /** Returns a new [ConfigurationTransition], based on the given data.  */
    @Throws(TransitionCreationException::class)
    fun create(data: T?): ConfigurationTransition?

    /**
     * Returns a [TransitionType] to clarify what data (if any) the factory requires to create a
     * transation.
     */
    fun transitionType(): TransitionType?

    // TODO(https://github.com/bazelbuild/bazel/issues/7814): Once everything uses TransitionFactory,
    // remove these methods.
    val isTool: Boolean
        /**
         * Returns `true` if the result of this [TransitionFactory] should be considered as
         * part of the tooling rather than a dependency of the original target.
         */
        get() = false

    val isSplit: Boolean
        /** Returns `true` if the result of this [TransitionFactory] is a split transition.  */
        get() = false

    /** Visit this trnsition factory with the given visitor.  */
    fun visit(visitor: Visitor<T?>) {
        visitor.visit(this)
    }

    /** Interface used to progressively visit transitions.  */
    interface Visitor<T : Data?> {
        fun visit(factory: TransitionFactory<T?>?)
    }
}
