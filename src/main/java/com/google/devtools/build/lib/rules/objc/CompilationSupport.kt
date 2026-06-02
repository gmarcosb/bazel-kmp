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
package com.google.devtools.build.lib.rules.objc

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Optional
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.actions.Artifact

/**
 * Support for rules that compile sources. Provides ways to determine files that should be output,
 * registering Xcode settings and generating the various actions that might be needed for
 * compilation.
 * 
 * 
 * A subclass should express a particular strategy for compile and link action registration.
 * Subclasses should implement the API without adding new visible methods - rule implementations
 * should be able to use a [CompilationSupport] instance to compile and link source without
 * knowing the subclass being used.
 * 
 * 
 * Methods on this class can be called in any order without impacting the result.
 */
object CompilationSupport {
    @VisibleForTesting
    const val OBJC_MODULE_CACHE_DIR_NAME: String = "_objc_module_cache"

    @VisibleForTesting
    const val ABSOLUTE_INCLUDES_PATH_FORMAT: String = "The path '%s' is absolute, but only relative paths are allowed."


    // These are added by Xcode when building, because the simulator is built on OSX
    // frameworks so we aim compile to match the OSX objc runtime.
    @kotlin.jvm.JvmField
    @VisibleForTesting
    val SIMULATOR_COMPILE_FLAGS: ImmutableList<String?> = ImmutableList.of<String?>(
        "-fexceptions", "-fasm-blocks", "-fobjc-abi-version=2", "-fobjc-legacy-dispatch"
    )


    @VisibleForTesting
    const val FILE_IN_SRCS_AND_HDRS_WARNING_FORMAT: String = "File '%s' is in both srcs and hdrs."

    @VisibleForTesting
    const val FILE_IN_SRCS_AND_NON_ARC_SRCS_ERROR_FORMAT: String =
        "File '%s' is present in both srcs and non_arc_srcs which is forbidden."

    @VisibleForTesting
    const val BOTH_MODULE_NAME_AND_MODULE_MAP_SPECIFIED: String =
        "Specifying both module_name and module_map is invalid, please remove one of them."

    @kotlin.jvm.JvmField
    val DEFAULT_COMPILER_FLAGS: ImmutableList<String?> = ImmutableList.of<String?>("-DOS_IOS")

    fun getCustomModuleMap(ruleContext: RuleContext): Optional<Artifact?> {
        if (ruleContext.attributes().has("module_map", BuildType.LABEL)) {
            return Optional.fromNullable<T?>(ruleContext.getPrerequisiteArtifact("module_map"))
        }
        return Optional.absent<Artifact?>()
    }
}
