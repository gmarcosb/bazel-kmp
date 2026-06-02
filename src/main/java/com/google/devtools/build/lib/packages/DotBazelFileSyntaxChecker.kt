// Copyright 2023 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadHostile

/**
 * A [NodeVisitor] that can be used to check that a Starlark AST conforms to the restricted
 * syntax that BUILD, WORKSPACE, REPO.bazel, and MODULE.bazel files use. This restricted syntax
 * disallows:
 * 
 * 
 *  * control-flow statements (`for` and `if`, but not comprehensions and `if`
 * expressions),
 *  * function definitions (`def` and `lambda`),
 *  * variadic arguments (`*args` and `**kwargs`) in function call sites, and
 *  * optionally, `load()` statements.
 * 
 */
@ThreadHostile
open class DotBazelFileSyntaxChecker @kotlin.jvm.JvmOverloads constructor(
    where: String?,
    canLoadBzl: Boolean,
    allowLiteralStarStarArgs: Boolean = false
) : net.starlark.java.syntax.NodeVisitor() {
    private val where: String?
    private val canLoadBzl: Boolean
    private val allowLiteralStarStarArgs: Boolean
    private var errors: com.google.common.collect.ImmutableList.Builder<net.starlark.java.syntax.SyntaxError?> =
        com.google.common.collect.ImmutableList.builder<net.starlark.java.syntax.SyntaxError?>()

    /**
     * @param where describes the type of file being checked.
     * @param canLoadBzl whether the file type being check supports load statements. This is used to
     * generate more informative error messages.
     * @param allowLiteralStarStarArgs whether to allow **kwargs in function calls if the dict is a
     * literal. This is needed for some functions that take arbitrary keyword arguments whose keys
     * may have to contain non-identifier characters.
     */
    init {
        this.where = where
        this.canLoadBzl = canLoadBzl
        this.allowLiteralStarStarArgs = allowLiteralStarStarArgs
    }

    @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
    fun check(file: net.starlark.java.syntax.StarlarkFile) {
        this.errors = com.google.common.collect.ImmutableList.builder<net.starlark.java.syntax.SyntaxError?>()
        visit(file)
        val errors: com.google.common.collect.ImmutableList<net.starlark.java.syntax.SyntaxError?> = this.errors.build()
        if (!errors.isEmpty()) {
            throw net.starlark.java.syntax.SyntaxError.Exception(errors)
        }
    }

    protected fun error(loc: net.starlark.java.syntax.Location?, message: String?) {
        errors.add(net.starlark.java.syntax.SyntaxError(loc, message))
    }

    // Reject f(*args) and f(**kwargs) calls.
    private fun rejectStarArgs(call: net.starlark.java.syntax.CallExpression) {
        for (arg in call.getArguments()) {
            if (arg is net.starlark.java.syntax.Argument.StarStar) {
                if (!allowLiteralStarStarArgs) {
                    error(
                        arg.getStartLocation(),
                        ("**kwargs arguments are not allowed in "
                                + where
                                + ". Pass the arguments in explicitly.")
                    )
                }
                if (arg.getValue() !is net.starlark.java.syntax.DictExpression) {
                    error(
                        arg.getStartLocation(),
                        "**kwargs arguments must be a literal dict in " + where + "."
                    )
                }
            } else if (arg is net.starlark.java.syntax.Argument.Star) {
                error(
                    arg.getStartLocation(),
                    "*args arguments are not allowed in " + where + ". Pass the arguments in explicitly."
                )
            }
        }
    }

    override fun visit(node: net.starlark.java.syntax.LoadStatement) {
        if (!canLoadBzl) {
            error(node.getStartLocation(), "`load` statements may not be used in " + where)
        }
    }

    // We prune the traversal if we encounter disallowed keywords, as we have already reported the
    // root error and there's no point reporting more.
    override fun visit(node: net.starlark.java.syntax.DefStatement) {
        error(
            node.getStartLocation(),
            ("functions may not be defined in "
                    + where
                    + (if (canLoadBzl) ". You may move the function to a .bzl file and load it." else "."))
        )
    }

    override fun visit(node: net.starlark.java.syntax.LambdaExpression) {
        error(
            node.getStartLocation(),
            ("functions may not be defined in "
                    + where
                    + (if (canLoadBzl) ". You may move the function to a .bzl file and load it." else "."))
        )
    }

    override fun visit(node: net.starlark.java.syntax.ForStatement) {
        error(
            node.getStartLocation(),
            ("`for` statements are not allowed in "
                    + where
                    + ". You may inline the loop"
                    + (if (canLoadBzl) ", move it to a function definition (in a .bzl file)," else "")
                    + " or as a last resort use a list comprehension.")
        )
    }

    override fun visit(node: net.starlark.java.syntax.IfStatement) {
        error(
            node.getStartLocation(),
            ("`if` statements are not allowed in "
                    + where
                    + ". You may"
                    + (if (canLoadBzl)
                " move conditional logic to a function definition (in a .bzl file), or"
            else
                "")
                    + " use an `if` expression for simple cases.")
        )
    }

    override fun visit(node: net.starlark.java.syntax.CallExpression) {
        rejectStarArgs(node)
        // Continue traversal so as not to miss nested calls
        // like cc_binary(..., f(**kwargs), ...).
        super.visit(node)
    }
}
