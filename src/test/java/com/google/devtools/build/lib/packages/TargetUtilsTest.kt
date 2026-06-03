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
package com.google.devtools.build.lib.packages

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.getTarget
import com.google.devtools.build.lib.packages.util.PackageLoadingTestCase
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Starlark
import net.starlark.java.syntax.Location.file
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Test for [TargetUtils]
 */
@RunWith(JUnit4::class)
class TargetUtilsTest : PackageLoadingTestCase() {
    @get:org.junit.Test
    val ruleLanguage: Unit
        get() {
            assertThat(TargetUtils.getRuleLanguage("java_binary")).isEqualTo("java")
            assertThat(TargetUtils.getRuleLanguage("foobar")).isEqualTo("foobar")
            assertThat(TargetUtils.getRuleLanguage("")).isEmpty()
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilterByTag() {
        scratch.file(
            "tests/BUILD",
            """
        load("//test_defs:foo_binary.bzl", "foo_binary")
        foo_binary(
            name = "tag1",
            srcs = ["sh.sh"],
            tags = ["tag1"],
        )

        foo_binary(
            name = "tag2",
            srcs = ["sh.sh"],
            tags = ["tag2"],
        )

        foo_binary(
            name = "tag1b",
            srcs = ["sh.sh"],
            tags = ["tag1"],
        )
        
        """.trimIndent()
        )

        val tag1: Target? = getTarget("//tests:tag1")
        val tag2: Target? = getTarget("//tests:tag2")
        val tag1b: Target? = getTarget("//tests:tag1b")

        var tagFilter: com.google.common.base.Predicate<Target?> = TargetUtils.tagFilter(java.util.ArrayList<String?>())
        Truth.assertThat(tagFilter.apply(tag1)).isTrue()
        Truth.assertThat(tagFilter.apply(tag2)).isTrue()
        Truth.assertThat(tagFilter.apply(tag1b)).isTrue()
        tagFilter = TargetUtils.tagFilter(com.google.common.collect.Lists.newArrayList<E?>("tag1", "tag2"))
        Truth.assertThat(tagFilter.apply(tag1)).isTrue()
        Truth.assertThat(tagFilter.apply(tag2)).isTrue()
        Truth.assertThat(tagFilter.apply(tag1b)).isTrue()
        tagFilter = TargetUtils.tagFilter(com.google.common.collect.Lists.< E > newArrayList < E ? > ("tag1"))
        Truth.assertThat(tagFilter.apply(tag1)).isTrue()
        Truth.assertThat(tagFilter.apply(tag2)).isFalse()
        Truth.assertThat(tagFilter.apply(tag1b)).isTrue()
        tagFilter = TargetUtils.tagFilter(com.google.common.collect.Lists.< E > newArrayList < E ? > ("-tag2"))
        Truth.assertThat(tagFilter.apply(tag1)).isTrue()
        Truth.assertThat(tagFilter.apply(tag2)).isFalse()
        Truth.assertThat(tagFilter.apply(tag1b)).isTrue()
        // Applying same tag as positive and negative filter produces an empty
        // result because the negative filter is applied first and positive filter will
        // not match anything.
        tagFilter = TargetUtils.tagFilter(com.google.common.collect.Lists.newArrayList<E?>("tag2", "-tag2"))
        Truth.assertThat(tagFilter.apply(tag1)).isFalse()
        Truth.assertThat(tagFilter.apply(tag2)).isFalse()
        Truth.assertThat(tagFilter.apply(tag1b)).isFalse()
        tagFilter = TargetUtils.tagFilter(com.google.common.collect.Lists.newArrayList<E?>("tag2", "-tag1"))
        Truth.assertThat(tagFilter.apply(tag1)).isFalse()
        Truth.assertThat(tagFilter.apply(tag2)).isTrue()
        Truth.assertThat(tagFilter.apply(tag1b)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionInfo() {
        scratch.file(
            "tests/BUILD",
            """
        load("//test_defs:foo_binary.bzl", "foo_binary")
        foo_binary(
            name = "tag1",
            srcs = ["sh.sh"],
            tags = [
                "no-cache",
                "supports-workers",
            ],
        )

        foo_binary(
            name = "tag2",
            srcs = ["sh.sh"],
            tags = ["disable-local-prefetch"],
        )

        foo_binary(
            name = "tag1b",
            srcs = ["sh.sh"],
            tags = [
                "cpu:4",
                "local",
            ],
        )
        
        """.trimIndent()
        )

        val tag1: Rule? = getTarget("//tests:tag1") as Rule?
        val tag2: Rule? = getTarget("//tests:tag2") as Rule?
        val tag1b: Rule? = getTarget("//tests:tag1b") as Rule?

        var execInfo: MutableMap<String?, String?>? = TargetUtils.getExecutionInfo(tag1)
        Truth.assertThat(execInfo).containsExactly("supports-workers", "", "no-cache", "")
        execInfo = TargetUtils.getExecutionInfo(tag2)
        Truth.assertThat(execInfo).containsExactly("disable-local-prefetch", "")
        execInfo = TargetUtils.getExecutionInfo(tag1b)
        Truth.assertThat(execInfo).containsExactly("local", "", "cpu:4", "")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionInfo_withPrefixSupports() {
        scratch.file(
            "tests/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'with-prefix-supports', srcs=['sh.sh'], tags=['supports-workers',"
                    + " 'supports-whatever', 'my-tag'])"
        )

        val withSupportsPrefix: Rule? = getTarget("//tests:with-prefix-supports") as Rule?

        val execInfo: MutableMap<String?, String?>? = TargetUtils.getExecutionInfo(withSupportsPrefix)
        Truth.assertThat(execInfo).containsExactly("supports-whatever", "", "supports-workers", "")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionInfo_withPrefixDisable() {
        scratch.file(
            "tests/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'with-prefix-disable', srcs=['sh.sh'], tags=['disable-local-prefetch',"
                    + " 'disable-something-else', 'another-tag'])"
        )

        val withDisablePrefix: Rule? = getTarget("//tests:with-prefix-disable") as Rule?

        val execInfo: MutableMap<String?, String?>? = TargetUtils.getExecutionInfo(withDisablePrefix)
        Truth.assertThat(execInfo)
            .containsExactly("disable-local-prefetch", "", "disable-something-else", "")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionInfo_withPrefixNo() {
        scratch.file(
            "tests/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'with-prefix-no', srcs=['sh.sh'], tags=['no-remote-imaginary-flag',"
                    + " 'no-sandbox', 'unknown'])"
        )

        val withNoPrefix: Rule? = getTarget("//tests:with-prefix-no") as Rule?

        val execInfo: MutableMap<String?, String?>? = TargetUtils.getExecutionInfo(withNoPrefix)
        Truth.assertThat(execInfo).containsExactly("no-remote-imaginary-flag", "", "no-sandbox", "")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionInfo_withPrefixRequires() {
        scratch.file(
            "tests/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'with-prefix-requires', srcs=['sh.sh'], tags=['requires-network',"
                    + " 'requires-sunlight', 'test-only'])"
        )

        val withRequiresPrefix: Rule? = getTarget("//tests:with-prefix-requires") as Rule?

        val execInfo: MutableMap<String?, String?>? = TargetUtils.getExecutionInfo(withRequiresPrefix)
        Truth.assertThat(execInfo).containsExactly("requires-network", "", "requires-sunlight", "")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionInfo_withPrefixBlock() {
        scratch.file(
            "tests/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'with-prefix-block', srcs=['sh.sh'], tags=['block-some-feature',"
                    + " 'block-network', 'wrong-tag'])"
        )

        val withBlockPrefix: Rule? = getTarget("//tests:with-prefix-block") as Rule?

        val execInfo: MutableMap<String?, String?>? = TargetUtils.getExecutionInfo(withBlockPrefix)
        Truth.assertThat(execInfo).containsExactly("block-network", "", "block-some-feature", "")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionInfo_withPrefixCpu() {
        scratch.file(
            "tests/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'with-prefix-cpu', srcs=['sh.sh'], tags=['cpu:123', 'wrong-tag'])"
        )

        val withCpuPrefix: Rule? = getTarget("//tests:with-prefix-cpu") as Rule?

        val execInfo: MutableMap<String?, String?>? = TargetUtils.getExecutionInfo(withCpuPrefix)
        Truth.assertThat(execInfo).containsExactly("cpu:123", "")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionInfo_withLocalTag() {
        scratch.file(
            "tests/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'with-local-tag', srcs=['sh.sh'], tags=['local', 'some-tag'])"
        )

        val withLocal: Rule? = getTarget("//tests:with-local-tag") as Rule?

        val execInfo: MutableMap<String?, String?>? = TargetUtils.getExecutionInfo(withLocal)
        Truth.assertThat(execInfo).containsExactly("local", "")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilteredExecutionInfo_fromUncheckedExecRequirements() {
        scratch.file(
            "tests/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'no-tag', srcs=['sh.sh'])"
        )

        val noTag: Rule? = getTarget("//tests:no-tag") as Rule?

        var execInfo: MutableMap<String?, String?>? =
            TargetUtils.getFilteredExecutionInfo(
                Dict.builder<String?, String?>().put("supports-worker", "1").buildImmutable(),
                noTag,  /* allowTagsPropagation */
                true
            )
        Truth.assertThat(execInfo).containsExactly("supports-worker", "1")

        execInfo =
            TargetUtils.getFilteredExecutionInfo(
                Dict.builder<String?, String?>()
                    .put("some-custom-tag", "1")
                    .put("no-cache", "1")
                    .buildImmutable(),
                noTag,  /* allowTagsPropagation */
                true
            )
        Truth.assertThat(execInfo).containsExactly("no-cache", "1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilteredExecutionInfo_fromUncheckedExecRequirements_withWorkerKeyMnemonic() {
        scratch.file(
            "tests/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'no-tag', srcs=['sh.sh'])"
        )

        val noTag: Rule? = getTarget("//tests:no-tag") as Rule?

        val execInfo: MutableMap<String?, String?>? =
            TargetUtils.getFilteredExecutionInfo(
                Dict.builder<String?, String?>()
                    .put("supports-workers", "1")
                    .put("worker-key-mnemonic", "MyMnemonic")
                    .buildImmutable(),
                noTag,  /* allowTagsPropagation */
                true
            )
        Truth.assertThat(execInfo)
            .containsExactly("supports-workers", "1", "worker-key-mnemonic", "MyMnemonic")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilteredExecutionInfo() {
        scratch.file(
            "tests/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'tag1', srcs=['sh.sh'], tags=['supports-workers', 'no-cache'])"
        )
        val tag1: Rule? = getTarget("//tests:tag1") as Rule?
        val executionRequirementsUnchecked: Dict<String?, String?>? =
            Dict.builder<String?, String?>().put("no-remote", "1").buildImmutable()

        val execInfo: MutableMap<String?, String?>? =
            TargetUtils.getFilteredExecutionInfo(
                executionRequirementsUnchecked, tag1,  /* allowTagsPropagation */true
            )

        Truth.assertThat(execInfo).containsExactly("no-cache", "", "supports-workers", "", "no-remote", "1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilteredExecutionInfo_withDuplicateTags() {
        scratch.file(
            "tests/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'tag1', srcs=['sh.sh'], tags=['supports-workers', 'no-cache'])"
        )
        val tag1: Rule? = getTarget("//tests:tag1") as Rule?
        val executionRequirementsUnchecked: Dict<String?, String?>? =
            Dict.builder<String?, String?>().put("no-cache", "1").buildImmutable()

        val execInfo: MutableMap<String?, String?>? =
            TargetUtils.getFilteredExecutionInfo(
                executionRequirementsUnchecked, tag1,  /* allowTagsPropagation */true
            )

        Truth.assertThat(execInfo).containsExactly("no-cache", "1", "supports-workers", "")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilteredExecutionInfo_withNullUncheckedExecRequirements() {
        scratch.file(
            "tests/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'tag1', srcs=['sh.sh'], tags=['supports-workers', 'no-cache'])"
        )
        val tag1: Rule? = getTarget("//tests:tag1") as Rule?

        var execInfo: MutableMap<String?, String?>? =
            TargetUtils.getFilteredExecutionInfo(null, tag1,  /* allowTagsPropagation */true)
        Truth.assertThat(execInfo).containsExactly("no-cache", "", "supports-workers", "")

        execInfo =
            TargetUtils.getFilteredExecutionInfo(Starlark.NONE, tag1,  /* allowTagsPropagation */true)
        Truth.assertThat(execInfo).containsExactly("no-cache", "", "supports-workers", "")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilteredExecutionInfo_whenIncompatibleFlagDisabled() {
        // when --incompatible_allow_tags_propagation=false
        scratch.file(
            "tests/BUILD",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'tag1', srcs=['sh.sh'], tags=['supports-workers', 'no-cache'])"
        )
        val tag1: Rule? = getTarget("//tests:tag1") as Rule?
        val executionRequirementsUnchecked: Dict<String?, String?>? =
            Dict.builder<String?, String?>().put("no-remote", "1").buildImmutable()

        val execInfo: MutableMap<String?, String?>? =
            TargetUtils.getFilteredExecutionInfo(
                executionRequirementsUnchecked, tag1,  /* allowTagsPropagation */false
            )

        Truth.assertThat(execInfo).containsExactly("no-remote", "1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionInfoMisc() {
        // Migrated from a removed test class that was focused on top-level build configuration.
        // TODO(anyone): remove tests here that are redundant w.r.t. the other tests in this file.
        scratch.file(
            "x/BUILD",
            """
        load("//test_defs:foo_binary.bzl", "foo_binary")
        load("//test_defs:foo_test.bzl", "foo_test")

        foo_test(
            name = "y",
            size = "small",
            srcs = ["a"],
            tags = [
                "exclusive",
                "local",
                "manual",
            ],
        )

        foo_test(
            name = "z",
            size = "small",
            srcs = ["a"],
            tags = [
                "othertag",
                "requires-feature2",
            ],
        )

        foo_test(
            name = "k",
            size = "small",
            srcs = ["a"],
            tags = ["requires-feature1"],
        )

        foo_test(
            name = "exclusive_if_local",
            size = "small",
            srcs = ["a"],
            tags = ["exclusive-if-local"],
        )

        foo_test(
            name = "exclusive_only",
            size = "small",
            srcs = ["a"],
            tags = ["exclusive"],
        )

        test_suite(
            name = "ts",
            tests = ["z"],
        )

        foo_binary(
            name = "x",
            srcs = [
                "a",
                "b",
                "c",
            ],
        )

        genrule(
            name = "gen1",
            srcs = [],
            outs = [
                "t1",
                "t2",
            ],
            cmd = "my cmd",
        )

        genrule(
            name = "gen2",
            srcs = ["liba.so"],
            outs = ["libnewa.so"],
            cmd = "my cmd",
        )
        
        """.trimIndent()
        )
        val x: Rule? = getTarget("//x:x") as Rule?
        assertThat(TargetUtils.isTestRule(x)).isFalse()
        val ts: Rule? = getTarget("//x:ts") as Rule?
        assertThat(TargetUtils.isTestRule(ts)).isFalse()
        assertThat(TargetUtils.isTestOrTestSuiteRule(ts)).isTrue()
        val z: Rule? = getTarget("//x:z") as Rule?
        assertThat(TargetUtils.isTestRule(z)).isTrue()
        assertThat(TargetUtils.isTestOrTestSuiteRule(z)).isTrue()
        assertThat(TargetUtils.isExclusiveTestRule(z)).isFalse()
        assertThat(TargetUtils.isExclusiveIfLocalTestRule(z)).isFalse()
        assertThat(TargetUtils.isLocalTestRule(z)).isFalse()
        assertThat(TargetUtils.hasManualTag(z)).isFalse()
        assertThat(TargetUtils.getExecutionInfo(z)).doesNotContainKey("requires-feature1")
        assertThat(TargetUtils.getExecutionInfo(z)).containsKey("requires-feature2")
        val k: Rule? = getTarget("//x:k") as Rule?
        assertThat(TargetUtils.isTestRule(k)).isTrue()
        assertThat(TargetUtils.isTestOrTestSuiteRule(k)).isTrue()
        assertThat(TargetUtils.isExclusiveTestRule(k)).isFalse()
        assertThat(TargetUtils.isExclusiveIfLocalTestRule(k)).isFalse()
        assertThat(TargetUtils.isLocalTestRule(k)).isFalse()
        assertThat(TargetUtils.hasManualTag(k)).isFalse()
        assertThat(TargetUtils.getExecutionInfo(k)).containsKey("requires-feature1")
        assertThat(TargetUtils.getExecutionInfo(k)).doesNotContainKey("requires-feature2")
        val y: Rule? = getTarget("//x:y") as Rule?
        assertThat(TargetUtils.isTestRule(y)).isTrue()
        assertThat(TargetUtils.isTestOrTestSuiteRule(y)).isTrue()
        assertThat(TargetUtils.isExclusiveTestRule(y)).isTrue()
        assertThat(TargetUtils.isExclusiveIfLocalTestRule(y)).isFalse()
        assertThat(TargetUtils.isLocalTestRule(y)).isTrue()
        assertThat(TargetUtils.hasManualTag(y)).isTrue()
        assertThat(TargetUtils.getExecutionInfo(y)).doesNotContainKey("requires-feature1")
        assertThat(TargetUtils.getExecutionInfo(y)).doesNotContainKey("requires-feature2")
        val exclusiveIfRunLocally: Rule? = getTarget("//x:exclusive_if_local") as Rule?
        assertThat(TargetUtils.isExclusiveIfLocalTestRule(exclusiveIfRunLocally)).isTrue()
        assertThat(TargetUtils.isLocalTestRule(exclusiveIfRunLocally)).isFalse()
        assertThat(TargetUtils.isExclusiveTestRule(exclusiveIfRunLocally)).isFalse()
        val exclusive: Rule? = getTarget("//x:exclusive_only") as Rule?
        assertThat(TargetUtils.isExclusiveTestRule(exclusive)).isTrue()
        assertThat(TargetUtils.isLocalTestRule(exclusive)).isFalse() // LOCAL tag gets added later.
        assertThat(TargetUtils.isExclusiveIfLocalTestRule(exclusive)).isFalse()
    }
}
