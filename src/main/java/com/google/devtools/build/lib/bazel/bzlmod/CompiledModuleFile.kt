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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code

/**
 * Represents a compiled MODULE.bazel file, ready to be executed on a [StarlarkThread]. It's
 * been successfully checked for syntax errors.
 * 
 * 
 * Use the [.parseAndCompile] factory method instead of directly instantiating this record.
 */
class CompiledModuleFile(
    moduleFile: ModuleFile?,
    program: net.starlark.java.syntax.Program?,
    predeclaredEnv: net.starlark.java.eval.Module?,
    includeStatements: com.google.common.collect.ImmutableList<IncludeStatement?>?
) {
    internal class IncludeStatement(val includeLabel: String?, location: net.starlark.java.syntax.Location?) {
        val location: net.starlark.java.syntax.Location?

        init {
            this.location = location
        }
    }

    private class SyntaxChecker :
        DotBazelFileSyntaxChecker("MODULE.bazel files",  /* canLoadBzl= */false,  /* allowLiteralStarStarArgs= */true) {
        val includeStatements: com.google.common.collect.ImmutableList.Builder<IncludeStatement?> =
            com.google.common.collect.ImmutableList.builder<IncludeStatement?>()

        // Once `include` the identifier is assigned to, we no longer care about its appearance
        // anywhere. This allows `include` to be used as a module extension proxy (and technically
        // any other variable binding).
        private var includeWasAssigned = false

        init {
            // Don't pick up uses of "include" in keyword args or object fields.
            this.skipNonSymbolIdentifiers = true
        }

        override fun visit(node: net.starlark.java.syntax.ExpressionStatement) {
            // We can assume this statement isn't nested in any block, since we don't allow
            // `if`/`def`/`for` in MODULE.bazel.
            if (!includeWasAssigned && node.getExpression() is net.starlark.java.syntax.CallExpression
                && call.getFunction() is net.starlark.java.syntax.Identifier
                && id.getName() == INCLUDE_IDENTIFIER
            ) {
                // Found a top-level call to `include`!
                if (call.getArguments().size() == 1 && call.getArguments()
                        .getFirst() is net.starlark.java.syntax.Argument.Positional
                    && pos.getValue() is net.starlark.java.syntax.StringLiteral
                ) {
                    includeStatements.add(IncludeStatement(str.getValue(), call.getStartLocation()))
                    // Nothing else to check, we can stop visiting sub-nodes now.
                    return
                }
                error(
                    node.getStartLocation(),
                    "the `include` directive MUST be called with exactly one positional argument that "
                            + "is a string literal"
                )
                return
            }
            super.visit(node)
        }

        override fun visit(node: net.starlark.java.syntax.AssignmentStatement) {
            visit(node.getRHS())
            if (!includeWasAssigned && node.getLHS() is net.starlark.java.syntax.Identifier
                && id.getName() == INCLUDE_IDENTIFIER
            ) {
                includeWasAssigned = true
                // Technically someone could do something like
                //   (include, myvar) = (print, 3)
                // and work around our check, but at that point IDGAF.
            } else {
                visit(node.getLHS())
            }
        }

        override fun visit(node: net.starlark.java.syntax.Identifier) {
            if (!includeWasAssigned && node.getName() == INCLUDE_IDENTIFIER) {
                // If we somehow reach the `include` identifier but NOT as the other allowed cases above,
                // cry foul.
                error(
                    node.getStartLocation(),
                    "the `include` directive MUST be called directly at the top-level"
                )
            }
            super.visit(node)
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun runOnThread(thread: net.starlark.java.eval.StarlarkThread?) {
        net.starlark.java.eval.Starlark.execFileProgram(program, predeclaredEnv, thread)
    }

    val moduleFile: ModuleFile?
    val program: net.starlark.java.syntax.Program?
    val predeclaredEnv: net.starlark.java.eval.Module?
    val includeStatements: com.google.common.collect.ImmutableList<IncludeStatement?>?

    init {
        this.moduleFile = moduleFile
        this.program = program
        this.predeclaredEnv = predeclaredEnv
        this.includeStatements = includeStatements
    }

    companion object {
        const val INCLUDE_IDENTIFIER: String = "include"

        /** Parses and compiles a given module file, checking it for syntax errors.  */
        @Throws(ExternalDepsException::class)
        fun parseAndCompile(
            moduleFile: ModuleFile,
            moduleKey: ModuleKey?,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics,
            starlarkEnv: BazelStarlarkEnvironment,
            eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?
        ): CompiledModuleFile {
            val parserInput: net.starlark.java.syntax.ParserInput?
            try {
                parserInput =
                    com.google.devtools.build.lib.skyframe.StarlarkUtil.createParserInput(
                        moduleFile.getContent(),
                        moduleFile.getLocation(),
                        starlarkSemantics.get<Utf8EnforcementMode?>(BuildLanguageOptions.INCOMPATIBLE_ENFORCE_STARLARK_UTF8),
                        eventHandler
                    )
            } catch (e: InvalidUtf8Exception) {
                throw withMessage(
                    Code.BAD_MODULE, "error reading MODULE.bazel file for %s", moduleKey
                )
            }
            val starlarkFile: net.starlark.java.syntax.StarlarkFile =
                net.starlark.java.syntax.StarlarkFile.parse(parserInput)
            if (!starlarkFile.ok()) {
                com.google.devtools.build.lib.events.Event.replayEventsOn(eventHandler, starlarkFile.errors())
                throw withMessage(
                    Code.BAD_MODULE, "error parsing MODULE.bazel file for %s", moduleKey
                )
            }
            try {
                val includeStatements: com.google.common.collect.ImmutableList<IncludeStatement?> =
                    checkModuleFileSyntax(starlarkFile)
                val predeclaredEnv: net.starlark.java.eval.Module =
                    net.starlark.java.eval.Module.withPredeclared(starlarkSemantics, starlarkEnv.getModuleBazelEnv())
                val program: net.starlark.java.syntax.Program =
                    net.starlark.java.syntax.Program.compileFile(starlarkFile, predeclaredEnv)
                return CompiledModuleFile(moduleFile, program, predeclaredEnv, includeStatements)
            } catch (e: net.starlark.java.syntax.SyntaxError.Exception) {
                com.google.devtools.build.lib.events.Event.replayEventsOn(eventHandler, e.errors())
                throw withMessage(
                    Code.BAD_MODULE, "syntax error in MODULE.bazel file for %s", moduleKey
                )
            }
        }

        /**
         * Checks the given `starlarkFile` for module file syntax, and returns the list of `include`
         * statements it contains. This is a somewhat crude sweep over the AST; we loudly complain about
         * any usage of `include` that is not in a top-level function call statement with one single
         * string literal positional argument, *except* that we don't do this check once `include` is
         * assigned to, due to backwards compatibility concerns.
         */
        @com.google.common.annotations.VisibleForTesting
        @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
        fun checkModuleFileSyntax(starlarkFile: net.starlark.java.syntax.StarlarkFile?): com.google.common.collect.ImmutableList<IncludeStatement?> {
            val checker = SyntaxChecker()
            checker.check(starlarkFile)
            return checker.includeStatements.build()
        }
    }
}
