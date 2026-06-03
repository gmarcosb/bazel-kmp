// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

/** Tests for [BuildOptionsScopeFunction].  */
@RunWith(TestParameterInjector::class)
class BuildOptionsScopeFunctionTest : BuildViewTestCase() {
    @Before
    fun doBeforeEachTest() {
        // inject Precomputed.BASELINE_CONFIGURATION
        val analysisMock: AnalysisMock = AnalysisMock.get()
        val ruleClassProvider: ConfiguredRuleClassProvider = analysisMock.createRuleClassProvider()
        val buildOptionClasses: com.google.common.collect.ImmutableSortedSet<java.lang.Class<out FragmentOptions?>?>? =
            ruleClassProvider.getFragmentRegistry().getOptionsClasses()

        val skyframeExecutor: SequencedSkyframeExecutor = getSkyframeExecutor()
        val defaultBuildOptions: BuildOptions? =
            BuildOptions.getDefaultBuildOptionsForFragments(buildOptionClasses).clone()
        skyframeExecutor.injectExtraPrecomputedValues(
            com.google.common.collect.ImmutableList.Builder<PrecomputedValue.Injected?>()
                .add(
                    PrecomputedValue.injected(
                        BaselineOptionsFunction.BASELINE_CONFIGURATION, defaultBuildOptions
                    )
                )
                .addAll(analysisMock.precomputedValues)
                .build()
        )
    }

    @org.junit.Test
    @TestParameters(
        "{scope: 'universal', expectFail: false}",
        "{scope: 'target', expectFail: false}",
        "{scope: 'project', expectFail: false}",
        "{scope: 'badvalue', expectFail: true}",
        "{scope: 'default', expectFail: true}" // Valid internal value but can't be set by users.
    )
    @Throws(java.lang.Exception::class)
    fun validScopeAttributeValues(scope: String?, expectFail: Boolean) {
        scratch.file(
            "test_flags/build_setting.bzl",
            """
        bool_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.bool(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test_flags/BUILD",
            """
        load("//test_flags:build_setting.bzl", "bool_flag")
        bool_flag(
            name = "foo",
            build_setting_default = False,
            scope = "%s",
        )
        
        """
                .trimIndent()
                .formatted(scope)
        )

        if (!expectFail) {
            assertThat(createBuildOptions("--//test_flags:foo=True")).isNotNull()
        } else {
            val exception: T? =
                org.junit.Assert.assertThrows<T?>(
                    InvalidConfigurationException::class.java,
                    org.junit.function.ThrowingRunnable { createBuildOptions("--//test_flags:foo=True") })
            assertThat(exception).hasMessageThat().contains("Invalid \"scope\" attribute value")
        }
    }

    @org.junit.Test
    @Ignore("TODO(b/359622692): turns this back on in a follow up CL")
    @Throws(java.lang.Exception::class)
    fun buildOptionsScopesFunction_returnsCorrectScope() {
        scratch.file(
            "test_flags/build_setting.bzl",
            """
        bool_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.bool(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test_flags/BUILD",
            """
        load("//test_flags:build_setting.bzl", "bool_flag")
        bool_flag(
            name = "foo",
            build_setting_default = False,
            scope = "project",
        )
        bool_flag(
            name = "bar",
            build_setting_default = False,
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test_flags/PROJECT.scl",
            """
        active_directories = {
          "default": [
              "//my_project/"
          ]
        }
        
        """.trimIndent()
        )
        setBuildLanguageOptions("--experimental_enable_scl_dialect=true")
        val buildOptions: BuildOptions =
            createBuildOptions("--//test_flags:foo=True", "--//test_flags:bar=True")

        // purposely removing the scope for //test_flags:bar to simulate the case where the scope is
        // not yet resolved for a flag.
        val inputBuildOptionsWithIncompleteScopeTypeMap: BuildOptions? =
            buildOptions.toBuilder().removeScope(Label.parseCanonical("//test_flags:bar")).build()

        val scopedFlags: com.google.common.collect.ImmutableList<Label?> =
            com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//test_flags:bar"))
        val key: BuildOptionsScopeValue.Key =
            BuildOptionsScopeValue.Key.create(inputBuildOptionsWithIncompleteScopeTypeMap, scopedFlags)

        // verify that the scope type is not yet resolved for //test_flags:bar
        assertThat(key.getBuildOptions().getScopeTypeMap()).hasSize(1)

        val buildOptionsScopeValue: BuildOptionsScopeValue = executeFunction(key)

        // verify that the Scope is fully resolved for //test_flags:foo and //test_flags:bar
        val unused: com.google.common.truth.Subject? =
            assertThat(
                buildOptionsScopeValue
                    .getFullyResolvedScopes()
                    .equals(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            Label.parseCanonical("//test_flags:foo"),
                            Scope(
                                ScopeType(Scope.ScopeType.PROJECT),
                                ScopeDefinition(com.google.common.collect.ImmutableSet.of<E?>("//my_project/"))
                            ),
                            Label.parseCanonical("//test_flags:bar"),
                            Scope(ScopeType(Scope.ScopeType.UNIVERSAL), null)
                        )
                    )
            )

        // verify that the BuildOptionsScopeValue.getResolvedBuildOptionsWithScopeTypes() has the
        // correct ScopeType map for all flags.
        assertThat(buildOptionsScopeValue.getResolvedBuildOptionsWithScopeTypes().getScopeTypeMap())
            .containsExactly(
                Label.parseCanonical("//test_flags:foo"),
                ScopeType(Scope.ScopeType.PROJECT),
                Label.parseCanonical("//test_flags:bar"),
                ScopeType(Scope.ScopeType.UNIVERSAL)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildOptionsScopesFunction_doesNotErrorOut_whenNoProjectFile() {
        scratch.file(
            "test_flags/build_setting.bzl",
            """
        bool_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.bool(flag = True),
            attrs = {
                "scope": attr.string(default = "universal"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test_flags/BUILD",
            """
        load("//test_flags:build_setting.bzl", "bool_flag")
        bool_flag(
            name = "foo",
            build_setting_default = False,
            scope = "project",
        )
        
        """.trimIndent()
        )

        setBuildLanguageOptions("--experimental_enable_scl_dialect=true")
        val buildOptionsWithoutScopes: BuildOptions = createBuildOptions("--//test_flags:foo=True")
        val scopedFlags: com.google.common.collect.ImmutableList<Label?> =
            com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//test_flags:foo"))
        val key: BuildOptionsScopeValue.Key? =
            BuildOptionsScopeValue.Key.create(buildOptionsWithoutScopes, scopedFlags)

        val buildOptionsScopeValue: BuildOptionsScopeValue = executeFunction(key)
        val unused: com.google.common.truth.Subject? =
            assertThat(
                buildOptionsScopeValue
                    .getFullyResolvedScopes()
                    .equals(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            Label.parseCanonical("//test_flags:foo"),
                            Scope(ScopeType(Scope.ScopeType.PROJECT), null)
                        )
                    )
            )
    }

    @Throws(java.lang.Exception::class)
    private fun executeFunction(key: BuildOptionsScopeValue.Key?): BuildOptionsScopeValue {
        val skyframeExecutor: SkyframeExecutor = getSkyframeExecutor()
        val result: EvaluationResult<BuildOptionsScopeValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key,  /* keepGoing= */false, reporter)
        if (result.hasError()) {
            throw result.getError(key).getException()
        }
        return result.get(key)
    }
}
