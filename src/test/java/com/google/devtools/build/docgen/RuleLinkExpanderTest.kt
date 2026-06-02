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
package com.google.devtools.build.docgen

import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [RuleLinkExpander].  */
@RunWith(JUnit4::class)
class RuleLinkExpanderTest {
    private var multiPageExpander: RuleLinkExpander? = null
    private var singlePageExpander: RuleLinkExpander? = null

    @Before
    fun setUp() {
        val index: MutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.builder<String?, String?>()
                .put("cc_library", "c-cpp")
                .put("cc_binary", "c-cpp")
                .put("java_binary", "java")
                .put("Fileset", "fileset")
                .put("proto_library", "protocol-buffer")
                .buildOrThrow()
        val linkMap: DocLinkMap =
            DocLinkMap( /* beRoot= */
                "",
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "make-variables",
                    "make-variables.html",
                    "common-definitions",
                    "common-definitions.html",
                    "standalone",
                    "standalone.html"
                ),  /* sourceUrlRoot= */
                "",
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        multiPageExpander = RuleLinkExpander(index, false, linkMap)
        singlePageExpander = RuleLinkExpander(index, true, linkMap)
    }

    private fun checkExpandSingle(docs: String?, expected: String?) {
        assertThat(singlePageExpander.expand(docs)).isEqualTo(expected)
    }

    private fun checkExpandMulti(docs: String?, expected: String?) {
        assertThat(multiPageExpander.expand(docs)).isEqualTo(expected)
    }

    @org.junit.Test
    fun testRule() {
        checkExpandMulti(
            "<a href=\"\${link java_binary}\">java_binary rule</a>",
            "<a href=\"java.html#java_binary\">java_binary rule</a>"
        )
        checkExpandSingle(
            "<a href=\"\${link java_binary}\">java_binary rule</a>",
            "<a href=\"#java_binary\">java_binary rule</a>"
        )
    }

    @org.junit.Test
    fun testRuleAndAttribute() {
        checkExpandMulti(
            "<a href=\"\${link java_binary.runtime_deps}\">runtime_deps attribute</a>",
            "<a href=\"java.html#java_binary.runtime_deps\">runtime_deps attribute</a>"
        )
        checkExpandSingle(
            "<a href=\"\${link java_binary.runtime_deps}\">runtime_deps attribute</a>",
            "<a href=\"#java_binary.runtime_deps\">runtime_deps attribute</a>"
        )
    }

    @org.junit.Test
    fun testUpperCaseRule() {
        checkExpandMulti(
            "<a href=\"\${link Fileset.entries}\">entries</a>",
            "<a href=\"fileset.html#Fileset.entries\">entries</a>"
        )
        checkExpandSingle(
            "<a href=\"\${link Fileset.entries}\">entries</a>",
            "<a href=\"#Fileset.entries\">entries</a>"
        )
    }

    @org.junit.Test
    fun testRuleExamples() {
        checkExpandMulti(
            "<a href=\"\${link cc_binary_examples}\">examples</a>",
            "<a href=\"c-cpp.html#cc_binary_examples\">examples</a>"
        )
        checkExpandSingle(
            "<a href=\"\${link cc_binary_examples}\">examples</a>",
            "<a href=\"#cc_binary_examples\">examples</a>"
        )
    }

    @org.junit.Test
    fun testRuleArgs() {
        checkExpandMulti(
            "<a href=\"\${link cc_binary_args}\">args</a>",
            "<a href=\"c-cpp.html#cc_binary_args\">args</a>"
        )
        checkExpandSingle(
            "<a href=\"\${link cc_binary_args}\">args</a>",
            "<a href=\"#cc_binary_args\">args</a>"
        )
    }

    @org.junit.Test
    fun testRuleImplicitOutputsj() {
        checkExpandMulti(
            "<a href=\"\${link cc_binary_implicit_outputs}\">args</a>",
            "<a href=\"c-cpp.html#cc_binary_implicit_outputs\">args</a>"
        )
        checkExpandSingle(
            "<a href=\"\${link cc_binary_implicit_outputs}\">args</a>",
            "<a href=\"#cc_binary_implicit_outputs\">args</a>"
        )
    }

    @org.junit.Test
    fun testStaticPageRef_pageReplacedBySinglePageBE() {
        checkExpandMulti(
            "<a href=\"\${link common-definitions}\">Common Definitions</a>",
            "<a href=\"common-definitions.html\">Common Definitions</a>"
        )
        checkExpandSingle(
            "<a href=\"\${link common-definitions}\">Common Definitions</a>",
            "<a href=\"#common-definitions\">Common Definitions</a>"
        )
    }

    @org.junit.Test
    fun testStaticPageRef_separatePage() {
        checkExpandMulti(
            "<a href=\"\${link standalone}\">standalone</a>",
            "<a href=\"standalone.html\">standalone</a>"
        )
        checkExpandSingle(
            "<a href=\"\${link standalone}\">standalone</a>",
            "<a href=\"standalone.html\">standalone</a>"
        )
    }

    @org.junit.Test
    fun testRefNotFound() {
        val docs = "<a href=\"\${link foo.bar}\">bar</a>"
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                multiPageExpander.expand(docs)
            })
    }

    @org.junit.Test
    fun testIncorrectStaticPageHeadingLink() {
        val docs = "<a href=\"\${link common-definitions.label-expansion}\">Label Expansion</a>"
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                multiPageExpander.expand(docs)
            })
    }

    @org.junit.Test
    fun testRuleHeadingLink() {
        checkExpandMulti(
            "<a href=\"\${link cc_library#alwayslink_lib_example}\">examples</a>",
            "<a href=\"c-cpp.html#alwayslink_lib_example\">examples</a>"
        )
        checkExpandSingle(
            "<a href=\"\${link cc_library#alwayslink_lib_example}\">examples</a>",
            "<a href=\"#alwayslink_lib_example\">examples</a>"
        )
    }

    @org.junit.Test
    fun testStaticPageHeadingLink_pageReplacedBySinglePageBE() {
        checkExpandMulti(
            "<a href=\"\${link make-variables#predefined_variables.genrule.cmd}\">genrule cmd</a>",
            "<a href=\"make-variables.html#predefined_variables.genrule.cmd\">genrule cmd</a>"
        )
        checkExpandSingle(
            "<a href=\"\${link make-variables#predefined_variables.genrule.cmd}\">genrule cmd</a>",
            "<a href=\"#predefined_variables.genrule.cmd\">genrule cmd</a>"
        )
    }

    @org.junit.Test
    fun testStaticPageHeadingLink_separatePage() {
        checkExpandMulti(
            "<a href=\"\${link standalone#foobar}\">standalone</a>",
            "<a href=\"standalone.html#foobar\">standalone</a>"
        )
        checkExpandSingle(
            "<a href=\"\${link standalone#foobar}\">standalone</a>",
            "<a href=\"standalone.html#foobar\">standalone</a>"
        )
    }

    @org.junit.Test
    fun testExpandRef() {
        assertThat(multiPageExpander.expandRef("java_binary.runtime_deps"))
            .isEqualTo("java.html#java_binary.runtime_deps")
        assertThat(singlePageExpander.expandRef("java_binary.runtime_deps"))
            .isEqualTo("#java_binary.runtime_deps")
    }

    @org.junit.Test
    fun testExcplicitBuildEncyclopediaRoot() {
        val linkMap: DocLinkMap =
            DocLinkMap( /* beRoot= */
                "/be_root",
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* sourceUrlRoot= */
                "",
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        val expander: RuleLinkExpander =
            RuleLinkExpander(com.google.common.collect.ImmutableMap.of<K?, V?>("java_binary", "java"), false, linkMap)

        assertThat(expander.expand("<a href=\"\${link java_binary}\">java_binary rule</a>"))
            .isEqualTo("<a href=\"/be_root/java.html#java_binary\">java_binary rule</a>")
    }
}
