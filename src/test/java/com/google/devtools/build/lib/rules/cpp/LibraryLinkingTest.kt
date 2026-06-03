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

import com.google.devtools.build.lib.actions.Artifact

/** Test for shared C++ library linking.  */
@RunWith(JUnit4::class)
class LibraryLinkingTest : BuildViewTestCase() {
    @Throws(java.lang.Exception::class)
    private fun getLinkOpts(linkAction: SpawnAction, vararg optionPatterns: String?): MutableList<String?> {
        // Strip the first parameters from the argv, which are the dynamic library script
        // (usually tools/cpp/link_dynamic_library.sh), and its arguments.
        return linkAction.getArguments().subList(1, optionPatterns.size + 1)
    }

    @Throws(java.lang.Exception::class)
    private fun assertLinkopts(linkAction: SpawnAction, vararg optionPatterns: String?) {
        val linkopts = getLinkOpts(linkAction, *optionPatterns)
        for (i in optionPatterns.indices) {
            Truth.assertThat(linkopts.get(i)).matches(optionPatterns[i])
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGeneratedLib() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )

        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--noincompatible_remove_legacy_whole_archive"
        )
        val genlib: ConfiguredTarget =
            scratchConfiguredTarget(
                "genrule",
                "thebinary.so",
                "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "genrule(name = 'genlib',",
                "        outs = ['genlib.a'],",
                "        cmd = '')",
                "cc_library(name = 'thelib',",
                "           srcs = [':genlib'],",
                "           linkstatic = 1)",
                "cc_binary(name = 'thebinary.so',",
                "          deps = [':thelib'],",
                "          linkstatic = 1,",
                "          linkshared = 1)"
            )
        val executable: Artifact = getExecutable(genlib)
        val linkAction: SpawnAction = getGeneratingAction(executable) as SpawnAction
        assertLinkopts(
            linkAction,
            "-shared",
            "-o",
            analysisMock.productName + "-out/.+/genrule/thebinary.so",
            "-Wl,-whole-archive",
            analysisMock.productName + "-out/.+/genrule/genlib.a",
            "-Wl,-no-whole-archive"
        )
    }

    /**
     * Tests that the shared library version of a cc_library includes linkopts settings
     * in its link command line, but the archive library version doesn't.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcLibraryLinkopts() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )

        scratch.overwriteFile(
            "custom_malloc/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "custom_malloc",
            srcs = ["custom_malloc.cc"],
            linkopts = ["-Lmalloc_dir -lmalloc_opt"],
        )
        
        """.trimIndent()
        )

        val ccLib: ConfiguredTarget = getConfiguredTarget("//custom_malloc:custom_malloc")
        val linkOpt1 = "-Lmalloc_dir"
        val linkOpt2 = "-lmalloc_opt"

        // Archive library version:
        val archiveLib: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                com.google.common.collect.Iterables.filter<T?>(
                    ccLib.getProvider(FileProvider::class.java).getFilesToBuild().toList(),
                    com.google.common.base.Predicate { artifact: T? ->
                        artifact.getFilename().equals("libcustom_malloc.a")
                    })
            )
        val archiveLink: SpawnAction = getGeneratingAction(archiveLib) as SpawnAction
        var args: MutableList<String?>? = archiveLink.getArguments()
        Truth.assertThat(args).doesNotContain(linkOpt1)
        Truth.assertThat(args).doesNotContain(linkOpt2)

        // Shared library version:
        val soLib: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                CcInfo.get(ccLib)
                    .getCcLinkingContext()
                    .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
            )
        // This artifact is generated by a SolibSymlinkAction, so we need to go back two levels.
        val solibLink: SpawnAction =
            getGeneratingAction(getGeneratingAction(soLib).getPrimaryInput()) as SpawnAction
        args = solibLink.getArguments()
        Truth.assertThat(args).contains(linkOpt1)
        Truth.assertThat(args).contains(linkOpt2)
    }
}
