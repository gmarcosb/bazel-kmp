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
package com.google.devtools.build.lib.packages.util

import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.base.Predicate
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.actions.Artifact
import java.util.*

/**
 * Creates mock BUILD files required for the C/C++ rules.
 */
abstract class MockCcSupport {
    /** Filter to remove implicit dependencies of C/C++ rules.  */
    private val ccLabelFilter: Predicate<Label?> = object : Predicate<Label?> {
        override fun apply(label: Label): Boolean {
            return labelNameFilter()!!.apply("//" + label.getPackageName())
        }
    }

    abstract fun labelNameFilter(): Predicate<String?>?

    /**
     * Setup the support for building C/C++.
     */
    @Throws(IOException::class)
    abstract fun setup(config: MockToolsConfig?)

    @Throws(IOException::class)
    fun setupCcToolchainConfigForCpu(config: MockToolsConfig, vararg cpus: String?) {
        val toolchainConfigBuilder: ImmutableList.Builder<CcToolchainConfig?> =
            ImmutableList.builder<CcToolchainConfig?>()
        toolchainConfigBuilder.add(CcToolchainConfig.Companion.getDefaultCcToolchainConfig())
        for (cpu in cpus) {
            toolchainConfigBuilder.add(CcToolchainConfig.Companion.getCcToolchainConfigForCpu(cpu))
        }
        setupCcToolchainConfig(config, toolchainConfigBuilder.build())
    }

    protected open fun shouldUseRealFileSystemCrosstool(): Boolean {
        return true
    }

    @kotlin.jvm.JvmOverloads
    @Throws(IOException::class)
    fun setupCcToolchainConfig(
        config: MockToolsConfig,
        ccToolchainConfig: Crosstool.CcToolchainConfig.Builder = CcToolchainConfig.Companion.builder()
    ) {
        setupCcToolchainConfig(config, ImmutableList.of<CcToolchainConfig?>(ccToolchainConfig.build()))
    }

    @Throws(IOException::class)
    fun setupCcToolchainConfig(
        config: MockToolsConfig, toolchainConfigBuilder: ImmutableList.Builder<CcToolchainConfig?>
    ) {
        setupCcToolchainConfig(config, toolchainConfigBuilder.build())
    }

    @Throws(IOException::class)
    fun setupCcToolchainConfig(
        config: MockToolsConfig, ccToolchainConfigs: ImmutableList<CcToolchainConfig?>?
    ) {
        if (config.isRealFileSystem() && shouldUseRealFileSystemCrosstool()) {
            val crosstoolTopPath = this.realFilesystemCrosstoolTopPath
            config.linkTools(*getRealFilesystemToolsToLink(crosstoolTopPath))
            config.copyTools(*getRealFilesystemToolsToCopy(crosstoolTopPath))
            writeToolchainsForRealFilesystemTools(config, crosstoolTopPath)
        } else {
            Crosstool(config, this.mockCrosstoolPath, this.mockCrosstoolLabel)
                .setCcToolchainFile(readCcToolchainConfigFile())
                .setSupportedArchs(this.crosstoolArchs)
                .setToolchainConfigs(ccToolchainConfigs)
                .setSupportsHeaderParsing(true)
                .write()
        }
    }

    /** Writes a basic toolchain definition to keep the CC tests working.  */ // TODO(cc-rules): Remove this when crosstool provides its own toolchain definitions.
    @Throws(IOException::class)
    private fun writeToolchainsForRealFilesystemTools(
        config: MockToolsConfig, crosstoolTopPath: String?
    ) {
        config.create(
            "toolchains/BUILD",
            "toolchain(",
            "    name = 'k8-toolchain',",
            "    toolchain = '//" + crosstoolTopPath + ":cc-compiler-k8-llvm.k8',",
            "    toolchain_type = '" + TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain_type',",
            "    target_compatible_with = [",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64',",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux',",
            "    ],",
            ")",
            "toolchain(",
            "    name = 'arm-toolchain',",
            "    toolchain = '//" + crosstoolTopPath + ":cc-compiler-arm-llvm.k8',",
            "    toolchain_type = '" + TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain_type',",
            "    target_compatible_with = [",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:armv7',",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:android',",
            "    ],",
            ")"
        )
        config.append("MODULE.bazel", "register_toolchains('//toolchains:all')")
    }

    @Throws(IOException::class)
    protected fun setupRulesCc(config: MockToolsConfig) {
        for (path in ImmutableList.of<String?>(
            "cc/BUILD",
            "cc/defs.bzl",
            "cc/action_names.bzl",
            "cc/cc_binary.bzl",
            "cc/cc_import.bzl",
            "cc/cc_library.bzl",
            "cc/cc_test.bzl",
            "cc/cc_toolchain_config_lib.bzl",
            "cc/objc_import.bzl",
            "cc/objc_library.bzl",
            "cc/common/BUILD",
            "cc/common/cc_common.bzl",
            "cc/common/cc_helper.bzl",
            "cc/common/cc_shared_library_info.bzl",
            "cc/common/semantics.bzl",
            "cc/find_cc_toolchain.bzl",
            "cc/toolchains/BUILD",
            "cc/toolchains/cc_toolchain.bzl",
            "cc/toolchains/cc_toolchain_alias.bzl",
            "cc/toolchains/cc_toolchain_config_info.bzl",
            "cc/toolchains/tool_map.bzl",
            "cc/toolchain_utils.bzl"
        )) {
            try {
                val content =
                    ResourceLoader.readFromResources(TestConstants.RULES_CC_REPOSITORY_EXECROOT + path)
                config.overwrite("third_party/bazel_rules/rules_cc/" + path, content)
            } catch (e: Exception) {
                throw RuntimeException("Couldn't read rules_cc file from " + path, e)
            }
        }

        // We handle these files separately and ignore errors since some files are only present in Bazel
        // or Blaze contexts.
        for (path in ImmutableList.of<String?>(
            "cc/private/rules_impl/BUILD",
            "cc/private/rules_impl/native.bzl",
            "cc/private/rules_impl/blaze.bzl",
            "cc/private/rules_impl/wrappers/BUILD",
            "cc/private/rules_impl/native_cc_common.bzl",
            "cc/private/rules_impl/native_providers.bzl"
        )) {
            try {
                config.overwrite(
                    "third_party/bazel_rules/rules_cc/" + path,
                    ResourceLoader.readFromResources(TestConstants.RULES_CC_REPOSITORY_EXECROOT + path)
                )
            } catch (e: Exception) {
                // ignore
            }
        }

        config.overwrite(
            TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/cpp/cc_toolchain_config_lib.bzl",
            ResourceLoader.readFromResources(
                TestConstants.RULES_CC_REPOSITORY_EXECROOT + "cc/cc_toolchain_config_lib.bzl"
            )
        )
        config.overwrite(
            TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/build_defs/cc/action_names.bzl",
            ResourceLoader.readFromResources(
                TestConstants.RULES_CC_REPOSITORY_EXECROOT + "cc/action_names.bzl"
            )
        )
        config.create(
            TestConstants.RULES_CC_REPOSITORY_EXECROOT + "BUILD",
            "filegroup(name='license', visibility=['//visibility:public'])"
        )
        config.create(TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/build_defs/cc/BUILD")
        config.create(
            TestConstants.TOOLS_REPOSITORY_SCRATCH + "third_party/gloop/tools/build_defs/cc/BUILD"
        )
        config.append(TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/cpp/BUILD", "")

        // These could be a distinct method
        config.create(
            TestConstants.TOOLS_REPOSITORY_SCRATCH + TestConstants.MOCK_LICENSE_SCRATCH + "BUILD",
            "filegroup(name='license', visibility=['//visibility:public'])"
        )
        config.create(
            (TestConstants.TOOLS_REPOSITORY_SCRATCH
                    + TestConstants.MOCK_LICENSE_SCRATCH
                    + "rules/BUILD")
        )
        config.create(
            (TestConstants.TOOLS_REPOSITORY_SCRATCH
                    + TestConstants.MOCK_LICENSE_SCRATCH
                    + "rules/license.bzl"),
            "def license(name, **kwargs):",
            "    pass",
            ""
        )
    }

    protected fun getCrosstoolTopPathForConfig(config: MockToolsConfig): String? {
        if (config.isRealFileSystem()) {
            return this.realFilesystemCrosstoolTopPath
        } else {
            return this.mockCrosstoolPath
        }
    }

    abstract val mockCrosstoolPath: String?

    @Throws(IOException::class)
    protected fun readCcToolchainConfigFile(): String {
        return ResourceLoader.readFromResources(
            "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"
        )
    }

    abstract val mockCrosstoolLabel: Label?

    protected abstract val crosstoolArchs: ImmutableList<String?>?

    protected abstract fun getRealFilesystemToolsToLink(crosstoolTop: String?): Array<String?>?

    protected abstract fun getRealFilesystemToolsToCopy(crosstoolTop: String?): Array<String?>?

    protected abstract val realFilesystemCrosstoolTopPath: String?

    fun labelFilter(): Predicate<Label?> {
        return ccLabelFilter
    }

    @Throws(IOException::class)
    fun writeMacroFile(config: MockToolsConfig) {
        val ruleNamesBuilder =
            ImmutableList.builder<String?>()
                .add(
                    "cc_library",
                    "cc_binary",
                    "cc_test",
                    "cc_import",
                    "objc_import",
                    "objc_library",
                    "cc_toolchain",
                    "fdo_profile",
                    "fdo_prefetch_hints"
                )
        if (TestConstants.PRODUCT_NAME == "bazel") {
            ruleNamesBuilder.add("cc_proto_library")
        }
        val ruleNames = ruleNamesBuilder.build()
        config.create(TestConstants.TOOLS_REPOSITORY_SCRATCH + "third_party/cc_rules/macros/BUILD", "")

        val macros = StringBuilder()
        macros.append("load('@rules_cc//cc:defs.bzl'")
        for (ruleName in ruleNames) {
            macros.append(", _").append(ruleName).append("='").append(ruleName).append("'")
        }
        macros.append(")\n")
        for (ruleName in ruleNames) {
            Joiner.on("\n")
                .appendTo(
                    macros,
                    "def " + ruleName + "(**attrs):",
                    "    if 'tags' in attrs and attrs['tags'] != None:",
                    "        attrs['tags'] = attrs['tags'] +"
                            + " ['__CC_RULES_MIGRATION_DO_NOT_USE_WILL_BREAK__']",
                    "    else:",
                    "        attrs['tags'] = ['__CC_RULES_MIGRATION_DO_NOT_USE_WILL_BREAK__']",
                    "    _" + ruleName + "(**attrs)"
                )
            macros.append("\n")
        }
        config.create(
            TestConstants.TOOLS_REPOSITORY_SCRATCH + "third_party/cc_rules/macros/defs.bzl",
            macros.toString()
        )
    }

    fun getMacroLoadStatement(loadMacro: Boolean, vararg ruleNames: String?): String {
        if (!loadMacro) {
            return ""
        }
        Preconditions.checkState(ruleNames.size > 0)
        val loadStatement: StringBuilder =
            StringBuilder()
                .append("load('")
                .append(TestConstants.TOOLS_REPOSITORY)
                .append("//third_party/cc_rules/macros:defs.bzl', ")
        val quotedRuleNames = ImmutableList.builder<String?>()
        for (ruleName in ruleNames) {
            quotedRuleNames.add(String.format("'%s'", ruleName))
        }
        Joiner.on(",").appendTo(loadStatement, quotedRuleNames.build())
        loadStatement.append(")")
        return loadStatement.toString()
    }

    companion object {
        /** Filter to remove implicit crosstool artifact and module map inputs of C/C++ rules.  */
        val CC_ARTIFACT_FILTER: Predicate<Artifact?> = Predicate { artifact: Artifact? ->
            val pathString: String = artifact.getExecPathString()
            !pathString.startsWith("third_party/crosstool/") && !pathString.startsWith("tools/cpp/link_dynamic_library") && !pathString.startsWith(
                "tools/cpp/build_interface_so"
            ) && !pathString.startsWith("_bin/build_interface_so") && !pathString.endsWith(".cppmap") && !pathString.startsWith(
                "tools/cpp/grep-includes"
            )
        }

        /** This feature will prevent bazel from patching the crosstool.  */
        const val NO_LEGACY_FEATURES_FEATURE: String = "feature { name: 'no_legacy_features' }"

        val SUPPORTS_INTERFACE_SHARED_LIBRARIES_FEATURE: String =
            "feature { name: '" + CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES + "' enabled: true}"

        const val SIMPLE_LAYERING_CHECK_FEATURE_CONFIGURATION: String = "simple_layering_check"

        const val HEADER_MODULES_FEATURES: String = "header_modules_feature_configuration"

        /** A feature configuration snippet useful for testing environment variables.  */
        const val ENV_VAR_FEATURES: String = "env_var_feature_configuration"

        const val HOST_AND_NONHOST_CONFIGURATION_FEATURES: String = "host_and_nonhost_configuration"

        const val USER_COMPILE_FLAGS: String = "user_compile_flags"

        const val AUTOFDO_IMPLICIT_THINLTO: String = "autofdo_implicit_thinlto"

        const val FDO_IMPLICIT_THINLTO: String = "fdo_implicit_thinlto"

        const val XFDO_IMPLICIT_THINLTO: String = "xbinaryfdo_implicit_thinlto"

        const val FDO_SPLIT_FUNCTIONS: String = "fdo_split_functions"

        const val SPLIT_FUNCTIONS: String = "split_functions"

        const val FSAFDO: String = "fsafdo"

        const val IMPLICIT_FSAFDO: String = "implicit_fsafdo"

        const val ENABLE_FSAFDO: String = "enable_fsafdo"

        @kotlin.jvm.JvmField
        val STATIC_LINK_TWEAKED_ARTIFACT_NAME_PATTERN: ImmutableList<String?> =
            ImmutableList.of<String?>("static_library", "lib", ".lib")

        @kotlin.jvm.JvmField
        val STATIC_LINK_AS_DOT_A_ARTIFACT_NAME_PATTERN: ImmutableList<String?> =
            ImmutableList.of<String?>("static_library", "lib", ".a")

        val EMPTY_EXECUTABLE_ACTION_CONFIG: String? = emptyActionConfigFor(LinkTargetType.EXECUTABLE.actionName)

        @kotlin.jvm.JvmField
        val EMPTY_CC_TOOLCHAIN: String = Joiner.on("\n")
            .join(
                "load(\"@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl\","
                        + " \"CcToolchainConfigInfo\")",
                "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
                "def _impl(ctx):",
                "    return cc_common.create_cc_toolchain_config_info(",
                "                ctx = ctx,",
                "                toolchain_identifier = 'mock-llvm-toolchain-k8',",
                "                host_system_name = 'mock-system-name-for-k8',",
                "                target_system_name = 'mock-target-system-name-for-k8',",
                "                target_cpu = 'k8',",
                "                target_libc = 'mock-libc-for-k8',",
                "                compiler = 'mock-compiler-for-k8',",
                "                abi_libc_version = 'mock-abi-libc-for-k8',",
                "                abi_version = 'mock-abi-version-for-k8')",
                "cc_toolchain_config = rule(",
                "    implementation = _impl,",
                "    attrs = {},",
                "    provides = [CcToolchainConfigInfo],",
                ")"
            )

        val EMPTY_CROSSTOOL: String = "major_version: 'foo'\nminor_version:' foo'\n" + emptyToolchainForCpu("k8")

        const val SIMPLE_COMPILE_FEATURE: String = "simple_compile_feature"
        const val CPP_COMPILE_ACTION_WITH_REQUIREMENTS: String = "cpp_compile_with_requirements"

        fun emptyToolchainForCpu(cpu: String?, vararg append: String?): String {
            return Joiner.on("\n")
                .join(
                    ImmutableList.builder<Any?>()
                        .add(
                            "toolchain {",
                            "  toolchain_identifier: 'mock-llvm-toolchain-" + cpu + "'",
                            "  host_system_name: 'mock-system-name-for-" + cpu + "'",
                            "  target_system_name: 'mock-target-system-name-for-" + cpu + "'",
                            "  target_cpu: '" + cpu + "'",
                            "  target_libc: 'mock-libc-for-" + cpu + "'",
                            "  compiler: 'mock-compiler-for-" + cpu + "'",
                            "  abi_version: 'mock-abi-version-for-" + cpu + "'",
                            "  abi_libc_version: 'mock-abi-libc-for-" + cpu + "'"
                        )
                        .addAll(ImmutableList.copyOf<String?>(append))
                        .add("}")
                        .build()
                )
        }

        /**
         * Creates action_config for `actionName` action using DUMMY_TOOL that doesn't imply any
         * features.
         */
        private fun emptyActionConfigFor(actionName: String?): String? {
            return String.format(
                ("action_config {"
                        + "  config_name: '%s'"
                        + "  action_name: '%s'"
                        + "  tool {"
                        + "    tool_path: 'DUMMY_TOOL'"
                        + "  }"
                        + "}"),
                actionName, actionName
            )
        }

        @kotlin.jvm.JvmStatic
        fun get(): MockCcSupport? {
            try {
                val providerClass = Class.forName(TestConstants.MOCK_CC_SUPPORT_CLASS)
                val instanceField = providerClass.getField("INSTANCE")
                return (instanceField.get(null) as MockCcSupport?)
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }

        @Throws(IOException::class)
        protected fun createParseHeadersAndLayeringCheckWhitelist(config: MockToolsConfig) {
            config.create(
                TestConstants.TOOLS_REPOSITORY_SCRATCH
                        + "tools/build_defs/cc/whitelists/parse_headers_and_layering_check/BUILD",
                "package_group(",
                "    name = 'disabling_parse_headers_and_layering_check_allowed',",
                "    packages = ['//...']",
                ")"
            )
        }

        @Throws(IOException::class)
        fun createStarlarkLooseHeadersWhitelist(config: MockToolsConfig, vararg packages: String?) {
            val joinedPackages: String? = Arrays.stream<String?>(packages).map<String?> { s: String? -> "'" + s + "'" }
                .collect(Collectors.joining(","))
            config.overwrite(
                TestConstants.TOOLS_REPOSITORY_SCRATCH
                        + "tools/build_defs/cc/whitelists/starlark_hdrs_check/BUILD",
                "package_group(",
                "    name = 'loose_header_check_allowed_in_toolchain',",
                "    packages = [" + joinedPackages + "]",
                ")"
            )
        }

        val mockCrosstoolsTop: PackageIdentifier
            get() = PackageIdentifier.create(
                TestConstants.TOOLS_REPOSITORY, PathFragment.create(TestConstants.MOCK_CC_CROSSTOOL_PATH)
            )

        @Throws(IOException::class)
        fun writeCcRuntimeToolchains(scratch: Scratch) {
            scratch.file(
                "runtimes/toolchain.bzl",
                """
        BuildSettingInfo = provider(fields = ["value"])

        def _bool_flag_impl(ctx):
            return BuildSettingInfo(value = ctx.build_setting_value)

        bool_flag = rule(
            implementation = _bool_flag_impl,
            build_setting = config.bool(),
        )

        def _include_runtimes_transition_impl(_settings, _attr):
            return {"//runtimes:include_runtimes": False}

        _include_runtimes_transition = transition(
            implementation = _include_runtimes_transition_impl,
            inputs = [],
            outputs = ["//runtimes:include_runtimes"],
        )
        CcRuntimesInfo = provider(fields = ["runtimes", "copts"])

        def _cc_runtimes_toolchain_impl(ctx):
            return [platform_common.ToolchainInfo(
                cc_runtimes_info = CcRuntimesInfo(
                    runtimes = ctx.attr.runtimes,
                    copts = ctx.attr.copts,
                ),
            )]

        cc_runtimes_toolchain = rule(
            implementation = _cc_runtimes_toolchain_impl,
            attrs = {
                "runtimes": attr.label_list(cfg = _include_runtimes_transition),
                "copts": attr.string_list(),
            },
        )
        
        """.trimIndent()
            )

            scratch.file(
                "runtimes/BUILD",
                """
        load("//runtimes:toolchain.bzl", "bool_flag", "cc_runtimes_toolchain")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")

        bool_flag(
            name = "include_runtimes",
            build_setting_default = True,
        )

        config_setting(
            name = "include_runtimes_config",
            flag_values = {":include_runtimes": "True"},
        )

        cc_library(
            name = "runtime",
            srcs = ["runtime.cc"],
            hdrs = ["runtime.h"],
        )

        cc_runtimes_toolchain(
            name = "runtimes_toolchain",
            copts = ["-Iruntimes"],
            runtimes = [":runtime"],
        )

        toolchain(
            name = "toolchain",
            target_settings = [":include_runtimes_config"],
            toolchain = ":runtimes_toolchain",
            toolchain_type = "//tools/cpp:cc_runtimes_toolchain_type",
        )
        
        """.trimIndent()
            )
        }
    }
}
