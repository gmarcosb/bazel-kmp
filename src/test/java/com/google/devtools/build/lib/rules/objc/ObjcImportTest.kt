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
package com.google.devtools.build.lib.rules.objc

import com.google.common.base.Joiner
import com.google.common.base.Optional
import com.google.common.truth.Subject
import com.google.devtools.build.lib.actions.Artifact
import org.junit.Test
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableList
import kotlin.collections.MutableSet

/** Test case for objc_import.  */
@RunWith(JUnit4::class)
class ObjcImportTest : ObjcRuleTestCase() {
    @Throws(IOException::class)
    private fun addTrivialImportLibrary() {
        scratch.file("imp/precomp_lib.a")
        scratch.file(
            "imp/BUILD",
            """
        load("@rules_cc//cc:objc_import.bzl", "objc_import")
        objc_import(
            name = "imp",
            archives = ["precomp_lib.a"],
        )
        
        """.trimIndent()
        )
    }

    @Test
    @Throws(Exception::class)
    fun testImportLibrariesProvidedTransitively() {
        scratch.file("imp/this_library.a")
        addTrivialImportLibrary()
        scratch.file(
            "lib/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "lib",
            deps = ["//imp"],
        )
        
        """.trimIndent()
        )

        val library: Artifact =
            ccInfoForTarget("//lib:lib")
                .getCcLinkingContext()
                .getLibraries()
                .getSingleton()
                .getStaticLibrary()
        assertThat(library.getRunfilesPath().toString()).isEqualTo("imp/precomp_lib.a")
    }

    @Test
    @Throws(Exception::class)
    fun testImportLibrariesLinkedToFinalBinary() {
        addTrivialImportLibrary()
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)
        scratch.file(
            "bin/BUILD",
            """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "bin",
            platform_type = "ios",
            deps = ["//imp"],
        )
        
        """.trimIndent()
        )
        val linkBinAction: CommandAction = linkAction("//bin:bin")
        verifyObjlist(linkBinAction, "imp/precomp_lib.a")
        Subject.contains("imp/precomp_lib.a")
    }

    @Test
    @Throws(Exception::class)
    fun testAlwaysLinkDefaultFalse() {
        useConfiguration("--incompatible_objc_alwayslink_by_default=false")
        addTrivialImportLibrary()
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)
        scratch.file(
            "bin/BUILD",
            """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "bin",
            platform_type = "ios",
            deps = ["//imp"],
        )
        
        """.trimIndent()
        )
        val linkBinAction: CommandAction = linkAction("//bin:bin")
        Truth.assertThat(Joiner.on("").join(linkBinAction.getArguments())).doesNotContain("-force_load")
    }

    @Test
    @Throws(Exception::class)
    fun testAlwaysLinkDefaultTrue() {
        useConfiguration("--incompatible_objc_alwayslink_by_default")
        addTrivialImportLibrary()
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)
        scratch.file(
            "bin/BUILD",
            """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "bin",
            platform_type = "ios",
            deps = ["//imp"],
        )
        
        """.trimIndent()
        )
        val linkBinAction: CommandAction = linkAction("//bin:bin")
        Truth.assertThat(Joiner.on("").join(linkBinAction.getArguments()))
            .contains("-force_load imp/precomp_lib.a")
    }

    @Test
    @Throws(Exception::class)
    fun testAlwaysLinkTrueDefaultFalse() {
        useConfiguration("--incompatible_objc_alwayslink_by_default=false")
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)

        scratch.file("imp/precomp_lib.a")
        scratch.file(
            "imp/BUILD",
            """
        load("@rules_cc//cc:objc_import.bzl", "objc_import")
        objc_import(
            name = "imp",
            archives = ["precomp_lib.a"],
            alwayslink = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "bin/BUILD",
            """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "bin",
            platform_type = "ios",
            deps = ["//imp"],
        )
        
        """.trimIndent()
        )
        val linkBinAction: CommandAction = linkAction("//bin:bin")
        Truth.assertThat(Joiner.on("").join(linkBinAction.getArguments()))
            .contains("-force_load imp/precomp_lib.a")
    }

    @Test
    @Throws(Exception::class)
    fun testAlwaysLinkFalseDefaultTrue() {
        useConfiguration("--incompatible_objc_alwayslink_by_default")
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)

        scratch.file("imp/precomp_lib.a")
        scratch.file(
            "imp/BUILD",
            """
        load("@rules_cc//cc:objc_import.bzl", "objc_import")
        objc_import(
            name = "imp",
            archives = ["precomp_lib.a"],
            alwayslink = False,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "bin/BUILD",
            """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "bin",
            platform_type = "ios",
            deps = ["//imp"],
        )
        
        """.trimIndent()
        )
        val linkBinAction: CommandAction = linkAction("//bin:bin")
        Truth.assertThat(Joiner.on("").join(linkBinAction.getArguments())).doesNotContain("-force_load")
    }

    @Test
    @Throws(Exception::class)
    fun testArchiveRequiresDotInName() {
        checkError(
            "x",
            "x",
            "'//x:fooa' does not produce any objc_import archives files (expected .a)",
            "load('@rules_cc//cc:objc_import.bzl', 'objc_import')",
            "objc_import(",
            "    name = 'x',",
            "    archives = ['fooa'],",
            ")"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testDylibsProvided() {
        scratch.file("imp/imp.a")
        scratch.file(
            "imp/BUILD",
            """
        load("@rules_cc//cc:objc_import.bzl", "objc_import")
        objc_import(
            name = "imp",
            archives = ["imp.a"],
            sdk_dylibs = [
                "libdy1",
                "libdy2",
            ],
        )
        
        """.trimIndent()
        )

        val ccLinkingContext: CcLinkingContext = ccInfoForTarget("//imp:imp").getCcLinkingContext()
        assertThat(ccLinkingContext.getFlattenedUserLinkFlags()).containsExactly("-ldy1", "-ldy2")
    }

    @Test
    @Throws(Exception::class)
    fun testProvidesHdrsAndIncludes() {
        checkProvidesHdrsAndIncludes(RULE_TYPE, Optional.absent<String?>())
    }

    @Test
    @Throws(Exception::class)
    fun testSdkIncludesUsedInCompileActionsOfDependers() {
        checkSdkIncludesUsedInCompileActionsOfDependers(RULE_TYPE)
    }

    @Test
    @Throws(Exception::class)
    fun testObjcImportLoadedThroughMacro() {
        setupTestObjcImportLoadedThroughMacro( /* loadMacro= */true)
        assertThat(getConfiguredTarget("//a:a")).isNotNull()
        assertNoEvents()
    }

    @Throws(Exception::class)
    private fun setupTestObjcImportLoadedThroughMacro(loadMacro: Boolean) {
        scratch.file(
            "a/BUILD",
            getAnalysisMock().ccSupport().getMacroLoadStatement(loadMacro, "objc_import"),
            "objc_import(name='a', archives=['a.a'])"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testDependency() {
        scratch.file("imp/precomp_dep.a")
        scratch.file("imp/precomp_dep.h")
        scratch.file("imp/precomp_lib.a")
        scratch.file(
            "imp/BUILD",
            """
        load("@rules_cc//cc:objc_import.bzl", "objc_import")
        objc_import(
            name = "imp_dep",
            hdrs = ["precomp_dep.h"],
            archives = ["precomp_dep.a"],
        )

        objc_import(
            name = "imp",
            archives = ["precomp_lib.a"],
            deps = [":imp_dep"],
        )
        
        """.trimIndent()
        )

        Truth.assertThat(ObjcRuleTestCase.Companion.getArifactPathsOfLibraries(getConfiguredTarget("//imp:imp")))
            .containsExactly("imp/precomp_lib.a", "imp/precomp_dep.a")
        Truth.assertThat(ObjcRuleTestCase.Companion.getArifactPathsOfHeaders(getConfiguredTarget("//imp:imp")))
            .containsExactly("imp/precomp_dep.h")
    }

    companion object {
        protected val RULE_TYPE: RuleType = object : RuleType("objc_import") {
            @Throws(IOException::class)
            override fun requiredAttributes(
                scratch: Scratch, packageDir: String?, alreadyAdded: MutableSet<String?>
            ): Iterable<String?> {
                val attributes: MutableList<String?> = ArrayList<String?>()
                if (!alreadyAdded.contains("archives")) {
                    scratch.file(packageDir + "/precomp_library.a")
                    attributes.add("archives = ['precomp_library.a']")
                }
                return attributes
            }
        }
    }
}
