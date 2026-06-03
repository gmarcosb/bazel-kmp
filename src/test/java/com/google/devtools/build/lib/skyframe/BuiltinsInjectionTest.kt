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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/**
 * Tests for Starlark builtin injection.
 * 
 * 
 * Essentially these are integration tests between [StarlarkBuiltinsFunction], [ ], and the rest of package loading.
 */
@RunWith(JUnit4::class)
open class BuiltinsInjectionTest : BuildViewTestCase() {
    // Must be public due to reflective construction of rule factories.
    /** Factory for SANDWICH_RULE. (Javadoc'd to pacify linter.)  */
    class SandwichFactory : DefaultConfiguredTargetFactory() {
        @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
        override fun create(ruleContext: RuleContext): ConfiguredTarget {
            val value: Any = ruleContext.getStarlarkDefinedBuiltin("builtins_defined_symbol")
            val handler: com.google.devtools.build.lib.events.EventHandler =
                ruleContext.getAnalysisEnvironment().getEventHandler()
            handler.handle(com.google.devtools.build.lib.events.Event.info("builtins_defined_symbol :: " + value.toString()))
            return super.create(ruleContext)
        }
    }

    // Must be public due to reflective construction of rule factories.
    /** Factory for SANDWICH_LOGIC_RULE. (Javadoc'd to pacify linter.)  */
    class SandwichLogicFactory : DefaultConfiguredTargetFactory() {
        @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
        override fun create(ruleContext: RuleContext): ConfiguredTarget {
            val mu: Mutability? = ruleContext.getStarlarkThread().mutability()
            val func: Any? = ruleContext.getStarlarkDefinedBuiltin("builtins_defined_logic")
            val arg: Any = StarlarkList.newList<Any?>(mu)
            val return1: Any =
                ruleContext.callStarlarkOrThrowRuleError(
                    func,  /*args=*/
                    com.google.common.collect.ImmutableList.of<E?>(arg),  /*kwargs=*/
                    com.google.common.collect.ImmutableMap.of<K?, V?>()
                )
            val return2: Any =
                ruleContext.callStarlarkOrThrowRuleError(
                    func,  /*args=*/
                    com.google.common.collect.ImmutableList.of<E?>(arg),  /*kwargs=*/
                    com.google.common.collect.ImmutableMap.of<K?, V?>()
                )

            val handler: com.google.devtools.build.lib.events.EventHandler =
                ruleContext.getAnalysisEnvironment().getEventHandler()
            handler.handle(com.google.devtools.build.lib.events.Event.info("builtins_defined_logic call 1 :: " + return1.toString()))
            handler.handle(com.google.devtools.build.lib.events.Event.info("builtins_defined_logic call 2 :: " + return2.toString()))
            handler.handle(com.google.devtools.build.lib.events.Event.info("final list value :: " + arg.toString()))
            return super.create(ruleContext)
        }
    }

    // Must be public due to reflective construction of rule factories.
    /** Factory for SANDWICH_CTX_RULE. (Javadoc'd to pacify linter.)  */
    class SandwichCtxFactory : DefaultConfiguredTargetFactory() {
        @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
        override fun create(ruleContext: RuleContext): ConfiguredTarget {
            ruleContext.initStarlarkRuleContext()
            ruleContext.callStarlarkOrThrowRuleError(
                ruleContext.getStarlarkDefinedBuiltin("builtins_rule_impl_helper"),  /*args=*/
                com.google.common.collect.ImmutableList.of<E?>(ruleContext.getStarlarkRuleContext()),  /*kwargs=*/
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
            // Don't dispatch to super.create(), which would attempt to register an action to produce
            // "out".
            return RuleConfiguredTargetBuilder(ruleContext)
                .setFilesToBuild(
                    NestedSetBuilder.wrap(Order.STABLE_ORDER, ruleContext.getOutputArtifacts())
                )
                .setRunfilesSupport(null, null)
                .add(RunfilesProvider::class.java, RunfilesProvider.simple(Runfiles.EMPTY))
                .build()
        }
    }

    val defaultsForConfiguration: Iterable<String?>?
        get() =// Override BuildViewTestCase's behavior of setting all sorts of extra options that don't exist
        // on our minimal rule class provider.
            // We do need the host platform. Set it to something trivial.
            com.google.common.collect.ImmutableList.of<String?>(
                "--host_platform=//minimal_buildenv/platforms:default",
                "--platforms=//minimal_buildenv/platforms:default",  // Since this file tests builtins injection, replace the standard exec transition (which is
                // in builtins) with a no-op to avoid interference.
                "--experimental_exec_config=//pkg2:dummy_exec_platforms.bzl%noop_exec_transition"
            )

    val analysisMock: AnalysisMock
        get() = object : com.google.devtools.build.lib.analysis.util.AnalysisMock.Delegate(super.getAnalysisMock()) {
            public override fun getBuiltinModules(
                directories: BlazeDirectories?
            ): com.google.common.collect.ImmutableMap<String?, NonRegistryOverride?>? {
                return com.google.common.collect.ImmutableMap.of<String?, NonRegistryOverride?>()
            }
        }

    @Throws(IOException::class)
    override fun initializeMockClient() {
        analysisMock.setupMockClient(mockToolsConfig)
        // Provide a trivial platform definition.
        mockToolsConfig.create(
            "minimal_buildenv/platforms/BUILD",  //
            "platform(name = 'default')"
        )
        // No-op exec transition:
        scratch.overwriteFile("pkg2/BUILD", "")
        scratch.file(
            "pkg2/dummy_exec_platforms.bzl",
            """
        # Since this isn't in builtins, use `transition`, not `exec_transition`
        # This is fine, since this is a no-op and doesn't use any of the features that exec
        # transitions are allowed to use.
        noop_exec_transition = transition(
            implementation = lambda settings, attr: {
                '//command_line_option:is exec configuration': True,
            },
            inputs = [],
            outputs = ['//command_line_option:is exec configuration'],
        )
        
        """.trimIndent()
        )
    }

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        // Set up a bare-bones ConfiguredRuleClassProvider. Aside from being minimalistic, this heads
        // off the possibility that we somehow grow an implicit dependency on production builtins code,
        // which would break since we're overwriting --experimental_builtins_bzl_path.
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addMinimalRules(builder)
        // Add some mock symbols to override.
        builder
            .addRuleDefinition(OVERRIDABLE_RULE)
            .addRuleDefinition(SANDWICH_RULE)
            .addRuleDefinition(SANDWICH_LOGIC_RULE)
            .addRuleDefinition(SANDWICH_CTX_RULE)
            .addBzlToplevel("overridable_symbol", "original_value")
            .addBzlToplevel(
                "flag_guarded_symbol",  // For this mock symbol, we reuse the same flag that guards the production
                // _builtins_dummy symbol.
                FlagGuardedValue.onlyWhenExperimentalFlagIsTrue(
                    BuildLanguageOptions.EXPERIMENTAL_BUILTINS_DUMMY, "original_value"
                )
            )
            .addStarlarkBuiltinsInternal("internal_symbol", "internal_value")
        return builder.build()
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        setBuildLanguageOptionsWithBuiltinsStaging()
    }

    val defaultBuildLanguageOptions: MutableList<String?>
        get() {
            val builder: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            builder.addAll(super.defaultBuildLanguageOptions)
            // This is important for test initialization. BuildViewTestCase calls initializeSkyframeExecutor
            // which creates the top-level build configuration. That loads the Starlark exec transition from
            // a .bzl file (see --experimental_exec_config above). Without this, BzlLoadFunction tries to
            // resolve the builtins path and exports.bzl, which fails.
            //
            // Most tests override this by calling setBuildLanguageOptionsWithBuiltinsStaging()
            builder.add("--experimental_builtins_bzl_path=")
            return builder.build()
        }

    @Throws(java.lang.Exception::class)
    private fun setBuildLanguageOptionsWithBuiltinsStaging(vararg options: String?) {
        val newOptions: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
        newOptions.add("--experimental_builtins_bzl_path=tools/builtins_staging")
        Collections.addAll<String?>(newOptions, *options)
        setBuildLanguageOptions(*newOptions.toArray<String?>(arrayOf<String?>()))
    }

    /**
     * Writes an exports.bzl file with the given content, in the builtins location.
     * 
     * 
     * See [StarlarkBuiltinsFunction.EXPORTS_ENTRYPOINT] for the significance of exports.bzl.
     */
    @Throws(java.lang.Exception::class)
    private fun writeExportsBzl(vararg lines: String?) {
        scratch.overwriteFile("tools/builtins_staging/exports.bzl", lines)
        // Since builtins have changed, we need to be sure the cache is reset to re-load them.
        invalidatePackages( /* alsoConfigs= */false)
    }

    /**
     * Writes a pkg/dummy.bzl file with the given content. Meant to be used in conjunction with [ ][.writePkgBuild].
     * 
     * 
     * The bzl prints a marker phrase when it finishes evaluating, and includes a dummy symbol for
     * the BUILD file to load.
     */
    @Throws(java.lang.Exception::class)
    private fun writePkgBzl(vararg lines: String?) {
        val modifiedLines: MutableList<String?> = java.util.ArrayList<String?>(java.util.Arrays.asList<String?>(*lines))
        modifiedLines.add("dummy_symbol = None")
        // The marker phrase might not be needed, but I don't entirely trust BuildViewTestCase.
        modifiedLines.add("print('dummy.bzl evaluation completed')")
        scratch.overwriteFile("pkg/dummy.bzl", modifiedLines.< T > toArray < T ? > (lines))
    }

    /**
     * Writes a pkg/BUILD file with the given content. Meant to be used in conjunction with [ ][.writePkgBzl].
     * 
     * 
     * The BUILD file ensures the dummy.bzl file is loaded.
     */
    @Throws(java.lang.Exception::class)
    private fun writePkgBuild(vararg lines: String?) {
        val modifiedLines: MutableList<String?> = java.util.ArrayList<String?>(java.util.Arrays.asList<String?>(*lines))
        modifiedLines.add(0, "load(':dummy.bzl', 'dummy_symbol')")
        scratch.overwriteFile("pkg/BUILD", modifiedLines.< T > toArray < T ? > (lines))
    }

    /** Builds `//pkg` and asserts success, including that the marker print() event occurs.  */
    @Throws(java.lang.Exception::class)
    private fun buildAndAssertSuccess() {
        val result: Any = getConfiguredTarget("//pkg:BUILD")
        assertContainsEvent("dummy.bzl evaluation completed")
        // On error, getConfiguredTarget sometimes returns null without emitting events; see b/26382502.
        // Though in that case it seems unlikely the above assertion would've passed.
        Truth.assertThat(result).isNotNull()
    }

    /** Builds `//pkg:dummy` and asserts on the absence of the marker print() event.  */
    @Throws(java.lang.Exception::class)
    private fun buildAndAssertFailure() {
        reporter.removeHandler(failFastHandler)
        val result: Any = getConfiguredTarget("//pkg:BUILD")
        assertDoesNotContainEvent("dummy.bzl evaluation completed")
        Truth.assertWithMessage("Loading of //pkg succeeded unexpectedly").that(result).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicFunctionality() {
        writeExportsBzl(
            "exported_toplevels = {'overridable_symbol': 'new_value'}",
            "exported_rules = {'overridable_rule': 'new_rule'}",
            "exported_to_java = {}"
        )
        writePkgBuild("print('In BUILD: overridable_rule :: %s' % overridable_rule)")
        writePkgBzl(
            "print('In bzl: overridable_symbol :: %s' % overridable_symbol)",
            "print('In bzl: overridable_rule :: %s' % native.overridable_rule)"
        )

        buildAndAssertSuccess()
        assertContainsEvent("In bzl: overridable_symbol :: new_value")
        assertContainsEvent("In bzl: overridable_rule :: new_rule")
        assertContainsEvent("In BUILD: overridable_rule :: new_rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectedBzlToplevelsAreNotVisibleToBuild() {
        // The bzl toplevel symbols aren't toplevels for BUILD files. We test that injecting them
        // doesn't somehow change that.
        writeExportsBzl(
            "exported_toplevels = {'overridable_symbol': 'new_value'}",  //
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild("overridable_symbol")
        writePkgBzl()

        buildAndAssertFailure()
        assertContainsEvent("name 'overridable_symbol' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtinsCanLoadFromBuiltins() {
        // Define a few files that we can load with different kinds of label syntax. In each case,
        // access the `_builtins` symbol to demonstrate that we're being loaded as a builtins bzl.
        scratch.file(
            "tools/builtins_staging/absolute.bzl",
            """
        _builtins
        a = "A"
        
        """.trimIndent()
        )
        scratch.file(
            "tools/builtins_staging/repo_relative.bzl",
            """
        _builtins
        b = "B"
        
        """.trimIndent()
        )
        scratch.file(
            "tools/builtins_staging/subdir/pkg_relative1.bzl",
            """
        # Do a relative load within a load, to show it's relative to the (pseudo) package, i.e. the
        # root, and not relative to the file. That is, we specify 'subdir/pkg_relative2.bzl', not
        # just 'pkg_relative2.bzl'.
        load("subdir/pkg_relative2.bzl", "c2")

        _builtins
        c = c2
        
        """.trimIndent()
        )
        scratch.file(
            "tools/builtins_staging/subdir/pkg_relative2.bzl",
            """
        _builtins
        c2 = "C"
        
        """.trimIndent()
        )

        // Also create a file in the main repo whose package path coincides with a file in the builtins
        // pseudo-repo, to show that we get the right one.
        scratch.file("BUILD")
        scratch.file("repo_relative.bzl")

        writeExportsBzl(
            "load('@_builtins//:absolute.bzl', 'a')",
            "load('//:repo_relative.bzl', 'b')",  // default repo is @_builtins, not main repo
            "load('subdir/pkg_relative1.bzl', 'c')",  // relative to (pseudo) package, which is repo root
            "exported_toplevels = {'overridable_symbol': a + b + c}",
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl("print('overridable_symbol :: %s' % overridable_symbol)")

        buildAndAssertSuccess()
        assertContainsEvent("overridable_symbol :: ABC")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun otherBzlsCannotLoadFromBuiltins_apparent() {
        writeExportsBzl(
            "exported_toplevels = {}",  //
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl("load('@_builtins//:exports.bzl', 'exported_toplevels')")

        buildAndAssertFailure()
        assertContainsEvent("No repository visible as '@_builtins' from")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun otherBzlsCannotLoadFromBuiltins_canonical() {
        writeExportsBzl(
            "exported_toplevels = {}",  //
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl("load('@@_builtins//:exports.bzl', 'exported_toplevels')")

        buildAndAssertFailure()
        assertContainsEvent("The repository '@@_builtins' could not be resolved")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtinsCannotLoadFromNonBuiltins() {
        scratch.file("BUILD")
        scratch.file(
            "a_user_written.bzl",  //
            "toplevels = {'overridable_symbol': 'new_value'}"
        )
        writeExportsBzl( // Use @// syntax to specify the main repo. Otherwise, the load would be relative to the
            // @_builtins pseudo-repo.
            "load('@//:a_user_written.bzl', 'toplevels')",
            "exported_toplevels = toplevels",
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl()

        buildAndAssertFailure()
        assertContainsEvent(
            "in load statement: .bzl files in @_builtins cannot load from outside of @_builtins"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtinsCannotLoadWithMisplacedColon() {
        scratch.file(
            "tools/builtins_staging/subdir/helper.bzl",  //
            "toplevels = {'overridable_symbol': 'new_value'}"
        )
        writeExportsBzl(
            "load('//subdir:helper.bzl', 'toplevels')",  // Should've been loaded as //:subdir/helper.bzl
            "exported_toplevels = toplevels",
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl()

        buildAndAssertFailure()
        assertContainsEvent("@_builtins cannot have subpackages")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorInEvaluatingBuiltinsTransitiveDependency() {
        // Test case with a deep Starlark error in the @_builtins pseudo-repo itself.
        // Note that BzlLoadFunctionTest and PackageLoadingFunctionTest already cover the general case
        // of a failure in retrieving the StarlarkBuiltinsValue. Here we mainly want to make sure the
        // stack trace is informative for errors that occur in dependencies of exports.bzl.
        scratch.file(
            "tools/builtins_staging/helper.bzl",  //
            "toplevels = {'overridable_symbol': 1//0}  # <-- dynamic error"
        )
        writeExportsBzl(
            "load('@_builtins//:helper.bzl', 'toplevels')",
            "exported_toplevels = toplevels",
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl()

        buildAndAssertFailure()
        assertContainsEvent(
            "File \"/workspace/tools/builtins_staging/helper.bzl\", line 1, column 37, in <toplevel>"
        )
        assertContainsEvent("Error: integer division by zero")

        // We assert only the parts of the message before and after the module name, since the module
        // identified by the message depends on whether or not the test environment has a prelude file.
        val ev: com.google.devtools.build.lib.events.Event =
            assertContainsEvent("Internal error while loading Starlark builtins")
        Truth.assertThat(ev.getMessage())
            .contains(
                ("Failed to load builtins sources: "
                        + "at /workspace/tools/builtins_staging/exports.bzl:1:6: "
                        + "initialization of module 'helper.bzl' (internal) failed")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorInProcessingExports() {
        // Test case with an error in the symbols exported by exports.bzl, but no actual Starlark errors
        // in the builtins files themselves.
        writeExportsBzl(
            "exported_toplevels = None",  // should be dict
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl()

        buildAndAssertFailure()

        // We assert only the parts of the message before and after the module name, since the module
        // identified by the message depends on whether or not the test environment has a prelude file.
        val ev: com.google.devtools.build.lib.events.Event =
            assertContainsEvent("Internal error while loading Starlark builtins")
        Truth.assertThat(ev.getMessage())
            .contains(
                "Failed to apply declared builtins: "
                        + "got NoneType for 'exported_toplevels dict', want dict"
            )
    }

    // TODO(#11437): Remove once disabling is not allowed.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun injectionDisabledByFlag() {
        writeExportsBzl(
            "exported_toplevels = {'overridable_symbol': 'new_value'}",
            "exported_rules = {'overridable_rule': 'new_rule'}",
            "exported_to_java = {}"
        )
        writePkgBuild("print('In BUILD: overridable_rule :: %s' % overridable_rule)")
        writePkgBzl(
            "print('In bzl: overridable_symbol :: %s' % overridable_symbol)",
            "print('In bzl: overridable_rule :: %s' % native.overridable_rule)"
        )
        setBuildLanguageOptions("--experimental_builtins_bzl_path=")

        buildAndAssertSuccess()
        assertContainsEvent("In bzl: overridable_symbol :: original_value")
        assertContainsEvent("In bzl: overridable_rule :: <built-in rule overridable_rule>")
        assertContainsEvent("In BUILD: overridable_rule :: <built-in rule overridable_rule>")
    }

    // TODO(#11437): Remove once disabling is not allowed.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exportsBzlMayBeInErrorWhenInjectionIsDisabled() {
        writeExportsBzl( //
            "PARSE ERROR"
        )
        writePkgBuild("print('In BUILD: overridable_rule :: %s' % overridable_rule)")
        writePkgBzl(
            "print('In bzl: overridable_symbol :: %s' % overridable_symbol)",
            "print('In bzl: overridable_rule :: %s' % native.overridable_rule)"
        )
        setBuildLanguageOptions("--experimental_builtins_bzl_path=")

        buildAndAssertSuccess()
        assertContainsEvent("In bzl: overridable_symbol :: original_value")
        assertContainsEvent("In bzl: overridable_rule :: <built-in rule overridable_rule>")
        assertContainsEvent("In BUILD: overridable_rule :: <built-in rule overridable_rule>")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtinsCanSeeOriginalNativeToplevels() {
        writeExportsBzl(
            "print('In builtins: overridable_symbol :: %s' % _builtins.toplevel.overridable_symbol)",
            "exported_toplevels = {'overridable_symbol': 'new_value'}",
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl("print('In bzl: overridable_symbol :: %s' % overridable_symbol)")

        buildAndAssertSuccess()
        assertContainsEvent("In builtins: overridable_symbol :: original_value")
        assertContainsEvent("In bzl: overridable_symbol :: new_value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtinsCanSeeOriginalNativeRules() {
        writeExportsBzl(
            "print('In builtins: overridable_rule :: %s' % _builtins.native.overridable_rule)",
            "exported_toplevels = {}",
            "exported_rules = {'overridable_rule': 'new_rule'}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl("print('In bzl: overridable_rule :: %s' % native.overridable_rule)")

        buildAndAssertSuccess()
        assertContainsEvent("In builtins: overridable_rule :: <built-in rule overridable_rule>")
        assertContainsEvent("In bzl: overridable_rule :: new_rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtinsCanSeeBuiltinsInternalSymbol() {
        writeExportsBzl(
            "print('internal_symbol :: %s' % _builtins.internal.internal_symbol)",
            "exported_toplevels = {}",  //
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl()

        buildAndAssertSuccess()
        assertContainsEvent("internal_symbol :: internal_value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun otherBzlsCannotSeeBuiltinsInternalSymbol() {
        writeExportsBzl(
            "exported_toplevels = {}",  //
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl("internal_symbol")

        buildAndAssertFailure()
        assertContainsEvent("name 'internal_symbol' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtinsCanSeeFlags_unset() {
        writeExportsBzl( // We use a None default here, but note that that's brittle if any machinery explicitly sets
            // flags to their default values. In practice the flag's real default value should be used.
            "print('experimental_builtins_dummy :: %s' % ",
            "      _builtins.get_flag('experimental_builtins_dummy', None))",
            "exported_toplevels = {}",  //
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl()

        buildAndAssertSuccess()
        assertContainsEvent("experimental_builtins_dummy :: None")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtinsCanSeeFlags_set() {
        writeExportsBzl(
            "print('experimental_builtins_dummy :: %s' % ",
            "      _builtins.get_flag('experimental_builtins_dummy', None))",
            "exported_toplevels = {}",  //
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl()

        setBuildLanguageOptionsWithBuiltinsStaging("--experimental_builtins_dummy=true")
        buildAndAssertSuccess()
        assertContainsEvent("experimental_builtins_dummy :: True")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtinsCanSeeFlags_doesNotExist() {
        writeExportsBzl( // We use a None default here, but note that that's brittle if any machinery explicitly sets
            // flags to their default values. In practice the flag's real default value should be used.
            "print('experimental_does_not_exist :: %s' % ",
            "      _builtins.get_flag('experimental_does_not_exist', None))",
            "exported_toplevels = {}",  //
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl()

        buildAndAssertSuccess()
        assertContainsEvent("experimental_does_not_exist :: None")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagGuardedSymbol_canExportEvenWhenDisabled() {
        writeExportsBzl(
            "exported_toplevels = {'flag_guarded_symbol': 'overridden value'}",
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl()

        // Default value of --experimental_builtins_dummy is false.
        buildAndAssertSuccess()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagGuardedSymbol_cannotUseWhenDisabledEvenIfInjected() {
        // Implementation note: Flag guarding is implemented at name resolution time, before builtins
        // injection is applied.
        writeExportsBzl(
            "exported_toplevels = {'flag_guarded_symbol': 'overridden value'}",
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl("print('flag_guarded_symbol :: %s' % flag_guarded_symbol)")

        buildAndAssertFailure()
        assertContainsEvent("flag_guarded_symbol is experimental")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagGuardedSymbol_injectedValueIsSeenWhenFlagIsEnabled() {
        writeExportsBzl(
            "exported_toplevels = {'flag_guarded_symbol': 'overridden value'}",
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl("print('flag_guarded_symbol :: %s' % flag_guarded_symbol)")

        setBuildLanguageOptionsWithBuiltinsStaging("--experimental_builtins_dummy=true")
        buildAndAssertSuccess()
        assertContainsEvent("flag_guarded_symbol :: overridden value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagGuardedSymbol_unconditionallyAccessibleToBuiltins() {
        writeExportsBzl(
            "print('flag_guarded_symbol :: %s' % _builtins.toplevel.flag_guarded_symbol)",
            "exported_toplevels = {}",  //
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl()

        buildAndAssertSuccess()
        assertContainsEvent("flag_guarded_symbol :: original_value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nativeRulesCanUseSymbolsFromBuiltins() {
        writeExportsBzl(
            "exported_toplevels = {}",
            "exported_rules = {}",
            "exported_to_java = {'builtins_defined_symbol': 'value_from_builtins'}"
        )
        scratch.file(
            "pkg/BUILD",  //
            "sandwich_rule(name = 'sandwich')"
        )

        getConfiguredTarget("//pkg:sandwich")
        assertContainsEvent("builtins_defined_symbol :: value_from_builtins")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nativeRuleFailsToFindUnknownBuiltin() {
        writeExportsBzl(
            "exported_toplevels = {}",  //
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        scratch.file(
            "pkg/BUILD",  //
            "sandwich_rule(name = 'sandwich')"
        )
        reporter.removeHandler(failFastHandler)

        getConfiguredTarget("//pkg:sandwich")
        assertContainsEvent("(Internal error) No symbol named 'builtins_defined_symbol'")
    }

    // TODO(#11437): Verify whether this works for native-defined aspects as well.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nativeRulesCanCallFunctionsDefinedInBuiltins() {
        writeExportsBzl( // The driver rule calls this helper twice with a list.
            "def func(arg):",
            "  print('got arg %s' % arg)",
            "  arg.append('blah')",
            "  return len(arg)",
            "exported_toplevels = {}",
            "exported_rules = {}",
            "exported_to_java = {'builtins_defined_logic': func}"
        )
        scratch.file(
            "pkg/BUILD",  //
            "sandwich_logic_rule(name = 'sandwich_logic')"
        )

        getConfiguredTarget("//pkg:sandwich_logic")
        assertContainsEvent("got arg []")
        assertContainsEvent("builtins_defined_logic call 1 :: 1")
        assertContainsEvent("got arg [\"blah\"]")
        assertContainsEvent("builtins_defined_logic call 2 :: 2")
        assertContainsEvent("final list value :: [\"blah\", \"blah\"]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nativeRulesCanPassCtxToBuiltinsDefinedHelpers() {
        writeExportsBzl(
            "def impl_helper(ctx):",
            "  ctx.actions.write(output=ctx.outputs.out, content=ctx.attr.content)",
            "exported_toplevels = {}",
            "exported_rules = {}",
            "exported_to_java = {'builtins_rule_impl_helper': impl_helper}"
        )
        scratch.file(
            "pkg/BUILD",  //
            "sandwich_ctx_rule(name = 'sandwich_ctx', content='foo', out='bar.txt')"
        )

        val target: ConfiguredTarget = getConfiguredTarget("//pkg:sandwich_ctx")
        val output: Artifact = getBinArtifact("bar.txt", target)
        val action: ActionAnalysisMetadata = getGeneratingAction(output)
        assertThat(action).isInstanceOf(FileWriteAction::class.java)
        assertThat((action as FileWriteAction).getFileContents()).isEqualTo("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nativeRulesCanDisplayUsefulStarlarkStackTrace() {
        writeExportsBzl( // The driver rule calls this helper twice with a list. Doesn't matter, we fail immediately.
            "def func(arg):",
            "  1//0",
            "exported_toplevels = {}",
            "exported_rules = {}",
            "exported_to_java = {'builtins_defined_logic': func}"
        )
        scratch.file(
            "pkg/BUILD",  //
            "sandwich_logic_rule(name = 'sandwich_logic')"
        )
        reporter.removeHandler(failFastHandler)

        getConfiguredTarget("//pkg:sandwich_logic")
        // Rule implementation uses callStarlarkOrThrowRuleError(), which includes the stack trace.
        assertContainsEvent("line 2, column 4, in func")
        assertContainsEvent("Error: integer division by zero")
    }

    // The following tests check the integration of the injection override flag with builtins
    // injection. See BazelStarlarkEnvironmentTest for more detailed unit tests about the semantics of
    // this flag.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun perSymbolInjectionOverride() {
        writeExportsBzl(
            "exported_toplevels = {'-overridable_symbol': 'new_value'}",
            "exported_rules = {'-overridable_rule': 'new_rule'}",
            "exported_to_java = {}"
        )
        writePkgBuild("print('In BUILD: overridable_rule :: %s' % overridable_rule)")
        writePkgBzl(
            "print('In bzl: overridable_symbol :: %s' % overridable_symbol)",
            "print('In bzl: overridable_rule :: %s' % native.overridable_rule)"
        )

        setBuildLanguageOptionsWithBuiltinsStaging(
            "--experimental_builtins_injection_override=+overridable_symbol,+overridable_rule"
        )
        buildAndAssertSuccess()
        assertContainsEvent("In bzl: overridable_symbol :: new_value")
        assertContainsEvent("In bzl: overridable_rule :: new_rule")
        assertContainsEvent("In BUILD: overridable_rule :: new_rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun perSymbolInjectionOverride_lastOccurrenceWins() {
        writeExportsBzl(
            "exported_toplevels = {'-overridable_symbol': 'new_value'}",
            "exported_rules = {'-overridable_rule': 'new_rule'}",
            "exported_to_java = {}"
        )
        writePkgBuild()
        writePkgBzl(
            "print('In bzl: overridable_symbol :: %s' % overridable_symbol)",
            "print('In bzl: overridable_rule :: %s' % native.overridable_rule)"
        )

        // Tests that the last use of foo determines whether it's +foo or -foo. Also tests that the flag
        // is allowMultiple, so that passing the second list doesn't just zero out the first list.
        setBuildLanguageOptionsWithBuiltinsStaging(
            "--experimental_builtins_injection_override="
                    + "+overridable_rule,+overridable_symbol,-overridable_symbol",
            "--experimental_builtins_injection_override=+overridable_symbol"
        )
        buildAndAssertSuccess()
        assertContainsEvent("In bzl: overridable_symbol :: new_value")
        assertContainsEvent("In bzl: overridable_rule :: new_rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun perSymbolInjectionOverride_invalidOverrideItem() {
        writeExportsBzl("exported_toplevels = {}", "exported_rules = {}", "exported_to_java = {}")
        writePkgBuild()
        writePkgBzl()

        setBuildLanguageOptionsWithBuiltinsStaging("--experimental_builtins_injection_override=foo")
        buildAndAssertFailure()
        assertContainsEvent("Invalid injection override item: 'foo'")
    }

    /**
     * Tests for injection, under inlining of [BzlLoadFunction].
     * 
     * 
     * See [BzlLoadFunction.computeInline] for an explanation of inlining.
     */
    @RunWith(JUnit4::class)
    class BuiltinsInjectionTestWithInlining : BuiltinsInjectionTest() {
        override fun usesInliningBzlLoadFunction(): Boolean {
            return true
        }
    }

    companion object {
        /** A simple dummy rule that doesn't do anything.  */
        private val OVERRIDABLE_RULE: MockRule = MockRule { MockRule.define("overridable_rule") }

        /**
         * A dummy native rule that reads a value from `@_builtins`.
         * 
         * 
         * It looks up the symbol "builtins_defined_symbol" in exported_to_java, and prints its value
         * to the event handler.
         */
        private val SANDWICH_RULE: MockRule =
            MockRule { MockRule.factory(SandwichFactory::class.java).define("sandwich_rule") }

        /**
         * A dummy native rule that runs `@_builtins`-defined code.
         * 
         * 
         * It looks up the function listed as "builtins_defined_logic" in exported_to_java, and calls
         * it twice on an initially empty list. It prints both return values and the final value of the
         * list. On Starlark evaluation error, it reports a rule error.
         */
        private val SANDWICH_LOGIC_RULE: MockRule =
            MockRule { MockRule.factory(SandwichLogicFactory::class.java).define("sandwich_logic_rule") }

        /**
         * A dummy native rule that passes a Starlark rule context (`ctx`) object to
         * `@_builtins`-defined code.
         * 
         * 
         * It looks up "builtins_rule_impl_helper" in exported_to_java, and calls it with `ctx`
         * as its sole arg. The rule has a "content" string attribute and "out" output label attribute.
         * The Starlark helper function is responsible for registering an action to generate the output.
         */
        private val SANDWICH_CTX_RULE: MockRule = MockRule {
            MockRule.factory(SandwichCtxFactory::class.java)
                .define(
                    "sandwich_ctx_rule",
                    Attribute.attr("content", Type.STRING),
                    Attribute.attr("out", BuildType.OUTPUT)
                )
        }
    }
}
