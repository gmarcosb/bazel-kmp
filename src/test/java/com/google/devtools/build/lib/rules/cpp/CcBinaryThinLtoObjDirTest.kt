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

import com.google.devtools.build.lib.actions.Action

/** Tests for cc_binary with treeArtifacts, ThinLTO and separate obj dir for thinlto.  */
@RunWith(JUnit4::class)
class CcBinaryThinLtoObjDirTest : BuildViewTestCase() {
    private var targetName = "bin"

    @get:Throws(java.lang.Exception::class)
    private val currentTarget: ConfiguredTarget
        get() = getConfiguredTarget("//pkg:" + targetName)

    @get:Throws(java.lang.Exception::class)
    private val linkAction: SpawnAction
        get() {
            val pkg: ConfiguredTarget = this.currentTarget
            val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
            val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction
            assertThat(linkAction.getOutputs()).containsExactly(pkgArtifact)
            return linkAction
        }

    @Throws(java.lang.Exception::class)
    private fun getBackendAction(path: String?): LtoBackendAction {
        return getPredecessorByInputName(this.linkAction, path) as LtoBackendAction
    }

    @get:Throws(java.lang.Exception::class)
    private val rootExecPath: String
        get() {
            val pkg: ConfiguredTarget = this.currentTarget
            val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
            return pkgArtifact.getRoot().getExecPathString()
        }

    @Throws(java.lang.Exception::class)
    private fun getIndexAction(backendAction: LtoBackendAction): SpawnAction {
        return getPredecessorByInputName(
            backendAction,
            (backendAction.getPrimaryOutput().getExecPathString() + ".thinlto.bc")
                .replaceFirst(".lto-obj/", ".lto/")
        ) as SpawnAction
    }

    @Before
    @Throws(IOException::class)
    fun createBasePkg() {
        scratch.overwriteFile(
            "base/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "system_malloc",
            visibility = ["//visibility:public"],
        )

        cc_library(
            name = "empty_lib",
            visibility = ["//visibility:public"],
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    fun createBuildFiles(vararg extraCcBinaryParameters: String?) {
        scratch.file(
            "pkg/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "load(':do_gen.bzl', 'test_generation', 'test_generation_2', 'test_generation_empty')",
            "package(features = ['thin_lto', 'use_lto_native_object_directory'])",
            "",
            "test_generation(",
            "          name = 'tree',",
            ")",
            "test_generation_2(",
            "          name = 'tree_2',",
            ")",
            "test_generation_empty(",
            "          name = 'tree_empty',",
            ")",
            "cc_binary(name = '" + targetName + "',",
            "          srcs = ['binfile.cc', ],",
            "          deps = [ ':lib', ':tree', ':tree_2', 'tree_empty'], ",
            java.lang.String.join("", *extraCcBinaryParameters),
            "          link_extra_lib = '//base:empty_lib', ",
            "          malloc = '//base:system_malloc')",
            "cc_library(name = 'lib',",
            "        srcs = ['libfile.cc'],",
            "        hdrs = ['libfile.h'],",
            "        linkstamp = 'linkstamp.cc',",
            "       )"
        )
        scratch.file(
            "pkg/do_gen.bzl",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "def _create_cc_impl(ctx):",
            "    directory = ctx.actions.declare_directory(ctx.label.name + \"_gen_cc\")",
            "    ctx.actions.run_shell(",
            "        command = \"echo -e '#include \\\"pkg/treelib.h\\\"\\n"
                    + "Foo::~Foo() { }' > %s/file1.cc\" % directory.path,",
            "        outputs=[directory]",
            "    )",
            "    return DefaultInfo(files=depset([directory]))",
            "",
            "_create_cc = rule(implementation=_create_cc_impl)",
            "def test_generation(name):",
            "    _create_cc(name=name + \"_ccgen\")",
            "",
            "    cc_library(",
            "        name = name,",
            "        hdrs = [\"treelib.h\",],",
            "        srcs = [\":\" + name + \"_ccgen\",]",
            ")",
            "",
            "def _create_cc_impl_2(ctx):",
            "    directory = ctx.actions.declare_directory(ctx.label.name + \"_gen_cc_2\")",
            "    ctx.actions.run_shell(",
            ("        command = \"echo -e '#include \\\"pkg/treelib_2.h\\\"\\n"
                    + "int two() { return 2; }' > %s/file1.cc\" % directory.path +"
                    + " \"echo -e '#include \\\"pkg/treelib_2.h\\\"\\n"
                    + "int three() { return 3; }' > %s/file2.cc\" % directory.path,"),
            "        outputs=[directory]",
            "    )",
            "    return DefaultInfo(files=depset([directory]))",
            "",
            "_create_cc_2 = rule(implementation=_create_cc_impl_2)",
            "def test_generation_2(name):",
            "    _create_cc_2(name=name + \"_ccgen_2\")",
            "",
            "    cc_library(",
            "        name = name,",
            "        hdrs = [\"treelib_2.h\",],",
            "        srcs = [\":\" + name + \"_ccgen_2\",]",
            ")",
            "",
            "def _create_cc_impl_empty(ctx):",
            "    directory = ctx.actions.declare_directory(ctx.label.name + \"_gen_cc_empty\")",
            "    ctx.actions.run_shell(",
            "        command = \"echo  'empty'\",",
            "        outputs=[directory]",
            "    )",
            "    return DefaultInfo(files=depset([directory]))",
            "",
            "_create_cc_empty = rule(implementation=_create_cc_impl_empty)",
            "def test_generation_empty(name):",
            "    _create_cc_empty(name=name + \"_ccgen_empty\")",
            "",
            "    cc_library(",
            "        name = name,",
            "        srcs = [\":\" + name + \"_ccgen_empty\",]",
            ")"
        )

        scratch.file("pkg/treelib.h", "class Foo{ public:  ~Foo(); };")
        scratch.file("pkg/treelib_2.h", "int two(); int three();")

        scratch.file(
            "pkg/binfile.cc",
            "#include \"pkg/libfile.h\"",
            "#include \"pkg/treelib.h\"",
            "#include \"pkg/treelib_2.h\"",
            "int main() {",
            "  Foo foo;",
            "  return pkg() + two() + three(); }"
        )
        scratch.file("pkg/libfile.cc", "int pkg() { return 42; }")
        scratch.file("pkg/libfile.h", "int pkg();")
        scratch.file("pkg/linkstamp.cc")
    }

    @Throws(java.lang.Exception::class)
    fun createTestFiles(extraTestParameters: String?, extraLibraryParameters: String?) {
        scratch.file(
            "pkg/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "load('@rules_cc//cc:cc_test.bzl', 'cc_test')",
            "load(':do_gen.bzl', 'test_generation')",
            "package(features = ['thin_lto', 'use_lto_native_object_directory'])",
            "",
            "test_generation(",
            "          name = 'tree',",
            ")",
            "cc_test(",
            "    name = 'bin_test',",
            "    srcs = ['bin_test.cc', ],",
            "    deps = [ ':lib', ':tree', ], ",
            extraTestParameters,
            "    link_extra_lib = '//base:empty_lib', ",
            "    malloc = '//base:system_malloc'",
            ")",
            "cc_test(",
            "    name = 'bin_test2',",
            "    srcs = ['bin_test2.cc', ],",
            "    deps = [ ':lib', ':tree', ], ",
            extraTestParameters,
            "    link_extra_lib = '//base:empty_lib', ",
            "    malloc = '//base:system_malloc'",
            ")",
            "cc_library(",
            "    name = 'lib',",
            "    srcs = ['libfile.cc'],",
            "    hdrs = ['libfile.h'],",
            extraLibraryParameters,
            "    linkstamp = 'linkstamp.cc',",
            ")"
        )
        scratch.file(
            "pkg/do_gen.bzl",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "def _create_cc_impl(ctx):",
            "    directory = ctx.actions.declare_directory(ctx.label.name + \"_gen_cc\")",
            "    ctx.actions.run_shell(",
            "        command = \"echo -e '#include \\\"pkg/treelib.h\\\"\\n"
                    + "Foo::~Foo() { }' > %s/file.cc\" % directory.path,",
            "        outputs=[directory]",
            "    )",
            "    return DefaultInfo(files=depset([directory]))",
            "",
            "_create_cc = rule(implementation=_create_cc_impl)",
            "def test_generation(name):",
            "    _create_cc(name=name + \"_ccgen\")",
            "",
            "    cc_library(",
            "        name = name,",
            "        hdrs = [\"treelib.h\",],",
            "        srcs = [\":\" + name + \"_ccgen\",]",
            ")"
        )
        scratch.file("pkg/treelib.h", "class Foo{ public:  ~Foo(); };")
        scratch.file(
            "pkg/bin_test.cc",
            "#include \"pkg/libfile.h\"",
            "#include \"pkg/treelib.h\"",
            "int main() { Foo foo; return pkg(); }"
        )
        scratch.file(
            "pkg/bin_test2.cc",
            "#include \"pkg/libfile.h\"",
            "#include \"pkg/treelib.h\"",
            "int main() { Foo foo; return pkg(); }"
        )
        scratch.file("pkg/libfile.cc", "int pkg() { return 42; }")
        scratch.file("pkg/libfile.h", "int pkg();")
        scratch.file("pkg/linkstamp.cc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionGraph() {
        createBuildFiles()
        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC)

        /*
    We follow the chain from the final product backwards.

    binary <=[Link]=
    .lto-obj/...o <=[LTOBackend]=
    {.o.thinlto.bc,.o.imports} <=[LTOIndexing]=
    .o <= [CppCompile] .cc
    */
        val pkg: ConfiguredTarget = this.currentTarget
        val linkAction: SpawnAction = this.linkAction
        val rootExecPath = this.rootExecPath

        assertThat(ActionsTestUtil.getFirstArtifactEndingWith(linkAction.getInputs(), "linkstamp.o"))
            .isNotNull()

        val commandLine: MutableList<String> = linkAction.getArguments()
        val prefix: String? =
            targetConfiguration.getOutputDirectory(RepositoryName.MAIN).getExecPathString()
        Truth.assertThat(commandLine)
            .containsAtLeast(
                prefix + "/bin/pkg/bin.lto.merged.o",
                "thinlto_param_file=" + prefix + "/bin/pkg/bin-lto-final.params"
            )
            .inOrder()

        // We have no bitcode files: all files have pkg/bin.lto/
        for (arg in commandLine) {
            if (arg.contains("_objs") && !arg.contains("linkstamp.o")) {
                Truth.assertThat(arg).contains("pkg/bin.lto")
            }
        }

        Truth.assertThat(artifactsToStrings(linkAction.getInputs()))
            .containsAtLeast(
                "bin pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o",
                "bin pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o",
                "bin pkg/bin-lto-final.params"
            )

        val backendAction: LtoBackendAction =
            getBackendAction("pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o")
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")

        Truth.assertThat(artifactsToStrings(backendAction.getInputs()))
            .containsAtLeast(
                "bin pkg/bin.lto/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o.thinlto.bc",
                "bin pkg/bin.lto/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o.imports"
            )

        assertThat(backendAction.getArguments())
            .containsAtLeast(
                ("thinlto_index="
                        + prefix
                        + "/bin/pkg/bin.lto/"
                        + rootExecPath
                        + "/pkg/_objs/bin/binfile.pic.o.thinlto.bc"),
                ("thinlto_output_object_file="
                        + prefix
                        + "/bin/pkg/bin.lto-obj/"
                        + rootExecPath
                        + "/pkg/_objs/bin/binfile.pic.o"),
                "thinlto_input_bitcode_file=" + prefix + "/bin/pkg/_objs/bin/binfile.pic.o"
            )

        val indexAction: SpawnAction = getIndexAction(backendAction)

        val configuredTargetValue: RuleConfiguredTargetValue =
            getSkyframeExecutor()
                .getEvaluator()
                .getExistingEntryAtCurrentlyEvaluatingVersion(
                    ConfiguredTargetKey.builder()
                        .setLabel(pkg.getLabel())
                        .setConfiguration(getConfiguration(pkg))
                        .build()
                )
                .getValue() as RuleConfiguredTargetValue
        val linkstampCompileActions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata>? =
            configuredTargetValue.getActions().stream()
                .filter({ a -> a.getMnemonic().equals("CppLinkstampCompile") })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(linkstampCompileActions).hasSize(1)
        val linkstampCompileAction: ActionAnalysisMetadata = linkstampCompileActions.get(0)
        assertThat(indexAction.getInputs().toList())
            .containsNoneIn(linkstampCompileAction.getOutputs())

        assertThat(indexAction.getArguments())
            .doesNotContain("thinlto_param_file=" + prefix + "/bin/pkg/bin-lto-final.params")

        Truth.assertThat(artifactsToStrings(indexAction.getOutputs()))
            .containsAtLeast(
                "bin pkg/bin.lto/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o.imports",
                "bin pkg/bin.lto/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o.thinlto.bc",
                "bin pkg/bin.lto/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o.imports",
                "bin pkg/bin.lto/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o.thinlto.bc",
                "bin pkg/bin-lto-final.params"
            )

        assertThat(indexAction.getMnemonic()).isEqualTo("CppLTOIndexing")

        Truth.assertThat(artifactsToStrings(indexAction.getInputs()))
            .containsAtLeast(
                "bin pkg/_objs/bin/binfile.pic.indexing.o", "bin pkg/_objs/lib/libfile.pic.indexing.o"
            )

        val bitcodeAction: CppCompileAction? =
            getPredecessorByInputName(indexAction, "pkg/_objs/bin/binfile.pic.indexing.o") as CppCompileAction?
        assertThat(bitcodeAction.getMnemonic()).isEqualTo("CppCompile")
        com.google.common.truth.Subject.contains("lto_indexing_bitcode=" + prefix + "/bin/pkg/_objs/bin/binfile.pic.indexing.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkshared() {
        targetName = "bin.so"
        createBuildFiles("linkshared = 1,")
        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC)
        useConfiguration()

        val linkAction: SpawnAction = this.linkAction
        val rootExecPath = this.rootExecPath

        val backendAction: Action? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.so.lto-obj/" + rootExecPath + "/pkg/_objs/bin.so/binfile.pic.o"
            )
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoLinkstatic() {
        createBuildFiles("linkstatic = 0,")
        setupThinLTOCrosstool(
            CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
            CppRuleClasses.SUPPORTS_PIC,
            CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES
        )

        /*
    We follow the chain from the final product backwards to verify intermediate actions.

    binary <=[Link]=
    .ifso <=[SolibSymlink]=
    _S...ifso <=[SolibSymlink]=
    .ifso <=[Link]=
    .lto-obj/...o <=[LTOBackend]=
    {.o.thinlto.bc,.o.imports} <=[LTOIndexing]=
    .o <= [CppCompile] .cc
    */
        val linkAction: SpawnAction = this.linkAction
        val rootExecPath = this.rootExecPath

        val commandLine: MutableList<String>? = linkAction.getArguments()
        val prefix: String? =
            targetConfiguration.getOutputDirectory(RepositoryName.MAIN).getExecPathString()

        Truth.assertThat(commandLine).contains("-Wl,@" + prefix + "/bin/pkg/bin-lto-final.params")

        // We have no bitcode files: all files have pkg/bin.lto/
        for (arg in commandLine!!) {
            if (arg.contains("_objs") && !arg.contains("linkstamp.o")) {
                Truth.assertThat(arg).contains("pkg/bin.lto")
            }
        }

        Truth.assertThat(artifactsToStrings(linkAction.getInputs()))
            .containsAtLeast(
                "bin pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o",
                "bin _solib_k8/libpkg_Sliblib.ifso",
                "bin pkg/bin-lto-final.params"
            )

        val solibSymlinkAction: SolibSymlinkAction? =
            getPredecessorByInputName(linkAction, "_solib_k8/libpkg_Sliblib.ifso") as SolibSymlinkAction?
        assertThat(solibSymlinkAction.getMnemonic()).isEqualTo("SolibSymlink")

        val libLinkAction: SpawnAction? =
            getPredecessorByInputName(solibSymlinkAction, "bin/pkg/liblib.ifso") as SpawnAction?
        assertThat(libLinkAction.getMnemonic()).isEqualTo("CppLink")

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                libLinkAction,
                "pkg/liblib.so.lto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")

        Truth.assertThat(artifactsToStrings(backendAction.getInputs()))
            .contains(
                "bin pkg/liblib.so.lto/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o.thinlto.bc"
            )

        assertThat(backendAction.getArguments())
            .containsAtLeast(
                ("thinlto_index="
                        + prefix
                        + "/bin/pkg/liblib.so.lto/"
                        + rootExecPath
                        + "/pkg/_objs/lib/libfile.pic.o.thinlto.bc"),
                ("thinlto_output_object_file="
                        + prefix
                        + "/bin/pkg/liblib.so.lto-obj/"
                        + rootExecPath
                        + "/pkg/_objs/lib/libfile.pic.o"),
                "thinlto_input_bitcode_file=" + prefix + "/bin/pkg/_objs/lib/libfile.pic.o"
            )

        val indexAction: SpawnAction? =
            getPredecessorByInputName(
                backendAction,
                "pkg/liblib.so.lto/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o.thinlto.bc"
            ) as SpawnAction?

        Truth.assertThat(artifactsToStrings(indexAction.getOutputs()))
            .containsAtLeast(
                "bin pkg/liblib.so.lto/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o.imports",
                "bin pkg/liblib.so.lto/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o.thinlto.bc",
                "bin pkg/liblib.so-lto-final.params"
            )

        assertThat(indexAction.getMnemonic()).isEqualTo("CppLTOIndexing")

        Truth.assertThat(artifactsToStrings(indexAction.getInputs()))
            .contains("bin pkg/_objs/lib/libfile.pic.indexing.o")

        val bitcodeAction: CppCompileAction? =
            getPredecessorByInputName(indexAction, "pkg/_objs/lib/libfile.pic.indexing.o") as CppCompileAction?
        assertThat(bitcodeAction.getMnemonic()).isEqualTo("CppCompile")
        com.google.common.truth.Subject.contains("lto_indexing_bitcode=" + prefix + "/bin/pkg/_objs/lib/libfile.pic.indexing.o")
    }

    /** Helper method that checks that a .dwp has the expected generating action structure.  */
    @Throws(java.lang.Exception::class)
    private fun validateDwp(
        dwpFile: Artifact, toolchain: CcToolchainProvider, expectedInputs: MutableList<String?>?
    ) {
        val dwpAction: SpawnAction = getGeneratingAction(dwpFile) as SpawnAction
        val dwpToolPath: String? =
            CcToolchainProvider.getToolPathString(
                toolchain.getToolPaths(),
                Tool.DWP,
                toolchain.getCcToolchainLabel(),
                toolchain.getToolchainIdentifier()
            )
        assertThat(dwpAction.getMnemonic()).isEqualTo("CcGenerateDwp")
        Truth.assertThat(dwpToolPath).isEqualTo(dwpAction.getCommandFilename())
        val commandArgs: MutableList<String?> = dwpAction.getArguments()
        // The first argument should be the command being executed.
        Truth.assertThat(dwpToolPath).isEqualTo(commandArgs.get(0))
        // The final two arguments should be "-o dwpOutputFile".
        Truth.assertThat(commandArgs.subList(commandArgs.size() - 2, commandArgs.size()))
            .containsExactly("-o", dwpFile.getExecPathString())
            .inOrder()
        // The remaining arguments should be the set of .dwo inputs (in any order).
        Truth.assertThat(commandArgs.subList(1, commandArgs.size() - 2))
            .containsExactlyElementsIn(expectedInputs)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFission() {
        createBuildFiles()
        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.PER_OBJECT_DEBUG_INFO)
        useConfiguration("--fission=yes", "--copt=-g0")

        val rootExecPath = this.rootExecPath
        var backendAction: LtoBackendAction =
            getBackendAction("pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o")
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
        Truth.assertThat(artifactsToStrings(backendAction.getOutputs()))
            .containsExactly(
                "bin pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o",
                "bin pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.dwo"
            )

        assertThat(backendAction.getArguments()).containsAtLeast("-g0", "per_object_debug_info_option")

        backendAction =
            getBackendAction("pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o")
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
        Truth.assertThat(artifactsToStrings(backendAction.getOutputs()))
            .containsExactly(
                "bin pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o",
                "bin pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.dwo"
            )

        com.google.common.truth.Subject.contains("per_object_debug_info_option")

        // Now check the dwp action.
        val pkg: ConfiguredTarget = this.currentTarget
        val dwpFile: Artifact = getFileConfiguredTarget(pkg.getLabel() + ".dwp").getArtifact()
        val rootPrefix: PathFragment = dwpRootPrefix(dwpFile)
        val ruleContext: RuleContext = getRuleContext(pkg)
        val toolchain: CcToolchainProvider = CppHelper.getToolchain(ruleContext)
        validateDwp(
            dwpFile,
            toolchain,
            com.google.common.collect.ImmutableList.of<String?>(
                rootPrefix.toString() + "/pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.dwo",
                rootPrefix.toString() + "/pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.dwo"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoLinkstaticFission() {
        createBuildFiles("linkstatic = 0,")
        setupThinLTOCrosstool(
            CppRuleClasses.SUPPORTS_PIC,
            CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
            CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
            CppRuleClasses.PER_OBJECT_DEBUG_INFO
        )
        useConfiguration("--fission=yes")

        val pkg: ConfiguredTarget = getConfiguredTarget("//pkg:bin")
        val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
        val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction
        val rootExecPath: String? = pkgArtifact.getRoot().getExecPathString()

        val solibSymlinkAction: SolibSymlinkAction? =
            getPredecessorByInputName(linkAction, "_solib_k8/libpkg_Sliblib.ifso") as SolibSymlinkAction?
        assertThat(solibSymlinkAction.getMnemonic()).isEqualTo("SolibSymlink")

        val libLinkAction: SpawnAction? =
            getPredecessorByInputName(solibSymlinkAction, "bin/pkg/liblib.ifso") as SpawnAction?
        assertThat(libLinkAction.getMnemonic()).isEqualTo("CppLink")

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                libLinkAction,
                "pkg/liblib.so.lto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
        Truth.assertThat(artifactsToStrings(backendAction.getOutputs()))
            .containsExactly(
                "bin pkg/liblib.so.lto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o",
                "bin pkg/liblib.so.lto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.dwo"
            )

        com.google.common.truth.Subject.contains("per_object_debug_info_option")

        // Check the dwp action.
        val dwpFile: Artifact = getFileConfiguredTarget(pkg.getLabel() + ".dwp").getArtifact()
        val rootPrefix: PathFragment = dwpRootPrefix(dwpFile)
        val ruleContext: RuleContext = getRuleContext(pkg)
        val toolchain: CcToolchainProvider = CppHelper.getToolchain(ruleContext)
        validateDwp(
            dwpFile,
            toolchain,
            com.google.common.collect.ImmutableList.of<String?>(
                rootPrefix.toString() + "/pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.dwo"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkstaticCcTestFission() {
        createTestFiles("linkstatic = 1,", "")

        setupThinLTOCrosstool(
            CppRuleClasses.SUPPORTS_PIC,
            CppRuleClasses.THIN_LTO_LINKSTATIC_TESTS_USE_SHARED_NONLTO_BACKENDS,
            CppRuleClasses.PER_OBJECT_DEBUG_INFO
        )
        useConfiguration(
            "--fission=yes", "--features=thin_lto_linkstatic_tests_use_shared_nonlto_backends"
        )

        val pkg: ConfiguredTarget = getConfiguredTarget("//pkg:bin_test")
        val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
        val rootExecPath: String? = pkgArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction

        // All backends should be shared non-LTO in this case
        var backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction,
                "shared.nonlto-obj/" + rootExecPath + "/pkg/_objs/bin_test/bin_test.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
        Truth.assertThat(artifactsToStrings(backendAction.getOutputs()))
            .containsExactly(
                "bin shared.nonlto-obj/" + rootExecPath + "/pkg/_objs/bin_test/bin_test.pic.o",
                "bin shared.nonlto-obj/" + rootExecPath + "/pkg/_objs/bin_test/bin_test.pic.dwo"
            )

        com.google.common.truth.Subject.contains("per_object_debug_info_option")

        backendAction =
            getPredecessorByInputName(
                linkAction, "shared.nonlto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
        com.google.common.truth.Subject.contains("-fPIC")
        Truth.assertThat(artifactsToStrings(backendAction.getOutputs()))
            .containsExactly(
                "bin shared.nonlto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o",
                "bin shared.nonlto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.dwo"
            )

        com.google.common.truth.Subject.contains("per_object_debug_info_option")

        // Now check the dwp action.
        val dwpFile: Artifact = getFileConfiguredTarget(pkg.getLabel() + ".dwp").getArtifact()
        val rootPrefix: PathFragment = dwpRootPrefix(dwpFile)
        val ruleContext: RuleContext = getRuleContext(pkg)
        val toolchain: CcToolchainProvider = CppHelper.getToolchain(ruleContext)
        validateDwp(
            dwpFile,
            toolchain,
            com.google.common.collect.ImmutableList.of<String?>(
                rootPrefix.toString() + "/shared.nonlto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.dwo",
                (rootPrefix
                    .toString() + "/shared.nonlto-obj/"
                        + rootExecPath
                        + "/pkg/_objs/bin_test/bin_test.pic.dwo")
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkstaticCcTest() {
        createTestFiles("linkstatic = 1,", "")

        setupThinLTOCrosstool(
            CppRuleClasses.SUPPORTS_PIC,
            CppRuleClasses.THIN_LTO_LINKSTATIC_TESTS_USE_SHARED_NONLTO_BACKENDS,
            CppRuleClasses.PER_OBJECT_DEBUG_INFO
        )
        useConfiguration("--features=thin_lto_linkstatic_tests_use_shared_nonlto_backends")

        val pkg: ConfiguredTarget = getConfiguredTarget("//pkg:bin_test")
        val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
        val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction

        val pkg2: ConfiguredTarget = getConfiguredTarget("//pkg:bin_test2")
        val pkgArtifact2: Artifact = getFilesToBuild(pkg2).getSingleton()
        val linkAction2: SpawnAction = getGeneratingAction(pkgArtifact2) as SpawnAction

        // All backends should be shared non-LTO in this case
        val rootExecPath1: String? = pkgArtifact.getRoot().getExecPathString()
        val rootExecPath2: String? = pkgArtifact.getRoot().getExecPathString()
        var backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction,
                "shared.nonlto-obj/" + rootExecPath1 + "/pkg/_objs/bin_test/bin_test.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")

        backendAction =
            getPredecessorByInputName(
                linkAction, "shared.nonlto-obj/" + rootExecPath1 + "/pkg/_objs/lib/libfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
        com.google.common.truth.Subject.contains("-fPIC")

        val backendAction2: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction2, "shared.nonlto-obj/" + rootExecPath2 + "/pkg/_objs/lib/libfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction2.getMnemonic()).isEqualTo("CcLtoBackendCompile")

        assertThat(backendAction).isEqualTo(backendAction2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestOnlyTarget() {
        createBuildFiles("testonly = 1,")

        setupThinLTOCrosstool(
            CppRuleClasses.SUPPORTS_PIC,
            CppRuleClasses.THIN_LTO_LINKSTATIC_TESTS_USE_SHARED_NONLTO_BACKENDS
        )
        useConfiguration("--features=thin_lto_linkstatic_tests_use_shared_nonlto_backends")

        val pkg: ConfiguredTarget = getConfiguredTarget("//pkg:bin")
        val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
        val rootExecPath: String? = pkgArtifact.getRoot().getExecPathString()
        val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "shared.nonlto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUseSharedAllLinkstatic() {
        createBuildFiles()

        setupThinLTOCrosstool(
            CppRuleClasses.THIN_LTO_ALL_LINKSTATIC_USE_SHARED_NONLTO_BACKENDS,
            CppRuleClasses.SUPPORTS_PIC
        )
        useConfiguration("--features=thin_lto_all_linkstatic_use_shared_nonlto_backends")

        val pkg: ConfiguredTarget = getConfiguredTarget("//pkg:bin")
        val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
        val rootExecPath: String? = pkgArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "shared.nonlto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
    }

    private fun getPredecessorByInputName(action: Action, str: String?): Action? {
        for (a in action.getInputs().toList()) {
            if (a.getExecPathString().contains(str)) {
                return getGeneratingAction(a)
            }
        }
        return null
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFdoInstrument() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        package(features = [
            "thin_lto",
            "use_lto_native_object_directory",
        ])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")

        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.FDO_INSTRUMENT)
        useConfiguration("--fdo_instrument=profiles")

        val pkg: ConfiguredTarget = getConfiguredTarget("//pkg:bin")

        val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
        val rootExecPath: String? = pkgArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(pkgArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o"
            ) as LtoBackendAction?
        // If the LtoBackendAction incorrectly tries to add the fdo_instrument
        // feature, we will fail with an "unknown variable 'fdo_instrument_path'"
        // error. But let's also explicitly confirm that the fdo_instrument
        // option didn't end up here.
        assertThat(backendAction.getArguments()).doesNotContain("fdo_instrument_option")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLtoIndexOpt() {
        createBuildFiles()

        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC)
        useConfiguration("--ltoindexopt=anltoindexopt")

        /*
    We follow the chain from the final product backwards.

    binary <=[Link]=
    .lto-obj/...o <=[LTOBackend]=
    {.o.thinlto.bc,.o.imports} <=[LTOIndexing]=
    .o <= [CppCompile] .cc
    */
        val pkg: ConfiguredTarget = getConfiguredTarget("//pkg:bin")

        val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
        val rootExecPath: String? = pkgArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(pkgArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")

        val indexAction: SpawnAction? =
            getPredecessorByInputName(
                backendAction,
                "pkg/bin.lto/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o.thinlto.bc"
            ) as SpawnAction?

        com.google.common.truth.Subject.contains("anltoindexopt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLtoStandaloneCommandLines() {
        createBuildFiles()

        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC)
        useConfiguration(
            "--ltoindexopt=anltoindexopt",
            "--incompatible_make_thinlto_command_lines_standalone",
            "--features=thin_lto",
            "--features=use_lto_native_object_directory"
        )

        /*
    We follow the chain from the final product backwards.

    binary <=[Link]=
    .lto-obj/...o <=[LTOBackend]=
    {.o.thinlto.bc,.o.imports} <=[LTOIndexing]=
    .o <= [CppCompile] .cc
    */
        val pkg: ConfiguredTarget = getConfiguredTarget("//pkg:bin")

        val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
        val rootExecPath: String? = pkgArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(pkgArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")

        val indexAction: SpawnAction? =
            getPredecessorByInputName(
                backendAction,
                "pkg/bin.lto/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o.thinlto.bc"
            ) as SpawnAction?

        com.google.common.truth.Subject.contains("--i_come_from_standalone_lto_index=anltoindexopt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCopt() {
        createBuildFiles()

        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC)
        useConfiguration("--copt=acopt")

        /*
    We follow the chain from the final product backwards.

    binary <=[Link]=
    .lto-obj/...o <=[LTOBackend]=
    */
        val pkg: ConfiguredTarget = getConfiguredTarget("//pkg:bin")

        val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
        val rootExecPath: String? = pkgArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(pkgArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
        com.google.common.truth.Subject.contains("acopt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPerFileCopt() {
        createBuildFiles()
        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC)
        useConfiguration(
            "--per_file_copt=binfile\\.cc@copt1",
            "--per_file_copt=libfile\\.cc@copt2",
            "--per_file_copt=.*\\.cc,-binfile\\.cc@copt2"
        )

        /*
    We follow the chain from the final product backwards.

    binary <=[Link]=
    .lto-obj/...o <=[LTOBackend]=
    */
        val pkg: ConfiguredTarget = getConfiguredTarget("//pkg:bin")
        val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
        val rootExecPath: String? = pkgArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(pkgArtifact)

        var backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o"
            ) as LtoBackendAction?
        com.google.common.truth.Subject.contains("copt1")
        assertThat(backendAction.getArguments()).doesNotContain("copt2")

        backendAction =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getArguments()).doesNotContain("copt1")
        com.google.common.truth.Subject.contains("copt2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLtoBackendOpt() {
        createBuildFiles()

        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC, MockCcSupport.USER_COMPILE_FLAGS)
        useConfiguration("--ltobackendopt=anltobackendopt")

        /*
    We follow the chain from the final product backwards.

    binary <=[Link]=
    .lto-obj/...o <=[LTOBackend]=
    */
        val pkg: ConfiguredTarget = getConfiguredTarget("//pkg:bin")

        val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
        val rootExecPath: String? = pkgArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(pkgArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
        assertThat(backendAction.getArguments())
            .containsAtLeast("--default-compile-flag", "anltobackendopt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPerFileLtoBackendOpt() {
        createBuildFiles()

        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC)
        useConfiguration(
            "--per_file_ltobackendopt=binfile\\.pic\\.o@ltobackendopt1",
            "--per_file_ltobackendopt=.*\\.o,-binfile\\.pic\\.o@ltobackendopt2"
        )

        /*
    We follow the chain from the final product backwards.

    binary <=[Link]=
    .lto-obj/...o <=[LTOBackend]=
    */
        val pkg: ConfiguredTarget = getConfiguredTarget("//pkg:bin")
        val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
        val rootExecPath: String? = pkgArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(pkgArtifact)

        var backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o"
            ) as LtoBackendAction?
        com.google.common.truth.Subject.contains("ltobackendopt1")
        assertThat(backendAction.getArguments()).doesNotContain("ltobackendopt2")

        backendAction =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getArguments()).doesNotContain("ltobackendopt1")
        com.google.common.truth.Subject.contains("ltobackendopt2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoUseLtoIndexingBitcodeFile() {
        createBuildFiles()

        setupThinLTOCrosstool(
            CppRuleClasses.NO_USE_LTO_INDEXING_BITCODE_FILE, CppRuleClasses.SUPPORTS_PIC
        )
        useConfiguration(
            "--features=no_use_lto_indexing_bitcode_file",
            "--features=use_lto_native_object_directory"
        )
        val rootExecPath = this.rootExecPath

        /*
    We follow the chain from the final product backwards.

    binary <=[Link]=
    .lto-obj/...o <=[LTOBackend]=
    {.o.thinlto.bc,.o.imports} <=[LTOIndexing]=
    .o <= [CppCompile] .cc
    */
        val indexAction: SpawnAction =
            getIndexAction(
                getBackendAction("pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o")
            )

        assertThat(indexAction.getArguments()).doesNotContain("object_suffix_replace")

        Truth.assertThat(artifactsToStrings(indexAction.getInputs()))
            .containsAtLeast("bin pkg/_objs/bin/binfile.pic.o", "bin pkg/_objs/lib/libfile.pic.o")

        val bitcodeAction: CppCompileAction? =
            getPredecessorByInputName(indexAction, "pkg/_objs/bin/binfile.pic.o") as CppCompileAction?
        assertThat(bitcodeAction.getArguments()).doesNotContain("lto_indexing_bitcode=")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAutoFdo() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        package(features = [
            "thin_lto",
            "use_lto_native_object_directory",
        ])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.afdo", "")

        setupThinLTOCrosstool(CppRuleClasses.AUTOFDO)
        useConfiguration("--fdo_optimize=/pkg/profile.afdo", "--compilation_mode=opt")

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?

        // Checks that -fauto-profile is added to the LtoBackendAction.
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments())).containsMatch(
            "-fauto-profile=[^ ]*/profile.afdo"
        )
        com.google.common.truth.Subject.contains(
            "profile.afdo"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun setupThinLTOCrosstool(vararg extraFeatures: String?) {
        val allFeatures: Array<String?> =
            java.util.stream.Stream.concat<String>(
                java.util.stream.Stream.of<String>(
                    CppRuleClasses.THIN_LTO,
                    CppRuleClasses.USE_LTO_NATIVE_OBJECT_DIRECTORY,
                    CppRuleClasses.SUPPORTS_START_END_LIB,
                    MockCcSupport.HOST_AND_NONHOST_CONFIGURATION_FEATURES
                ),
                java.util.Arrays.stream<String?>(extraFeatures)
            )
                .toArray<String?>(java.util.function.IntFunction { _Dummy_.__Array__() })
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures(allFeatures)
            )
    }

    @Throws(java.lang.Exception::class)
    private fun setupAutoFdoThinLtoCrosstool() {
        setupThinLTOCrosstool(
            CppRuleClasses.AUTOFDO,
            CppRuleClasses.ENABLE_AFDO_THINLTO,
            CppRuleClasses.AUTOFDO_IMPLICIT_THINLTO
        )
    }

    /**
     * Tests that ThinLTO is not enabled for AFDO with LLVM without
     * --features=autofdo_implicit_thinlto.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAutoFdoNoImplicitThinLto() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.afdo", "")

        setupAutoFdoThinLtoCrosstool()
        useConfiguration("--fdo_optimize=/pkg/profile.afdo", "--compilation_mode=opt")

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // We should not have a ThinLTO backend action
        assertThat(backendAction).isNull()
    }

    /** Tests that --features=autofdo_implicit_thinlto enables ThinLTO for AFDO with LLVM.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAutoFdoImplicitThinLto() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.afdo", "")

        setupAutoFdoThinLtoCrosstool()
        useConfiguration(
            "--fdo_optimize=/pkg/profile.afdo",
            "--compilation_mode=opt",
            "--features=autofdo_implicit_thinlto",
            "--features=use_lto_native_object_directory"
        )

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // For ThinLTO compilation we should have a non-null backend action
        assertThat(backendAction).isNotNull()
    }

    /**
     * Tests that --features=-thin_lto overrides --features=autofdo_implicit_thinlto and prevents
     * enabling ThinLTO for AFDO with LLVM.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAutoFdoImplicitThinLtoDisabledOption() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.afdo", "")

        setupAutoFdoThinLtoCrosstool()
        useConfiguration(
            "--fdo_optimize=/pkg/profile.afdo",
            "--compilation_mode=opt",
            "--features=autofdo_implicit_thinlto",
            "--features=-thin_lto",
            "--features=use_lto_native_object_directory"
        )

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // We should not have a ThinLTO backend action
        assertThat(backendAction).isNull()
    }

    /**
     * Tests that features=[-thin_lto] in the build rule overrides --features=autofdo_implicit_thinlto
     * and prevents enabling ThinLTO for AFDO with LLVM.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAutoFdoImplicitThinLtoDisabledRule() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            features = [
                "-thin_lto",
                "use_lto_native_object_directory",
            ],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.afdo", "")

        setupAutoFdoThinLtoCrosstool()
        useConfiguration(
            "--fdo_optimize=/pkg/profile.afdo",
            "--compilation_mode=opt",
            "--features=autofdo_implicit_thinlto"
        )

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // We should not have a ThinLTO backend action
        assertThat(backendAction).isNull()
    }

    /**
     * Tests that features=[-thin_lto] in the package overrides --features=autofdo_implicit_thinlto
     * and prevents enabling ThinLTO for AFDO with LLVM.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAutoFdoImplicitThinLtoDisabledPackage() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        package(features = [
            "-thin_lto",
            "use_lto_native_object_directory",
        ])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.afdo", "")

        setupAutoFdoThinLtoCrosstool()
        useConfiguration(
            "--fdo_optimize=/pkg/profile.afdo",
            "--compilation_mode=opt",
            "--features=autofdo_implicit_thinlto"
        )

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // We should not have a ThinLTO backend action
        assertThat(backendAction).isNull()
    }

    @Throws(java.lang.Exception::class)
    private fun setupFdoThinLtoCrosstool() {
        setupThinLTOCrosstool(
            CppRuleClasses.FDO_OPTIMIZE,
            CppRuleClasses.ENABLE_FDO_THINLTO,
            MockCcSupport.FDO_IMPLICIT_THINLTO
        )
    }

    /**
     * Tests that ThinLTO is not enabled for FDO with LLVM without --features=fdo_implicit_thinlto.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFdoNoImplicitThinLto() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.zip", "")

        setupFdoThinLtoCrosstool()
        useConfiguration("--fdo_optimize=/pkg/profile.zip", "--compilation_mode=opt")

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // We should not have a ThinLTO backend action
        assertThat(backendAction).isNull()
    }

    /** Tests that --features=fdo_implicit_thinlto enables ThinLTO for FDO with LLVM.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFdoImplicitThinLto() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.zip", "")

        setupFdoThinLtoCrosstool()
        useConfiguration(
            "--fdo_optimize=/pkg/profile.zip",
            "--compilation_mode=opt",
            "--features=fdo_implicit_thinlto",
            "--features=use_lto_native_object_directory"
        )

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // For ThinLTO compilation we should have a non-null backend action
        assertThat(backendAction).isNotNull()
    }

    /**
     * Tests that --features=-thin_lto overrides --features=fdo_implicit_thinlto and prevents enabling
     * ThinLTO for FDO with LLVM.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFdoImplicitThinLtoDisabledOption() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.zip", "")

        setupFdoThinLtoCrosstool()
        useConfiguration(
            "--fdo_optimize=/pkg/profile.zip",
            "--compilation_mode=opt",
            "--features=fdo_implicit_thinlto",
            "--features=-thin_lto",
            "--features=use_lto_native_object_directory"
        )

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // We should not have a ThinLTO backend action
        assertThat(backendAction).isNull()
    }

    /**
     * Tests that features=[-thin_lto] in the build rule overrides --features=fdo_implicit_thinlto and
     * prevents enabling ThinLTO for FDO with LLVM.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFdoImplicitThinLtoDisabledRule() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            features = [
                "-thin_lto",
                "use_lto_native_object_directory",
            ],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.zip", "")

        setupFdoThinLtoCrosstool()
        useConfiguration(
            "--fdo_optimize=/pkg/profile.zip",
            "--compilation_mode=opt",
            "--features=fdo_implicit_thinlto"
        )

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // We should not have a ThinLTO backend action
        assertThat(backendAction).isNull()
    }

    /**
     * Tests that features=[-thin_lto] in the package overrides --features=fdo_implicit_thinlto and
     * prevents enabling ThinLTO for FDO with LLVM.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFdoImplicitThinLtoDisabledPackage() {
        setupThinLTOCrosstool()
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        package(features = [
            "-thin_lto",
            "use_lto_native_object_directory",
        ])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.zip", "")

        setupFdoThinLtoCrosstool()
        useConfiguration(
            "--fdo_optimize=/pkg/profile.zip",
            "--compilation_mode=opt",
            "--features=fdo_implicit_thinlto"
        )

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // We should not have a ThinLTO backend action
        assertThat(backendAction).isNull()
    }

    @Throws(java.lang.Exception::class)
    private fun setupXBinaryFdoThinLtoCrosstool() {
        setupThinLTOCrosstool(
            CppRuleClasses.XBINARYFDO,
            CppRuleClasses.ENABLE_XFDO_THINLTO,
            MockCcSupport.XFDO_IMPLICIT_THINLTO
        )
    }

    /**
     * Tests that ThinLTO is not enabled for XFDO with LLVM without
     * --features=xbinaryfdo_implicit_thinlto.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testXBinaryFdoNoImplicitThinLto() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc/toolchains:fdo_profile.bzl", "fdo_profile")
        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
        )

        fdo_profile(
            name = "out.xfdo",
            profile = "profiles.xfdo",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")

        setupXBinaryFdoThinLtoCrosstool()
        useConfiguration("--xbinary_fdo=//pkg:out.xfdo", "--compilation_mode=opt")

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // We should not have a ThinLTO backend action
        assertThat(backendAction).isNull()
    }

    /** Tests that --features=xbinaryfdo_implicit_thinlto enables ThinLTO for XFDO with LLVM.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testXBinaryFdoImplicitThinLto() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc/toolchains:fdo_profile.bzl", "fdo_profile")
        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
        )

        fdo_profile(
            name = "out.xfdo",
            profile = "profiles.xfdo",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")

        setupXBinaryFdoThinLtoCrosstool()
        useConfiguration(
            "--xbinary_fdo=//pkg:out.xfdo",
            "--compilation_mode=opt",
            "--features=xbinaryfdo_implicit_thinlto",
            "--features=use_lto_native_object_directory"
        )

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // For ThinLTO compilation we should have a non-null backend action
        assertThat(backendAction).isNotNull()
    }

    /**
     * Tests that --features=-thin_lto overrides --features=xbinaryfdo_implicit_thinlto and prevents
     * enabling ThinLTO for XFDO with LLVM.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testXBinaryFdoImplicitThinLtoDisabledOption() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc/toolchains:fdo_profile.bzl", "fdo_profile")
        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
        )

        fdo_profile(
            name = "out.xfdo",
            profile = "profiles.xfdo",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")

        setupXBinaryFdoThinLtoCrosstool()
        useConfiguration(
            "--xbinary_fdo=//pkg:out.xfdo",
            "--compilation_mode=opt",
            "--features=xbinaryfdo_implicit_thinlto",
            "--features=-thin_lto",
            "--features=use_lto_native_object_directory"
        )

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // We should not have a ThinLTO backend action
        assertThat(backendAction).isNull()
    }

    /**
     * Tests that features=[-thin_lto] in the build rule overrides
     * --features=xbinaryfdo_implicit_thinlto and prevents enabling ThinLTO for XFDO with LLVM.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testXBinaryFdoImplicitThinLtoDisabledRule() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc/toolchains:fdo_profile.bzl", "fdo_profile")
        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            features = [
                "-thin_lto",
                "use_lto_native_object_directory",
            ],
        )

        fdo_profile(
            name = "out.xfdo",
            profile = "profiles.xfdo",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")

        setupXBinaryFdoThinLtoCrosstool()
        useConfiguration(
            "--xbinary_fdo=//pkg:out.xfdo",
            "--compilation_mode=opt",
            "--features=xbinaryfdo_implicit_thinlto"
        )

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // We should not have a ThinLTO backend action
        assertThat(backendAction).isNull()
    }

    /**
     * Tests that features=[-thin_lto] in the package overrides --features=fdo_implicit_thinlto and
     * prevents enabling ThinLTO for XFDO with LLVM.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testXBinaryFdoImplicitThinLtoDisabledPackage() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc/toolchains:fdo_profile.bzl", "fdo_profile")
        package(features = [
            "-thin_lto",
            "use_lto_native_object_directory",
        ])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
        )

        fdo_profile(
            name = "out.xfdo",
            profile = "profiles.xfdo",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")

        setupXBinaryFdoThinLtoCrosstool()
        useConfiguration(
            "--xbinary_fdo=//pkg:out.xfdo",
            "--compilation_mode=opt",
            "--features=xbinaryfdo_implicit_thinlto"
        )

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        // We should not have a ThinLTO backend action
        assertThat(backendAction).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testXBinaryFdo() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc/toolchains:fdo_profile.bzl", "fdo_profile")
        package(features = [
            "thin_lto",
            "use_lto_native_object_directory",
        ])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )

        fdo_profile(
            name = "out.xfdo",
            profile = "profiles.xfdo",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")

        setupThinLTOCrosstool(CppRuleClasses.XBINARYFDO)
        useConfiguration("--xbinary_fdo=//pkg:out.xfdo", "--compilation_mode=opt")

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?

        // Checks that -fauto-profile is added to the LtoBackendAction.
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch("-fauto-profile=[^ ]*/profiles.xfdo")
        com.google.common.truth.Subject.contains("profiles.xfdo")
    }

    /**
     * Tests that ThinLTO is not enabled for XBINARYFDO with --features=autofdo_implicit_thinlto and
     * --features=fdo_implicit_thinlto.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testXBinaryFdoNoAutoFdoOrFdoImplicitThinLto() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc/toolchains:fdo_profile.bzl", "fdo_profile")
        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )

        fdo_profile(
            name = "out.xfdo",
            profile = "profiles.xfdo",
        )
        
        """.trimIndent()
        )

        scratch.file("pkg/binfile.cc", "int main() {}")

        setupThinLTOCrosstool(
            CppRuleClasses.ENABLE_FDO_THINLTO,
            MockCcSupport.FDO_IMPLICIT_THINLTO,
            CppRuleClasses.ENABLE_AFDO_THINLTO,
            MockCcSupport.AUTOFDO_IMPLICIT_THINLTO,
            CppRuleClasses.XBINARYFDO
        )
        useConfiguration(
            "--xbinary_fdo=//pkg:out.xfdo",
            "--compilation_mode=opt",
            "--features=autofdo_implicit_thinlto",
            "--features=fdo_implicit_thinlto",
            "--features=use_lto_native_object_directory"
        )

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/pkg/binfile.o"
            ) as LtoBackendAction?
        // We should not have a ThinLTO backend action
        assertThat(backendAction).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPICBackendOrder() {
        createBuildFiles()

        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC)
        useConfiguration("--copt=-fno-PIE")
        val rootExecPath = this.rootExecPath
        val backendAction: LtoBackendAction =
            getBackendAction("pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.pic.o")
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
        assertThat(backendAction.getArguments()).containsAtLeast("-fno-PIE", "-fPIC").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPropellerOptimizeAbsoluteOptions() {
        createBuildFiles()

        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.AUTOFDO)

        useConfiguration(
            "--propeller_optimize_absolute_cc_profile=/tmp/cc_profile.txt",
            "--propeller_optimize_absolute_ld_profile=/tmp/ld_profile.txt",
            "--compilation_mode=opt"
        )
        val binArtifact: Artifact = getFilesToBuild(this.currentTarget).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)
        com.google.common.truth.Subject.contains("ld_profile.txt")

        val commandLine: MutableList<String?> = linkAction.getArguments()
        Truth.assertThat(commandLine.toString())
            .containsMatch("-Wl,--symbol-ordering-file=.*/ld_profile.txt")

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?

        val expectedCompilerFlag = "-fbasic-block-sections=list=.*/cc_profile.txt"
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch(expectedCompilerFlag)
        val expectedBuildTypeFlag = "-DBUILD_PROPELLER_ENABLED=1"
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch(expectedBuildTypeFlag)
        com.google.common.truth.Subject.contains("cc_profile.txt")

        val indexAction: SpawnAction = getIndexAction(backendAction)
        assertThat(ActionsTestUtil.baseArtifactNames(indexAction.getInputs()))
            .doesNotContain("ld_profile.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPropellerCcCompile() {
        createBuildFiles()

        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.AUTOFDO)

        useConfiguration(
            "--propeller_optimize_absolute_cc_profile=/tmp/cc_profile.txt",
            "--propeller_optimize_absolute_ld_profile=/tmp/ld_profile.txt",
            "--compilation_mode=opt"
        )
        val binArtifact: Artifact = getFilesToBuild(this.currentTarget).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        val indexAction: SpawnAction = getIndexAction(backendAction)
        val bitcodeAction: CppCompileAction? =
            getPredecessorByInputName(indexAction, "pkg/_objs/bin/binfile.indexing.o") as CppCompileAction?
        assertThat(ActionsTestUtil.baseArtifactNames(bitcodeAction.getInputs()))
            .doesNotContain("cc_profile.txt")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(bitcodeAction.getArguments()))
            .doesNotContainMatch("-fbasic-block-sections=")
    }

    /**
     * Check that the temporary opt-out from disabling Propeller profiles for ThinLTO compile actions
     * works.
     * 
     * 
     * TODO(b/182804945): Remove after making sure that the rollout of the new Propeller profile
     * passing logic didn't break anything.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPropellerCcCompileWithPropellerOptimizeThinLtoCompileActions() {
        createBuildFiles()

        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.AUTOFDO)

        useConfiguration(
            "--propeller_optimize_absolute_cc_profile=/tmp/cc_profile.txt",
            "--propeller_optimize_absolute_ld_profile=/tmp/ld_profile.txt",
            "--compilation_mode=opt",
            "--features=propeller_optimize_thinlto_compile_actions",
            "--features=use_lto_native_object_directory"
        )
        val binArtifact: Artifact = getFilesToBuild(this.currentTarget).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        val indexAction: SpawnAction = getIndexAction(backendAction)
        Truth.assertThat(artifactsToStrings(indexAction.getInputs()))
            .containsAtLeast(
                "bin pkg/_objs/bin/binfile.indexing.o", "bin pkg/_objs/lib/libfile.indexing.o"
            )

        val bitcodeAction: CppCompileAction? =
            getPredecessorByInputName(indexAction, "pkg/_objs/bin/binfile.indexing.o") as CppCompileAction?
        com.google.common.truth.Subject.contains("cc_profile.txt")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(bitcodeAction.getArguments()))
            .containsMatch("-fbasic-block-sections=list=.*/cc_profile.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPropellerHostBuilds() {
        scratch.file(
            "pkg/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "package(features = ['thin_lto', 'use_lto_native_object_directory'])",
            "",
            "cc_binary(name = '" + targetName + "',",
            "          srcs = ['binfile.cc', ],",
            "          deps = [ ':lib' ], ",
            "          malloc = '//base:system_malloc')",
            "cc_library(name = 'lib',",
            "        srcs = ['libfile.cc'],",
            "        hdrs = ['libfile.h'])",
            "cc_binary(name = 'gen_lib',",
            "        srcs = ['gen_lib.cc'])",
            "genrule(name = 'lib_genrule',",
            "        srcs = [],",
            "        outs = ['libfile.cc'],",
            "        cmd = '$(location gen_lib) > \"$@\"',",
            "        tools = [':gen_lib'])"
        )

        scratch.file("pkg/binfile.cc", "#include \"pkg/libfile.h\"", "int main() { return pkg(); }")
        scratch.file(
            "pkg/gen_lib.cc",
            "#include <cstdio>",
            "int main() { puts(\"int pkg() { return 42; }\"); }"
        )
        scratch.file("pkg/libfile.h", "int pkg();")

        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.AUTOFDO)

        useConfiguration(
            "--propeller_optimize_absolute_cc_profile=/tmp/cc_profile.txt",
            "--propeller_optimize_absolute_ld_profile=/tmp/ld_profile.txt",
            "--compilation_mode=opt"
        )
        val binArtifact: Artifact = getFilesToBuild(this.currentTarget).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()
        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?
        val indexAction: SpawnAction = getIndexAction(backendAction)
        Truth.assertThat(artifactsToStrings(indexAction.getInputs()))
            .contains("bin pkg/_objs/lib/libfile.indexing.o")

        val bitcodeAction: CppCompileAction? =
            getPredecessorByInputName(indexAction, "pkg/_objs/lib/libfile.indexing.o") as CppCompileAction?

        val genruleAction: Action? = getPredecessorByInputName(bitcodeAction, "pkg/libfile.cc")

        val hostLinkAction: SpawnAction? =
            getPredecessorByInputName(genruleAction, "pkg/gen_lib") as SpawnAction?
        assertThat(ActionsTestUtil.baseArtifactNames(hostLinkAction.getInputs()))
            .doesNotContain("ld_profile.txt")
        assertThat(hostLinkAction.getArguments().toString())
            .doesNotContainMatch("-Wl,--symbol-ordering-file=.*/ld_profile.txt")

        // The hostLinkAction inputs has a different root from the backendAction.
        // Here we confirm that the correct root is on the path
        val hostrootExecPath: String? = hostLinkAction.getPrimaryOutput().getRoot().getExecPathString()
        val hostBackendAction: LtoBackendAction? =
            getPredecessorByInputName(
                hostLinkAction,
                "pkg/gen_lib.lto-obj/" + hostrootExecPath + "/pkg/_objs/gen_lib/gen_lib.o"
            ) as LtoBackendAction?
        assertThat(ActionsTestUtil.baseArtifactNames(hostBackendAction.getInputs()))
            .doesNotContain("cc_profile.txt")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(hostBackendAction.getArguments()))
            .doesNotContainMatch("-fbasic-block-sections")

        val hostIndexAction: SpawnAction = getIndexAction(hostBackendAction)
        assertThat(hostIndexAction).isNotNull()
        assertThat(ActionsTestUtil.baseArtifactNames(hostIndexAction.getInputs()))
            .doesNotContain("ld_profile.txt")
        assertThat(hostIndexAction.getArguments().toString())
            .doesNotContainMatch("-Wl,--symbol-ordering-file=.*/ld_profile.txt")

        val hostBitcodeAction: CppCompileAction? =
            getPredecessorByInputName(hostIndexAction, "pkg/_objs/gen_lib/gen_lib.indexing.o") as CppCompileAction?
        assertThat(ActionsTestUtil.baseArtifactNames(hostBitcodeAction.getInputs()))
            .doesNotContain("cc_profile.txt")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(hostBitcodeAction.getArguments()))
            .doesNotContainMatch("-fbasic-block-sections=")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPropellerOptimizeOptionFromLabel() {
        createBuildFiles()
        scratch.file(
            "fdo/BUILD",
            "load('@rules_cc//cc/toolchains:propeller_optimize.bzl',"
                    + " 'propeller_optimize')",
            "propeller_optimize(name='test_propeller_optimize', cc_profile=':cc_profile.txt',"
                    + " ld_profile=':ld_profile.txt')"
        )
        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.AUTOFDO)
        useConfiguration(
            "--propeller_optimize=//fdo:test_propeller_optimize", "--compilation_mode=opt"
        )

        val binArtifact: Artifact = getFilesToBuild(this.currentTarget).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()

        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val commandLine: MutableList<String?> = linkAction.getArguments()
        Truth.assertThat(commandLine.toString())
            .containsMatch("-Wl,--symbol-ordering-file=.*/ld_profile.txt")

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?

        val expectedCompilerFlag = "-fbasic-block-sections=list=.*/cc_profile.txt"
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch(expectedCompilerFlag)
        val expectedBuildTypeFlag = "-DBUILD_PROPELLER_ENABLED=1"
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch(expectedBuildTypeFlag)
        com.google.common.truth.Subject.contains("cc_profile.txt")
    }

    @Throws(java.lang.Exception::class)
    private fun testLLVMCachePrefetchBackendOption(extraOption: String?) {
        createBuildFiles()
        scratch.file(
            "fdo/BUILD",
            "load('@rules_cc//cc/toolchains:fdo_prefetch_hints.bzl',"
                    + " 'fdo_prefetch_hints')",
            "fdo_prefetch_hints(name='test_profile', profile=':prefetch.afdo')"
        )

        setupThinLTOCrosstool(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.AUTOFDO)
        useConfiguration(
            "--fdo_prefetch_hints=//fdo:test_profile", "--compilation_mode=opt", extraOption
        )

        val rootExecPath = this.rootExecPath
        val backendAction: LtoBackendAction =
            getBackendAction("pkg/bin.lto-obj/" + rootExecPath + "/pkg/_objs/bin/binfile.o")

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch("-mllvm -prefetch-hints-file=.*/prefetch.afdo")

        com.google.common.truth.Subject.contains("prefetch.afdo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFdoCachePrefetchLLVMOptionsToBackendFromLabel() {
        testLLVMCachePrefetchBackendOption("")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFdoCachePrefetchAndFdoLLVMOptionsToBackendFromLabel() {
        testLLVMCachePrefetchBackendOption("--fdo_optimize=/profile.zip")
    }

    companion object {
        /** Helper method to get the root prefix from the given dwpFile.  */
        @Throws(java.lang.Exception::class)
        private fun dwpRootPrefix(dwpFile: Artifact): PathFragment {
            return dwpFile
                .getExecPath()
                .subFragment(
                    0, dwpFile.getExecPath().segmentCount() - dwpFile.getRootRelativePath().segmentCount()
                )
        }
    }
}
