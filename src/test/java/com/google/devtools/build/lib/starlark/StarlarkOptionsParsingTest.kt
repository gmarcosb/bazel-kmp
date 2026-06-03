// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlark

import com.google.devtools.build.lib.pkgcache.TargetParsingCompleteEvent

/** Unit test for the `StarlarkOptionsParser`.  */
@RunWith(TestParameterInjector::class)
class StarlarkOptionsParsingTest : StarlarkOptionsTestCase() {
    private var postedEvents: MutableList<Postable?>? = null

    @Before
    fun addPostableEventHandler() {
        postedEvents = java.util.ArrayList<Postable?>()
        reporter.addHandler(
            object : ExtendedEventHandler {
                override fun post(obj: Postable?) {
                    postedEvents!!.add(obj)
                }

                override fun handle(event: com.google.devtools.build.lib.events.Event?) {}
            })
    }

    /** Returns only the posted events of the given class.  */
    private fun eventsOfType(clazz: java.lang.Class<out Postable?>?): MutableList<Postable?> {
        return postedEvents.stream()
            .filter { event: Postable? -> event.javaClass == clazz }
            .collect(Collectors.toList())
    }

    // test --flag=value
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagEqualsValueForm() {
        writeBasicIntFlag()

        val result: OptionsParsingResult = parseStarlarkOptions("--//test:my_int_setting=666")

        Truth.assertThat(result.getStarlarkOptions()).hasSize(1)
        Truth.assertThat(result.getStarlarkOptions().get("//test:my_int_setting"))
            .isEqualTo(StarlarkInt.of(666))
        Truth.assertThat(result.getResidue()).isEmpty()
    }

    // test --@main_workspace//flag=value parses out to //flag=value
    // test --@other_workspace//flag=value parses out to @other_workspace//flag=value
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagNameWithExternalRepo() {
        writeBasicIntFlag()
        scratch.file("test/repo2/MODULE.bazel", "module(name = 'repo2')")
        scratch.file(
            "test/repo2/defs.bzl",
            """
        def _impl(ctx):
            pass

        my_flag = rule(
            implementation = _impl,
            build_setting = config.int(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/repo2/BUILD",
            """
        load(":defs.bzl", "my_flag")

        my_flag(
            name = "flag2",
            build_setting_default = 2,
        )
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        module(name = "starlark_options_test")

        bazel_dep(name = "repo2")

        local_path_override(
            module_name = "repo2",
            path = "test/repo2",
        )
        
        """.trimIndent()
        )

        val result: OptionsParsingResult =
            parseStarlarkOptions(
                "--@starlark_options_test//test:my_int_setting=666 --@repo2//:flag2=222",  /* onlyStarlarkParser= */
                true
            )

        Truth.assertThat(result.getStarlarkOptions()).hasSize(2)
        Truth.assertThat(result.getStarlarkOptions().get("//test:my_int_setting"))
            .isEqualTo(StarlarkInt.of(666))
        Truth.assertThat(result.getStarlarkOptions().get("@@repo2+//:flag2")).isEqualTo(StarlarkInt.of(222))
        Truth.assertThat(result.getResidue()).isEmpty()
    }

    // test --fake_flag=value
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadFlag_equalsForm() {
        scratch.file("test/BUILD")
        reporter.removeHandler(failFastHandler)

        val e: OptionsParsingException =
            org.junit.Assert.assertThrows<OptionsParsingException>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseStarlarkOptions("--//fake_flag=blahblahblah") })

        Truth.assertThat(e).hasMessageThat().contains("Error loading option //fake_flag")
        assertThat(e.invalidArgument).isEqualTo("//fake_flag")
    }

    // test --fake_flag
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadFlag_boolForm() {
        scratch.file("test/BUILD")
        reporter.removeHandler(failFastHandler)

        val e: OptionsParsingException =
            org.junit.Assert.assertThrows<OptionsParsingException>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseStarlarkOptions("--//fake_flag") })

        Truth.assertThat(e).hasMessageThat().contains("Error loading option //fake_flag")
        assertThat(e.invalidArgument).isEqualTo("//fake_flag")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadFlag_keepGoing() {
        optionsParser.parse("--keep_going")
        scratch.file("test/BUILD")
        reporter.removeHandler(failFastHandler)

        val e: OptionsParsingException =
            org.junit.Assert.assertThrows<OptionsParsingException>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseStarlarkOptions("--//fake_flag") })

        Truth.assertThat(e).hasMessageThat().contains("Error loading option //fake_flag")
        assertThat(e.invalidArgument).isEqualTo("//fake_flag")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleDash_notAllowed() {
        writeBasicIntFlag()

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parseStarlarkOptions(
                        "-//test:my_int_setting=666",  /* onlyStarlarkParser= */
                        true
                    )
                })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Invalid options syntax: -//test:my_int_setting=666")
    }

    // test --non_flag_setting=value
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonFlagParsing() {
        scratch.file(
            "test/build_setting.bzl",
            """
        def _build_setting_impl(ctx):
            return []

        int_flag = rule(
            implementation = _build_setting_impl,
            build_setting = config.int(flag = False),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_setting.bzl", "int_flag")

        int_flag(
            name = "my_int_setting",
            build_setting_default = 42,
        )
        
        """.trimIndent()
        )

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseStarlarkOptions("--//test:my_int_setting=666") })

        Truth.assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: //test:my_int_setting=666")
    }

    // test --bool_flag
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBooleanFlag() {
        writeBasicBoolFlag()

        val result: OptionsParsingResult = parseStarlarkOptions("--//test:my_bool_setting=false")

        Truth.assertThat(result.getStarlarkOptions()).hasSize(1)
        Truth.assertThat(result.getStarlarkOptions().get("//test:my_bool_setting")).isEqualTo(false)
        Truth.assertThat(result.getResidue()).isEmpty()
    }

    // test --nobool_flag
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoPrefixedBooleanFlag() {
        writeBasicBoolFlag()

        val result: OptionsParsingResult = parseStarlarkOptions("--no//test:my_bool_setting")

        Truth.assertThat(result.getStarlarkOptions()).hasSize(1)
        Truth.assertThat(result.getStarlarkOptions().get("//test:my_bool_setting")).isEqualTo(false)
        Truth.assertThat(result.getResidue()).isEmpty()
    }

    // test --no@main_workspace//:bool_flag
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoPrefixedBooleanFlag_withWorkspace() {
        writeBasicBoolFlag()

        val result: OptionsParsingResult = parseStarlarkOptions("--no@//test:my_bool_setting")

        Truth.assertThat(result.getStarlarkOptions()).hasSize(1)
        Truth.assertThat(result.getStarlarkOptions().get("//test:my_bool_setting")).isEqualTo(false)
        Truth.assertThat(result.getResidue()).isEmpty()
    }

    // test --noint_flag
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoPrefixedNonBooleanFlag() {
        writeBasicIntFlag()

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseStarlarkOptions("--no//test:my_int_setting") })

        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo("Illegal use of 'no' prefix on non-boolean option: //test:my_int_setting")
    }

    // test --int_flag
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagWithoutValue() {
        writeBasicIntFlag()

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseStarlarkOptions("--//test:my_int_setting") })

        Truth.assertThat(e).hasMessageThat().isEqualTo("Expected value after --//test:my_int_setting")
    }

    // test --flag --flag
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepeatFlagLastOneWins() {
        writeBasicIntFlag()

        val result: OptionsParsingResult =
            parseStarlarkOptions("--//test:my_int_setting=4 --//test:my_int_setting=7")

        Truth.assertThat(result.getStarlarkOptions()).hasSize(1)
        Truth.assertThat(result.getStarlarkOptions().get("//test:my_int_setting"))
            .isEqualTo(StarlarkInt.of(7))
        Truth.assertThat(result.getResidue()).isEmpty()
    }

    // test --flagA=valueA --flagB=valueB
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleFlags() {
        scratch.file(
            "test/build_setting.bzl",
            """
        def _build_setting_impl(ctx):
            return []

        int_flag = rule(
            implementation = _build_setting_impl,
            build_setting = config.int(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_setting.bzl", "int_flag")

        int_flag(
            name = "my_int_setting",
            build_setting_default = 42,
        )

        int_flag(
            name = "my_other_int_setting",
            build_setting_default = 77,
        )
        
        """.trimIndent()
        )

        val result: OptionsParsingResult =
            parseStarlarkOptions("--//test:my_int_setting=0 --//test:my_other_int_setting=0")

        Truth.assertThat(result.getResidue()).isEmpty()
        Truth.assertThat(result.getStarlarkOptions()).hasSize(2)
        Truth.assertThat(result.getStarlarkOptions().get("//test:my_int_setting"))
            .isEqualTo(StarlarkInt.of(0))
        Truth.assertThat(result.getStarlarkOptions().get("//test:my_other_int_setting"))
            .isEqualTo(StarlarkInt.of(0))
    }

    // test --non_build_setting
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonBuildSetting() {
        scratch.file(
            "test/rules.bzl",
            """
        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "my_rule")
        
        """.trimIndent()
        )
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseStarlarkOptions("--//test:my_rule") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: //test:my_rule")
    }

    // test --non_rule_configured_target
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonRuleConfiguredTarget() {
        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = "my_gen",
            srcs = ["x.in"],
            outs = ["x.cc"],
            cmd = "${'$'}(locations :tool) ${'$'}< >${'$'}@",
            tools = [":tool"],
        )

        filegroup(name = "tool-dep")
        
        """.trimIndent()
        )
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseStarlarkOptions("--//test:x.in") })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Unrecognized option: //test:x.in")
    }

    // test --int_flag=non_int_value
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWrongValueType_int() {
        writeBasicIntFlag()

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseStarlarkOptions("--//test:my_int_setting=woohoo") })

        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo("While parsing option //test:my_int_setting=woohoo: 'woohoo' is not a int")
    }

    // test --bool_flag=non_bool_value
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWrongValueType_bool() {
        writeBasicBoolFlag()

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseStarlarkOptions("--//test:my_bool_setting=woohoo") })

        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo("While parsing option //test:my_bool_setting=woohoo: 'woohoo' is not a boolean")
    }

    // test --int-flag=same value as default
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDontStoreDefaultValue() {
        // build_setting_default = 42
        writeBasicIntFlag()

        val result: OptionsParsingResult = parseStarlarkOptions("--//test:my_int_setting=42")

        Truth.assertThat(result.getStarlarkOptions()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOptionsAreParsedWithBuildTestsOnly() {
        writeBasicIntFlag()
        optionsParser.parse("--build_tests_only")

        val result: OptionsParsingResult = parseStarlarkOptions("--//test:my_int_setting=15")

        Truth.assertThat(result.getStarlarkOptions().get("//test:my_int_setting"))
            .isEqualTo(StarlarkInt.of(15))
    }

    /**
     * When Starlark flags are only set as flags, they shouldn't produce [ ]s. That's intended to communicate (to the build event protocol)
     * which of the targets in `blaze build //foo:all //bar:all` were built.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpectedBuildEventOutput_asFlag() {
        writeBasicIntFlag()
        scratch.file(
            "blah/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'mylib')"
        )
        useConfiguration("--//test:my_int_setting=15")
        update(
            com.google.common.collect.ImmutableList.of<String?>("//blah:mylib"),  /*keepGoing=*/
            false,  /*loadingPhaseThreads=*/
            BuildViewTestCase.Companion.LOADING_PHASE_THREADS,  /*doAnalysis*/
            true,
            eventBus
        )
        val targetParsingCompleteEvents: MutableList<Postable?> = eventsOfType(TargetParsingCompleteEvent::class.java)
        Truth.assertThat(targetParsingCompleteEvents).hasSize(1)
        assertThat(
            (targetParsingCompleteEvents.get(0) as TargetParsingCompleteEvent)
                .getOriginalTargetPattern()
        )
            .containsExactly("//blah:mylib")
    }

    /**
     * But Starlark are also targets. When they're requested as normal build targets they should
     * produce [TargetParsingCompleteEvent] just like any other target.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpectedBuildEventOutput_asTarget() {
        writeBasicIntFlag()
        scratch.file(
            "blah/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'mylib')"
        )
        useConfiguration("--//test:my_int_setting=15")
        update(
            com.google.common.collect.ImmutableList.of<String?>(
                "//blah:mylib",
                "//test:my_int_setting"
            ),  /*keepGoing=*/
            false,  /*loadingPhaseThreads=*/
            BuildViewTestCase.Companion.LOADING_PHASE_THREADS,  /*doAnalysis*/
            true,
            eventBus
        )
        val targetParsingCompleteEvents: MutableList<Postable?> = eventsOfType(TargetParsingCompleteEvent::class.java)
        Truth.assertThat(targetParsingCompleteEvents).hasSize(1)
        assertThat(
            (targetParsingCompleteEvents.get(0) as TargetParsingCompleteEvent)
                .getOriginalTargetPattern()
        )
            .containsExactly("//blah:mylib", "//test:my_int_setting")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowMultipleStringFlag() {
        scratch.file(
            "test/build_setting.bzl",
            """
        def _build_setting_impl(ctx):
            return []

        allow_multiple_flag = rule(
            implementation = _build_setting_impl,
            build_setting = config.string(flag = True, allow_multiple = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_setting.bzl", "allow_multiple_flag")

        allow_multiple_flag(
            name = "cats",
            build_setting_default = "tabby",
        )
        
        """.trimIndent()
        )

        val result: OptionsParsingResult = parseStarlarkOptions("--//test:cats=calico --//test:cats=bengal")

        Truth.assertThat(result.getStarlarkOptions().keys).containsExactly("//test:cats")
        Truth.assertThat(result.getStarlarkOptions().get("//test:cats") as MutableList<String?>?)
            .containsExactly("calico", "bengal")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepeatedStringListFlag() {
        scratch.file(
            "test/build_setting.bzl",
            """
        def _build_setting_impl(ctx):
            return []

        repeated_flag = rule(
            implementation = _build_setting_impl,
            build_setting = config.string_list(flag = True, repeatable = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_setting.bzl", "repeated_flag")

        repeated_flag(
            name = "cats",
            build_setting_default = ["tabby"],
        )
        
        """.trimIndent()
        )

        val result: OptionsParsingResult = parseStarlarkOptions("--//test:cats=calico --//test:cats=bengal")

        Truth.assertThat(result.getStarlarkOptions().keys).containsExactly("//test:cats")
        Truth.assertThat(result.getStarlarkOptions().get("//test:cats") as MutableList<String?>?)
            .containsExactly("calico", "bengal")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagReferencesExactlyOneTarget() {
        scratch.file(
            "test/build_setting.bzl",
            """
        string_flag = rule(
            implementation = lambda ctx, attr: [],
            build_setting = config.string(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_setting.bzl", "string_flag")

        string_flag(
            name = "one",
            build_setting_default = "",
        )

        string_flag(
            name = "two",
            build_setting_default = "",
        )
        
        """.trimIndent()
        )

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseStarlarkOptions("--//test:all") })

        Truth.assertThat(e)
            .hasMessageThat()
            .contains("//test:all: user-defined flags must reference exactly one target")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagIsAlias() {
        scratch.file(
            "test/build_setting.bzl",
            """
        string_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.string(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_setting.bzl", "string_flag")

        alias(
            name = "one",
            actual = "//test/pkg:two",
        )

        string_flag(
            name = "three",
            build_setting_default = "",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/pkg/BUILD",
            """
        alias(
            name = "two",
            actual = "//test:three",
        )
        
        """.trimIndent()
        )

        val result: OptionsParsingResult = parseStarlarkOptions("--//test:one=one --//test/pkg:two=two")

        Truth.assertThat(result.getStarlarkOptions()).containsExactly("//test:three", "two")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagIsAlias_cycle() {
        scratch.file(
            "test/BUILD",
            """
        alias(
            name = "one",
            actual = "//test/pkg:two",
        )

        alias(
            name = "three",
            actual = ":one",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/pkg/BUILD",
            """
        alias(
            name = "two",
            actual = "//test:three",
        )
        
        """.trimIndent()
        )

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseStarlarkOptions("--//test:one=one") })

        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "Failed to load build setting '//test:one' due to a cycle in alias chain: //test:one"
                        + " -> //test/pkg:two -> //test:three -> //test:one"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagIsAlias_usesSelect() {
        scratch.file(
            "test/BUILD",
            """
        alias(
            name = "one",
            actual = "//test/pkg:two",
        )

        alias(
            name = "three",
            actual = ":one",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/pkg/BUILD",
            """
        # Needed to avoid select() being eliminated as trivial.
        config_setting(
            name = "config",
            values = {"define": "pi=3"},
        )

        alias(
            name = "two",
            actual = select({
                ":config": "//test:three",
                "//conditions:default": "//test:three",
            }),
        )
        
        """.trimIndent()
        )

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseStarlarkOptions("--//test:one=one") })

        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                ("Failed to load build setting '//test:one' as it resolves to an alias with an actual"
                        + " value that uses select(): //test:one -> //test/pkg:two. This is not supported"
                        + " as build settings are needed to determine the configuration the select is"
                        + " evaluated in.")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagIsAlias_resolvesToNonBuildSettingTarget() {
        scratch.file(
            "test/BUILD",
            """
        alias(
            name = "one",
            actual = "//test/pkg:two",
        )

        genrule(
            name = "three",
            outs = ["out"],
            cmd = "echo hello > ${'$'}@",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/pkg/BUILD",
            """
        alias(
            name = "two",
            actual = "//test:three",
        )
        
        """.trimIndent()
        )

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseStarlarkOptions("--//test:one=one") })

        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo("Unrecognized option: //test:one -> //test/pkg:two -> //test:three")
    }

    @org.junit.Test
    @TestParameters( // Not repeatable, flag value is not the same as the default, flag added to options.
        ("{defaultValue: ['default'], repeatable: false, cmdValue:"
                + " ['v1,v4,v3,v2,v3,v1,v4,v1'], expectedValue: ['v1', 'v2', 'v3', 'v4']}") // Repeatable, flag value is not the same as the default, flag added to options.
        , ("{defaultValue: ['default'], repeatable: true, cmdValue: ['v2', 'v2', 'v1', 'v3', 'v4', 'v1'],"
                + " expectedValue: ['v1', 'v2', 'v3', 'v4']}") // Not repeatable, flag value is the same as the default, flag not added to options.
        , ("{defaultValue: ['v2','v1','v1', 'v3', 'v2', 'v3', 'v4'], repeatable: false, cmdValue:"
                + " ['v1,v4,v3,v2,v3,v1,v4,v1'], expectedValue: null}") // Repeatable, flag value is the same as the default, flag not added to options.
        , ("{defaultValue: ['v2','v1','v1', 'v3', 'v2', 'v3', 'v4'], repeatable: true, cmdValue: ['v2',"
                + " 'v2', 'v1', 'v3', 'v4', 'v1'], expectedValue: null}")
    )
    @Throws(java.lang.Exception::class)
    fun testStringSetFlag(
        defaultValue: MutableList<String?>,
        repeatable: Boolean,
        cmdValue: MutableList<String?>,
        expectedValue: MutableList<String?>?
    ) {
        scratch.file(
            "test/build_setting.bzl",
            String.format(
                """
            def _build_setting_impl(ctx):
                return []

            string_set_flag = rule(
                implementation = _build_setting_impl,
                build_setting = config.string_set(flag = True, repeatable = %s),
            )
            
            """.trimIndent(),
                if (repeatable) "True" else "False"
            )
        )
        scratch.file(
            "test/BUILD",
            String.format(
                """
            load("//test:build_setting.bzl", "string_set_flag")

            string_set_flag(
                name = "my_flag",
                build_setting_default = set([%s]),
            )
            
            """.trimIndent(),
                defaultValue.stream().map<String?> { v: String? -> String.format("'%s'", v) }
                    .collect(Collectors.joining(","))))

        val result: OptionsParsingResult =
            parseStarlarkOptions(
                cmdValue.stream()
                    .map<String?> { v: String? -> String.format("--//test:my_flag=%s", v) }
                    .collect(Collectors.joining(" ")))

        if (expectedValue == null) {
            Truth.assertThat(result.getStarlarkOptions()).isEmpty()
        } else {
            Truth.assertThat(result.getStarlarkOptions().keys).containsExactly("//test:my_flag")
            Truth.assertThat(result.getStarlarkOptions().get("//test:my_flag"))
                .isEqualTo(com.google.common.collect.ImmutableSet.copyOf<String?>(expectedValue))
        }
    }
}
