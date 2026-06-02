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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.packages.Attribute.attr

/** Tests that check that dependency cycles are reported correctly.  */
@RunWith(JUnit4::class)
class CircularDependencyTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOneRuleCycle() {
        checkError(
            "cycle",
            "foo.g",  // error message
            selfEdgeMsg("//cycle:foo.g"),  // Rule
            "genrule(name = 'foo.g',",
            "        outs = ['Foo.java'],",
            "        srcs = ['foo.g'],",
            "        cmd = 'cat $(SRCS) > $<' )"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectPackageGroupCycle() {
        checkError(
            "cycle",
            "melon",
            selfEdgeMsg("//cycle:moebius"),
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "package_group(name='moebius', packages=[], includes=['//cycle:moebius'])",
            "foo_library(name='melon', visibility=[':moebius'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testThreeLongPackageGroupCycle() {
        val expectedEvent: java.util.regex.Pattern =
            java.util.regex.Pattern.compile(
                ("cycle in dependency graph:\n"
                        + "    //cycle:superman \\([a-f0-9]+\\)\n"
                        + ".-> //cycle:rock \\(null\\)\n"
                        + "|   //cycle:paper \\(null\\)\n"
                        + "|   //cycle:scissors \\(null\\)\n"
                        + "`-- //cycle:rock \\(null\\)")
            )
        checkError(
            "cycle",
            "superman",
            expectedEvent,
            "# dummy line",
            "package_group(name='paper', includes=['//cycle:scissors'])",
            "package_group(name='rock', includes=['//cycle:paper'])",
            "package_group(name='scissors', includes=['//cycle:rock'])",
            "filegroup(name='superman', visibility=[':rock'])"
        )

        val foundEvent: com.google.devtools.build.lib.events.Event = assertContainsEvent(expectedEvent)
        Truth.assertThat(foundEvent.getLocation().toString()).isEqualTo("/workspace/cycle/BUILD:3:14")
    }

    /** Test to detect implicit input/output file overlap in rules.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOneRuleImplicitCycleJava() {
        val pkg: Package =
            createScratchPackageForImplicitCycle(
                "cycle",
                "load('@rules_java//java:defs.bzl', 'java_library')",
                "java_library(name='jcyc',",
                "      srcs = ['libjcyc.jar', 'foo.java'])"
            )
        org.junit.Assert.assertThrows<T?>(
            NoSuchTargetException::class.java,
            org.junit.function.ThrowingRunnable { pkg.getTarget("jcyc") })
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent("rule 'jcyc' has file 'libjcyc.jar' as both an" + " input and an output")
    }

    /**
     * Test not to detect implicit input/output file overlap in rules, when coming from a different
     * package.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInputOutputConflictDifferentPackage() {
        val pkg: Package =
            createScratchPackageForImplicitCycle(
                "googledata/xxx",
                "genrule(name='geo',",
                "    srcs = ['//googledata/geo:geo_info.txt'],",
                "    outs = ['geoinfo.txt'],",
                "    cmd = '$(SRCS) > $@')"
            )
        assertThat(pkg.containsErrors()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTwoRuleCycle() {
        scratchRule(
            "b",
            "rule2",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='rule2',",
            "           deps=['//a:rule1'])"
        )

        checkError(
            "a",
            "rule1",
            java.util.regex.Pattern.compile(
                ("in cc_library rule //a:rule1: cycle in dependency graph:\n"
                        + ".-> //a:rule1 \\([a-f0-9]+\\)\n"
                        + "|   //b:rule2 \\([a-f0-9]+\\)\n"
                        + "`-- //a:rule1 \\([a-f0-9]+\\)")
            ),
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='rule1',",
            "           deps=['//b:rule2'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTwoRuleCycle2() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        scratch.file(
            "x/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "x",
            deps = ["y"],
        )

        java_library(
            name = "y",
            deps = ["x"],
        )
        
        """.trimIndent()
        )
        getConfiguredTarget("//x")
        assertContainsEvent("in java_library rule //x:x: cycle in dependency graph")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIndirectOneRuleCycle() {
        scratchRule(
            "cycle",
            "foo.h",
            "genrule(name = 'foo.h',",
            "      outs = ['bar.h'],",
            "      srcs = ['foo.h'],",
            "      cmd = 'cp $< $@')"
        )
        checkError(
            "main",
            "mygenrule",  // error message
            selfEdgeMsg("//cycle:foo.h"),  // Rule
            "genrule(name='mygenrule',",
            "      outs = ['baz.h'],",
            "      srcs = ['//cycle:foo.h'],",
            "      cmd = 'cp $< $@')"
        )
    }

    private fun selfEdgeMsg(label: String?): java.util.regex.Pattern {
        return java.util.regex.Pattern.compile(label + " \\([a-f0-9]+|null\\) \\[self-edge\\]")
    }

    // Regression test for: "IllegalStateException in
    // AbstractConfiguredTarget.initialize()".
    // Failure to mark all cycle-forming nodes when there are *two* cycles led to
    // an attempt to initialise a node we'd already visited.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTwoCycles() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        scratch.file(
            "x/BUILD",
            """
        genrule(
            name = "b",
            srcs = ["c"],
            outs = ["b.out"],
            cmd = ":",
            tools = ["c"],
        )

        genrule(
            name = "c",
            srcs = ["b.out"],
            outs = [],
            cmd = ":",
        )
        
        """.trimIndent()
        )
        getConfiguredTarget("//x:b") // doesn't crash!
        assertContainsEvent("cycle in dependency graph")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectCycle() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "x/BUILD",
            """
        load("//x:x.bzl", "aspected", "plain")

        # Using data= makes the dependency graph clearer because then the aspect does not propagate
        # from aspectdep through a to b (and c)
        plain(
            name = "a",
            noaspect_deps = [":b"],
        )

        aspected(
            name = "b",
            aspect_deps = ["c"],
        )

        plain(name = "c")

        plain(
            name = "aspectdep",
            aspect_deps = ["a"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "x/x.bzl",
            """
        def _impl(ctx):
            return []

        rule_aspect = aspect(
            implementation = _impl,
            attr_aspects = ["aspect_deps"],
            attrs = {"_implicit": attr.label(default = Label("//x:aspectdep"))},
        )

        plain = rule(
            implementation = _impl,
            attrs = {"aspect_deps": attr.label_list(), "noaspect_deps": attr.label_list()},
        )

        aspected = rule(
            implementation = _impl,
            attrs = {"aspect_deps": attr.label_list(aspects = [rule_aspect])},
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//x:a")
        assertContainsEvent("cycle in dependency graph")
        assertContainsEvent("//x:c with aspect //x:x.bzl%rule_aspect")
    }

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder =
            Builder()
                .addRuleDefinition(NORMAL_DEPENDER)
                .addRuleDefinition(LATE_BOUND_DEPENDER)
                .addRuleDefinition(DEFINE_CLEARER)
        TestRuleClassProvider.addStandardRules(builder)
        return builder.build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLateBoundTargetCycleNotConfiguredTargetCycle() {
        // Target graph: //a -> //b -?> //c -> //a (loop)
        // Configured target graph: //a -> //b -> //c -> //a (2) -> //b (2)
        scratch.file("a/BUILD", "normal_dep(name = 'a', dep = '//b')")
        scratch.file("b/BUILD", "late_bound_dep(name = 'b', dep = '//c', define = 'CYCLE_ON')")
        scratch.file("c/BUILD", "define_clearer(name = 'c', dep = '//a', define = 'CYCLE_ON')")

        useConfiguration("--define=CYCLE_ON=yes")
        getConfiguredTarget("//a")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectTargetCycleNotConfiguredTargetCycle() {
        // Target graph: //a -> //b -?> //c -> //a (loop)
        // Configured target graph: //a -> //b -> //c -> //a (2) -> //b (2) -> //b:stop (2)
        scratch.file("a/BUILD", "normal_dep(name = 'a', dep = '//b')")
        scratch.file(
            "b/BUILD",
            """
        config_setting(
            name = "cycle",
            define_values = {"CYCLE_ON": "yes"},
        )

        normal_dep(name = "stop")

        normal_dep(
            name = "b",
            dep = select({
                ":cycle": "//c",
                "//conditions:default": ":stop",
            }),
        )
        
        """.trimIndent()
        )
        scratch.file("c/BUILD", "define_clearer(name = 'c', dep = '//a', define = 'CYCLE_ON')")

        useConfiguration("--define=CYCLE_ON=yes")
        getConfiguredTarget("//a")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidVisibility() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "rule1",
            visibility = ["//b:rule2"],
            deps = ["//b:rule2"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "b/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='rule2')"
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//a:rule1") })

        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("Label '//b:rule2' does not refer to a package group.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidVisibilityWithSelect() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "rule1",
            visibility = ["//b:rule2"],
            deps = ["//b:rule2"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "b/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        config_setting(
            name = "fastbuild",
            values = {"compilation_mode": "fastbuild"},
        )

        cc_library(
            name = "rule2",
            hdrs = select({
                ":fastbuild": glob(
                    [
                        "*.h",
                    ],
                    allow_empty = True,
                ),
            }),
        )
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//a:rule1") })

        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("Label '//b:rule2' does not refer to a package group.")
    }

    companion object {
        /** A late bound dependency which depends on the 'dep' label if the 'define' is in --defines.  */ // TODO(b/65746853): provide a way to do this without passing the entire configuration
        private val LATE_BOUND_DEP: LabelLateBoundDefault<BuildConfigurationValue?>? =
            LabelLateBoundDefault.fromTargetConfiguration(
                BuildConfigurationValue::class.java,
                null,
                { rule, attributes, config ->
                    if (config.getCommandLineBuildVariables().containsKey(attributes.get("define", STRING)))
                        attributes.get("dep", NODEP_LABEL)
                    else
                        null
                })

        /** A rule which always depends on the given label.  */
        private val NORMAL_DEPENDER: MockRule =
            MockRule { MockRule.define("normal_dep", attr("dep", LABEL).allowedFileTypes()) }

        /** A rule which depends on a given label only if the given define is set.  */
        private val LATE_BOUND_DEPENDER: MockRule = MockRule {
            MockRule.define(
                "late_bound_dep",
                attr("define", STRING).mandatory(),
                attr("dep", NODEP_LABEL).mandatory(),
                attr(":late_bound_dep", LABEL).value(LATE_BOUND_DEP)
            )
        }

        /** A rule which removes a define from the configuration of its dependency.  */
        private val DEFINE_CLEARER: MockRule = MockRule {
            MockRule.define(
                "define_clearer",
                attr("define", STRING).mandatory(),
                attr("dep", LABEL)
                    .mandatory()
                    .allowedFileTypes()
                    .cfg(
                        object : TransitionFactory() {
                            public override fun create(data: AttributeTransitionData): SplitTransition? {
                                return@MockRule object : SplitTransition() {
                                    public override fun requiresOptionFragments(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> {
                                        return@MockRule com.google.common.collect.ImmutableSet.of<E?>(CoreOptions::class.java)
                                    }

                                    public override fun split(
                                        options: BuildOptionsView,
                                        eventHandler: com.google.devtools.build.lib.events.EventHandler?
                                    ): MutableMap<String?, BuildOptions?> {
                                        val define: String? = data.attributes().get("define", STRING)
                                        val newOptions: BuildOptionsView = options.clone()
                                        val optionsFragment: CoreOptions = newOptions.get(CoreOptions::class.java)
                                        optionsFragment.setCommandLineBuildVariables(
                                            optionsFragment.getCommandLineBuildVariables().stream()
                                                .filter({ pair -> !pair.getKey().equals(define) })
                                                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
                                        )
                                        return@MockRule com.google.common.collect.ImmutableMap.of<K?, V?>(
                                            "define_cleaner",
                                            newOptions.underlying()
                                        )
                                    }
                                }
                            }

                            public override fun transitionType(): TransitionType {
                                return@MockRule TransitionType.ATTRIBUTE
                            }

                            public override fun isSplit(): Boolean {
                                return@MockRule true
                            }
                        })
            )
        }
    }
}
