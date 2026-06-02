// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers

import com.google.devtools.build.lib.analysis.InvalidVisibilityDependencyException

/** Tagged union of exceptions thrown by [DependencyProducer].  */
@AutoOneOf(com.google.devtools.build.lib.analysis.producers.DependencyError.Kind::class)
abstract class DependencyError {
    /**
     * Tags for different error types.
     * 
     * 
     * The earlier in this list, the higher the priority when there are multiple errors. See [ ][.isSecondErrorMoreImportant].
     */
    enum class Kind {
        DEPENDENCY_OPTIONS_PARSING,
        DEPENDENCY_TRANSITION,
        MATERIALIZER,
        INVALID_VISIBILITY,

        /** An error occurred either computing the aspect collection or merging the aspect values.  */
        ASPECT_EVALUATION,

        /** An error occurred during evaluation of the aspect using Skyframe.  */
        ASPECT_CREATION,

        /** An error occurred during evaluation of platform mappings.  */
        PLATFORM_MAPPING,

        /** An error occurred while looking up the target platform.  */
        INVALID_PLATFORM,

        /** An error occurred while creating a transition.  */
        TRANSITION_CREATION,

        /** An error occurred during evaluation of build options scopes.  */
        BUILD_OPTIONS_SCOPE,
    }

    abstract fun kind(): Kind?

    abstract fun dependencyOptionsParsing(): com.google.devtools.common.options.OptionsParsingException?

    abstract fun dependencyTransition(): TransitionException?

    abstract fun materializer(): MaterializerException?

    abstract fun invalidVisibility(): InvalidVisibilityDependencyException?

    abstract fun aspectEvaluation(): DependencyEvaluationException?

    abstract fun aspectCreation(): AspectCreationException?

    abstract fun platformMapping(): PlatformMappingException?

    abstract fun invalidPlatform(): InvalidPlatformException?

    abstract fun transitionCreation(): TransitionCreationException?

    abstract fun buildOptionsScope(): BuildOptionsScopeFunctionException?

    val exception: java.lang.Exception?
        get() = when (kind()) {
            com.google.devtools.build.lib.analysis.producers.DependencyError.Kind.DEPENDENCY_OPTIONS_PARSING -> dependencyOptionsParsing()
            com.google.devtools.build.lib.analysis.producers.DependencyError.Kind.DEPENDENCY_TRANSITION -> dependencyTransition()
            com.google.devtools.build.lib.analysis.producers.DependencyError.Kind.MATERIALIZER -> materializer()
            com.google.devtools.build.lib.analysis.producers.DependencyError.Kind.INVALID_VISIBILITY -> invalidVisibility()
            com.google.devtools.build.lib.analysis.producers.DependencyError.Kind.ASPECT_EVALUATION -> aspectEvaluation()
            com.google.devtools.build.lib.analysis.producers.DependencyError.Kind.ASPECT_CREATION -> aspectCreation()
            com.google.devtools.build.lib.analysis.producers.DependencyError.Kind.PLATFORM_MAPPING -> platformMapping()
            com.google.devtools.build.lib.analysis.producers.DependencyError.Kind.INVALID_PLATFORM -> invalidPlatform()
            com.google.devtools.build.lib.analysis.producers.DependencyError.Kind.TRANSITION_CREATION -> transitionCreation()
            com.google.devtools.build.lib.analysis.producers.DependencyError.Kind.BUILD_OPTIONS_SCOPE -> buildOptionsScope()
        }

    companion object {
        fun isSecondErrorMoreImportant(first: DependencyError, second: DependencyError): Boolean {
            // There isn't a good way to prioritize when the type matches, so we just keep the first.
            return first.kind()!!.compareTo(second.kind()!!) > 0
        }

        fun of(e: TransitionException?): DependencyError {
            return AutoOneOf_DependencyError.dependencyTransition(e)
        }

        fun of(e: com.google.devtools.common.options.OptionsParsingException?): DependencyError {
            return AutoOneOf_DependencyError.dependencyOptionsParsing(e)
        }

        fun of(e: MaterializerException?): DependencyError {
            return AutoOneOf_DependencyError.materializer(e)
        }

        fun of(e: InvalidVisibilityDependencyException?): DependencyError {
            return AutoOneOf_DependencyError.invalidVisibility(e)
        }

        fun of(e: DependencyEvaluationException?): DependencyError {
            return AutoOneOf_DependencyError.aspectEvaluation(e)
        }

        fun of(e: AspectCreationException?): DependencyError {
            return AutoOneOf_DependencyError.aspectCreation(e)
        }

        fun of(e: PlatformMappingException?): DependencyError {
            return AutoOneOf_DependencyError.platformMapping(e)
        }

        fun of(e: InvalidPlatformException?): DependencyError {
            return AutoOneOf_DependencyError.invalidPlatform(e)
        }

        fun of(e: TransitionCreationException?): DependencyError {
            return AutoOneOf_DependencyError.transitionCreation(e)
        }

        fun of(e: BuildOptionsScopeFunctionException?): DependencyError {
            return AutoOneOf_DependencyError.buildOptionsScope(e)
        }
    }
}
