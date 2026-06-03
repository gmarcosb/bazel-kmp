// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.config

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.lang.String
import java.util.function.Function
import kotlin.AssertionError
import kotlin.Boolean
import kotlin.Exception
import kotlin.Unit
import kotlin.arrayOfNulls
import kotlin.toString

/** Tests for [config.string_set].  */
@RunWith(TestParameterInjector::class)
class ConfigStringSetTest : BuildViewTestCase() {
    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        builder.addConfigurationFragment(DummyTestFragment::class.java)
        return builder.build()
    }

    @Throws(Exception::class)
    private fun createStringSetFlag(defaultValues: MutableList<String?>, repeatable: Boolean) {
        scratch.file(
            "flag/def.bzl",
            String.format(
                """
            def _impl(ctx):
              return []

            string_set_flag = rule(
              implementation = _impl,
              build_setting = config.string_set(flag = True, repeatable = %s),
            )
            
            """.trimIndent(),
                if (repeatable) "True" else "False"
            )
        )
        scratch.file(
            "flag/BUILD",
            String.format(
                """
            load("//flag:def.bzl", "string_set_flag")
            string_set_flag(
                name = "foo",
                build_setting_default = set([%s]),
            )
            
            """.trimIndent(),
                defaultValues.stream().map<kotlin.String?>(Function { v: kotlin.String? -> String.format("'%s'", v) })
                    .collect(Collectors.joining(", "))
            )
        )
    }

    @Test
    @TestParameters( // Only default value is set, does not show in BuildOptions.
        ("{defaultValue: ['v2', 'v1', 'v1', 'v3', 'v2', 'v3', 'v4'], repeatable: false, cmdValue: [],"
                + " expectedValue: null}") // Not repeatable, flag value is not the same as the default, shows in BuildOptions.

        , ("{defaultValue: ['default'], repeatable: false, cmdValue:"
                + " ['v1,v4,v3,v2,v3,v1,v4,v1'], expectedValue: ['v1', 'v2', 'v3', 'v4']}") // Repeatable, flag value is not the same as the default, shows in BuildOptions.
        , ("{defaultValue: ['default'], repeatable: true, cmdValue: ['v2', 'v2', 'v1', 'v3', 'v4', 'v1'],"
                + " expectedValue: ['v1', 'v2', 'v3', 'v4']}") // Repeatable with complex entries, flag value is not the same as the default, shows in
        // BuildOptions.
        , ("{defaultValue: ['default'], repeatable: true, cmdValue: ['v2,v1,v3', 'v1,v2,v3', 'v1', 'v3',"
                + " 'v4', 'v1'], expectedValue: ['v2,v1,v3', 'v1,v2,v3', 'v1', 'v3', 'v4']}") // Not repeatable, flag value is the same as the default, does not show in BuildOptions.

        , ("{defaultValue: ['v2', 'v1', 'v1', 'v3', 'v2', 'v3', 'v4'], repeatable: false, cmdValue:"
                + " ['v1,v4,v3,v2,v3,v1,v4,v1'], expectedValue: null}") // Repeatable, flag value is the same as the default, does not show in BuildOptions.
        , ("{defaultValue: ['v2', 'v1', 'v1', 'v3', 'v2', 'v3', 'v4'], repeatable: true, cmdValue: ['v3',"
                + " 'v2', 'v1', 'v3', 'v4', 'v1'], expectedValue: null}") // Repeatable with complex entries, flag value is the same as the default, does not show in
        // BuildOptions.
        , ("{defaultValue: ['v2,v1,v3', 'v1,v2,v3', 'v1', 'v3', 'v3', 'v4'], repeatable: true, cmdValue:"
                + " ['v2,v1,v3', 'v3', 'v1,v2,v3', 'v1', 'v4', 'v4', 'v1'], expectedValue: null}")
    )
    @Throws(Exception::class)
    fun commandLineFlag_comparedToDefault(
        defaultValue: MutableList<kotlin.String?>,
        repeatable: Boolean,
        cmdValue: MutableList<kotlin.String?>,
        expectedValue: MutableList<kotlin.String?>?
    ) {
        createStringSetFlag(defaultValue, repeatable)
        scratch.file(
            "pkg/def.bzl",
            """
        def _simple_impl(ctx):
          f = ctx.actions.declare_file("out")
          ctx.actions.write(f, "bar")
          return [DefaultInfo(files = depset([f]))]

        simple_rule = rule(
          implementation = _simple_impl,
        )
        
        """.trimIndent()
        )

        scratch.file(
            "pkg/BUILD",
            """
        load("//pkg:def.bzl", "simple_rule")
        simple_rule(
            name = "bar",
        )
        
        """.trimIndent()
        )

        useConfiguration(
            *cmdValue.stream()
                .map<kotlin.String?>(Function { v: kotlin.String? -> "--//flag:foo=" + v })
                .collect(ImmutableList.toImmutableList<kotlin.String?>())
                .toArray<kotlin.String?>(arrayOfNulls<kotlin.String>(0))
        )

        val target: ConfiguredTarget = getConfiguredTarget("//pkg:bar")

        val topLevelTargetStarlarkOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            target.getConfigurationKey().getOptions().getStarlarkOptions()
        val baselineStarlarkOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            targetConfig.getOptions().getStarlarkOptions()
        assertThat(topLevelTargetStarlarkOptions).isEqualTo(baselineStarlarkOptions)

        if (expectedValue == null) {
            assertThat(topLevelTargetStarlarkOptions).isEmpty()
        } else {
            assertThat(topLevelTargetStarlarkOptions.get(Label.parseCanonicalUnchecked("//flag:foo")))
                .isEqualTo(ImmutableSet.copyOf<kotlin.String?>(expectedValue))
        }

        assertThat(targetConfig.getOutputDirectoryName()).doesNotContain("-ST-")
        Truth.assertThat(getArtifactPath(target)).doesNotContain("-ST-")
    }

    @Throws(Exception::class)
    private fun createRuleWithTransition(flagValue: kotlin.String?) {
        scratch.file(
            "pkg/def.bzl",
            String.format(
                """
            def _transition_impl(settings, attributes):
              return {'//flag:foo': %s}

            my_transition = transition(
              implementation = _transition_impl,
              inputs = [],
              outputs = ['//flag:foo'],
            )

            def _simple_impl(ctx):
              f = ctx.actions.declare_file("out")
              ctx.actions.write(f, "bar")
              return [DefaultInfo(files = depset([f]))]

            simple_rule = rule(
              implementation = _simple_impl,
              cfg = my_transition
            )
            
            """.trimIndent(),
                flagValue
            )
        )

        scratch.file(
            "pkg/BUILD",
            """
        load("//pkg:def.bzl", "simple_rule")
        simple_rule(
            name = "bar",
        )
        
        """.trimIndent()
        )
    }

    @Test
    @Throws(Exception::class)
    fun modifiedInRuleTransition_diffValues_diffConfig() {
        createStringSetFlag( /* defaultValues= */ImmutableList.of<kotlin.String?>(),  /* repeatable= */false)
        createRuleWithTransition( /* flagValue= */"['v4', 'v3', 'v4', 'v5']")
        useConfiguration("--//flag:foo=v2,v1,v1,v3")

        val target: ConfiguredTarget = getConfiguredTarget("//pkg:bar")

        val topLevelTargetStarlarkOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            target.getConfigurationKey().getOptions().getStarlarkOptions()
        val baselineStarlarkOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            targetConfig.getOptions().getStarlarkOptions()
        assertThat(topLevelTargetStarlarkOptions).isNotEqualTo(baselineStarlarkOptions)

        assertThat(baselineStarlarkOptions.get(Label.parseCanonicalUnchecked("//flag:foo")))
            .isEqualTo(ImmutableSet.of<kotlin.String?>("v1", "v2", "v3"))
        assertThat(topLevelTargetStarlarkOptions.get(Label.parseCanonicalUnchecked("//flag:foo")))
            .isEqualTo(ImmutableSet.of<kotlin.String?>("v3", "v4", "v5"))

        assertThat(targetConfig.getOutputDirectoryName()).doesNotContain("-ST-")
        Truth.assertThat(getArtifactPath(target)).contains("-ST-")
    }

    @Test
    @Throws(Exception::class)
    fun modifiedInRuleTransition_sameValues_sameConfig() {
        createStringSetFlag( /* defaultValues= */ImmutableList.of<kotlin.String?>(),  /* repeatable= */true)
        createRuleWithTransition( /* flagValue= */"['v5', 'v5', 'v4', 'v3']")
        useConfiguration("--//flag:foo=v3", "--//flag:foo=v5", "--//flag:foo=v5", "--//flag:foo=v4")

        val target: ConfiguredTarget = getConfiguredTarget("//pkg:bar")

        val topLevelTargetStarlarkOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            target.getConfigurationKey().getOptions().getStarlarkOptions()
        val baselineStarlarkOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            targetConfig.getOptions().getStarlarkOptions()
        assertThat(topLevelTargetStarlarkOptions).isEqualTo(baselineStarlarkOptions)

        assertThat(topLevelTargetStarlarkOptions.get(Label.parseCanonicalUnchecked("//flag:foo")))
            .isEqualTo(ImmutableSet.of<kotlin.String?>("v3", "v4", "v5"))

        assertThat(targetConfig.getOutputDirectoryName()).doesNotContain("-ST-")
        Truth.assertThat(getArtifactPath(target)).doesNotContain("-ST-")
    }

    @Test
    @Throws(Exception::class)
    fun modifiedInRuleTransition_sameValueAsDefault_notInBuildOptions() {
        createStringSetFlag( /* defaultValues= */
            ImmutableList.of<kotlin.String?>("v3", "v1", "v2"),  /* repeatable= */true
        )
        createRuleWithTransition( /* flagValue= */"['v2', 'v2', 'v3', 'v3', 'v1', 'v1']")

        val target: ConfiguredTarget = getConfiguredTarget("//pkg:bar")

        val topLevelTargetStarlarkOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            target.getConfigurationKey().getOptions().getStarlarkOptions()
        val baselineStarlarkOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            targetConfig.getOptions().getStarlarkOptions()
        assertThat(topLevelTargetStarlarkOptions).isEqualTo(baselineStarlarkOptions)

        assertThat(topLevelTargetStarlarkOptions).isEmpty()

        assertThat(targetConfig.getOutputDirectoryName()).doesNotContain("-ST-")
        Truth.assertThat(getArtifactPath(target)).doesNotContain("-ST-")
    }

    @Test
    @Throws(Exception::class)
    fun transitionSeesSetType() {
        createStringSetFlag( /* defaultValues= */ImmutableList.of<kotlin.String?>(),  /* repeatable= */true)
        scratch.file(
            "pkg/def.bzl",
            """
        def _transition_impl(settings, attributes):
          if type(settings['//flag:foo']) != 'set':
            fail("Unexpected value type: %s" % type(settings['//flag:foo']))
          if settings['//flag:foo'] != set(['v1', 'v2', 'v3']):
            fail("Unexpected value: %s" % settings['//flag:foo'])
          return {}

        my_transition = transition(
          implementation = _transition_impl,
          inputs = ['//flag:foo'],
          outputs = [],
        )

        def _simple_impl(ctx):
          pass

        simple_rule = rule(
          implementation = _simple_impl,
          cfg = my_transition
        )
        
        """.trimIndent()
        )

        scratch.file(
            "pkg/BUILD",
            """
        load("//pkg:def.bzl", "simple_rule")
        simple_rule(
            name = "bar",
        )
        
        """.trimIndent()
        )
        useConfiguration("--//flag:foo=v2", "--//flag:foo=v3", "--//flag:foo=v1", "--//flag:foo=v2")

        val target: ConfiguredTarget = getConfiguredTarget("//pkg:bar")

        assertThat(target).isNotNull()
        assertNoEvents()
    }

    @Test
    @Throws(Exception::class)
    fun stringSetFlag_transitionCanReturnListOrSet() {
        createStringSetFlag( /* defaultValues= */ImmutableList.of<kotlin.String?>(),  /* repeatable= */true)
        scratch.file(
            "pkg/def.bzl",
            """
        def _set_transition_impl(settings, attributes):
          return {"//flag:foo": set(['v1', 'v2', 'v3'])}

        set_transition = transition(
          implementation = _set_transition_impl,
          inputs = [],
          outputs = ["//flag:foo"],
        )

        def _list_transition_impl(settings, attributes):
          return {"//flag:foo": ['v1', 'v2', 'v3']}

        list_transition = transition(
          implementation = _list_transition_impl,
          inputs = [],
          outputs = ["//flag:foo"],
        )

        def _impl(ctx):
          pass

        set_transition_rule = rule(
          implementation = _impl,
          cfg = set_transition
        )

        list_transition_rule = rule(
          implementation = _impl,
          cfg = list_transition
        )
        
        """.trimIndent()
        )

        scratch.file(
            "pkg/BUILD",
            """
        load("//pkg:def.bzl", "set_transition_rule", "list_transition_rule")
        set_transition_rule(
            name = "set_transition",
        )
        list_transition_rule(
            name = "list_transition",
        )
        
        """.trimIndent()
        )

        // Transitions can return either a set or a list for a string_set flag and the value will be
        // converted to a set in the Starlark options.
        val setTarget: ConfiguredTarget = getConfiguredTarget("//pkg:set_transition")
        val setTargetStarlarkOption: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            setTarget
                .getConfigurationKey()
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:foo"))
        assertThat(setTargetStarlarkOption).isEqualTo(ImmutableSet.of<kotlin.String?>("v1", "v2", "v3"))
        // no conversion is needed so the value type stays as {@code StarlarkSet}.
        assertThat(setTargetStarlarkOption).isInstanceOf(StarlarkSet::class.java)

        val listTarget: ConfiguredTarget = getConfiguredTarget("//pkg:list_transition")
        val listTargetStarlarkOption: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            listTarget
                .getConfigurationKey()
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:foo"))
        assertThat(listTargetStarlarkOption).isEqualTo(ImmutableSet.of<kotlin.String?>("v1", "v2", "v3"))
        // the list value is converted to a {@code ImmutableSet}.
        assertThat(listTargetStarlarkOption).isInstanceOf(ImmutableSet::class.java)
    }

    @Test
    @Throws(Exception::class)
    fun modifiedInAttributeTransitions_sameFlagValues_sameConfig() {
        createStringSetFlag( /* defaultValues= */ImmutableList.of<kotlin.String?>(),  /* repeatable= */false)
        scratch.file(
            "pkg/def.bzl",
            """
        def _r1_transition_impl(settings, attributes):
          return {'//flag:foo': ['v2', 'v1', 'v3']}

        r1_transition = transition(
          implementation = _r1_transition_impl,
          inputs = [],
          outputs = ['//flag:foo'],
        )

        def _r2_transition_impl(settings, attributes):
          return {'//flag:foo': set(['v3', 'v2', 'v1'])}

        r2_transition = transition(
          implementation = _r2_transition_impl,
          inputs = [],
          outputs = ['//flag:foo'],
        )

        def _simple_impl(ctx):
          f = ctx.actions.declare_file("out")
          ctx.actions.write(f, "bar")
          return [DefaultInfo(files = depset([f]))]

        r1 = rule(
          implementation = _simple_impl,
          attrs = {
            'transitioned_deps': attr.label_list(cfg = r1_transition),
            'not_transitioned_deps': attr.label_list()
          })

        r2 = rule(
          implementation = _simple_impl,
          attrs = {
            'transitioned_deps': attr.label_list(cfg = r2_transition),
          })
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//pkg:def.bzl", "r1", "r2")
        r1(
            name = "t1",
            transitioned_deps = [":t2", ":t3"],
            not_transitioned_deps = [":t4"],
        )
        r2(
            name = "t2",
            transitioned_deps = [":t3"],
        )
        r2(name = "t3")
        r2(name = "t4")
        
        """.trimIndent()
        )

        val t1: ConfiguredTarget = getConfiguredTarget("//pkg:t1")

        val baselineStarlarkOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            targetConfig.getOptions().getStarlarkOptions()

        // t3 exists with a single configuration since the //flag:foo flag value is the same in the 2
        // paths leading to it.
        val possibleT3s: ImmutableList<ConfiguredTarget> = getComputedConfiguredTarget("//pkg:t3")
        Truth.assertThat(possibleT3s).hasSize(1)
        val t3: ConfiguredTarget = possibleT3s.get(0)
        assertThat(
            t3.getConfigurationKey()
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:foo"))
        )
            .isEqualTo(ImmutableSet.of<kotlin.String?>("v1", "v2", "v3"))

        // t4 has the same configuration as t1 as it did not apply a transition on it.
        val t4: ConfiguredTarget? = Iterables.getOnlyElement<ConfiguredTarget?>(getComputedConfiguredTarget("//pkg:t4"))
        assertThat(t4.getConfigurationKey()).isEqualTo(t1.getConfigurationKey())
        assertThat(t1.getConfigurationKey().getOptions().getStarlarkOptions())
            .isEqualTo(baselineStarlarkOptions)

        // t2 should have the same configuration as t3 since the //flag:foo flag has the same set.
        val t2: ConfiguredTarget? = Iterables.getOnlyElement<ConfiguredTarget?>(getComputedConfiguredTarget("//pkg:t2"))
        assertThat(t2.getConfigurationKey()).isEqualTo(t3.getConfigurationKey())

        // Since the transition returns the same set of values for //flag:foo, the {@code BuildOptions}
        // are the same.
        assertThat(t2.getConfigurationKey().getOptions())
            .isSameInstanceAs(t3.getConfigurationKey().getOptions())

        Truth.assertThat(getArtifactPath(t1)).doesNotContain("-ST-")
        Truth.assertThat(getArtifactPath(t2)).contains("-ST-")
        Truth.assertThat(getArtifactPath(t3)).contains("-ST-")
        Truth.assertThat(getArtifactPath(t4)).doesNotContain("-ST-")
    }

    @Test
    @Throws(Exception::class)
    fun modifiedInMultipleRuleTransitions_sameValues_sameConfig() {
        createStringSetFlag( /* defaultValues= */ImmutableList.of<kotlin.String?>(),  /* repeatable= */false)
        scratch.file(
            "pkg/def.bzl",
            """
        def _r2_transition_impl(settings, attributes):
          return {'//flag:foo': set(['v1', 'v2'])}

        r2_transition = transition(
          implementation = _r2_transition_impl,
          inputs = [],
          outputs = ['//flag:foo'],
        )

        def _r3_transition_impl(settings, attributes):
          return {'//flag:foo': ['v2', 'v1', 'v2', 'v2']}

        r3_transition = transition(
          implementation = _r3_transition_impl,
          inputs = [],
          outputs = ['//flag:foo'],
        )

        def _simple_impl(ctx):
          f = ctx.actions.declare_file("out")
          ctx.actions.write(f, "bar")
          return [DefaultInfo(files = depset([f]))]

        r1 = rule(
          implementation = _simple_impl,
          attrs = {
            'deps': attr.label_list(),
          })

        r2 = rule(
          implementation = _simple_impl,
          cfg = r2_transition,
          attrs = {
            'deps': attr.label_list(),
          })

        r3 = rule(
          implementation = _simple_impl,
          cfg = r3_transition,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//pkg:def.bzl", "r1", "r2", "r3")
        r1(
            name = "t1",
            deps = [":t2"],
        )
        r2(
            name = "t2",
            deps = [":t3"],
        )
        r3(name = "t3")
        
        """.trimIndent()
        )

        useConfiguration("--//flag:foo=v3")
        val t1: ConfiguredTarget = getConfiguredTarget("//pkg:t1")

        val baselineStarlarkOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            targetConfig.getOptions().getStarlarkOptions()
        assertThat(baselineStarlarkOptions)
            .isEqualTo(t1.getConfigurationKey().getOptions().getStarlarkOptions())

        // Due to the rule transition, t2 will have 2 entries in the skyframe values, both pointing to
        // the same configured target.
        val t2ConfiguredTargets: ImmutableList<ConfiguredTarget> = getComputedConfiguredTarget("//pkg:t2")
        Truth.assertThat(t2ConfiguredTargets).hasSize(2)
        assertThat(t2ConfiguredTargets.get(0)).isEqualTo(t2ConfiguredTargets.get(1))
        val t2: ConfiguredTarget = t2ConfiguredTargets.get(0)

        // For t3, the rule transition returns the same set for //flag:foo, so t3 will only have
        // a single configuration.
        val t3: ConfiguredTarget? = Iterables.getOnlyElement<ConfiguredTarget?>(getComputedConfiguredTarget("//pkg:t3"))

        assertThat(t2.getConfigurationKey()).isEqualTo(t3.getConfigurationKey())

        // Since the transition returns the same set of values for //flag:foo, the {@code BuildOptions}
        // are the same.
        assertThat(t2.getConfigurationKey().getOptions())
            .isSameInstanceAs(t3.getConfigurationKey().getOptions())

        Truth.assertThat(getArtifactPath(t1)).doesNotContain("-ST-")
        Truth.assertThat(getArtifactPath(t2)).contains("-ST-")
        Truth.assertThat(getArtifactPath(t3)).contains("-ST-")
    }

    @Test
    @Throws(Exception::class)
    fun stringSetInPlatformFlags() {
        createStringSetFlag( /* defaultValues= */
            ImmutableList.of<kotlin.String?>("v1", "v1", "v1", "v2"),  /* repeatable= */true
        )
        scratch.file(
            "pkg/def.bzl",
            """
        def _attr_transition_impl(settings, attributes):
          return {'//command_line_option:platforms': ['//pkg:platform_3']}

        attr_transition = transition(
          implementation = _attr_transition_impl,
          inputs = [],
          outputs = ['//command_line_option:platforms'],
        )

        def _rule_transition_impl(settings, attributes):
          return {'//command_line_option:platforms': ['//pkg:platform_2']}

        rule_transition = transition(
          implementation = _rule_transition_impl,
          inputs = [],
          outputs = ['//command_line_option:platforms'],
        )

        def _simple_impl(ctx):
          f = ctx.actions.declare_file("out")
          ctx.actions.write(f, "bar")
          return [DefaultInfo(files = depset([f]))]

        r1 = rule(
          implementation = _simple_impl,
          cfg = rule_transition,
          attrs = {
            'transitioned_deps': attr.label_list(cfg = attr_transition),
            'not_transitioned_deps': attr.label_list()
          })

        r2 = rule(
          implementation = _simple_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//pkg:def.bzl", "r1", "r2")
        r1(
            name = "t1",
            transitioned_deps = [":t2"],
            not_transitioned_deps = [":t3"],
        )
        r2(name = "t2")
        r2(name = "t3")

        platform(
            name = "platform_1",
            flags = ["--//flag:foo=v3"],
        )

        platform(
            name = "platform_2",
            flags = ["--//flag:foo=v2", "--//flag:foo=v1", "--//flag:foo=v1"],
        )

        platform(
            name = "platform_3",
            flags = ["--//flag:foo=v2", "--//flag:foo=v1", "--//flag:foo=v3", "--//flag:foo=v2"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--platforms=//pkg:platform_1")

        val t1: ConfiguredTarget = getConfiguredTarget("//pkg:t1")

        val baselineStarlarkOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            targetConfig.getOptions().getStarlarkOptions()

        // t3 has the same configuration as t1 as it did not apply a transition on it.
        val t3: ConfiguredTarget? = Iterables.getOnlyElement<ConfiguredTarget?>(getComputedConfiguredTarget("//pkg:t3"))
        assertThat(t3.getConfigurationKey()).isEqualTo(t1.getConfigurationKey())
        assertThat(t1.getConfigurationKey().getOptions().getStarlarkOptions())
            .isNotEqualTo(baselineStarlarkOptions)

        // //pk:platform_2 sets the flag to its default value, so it is removed from the starlark
        // options.
        assertThat(t1.getConfigurationKey().getOptions().getStarlarkOptions()).isEmpty()
        assertThat(baselineStarlarkOptions.get(Label.parseCanonicalUnchecked("//flag:foo")))
            .isEqualTo(ImmutableSet.of<kotlin.String?>("v3"))

        // t2 should have different configuration from t1 since the platform flag value is different.
        val t2: ConfiguredTarget? = Iterables.getOnlyElement<ConfiguredTarget?>(getComputedConfiguredTarget("//pkg:t2"))
        assertThat(t2.getConfigurationKey()).isNotEqualTo(t1.getConfigurationKey())
        assertThat(
            t2.getConfigurationKey()
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:foo"))
        )
            .isEqualTo(ImmutableSet.of<kotlin.String?>("v1", "v2", "v3"))

        assertThat(targetConfig.getOutputDirectoryName()).doesNotContain("ST")
        Truth.assertThat(getArtifactPath(t1)).contains("-ST-")
        Truth.assertThat(getArtifactPath(t2)).contains("-ST-")
        Truth.assertThat(getArtifactPath(t3)).contains("-ST-")
    }

    @Test
    @Throws(Exception::class)
    fun stringSet_differentSetTypes_sameConfig() {
        createStringSetFlag( /* defaultValues= */ImmutableList.of<kotlin.String?>(),  /* repeatable= */false)
        scratch.file(
            "pkg/def.bzl",
            """
        def _attr_transition_impl(settings, attributes):
          return {'//flag:foo': set(['v1', 'v3', 'v1', 'v2'])}

        attr_transition = transition(
          implementation = _attr_transition_impl,
          inputs = [],
          outputs = ['//flag:foo'],
        )

        def _rule_transition_impl(settings, attributes):
          return {'//command_line_option:platforms': '//pkg:my_platform'}

        rule_transition = transition(
          implementation = _rule_transition_impl,
          inputs = [],
          outputs = ['//command_line_option:platforms'],
        )

        def _simple_impl(ctx):
          f = ctx.actions.declare_file("out")
          ctx.actions.write(f, "bar")
          return [DefaultInfo(files = depset([f]))]

        r1 = rule(
          implementation = _simple_impl,
          attrs = {
            'deps': attr.label_list(cfg = attr_transition),
          })

        r2 = rule(
          implementation = _simple_impl,
          attrs = {
            'deps': attr.label_list(),
          })

        r3 = rule(
          implementation = _simple_impl,
          cfg = rule_transition,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//pkg:def.bzl", "r1", "r2", "r3")
        r1(name = "t1", deps = [":t2"])
        r2(name = "t2", deps = [":t3"])
        r3(name = "t3")

        platform(
            name = "my_platform",
            flags = ["--//flag:foo=v3,v2,v1,v2"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)

        val t1: ConfiguredTarget = getConfiguredTarget("//pkg:t1")
        val baselineStarlarkOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            targetConfig.getOptions().getStarlarkOptions()
        assertThat(baselineStarlarkOptions)
            .isEqualTo(t1.getConfigurationKey().getOptions().getStarlarkOptions())

        val t2: ConfiguredTarget? = Iterables.getOnlyElement<ConfiguredTarget?>(getComputedConfiguredTarget("//pkg:t2"))

        // Due to the rule transition, t3 will have 2 entries in the skyframe values, both pointing to
        // the same configured target.
        val t3ConfiguredTargets: ImmutableList<ConfiguredTarget> = getComputedConfiguredTarget("//pkg:t3")
        Truth.assertThat(t3ConfiguredTargets).hasSize(2)
        assertThat(t3ConfiguredTargets.get(0)).isEqualTo(t3ConfiguredTargets.get(1))
        val t3: ConfiguredTarget = t3ConfiguredTargets.get(0)

        // For t2, the rule transition returns a {@code StarlarkSet} for //flag:foo
        assertThat(
            t2.getConfigurationKey()
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:foo"))
        )
            .isInstanceOf(StarlarkSet::class.java)
        assertThat(
            t2.getConfigurationKey()
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:foo"))
                .toString()
        ) // The order of elements is sorted.
            .isEqualTo("set([\"v1\", \"v2\", \"v3\"])")

        // For t3, the value of //flag:foo is coming from the platform flag, so it should be of type
        // {@code ImmutableSet}.
        assertThat(
            t3.getConfigurationKey()
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:foo"))
        )
            .isInstanceOf(ImmutableSet::class.java)
        assertThat(
            t3.getConfigurationKey()
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:foo"))
                .toString()
        ) // The order of elements is sorted.
            .isEqualTo("[v1, v2, v3]")

        // Even though the value of //flag:foo is comping from different sources for t2 and t3, the set
        // values are equal.
        assertThat(t3.getConfigurationKey().getOptions().getStarlarkOptions())
            .isEqualTo(t2.getConfigurationKey().getOptions().getStarlarkOptions())
        assertThat(
            t2.getConfigurationKey()
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:foo"))
        )
            .isEqualTo(ImmutableSet.of<kotlin.String?>("v1", "v2", "v3"))

        // The difference in build options between t2 and t3 should only be --platforms.
        val diff: OptionsDiff =
            OptionsDiff.diff(
                t2.getConfigurationKey().getOptions(), t3.getConfigurationKey().getOptions()
            )
        assertThat(diff.prettyPrint())
            .isEqualTo(
                ("platforms:["
                        + Label.parseCanonicalUnchecked(TestConstants.PLATFORM_LABEL)
                        + "] -> [[//pkg:my_platform]]\n")
            )

        assertThat(targetConfig.getOutputDirectoryName()).doesNotContain("ST")
        Truth.assertThat(getArtifactPath(t1)).doesNotContain("-ST-")
        Truth.assertThat(getArtifactPath(t2)).contains("-ST-")
        Truth.assertThat(getArtifactPath(t3)).contains("-ST-")
    }

    @Test
    @Throws(Exception::class)
    fun transitionSetsInvalidValue_fails() {
        createStringSetFlag( /* defaultValues= */ImmutableList.of<kotlin.String?>(),  /* repeatable= */false)
        scratch.file(
            "pkg/def.bzl",
            """
        def _transition_impl(settings, attributes):
          return {'//flag:foo': ['ss', 123, 'set']}

        my_transition = transition(
          implementation = _transition_impl,
          inputs = [],
          outputs = ['//flag:foo'],
        )

        def _simple_impl(ctx):
          f = ctx.actions.declare_file("out")
          ctx.actions.write(f, "bar")
          return [DefaultInfo(files = depset([f]))]

        simple_rule = rule(
          implementation = _simple_impl,
          cfg = my_transition
        )
        
        """.trimIndent()
        )

        scratch.file(
            "pkg/BUILD",
            """
        load("//pkg:def.bzl", "simple_rule")
        simple_rule(
            name = "bar",
        )
        
        """.trimIndent()
        )

        val e = Assert.assertThrows<AssertionError?>(
            AssertionError::class.java,
            ThrowingRunnable { getConfiguredTarget("//pkg:bar") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("expected value of type 'string' for element 1 of //flag:foo, but got 123 (int)")
    }

    private fun getArtifactPath(ct: ConfiguredTarget?): kotlin.String {
        val artifact: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ActionsTestUtil.getFirstArtifactEndingWith(
                actionsTestUtil().artifactClosureOf(getFilesToBuild(ct)), "out"
            )
        return artifact.getExecPathString()
    }

    @Throws(Exception::class)
    private fun getComputedConfiguredTarget(label: kotlin.String?): ImmutableList<ConfiguredTarget> {
        return skyframeExecutor.getEvaluator().getDoneValues().entrySet().stream()
            .filter(
                { e ->
                    e.getKey() is ConfiguredTargetKey
                            && (e.getKey() as ConfiguredTargetKey).getLabel().toString().equals(label)
                })
            .map({ e -> (e.getValue() as ConfiguredTargetValue).getConfiguredTarget() })
            .collect(ImmutableList.toImmutableList<E?>())
    }
}
