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
package com.google.devtools.build.lib.testutil


import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/**
 * Various constants required by the tests.
 */
object TestConstants {
    val LOAD_PROTO_LANG_TOOLCHAIN: String = ("load('@com_google_protobuf//bazel/toolchains:proto_lang_toolchain.bzl',"
            + " 'proto_lang_toolchain')")

    const val PRODUCT_NAME: String = "bazel"

    /**
     * A list of all embedded binaries that go into the regular Bazel binary.
     */
    val EMBEDDED_TOOLS: com.google.common.collect.ImmutableList<String?> =
        com.google.common.collect.ImmutableList.of<String?>(
            "linux-sandbox",
            "process-wrapper",
            "xcode-locator"
        )

    /**
     * Location in the bazel repo where embedded binaries come from.
     */
    val EMBEDDED_SCRIPTS_PATHS: com.google.common.collect.ImmutableList<String?> =
        com.google.common.collect.ImmutableList.of<String?>(
            "_main/src/main/tools"
        )

    /**
     * Default workspace name.
     */
    const val WORKSPACE_NAME: String = "_main"

    val OBJC_INFO_LOAD_KEY: BzlLoadValue.Key? =
        keyForBuild(Label.parseCanonicalUnchecked("@rules_cc+//cc/private:objc_info.bzl"))

    /**
     * Name of a class with an INSTANCE field of type AnalysisMock to be used for analysis tests.
     */
    const val TEST_ANALYSIS_MOCK: String = "com.google.devtools.build.lib.analysis.mock.BazelAnalysisMock"

    /**
     * Directory where we can find bazel's Java tests, relative to a test's runfiles directory.
     */
    const val JAVATESTS_ROOT: String = "_main/src/test/java/"

    /** Location of the bazel repo relative to the workspace root  */
    const val BAZEL_REPO_PATH: String = ""

    /** The file path in which to create files so that they end up under Bazel main repository.  */
    const val BAZEL_REPO_SCRATCH: String = "../_main/"

    /** Relative path to the `process-wrapper` tool.  */
    const val PROCESS_WRAPPER_PATH: String = "_main/src/main/tools/process-wrapper"

    /** Relative path to the `linux-sandbox` tool.  */
    const val LINUX_SANDBOX_PATH: String = "_main/src/main/tools/linux-sandbox"

    /** Relative path to the `spend_cpu_time` testing tool.  */
    const val CPU_TIME_SPENDER_PATH: String = "_main/src/test/shell/integration/spend_cpu_time"

    /**
     * Relative path to the protolark-created `project_proto.scl` file that `PROJECT.scl`
     * files load to define configuration.
     */
    const val PROJECT_SCL_DEFINITION_PATH: String = "src/main/protobuf/project/project_proto.scl"

    /**
     * Directory where we can find Bazel's own bootstrapping rules relative to a test's runfiles
     * directory, i.e. when //tools/build_rules:srcs is in a test's data.
     */
    const val BUILD_RULES_DATA_PATH: String = "_main/tools/build_rules/"

    const val TEST_RULE_CLASS_PROVIDER: String = "com.google.devtools.build.lib.bazel.rules.BazelRuleClassProvider"
    const val TEST_RULE_MODULE: String = "com.google.devtools.build.lib.bazel.rules.BazelRulesModule"
    const val TEST_STRATEGY_MODULE: String = "com.google.devtools.build.lib.bazel.rules.BazelStrategyModule"

    val IGNORED_MESSAGE_PREFIXES: com.google.common.collect.ImmutableList<String?> =
        com.google.common.collect.ImmutableList.of<String?>()

    /** The path in which the mock cc crosstool resides.  */
    const val MOCK_CC_CROSSTOOL_PATH: String = "tools/cpp"

    /** The path in which the mock license rule resides.  */
    const val MOCK_LICENSE_SCRATCH: String = "third_party/rules_license/"

    /** The workspace repository label under which built-in tools reside.  */
    val TOOLS_REPOSITORY: RepositoryName? = RepositoryName.BAZEL_TOOLS

    /** The file path in which to create files so that they end up under [.TOOLS_REPOSITORY].  */
    const val TOOLS_REPOSITORY_SCRATCH: String = "embedded_tools/"

    /** The directory in which rules_cc repo resides in execroot.  */
    val RULES_CC_REPOSITORY_EXECROOT: String = "external/" + RulesCcRepoName.CANONICAL_REPO_NAME + "/"

    /* Prefix for loads from rules_cc */
    const val RULES_CC: String = "@rules_cc//cc"
    const val RULES_CC_CANNONICAL: String = "@@rules_cc+//cc"
    const val MOCK_CC_SUPPORT_CLASS: String = "com.google.devtools.build.lib.packages.util.BazelMockCcSupport"

    /**
     * The repo/package rules_python is rooted at. If empty, builtin rules are used.
     */
    const val RULES_PYTHON_PACKAGE_ROOT: String = "@@rules_python+/"

    const val PYINFO_BZL: String = "@@rules_python+//python/private:py_info.bzl"

    const val PYRUNTIMEINFO_BZL: String = "@@rules_python+//python/private:py_runtime_info.bzl"

    // Constants used to determine how genrule pulls in the setup script.
    const val GENRULE_SETUP: String = "@bazel_tools//tools/genrule:genrule-setup.sh"
    const val GENRULE_SETUP_PATH: String = "genrule-setup.sh"

    const val STARLARK_EXEC_TRANSITION: String = "@_builtins//:common/builtin_exec_platforms.bzl%bazel_exec_transition"

    /**
     * Flags that must be set for Bazel to work properly, if the default values are unusable for some
     * reason.
     */
    val PRODUCT_SPECIFIC_FLAGS: com.google.common.collect.ImmutableList<String?> =
        com.google.common.collect.ImmutableList.of<String?>(
            "--platforms=@platforms//host",
            "--host_platform=@platforms//host",  // TODO(#7849): Remove after flag flip.
            "--incompatible_use_toolchain_resolution_for_java_rules",
            "--incompatible_disable_select_on=cpu,crosstool_top,host_cpu"
        )

    val PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS: com.google.common.collect.ImmutableList<String?> =
        com.google.common.collect.ImmutableList.of<String?>()

    /** Partial query to filter out implicit dependencies of C/C++ rules.  */
    val CC_DEPENDENCY_CORRECTION: String = (" - deps(" + TOOLS_REPOSITORY + "//tools/cpp:current_cc_toolchain)"
            + " - deps(" + TOOLS_REPOSITORY + "//tools/cpp:grep-includes)")

    const val APPLE_PLATFORM_PATH: String = "build_bazel_apple_support/platforms"
    const val APPLE_PLATFORM_PACKAGE_ROOT: String = "@@build_bazel_apple_support+//platforms"
    const val CONSTRAINTS_PACKAGE_ROOT: String = "@platforms//"

    const val PLATFORMS_PATH: String = "embedded_tools/platforms"
    const val CONSTRAINTS_PATH: String = "platforms_workspace"

    const val PLATFORM_LABEL: String = "@platforms//host"
    const val PIII_PLATFORM_LABEL: String = "@platforms//host:piii"

    /** The java toolchain type.  */
    const val JAVA_TOOLCHAIN_TYPE: String = "@@bazel_tools//tools/jdk:toolchain_type"

    /** The cpp toolchain type.  */
    const val CPP_TOOLCHAIN_TYPE: String = "@@bazel_tools//tools/cpp:toolchain_type"

    /** Whether blake3 can be used through JNI  */
    const val BLAKE3_AVAILABLE: Boolean = true

    /** A choice of test execution mode, only varies internally.  */
    enum class InternalTestExecutionMode {
        NORMAL
    }
}
