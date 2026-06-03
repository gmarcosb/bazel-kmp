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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.InputMetadataProvider

/** Tests for toolchain features.  */
@RunWith(JUnit4::class)
class CcToolchainFeaturesTest : BuildViewTestCase() {
    private val starlarkConfigCounter: AtomicInteger = AtomicInteger(0)

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
    private fun buildFeatures(vararg content: String?): CcToolchainFeatures {
        loadCcToolchainConfigLib()
        val packageName = "crosstool" + starlarkConfigCounter.getAndIncrement()
        scratch.overwriteFile(
            packageName + "/crosstool.bzl",
            "load(",
            "    '//tools/cpp:cc_toolchain_config_lib.bzl',",
            "    'action_config',",
            "    'artifact_name_pattern',",
            "    'env_entry',",
            "    'env_set',",
            "    'feature',",
            "    'feature_set',",
            "    'flag_group',",
            "    'flag_set',",
            "    'tool',",
            "    'variable_with_value',",
            "    'with_feature_set',",
            ")",
            "load('@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl',"
                    + " 'CcToolchainConfigInfo')",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "",
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
        return getStarlarkProvider(target, "MyInfo").getValue("f") as CcToolchainFeatures
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatureConfigurationCodec() {
        val emptyConfiguration: FeatureConfiguration? =
            FeatureConfiguration.intern(
                buildFeatures("features=[feature(name = 'no_legacy_features')]")
                    .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>())
            )
        val emptyFeatures: FeatureConfiguration? =
            buildFeatures(
                "features=[feature(name = 'no_legacy_features'),feature(name='a'),"
                        + " feature(name='b')]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "b"))
        val featuresWithFlags: FeatureConfiguration? =
            buildFeatures(
                "features = [",
                "    feature(name = 'no_legacy_features'),",
                "    feature(",
                "        name = 'a',",
                "        flag_sets = [",
                "            flag_set(",
                "                actions = ['action-a'],",
                "                flag_groups = [flag_group(flags = ['flag-a'])],",
                "            ),",
                "            flag_set(",
                "                actions = ['action-b'],",
                "                flag_groups = [flag_group(flags = ['flag-b'])],",
                "            ),",
                "        ],",
                "    ),",
                "    feature(",
                "        name = 'b',",
                "        flag_sets = [",
                "            flag_set(",
                "                actions = ['action-c'],",
                "                flag_groups = [flag_group(flags = ['flag-c'])],",
                "            ),",
                "        ],",
                "    ),",
                "]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "b"))
        val featureWithEnvSet: FeatureConfiguration? =
            buildFeatures(
                "features = [",
                "    feature(name = 'no_legacy_features'),",
                "    feature(",
                "        name = 'a',",
                "        env_sets = [",
                "            env_set(",
                "                actions = ['action-a'],",
                "                env_entries = [",
                "                    env_entry(key = 'foo', value = 'bar'),",
                "                    env_entry(key = 'baz', value = 'zee'),",
                "                ],",
                "            ),",
                "        ],",
                "    ),",
                "]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))

        SerializationTester(emptyConfiguration, emptyFeatures, featuresWithFlags, featureWithEnvSet)
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnconditionalFeature() {
        assertThat(
            buildFeatures("features = []")
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))
                .isEnabled("a")
        )
            .isFalse()
        assertThat(
            buildFeatures("features = [feature(name = 'a')]")
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("b"))
                .isEnabled("a")
        )
            .isFalse()
        assertThat(
            buildFeatures("features = [feature(name = 'a')]")
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))
                .isEnabled("a")
        )
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnsupportedAction() {
        val configuration: FeatureConfiguration =
            buildFeatures("features = []").getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>())
        assertThat(configuration.getCommandLine("invalid-action", createVariables())).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagOrderEqualsSpecOrder() {
        val configuration: FeatureConfiguration =
            buildFeatures(
                "features = [",
                "    feature(",
                "        name = 'a',",
                "        flag_sets = [",
                "            flag_set(",
                "                actions = ['c++-compile'],",
                "                flag_groups = [flag_group(flags = ['-a-c++-compile'])],",
                "            ),",
                "            flag_set(",
                "                actions = ['link'],",
                "                flag_groups = [flag_group(flags = ['-a-c++-compile'])],",
                "            ),",
                "        ],",
                "    ),",
                "    feature(",
                "        name = 'b',",
                "        flag_sets = [",
                "            flag_set(",
                "                actions = ['c++-compile'],",
                "                flag_groups = [flag_group(flags = ['-b-c++-compile'])],",
                "            ),",
                "            flag_set(",
                "                actions = ['link'],",
                "                flag_groups = [flag_group(flags = ['-b-link'])],",
                "            ),",
                "        ],",
                "    ),",
                "]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "b"))
        val commandLine: MutableList<String?>? =
            configuration.getCommandLine(CppActionNames.CPP_COMPILE, createVariables())
        Truth.assertThat(commandLine).containsExactly("-a-c++-compile", "-b-c++-compile").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvVars() {
        val configuration: FeatureConfiguration =
            buildFeatures(
                "features = [",
                "    feature(",
                "        name = 'a',",
                "        env_sets = [",
                "            env_set(",
                "                actions = ['c++-compile'],",
                "                env_entries = [",
                "                    env_entry(key = 'foo', value = 'bar'),",
                "                    env_entry(key = 'cat', value = 'meow'),",
                "                ],",
                "            ),",
                "        ],",
                "        flag_sets = [",
                "            flag_set(",
                "                actions = ['c++-compile'],",
                "                flag_groups = [flag_group(flags = ['-a-c++-compile'])],",
                "            ),",
                "        ],",
                "    ),",
                "    feature(",
                "        name = 'b',",
                "        env_sets = [",
                "            env_set(",
                "                actions = ['c++-compile'],",
                "                env_entries = [env_entry(key = 'dog', value = 'woof')],",
                "            ),",
                "            env_set(",
                "                actions = ['c++-compile'],",
                "                with_features = [with_feature_set(features = ['d'])],",
                "                env_entries = [env_entry(key = 'withFeature', value = 'value1')],",
                "            ),",
                "            env_set(",
                "                actions = ['c++-compile'],",
                "                with_features = [with_feature_set(features = ['e'])],",
                "                env_entries = [env_entry(key = 'withoutFeature', value ="
                        + " 'value2')],",
                "            ),",
                "            env_set(",
                "                actions = ['c++-compile'],",
                "                with_features = [with_feature_set(not_features = ['f'])],",
                "                env_entries = [env_entry(key = 'withNotFeature', value ="
                        + " 'value3')],",
                "            ),",
                "            env_set(",
                "                actions = ['c++-compile'],",
                "                with_features = [with_feature_set(not_features = ['g'])],",
                "                env_entries = [env_entry(key = 'withoutNotFeature', value ="
                        + " 'value4')],",
                "            ),",
                "        ],",
                "    ),",
                "    feature(",
                "        name = 'c',",
                "        env_sets = [",
                "            env_set(",
                "                actions = ['c++-compile'],",
                "                env_entries = [env_entry(key = 'doNotInclude', value ="
                        + " 'doNotIncludePlease')],",
                "            ),",
                "        ],",
                "    ),",
                "    feature(name = 'd'),",
                "    feature(name = 'e'),",
                "    feature(name = 'f'),",
                "    feature(name = 'g'),",
                "]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "b", "d", "f"))
        val env: com.google.common.collect.ImmutableMap<String?, String?>? =
            configuration.getEnvironmentVariables(
                CppActionNames.CPP_COMPILE, createVariables(), PathMapper.NOOP
            )
        Truth.assertThat(env)
            .containsExactly(
                "foo",
                "bar",
                "cat",
                "meow",
                "dog",
                "woof",
                "withFeature",
                "value1",
                "withoutNotFeature",
                "value4"
            )
            .inOrder()
        Truth.assertThat(env).doesNotContainEntry("withoutFeature", "value2")
        Truth.assertThat(env).doesNotContainEntry("withNotFeature", "value3")
        Truth.assertThat(env).doesNotContainEntry("doNotInclude", "doNotIncludePlease")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvVarsWithMissingVariableIsNotExpanded() {
        val configuration: FeatureConfiguration =
            buildFeatures(
                "features = [",
                "    feature(",
                "        name = 'a',",
                "        env_sets = [",
                "            env_set(",
                "                actions = ['c++-compile'],",
                "                env_entries = [",
                "                    env_entry(key = 'foo', value = 'bar', expand_if_available"
                        + " = 'v')",
                "                ],",
                "            ),",
                "        ],",
                "    ),",
                "]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))

        val env: com.google.common.collect.ImmutableMap<String?, String?>? =
            configuration.getEnvironmentVariables(
                CppActionNames.CPP_COMPILE, createVariables(), PathMapper.NOOP
            )

        Truth.assertThat(env).doesNotContainEntry("foo", "bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvVarsWithAllVariablesPresentAreExpanded() {
        val configuration: FeatureConfiguration =
            buildFeatures(
                "features = [",
                "    feature(",
                "        name = 'a',",
                "        env_sets = [",
                "            env_set(",
                "                actions = ['c++-compile'],",
                "                env_entries = [",
                "                    env_entry(key = 'foo', value = 'bar', expand_if_available"
                        + " = 'v')",
                "                ],",
                "            ),",
                "        ],",
                "    ),",
                "]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))

        val env: com.google.common.collect.ImmutableMap<String?, String?>? =
            configuration.getEnvironmentVariables(
                CppActionNames.CPP_COMPILE, createVariables("v", "1"), PathMapper.NOOP
            )

        Truth.assertThat(env).containsExactly("foo", "bar").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvVarsWithAllVariablesPresentAreExpandedWithVariableExpansion() {
        val configuration: FeatureConfiguration =
            buildFeatures(
                "features = [",
                "    feature(",
                "        name = 'a',",
                "        env_sets = [",
                "            env_set(",
                "                actions = ['c++-compile'],",
                "                env_entries = [",
                "                    env_entry(key = 'foo', value = '%{v}', expand_if_available"
                        + " = 'v')",
                "                ],",
                "            ),",
                "        ],",
                "    ),",
                "]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))

        val env: com.google.common.collect.ImmutableMap<String?, String?>? =
            configuration.getEnvironmentVariables(
                CppActionNames.CPP_COMPILE, createVariables("v", "1"), PathMapper.NOOP
            )

        Truth.assertThat(env).containsExactly("foo", "1").inOrder()
    }

    @Throws(java.lang.Exception::class)
    private fun getExpansionOfFlag(value: String?): String? {
        return getExpansionOfFlag(value, createVariables())
    }

    @Throws(java.lang.Exception::class)
    private fun getExpansionOfFlag(value: String?, variables: CcToolchainVariables?): String? {
        return getExpansionOfFlag(value, variables, PathMapper.NOOP)
    }

    @Throws(java.lang.Exception::class)
    private fun getExpansionOfFlag(
        value: String?, variables: CcToolchainVariables?, pathMapper: PathMapper?
    ): String? {
        return getCommandLineForFlag(value, variables, pathMapper).getFirst()
    }

    @Throws(java.lang.Exception::class)
    private fun getCommandLineForFlagGroups(groups: String?, variables: CcToolchainVariables?): MutableList<String?> {
        return getCommandLineForFlagGroups(groups, variables, PathMapper.NOOP)
    }

    @Throws(java.lang.Exception::class)
    private fun getCommandLineForFlagGroups(
        groups: String?, variables: CcToolchainVariables?, pathMapper: PathMapper?
    ): MutableList<String?> {
        val configuration: FeatureConfiguration =
            buildFeatures(
                "features = [",
                "   feature(name = 'no_legacy_features'),",
                "   feature(",
                "    name = 'a',",
                "    flag_sets = [",
                "        flag_set(",
                "            actions = ['c++-compile'],",
                "            flag_groups = [" + groups + "],",
                "        ),",
                "    ],",
                ")]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))
        return configuration.getCommandLine(
            CppActionNames.CPP_COMPILE, variables,  /* inputMetadataProvider= */null, pathMapper
        )
    }

    @Throws(java.lang.Exception::class)
    private fun getCommandLineForFlag(
        value: String?, variables: CcToolchainVariables?, pathMapper: PathMapper?
    ): MutableList<String?> {
        return getCommandLineForFlagGroups(
            "flag_group(flags = ['" + value + "'])", variables, pathMapper
        )
    }

    private fun getFlagParsingError(value: String?): String? {
        return org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getExpansionOfFlag(value) }).message
    }

    private fun getFlagExpansionError(value: String?, variables: CcToolchainVariables?): String {
        return org.junit.Assert.assertThrows<T?>(
            ExpansionException::class.java,
            org.junit.function.ThrowingRunnable { getExpansionOfFlag(value, variables) })
            .getMessage()
    }

    private fun getFlagGroupsExpansionError(flagGroups: String?, variables: CcToolchainVariables?): String {
        return org.junit.Assert.assertThrows<T?>(
            ExpansionException::class.java,
            org.junit.function.ThrowingRunnable { getCommandLineForFlagGroups(flagGroups, variables) })
            .getMessage()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVariableExpansion() {
        Truth.assertThat(getExpansionOfFlag("%%")).isEqualTo("%")
        Truth.assertThat(getExpansionOfFlag("%% a %% b %%")).isEqualTo("% a % b %")
        Truth.assertThat(getExpansionOfFlag("%%{var}")).isEqualTo("%{var}")
        Truth.assertThat(getExpansionOfFlag("%{v}", createVariables("v", "<flag>"))).isEqualTo("<flag>")
        Truth.assertThat(getExpansionOfFlag(" %{v1} %{v2} ", createVariables("v1", "1", "v2", "2")))
            .isEqualTo(" 1 2 ")
        Truth.assertThat(getFlagParsingError("%"))
            .contains("expected '{' at position 1 while parsing a flag containing '%'")
        Truth.assertThat(getFlagParsingError("% "))
            .contains("expected '{' at position 1 while parsing a flag containing '% '")
        Truth.assertThat(getFlagParsingError("%{")).contains("expected variable name")
        Truth.assertThat(getFlagParsingError("%{}")).contains("expected variable name")
        Truth.assertThat(
            getCommandLineForFlagGroups(
                "flag_group(iterate_over = 'v', flags = ['%{v}'])",
                CcToolchainVariables.builder()
                    .addStringSequenceVariable("v", com.google.common.collect.ImmutableList.of<E?>())
                    .build()
            )
        )
            .isEmpty()
        Truth.assertThat(getFlagExpansionError("%{v}", createVariables()))
            .contains("Invalid toolchain configuration: Cannot find variable named 'v'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPathExpansion() {
        val pathMapper: PathMapper =
            PathMapper { path: PathFragment? ->
                if (path.startsWith(PathFragment.create("bazel-out")))
                    path.subFragment(0, 1).getRelative("cfg").getRelative(path.subFragment(2))
                else
                    path
            }
        Truth.assertThat(getExpansionOfFlag("%{path:my/source.c}", CcToolchainVariables.empty(), pathMapper))
            .isEqualTo("my/source.c")
        Truth.assertThat(
            getExpansionOfFlag(
                "%{path:bazel-out/foobar/bin/my/artifact.a}",
                CcToolchainVariables.empty(), pathMapper
            )
        )
            .isEqualTo("bazel-out/cfg/bin/my/artifact.a")

        reporter.removeHandler(failFastHandler)
        org.junit.Assert.assertThrows<Throwable?>(
            Throwable::class.java,
            org.junit.function.ThrowingRunnable { getExpansionOfFlag("%{path:/absolute/path}") })
        assertContainsEvent(
            "Invalid toolchain configuration: expected relative Unix-style path after 'path:' at"
                    + " position 2 while parsing a flag containing '%{path:/absolute/path}"
        )

        org.junit.Assert.assertThrows<Throwable?>(
            Throwable::class.java,
            org.junit.function.ThrowingRunnable { getExpansionOfFlag("%{path:}") })
        assertContainsEvent(
            "Invalid toolchain configuration: expected path after 'path:' at position 2 while parsing a"
                    + " flag containing '%{path:}"
        )
    }

    /**
     * Single structure value. Be careful not to create sequences of single structures, as the memory
     * overhead is prohibitively big.
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    private class StructureValue(value: com.google.common.collect.ImmutableMap<String?, VariableValue?>?) :
        VariableValueAdapter {
        public override fun getFieldValue(
            variableName: String?,
            field: String?,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper?,
            throwOnMissingVariable: Boolean
        ): VariableValue? {
            return value.getOrDefault(field, null)
        }

        val isTruthy: Boolean
            get() = !value.isEmpty()
        val value: com.google.common.collect.ImmutableMap<String?, VariableValue?>?

        init {
            this.value = value
        }

        companion object {
            val variableTypeName: String = "structure"
                get() = Companion.field
        }
    }

    /** Builder for StructureValue.  */
    class StructureBuilder {
        private val fields: com.google.common.collect.ImmutableMap.Builder<String?, VariableValue?> =
            com.google.common.collect.ImmutableMap.builder<String?, VariableValue?>()

        /** Adds a field to the structure.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addField(name: String?, value: VariableValue?): StructureBuilder {
            fields.put(name, value)
            return this
        }

        /** Adds a field to the structure.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addField(name: String?, valueBuilder: StructureBuilder): StructureBuilder {
            com.google.common.base.Preconditions.checkArgument(
                valueBuilder != null,
                "Cannot use null builder to get a field value for field '%s'",
                name
            )
            fields.put(name, valueBuilder.build())
            return this
        }

        /** Adds a field to the structure.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addField(name: String?, value: String?): StructureBuilder {
            fields.put(name, StringValue(value))
            return this
        }

        /** Adds a field to the structure.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addField(name: String?, values: com.google.common.collect.ImmutableList<String?>?): StructureBuilder {
            fields.put(name, Sequence(values))
            return this
        }

        /** Returns an immutable structure.  */
        fun build(): StructureValue {
            return StructureValue(fields.buildOrThrow())
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleStructureVariableExpansion() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                "flag_group(flags = ['-A%{struct.foo}', '-B%{struct.bar}'])",
                createStructureVariables(
                    "struct",
                    StructureBuilder()
                        .addField("foo", "fooValue")
                        .addField("bar", "barValue")
                )
            )
        )
            .containsExactly("-AfooValue", "-BbarValue")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNestedStructureVariableExpansion() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                "flag_group(flags = ['-A%{struct.foo.bar}'])",
                createStructureVariables(
                    "struct",
                    StructureBuilder()
                        .addField("foo", StructureBuilder().addField("bar", "fooBarValue"))
                )
            )
        )
            .containsExactly("-AfooBarValue")
    }

    @org.junit.Test
    fun testAccessingStructureAsStringFails() {
        Truth.assertThat(
            getFlagGroupsExpansionError(
                "flag_group(flags = ['-A%{struct}'])",
                createStructureVariables(
                    "struct",
                    StructureBuilder()
                        .addField("foo", "fooValue")
                        .addField("bar", "barValue")
                )
            )
        )
            .isEqualTo(
                "Invalid toolchain configuration: Cannot expand variable 'struct': expected string, "
                        + "found structure"
            )
    }

    @org.junit.Test
    fun testAccessingStringValueAsStructureFails() {
        Truth.assertThat(
            getFlagGroupsExpansionError(
                "flag_group(flags = ['-A%{stringVar.foo}'])",
                createVariables("stringVar", "stringVarValue")
            )
        )
            .isEqualTo(
                "Invalid toolchain configuration: Cannot expand variable 'stringVar.foo': variable "
                        + "'stringVar' is string, expected structure"
            )
    }

    @org.junit.Test
    fun testAccessingSequenceAsStructureFails() {
        Truth.assertThat(
            getFlagGroupsExpansionError(
                "flag_group(flags = ['-A%{sequence.foo}'])",
                createVariables("sequence", "foo1", "sequence", "foo2")
            )
        )
            .isEqualTo(
                "Invalid toolchain configuration: Cannot expand variable 'sequence.foo': variable "
                        + "'sequence' is sequence, expected structure"
            )
    }

    @org.junit.Test
    fun testAccessingMissingStructureFieldFails() {
        Truth.assertThat(
            getFlagGroupsExpansionError(
                "flag_group(flags = ['-A%{struct.missing}'])",
                createStructureVariables(
                    "struct", StructureBuilder().addField("bar", "barValue")
                )
            )
        )
            .isEqualTo(
                "Invalid toolchain configuration: Cannot expand variable 'struct.missing': structure "
                        + "struct doesn't have a field named 'missing'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSequenceOfStructuresExpansion() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                "flag_group(iterate_over = 'structs', flags = ['-A%{structs.foo}'])",
                createStructureSequenceVariables(
                    "structs",
                    StructureBuilder().addField("foo", "foo1Value").build(),
                    StructureBuilder().addField("foo", "foo2Value").build()
                )
            )
        )
            .containsExactly("-Afoo1Value", "-Afoo2Value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructureOfSequencesExpansion() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group("
                        + "  iterate_over = 'struct.sequences',"
                        + "  flags = ['-A%{struct.sequences.foo}']"
                        + ")"),
                createStructureVariables(
                    "struct",
                    StructureBuilder()
                        .addField(
                            "sequences",
                            Sequence(
                                com.google.common.collect.ImmutableList.of<E?>(
                                    StructureBuilder().addField("foo", "foo1Value").build(),
                                    StructureBuilder()
                                        .addField("foo", "foo2Value")
                                        .build()
                                )
                            )
                        )
                )
            )
        )
            .containsExactly("-Afoo1Value", "-Afoo2Value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDottedNamesNotAlwaysMeanStructures() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group("
                        + "  iterate_over = 'struct.sequence',"
                        + "  flag_groups = [flag_group("
                        + "    iterate_over = 'other_sequence',"
                        + "    flag_groups = [flag_group("
                        + "      flags = ['-A%{struct.sequence} -B%{other_sequence}']"
                        + "    )]"
                        + "  )]"
                        + ")"),
                CcToolchainVariables.builder()
                    .addVariable(
                        "struct",
                        StructureBuilder()
                            .addField(
                                "sequence",
                                com.google.common.collect.ImmutableList.of<String?>("first", "second")
                            )
                            .build()
                    )
                    .addStringSequenceVariable(
                        "other_sequence",
                        com.google.common.collect.ImmutableList.of<E?>("foo", "bar")
                    )
                    .build()
            )
        )
            .containsExactly("-Afirst -Bfoo", "-Afirst -Bbar", "-Asecond -Bfoo", "-Asecond -Bbar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandIfAllAvailableWithStructsExpandsIfPresent() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group("
                        + "  expand_if_available = 'struct',"
                        + "  flags = ['-A%{struct.foo}', '-B%{struct.bar}']"
                        + ")"),
                createStructureVariables(
                    "struct",
                    StructureBuilder()
                        .addField("foo", "fooValue")
                        .addField("bar", "barValue")
                )
            )
        )
            .containsExactly("-AfooValue", "-BbarValue")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandIfAllAvailableWithStructsDoesntExpandIfMissing() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group("
                        + "  expand_if_available = 'nonexistent',"
                        + "  flags = ['-A%{struct.foo}','-B%{struct.bar}']"
                        + ")"),
                createStructureVariables(
                    "struct",
                    StructureBuilder()
                        .addField("foo", "fooValue")
                        .addField("bar", "barValue")
                )
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandIfAllAvailableWithStructsDoesntCrashIfMissing() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group("
                        + "  expand_if_available = 'nonexistent',"
                        + "  flags = ['-A%{nonexistent.foo}','-B%{nonexistent.bar}']"
                        + ")"),
                createVariables()
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandIfAllAvailableWithStructFieldDoesntCrashIfMissing() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group("
                        + "  expand_if_available = 'nonexistent.nonexistant_field',"
                        + "  flags = ['-A%{nonexistent.foo}','-B%{nonexistent.bar}']"
                        + ")"),
                createVariables()
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandIfAllAvailableWithStructFieldExpandsIfPresent() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group("
                        + "  expand_if_available = 'struct.foo',"
                        + "  flags = ['-A%{struct.foo}','-B%{struct.bar}']"
                        + ")"),
                createStructureVariables(
                    "struct",
                    StructureBuilder()
                        .addField("foo", "fooValue")
                        .addField("bar", "barValue")
                )
            )
        )
            .containsExactly("-AfooValue", "-BbarValue")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandIfAllAvailableWithStructFieldDoesntExpandIfMissing() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group("
                        + "  expand_if_available = 'struct.foo',"
                        + "  flags = ['-A%{struct.foo}','-B%{struct.bar}']"
                        + ")"),
                createStructureVariables(
                    "struct", StructureBuilder().addField("bar", "barValue")
                )
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandIfAllAvailableWithStructFieldScopesRight() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group(flag_groups = [flag_group("
                        + "    expand_if_available = 'struct.foo',"
                        + "    flags = ['-A%{struct.foo}']"
                        + "  ),"
                        + "  flag_group("
                        + "    flags = ['-B%{struct.bar}']"
                        + "  )]"
                        + ")"),
                createStructureVariables(
                    "struct", StructureBuilder().addField("bar", "barValue")
                )
            )
        )
            .containsExactly("-BbarValue")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandIfNoneAvailableExpandsIfNotAvailable() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group(flag_groups = [flag_group("
                        + "    expand_if_not_available = 'not_available',"
                        + "    flags = ['-foo']"
                        + "  ),"
                        + "  flag_group("
                        + "    expand_if_not_available = 'available',"
                        + "    flags = ['-bar']"
                        + "  )]"
                        + ")"),
                createVariables("available", "available")
            )
        )
            .containsExactly("-foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandIfNoneAvailableDoesntExpandIfThereIsOneOfManyAvailable() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group("
                        + "  expand_if_not_available = 'not_available',"
                        + "  flag_groups = [flag_group("
                        + "    expand_if_not_available = 'available',"
                        + "    flags = ['-foo']"
                        + "  )]"
                        + ")"),
                createVariables("available", "available")
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandIfTrueDoesntExpandIfMissing() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group("
                        + "  expand_if_true = 'missing',"
                        + "  flags = ['-A%{missing}']"
                        + "),"
                        + "flag_group("
                        + "  expand_if_false = 'missing',"
                        + "  flags = ['-B%{missing}']"
                        + ")"),
                createVariables()
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandIfTrueExpandsIfOne() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group("
                        + "  expand_if_true = 'struct.bool',"
                        + "  flags = ['-A%{struct.foo}','-B%{struct.bar}']"
                        + "),"
                        + "flag_group("
                        + "  expand_if_false = 'struct.bool',"
                        + "  flags = ['-X%{struct.foo}','-Y%{struct.bar}']"
                        + ")"),
                createStructureVariables(
                    "struct",
                    StructureBuilder()
                        .addField("bool", booleanValue(true))
                        .addField("foo", "fooValue")
                        .addField("bar", "barValue")
                )
            )
        )
            .containsExactly("-AfooValue", "-BbarValue")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandIfTrueExpandsIfZero() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group("
                        + "  expand_if_true = 'struct.bool',"
                        + "  flags = ['-A%{struct.foo}','-B%{struct.bar}']"
                        + "),"
                        + "flag_group("
                        + "  expand_if_false = 'struct.bool',"
                        + "  flags = ['-X%{struct.foo}', '-Y%{struct.bar}']"
                        + ")"),
                createStructureVariables(
                    "struct",
                    StructureBuilder()
                        .addField("bool", booleanValue(false))
                        .addField("foo", "fooValue")
                        .addField("bar", "barValue")
                )
            )
        )
            .containsExactly("-XfooValue", "-YbarValue")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandIfEqual() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group(  expand_if_equal = variable_with_value(name = 'var', value ="
                        + " 'equal_value'),  flags = ['-foo_%{var}']),flag_group(  expand_if_equal ="
                        + " variable_with_value(name = 'var', value = 'non_equal_value'),  flags ="
                        + " ['-bar_%{var}']),flag_group(  expand_if_equal = variable_with_value(name ="
                        + " 'non_existing_var', value = 'non_existing'),  flags ="
                        + " ['-baz_%{non_existing_var}'])"),
                createVariables("var", "equal_value")
            )
        )
            .containsExactly("-foo_equal_value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListVariableExpansion() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                "flag_group(iterate_over = 'v', flags = ['%{v}'])",
                createVariables("v", "1", "v", "2")
            )
        )
            .containsExactly("1", "2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListVariableExpansionMixedWithNonListVariable() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                "flag_group(iterate_over = 'v1', flags = ['%{v1} %{v2}'])",
                createVariables("v1", "a1", "v1", "a2", "v2", "b")
            )
        )
            .containsExactly("a1 b", "a2 b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNestedListVariableExpansion() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                ("flag_group("
                        + "  iterate_over = 'v1',"
                        + "  flag_groups = [flag_group("
                        + "    iterate_over = 'v2',"
                        + "    flags = ['%{v1} %{v2}'],"
                        + "  )]"
                        + ")"),
                createVariables("v1", "a1", "v1", "a2", "v2", "b1", "v2", "b2")
            )
        )
            .containsExactly("a1 b1", "a1 b2", "a2 b1", "a2 b2")
    }

    @org.junit.Test
    fun testListVariableExpansionMixedWithImplicitlyAccessedListVariableFails() {
        Truth.assertThat(
            getFlagGroupsExpansionError(
                "flag_group(iterate_over = 'v1', flags = ['%{v1} %{v2}'])",
                createVariables("v1", "a1", "v1", "a2", "v2", "b1", "v2", "b2")
            )
        )
            .contains("Cannot expand variable 'v2': expected string, found sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagGroupVariableExpansion() {
        Truth.assertThat(
            getCommandLineForFlagGroups(
                (""
                        + "flag_group(iterate_over = 'v', flags = ['-f', '%{v}']),"
                        + "flag_group(flags = ['-end'])"),
                createVariables("v", "1", "v", "2")
            )
        )
            .containsExactly("-f", "1", "-f", "2", "-end")
        Truth.assertThat(
            getCommandLineForFlagGroups(
                (""
                        + "flag_group(iterate_over = 'v', flags = ['-f', '%{v}']),"
                        + "flag_group(iterate_over = 'v', flags = ['%{v}'])"),
                createVariables("v", "1", "v", "2")
            )
        )
            .containsExactly("-f", "1", "-f", "2", "1", "2")
        Truth.assertThat(
            getCommandLineForFlagGroups(
                (""
                        + "flag_group(iterate_over = 'v', flags = ['-f', '%{v}']),"
                        + "flag_group(iterate_over = 'v', flags = ['%{v}'])"),
                createVariables("v", "1", "v", "2")
            )
        )
            .containsExactly("-f", "1", "-f", "2", "1", "2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagTreeVariableExpansion() {
        val nestedGroup =
            (""
                    + "flag_group("
                    + "  iterate_over = 'v',"
                    + "  flag_groups = ["
                    + "    flag_group(flags = ['-a']),"
                    + "    flag_group(iterate_over = 'v', flags = ['%{v}']),"
                    + "    flag_group(flags = ['-b']),"
                    + "  ],"
                    + ")")
        Truth.assertThat(getCommandLineForFlagGroups(nestedGroup, createNestedVariables("v", 1, 3)))
            .containsExactly(
                "-a", "00", "01", "02", "-b", "-a", "10", "11", "12", "-b", "-a", "20", "21", "22",
                "-b"
            )

        val e: ExpansionException? =
            org.junit.Assert.assertThrows<T?>(
                ExpansionException::class.java,
                org.junit.function.ThrowingRunnable {
                    getCommandLineForFlagGroups(
                        nestedGroup,
                        createNestedVariables("v", 2, 3)
                    )
                })
        assertThat(e).hasMessageThat().contains("'v'")

        val ae: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable {
                    buildFeatures(
                        "features = [feature(",
                        "  name =  'a',",
                        "  flag_sets = [flag_set(",
                        "    action = 'c++-compile',",
                        "    flag_groups = [flag_group(",
                        "      flag_groups = [flag_group(flags = ['-f'])],",
                        "      flags = ['-f'],",
                        "    )],",
                        "  )],",
                        ")]"
                    )
                })
        Truth.assertThat(ae)
            .hasMessageThat()
            .contains("flag_group must not contain both a flag and another flag_group.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplies() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(name = 'a', implies = ['b', 'c']),",
                "    feature(name = 'b'),",
                "    feature(name = 'c', implies = ['d']),",
                "    feature(name = 'd'),",
                "    feature(name = 'e'),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "a")).containsExactly("a", "b", "c", "d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRequires() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(name = 'a', requires = [feature_set(features = ['b'])]),",
                "    feature(name = 'b', requires = [feature_set(features = ['c'])]),",
                "    feature(name = 'c'),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "a")).isEmpty()
        Truth.assertThat(getEnabledFeatures(features, "a", "b")).isEmpty()
        Truth.assertThat(getEnabledFeatures(features, "a", "c")).containsExactly("c")
        Truth.assertThat(getEnabledFeatures(features, "a", "b", "c")).containsExactly("a", "b", "c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisabledRequirementChain() {
        var features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(name = 'a'),",
                "    feature(name = 'b', requires = [feature_set(features = ['c'])], implies ="
                        + " ['a']),",
                "    feature(name = 'c'),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "b")).isEmpty()
        features =
            buildFeatures(
                "features = [",
                "    feature(name = 'a'),",
                "    feature(name = 'b', requires = [feature_set(features = ['a'])], implies ="
                        + " ['c']),",
                "    feature(name = 'c'),",
                "    feature(name = 'd', requires = [feature_set(features = ['c'])], implies ="
                        + " ['e']),",
                "    feature(name = 'e'),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "b", "d")).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnabledRequirementChain() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(name = '0', implies = ['a']),",
                "    feature(name = 'a'),",
                "    feature(name = 'b', requires = [feature_set(features = ['a'])], implies ="
                        + " ['c']),",
                "    feature(name = 'c'),",
                "    feature(name = 'd', requires = [feature_set(features = ['c'])], implies ="
                        + " ['e']),",
                "    feature(name = 'e'),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "0", "b", "d"))
            .containsExactly("0", "a", "b", "c", "d", "e")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLogicInRequirements() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(",
                "        name = 'a',",
                "        requires = [",
                "            feature_set(features = ['b', 'c']),",
                "            feature_set(features = ['d']),",
                "        ],",
                "    ),",
                "    feature(name = 'b'),",
                "    feature(name = 'c'),",
                "    feature(name = 'd'),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "a", "b", "c")).containsExactly("a", "b", "c")
        Truth.assertThat(getEnabledFeatures(features, "a", "b")).containsExactly("b")
        Truth.assertThat(getEnabledFeatures(features, "a", "c")).containsExactly("c")
        Truth.assertThat(getEnabledFeatures(features, "a", "d")).containsExactly("a", "d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImpliesImpliesRequires() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(name = 'a', implies = ['b']),",
                "    feature(name = 'b', requires = [feature_set(features = ['c'])]),",
                "    feature(name = 'c'),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "a")).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleImplies() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(name = 'a', implies = ['b', 'c', 'd']),",
                "    feature(name = 'b'),",
                "    feature(name = 'c', requires = [feature_set(features = ['e'])]),",
                "    feature(name = 'd'),",
                "    feature(name = 'e'),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "a")).isEmpty()
        Truth.assertThat(getEnabledFeatures(features, "a", "e")).containsExactly("a", "b", "c", "d", "e")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisabledFeaturesDoNotEnableImplications() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(name = 'a', implies = ['b'], requires = [feature_set(features = ['c'])]),",
                "    feature(name = 'b'),",
                "    feature(name = 'c'),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "a")).isEmpty()
    }

    @org.junit.Test
    fun testFeatureNameCollision() {
        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable {
                    buildFeatures(
                        "features = [",
                        "    feature(name = '+++collision+++'),",
                        "    feature(name = '+++collision+++'),",
                        "]"
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("feature or action config '+++collision+++' was specified multiple times.")
    }

    @org.junit.Test
    fun testReferenceToUndefinedFeature() {
        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { buildFeatures("features = [feature(name = 'a', implies = ['<<<undefined>>>'])]") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "feature '<<<undefined>>>', which is referenced from feature 'a', is not defined"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImpliesWithCycle() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(name = 'a', implies = ['b']),",
                "    feature(name = 'b', implies = ['a']),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "a")).containsExactly("a", "b")
        Truth.assertThat(getEnabledFeatures(features, "b")).containsExactly("a", "b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleImpliesCycle() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(name = 'a', implies = ['b', 'c', 'd']),",
                "    feature(name = 'b'),",
                "    feature(name = 'c', requires = [feature_set(features = ['e'])]),",
                "    feature(name = 'd', requires = [feature_set(features = ['f'])]),",
                "    feature(name = 'e', requires = [feature_set(features = ['c'])]),",
                "    feature(name = 'f'),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "a", "e")).isEmpty()
        Truth.assertThat(getEnabledFeatures(features, "a", "e", "f"))
            .containsExactly("a", "b", "c", "d", "e", "f")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRequiresWithCycle() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(name = 'a', requires = [feature_set(features = ['b'])]),",
                "    feature(name = 'b', requires = [feature_set(features = ['a'])]),",
                "    feature(name = 'c', implies = ['a']),",
                "    feature(name = 'd', implies = ['b']),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "c")).isEmpty()
        Truth.assertThat(getEnabledFeatures(features, "d")).isEmpty()
        Truth.assertThat(getEnabledFeatures(features, "c", "d")).containsExactly("a", "b", "c", "d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImpliedByOneEnabledAndOneDisabledFeature() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(name = 'a'),",
                "    feature(name = 'b', requires = [feature_set(features = ['a'])], implies ="
                        + " ['d']),",
                "    feature(name = 'c', implies = ['d']),",
                "    feature(name = 'd'),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "b", "c")).containsExactly("c", "d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRequiresOneEnabledAndOneUnsupportedFeature() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(",
                "        name = 'a',",
                "        requires = [",
                "            feature_set(features = ['b']),",
                "            feature_set(features = ['c'])",
                "        ],",
                "    ),",
                "    feature(name = 'b'),",
                "    feature(name = 'c', requires = [feature_set(features = ['d'])]),",
                "    feature(name = 'd'),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "a", "b", "c")).containsExactly("a", "b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagGroupsWithMissingVariableIsNotExpanded() {
        val configuration: FeatureConfiguration =
            buildFeatures(
                "features = [feature(",
                "    name = 'a',",
                "    flag_sets = [",
                "        flag_set(",
                "            actions = ['c++-compile'],",
                "            flag_groups = [",
                "                flag_group(expand_if_available = 'v', flags = ['%{v}'])",
                "            ],",
                "        ),",
                "        flag_set(",
                "            actions = ['c++-compile'],",
                "            flag_groups = [flag_group(flags = ['unconditional'])],",
                "        ),",
                "    ],",
                ")]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))

        assertThat(configuration.getCommandLine(CppActionNames.CPP_COMPILE, createVariables()))
            .containsExactly("unconditional")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOnlyFlagGroupsWithAllVariablesPresentAreExpanded() {
        val configuration: FeatureConfiguration =
            buildFeatures(
                "features = [feature(",
                "    name = 'a',",
                "    flag_sets = [",
                "        flag_set(",
                "            actions = ['c++-compile'],",
                "            flag_groups = [",
                "                flag_group(expand_if_available = 'v', flags = ['%{v}'])",
                "            ],",
                "        ),",
                "        flag_set(",
                "            actions = ['c++-compile'],",
                "            flag_groups = [",
                "                flag_group(",
                "                    expand_if_available = 'w',",
                "                    flag_groups = [flag_group(",
                "                    expand_if_available = 'v',",
                "                    flags = ['%{v}%{w}'])],",
                "                )",
                "            ],",
                "        ),",
                "        flag_set(",
                "            actions = ['c++-compile'],",
                "            flag_groups = [flag_group(flags = ['unconditional'])],",
                "        ),",
                "    ],",
                ")]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))

        assertThat(configuration.getCommandLine(CppActionNames.CPP_COMPILE, createVariables("v", "1")))
            .containsExactly("1", "unconditional")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOnlyInnerFlagGroupIsIteratedWithSequenceVariable() {
        val configuration: FeatureConfiguration =
            buildFeatures(
                "features = [feature(",
                "    name = 'a',",
                "    flag_sets = [",
                "        flag_set(",
                "            actions = ['c++-compile'],",
                "            flag_groups = [",
                "                flag_group(",
                "                    expand_if_available = 'v',",
                "                    iterate_over = 'v',",
                "                    flags = ['%{v}']",
                "                ),",
                "            ],",
                "        ),",
                "        flag_set(",
                "            actions = ['c++-compile'],",
                "            flag_groups = [",
                "                flag_group(",
                "                    expand_if_available = 'w',",
                "                    flag_groups = [flag_group(",
                "                    iterate_over = 'v',",
                "                    expand_if_available = 'v',",
                "                    flags = ['%{v}%{w}'])],",
                "                ),",
                "            ],",
                "        ),",
                "        flag_set(",
                "            actions = ['c++-compile'],",
                "            flag_groups = [flag_group(flags = ['unconditional'])],",
                "        ),",
                "    ],",
                ")]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))

        assertThat(
            configuration.getCommandLine(
                CppActionNames.CPP_COMPILE, createVariables("v", "1", "v", "2")
            )
        )
            .containsExactly("1", "2", "unconditional")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagSetsAreIteratedIndividuallyForSequenceVariables() {
        val configuration: FeatureConfiguration =
            buildFeatures(
                "features = [feature(",
                "    name = 'a',",
                "    flag_sets = [",
                "        flag_set(",
                "            actions = ['c++-compile'],",
                "            flag_groups = [",
                "                flag_group(",
                "                    expand_if_available = 'v',",
                "                    iterate_over = 'v',",
                "                    flags = ['%{v}']",
                "                ),",
                "            ],",
                "        ),",
                "        flag_set(",
                "            actions = ['c++-compile'],",
                "            flag_groups = [",
                "                flag_group(",
                "                    iterate_over = 'v',",
                "                    expand_if_available = 'v',",
                "                    flags = ['%{v}%{w}']",
                "                ),",
                "            ],",
                "        ),",
                "        flag_set(",
                "            actions = ['c++-compile'],",
                "            flag_groups = [flag_group(flags = ['unconditional'])],",
                "        ),",
                "    ],",
                ")]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))

        assertThat(
            configuration.getCommandLine(
                CppActionNames.CPP_COMPILE, createVariables("v", "1", "v", "2", "w", "3")
            )
        )
            .containsExactly("1", "2", "13", "23", "unconditional")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfiguration() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(",
                "        name = 'a',",
                "        flag_sets = [",
                "            flag_set(",
                "                actions = ['c++-compile'],",
                "                flag_groups = [flag_group(flags = ['-f', '%{v}'])],",
                "            ),",
                "        ],",
                "    ),",
                "    feature(name = 'b', implies = ['a']),",
                "]"
            )
        Truth.assertThat(getEnabledFeatures(features, "b")).containsExactly("a", "b")
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("b"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables("v", "1"))
        )
            .containsExactly("-f", "1")

        val deserialized: CcToolchainFeatures = RoundTripping.roundTrip(features)
        Truth.assertThat(getEnabledFeatures(deserialized, "b")).containsExactly("a", "b")
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("b"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables("v", "1"))
        )
            .containsExactly("-f", "1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultFeatures() {
        val features: CcToolchainFeatures =
            buildFeatures(
                ("features = ["
                        + "feature(name = 'no_legacy_features'),"
                        + "feature(name = 'a'), feature(name = 'b', enabled = True)"
                        + "]")
            )
        assertThat(features.getDefaultFeaturesAndActionConfigs()).containsExactly("b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultActionConfigs() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [feature(name = 'no_legacy_features')],",
                "action_configs = [",
                "    action_config(action_name = 'a'),",
                "    action_config(action_name = 'b', enabled = True),",
                "]"
            )
        assertThat(features.getDefaultFeaturesAndActionConfigs()).containsExactly("b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWithFeature_oneSetOneFeature() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "feature(name = 'no_legacy_features'),",
                "    feature(",
                "        name = 'a',",
                "        flag_sets = [",
                "            flag_set(",
                "                with_features = [with_feature_set(features = ['b'])],",
                "                actions = ['c++-compile'],",
                "                flag_groups = [flag_group(flags = ['dummy_flag'])],",
                "            ),",
                "        ],",
                "    ),",
                "    feature(name = 'b'),",
                "]"
            )
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "b"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables())
        )
            .containsExactly("dummy_flag")
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables())
        )
            .doesNotContain("dummy_flag")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWithFeature_oneSetMultipleFeatures() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(",
                "        name = 'a',",
                "        flag_sets = [",
                "            flag_set(",
                "                with_features = [with_feature_set(features = ['b', 'c'])],",
                "                actions = ['c++-compile'],",
                "                flag_groups = [flag_group(flags = ['dummy_flag'])],",
                "            ),",
                "        ],",
                "    ),",
                "    feature(name = 'b'),",
                "    feature(name = 'c'),",
                "]"
            )
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "b", "c"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables())
        )
            .containsExactly("dummy_flag")
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "b"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables())
        )
            .doesNotContain("dummy_flag")
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables())
        )
            .doesNotContain("dummy_flag")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWithFeature_mulipleSetsMultipleFeatures() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(",
                "        name = 'a',",
                "        flag_sets = [",
                "            flag_set(",
                "                with_features = [",
                "                    with_feature_set(features = ['b1', 'c1']),",
                "                    with_feature_set(features = ['b2', 'c2']),",
                "                ],",
                "                actions = ['c++-compile'],",
                "                flag_groups = [flag_group(flags = ['dummy_flag'])],",
                "            ),",
                "        ],",
                "    ),",
                "    feature(name = 'b1'),",
                "    feature(name = 'c1'),",
                "    feature(name = 'b2'),",
                "    feature(name = 'c2'),",
                "]"
            )
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "b1", "c1", "b2", "c2"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables())
        )
            .containsExactly("dummy_flag")
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "b1", "c1"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables())
        )
            .containsExactly("dummy_flag")
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "b1", "b2"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables())
        )
            .doesNotContain("dummy_flag")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWithFeature_notFeature() {
        val features: CcToolchainFeatures =
            buildFeatures(
                "features = [",
                "    feature(",
                "        name = 'a',",
                "        flag_sets = [",
                "            flag_set(",
                "                with_features = [",
                "                    with_feature_set(not_features = ['x', 'y'], features = ['z']),",
                "                    with_feature_set(not_features = ['q']),",
                "                ],",
                "                actions = ['c++-compile'],",
                "                flag_groups = [flag_group(flags = ['dummy_flag'])],",
                "            ),",
                "        ],",
                "    ),",
                "    feature(name = 'x'),",
                "    feature(name = 'y'),",
                "    feature(name = 'z'),",
                "    feature(name = 'q'),",
                "]"
            )
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables())
        )
            .containsExactly("dummy_flag")
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "q"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables())
        )
            .doesNotContain("dummy_flag")
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "q", "z"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables())
        )
            .containsExactly("dummy_flag")
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "q", "x", "z"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables())
        )
            .doesNotContain("dummy_flag")
        assertThat(
            features
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "q", "x", "y", "z"))
                .getCommandLine(CppActionNames.CPP_COMPILE, createVariables())
        )
            .doesNotContain("dummy_flag")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActivateActionConfigFromFeature() {
        val toolchainFeatures: CcToolchainFeatures =
            buildFeatures(
                "action_configs = [",
                "    action_config(",
                "        action_name = 'action-a',",
                "        tools = [",
                "            tool(",
                "                path = 'toolchain/feature-a',",
                "                with_features = [with_feature_set(features = ['feature-a'])],",
                "            ),",
                "        ],",
                "    ),",
                "],",
                "features = [",
                "    feature(name = 'activates-action-a', implies = ['action-a']),",
                "]"
            )

        val featureConfiguration: FeatureConfiguration =
            toolchainFeatures.getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("activates-action-a"))

        assertThat(featureConfiguration.actionIsConfigured("action-a")).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatureCanRequireActionConfig() {
        val toolchainFeatures: CcToolchainFeatures =
            buildFeatures(
                "action_configs = [",
                "    action_config(",
                "        action_name = 'action-a',",
                "        tools = [",
                "            tool(",
                "                path = 'toolchain/feature-a',",
                "                with_features = [with_feature_set(features = ['feature-a'])],",
                "            ),",
                "        ],",
                "    ),",
                "],",
                "features = [",
                "    feature(",
                "        name = 'requires-action-a',",
                "        requires = [feature_set(features = ['action-a'])],",
                "    ),",
                "]"
            )

        val featureConfigurationWithoutAction: FeatureConfiguration =
            toolchainFeatures.getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("requires-action-a"))
        assertThat(featureConfigurationWithoutAction.isEnabled("requires-action-a")).isFalse()

        val featureConfigurationWithAction: FeatureConfiguration =
            toolchainFeatures.getFeatureConfiguration(
                com.google.common.collect.ImmutableSet.of<E?>(
                    "action-a",
                    "requires-action-a"
                )
            )
        assertThat(featureConfigurationWithAction.isEnabled("requires-action-a")).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleActionTool() {
        val configuration: FeatureConfiguration =
            buildFeatures(
                "action_configs = [",
                "    action_config(",
                "        action_name = 'action-a',",
                "        tools = [tool(path = 'toolchain/a')],",
                "    ),",
                "],",
                "features = [",
                "    feature(name = 'activates-action-a', implies = ['action-a']),",
                "]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("activates-action-a"))
        assertThat(configuration.getToolPathForAction("action-a")).isEqualTo("crosstool/toolchain/a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionToolFromFeatureSet() {
        val toolchainFeatures: CcToolchainFeatures =
            buildFeatures(
                "action_configs = [",
                "    action_config(",
                "        action_name = 'action-a',",
                "        tools = [",
                "            tool(",
                "                path = 'toolchain/features-a-and-b',",
                "                with_features = [with_feature_set(features = ['feature-a',"
                        + " 'feature-b'])],",
                "            ),",
                "            tool(",
                "                path = 'toolchain/feature-a-and-not-c',",
                "                with_features = [with_feature_set(features = ['feature-a'],"
                        + " not_features = ['feature-c'])],",
                "            ),",
                "            tool(",
                "                path = 'toolchain/feature-b-or-c',",
                "                with_features = [",
                "                    with_feature_set(features = ['feature-b']),",
                "                    with_feature_set(features = ['feature-c'])",
                "                ],",
                "            ),",
                "            tool(path = 'toolchain/default'),",
                "        ],",
                "    ),",
                "],",
                "features = [",
                "    feature(name = 'feature-a'),",
                "    feature(name = 'feature-b'),",
                "    feature(name = 'feature-c'),",
                "    feature(name = 'activates-action-a', implies = ['action-a']),",
                "]"
            )

        val featureAConfiguration: FeatureConfiguration =
            toolchainFeatures.getFeatureConfiguration(
                com.google.common.collect.ImmutableSet.of<E?>("feature-a", "activates-action-a")
            )
        assertThat(featureAConfiguration.getToolPathForAction("action-a"))
            .isEqualTo("crosstool/toolchain/feature-a-and-not-c")

        val featureAAndCConfiguration: FeatureConfiguration =
            toolchainFeatures.getFeatureConfiguration(
                com.google.common.collect.ImmutableSet.of<E?>("feature-a", "feature-c", "activates-action-a")
            )
        assertThat(featureAAndCConfiguration.getToolPathForAction("action-a"))
            .isEqualTo("crosstool/toolchain/feature-b-or-c")

        val featureBConfiguration: FeatureConfiguration =
            toolchainFeatures.getFeatureConfiguration(
                com.google.common.collect.ImmutableSet.of<E?>("feature-b", "activates-action-a")
            )
        assertThat(featureBConfiguration.getToolPathForAction("action-a"))
            .isEqualTo("crosstool/toolchain/feature-b-or-c")

        val featureCConfiguration: FeatureConfiguration =
            toolchainFeatures.getFeatureConfiguration(
                com.google.common.collect.ImmutableSet.of<E?>("feature-c", "activates-action-a")
            )
        assertThat(featureCConfiguration.getToolPathForAction("action-a"))
            .isEqualTo("crosstool/toolchain/feature-b-or-c")

        val featureAAndBConfiguration: FeatureConfiguration =
            toolchainFeatures.getFeatureConfiguration(
                com.google.common.collect.ImmutableSet.of<E?>("feature-a", "feature-b", "activates-action-a")
            )
        assertThat(featureAAndBConfiguration.getToolPathForAction("action-a"))
            .isEqualTo("crosstool/toolchain/features-a-and-b")

        val noFeaturesConfiguration: FeatureConfiguration =
            toolchainFeatures.getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("activates-action-a"))
        assertThat(noFeaturesConfiguration.getToolPathForAction("action-a"))
            .isEqualTo("crosstool/toolchain/default")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testErrorForNoMatchingTool() {
        val toolchainFeatures: CcToolchainFeatures =
            buildFeatures(
                "action_configs = [",
                "    action_config(",
                "        action_name = 'action-a',",
                "        tools = [",
                "            tool(",
                "                path = 'toolchain/feature-a',",
                "                with_features = [with_feature_set(features = ['feature-a'])],",
                "            ),",
                "        ],",
                "    ),",
                "],",
                "features = [",
                "    feature(name = 'feature-a'),",
                "    feature(name = 'activates-action-a', implies = ['action-a']),",
                "]"
            )

        val noFeaturesConfiguration: FeatureConfiguration =
            toolchainFeatures.getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("activates-action-a"))

        val e: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { noFeaturesConfiguration.getToolPathForAction("action-a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Matching tool for action action-a not found for given feature configuration")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActivateActionConfigDirectly() {
        val toolchainFeatures: CcToolchainFeatures =
            buildFeatures(
                "action_configs = [",
                "    action_config(",
                "        action_name = 'action-a',",
                "        tools = [",
                "            tool(",
                "                path = 'toolchain/feature-a',",
                "                with_features = [with_feature_set(features = ['feature-a'])],",
                "            ),",
                "        ],",
                "    ),",
                "]"
            )

        val featureConfiguration: FeatureConfiguration =
            toolchainFeatures.getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("action-a"))

        assertThat(featureConfiguration.actionIsConfigured("action-a")).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConfigCanActivateFeature() {
        val toolchainFeatures: CcToolchainFeatures =
            buildFeatures(
                "action_configs = [",
                "    action_config(",
                "        action_name = 'action-a',",
                "        tools = [",
                "            tool(",
                "                path = 'toolchain/feature-a',",
                "                with_features = [with_feature_set(features = ['feature-a'])],",
                "            ),",
                "        ],",
                "        implies = ['activated-feature'],",
                "    ),",
                "],",
                "features = [",
                "    feature(name = 'activated-feature'),",
                "]"
            )

        val featureConfiguration: FeatureConfiguration =
            toolchainFeatures.getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("action-a"))

        assertThat(featureConfiguration.isEnabled("activated-feature")).isTrue()
    }

    @org.junit.Test
    fun testInvalidActionConfigurationMultipleActionConfigsForAction() {
        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable {
                    buildFeatures(
                        "action_configs = [",
                        "    action_config(action_name = 'action-a'),",
                        "    action_config(action_name = 'action-a'),",
                        "]"
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("multiple action configs for action 'action-a'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagsFromActionConfig() {
        val featureConfiguration: FeatureConfiguration =
            buildFeatures(
                "action_configs = [",
                "    action_config(",
                "        action_name = 'c++-compile',",
                "        flag_sets = [flag_set(flag_groups = [flag_group(flags = ['foo'])])],",
                "    ),",
                "]"
            )
                .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("c++-compile"))
        val commandLine: MutableList<String?>? =
            featureConfiguration.getCommandLine("c++-compile", createVariables())
        Truth.assertThat(commandLine).contains("foo")
    }

    @org.junit.Test
    fun testErrorForFlagFromActionConfigWithSpecifiedAction() {
        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable {
                    buildFeatures(
                        "action_configs = [",
                        "    action_config(",
                        "        action_name = 'c++-compile',",
                        "        flag_sets = [",
                        "            flag_set(",
                        "                actions = ['c++-compile'],",
                        "                flag_groups = [flag_group(flags = ['foo'])],",
                        "            ),",
                        "        ],",
                        "    ),",
                        "]"
                    )
                        .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("c++-compile"))
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(java.lang.String.format(ActionConfig.FLAG_SET_WITH_ACTION_ERROR, "c++-compile"))
    }

    @org.junit.Test
    fun testProvidesCollision() {
        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<java.lang.Exception?>(
                java.lang.Exception::class.java,
                org.junit.function.ThrowingRunnable {
                    buildFeatures(
                        "features = [",
                        "    feature(name = 'a', provides = ['provides_string']),",
                        "    feature(name = 'b', provides = ['provides_string']),",
                        "]"
                    )
                        .getFeatureConfiguration(com.google.common.collect.ImmutableSet.of<E?>("a", "b"))
                })
        Truth.assertThat(e).hasMessageThat().contains("a b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetArtifactNameExtensionForCategory() {
        val toolchainFeatures: CcToolchainFeatures =
            buildFeatures(
                "artifact_name_patterns = [",
                "    artifact_name_pattern(",
                "        category_name = 'object_file',",
                "        prefix = '',",
                "        extension = '.obj',",
                "    ),",
                "    artifact_name_pattern(",
                "        category_name = 'executable',",
                "        prefix = '',",
                "        extension = '',",
                "    ),",
                "    artifact_name_pattern(",
                "        category_name = 'static_library',",
                "        prefix = '',",
                "        extension = '.a',",
                "    ),",
                "]"
            )
        assertThat(toolchainFeatures.getArtifactNameExtensionForCategory(ArtifactCategory.OBJECT_FILE))
            .isEqualTo(".obj")
        assertThat(toolchainFeatures.getArtifactNameExtensionForCategory(ArtifactCategory.EXECUTABLE))
            .isEmpty()
        assertThat(
            toolchainFeatures.getArtifactNameExtensionForCategory(ArtifactCategory.STATIC_LIBRARY)
        )
            .isEqualTo(".a")
    }

    companion object {
        /**
         * Creates a `Variables` configuration from a list of key/value pairs.
         * 
         * 
         * If there are multiple entries with the same key, the variable will be treated as sequence
         * type.
         */
        private fun createVariables(vararg entries: String?): CcToolchainVariables {
            require(entries.size % 2 == 0) { "createVariables takes an even number of arguments (key/value pairs)" }
            val entryMap: com.google.common.collect.ListMultimap<String?, String?> =
                com.google.common.collect.ArrayListMultimap.create<String?, String?>()
            var i = 0
            while (i < entries.size) {
                entryMap.put(entries[i], entries[i + 1])
                i += 2
            }
            val variables: CcToolchainVariables.Builder = CcToolchainVariables.builder()
            for (name in entryMap.keySet()) {
                val value: MutableList<String?> = entryMap.get(name)
                if (value.size == 1) {
                    variables.addVariable(name, value.get(0))
                } else {
                    variables.addStringSequenceVariable(
                        name,
                        com.google.common.collect.ImmutableList.< E > copyOf < E ? > (value)
                    )
                }
            }
            return variables.build()
        }

        @Throws(java.lang.Exception::class)
        private fun getEnabledFeatures(
            features: CcToolchainFeatures, vararg requestedFeatures: String?
        ): com.google.common.collect.ImmutableSet<String?> {
            val configuration: FeatureConfiguration =
                features.getFeatureConfiguration(com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (requestedFeatures))
            val enabledFeatures: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.builder<String?>()
            for (feature in features.getActivatableNames()) {
                if (configuration.isEnabled(feature)) {
                    enabledFeatures.add(feature)
                }
            }
            return enabledFeatures.build()
        }

        private fun createStructureSequenceVariables(
            name: String?, vararg values: VariableValue?
        ): CcToolchainVariables {
            return CcToolchainVariables.builder()
                .addVariable(name, com.google.common.collect.ImmutableList.< E > copyOf < E ? > (values)).build()
        }

        private fun createStructureVariables(
            name: String?, value: StructureBuilder
        ): CcToolchainVariables {
            return CcToolchainVariables.builder().addVariable(name, value.build()).build()
        }

        @Throws(ExpansionException::class)
        private fun booleanValue(`val`: Boolean): VariableValue {
            return CcToolchainVariables.builder()
                .addVariable("name", `val`)
                .build()
                .getVariable("name", PathMapper.NOOP)
        }

        private fun createNestedSequence(
            depth: Int, count: Int, prefix: String
        ): com.google.common.collect.ImmutableList<VariableValue?> {
            val builder: com.google.common.collect.ImmutableList.Builder<VariableValue?> =
                com.google.common.collect.ImmutableList.builder<VariableValue?>()
            if (depth == 0) {
                for (i in 0..<count) {
                    val value = prefix + i
                    builder.add(StringValue(value))
                }
            } else {
                for (i in 0..<count) {
                    val value = prefix + i
                    builder.add(Sequence(createNestedSequence(depth - 1, count, value)))
                }
            }
            return builder.build()
        }

        private fun createNestedVariables(name: String?, depth: Int, count: Int): CcToolchainVariables {
            return CcToolchainVariables.builder()
                .addVariable(name, createNestedSequence(depth, count, ""))
                .build()
        }
    }
}
