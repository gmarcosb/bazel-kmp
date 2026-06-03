// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Artifact

/**
 * Common test code to test that C++ linking action is populated with the correct build variables.
 */
open class LinkBuildVariablesTestCase : BuildViewTestCase() {
    private val counter: AtomicInteger = AtomicInteger(0)

    enum class LinkBuildVariables(variableName: String) {
        /** Entries in the linker search path (usually set by -L flag)  */
        LIBRARY_SEARCH_DIRECTORIES("library_search_directories"),

        /** Flags providing files to link as inputs in the linker invocation  */
        LIBRARIES_TO_LINK("libraries_to_link"),

        /** Location of linker param file created by bazel to overcome command line length limit  */
        LINKER_PARAM_FILE("linker_param_file"),

        /** execpath of the output of the linker.  */
        OUTPUT_EXECPATH("output_execpath"),

        /** "yes"|"no" depending on whether interface library should be generated.  */
        GENERATE_INTERFACE_LIBRARY("generate_interface_library"),

        /** Path to the interface library builder tool.  */
        INTERFACE_LIBRARY_BUILDER("interface_library_builder_path"),

        /** Input for the interface library ifso builder tool.  */
        INTERFACE_LIBRARY_INPUT("interface_library_input_path"),

        /** Path where to generate interface library using the ifso builder tool.  */
        INTERFACE_LIBRARY_OUTPUT("interface_library_output_path"),

        /** Linker flags coming from the --linkopt or linkopts attribute.  */
        USER_LINK_FLAGS("user_link_flags"),

        /** A build variable giving linkstamp paths.  */
        LINKSTAMP_PATHS("linkstamp_paths"),

        /** Presence of this variable indicates that PIC code should be generated.  */
        FORCE_PIC("force_pic"),

        /** Presence of this variable indicates that the debug symbols should be stripped.  */
        STRIP_DEBUG_SYMBOLS("strip_debug_symbols"),

        /** Truthy when current action is a cc_test linking action, falsey otherwise.  */
        IS_CC_TEST("is_cc_test"),

        /**
         * Presence of this variable indicates that files were compiled with fission (debug info is in
         * .dwo files instead of .o files and linker needs to know).
         */
        IS_USING_FISSION("is_using_fission");

        /** Path to the fdo instrument.  */
        val variableName: String?

        init {
            this.variableName = variableName
        }
    }

    protected fun getCppLinkAction(target: ConfiguredTarget, type: Link.LinkTargetType): SpawnAction? {
        var linkerOutput: Artifact? = null
        when (type) {
            STATIC_LIBRARY, ALWAYS_LINK_STATIC_LIBRARY -> linkerOutput =
                getBinArtifact("lib" + target.getLabel().getName() + ".a", target)

            PIC_STATIC_LIBRARY, ALWAYS_LINK_PIC_STATIC_LIBRARY -> linkerOutput =
                getBinArtifact("lib" + target.getLabel().getName() + "pic.a", target)

            NODEPS_DYNAMIC_LIBRARY -> linkerOutput = getBinArtifact("lib" + target.getLabel().getName() + ".so", target)
            DYNAMIC_LIBRARY -> linkerOutput = getBinArtifact(target.getLabel().getName(), target)
            EXECUTABLE -> linkerOutput = getExecutable(target)
            else -> throw java.lang.IllegalArgumentException(
                String.format("Cannot get SpawnAction for link type %s", type)
            )
        }
        return getGeneratingAction(linkerOutput) as SpawnAction
    }

    protected fun getLinkCommandLine(cppLinkAction: SpawnAction): LinkCommandLine? {
        val commandLines: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            cppLinkAction.getCommandLines().unpack()
        assertThat(commandLines).hasSize(2)
        assertThat(commandLines.get(1).commandLine).isInstanceOf(LinkCommandLine::class.java)
        return commandLines.get(1).commandLine as LinkCommandLine?
    }

    /** Returns active build variables for a link action of given type for given target.  */
    protected fun getLinkBuildVariables(
        target: ConfiguredTarget, type: Link.LinkTargetType
    ): CcToolchainVariables {
        return getLinkCommandLine(getCppLinkAction(target, type)).getBuildVariables()
    }

    /** Returns the value of a given sequence variable in context of the given Variables instance.  */
    @Throws(java.lang.Exception::class)
    protected fun getSequenceVariableValue(variables: CcToolchainVariables?, variable: String?): MutableList<String?> {
        val mockFeatureConfiguration: FeatureConfiguration =
            buildFeatures(
                "features = [feature(",
                "  name = 'a',",
                "  flag_sets = [flag_set(",
                "    actions = ['foo'],",
                "    flag_groups = [flag_group(",
                "      iterate_over = '" + variable + "',",
                "      flags = ['%{" + variable + "}'],",
                "    )],",
                "  )],",
                ")]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))
        return mockFeatureConfiguration.getCommandLine("foo", variables)
    }

    /** Returns the value of a given string variable in context of the given Variables instance.  */
    @Throws(java.lang.Exception::class)
    protected fun getVariableValue(variables: CcToolchainVariables?, variable: String?): String? {
        val mockFeatureConfiguration: FeatureConfiguration =
            buildFeatures(
                "features = [feature(",
                "  name = 'a',",
                "  flag_sets = [flag_set(",
                "    actions = ['foo'],",
                "    flag_groups = [flag_group(",
                "      flags = ['%{" + variable + "}'],",
                "    )],",
                "  )],",
                ")]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))
        return com.google.common.collect.Iterables.getOnlyElement<T?>(
            mockFeatureConfiguration.getCommandLine(
                "foo",
                variables
            )
        )
    }

    @Throws(java.lang.Exception::class)
    private fun loadCcToolchainConfigLib() {
        scratch.appendFile("tools/cpp/BUILD", "")
        scratch.overwriteFile(
            "tools/cpp/cc_toolchain_config_lib.bzl",
            com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources(
                TestConstants.RULES_CC_REPOSITORY_EXECROOT + "cc/cc_toolchain_config_lib.bzl"
            )
        )
    }

    @Throws(java.lang.Exception::class)
    private fun buildFeatures(vararg content: String?): CcToolchainFeatures? {
        loadCcToolchainConfigLib()
        val packageName = "crosstool" + counter.incrementAndGet()
        scratch.overwriteFile(
            packageName + "/crosstool.bzl",
            "load(",
            "    '//tools/cpp:cc_toolchain_config_lib.bzl',",
            "    'feature',",
            "    'flag_group',",
            "    'flag_set',",
            ")",
            "load('@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl',"
                    + " 'CcToolchainConfigInfo')",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _impl(ctx):",
            "    return cc_common.create_cc_toolchain_config_info(",
            "        ctx = ctx,",
            java.lang.String.join("\n", *content) + ",",
            "        toolchain_identifier = 'toolchain',",
            "        host_system_name = 'host',",
            "        target_system_name = 'target',",
            "        target_cpu = 'cpu',",
            "        target_libc = 'libc',",
            "        compiler = 'compiler',",
            "    )",
            "",
            "cc_toolchain_config_rule = rule(implementation = _impl, provides ="
                    + " [CcToolchainConfigInfo])"
        )
        scratch.overwriteFile("bazel_internal/test_rules/cc/BUILD")
        scratch.overwriteFile(
            "bazel_internal/test_rules/cc/ctf_rule.bzl",
            """
        load('@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl', 'CcToolchainConfigInfo')
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        MyInfo = provider()
        def _impl(ctx):
          return [MyInfo(f = cc_common.cc_toolchain_features(
                    toolchain_config_info = ctx.attr.config[CcToolchainConfigInfo],
                    tools_directory = "crosstool",
                  ))]
        cc_toolchain_features = rule(_impl, attrs = {"config":attr.label()})
        
        """.trimIndent()
        )
        scratch.overwriteFile(
            packageName + "/BUILD",
            "load(':crosstool.bzl', 'cc_toolchain_config_rule')",
            "load('//bazel_internal/test_rules/cc:ctf_rule.bzl', 'cc_toolchain_features')",
            "cc_toolchain_features(name = 'f', config = ':r')",
            "cc_toolchain_config_rule(name = 'r')"
        )

        val target: ConfiguredTarget = getConfiguredTarget("//" + packageName + ":f")
        assertThat(target).isNotNull()
        return getStarlarkProvider(target, "MyInfo").getValue("f") as CcToolchainFeatures?
    }

    companion object {
        /** Name of the build variable for the sysroot path variable name.  */
        const val SYSROOT_VARIABLE_NAME: String = "sysroot"
    }
}
