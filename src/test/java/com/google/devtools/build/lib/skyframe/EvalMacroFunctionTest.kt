// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/** Unit tests of [EvalMacroFunction].  */
@RunWith(TestParameterInjector::class)
class EvalMacroFunctionTest : BuildViewTestCase() {
    @Throws(java.lang.InterruptedException::class)
    private fun evaluate(
        skyKey: PackagePieceIdentifier.ForMacro?
    ): EvaluationResult<PackagePieceValue.ForMacro?> {
        return SkyframeExecutorTestUtils.evaluate<T?>(
            getSkyframeExecutor(), skyKey,  /* keepGoing= */false, reporter
        )
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    private fun getPackagePiece(pkg: String?, vararg macroInstances: String?): PackagePiece.ForMacro {
        val skyKey: PackagePieceIdentifier.ForMacro? = getMacroKey(pkg, *macroInstances)
        val result: EvaluationResult<PackagePieceValue.ForMacro?> = evaluate(skyKey)
        if (result.hasError()) {
            org.junit.Assert.fail(result.getError(skyKey).getException().getMessage())
        }
        val value: PackagePiece.ForMacro = result.get(skyKey).getPackagePiece()
        assertThat(value.getIdentifier()).isEqualTo(skyKey)
        return value
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    private fun getPackagePieceWithoutErrors(pkg: String?, vararg macroInstances: String?): PackagePiece.ForMacro {
        val value: PackagePiece.ForMacro = getPackagePiece(pkg, *macroInstances)
        assertThat(value.containsErrors()).isFalse()
        return value
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    private fun <T> getExceptionForPackagePiece(
        exceptionClass: java.lang.Class<T?>, pkg: String?, vararg macroInstances: String?
    ): T? {
        reporter.removeHandler(failFastHandler)
        val skyKey: PackagePieceIdentifier.ForMacro? = getMacroKey(pkg, *macroInstances)
        val result: EvaluationResult<PackagePieceValue.ForMacro?> = evaluate(skyKey)
        assertThat(result.hasError()).isTrue()
        val exception: java.lang.Exception? = result.getError(skyKey).getException()
        Truth.assertThat(exception).isInstanceOf(exceptionClass)
        return exceptionClass.cast(exception)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validMacro() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _impl(name, visibility):
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
        val forMacro: PackagePiece.ForMacro = getPackagePieceWithoutErrors("pkg", "foo")
        assertThat(forMacro.getTargets()).containsKey("foo")
    }

    @org.junit.Test
    @TestParameters("{suffix: ''}", "{suffix: '_inner'}")
    @Throws(java.lang.Exception::class)
    fun validNestedMacro(suffix: String) {
        scratch.file(
            "pkg/inner_macro.bzl",
            """
        def _impl(name, visibility):
            native.filegroup(name = name, visibility = visibility)
        inner_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/outer_macro.bzl",
            String.format(
                """
            load(":inner_macro.bzl", "inner_macro")

            def _impl(name, visibility):
                inner_macro(name = name + "%s", visibility = visibility)
            outer_macro = macro(implementation = _impl)
            
            """.trimIndent(),
                suffix
            )
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":outer_macro.bzl", "outer_macro")
        outer_macro(name = "foo")
        
        """.trimIndent()
        )
        val innerMacroInstanceName = "foo" + suffix
        val forInnerMacro: PackagePiece.ForMacro =
            getPackagePieceWithoutErrors("pkg", "foo", innerMacroInstanceName)
        assertThat(forInnerMacro.getTargets()).containsKey(innerMacroInstanceName)
        val forOuterMacro: PackagePiece.ForMacro = getPackagePieceWithoutErrors("pkg", "foo")
        assertThat(forOuterMacro.getMacroByName(innerMacroInstanceName))
            .isSameInstanceAs(forInnerMacro.getEvaluatedMacro())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun innerAndSiblingMacros_notExpandedUnlessRequested() {
        scratch.file(
            "pkg/inner_macro.bzl",
            """
        def _impl(name, visibility):
            fail("This will fail if the inner macro is expanded")
        inner_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/sibling_macro.bzl",
            """
        def _impl(name, visibility):
            fail("This will fail if the sibling macro is expanded")
        sibling_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/outer_macro.bzl",
            """
        load(":inner_macro.bzl", "inner_macro")

        def _impl(name, visibility):
            inner_macro(name = name + "_inner", visibility = visibility)
        outer_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":sibling_macro.bzl", "sibling_macro")
        load(":outer_macro.bzl", "outer_macro")
        sibling_macro(name = "bar")
        outer_macro(name = "foo")
        
        """.trimIndent()
        )
        getPackagePieceWithoutErrors("pkg", "foo")
        val siblingPieceException: SkyframeExecutor.FailureToRetrieveIntrospectedValueException? =
            org.junit.Assert.assertThrows<T?>(
                SkyframeExecutor.FailureToRetrieveIntrospectedValueException::class.java,
                org.junit.function.ThrowingRunnable {
                    skyframeExecutor.getDoneSkyValueForIntrospection(
                        getMacroKey(
                            "pkg",
                            "bar"
                        )
                    )
                })
        assertThat(siblingPieceException)
            .hasMessageThat()
            .contains(
                "<PackagePieceIdentifier.ForMacro name=//pkg:bar"
                        + " declared_in=<PackagePieceIdentifier.ForBuildFile pkg=//pkg>> not found"
            )
        val innerPieceException: SkyframeExecutor.FailureToRetrieveIntrospectedValueException? =
            org.junit.Assert.assertThrows<T?>(
                SkyframeExecutor.FailureToRetrieveIntrospectedValueException::class.java,
                org.junit.function.ThrowingRunnable {
                    skyframeExecutor.getDoneSkyValueForIntrospection(
                        getMacroKey("pkg", "foo", "foo_inner")
                    )
                })
        assertThat(innerPieceException)
            .hasMessageThat()
            .contains(
                ("<PackagePieceIdentifier.ForMacro name=//pkg:foo_inner"
                        + " declared_in=<PackagePieceIdentifier.ForMacro name=//pkg:foo"
                        + " declared_in=<PackagePieceIdentifier.ForBuildFile pkg=//pkg>>> not found")
            )
    }

    // TODO(https://github.com/bazelbuild/bazel/issues/26128): also prune outer macro changes at an
    // inner macro instance.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildFileChange_prunedAtMacroInstance() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _impl(name, visibility):
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
        val packagePieceBeforeUpdate: PackagePiece.ForMacro = getPackagePieceWithoutErrors("pkg", "foo")

        // Edit and invalidate BUILD file. Note that for change pruning to work, the edit must preserve
        // line numbers in the macro call stack.
        // TODO(https://github.com/bazelbuild/bazel/issues/26128): relax this requirement.
        scratch.overwriteFile(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        filegroup(name = "unrelated")
        
        """.trimIndent()
        )
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                ModifiedFileSet.builder().modify(PathFragment.create("pkg/BUILD")).build(),
                Root.fromPath(rootDirectory)
            )

        // PackagePieceValue.ForMacro is a NotComparableSkyValue; if we get back the same instance after
        // update, it means all of the PackagePieceValue's deps were either change-pruned or unchanged.
        assertThat(getPackagePieceWithoutErrors("pkg", "foo"))
            .isSameInstanceAs(packagePieceBeforeUpdate)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun maxComputationSteps_enforced() {
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
        assertThat(
            getExceptionForPackagePiece<NoSuchPackagePieceException?>(
                NoSuchPackagePieceException::class.java,
                "pkg",
                "foo"
            )
        )
            .hasMessageThat()
            .containsMatch(
                "symbolic macro evaluation took 1\\d{3} computation steps, but"
                        + " --max_computation_steps=100"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noBuildFile_failsCleanly() {
        assertThat(
            getExceptionForPackagePiece<NoSuchPackageException?>(
                NoSuchPackageException::class.java,
                "no_such_pkg",
                "foo"
            )
        )
            .hasMessageThat()
            .contains("no such package 'no_such_pkg': BUILD file not found")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun badBuildFile_failsCleanly() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _impl(name, visibility):
            native.filegroup(name = name, visibility = visibility)
        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        load(":bad_load.bzl", "bad_value")
        my_macro(name = "foo")
        
        """.trimIndent()
        )
        assertThat(
            getExceptionForPackagePiece<NoSuchPackageException?>(
                NoSuchPackageException::class.java,
                "pkg",
                "foo"
            )
        )
            .hasMessageThat()
            .contains("cannot load '//pkg:bad_load.bzl': no such file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noMacroInstance_failsCleanly() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _impl(name, visibility):
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
        assertThat(
            getExceptionForPackagePiece<NoSuchMacroInstanceException?>(
                NoSuchMacroInstanceException::class.java,
                "pkg",
                "no_such_name"
            )
        )
            .hasMessageThat()
            .contains("Macro instance 'no_such_name' not found in top-level package piece")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun badMacroImplementation_producesPackagePieceWithErrors() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _impl(name, visibility):
            native.filegroup(name = name, visibility = visibility)
            fail("fail fail fail")
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

        reporter.removeHandler(failFastHandler)
        val forMacro: PackagePiece.ForMacro = getPackagePiece("pkg", "foo")
        assertThat(forMacro.containsErrors()).isTrue()
        assertThat((forMacro.getTarget("foo") as Rule).containsErrors()).isTrue()
        assertContainsEvent(
            """
        ERROR /workspace/pkg/my_macro.bzl:3:9: Traceback (most recent call last):
        ${'\t'}File "/workspace/pkg/BUILD", line 2, column 9, in <toplevel>
        ${'\t'}${'\t'}my_macro(name = "foo")
        ${'\t'}File "/workspace/pkg/my_macro.bzl", line 4, column 1, in my_macro
        ${'\t'}${'\t'}my_macro = macro(implementation = _impl)
        ${'\t'}File "/workspace/pkg/my_macro.bzl", line 3, column 9, in _impl
        ${'\t'}${'\t'}fail("fail fail fail")
        Error in fail: fail fail fail
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalizers_seeNonFinalizerDefinedRulesOrderedByName() {
        scratch.file(
            "pkg/my_finalizer.bzl",
            """
        # Dummy rule used to save native.existing_rules() keys in a string list attribute.
        _existing_rules_saver = rule(
            implementation = lambda ctx: [],
            attrs = {"existing_rules": attr.string_list()},
        )

        def _impl(name, visibility):
            _existing_rules_saver(name = name, existing_rules = list(native.existing_rules()))
        my_finalizer = macro(implementation = _impl, finalizer = True)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/other_macro.bzl",
            """
        def _other_inner_macro_impl(name, visibility):
            native.filegroup(name = name, visibility = visibility)
        other_inner_macro = macro(implementation = _other_inner_macro_impl)

        def _other_macro_impl(name, visibility):
            other_inner_macro(name = name + "_c_inner", visibility = visibility)
            native.filegroup(name = name + "_b", visibility = visibility)
            other_inner_macro(name = name + "_a_inner", visibility = visibility)
        other_macro = macro(implementation = _other_macro_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_finalizer.bzl", "my_finalizer")
        load(":other_macro.bzl", "other_macro")
        my_finalizer(name = "finalize")
        other_macro(name = "macro_declared")
        filegroup(name = "a_top_level")
        filegroup(name = "z_top_level")
        
        """.trimIndent()
        )
        // getPackagePieceWithoutErrors("pkg", "finalize");
        val finalizerPiece: PackagePiece.ForMacro = getPackagePieceWithoutErrors("pkg", "finalize")
        val existingRulesSaverRule: Rule = finalizerPiece.getTarget("finalize") as Rule
        val existingRules: MutableList<String?>? =
            Types.STRING_LIST.cast(existingRulesSaverRule.getAttr("existing_rules"))
        Truth.assertThat(existingRules)
            .containsExactly( // Ordered by name.
                "a_top_level",
                "macro_declared_a_inner",
                "macro_declared_b",
                "macro_declared_c_inner",
                "z_top_level"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalizers_doNotSeeFinalizerDefinedTargets() {
        scratch.file(
            "pkg/my_finalizer.bzl",
            """
        # Dummy rule used to save native.existing_rules() keys in a string list attribute.
        _existing_rules_saver = rule(
            implementation = lambda ctx: [],
            attrs = {"existing_rules": attr.string_list()},
        )

        def _impl(name, visibility):
            native.filegroup(name = name + "_dummy_rule")
            _existing_rules_saver(name = name, existing_rules = list(native.existing_rules()))
        my_finalizer = macro(implementation = _impl, finalizer = True)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_finalizer.bzl", "my_finalizer")
        my_finalizer(name = "finalize")
        my_finalizer(name = "other_finalize")
        
        """.trimIndent()
        )

        val finalizerPiece: PackagePiece.ForMacro = getPackagePieceWithoutErrors("pkg", "finalize")
        assertThat(
            Types.STRING_LIST.cast(
                (finalizerPiece.getTarget("finalize") as Rule).getAttr("existing_rules")
            )
        )
            .isEmpty()
        val otherFinalizerPiece: PackagePiece.ForMacro =
            getPackagePieceWithoutErrors("pkg", "other_finalize")
        assertThat(
            Types.STRING_LIST.cast(
                (otherFinalizerPiece.getTarget("other_finalize") as Rule).getAttr("existing_rules")
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalizers_notEvaluated_ifNonFinalizerPackagePieceInError() {
        scratch.file(
            "pkg/my_finalizer.bzl",
            """
        def _impl(name, visibility):
            native.filegroup(name = name + "_saw_rules", srcs = native.existing_rules())
        my_finalizer = macro(implementation = _impl, finalizer = True)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/fail_macro.bzl",
            """
        def _impl(name, visibility):
            native.filegroup(name = name, visibility = visibility)
            fail("fail fail fail")

        fail_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":fail_macro.bzl", "fail_macro")
        load(":my_finalizer.bzl", "my_finalizer")
        my_finalizer(name = "finalize")
        filegroup(name = "top_level_rule")
        fail_macro(name = "failing_macro")
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        val finalizerPiece: PackagePiece.ForMacro = getPackagePiece("pkg", "finalize")
        assertThat(finalizerPiece.containsErrors()).isTrue()
        assertThat(finalizerPiece.getTargets()).isEmpty()
        com.google.common.truth.Subject.contains(
            ("cannot compute package piece for finalizer macro //pkg:finalize defined by"
                    + " //pkg:my_finalizer.bzl%my_finalizer: error in package piece for macro"
                    + " //pkg:failing_macro defined by //pkg:fail_macro.bzl%fail_macro")
        )
        assertThat(getPackagePiece("pkg", "failing_macro").containsErrors()).isTrue()
        assertContainsEventsInOrder(
            """
        Traceback (most recent call last):
        ${'\t'}File "/workspace/pkg/BUILD", line 5, column 11, in <toplevel>
        ${'\t'}${'\t'}fail_macro(name = "failing_macro")
        ${'\t'}File "/workspace/pkg/fail_macro.bzl", line 5, column 1, in fail_macro
        ${'\t'}${'\t'}fail_macro = macro(implementation = _impl)
        ${'\t'}File "/workspace/pkg/fail_macro.bzl", line 3, column 9, in _impl
        ${'\t'}${'\t'}fail("fail fail fail")
        Error in fail: fail fail fail
        """.trimIndent(),
            "cannot compute package piece for finalizer macro //pkg:finalize defined by"
                    + " //pkg:my_finalizer.bzl%my_finalizer"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalizers_notEvaluated_ifNameConflictBetweenNonFinalizerPackagePieces() {
        scratch.file(
            "pkg/my_finalizer.bzl",
            """
        def _impl(name, visibility):
            native.filegroup(name = name + "_saw_rules", srcs = native.existing_rules())
        my_finalizer = macro(implementation = _impl, finalizer = True)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/name_conflict_macro.bzl",
            """
        def _impl(name, suffix, visibility):
            native.filegroup(name = name + suffix, visibility = visibility)

        name_conflict_macro = macro(
            implementation = _impl,
            attrs = {"suffix": attr.string(configurable = False)},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_finalizer.bzl", "my_finalizer")
        load(":name_conflict_macro.bzl", "name_conflict_macro")
        my_finalizer(name = "finalize")
        filegroup(name = "top_level_rule")
        name_conflict_macro(name = "top", suffix = "_level_rule")
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        val finalizerPiece: PackagePiece.ForMacro = getPackagePiece("pkg", "finalize")
        assertThat(finalizerPiece.containsErrors()).isTrue()
        assertThat(finalizerPiece.getTargets()).isEmpty()
        com.google.common.truth.Subject.contains(
            ("cannot compute package piece for finalizer macro //pkg:finalize defined by"
                    + " //pkg:my_finalizer.bzl%my_finalizer: filegroup rule 'top_level_rule' conflicts"
                    + " with existing filegroup rule")
        )
        // Note that individual non-finalizer package pieces are not in error - the conflict is in the
        // NonFinalizerPackagePiecesValue.
        getPackagePieceWithoutErrors("pkg", "top")
        assertContainsEventsInOrder(
            """
        Traceback (most recent call last):
        ${'\t'}File "/workspace/pkg/BUILD", line 5, column 20, in <toplevel>
        ${'\t'}${'\t'}name_conflict_macro(name = "top", suffix = "_level_rule")
        ${'\t'}File "/workspace/pkg/name_conflict_macro.bzl", line 4, column 1, in name_conflict_macro
        ${'\t'}${'\t'}name_conflict_macro = macro(
        ${'\t'}File "/workspace/pkg/name_conflict_macro.bzl", line 2, column 21, in _impl
        ${'\t'}${'\t'}native.filegroup(name = name + suffix, visibility = visibility)
        Error: filegroup rule 'top_level_rule' conflicts with existing filegroup rule, defined at /workspace/pkg/BUILD:4:10
        """.trimIndent(),
            "cannot compute package piece for finalizer macro //pkg:finalize defined by"
                    + " //pkg:my_finalizer.bzl%my_finalizer"
        )
    }

    companion object {
        private fun getBuildFileKey(pkg: String?): PackagePieceIdentifier.ForBuildFile {
            val pkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo(pkg)
            return ForBuildFile(pkgId)
        }

        /**
         * Returns the skykey for a [PackagePieceValue.ForMacro].
         * 
         * @param pkg the package name
         * @param macroInstances a list of macro instance names from the outermost to the innermost; for
         * example, ["foo", "foo_bar"] means the key for the package piece generated by expanding
         * macro instance "foo_bar" which is declared in macro instance "foo".
         */
        private fun getMacroKey(pkg: String?, vararg macroInstances: String?): PackagePieceIdentifier.ForMacro? {
            com.google.common.base.Preconditions.checkArgument(macroInstances.size > 0)
            val buildFileKey: PackagePieceIdentifier.ForBuildFile = getBuildFileKey(pkg)
            var macroKey: PackagePieceIdentifier.ForMacro? = null
            for (macroInstance in macroInstances) {
                macroKey =
                    ForMacro(
                        buildFileKey.getPackageIdentifier(),
                        if (macroKey != null) macroKey else buildFileKey,
                        macroInstance
                    )
            }
            return macroKey
        }
    }
}
