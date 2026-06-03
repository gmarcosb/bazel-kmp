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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Action

/** A test for [CcCommon].  */
@RunWith(JUnit4::class)
class CcCommonTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun createBuildFiles() {
        // Having lots of setUp code leads to bad running time. Don't add anything here!
        scratch.file(
            "empty/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(name = "emptylib")

        cc_binary(name = "emptybinary")
        
        """.trimIndent()
        )

        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "foo",
            srcs = ["foo.cc"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "bar/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "bar",
            srcs = ["bar.cc"],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyBinary() {
        val emptybin: ConfiguredTarget = getConfiguredTarget("//empty:emptybinary")
        assertThat(baseNamesOf(getFilesToBuild(emptybin)))
            .isEqualTo("emptybinary")
    }

    @Throws(java.lang.Exception::class)
    private fun getCopts(target: String?): MutableList<String?> {
        val cLib: ConfiguredTarget = getConfiguredTarget(target)
        val `object`: Artifact = getOutputGroup(cLib, OutputGroupInfo.FILES_TO_COMPILE).getSingleton()
        val compileAction: CppCompileAction = getGeneratingAction(`object`) as CppCompileAction
        return compileAction.getCompilerOptions()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCoptsTokenization() {
        scratch.file(
            "copts/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "c_lib",
            srcs = ["foo.cc"],
            copts = ["-Wmy-warning -frun-faster"],
        )
        
        """.trimIndent()
        )
        val copts = getCopts("//copts:c_lib")
        Truth.assertThat(copts).containsAtLeast("-Wmy-warning", "-frun-faster")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCoptsNoTokenization() {
        scratch.file(
            "copts/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        package(features = ["no_copts_tokenization"])

        cc_library(
            name = "c_lib",
            srcs = ["foo.cc"],
            copts = ["-Wmy-warning -frun-faster"],
        )
        
        """.trimIndent()
        )
        val copts = getCopts("//copts:c_lib")
        Truth.assertThat(copts).contains("-Wmy-warning -frun-faster")
    }

    /**
     * Test that we handle ".a" files in cc_library srcs correctly when linking dynamically. In
     * particular, if srcs contains only the ".a" file for a library, with no corresponding ".so",
     * then we need to link in the ".a" file even when we're linking dynamically. If srcs contains
     * both ".a" and ".so" then we should only link in the ".so".
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArchiveInCcLibrarySrcs() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        val archiveInSrcsTest: ConfiguredTarget =
            scratchConfiguredTarget(
                "archive_in_srcs",
                "archive_in_srcs_test",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "load('@rules_cc//cc:cc_test.bzl', 'cc_test')",
                "cc_test(name = 'archive_in_srcs_test',",
                "           srcs = ['archive_in_srcs_test.cc'],",
                "           deps = [':archive_in_srcs_lib'],",
                "           linkstatic = 0,)",
                "cc_library(name = 'archive_in_srcs_lib',",
                "           srcs = ['libstatic.a', 'libboth.a', 'libboth.so'])"
            )
        val artifactNames: MutableList<String?>? = baseArtifactNames(getLinkerInputs(archiveInSrcsTest))
        Truth.assertThat(artifactNames).containsAtLeast("libboth.so", "libstatic.a")
        Truth.assertThat(artifactNames).doesNotContain("libboth.a")
    }

    private fun getLinkerInputs(target: ConfiguredTarget?): Iterable<Artifact?> {
        val executable: Artifact = getExecutable(target)
        val linkAction: SpawnAction = getGeneratingAction(executable) as SpawnAction
        return linkAction.getInputs().toList()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDylibLibrarySuffixIsStripped() {
        val archiveInSrcsTest: ConfiguredTarget =
            scratchConfiguredTarget(
                "archive_in_src_darwin",
                "archive_in_srcs",
                "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
                "cc_binary(name = 'archive_in_srcs',",
                "    srcs = ['libarchive.34.dylib'])"
            )

        val executable: Artifact = getExecutable(archiveInSrcsTest)
        val linkAction: SpawnAction = getGeneratingAction(executable) as SpawnAction
        com.google.common.truth.Subject.contains("-larchive.34")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkStaticStatically() {
        val statically: ConfiguredTarget =
            scratchConfiguredTarget(
                "statically",
                "statically",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'statically',",
                "           srcs = ['statically.cc'],",
                "           linkstatic=1)"
            )
        Truth.assertThat(
            CcInfo.Companion.get(statically)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
                .isEmpty()
        )
            .isTrue()
        val staticallyDotA: Artifact = getFilesToBuild(statically).getSingleton()
        assertThat(getGeneratingAction(staticallyDotA).getMnemonic()).isEqualTo("CppArchive")
        val dotAPath: PathFragment = staticallyDotA.getExecPath()
        assertThat(dotAPath.getPathString()).endsWith(STATIC_LIB)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedDefinesAgainstDeps() {
        val expandedDefines: ConfiguredTarget =
            scratchConfiguredTarget(
                "expanded_defines",
                "expand_deps",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'expand_deps',",
                "           srcs = ['defines.cc'],",
                "           deps = ['//foo'],",
                "           defines = ['FOO=$(location //foo)'])"
            )
        assertThat(CcInfo.Companion.get(expandedDefines).getCcCompilationContext().getDefines())
            .containsExactly(
                java.lang.String.format("FOO=%s/foo/libfoo.a", getRuleContext(expandedDefines).getBinFragment())
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedDefinesAgainstSrcs() {
        val expandedDefines: ConfiguredTarget =
            scratchConfiguredTarget(
                "expanded_defines",
                "expand_srcs",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'expand_srcs',",
                "           srcs = ['defines.cc'],",
                "           defines = ['FOO=$(location defines.cc)'])"
            )
        assertThat(CcInfo.Companion.get(expandedDefines).getCcCompilationContext().getDefines())
            .containsExactly("FOO=expanded_defines/defines.cc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedDefinesAgainstData() {
        scratch.file("data/BUILD", "filegroup(name = 'data', srcs = ['data.txt'])")
        val expandedDefines: ConfiguredTarget =
            scratchConfiguredTarget(
                "expanded_defines",
                "expand_srcs",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'expand_srcs',",
                "           srcs = ['defines.cc'],",
                "           data = ['//data'],",
                "           defines = ['FOO=$(location //data)'])"
            )
        assertThat(CcInfo.Companion.get(expandedDefines).getCcCompilationContext().getDefines())
            .containsExactly("FOO=data/data.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedDefinesDuplicateTargets() {
        scratch.file(
            "data/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'a', srcs = ['foo.cc'])"
        )
        val expandedDefines: ConfiguredTarget =
            scratchConfiguredTarget(
                "expanded_defines",
                "expand_srcs",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'expand_srcs',",
                "           srcs = ['defines.cc'],",
                "           data = ['//data:a'],",
                "           deps = ['//data:a'],",
                "           defines = ['FOO=$(location //data:a)'])"
            )
        val depPath: String? =
            getFilesToBuild(getConfiguredTarget("//data:a")).getSingleton().getExecPathString()
        assertThat(CcInfo.Companion.get(expandedDefines).getCcCompilationContext().getDefines())
            .containsExactly(java.lang.String.format("FOO=%s", depPath))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStartEndLib() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_START_END_LIB)
            )
        useConfiguration("--start_end_lib")
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "lib",
            srcs = ["lib.c"],
        )

        cc_binary(
            name = "bin",
            srcs = ["bin.c"],
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget = getConfiguredTarget("//test:bin")
        val action: SpawnAction = getGeneratingAction(getExecutable(target)) as SpawnAction
        for (input in action.getInputs().toList()) {
            val name: String? = input.getFilename()
            Truth.assertThat(!CppFileTypes.ARCHIVE.matches(name) && !CppFileTypes.PIC_ARCHIVE.matches(name))
                .isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStartEndLibThroughFeature() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_START_END_LIB)
            )
        useConfiguration("--start_end_lib")
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "lib",
            srcs = ["lib.c"],
        )

        cc_binary(
            name = "bin",
            srcs = ["bin.c"],
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget = getConfiguredTarget("//test:bin")
        val action: SpawnAction = getGeneratingAction(getExecutable(target)) as SpawnAction
        for (input in action.getInputs().toList()) {
            val name: String = input.getFilename()
            Truth.assertWithMessage("Expect '%s' not to be an archive", name)
                .that(!CppFileTypes.ARCHIVE.matches(name) && !CppFileTypes.PIC_ARCHIVE.matches(name))
                .isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTempsWithDifferentExtensions() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC)
            )
        invalidatePackages()
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL, "--save_temps")
        scratch.file(
            "ananas/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "ananas",
            srcs = [
                "1.c",
                "2.cc",
                "3.cpp",
                "4.S",
                "5.h",
                "6.hpp",
                "7.inc",
                "8.inl",
                "9.tlh",
                "A.tli",
            ],
        )
        
        """.trimIndent()
        )

        val ananas: ConfiguredTarget = getConfiguredTarget("//ananas:ananas")
        val temps: Iterable<String?>? =
            ActionsTestUtil.baseArtifactNames(getOutputGroup(ananas, OutputGroupInfo.TEMP_FILES))
        Truth.assertThat(temps)
            .containsExactly(
                "1.pic.i", "1.pic.s",
                "2.pic.ii", "2.pic.s",
                "3.pic.ii", "3.pic.s"
            )
    }

    /**
     * Returns the [IterableSubject] for the [OutputGroupInfo.TEMP_FILES] generated when
     * `testTarget` is built for `cpu`.
     */
    @Throws(java.lang.Exception::class)
    private fun assertTempsForTarget(testTarget: String?): IterableSubject {
        useConfiguration("--save_temps")
        val target: ConfiguredTarget = getConfiguredTarget(testTarget)
        assertThat(target).isNotNull()

        val temps: MutableList<String?>? =
            ActionsTestUtil.baseArtifactNames(getOutputGroup(target, OutputGroupInfo.TEMP_FILES))

        // Return the IterableSubject for the temp files.
        return Truth.assertWithMessage("k8").that(temps)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTempsForCcWithPic() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC)
            )
        invalidatePackages()
        assertTempsForTarget("//foo:foo").containsExactly("foo.pic.ii", "foo.pic.s")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTempsForCcWithoutPic() {
        assertTempsForTarget("//foo:foo").containsExactly("foo.ii", "foo.s")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTempsForCWithPic() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC)
            )
        invalidatePackages()
        useConfiguration()

        scratch.file(
            "csrc/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='csrc', srcs=['foo.c'])"
        )
        assertTempsForTarget("//csrc:csrc").containsExactly("foo.pic.i", "foo.pic.s")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTempsForCWithoutPic() {
        scratch.file(
            "csrc/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='csrc', srcs=['foo.c'])"
        )
        assertTempsForTarget("//csrc:csrc").containsExactly("foo.i", "foo.s")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPicModeAssembly() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.PIC)
            )
        invalidatePackages()
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='preprocess', srcs=['preprocess.S'])"
        )
        val argv: MutableList<String?>? = getCppCompileAction("//a:preprocess").getArguments()
        Truth.assertThat(argv).contains("-fPIC")
    }

    @Throws(java.lang.Exception::class)
    private fun getCppCompileAction(label: String?): CppCompileAction {
        val target: ConfiguredTarget = getConfiguredTarget(label)
        val compilationSteps: MutableList<CppCompileAction> =
            actionsTestUtil()
                .findTransitivePrerequisitesOf(
                    getFilesToBuild(target).toList().get(0), CppCompileAction::class.java
                )
        return compilationSteps.get(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisabledGenfilesDontShowUpInSystemIncludePaths() {
        scratch.file(
            "bang/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "bang",
            srcs = ["bang.cc"],
            includes = ["bang_includes"],
        )
        
        """.trimIndent()
        )
        val includesRoot = "bang/bang_includes"

        useConfiguration("--noincompatible_merge_genfiles_directory")
        var foo: ConfiguredTarget = getConfiguredTarget("//bang:bang")
        val genfilesDir: PathFragment? =
            targetConfig.getGenfilesFragment(RepositoryName.MAIN).getRelative(includesRoot)
        com.google.common.truth.Subject.contains(genfilesDir)

        useConfiguration("--incompatible_merge_genfiles_directory")
        foo = getConfiguredTarget("//bang:bang")
        assertThat(CcInfo.Companion.get(foo).getCcCompilationContext().getIncludeDirs())
            .doesNotContain(genfilesDir)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUseIsystemForIncludes() {
        // Tests the effect of using `-isystem` for include paths.
        useConfiguration(
            "--incompatible_merge_genfiles_directory=false", "--features=system_include_paths"
        )
        scratch.file(
            "no_includes/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "no_includes",
            srcs = ["no_includes.cc"],
        )
        
        """.trimIndent()
        )
        val noIncludes: ConfiguredTarget = getConfiguredTarget("//no_includes:no_includes")

        scratch.file(
            "bang/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "bang",
            srcs = ["bang.cc"],
            includes = ["bang_includes"],
            features = ["system_include_paths"],
        )
        
        """.trimIndent()
        )

        val foo: ConfiguredTarget = getConfiguredTarget("//bang:bang")

        val includesRoot = "bang/bang_includes"
        val expected: MutableList<PathFragment?> =
            com.google.common.collect.ImmutableList.Builder<PathFragment?>()
                .addAll(CcInfo.Companion.get(noIncludes).getCcCompilationContext().getSystemIncludeDirs())
                .add(PathFragment.create(includesRoot))
                .add(targetConfig.getGenfilesFragment(RepositoryName.MAIN).getRelative(includesRoot))
                .add(targetConfig.getBinFragment(RepositoryName.MAIN).getRelative(includesRoot))
                .build()
        assertThat(CcInfo.Companion.get(foo).getCcCompilationContext().getSystemIncludeDirs())
            .containsExactlyElementsIn(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUseIForIncludes() {
        // Tests that includes use -I without the alternative feature (system_include_paths).
        useConfiguration("--incompatible_merge_genfiles_directory=false")
        scratch.file(
            "no_includes/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "no_includes",
            srcs = ["no_includes.cc"],
        )
        
        """.trimIndent()
        )
        val noIncludes: ConfiguredTarget = getConfiguredTarget("//no_includes:no_includes")

        scratch.file(
            "bang/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "bang",
            srcs = ["bang.cc"],
            includes = ["bang_includes"],
        )
        
        """.trimIndent()
        )

        val foo: ConfiguredTarget = getConfiguredTarget("//bang:bang")

        val includesRoot = "bang/bang_includes"
        val expected: com.google.common.collect.ImmutableList<PathFragment?> =
            com.google.common.collect.ImmutableList.Builder<PathFragment?>()
                .addAll(CcInfo.Companion.get(noIncludes).getCcCompilationContext().getIncludeDirs())
                .add(PathFragment.create(includesRoot))
                .add(targetConfig.getGenfilesFragment(RepositoryName.MAIN).getRelative(includesRoot))
                .add(targetConfig.getBinFragment(RepositoryName.MAIN).getRelative(includesRoot))
                .build()
        assertThat(CcInfo.Companion.get(foo).getCcCompilationContext().getIncludeDirs())
            .containsExactlyElementsIn(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcTestDisallowsAlwaysLink() {
        scratch.file(
            "cc/common/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc:cc_test.bzl", "cc_test")
        cc_library(
            name = "lib1",
            srcs = ["foo1.cc"],
            deps = ["//left"],
        )

        cc_test(
            name = "testlib",
            deps = [":lib1"],
            alwayslink = 1,
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        packageManager.getPackage(reporter, PackageIdentifier.createInMainRepo("cc/common"))
        assertContainsEvent(
            "//cc/common:testlib: no such attribute 'alwayslink'" + " in 'cc_test' rule"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcTestBuiltWithFissionHasDwp() {
        // Tests that cc_tests built statically and with Fission will have the .dwp file
        // in their runfiles.
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PER_OBJECT_DEBUG_INFO)
            )
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--build_test_dwp",
            "--dynamic_mode=off",
            "--fission=yes"
        )
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "mypackage",
                "mytest",
                "load('@rules_cc//cc:cc_test.bzl', 'cc_test')",
                "cc_test(name = 'mytest', srcs = ['mytest.cc'])"
            )

        val runfiles: NestedSet<Artifact?>? = collectRunfiles(target)
        com.google.common.truth.Subject.contains("mytest.dwp")
    }

    @org.junit.Test
    @Ignore("(b/484481656): Starlark does not support warnings.")
    @Throws(java.lang.Exception::class)
    fun testCcLibraryBadIncludesWarnedAndIgnored() {
        checkWarning(
            "badincludes",
            "flaky_lib",  // message:
            "in includes attribute of cc_library rule //badincludes:flaky_lib: "
                    + "ignoring invalid absolute path '//third_party/procps/proc'",  // build file:
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'flaky_lib',",
            "   srcs = [ 'ok.cc' ],",
            "   includes = [ '//third_party/procps/proc' ])"
        )
    }

    @org.junit.Test
    @Ignore("(b/484481656): Starlark does not support warnings.")
    @Throws(java.lang.Exception::class)
    fun testCcLibraryUplevelIncludesWarned() {
        checkWarning(
            "third_party/uplevel",
            "lib",  // message:
            ("in includes attribute of cc_library rule //third_party/uplevel:lib: '../bar' resolves to "
                    + "'third_party/bar' not below the relative path of its package 'third_party/uplevel'. "
                    + "This will be an error in the future"),  // build file:
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "licenses(['unencumbered'])",
            "cc_library(name = 'lib',",
            "           srcs = ['foo.cc'],",
            "           includes = ['../bar'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcLibraryThirdPartyIncludesNotWarned() {
        eventCollector.clear()
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "third_party/pkg",
                "lib",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "licenses(['unencumbered'])",
                "cc_library(name = 'lib',",
                "           srcs = ['foo.cc'],",
                "           includes = ['./'])"
            )
        Truth.assertThat(view.hasErrors(target)).isFalse()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcLibraryExternalIncludesNotWarned() {
        if (!analysisMock.isThisBazel) {
            return
        }
        eventCollector.clear()
        FileSystemUtils.appendIsoLatin1(
            scratch.resolve("MODULE.bazel"),
            "bazel_dep(name = 'pkg')",
            "local_path_override(module_name = 'pkg', path = '/foo')"
        )
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                Builder().modify(PathFragment.create("MODULE.bazel")).build(),
                Root.fromPath(rootDirectory)
            )
        scratch.resolve("/foo/bar").createDirectoryAndParents()
        scratch.file("/foo/MODULE.bazel", "module(name = 'pkg')")
        MockCcSupport.get().setup(MockToolsConfig(scratch.resolve("/foo")))
        scratch.file(
            "/foo/bar/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "lib",
            srcs = ["foo.cc"],
            includes = ["./"],
        )
        
        """.trimIndent()
        )
        val label: Label = Label.parseCanonical("@@pkg+//bar:lib")
        val target: ConfiguredTarget = view.getConfiguredTargetForTesting(reporter, label, targetConfig)
        Truth.assertThat(view.hasErrors(target)).isFalse()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcLibraryRootIncludesError() {
        checkError(
            "third_party/root",
            "lib",  // message:
            ("attribute includes: '../..' resolves to the "
                    + "workspace root, which would allow this rule and all of its transitive dependents to "
                    + "include any file in your workspace. Please include only what you need"),  // build file:
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "licenses(['unencumbered'])",
            "cc_library(name = 'lib',",
            "           srcs = ['foo.cc'],",
            "           includes = ['../..'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStaticallyLinkedBinaryNeedsSharedObject() {
        scratch.file(
            "third_party/sophos/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        licenses(["notice"])

        cc_library(
            name = "savi",
            srcs = ["lib/libsavi.so"],
        )
        
        """.trimIndent()
        )
        val wrapsophos: ConfiguredTarget =
            scratchConfiguredTarget(
                "quality/malware/support",
                "wrapsophos",
                "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'sophosengine',",
                "           srcs = [ 'sophosengine.cc' ],",
                "           deps = [ '//third_party/sophos:savi' ])",
                "cc_binary(name = 'wrapsophos',",
                "          srcs = [ 'wrapsophos.cc' ],",
                "          deps = [ ':sophosengine' ],",
                "          linkstatic=1)"
            )

        val artifactNames: MutableList<String?>? = baseArtifactNames(getLinkerInputs(wrapsophos))
        Truth.assertThat(artifactNames).contains("libsavi.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandLabelInLinkoptsAgainstSrc() {
        scratch.file(
            "coolthing/BUILD",
            """
        genrule(
            name = "build-that",
            srcs = ["foo"],
            outs = ["nicelib.a"],
            cmd = "cat  ${'$'}< > ${'$'}@",
        )
        
        """.trimIndent()
        )
        // In reality the linkopts might contain several externally-provided
        // '.a' files with cyclic dependencies amongst them, but in this test
        // it suffices to show that one label in linkopts was resolved.
        scratch.file(
            "myapp/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "myapp",
            srcs = ["//coolthing:nicelib.a"],
            linkopts = ["//coolthing:nicelib.a"],
        )
        
        """.trimIndent()
        )
        val theLib: ConfiguredTarget = getConfiguredTarget("//coolthing:build-that")
        val theApp: ConfiguredTarget = getConfiguredTarget("//myapp:myapp")
        // make sure we did not print warnings about the linkopt
        assertNoEvents()
        // make sure the binary is dependent on the static lib
        val linkAction: Action = getGeneratingAction(getFilesToBuild(theApp).getSingleton())
        val filesToBuild: com.google.common.collect.ImmutableList<Artifact?>? = getFilesToBuild(theLib).toList()
        assertThat(linkAction.getInputs().toSet()).containsAtLeastElementsIn(filesToBuild)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcLibraryWithDashStaticOnDarwin() {
        getAnalysisMock().ccSupport().setupCcToolchainConfigForCpu(mockToolsConfig, "darwin_x86_64")
        mockToolsConfig.create(
            "platforms/BUILD",
            "platform(",
            "  name = 'darwin_x86_64',",
            "  constraint_values = [",
            "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:macos',",
            "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64',",
            "  ],",
            ")"
        )
        useConfiguration("--platforms=//platforms:darwin_x86_64")

        checkError(
            "badlib",
            "lib_with_dash_static",  // message:
            "in linkopts attribute of cc_library rule @@//badlib:lib_with_dash_static: "
                    + "Apple builds do not support statically linked binaries",  // build file:
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'lib_with_dash_static',",
            "   srcs = [ 'ok.cc' ],",
            "   linkopts = [ '-static' ])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStampTests_enabled() {
        writeStampTestFiles()

        useConfiguration("--stamp")
        assertStamping(false, "//test:a")
        assertStamping(false, "//test:b")
        assertStamping(true, "//test:c")
        assertStamping(true, "//test:d")
        assertStamping(false, "//test:e")
        assertStamping(true, "//test:f")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStampTests_disabled() {
        writeStampTestFiles()

        useConfiguration("--nostamp")
        assertStamping(false, "//test:a")
        assertStamping(false, "//test:b")
        assertStamping(true, "//test:c")
        assertStamping(false, "//test:d")
        assertStamping(false, "//test:e")
        assertStamping(true, "//test:f")
    }

    @Throws(java.lang.Exception::class)
    private fun writeStampTestFiles() {
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_test.bzl", "cc_test")
        cc_test(
            name = "a",
            srcs = ["a.cc"],
        )

        cc_test(
            name = "b",
            srcs = ["b.cc"],
            stamp = 0,
        )

        cc_test(
            name = "c",
            srcs = ["c.cc"],
            stamp = 1,
        )

        cc_binary(
            name = "d",
            srcs = ["d.cc"],
        )

        cc_binary(
            name = "e",
            srcs = ["e.cc"],
            stamp = 0,
        )

        cc_binary(
            name = "f",
            srcs = ["f.cc"],
            stamp = 1,
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun assertStamping(enabled: Boolean, label: String?) {
        assertThat(AnalysisUtils.isStampingEnabled(getRuleContext(getConfiguredTarget(label))))
            .isEqualTo(enabled)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncludeRelativeHeadersAboveExecRoot() {
        checkError(
            "test",
            "bad_relative_include",
            "Path references a path above the execution root.",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='bad_relative_include', srcs=[], includes=['../..'])"
        )
    }

    @org.junit.Test
    @Ignore("(b/484481656): Starlark does not support warnings.")
    @Throws(java.lang.Exception::class)
    fun testIncludeAbsoluteHeaders() {
        checkWarning(
            "test",
            "bad_absolute_include",
            "ignoring invalid absolute path",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='bad_absolute_include', srcs=[], includes=['/usr/include/'])"
        )
    }

    /** Tests that shared libraries of the form "libfoo.so.1.2" are permitted within "srcs".  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVersionedSharedLibrarySupport() {
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "mypackage",
                "mybinary",
                "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_binary(name = 'mybinary',",
                "           srcs = ['mybinary.cc'],",
                "           deps = [':mylib'])",
                "cc_library(name = 'mylib',",
                "           srcs = ['libshared.so', 'libshared.so.1.1', 'foo.cc'])"
            )
        val artifactNames: MutableList<String?>? = baseArtifactNames(getLinkerInputs(target))
        Truth.assertThat(artifactNames).containsAtLeast("libshared.so", "libshared.so.1.1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedLinkopts() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        genrule(
            name = "linker",
            outs = ["a.lds"],
            cmd = "generate",
        )

        cc_binary(
            name = "bin",
            srcs = ["b.cc"],
            linkopts = ["-Wl,@${'$'}(location a.lds)"],
            deps = ["a.lds"],
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//a:bin")
        val action: SpawnAction = getGeneratingAction(getFilesToBuild(target).getSingleton()) as SpawnAction
        com.google.common.truth.Subject.contains(
            java.lang.String.format(
                "-Wl,@%s/a/a.lds",
                targetConfiguration
                    .getGenfilesDirectory(RepositoryName.MAIN)
                    .getExecPath()
                    .getPathString()
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedEnv() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_test.bzl", "cc_test")
        genrule(
            name = "linker",
            outs = ["a.lds"],
            cmd = "generate",
        )

        cc_test(
            name = "bin_test",
            srcs = ["b.cc"],
            env = {"SOME_KEY": "-Wl,@${'$'}(location a.lds)"},
            deps = ["a.lds"],
        )
        
        """.trimIndent()
        )
        val starlarkTarget: ConfiguredTarget = getConfiguredTarget("//a:bin_test")
        val provider: RunEnvironmentInfo = starlarkTarget.get(RunEnvironmentInfo.provider)
        assertThat(provider.getEnvironment()).containsEntry("SOME_KEY", "-Wl,@a/a.lds")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProvidesLinkerScriptToLinkAction() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "bin",
            srcs = ["b.cc"],
            linkopts = ["-Wl,@${'$'}(location a.lds)"],
            deps = ["a.lds"],
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//a:bin")
        val action: SpawnAction = getGeneratingAction(getFilesToBuild(target).getSingleton()) as SpawnAction
        val linkInputs: NestedSet<Artifact?>? = action.getInputs()
        com.google.common.truth.Subject.contains("a.lds")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncludeManglingSmoke() {
        scratch.file(
            "third_party/a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        licenses(["notice"])

        cc_library(
            name = "a",
            hdrs = ["v1/b/c.h"],
            include_prefix = "lib",
            strip_include_prefix = "v1",
        )
        
        """.trimIndent()
        )

        val lib: ConfiguredTarget = getConfiguredTarget("//third_party/a")
        val ccCompilationContext: CcCompilationContext = CcInfo.Companion.get(lib).getCcCompilationContext()
        assertThat(ActionsTestUtil.prettyArtifactNames(ccCompilationContext.getDeclaredIncludeSrcs()))
            .containsExactly("third_party/a/_virtual_includes/a/lib/b/c.h", "third_party/a/v1/b/c.h")
        assertThat(ccCompilationContext.getIncludeDirs())
            .containsExactly(
                targetConfiguration
                    .getBinFragment(RepositoryName.MAIN)
                    .getRelative("third_party/a/_virtual_includes/a")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncludeManglingSmokeWithShortendVirtualIncludes() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SHORTEN_VIRTUAL_INCLUDES)
            )
        scratch.file(
            "third_party/a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        licenses(["notice"])

        cc_library(
            name = "a",
            hdrs = ["v1/b/c.h"],
            include_prefix = "lib",
            strip_include_prefix = "v1",
        )
        
        """.trimIndent()
        )

        val lib: ConfiguredTarget = getConfiguredTarget("//third_party/a")
        val ccCompilationContext: CcCompilationContext = CcInfo.Companion.get(lib).getCcCompilationContext()
        assertThat(ActionsTestUtil.prettyArtifactNames(ccCompilationContext.getDeclaredIncludeSrcs()))
            .containsExactly("_virtual_includes/207132b2/lib/b/c.h", "third_party/a/v1/b/c.h")
        assertThat(ccCompilationContext.getIncludeDirs())
            .containsExactly(
                targetConfiguration
                    .getBinFragment(RepositoryName.MAIN)
                    .getRelative("_virtual_includes/207132b2")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUpLevelReferencesInIncludeMangling() {
        scratch.file(
            "third_party/a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        licenses(["notice"])

        cc_library(
            name = "sip",
            srcs = ["a.h"],
            strip_include_prefix = "a/../b",
        )

        cc_library(
            name = "ip",
            srcs = ["a.h"],
            include_prefix = "a/../b",
        )

        cc_library(
            name = "ipa",
            srcs = ["a.h"],
            include_prefix = "/foo",
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//third_party/a:sip")
        assertContainsEvent("should not contain uplevel references")

        eventCollector.clear()
        getConfiguredTarget("//third_party/a:ip")
        assertContainsEvent("should not contain uplevel references")

        eventCollector.clear()
        getConfiguredTarget("//third_party/a:ipa")
        assertContainsEvent("should be a relative path")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsoluteAndRelativeStripPrefix() {
        scratch.file(
            "third_party/a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        licenses(["notice"])

        cc_library(
            name = "relative",
            hdrs = ["v1/b.h"],
            strip_include_prefix = "v1",
        )

        cc_library(
            name = "absolute",
            hdrs = ["v1/b.h"],
            strip_include_prefix = "/third_party",
        )
        
        """.trimIndent()
        )

        val relative: CcCompilationContext =
            CcInfo.Companion.get(getConfiguredTarget("//third_party/a:relative")).getCcCompilationContext()
        val absolute: CcCompilationContext =
            CcInfo.Companion.get(getConfiguredTarget("//third_party/a:absolute")).getCcCompilationContext()

        assertThat(ActionsTestUtil.prettyArtifactNames(relative.getDeclaredIncludeSrcs()))
            .containsExactly("third_party/a/_virtual_includes/relative/b.h", "third_party/a/v1/b.h")
        assertThat(ActionsTestUtil.prettyArtifactNames(absolute.getDeclaredIncludeSrcs()))
            .containsExactly(
                "third_party/a/_virtual_includes/absolute/a/v1/b.h", "third_party/a/v1/b.h"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyPackageStripPrefix() {
        if (!AnalysisMock.get().isThisBazel()) {
            return
        }
        scratch.file(
            "BUILD",
            "licenses(['notice'])",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='a', hdrs=['b.h'], strip_include_prefix=None)"
        )
        val ccContext: CcCompilationContext =
            CcInfo.Companion.get(getConfiguredTarget("//:a")).getCcCompilationContext()
        assertThat(ActionsTestUtil.prettyArtifactNames(ccContext.getDeclaredIncludeSrcs()))
            .containsExactly("b.h")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDotPackageStripPrefix() {
        if (!AnalysisMock.get().isThisBazel()) {
            return
        }
        scratch.file(
            "BUILD",
            "licenses(['notice'])",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='a', hdrs=['b.h'], strip_include_prefix='.')"
        )
        val ccContext: CcCompilationContext =
            CcInfo.Companion.get(getConfiguredTarget("//:a")).getCcCompilationContext()
        assertThat(ActionsTestUtil.prettyArtifactNames(ccContext.getDeclaredIncludeSrcs()))
            .containsExactly("_virtual_includes/a/b.h", "b.h")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArtifactNotUnderStripPrefix() {
        scratch.file(
            "third_party/a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        licenses(["notice"])

        cc_library(
            name = "a",
            hdrs = ["v1/b.h"],
            strip_include_prefix = "v2",
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//third_party/a:a")
        assertContainsEvent(
            "header 'third_party/a/v1/b.h' is not under the specified strip prefix 'third_party/a/v2'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkActionIsRegisteredWhenIncludePrefixDoesntChangePath() {
        scratch.file(
            "third_party/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        licenses(["notice"])

        cc_library(
            name = "a",
            hdrs = ["a.h"],
            include_prefix = "third_party",
        )
        
        """.trimIndent()
        )

        val ccCompilationContext: CcCompilationContext =
            CcInfo.Companion.get(getConfiguredTarget("//third_party:a")).getCcCompilationContext()
        assertThat(ActionsTestUtil.prettyArtifactNames(ccCompilationContext.getDeclaredIncludeSrcs()))
            .containsExactly("third_party/_virtual_includes/a/third_party/a.h", "third_party/a.h")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigureFeaturesDoesntCrashOnCollidingFeaturesExceptionButReportsRuleErrorCleanly() {
        reporter.removeHandler(failFastHandler)
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures("same_symbol_provided_configuration")
            )
        useConfiguration("--features=a1", "--features=a2")

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        getConfiguredTarget("//x:foo")
        assertContainsEvent("Symbol a is provided by all of the following features: a1 a2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSupportsPicFeatureResultsInPICObjectGenerated() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.NO_LEGACY_FEATURES, CppRuleClasses.SUPPORTS_PIC)
                    .withActionConfigs(
                        CppActionNames.CPP_LINK_STATIC_LIBRARY,
                        CppActionNames.CPP_COMPILE,
                        CppActionNames.CPP_LINK_NODEPS_DYNAMIC_LIBRARY
                    )
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        val ccLibrary: RuleConfiguredTarget = getConfiguredTarget("//x:foo") as RuleConfiguredTarget
        val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> = ccLibrary.getActions()
        val outputs: com.google.common.collect.ImmutableList<String?> =
            actions.stream()
                .map<Any?>(ActionAnalysisMetadata::getPrimaryOutput)
                .map<Any?>(Artifact::getFilename)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        Truth.assertThat(outputs).contains("a.pic.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWhenSupportsPicDisabledPICObjectAreNotGenerated() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.NO_LEGACY_FEATURES)
                    .withActionConfigs(
                        CppActionNames.CPP_LINK_STATIC_LIBRARY,
                        CppActionNames.CPP_COMPILE,
                        CppActionNames.CPP_LINK_NODEPS_DYNAMIC_LIBRARY
                    )
            )
        useConfiguration("--features=-supports_pic")

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        val ccLibrary: RuleConfiguredTarget = getConfiguredTarget("//x:foo") as RuleConfiguredTarget
        val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> = ccLibrary.getActions()
        val outputs: com.google.common.collect.ImmutableList<String?> =
            actions.stream()
                .map<Any?>(ActionAnalysisMetadata::getPrimaryOutput)
                .map<Any?>(Artifact::getFilename)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        Truth.assertThat(outputs).doesNotContain("a.pic.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWhenSupportsPicDisabledButForcePicSetPICAreGenerated() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.NO_LEGACY_FEATURES, CppRuleClasses.SUPPORTS_PIC)
                    .withActionConfigs(
                        CppActionNames.CPP_LINK_STATIC_LIBRARY,
                        CppActionNames.CPP_COMPILE,
                        CppActionNames.CPP_LINK_NODEPS_DYNAMIC_LIBRARY
                    )
            )
        useConfiguration("--force_pic", "--platforms=" + TestConstants.PLATFORM_LABEL)

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        val ccLibrary: RuleConfiguredTarget = getConfiguredTarget("//x:foo") as RuleConfiguredTarget
        val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> = ccLibrary.getActions()
        val outputs: com.google.common.collect.ImmutableList<String?> =
            actions.stream()
                .map<Any?>(ActionAnalysisMetadata::getPrimaryOutput)
                .map<Any?>(Artifact::getFilename)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        Truth.assertThat(outputs).contains("a.pic.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPreferPicForOptBinaryFeature() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.NO_LEGACY_FEATURES,
                        CppRuleClasses.SUPPORTS_PIC,
                        CppRuleClasses.PREFER_PIC_FOR_OPT_BINARIES
                    )
                    .withActionConfigs(
                        CppActionNames.CPP_LINK_STATIC_LIBRARY,
                        CppActionNames.CPP_COMPILE,
                        CppActionNames.CPP_LINK_NODEPS_DYNAMIC_LIBRARY
                    )
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL, "--compilation_mode=opt")

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        val ccLibrary: RuleConfiguredTarget = getConfiguredTarget("//x:foo") as RuleConfiguredTarget
        val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> = ccLibrary.getActions()
        val outputs: com.google.common.collect.ImmutableList<String?> =
            actions.stream()
                .map<Any?>(ActionAnalysisMetadata::getPrimaryOutput)
                .map<Any?>(Artifact::getFilename)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        Truth.assertThat(outputs).doesNotContain("a.o")
        Truth.assertThat(outputs).contains("a.pic.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPreferPicForOptBinaryFeatureNeedsPicSupport() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.NO_LEGACY_FEATURES, CppRuleClasses.PREFER_PIC_FOR_OPT_BINARIES
                    )
                    .withActionConfigs(
                        CppActionNames.CPP_LINK_STATIC_LIBRARY,
                        CppActionNames.CPP_COMPILE,
                        CppActionNames.CPP_LINK_NODEPS_DYNAMIC_LIBRARY
                    )
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL, "--compilation_mode=opt")

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        val ccLibrary: RuleConfiguredTarget = getConfiguredTarget("//x:foo") as RuleConfiguredTarget
        val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> = ccLibrary.getActions()
        val outputs: com.google.common.collect.ImmutableList<String?> =
            actions.stream()
                .map<Any?>(ActionAnalysisMetadata::getPrimaryOutput)
                .map<Any?>(Artifact::getFilename)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        Truth.assertThat(outputs).doesNotContain("a.pic.o")
        Truth.assertThat(outputs).contains("a.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWhenSupportsPicNotPresentAndForcePicPassedIsError() {
        reporter.removeHandler(failFastHandler)
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.NO_LEGACY_FEATURES)
                    .withActionConfigs(
                        CppActionNames.CPP_LINK_STATIC_LIBRARY,
                        CppActionNames.CPP_LINK_NODEPS_DYNAMIC_LIBRARY,
                        CppActionNames.CPP_COMPILE
                    )
            )
        useConfiguration("--force_pic", "--features=-supports_pic")

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        getConfiguredTarget("//x:foo")
        assertContainsEvent(
            "PIC compilation is requested but the toolchain does not support it"
                    + " (feature named 'supports_pic' is not enabled"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompilationParameterFile() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.COMPILER_PARAM_FILE)
            )
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='foo', srcs=['foo.cc'])"
        )
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            java.lang.String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )
        val cppCompileAction: CppCompileAction = getCppCompileAction("//a:foo")
        assertThat(
            cppCompileAction.getArgumentsForExecute(PathMapper.NOOP).arguments.stream()
                .map({ x -> removeOutDirectory(x) })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
            .containsExactly("/usr/bin/mock-gcc", "@/k8-fastbuild/bin/a/_objs/foo/foo.o.params")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCppCompileActionArgvIgnoreParamFile() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.COMPILER_PARAM_FILE)
            )
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='foo', srcs=['foo.cc'])"
        )
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            java.lang.String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )
        val cppCompileAction: CppCompileAction = getCppCompileAction("//a:foo")
        val argv: com.google.common.collect.ImmutableList<String?>? =
            cppCompileAction.getStarlarkArgv().stream()
                .map({ x -> removeOutDirectory(x) })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(argv).contains("/usr/bin/mock-gcc")
        Truth.assertThat(argv).contains("-o")
        Truth.assertThat(argv).contains("/k8-fastbuild/bin/a/_objs/foo/foo.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompilationParameterFileOnDemand() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.COMPILER_PARAM_FILE_ON_DEMAND)
            )
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='foo', srcs=['foo.cc'])"
        )
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            java.lang.String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )
        val cppCompileAction: CppCompileAction = getCppCompileAction("//a:foo")
        // It should NOT use the param file because it's on-demand and command line is short.
        assertThat(
            cppCompileAction.getArgumentsForExecute(PathMapper.NOOP).arguments.stream()
                .map({ x -> removeOutDirectory(x) })
        )
            .containsAtLeast(
                "/usr/bin/mock-gcc",
                "--default-compile-flag",
                "-MD",
                "-MF",
                "/k8-fastbuild/bin/a/_objs/foo/foo.d",
                "-frandom-seed=/k8-fastbuild/bin/a/_objs/foo/foo.o",
                "-iquote",
                ".",
                "-iquote",
                "/k8-fastbuild/bin",
                "--sysroot=/usr/grte/v1",
                "-c",
                "a/foo.cc",
                "-o",
                "/k8-fastbuild/bin/a/_objs/foo/foo.o"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompilationParameterFileOnDemandLongCommandLine() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.COMPILER_PARAM_FILE_ON_DEMAND)
            )
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='foo', srcs=['foo.cc'])"
        )
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            java.lang.String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            ),
            "--min_param_file_size=0"
        ) // Force max length to be 0
        val cppCompileAction: CppCompileAction = getCppCompileAction("//a:foo")
        // With min_param_file_size=0, it should dynamically decide to use the param file
        assertThat(
            cppCompileAction.getArgumentsForExecute(PathMapper.NOOP).arguments.stream()
                .map({ x -> removeOutDirectory(x) })
        )
            .containsExactly("/usr/bin/mock-gcc", "@/k8-fastbuild/bin/a/_objs/foo/foo.o.params")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClangClParameters() {
        if (!AnalysisMock.get().isThisBazel()) {
            return
        }
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.TARGETS_WINDOWS,
                        CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY
                    )
            )
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "foo",
            srcs = ["foo.cc"],
            copts = [
                "/imsvc",
                "SYSTEM_INCLUDE_1",
                "-imsvcSYSTEM_INCLUDE_2",
                "/ISTANDARD_INCLUDE",
                "/FI",
                "forced_include_1",
                "-FIforced_include_2",
            ],
        )
        
        """.trimIndent()
        )
        val cppCompileAction: CppCompileAction = getCppCompileAction("//a:foo")

        val systemInclude1: PathFragment? = PathFragment.create("SYSTEM_INCLUDE_1")
        val systemInclude2: PathFragment? = PathFragment.create("SYSTEM_INCLUDE_2")
        val standardInclude: PathFragment? = PathFragment.create("STANDARD_INCLUDE")

        com.google.common.truth.Subject.contains(systemInclude1)
        com.google.common.truth.Subject.contains(systemInclude2)
        assertThat(cppCompileAction.getSystemIncludeDirs()).doesNotContain(standardInclude)

        assertThat(cppCompileAction.getIncludeDirs()).doesNotContain(systemInclude1)
        assertThat(cppCompileAction.getIncludeDirs()).doesNotContain(systemInclude2)
        com.google.common.truth.Subject.contains(standardInclude)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcLibraryLoadedThroughMacro() {
        setupTestCcLibraryLoadedThroughMacro( /* loadMacro= */true)
        assertThat(getConfiguredTarget("//a:a")).isNotNull()
        assertNoEvents()
    }

    @Throws(java.lang.Exception::class)
    private fun setupTestCcLibraryLoadedThroughMacro(loadMacro: Boolean) {
        scratch.file(
            "a/BUILD",
            getAnalysisMock().ccSupport().getMacroLoadStatement(loadMacro, "cc_library"),
            "cc_library(name='a', srcs=['a.cc'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFdoProfileLoadedThroughMacro() {
        setuptestFdoProfileLoadedThroughMacro( /* loadMacro= */true)
        assertThat(getConfiguredTarget("//a:a")).isNotNull()
        assertNoEvents()
    }

    @Throws(java.lang.Exception::class)
    private fun setuptestFdoProfileLoadedThroughMacro(loadMacro: Boolean) {
        scratch.file(
            "a/BUILD",
            getAnalysisMock().ccSupport().getMacroLoadStatement(loadMacro, "fdo_profile"),
            "fdo_profile(name='a', profile='profile.xfdo')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFdoPrefetchHintsLoadedThroughMacro() {
        setupTestFdoPrefetchHintsLoadedThroughMacro( /* loadMacro= */true)
        assertThat(getConfiguredTarget("//a:a")).isNotNull()
        assertNoEvents()
    }

    @Throws(java.lang.Exception::class)
    private fun setupTestFdoPrefetchHintsLoadedThroughMacro(loadMacro: Boolean) {
        scratch.file(
            "a/BUILD",
            getAnalysisMock().ccSupport().getMacroLoadStatement(loadMacro, "fdo_prefetch_hints"),
            "fdo_prefetch_hints(",
            "    name = 'a',",
            "    profile = 'profile.afdo',",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkExtra() {
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "mypackage",
                "mybinary",
                "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
                "cc_binary(name = 'mybinary',",
                "          srcs = ['mybinary.cc'])"
            )
        val artifactNames: MutableList<String?>? = baseArtifactNames(getLinkerInputs(target))
        Truth.assertThat(artifactNames).contains("liblink_extra_lib.a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoLinkExtra() {
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "mypackage",
                "mybinary",
                "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'empty_lib')",
                "cc_binary(name = 'mybinary',",
                "          srcs = ['mybinary.cc'],",
                "          link_extra_lib = ':empty_lib')"
            )
        val artifactNames: MutableList<String?>? = baseArtifactNames(getLinkerInputs(target))
        Truth.assertThat(artifactNames).doesNotContain("liblink_extra_lib.a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGenerateLinkMap() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.GENERATE_LINKMAP_FEATURE_NAME)
            )
        val generateLinkMapTest: ConfiguredTarget =
            scratchConfiguredTarget(
                "generate_linkmap",
                "generate_linkmap_test",
                "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
                "cc_binary(name = 'generate_linkmap_test',",
                "          features = ['generate_linkmap'],",
                "          srcs = ['generate_linkmap_test.cc'],",
                "          )"
            )
        val temps: Iterable<String?>? =
            ActionsTestUtil.baseArtifactNames(getOutputGroup(generateLinkMapTest, "linkmap"))
        Truth.assertThat(temps).containsExactly("generate_linkmap_test.map")
    }

    companion object {
        private const val STATIC_LIB = "statically/libstatically.a"

        private fun removeOutDirectory(s: String): String? {
            return s.replace("blaze-out", "").replace("bazel-out", "")
        }
    }
}
