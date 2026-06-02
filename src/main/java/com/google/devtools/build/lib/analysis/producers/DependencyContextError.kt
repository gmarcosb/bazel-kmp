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

import com.google.devtools.build.lib.analysis.constraints.IncompatibleTargetChecker.IncompatibleTargetException

/** Tagged union of errors that can be encountered when creating the [DependencyContext].  */
@AutoOneOf(com.google.devtools.build.lib.analysis.producers.DependencyContextError.Kind::class)
abstract class DependencyContextError {
    /** Tags for errors types that may occur.  */
    enum class Kind {
        TOOLCHAIN,
        CONFIGURED_VALUE_CREATION,
        INCOMPATIBLE_TARGET,
        VALIDATION
    }

    abstract fun kind(): Kind?

    abstract fun toolchain(): ToolchainException?

    abstract fun configuredValueCreation(): ConfiguredValueCreationException?

    /** This error is only possible for [DependencyContextProducerWithCompatibilityCheck].  */
    abstract fun incompatibleTarget(): IncompatibleTargetException?

    /** This error is only possible for [DependencyContextProducerWithCompatibilityCheck].  */
    abstract fun validation(): com.google.devtools.build.lib.packages.ConfiguredAttributeMapper.ValidationException?

    companion object {
        fun of(error: ToolchainException?): DependencyContextError {
            return AutoOneOf_DependencyContextError.toolchain(error)
        }

        fun of(error: ConfiguredValueCreationException?): DependencyContextError {
            return AutoOneOf_DependencyContextError.configuredValueCreation(error)
        }

        fun of(error: IncompatibleTargetException?): DependencyContextError {
            return AutoOneOf_DependencyContextError.incompatibleTarget(error)
        }

        fun of(error: com.google.devtools.build.lib.packages.ConfiguredAttributeMapper.ValidationException?): DependencyContextError {
            return AutoOneOf_DependencyContextError.validation(error)
        }
    }
}
