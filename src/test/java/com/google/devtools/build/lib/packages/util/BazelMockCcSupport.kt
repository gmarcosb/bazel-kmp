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

import com.google.common.base.Predicate
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.cmdline.Label
import com.google.devtools.build.lib.util.OS

/** Bazel implementation of [MockCcSupport]  */
class BazelMockCcSupport private constructor() : MockCcSupport() {
    override fun getRealFilesystemCrosstoolTopPath(): String {
        if (OS.getCurrent() == OS.LINUX) {
            return "src/test/java/com/google/devtools/build/lib/packages/util/real/linux"
        }
        throw IllegalStateException("Unsupported OS: " + OS.getCurrent())
    }

    override fun getRealFilesystemToolsToLink(crosstoolTop: String?): Array<String?> {
        return arrayOfNulls<String>(0)
    }

    override fun getRealFilesystemToolsToCopy(crosstoolTop: String?): Array<String?> {
        return arrayOf<String>(crosstoolTop + "/BUILD")
    }

    override fun getCrosstoolArchs(): ImmutableList<String?> {
        return CROSSTOOL_ARCHS
    }

    @Throws(IOException::class)
    override fun setup(config: MockToolsConfig) {
        writeMacroFile(config)
        setupRulesCc(config)
        setupCcToolchainConfig(config, toolchainConfigs)
        MockCcSupport.Companion.createParseHeadersAndLayeringCheckWhitelist(config)
        MockCcSupport.Companion.createStarlarkLooseHeadersWhitelist(config, "//...")
        config.append(
            TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/cpp/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "alias(name='host_xcodes',actual='@local_config_xcode//:host_xcodes')"
        )
        if (config.isRealFileSystem() && shouldUseRealFileSystemCrosstool()) {
            config.append(
                TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/cpp/BUILD",
                """
          toolchain_type(name = 'toolchain_type')
          cc_library(
              name = 'link_extra_lib',
              srcs = ['linkextra.cc'],
              tags = ['__DONT_DEPEND_ON_DEF_PARSER__'],
          )
          cc_library(
              name = 'malloc',
              srcs = ['malloc.cc'],
              tags = ['__DONT_DEPEND_ON_DEF_PARSER__'],
          )
          filegroup(
              name = 'aggregate-ddi',
              srcs = ['aggregate-ddi.sh'],
          )
          filegroup(
              name = 'generate-modmap',
              srcs = ['generate-modmap.sh'],
          )
          filegroup(
              name = 'interface_library_builder',
              srcs = ['interface_library_builder.sh'],
          )
          filegroup(
              name = 'link_dynamic_library',
              srcs = ['link_dynamic_library.sh'],
          )
          
          """.trimIndent()
            )
            for (s in mutableListOf<String?>(
                "linkextra.cc",
                "malloc.cc",
                "aggregate-ddi.sh",
                "generate-modmap.sh",
                "interface_library_builder.sh",
                "link_dynamic_library.sh"
            )) {
                config.create(TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/cpp/" + s)
            }
        }

        // Copies rules_cc from real @rules_cc
        config.create("third_party/bazel_rules/rules_cc/MODULE.bazel", "module(name='rules_cc')")
        val runfiles: Runfiles = Runfiles.preload().withSourceRepository("")
        val path: PathFragment = PathFragment.create(runfiles.rlocation("rules_cc/cc/defs.bzl"))
        config.copyDirectory(
            path.getParentDirectory(), "third_party/bazel_rules/rules_cc/cc", Int.Companion.MAX_VALUE, true
        )

        // avoid cc_compatibility_proxy indirection
        for (ruleName in ImmutableList.of<String?>(
            "cc_binary",
            "cc_import",
            "cc_library",
            "cc_shared_library",
            "cc_static_library",
            "cc_test",
            "objc_import",
            "objc_library"
        )) {
            config.overwrite(
                "third_party/bazel_rules/rules_cc/cc/" + ruleName + ".bzl",
                MessageFormat.format(
                    """
              load("//cc/private/rules_impl:{0}.bzl", _{0} = "{0}")
              {0} = _{0}
              
              """.trimIndent(),
                    ruleName
                )
            )
        }
        for (ruleName in ImmutableList.of<String?>("cc_toolchain", "cc_toolchain_alias")) {
            config.overwrite(
                "third_party/bazel_rules/rules_cc/cc/toolchains/" + ruleName + ".bzl",
                MessageFormat.format(
                    """
              load("//cc/private/rules_impl:{0}.bzl", _{0} = "{0}")
              {0} = _{0}
              
              """.trimIndent(),
                    ruleName
                )
            )
        }
        for (ruleName in ImmutableList.of<String?>("fdo_prefetch_hints", "fdo_profile", "propeller_optimize")) {
            config.overwrite(
                "third_party/bazel_rules/rules_cc/cc/toolchains/" + ruleName + ".bzl",
                MessageFormat.format(
                    """
              load("//cc/private/rules_impl/fdo:{0}.bzl", _{0} = "{0}")
              {0} = _{0}
              
              """.trimIndent(),
                    ruleName
                )
            )
        }
        config.overwrite(
            "third_party/bazel_rules/rules_cc/cc/common/cc_info.bzl",
            """
        load("//cc/private:cc_info.bzl", _CcInfo = "CcInfo")
        CcInfo = _CcInfo
        
        """.trimIndent()
        )
        config.overwrite(
            "third_party/bazel_rules/rules_cc/cc/common/cc_shared_library_info.bzl",
            """
        load("//cc/private:cc_shared_library_info.bzl", _CcSharedLibraryInfo = "CcSharedLibraryInfo")
        CcSharedLibraryInfo = _CcSharedLibraryInfo
        
        """.trimIndent()
        )
        config.overwrite(
            "third_party/bazel_rules/rules_cc/cc/common/debug_package_info.bzl",
            """
        load("//cc/private:debug_package_info.bzl", _DebugPackageInfo = "DebugPackageInfo")
        DebugPackageInfo = _DebugPackageInfo
        
        """.trimIndent()
        )
        config.overwrite(
            "third_party/bazel_rules/rules_cc/cc/common/cc_common.bzl",
            """
        load("//cc/private:cc_common.bzl", _cc_common = "cc_common")
        cc_common = _cc_common
        
        """.trimIndent()
        )
        config.overwrite(
            "third_party/bazel_rules/rules_cc/cc/common/objc_info.bzl",
            """
        load("//cc/private:objc_info.bzl", _ObjcInfo = "ObjcInfo")
        ObjcInfo = _ObjcInfo
        
        """.trimIndent()
        )
        config.overwrite(
            "third_party/bazel_rules/rules_cc/cc/toolchains/cc_toolchain_config_info.bzl",
            """
        load("//cc/private/toolchain_config:cc_toolchain_config_info.bzl", _CcToolchainConfigInfo = "CcToolchainConfigInfo")
        CcToolchainConfigInfo = _CcToolchainConfigInfo
        
        """.trimIndent()
        )
        config.overwrite("third_party/bazel_rules/bazel_features_mock/BUILD")
        config.overwrite("third_party/bazel_rules/rules_cc/cc/common/BUILD")
        config.overwrite("third_party/bazel_rules/rules_cc/cc/private/BUILD")
        config.overwrite("third_party/bazel_rules/rules_cc/cc/toolchains/actions/BUILD")
        config.overwrite("third_party/bazel_rules/rules_cc/cc/toolchains/args/BUILD")
        config.overwrite("third_party/bazel_rules/rules_cc/cc/toolchains/artifacts/BUILD")
        config.overwrite("third_party/bazel_rules/rules_cc/cc/toolchains/BUILD")
        config.overwrite("third_party/bazel_rules/rules_cc/cc/toolchains/features/BUILD")
        config.overwrite("third_party/bazel_rules/rules_cc/cc/toolchains/features/legacy/BUILD")
        config.overwrite("third_party/bazel_rules/rules_cc/cc/toolchains/impl/BUILD")
        config.overwrite("third_party/bazel_rules/rules_cc/cc/toolchains/variables/BUILD")
    }

    override fun getMockCrosstoolLabel(): Label {
        return Label.parseCanonicalUnchecked("@bazel_tools//tools/cpp:toolchain")
    }

    override fun getMockCrosstoolPath(): String {
        return "embedded_tools/tools/cpp/"
    }

    override fun labelNameFilter(): Predicate<String?> {
        return Predicate { label: String? -> Companion.isNotCcLabel(label!!) }
    }

    override fun shouldUseRealFileSystemCrosstool(): Boolean {
        return OS.getCurrent() == OS.LINUX
    }

    companion object {
        val INSTANCE: BazelMockCcSupport = BazelMockCcSupport()

        /** Filter to remove implicit dependencies of C/C++ rules.  */
        private fun isNotCcLabel(label: String): Boolean {
            return !label.startsWith("//tools/cpp")
        }

        private val CROSSTOOL_ARCHS: ImmutableList<String?> =
            ImmutableList.of<String?>("piii", "k8", "armeabi-v7a", "ppc", "darwin_x86_64")

        private val toolchainConfigs: ImmutableList<CcToolchainConfig>
            get() {
                val result: ImmutableList.Builder<CcToolchainConfig?> =
                    ImmutableList.builder<CcToolchainConfig?>()

                // Different from CcToolchainConfig.getDefault....
                result.add(CcToolchainConfig.Companion.builder().build())

                if (OS.getCurrent() == OS.DARWIN) {
                    result.add(CcToolchainConfig.Companion.getCcToolchainConfigForCpu("darwin_x86_64"))
                    result.add(CcToolchainConfig.Companion.getCcToolchainConfigForCpu("darwin_arm64"))
                }

                if (System.getProperty("os.arch") == "s390x") {
                    result.add(CcToolchainConfig.Companion.getCcToolchainConfigForCpu("s390x"))
                }
                return result.build()
            }
    }
}
