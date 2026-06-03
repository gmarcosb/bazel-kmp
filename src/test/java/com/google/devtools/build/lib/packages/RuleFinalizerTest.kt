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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/** Tests the execution of symbolic macro implementations.  */
@RunWith(JUnit4::class)
class RuleFinalizerTest : BuildViewTestCase() {
    /**
     * Returns a package by the given name (no leading "//"), or null upon [ ].
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class, NoSuchPackageException::class)
    private fun getPackage(pkgName: String?): java.lang.Package? {
        return packageManager.getPackage(reporter, PackageIdentifier.createInMainRepo(pkgName))
    }

    private fun assertPackageNotInError(pkg: java.lang.Package?) {
        Truth.assertThat(pkg).isNotNull()
        assertThat(pkg.containsErrors()).isFalse()
    }

    @Throws(java.lang.Exception::class)
    private fun assertGetPackageFailsWithEvent(pkgName: String?, msg: String?) {
        reporter.removeHandler(failFastHandler)
        val pkg: java.lang.Package = getPackage(pkgName)
        Truth.assertThat(pkg).isNotNull()
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(msg)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicFunctionality() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(name, visibility, targets_of_interest):
            for r in native.existing_rules().values():
                if r["name"] in [t.name for t in targets_of_interest]:
                    genrule_name = name + "_" + r["name"] + "_finalize"
                    native.genrule(
                        name = genrule_name,
                        srcs = [r["name"]],
                        outs = [genrule_name + ".txt"],
                        cmd = "... > ${'$'}@",
                    )

        my_finalizer = macro(
            implementation = _impl,
            finalizer = True,
            attrs = {"targets_of_interest": attr.label_list(configurable = False)},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_finalizer")
        filegroup(name = "foo")
        my_finalizer(name = "abc", targets_of_interest = [":foo"])
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertThat(pkg.getTargets().keySet())
            .containsAtLeast("abc_foo_finalize", "abc_foo_finalize.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalizer_canCallFinalizer() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl_inner(name, visibility):
            for r in native.existing_rules().values():
                if r["name"] == "foo":
                    genrule_name = name + "_" + r["name"] + "_finalize"
                    native.genrule(
                        name = genrule_name,
                        srcs = [r["name"]],
                        outs = [genrule_name + ".txt"],
                        cmd = "... > ${'$'}@",
                    )

        my_finalizer_inner = macro(implementation = _impl_inner, finalizer = True)

        def _impl_outer(name, visibility):
            my_finalizer_inner(name = name + "_inner")

        my_finalizer_outer = macro(implementation = _impl_outer, finalizer = True)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_finalizer_outer")
        filegroup(name = "foo")
        my_finalizer_outer(name = "abc")
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertThat(pkg.getTargets()).containsKey("abc_inner_foo_finalize")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalizer_canCallNonFinalizerMacro() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl_macro(name, visibility, deps):
            native.genrule(
                name = name,
                srcs = deps,
                outs = [name + ".txt"],
                cmd = "... > ${'$'}@",
            )

        my_macro = macro(implementation = _impl_macro, attrs = {"deps": attr.label_list()})

        def _impl_finalizer(name, visibility):
            for r in native.existing_rules().values():
                if r["name"] == "foo":
                    my_macro(name=name + "_" + r["name"] + "_finalize", deps = [r["name"]])

        my_finalizer = macro(implementation = _impl_finalizer, finalizer = True)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_finalizer")
        filegroup(name = "foo")
        my_finalizer(name = "abc")
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertThat(pkg.getTargets().keySet())
            .containsAtLeast("abc_foo_finalize", "abc_foo_finalize.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonFinalizerMacro_cannotCallFinalizer() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl_finalizer(name, visibility):
            for r in native.existing_rules().values():
                if r["name"] == "foo":
                    genrule_name = name + "_" + r["name"] + "_finalize"
                    native.genrule(
                        name = genrule_name,
                        srcs = [r["name"]],
                        outs = [genrule_name + ".txt"],
                        cmd = "... > ${'$'}@",
                    )

        my_finalizer = macro(implementation = _impl_finalizer, finalizer = True)

        def _impl_macro(name, visibility):
            my_finalizer(name = name + "_inner")

        my_macro = macro(implementation = _impl_macro)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_macro")
        my_macro(name = "abc")
        
        """.trimIndent()
        )

        assertGetPackageFailsWithEvent(
            "pkg", "Cannot instantiate a rule finalizer within a non-finalizer symbolic macro"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalizer_nativeExistingRule_seesOnlyNonFinalizerTargets_inAllLexicalPositions() {
        scratch.file(
            "pkg/foo.bzl",
            """
        EXPECTED = [
            "top_level_lexically_before_finalizer",
            "macro_lexically_before_finalizer_inner_lib",
            "top_level_lexically_after_finalizer",
            "macro_lexically_after_finalizer_inner_lib",
        ]

        UNEXPECTED = [
            "finalizer_inner_lib",
            "finalizer_inner_macro_inner_lib",
            "finalizer_inner_finalizer_inner_lib",
            "other_finalizer_inner_lib",
            "other_finalizer_inner_macro_inner_lib",
            "other_finalizer_inner_finalizer_inner_lib",
        ]

        def check_existing_rules():
            if (sorted(native.existing_rules().keys()) != sorted(EXPECTED)):
                fail("native.existing_rules().keys(): " + native.existing_rules().keys())
            for t in EXPECTED:
                if native.existing_rule(t) == None:
                    fail("native.existing_rule(" + t + ") == None")
            for t in UNEXPECTED:
                if native.existing_rule(t) != None:
                    fail("native.existing_rule(" + t + ") != None")
            print("native.existing_rules and native.existing_rule are as expected")

        def _impl_macro(name, visibility):
            native.filegroup(name = name + "_inner_lib")

        my_macro = macro(implementation = _impl_macro)

        def _impl_inner_finalizer(name, visibility):
            native.filegroup(name = name + "_inner_lib")
            check_existing_rules()

        inner_finalizer = macro(implementation = _impl_inner_finalizer, finalizer = True)

        def _impl_finalizer(name, visibility):
            native.filegroup(name = name + "_inner_lib")
            my_macro(name = name + "_inner_macro")
            inner_finalizer(name = name + "_inner_finalizer")
            check_existing_rules()

        my_finalizer = macro(implementation = _impl_finalizer, finalizer = True)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "my_finalizer", "my_macro")
        filegroup(name = "top_level_lexically_before_finalizer")
        my_macro(name = "macro_lexically_before_finalizer")
        my_finalizer(name = "finalizer")
        my_finalizer(name = "other_finalizer")
        filegroup(name = "top_level_lexically_after_finalizer")
        my_macro(name = "macro_lexically_after_finalizer")
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = getPackage("pkg")
        assertPackageNotInError(pkg)
        assertContainsEventWithFrequency(
            "native.existing_rules and native.existing_rule are as expected", 4
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun packageInError_notFinalized() {
        scratch.file(
            "pkg/finalizers.bzl",
            """
        def _impl(name, visibility):
            print("in my_finalizer")
            native.filegroup(name = name + "_lib")

        my_finalizer = macro(implementation = _impl, finalizer = True)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":finalizers.bzl", "my_finalizer")
        my_finalizer(name = "finalize")
        filegroup(name = 1 // 0)  # causes EvalException
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val pkg: java.lang.Package = getPackage("pkg")
        Truth.assertThat(pkg).isNotNull()
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent("division by zero")
        assertDoesNotContainEvent("in my_finalizer")
        assertThat(pkg.getTargets().keySet()).doesNotContain("finalize_lib")
    }

    // Regression test for b/419523258.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalizerFailure_handledCleanly() {
        scratch.file(
            "pkg/finalizers.bzl",
            """
        def _fail_impl(name, visibility):
            fail("fail fail fail")

        def _good_impl(name, visibility):
            native.filegroup(name = name + "_lib")

        fail_finalizer = macro(implementation = _fail_impl, finalizer = True)
        good_finalizer = macro(implementation = _good_impl, finalizer = True)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":finalizers.bzl", "fail_finalizer", "good_finalizer")
        good_finalizer(name = "good_finalizer")
        fail_finalizer(name = "bad_finalizer")
        good_finalizer(name = "should_not_be_expanded")  # because it follows a failing one
        filegroup(name = "unrelated_target")  # evaluated before any finalizers
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val pkg: java.lang.Package = getPackage("pkg")
        Truth.assertThat(pkg).isNotNull()
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent("fail fail fail")
        assertThat(pkg.getTargets().keySet()).containsAtLeast("unrelated_target", "good_finalizer_lib")
        assertThat(pkg.getTargets().keySet()).doesNotContain("should_not_be_expanded_lib")
    }
}
