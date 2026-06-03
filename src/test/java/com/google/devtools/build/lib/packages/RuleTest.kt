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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.Attribute.attr

/** Tests for [Rule].  */
@RunWith(JUnit4::class)
class RuleTest : PackageLoadingTestCase() {
    val extraRules: com.google.common.collect.ImmutableList<RuleDefinition?>
        get() = com.google.common.collect.ImmutableList.of<RuleDefinition?>(
            FAKE_CC_LIBRARY,
            FAKE_CC_BINARY,
            FAKE_CC_TEST
        )

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputNameError() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "namecollide/BUILD",
            """
        genrule(
            name = "hello_world",
            srcs = ["ignore_me.txt"],
            outs = [
                "message.txt",
                "hello_world",
            ],
            cmd = 'echo "Hello, world." >${'$'}(location message.txt)',
        )
        
        """.trimIndent()
        )
        val genRule: Rule = getTarget("//namecollide:hello_world") as Rule
        assertThat(genRule.containsErrors()).isFalse() // TODO: assertTrue
        assertContainsEvent(
            "target 'hello_world' is both a rule and a file; please choose another name for the rule",
            com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.events.EventKind?>(com.google.devtools.build.lib.events.EventKind.WARNING)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsLocalTestRuleForLocalEquals1() {
        scratch.file(
            "x/BUILD",
            """
        fake_cc_test(
            name = "y",
            srcs = ["a"],
            local = 0,
        )

        fake_cc_test(
            name = "z",
            srcs = ["a"],
            local = 1,
        )
        
        """.trimIndent()
        )
        val y: Rule? = getTarget("//x:y") as Rule?
        assertThat(TargetUtils.isLocalTestRule(y)).isFalse()
        val z: Rule? = getTarget("//x:z") as Rule?
        assertThat(TargetUtils.isLocalTestRule(z)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeprecation() {
        scratch.file(
            "x/BUILD",
            """
        fake_cc_test(name = "y")

        fake_cc_test(
            name = "z",
            deprecation = "Foo",
        )
        
        """.trimIndent()
        )
        val y: Rule? = getTarget("//x:y") as Rule?
        assertThat(TargetUtils.getDeprecation(y)).isNull()
        val z: Rule? = getTarget("//x:z") as Rule?
        assertThat(TargetUtils.getDeprecation(z)).isEqualTo("Foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVisibilityValid() {
        scratch.file(
            "x/BUILD",
            """
        fake_cc_binary(
            name = "pr",
            visibility = ["//visibility:private"],
        )

        fake_cc_binary(
            name = "pu",
            visibility = ["//visibility:public"],
        )

        fake_cc_binary(
            name = "cu",
            visibility = ["//a:b"],
        )
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("x")
        assertThat(pkg.getRule("pu").getVisibility()).isEqualTo(RuleVisibility.PUBLIC)
        assertThat(pkg.getRule("pr").getVisibility()).isEqualTo(RuleVisibility.PRIVATE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVisibilityTypo_failsCleanly() {
        scratch.file(
            "x/BUILD",
            """
        fake_cc_binary(
            name = "typo",
            visibility = ["//visibility:none"],
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val pkg: java.lang.Package = getPackage("x")
        assertContainsEvent(
            "Invalid visibility label '//visibility:none'; did you mean //visibility:public or"
                    + " //visibility:private?"
        )
        assertThat(pkg.containsErrors()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVisibilityTypo_whenVisibilityPackageExists_failsCleanly() {
        scratch.file(
            "visibility/BUILD",
            """
        fake_cc_binary(
            name = "none",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "x/BUILD",
            """
        fake_cc_binary(
            name = "typo",
            visibility = ["//visibility:none"],
        )
        
        """.trimIndent()
        )
        assertThat(getPackage("visibility").containsErrors()).isFalse()
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val pkg: java.lang.Package = getPackage("x")
        assertContainsEvent(
            "Invalid visibility label '//visibility:none'; did you mean //visibility:public or"
                    + " //visibility:private?"
        )
        assertThat(pkg.containsErrors()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVisibilityPkgSubpackages_whenVisibilityPackageExists_succeeds() {
        scratch.file(
            "visibility/BUILD",
            """
        fake_cc_binary(
            name = "none",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "x/BUILD",
            """
        fake_cc_binary(
            name = "p",
            visibility = ["//visibility:__pkg__"],
        )

        fake_cc_binary(
            name = "s",
            visibility = ["//visibility:__subpackages__"],
        )
        
        """.trimIndent()
        )
        assertThat(getPackage("visibility").containsErrors()).isFalse()
        val pkg: java.lang.Package = getPackage("x")
        assertThat(pkg.containsErrors()).isFalse()
        assertThat(pkg.getRule("p").getVisibility().getDeclaredLabels())
            .containsExactly(Label.parseCanonicalUnchecked("//visibility:__pkg__"))
        assertThat(pkg.getRule("s").getVisibility().getDeclaredLabels())
            .containsExactly(Label.parseCanonicalUnchecked("//visibility:__subpackages__"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVisibilityMisspelling() {
        scratch.file(
            "x/BUILD",
            """
        fake_cc_binary(
            name = "is_this_public",
            visibility = ["//visibility:plubic"],
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val pkg: java.lang.Package = getPackage("x")
        assertContainsEvent(
            "Invalid visibility label '//visibility:plubic'; did you mean //visibility:public or"
                    + " //visibility:private?"
        )
        assertThat(pkg.containsErrors()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPublicAndPrivateVisibility() {
        scratch.file(
            "x/BUILD",
            """
        package(default_visibility = ["//default:__pkg__"])

        fake_cc_binary(
            name = "is_this_public",
            visibility = ["//some:__pkg__", "//visibility:public"],
        )

        fake_cc_binary(
            name = "is_private_dropped",
            visibility = ["//some:__pkg__", "//visibility:private"],
        )

        fake_cc_binary(
            name = "is_empty_visibility_private",
            visibility = [],
        )
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("x")
        assertThat(pkg.containsErrors()).isFalse()
        assertThat(pkg.getRule("is_this_public").getVisibility().getDeclaredLabels())
            .containsExactly(Label.parseCanonicalUnchecked("//visibility:public"))
        assertThat(pkg.getRule("is_private_dropped").getVisibility().getDeclaredLabels())
            .containsExactly(Label.parseCanonicalUnchecked("//some:__pkg__"))
        assertThat(pkg.getRule("is_empty_visibility_private").getVisibility().getDeclaredLabels())
            .containsExactly(Label.parseCanonicalUnchecked("//visibility:private"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReduceForSerialization() {
        scratch.file(
            "x/BUILD",
            """
        fake_cc_library(
            name = "dep",
            deprecation = "message should serialize",
        )

        fake_cc_test(
            name = "y",
            srcs = ["a"],
            deps = [":dep"],
        )

        fake_cc_binary(
            name = "cu",
            visibility = ["//a:b"],
        )

        genrule(
            name = "hello_world",
            srcs = ["ignore_me.txt"],
            outs = ["message.txt"],
            cmd = 'echo "Hello, world." >message.txt',
        )
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("x")

        val testDep: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            pkg.getRule("dep")
        assertThat(testDep).hasSamePropertiesAs(roundTrip(testDep))

        val testRule: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = pkg.getRule("y")
        assertThat(testRule).hasSamePropertiesAs(roundTrip(testRule))

        val ccBinaryRule: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            pkg.getRule("cu")
        assertThat(ccBinaryRule).hasSamePropertiesAs(roundTrip(ccBinaryRule))

        // Covers the case of a native rule.
        val genruleRule: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            pkg.getRule("hello_world")
        assertThat(genruleRule).hasSamePropertiesAs(roundTrip(genruleRule))
    }

    @Throws(SerializationException::class, IOException::class)
    private fun roundTrip(target: Target): TargetData {
        return RoundTripping.roundTrip(
            target.reduceForSerialization(),
            com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(
                RuleClassProvider::class.java, skyframeExecutor.getRuleClassProviderForTesting()
            )
        )
    }

    companion object {
        private val FAKE_CC_LIBRARY: RuleDefinition =
            MockRule { MockRule.define("fake_cc_library", { builder, env -> }) } as MockRule

        private val FAKE_CC_BINARY: RuleDefinition = MockRule {
            MockRule.define(
                "fake_cc_binary",
                { builder, env -> builder.add(attr("srcs", LABEL_LIST).legacyAllowAnyFileType()) })
        } as MockRule

        private val FAKE_CC_TEST: RuleDefinition = MockRule {
            MockRule.ancestor(BaseRuleClasses.NativeBuildRule::class.java)
                .type(RuleClassType.TEST)
                .define(
                    "fake_cc_test",
                    { builder, env ->
                        builder
                            .add(attr("srcs", LABEL_LIST).legacyAllowAnyFileType())
                            .add(attr("deps", LABEL_LIST).legacyAllowAnyFileType())
                            .add(attr("size", STRING).nonconfigurable("policy").value("small"))
                            .add(attr("timeout", STRING).nonconfigurable("policy").value("short"))
                            .add(attr("flaky", BOOLEAN))
                            .add(attr("shard_count", INTEGER))
                            .add(attr("local", BOOLEAN).nonconfigurable("policy"))
                    })
        } as MockRule
    }
}
