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

import com.google.common.base.Function
import com.google.common.base.Joiner
import com.google.common.base.Optional
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.collect.MoreCollectors
import com.google.common.truth.Subject
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata
import net.starlark.java.eval.EvalException
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.lang.String
import kotlin.AssertionError
import kotlin.Boolean
import kotlin.Exception
import kotlin.IllegalArgumentException
import kotlin.RuntimeException
import kotlin.plus

/** Test case for objc_library.  */
@RunWith(TestParameterInjector::class)
class ObjcLibraryTest : ObjcRuleTestCase() {
    @Test
    @Throws(Exception::class)
    fun testConfigTransitionWithTopLevelAppleConfiguration() {
        scratch.file(
            "bin/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "objc",
            srcs = ["objc.m"],
        )

        cc_binary(
            name = "cc",
            srcs = ["cc.cc"],
            deps = [":objc"],
        )
        
        """.trimIndent()
        )

        setBuildLanguageOptions("--noincompatible_disable_objc_library_transition")
        useConfiguration(
            "--apple_platform_type=ios",
            "--ios_multi_cpus=x86_64",
            "--platforms=" + MockObjcSupport.IOS_X86_64,
            "--experimental_platform_in_output_dir"
        )

        val cc: ConfiguredTarget = getConfiguredTarget("//bin:cc")
        val objcObject: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(
                actionsTestUtil().artifactClosureOf(getFilesToBuild(cc)), "objc.o"
            )
        Subject.contains("ios_x86_64")
    }

    @Test
    @Throws(Exception::class)
    fun testNoTransition() {
        scratch.file(
            "bin/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "objc",
            srcs = ["objc.m"],
        )

        cc_binary(
            name = "cc",
            srcs = ["cc.cc"],
            deps = [":objc"],
        )
        
        """.trimIndent()
        )

        setBuildLanguageOptions("--incompatible_disable_objc_library_transition")
        useConfiguration("--macos_cpus=arm64,x86_64", "--platforms=" + TestConstants.PLATFORM_LABEL)

        // fails to find appropriate toolchain for `objc_library` given the default platform with
        // transition disabled
        Assert.assertThrows<AssertionError?>(
            AssertionError::class.java,
            ThrowingRunnable { getConfiguredTarget("//bin:cc") })
        assertContainsEvent("objc_library rule //bin:objc")
    }

    @Test
    @Throws(Exception::class)
    fun testFilesToBuild() {
        val target: ConfiguredTarget =
            createLibraryTargetWriter("//objc:One")
                .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
                .write()

        val files: NestedSet<Artifact?>? = getFilesToBuild(target)
        assertThat(Artifact.toRootRelativePaths(files)).containsExactly("objc/libOne.a")
    }

    @Test
    @Throws(Exception::class)
    fun testCompilesSources() {
        createLibraryTargetWriter("//objc/lib1")
            .setAndCreateFiles("srcs", "a.m")
            .setAndCreateFiles("hdrs", "hdr.h")
            .write()

        createLibraryTargetWriter("//objc/lib2")
            .setAndCreateFiles("srcs", "b.m")
            .setAndCreateFiles("hdrs", "private.h")
            .write()

        createLibraryTargetWriter("//objc/lib3")
            .setAndCreateFiles("srcs", "a.m")
            .setAndCreateFiles("hdrs", "hdr.h")
            .setList("deps", "//objc/lib1")
            .setList("implementation_deps", "//objc/lib2")
            .write()

        createLibraryTargetWriter("//objc:x")
            .setAndCreateFiles("srcs", "a.m", "private.h")
            .setAndCreateFiles("hdrs", "hdr.h")
            .setList("deps", "//objc/lib3:lib3")
            .write()

        val compileA: CppCompileAction = compileAction("//objc:x", "a.o") as CppCompileAction

        assertThat(Artifact.toRootRelativePaths(compileA.getPossibleInputsForTesting()))
            .containsAtLeast("objc/a.m", "objc/hdr.h", "objc/private.h")
        assertThat(Artifact.toRootRelativePaths(compileA.getOutputs()))
            .containsExactly("objc/_objs/x/arc/a.o", "objc/_objs/x/arc/a.d")
    }

    @Test
    @Throws(Exception::class)
    fun testSerializedDiagnosticsFileFeature() {
        useConfiguration("--features=serialized_diagnostics_file")

        createLibraryTargetWriter("//objc/lib1")
            .setAndCreateFiles("srcs", "a.m")
            .setAndCreateFiles("hdrs", "hdr.h")
            .write()

        createLibraryTargetWriter("//objc/lib2")
            .setAndCreateFiles("srcs", "a.m")
            .setAndCreateFiles("hdrs", "hdr.h")
            .setList("deps", "//objc/lib1")
            .write()

        createLibraryTargetWriter("//objc:x")
            .setAndCreateFiles("srcs", "a.m", "private.h")
            .setAndCreateFiles("hdrs", "hdr.h")
            .setList("deps", "//objc/lib2:lib2")
            .write()

        val compileA: CppCompileAction = compileAction("//objc:x", "a.o") as CppCompileAction

        assertThat(Artifact.toRootRelativePaths(compileA.getOutputs()))
            .containsExactly("objc/_objs/x/arc/a.o", "objc/_objs/x/arc/a.d", "objc/_objs/x/arc/a.dia")
    }

    @Test
    @Throws(Exception::class)
    fun testCompilesSourcesWithSameBaseName() {
        createLibraryTargetWriter("//foo:lib")
            .setAndCreateFiles("srcs", "a.m", "pkg1/a.m", "b.m")
            .setAndCreateFiles("non_arc_srcs", "pkg2/a.m")
            .write()

        getConfiguredTarget("//foo:lib")

        val a0: Artifact = getBinArtifact("_objs/lib/arc/0/a.o", getConfiguredTarget("//foo:lib"))
        val a1: Artifact = getBinArtifact("_objs/lib/arc/1/a.o", getConfiguredTarget("//foo:lib"))
        val a2: Artifact = getBinArtifact("_objs/lib/non_arc/a.o", getConfiguredTarget("//foo:lib"))
        val b: Artifact = getBinArtifact("_objs/lib/arc/b.o", getConfiguredTarget("//foo:lib"))

        assertThat(getGeneratingAction(a0)).isNotNull()
        assertThat(getGeneratingAction(a1)).isNotNull()
        assertThat(getGeneratingAction(a2)).isNotNull()
        assertThat(getGeneratingAction(b)).isNotNull()

        Subject.contains(getSourceArtifact("foo/a.m"))
        Subject.contains(getSourceArtifact("foo/pkg1/a.m"))
        Subject.contains(getSourceArtifact("foo/pkg2/a.m"))
        Subject.contains(getSourceArtifact("foo/b.m"))
    }

    @Test
    @Throws(Exception::class)
    fun testObjcPlusPlusCompile() {
        MockObjcSupport.setupCcToolchainConfig(mockToolsConfig, MockObjcSupport.ios_arm64())
        useConfiguration(
            "--apple_platform_type=ios",
            "--ios_multi_cpus=arm64",
            "--platforms=" + MockObjcSupport.IOS_ARM64
        )
        createLibraryTargetWriter("//objc:lib").setList("srcs", "a.mm").write()
        val compileAction: CommandAction = compileAction("//objc:lib", "a.o")
        assertThat(compileAction.getArguments()).containsAtLeast("-stdlib=libc++", "-std=gnu++11")
    }

    @Test
    @Throws(Exception::class)
    fun testObjcPlusPlusCompileDarwin() {
        MockObjcSupport.setupCcToolchainConfig(mockToolsConfig, MockObjcSupport.darwinX86_64())
        useConfiguration("--platforms=" + MockObjcSupport.DARWIN_X86_64)
        createLibraryTargetWriter("//objc:lib").setList("srcs", "a.mm").write()
        val compileAction: CommandAction = compileAction("//objc:lib", "a.o")
        assertThat(compileAction.getArguments()).containsAtLeast("-stdlib=libc++", "-std=gnu++11")
    }

    @Test
    @Throws(Exception::class)
    fun testObjcSourceContainsObjccopt() {
        useConfiguration("--objccopt=--xyzzy")
        scratch.file("objc/a.m")
        scratch.file(
            "objc/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            RULE_TYPE.target(scratch, "objc", "lib", "srcs", "['a.m']")
        )

        val compileActionA: CommandAction = compileAction("//objc:lib", "a.o")
        Subject.contains("--xyzzy")
    }

    @Test
    @Throws(Exception::class)
    fun testObjcppSourceContainsObjccopt() {
        useConfiguration("--objccopt=--xyzzy")
        scratch.file("objc/a.mm")
        scratch.file(
            "objc/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            RULE_TYPE.target(scratch, "objc", "lib", "srcs", "['a.mm']")
        )

        val compileActionA: CommandAction = compileAction("//objc:lib", "a.o")
        Subject.contains("--xyzzy")
    }

    @Test
    @Throws(Exception::class)
    fun testCSourceDoesNotContainObjccopt() {
        useConfiguration("--objccopt=--xyzzy")
        scratch.file("objc/a.c")
        scratch.file(
            "objc/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            RULE_TYPE.target(scratch, "objc", "lib", "srcs", "['a.c']")
        )

        val compileActionA: CommandAction = compileAction("//objc:lib", "a.o")
        assertThat(compileActionA.getArguments()).doesNotContain("--xyzzy")
    }

    @Test
    @Throws(Exception::class)
    fun testCppSourceDoesNotContainObjccopt() {
        useConfiguration("--objccopt=--xyzzy")
        scratch.file("objc/a.cc")
        scratch.file(
            "objc/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            RULE_TYPE.target(scratch, "objc", "lib", "srcs", "['a.cc']")
        )

        val compileActionA: CommandAction = compileAction("//objc:lib", "a.o")
        assertThat(compileActionA.getArguments()).doesNotContain("--xyzzy")
    }

    @Test
    @Throws(Exception::class)
    fun testCppHeaderDoesNotContainsObjccopt() {
        MockObjcSupport.setupCcToolchainConfig(
            mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS)
        )
        useConfiguration(
            "--features=parse_headers", "--process_headers_in_dependencies", "--objccopt=--xyzzy"
        )

        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'x', hdrs = ['x.h'])"
            )

        assertThat(getGeneratingCompileAction("_objs/x/x.h.processed", x).getArguments())
            .doesNotContain("--xyzzy")
    }

    @Test
    @Throws(Exception::class)
    fun testObjcHeaderContainsObjccopt() {
        MockObjcSupport.setupCcToolchainConfig(
            mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS)
        )
        useConfiguration(
            "--features=parse_headers", "--process_headers_in_dependencies", "--objccopt=--xyzzy"
        )

        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
                "objc_library(name = 'x', hdrs = ['x.h'])"
            )

        Subject.contains("--xyzzy")
    }

    @Test
    @Throws(Exception::class)
    fun testCompilationModeDbg() {
        useConfiguration("--ios_multi_cpus=arm64", "--compilation_mode=dbg")
        scratch.file("objc/a.m")
        scratch.file(
            "objc/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            RULE_TYPE.target(scratch, "objc", "lib", "srcs", "['a.m']")
        )

        val compileActionA: CommandAction = compileAction("//objc:lib", "a.o")

        Subject.contains("--DBG_ONLY_FLAG")
        assertThat(compileActionA.getArguments()).doesNotContain("--FASTBUILD_ONLY_FLAG")
        assertThat(compileActionA.getArguments()).doesNotContain("--OPT_ONLY_FLAG")
    }

    @Test
    @Throws(Exception::class)
    fun testCompilationModeFastbuild() {
        useConfiguration("--compilation_mode=fastbuild")
        scratch.file("objc/a.m")
        scratch.file(
            "objc/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            RULE_TYPE.target(scratch, "objc", "lib", "srcs", "['a.m']")
        )

        val compileActionA: CommandAction = compileAction("//objc:lib", "a.o")

        assertThat(compileActionA.getArguments()).doesNotContain("--DBG_ONLY_FLAG")
        Subject.contains("--FASTBUILD_ONLY_FLAG")
        assertThat(compileActionA.getArguments()).doesNotContain("--OPT_ONLY_FLAG")
    }

    @Test
    @Throws(Exception::class)
    fun testCompilationModeOpt() {
        useConfiguration("--compilation_mode=opt")
        scratch.file("objc/a.m")
        scratch.file(
            "objc/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            RULE_TYPE.target(scratch, "objc", "lib", "srcs", "['a.m']")
        )

        val compileActionA: CommandAction = compileAction("//objc:lib", "a.o")

        assertThat(compileActionA.getArguments()).doesNotContain("--DBG_ONLY_FLAG")
        assertThat(compileActionA.getArguments()).doesNotContain("--FASTBUILD_ONLY_FLAG")
        Subject.contains("--OPT_ONLY_FLAG")
    }

    @Test
    @Throws(Exception::class)
    fun testCreate_runfilesWithSourcesOnly() {
        val target: ConfiguredTarget =
            createLibraryTargetWriter("//objc:One")
                .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
                .write()
        val provider: RunfilesProvider = target.getProvider(RunfilesProvider::class.java)
        assertThat(baseArtifactNames(provider.getDefaultRunfiles().getArtifacts())).isEmpty()
        if (!analysisMock!!
                .isThisBazel
        ) { // TODO(b/507033784): Re-enable in bazel after rules_cc release.
            assertThat(baseArtifactNames(provider.getDataRunfiles().getArtifacts())).isEmpty()
        }
    }

    @Test
    @Throws(Exception::class)
    fun testCreate_noErrorForEmptySourcesButHasDependency() {
        createLibraryTargetWriter("//baselib:baselib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .write()
        createLibraryTargetWriter("//lib:lib")
            .setAndCreateFiles("hdrs", "a.h")
            .setList("deps", "//baselib:baselib")
            .write()

        val ccLinkingContext: CcLinkingContext =
            CcInfo.get(getConfiguredTarget("//lib:lib")).getCcLinkingContext()
        assertThat(ccLinkingContext.getStaticModeParamsForDynamicLibraryLibraries())
            .containsExactlyElementsIn(archiveAction("//baselib:baselib").getOutputs())
    }

    @Test
    @Throws(Exception::class)
    fun testCreate_srcsContainingHeaders() {
        scratch.file("x/a.m", "dummy source file")
        scratch.file("x/a.h", "dummy header file")
        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            "objc_library(name = 'Target', srcs = ['a.m', 'a.h'])"
        )
        Truth.assertThat(view!!.hasErrors(getConfiguredTarget("//x:Target"))).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun testCreate_headerAndCompiledSourceWithSameName() {
        scratch.file(
            "objc/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            "objc_library(name = 'Target', srcs = ['a.m'], hdrs = ['a.h'])"
        )
        Truth.assertThat(view!!.hasErrors(getConfiguredTarget("//objc:Target"))).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun testCreate_errorForCcInNonArcSources() {
        scratch.file("x/cc.cc")
        checkError(
            "x",
            "x",
            "non_arc_srcs attribute of objc_library rule @@//x:x: source file '@@//x:cc.cc' is"
                    + " misplaced here",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            "objc_library(name = 'x', non_arc_srcs = ['cc.cc'])"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testFileInSrcsAndNonArcSources() {
        checkError(
            "x",
            "x",
            String.format(CompilationSupport.FILE_IN_SRCS_AND_NON_ARC_SRCS_ERROR_FORMAT, "x/foo.m"),
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            "objc_library(name = 'x', srcs = ['foo.m'], non_arc_srcs = ['foo.m'])"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testCreate_headerContainingDotMAndDotCFiles() {
        scratch.file("x/a.m", "dummy source file")
        scratch.file("x/a.h", "dummy header file")
        scratch.file("x/b.m", "dummy source file")
        scratch.file("x/a.c", "dummy source file")
        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            "objc_library(name = 'Target', srcs = ['a.m'], hdrs = ['a.h', 'b.m', 'a.c'])"
        )
        Truth.assertThat(view!!.hasErrors(getConfiguredTarget("//x:Target"))).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun testProvidesObjcHeadersWithDotMFiles() {
        val target: ConfiguredTarget =
            createLibraryTargetWriter("//objc:lib")
                .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
                .setAndCreateFiles("hdrs", "a.h", "b.h", "f.m")
                .write()
        val depender: ConfiguredTarget =
            createLibraryTargetWriter("//objc2:lib")
                .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
                .setAndCreateFiles("hdrs", "d.h", "e.m")
                .setList("deps", "//objc:lib")
                .write()
        Truth.assertThat(ObjcRuleTestCase.Companion.getArifactPathsOfHeaders(target))
            .containsExactly("objc/a.h", "objc/b.h", "objc/f.m", "objc/private.h")
        Truth.assertThat(ObjcRuleTestCase.Companion.getArifactPathsOfHeaders(depender))
            .containsExactly(
                "objc/a.h",
                "objc/b.h",
                "objc/f.m",
                "objc/private.h",
                "objc2/d.h",
                "objc2/e.m",
                "objc2/private.h"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testMultiPlatformLibrary() {
        useConfiguration("--ios_multi_cpus=arm64,x86_64,arm64e,sim_arm64")

        createLibraryTargetWriter("//objc:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setAndCreateFiles("hdrs", "a.h")
            .write()

        Truth.assertThat(view!!.hasErrors(getConfiguredTarget("//objc:lib"))).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun testCompilationActions_simulator() {
        useConfiguration(
            "--apple_platform_type=ios",
            "--ios_multi_cpus=x86_64",
            "--platforms=" + MockObjcSupport.IOS_X86_64
        )

        scratch.file("objc/a.m")
        scratch.file("objc/non_arc.m")
        scratch.file("objc/private.h")
        scratch.file("objc/c.h")
        scratch.file(
            "objc/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            RULE_TYPE.target(
                scratch,
                "objc",
                "lib",
                "srcs",
                "['a.m', 'private.h']",
                "hdrs",
                "['c.h']",
                "non_arc_srcs",
                "['non_arc.m']"
            )
        )

        val compileActionA: CommandAction = compileAction("//objc:lib", "a.o")
        val compileActionNonArc: CommandAction = compileAction("//objc:lib", "non_arc.o")

        assertRequiresDarwin(compileActionA)
        Subject.contains("tools/osx/crosstool/iossim/" + WRAPPED_CLANG)
        assertThat(compileActionA.getArguments())
            .containsAtLeast("-isysroot", "__BAZEL_XCODE_SDKROOT__")
            .inOrder()
        assertThat(compileActionA.getArguments())
            .containsAtLeastElementsIn(CompilationSupport.DEFAULT_COMPILER_FLAGS)
        assertThat(compileActionA.getArguments())
            .containsAtLeastElementsIn(CompilationSupport.SIMULATOR_COMPILE_FLAGS)
        Subject.contains("-fobjc-arc")
        assertThat(compileActionA.getArguments()).containsAtLeast("-c", "objc/a.m")
        Subject.contains("-fno-objc-arc")
        Subject.contains("-arch x86_64")
    }

    @Test
    @Throws(Exception::class)
    fun testCompilationActions_device() {
        useConfiguration(
            "--apple_platform_type=ios",
            "--ios_multi_cpus=arm64",
            "--platforms=" + MockObjcSupport.IOS_ARM64
        )

        scratch.file("objc/a.m")
        scratch.file("objc/non_arc.m")
        scratch.file("objc/private.h")
        scratch.file("objc/c.h")
        scratch.file(
            "objc/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            RULE_TYPE.target(
                scratch,
                "objc",
                "lib",
                "srcs",
                "['a.m', 'private.h']",
                "hdrs",
                "['c.h']",
                "non_arc_srcs",
                "['non_arc.m']"
            )
        )

        val compileActionA: CommandAction = compileAction("//objc:lib", "a.o")
        val compileActionNonArc: CommandAction = compileAction("//objc:lib", "non_arc.o")

        assertRequiresDarwin(compileActionA)
        Subject.contains("tools/osx/crosstool/ios/" + WRAPPED_CLANG)
        assertThat(compileActionA.getArguments())
            .containsAtLeast("-isysroot", "__BAZEL_XCODE_SDKROOT__")
            .inOrder()
        assertThat(compileActionA.getArguments())
            .containsAtLeastElementsIn(CompilationSupport.DEFAULT_COMPILER_FLAGS)
        assertThat(compileActionA.getArguments())
            .containsNoneIn(CompilationSupport.SIMULATOR_COMPILE_FLAGS)

        Subject.contains("-fobjc-arc")
        assertThat(compileActionA.getArguments()).containsAtLeast("-c", "objc/a.m")

        Subject.contains("-fno-objc-arc")
        Subject.contains("-arch arm64")
    }

    @Test
    @Throws(Exception::class)
    fun testArchivesPrecompiledObjectFiles() {
        scratch.file("objc/a.m")
        scratch.file("objc/b.o")
        scratch.file(
            "objc/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            RULE_TYPE.target(scratch, "objc", "x", "srcs", "['a.m', 'b.o']")
        )
        Subject.contains("objc/b.o")
    }

    @Test
    @Throws(Exception::class)
    fun testCompileWithFrameworkImportsIncludesFlags() {
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)
        addBinWithTransitiveDepOnFrameworkImport()
        val compileAction: CommandAction = compileAction("//lib:lib", "a.o")

        assertThat(compileAction.getArguments()).doesNotContain("-framework")
        Truth.assertThat(Joiner.on("").join(compileAction.getArguments())).contains("-Ffx")
    }

    @Test
    @Throws(Exception::class)
    fun testPrecompiledHeaders() {
        scratch.file("objc/a.m")
        scratch.file("objc/c.pch")
        scratch.file(
            "objc/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            RULE_TYPE.target(
                scratch, "objc", "x", "srcs", "['a.m']", "non_arc_srcs", "['b.m']", "pch", "'c.pch'"
            )
        )
        val compileAction: CppCompileAction = compileAction("//objc:x", "a.o") as CppCompileAction
        Truth.assertThat(Joiner.on(" ").join(compileAction.getArguments())).contains("-include objc/c.pch")
        Subject.contains("objc/c.pch")
    }

    @Test
    @Throws(Exception::class)
    fun testCompilationActionsWithCopts() {
        useConfiguration("--apple_platform_type=ios", "--platforms=" + MockObjcSupport.IOS_ARM64)

        scratch.file(
            "objc/defs.bzl",
            """
        def _var_providing_rule_impl(ctx):
            return [
                platform_common.TemplateVariableInfo({
                    "FOO": "${'$'}(BAR)",
                    "BAR": ctx.attr.var_value,
                    "BAZ": "${'$'}(FOO)",
                }),
            ]

        var_providing_rule = rule(
            implementation = _var_providing_rule_impl,
            attrs = {"var_value": attr.string()},
        )
        
        """.trimIndent()
        )

        scratch.file(
            "objc/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("//objc:defs.bzl", "var_providing_rule")

        var_providing_rule(
            name = "set_foo_to_bar",
            var_value = "bar",
        )

        objc_library(
            name = "lib",
            srcs = [
                "a.m",
                "b.m",
                "private.h",
            ],
            hdrs = ["c.h"],
            copts = [
                "-Ifoo",
                "--monkeys=enabled",
                "--gorillas=${'$'}(FOO),${'$'}(BAR),${'$'}(BAZ)",
            ],
            toolchains = [":set_foo_to_bar"],
        )
        
        """.trimIndent()
        )

        val compileActionA: CommandAction = compileAction("//objc:lib", "a.o")
        assertThat(compileActionA.getArguments())
            .containsAtLeast("-Ifoo", "--monkeys=enabled", "--gorillas=bar,bar,bar")
    }

    @Test
    @Throws(Exception::class)
    fun testObjcCopts() {
        useConfiguration("--objccopt=-foo")
        createLibraryTargetWriter("//lib:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .write()
        val args: MutableList<String?>? = compileAction("//lib:lib", "a.o").getArguments()
        Truth.assertThat(args).contains("-foo")
    }

    @Test
    @Throws(Exception::class)
    fun testObjcCopts_argumentOrdering() {
        useConfiguration("--objccopt=-foo")
        createLibraryTargetWriter("//lib:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setList("copts", "-bar")
            .write()
        val args: MutableList<String?>? = compileAction("//lib:lib", "a.o").getArguments()
        Truth.assertThat(args).containsAtLeast("-fobjc-arc", "-foo", "-bar").inOrder()
    }

    @Test
    @Throws(Exception::class)
    fun testObjcCxxopts_argumentOrdering() {
        useConfiguration("--objccopt=-foo", "--cxxopt=-cxxfoo")
        createLibraryTargetWriter("//lib:lib")
            .setAndCreateFiles("srcs", "a.mm", "b.m", "private.h")
            .setList("copts", "-bar")
            .write()
        val aArgs: MutableList<String?>? = compileAction("//lib:lib", "a.o").getArguments()
        Truth.assertThat(aArgs).containsAtLeast("-fobjc-arc", "-cxxfoo", "-foo", "-bar").inOrder()
        val bArgs: MutableList<String?>? = compileAction("//lib:lib", "b.o").getArguments()
        Truth.assertThat(bArgs).containsAtLeast("-fobjc-arc", "-foo", "-bar").inOrder()
        Truth.assertThat(bArgs).doesNotContain("-cxxfoo")
    }

    @Test
    @Throws(Exception::class)
    fun testMultipleLanguagesCopts() {
        useConfiguration("--apple_platform_type=ios", "--platforms=" + MockObjcSupport.IOS_ARM64)

        scratch.file(
            "objc/defs.bzl",
            """
        def _var_providing_rule_impl(ctx):
            return [
                platform_common.TemplateVariableInfo({
                    "FOO": "${'$'}(BAR)",
                    "BAR": ctx.attr.var_value,
                    "BAZ": "${'$'}(FOO)",
                }),
            ]

        var_providing_rule = rule(
            implementation = _var_providing_rule_impl,
            attrs = {"var_value": attr.string()},
        )
        
        """.trimIndent()
        )

        scratch.file(
            "objc/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("//objc:defs.bzl", "var_providing_rule")

        var_providing_rule(
            name = "set_foo_to_bar",
            var_value = "bar",
        )

        objc_library(
            name = "lib",
            srcs = [
                "c.c",
                "cpp.cpp",
                "objc.m",
                "objcpp.mm",
            ],
            copts = ["-DFROM_SHARED=${'$'}(FOO),${'$'}(BAR),${'$'}(BAZ)"],
            conlyopts = ["-DFROM_CONLYOPTS=${'$'}(FOO),${'$'}(BAR),${'$'}(BAZ)"],
            cxxopts = ["-DFROM_CXXOPTS=${'$'}(FOO),${'$'}(BAR),${'$'}(BAZ)"],
            toolchains = [":set_foo_to_bar"],
        )
        
        """.trimIndent()
        )

        val cCompileAction: CommandAction = compileAction("//objc:lib", "c.o")
        assertThat(cCompileAction.getArguments())
            .containsAtLeast("-DFROM_SHARED=bar,bar,bar", "-DFROM_CONLYOPTS=bar,bar,bar")
            .inOrder()
        assertThat(cCompileAction.getArguments()).doesNotContain("-DFROM_CXXOPTS=bar,bar,bar")

        val objcCompileAction: CommandAction = compileAction("//objc:lib", "objc.o")
        Subject.contains("-DFROM_SHARED=bar,bar,bar")
        assertThat(objcCompileAction.getArguments()).doesNotContain("-DFROM_CONLYOPTS=bar,bar,bar")
        assertThat(objcCompileAction.getArguments()).doesNotContain("-DFROM_CXXOPTS=bar,bar,bar")

        val objcppCompileAction: CommandAction = compileAction("//objc:lib", "objcpp.o")
        assertThat(objcppCompileAction.getArguments())
            .containsAtLeast("-DFROM_SHARED=bar,bar,bar", "-DFROM_CXXOPTS=bar,bar,bar")
            .inOrder()
        assertThat(objcppCompileAction.getArguments()).doesNotContain("-DFROM_CONLYOPTS=bar,bar,bar")

        val cppCompileAction: CommandAction = compileAction("//objc:lib", "cpp.o")
        assertThat(cppCompileAction.getArguments())
            .containsAtLeast("-DFROM_SHARED=bar,bar,bar", "-DFROM_CXXOPTS=bar,bar,bar")
            .inOrder()
        assertThat(cppCompileAction.getArguments()).doesNotContain("-DFROM_CONLYOPTS=bar,bar,bar")
    }

    @Test
    @Throws(Exception::class)
    fun testCompilationActionsWithCoptFmodules() {
        createLibraryTargetWriter("//objc:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setAndCreateFiles("hdrs", "c.h")
            .setList("copts", "-fmodules")
            .write()
        val compileActionA: CommandAction = compileAction("//objc:lib", "a.o")
        Truth.assertThat(removeConfigFragment(compileActionA.getArguments()))
            .containsAtLeast(
                "-fmodules",
                ("-fmodules-cache-path="
                        + ObjcRuleTestCase.Companion.OUTPUTDIR
                        + "/"
                        + CompilationSupport.OBJC_MODULE_CACHE_DIR_NAME)
            )
    }

    @Test
    @Throws(Exception::class)
    fun testArchiveAction_simulator() {
        useConfiguration(
            "--apple_platform_type=ios",
            "--ios_multi_cpus=x86_64",
            "--platforms=" + MockObjcSupport.IOS_X86_64
        )
        createLibraryTargetWriter("//objc:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setAndCreateFiles("hdrs", "c.h")
            .write()

        val archiveAction: CommandAction = archiveAction("//objc:lib")
        assertThat(archiveAction.getArguments())
            .isEqualTo(
                ImmutableList.of<E?>(
                    "tools/osx/crosstool/iossim/ar_wrapper",
                    "rcs",
                    Iterables.getOnlyElement<T?>(archiveAction.getOutputs()).getExecPathString(),
                    getBinArtifact("_objs/lib/arc/a.o", getConfiguredTarget("//objc:lib"))
                        .getExecPathString(),
                    getBinArtifact("_objs/lib/arc/b.o", getConfiguredTarget("//objc:lib"))
                        .getExecPathString()
                )
            )
        assertThat(baseArtifactNames(archiveAction.getInputs()))
            .containsAtLeast("a.o", "b.o", "ar", "libempty.a", "libtool")
        assertThat(baseArtifactNames(archiveAction.getOutputs())).containsExactly("liblib.a")
        assertRequiresDarwin(archiveAction)
    }

    @Test
    @Throws(Exception::class)
    fun testArchiveAction_device() {
        useConfiguration(
            "--apple_platform_type=ios",
            "--ios_multi_cpus=arm64",
            "--platforms=" + MockObjcSupport.IOS_ARM64
        )
        createLibraryTargetWriter("//objc:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setAndCreateFiles("hdrs", "c.h")
            .write()
        val archiveAction: CommandAction = archiveAction("//objc:lib")

        assertThat(archiveAction.getArguments())
            .isEqualTo(
                ImmutableList.of<E?>(
                    "tools/osx/crosstool/ios/ar_wrapper",
                    "rcs",
                    Iterables.getOnlyElement<T?>(archiveAction.getOutputs()).getExecPathString(),
                    getBinArtifact("_objs/lib/arc/a.o", getConfiguredTarget("//objc:lib"))
                        .getExecPathString(),
                    getBinArtifact("_objs/lib/arc/b.o", getConfiguredTarget("//objc:lib"))
                        .getExecPathString()
                )
            )
        assertThat(baseArtifactNames(archiveAction.getInputs())).containsAtLeast("a.o", "b.o")
        assertThat(baseArtifactNames(archiveAction.getOutputs())).containsExactly("liblib.a")
        assertRequiresDarwin(archiveAction)
    }

    @Test
    @Throws(Exception::class)
    fun testIncludesDirsGetPassedToCompileAction() {
        createLibraryTargetWriter("//lib:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setList("includes", "../third_party/foo", "opensource/bar")
            .write()
        val compileAction: CommandAction = compileAction("//lib:lib", "a.o")

        for (path in rootedIncludePaths("third_party/foo", "lib/opensource/bar")) {
            Truth.assertThat(Joiner.on("").join(removeConfigFragment(compileAction.getArguments())))
                .contains("-I" + path)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testIncludesDirs_inExternalRepo_resolvesSiblingLayout() {
        if (!analysisMock!!.isThisBazel) {
            return
        }
        scratch.appendFile(
            "MODULE.bazel",
            "bazel_dep(name='lib_external')",
            "local_path_override(module_name = 'lib_external', path = 'lib_external')"
        )
        scratch.file("lib_external/MODULE.bazel", "module(name='lib_external')")
        analysisMock!!.ccSupport().setup(MockToolsConfig(scratch.resolve("lib_external")))
        scratch.file(
            "lib_external/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "lib",
            srcs = [
                "a.m",
                "bar/b.h",
            ],
            includes = ["bar"],
        )
        
        """.trimIndent()
        )
        scratch.file("lib_external/a.m")
        scratch.file("lib_external/bar/b.h")
        invalidatePackages()

        setBuildLanguageOptions("--experimental_sibling_repository_layout")

        val compileAction: CommandAction = compileAction("@@lib_external+//:lib", "a.o")
        val actionArgs: String = Joiner.on("").join(removeConfigFragment(compileAction.getArguments()))

        Truth.assertThat(actionArgs).contains("-I../lib_external+/bar")
    }

    @Test
    @Throws(Exception::class)
    fun testPropagatesDefinesToDependersTransitively() {
        useConfiguration("--apple_platform_type=ios", "--platforms=" + MockObjcSupport.IOS_X86_64)
        createLibraryTargetWriter("//lib1:lib1")
            .setAndCreateFiles("srcs", "a.m")
            .setAndCreateFiles("non_arc_srcs", "b.m")
            .setList("defines", "A=foo", "B", "MONKEYS=enabled")
            .write()
        createLibraryTargetWriter("//lib2:lib2")
            .setAndCreateFiles("srcs", "a.m")
            .setAndCreateFiles("non_arc_srcs", "b.m")
            .setList("deps", "//lib1:lib1")
            .setList("defines", "C=bar", "D")
            .write()
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)
        scratch.file(
            "bin/BUILD",
            """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "bin",
            platform_type = "ios",
            deps = ["//lib2"],
        )
        
        """.trimIndent()
        )

        assertThat(compileAction("//lib1:lib1", "a.o").getArguments())
            .containsAtLeast("-DA=foo", "-DB", "-DMONKEYS=enabled")
            .inOrder()
        assertThat(compileAction("//lib1:lib1", "b.o").getArguments())
            .containsAtLeast("-DA=foo", "-DB", "-DMONKEYS=enabled")
            .inOrder()
        assertThat(compileAction("//lib2:lib2", "a.o").getArguments())
            .containsAtLeast("-DA=foo", "-DB", "-DMONKEYS=enabled", "-DC=bar", "-DD")
            .inOrder()
        assertThat(compileAction("//lib2:lib2", "b.o").getArguments())
            .containsAtLeast("-DA=foo", "-DB", "-DMONKEYS=enabled", "-DC=bar", "-DD")
            .inOrder()
        // TODO: Add tests for //bin:bin once experimental_objc_binary is implemented
    }

    @Test
    @Throws(Exception::class)
    fun testDuplicateDefines() {
        createLibraryTargetWriter("//lib:lib")
            .setAndCreateFiles("srcs", "a.m")
            .setList("defines", "foo=bar", "foo=bar")
            .write()
        var timesDefinesAppear = 0
        for (arg in compileAction("//lib:lib", "a.o").getArguments()) {
            if (arg == "-Dfoo=bar") {
                timesDefinesAppear++
            }
        }
        Truth.assertWithMessage("Duplicate define \"foo=bar\" should occur only once in command line")
            .that(timesDefinesAppear)
            .isEqualTo(1)
    }

    @Test
    @Throws(Exception::class)
    fun checkDefinesFromCcLibraryDep() {
        checkDefinesFromCcLibraryDep(RULE_TYPE)
    }

    @Test
    @Throws(Exception::class)
    fun testCppSourceCompilesWithCppFlags() {
        createLibraryTargetWriter("//objc:x")
            .setAndCreateFiles("srcs", "a.mm", "b.cc", "c.mm", "d.cxx", "e.c", "f.m", "g.C")
            .write()
        Subject.contains("-std=gnu++11")
        Subject.contains("-std=gnu++11")
        Subject.contains("-std=gnu++11")
        Subject.contains("-std=gnu++11")
        assertThat(compileAction("//objc:x", "e.o").getArguments()).doesNotContain("-std=gnu++11")
        assertThat(compileAction("//objc:x", "f.o").getArguments()).doesNotContain("-std=gnu++11")
        Subject.contains("-std=gnu++11")
    }

    @Test
    @Throws(Exception::class)
    fun testDoesNotUseCxxUnfilteredFlags() {
        createLibraryTargetWriter("//lib:lib").setList("srcs", "a.m").write()
        // -pthread is an unfiltered_cxx_flag in the osx crosstool.
        assertThat(compileAction("//lib:lib", "a.o").getArguments()).doesNotContain("-pthread")
    }

    @Test
    @Throws(Exception::class)
    fun testDoesNotUseDotdPruning() {
        useConfiguration("--objc_use_dotd_pruning=false")
        createLibraryTargetWriter("//lib:lib").setList("srcs", "a.m").write()
        val compileAction: CppCompileAction = compileAction("//lib:lib", "a.o") as CppCompileAction
        assertThat(compileAction.getDotdFile()).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun testProvidesObjcLibraryAndHeaders() {
        val target: ConfiguredTarget =
            createLibraryTargetWriter("//objc:lib")
                .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
                .setAndCreateFiles("hdrs", "a.h", "b.h")
                .write()
        val impltarget: ConfiguredTarget =
            createLibraryTargetWriter("//objc_impl:lib")
                .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
                .setAndCreateFiles("hdrs", "a.h", "b.h")
                .write()
        val depender: ConfiguredTarget =
            createLibraryTargetWriter("//objc_depender:lib")
                .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
                .setAndCreateFiles("hdrs", "c.h", "d.h")
                .setList("deps", "//objc:lib")
                .setList("implementation_deps", "//objc_impl:lib")
                .write()

        Truth.assertThat(ObjcRuleTestCase.Companion.getArifactPathsOfLibraries(target)).containsExactly("objc/liblib.a")
        Truth.assertThat(ObjcRuleTestCase.Companion.getArifactPathsOfLibraries(depender))
            .containsExactly("objc/liblib.a", "objc_impl/liblib.a", "objc_depender/liblib.a")
        Truth.assertThat(ObjcRuleTestCase.Companion.getArifactPathsOfHeaders(target))
            .containsExactly("objc/a.h", "objc/b.h", "objc/private.h")
        Truth.assertThat(ObjcRuleTestCase.Companion.getArifactPathsOfHeaders(impltarget))
            .containsExactly("objc_impl/a.h", "objc_impl/b.h", "objc_impl/private.h")
        Truth.assertThat(ObjcRuleTestCase.Companion.getArifactPathsOfHeaders(depender))
            .containsExactly(
                "objc/a.h",
                "objc/b.h",
                "objc/private.h",
                "objc_depender/c.h",
                "objc_depender/d.h",
                "objc_depender/private.h"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testCollectsWeakSdkFrameworksTransitively() {
        createLibraryTargetWriter("//base_lib:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setList("weak_sdk_frameworks", "foo")
            .write()
        createLibraryTargetWriter("//depender_lib:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setList("weak_sdk_frameworks", "bar")
            .setList("deps", "//base_lib:lib")
            .write()

        val baseLinkFlags = getCcInfoUserLinkFlagsFromTarget("//base_lib:lib")
        Truth.assertThat(baseLinkFlags).containsExactly("-weak_framework", "foo").inOrder()
        val dependerLinkFlags =
            getCcInfoUserLinkFlagsFromTarget("//depender_lib:lib")
        Truth.assertThat(dependerLinkFlags)
            .containsExactly("-weak_framework", "bar", "-weak_framework", "foo")
            .inOrder()
    }

    @Test
    @Throws(Exception::class)
    fun testErrorIfDepDoesNotExist() {
        checkErrorIfNotExist("deps", "[':nonexistent']")
    }

    @Test
    @Throws(Exception::class)
    fun testArIsNotImplicitOutput() {
        createLibraryTargetWriter("//lib:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .write()
        reporter.removeHandler(failFastHandler)
        Assert.assertThrows<T?>(NoSuchTargetException::class.java, ThrowingRunnable { getTarget("//lib:liblib.a") })
    }

    @Test
    @Throws(Exception::class)
    fun testErrorForAbsoluteIncludesPath() {
        scratch.file("x/a.m")
        checkError(
            "x",
            "x",
            String.format(ABSOLUTE_INCLUDES_PATH_FORMAT, "/absolute/path"),
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            "objc_library(",
            "    name = 'x',",
            "    srcs = ['a.m'],",
            "    includes = ['/absolute/path'],",
            ")"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testDylibsProvided() {
        createLibraryTargetWriter("//lib:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setList("sdk_dylibs", "libdy1", "libdy2")
            .write()

        val ccLinkingContext: CcLinkingContext = ccInfoForTarget("//lib:lib").getCcLinkingContext()
        assertThat(ccLinkingContext.getFlattenedUserLinkFlags()).containsExactly("-ldy1", "-ldy2")
    }

    @Test
    @Throws(Exception::class)
    fun testPopulatesCompilationArtifacts() {
        checkPopulatesCompilationArtifacts(RULE_TYPE)
    }

    @Test
    @Throws(Exception::class)
    fun testCompilationActionsForDebug() {
        checkClangCoptsForCompilationMode(RULE_TYPE, CompilationMode.DBG)
    }

    @Test
    @Throws(Exception::class)
    fun testClangCoptsForDebugModeWithoutHardcoding() {
        useConfiguration(
            "--apple_platform_type=ios",
            "--platforms=" + MockObjcSupport.IOS_X86_64,
            "--compilation_mode=dbg"
        )
        scratch.file("x/a.m")
        RULE_TYPE.scratchTarget(scratch, "srcs", "['a.m']")

        assertThat(compileAction("//x:x", "a.o").getArguments())
            .containsNoneOf("-D_GLIBCXX_DEBUG", "-DDEBUG=1")
    }

    @Test
    @Throws(Exception::class)
    fun testCompilationActionsForOptimized() {
        checkClangCoptsForCompilationMode(RULE_TYPE, CompilationMode.OPT)
    }

    @Test
    @Throws(Exception::class)
    fun testClangCoptsForOptimizedWithoutHardcoding() {
        useConfiguration(
            "--apple_platform_type=ios",
            "--platforms=" + MockObjcSupport.IOS_X86_64,
            "--compilation_mode=opt"
        )
        scratch.file("x/a.m")
        RULE_TYPE.scratchTarget(scratch, "srcs", "['a.m']")

        assertThat(compileAction("//x:x", "a.o").getArguments()).doesNotContain("-DNDEBUG=1")
    }

    @Test
    @Throws(Exception::class)
    fun testUsesDefinesFromTransitiveCcDeps() {
        scratch.file(
            "package/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        cc_library(
            name = "cc_lib",
            srcs = ["a.cc"],
            defines = ["FOO"],
        )

        objc_library(
            name = "objc_lib",
            srcs = ["b.m"],
            deps = [":cc_lib"],
        )
        
        """.trimIndent()
        )

        val compileAction: CommandAction = compileAction("//package:objc_lib", "b.o")
        Subject.contains("-DFOO")
    }

    @Test
    @Throws(Exception::class)
    fun testAllowVariousNonBlacklistedTypesInHeaders() {
        checkAllowVariousNonBlacklistedTypesInHeaders(RULE_TYPE)
    }

    @Test
    @Throws(Exception::class)
    fun testXcodeVersionEnv() {
        useConfiguration("--xcode_version=5.8")

        createLibraryTargetWriter("//objc:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setAndCreateFiles("hdrs", "c.h")
            .write()
        val action: CommandAction = compileAction("//objc:lib", "a.o")

        assertXcodeVersionEnv(action, "5.8")
    }

    @Test
    fun testIosSdkVersionCannotBeDefinedButEmpty() {
        val e: T? =
            Assert.assertThrows<T?>(
                InvalidConfigurationException::class.java, ThrowingRunnable { useConfiguration("--ios_sdk_version=") })
        assertThat(e).hasMessageThat().contains("--ios_sdk_version")
    }

    @Throws(Exception::class)
    private fun checkErrorIfNotExist(attribute: String?, value: String?) {
        scratch.file("x/a.m")
        checkError(
            "x",
            "x",
            ("in "
                    + attribute
                    + " attribute of objc_library rule //x:x: rule '//x:nonexistent' does not exist"),
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            "objc_library(",
            "    name = 'x',",
            "    srcs = ['a.m'],",
            attribute + " = " + value,
            ")"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testCompilesWithHdrs() {
        checkCompilesWithHdrs(RULE_TYPE)
    }

    @Test
    @Throws(Exception::class)
    fun testCompilesAssemblyWithPreprocessing() {
        createLibraryTargetWriter("//objc:lib")
            .setAndCreateFiles("srcs", "a.m", "b.S")
            .setAndCreateFiles("hdrs", "c.h")
            .write()

        val compileAction: CommandAction = compileAction("//objc:lib", "b.o")

        // Clang automatically preprocesses .S files, so the assembler-with-cpp flag is unnecessary.
        // Regression test for b/22636858.
        assertThat(compileAction.getArguments()).doesNotContain("-x")
        assertThat(compileAction.getArguments()).doesNotContain("assembler-with-cpp")
        assertThat(baseArtifactNames(compileAction.getOutputs())).containsExactly("b.o", "b.d")
        assertThat(baseArtifactNames(compileAction.getPossibleInputsForTesting()))
            .containsAtLeast("c.h", "b.S")
    }

    @Test
    @Throws(Exception::class)
    fun testReceivesTransitivelyPropagatedDefines() {
        checkReceivesTransitivelyPropagatedDefines(RULE_TYPE)
    }

    @Test
    @Throws(Exception::class)
    fun testSdkIncludesUsedInCompileAction() {
        checkSdkIncludesUsedInCompileAction(RULE_TYPE)
    }

    @Test
    @Throws(Exception::class)
    fun testCompilationActionsWithPch() {
        useConfiguration("--apple_platform_type=ios", "--platforms=" + MockObjcSupport.IOS_X86_64)
        scratch.file("objc/foo.pch")
        createLibraryTargetWriter("//objc:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setAndCreateFiles("hdrs", "c.h")
            .set("pch", "'some.pch'")
            .write()

        val compileActionA: CommandAction = compileAction("//objc:lib", "a.o")

        Truth.assertThat(removeConfigFragment(compileActionA.getArguments()))
            .containsAtLeastElementsIn(
                ImmutableList.Builder<String?>()
                    .add("-fexceptions")
                    .add("-fasm-blocks")
                    .add("-fobjc-abi-version=2")
                    .add("-fobjc-legacy-dispatch")
                    .addAll(CompilationSupport.DEFAULT_COMPILER_FLAGS)
                    .add("-arch x86_64")
                    .add("-isysroot", "__BAZEL_XCODE_SDKROOT__")
                    .add("-iquote", ".")
                    .add("-iquote", ObjcRuleTestCase.Companion.OUTPUTDIR)
                    .add("-include", "objc/some.pch")
                    .add("-fobjc-arc")
                    .add("-c", "objc/a.m")
                    .addAll(outputArgs(compileActionA.getOutputs()))
                    .build()
            )

        Subject.contains(getFileConfiguredTarget("//objc:some.pch").getArtifact())
    }

    // Converts output artifacts into expected command-line arguments.
    private fun outputArgs(outputs: MutableCollection<Artifact?>?): ImmutableList<String?> {
        val result = ImmutableList.Builder<String?>()
        for (outputConfig in Artifact.toExecPaths(outputs)) {
            val output: String = removeConfigFragment(outputConfig)
            if (output.endsWith(".o")) {
                result.add("-o", output)
            } else if (output.endsWith(".d")) {
                result.add("-MD", "-MF", output)
            } else {
                throw IllegalArgumentException(
                    "output " + output + " has unknown ending (not in (.d, .o)"
                )
            }
        }
        return result.build()
    }

    @Test
    @Throws(Exception::class)
    fun testCollectsSdkFrameworksTransitively() {
        createLibraryTargetWriter("//base_lib:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setList("sdk_frameworks", "foo")
            .write()
        createLibraryTargetWriter("//depender_lib:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setList("sdk_frameworks", "bar")
            .setList("deps", "//base_lib:lib")
            .write()

        val baseLinkFlags = getCcInfoUserLinkFlagsFromTarget("//base_lib:lib")
        Truth.assertThat(baseLinkFlags).containsExactly("-framework", "foo").inOrder()
        val dependerLinkFlags =
            getCcInfoUserLinkFlagsFromTarget("//depender_lib:lib")
        Truth.assertThat(dependerLinkFlags)
            .containsExactly("-framework", "bar", "-framework", "foo")
            .inOrder()

        // Make sure that the archive action does not actually include the frameworks. This is needed
        // for creating binaries but is ignored for libraries.
        val archiveAction: CommandAction = archiveAction("//depender_lib:lib")
        assertThat(archiveAction.getArguments())
            .isEqualTo(
                ImmutableList.of<E?>(
                    "tools/osx/crosstool/mac/ar_wrapper",
                    "rcs",
                    Iterables.getOnlyElement<T?>(archiveAction.getOutputs()).getExecPathString(),
                    getBinArtifact("_objs/lib/arc/a.o", getConfiguredTarget("//depender_lib:lib"))
                        .getExecPathString(),
                    getBinArtifact("_objs/lib/arc/b.o", getConfiguredTarget("//depender_lib:lib"))
                        .getExecPathString()
                )
            )
    }

    @Test
    @Throws(Exception::class)
    fun testMultipleRulesCompilingOneSourceGenerateUniqueObjFiles() {
        scratch.file("lib/a.m")
        scratch.file(
            "lib/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "lib1",
            srcs = ["a.m"],
            copts = ["-Ilib1flag"],
        )

        objc_library(
            name = "lib2",
            srcs = ["a.m"],
            copts = ["-Ilib2flag"],
        )
        
        """.trimIndent()
        )
        val obj1: Artifact? = Iterables.getOnlyElement<Artifact?>(inputsEndingWith(archiveAction("//lib:lib1"), ".o"))
        val obj2: Artifact? = Iterables.getOnlyElement<Artifact?>(inputsEndingWith(archiveAction("//lib:lib2"), ".o"))

        // The exec paths of each obj file should be based on the objc_library target.
        Subject.contains("lib1")
        assertThat(obj1.getExecPathString()).doesNotContain("lib2")
        assertThat(obj2.getExecPathString()).doesNotContain("lib1")
        Subject.contains("lib2")

        val compile1: CommandAction = getGeneratingAction(obj1) as CommandAction
        val compile2: CommandAction = getGeneratingAction(obj2) as CommandAction
        Subject.contains("-Ilib1flag")
        Subject.contains("-Ilib2flag")
    }

    @Test
    @Throws(Exception::class)
    fun testIncludesDirsOfTransitiveDepsGetPassedToCompileAction() {
        createLibraryTargetWriter("//lib1:lib1")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setList("includes", "third_party/foo", "opensource/bar")
            .write()

        createLibraryTargetWriter("//lib2:lib2")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setList("includes", "more_includes")
            .setList("deps", "//lib1:lib1")
            .write()
        val compileAction: CommandAction = compileAction("//lib2:lib2", "a.o")
        // We remove spaces, since the crosstool rules do not use spaces in include paths
        val compileActionArgs: String? =
            Joiner.on("").join(removeConfigFragment(compileAction.getArguments())).replace(" ", "")
        val expectedIncludePaths =
            rootedIncludePaths("lib2/more_includes", "lib1/third_party/foo", "lib1/opensource/bar")
        for (expectedIncludePath in expectedIncludePaths) {
            Truth.assertThat(compileActionArgs).contains("-I" + expectedIncludePath)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testIncludesDirsOfTransitiveCcDepsGetPassedToCompileAction() {
        scratch.file(
            "package/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        cc_library(
            name = "cc_lib",
            srcs = ["a.cc"],
            includes = ["foo/bar"],
        )

        objc_library(
            name = "objc_lib",
            srcs = ["b.m"],
            deps = [":cc_lib"],
        )
        
        """.trimIndent()
        )

        val compileAction: CommandAction = compileAction("//package:objc_lib", "b.o")
        assertContainsSublist(
            removeConfigFragment(removeConfigFragment(compileAction.getArguments())),
            ImmutableList.< E > copyOf < E ? > (
                    Iterables.concat<T?>(
                        Iterables.transform<F?, T?>(
                            rootedIncludePaths("package/foo/bar"),
                            Function { element: F? -> ImmutableList.of<E?>("-I" + element) })
                    ))
        )
    }

    @Test
    @Throws(Exception::class)
    fun testIncludesDirsOfTransitiveCcIncDepsGetPassedToCompileAction() {
        scratch.file(
            "third_party/cc_lib/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        licenses(["unencumbered"])

        cc_library(
            name = "cc_lib_impl",
            srcs = [
                "v1/a.c",
                "v1/a.h",
            ],
        )

        cc_library(
            name = "cc_lib",
            hdrs = ["v1/a.h"],
            strip_include_prefix = "v1",
            deps = [":cc_lib_impl"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "package/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "objc_lib",
            srcs = ["b.m"],
            deps = ["//third_party/cc_lib"],
        )
        
        """.trimIndent()
        )

        val compileAction: CommandAction = compileAction("//package:objc_lib", "b.o")
        // We remove spaces, since the crosstool rules do not use spaces for include paths.
        val compileActionArgs: String? = Joiner.on("").join(compileAction.getArguments()).replace(" ", "")
        Truth.assertThat(compileActionArgs)
            .matches(".*-iquote.*/third_party/cc_lib/_virtual_includes/cc_lib.*")
    }

    @Test
    @Throws(Exception::class)
    fun testIncludesIquoteFlagForGenFilesRoot() {
        createLibraryTargetWriter("//lib:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .write()
        val compileAction: CommandAction = compileAction("//lib:lib", "a.o")
        assertContainsSublist(
            removeConfigFragment(compileAction.getArguments()),
            ImmutableList.of<E?>("-iquote", ObjcRuleTestCase.Companion.OUTPUTDIR)
        )
    }

    @Test
    @Throws(Exception::class)
    fun testCompilesAssemblyAsm() {
        createLibraryTargetWriter("//objc:lib")
            .setAndCreateFiles("srcs", "a.m", "b.asm")
            .setAndCreateFiles("hdrs", "c.h")
            .write()

        val compileAction: CommandAction = compileAction("//objc:lib", "b.o")

        assertThat(compileAction.getArguments()).doesNotContain("-x")
        assertThat(compileAction.getArguments()).doesNotContain("assembler-with-cpp")
        Subject.contains("b.o")
        assertThat(baseArtifactNames(compileAction.getPossibleInputsForTesting()))
            .containsAtLeast("c.h", "b.asm")
    }

    @Test
    @Throws(Exception::class)
    fun testCompilesAssemblyS() {
        createLibraryTargetWriter("//objc:lib")
            .setAndCreateFiles("srcs", "a.m", "b.s")
            .setAndCreateFiles("hdrs", "c.h")
            .write()

        val compileAction: CommandAction = compileAction("//objc:lib", "b.o")

        assertThat(compileAction.getArguments()).doesNotContain("-x")
        assertThat(compileAction.getArguments()).doesNotContain("assembler-with-cpp")
        Subject.contains("b.o")
        assertThat(baseArtifactNames(compileAction.getPossibleInputsForTesting()))
            .containsAtLeast("c.h", "b.s")
    }

    @Test
    @Throws(Exception::class)
    fun testProvidesHdrsAndIncludes() {
        checkProvidesHdrsAndIncludes(RULE_TYPE, Optional.of<String?>("x/private.h"))
    }

    @Test
    @Throws(Exception::class)
    fun testPruningActionsSetLocalityBasedOnXcode() {
        scratch.file(
            "xcode/BUILD",
            """
        load("@build_bazel_apple_support//xcode:available_xcodes.bzl", "available_xcodes")
        load("@build_bazel_apple_support//xcode:xcode_config.bzl", "xcode_config")
        load("@build_bazel_apple_support//xcode:xcode_version.bzl", "xcode_version")

        xcode_version(
            name = "version10_1_0",
            aliases = [
                "10.1",
                "10.1.0",
            ],
            default_ios_sdk_version = "12.1",
            default_macos_sdk_version = "10.14",
            default_tvos_sdk_version = "12.1",
            default_watchos_sdk_version = "5.1",
            version = "10.1.0",
        )

        xcode_version(
            name = "version10_2_1",
            aliases = [
                "10.2.1",
                "10.2",
            ],
            default_ios_sdk_version = "12.2",
            default_macos_sdk_version = "10.14",
            default_tvos_sdk_version = "12.2",
            default_watchos_sdk_version = "5.2",
            version = "10.2.1",
        )

        available_xcodes(
            name = "local",
            default = ":version10_1_0",
            versions = [":version10_1_0"],
        )

        available_xcodes(
            name = "remote",
            default = ":version10_2_1",
            versions = [":version10_2_1"],
        )

        xcode_config(
            name = "my_config",
            local_versions = ":local",
            remote_versions = ":remote",
        )
        
        """.trimIndent()
        )

        useConfigurationWithCustomXcode(
            "--xcode_version=10.2.1",
            "--xcode_version_config=//xcode:my_config",
            "--objc_use_dotd_pruning"
        )
        createLibraryTargetWriter("//lib:lib").setList("srcs", "a.m").write()
        val action: CppCompileAction = compileAction("//lib:lib", "a.o") as CppCompileAction
        assertHasRequirement(action, ExecutionRequirements.REQUIREMENTS_SET)
        assertHasRequirement(action, ExecutionRequirements.NO_LOCAL)
        assertNotHasRequirement(action, ExecutionRequirements.NO_REMOTE)
    }

    @Test
    @Throws(Exception::class)
    fun testUsesDotdPruning() {
        useConfiguration("--objc_use_dotd_pruning")
        createLibraryTargetWriter("//lib:lib").setList("srcs", "a.m").write()
        val compileAction: CppCompileAction = compileAction("//lib:lib", "a.o") as CppCompileAction
        val expected: ActionExecutionException? =
            Assert.assertThrows<T?>(
                ActionExecutionException::class.java,
                ThrowingRunnable {
                    compileAction.discoverInputsFromDotdFiles(
                        ActionExecutionContextBuilder().build(),
                        null,
                        null,
                        null,
                        false,
                        PathMapper.NOOP
                    )
                })
        assertThat(expected).hasMessageThat().contains("error while parsing .d file")
    }

    @Test
    @Throws(Exception::class)
    fun testAppleSdkDefaultPlatformEnv() {
        useConfiguration("--apple_platform_type=ios", "--platforms=" + MockObjcSupport.IOS_X86_64)
        createLibraryTargetWriter("//objc:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setAndCreateFiles("hdrs", "c.h")
            .write()
        val action: CommandAction = compileAction("//objc:lib", "a.o")

        assertAppleSdkPlatformEnv(action, "iPhoneSimulator")
    }

    @Test
    @Throws(Exception::class)
    fun testAppleSdkDevicePlatformEnv() {
        useConfiguration(
            "--apple_platform_type=ios",
            "--ios_multi_cpus=arm64",
            "--platforms=" + MockObjcSupport.IOS_ARM64
        )

        createLibraryTargetWriter("//objc:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setAndCreateFiles("hdrs", "c.h")
            .write()
        val action: CommandAction = compileAction("//objc:lib", "a.o")

        assertAppleSdkPlatformEnv(action, "iPhoneOS")
    }

    @Test
    @Throws(Exception::class)
    fun testObjcImportDoesNotCrash() {
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("@rules_cc//cc:objc_import.bzl", "objc_import")
        objc_library(
            name = "objc",
            srcs = ["source.m"],
            deps = [":import"],
        )

        objc_import(
            name = "import",
            archives = ["archive.a"],
        )
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//x:objc")).isNotNull()
    }

    @Test
    @Throws(Exception::class)
    fun testCompilationActionsWithIQuotesInCopts() {
        useConfiguration("--ios_multi_cpus=arm64")
        createLibraryTargetWriter("//objc:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setAndCreateFiles("hdrs", "c.h")
            .setList("copts", "-iquote foo/bar", "-iquote bam/baz")
            .write()

        val compileActionA: CommandAction = compileAction("//objc:lib", "a.o")
        val action: String? = String.join(" ", compileActionA.getArguments())
        Truth.assertThat(action).contains("-iquote foo/bar")
        Truth.assertThat(action).contains("-iquote bam/baz")
    }

    @Test
    @Throws(Exception::class)
    fun testCollectCodeCoverageWithGCOVFlags() {
        useConfiguration("--collect_code_coverage")
        createLibraryTargetWriter("//objc:x")
            .setAndCreateFiles("srcs", "a.mm", "b.cc", "c.mm", "d.cxx", "e.c", "f.m", "g.C")
            .write()
        val copts = ImmutableList.of<kotlin.String?>("-fprofile-arcs", "-ftest-coverage")
        assertThat(compileAction("//objc:x", "a.o").getArguments()).containsAtLeastElementsIn(copts)
        assertThat(compileAction("//objc:x", "b.o").getArguments()).containsAtLeastElementsIn(copts)
        assertThat(compileAction("//objc:x", "c.o").getArguments()).containsAtLeastElementsIn(copts)
        assertThat(compileAction("//objc:x", "d.o").getArguments()).containsAtLeastElementsIn(copts)
        assertThat(compileAction("//objc:x", "e.o").getArguments()).containsAtLeastElementsIn(copts)
        assertThat(compileAction("//objc:x", "f.o").getArguments()).containsAtLeastElementsIn(copts)
        assertThat(compileAction("//objc:x", "g.o").getArguments()).containsAtLeastElementsIn(copts)
    }

    @Test
    @Throws(Exception::class)
    fun testCollectCodeCoverageWithLLVMCOVFlags() {
        useConfiguration("--collect_code_coverage", "--experimental_use_llvm_covmap")
        createLibraryTargetWriter("//objc:x")
            .setAndCreateFiles("srcs", "a.mm", "b.cc", "c.mm", "d.cxx", "e.c", "f.m", "g.C")
            .write()
        val copts =
            ImmutableList.of<kotlin.String?>("-fprofile-instr-generate", "-fcoverage-mapping")
        assertThat(compileAction("//objc:x", "a.o").getArguments()).containsAtLeastElementsIn(copts)
        assertThat(compileAction("//objc:x", "b.o").getArguments()).containsAtLeastElementsIn(copts)
        assertThat(compileAction("//objc:x", "c.o").getArguments()).containsAtLeastElementsIn(copts)
        assertThat(compileAction("//objc:x", "d.o").getArguments()).containsAtLeastElementsIn(copts)
        assertThat(compileAction("//objc:x", "e.o").getArguments()).containsAtLeastElementsIn(copts)
        assertThat(compileAction("//objc:x", "f.o").getArguments()).containsAtLeastElementsIn(copts)
        assertThat(compileAction("//objc:x", "g.o").getArguments()).containsAtLeastElementsIn(copts)
    }

    @Test
    @Throws(Exception::class)
    fun testNoG0IfGeneratesDsym() {
        useConfiguration("--apple_generate_dsym", "-c", "opt")
        createLibraryTargetWriter("//x:x").setList("srcs", "a.m").write()
        val compileAction: CommandAction = compileAction("//x:x", "a.o")
        assertThat(compileAction.getArguments()).doesNotContain("-g0")
    }

    @Test
    @Throws(Exception::class)
    fun testFilesToCompileOutputGroup() {
        checkFilesToCompileOutputGroup(RULE_TYPE)
    }

    @Test
    @Throws(Exception::class)
    fun testDefaultEnabledFeatureIsUsed() {
        // Although using --ios_multi_cpus=x86_64, it transitions to darwin_x86_64, so the actual
        // cc_toolchain in use will be the darwin_x86_64 one.
        MockObjcSupport.setupCcToolchainConfig(
            mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures("default_feature")
        )
        useConfiguration("--ios_multi_cpus=x86_64")
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "objc",
            srcs = ["source.m"],
        )
        
        """.trimIndent()
        )
        val compileAction: CommandAction = compileAction("//x:objc", "source.o")
        Subject.contains("-dummy")
    }

    @Test
    @Throws(Exception::class)
    fun testHeaderPassedToCcLib() {
        createLibraryTargetWriter("//objc:lib").setList("hdrs", "objc_hdr.h").write()
        ScratchAttributeWriter.fromLabelString(
            this,
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library",
            "//cc:lib"
        )
            .setList("srcs", "a.cc")
            .setList("deps", "//objc:lib")
            .write()
        val compileAction: CommandAction = compileAction("//cc:lib", "a.o")
        Subject.contains("objc/objc_hdr.h")
    }

    @Test
    @Throws(Exception::class)
    fun testTextualHeaderPassedToCcLib() {
        ScratchAttributeWriter.fromLabelString(
            this,
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library",
            "//cc/txt_dep"
        )
            .setList("textual_hdrs", "hdr.h")
            .write()
        createLibraryTargetWriter("//objc:lib").setList("deps", "//cc/txt_dep").write()
        ScratchAttributeWriter.fromLabelString(
            this,
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library",
            "//cc/lib"
        )
            .setList("srcs", "a.cc")
            .setList("deps", "//objc:lib")
            .write()
        val compileAction: CommandAction = compileAction("//cc/lib", "a.o")
        Subject.contains("cc/txt_dep/hdr.h")
    }

    /** Regression test for https://github.com/bazelbuild/bazel/issues/7721.  */
    @Test
    @Throws(Exception::class)
    fun testToolchainRuntimeLibrariesSolibDir() {
        MockObjcSupport.setupCcToolchainConfig(
            mockToolsConfig,
            MockObjcSupport.darwinX86_64()
                .withFeatures(
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER
                )
        )
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc:cc_test.bzl", "cc_test")
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        cc_test(
            name = "d",
            deps = [":b"],
        )

        objc_library(
            name = "b",
            deps = [":a"],
        )

        cc_library(
            name = "a",
            srcs = ["a.c"],
        )
        
        """.trimIndent()
        )
        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//foo:d")
        assertThat(configuredTarget).isNotNull()
    }

    @Test
    @Throws(Exception::class)
    fun testDirectFields() {
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "foo",
            srcs = [
                "foo.m",
                "foo_impl.h",
            ],
            hdrs = ["foo.h"],
            textual_hdrs = ["foo.inc"],
        )

        objc_library(
            name = "bar",
            srcs = [
                "bar.m",
                "bar_impl.h",
            ],
            hdrs = ["bar.h"],
            textual_hdrs = ["bar.inc"],
            deps = [":foo"],
        )
        
        """.trimIndent()
        )

        val dependerProvider: StarlarkInfo = ObjcRuleTestCase.Companion.getObjcInfo(getConfiguredTarget("//x:bar"))
        assertThat(baseArtifactNames(ObjcRuleTestCase.Companion.getDirectSources(dependerProvider)))
            .containsExactly("bar.m", "bar_impl.h")

        val target: ConfiguredTarget = getConfiguredTarget("//x:bar")
        val ccCompilationContext: CcCompilationContext = CcInfo.get(target).getCcCompilationContext()
        assertThat(baseArtifactNames(ccCompilationContext.getDirectPublicHdrs()))
            .containsExactly("bar.h")
        assertThat(baseArtifactNames(ccCompilationContext.getDirectPrivateHdrs()))
            .containsExactly("bar_impl.h")
        assertThat(baseArtifactNames(ccCompilationContext.getTextualHdrs())).containsExactly("bar.inc")

        // Verify that the CppModuleMap objects are not added twice when merging the ARC and non-ARC
        // contexts.
        assertThat(ccCompilationContext.getExportingModuleMaps()).hasSize(1)
    }

    @Test
    @Throws(Exception::class)
    fun testNameHasSlash() {
        scratch.file("x/foo.m")
        checkError(
            "x",
            "foo/bar",
            "this attribute has unsupported character '/'",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            "objc_library(name = 'foo/bar', srcs = ['foo.m'])"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testObjcLibraryLoadedThroughMacro() {
        setupTestObjcLibraryLoadedThroughMacro( /* loadMacro= */true)
        assertThat(getConfiguredTarget("//a:a")).isNotNull()
        assertNoEvents()
    }

    @Throws(Exception::class)
    private fun setupTestObjcLibraryLoadedThroughMacro(loadMacro: Boolean) {
        scratch.file(
            "a/BUILD",
            getAnalysisMock().ccSupport().getMacroLoadStatement(loadMacro, "objc_library"),
            "objc_library(name='a', srcs=['a.cc'])"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testGenerateDsymFlagPropagatesToObjcLibraryFeature() {
        useConfiguration("--apple_generate_dsym")
        createLibraryTargetWriter("//objc/lib").setList("srcs", "a.m").write()
        val compileAction: CommandAction = compileAction("//objc/lib", "a.o")
        Subject.contains("-DDUMMY_GENERATE_DSYM_FILE")
    }

    @Test
    @Throws(Exception::class)
    fun testArtifactsToAlwaysBuild() {
        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
                "objc_library(name = 'x', srcs = ['x.m'], non_arc_srcs = ['x2.m'], deps = [':y'])",
                "objc_library(name = 'y', srcs = ['y.m'], non_arc_srcs = ['y2.m'], )"
            )
        assertThat(
            ActionsTestUtil.sortedBaseNamesOf(getOutputGroup(x, OutputGroupInfo.HIDDEN_TOP_LEVEL))
        )
            .isEqualTo("x.o x2.o y.o y2.o")
    }

    @Test
    @Throws(Exception::class)
    fun testLangObjcFeature() {
        MockObjcSupport.setupCcToolchainConfig(
            mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS)
        )
        useConfiguration("--features=parse_headers", "--process_headers_in_dependencies")

        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
                "objc_library(name = 'x', hdrs = ['x.h'])"
            )

        Subject.contains("-DDUMMY_LANG_OBJC")
    }

    private fun getGeneratingCompileAction(
        packageRelativePath: kotlin.String?, owner: ConfiguredTarget
    ): CppCompileAction {
        return getGeneratingAction(getBinArtifact(packageRelativePath, owner)) as CppCompileAction
    }

    @Test
    @Throws(Exception::class)
    fun testProcessHeadersInArcOnly() {
        MockObjcSupport.setupCcToolchainConfig(
            mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS)
        )
        useConfiguration("--features=parse_headers", "--process_headers_in_dependencies")
        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
                "objc_library(name = 'x', hdrs = ['x.h'])"
            )
        val ccCompilationContext: CcCompilationContext = CcInfo.get(x).getCcCompilationContext()
        assertThat(Artifact.toRootRelativePaths(ccCompilationContext.getHeaderTokens()))
            .containsExactly("foo/_objs/x/arc/x.h.processed")
    }

    @Test
    @Throws(Exception::class)
    fun testProcessHeadersInDependencies() {
        MockObjcSupport.setupCcToolchainConfig(
            mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS)
        )
        useConfiguration("--features=parse_headers", "--process_headers_in_dependencies")
        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
                "objc_library(name = 'x', deps = [':y'])",
                "objc_library(name = 'y', hdrs = ['y.h'])"
            )
        val ccCompilationContext: CcCompilationContext = CcInfo.get(x).getCcCompilationContext()
        assertThat(ActionsTestUtil.baseNamesOf(ccCompilationContext.getHeaderTokens()))
            .isEqualTo("y.h.processed")
        assertThat(ActionsTestUtil.baseNamesOf(getOutputGroup(x, OutputGroupInfo.HIDDEN_TOP_LEVEL)))
            .isEqualTo("y.h.processed")
    }

    @Test
    @Throws(Exception::class)
    fun testProcessHeadersInDependenciesOfCcBinary() {
        MockObjcSupport.setupCcToolchainConfig(
            mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS)
        )
        useConfiguration("--features=parse_headers", "--process_headers_in_dependencies")
        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
                "cc_binary(name = 'x', deps = [':y', ':z'])",
                "cc_library(name = 'y', hdrs = ['y.h'])",
                "objc_library(name = 'z', srcs = ['z.h'])"
            )
        val validation: kotlin.String? = ActionsTestUtil.baseNamesOf(getOutputGroup(x, OutputGroupInfo.VALIDATION))
        Truth.assertThat(validation).contains("y.h.processed")
        Truth.assertThat(validation).contains("z.h.processed")
    }

    @Test
    @Throws(Exception::class)
    fun testSrcCompileActionMnemonic() {
        MockObjcSupport.setupCcToolchainConfig(
            mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS)
        )
        useConfiguration("--features=parse_headers", "--process_headers_in_dependencies")

        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
                "objc_library(name = 'x', srcs = ['a.m'])"
            )

        assertThat(getGeneratingCompileAction("_objs/x/arc/a.o", x).getMnemonic())
            .isEqualTo("ObjcCompile")
    }

    @Test
    @Throws(Exception::class)
    fun testHeaderCompileActionMnemonic() {
        MockObjcSupport.setupCcToolchainConfig(
            mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS)
        )
        useConfiguration("--features=parse_headers", "--process_headers_in_dependencies")

        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
                "objc_library(name = 'x', srcs = ['y.h'], hdrs = ['z.h'])"
            )

        assertThat(getGeneratingCompileAction("_objs/x/arc/y.h.processed", x).getMnemonic())
            .isEqualTo("ObjcCompile")
        assertThat(getGeneratingCompileAction("_objs/x/arc/z.h.processed", x).getMnemonic())
            .isEqualTo("ObjcCompile")
    }

    @Test
    @Throws(Exception::class)
    fun testIncompatibleUseCppCompileHeaderMnemonic() {
        MockObjcSupport.setupCcToolchainConfig(
            mockToolsConfig, MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.PARSE_HEADERS)
        )
        useConfiguration(
            "--incompatible_use_cpp_compile_header_mnemonic",
            "--features=parse_headers",
            "--process_headers_in_dependencies"
        )

        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
                "objc_library(name = 'x', srcs = ['a.m', 'y.h'], hdrs = ['z.h'])"
            )

        assertThat(getGeneratingCompileAction("_objs/x/arc/a.o", x).getMnemonic())
            .isEqualTo("ObjcCompile")
        assertThat(getGeneratingCompileAction("_objs/x/arc/y.h.processed", x).getMnemonic())
            .isEqualTo("ObjcCompileHeader")
        assertThat(getGeneratingCompileAction("_objs/x/arc/z.h.processed", x).getMnemonic())
            .isEqualTo("ObjcCompileHeader")
    }

    @Test
    @Throws(Exception::class)
    fun testAlwaysLinkDefaultFalse() {
        useConfiguration("--incompatible_objc_alwayslink_by_default=false")
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)

        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "objc_bin",
            platform_type = "ios",
            deps = [":main_lib"],
        )

        objc_library(
            name = "main_lib",
            srcs = ["b.m"],
        )
        
        """.trimIndent()
        )

        val testLinkAction: CommandAction = linkAction("//test:objc_bin")
        Truth.assertThat(Joiner.on(" ").join(testLinkAction.getArguments())).doesNotContain("-force_load")
    }

    @Test
    @Throws(Exception::class)
    fun testAlwaysLinkDefaultTrue() {
        useConfiguration("--incompatible_objc_alwayslink_by_default")
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)

        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "objc_bin",
            platform_type = "ios",
            deps = [":main_lib"],
        )

        objc_library(
            name = "main_lib",
            srcs = ["b.m"],
        )
        
        """.trimIndent()
        )
        scratch.file("test/b.m", "// dummy file")

        val testLinkAction: CommandAction = linkAction("//test:objc_bin")
        Truth.assertThat(Joiner.on(" ").join(testLinkAction.getArguments()))
            .containsMatch("-force_load [^ ]+-out/[^ ]+/test/libmain_lib.lo")
    }

    @Test
    @Throws(Exception::class)
    fun testAlwaysLinkTrueDefaultFalse() {
        useConfiguration("--incompatible_objc_alwayslink_by_default=false")
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)

        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "objc_bin",
            platform_type = "ios",
            deps = [":main_lib"],
        )

        objc_library(
            name = "main_lib",
            srcs = ["b.m"],
            alwayslink = True,
        )
        
        """.trimIndent()
        )

        val testLinkAction: CommandAction = linkAction("//test:objc_bin")
        Truth.assertThat(Joiner.on(" ").join(testLinkAction.getArguments()))
            .containsMatch("-force_load [^ ]+-out/[^ ]+/test/libmain_lib.lo")
    }

    @Test
    @Throws(Exception::class)
    fun testAlwaysLinkFalseDefaultTrue() {
        useConfiguration("--incompatible_objc_alwayslink_by_default")
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)

        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "objc_bin",
            platform_type = "ios",
            deps = [":main_lib"],
        )

        objc_library(
            name = "main_lib",
            srcs = ["b.m"],
            alwayslink = False,
        )
        
        """.trimIndent()
        )

        val testLinkAction: CommandAction = linkAction("//test:objc_bin")
        Truth.assertThat(Joiner.on(" ").join(testLinkAction.getArguments())).doesNotContain("-force_load")
    }

    @Test
    @Throws(Exception::class)
    fun testLinkActionMnemonic() {
        scratchConfiguredTarget(
            "foo",
            "x",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            "objc_library(name = 'x', srcs = ['a.m'])"
        )

        val archiveAction: SpawnAction = archiveAction("//foo:x") as SpawnAction
        assertThat(archiveAction.getMnemonic()).isEqualTo("CppArchive")
    }

    @Test
    @Throws(Exception::class)
    fun testPassesThroughLinkstamps() {
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "foo",
            deps = [":bar"],
        )

        cc_library(
            name = "bar",
            linkstamp = "bar.cc",
        )
        
        """.trimIndent()
        )

        val ccLinkingContext: CcLinkingContext =
            CcInfo.get(getConfiguredTarget("//x:foo")).getCcLinkingContext()
        assertThat(
            ccLinkingContext.getLinkerInputs().toList().stream()
                .flatMap({ linkerInput -> getLinkstamps(linkerInput)!!.stream() })
                .map({ linkstamp: StarlarkInfo -> getLinkstampFile(linkstamp) })
                .map(Artifact::getExecPathString)
        )
            .containsExactly("x/bar.cc")
    }

    @Test
    @Throws(Exception::class)
    fun testCompileLanguageApi() {
        var fragments = "    fragments = ['google_cpp', 'cpp'],"
        if (AnalysisMock.get().isThisBazel()) {
            fragments = "    fragments = ['cpp'],"
        }
        scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()")
        scratch.file("myinfo/BUILD")
        scratch.overwriteFile("tools/build_defs/foo/BUILD")
        scratch.file(
            "tools/build_defs/foo/extension.bzl",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "load('@rules_cc//cc/common:cc_info.bzl', 'CcInfo')",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _objc_starlark_library_impl(ctx):",
            "    toolchain = ctx.attr._my_cc_toolchain[cc_common.CcToolchainInfo]",
            "    features = ['objc-compile']",
            "    features.extend(ctx.features)",
            "    feature_configuration = cc_common.configure_features(",
            "        ctx = ctx,",
            "        cc_toolchain=toolchain,",
            "        requested_features = features,",
            "        unsupported_features = ctx.disabled_features)",
            "    foo_dict = {'string_variable': 'foo',",
            "            'string_sequence_variable' : ['foo'],",
            "            'string_depset_variable': depset(['foo'])}",
            "    (compilation_context, compilation_outputs) = cc_common.compile(",
            "        actions=ctx.actions,",
            "        feature_configuration=feature_configuration,",
            "        cc_toolchain=toolchain,",
            "        srcs=ctx.files.srcs,",
            "        name=ctx.label.name + '_suffix',",
            "        language='objc'",
            "    )",
            "    (linking_context,",
            "     linking_outputs) = cc_common.create_linking_context_from_compilation_outputs(",
            "        actions=ctx.actions,",
            "        feature_configuration=feature_configuration,",
            "        compilation_outputs=compilation_outputs,",
            "        name = ctx.label.name,",
            "        cc_toolchain=toolchain,",
            "        language='c++'",
            "    )",
            "    files_to_build = []",
            "    files_to_build.extend(compilation_outputs.pic_objects)",
            "    files_to_build.extend(compilation_outputs.objects)",
            "    library_to_link = None",
            "    if len(ctx.files.srcs) > 0:",
            "        library_to_link = linking_outputs.library_to_link",
            "        if library_to_link.pic_static_library != None:",
            "            files_to_build.append(library_to_link.pic_static_library)",
            "        files_to_build.append(library_to_link.dynamic_library)",
            "    return [MyInfo(libraries=[library_to_link]),",
            "            DefaultInfo(files=depset(files_to_build)),",
            "            CcInfo(compilation_context=compilation_context,",
            "                   linking_context=linking_context)]",
            "objc_starlark_library = rule(",
            "    implementation = _objc_starlark_library_impl,",
            "    attrs = {",
            "      'srcs': attr.label_list(allow_files=True),",
            "      '_my_cc_toolchain': attr.label(default =",
            "          '//a:alias')",
            "    },",
            fragments,
            ")"
        )
        scratch.file(
            "foo/BUILD",
            """
        load("//tools/build_defs/foo:extension.bzl", "objc_starlark_library")

        objc_starlark_library(
            name = "starlark_lib",
            srcs = ["starlark_lib.m"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc/toolchains:cc_toolchain_alias.bzl',"
                    + " 'cc_toolchain_alias')",
            "cc_toolchain_alias(name='alias')"
        )
        getConfiguredTarget("//foo:starlark_lib")
        assertNoEvents()
    }

    @Test
    @Throws(Exception::class)
    fun testCcTestUsesStaticLibraries() {
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc:cc_test.bzl", "cc_test")
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        cc_test(
            name = "test",
            deps = [":foo"],
        )

        objc_library(
            name = "foo",
            deps = [":bar"],
        )

        cc_library(
            name = "bar",
            srcs = [
                "bar.a",
                "bar.so",
            ],
        )
        
        """.trimIndent()
        )

        Truth.assertThat(
            artifactsToStrings(
                getGeneratingAction(
                    getConfiguredTarget("//x:test")
                        .getProvider(FilesToRunProvider::class.java)
                        .getExecutable()
                )
                    .getInputs()
            )
        )
            .contains("src x/bar.a")
    }

    @Test
    @Throws(Exception::class)
    fun testPassesDependenciesStaticLibrariesInCcInfo() {
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "baz",
            srcs = ["baz.mm"],
        )

        objc_library(
            name = "foo",
            srcs = ["foo.mm"],
            deps = [":baz"],
        )

        cc_library(
            name = "bar",
            srcs = ["bar.cc"],
            deps = [":foo"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--platforms=" + MockObjcSupport.DARWIN_X86_64)

        val ccInfo: CcInfo = CcInfo.get(getConfiguredTarget("//x:bar"))

        Truth.assertThat(
            artifactsToStrings(
                ccInfo.getCcLinkingContext().getLinkerInputs().toList().stream()
                    .map(LinkerInput::getLibraries)
                    .flatMap({ obj: MutableList<*>? -> obj!!.stream() })
                    .map(LibraryToLink::getStaticLibrary)
                    .collect(ImmutableList.toImmutableList<E?>())
            )
        )
            .contains("bin x/libbaz.a")
    }

    @Test
    @Throws(Exception::class)
    fun testGrepIncludesPassed() {
        if (analysisMock!!.isThisBazel) {
            return
        }
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "foo",
            srcs = ["foo.mm"],
        )
        
        """.trimIndent()
        )

        val compileA: CppCompileAction = compileAction("//x:foo", "foo.o") as CppCompileAction
        assertThat(compileA.getGrepIncludes()).isNotNull()
    }

    @Test
    @Throws(Exception::class)
    fun correctToolFilesUsed() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        cc_toolchain_alias(name = "a")

        objc_library(
            name = "l",
            srcs = ["l.m"],
        )

        objc_library(
            name = "asm",
            srcs = ["a.s"],
        )

        objc_library(
            name = "preprocessed-asm",
            srcs = ["a.S"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_use_specific_tool_files")

        val target: ConfiguredTarget = getConfiguredTarget("//a:a")
        val toolchainProvider: CcToolchainProvider = CcToolchainProvider.getFromTarget(target)

        val libTarget: RuleConfiguredTarget = getConfiguredTarget("//a:l") as RuleConfiguredTarget
        val archiveAction: ActionAnalysisMetadata =
            libTarget.getActions().stream()
                .filter({ a -> a.getMnemonic().equals("CppArchive") })
                .collect(MoreCollectors.onlyElement<T?>())
        assertThat(archiveAction.getInputs().toList())
            .containsAtLeastElementsIn(toolchainProvider.getArFiles().toList())

        val objcCompileAction: ActionAnalysisMetadata =
            libTarget.getActions().stream()
                .filter({ a -> a.getMnemonic().equals("ObjcCompile") })
                .collect(MoreCollectors.onlyElement<T?>())
        assertThat(objcCompileAction.getInputs().toList())
            .containsAtLeastElementsIn(toolchainProvider.getCompilerFiles().toList())

        val asmAction: ActionAnalysisMetadata =
            (getConfiguredTarget("//a:asm") as RuleConfiguredTarget)
                .getActions().stream()
                .filter({ a -> a.getMnemonic().equals("CppCompile") })
                .collect(MoreCollectors.onlyElement<T?>())
        assertThat(asmAction.getInputs().toList())
            .containsAtLeastElementsIn(toolchainProvider.getAsFiles().toList())

        val preprocessedAsmAction: ActionAnalysisMetadata =
            (getConfiguredTarget("//a:preprocessed-asm") as RuleConfiguredTarget)
                .getActions().stream()
                .filter({ a -> a.getMnemonic().equals("CppCompile") })
                .collect(MoreCollectors.onlyElement<T?>())
        assertThat(preprocessedAsmAction.getInputs().toList())
            .containsAtLeastElementsIn(toolchainProvider.getCompilerFiles().toList())
    }

    /** b/197608223  */
    @Test
    @Throws(Exception::class)
    fun testCompilationPrerequisitesHasHeaders() {
        scratch.file(
            "bin/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "objc",
            srcs = ["objc.m"],
            deps = [":cc"],
        )

        cc_library(
            name = "cc",
            srcs = ["cc.cc"],
            hdrs = ["cc.h"],
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--apple_platform_type=ios",
            "--ios_multi_cpus=x86_64",
            "--platforms=" + MockObjcSupport.IOS_X86_64
        )

        val cc: ConfiguredTarget = getConfiguredTarget("//bin:objc")

        Truth.assertThat(
            artifactsToStrings(
                cc.get(OutputGroupInfo.STARLARK_CONSTRUCTOR)
                    .getOutputGroup(OutputGroupInfo.COMPILATION_PREREQUISITES)
            )
        )
            .contains("src bin/cc.h")
    }

    @Test
    @Throws(Exception::class)
    fun testCoptsLocationIsExpanded() {
        scratch.file(
            "bin/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            "objc_library(",
            "    name = 'lib',",
            "    copts = ['$(rootpath lib1.m) $(location lib2.m) $(location data.data) $(execpath"
                    + " header.h)'],",
            "    srcs = ['lib1.m'],",
            "    non_arc_srcs = ['lib2.m'],",
            "    data = ['data.data', 'lib2.m'],",
            "    hdrs = ['header.h'],",
            ")"
        )

        useConfiguration(
            "--apple_platform_type=ios",
            "--ios_multi_cpus=x86_64",
            "--platforms=" + MockObjcSupport.IOS_X86_64
        )

        val compileA: CppCompileAction = compileAction("//bin:lib", "lib1.o") as CppCompileAction
        assertThat(compileA.compileCommandLine.getCopts(PathMapper.NOOP))
            .containsAtLeast("bin/lib1.m", "bin/lib2.m", "bin/data.data", "bin/header.h")
    }

    @Test
    @Throws(Exception::class)
    fun testCoptsLocationWhenNotExpanded_throwsAssertionError() {
        scratch.file(
            "bin/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "lib",
            srcs = ["lib1.m"],
            hdrs = ["header.h"],
            copts = ["${'$'}(execpath lib2.m)"],
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--apple_platform_type=ios",
            "--ios_multi_cpus=x86_64",
            "--platforms=" + MockObjcSupport.IOS_X86_64
        )

        Assert.assertThrows<AssertionError?>(
            AssertionError::class.java,
            ThrowingRunnable { compileAction("//bin:lib", "lib1.o") })
    }

    @Test
    @Throws(Exception::class)
    fun testEnableCoveragePropagatesSupportFiles() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        cc_toolchain_alias(name = "toolchain")

        objc_library(
            name = "lib",
        )
        
        """.trimIndent()
        )
        useConfiguration("--collect_code_coverage", "--instrumentation_filter=//a[:/]")

        val ccToolchainProvider: CcToolchainProvider =
            CcToolchainProvider.getFromTarget(getConfiguredTarget("//a:toolchain"))
        val instrumentedFilesInfo: InstrumentedFilesInfo =
            getConfiguredTarget("//a:lib").get(InstrumentedFilesInfo.provider)

        assertThat(instrumentedFilesInfo.getCoverageSupportFiles().toList()).isNotEmpty()
        assertThat(instrumentedFilesInfo.getCoverageSupportFiles().toList())
            .containsExactlyElementsIn(ccToolchainProvider.getCoverageFiles().toList())
    }

    @Test
    @Throws(Exception::class)
    fun testDisableCoverageDoesNotPropagateSupportFiles() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        cc_toolchain_alias(name = "toolchain")

        objc_library(
            name = "lib",
        )
        
        """.trimIndent()
        )

        val instrumentedFilesInfo: InstrumentedFilesInfo =
            getConfiguredTarget("//a:lib").get(InstrumentedFilesInfo.provider)

        assertThat(instrumentedFilesInfo.getCoverageSupportFiles().toList()).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testCoverageMetadataFiles() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        cc_toolchain_alias(name = "toolchain")

        objc_library(
            name = "foo",
            srcs = ["foo.m"],
        )

        objc_library(
            name = "bar",
            srcs = ["bar.m"],
            deps = [":foo"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--collect_code_coverage", "--instrumentation_filter=//a[:/]")

        val instrumentedFilesInfo: InstrumentedFilesInfo =
            getConfiguredTarget("//a:bar").get(InstrumentedFilesInfo.provider)

        assertThat(
            Artifact.toRootRelativePaths(instrumentedFilesInfo.getInstrumentationMetadataFiles())
        )
            .containsExactly("a/_objs/foo/arc/foo.gcno", "a/_objs/bar/arc/bar.gcno")
    }

    @Throws(LabelSyntaxException::class, RuleErrorException::class)
    private fun getCcInfoUserLinkFlagsFromTarget(target: kotlin.String?): ImmutableList<kotlin.String?> {
        return CcInfo.get(getConfiguredTarget(target))
            .getCcLinkingContext()
            .getLinkerInputs()
            .toList()
            .stream()
            .flatMap({ linkerInput -> LinkerInput.getUserLinkFlags(linkerInput).stream() })
            .collect(ImmutableList.toImmutableList<E?>())
    }

    @Test
    @Throws(Exception::class)
    fun testSdkUserLinkFlagsFromSdkFieldsAndLinkoptsArePropagatedOnCcInfo() {
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "foo",
            linkopts = [
                "-lxml2",
                "-framework AVFoundation",
                "-Wl,-framework,Framework",
            ],
            sdk_dylibs = ["libz"],
            sdk_frameworks = ["CoreData"],
            deps = [
                ":bar",
                ":car",
            ],
        )

        objc_library(
            name = "bar",
            linkopts = [
                "-lsqlite3",
                "-Wl,-weak_framework,WeakFrameworkFromLinkOpt",
            ],
            sdk_frameworks = ["Foundation"],
        )

        objc_library(
            name = "car",
            linkopts = [
                "-framework UIKit",
            ],
            sdk_dylibs = ["libc++"],
            weak_sdk_frameworks = ["WeakFramework"],
        )
        
        """.trimIndent()
        )

        val userLinkFlags = getCcInfoUserLinkFlagsFromTarget("//x:foo")
        Truth.assertThat(userLinkFlags).isNotEmpty()
        Truth.assertThat(userLinkFlags).containsAtLeast("-framework", "AVFoundation").inOrder()
        Truth.assertThat(userLinkFlags).containsAtLeast("-framework", "CoreData").inOrder()
        Truth.assertThat(userLinkFlags).containsAtLeast("-framework", "Foundation").inOrder()
        Truth.assertThat(userLinkFlags).containsAtLeast("-framework", "UIKit").inOrder()
        Truth.assertThat(userLinkFlags).containsAtLeast("-lz", "-lc++", "-lxml2", "-lsqlite3")
        Truth.assertThat(userLinkFlags).containsAtLeast("-framework", "Framework").inOrder()
        Truth.assertThat(userLinkFlags).containsAtLeast("-weak_framework", "WeakFramework").inOrder()
        Truth.assertThat(userLinkFlags)
            .containsAtLeast("-weak_framework", "WeakFrameworkFromLinkOpt")
            .inOrder()
    }

    @Test
    @Throws(Exception::class)
    fun testTreeArtifactSrcs() {
        doTestTreeAtrifactInAttributes("srcs")
    }

    @Test
    @Throws(Exception::class)
    fun testTreeArtifactNonArcSrcs() {
        doTestTreeAtrifactInAttributes("non_arc_srcs")
    }

    @Test
    @Throws(Exception::class)
    fun testTreeArtifactHdrs() {
        doTestTreeAtrifactInAttributes("hdrs")
    }

    @Throws(Exception::class)
    private fun doTestTreeAtrifactInAttributes(attrName: kotlin.String?) {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "bar/create_tree_artifact.bzl",
            """
        def _impl(ctx):
            tree = ctx.actions.declare_directory("dir")
            ctx.actions.run_shell(
                outputs = [tree],
                inputs = [],
                arguments = [tree.path],
                command = "mkdir ${'$'}1",
            )
            return [DefaultInfo(files = depset([tree]))]

        create_tree_artifact = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "bar/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            "load(':create_tree_artifact.bzl', 'create_tree_artifact')",
            "create_tree_artifact(name = 'tree_artifact')",
            "objc_library(",
            "    name = 'lib',",
            "    " + attrName + " = [':tree_artifact'],",
            ")"
        )

        getConfiguredTarget("//bar:lib")

        assertNoEvents()
    }

    @Test
    @Throws(Exception::class)
    fun testObjcTransitionWithTopLevelApplePlatforms(
        @TestParameter usePlatformsInAppleCrosstoolTransition: Boolean
    ) {
        scratch.file(
            "bin/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        objc_library(
            name = "objc",
            srcs = ["objc.m"],
        )

        cc_binary(
            name = "cc",
            srcs = ["cc.cc"],
            deps = [":objc"],
        )
        
        """.trimIndent()
        )

        setBuildLanguageOptions("--noincompatible_disable_objc_library_transition")
        val args = ImmutableList.builder<kotlin.String?>()
        args.add(
            "--apple_platform_type=ios",
            "--platforms=" + MockObjcSupport.IOS_ARM64,
            "--experimental_platform_in_output_dir",
            "--use_platforms_in_apple_crosstool_transition=" + usePlatformsInAppleCrosstoolTransition
        )
        if (!usePlatformsInAppleCrosstoolTransition) {
            args.add("--cpu=ios_arm64")
        }
        useConfiguration(*args.build().toTypedArray<kotlin.String?>())

        val cc: ConfiguredTarget = getConfiguredTarget("//bin:cc")
        val objcObject: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(
                actionsTestUtil().artifactClosureOf(getFilesToBuild(cc)), "objc.o"
            )
        Subject.contains("ios_arm64")
    }

    @Test
    @Throws(Exception::class)
    fun testObjcTransitionInExecConfig(
        @TestParameter usePlatformsInAppleCrosstoolTransition: Boolean
    ) {
        scratch.file(
            "bin/defs.bzl",
            """
        def _impl(ctx):
          return [DefaultInfo(files = ctx.attr.dep[DefaultInfo].files)]
        my_rule = rule(
            implementation = _impl,
            attrs = {"dep": attr.label(cfg = 'exec')}
        )
        
        """.trimIndent()
        )
        scratch.file(
            "bin/BUILD",
            """
        load("@rules_cc//cc:objc_library.bzl", "objc_library")
        load(":defs.bzl", "my_rule")

        objc_library(
            name = "objc",
            srcs = ["objc.m"],
        )

        my_rule(
            name = "t1",
            dep = ":objc",
        )
        
        """.trimIndent()
        )

        setBuildLanguageOptions("--noincompatible_disable_objc_library_transition")
        val args = ImmutableList.builder<kotlin.String?>()
        args.add(
            "--apple_platform_type=ios",
            "--platforms=" + MockObjcSupport.IOS_ARM64,
            "--experimental_platform_in_output_dir",
            "--use_platforms_in_apple_crosstool_transition=" + usePlatformsInAppleCrosstoolTransition,
            "--host_platform=" + MockObjcSupport.DARWIN_ARM64
        )
        if (!usePlatformsInAppleCrosstoolTransition) {
            args.add("--host_cpu=darwin_arm64")
        }
        useConfiguration(*args.build().toTypedArray<kotlin.String?>())

        val t1: ConfiguredTarget = getConfiguredTarget("//bin:t1")
        val objcObject: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(
                actionsTestUtil().artifactClosureOf(getFilesToBuild(t1)), "objc.o"
            )
        val execPath: kotlin.String? = objcObject.getExecPathString()
        Truth.assertThat(execPath).contains("darwin_arm64")
        Truth.assertThat(execPath).doesNotContain("-ST-")
    }

    companion object {
        private val RULE_TYPE: RuleType = OnlyNeedsSourcesRuleType("objc_library")
        private const val WRAPPED_CLANG = "wrapped_clang"

        private fun getLinkstamps(linkerInput: StarlarkInfo): MutableList<StarlarkInfo?>? {
            try {
                val linkstamps: MutableList<StarlarkInfo?>? =
                    linkerInput.getValue("linkstamps", MutableList::class.java) as MutableList<StarlarkInfo?>?
                return linkstamps
            } catch (e: EvalException) {
                return ImmutableList.of<StarlarkInfo?>()
            }
        }

        private fun getLinkstampFile(linkstamp: StarlarkInfo): Artifact? {
            try {
                Mutability.create().use { mu ->
                    val func: StarlarkFunction? = linkstamp.getValue("file", StarlarkFunction::class.java)
                    val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
                    return Starlark.positionalOnlyCall(thread, func) as Artifact?
                }
            } catch (e: EvalException) {
                throw RuntimeException(e)
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            }
        }
    }
}
