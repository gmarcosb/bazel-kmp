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
package com.google.devtools.build.lib.rules.platform

import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement

/** Utility methods for setting up platform and toolchain related tests.  */
abstract class ToolchainTestCase : BuildViewTestCase() {
    var linuxPlatform: PlatformInfo? = null
    var macPlatform: PlatformInfo? = null

    var setting: ConstraintSettingInfo? = null
    var defaultedSetting: ConstraintSettingInfo? = null
    var linuxConstraint: ConstraintValueInfo? = null
    var macConstraint: ConstraintValueInfo? = null
    var defaultedConstraint: ConstraintValueInfo? = null

    var testToolchainTypeLabel: Label? = null
    var testToolchainType: ToolchainTypeRequirement? = null
    var testToolchainTypeInfo: ToolchainTypeInfo? = null

    var optionalToolchainTypeLabel: Label? = null
    var optionalToolchainType: ToolchainTypeRequirement? = null
    var optionalToolchainTypeInfo: ToolchainTypeInfo? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createConstraints() {
        scratch.file(
            "constraints/BUILD",
            """
        constraint_setting(name = "os")

        constraint_value(
            name = "linux",
            constraint_setting = ":os",
        )

        constraint_value(
            name = "mac",
            constraint_setting = ":os",
        )

        constraint_setting(
            name = "setting_with_default",
            default_constraint_value = ":default_value",
        )

        constraint_value(
            name = "default_value",
            constraint_setting = ":setting_with_default",
        )

        constraint_value(
            name = "non_default_value",
            constraint_setting = ":setting_with_default",
        )
        
        """.trimIndent()
        )

        scratch.file(
            "platforms/BUILD",
            """
        platform(
            name = "linux",
            constraint_values = [
                "//constraints:linux",
                "//constraints:non_default_value",
            ],
        )

        platform(
            name = "mac",
            constraint_values = [
                "//constraints:mac",
                "//constraints:non_default_value",
            ],
        )
        
        """.trimIndent()
        )

        setting = ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraints:os"))
        linuxConstraint =
            ConstraintValueInfo.create(setting, Label.parseCanonicalUnchecked("//constraints:linux"))
        macConstraint =
            ConstraintValueInfo.create(setting, Label.parseCanonicalUnchecked("//constraints:mac"))
        defaultedSetting =
            ConstraintSettingInfo.create(
                Label.parseCanonicalUnchecked("//constraints:setting_with_default")
            )
        defaultedConstraint =
            ConstraintValueInfo.create(
                defaultedSetting, Label.parseCanonicalUnchecked("//constraints:non_default_value")
            )

        linuxPlatform =
            PlatformInfo.builder()
                .setLabel(Label.parseCanonicalUnchecked("//platforms:linux"))
                .addConstraint(linuxConstraint)
                .addConstraint(defaultedConstraint)
                .build()
        macPlatform =
            PlatformInfo.builder()
                .setLabel(Label.parseCanonicalUnchecked("//platforms:mac"))
                .addConstraint(macConstraint)
                .addConstraint(defaultedConstraint)
                .build()
    }

    @Throws(java.lang.Exception::class)
    fun addToolchain(
        packageName: String?,
        toolchainName: String?,
        toolchainType: Label?,
        execConstraints: MutableCollection<String?>,
        targetConstraints: MutableCollection<String?>,
        data: String?
    ) {
        scratch.appendFile(
            packageName + "/BUILD",
            "load('//toolchain:toolchain_def.bzl', 'test_toolchain')",
            "toolchain(",
            "    name = '" + toolchainName + "',",
            "    toolchain_type = '" + toolchainType + "',",
            "    exec_compatible_with = [" + formatConstraints(execConstraints) + "],",
            "    target_compatible_with = [" + formatConstraints(targetConstraints) + "],",
            "    toolchain = ':" + toolchainName + "_impl')",
            "test_toolchain(",
            "  name='" + toolchainName + "_impl',",
            "  data = '" + data + "')"
        )
    }

    @Throws(java.lang.Exception::class)
    fun addToolchain(
        packageName: String?,
        toolchainName: String?,
        execConstraints: MutableCollection<String?>,
        targetConstraints: MutableCollection<String?>,
        data: String?
    ) {
        addToolchain(
            packageName,
            toolchainName,
            testToolchainTypeLabel,
            execConstraints,
            targetConstraints,
            data
        )
    }

    @Throws(java.lang.Exception::class)
    fun addOptionalToolchain(
        packageName: String?,
        toolchainName: String?,
        execConstraints: MutableCollection<String?>,
        targetConstraints: MutableCollection<String?>,
        data: String?
    ) {
        addToolchain(
            packageName,
            toolchainName,
            optionalToolchainTypeLabel,
            execConstraints,
            targetConstraints,
            data
        )
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun createToolchains() {
        rewriteModuleDotBazel(
            """
        register_toolchains("//toolchain:toolchain_1", "//toolchain:toolchain_2")
        
        """.trimIndent()
        )

        scratch.file(
            "toolchain/toolchain_def.bzl",
            """
        def _impl(ctx):
            toolchain = platform_common.ToolchainInfo(
                data = ctx.attr.data,
            )
            return [toolchain]

        test_toolchain = rule(
            implementation = _impl,
            attrs = {
                "data": attr.string(),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "toolchain/BUILD",
            """
        toolchain_type(name = "test_toolchain")

        toolchain_type(name = "optional_toolchain")

        toolchain_type(name = "workspace_suffix_toolchain")
        
        """.trimIndent()
        )

        testToolchainTypeLabel = Label.parseCanonicalUnchecked("//toolchain:test_toolchain")
        testToolchainType = ToolchainTypeRequirement.create(testToolchainTypeLabel)
        testToolchainTypeInfo = ToolchainTypeInfo.create(testToolchainTypeLabel)

        optionalToolchainTypeLabel = Label.parseCanonicalUnchecked("//toolchain:optional_toolchain")
        optionalToolchainType =
            ToolchainTypeRequirement.builder(optionalToolchainTypeLabel).mandatory(false).build()
        optionalToolchainTypeInfo = ToolchainTypeInfo.create(optionalToolchainTypeLabel)

        addToolchain(
            "toolchain",
            "toolchain_1",
            com.google.common.collect.ImmutableList.of<String?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<String?>("//constraints:mac"),
            "foo"
        )
        addToolchain(
            "toolchain",
            "toolchain_2",
            com.google.common.collect.ImmutableList.of<String?>("//constraints:mac"),
            com.google.common.collect.ImmutableList.of<String?>("//constraints:linux"),
            "bar"
        )
        val suffixToolchainTypeLabel: Label? =
            Label.parseCanonicalUnchecked("//toolchain:workspace_suffix_toolchain")
        addToolchain(
            "toolchain",
            "suffix_toolchain_1",
            suffixToolchainTypeLabel,
            com.google.common.collect.ImmutableList.of<String?>(),
            com.google.common.collect.ImmutableList.of<String?>(),
            "suffix1"
        )
        addToolchain(
            "toolchain",
            "suffix_toolchain_2",
            suffixToolchainTypeLabel,
            com.google.common.collect.ImmutableList.of<String?>(),
            com.google.common.collect.ImmutableList.of<String?>(),
            "suffix2"
        )
    }

    @Throws(java.lang.InterruptedException::class)
    protected fun requestToolchainsFromSkyframe(
        toolchainsKey: SkyKey?
    ): EvaluationResult<RegisteredToolchainsValue?>? {
        try {
            getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(true)
            return SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), toolchainsKey,  /*keepGoing=*/false, reporter
            )
        } finally {
            getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(false)
        }
    }

    companion object {
        protected fun assertToolchainLabels(
            registeredToolchainsValue: RegisteredToolchainsValue
        ): IterableSubject {
            return assertToolchainLabels(registeredToolchainsValue, null)
        }

        protected fun assertToolchainLabels(
            registeredToolchainsValue: RegisteredToolchainsValue,
            packageRoot: PackageIdentifier?
        ): IterableSubject {
            assertThat(registeredToolchainsValue).isNotNull()
            val declaredToolchains: com.google.common.collect.ImmutableList<DeclaredToolchainInfo?> =
                registeredToolchainsValue.registeredToolchains()
            val labels: MutableList<Label?> = collectToolchainLabels(declaredToolchains, packageRoot)
            return Truth.assertThat(labels)
        }

        protected fun collectToolchainLabels(
            toolchains: MutableList<DeclaredToolchainInfo?>, packageRoot: PackageIdentifier?
        ): MutableList<Label?> {
            return toolchains.stream()
                .map<Any?>(DeclaredToolchainInfo::resolvedToolchainLabel)
                .filter { label: Any? -> filterLabel(packageRoot, label) }
                .collect(Collectors.toList())
        }

        protected fun filterLabel(packageRoot: PackageIdentifier?, label: Label): Boolean {
            if (packageRoot == null) {
                return true
            }

            // Make sure the label is under the packageRoot.
            if (!label.getRepository().equals(packageRoot.getRepository())) {
                return false
            }

            return label
                .getPackageIdentifier()
                .getPackageFragment()
                .startsWith(packageRoot.getPackageFragment())
        }

        private fun formatConstraints(constraints: MutableCollection<String?>): String? {
            return constraints.stream().map<String?> { c: String? -> String.format("'%s'", c) }
                .collect(Collectors.joining(", "))
        }
    }
}
