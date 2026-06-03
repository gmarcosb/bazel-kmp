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
import com.google.common.base.Predicate
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.truth.Subject
import com.google.devtools.build.lib.rules.apple.DottedVersion
import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Sequence

/**
 * Superclass for all Obj-C rule tests.
 * 
 * 
 * TODO(matvore): split this up into more helper classes, especially the check... methods, which
 * are many and not shared by all objc_ rules.
 * 
 * 
 * TODO(matvore): find a more concise way to repeat common tests (in particular, those which
 * simply call a check... method) across several rule types.
 */
abstract class ObjcRuleTestCase : BuildViewTestCase() {
    @Before
    @Throws(Exception::class)
    fun setUp() {
        setBuildLanguageOptions("--noincompatible_disable_objc_library_transition")
    }

    protected fun execPathEndingWith(artifacts: Iterable<Artifact?>?, suffix: String?): String {
        return getFirstArtifactEndingWith(artifacts, suffix).getExecPathString()
    }

    @Throws(IOException::class)
    override fun initializeMockClient() {
        super.initializeMockClient()
        MockObjcSupport.setup(mockToolsConfig)
    }

    /** Creates an `objc_library` target writer for the label indicated by the given String.  */
    protected open fun createLibraryTargetWriter(labelString: String?): ScratchAttributeWriter {
        return ScratchAttributeWriter.fromLabelString(
            this,
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            "objc_library",
            labelString
        )
    }

    /** Override this to trigger platform-based Apple toolchain resolution.  */
    protected open fun platformBasedToolchains(): Boolean {
        return false
    }

    @Throws(Exception::class)
    override fun useConfiguration(vararg args: String?) {
        val newArgs: ImmutableList<String?>?
        if (platformBasedToolchains()) {
            newArgs = MockObjcSupport.requiredObjcPlatformFlags(*args)
        } else {
            newArgs = MockObjcSupport.requiredObjcCrosstoolFlags(*args)
        }
        super.useConfiguration(*newArgs!!.toArray<String?>(arrayOf<String?>()))
    }

    @Throws(Exception::class)
    protected fun useConfigurationWithCustomXcode(vararg args: String?) {
        val newArgs: ImmutableList<String?>?
        if (platformBasedToolchains()) {
            newArgs = MockObjcSupport.requiredObjcPlatformFlagsNoXcodeConfig(*args)
        } else {
            newArgs = MockObjcSupport.requiredObjcCrosstoolFlagsNoXcodeConfig(*args)
        }
        super.useConfiguration(*newArgs!!.toArray<String?>(arrayOf<String?>()))
    }

    /** Asserts that an action specifies the given requirement.  */
    protected fun assertHasRequirement(action: Action, executionRequirement: String?) {
        assertThat(action.getExecutionInfo()).containsKey(executionRequirement)
    }

    /** Asserts that an action does not specify the given requirement.  */
    protected fun assertNotHasRequirement(action: Action, executionRequirement: String?) {
        assertThat(action.getExecutionInfo()).doesNotContainKey(executionRequirement)
    }

    /**
     * Verifies a `-filelist` file's contents.
     * 
     * @param originalAction the action which uses the filelist artifact
     * @param inputArchives path suffixes of the expected contents of the filelist
     */
    @Throws(Exception::class)
    protected fun verifyObjlist(originalAction: Action, vararg inputArchives: String?) {
        val execPaths = ImmutableList.builder<String?>()
        for (inputArchive in inputArchives) {
            execPaths.add(execPathEndingWith(originalAction.getInputs().toList(), inputArchive))
        }
        Truth.assertThat(paramFileArgsForAction(originalAction)).containsExactlyElementsIn(execPaths.build())
    }

    @Throws(ActionExecutionException::class)
    protected fun assertAppleSdkPlatformEnv(action: CommandAction, platformName: String?) {
        assertThat(action.getIncompleteEnvironmentForTesting())
            .containsEntry("APPLE_SDK_PLATFORM", platformName)
    }

    @Throws(ActionExecutionException::class)
    protected fun assertXcodeVersionEnv(action: CommandAction, versionNumber: String?) {
        assertThat(action.getIncompleteEnvironmentForTesting())
            .containsEntry("XCODE_VERSION_OVERRIDE", versionNumber)
    }

    @Throws(Exception::class)
    protected fun ccInfoForTarget(label: String?): CcInfo? {
        val ccInfo: CcInfo? = CcInfo.get(getConfiguredTarget(label))
        if (ccInfo != null) {
            return ccInfo
        }
        val executableProvider: StructImpl? =
            getConfiguredTarget(label).get(APPLE_EXECUTABLE_BINARY_PROVIDER_KEY) as StructImpl?
        if (executableProvider != null) {
            return executableProvider.getValue("cc_info", CcInfo::class.java)
        }
        return null
    }

    @Throws(Exception::class)
    protected fun archiveAction(label: String?): CommandAction {
        val target: ConfiguredTarget = getConfiguredTarget(label)
        return getGeneratingAction(getBinArtifact("lib" + target.getLabel().getName() + ".a", target)) as CommandAction
    }

    protected fun inputsEndingWith(action: Action, suffix: String?): Iterable<Artifact?> {
        return Iterables.filter<T?>(
            action.getInputs().toList(), Predicate { artifact: T? -> artifact.getExecPathString().endsWith(suffix) })
    }

    /**
     * Asserts that the given action can specify execution requirements, and requires execution on
     * darwin.
     */
    protected fun assertRequiresDarwin(action: Action) {
        assertHasRequirement(action, ExecutionRequirements.REQUIRES_DARWIN)
    }

    @Throws(Exception::class)
    protected fun addBinWithTransitiveDepOnFrameworkImport(): ConfiguredTarget {
        val lib: ConfiguredTarget = addLibWithDepOnFrameworkImport()
        scratch.file(
            "bin/BUILD",
            "load('//test_starlark:apple_binary_starlark.bzl', 'apple_binary_starlark')",
            "apple_binary_starlark(",
            "    name = 'bin',",
            "    platform_type = 'ios',",
            "    deps = ['" + lib.getLabel().toString() + "'],",
            ")"
        )
        return getConfiguredTarget("//bin:bin")
    }

    @Throws(Exception::class)
    private fun addLibWithDepOnFrameworkImport(): ConfiguredTarget {
        scratch.file(
            "fx/defs.bzl",
            """
        load("@rules_cc//cc/common:cc_info.bzl", "CcInfo")
        load("@rules_cc//cc/common:cc_common.bzl", "cc_common")
        def _custom_static_framework_import_impl(ctx):
            return [
                CcInfo(
                    compilation_context = cc_common.create_compilation_context(
                        framework_includes = depset(ctx.attr.framework_search_paths),
                    ),
                ),
            ]

        custom_static_framework_import = rule(
            _custom_static_framework_import_impl,
            attrs = {"framework_search_paths": attr.string_list()},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "fx/BUILD",
            """
        load(":defs.bzl", "custom_static_framework_import")

        custom_static_framework_import(
            name = "fx",
            framework_search_paths = ["fx"],
        )
        
        """.trimIndent()
        )
        return createLibraryTargetWriter("//lib:lib")
            .setAndCreateFiles("srcs", "a.m", "b.m", "private.h")
            .setList("deps", "//fx:fx")
            .write()
    }

    @Throws(Exception::class)
    protected fun compileAction(ownerLabel: String?, objFileName: String?): CommandAction {
        val archiveAction: Action = archiveAction(ownerLabel)
        return getGeneratingAction(
            getFirstArtifactEndingWith(archiveAction.getInputs(), "/" + objFileName)
        ) as CommandAction
    }

    /**
     * Verifies simply that some rule type creates the [CompilationArtifacts] object
     * successfully; in particular, makes sure it is not ignoring attributes. If the scope of [ ] expands, make sure this method tests it properly.
     * 
     * 
     * This test only makes sure the attributes are not being ignored - it does not test any other
     * functionality in depth, which is covered by other unit tests.
     */
    @Throws(Exception::class)
    protected fun checkPopulatesCompilationArtifacts(ruleType: RuleType) {
        scratch.file("x/a.m")
        scratch.file("x/b.m")
        scratch.file("x/c.pch")
        ruleType.scratchTarget(scratch, "srcs", "['a.m']", "non_arc_srcs", "['b.m']", "pch", "'c.pch'")
        val includeFlags = ImmutableList.of<String?>("-include", "x/c.pch")
        assertContainsSublist(compileAction("//x:x", "a.o").getArguments(), includeFlags)
        assertContainsSublist(compileAction("//x:x", "b.o").getArguments(), includeFlags)
    }

    @Throws(Exception::class)
    protected fun checkProvidesHdrsAndIncludes(ruleType: RuleType, privateHdr: Optional<String?>) {
        scratch.file("x/a.h")
        ruleType.scratchTarget(scratch, "hdrs", "['a.h']", "includes", "['incdir']")
        val ccCompilationContext: CcCompilationContext =
            CcInfo.get(getConfiguredTarget("//x:x")).getCcCompilationContext()
        val declaredIncludeSrcs: ImmutableList<String?>? =
            ccCompilationContext.getDeclaredIncludeSrcs().toList().stream()
                .map({ x -> removeConfigFragment(x.getExecPathString()) })
                .collect(ImmutableList.toImmutableList<E?>())
        if (privateHdr.isPresent()) {
            Truth.assertThat(declaredIncludeSrcs)
                .containsExactly(
                    getSourceArtifact("x/a.h").getExecPathString(),
                    getSourceArtifact(privateHdr.get()).getExecPathString()
                )
        } else {
            Truth.assertThat(declaredIncludeSrcs)
                .containsExactly(getSourceArtifact("x/a.h").getExecPathString())
        }
        assertThat(
            ccCompilationContext.getIncludeDirs().stream()
                .map({ x -> removeConfigFragment(x.toString()) })
        )
            .containsExactly(PathFragment.create("x/incdir").toString(), OUTPUTDIR + "/x/incdir")
    }

    @Throws(Exception::class)
    protected fun checkCompilesWithHdrs(ruleType: RuleType) {
        scratch.file("x/a.m")
        scratch.file("x/a.h")
        ruleType.scratchTarget(scratch, "srcs", "['a.m']", "hdrs", "['a.h']")
        Subject.contains(getSourceArtifact("x/a.h"))
    }

    @Throws(Exception::class)
    protected fun lipoBinAction(binLabel: String?): Action? {
        return actionProducingArtifact(binLabel, "_lipobin")
    }

    @Throws(Exception::class)
    protected fun linkAction(binLabel: String?): CommandAction? {
        var linkAction: CommandAction? = actionProducingArtifact(binLabel, "_bin") as CommandAction?
        if (linkAction == null) {
            // For multi-architecture rules, the link action is not in the target configuration, but
            // across a configuration transition.
            val lipoAction: Action? = lipoBinAction(binLabel)
            if (lipoAction != null) {
                val binArtifact: Artifact = getFirstArtifactEndingWith(lipoAction.getInputs(), "_bin")
                linkAction = getGeneratingAction(binArtifact) as CommandAction
            }
        }
        return linkAction
    }

    @Throws(Exception::class)
    protected fun actionProducingArtifact(targetLabel: String?, artifactSuffix: String?): Action? {
        val libraryTarget: ConfiguredTarget = getConfiguredTarget(targetLabel)
        val parsedLabel: Label = Label.parseCanonical(targetLabel)
        val linkedLibrary: Artifact = getBinArtifact(parsedLabel.name + artifactSuffix, libraryTarget)
        return getGeneratingAction(linkedLibrary)
    }

    protected fun rootedIncludePaths(vararg unrootedPaths: String): MutableList<String?> {
        val rootedPaths = ImmutableList.Builder<String?>()
        for (unrootedPath in unrootedPaths) {
            rootedPaths
                .add(unrootedPath)
                .add(
                    removeConfigFragment(
                        PathFragment.create(TestConstants.PRODUCT_NAME + "-out/any-config-fragment/bin")
                            .getRelative(unrootedPath)
                            .getSafePathString()
                    )
                )
        }
        return rootedPaths.build()
    }

    @Throws(Exception::class)
    protected fun checkClangCoptsForCompilationMode(ruleType: RuleType, mode: CompilationMode) {
        val allExpectedCoptsBuilder =
            ImmutableList.builder<String?>().addAll(CompilationSupport.DEFAULT_COMPILER_FLAGS)

        useConfiguration(
            "--platforms=" + MockObjcSupport.IOS_X86_64,
            "--apple_platform_type=ios",
            "--compilation_mode=" + compilationModeFlag(mode)
        )

        scratch.file("x/a.m")
        ruleType.scratchTarget(scratch, "srcs", "['a.m']")

        val compileActionA: CommandAction = compileAction("//x:x", "a.o")

        assertThat(compileActionA.getArguments())
            .containsAtLeastElementsIn(allExpectedCoptsBuilder.build())
    }

    @Throws(Exception::class)
    private fun addTransitiveDefinesUsage(topLevelRuleType: RuleType) {
        createLibraryTargetWriter("//lib1:lib1")
            .setAndCreateFiles("srcs", "a.m")
            .setList("defines", "A=foo", "B")
            .write()
        createLibraryTargetWriter("//lib2:lib2")
            .setAndCreateFiles("srcs", "a.m")
            .setList("deps", "//lib1:lib1")
            .setList("defines", "C=bar", "D")
            .write()

        topLevelRuleType.scratchTarget(
            scratch,
            "srcs",
            "['a.m']",
            "non_arc_srcs",
            "['b.m']",
            "deps",
            "['//lib2:lib2']",
            "defines",
            "['E=baz']",
            "copts",
            "['explicit_copt']"
        )
    }

    @Throws(Exception::class)
    protected fun checkReceivesTransitivelyPropagatedDefines(ruleType: RuleType) {
        addTransitiveDefinesUsage(ruleType)
        val expectedArgs: MutableList<String?> =
            ImmutableList.of<String?>("-DA=foo", "-DB", "-DC=bar", "-DD", "-DE=baz", "explicit_copt")
        val compileActionAArgs: MutableList<String?>? = compileAction("//x:x", "a.o").getArguments()
        val compileActionBArgs: MutableList<String?>? = compileAction("//x:x", "b.o").getArguments()
        for (expectedArg in expectedArgs) {
            Truth.assertThat(compileActionAArgs).contains(expectedArg)
            Truth.assertThat(compileActionBArgs).contains(expectedArg)
        }
    }

    @Throws(Exception::class)
    protected fun checkDefinesFromCcLibraryDep(ruleType: RuleType) {
        useConfiguration()
        ScratchAttributeWriter.fromLabelString(
            this,
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library",
            "//dep:lib"
        )
            .setList("srcs", "a.cc")
            .setList("defines", "foo", "bar")
            .write()

        ScratchAttributeWriter.fromLabelString(
            this,
            analysisMock
                .ccSupport()
                .getMacroLoadStatement( /* loadMacro= */true, ruleType.getRuleTypeName()),
            ruleType.getRuleTypeName(),
            "//objc:x"
        )
            .setList("srcs", "a.m")
            .setList("deps", "//dep:lib")
            .write()

        val compileAction: CommandAction = compileAction("//objc:x", "a.o")
        assertThat(compileAction.getArguments()).containsAtLeast("-Dfoo", "-Dbar")
    }

    @Throws(Exception::class)
    protected fun checkSdkIncludesUsedInCompileAction(ruleType: RuleType) {
        ruleType.scratchTarget(scratch, "sdk_includes", "['foo', 'bar/baz']", "srcs", "['a.m', 'b.m']")
        val sdkIncludeDir = "__BAZEL_XCODE_SDKROOT__/usr/include"
        // we remove spaces, since the legacy rules put a space after "-I" in include paths.
        val compileActionACommandLine: String? =
            Joiner.on(" ").join(compileAction("//x:x", "a.o").getArguments()).replace(" ", "")
        Truth.assertThat(compileActionACommandLine).contains("-I" + sdkIncludeDir + "/foo")
        Truth.assertThat(compileActionACommandLine).contains("-I" + sdkIncludeDir + "/bar/baz")

        val compileActionBCommandLine: String? =
            Joiner.on(" ").join(compileAction("//x:x", "b.o").getArguments()).replace(" ", "")
        Truth.assertThat(compileActionBCommandLine).contains("-I" + sdkIncludeDir + "/foo")
        Truth.assertThat(compileActionBCommandLine).contains("-I" + sdkIncludeDir + "/bar/baz")
    }

    @Throws(Exception::class)
    protected fun checkSdkIncludesUsedInCompileActionsOfDependers(ruleType: RuleType) {
        ruleType.scratchTarget(scratch, "sdk_includes", "['foo', 'bar/baz']")
        // Add some dependers (including transitive depender //bin:bin) and make sure they use the flags
        // as well.
        createLibraryTargetWriter("//lib:lib")
            .setAndCreateFiles("srcs", "a.m")
            .setList("deps", "//x:x")
            .setList("sdk_includes", "from_lib")
            .write()
        createLibraryTargetWriter("//bin:main_lib")
            .setAndCreateFiles("srcs", "b.m")
            .setList("deps", "//lib:lib")
            .setList("sdk_includes", "from_bin")
            .write()
        val sdkIncludeDir = "__BAZEL_XCODE_SDKROOT__/usr/include"

        // We remove spaces because the crosstool case does not use spaces for include paths.
        val compileAArgs: String? =
            Joiner.on("").join(compileAction("//lib:lib", "a.o").getArguments()).replace(" ", "")
        Truth.assertThat(compileAArgs).contains("-I" + sdkIncludeDir + "/from_lib")
        Truth.assertThat(compileAArgs).contains("-I" + sdkIncludeDir + "/foo")
        Truth.assertThat(compileAArgs).contains("-I" + sdkIncludeDir + "/bar/baz")

        val compileBArgs: String? =
            Joiner.on("").join(compileAction("//bin:main_lib", "b.o").getArguments()).replace(" ", "")
        Truth.assertThat(compileBArgs).contains("-I" + sdkIncludeDir + "/from_bin")
        Truth.assertThat(compileBArgs).contains("-I" + sdkIncludeDir + "/from_lib")
        Truth.assertThat(compileBArgs).contains("-I" + sdkIncludeDir + "/foo")
        Truth.assertThat(compileBArgs).contains("-I" + sdkIncludeDir + "/bar/baz")
    }

    @Throws(Exception::class)
    fun checkAllowVariousNonBlacklistedTypesInHeaders(ruleType: RuleType) {
        ruleType.scratchTarget(scratch, "hdrs", "['foo.foo', 'NoExtension', 'bar.inc', 'baz.hpp']")
        Truth.assertThat(view.hasErrors(getConfiguredTarget("//x:x"))).isFalse()
    }

    @Throws(Exception::class)
    fun checkFilesToCompileOutputGroup(ruleType: RuleType) {
        ruleType.scratchTarget(scratch)
        val target: ConfiguredTarget = getConfiguredTarget("//x:x")
        assertThat(
            ActionsTestUtil.baseNamesOf(getOutputGroup(target, OutputGroupInfo.FILES_TO_COMPILE))
        )
            .isEqualTo("a.o")
    }

    protected fun removeConfigFragment(text: String): String? {
        return text.replace("-out/.*/bin".toRegex(), "-out//bin").replace("-out/.*/gen".toRegex(), "-out//gen")
    }

    protected fun removeConfigFragment(text: MutableList<String?>): MutableList<String?> {
        return text.stream().map<String?> { text: String? -> this.removeConfigFragment(text!!) }
            .collect(ImmutableList.toImmutableList<String?>())
    }

    companion object {
        protected val DEFAULT_IOS_SDK_VERSION: DottedVersion? =
            DottedVersion.fromStringUnchecked(AppleCommandLineOptions.DEFAULT_IOS_SDK_VERSION)

        protected val OUTPUTDIR: String = TestConstants.PRODUCT_NAME + "-out//bin"

        private val APPLE_EXECUTABLE_BINARY_PROVIDER_KEY: Provider.Key = Key(
            keyForBuild(Label.parseCanonicalUnchecked("//test_starlark:apple_binary_starlark.bzl")),
            "AppleExecutableBinaryInfo"
        )

        private fun compilationModeFlag(mode: CompilationMode): String {
            when (mode) {
                DBG -> return "dbg"
                OPT -> return "opt"
                FASTBUILD -> return "fastbuild"
            }
            throw AssertionError()
        }

        protected fun legacyCompilationModeCopts(mode: CompilationMode): ImmutableList<String?> {
            when (mode) {
                DBG -> return ImmutableList.copyOf<String?>(ObjcConfiguration.DBG_COPTS)
                OPT -> return ObjcConfiguration.OPT_COPTS
                FASTBUILD -> throw AssertionError("FASTBUILD is not supported by legacyCompilationModeCopts().")
            }
            throw AssertionError()
        }

        @Throws(Exception::class)
        protected fun addAppleBinaryStarlarkRule(scratch: Scratch) {
            scratch.file(
                "test_starlark/BUILD",
                """
        load(":cc_toolchain_forwarder.bzl", "cc_toolchain_forwarder")
        cc_toolchain_forwarder(
            name = "default_cc_toolchain_forwarder",
        )
        
        """.trimIndent()
            )
            val toolsRepo: RepositoryName = TestConstants.TOOLS_REPOSITORY

            scratch.file(
                "test_starlark/apple_binary_starlark.bzl",
                ("load('//third_party/bazel_rules/rules_apple:apple_binary_starlark.bzl',"
                        + " _apple_binary_starlark = 'apple_binary_starlark', _ApplePlatformInfo ="
                        + " 'ApplePlatformInfo')"),
                "apple_binary_starlark = _apple_binary_starlark",
                "ApplePlatformInfo = _ApplePlatformInfo"
            )
            scratch.file("third_party/bazel_rules/rules_apple/BUILD")
            scratch.file(
                "third_party/bazel_rules/rules_apple/apple_binary_starlark.bzl",
                "load('@rules_cc//cc/common:cc_info.bzl', 'CcInfo')",
                "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
                "load('@rules_cc//cc/private/rules_impl:objc_compilation_support.bzl',"
                        + " 'compilation_support')",
                "load(\"@bazel_skylib//lib:paths.bzl\", \"paths\")",
                "_CPU_TO_PLATFORM = {",
                "    'darwin_x86_64': '" + MockObjcSupport.DARWIN_X86_64 + "',",
                "    'ios_x86_64': '" + MockObjcSupport.IOS_X86_64 + "',",
                "    'ios_arm64': '" + MockObjcSupport.IOS_ARM64 + "',",
                "    'ios_arm64e': '" + MockObjcSupport.IOS_ARM64E + "',",
                "    'ios_i386': '" + MockObjcSupport.IOS_I386 + "',",  // legacy platform used in tests
                "    'ios_armv7': '" + MockObjcSupport.IOS_ARMV7 + "',",  // legacy platform used in tests
                "    'watchos_armv7k': '" + MockObjcSupport.WATCHOS_ARMV7K + "',",
                "    'watchos_arm64_32': '" + MockObjcSupport.WATCHOS_ARM64_32 + "',",
                "}",
                "_apple_platform_transition_inputs = [",
                "    '//command_line_option:cpu',",
                "    '//command_line_option:ios_multi_cpus',",
                "    '//command_line_option:macos_cpus',",
                "    '//command_line_option:tvos_cpus',",
                "    '//command_line_option:watchos_cpus',",
                "]",
                "_apple_rule_base_transition_outputs = [",
                "    '//command_line_option:apple_platform_type',",
                "    '//command_line_option:apple_split_cpu',",
                "    '//command_line_option:compiler',",
                "    '//command_line_option:cpu',",
                "    '//command_line_option:fission',",
                "    '//command_line_option:grte_top',",
                "    '//command_line_option:platforms',",
                "]",
                "ApplePlatformInfo = provider(",
                "    fields = ['target_os', 'target_arch', 'target_environment', 'target_build_config'],)",
                "",
                "AppleDynamicFrameworkInfo = provider(",
                "    fields = ['framework_dirs', 'framework_files', 'binary', 'cc_info'],)",
                "",
                "AppleExecutableBinaryInfo = provider(",
                "    fields = ['binary', 'cc_info'],)",
                "",
                "AppleDebugOutputsInfo = provider(",
                "    fields = ['outputs_map'],)",
                "",
                "def _command_line_options(*, environment_arch = None, platform_type, settings):",
                "    cpu = ('darwin_' + environment_arch if platform_type == 'macos'",
                "            else platform_type + '_' +  environment_arch)",
                "    output_dictionary = {",
                "        '//command_line_option:apple_platform_type': platform_type,",
                "        '//command_line_option:apple_split_cpu': environment_arch,",
                "        '//command_line_option:compiler': None,",
                "        '//command_line_option:cpu': cpu,",
                "        '//command_line_option:fission': [],",
                "        '//command_line_option:grte_top': None,",
                "        '//command_line_option:platforms': [_CPU_TO_PLATFORM[cpu]],",
                "    }",
                "    return output_dictionary",
                "def _apple_platform_split_transition_impl(settings, attr):",
                "    output_dictionary = {}",
                "    platform_type = attr.platform_type",
                "    if platform_type == 'ios':",
                "       environment_archs = settings['//command_line_option:ios_multi_cpus']",
                "    else:",
                "       environment_archs = settings['//command_line_option:%s_cpus' % platform_type]",
                "    if not environment_archs and platform_type == 'ios':",
                "       cpu_value = settings['//command_line_option:cpu']",
                "       if cpu_value.startswith('ios_'):",
                "           environment_archs = [cpu_value[4:]]",
                "    if not environment_archs:",
                "        environment_archs = ['x86_64']",
                "    for environment_arch in environment_archs:",
                "        found_cpu = 'ios_{}'.format(environment_arch)",
                "        output_dictionary[found_cpu] = _command_line_options(",
                "            environment_arch = environment_arch,",
                "            platform_type = platform_type,",
                "            settings = settings,",
                "        )",
                "    return output_dictionary",
                "apple_platform_split_transition = transition(",
                "    implementation = _apple_platform_split_transition_impl,",
                "    inputs = _apple_platform_transition_inputs,",
                "    outputs = _apple_rule_base_transition_outputs,",
                ")",
                "def _get_libraries_for_linking(libraries_to_link):",
                "    libraries = []",
                "    for library_to_link in libraries_to_link:",
                "        libraries.append(_get_library_for_linking(library_to_link))",
                "    return libraries",
                "",
                "def _get_static_library_for_linking(library_to_link):",
                "    if library_to_link.static_library:",
                "        return library_to_link.static_library",
                "    elif library_to_link.pic_static_library:",
                "        return library_to_link.pic_static_library",
                "    else:",
                "        return None",
                "",
                "def _build_avoid_library_set(avoid_dep_linking_contexts):",
                "    avoid_library_set = dict()",
                "    for linking_context in avoid_dep_linking_contexts:",
                "        for linker_input in linking_context.linker_inputs.to_list():",
                "            for library_to_link in linker_input.libraries:",
                "                library_artifact = _get_static_library_for_linking(library_to_link)",
                "                if library_artifact:",
                "                    avoid_library_set[library_artifact.short_path] = True",
                "    return avoid_library_set",
                "",
                "def _get_library_for_linking(library_to_link):",
                "    if library_to_link.static_library:",
                "        return library_to_link.static_library",
                "    elif library_to_link.pic_static_library:",
                "        return library_to_link.pic_static_library",
                "    elif library_to_link.interface_library:",
                "        return library_to_link.interface_library",
                "    else:",
                "        return library_to_link.dynamic_library",
                "",
                "def subtract_linking_contexts(owner, linking_contexts, avoid_dep_linking_contexts):",
                "    libraries = []",
                "    user_link_flags = []",
                "    additional_inputs = []",
                "    linkstamps = []",
                "    avoid_library_set = _build_avoid_library_set(avoid_dep_linking_contexts)",
                "    for linking_context in linking_contexts:",
                "        for linker_input in linking_context.linker_inputs.to_list():",
                "            for library_to_link in linker_input.libraries:",
                "                library_artifact = _get_library_for_linking(library_to_link)",
                "                if library_artifact.short_path not in avoid_library_set:",
                "                    libraries.append(library_to_link)",
                "            user_link_flags.extend(linker_input.user_link_flags)",
                "            additional_inputs.extend(linker_input.additional_inputs)",
                "            linkstamps.extend(linker_input.linkstamps)",
                "    linker_input = cc_common.create_linker_input(",
                "        owner = owner,",
                "        libraries = depset(libraries, order = 'topological'),",
                "        user_link_flags = user_link_flags,",
                "        additional_inputs = depset(additional_inputs),",
                "        linkstamps = depset(linkstamps),",
                "    )",
                "    return cc_common.create_linking_context(",
                "        linker_inputs = depset([linker_input]),",
                "    )",
                "",
                "def _build_feature_configuration(common_variables, for_swift_module_map,"
                        + " support_parse_headers):",
                "    ctx = common_variables.ctx",
                "",
                "    enabled_features = []",
                "    enabled_features.extend(ctx.features)",
                "    enabled_features.extend(common_variables.extra_enabled_features)",
                "",
                "    disabled_features = []",
                "    disabled_features.extend(ctx.disabled_features)",
                "    disabled_features.extend(common_variables.extra_disabled_features)",
                "",
                "    if not support_parse_headers:",
                "        disabled_features.append('parse_headers')",
                "",
                "    if for_swift_module_map:",
                "        enabled_features.append('module_maps')",
                "        enabled_features.append('compile_all_modules')",
                "        enabled_features.append('only_doth_headers_in_module_maps')",
                "        enabled_features.append('exclude_private_headers_in_module_maps')",
                "        enabled_features.append('module_map_without_extern_module')",
                "        disabled_features.append('generate_submodules')",
                "",
                "    return cc_common.configure_features(",
                "        ctx = common_variables.ctx,",
                "        cc_toolchain = common_variables.toolchain,",
                "        language = 'objc',",
                "        requested_features = enabled_features,",
                "        unsupported_features = disabled_features,",
                "    )",
                "def _create_deduped_linkopts_list(linker_inputs):",
                "    seen_flags = {}",
                "    final_linkopts = []",
                "    for linker_input in linker_inputs.to_list():",
                "        (_, new_flags, seen_flags) = _dedup_link_flags(",
                "            linker_input.user_link_flags,",
                "            seen_flags,",
                "        )",
                "        final_linkopts.extend(new_flags)",
                "",
                "    return final_linkopts",
                "",
                "def _linkstamp_map(ctx, linkstamps, output, build_config):",
                "    # create linkstamps_map - mapping from linkstamps to object files",
                "    linkstamps_map = {}",
                "",
                "    stamp_output_dir = paths.join(ctx.label.package, '_objs', output.basename)",
                "    for linkstamp in linkstamps:",
                "      linkstamp_file = linkstamp.file()",
                "      stamp_output_path = paths.join(",
                "          stamp_output_dir,",
                "          linkstamp_file.short_path[:-len(linkstamp_file.extension)].rstrip('.') + '.o',",
                "      )",
                "      stamp_output_file = ctx.actions.declare_shareable_artifact(",
                "          stamp_output_path,",
                "          build_config.bin_dir,",
                "      )",
                "      linkstamps_map[linkstamp_file] = stamp_output_file",
                "    return linkstamps_map",
                "",
                "def _classify_libraries(libraries_to_link):",
                "    always_link_libraries = {",
                "        lib: None",
                "        for lib in _get_libraries_for_linking(",
                "            [lib for lib in libraries_to_link if lib.alwayslink],",
                "        )",
                "    }",
                "    as_needed_libraries = {",
                "        lib: None",
                "        for lib in _get_libraries_for_linking(",
                "            [lib for lib in libraries_to_link if not lib.alwayslink],",
                "        )",
                "        if lib not in always_link_libraries",
                "    }",
                "    return always_link_libraries.keys(), as_needed_libraries.keys()",
                "def _emit_builtin_objc_strip_action(ctx):",
                "    return (",
                "        ctx.fragments.objc.builtin_objc_strip_action and",
                "        ctx.fragments.cpp.objc_enable_binary_stripping() and",
                "        ctx.fragments.cpp.compilation_mode() == 'opt'",
                "    )",
                "",
                "def _register_configuration_specific_link_actions(",
                "        name,",
                "        common_variables,",
                "        cc_linking_context,",
                "        apple_platform_info,",
                "        extra_link_args,",
                "        stamp,",
                "        user_variable_extensions,",
                "        additional_outputs,",
                "        deps,",
                "        extra_link_inputs,",
                "        attr_linkopts):",
                "    ctx = common_variables.ctx",
                "    feature_configuration = _build_feature_configuration(common_variables, False, False)",
                "",
                "    if _emit_builtin_objc_strip_action(ctx):",
                "        binary = ctx.actions.declare_shareable_artifact(",
                "            paths.join(ctx.label.package, name + '_unstripped'),",
                "            apple_platform_info.target_build_config.bin_dir,",
                "        )",
                "    else:",
                "        binary = ctx.actions.declare_shareable_artifact(",
                "            paths.join(ctx.label.package, name),",
                "            apple_platform_info.target_build_config.bin_dir,",
                "        )",
                "",
                "    if cc_common.is_enabled(",
                "        feature_configuration = feature_configuration,",
                "        feature_name = 'use_cpp_variables_for_objc_executable',",
                "    ):",
                "        return _register_configuration_specific_link_actions_with_cpp_variables(",
                "            name,",
                "            binary,",
                "            common_variables,",
                "            feature_configuration,",
                "            cc_linking_context,",
                "            apple_platform_info,",
                "            extra_link_args,",
                "            stamp,",
                "            user_variable_extensions,",
                "            additional_outputs,",
                "            deps,",
                "            extra_link_inputs,",
                "            attr_linkopts,",
                "        )",
                "    else:",
                "        return _register_configuration_specific_link_actions_with_objc_variables(",
                "            name,",
                "            binary,",
                "            common_variables,",
                "            feature_configuration,",
                "            cc_linking_context,",
                "            apple_platform_info,",
                "            extra_link_args,",
                "            stamp,",
                "            user_variable_extensions,",
                "            additional_outputs,",
                "            deps,",
                "            extra_link_inputs,",
                "            attr_linkopts,",
                "        )",
                "",
                "def _register_configuration_specific_link_actions_with_cpp_variables(",
                "        name,",
                "        binary,",
                "        common_variables,",
                "        feature_configuration,",
                "        cc_linking_context,",
                "        apple_platform_info,",
                "        extra_link_args,",
                "        stamp,",
                "        user_variable_extensions,",
                "        additional_outputs,",
                "        deps,",
                "        extra_link_inputs,",
                "        attr_linkopts):",
                "    ctx = common_variables.ctx",
                "",
                "    prefixed_attr_linkopts = [",
                "        '-Wl,%s' % linkopt",
                "        for linkopt in attr_linkopts",
                "    ]",
                "",
                "    seen_flags = {}",
                "    (_, user_link_flags, seen_flags) = _dedup_link_flags(",
                "        extra_link_args + prefixed_attr_linkopts,",
                "        seen_flags,",
                "    )",
                "    (cc_linking_context, _) = _create_deduped_linkopts_linking_context(",
                "        ctx.label,",
                "        cc_linking_context,",
                "        seen_flags,",
                "    )",
                "",
                "    cc_common.link(",
                "        name = name,",
                "        actions = ctx.actions,",
                "        additional_inputs = (",
                "            extra_link_inputs +",
                "            getattr(ctx.files, 'additional_linker_inputs', [])",
                "        ),",
                "        additional_outputs = additional_outputs,",
                "        build_config = apple_platform_info.target_build_config,",
                "        cc_toolchain = common_variables.toolchain,",
                "        feature_configuration = feature_configuration,",
                "        language = 'objc',",
                "        linking_contexts = [cc_linking_context],",
                "        main_output = binary,",
                "        output_type = 'executable',",
                "        stamp = stamp,",
                "        user_link_flags = user_link_flags,",
                "        variables_extension = user_variable_extensions,",
                "    )",
                "",
                "    if _emit_builtin_objc_strip_action(ctx):",
                "        return _register_binary_strip_action(",
                "            ctx,",
                "            name,",
                "            binary,",
                "            feature_configuration,",
                "            apple_platform_info,",
                "            extra_link_args,",
                "        )",
                "    else:",
                "        return binary",
                "",
                "def _dedup_link_flags(flags, seen_flags = {}):",
                "    new_flags = []",
                "    previous_arg = None",
                "    for arg in flags:",
                "        if previous_arg in ['-framework', '-weak_framework']:",
                "            framework = arg",
                "            key = previous_arg[1] + framework",
                "            if key not in seen_flags:",
                "                new_flags.extend([previous_arg, framework])",
                "                seen_flags[key] = True",
                "            previous_arg = None",
                "        elif arg in ['-framework', '-weak_framework']:",
                "            previous_arg = arg",
                "        elif arg.startswith('-Wl,-framework,') or arg.startswith('-Wl,-weak_framework,'):",
                "            framework = arg.split(',')[2]",
                "            key = arg[5] + framework",
                "            if key not in seen_flags:",
                "                new_flags.extend([arg.split(',')[1], framework])",
                "                seen_flags[key] = True",
                "        elif arg.startswith('-Wl,-rpath,'):",
                "            rpath = arg.split(',')[2]",
                "            key = arg[5] + rpath",
                "            if key not in seen_flags:",
                "                new_flags.append(arg)",
                "                seen_flags[key] = True",
                "        elif arg.startswith('-l'):",
                "            if arg not in seen_flags:",
                "                new_flags.append(arg)",
                "                seen_flags[arg] = True",
                "        else:",
                "            new_flags.append(arg)",
                "",
                "    same = (",
                "        len(flags) == len(new_flags) and",
                "        all([flags[i] == new_flags[i] for i in range(0, len(flags))])",
                "    )",
                "",
                "    return (same, new_flags, seen_flags)",
                "",
                "def _create_deduped_linkopts_linking_context(owner, cc_linking_context, seen_flags):",
                "    linker_inputs = []",
                "    for linker_input in cc_linking_context.linker_inputs.to_list():",
                "        (same, new_flags, seen_flags) = _dedup_link_flags(",
                "            linker_input.user_link_flags,",
                "            seen_flags,",
                "        )",
                "        if same:",
                "            linker_inputs.append(linker_input)",
                "        else:",
                "            linker_inputs.append(cc_common.create_linker_input(",
                "                owner = linker_input.owner,",
                "                libraries = depset(linker_input.libraries),",
                "                user_link_flags = new_flags,",
                "                additional_inputs = depset(linker_input.additional_inputs),",
                "                linkstamps = depset(linker_input.linkstamps),",
                "            ))",
                "",
                "    return (",
                "        cc_common.create_linking_context(",
                "            linker_inputs = depset(linker_inputs),",
                "        ),",
                "        seen_flags,",
                "    )",
                "",
                "def _libraries_from_linking_context(linking_context):",
                "    libraries = []",
                "    for linker_input in linking_context.linker_inputs.to_list():",
                "        libraries.extend(linker_input.libraries)",
                "    return depset(libraries, order = 'topological')",
                "",
                "def _register_configuration_specific_link_actions_with_objc_variables(",
                "        name,",
                "        binary,",
                "        common_variables,",
                "        feature_configuration,",
                "        cc_linking_context,",
                "        apple_platform_info,",
                "        extra_link_args,",
                "        stamp,",
                "        user_variable_extensions,",
                "        additional_outputs,",
                "        deps,",
                "        extra_link_inputs,",
                "        attr_linkopts):",
                "    ctx = common_variables.ctx",
                "    libraries_to_link = _libraries_from_linking_context(cc_linking_context).to_list()",
                "    always_link_libraries, as_needed_libraries = _classify_libraries(libraries_to_link)",
                "    static_runtimes = common_variables.toolchain.static_runtime_lib(",
                "        feature_configuration = feature_configuration,",
                "    )",
                "",
                "    # Passing large numbers of inputs on the command line triggers a bug in Apple's Clang",
                "    # (b/29094356), so we'll create an input list manually and pass -filelist"
                        + " path/to/input/list.",
                "",
                "    # Populate the input file list with both the compiled object files and any linkstamp"
                        + " object",
                "    # files.",
                "    # There's some weirdness: cc_common.link compiles linkstamps and does the linking"
                        + " (without ever",
                "    # returning linkstamp objects)",
                "    # We replicate the linkstamp objects names (guess them) and generate input_file_list",
                "    # which is input to linking action.",
                "    linkstamps = [",
                "        linkstamp",
                "        for linker_input in cc_linking_context.linker_inputs.to_list()",
                "        for linkstamp in linker_input.linkstamps",
                "    ]",
                "    linkstamp_map = _linkstamp_map(ctx, linkstamps, binary,"
                        + " apple_platform_info.target_build_config)",
                "    input_file_list = _register_obj_filelist_action(",
                "        ctx,",
                "        apple_platform_info.target_build_config,",
                "        as_needed_libraries + static_runtimes.to_list() + linkstamp_map.values(),",
                "    )",
                "",
                "    extensions = user_variable_extensions | {",
                "        'framework_paths': [],",
                "        'framework_names': [],",
                "        'weak_framework_names': [],",
                "        'library_names': [],",
                "        'filelist': input_file_list.path,",
                "        'linked_binary': binary.path,",
                "        # artifacts to be passed to the linker with `-force_load`",
                "        'force_load_exec_paths': [lib.path for lib in always_link_libraries],",
                "        # linkopts from dependency",
                "        'dep_linkopts': _create_deduped_linkopts_list(cc_linking_context.linker_inputs),",
                "        'attr_linkopts': attr_linkopts,  # linkopts arising from rule attributes",
                "    }",
                "    additional_inputs = [",
                "        input",
                "        for linker_input in cc_linking_context.linker_inputs.to_list()",
                "        for input in linker_input.additional_inputs",
                "    ]",
                "    cc_common.link(",
                "        name = name,",
                "        actions = ctx.actions,",
                "        feature_configuration = feature_configuration,",
                "        cc_toolchain = common_variables.toolchain,",
                "        language = 'objc',",
                "        additional_inputs = (",
                "            as_needed_libraries + always_link_libraries + [input_file_list] +"
                        + " extra_link_inputs +",
                "            additional_inputs +",
                "            getattr(ctx.files, 'additional_linker_inputs', [])",
                "        ),",
                "        linking_contexts = [cc_common.create_linking_context(linker_inputs = depset(",
                "            [cc_common.create_linker_input(",
                "                owner = ctx.label,",
                "                linkstamps = depset(linkstamps),",
                "            )],",
                "        ))],",
                "        output_type = 'executable',",
                "        build_config = apple_platform_info.target_build_config,",
                "        user_link_flags = extra_link_args,",
                "        stamp = stamp,",
                "        variables_extension = extensions,",
                "        additional_outputs = additional_outputs,",
                "        main_output = binary,",
                "    )",
                "",
                "    if _emit_builtin_objc_strip_action(ctx):",
                "        return _register_binary_strip_action(",
                "            ctx,",
                "            name,",
                "            binary,",
                "            feature_configuration,",
                "            apple_platform_info,",
                "            extra_link_args,",
                "        )",
                "    else:",
                "        return binary",
                "def _register_obj_filelist_action(ctx, build_config, obj_files):",
                "    obj_list = ctx.actions.declare_shareable_artifact(",
                "        paths.join(ctx.label.package, ctx.label.name + '-linker.objlist'),",
                "        build_config.bin_dir,",
                "    )",
                "",
                "    args = ctx.actions.args()",
                "    args.add_all(obj_files)",
                "    args.set_param_file_format('multiline')",
                "    ctx.actions.write(obj_list, args)",
                "",
                "    return obj_list",
                "",
                "def _apple_common_platform_from_platform_info(*, apple_platform_info):",
                "    '''Returns an apple_common.platform given the contents of an ApplePlatformInfo"
                        + " provider'''",
                "    if apple_platform_info.target_os == 'ios':",
                "        if apple_platform_info.target_environment == 'device':",
                "            return apple_common.platform.ios_device",
                "        elif apple_platform_info.target_environment == 'simulator':",
                "            return apple_common.platform.ios_simulator",
                "    elif apple_platform_info.target_os == 'macos':",
                "        return apple_common.platform.macos",
                "    elif apple_platform_info.target_os == 'tvos':",
                "        if apple_platform_info.target_environment == 'device':",
                "            return apple_common.platform.tvos_device",
                "        elif apple_platform_info.target_environment == 'simulator':",
                "            return apple_common.platform.tvos_simulator",
                "    elif apple_platform_info.target_os == 'visionos':",
                "        if apple_platform_info.target_environment == 'device':",
                "            return apple_common.platform.visionos_device",
                "        elif apple_platform_info.target_environment == 'simulator':",
                "            return apple_common.platform.visionos_simulator",
                "    elif apple_platform_info.target_os == 'watchos':",
                "        if apple_platform_info.target_environment == 'device':",
                "            return apple_common.platform.watchos_device",
                "        elif apple_platform_info.target_environment == 'simulator':",
                "            return apple_common.platform.watchos_simulator",
                "    else:",
                "        fail('Internal Error: Found unrecognized target os of ' +"
                        + " apple_platform_info.target_os)",
                "    ",
                "def _register_binary_strip_action(",
                "        ctx,",
                "        name,",
                "        binary,",
                "        feature_configuration,",
                "        apple_platform_info,",
                "        extra_link_args):",
                "    strip_safe = ctx.fragments.objc.strip_executable_safely",
                "",
                "    # For dylibs, loadable bundles, and kexts, must strip only local symbols.",
                "    link_dylib = cc_common.is_enabled(",
                "        feature_configuration = feature_configuration,",
                "        feature_name = 'link_dylib',",
                "    )",
                "    link_bundle = cc_common.is_enabled(",
                "        feature_configuration = feature_configuration,",
                "        feature_name = 'link_bundle',",
                "    )",
                "    if ('-dynamiclib' in extra_link_args or link_dylib or",
                "        '-bundle' in extra_link_args or link_bundle or '-kext' in extra_link_args):",
                "        strip_safe = True",
                "",
                "    stripped_binary = ctx.actions.declare_shareable_artifact(",
                "        paths.join(ctx.label.package, name),",
                "        apple_platform_info.target_build_config.bin_dir,",
                "    )",
                "    args = ctx.actions.args()",
                "    args.add('strip')",
                "    if strip_safe:",
                "        args.add('-x')",
                "    args.add('-o', stripped_binary)",
                "    args.add(binary)",
                "    xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig]",
                "    platform = _apple_common_platform_from_platform_info(apple_platform_info ="
                        + " apple_platform_info)",
                "",
                "    ctx.actions.run(",
                "        mnemonic = 'ObjcBinarySymbolStrip',",
                "        executable = '/usr/bin/xcrun',",
                "        arguments = [args],",
                "        inputs = [binary],",
                "        outputs = [stripped_binary],",
                "        execution_requirements ="
                        + " ctx.attr._xcode_config[apple_common.XcodeVersionConfig].execution_info(),",
                "        env = apple_common.apple_host_system_env(xcode_config) |",
                "              apple_common.target_apple_env(xcode_config, platform),",
                "    )",
                "    return stripped_binary",
                "def _link_multi_arch_binary(",
                "        *,",
                "        ctx,",
                "        avoid_deps = [],",
                "        cc_toolchains = {},",
                "        extra_linkopts = [],",
                "        extra_link_inputs = [],",
                "        extra_requested_features = [],",
                "        extra_disabled_features = [],",
                "        stamp = -1,",
                "        variables_extension = {}):",
                "",
                "    split_deps = ctx.split_attr.deps",
                "",
                "    if split_deps and split_deps.keys() != cc_toolchains.keys():",
                "        fail(('Split transition keys are different between deps [%s] and ' +",
                "              '_cc_toolchain_forwarder [%s]') % (",
                "            split_deps.keys(),",
                "            cc_toolchains.keys(),",
                "        ))",
                "",
                "    avoid_cc_infos = [",
                "        dep[AppleDynamicFrameworkInfo].cc_info",
                "        for dep in avoid_deps",
                "        if AppleDynamicFrameworkInfo in dep",
                "    ]",
                "    avoid_cc_infos.extend([",
                "        dep[AppleExecutableBinaryInfo].cc_info",
                "        for dep in avoid_deps",
                "        if AppleExecutableBinaryInfo in dep",
                "    ])",
                "    avoid_cc_infos.extend([dep[CcInfo] for dep in avoid_deps if CcInfo in dep])",
                "    avoid_cc_linking_contexts = [dep.linking_context for dep in avoid_cc_infos]",
                "",
                "    outputs = []",
                "    cc_infos = []",
                "    legacy_debug_outputs = {}",
                "",
                "    cc_infos.extend(avoid_cc_infos)",
                "",
                "    additional_linker_inputs = getattr(ctx.attr, 'additional_linker_inputs', [])",
                "    attr_linkopts = [",
                "        ctx.expand_location(opt, targets = additional_linker_inputs)",
                "        for opt in getattr(ctx.attr, 'linkopts', [])",
                "    ]",
                "    attr_linkopts = [token for opt in attr_linkopts for token in ctx.tokenize(opt)]",
                "",
                "    for split_transition_key, child_toolchain in cc_toolchains.items():",
                "        cc_toolchain = child_toolchain[cc_common.CcToolchainInfo]",
                "        deps = split_deps.get(split_transition_key, [])",
                "        platform_info = child_toolchain[ApplePlatformInfo]",
                "",
                "        common_variables = compilation_support.build_common_variables(",
                "            ctx = ctx,",
                "            toolchain = cc_toolchain,",
                "            deps = deps,",
                "            extra_disabled_features = extra_disabled_features,",
                "            extra_enabled_features = extra_requested_features,",
                "            attr_linkopts = attr_linkopts,",
                "        )",
                "",
                "        cc_infos.append(CcInfo(",
                "            compilation_context = cc_common.merge_compilation_contexts(",
                "                compilation_contexts =",
                "                    common_variables.objc_compilation_context.cc_compilation_contexts,",
                "            ),",
                "            linking_context = cc_common.merge_linking_contexts(",
                "                linking_contexts ="
                        + " common_variables.objc_linking_context.cc_linking_contexts,",
                "            ),",
                "        ))",
                "",
                "        cc_linking_context = subtract_linking_contexts(",
                "            owner = ctx.label,",
                "            linking_contexts = common_variables.objc_linking_context.cc_linking_contexts"
                        + " +",
                "                               avoid_cc_linking_contexts,",
                "            avoid_dep_linking_contexts = avoid_cc_linking_contexts,",
                "        )",
                "",
                "",
                "        additional_outputs = []",
                "        extensions = {}",
                "",
                "        dsym_binary = None",
                "        if ctx.fragments.cpp.apple_generate_dsym:",
                "            if ctx.fragments.cpp.objc_should_strip_binary:",
                "                suffix = '_bin_unstripped.dwarf'",
                "            else:",
                "                suffix = '_bin.dwarf'",
                "            dsym_binary = ctx.actions.declare_shareable_artifact(",
                "                ctx.label.package + '/' + ctx.label.name + suffix,",
                "                platform_info.target_build_config.bin_dir,",
                "            )",
                "            extensions['dsym_path'] = dsym_binary.path  # dsym symbol file",
                "            additional_outputs.append(dsym_binary)",
                "            legacy_debug_outputs.setdefault(platform_info.target_arch,"
                        + " {})['dsym_binary'] = dsym_binary",
                "",
                "        linkmap = None",
                "        if ctx.fragments.cpp.objc_generate_linkmap:",
                "            linkmap = ctx.actions.declare_shareable_artifact(",
                "                ctx.label.package + '/' + ctx.label.name + '.linkmap',",
                "                platform_info.target_build_config.bin_dir,",
                "            )",
                "            extensions['linkmap_exec_path'] = linkmap.path  # linkmap file",
                "            additional_outputs.append(linkmap)",
                "            legacy_debug_outputs.setdefault(platform_info.target_arch, {})['linkmap'] ="
                        + " linkmap",
                "",
                "        name = ctx.label.name + '_bin'",
                "        executable = _register_configuration_specific_link_actions(",
                "            name = name,",
                "            common_variables = common_variables,",
                "            cc_linking_context = cc_linking_context,",
                "            apple_platform_info = platform_info,",
                "            extra_link_args = extra_linkopts,",
                "            stamp = stamp,",
                "            user_variable_extensions = variables_extension | extensions,",
                "            additional_outputs = additional_outputs,",
                "            deps = deps,",
                "            extra_link_inputs = extra_link_inputs,",
                "            attr_linkopts = attr_linkopts,",
                "        )",
                "",
                "        output = {",
                "            'binary': executable,",
                "            'platform': platform_info.target_os,",
                "            'architecture': platform_info.target_arch,",
                "            'environment': platform_info.target_environment,",
                "            'dsym_binary': dsym_binary,",
                "            'linkmap': linkmap,",
                "        }",
                "",
                "        outputs.append(struct(**output))",
                "",
                "    header_tokens = []",
                "    for _, deps in split_deps.items():",
                "        for dep in deps:",
                "            if CcInfo in dep:",
                "               "
                        + " header_tokens.append(dep[CcInfo].compilation_context.validation_artifacts)",
                "",
                "    output_groups = {'_validation': depset(transitive = header_tokens)}",
                "",
                "    return struct(",
                "        cc_info = cc_common.merge_cc_infos(direct_cc_infos = cc_infos),",
                "        output_groups = output_groups,",
                "        outputs = outputs,",
                "        debug_outputs_provider = AppleDebugOutputsInfo(outputs_map ="
                        + " legacy_debug_outputs),",
                "    )",
                "",
                "def apple_binary_starlark_impl(ctx):",
                "    all_avoid_deps = list(ctx.attr.avoid_deps)",
                "    binary_type = ctx.attr.binary_type",
                "    bundle_loader = ctx.attr.bundle_loader",
                "    linkopts = []",
                "    link_inputs = []",
                "    variables_extension = {}",
                "    variables_extension.update(ctx.attr.string_variables_extension)",
                "    variables_extension.update(ctx.attr.string_list_variables_extension)",
                "    if binary_type == 'dylib':",
                "        linkopts.append('-dynamiclib')",
                "    elif binary_type == 'loadable_bundle':",
                "        linkopts.extend(['-bundle', '-Xlinker', '-rpath', '-Xlinker',"
                        + " '@loader_path/Frameworks'])",
                "    if ctx.attr.bundle_loader:",
                "        bundle_loader = ctx.attr.bundle_loader",
                "        bundle_loader_file = bundle_loader[AppleExecutableBinaryInfo].binary",
                "        all_avoid_deps.append(bundle_loader)",
                "        linkopts.extend(['-bundle_loader', bundle_loader_file.path])",
                "        link_inputs.append(bundle_loader_file)",
                "    link_result = _link_multi_arch_binary(",
                "        ctx = ctx,",
                "        avoid_deps = all_avoid_deps,",
                "        cc_toolchains = ctx.split_attr._cc_toolchain_forwarder,",
                "        extra_linkopts = linkopts,",
                "        extra_link_inputs = link_inputs,",
                "        extra_requested_features = ctx.attr.extra_requested_features,",
                "        extra_disabled_features = ctx.attr.extra_disabled_features,",
                "        stamp = ctx.attr.stamp,",
                "        variables_extension = variables_extension,",
                "    )",
                "    processed_binary = ctx.actions.declare_file('{}_lipobin'.format(ctx.label.name))",
                "    lipo_inputs = [output.binary for output in link_result.outputs]",
                "    if len(lipo_inputs) > 1:",
                "        apple_env = {}",
                "        xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig]",
                "        apple_env.update(apple_common.apple_host_system_env(xcode_config))",
                "        apple_env.update(",
                "            apple_common.target_apple_env(",
                "                xcode_config,",
                "                ctx.fragments.apple.single_arch_platform,",
                "            ),",
                "        )",
                "        args = ctx.actions.args()",
                "        args.add('-create')",
                "        args.add_all(lipo_inputs)",
                "        args.add('-output', processed_binary)",
                "        ctx.actions.run(",
                "            arguments = [args],",
                "            env = apple_env,",
                "            executable = '/usr/bin/lipo',",
                "            execution_requirements = xcode_config.execution_info(),",
                "            inputs = lipo_inputs,",
                "            outputs = [processed_binary],",
                "        )",
                "    else:",
                "        ctx.actions.symlink(target_file = lipo_inputs[0], output = processed_binary)",
                "    providers = [",
                "        DefaultInfo(files=depset([processed_binary])),",
                "        OutputGroupInfo(**link_result.output_groups),",
                "        link_result.debug_outputs_provider,",
                "    ]",
                "    if binary_type == 'executable':",
                "        providers.append(AppleExecutableBinaryInfo(",
                "            binary = processed_binary,",
                "            cc_info = link_result.cc_info,",
                "        ))",
                "    return providers",
                "apple_binary_starlark = rule(",
                "    apple_binary_starlark_impl,",
                "    attrs = {",
                "        '_cc_toolchain_forwarder': attr.label(",
                "            cfg = apple_platform_split_transition,",
                "            providers = [cc_common.CcToolchainInfo, ApplePlatformInfo],",
                "            default = Label('//test_starlark:default_cc_toolchain_forwarder'),),",
                "        '_xcode_config': attr.label(",
                "            default=configuration_field(",
                "                fragment='apple', name='xcode_config_label'),),",
                "        'additional_linker_inputs': attr.label_list(",
                "            allow_files = True,",
                "        ),",
                "        'avoid_deps': attr.label_list(default=[]),",
                "        'binary_type': attr.string(",
                "             default = 'executable',",
                "             values = ['dylib', 'executable', 'loadable_bundle']",
                "        ),",
                "        'bundle_loader': attr.label(),",
                "        'deps': attr.label_list(",
                "             cfg=apple_platform_split_transition,",
                "        ),",
                "        'linkopts': attr.string_list(),",
                "        'extra_requested_features': attr.string_list(),",
                "        'extra_disabled_features': attr.string_list(),",
                "        'platform_type': attr.string(),",
                "        'minimum_os_version': attr.string(),",
                "        'stamp': attr.int(values=[-1,0,1],default=-1),",
                "        'string_variables_extension': attr.string_dict(),",
                "        'string_list_variables_extension': attr.string_list_dict(),",
                "    },",
                "    fragments = ['apple', 'objc', 'cpp']",
                ")"
            )
            scratch.overwriteFile(
                "tools/allowlists/function_transition_allowlist/BUILD",
                """
        package_group(
            name = "function_transition_allowlist",
            packages = [
                "//...",
            ],
        )
        
        """.trimIndent()
            )
            scratch.file(
                "test_starlark/cc_toolchain_forwarder.bzl",
                """
load("@rules_cc//cc:find_cc_toolchain.bzl", "find_cc_toolchain", "use_cc_toolchain")
load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
load(":apple_binary_starlark.bzl", "ApplePlatformInfo")

def _target_os_from_rule_ctx(ctx):
  ios_constraint = ctx.attr._ios_constraint[platform_common.ConstraintValueInfo]
  macos_constraint = ctx.attr._macos_constraint[platform_common.ConstraintValueInfo]
  tvos_constraint = ctx.attr._tvos_constraint[platform_common.ConstraintValueInfo]
  visionos_constraint = ctx.attr._visionos_constraint[platform_common.ConstraintValueInfo]
  watchos_constraint = ctx.attr._watchos_constraint[platform_common.ConstraintValueInfo]

  if ctx.target_platform_has_constraint(ios_constraint):
      return str(apple_common.platform_type.ios)
  elif ctx.target_platform_has_constraint(macos_constraint):
      return str(apple_common.platform_type.macos)
  elif ctx.target_platform_has_constraint(tvos_constraint):
      return str(apple_common.platform_type.tvos)
  elif ctx.target_platform_has_constraint(visionos_constraint):
      return str(apple_common.platform_type.visionos)
  elif ctx.target_platform_has_constraint(watchos_constraint):
      return str(apple_common.platform_type.watchos)
  fail("ERROR: A valid Apple platform constraint could not be found " +
          "from the resolved toolchain.")

def _target_arch_from_rule_ctx(ctx):
  arm64_constraint = ctx.attr._arm64_constraint[platform_common.ConstraintValueInfo]
  arm64e_constraint = ctx.attr._arm64e_constraint[platform_common.ConstraintValueInfo]
  arm64_32_constraint = ctx.attr._arm64_32_constraint[platform_common.ConstraintValueInfo]
  armv7k_constraint = ctx.attr._armv7k_constraint[platform_common.ConstraintValueInfo]
  x86_64_constraint = ctx.attr._x86_64_constraint[platform_common.ConstraintValueInfo]

  if ctx.target_platform_has_constraint(arm64_constraint):
      return "arm64"
  elif ctx.target_platform_has_constraint(arm64e_constraint):
      return "arm64e"
  elif ctx.target_platform_has_constraint(arm64_32_constraint):
      return "arm64_32"
  elif ctx.target_platform_has_constraint(armv7k_constraint):
      return "armv7k"
  elif ctx.target_platform_has_constraint(x86_64_constraint):
      return "x86_64"
  fail("ERROR: A valid Apple cpu constraint could not be" +
           " found from the resolved toolchain.")

def _target_environment_from_rule_ctx(ctx):
  device_constraint = ctx.attr._apple_device_constraint[platform_common.ConstraintValueInfo]
  simulator_constraint = ctx.attr._apple_simulator_constraint[platform_common.ConstraintValueInfo]

  if ctx.target_platform_has_constraint(device_constraint):
      return "device"
  elif ctx.target_platform_has_constraint(simulator_constraint):
      return "simulator"

  fail("ERROR: A valid Apple environment (device, simulator) constraint could not be found from" +
      " the resolved toolchain.")

def _cc_toolchain_forwarder_impl(ctx):
  return [
      find_cc_toolchain(ctx),
      ApplePlatformInfo(
          target_os = _target_os_from_rule_ctx(ctx),
          target_arch = _target_arch_from_rule_ctx(ctx),
          target_environment = _target_environment_from_rule_ctx(ctx),
          target_build_config = ctx.configuration,
      ),
  ]

cc_toolchain_forwarder = rule(
  implementation = _cc_toolchain_forwarder_impl,
  attrs = {
      "_cc_toolchain": attr.label(
          default = Label("TOOLS_REPOSITORY//tools/cpp:current_cc_toolchain"),
      ),
      "_ios_constraint": attr.label(
          default = Label("CONSTRAINTS_PACKAGE_ROOTos:ios"),
      ),
      "_macos_constraint": attr.label(
          default = Label("CONSTRAINTS_PACKAGE_ROOTos:osx"),
      ),
      "_tvos_constraint": attr.label(
          default = Label("CONSTRAINTS_PACKAGE_ROOTos:tvos"),
      ),
      "_visionos_constraint": attr.label(
          default = Label("CONSTRAINTS_PACKAGE_ROOTos:visionos"),
      ),
      "_watchos_constraint": attr.label(
          default = Label("CONSTRAINTS_PACKAGE_ROOTos:watchos"),
      ),
      "_arm64_constraint": attr.label(
          default = Label("CONSTRAINTS_PACKAGE_ROOTcpu:arm64"),
      ),
      "_arm64e_constraint": attr.label(
          default = Label("CONSTRAINTS_PACKAGE_ROOTcpu:arm64e"),
      ),
      "_arm64_32_constraint": attr.label(
          default = Label("CONSTRAINTS_PACKAGE_ROOTcpu:arm64_32"),
      ),
      "_armv7k_constraint": attr.label(
          default = Label("CONSTRAINTS_PACKAGE_ROOTcpu:armv7k"),
      ),
      "_x86_64_constraint": attr.label(
          default = Label("CONSTRAINTS_PACKAGE_ROOTcpu:x86_64"),
      ),
      "_apple_device_constraint": attr.label(
          default = Label("CONSTRAINTS_PACKAGE_ROOTenv:device"),
      ),
      "_apple_simulator_constraint": attr.label(
          default = Label("CONSTRAINTS_PACKAGE_ROOTenv:simulator"),
      ),
  },
  provides = [cc_common.CcToolchainInfo, ApplePlatformInfo],
  toolchains = use_cc_toolchain(),
)

"""
                    .trimIndent()
                    .replace("TOOLS_REPOSITORY", toolsRepo.toString())
                    .replace("CONSTRAINTS_PACKAGE_ROOT", TestConstants.CONSTRAINTS_PACKAGE_ROOT)
            )
        }

        @Throws(EvalException::class, RuleErrorException::class)
        protected fun getArifactPathsOfLibraries(target: ConfiguredTarget?): Iterable<String?> {
            return Artifact.toRootRelativePaths(
                CcInfo.get(target).getCcLinkingContext().getStaticModeParamsForDynamicLibraryLibraries()
            )
        }

        @Throws(RuleErrorException::class)
        protected fun getArifactPathsOfHeaders(target: ConfiguredTarget?): Iterable<String?> {
            return Artifact.toRootRelativePaths(
                CcInfo.get(target).getCcCompilationContext().getDeclaredIncludeSrcs()
            )
        }

        @Throws(LabelSyntaxException::class)
        protected fun getObjcInfo(starlarkTarget: ConfiguredTarget): StarlarkInfo? {
            return starlarkTarget.get(Key(TestConstants.OBJC_INFO_LOAD_KEY, "ObjcInfo")) as StarlarkInfo?
        }

        @Throws(EvalException::class)
        protected fun getDirectSources(provider: StarlarkInfo): ImmutableList<Artifact?>? {
            return Sequence.cast<T?>(provider.getValue("direct_sources"), Artifact::class.java, "direct_sources")
                .getImmutableList()
        }

        @Throws(EvalException::class)
        protected fun getModuleMap(provider: StarlarkInfo): NestedSet<Artifact?> {
            return Depset.cast(provider.getValue("module_map"), Artifact::class.java, "module_map")
        }

        @Throws(EvalException::class)
        protected fun getSource(provider: StarlarkInfo): ImmutableList<Artifact?> {
            return Depset.cast(provider.getValue("source"), Artifact::class.java, "source").toList()
        }

        @Throws(EvalException::class)
        protected fun getStrictInclude(provider: StarlarkInfo): ImmutableList<String?> {
            return Depset.cast(provider.getValue("strict_include"), String::class.java, "strict_include")
                .toList()
        }

        @Throws(EvalException::class)
        protected fun getUmbrellaHeader(provider: StarlarkInfo): ImmutableList<Artifact?> {
            return Depset.cast(provider.getValue("umbrella_header"), Artifact::class.java, "umbrella_header")
                .toList()
        }
    }
}
