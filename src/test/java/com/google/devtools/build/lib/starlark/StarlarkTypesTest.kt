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
package com.google.devtools.build.lib.starlark

import com.google.devtools.build.lib.analysis.util.AnalysisTestCase.getTarget
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.checkLoadingPhaseError
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.getTarget
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.invalidatePackages
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.setBuildLanguageOptions
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for Starlark types.  */
@RunWith(JUnit4::class)
class StarlarkTypesTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun experimentalStarlarkTypes_on_allowsTypeAnnotations() {
        setBuildLanguageOptions(
            "--experimental_starlark_type_syntax",
            "--experimental_starlark_types_allowed_paths=//test"
        )
        scratch.file(
            "test/foo.bzl",
            """
        def f(a: int):
          pass
          """.trimIndent()
        )
        scratch.file("test/BUILD", "load(':foo.bzl', 'f')")

        getTarget("//test:BUILD")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun experimentalStarlarkTypes_off_disallowsTypeAnnotations() {
        setBuildLanguageOptions(
            "--noexperimental_starlark_type_syntax",
            "--experimental_starlark_types_allowed_paths=//test"
        )
        scratch.file(
            "test/foo.bzl",
            """
        def f(a: int):
          pass
          """.trimIndent()
        )
        scratch.file("test/BUILD", "load(':foo.bzl', 'f')")

        checkLoadingPhaseError("//test:BUILD", "syntax error at ':': type annotations are disallowed")
        assertContainsEvent(
            "Type annotations syntax can be enabled with --experimental_starlark_type_syntax and/or"
                    + " --experimental_starlark_types_allowed_paths."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun experimentalStarlarkTypes_prohibitedInSclRegardlessOfFlag() {
        setBuildLanguageOptions("--experimental_starlark_type_syntax")
        scratch.file(
            "test/foo.scl",
            """
        def f(a: int):
          pass
          """.trimIndent()
        )
        scratch.file("test/BUILD", "load(':foo.scl', 'f')")

        checkLoadingPhaseError("//test:BUILD", "syntax error at ':': type annotations are disallowed")
        assertContainsEvent("Type annotations are not permitted in .scl files.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkTypesAllowedPath_notOnPath_disallowsTypeAnnotations() {
        setBuildLanguageOptions(
            "--experimental_starlark_type_syntax",
            "--experimental_starlark_types_allowed_paths=//main"
        )
        scratch.file(
            "test/foo.bzl",
            """
        def f(a: int):
          pass
          """.trimIndent()
        )
        scratch.file("test/BUILD", "load(':foo.bzl', 'f')")

        checkLoadingPhaseError("//test:BUILD", "syntax error at ':': type annotations are disallowed")
        assertContainsEvent(
            "Type annotations syntax can be enabled with --experimental_starlark_type_syntax and/or"
                    + " --experimental_starlark_types_allowed_paths."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkTypesAllowedPath_externalPath_allowsTypeAnnotations() {
        setBuildLanguageOptions(
            "--experimental_starlark_type_syntax",
            "--experimental_starlark_types_allowed_paths=@@r+//test"
        )
        scratch.overwriteFile(
            "MODULE.bazel", "bazel_dep(name='r')", "local_path_override(module_name='r', path='/r')"
        )
        scratch.file("/r/MODULE.bazel", "module(name='r')")
        scratch.file(
            "/r/test/foo.bzl",
            """
        def f(a: int):
          pass
          """.trimIndent()
        )
        scratch.file("/r/test/BUILD", "load(':foo.bzl', 'f')")

        // Required since we have a new MODULE.bazel file.
        invalidatePackages(true)
        getTarget("@@r+//test:BUILD")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun typeResolverDoesNotRunByDefault() {
        // If the type resolver were running, it'd complain about the var annotation after x has already
        // been assigned to.
        setBuildLanguageOptions("--experimental_starlark_type_syntax")
        scratch.file(
            "test/foo.bzl",
            """
        def f():
            x = 1
            x : int
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(":foo.bzl", "f")
        
        """.trimIndent()
        )

        getTarget("//test:BUILD")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun typeResolverDoesRunWithDynamicTypeCheckingFlag() {
        setBuildLanguageOptions(
            "--experimental_starlark_type_syntax", "--experimental_starlark_dynamic_type_checking"
        )
        scratch.file(
            "test/foo.bzl",
            """
        def f():
            x = 1
            x : int
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(":foo.bzl", "f")
        
        """.trimIndent()
        )

        checkLoadingPhaseError(
            "//test:BUILD", "type annotation on 'x' may only appear at its declaration"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun staticTypeCheckingDoesNotRunByDefault() {
        setBuildLanguageOptions("--experimental_starlark_type_syntax")
        scratch.file(
            "test/foo.bzl",
            """
        x: int = "a"
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(":foo.bzl", "x")
        
        """.trimIndent()
        )

        getTarget("//test:BUILD")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun staticTypeCheckingDoesRunWithStaticTypeCheckingFlag() {
        setBuildLanguageOptions(
            "--experimental_starlark_type_syntax", "--experimental_starlark_static_type_checking"
        )
        scratch.file(
            "test/foo.bzl",
            """
        x: int = "a"
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(":foo.bzl", "x")
        
        """.trimIndent()
        )

        checkLoadingPhaseError("//test:BUILD", "cannot assign type 'str' to 'x' of type 'int'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dynamicTypeCheckingDoesNotRunByDefault() {
        setBuildLanguageOptions("--experimental_starlark_type_syntax")
        scratch.file(
            "test/foo.bzl",
            """
        def f(x: int):
            pass
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(":foo.bzl", "f")
        f("abc")
        
        """.trimIndent()
        )

        getTarget("//test:BUILD")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dynamicTypeCheckingDoesRunWithDynamicTypeCheckingFlag() {
        setBuildLanguageOptions(
            "--experimental_starlark_type_syntax", "--experimental_starlark_dynamic_type_checking"
        )
        scratch.file(
            "test/foo.bzl",
            """
        def f(x: int):
            pass
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(":foo.bzl", "f")
        f("abc")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getTarget("//test:BUILD")
        assertContainsEvent("in call to f(), parameter 'x' got value of type 'str', want 'int'")
    }
}
