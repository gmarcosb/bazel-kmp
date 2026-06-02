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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey

/** Value types in Skyframe.  */
object SkyFunctions {
    @kotlin.jvm.JvmField
    val PRECOMPUTED: SkyFunctionName = SkyFunctionName.createNonHermetic("PRECOMPUTED")
    @kotlin.jvm.JvmField
    val CLIENT_ENVIRONMENT_VARIABLE: SkyFunctionName = SkyFunctionName.createNonHermetic("CLIENT_ENVIRONMENT_VARIABLE")
    @kotlin.jvm.JvmField
    val ACTION_ENVIRONMENT_VARIABLE: SkyFunctionName = SkyFunctionName.createHermetic("ACTION_ENVIRONMENT_VARIABLE")
    @kotlin.jvm.JvmField
    val REPOSITORY_ENVIRONMENT_VARIABLE: SkyFunctionName =
        SkyFunctionName.createHermetic("REPOSITORY_ENVIRONMENT_VARIABLE")
    @kotlin.jvm.JvmField
    val DIRECTORY_LISTING_STATE: SkyFunctionName = SkyFunctionName.createNonHermetic("DIRECTORY_LISTING_STATE")
    @kotlin.jvm.JvmField
    val DIRECTORY_LISTING: SkyFunctionName = SkyFunctionName.createHermetic("DIRECTORY_LISTING")
    @kotlin.jvm.JvmField
    val DIRECTORY_TREE_DIGEST: SkyFunctionName = SkyFunctionName.createHermetic("DIRECTORY_TREE_DIGEST")

    // Hermetic even though package lookups secretly access the set of deleted packages, because
    // SequencedSkyframeExecutor deletes any affected PACKAGE_LOOKUP nodes when that set changes.
    @kotlin.jvm.JvmField
    val PACKAGE_LOOKUP: SkyFunctionName = SkyFunctionName.createHermetic("PACKAGE_LOOKUP")
    @kotlin.jvm.JvmField
    val CONTAINING_PACKAGE_LOOKUP: SkyFunctionName = SkyFunctionName.createHermetic("CONTAINING_PACKAGE_LOOKUP")
    @kotlin.jvm.JvmField
    val PROJECT: SkyFunctionName = SkyFunctionName.createHermetic("PROJECT")
    @kotlin.jvm.JvmField
    val PROJECT_FILES_LOOKUP: SkyFunctionName = SkyFunctionName.createHermetic("PROJECT_FILES_LOOKUP")
    @kotlin.jvm.JvmField
    val BZL_COMPILE: SkyFunctionName = SkyFunctionName.createHermetic("BZL_COMPILE")
    @kotlin.jvm.JvmField
    val STARLARK_BUILTINS: SkyFunctionName = SkyFunctionName.createHermetic("STARLARK_BUILTINS")
    @kotlin.jvm.JvmField
    val BZL_LOAD: SkyFunctionName = SkyFunctionName.createHermetic("BZL_LOAD")

    // Depends non-hermetically on package path, but that is under the control of a flag, so use
    // semi-hermetic.
    @kotlin.jvm.JvmField
    val FILE: SkyFunctionName = SkyFunctionName.createSemiHermetic("FILE")
    @kotlin.jvm.JvmField
    val GLOB: SkyFunctionName = SkyFunctionName.createHermetic("GLOB")
    @kotlin.jvm.JvmField
    val GLOBS: SkyFunctionName = SkyFunctionName.createHermetic("GLOBS")
    @kotlin.jvm.JvmField
    val PACKAGE: SkyFunctionName = SkyFunctionName.createHermetic("PACKAGE")
    @kotlin.jvm.JvmField
    val PACKAGE_DECLARATIONS: SkyFunctionName = SkyFunctionName.createHermetic("PACKAGE_DECLARATIONS")
    @kotlin.jvm.JvmField
    val PACKAGE_ERROR: SkyFunctionName = SkyFunctionName.createHermetic("PACKAGE_ERROR")
    @kotlin.jvm.JvmField
    val PACKAGE_ERROR_MESSAGE: SkyFunctionName = SkyFunctionName.createHermetic("PACKAGE_ERROR_MESSAGE")
    @kotlin.jvm.JvmField
    val EVAL_MACRO: SkyFunctionName = SkyFunctionName.createHermetic("EVAL_MACRO")
    @kotlin.jvm.JvmField
    val MACRO_INSTANCE: SkyFunctionName = SkyFunctionName.createHermetic("MACRO_INSTANCE")
    @kotlin.jvm.JvmField
    val NON_FINALIZER_PACKAGE_PIECES: SkyFunctionName = SkyFunctionName.createHermetic("NON_FINALIZER_PACKAGE_PIECES")

    // Semi-hermetic because accesses package locator
    @kotlin.jvm.JvmField
    val TARGET_PATTERN: SkyFunctionName = SkyFunctionName.createSemiHermetic("TARGET_PATTERN")
    @kotlin.jvm.JvmField
    val TARGET_PATTERN_ERROR: SkyFunctionName = SkyFunctionName.createHermetic("TARGET_PATTERN_ERROR")
    @kotlin.jvm.JvmField
    val PREPARE_DEPS_OF_PATTERNS: SkyFunctionName = SkyFunctionName.createHermetic("PREPARE_DEPS_OF_PATTERNS")

    // Non-hermetic because accesses package locator
    @kotlin.jvm.JvmField
    val PREPARE_DEPS_OF_PATTERN: SkyFunctionName = SkyFunctionName.createNonHermetic("PREPARE_DEPS_OF_PATTERN")
    @kotlin.jvm.JvmField
    val PREPARE_DEPS_OF_TARGETS_UNDER_DIRECTORY: SkyFunctionName =
        SkyFunctionName.createHermetic("PREPARE_DEPS_OF_TARGETS_UNDER_DIRECTORY")
    @kotlin.jvm.JvmField
    val COLLECT_TARGETS_IN_PACKAGE: SkyFunctionName = SkyFunctionName.createHermetic("COLLECT_TARGETS_IN_PACKAGE")

    @kotlin.jvm.JvmField
    val COLLECT_PACKAGES_UNDER_DIRECTORY: SkyFunctionName =
        SkyFunctionName.createHermetic("COLLECT_PACKAGES_UNDER_DIRECTORY")
    @kotlin.jvm.JvmField
    val IGNORED_SUBDIRECTORIES: SkyFunctionName = SkyFunctionName.createHermetic("IGNORED_SUBDIRECTORIES")
    @kotlin.jvm.JvmField
    val TEST_SUITE_EXPANSION: SkyFunctionName = SkyFunctionName.createHermetic("TEST_SUITE_EXPANSION")
    @kotlin.jvm.JvmField
    val TESTS_IN_SUITE: SkyFunctionName = SkyFunctionName.createHermetic("TESTS_IN_SUITE")

    // Non-hermetic because accesses package locator
    @kotlin.jvm.JvmField
    val TARGET_PATTERN_PHASE: SkyFunctionName = SkyFunctionName.createNonHermetic("TARGET_PATTERN_PHASE")
    @kotlin.jvm.JvmField
    val PREPARE_ANALYSIS_PHASE: SkyFunctionName = SkyFunctionName.createNonHermetic("PREPARE_ANALYSIS_PHASE")
    @kotlin.jvm.JvmField
    val RECURSIVE_PKG: SkyFunctionName = SkyFunctionName.createHermetic("RECURSIVE_PKG")
    @kotlin.jvm.JvmField
    val CONFIGURED_TARGET: SkyFunctionName = SkyFunctionName.createHermetic("CONFIGURED_TARGET")
    @kotlin.jvm.JvmField
    val ACTION_LOOKUP_CONFLICT_FINDING: SkyFunctionName =
        SkyFunctionName.createHermetic("ACTION_LOOKUP_CONFLICT_DETECTION")
    @kotlin.jvm.JvmField
    val TOP_LEVEL_ACTION_LOOKUP_CONFLICT_FINDING: SkyFunctionName =
        SkyFunctionName.createHermetic("TOP_LEVEL_ACTION_LOOKUP_CONFLICT_DETECTION")
    @kotlin.jvm.JvmField
    val ASPECT: SkyFunctionName = SkyFunctionName.createHermetic("ASPECT")
    @kotlin.jvm.JvmField
    val TOP_LEVEL_ASPECTS: SkyFunctionName = SkyFunctionName.createHermetic("TOP_LEVEL_ASPECTS")
    @kotlin.jvm.JvmField
    val LOAD_ASPECTS: SkyFunctionName = SkyFunctionName.createHermetic("LOAD_ASPECTS")
    @kotlin.jvm.JvmField
    val TARGET_COMPLETION: SkyFunctionName = SkyFunctionName.createHermetic("TARGET_COMPLETION")
    @kotlin.jvm.JvmField
    val ASPECT_COMPLETION: SkyFunctionName = SkyFunctionName.createHermetic("ASPECT_COMPLETION")
    @kotlin.jvm.JvmField
    val TEST_COMPLETION: SkyFunctionName = SkyFunctionName.createHermetic("TEST_COMPLETION")
    @kotlin.jvm.JvmField
    val BUILD_CONFIGURATION: SkyFunctionName = SkyFunctionName.createHermetic("BUILD_CONFIGURATION")
    @kotlin.jvm.JvmField
    val BUILD_CONFIGURATION_KEY: SkyFunctionName = SkyFunctionName.createHermetic("BUILD_CONFIGURATION_KEY")
    @kotlin.jvm.JvmField
    val PARSED_FLAGS: SkyFunctionName = SkyFunctionName.createHermetic("PARSED_FLAGS")
    @kotlin.jvm.JvmField
    val BASELINE_OPTIONS: SkyFunctionName = SkyFunctionName.createNonHermetic("BASELINE_OPTIONS")
    @kotlin.jvm.JvmField
    val STARLARK_BUILD_SETTINGS_DETAILS: SkyFunctionName =
        SkyFunctionName.createHermetic("STARLARK_BUILD_SETTINGS_DETAILS")

    // Action execution can be nondeterministic, so semi-hermetic.
    @kotlin.jvm.JvmField
    val ACTION_EXECUTION: SkyFunctionName = SkyFunctionName.createSemiHermetic("ACTION_EXECUTION")
    @kotlin.jvm.JvmField
    val ARTIFACT_NESTED_SET: SkyFunctionName = SkyFunctionName.createHermetic("ARTIFACT_NESTED_SET")
    @kotlin.jvm.JvmField
    val RECURSIVE_FILESYSTEM_TRAVERSAL: SkyFunctionName =
        SkyFunctionName.createHermetic("RECURSIVE_FILESYSTEM_TRAVERSAL")
    val FILESET_ENTRY: SkyFunctionName = SkyFunctionName.createHermetic("FILESET_ENTRY")
    @kotlin.jvm.JvmField
    val BUILD_INFO: SkyFunctionName = SkyFunctionName.createHermetic("BUILD_INFO")
    @kotlin.jvm.JvmField
    val PLATFORM: SkyFunctionName = SkyFunctionName.createHermetic("PLATFORM")
    @kotlin.jvm.JvmField
    val PLATFORM_MAPPING: SkyFunctionName = SkyFunctionName.createHermetic("PLATFORM_MAPPING")
    @kotlin.jvm.JvmField
    val COVERAGE_REPORT: SkyFunctionName = SkyFunctionName.createHermetic("COVERAGE_REPORT")
    @kotlin.jvm.JvmField
    val REPOSITORY_DIRECTORY: SkyFunctionName = SkyFunctionName.createNonHermetic("REPOSITORY_DIRECTORY")
    @kotlin.jvm.JvmField
    val ACTION_TEMPLATE_EXPANSION: SkyFunctionName = SkyFunctionName.createHermetic("ACTION_TEMPLATE_EXPANSION")
    @kotlin.jvm.JvmField
    val LOCAL_REPOSITORY_LOOKUP: SkyFunctionName = SkyFunctionName.createHermetic("LOCAL_REPOSITORY_LOOKUP")
    @kotlin.jvm.JvmField
    val REGISTERED_EXECUTION_PLATFORMS: SkyFunctionName =
        SkyFunctionName.createHermetic("REGISTERED_EXECUTION_PLATFORMS")
    @kotlin.jvm.JvmField
    val REGISTERED_TOOLCHAINS: SkyFunctionName = SkyFunctionName.createHermetic("REGISTERED_TOOLCHAINS")
    @kotlin.jvm.JvmField
    val SINGLE_TOOLCHAIN_RESOLUTION: SkyFunctionName = SkyFunctionName.createHermetic("SINGLE_TOOLCHAIN_RESOLUTION")
    @kotlin.jvm.JvmField
    val TOOLCHAIN_RESOLUTION: SkyFunctionName = SkyFunctionName.createHermetic("TOOLCHAIN_RESOLUTION")
    @kotlin.jvm.JvmField
    val REPOSITORY_MAPPING: SkyFunctionName = SkyFunctionName.createHermetic("REPOSITORY_MAPPING")
    @kotlin.jvm.JvmField
    val MODULE_FILE: SkyFunctionName = SkyFunctionName.createNonHermetic("MODULE_FILE")
    @kotlin.jvm.JvmField
    val REPO_PACKAGE_ARGS: SkyFunctionName = SkyFunctionName.createHermetic("REPO_PACKAGE_ARGS")
    @kotlin.jvm.JvmField
    val REPO_FILE: SkyFunctionName = SkyFunctionName.createHermetic("REPO_FILE")
    @kotlin.jvm.JvmField
    val BUILD_DRIVER: SkyFunctionName = SkyFunctionName.createNonHermetic("BUILD_DRIVER")

    val BAZEL_MOD_TIDY: SkyFunctionName = SkyFunctionName.createHermetic("BAZEL_MOD_TIDY")
    @kotlin.jvm.JvmField
    val BAZEL_MODULE_RESOLUTION: SkyFunctionName = SkyFunctionName.createHermetic("BAZEL_MODULE_RESOLUTION")
    val BAZEL_MODULE_INSPECTION: SkyFunctionName = SkyFunctionName.createHermetic("BAZEL_MODULE_INSPECTION")
    @kotlin.jvm.JvmField
    val SINGLE_EXTENSION_USAGES: SkyFunctionName = SkyFunctionName.createHermetic("SINGLE_EXTENSION_USAGES")
    @kotlin.jvm.JvmField
    val SINGLE_EXTENSION: SkyFunctionName = SkyFunctionName.createHermetic("SINGLE_EXTENSION")
    @kotlin.jvm.JvmField
    val SINGLE_EXTENSION_EVAL: SkyFunctionName = SkyFunctionName.createNonHermetic("SINGLE_EXTENSION_EVAL")
    @kotlin.jvm.JvmField
    val BAZEL_DEP_GRAPH: SkyFunctionName = SkyFunctionName.createHermetic("BAZEL_DEP_GRAPH")
    @kotlin.jvm.JvmField
    val BAZEL_LOCK_FILE: SkyFunctionName = SkyFunctionName.createHermetic("BAZEL_LOCK_FILE")
    val BAZEL_FETCH_ALL: SkyFunctionName = SkyFunctionName.createHermetic("BAZEL_FETCH_ALL")
    @kotlin.jvm.JvmField
    val REGISTRY: SkyFunctionName = SkyFunctionName.createNonHermetic("REGISTRY")
    @kotlin.jvm.JvmField
    val REPO_SPEC: SkyFunctionName = SkyFunctionName.createNonHermetic("REPO_SPEC")
    @kotlin.jvm.JvmField
    val YANKED_VERSIONS: SkyFunctionName = SkyFunctionName.createNonHermetic("YANKED_VERSIONS")

    @kotlin.jvm.JvmField
    val MODULE_EXTENSION_REPO_MAPPING_ENTRIES: SkyFunctionName =
        SkyFunctionName.createHermetic("MODULE_EXTENSION_REPO_MAPPING_ENTRIES")
    val VENDOR_FILE: SkyFunctionName = SkyFunctionName.createHermetic("VENDOR_FILE")

    @kotlin.jvm.JvmField
    val FLAG_SET: SkyFunctionName = SkyFunctionName.createHermetic("FLAG_SET")
    @kotlin.jvm.JvmField
    val BUILD_OPTIONS_SCOPE: SkyFunctionName = SkyFunctionName.createHermetic("BUILD_OPTIONS_SCOPE")

    fun isSkyFunction(functionName: SkyFunctionName?): com.google.common.base.Predicate<SkyKey?> {
        return com.google.common.base.Predicate { key: SkyKey? -> key.functionName() == functionName }
    }
}
