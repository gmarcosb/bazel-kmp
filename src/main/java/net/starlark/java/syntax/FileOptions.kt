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
package net.starlark.java.syntax

import com.google.auto.value.AutoValue

/**
 * FileOptions is a set of options that affect the static processing---scanning, parsing, validation
 * (identifier resolution), and compilation---of a single Starlark file. These options affect the
 * language accepted by the frontend (in effect, the dialect), and "code generation", analogous to
 * the command-line options of a typical compiler.
 * 
 * 
 * Different files within the same application and even executed within the same thread may be
 * subject to different file options. For example, in Bazel, load statements in WORKSPACE files may
 * need to be interleaved with other statements, whereas in .bzl files, load statements must appear
 * all at the top. A single thread may execute a WORKSPACE file and call functions defined in .bzl
 * files.
 * 
 * 
 * The [.DEFAULT] options represent the desired behavior for new uses of Starlark. It is a
 * goal to keep this set of options small and closed. Each represents a language feature, perhaps a
 * deprecated, obscure, or regrettable one. By contrast, [StarlarkSemantics] defines a
 * (soon-to-be) open-ended set of options that affect the dynamic behavior of Starlark threads and
 * (mostly application-defined) built-in functions, and particularly attribute selection operations
 * `x.f`.
 */
@AutoValue
abstract class FileOptions {
    /**
     * During resolution, permit load statements to access private names such as `_x`. <br></br>
     * (Required for continued support of Bazel "WORKSPACE.resolved" files.)
     */
    abstract fun allowLoadPrivateSymbols(): Boolean

    /**
     * During resolution, permit multiple assignments to a given top-level binding, whether file-local
     * or global. However, as usual, you may not create both a file-local and a global binding of the
     * same name (e.g. `load(..., x="x"); x=1`), so if you use this option, you probably want
     * [.loadBindsGlobally] too, to avoid confusing errors. <br></br>
     * (Required for continued support of Bazel BUILD files and Copybara files.)
     */
    abstract fun allowToplevelRebinding(): Boolean

    /**
     * During resolution, make load statements bind global variables of the module, not file-local
     * variables.<br></br>
     * (Intended for use in REPLs, and the Bazel prelude; and in Bazel BUILD files, which make
     * frequent use of `load(..., "x"); x=1` for reasons unclear.)
     */
    abstract fun loadBindsGlobally(): Boolean

    /**
     * During resolution, require load statements to appear before other kinds of statements. <br></br>
     * (Required for continued support of Bazel BUILD and especially WORKSPACE files.)
     */
    abstract fun requireLoadStatementsFirst(): Boolean

    /**
     * During lexing, whether to ban non-ASCII characters (i.e., characters with code point > U+7F) in
     * string literals.
     * 
     * 
     * This applies to string literals' raw content as well as escape sequences.
     */
    abstract fun stringLiteralsAreAsciiOnly(): Boolean

    /** Whether type annotations and related syntax are allowed in the source code.  */
    abstract fun allowTypeSyntax(): Boolean

    /**
     * Whether type names in type annotations are processed by the resolver.
     * 
     * 
     * This is required for any form of type checking, but will cause code to fail if it contains
     * type annotations that are not understood by this version of Bazel.
     */
    abstract fun resolveTypeSyntax(): Boolean

    /**
     * If true, type expressions in annotations and `type` declarations may be any valid
     * expression (except for unparenthesized tuples, which are grammatically ambiguous). Otherwise
     * type expressions must represent a valid type.
     * 
     * 
     * Enabling this boolean is helpful for backwards compatibility, but results in an AST that is
     * not usable for type checking.
     * 
     * 
     * This has no effect if [.allowTypeSyntax] is false.
     */
    abstract fun tolerateInvalidTypeExpressions(): Boolean

    abstract fun toBuilder(): Builder?

    /** This javadoc comment states that FileOptions.Builder is a builder for FileOptions.  */
    @AutoValue.Builder
    abstract class Builder {
        // AutoValue why u make me say it 3 times?
        abstract fun allowLoadPrivateSymbols(value: Boolean): Builder?

        abstract fun allowToplevelRebinding(value: Boolean): Builder?

        abstract fun loadBindsGlobally(value: Boolean): Builder?

        abstract fun requireLoadStatementsFirst(value: Boolean): Builder?

        abstract fun stringLiteralsAreAsciiOnly(value: Boolean): Builder?

        abstract fun allowTypeSyntax(value: Boolean): Builder?

        abstract fun resolveTypeSyntax(value: Boolean): Builder?

        abstract fun tolerateInvalidTypeExpressions(value: Boolean): Builder?

        abstract fun build(): FileOptions?
    }

    companion object {
        /** The default options for Starlark static processing. New clients should use these defaults.  */
        @kotlin.jvm.JvmField
        val DEFAULT: FileOptions? = builder().build()

        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            // These are the DEFAULT values.
            return Builder()
                .allowLoadPrivateSymbols(false)
                .allowToplevelRebinding(false)
                .loadBindsGlobally(false)
                .requireLoadStatementsFirst(true)
                .stringLiteralsAreAsciiOnly(false)
                .allowTypeSyntax(false)
                .resolveTypeSyntax(false)
                .tolerateInvalidTypeExpressions(false)!!
        }
    }
}
