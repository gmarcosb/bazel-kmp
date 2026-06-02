// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.test.AnalysisFailure

/** Tests the execution of symbolic macro implementations.  */
@RunWith(TestParameterInjector::class)
class SymbolicMacroTest : BuildViewTestCase() {
    /**
     * Returns a package by the given name (no leading "//"), or null upon [ ].
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    private fun getPackage(pkgName: String?): Package? {
        try {
            return getPackageManager().getPackage(reporter, PackageIdentifier.createInMainRepo(pkgName))
        } catch (unused: NoSuchPackageException) {
            return null
        }
    }

    private fun assertPackageNotInError(pkg: Package?) {
        assertThat(pkg).isNotNull()
        assertThat(pkg.containsErrors()).isFalse()
    }

    /**
     * Convenience method for asserting that a package evaluates in error and produces an event
     * containing the given substring.
     * 
     * 
     * Note that this is not suitable for errors that occur during top-level .bzl evaluation (i.e.,
     * triggered by load() rather than during BUILD evaluation), since our test framework fails to
     * produce a result in that case (b/26382502).
     */
    @Throws(java.lang.Exception::class)
    private fun assertGetPackageFailsWithEvent(pkgName: String?, msg: String?) {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val pkg: Package? = getPackage(pkgName)
        assertThat(pkg).isNotNull()
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(msg)
    }

    /**
     * Convenience method for asserting that a package evaluates without error, but that the given
     * target cannot be configured due to violating macro naming rules.
     */
    @Throws(java.lang.Exception::class)
    private fun assertPackageLoadsButGetConfiguredTargetFailsMacroNamingCheck(
        pkgName: String?, macroName: String?, targetName: String?
    ) {
        val pkg: Package? = getPackage(pkgName)
        assertPackageNotInError(pkg)
        assertThat(pkg.getTargets()).containsKey(targetName)

        val labelString: String? = String.format("//%s:%s", pkgName, targetName)
        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget(labelString) })
        Truth.assertThat(error)
            .hasMessageThat()
            .contains(
                String.format(
                    "Target %s declared in symbolic macro '%s' violates macro naming rules",
                    labelString, macroName
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroCanBeDefinedUsingFactory() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            pass

        def macro_factory():
            return macro(implementation=_impl)

        my_macro = macro_factory()
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name = "abc")
        
        """.trimIndent()
        )

        assertPackageNotInError(getPackage("pkg"))
    }

    // Regression test for b/409532322
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroCannotBeDefinedInBuildFileThread() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            pass

        def macro_factory():
            return macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "macro_factory")
        my_macro = macro_factory()
        
        """.trimIndent()
        )

        assertGetPackageFailsWithEvent("pkg", "macro() can only be used during .bzl initialization")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun implementationIsInvokedWithNameParam() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            print("my_macro called with name = %s" % name)
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertContainsEvent("called with name = abc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun implementationFailsDueToBadSignature() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl():
            pass
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        assertGetPackageFailsWithEvent(
            "pkg", "_impl() got unexpected keyword arguments: name, visibility"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun implementationMustNotReturnAValue() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            return True
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        assertGetPackageFailsWithEvent("pkg", "macro 'abc' may not return a non-None value (got True)")
    }

    /**
     * Writes source files for package with a given name such that there is a macro by the given name
     * declaring a target by the given name.
     */
    @Throws(java.lang.Exception::class)
    private fun setupForMacroWithSingleTarget(pkgName: String?, macroName: String?, targetName: String?) {
        scratch.file(
            String.format("%s/foo.bzl", pkgName),
            String.format(
                """
            def _impl(name, visibility):
                native.filegroup(name="%s")
            my_macro = macro(implementation=_impl)
            
            """.trimIndent(),
                targetName
            )
        )
        scratch.file(
            String.format("%s/BUILD", pkgName),
            String.format(
                """
            load(":foo.bzl", "my_macro")
            my_macro(name="%s")
            
            """.trimIndent(),
                macroName
            )
        )
    }

    @org.junit.Test
    @TestParameters("{separator: '_'}", "{separator: '-'}", "{separator: '.'}")
    @Throws(java.lang.Exception::class)
    fun macroTargetName_canBeNamePlusSeparatorPlusSomething(separator: String?) {
        val targetName: String? = String.format("abc%slib", separator)
        setupForMacroWithSingleTarget("pkg", "abc", targetName)

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertThat(pkg.getTargets()).containsKey(targetName)
        assertThat(getConfiguredTarget(String.format("//pkg:%s", targetName))).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroTargetName_canBeJustNameForMainTarget() {
        setupForMacroWithSingleTarget("pkg", "abc", "abc")

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertThat(pkg.getTargets()).containsKey("abc")
        assertThat(getConfiguredTarget("//pkg:abc")).isNotNull()
        assertThat(pkg.getMacrosById()).containsKey("abc:1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroTargetName_cannotBeNonSuffixOfName() {
        setupForMacroWithSingleTarget("pkg", "abc", "xyz")

        assertPackageLoadsButGetConfiguredTargetFailsMacroNamingCheck("pkg", "abc", "xyz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroTargetName_cannotBeLibPrefixOfName() {
        setupForMacroWithSingleTarget("pkg", "abc", "libabc.so")

        assertPackageLoadsButGetConfiguredTargetFailsMacroNamingCheck("pkg", "abc", "libabc.so")
    }

    @org.junit.Test
    @TestParameters("{separator: ''}", "{separator: '@'}")
    @Throws(java.lang.Exception::class)
    fun macroTargetName_cannotBeInvalidSeparatorPlusSomething(separator: String?) {
        val targetName: String? = String.format("abc%sxyz", separator)
        setupForMacroWithSingleTarget("pkg", "abc", targetName)

        assertPackageLoadsButGetConfiguredTargetFailsMacroNamingCheck("pkg", "abc", targetName)
    }

    @org.junit.Test
    @TestParameters("{separator: '_'}", "{separator: '-'}", "{separator: '.'}")
    @Throws(java.lang.Exception::class)
    fun macroTargetName_cannotBeNamePlusSeparatorPlusNothing(separator: String) {
        val targetName = "abc" + separator
        setupForMacroWithSingleTarget("pkg", "abc", targetName)

        assertPackageLoadsButGetConfiguredTargetFailsMacroNamingCheck("pkg", "abc", targetName)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun illegallyNamedExportsFilesDoNotBreakPackageLoadingButCannotBeConfigured() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            # valid names
            native.exports_files(srcs=["abc_txt"])
            native.exports_files(srcs=["abc-txt"])
            native.exports_files(srcs=["abc.txt"])
            # allowed during package loading, but cannot be configured
            native.exports_files(srcs=["xyz.txt"])
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        assertPackageLoadsButGetConfiguredTargetFailsMacroNamingCheck("pkg", "abc", "xyz.txt")
        assertThat(getConfiguredTarget("//pkg:abc_txt")).isNotNull()
        assertThat(getConfiguredTarget("//pkg:abc-txt")).isNotNull()
        assertThat(getConfiguredTarget("//pkg:abc.txt")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun illegallyNamedOutputsDoNotBreakPackageLoadingButCannotBeConfigured() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _my_rule_impl(ctx):
            ctx.actions.write(ctx.outputs.out1, "")
            ctx.actions.write(ctx.outputs.out2, "")
            ctx.actions.write(ctx.outputs.out3, "")
            ctx.actions.write(ctx.outputs.out4, "")
            return []
        my_rule = rule(
            implementation = _my_rule_impl,
            outputs = {
              # valid names
              "out1": "%{name}_out1",
              "out2": "%{name}-out2",
              "out3": "%{name}.out3",
              # allowed during package loading, but cannot be configured
              "out4": "lib%{name}.so",
            },
        )
        def _my_macro_impl(name, visibility):
            my_rule(name=name)
        my_macro = macro(implementation=_my_macro_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        assertPackageLoadsButGetConfiguredTargetFailsMacroNamingCheck("pkg", "abc", "libabc.so")
        assertThat(getConfiguredTarget("//pkg:abc")).isNotNull()
        assertThat(getConfiguredTarget("//pkg:abc_out1")).isNotNull()
        assertThat(getConfiguredTarget("//pkg:abc-out2")).isNotNull()
        assertThat(getConfiguredTarget("//pkg:abc.out3")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun illegallyNamedTargetsProvideAnalysisFailureInfo() {
        useConfiguration("--allow_analysis_failures=true")
        setupForMacroWithSingleTarget("pkg", "abc", "libabc.so")
        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)

        val target: ConfiguredTarget? = getConfiguredTarget("//pkg:libabc.so")
        val info: AnalysisFailureInfo =
            target.get(AnalysisFailureInfo.provider.getKey()) as AnalysisFailureInfo
        val failure: AnalysisFailure = info.getCauses().getSet(AnalysisFailure::class.java).toList().get(0)
        com.google.common.truth.Subject.contains(
            "Target //pkg:libabc.so declared in symbolic macro 'abc' violates macro naming rules"
                    + " and cannot be built."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetOutsideMacroMayInvadeMacroNamespace() {
        // Targets outside a macro may have names that would be valid for targets inside the macro.
        // This is not an error so long as no actual target inside the macro clashes on that name.

        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            native.filegroup(name = name + "_inside_macro")
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        filegroup(name = "abc_outside_macro")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertThat(pkg.getTargets().keySet()).containsAtLeast("abc_inside_macro", "abc_outside_macro")
        assertThat(pkg.getMacrosById()).containsKey("abc:1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetOutsideMacroMayNotClashWithTargetInsideMacro() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            native.filegroup(name = name + "_target")
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        filegroup(name = "abc_target")
        
        """.trimIndent()
        )

        assertGetPackageFailsWithEvent(
            "pkg", "filegroup rule 'abc_target' conflicts with existing filegroup rule"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroCanReferToInputFile() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            native.filegroup(
                name = name,
                srcs = [
                    "explicit_input.cc",
                    # This usage does not cause implicit_input.cc to be created since we're inside
                    # a symbolic macro. We force the input's creation by referring to it from bar
                    # in the BUILD file.
                    "implicit_input.cc",
                ],
            )
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        exports_files(["explicit_input.cc"])
        filegroup(name = "bar", srcs = ["implicit_input.cc"])
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertThat(pkg.getTargets()).containsKey("abc")
        assertThat(pkg.getTargets()).containsKey("implicit_input.cc")
        assertThat(pkg.getTargets()).containsKey("explicit_input.cc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroCannotForceCreationOfImplicitInputFileOnItsOwn() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _sub_impl(name, visibility):
            native.filegroup(
                name = name + "_target",
                srcs = ["implicit_input.cc"],
            )

        my_submacro = macro(implementation=_sub_impl)

        def _impl(name, visibility):
            native.filegroup(
                name = name + "_target",
                srcs = ["implicit_input.cc"],
            )
            my_submacro(name = name + "_submacro")
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        // Confirm that implicit_input.cc is not a target of the package, despite being used inside a
        // symbolic macro (and not by anything at the top level) for both a target and a submacro.
        //
        // It'd be an execution time error to attempt to build the declared rule targets, but the
        // package still loads just fine.
        assertPackageNotInError(pkg)
        assertThat(pkg.getTargets()).containsKey("abc_target")
        assertThat(pkg.getTargets()).containsKey("abc_submacro_target")
        assertThat(pkg.getTargets()).doesNotContainKey("implicit_input.cc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroCanDeclareSubmacros() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _inner_impl(name, visibility):
            native.filegroup(name = name + "_lib")
        inner_macro = macro(implementation=_inner_impl)
        def _impl(name, visibility):
            inner_macro(name = name + "_inner")
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertThat(pkg.getTargets()).containsKey("abc_inner_lib")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun submacroNameMustFollowPrefixNamingConvention() {
        scratch.file("pkg/BUILD")
        scratch.file(
            "pkg/foo.bzl",
            """
        def _inner_impl(name, visibility):
            pass
        inner_macro = macro(implementation=_inner_impl)
        def _impl(name, visibility, sep):
            inner_macro(name = name + sep + "inner")
        my_macro = macro(implementation=_impl, attrs={"sep": attr.string(configurable=False)})
        
        """.trimIndent()
        )
        scratch.file(
            "good/BUILD",
            """
        load("//pkg:foo.bzl", "my_macro")
        my_macro(name="abc", sep = "_")  # ok
        my_macro(name="def", sep = "-")  # ok
        my_macro(name="ghi", sep = ".")  # ok
        
        """.trimIndent()
        )
        scratch.file(
            "bad/BUILD",
            """
        load("//pkg:foo.bzl", "my_macro")
        my_macro(name="jkl", sep = "${'$'}")  # bad
        
        """.trimIndent()
        )

        val good: Package? = getPackage("good")
        assertPackageNotInError(good)
        assertGetPackageFailsWithEvent("bad", "macro 'jkl' cannot declare submacro named 'jkl\$inner'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun submacroMayHaveSameNameAsAncestorMacros() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _inner_impl(name, visibility):
            native.filegroup(name = name)
        inner_macro = macro(implementation=_inner_impl)

        def _middle_impl(name, visibility):
            inner_macro(name = name)
        middle_macro = macro(implementation=_middle_impl)

        def _outer_impl(name, visibility):
            middle_macro(name = name)
        outer_macro = macro(implementation = _outer_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "outer_macro")
        outer_macro(name="abc")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertThat(pkg.getTargets()).containsKey("abc")
        assertThat(pkg.getMacrosById()).containsKey("abc:1")
        assertThat(pkg.getMacrosById()).containsKey("abc:2")
        assertThat(pkg.getMacrosById()).containsKey("abc:3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cannotHaveTwoMainTargets() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            native.filegroup(name = name)
            native.filegroup(name = name)
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        assertGetPackageFailsWithEvent(
            "pkg", "filegroup rule 'abc' conflicts with existing filegroup rule"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cannotHaveTwoMainSubmacros() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _inner_impl(name, visibility):
            pass
        inner_macro = macro(implementation=_inner_impl)

        def _impl(name, visibility):
            inner_macro(name = name)
            inner_macro(name = name)
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        assertGetPackageFailsWithEvent(
            "pkg", "macro 'abc' conflicts with an existing macro (and was not created by it)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cannotHaveBothMainTargetAndMainSubmacro_submacroDeclaredFirst() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _inner_impl(name, visibility):
            # Don't define a main target; we don't want to trigger a name conflict between this and
            # the outer target.
            pass
        inner_macro = macro(implementation=_inner_impl)

        def _impl(name, visibility):
            inner_macro(name = name)
            native.filegroup(name = name)
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        assertGetPackageFailsWithEvent(
            "pkg", "target 'abc' conflicts with an existing macro (and was not created by it)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cannotHaveBothMainTargetAndMainSubmacro_targetDeclaredFirst() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _inner_impl(name, visibility):
            # Don't define a main target; we don't want to trigger a name conflict between this and
            # the outer target.
            pass
        inner_macro = macro(implementation=_inner_impl)

        def _impl(name, visibility):
            native.filegroup(name = name)
            inner_macro(name = name)
        my_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        assertGetPackageFailsWithEvent("pkg", "macro 'abc' conflicts with an existing target")
    }

    /**
     * Implementation of a test that ensures a given API cannot be called from inside a symbolic
     * macro.
     */
    @Throws(java.lang.Exception::class)
    private fun doCannotCallApiTest(apiName: String?, usageLine: String?, errorMessageParticiple: String? = "used") {
        scratch.file(
            "pkg/foo.bzl",
            String.format(
                """
            def _impl(name, visibility):
                %s
            my_macro = macro(implementation=_impl)
            
            """.trimIndent(),
                usageLine
            )
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        assertGetPackageFailsWithEvent(
            "pkg",
            String.format( // The error also has one of the following suffixes:
                //   - " or a legacy macro"
                //   - ", a rule finalizer, a legacy macro, or a WORKSPACE file"
                "%s can only be %s while evaluating a BUILD file", apiName, errorMessageParticiple
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroCannotCallPackage() {
        doCannotCallApiTest(
            "package()", "native.package(default_visibility = ['//visibility:public'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroCannotCallGlob() {
        doCannotCallApiTest("glob()", "native.glob(['foo*'])")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroCannotCallSubpackages() {
        doCannotCallApiTest("subpackages()", "native.subpackages(include = ['*'])")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroCannotCallExistingRule() {
        doCannotCallApiTest("existing_rule()", "native.existing_rule('foo')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroCannotCallExistingRules() {
        doCannotCallApiTest("existing_rules()", "native.existing_rules()")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macroCannotCallEnvironmentRuleFunction() {
        doCannotCallApiTest("environment rule", "native.environment(name = 'foo')", "instantiated")
    }

    // There are other symbols that must not be called from within symbolic macros, but we don't test
    // them because they can't be obtained from a symbolic macro implementation anyway, since they are
    // not under `native` (at least, for BUILD-loaded .bzl files) and because symbolic macros can't
    // take arbitrary parameter types from their caller. These untested symbols include:
    //
    //  - For BUILD threads: licenses(), environment_group()
    //  - For WORKSPACE threads: workspace(), register_toolchains(), register_execution_platforms(),
    //    bind(), and repository rules.
    //
    // Starlark-defined repository rules might technically be callable but we skip over that edge
    // case here.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRules_canSeeTargetsCreatedByOrdinaryMacros() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            native.filegroup(name = name + "_lib")
        my_macro = macro(implementation=_impl)
        def query():
            print("existing_rules() keys: %s" % native.existing_rules().keys())
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro", "query")
        filegroup(name = "outer_target")
        my_macro(name="abc")
        query()
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertContainsEvent("existing_rules() keys: [\"outer_target\", \"abc_lib\"]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun existingRules_cannotSeeTargetsCreatedByFinalizers() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            native.filegroup(name = name + "_lib")
        my_macro = macro(implementation=_impl, finalizer=True)
        def query():
            print("existing_rules() keys: %s" % native.existing_rules().keys())
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro", "query")
        filegroup(name = "outer_target")
        my_macro(name="abc")
        query()
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertContainsEvent("existing_rules() keys: [\"outer_target\"]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hardcodedDefaultAttrValue_isUsedWhenNotOverriddenAndAttrHasNoUserSpecifiedDefault() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, dep_nonconfigurable, dep_configurable, xyz_configurable):
            print("dep_nonconfigurable is %s" % dep_nonconfigurable)
            print("dep_configurable is %s" % dep_configurable)
            print("xyz_configurable is %s" % xyz_configurable)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              # Test label type, since LabelType#getDefaultValue returns null.
              "dep_nonconfigurable": attr.label(configurable=False),
              # Try it again, this time configurable. Select()-promotion doesn't apply to None.
              "dep_configurable": attr.label(),
              # Now do it for a value besides None. Select()-promotion applies.
              "xyz_configurable": attr.string(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertContainsEvent("dep_nonconfigurable is None")
        assertContainsEvent("dep_configurable is None")
        assertContainsEvent("xyz_configurable is select({\"//conditions:default\": \"\"})")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultAttrValue_isUsedWhenNotOverridden() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, xyz):
            print("xyz is %s" % xyz)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.string(default="DEFAULT", configurable=False)
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertContainsEvent("xyz is DEFAULT")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultAttrValue_canBeOverridden() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, xyz):
            print("xyz is %s" % xyz)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.string(default="DEFAULT", configurable=False)
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = "OVERRIDDEN",
        )
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertContainsEvent("xyz is OVERRIDDEN")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultAttrValue_isUsed_whenAttrIsImplicit() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, _xyz):
            print("xyz is %s" % _xyz)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "_xyz": attr.string(default="IMPLICIT", configurable=False)
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertContainsEvent("xyz is IMPLICIT")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultAttrValue_wrappingMacroTakesPrecedenceOverWrappedRule() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _rule_impl(ctx):
            pass

        my_rule = rule(
            implementation = _rule_impl,
            attrs = {"dep": attr.label(default="//common:rule_default")},
        )

        def _macro_impl(name, visibility, dep):
            my_rule(name = name, dep = dep)

        my_macro = macro(
            implementation = _macro_impl,
            attrs = {"dep": attr.label(default="//common:macro_default")},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name="abc")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        val rule: Rule = pkg.getRule("abc")
        assertThat(rule).isNotNull()
        assertThat(rule.getAttr("dep"))
            .isEqualTo(Label.parseCanonicalUnchecked("//common:macro_default"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noneAttrValue_doesNotOverrideDefault() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, xyz):
            print("xyz is %s" % xyz)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.string(default="DEFAULT", configurable=False)
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = None,
        )
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertContainsEvent("xyz is DEFAULT")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noneAttrValue_doesNotSatisfyMandatoryRequirement() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            pass
        my_macro = macro(
            implementation = _impl,
            attrs = {
                "xyz": attr.string(mandatory=True),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = None,
        )
        
        """.trimIndent()
        )

        assertGetPackageFailsWithEvent(
            "pkg", "missing value for mandatory attribute 'xyz' in 'my_macro' macro"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noneAttrValue_disallowedWhenAttrDoesNotExist() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility):
            pass
        my_macro = macro(
            implementation = _impl,
            attrs = {
                "xzz": attr.string(doc="This attr is public"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = None,
        )
        
        """.trimIndent()
        )

        assertGetPackageFailsWithEvent(
            "pkg", "no such attribute 'xyz' in 'my_macro' macro (did you mean 'xzz'?)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stringAttrsAreConvertedToLabelsAndInRightContext() {
        scratch.file("lib/BUILD")
        scratch.file(
            "lib/foo.bzl",
            """
        def _impl(name, visibility, xyz, _xyz):
            print("xyz is %s" % xyz)
            print("_xyz is %s" % _xyz)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.label(configurable = False),
              "_xyz": attr.label(default=":BUILD", configurable=False)
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//lib:foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = ":BUILD",  # Should be parsed relative to //pkg, not //lib
        )
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertContainsEvent("xyz is @@//pkg:BUILD")
        assertContainsEvent("_xyz is @@//lib:BUILD")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cannotMutateAttrValues() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, xyz):
            xyz.append(4)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.int_list(configurable=False),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = [1, 2, 3],
        )
        
        """.trimIndent()
        )

        assertGetPackageFailsWithEvent("pkg", "Error in append: trying to mutate a frozen list value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun attrsAllowSelectsByDefault() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, xyz):
            print("xyz is %s" % xyz)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.string(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = select({"//some:condition": ":target1", "//some:other_condition": ":target2"}),
        )
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertContainsEvent(
            "xyz is select({Label(\"@@//some:condition\"): \":target1\","
                    + " Label(\"@@//some:other_condition\"): \":target2\"})"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noneAttrValue_canAppearInSelects() {
        // None can appear as a value in a select() entry, and its meaning is "in this case, use
        // whatever default would've been chosen if this attribute weren't specified" -- i.e. the same
        // behavior as when None is used as the whole attribute value.
        //
        // The three cases for using a default value are 1) the attribute schema specifies a default, or
        // else 2) the attribute type specifies a hardcoded default that is a valid value for the type
        // (e.g. the empty string for StringType), or 3) the attribute type uses null as its hardcoded
        // default, which we represent in Starlark as None at rule analysis time. We exercise all three
        // cases here.
        scratch.file(
            "pkg/foo.bzl",
            """
def _impl(name, visibility, attr_using_schema_default, attr_using_hardcoded_nonnull_default,
          attr_using_hardcoded_null_default):
    print("attr_using_schema_default is %s" % attr_using_schema_default)
    print("attr_using_hardcoded_nonnull_default is %s"
              % attr_using_hardcoded_nonnull_default)
    print("attr_using_hardcoded_null_default is %s" % attr_using_hardcoded_null_default)
my_macro = macro(
    implementation = _impl,
    attrs = {
      "attr_using_schema_default": attr.string(default="some_default"),
      "attr_using_hardcoded_nonnull_default": attr.string(),
      "attr_using_hardcoded_null_default": attr.label(),
    },
)

""".trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            attr_using_schema_default = select({
                "//common:some_configsetting": None,
                "//conditions:default": None,
            }),
            attr_using_hardcoded_nonnull_default = select({
                "//common:some_configsetting": None,
                "//conditions:default": None,
            }),
            attr_using_hardcoded_null_default = select({
                "//common:some_configsetting": None,
                "//conditions:default": None,
            }),
        )
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        // From the macro implementation's point of view, the select() entries are still None,
        // regardless of how they are represented and transformed internally.
        assertContainsEvent(
            """
attr_using_schema_default is select({Label("@@//common:some_configsetting"): None, Label("@@//conditions:default"): None})
""".trimIndent()
        )
        assertContainsEvent(
            """
attr_using_hardcoded_nonnull_default is select({Label("@@//common:some_configsetting"): None, Label("@@//conditions:default"): None})
""".trimIndent()
        )
        assertContainsEvent(
            """
attr_using_hardcoded_null_default is select({Label("@@//common:some_configsetting"): None, Label("@@//conditions:default"): None})
""".trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun configurableAttrValuesArePromotedToSelects() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility,
                  configurable_xyz, nonconfigurable_xyz, configurable_default_xyz):
            print("configurable_xyz is '%s' (type %s)" %
                (str(configurable_xyz), type(configurable_xyz)))
            print("nonconfigurable_xyz is '%s' (type %s)" %
                    (str(nonconfigurable_xyz), type(nonconfigurable_xyz)))
            print("configurable_default_xyz is '%s' (type %s)" %
                (str(configurable_default_xyz), type(configurable_default_xyz)))

        my_macro = macro(
            implementation = _impl,
            attrs = {
              "configurable_xyz": attr.string(),
              "nonconfigurable_xyz": attr.string(configurable=False),
              "configurable_default_xyz": attr.string(default = "xyz"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            configurable_xyz = "configurable",
            nonconfigurable_xyz = "nonconfigurable",
            # configurable_default_xyz not set
        )
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertContainsEvent(
            "configurable_xyz is 'select({\"//conditions:default\": \"configurable\"})' (type select)"
        )
        assertContainsEvent("nonconfigurable_xyz is 'nonconfigurable' (type string)")
        assertContainsEvent(
            "configurable_default_xyz is 'select({\"//conditions:default\": \"xyz\"})' (type select)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonconfigurableAttrValuesProhibitSelects() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, xyz):
            print("xyz is %s" % xyz)
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.string(configurable=False),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = select({"//some:condition": ":target1", "//some:other_condition": ":target2"}),
        )
        
        """.trimIndent()
        )

        assertGetPackageFailsWithEvent("pkg", "attribute \"xyz\" is not configurable")
    }

    // TODO(b/331193690): Prevent selects from being evaluated as bools
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectableAttrCanBeEvaluatedAsBool() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, xyz):
            # Allowed for now when xyz is a select().
            # In the future, we'll ban implicit conversion and only allow
            # if there's an explicit bool(xyz).
            if xyz:
              print("xyz evaluates to True")
            else:
              print("xyz evaluates to False")

        my_macro = macro(
            implementation = _impl,
            attrs = {
              "xyz": attr.string(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            xyz = select({"//conditions:default" :"False"}),
        )
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertContainsEvent("xyz evaluates to True")
        assertDoesNotContainEvent("xyz evaluates to False")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun labelVisitation() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, **kwargs):
            pass
        my_macro = macro(
            implementation = _impl,
            attrs = {
              "singular": attr.label(configurable=False),
              "list": attr.label_list(configurable=False),
              "not_a_label": attr.string_list(configurable=False),
              "output": attr.output(),  # (always nonconfigurable)
              "configurable": attr.label(),
              "configurable_withdefault": attr.label(default="//common:configurable_withdefault"),
              # These are not passed in below.
              "omitted": attr.label(configurable=False),
              "_implicit_default": attr.label(default="//common:implicit_default"),
              "explicit_default": attr.label(default="//common:explicit_default"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(
            name = "abc",
            singular = "//A:A",
            list = ["//A:A", "//B:B"], # duplicate with previous attr
            not_a_label = ["qwerty"],
            output = "out.txt",
            configurable = select({
                "//Q:cond1": "//C:C",
                "//Q:cond2": None,
                "//conditions:default": "//D:D",
            }),
            configurable_withdefault = select({"//Q:cond": "//E:E", "//conditions:default": None}),
            visibility = ["//common:my_package_group"],
        )
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        val macroInstance: MacroInstance = getMacroById(pkg, "abc:1")
        val labels: java.util.ArrayList<Label?> = java.util.ArrayList<Label?>()
        macroInstance.visitExplicitAttributeLabels(labels::add)
        // Order is the same as the attribute definition order.
        Truth.assertThat(asStringList(labels))
            .containsExactly(
                "//A:A",
                "//A:A",  // duplicate not pruned
                "//B:B",  // `not_a_label` and `output` are skipped
                "//C:C",  // //Q:cond2 maps to default, which doesn't exist for that attr
                "//D:D",
                "//E:E",
                "//common:configurable_withdefault",  // from attr default
                // `omitted` ignored, it has no default
                // `_implicit_default` ignored because it's implicit
                "//common:explicit_default" // from attr default
                // `visibility` ignored, it's a NODEP label list
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun macrosThreadVisibilityAttrThroughWithCallsiteLocationAdded() {
        // Don't use test machinery's convenience setup that makes everything public by default.
        setPackageOptions("--default_visibility=private")

        // Submacro defines two targets, one exported (visibility = visibility) and one internal
        // (private to the submacro's package).
        scratch.file("lib1/BUILD")
        scratch.file(
            "lib1/macro.bzl",
            """
        def _impl(name, visibility):
            native.filegroup(
                name = name + "_exported",
                visibility = visibility)
            native.filegroup(name = name + "_internal")

        submacro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        // Outer macro also defines two targets, but in addition calls the submacro twice, as an
        // exported submacro and an internal one. So a total of six targets across three macro
        // instances.
        scratch.file("lib2/BUILD")
        scratch.file(
            "lib2/macro.bzl",
            """
        load("//lib1:macro.bzl", "submacro")

        def _impl(name, visibility):
            native.filegroup(
                name = name + "_exported",
                visibility = visibility)
            native.filegroup(name = name + "_internal")
            submacro(name=name + "_subexported", visibility = visibility)
            submacro(name=name + "_subinternal")


        outer_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//lib2:macro.bzl", "outer_macro")
        outer_macro(name="abc")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)

        // Outer macro visible to BUILD file.
        Truth.assertThat(getMacroVisibility(pkg, "abc:1")).containsExactly("//pkg:__pkg__")

        // The outer macro's exported target and the exported submacro are visible to both the BUILD
        // file and the outer macro.
        Truth.assertThat(getTargetVisibility(pkg, "abc_exported"))
            .containsExactly("//pkg:__pkg__", "//lib2:__pkg__")
        Truth.assertThat(getMacroVisibility(pkg, "abc_subexported:1"))
            .containsExactly("//pkg:__pkg__", "//lib2:__pkg__")

        // The outer macro's internal target and the internal submacro are visible only to the outer
        // macro.
        Truth.assertThat(getTargetVisibility(pkg, "abc_internal")).containsExactly("//lib2:__pkg__")
        Truth.assertThat(getMacroVisibility(pkg, "abc_subinternal:1")).containsExactly("//lib2:__pkg__")

        // The exported submacro's exported target is visible to everything (exports all the way down).
        Truth.assertThat(getTargetVisibility(pkg, "abc_subexported_exported"))
            .containsExactly("//pkg:__pkg__", "//lib2:__pkg__", "//lib1:__pkg__")

        // The internal submacro's exported target is visible to the outer macro and submacro.
        Truth.assertThat(getTargetVisibility(pkg, "abc_subinternal_exported"))
            .containsExactly("//lib2:__pkg__", "//lib1:__pkg__")

        // Finally, the internal targets of both the exported and internal submacros are visible only to
        // the submacro.
        Truth.assertThat(getTargetVisibility(pkg, "abc_subexported_internal"))
            .containsExactly("//lib1:__pkg__")
        Truth.assertThat(getTargetVisibility(pkg, "abc_subinternal_internal"))
            .containsExactly("//lib1:__pkg__")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultVisibilityAppliesOnlyToTopLevelMacros() {
        scratch.file("lib/BUILD")
        scratch.file(
            "lib/macro.bzl",
            """
        def _sub_impl(name, visibility):
            pass

        submacro = macro(implementation=_sub_impl)

        def _impl(name, visibility):
            submacro(name=name + "_sub")

        outer_macro = macro(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//lib:macro.bzl", "outer_macro")

        package(default_visibility=["//defaulted:__pkg__"])

        outer_macro(
            name = "macro_with_explicit_vis",
            visibility = ["//explicit:__pkg__"],
        )
        outer_macro(name="macro_without_explicit_vis")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)

        // Default visibility only applies when visibility is not specified.
        Truth.assertThat(getMacroVisibility(pkg, "macro_with_explicit_vis:1"))
            .containsExactly("//explicit:__pkg__", "//pkg:__pkg__")
        // When it does apply, we still append the callsite location.
        Truth.assertThat(getMacroVisibility(pkg, "macro_without_explicit_vis:1"))
            .containsExactly("//defaulted:__pkg__", "//pkg:__pkg__")
        // Default visibility never applies inside a symbolic macro (i.e. to submacros).
        Truth.assertThat(getMacroVisibility(pkg, "macro_with_explicit_vis_sub:1"))
            .containsExactly("//lib:__pkg__")
        Truth.assertThat(getMacroVisibility(pkg, "macro_without_explicit_vis_sub:1"))
            .containsExactly("//lib:__pkg__")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun wrongKeyTypeInAttrsDict_detected() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _impl,
            attrs = {123: attr.string()},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name = "abc")
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getPackage("pkg")).isNull()
        assertContainsEvent("got dict<int, Attribute> for 'attrs', want dict<string, Attribute|None>")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun wrongKeyValueInAttrsDict_detected() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _impl,
            attrs = {"bad attr": None},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name = "abc")
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getPackage("pkg")).isNull()
        assertContainsEvent("attribute name `bad attr` is not a valid identifier")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun wrongValueTypeInAttrsDict_detected() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _impl,
            attrs = {"bad": 123},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name = "abc")
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getPackage("pkg")).isNull()
        assertContainsEvent("got dict<string, int> for 'attrs', want dict<string, Attribute|None>")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noneValueInAttrsDict_ignored() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _impl,
            attrs = {"disabled_attr": None},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name = "abc")
        
        """.trimIndent()
        )
        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertMacroDoesNotHaveAttributes(
            getMacroById(pkg, "abc:1"),
            com.google.common.collect.ImmutableList.of<String?>("disabled_attr")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inheritAttrs_fromInvalidSource_fails() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _my_macro_impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _my_macro_impl,
            inherit_attrs = "???",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getPackage("pkg")).isNull()
        assertContainsEvent(
            "Invalid 'inherit_attrs' value \"???\"; expected a rule, a macro, or \"common\""
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inheritAttrs_withoutKwargsInImplementation_fails() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _my_macro_impl(name, visibility, tags):
            pass

        my_macro = macro(
            implementation = _my_macro_impl,
            inherit_attrs = "common"
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getPackage("pkg")).isNull()
        assertContainsEvent(
            "If inherit_attrs is set, implementation function must have a **kwargs parameter"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inheritAttrs_fromCommon_withOverrides() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _my_macro_impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _my_macro_impl,
            attrs = {
                # add a new attr
                "new_attr": attr.string(),
                # override an inherited attr
                "tags": attr.string_list(default = ["foo"]),
                # remove an inherited attr
                "features": None,
            },
            inherit_attrs = "common",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "abc")
        
        """.trimIndent()
        )
        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        // inherited attrs
        val macroInstance: MacroInstance = getMacroById(pkg, "abc:1")
        assertMacroHasAttributes(
            macroInstance,
            com.google.common.collect.ImmutableList.of<String?>("compatible_with", "testonly", "toolchains")
        )
        // overridden attr
        assertThat(
            macroInstance
                .getMacroClass()
                .getAttributeProvider()
                .getAttributeByName("tags").defaultValueUnchecked
        )
            .isEqualTo(com.google.common.collect.ImmutableList.of<String?>("foo"))
        // non-inherited attr
        assertMacroDoesNotHaveAttributes(macroInstance, com.google.common.collect.ImmutableList.of<String?>("features"))
        // internal public attrs which macro machinery must avoid inheriting
        assertMacroDoesNotHaveAttributes(
            macroInstance,
            com.google.common.collect.ImmutableList.of<String?>(
                "generator_name",
                "generator_location",
                "generator_function"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inheritAttrs_fromAnyNativeRule() {
        // Ensure that a symbolic macro can inherit attributes from (and thus, can conveniently wrap)
        // any native rule. Native rules may use attribute definitions which are unavailable to Starlark
        // rules, so to verify that we handle the native attribute corner cases, we exhaustively test
        // wrapping of all builtin rule classes which are accessible from Starlark. We do not test rule
        // classes which are exposed to Starlark via macro wrappers in @_builtins, because Starlark code
        // typically cannot get at the wrapped native rule's rule class symbol from which to inherit
        // attributes. We also do not test rule target instantiation (and thus, do not test whether such
        // a target would pass analysis) because declaring arbitrary native rule targets is difficult to
        // automate.
        //
        // This test is expected to fail if:
        // * a native rule adds a mandatory attribute of a type which is not supported by this test's
        //   fakeMandatoryArgs mechanism below (to fix, add support for it to fakeMandatoryArgs); or
        // * a new AttributeValueSource or a new attribute type is introduced, and symbolic macros
        //   cannot inherit an attribute with a default with this source or of such a type (to fix, add
        //   a check for it in MacroClass#forceDefaultToNone).
        for (ruleClass in getBuiltinRuleClasses(false)) {
            if (ruleClass.getAttributeProvider().getAttributes().isEmpty()) {
                continue
            }
            if (!(ruleClass.getRuleClassType().equals(RuleClass.Builder.RuleClassType.NORMAL)
                        || ruleClass.getRuleClassType().equals(RuleClass.Builder.RuleClassType.TEST))
            ) {
                continue
            }
            val pkgName = "pkg_" + ruleClass.getName()
            val macroName = "my_" + ruleClass.getName()
            // Provide fake values for mandatory attributes in macro invocation
            val fakeMandatoryArgs: java.lang.StringBuilder = java.lang.StringBuilder()
            for (attr in ruleClass.getAttributeProvider().getAttributes()) {
                var fakeValue: String? = null
                if (attr.isPublic() && attr.isMandatory() && !attr.name.equals("name")) {
                    val type: Type<*> = attr.getType()
                    if (type.equals(Type.STRING)
                        || type.equals(BuildType.OUTPUT)
                        || type.equals(BuildType.LABEL)
                        || type.equals(BuildType.NODEP_LABEL)
                        || type.equals(BuildType.DORMANT_LABEL)
                        || type.equals(BuildType.GENQUERY_SCOPE_TYPE)
                    ) {
                        fakeValue = "\":fake\""
                    } else if (type.equals(Types.STRING_LIST)
                        || type.equals(BuildType.OUTPUT_LIST)
                        || type.equals(BuildType.LABEL_LIST)
                        || type.equals(BuildType.NODEP_LABEL_LIST)
                        || type.equals(BuildType.DORMANT_LABEL_LIST)
                        || type.equals(BuildType.GENQUERY_SCOPE_TYPE_LIST)
                    ) {
                        fakeValue = "[\":fake\"]"
                    } else if (type.equals(BuildType.LABEL_DICT_UNARY)
                        || type.equals(BuildType.LABEL_KEYED_STRING_DICT)
                    ) {
                        fakeValue = "{\":fake\": \":fake\"}"
                    }
                }
                if (fakeValue != null) {
                    fakeMandatoryArgs.append(", ").append(attr.name).append(" = ").append(fakeValue)
                }
            }

            scratch.file(
                pkgName + "/macro.bzl",
                java.lang.String.format(
                    """
              def _impl(name, visibility, **kwargs):
                  pass

              %s = macro(
                  implementation = _impl,
                  inherit_attrs = native.%s,
              )
              
              """.trimIndent(),
                    macroName, ruleClass.getName()
                )
            )
            scratch.file(
                pkgName + "/BUILD",
                String.format(
                    """
              load(":macro.bzl", "%s")
              %s(name = "abc"%s)
              
              """.trimIndent(),
                    macroName, macroName, fakeMandatoryArgs
                )
            )
            val pkg: Package? = getPackage(pkgName)
            assertPackageNotInError(pkg)
            assertMacroHasAttributes(
                getMacroById(pkg, "abc:1"),
                ruleClass.getAttributeProvider().getAttributes().stream()
                    .filter({ a -> a.isPublic() && a.isDocumented() })
                    .map(Attribute::getName)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            )
            assertMacroDoesNotHaveAttributes(
                getMacroById(pkg, "abc:1"),
                com.google.common.collect.ImmutableList.of<String?>(
                    "generator_name", "generator_location", "generator_function", "generator_location"
                )
            )
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inheritAttrs_fromGenrule_producesTargetThatPassesAnalysis() {
        // inheritAttrs_fromAnyNativeRule() above is loading-phase only; by contrast, this test verifies
        // that we can wrap a native rule (in this case, genrule) in a macro with inherit_attrs, and
        // that the macro-wrapped rule target passes analysis.
        scratch.file(
            "pkg/my_genrule.bzl",
            """
def _my_genrule_impl(name, visibility, tags, **kwargs):
    print("my_genrule: tags = %s" % tags)
    for k in kwargs:
        print("my_genrule: kwarg %s = %s" % (k, kwargs[k]))
    native.genrule(name = name + "_wrapped_genrule", visibility = visibility, **kwargs)

my_genrule = macro(
    implementation = _my_genrule_impl,
    inherit_attrs = native.genrule,
)

""".trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_genrule.bzl", "my_genrule")
        my_genrule(
            name = "abc",
            outs = ["out.txt"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertThat(getConfiguredTarget("//pkg:abc_wrapped_genrule")).isNotNull()
        assertContainsEvent("my_genrule: tags = None") // Not []
        assertContainsEvent(
            "my_genrule: kwarg srcs = None"
        ) // Not select({"//conditions:default": []})
        assertContainsEvent(
            "my_genrule: kwarg testonly = None"
        ) // Not select({"//conditions:default": False})
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inheritAttrs_fromExportedStarlarkRule() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _my_rule_impl(ctx):
            pass

        _my_rule = rule(
            implementation = _my_rule_impl,
            attrs = {
                "srcs": attr.label_list(),
            },
        )

        def _my_macro_impl(name, visibility, **kwargs):
            _my_rule(name = name + "_my_rule", visibility = visibility, **kwargs)

        my_macro = macro(
            implementation = _my_macro_impl,
            inherit_attrs = _my_rule,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "abc")
        
        """.trimIndent()
        )
        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertMacroHasAttributes(
            getMacroById(pkg, "abc:1"),
            com.google.common.collect.ImmutableList.of<String?>("srcs", "tags")
        )
        assertMacroDoesNotHaveAttributes(
            getMacroById(pkg, "abc:1"),
            com.google.common.collect.ImmutableList.of<String?>(
                "generator_name",
                "generator_location",
                "generator_function"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inheritAttrs_fromUnexportedStarlarkRule_fails() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _my_rule_impl(ctx):
            pass

        _unexported = struct(
            rule = rule(
                implementation = _my_rule_impl,
                attrs = {
                    "srcs": attr.label_list(),
                },
            ),
        )

        def _my_macro_impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _my_macro_impl,
            inherit_attrs = _unexported.rule,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "abc")
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getPackage("pkg")).isNull()
        assertContainsEvent(
            "a rule or macro callable must be assigned to a global variable in a .bzl file before it"
                    + " can be inherited from"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inheritAttrs_fromExportedMacro() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
def _other_macro_impl(name, visibility, **kwargs):
    pass

_other_macro = macro(
    implementation = _other_macro_impl,
    attrs = {
        "srcs": attr.label_list(),
        "tags": attr.string_list(configurable = False),
    },
)

def _my_macro_impl(name, visibility, tags, **kwargs):
    print("my_macro: tags = %s" % tags)
    for k in kwargs:
        print("my_macro: kwarg %s = %s" % (k, kwargs[k]))
    _other_macro(name = name + "_other_macro", visibility = visibility, tags = tags, **kwargs)

my_macro = macro(
    implementation = _my_macro_impl,
    inherit_attrs = _other_macro,
)

""".trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "abc")
        
        """.trimIndent()
        )
        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertMacroHasAttributes(
            getMacroById(pkg, "abc:1"),
            com.google.common.collect.ImmutableList.of<String?>("name", "visibility", "srcs", "tags")
        )
        assertThat(
            getMacroById(pkg, "abc:1").getMacroClass().getAttributeProvider().getAttributeCount()
        )
            .isEqualTo(4)
        assertContainsEvent("my_macro: tags = None") // Not []
        assertContainsEvent("my_macro: kwarg srcs = None") // Not select({"//conditions:default": []})
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inheritAttrs_fromUnexportedMacro_fails() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _other_macro_impl(name, visibility, **kwargs):
            pass

        _unexported = struct(
            macro = macro(
                implementation = _other_macro_impl,
                attrs = {
                    "srcs": attr.label_list(),
                },
            ),
        )

        def _my_macro_impl(name, visibility, **kwargs):
            pass

        my_macro = macro(
            implementation = _my_macro_impl,
            inherit_attrs = _unexported.macro,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "abc")
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getPackage("pkg")).isNull()
        assertContainsEvent(
            "a rule or macro callable must be assigned to a global variable in a .bzl file before it"
                    + " can be inherited from"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun generatorInfoAndCallStack_atTopLevel() {
        // filegroup_legacy_macro is a legacy macro instantiating a filegroup rule.
        scratch.file(
            "pkg/inner_legacy_macro.bzl",
            """
        def inner_legacy_macro(name, **kwargs):
              native.filegroup(name = name, **kwargs)
        
        """.trimIndent()
        )
        // my_macro is a symbolic macro that instantiates 2 filegroup rules: one directly, and one
        // wrapped by filegroup_legacy_macro.
        scratch.file(
            "pkg/my_macro.bzl",
            """
        load(":inner_legacy_macro.bzl", "inner_legacy_macro")

        def _impl(name, visibility, **kwargs):
            native.filegroup(name = name + "_lib")
            inner_legacy_macro(name  = name + "_legacy_macro_lib")

        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")

        my_macro(name = "foo")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        val foo: MacroInstance = getMacroById(pkg, "foo:1")
        assertThat(foo.generatorName).isEqualTo("foo")
        assertThat(foo.getBuildFileLocation())
            .isEqualTo(net.starlark.java.syntax.Location.fromFileLineColumn("/workspace/pkg/BUILD", 3, 9))
        assertThat(foo.reconstructParentCallStack())
            .containsExactly(
                StarlarkThread.callStackEntry(StarlarkThread.TOP_LEVEL, foo.getBuildFileLocation()),
                StarlarkThread.callStackEntry(
                    "my_macro",
                    net.starlark.java.syntax.Location.fromFileLineColumn("/workspace/pkg/my_macro.bzl", 7, 1)
                )
            )
            .inOrder()

        val fooLib: Rule = pkg.getRule("foo_lib")
        assertThat(fooLib.isRuleCreatedInMacro()).isTrue()
        assertThat(fooLib.getLocation()).isEqualTo(foo.getBuildFileLocation())
        assertThat(fooLib.getAttr("generator_name", Type.STRING)).isEqualTo("foo")
        assertThat(fooLib.getAttr("generator_function", Type.STRING)).isEqualTo("my_macro")
        assertThat(fooLib.getAttr("generator_location", Type.STRING)).isEqualTo("pkg/BUILD:3:9")
        assertThat(fooLib.reconstructCallStack())
            .isEqualTo(
                com.google.common.collect.ImmutableList.builder<Any?>()
                    .addAll(foo.reconstructParentCallStack())
                    .add(
                        StarlarkThread.callStackEntry(
                            "_impl",
                            net.starlark.java.syntax.Location.fromFileLineColumn("/workspace/pkg/my_macro.bzl", 4, 21)
                        )
                    )
                    .build()
            )

        val fooLegacyLib: Rule = pkg.getRule("foo_legacy_macro_lib")
        assertThat(fooLegacyLib.isRuleCreatedInMacro()).isTrue()
        assertThat(fooLegacyLib.getLocation()).isEqualTo(foo.getBuildFileLocation())
        assertThat(fooLegacyLib.getAttr("generator_name", Type.STRING)).isEqualTo("foo")
        assertThat(fooLegacyLib.getAttr("generator_function", Type.STRING)).isEqualTo("my_macro")
        assertThat(fooLegacyLib.getAttr("generator_location", Type.STRING)).isEqualTo("pkg/BUILD:3:9")
        assertThat(fooLegacyLib.reconstructCallStack())
            .isEqualTo(
                com.google.common.collect.ImmutableList.builder<Any?>()
                    .addAll(foo.reconstructParentCallStack())
                    .add(
                        StarlarkThread.callStackEntry(
                            "_impl",
                            net.starlark.java.syntax.Location.fromFileLineColumn("/workspace/pkg/my_macro.bzl", 5, 23)
                        )
                    )
                    .add(
                        StarlarkThread.callStackEntry(
                            "inner_legacy_macro",
                            net.starlark.java.syntax.Location.fromFileLineColumn(
                                "/workspace/pkg/inner_legacy_macro.bzl", 2, 23
                            )
                        )
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun generatorInfoAndCallStack_withLegacyWrapperWithoutName() {
        // my_macro is a symbolic macro that instantiates a filegroup rule
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _impl(name, visibility, **kwargs):
            native.filegroup(name = name + "_bin")

        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        // legacy_wrapper is a legacy wrapper of my_macro which doesn't have a `name` parameter
        scratch.file(
            "pkg/legacy_nameless_wrapper.bzl",
            """
        load(":my_macro.bzl", "my_macro")

        def legacy_nameless_wrapper(praenomen):
            my_macro(name = praenomen)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":legacy_nameless_wrapper.bzl", "legacy_nameless_wrapper")

        legacy_nameless_wrapper(praenomen = "foo")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        val foo: MacroInstance = getMacroById(pkg, "foo:1")
        assertThat(foo.generatorName).isNull()
        assertThat(foo.getBuildFileLocation())
            .isEqualTo(net.starlark.java.syntax.Location.fromFileLineColumn("/workspace/pkg/BUILD", 3, 24))
        assertThat(foo.reconstructParentCallStack())
            .containsExactly(
                StarlarkThread.callStackEntry(StarlarkThread.TOP_LEVEL, foo.getBuildFileLocation()),
                StarlarkThread.callStackEntry(
                    "legacy_nameless_wrapper",
                    net.starlark.java.syntax.Location.fromFileLineColumn(
                        "/workspace/pkg/legacy_nameless_wrapper.bzl",
                        4,
                        13
                    )
                ),
                StarlarkThread.callStackEntry(
                    "my_macro",
                    net.starlark.java.syntax.Location.fromFileLineColumn("/workspace/pkg/my_macro.bzl", 4, 1)
                )
            )
            .inOrder()

        val fooBin: Rule = pkg.getRule("foo_bin")
        assertThat(fooBin.isRuleCreatedInMacro()).isTrue()
        assertThat(fooBin.getLocation()).isEqualTo(foo.getBuildFileLocation())
        assertThat(fooBin.getAttr("generator_name", Type.STRING)).isEqualTo("foo_bin")
        assertThat(fooBin.getAttr("generator_function", Type.STRING))
            .isEqualTo("legacy_nameless_wrapper")
        assertThat(fooBin.getAttr("generator_location", Type.STRING)).isEqualTo("pkg/BUILD:3:24")
        assertThat(fooBin.reconstructCallStack())
            .isEqualTo(
                com.google.common.collect.ImmutableList.builder<Any?>()
                    .addAll(foo.reconstructParentCallStack())
                    .add(
                        StarlarkThread.callStackEntry(
                            "_impl",
                            net.starlark.java.syntax.Location.fromFileLineColumn("/workspace/pkg/my_macro.bzl", 2, 21)
                        )
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun generatorInfoAndCallStack_nestedMacros() {
        // inner_legacy_wrapper is a legacy macro wrapper around inner_macro, which is a symbolic macro
        // that instantiates a filegroup rule.
        scratch.file(
            "pkg/inner.bzl",
            """
        def _inner_impl(name, visibility, **kwargs):
            native.filegroup(name = name, **kwargs)

        inner_macro = macro(implementation = _inner_impl)

        def inner_legacy_wrapper(name, **kwargs):
            inner_macro(name = name, **kwargs)
        
        """.trimIndent()
        )
        // outer_legacy_wrapper is a legacy wrapper around outer_macro, which is a symbolic macro that
        // invokes inner_legacy_wrapper.
        scratch.file(
            "pkg/outer.bzl",
            """
        load(":inner.bzl", "inner_legacy_wrapper")

        def _outer_impl(name, visibility, **kwargs):
            inner_legacy_wrapper(name  = name + "_inner", **kwargs)

        outer_macro = macro(implementation = _outer_impl)

        def outer_legacy_wrapper(name, **kwargs):
            outer_macro(name = name, **kwargs)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":outer.bzl", "outer_legacy_wrapper")

        outer_legacy_wrapper(name = "foo")
        
        """.trimIndent()
        )

        val pkg: Package? = getPackage("pkg")
        assertPackageNotInError(pkg)
        val foo: MacroInstance = getMacroById(pkg, "foo:1")
        assertThat(foo.generatorName).isEqualTo("foo")
        assertThat(foo.getBuildFileLocation())
            .isEqualTo(net.starlark.java.syntax.Location.fromFileLineColumn("/workspace/pkg/BUILD", 3, 21))
        assertThat(foo.reconstructParentCallStack())
            .containsExactly(
                StarlarkThread.callStackEntry(StarlarkThread.TOP_LEVEL, foo.getBuildFileLocation()),
                StarlarkThread.callStackEntry(
                    "outer_legacy_wrapper",
                    net.starlark.java.syntax.Location.fromFileLineColumn("/workspace/pkg/outer.bzl", 9, 16)
                ),
                StarlarkThread.callStackEntry(
                    "outer_macro",
                    net.starlark.java.syntax.Location.fromFileLineColumn("/workspace/pkg/outer.bzl", 6, 1)
                )
            )
            .inOrder()

        val fooInner: MacroInstance = getMacroById(pkg, "foo_inner:1")
        assertThat(fooInner.generatorName).isEqualTo(foo.generatorName)
        assertThat(fooInner.getBuildFileLocation()).isEqualTo(foo.getBuildFileLocation())
        assertThat(fooInner.reconstructParentCallStack())
            .containsExactly(
                StarlarkThread.callStackEntry(
                    "_outer_impl",
                    net.starlark.java.syntax.Location.fromFileLineColumn("/workspace/pkg/outer.bzl", 4, 25)
                ),
                StarlarkThread.callStackEntry(
                    "inner_legacy_wrapper",
                    net.starlark.java.syntax.Location.fromFileLineColumn("/workspace/pkg/inner.bzl", 7, 16)
                ),
                StarlarkThread.callStackEntry(
                    "inner_macro",
                    net.starlark.java.syntax.Location.fromFileLineColumn("/workspace/pkg/inner.bzl", 4, 1)
                )
            )
            .inOrder()

        val fooLib: Rule = pkg.getRule("foo_inner")
        assertThat(fooLib.isRuleCreatedInMacro()).isTrue()
        assertThat(fooLib.getLocation()).isEqualTo(foo.getBuildFileLocation())
        assertThat(fooLib.getAttr("generator_name", Type.STRING)).isEqualTo("foo")
        assertThat(fooLib.getAttr("generator_function", Type.STRING)).isEqualTo("outer_legacy_wrapper")
        assertThat(fooLib.getAttr("generator_location", Type.STRING)).isEqualTo("pkg/BUILD:3:21")
        assertThat(fooLib.reconstructCallStack())
            .isEqualTo(
                com.google.common.collect.ImmutableList.builder<Any?>()
                    .addAll(foo.reconstructParentCallStack())
                    .addAll(fooInner.reconstructParentCallStack())
                    .add(
                        StarlarkThread.callStackEntry(
                            "_inner_impl",
                            net.starlark.java.syntax.Location.fromFileLineColumn("/workspace/pkg/inner.bzl", 2, 21)
                        )
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun maxComputationSteps_enforcedInMacros() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _impl(name, visibility):
            # exceed max_computation_steps
            for i in range(1000):
                pass
            native.filegroup(name = name, visibility = visibility)

        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        
        """.trimIndent()
        )
        setBuildLanguageOptions("--max_computation_steps=100") // sufficient for BUILD but not my_macro
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val exception: NoSuchPackageException? =
            org.junit.Assert.assertThrows<T?>(
                NoSuchPackageException::class.java,
                org.junit.function.ThrowingRunnable {
                    getPackageManager()
                        .getPackage(reporter, PackageIdentifier.createInMainRepo("pkg"))
                })
        assertThat(exception)
            .hasMessageThat()
            .containsMatch("computation took 1\\d{3} steps, but --max_computation_steps=100")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failingMacro_immediatelyThrowsEvalExceptionWithFullCallStack() {
        scratch.file(
            "pkg/inner.bzl",
            """
        def _inner_impl(name, visibility, **kwargs):
            fail("Inner macro failed")

        inner_macro = macro(implementation = _inner_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/outer.bzl",
            """
        load(":inner.bzl", "inner_macro")

        def _outer_impl(name, visibility, **kwargs):
            inner_macro(name  = name + "_inner", **kwargs)
            fail("This should not be reached")

        outer_macro = macro(implementation = _outer_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":outer.bzl", "outer_macro")

        outer_macro(name = "foo")
        fail("This should not be reached")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val pkg: Package? = getPackage("pkg")
        assertThat(pkg.containsErrors()).isTrue()
        assertDoesNotContainEvent("This should not be reached")
        assertContainsEvent(
            """
${'\t'}File "/workspace/pkg/BUILD", line 3, column 12, in <toplevel>
${'\t'}${'\t'}outer_macro(name = "foo")
${'\t'}File "/workspace/pkg/outer.bzl", line 7, column 1, in outer_macro
${'\t'}${'\t'}outer_macro = macro(implementation = _outer_impl)
${'\t'}File "/workspace/pkg/outer.bzl", line 4, column 16, in _outer_impl
${'\t'}${'\t'}inner_macro(name  = name + "_inner", **kwargs)
${'\t'}File "/workspace/pkg/inner.bzl", line 4, column 1, in inner_macro
${'\t'}${'\t'}inner_macro = macro(implementation = _inner_impl)
${'\t'}File "/workspace/pkg/inner.bzl", line 2, column 9, in _inner_impl
${'\t'}${'\t'}fail("Inner macro failed")

""".trimIndent()
        )
    }

    private fun assertMacroHasAttributes(
        macro: MacroInstance,
        attributeNames: com.google.common.collect.ImmutableList<String?>
    ) {
        for (attributeName in attributeNames) {
            assertThat(
                macro.getMacroClass().getAttributeProvider().getAttributeByNameMaybe(attributeName)
            )
                .isNotNull()
        }
    }

    private fun assertMacroDoesNotHaveAttributes(
        macro: MacroInstance, attributeNames: com.google.common.collect.ImmutableList<String?>
    ) {
        for (attributeName in attributeNames) {
            assertThat(
                macro.getMacroClass().getAttributeProvider().getAttributeByNameMaybe(attributeName)
            )
                .isNull()
        }
    }

    companion object {
        /** Retrieves the macro with the given id, which must exist in the package.  */
        private fun getMacroById(pkg: Package, id: String?): MacroInstance {
            val macro: MacroInstance = pkg.getMacrosById().get(id)
            assertThat(macro).isNotNull()
            return macro
        }

        /** Maps a list of labels to a more convenient list of strings.  */
        private fun asStringList(labelList: MutableList<Label?>): com.google.common.collect.ImmutableList<String?> {
            return labelList.stream().map<Any?>(Label::getCanonicalForm)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        }

        /**
         * Retrieves the visibility labels of the target with the given name, which must exist in the
         * package.
         */
        @Throws(java.lang.Exception::class)
        private fun getTargetVisibility(pkg: Package, name: String?): com.google.common.collect.ImmutableList<String?> {
            val target: Target = pkg.getTarget(name)
            assertThat(target).isNotNull()
            return asStringList(target.getActualVisibility().getDeclaredLabels())
        }

        /**
         * Retrieves the visibility labels of the macro with the given id, which must exist in the
         * package.
         */
        @Throws(java.lang.Exception::class)
        private fun getMacroVisibility(pkg: Package, id: String?): com.google.common.collect.ImmutableList<String?> {
            return asStringList(getMacroById(pkg, id).getActualVisibility())
        }
    }
}
