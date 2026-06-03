// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

/** Unit tests for PackageFactory's management of the predeclared Starlark symbols.  */
@RunWith(JUnit4::class)
class BazelStarlarkEnvironmentTest : BuildViewTestCase() {
    private var starlarkEnv: BazelStarlarkEnvironment? = null

    @Before
    fun setUp() {
        this.starlarkEnv = ruleClassProvider.getBazelStarlarkEnvironment()
    }

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        // Add a fake rule and top-level symbol to override.
        val builder: ConfiguredRuleClassProvider.Builder =
            Builder() // While reading, feel free to mentally substitute overridable_rule -> cc_library and
                // overridable_symbol -> CcInfo.
                .addRuleDefinition(OVERRIDABLE_RULE)
                .addBzlToplevel("overridable_symbol", "original_value")
                .addBzlToplevel("another_overridable_symbol", "another_original_value")
        TestRuleClassProvider.addStandardRules(builder)
        return builder.build()
    }

    // TODO(#11954): We want BUILD- and MODULE-loaded bzl files to have the exact same environment.
    // In the meantime these two tests help avoid regressions.
    // This property is important for BzlCompileFunction, which relies on the symbol names in the env
    // matching even if the symbols themselves differ.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildAndModuleBzlEnvsDeclareSameNames() {
        assertThat(starlarkEnv.getUninjectedBuildBzlEnv().keySet())
            .containsExactlyElementsIn(starlarkEnv.getUninjectedModuleBzlEnv().keySet())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildAndModuleBzlEnvsAreSameExceptForNative() {
        val buildBzlEnv: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        buildBzlEnv.putAll(starlarkEnv.getUninjectedBuildBzlEnv())
        buildBzlEnv.remove("native")
        val moduleBzlEnv: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        moduleBzlEnv.putAll(starlarkEnv.getUninjectedModuleBzlEnv())
        moduleBzlEnv.remove("native")
        Truth.assertThat(buildBzlEnv).isEqualTo(moduleBzlEnv)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtinsBzlEnv() {
        val env: com.google.common.collect.ImmutableMap<String?, Any?>? = starlarkEnv.getBuiltinsBzlEnv()
        // Can see general toplevel symbols.
        Truth.assertThat(env).containsKey("rule")
        // Cannot see rule-specific toplevel symbols.
        Truth.assertThat(env).doesNotContainKey("overridable_symbol")
        // Has the special builtins-internal module.
        Truth.assertThat(env).containsKey("_builtins")
    }

    /**
     * Asserts that injection for a BUILD-loaded .bzl file fails, using the given maps and expecting
     * the given error substring. The overrides list is empty.
     */
    private fun assertBuildBzlInjectionFailure(
        exportedToplevels: MutableMap<String?, Any?>?, exportedRules: MutableMap<String?, Any?>?, message: String?
    ) {
        val ex: InjectionException? =
            org.junit.Assert.assertThrows<T?>(
                InjectionException::class.java,
                org.junit.function.ThrowingRunnable {
                    starlarkEnv.createBuildBzlEnvUsingInjection(
                        exportedToplevels,
                        exportedRules,  /* overridesList= */
                        com.google.common.collect.ImmutableList.of<E?>()
                    )
                })
        assertThat(ex).hasMessageThat().contains(message)
    }

    /**
     * Asserts that injection for a BUILD file fails, using the given map and expecting the given
     * error substring. The overrides list is empty.
     */
    private fun assertBuildInjectionFailure(exportedRules: MutableMap<String?, Any?>?, message: String?) {
        val ex: InjectionException? =
            org.junit.Assert.assertThrows<T?>(
                InjectionException::class.java,
                org.junit.function.ThrowingRunnable {
                    starlarkEnv.createBuildEnvUsingInjection(
                        exportedRules,  /* overridesList= */com.google.common.collect.ImmutableList.of<E?>()
                    )
                })
        assertThat(ex).hasMessageThat().contains(message)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildBzlInjection() {
        val env: MutableMap<String?, Any?> =
            starlarkEnv.createBuildBzlEnvUsingInjection(
                com.google.common.collect.ImmutableMap.of<K?, V?>("overridable_symbol", "new_value"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("overridable_rule", "new_rule"),  /* overridesList= */
                com.google.common.collect.ImmutableList.of<E?>()
            )
        Truth.assertThat(env).containsEntry("overridable_symbol", "new_value")
        Truth.assertThat((env.get("native") as Structure).getValue("overridable_rule")).isEqualTo("new_rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildInjection() {
        val env: MutableMap<String?, Any?>? =
            starlarkEnv.createBuildEnvUsingInjection(
                com.google.common.collect.ImmutableMap.of<K?, V?>("overridable_rule", "new_rule"),  /* overridesList= */
                com.google.common.collect.ImmutableList.of<E?>()
            )
        Truth.assertThat(env).containsEntry("overridable_rule", "new_rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectedNameMustOverrideExistingName_toplevel() {
        assertBuildBzlInjectionFailure(
            com.google.common.collect.ImmutableMap.of<String?, Any?>("brand_new_toplevel", "foo"),
            com.google.common.collect.ImmutableMap.of<String?, Any?>(),
            "Injected top-level symbol 'brand_new_toplevel' must override an existing one by that"
                    + " name"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectedNameMustOverrideExistingName_rule() {
        assertBuildBzlInjectionFailure(
            com.google.common.collect.ImmutableMap.of<String?, Any?>(),
            com.google.common.collect.ImmutableMap.of<String?, Any?>("brand_new_rule", "foo"),
            "Injected rule 'brand_new_rule' must override an existing one by that name"
        )
        assertBuildInjectionFailure(
            com.google.common.collect.ImmutableMap.of<String?, Any?>("brand_new_rule", "foo"),
            "Injected rule 'brand_new_rule' must override an existing one by that name"
        )
    }

    @org.junit.Test
    fun cannotInjectGeneralSymbol_toplevel() {
        assertBuildBzlInjectionFailure(
            com.google.common.collect.ImmutableMap.of<String?, Any?>("provider", "new_builtin"),
            com.google.common.collect.ImmutableMap.of<String?, Any?>(),
            "Cannot override 'provider' with an injected top-level symbol"
        )
    }

    @org.junit.Test
    fun cannotInjectGeneralSymbol_nativeField() {
        // (Native field for bzl files, toplevel for BUILD files.)
        assertBuildBzlInjectionFailure(
            com.google.common.collect.ImmutableMap.of<String?, Any?>(),
            com.google.common.collect.ImmutableMap.of<String?, Any?>("glob", "new_builtin"),
            "Cannot override 'glob' with an injected rule"
        )
        assertBuildInjectionFailure(
            com.google.common.collect.ImmutableMap.of<String?, Any?>("glob", "new_builtin"),
            "Cannot override 'glob' with an injected rule"
        )
    }

    @org.junit.Test
    fun cannotInjectGeneralSymbol_nativeModuleItself() {
        assertBuildBzlInjectionFailure(
            com.google.common.collect.ImmutableMap.of<String?, Any?>("native", "new_builtin"),
            com.google.common.collect.ImmutableMap.of<String?, Any?>(),
            "Cannot override 'native' with an injected top-level symbol"
        )
    }

    @org.junit.Test
    fun cannotInjectGeneralSymbol_universe() {
        assertBuildBzlInjectionFailure(
            com.google.common.collect.ImmutableMap.of<String?, Any?>("len", "new_builtin"),
            com.google.common.collect.ImmutableMap.of<String?, Any?>(),
            "Cannot override 'len' with an injected top-level symbol"
        )
        assertBuildInjectionFailure(
            com.google.common.collect.ImmutableMap.of<String?, Any?>("len", "new_builtin"),
            "Cannot override 'len' with an injected rule"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectionStatus_respectsDefault() {
        val env: MutableMap<String?, Any?> =
            starlarkEnv.createBuildBzlEnvUsingInjection(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "+overridable_symbol",
                    "new_value",
                    "-another_overridable_symbol",
                    "another_new_value"
                ),
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "-overridable_rule",
                    "new_rule"
                ),  /* overridesList= */
                com.google.common.collect.ImmutableList.of<E?>()
            )
        Truth.assertThat(env).containsEntry("overridable_symbol", "new_value")
        Truth.assertThat(env).containsEntry("another_overridable_symbol", "another_original_value")
        // Match the original rule's toString since the actual specific object is not easily accessible.
        val overridableRuleValue: Any? = (env.get("native") as Structure).getValue("overridable_rule")
        Truth.assertThat(overridableRuleValue.toString()).contains("overridable_rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectionStatus_canBeOverridden() {
        val env: MutableMap<String?, Any?> =
            starlarkEnv.createBuildBzlEnvUsingInjection(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "+overridable_symbol",
                    "new_value",
                    "-another_overridable_symbol",
                    "another_new_value"
                ),
                com.google.common.collect.ImmutableMap.of<K?, V?>("-overridable_rule", "new_rule"),
                com.google.common.collect.ImmutableList.of<E?>(
                    "-overridable_symbol", "+another_overridable_symbol", "+overridable_rule"
                )
            )
        Truth.assertThat(env).containsEntry("overridable_symbol", "original_value")
        Truth.assertThat(env).containsEntry("another_overridable_symbol", "another_new_value")
        Truth.assertThat((env.get("native") as Structure).getValue("overridable_rule")).isEqualTo("new_rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectionStatus_cannotBeOverriddenForUnprefixedKeys() {
        val env: MutableMap<String?, Any?>? =
            starlarkEnv.createBuildBzlEnvUsingInjection(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "overridable_symbol",
                    "new_value",
                    "another_overridable_symbol",
                    "another_new_value"
                ),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableList.of<E?>("+overridable_symbol", "-another_overridable_symbol")
            )
        // Both the + and - are no-ops since the keys aren't prefixed.
        Truth.assertThat(env).containsEntry("overridable_symbol", "new_value")
        Truth.assertThat(env).containsEntry("another_overridable_symbol", "another_new_value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectionStatus_overridingUnknownKeysIsNoop() {
        val env: MutableMap<String?, Any?>? =
            starlarkEnv.createBuildBzlEnvUsingInjection(
                com.google.common.collect.ImmutableMap.of<K?, V?>("-overridable_symbol", "new_value"),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableList.of<E?>(
                    "+overridable_symbol",
                    "+unknown_symbol",
                    "-another_unknown_symbol"
                )
            )
        // Both the + and - are no-ops since the keys aren't prefixed.
        Truth.assertThat(env).containsEntry("overridable_symbol", "new_value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectionStatus_lastOverrideTakesPrecedence() {
        val env: MutableMap<String?, Any?>? =
            starlarkEnv.createBuildBzlEnvUsingInjection(
                com.google.common.collect.ImmutableMap.of<K?, V?>("-overridable_symbol", "new_value"),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableList.of<E?>("+overridable_symbol", "-overridable_symbol")
            )
        // Both the + and - are no-ops since the keys aren't prefixed.
        Truth.assertThat(env).containsEntry("overridable_symbol", "original_value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectionStatus_invalidOverrideItem_empty() {
        val ex: InjectionException? =
            org.junit.Assert.assertThrows<T?>(
                InjectionException::class.java,
                org.junit.function.ThrowingRunnable {
                    starlarkEnv.createBuildBzlEnvUsingInjection(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        com.google.common.collect.ImmutableList.of<E?>("")
                    )
                })
        assertThat(ex).hasMessageThat().contains("Invalid injection override item: ''")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectionStatus_invalidOverrideItem_unprefixed() {
        val ex: InjectionException? =
            org.junit.Assert.assertThrows<T?>(
                InjectionException::class.java,
                org.junit.function.ThrowingRunnable {
                    starlarkEnv.createBuildBzlEnvUsingInjection(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        com.google.common.collect.ImmutableList.of<E?>("foo")
                    )
                })
        assertThat(ex).hasMessageThat().contains("Invalid injection override item: 'foo'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectionStatus_appliesToBuildFiles() {
        val env: MutableMap<String?, Any?> =
            starlarkEnv.createBuildEnvUsingInjection(
                com.google.common.collect.ImmutableMap.of<K?, V?>("+overridable_rule", "new_rule"),
                com.google.common.collect.ImmutableList.of<E?>("-overridable_rule")
            )
        // Match the original rule's toString since the actual specific object is not easily accessible.
        Truth.assertThat(env.get("overridable_rule").toString()).contains("overridable_rule")
    }

    companion object {
        private val OVERRIDABLE_RULE: MockRule = MockRule { MockRule.define("overridable_rule") }
    }
}
