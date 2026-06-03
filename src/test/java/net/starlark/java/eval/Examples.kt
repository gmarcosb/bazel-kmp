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
package net.starlark.java.eval

import net.starlark.java.annot.Param

/**
 * Examples of typical API usage of the Starlark interpreter.<br></br>
 * This is not a test, but it is checked by the compiler.
 */
internal class Examples {
    /**
     * This example reads, parses, compiles, and executes a Starlark file. It returns the module,
     * which holds the values of global variables.
     */
    @Throws(
        IOException::class,
        net.starlark.java.syntax.SyntaxError.Exception::class,
        EvalException::class,
        java.lang.InterruptedException::class
    )
    fun execFile(filename: String?): java.lang.Module? {
        // Read input from the named file.
        val input: net.starlark.java.syntax.ParserInput = net.starlark.java.syntax.ParserInput.readFile(filename)

        // Create the module that will be populated by executing the file.
        // It holds the global variables, initially empty.
        // Its predeclared environment defines only the standard builtins:
        // None, True, len, and so on.
        val module: java.lang.Module? = java.lang.Module.create()

        Mutability.create(input.getFile()).use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            Starlark.execFile(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
        }
        return module
    }

    /**
     * This example evaluates a Starlark expression in the specified environment and returns its
     * value.
     */
    @Throws(
        net.starlark.java.syntax.SyntaxError.Exception::class,
        EvalException::class,
        java.lang.InterruptedException::class
    )
    fun evalExpr(expr: String?, env: com.google.common.collect.ImmutableMap<String?, Any?>?): Any {
        // The apparent file name (for error messages) will be "<expr>".
        val input: net.starlark.java.syntax.ParserInput =
            net.starlark.java.syntax.ParserInput.fromString(expr, "<expr>")

        // Create the module in which the expression is evaluated.
        // It may define additional predeclared environment bindings.
        val module: java.lang.Module? = java.lang.Module.withPredeclared(StarlarkSemantics.DEFAULT, env)

        Mutability.create(input.getFile()).use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            return Starlark.eval(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
        }
    }

    /**
     * This advanced example reads, parses, and compiles a Starlark file to a Program, then later
     * executes it.
     */
    @Throws(
        IOException::class,
        net.starlark.java.syntax.SyntaxError.Exception::class,
        EvalException::class,
        java.lang.InterruptedException::class
    )
    fun compileThenExecute(): java.lang.Module? {
        // Read and parse the named file.
        val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.readFile("my/file.star")
        val file: net.starlark.java.syntax.StarlarkFile? = net.starlark.java.syntax.StarlarkFile.parse(input)

        // Compile the program, with additional predeclared environment bindings.
        // TODO(adonovan): supply Starlark.UNIVERSE somehow.
        val prog: net.starlark.java.syntax.Program = net.starlark.java.syntax.Program.compileFile(
            file,
            net.starlark.java.syntax.TestUtils.Module.Companion.withPredeclared("zero", "square")
        )

        // . . .

        // TODO(adonovan): when supported, show how the compiled program can be
        // saved and reloaded, to avoid repeating the cost of parsing and
        // compilation.

        // Execute the compiled program to populate a module.
        // The module's predeclared environment must match the
        // names provided during compilation.
        val module: java.lang.Module? = java.lang.Module.withPredeclared(StarlarkSemantics.DEFAULT, makeEnvironment())
        Mutability.create(prog.getFilename()).use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            Starlark.execFileProgram(prog, module, thread)
        }
        return module
    }

    /** This function shows how to construct a callable Starlark value from a Java method.  */
    fun makeEnvironment(): com.google.common.collect.ImmutableMap<String?, Any?> {
        val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        env.put("zero", 0)
        Starlark.addMethods(env, MyFunctions(), StarlarkSemantics.DEFAULT) // adds 'square'
        return env.buildOrThrow()
    }

    /**
     * The annotated methods of this class are added to the environment by [ ][Starlark.addMethods].
     */
    internal class MyFunctions {
        @StarlarkMethod(
            name = "square",
            parameters = [Param(name = "x")],
            doc = "Returns the square of its integer argument."
        )
        fun square(x: StarlarkInt?): StarlarkInt {
            return StarlarkInt.multiply(x, x)
        }
    }
}
