// Copyright 2020 The Bazel Authors. All rights reserved.
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

/**
 * Tests for [LinkCommandLine]. In particular, tests command line emitted subject to the
 * presence of certain build variables.
 */
@RunWith(JUnit4::class)
class LinkCommandLineTest : LinkBuildVariablesTestCase() {
    @Throws(java.lang.Exception::class)
    private fun buildMockFeatures(): CcToolchainFeatures? {
        scratch.overwriteFile(
            "crosstool.bzl",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _impl(ctx):",
            "    return cc_common.create_cc_toolchain_config_info(",
            "        ctx = ctx,",
            "        toolchain_identifier = 'toolchain',",
            "        host_system_name = 'host',",
            "        target_system_name = 'target',",
            "        target_cpu = 'cpu',",
            "        target_libc = 'libc',",
            "        compiler = 'compiler',",
            "    )",
            "",
            "cc_toolchain_config_rule = rule(implementation = _impl)"
        )

        scratch.overwriteFile(
            "BUILD",
            "load(':crosstool.bzl', 'cc_toolchain_config_rule')",
            "load('//bazel_internal/test_rules/cc:ctf_rule.bzl', 'cc_toolchain_features')",
            "cc_toolchain_features(name = 'f', config = ':r')",
            "cc_toolchain_config_rule(name = 'r')"
        )

        scratch.overwriteFile("bazel_internal/test_rules/cc/BUILD")
        scratch.overwriteFile(
            "bazel_internal/test_rules/cc/ctf_rule.bzl",
            """
        load('@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl', 'CcToolchainConfigInfo')
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        MyInfo = provider()
        def _impl(ctx):
          return [MyInfo(f = cc_common.cc_toolchain_features(
                    toolchain_config_info = ctx.attr.config[CcToolchainConfigInfo],
                    tools_directory = "",
                  ))]
        cc_toolchain_features = rule(_impl, attrs = {"config":attr.label()})
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//:f")
        assertThat(target).isNotNull()
        return getStarlarkProvider(target, "MyInfo").getValue("f") as CcToolchainFeatures?
    }

    @get:Throws(java.lang.Exception::class)
    private val mockFeatureConfiguration: FeatureConfiguration
        get() = buildMockFeatures()
            .getFeatureConfiguration(
                com.google.common.collect.ImmutableSet.of<E?>(
                    Link.LinkTargetType.EXECUTABLE.actionName,
                    Link.LinkTargetType.NODEPS_DYNAMIC_LIBRARY.actionName,
                    Link.LinkTargetType.STATIC_LIBRARY.actionName,
                    CppActionNames.CPP_COMPILE,
                    CppActionNames.LINKSTAMP_COMPILE,
                    CppRuleClasses.INCLUDES,
                    CppRuleClasses.PREPROCESSOR_DEFINES,
                    CppRuleClasses.INCLUDE_PATHS,
                    CppRuleClasses.PIC
                )
            )

    @Throws(java.lang.Exception::class)
    private fun minimalConfiguration(variables: CcToolchainVariables.Builder): LinkCommandLine.Builder {
        return Builder()
            .setBuildVariables(variables.build())
            .setFeatureConfiguration(this.mockFeatureConfiguration)
    }

    @Throws(java.lang.Exception::class)
    private fun minimalConfiguration(): LinkCommandLine.Builder {
        return minimalConfiguration(mockBuildVariables)
    }

    /**
     * Tests that when linking without linkstamps, the exec command is the same as the link command.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkCommandIsExecCommandWhenNoLinkstamps() {
        val linkConfig: LinkCommandLine =
            minimalConfiguration()
                .setActionName(LinkTargetType.EXECUTABLE.actionName)
                .build()
        val rawLinkArgv: MutableList<String?>? = linkConfig.arguments()
        assertThat(linkConfig.arguments()).isEqualTo(rawLinkArgv)
    }

    /** Tests that symbol count output does not appear in argv when it should not.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolCountsDisabled() {
        val linkConfig: LinkCommandLine =
            minimalConfiguration()
                .forceToolPath("foo/bar/gcc")
                .build()
        val argv: MutableList<String?> = linkConfig.arguments()
        for (arg in argv) {
            Truth.assertThat(arg).doesNotContain("print-symbol-counts")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLibrariesToLink() {
        val variables: CcToolchainVariables.Builder =
            mockBuildVariables
                .addVariable(
                    LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(),
                    com.google.common.collect.ImmutableList.of<E?>(
                        forStaticLibrary("foo", false),
                        forStaticLibrary("bar", true)
                    )
                )

        val linkConfig: LinkCommandLine =
            minimalConfiguration(variables)
                .forceToolPath("foo/bar/gcc")
                .setActionName(LinkTargetType.NODEPS_DYNAMIC_LIBRARY.actionName)
                .build()
        val commandLine: String = com.google.common.base.Joiner.on(" ").join(linkConfig.arguments())
        Truth.assertThat(commandLine).matches(".*foo -Wl,-whole-archive bar -Wl,-no-whole-archive.*")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLibrarySearchDirectories() {
        val variables: CcToolchainVariables.Builder =
            mockBuildVariables
                .addStringSequenceVariable(
                    LinkBuildVariables.LIBRARY_SEARCH_DIRECTORIES.getVariableName(),
                    com.google.common.collect.ImmutableList.of<E?>("foo", "bar")
                )

        val linkConfig: LinkCommandLine =
            minimalConfiguration(variables)
                .setActionName(LinkTargetType.NODEPS_DYNAMIC_LIBRARY.actionName)
                .build()
        assertThat(linkConfig.arguments()).containsAtLeast("-Lfoo", "-Lbar").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkerParamFileForStaticLibrary() {
        val variables: CcToolchainVariables.Builder =
            mockBuildVariables
                .addVariable(
                    LinkBuildVariables.LINKER_PARAM_FILE.getVariableName(),
                    "LINKER_PARAM_FILE_PLACEHOLDER"
                )

        val linkConfig: LinkCommandLine =
            minimalConfiguration(variables)
                .setActionName(LinkTargetType.STATIC_LIBRARY.actionName)
                .setSplitCommandLine(true)
                .build()
        assertThat(linkConfig.getCommandLines().unpack().get(1).paramFileInfo.always()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkerParamFileForDynamicLibrary() {
        val variables: CcToolchainVariables.Builder =
            mockBuildVariables
                .addVariable(
                    LinkBuildVariables.LINKER_PARAM_FILE.getVariableName(),
                    "LINKER_PARAM_FILE_PLACEHOLDER"
                )

        val linkConfig: LinkCommandLine =
            minimalConfiguration(variables)
                .setActionName(LinkTargetType.NODEPS_DYNAMIC_LIBRARY.actionName)
                .setSplitCommandLine(true)
                .build()
        assertThat(linkConfig.getCommandLines().unpack().get(1).paramFileInfo.always()).isTrue()
    }

    @Throws(java.lang.Exception::class)
    private fun basicArgv(targetType: LinkTargetType): MutableList<String?> {
        return basicArgv(targetType, mockBuildVariables)
    }

    @Throws(java.lang.Exception::class)
    private fun basicArgv(targetType: LinkTargetType, variables: CcToolchainVariables.Builder): MutableList<String?> {
        val linkConfig: LinkCommandLine =
            minimalConfiguration(variables)
                .setActionName(targetType.actionName)
                .build()
        return linkConfig.arguments()
    }

    /** Tests that a "--force_pic" configuration applies "-pie" to executable links.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPicMode() {
        val pieArg = "-pie"

        // Disabled:
        Truth.assertThat(basicArgv(LinkTargetType.EXECUTABLE)).doesNotContain(pieArg)
        Truth.assertThat(basicArgv(LinkTargetType.NODEPS_DYNAMIC_LIBRARY)).doesNotContain(pieArg)
        Truth.assertThat(basicArgv(LinkTargetType.STATIC_LIBRARY)).doesNotContain(pieArg)
        Truth.assertThat(basicArgv(LinkTargetType.PIC_STATIC_LIBRARY)).doesNotContain(pieArg)
        Truth.assertThat(basicArgv(LinkTargetType.ALWAYS_LINK_STATIC_LIBRARY)).doesNotContain(pieArg)
        Truth.assertThat(basicArgv(LinkTargetType.ALWAYS_LINK_PIC_STATIC_LIBRARY)).doesNotContain(pieArg)

        val picVariables: CcToolchainVariables.Builder =
            mockBuildVariables.addVariable(LinkBuildVariables.FORCE_PIC.getVariableName(), "")
        // Enabled:
        useConfiguration("--force_pic")
        Truth.assertThat(basicArgv(LinkTargetType.EXECUTABLE, picVariables)).contains(pieArg)
        Truth.assertThat(basicArgv(LinkTargetType.NODEPS_DYNAMIC_LIBRARY, picVariables))
            .doesNotContain(pieArg)
        Truth.assertThat(basicArgv(LinkTargetType.STATIC_LIBRARY, picVariables)).doesNotContain(pieArg)
        Truth.assertThat(basicArgv(LinkTargetType.PIC_STATIC_LIBRARY, picVariables)).doesNotContain(pieArg)
        Truth.assertThat(basicArgv(LinkTargetType.ALWAYS_LINK_STATIC_LIBRARY, picVariables))
            .doesNotContain(pieArg)
        Truth.assertThat(basicArgv(LinkTargetType.ALWAYS_LINK_PIC_STATIC_LIBRARY, picVariables))
            .doesNotContain(pieArg)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSplitStaticLinkCommand() {
        useConfiguration("--nostart_end_lib")
        val linkConfig: LinkCommandLine =
            minimalConfiguration(
                mockBuildVariables
                    .addVariable(
                        LinkBuildVariables.OUTPUT_EXECPATH.getVariableName(), "a/FakeOutput"
                    )
                    .addVariable(
                        LinkBuildVariables.LINKER_PARAM_FILE.getVariableName(),
                        "LINKER_PARAM_FILE_PLACEHOLDER"
                    )
            )
                .setActionName(LinkTargetType.STATIC_LIBRARY.actionName)
                .forceToolPath("foo/bar/ar")
                .setSplitCommandLine(true)
                .setParameterFileType(ParameterFileType.UNQUOTED)
                .build()
        assertThat(linkConfig.getCommandLines().unpack().get(0).commandLine.arguments())
            .containsExactly("foo/bar/ar")
        assertThat(linkConfig.getCommandLines().unpack().get(1).paramFileInfo.always()).isTrue()
        assertThat(linkConfig.getParamCommandLine(null, PathMapper.NOOP))
            .containsExactly("rcsD", "a/FakeOutput")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSplitDynamicLinkCommand() {
        useConfiguration("--nostart_end_lib")
        val linkConfig: LinkCommandLine =
            minimalConfiguration(
                mockBuildVariables
                    .addVariable(
                        LinkBuildVariables.OUTPUT_EXECPATH.getVariableName(), "a/FakeOutput"
                    )
                    .addVariable(
                        LinkBuildVariables.LINKER_PARAM_FILE.getVariableName(), "some/file.params"
                    )
                    .addStringSequenceVariable(
                        LinkBuildVariables.USER_LINK_FLAGS.getVariableName(),
                        com.google.common.collect.ImmutableList.of<E?>("")
                    )
            )
                .setActionName(LinkTargetType.DYNAMIC_LIBRARY.actionName)
                .forceToolPath("foo/bar/linker")
                .setSplitCommandLine(true)
                .build()
        assertThat(linkConfig.getCommandLines().unpack().get(0).commandLine.arguments())
            .containsExactly("foo/bar/linker")
        assertThat(linkConfig.getParamCommandLine(null, PathMapper.NOOP))
            .containsExactly("-shared", "-o", "a/FakeOutput", "")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStaticLinkCommand() {
        useConfiguration("--nostart_end_lib")
        val linkConfig: LinkCommandLine =
            minimalConfiguration(
                mockBuildVariables
                    .addVariable(
                        LinkBuildVariables.OUTPUT_EXECPATH.getVariableName(), "a/FakeOutput"
                    )
            )
                .forceToolPath("foo/bar/ar")
                .setActionName(LinkTargetType.STATIC_LIBRARY.actionName)
                .build()
        val result: MutableList<String?>? = linkConfig.arguments()
        Truth.assertThat(result).containsExactly("rcsD", "a/FakeOutput").inOrder()
        assertThat(linkConfig.getLinkerPathString()).isEqualTo("foo/bar/ar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSplitAlwaysLinkLinkCommand() {
        val variables: CcToolchainVariables.Builder =
            CcToolchainVariables.builder()
                .addVariable(LinkBuildVariablesTestCase.Companion.SYSROOT_VARIABLE_NAME, "/usr/grte/v1")
                .addVariable(LinkBuildVariables.OUTPUT_EXECPATH.getVariableName(), "a/FakeOutput")
                .addVariable(LinkBuildVariables.LINKER_PARAM_FILE.getVariableName(), "some/file.params")
                .addVariable(
                    LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(),
                    com.google.common.collect.ImmutableList.of<E?>(
                        forObjectFile("foo.o", false),
                        forObjectFile("bar.o", false)
                    )
                )

        val linkConfig: LinkCommandLine =
            minimalConfiguration(variables)
                .setActionName(LinkTargetType.ALWAYS_LINK_STATIC_LIBRARY.actionName)
                .forceToolPath("foo/bar/ar")
                .setSplitCommandLine(true)
                .build()

        assertThat(linkConfig.getCommandLines().unpack().get(0).commandLine.arguments())
            .containsExactly("foo/bar/ar")
        assertThat(linkConfig.getParamCommandLine(null, PathMapper.NOOP))
            .containsExactly("rcsD", "a/FakeOutput", "foo.o", "bar.o")
            .inOrder()
    }

    private fun createTreeArtifact(name: String?): SpecialArtifact {
        val fs: FileSystem = scratch.getFileSystem()
        val execRoot: Path? = fs.getPath(com.google.devtools.build.lib.testutil.TestUtils.tmpDir())
        val execPath: PathFragment? = PathFragment.create("out").getRelative(name)
        return ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out"), execPath
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTreeArtifactLink() {
        val testTreeArtifact: SpecialArtifact = createTreeArtifact("library_directory")

        val library0: TreeFileArtifact = TreeFileArtifact.createTreeOutput(testTreeArtifact, "library0.o")
        val library1: TreeFileArtifact = TreeFileArtifact.createTreeOutput(testTreeArtifact, "library1.o")

        // We don't read the tree artifact or its contents, so MISSING_FILE_MARKER is OK
        val treeArtifactValue: TreeArtifactValue =
            TreeArtifactValue.newBuilder(testTreeArtifact)
                .putChild(library0, FileArtifactValue.MISSING_FILE_MARKER)
                .putChild(library1, FileArtifactValue.MISSING_FILE_MARKER)
                .build()

        val fakeActionInputFileCache: FakeActionInputFileCache = FakeActionInputFileCache()
        fakeActionInputFileCache.putTreeArtifact(testTreeArtifact, treeArtifactValue)

        val treeArtifactsPaths: Iterable<String?> =
            com.google.common.collect.ImmutableList.of<E?>(testTreeArtifact.getExecPathString())
        val treeFileArtifactsPaths: Iterable<String?> =
            com.google.common.collect.ImmutableList.of<E?>(library0.getExecPathString(), library1.getExecPathString())

        val linkConfig: LinkCommandLine =
            minimalConfiguration(
                mockBuildVariables
                    .addVariable(
                        LinkBuildVariables.LINKER_PARAM_FILE.getVariableName(), "some/file.params"
                    )
                    .addVariable(
                        LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(),
                        com.google.common.collect.ImmutableList.of<E?>(
                            forObjectFileGroup(
                                com.google.common.collect.ImmutableList.of<Artifact?>(testTreeArtifact),
                                false
                            )
                        )
                    )
            )
                .forceToolPath("foo/bar/gcc")
                .setActionName(LinkTargetType.STATIC_LIBRARY.actionName)
                .setSplitCommandLine(true)
                .build()

        // Should only reference the tree artifact.
        verifyArguments(
            linkConfig.arguments(null, PathMapper.NOOP), treeArtifactsPaths, treeFileArtifactsPaths
        )
        verifyArguments(linkConfig.arguments(), treeArtifactsPaths, treeFileArtifactsPaths)
        verifyArguments(
            linkConfig.getParamCommandLine(null, PathMapper.NOOP),
            treeArtifactsPaths,
            treeFileArtifactsPaths
        )

        // Should only reference tree file artifacts.
        verifyArguments(
            linkConfig.arguments(fakeActionInputFileCache, PathMapper.NOOP),
            treeFileArtifactsPaths,
            treeArtifactsPaths
        )
        verifyArguments(
            linkConfig.arguments(fakeActionInputFileCache, PathMapper.NOOP),
            treeFileArtifactsPaths,
            treeArtifactsPaths
        )
        verifyArguments(
            linkConfig.getParamCommandLine(fakeActionInputFileCache, PathMapper.NOOP),
            treeFileArtifactsPaths,
            treeArtifactsPaths
        )
    }

    private fun forStaticLibrary(name: String, isWholeArchive: Boolean): StarlarkInfo {
        return StructProvider.STRUCT.create(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "type",
                "static_library",
                "name",
                name,
                "is_whole_archive",
                isWholeArchive
            ),
            ""
        )
    }

    private fun forObjectFile(path: String, isWholeArchive: Boolean): StarlarkInfo {
        return StructProvider.STRUCT.create(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "type",
                "object_file",
                "name",
                path,
                "is_whole_archive",
                isWholeArchive
            ),
            ""
        )
    }

    private fun forObjectFileGroup(
        objectFiles: com.google.common.collect.ImmutableList<Artifact?>?, isWholeArchive: Boolean
    ): StarlarkInfo {
        return StructProvider.STRUCT.create(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "type",
                "object_file_group",
                "object_files",
                StarlarkList.immutableCopyOf<T?>(objectFiles),
                "is_whole_archive",
                isWholeArchive
            ),
            ""
        )
    }

    companion object {
        private val mockBuildVariables: CcToolchainVariables.Builder
            get() = getMockBuildVariables(com.google.common.collect.ImmutableList.of<String?>())

        private fun getMockBuildVariables(
            linkstampOutputs: com.google.common.collect.ImmutableList<String?>?
        ): CcToolchainVariables.Builder {
            val result: CcToolchainVariables.Builder = CcToolchainVariables.builder()

            result.addVariable(LinkBuildVariables.GENERATE_INTERFACE_LIBRARY.getVariableName(), "no")
            result.addVariable(LinkBuildVariables.INTERFACE_LIBRARY_INPUT.getVariableName(), "ignored")
            result.addVariable(LinkBuildVariables.INTERFACE_LIBRARY_OUTPUT.getVariableName(), "ignored")
            result.addVariable(LinkBuildVariables.INTERFACE_LIBRARY_BUILDER.getVariableName(), "ignored")
            result.addStringSequenceVariable(
                LinkBuildVariables.LINKSTAMP_PATHS.getVariableName(), linkstampOutputs
            )

            return result
        }

        private fun verifyArguments(
            arguments: Iterable<String?>?,
            allowedArguments: Iterable<String?>,
            disallowedArguments: Iterable<String?>
        ) {
            Truth.assertThat(arguments).containsAtLeastElementsIn(allowedArguments)
            Truth.assertThat(arguments).containsNoneIn(disallowedArguments)
        }
    }
}
