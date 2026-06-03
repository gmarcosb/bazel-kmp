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
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.cmdline.Label
import com.google.errorprone.annotations.CanIgnoreReturnValue
import kotlin.collections.ArrayList
import kotlin.collections.MutableCollection
import kotlin.collections.MutableList
import kotlin.collections.MutableSet

/**
 * A helper class to create a crosstool package containing a CROSSTOOL file, and the various rules
 * needed for a mock - use this only for configured target tests, not for execution tests.
 */
class Crosstool internal constructor(
    private val config: MockToolsConfig,
    private val crosstoolTop: String?,
    crosstoolTopLabel: Label
) {
    /**
     * A class that contains relevant fields from either the CROSSTOOL file or the Starlark rule
     * implementation that are needed in order to generate the BUILD file.
     */
    class CcToolchainConfig private constructor(
        val targetCpu: String?,
        val compiler: String?,
        val toolchainIdentifier: String?,
        private val hostSystemName: String?,
        private val targetSystemName: String?,
        private val abiVersion: String?,
        private val abiLibcVersion: String?,
        private val targetLibc: String?,
        builtinSysroot: String?,
        ccTargetOs: String?,
        private val features: ImmutableList<String?>,
        private val actionConfigs: ImmutableList<String?>,
        private val artifactNamePatterns: ImmutableList<ImmutableList<String?>?>,
        toolPaths: ImmutableList<Pair<String?, String?>?>,
        cxxBuiltinIncludeDirectories: ImmutableList<String?>,
        makeVariables: ImmutableList<Pair<String?, String?>?>,
        toolchainExecConstraints: ImmutableList<String?>,
        toolchainTargetConstraints: ImmutableList<String?>
    ) {
        private val builtinSysroot: String?
        private val ccTargetOs: String?
        private val toolPaths: ImmutableList<Pair<String?, String?>?>
        private val cxxBuiltinIncludeDirectories: ImmutableList<String?>
        private val makeVariables: ImmutableList<Pair<String?, String?>?>
        private val toolchainExecConstraints: ImmutableList<String?>
        private val toolchainTargetConstraints: ImmutableList<String?>

        init {
            this.toolPaths = toolPaths
            this.builtinSysroot = builtinSysroot
            this.cxxBuiltinIncludeDirectories = cxxBuiltinIncludeDirectories
            this.makeVariables = makeVariables
            this.ccTargetOs = ccTargetOs
            this.toolchainExecConstraints = toolchainExecConstraints
            this.toolchainTargetConstraints = toolchainTargetConstraints
        }

        /** A Builder for [CcToolchainConfig].  */
        class Builder {
            private var features: ImmutableList<String?> = ImmutableList.of<String?>()
            private var actionConfigs: ImmutableList<String?> = ImmutableList.of<String?>()
            private var artifactNamePatterns: ImmutableList<ImmutableList<String?>?> =
                ImmutableList.of<ImmutableList<String?>?>()
            private var toolPaths: ImmutableList<Pair<String?, String?>?> = ImmutableList.of<Pair<String?, String?>?>()
            private var builtinSysroot: String? = "/usr/grte/v1"
            private var cxxBuiltinIncludeDirectories: ImmutableList<String?> = ImmutableList.of<String?>()
            private var makeVariables: ImmutableList<Pair<String?, String?>?> =
                ImmutableList.of<Pair<String?, String?>?>()
            private var ccTargetOs: String? = ""
            private var cpu: String? = "k8"
            private var compiler: String? = "compiler"
            private var toolchainIdentifier: String? = "mock-toolchain-k8"
            private var hostSystemName: String? = "local"
            private var targetSystemName: String? = "local"
            private var targetLibc: String? = "local"
            private var abiVersion: String? = "local"
            private var abiLibcVersion: String? = "local"
            private var toolchainExecConstraints: ImmutableList<String?> = ImmutableList.of<String?>(
                TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64",
                TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux"
            )
            private var toolchainTargetConstraints: ImmutableList<String?> = ImmutableList.of<String?>(
                TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64",
                TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux"
            )

            @CanIgnoreReturnValue
            fun withCpu(cpu: String?): Builder {
                this.cpu = cpu
                return this
            }

            @CanIgnoreReturnValue
            fun withCompiler(compiler: String?): Builder {
                this.compiler = compiler
                return this
            }

            @CanIgnoreReturnValue
            fun withToolchainIdentifier(toolchainIdentifier: String?): Builder {
                this.toolchainIdentifier = toolchainIdentifier
                return this
            }

            @CanIgnoreReturnValue
            fun withHostSystemName(hostSystemName: String?): Builder {
                this.hostSystemName = hostSystemName
                return this
            }

            @CanIgnoreReturnValue
            fun withTargetSystemName(targetSystemName: String?): Builder {
                this.targetSystemName = targetSystemName
                return this
            }

            @CanIgnoreReturnValue
            fun withTargetLibc(targetLibc: String?): Builder {
                this.targetLibc = targetLibc
                return this
            }

            @CanIgnoreReturnValue
            fun withAbiVersion(abiVersion: String?): Builder {
                this.abiVersion = abiVersion
                return this
            }

            @CanIgnoreReturnValue
            fun withAbiLibcVersion(abiLibcVersion: String?): Builder {
                this.abiLibcVersion = abiLibcVersion
                return this
            }

            @CanIgnoreReturnValue
            fun withFeatures(vararg features: String?): Builder {
                this.features = ImmutableList.copyOf<String?>(features)
                return this
            }

            @CanIgnoreReturnValue
            fun withActionConfigs(vararg actionConfigs: String?): Builder {
                this.actionConfigs = ImmutableList.copyOf<String?>(actionConfigs)
                return this
            }

            @CanIgnoreReturnValue
            fun withArtifactNamePatterns(vararg artifactNamePatterns: ImmutableList<String?>): Builder {
                for (pattern in artifactNamePatterns) {
                    Preconditions.checkArgument(
                        pattern.size == 3,
                        "Artifact name pattern should have three attributes: category_name, prefix and"
                                + " extension"
                    )
                }
                this.artifactNamePatterns = ImmutableList.copyOf<ImmutableList<String?>?>(artifactNamePatterns)
                return this
            }

            @CanIgnoreReturnValue
            fun withToolPaths(vararg toolPaths: Pair<String?, String?>?): Builder {
                this.toolPaths = ImmutableList.copyOf<Pair<String?, String?>?>(toolPaths)
                return this
            }

            @CanIgnoreReturnValue
            fun withSysroot(sysroot: String?): Builder {
                this.builtinSysroot = sysroot
                return this
            }

            @CanIgnoreReturnValue
            fun withCcTargetOs(ccTargetOs: String?): Builder {
                this.ccTargetOs = ccTargetOs
                return this
            }

            @CanIgnoreReturnValue
            fun withCxxBuiltinIncludeDirectories(vararg directories: String?): Builder {
                this.cxxBuiltinIncludeDirectories = ImmutableList.copyOf<String?>(directories)
                return this
            }

            @CanIgnoreReturnValue
            fun withMakeVariables(vararg makeVariables: Pair<String?, String?>?): Builder {
                this.makeVariables = ImmutableList.copyOf<Pair<String?, String?>?>(makeVariables)
                return this
            }

            @CanIgnoreReturnValue
            fun withToolchainExecConstraints(vararg execConstraints: String?): Builder {
                this.toolchainExecConstraints = ImmutableList.copyOf<String?>(execConstraints)
                return this
            }

            @CanIgnoreReturnValue
            fun withToolchainTargetConstraints(vararg targetConstraints: String?): Builder {
                this.toolchainTargetConstraints = ImmutableList.copyOf<String?>(targetConstraints)
                return this
            }

            fun build(): CcToolchainConfig {
                return CcToolchainConfig(
                    cpu,
                    compiler,
                    toolchainIdentifier,
                    hostSystemName,
                    targetSystemName,
                    abiVersion,
                    abiLibcVersion,
                    targetLibc,
                    builtinSysroot,
                    ccTargetOs,
                    features,
                    actionConfigs,
                    artifactNamePatterns,
                    toolPaths,
                    cxxBuiltinIncludeDirectories,
                    makeVariables,
                    toolchainExecConstraints,
                    toolchainTargetConstraints
                )
            }
        }

        fun getToolchainExecConstraints(): String? {
            return formatConstraints("exec", toolchainExecConstraints)
        }

        fun getToolchainTargetConstraints(): String? {
            var constraints: ImmutableList<String?> = this.toolchainTargetConstraints
            if (constraints.isEmpty()) {
                if (this.targetCpu == "k8") {
                    // Use default constraints
                    constraints =
                        ImmutableList.of<String?>(
                            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64",
                            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux"
                        )
                } else if (this.targetCpu == "darwin_x86_64") {
                    constraints =
                        ImmutableList.of<String?>(
                            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64",
                            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:macos"
                        )
                } else if (this.targetCpu == "darwin_arm64") {
                    constraints =
                        ImmutableList.of<String?>(
                            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:arm64",
                            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:macos"
                        )
                }
            }
            return formatConstraints("target", constraints)
        }

        fun hasStaticLinkCppRuntimesFeature(): Boolean {
            return features.contains(CppRuleClasses.STATIC_LINK_CPP_RUNTIMES)
        }

        val ccToolchainConfigRule: String
            get() {
                val featuresList =
                    features.stream()
                        .map<String?> { feature: String? -> "'" + feature + "'" }
                        .collect(ImmutableList.toImmutableList<String?>())
                val actionConfigsList =
                    actionConfigs.stream()
                        .map<String?> { config: String? -> "'" + config + "'" }
                        .collect(ImmutableList.toImmutableList<String?>())
                val patternsList =
                    artifactNamePatterns.stream()
                        .map<String?> { pattern: ImmutableList<String?>? ->
                            String.format(
                                "'%s': ['%s', '%s']", pattern!!.get(0), pattern.get(1), pattern.get(2)
                            )
                        }
                        .collect(ImmutableList.toImmutableList<String?>())
                val toolPathsList: ImmutableList<String?> =
                    toolPaths.stream()
                        .map<Any?> { toolPath: Pair<kotlin.String?, kotlin.String?>? ->
                            java.lang.String.format(
                                "'%s': '%s'",
                                toolPath.first,
                                toolPath.second
                            )
                        }
                        .collect(ImmutableList.toImmutableList<Any?>())
                val directoriesList =
                    cxxBuiltinIncludeDirectories.stream()
                        .map<String?> { directory: String? -> "'" + directory + "'" }
                        .collect(ImmutableList.toImmutableList<String?>())
                val makeVariablesList: ImmutableList<String?> =
                    makeVariables.stream()
                        .map<Any?> { variable: Pair<kotlin.String?, kotlin.String?>? ->
                            java.lang.String.format(
                                "'%s': '%s'",
                                variable.first,
                                variable.second
                            )
                        }
                        .collect(ImmutableList.toImmutableList<Any?>())

                return Joiner.on("\n")
                    .join(
                        "cc_toolchain_config(",
                        "  name = '" + this.targetCpu + "-" + compiler + "_config',",
                        "  toolchain_identifier = '" + toolchainIdentifier + "',",
                        "  cpu = '" + this.targetCpu + "',",
                        "  compiler = '" + compiler + "',",
                        "  host_system_name = '" + hostSystemName + "',",
                        "  target_system_name = '" + targetSystemName + "',",
                        "  target_libc = '" + targetLibc + "',",
                        "  abi_version = '" + abiVersion + "',",
                        "  abi_libc_version = '" + abiLibcVersion + "',",
                        String.format(
                            "  feature_names = [%s],",
                            Joiner.on(",\n    ").join(featuresList)
                        ),
                        String.format(
                            "  action_configs = [%s],",
                            Joiner.on(",\n    ").join(actionConfigsList)
                        ),
                        String.format(
                            "  artifact_name_patterns = {%s},",
                            Joiner.on(",\n    ").join(patternsList)
                        ),
                        String.format(
                            "  tool_paths = {%s},",
                            Joiner.on(",\n    ").join(toolPathsList)
                        ),
                        "  builtin_sysroot = '" + builtinSysroot + "',",
                        "  cc_target_os = '" + ccTargetOs + "',",
                        String.format(
                            "  cxx_builtin_include_directories = [%s],",
                            Joiner.on(",\n    ").join(directoriesList)
                        ),
                        String.format(
                            "  make_variables = {%s},",
                            Joiner.on(",\n    ").join(makeVariablesList)
                        ),
                        "  )"
                    )
            }

        companion object {
            @kotlin.jvm.JvmStatic
            fun builder(): Builder {
                return Builder()
            }

            private fun formatConstraints(type: String?, constraints: ImmutableList<String?>): String? {
                if (constraints.isEmpty()) {
                    return ""
                }

                val output: String? =
                    constraints.stream()
                        .map<String?> { constraint: String? -> String.format("'%s',", constraint) }
                        .collect(Collectors.joining("\n"))

                return String.format("%s_compatible_with = [\n%s\n],", type, output)
            }

            fun getCcToolchainConfigForCpu(cpu: String?): CcToolchainConfig {
                return CcToolchainConfig( /* cpu= */
                    cpu,  /* compiler= */
                    "mock-compiler-for-" + cpu,  /* toolchainIdentifier= */
                    "mock-llvm-toolchain-for-" + cpu,  /* hostSystemName= */
                    "mock-system-name-for-" + cpu,  /* targetSystemName= */
                    "mock-target-system-name-for-" + cpu,  /* abiVersion= */
                    "mock-abi-version-for-" + cpu,  /* abiLibcVersion= */
                    "mock-abi-libc-for-" + cpu,  /* targetLibc= */
                    "mock-libc-for-" + cpu,  /* builtinSysroot= */
                    "",  /* ccTargetOs= */
                    "",  /* features= */
                    ImmutableList.of<String?>(),  /* actionConfigs= */
                    ImmutableList.of<String?>(),  /* artifactNamePatterns= */
                    ImmutableList.of<ImmutableList<String?>?>(),  /* toolPaths= */
                    ImmutableList.of<Pair<String?, String?>?>(),  /* cxxBuiltinIncludeDirectories= */
                    ImmutableList.of<String?>(),  /* makeVariables= */
                    ImmutableList.of<Pair<String?, String?>?>(),  /* toolchainExecConstraints= */
                    ImmutableList.of<String?>(),  /* toolchainTargetConstraints= */
                    ImmutableList.of<String?>()
                )
            }

            val defaultCcToolchainConfig: CcToolchainConfig
                get() = getCcToolchainConfigForCpu("k8")
        }
    }

    private val crosstoolTopLabel: Label
    private var ccToolchainConfigFileContents: String? = null
    private val archs: MutableList<String?>
    private var supportsHeaderParsing = false
    private var ccToolchainConfigList: ImmutableList<CcToolchainConfig> = ImmutableList.of<CcToolchainConfig?>()

    init {
        this.crosstoolTopLabel = crosstoolTopLabel
        this.archs = ArrayList<String?>()
    }

    @CanIgnoreReturnValue
    fun setCcToolchainFile(ccToolchainConfigFileContents: String?): Crosstool {
        this.ccToolchainConfigFileContents = ccToolchainConfigFileContents
        return this
    }

    @CanIgnoreReturnValue
    fun setSupportedArchs(archs: ImmutableList<String?>?): Crosstool {
        this.archs.clear()
        this.archs.addAll(archs!!)
        return this
    }

    @CanIgnoreReturnValue
    fun setSupportsHeaderParsing(supportsHeaderParsing: Boolean): Crosstool {
        this.supportsHeaderParsing = supportsHeaderParsing
        return this
    }

    @CanIgnoreReturnValue
    fun setToolchainConfigs(ccToolchainConfigs: ImmutableList<CcToolchainConfig>): Crosstool {
        this.ccToolchainConfigList = ccToolchainConfigs
        return this
    }

    @Throws(IOException::class)
    fun write() {
        val runtimes: MutableSet<String?> = HashSet<String?>()
        val compilationTools = StringBuilder()
        for (compilationTool in CROSSTOOL_BINARIES) {
            val archTargets: MutableCollection<String?> = ArrayList<String?>()
            for (arch in archs) {
                archTargets.add(compilationTool + '-' + arch)
            }

            compilationTools.append(
                String.format(
                    "filegroup(name = '%s', srcs = ['%s'])\n",
                    compilationTool,
                    Joiner.on("', '").join(archTargets)
                )
            )
            for (archTarget in archTargets) {
                compilationTools.append(
                    String.format("filegroup(name = '%s', srcs = [':everything-multilib'])\n", archTarget)
                )
            }
        }

        for (toolchain in ccToolchainConfigList) {
            val staticRuntimeLabel =
                if (toolchain.hasStaticLinkCppRuntimesFeature())
                    "mock-static-runtimes-target-for-" + toolchain.toolchainIdentifier
                else
                    null
            val dynamicRuntimeLabel =
                if (toolchain.hasStaticLinkCppRuntimesFeature())
                    "mock-dynamic-runtimes-target-for-" + toolchain.toolchainIdentifier
                else
                    null
            if (staticRuntimeLabel != null) {
                runtimes.add(
                    Joiner.on('\n')
                        .join(
                            "filegroup(",
                            "  name = '" + staticRuntimeLabel + "',",
                            "  licenses = ['unencumbered'],",
                            "  srcs = ['libstatic-runtime-lib-source.a'])",
                            ""
                        )
                )
            }
            if (dynamicRuntimeLabel != null) {
                runtimes.add(
                    Joiner.on('\n')
                        .join(
                            "filegroup(",
                            "  name = '" + dynamicRuntimeLabel + "',",
                            "  licenses = ['unencumbered'],",
                            "  srcs = ['libdynamic-runtime-lib-source.so'])",
                            ""
                        )
                )
            }

            // Generate cc_toolchain target
            val suffix = toolchain.targetCpu + "-" + toolchain.compiler
            compilationTools.append(
                Joiner.on("\n")
                    .join(
                        "toolchain(",
                        "  name = 'cc-toolchain-" + suffix + "',",
                        ("  toolchain_type = '"
                                + TestConstants.TOOLS_REPOSITORY
                                + "//tools/cpp:toolchain_type',"),
                        "  toolchain = ':cc-compiler-" + suffix + "',",
                        toolchain.getToolchainExecConstraints(),
                        toolchain.getToolchainTargetConstraints(),
                        ")",
                        toolchain.ccToolchainConfigRule,
                        "cc_toolchain(",
                        "  name = 'cc-compiler-" + suffix + "',",
                        "  toolchain_identifier = '" + toolchain.toolchainIdentifier + "',",
                        "  toolchain_config = ':" + suffix + "_config',",
                        "  output_licenses = ['unencumbered'],",
                        "  module_map = 'crosstool.cppmap',",
                        "  ar_files = 'ar-" + toolchain.targetCpu + "',",
                        "  as_files = 'as-" + toolchain.targetCpu + "',",
                        "  compiler_files = 'compile-" + toolchain.targetCpu + "',",
                        "  coverage_files = 'coverage-file',",
                        "  dwp_files = 'dwp-" + toolchain.targetCpu + "',",
                        "  linker_files = 'link-" + toolchain.targetCpu + "',",
                        "  strip_files = ':every-file',",
                        "  objcopy_files = 'objcopy-" + toolchain.targetCpu + "',",
                        "  all_files = ':every-file',",
                        "  licenses = ['unencumbered'],",
                        if (supportsHeaderParsing) "    supports_header_parsing = 1," else "",
                        if (dynamicRuntimeLabel == null)
                            ""
                        else
                            "    dynamic_runtime_lib = '" + dynamicRuntimeLabel + "',",
                        if (staticRuntimeLabel == null)
                            ""
                        else
                            "    static_runtime_lib = '" + staticRuntimeLabel + "',",
                        ")",
                        ""
                    )
            )
        }

        val build =
            Joiner.on("\n")
                .join(
                    "load('@rules_cc//cc/toolchains:cc_toolchain.bzl',"
                            + " 'cc_toolchain')",
                    "load('@rules_cc//cc/toolchains:cc_toolchain_alias.bzl',"
                            + " 'cc_toolchain_alias')",
                    "package(default_visibility=['//visibility:public'])",
                    "licenses(['restricted'])",
                    "",
                    "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
                    ("load('"
                            + TestConstants.TOOLS_REPOSITORY
                            + "//third_party/cc_rules/macros:defs.bzl', 'cc_library', 'cc_toolchain')"),
                    "toolchain_type(name = 'toolchain_type')",
                    "toolchain_type(name = 'test_runner_toolchain_type')",
                    "cc_toolchain_alias(name = 'current_cc_toolchain')",
                    "cc_toolchain_alias(",
                    "    name = 'optional_current_cc_toolchain',",
                    "    mandatory = False,",
                    ")",
                    "alias(name = 'toolchain', actual = 'everything')",
                    "filegroup(name = 'everything-multilib',",
                    "          srcs = glob(['mock_version/**/*'],",
                    "              exclude_directories = 1),",
                    "          output_licenses = ['unencumbered'])",
                    "",
                    String.format(
                        "filegroup(name = 'every-file', srcs = ['%s'])",
                        Joiner.on("', '").join(CROSSTOOL_BINARIES)
                    ),
                    "",
                    compilationTools.toString(),
                    Joiner.on("\n").join(runtimes),
                    "",
                    "filegroup(",
                    "    name = 'interface_library_builder',",
                    "    srcs = ['build_interface_so'],",
                    ")",  // We add a :link_extra_lib target in case we need it.
                    "cc_library(name = 'link_extra_lib', srcs = ['linkextra.cc'])",  // We add an empty :malloc target in case we need it.
                    "cc_library(name = 'malloc')",  // Fake targets to get us through loading/analysis.
                    "exports_files(['grep-includes', 'link_dynamic_library'])",
                    "",
                    "filegroup(",
                    "    name = 'aggregate-ddi',",
                    "    srcs = ['aggregate-ddi.sh'],",
                    ")",
                    "",
                    "filegroup(",
                    "    name = 'generate-modmap',",
                    "    srcs = ['generate-modmap.sh'],",
                    ")"
                )

        config.create(crosstoolTop + "/mock_version/x86/bin/gcc")
        config.create(crosstoolTop + "/mock_version/x86/bin/ld")
        config.overwrite(crosstoolTop + "/BUILD", build)
        config.overwrite(crosstoolTop + "/cc_toolchain_config.bzl", ccToolchainConfigFileContents)
        config.create(crosstoolTop + "/crosstool.cppmap", "module crosstool {}")
        config.append(
            "MODULE.bazel",
            java.lang.String.format(
                "register_toolchains('%s:all')",
                crosstoolTopLabel.getPackageIdentifier().getCanonicalForm()
            )
        )
        // Empty files to satisfy fake targets.
        config.create(crosstoolTop + "/grep-includes")
        config.create(crosstoolTop + "/build_interface_so")
        config.create(crosstoolTop + "/link_dynamic_library")
        config.create(crosstoolTop + "/aggregate-ddi.sh")
        config.create(crosstoolTop + "/generate-modmap.sh")
    }

    @Throws(IOException::class)
    fun writeOSX() {
        // Create special lines specifying the compiler map entry for
        // each toolchain.

        // Create the test BUILD file.

        val crosstoolBuild =
            ImmutableList.builder<String?>()
                .add(
                    "package(default_visibility=['//visibility:public'])",
                    "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
                    ("load('"
                            + TestConstants.TOOLS_REPOSITORY
                            + "//third_party/cc_rules/macros:defs.bzl', 'cc_library', 'cc_toolchain')"),
                    "exports_files(glob(['**']))",
                    "",
                    "cc_library(",
                    "    name = 'custom_malloc',",
                    ")",
                    "",
                    "filegroup(",
                    "    name = 'empty',",
                    "    srcs = [],",
                    ")",
                    "",
                    "filegroup(",
                    "    name = 'link',",
                    "    srcs = [",
                    "        'ar',",
                    "        'libempty.a',",
                    String.format("        '%s//tools/objc:libtool'", TestConstants.TOOLS_REPOSITORY),
                    "    ],",
                    ")"
                )
        for (toolchainConfig in ccToolchainConfigList) {
            val staticRuntimeLabel =
                if (toolchainConfig.hasStaticLinkCppRuntimesFeature())
                    "mock-static-runtimes-target-for-" + toolchainConfig.toolchainIdentifier
                else
                    null
            val dynamicRuntimeLabel =
                if (toolchainConfig.hasStaticLinkCppRuntimesFeature())
                    "mock-dynamic-runtimes-target-for-" + toolchainConfig.toolchainIdentifier
                else
                    null
            if (staticRuntimeLabel != null) {
                crosstoolBuild.add(
                    Joiner.on('\n')
                        .join(
                            "filegroup(",
                            "  name = '" + staticRuntimeLabel + "',",
                            "  licenses = ['unencumbered'],",
                            "  srcs = ['libstatic-runtime-lib-source.a'])",
                            ""
                        )
                )
            }
            if (dynamicRuntimeLabel != null) {
                crosstoolBuild.add(
                    Joiner.on('\n')
                        .join(
                            "filegroup(",
                            "  name = '" + dynamicRuntimeLabel + "',",
                            "  licenses = ['unencumbered'],",
                            "  srcs = ['libdynamic-runtime-lib-source.so'])",
                            ""
                        )
                )
            }

            crosstoolBuild.add(
                "cc_toolchain(",
                "    name = 'cc-compiler-" + toolchainConfig.targetCpu + "',",
                "    toolchain_identifier = '" + toolchainConfig.targetCpu + "',",
                ("    toolchain_config = ':"
                        + toolchainConfig.targetCpu
                        + "-"
                        + toolchainConfig.compiler
                        + "_config',"),
                "    all_files = ':empty',",
                "    ar_files = ':link',",
                "    as_files = ':empty',",
                "    compiler_files = ':empty',",
                "    coverage_files = 'coverage-file',",
                "    dwp_files = ':empty',",
                "    linker_files = ':link',",
                "    objcopy_files = ':empty',",
                "    strip_files = ':empty',",
                if (supportsHeaderParsing) "    supports_header_parsing = 1," else "",
                if (dynamicRuntimeLabel == null)
                    ""
                else
                    "    dynamic_runtime_lib = '" + dynamicRuntimeLabel + "',",
                if (staticRuntimeLabel == null)
                    ""
                else
                    "    static_runtime_lib = '" + staticRuntimeLabel + "',",
                ")",
                "toolchain(name = 'cc-toolchain-" + toolchainConfig.targetCpu + "',",
                toolchainConfig.getToolchainExecConstraints(),
                toolchainConfig.getToolchainTargetConstraints(),
                "    toolchain = ':cc-compiler-" + toolchainConfig.targetCpu + "',",
                "    toolchain_type = '" + TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain_type'",
                ")"
            )
            crosstoolBuild.add(toolchainConfig.ccToolchainConfigRule)
        }

        config.overwrite(
            MockObjcSupport.DEFAULT_OSX_CROSSTOOL_DIR + "/BUILD",
            Joiner.on("\n").join(crosstoolBuild.build())
        )
        config.append(
            "MODULE.bazel",
            "register_toolchains('//" + MockObjcSupport.DEFAULT_OSX_CROSSTOOL_DIR + ":all')"
        )
        config.overwrite(crosstoolTop + "/cc_toolchain_config.bzl", ccToolchainConfigFileContents)
    }

    companion object {
        private val CROSSTOOL_BINARIES: ImmutableList<String?> =
            ImmutableList.of<String?>("ar", "as", "compile", "dwp", "link", "objcopy", "llvm-profdata")
    }
}
