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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory

/**
 * Helper class which contains data used by a [TransitionFactory] to create a transition for
 * attributes.
 * 
 * @param attributes Returns the [AttributeMap] which can be used to create a transition.
 * @param executionPlatform Returns the [Label] of the execution platform used by the
 * configured target this transition factory is part of.
 * @param analysisData Optional parameter to let callers instantiate objects that the `lib.packages` library can't resolve. This class is both defined in `lib.packages` and
 * referenced by other files in that package.
 * 
 * Callers are responsible for ensuring correct casting between writes and reads.
 */
class AttributeTransitionData(
    attributes: com.google.devtools.build.lib.packages.AttributeMap?,
    executionPlatform: Label?,
    analysisData: Any?
) : TransitionFactory.Data {
    /** Builder class for [AttributeTransitionData].  */
    @AutoBuilder
    abstract class Builder {
        /** Sets the attributes.  */
        abstract fun attributes(attributes: com.google.devtools.build.lib.packages.AttributeMap?): Builder?

        /** Sets the execution platform label.  */
        abstract fun executionPlatform(executionPlatform: Label?): Builder?

        abstract fun analysisData(analysisData: Any?): Builder?

        /** Returns the new [AttributeTransitionData].  */
        abstract fun build(): AttributeTransitionData?
    }

    val attributes: com.google.devtools.build.lib.packages.AttributeMap?
    val executionPlatform: Label?
    val analysisData: Any?

    init {
        this.attributes = attributes
        this.executionPlatform = executionPlatform
        this.analysisData = analysisData
    }

    companion object {
        /** Returns a new [Builder] for [AttributeTransitionData].  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return AutoBuilder_AttributeTransitionData_Builder()
        }
    }
}
