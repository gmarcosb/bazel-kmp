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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.cmdline.LabelConstants.COMMAND_LINE_OPTION_PREFIX

/**
 * Starlark output formatter for cquery results. Each configured target will result in an evaluation
 * of the Starlark expression specified by `--expr`.
 */
class StarlarkOutputFormatterCallback internal constructor(
    eventHandler: ExtendedEventHandler?,
    options: CqueryOptions,
    out: java.io.OutputStream?,
    skyframeExecutor: SkyframeExecutor?,
    accessor: TargetAccessor<CqueryNode?>?,
    starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
) : CqueryThreadsafeCallback(eventHandler, options, out, skyframeExecutor, accessor,  /* uniquifyResults= */false) {
    private inner class CqueryDialectGlobals {
        @net.starlark.java.annot.StarlarkMethod(
            name = "build_options",
            documented = false,
            parameters = [net.starlark.java.annot.Param(name = "target")]
        )
        fun buildOptions(target: CqueryNode): Any? {
            val config: BuildConfigurationValue? = getConfiguration(target.getConfigurationKey())

            if (config == null) {
                // config is null for input file configured targets.
                return net.starlark.java.eval.Starlark.NONE
            }

            val buildOptions: BuildOptions = config.getOptions()
            val result: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builder<String?, Any?>()

            // Add all build options from each native configuration fragment.
            for (fragmentOptions in buildOptions.getNativeOptions()) {
                val optionClass: java.lang.Class<out FragmentOptions?>? = fragmentOptions.getOptionsClass()
                for (def in com.google.devtools.common.options.OptionDefinition.getOptionDefinitions(optionClass)) {
                    val optionName: String = def.getOptionName()
                    val optionKey = COMMAND_LINE_OPTION_PREFIX + optionName

                    val options: FragmentOptions? = buildOptions.get(optionClass)
                    val optionValue: Any? = def.getValue(options)

                    try {
                        // fromJava is not a deep validity check.
                        // It is not guaranteed to catch all errors,
                        // nor does it specify how it reports the errors it does find.
                        // Passing arbitrary Java values into the Starlark interpreter
                        // is not safe.
                        // TODO(cparsons,twigg): fix it: convert value by explicit cases.
                        result.put(optionKey, net.starlark.java.eval.Starlark.fromJava(optionValue, null))
                    } catch (ex: java.lang.IllegalArgumentException) {
                        // optionValue is not a valid Starlark value, so skip this option.
                        // (e.g. tristate; a map with null values)
                    } catch (ex: java.lang.NullPointerException) {
                    }
                }
            }

            // Add Starlark options.
            for (e in buildOptions.getStarlarkOptions().entrySet()) {
                result.put(e.getKey().toString(), net.starlark.java.eval.Starlark.fromJava(e.getValue(), null))
            }
            return result.buildOrThrow()
        }

        @net.starlark.java.annot.StarlarkMethod(
            name = "providers",
            documented = false,
            parameters = [net.starlark.java.annot.Param(name = "target")]
        )
        fun providers(target: CqueryNode): Any? {
            val ret: net.starlark.java.eval.Dict<String?, Any?>? = target.getProvidersDictForQuery()
            if (ret == null) {
                return net.starlark.java.eval.Starlark.NONE
            }
            return ret
        }
    }

    // Starlark function with single required parameter "target", a CqueryNode query result.
    private val formatFn: net.starlark.java.eval.StarlarkFunction
    private val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?

    init {
        this.starlarkSemantics = starlarkSemantics

        var input: net.starlark.java.syntax.ParserInput? = null
        val exceptionMessagePrefix: String?
        if (!options.getFile().isEmpty()) {
            if (!options.getExpr().isEmpty()) {
                throw com.google.devtools.build.lib.query2.engine.QueryException(
                    "You must not specify both --starlark:expr and --starlark:file",
                    Query.Code.ILLEGAL_FLAG_COMBINATION
                )
            }
            exceptionMessagePrefix = "invalid --starlark:file: "
            try {
                input = net.starlark.java.syntax.ParserInput.readFile(options.getFile())
            } catch (ex: IOException) {
                throw com.google.devtools.build.lib.query2.engine.QueryException(
                    exceptionMessagePrefix + "failed to read " + ex.getMessage(),
                    Query.Code.QUERY_FILE_READ_FAILURE
                )
            }
        } else {
            exceptionMessagePrefix = "invalid --starlark:expr: "
            val expr = if (options.getExpr().isEmpty()) "str(target.label)" else options.getExpr()
            // Validate that options.expr is a pure expression (for example, that it does not attempt
            // to escape its scope via unbalanced parens).
            val exprParserInput: net.starlark.java.syntax.ParserInput =
                net.starlark.java.syntax.ParserInput.fromString(expr, "--starlark:expr")
            try {
                net.starlark.java.syntax.Expression.parse(exprParserInput)
            } catch (ex: net.starlark.java.syntax.SyntaxError.Exception) {
                throw com.google.devtools.build.lib.query2.engine.QueryException(
                    exceptionMessagePrefix + ex.getMessage(), ConfigurableQuery.Code.STARLARK_SYNTAX_ERROR
                )
            }

            // Create a synthetic file that defines a function with single parameter "target",
            // whose body is provided by the user's expression. Dynamic errors will have the wrong column.
            val fileBody = "def format(target): return (" + expr + ")"
            input = net.starlark.java.syntax.ParserInput.fromString(fileBody, "--starlark:expr")
        }

        val file: net.starlark.java.syntax.StarlarkFile =
            net.starlark.java.syntax.StarlarkFile.parse(input, net.starlark.java.syntax.FileOptions.DEFAULT)
        if (!file.ok()) {
            com.google.devtools.build.lib.events.Event.replayEventsOn(eventHandler, file.errors())
        }
        try {
            net.starlark.java.eval.Mutability.create("formatter").use { mu ->
                val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                    com.google.common.collect.ImmutableMap.builder<String?, Any?>()
                net.starlark.java.eval.Starlark.addMethods(env, CqueryDialectGlobals(), starlarkSemantics)
                env.putAll(StarlarkGlobalsImpl.INSTANCE.getUtilToplevelsForCquery())
                val module: net.starlark.java.eval.Module =
                    net.starlark.java.eval.Module.withPredeclared(starlarkSemantics, env.buildOrThrow())

                val thread: net.starlark.java.eval.StarlarkThread =
                    net.starlark.java.eval.StarlarkThread.createTransient(mu, starlarkSemantics)
                net.starlark.java.eval.Starlark.execFile(
                    input,
                    net.starlark.java.syntax.FileOptions.DEFAULT,
                    module,
                    thread
                )
                val formatFn: Any? = module.getGlobal("format")
                if (formatFn == null) {
                    throw com.google.devtools.build.lib.query2.engine.QueryException(
                        exceptionMessagePrefix + "file does not define 'format'",
                        ConfigurableQuery.Code.FORMAT_FUNCTION_ERROR
                    )
                }
                if (formatFn !is net.starlark.java.eval.StarlarkFunction) {
                    throw com.google.devtools.build.lib.query2.engine.QueryException(
                        (exceptionMessagePrefix
                                + "got "
                                + net.starlark.java.eval.Starlark.type(formatFn)
                                + " for 'format', want function"),
                        ConfigurableQuery.Code.FORMAT_FUNCTION_ERROR
                    )
                }
                this.formatFn = formatFn as net.starlark.java.eval.StarlarkFunction
                if (this.formatFn.getParameterNames().size() != 1) {
                    throw com.google.devtools.build.lib.query2.engine.QueryException(
                        exceptionMessagePrefix + "'format' function must take exactly 1 argument",
                        ConfigurableQuery.Code.FORMAT_FUNCTION_ERROR
                    )
                }
            }
        } catch (ex: net.starlark.java.syntax.SyntaxError.Exception) {
            throw com.google.devtools.build.lib.query2.engine.QueryException(
                exceptionMessagePrefix + ex.getMessage(), ConfigurableQuery.Code.STARLARK_SYNTAX_ERROR
            )
        } catch (ex: net.starlark.java.eval.EvalException) {
            throw com.google.devtools.build.lib.query2.engine.QueryException(
                exceptionMessagePrefix + ex.getMessageWithStack(),
                ConfigurableQuery.Code.STARLARK_EVAL_ERROR
            )
        }
    }

    val name: String
        get() = "starlark"

    @Throws(java.lang.InterruptedException::class)
    override fun processOutput(partialResult: Iterable<CqueryNode>) {
        for (target in partialResult) {
            try {
                val thread: net.starlark.java.eval.StarlarkThread =
                    net.starlark.java.eval.StarlarkThread.createTransient(
                        net.starlark.java.eval.Mutability.create("cquery evaluation"), starlarkSemantics
                    )
                thread.setMaxExecutionSteps(500000L)

                // Invoke formatFn with `target` argument.
                val result: Any? = net.starlark.java.eval.Starlark.positionalOnlyCall(thread, this.formatFn, target)

                addResult(net.starlark.java.eval.Starlark.str(result, thread.getSemantics()))
            } catch (ex: net.starlark.java.eval.EvalException) {
                eventHandler.handle(
                    com.google.devtools.build.lib.events.Event.error(
                        java.lang.String.format(
                            "Starlark evaluation error for %s: %s",
                            target.getOriginalLabel(), ex.getMessageWithStack()
                        )
                    )
                )
                continue
            }
        }
    }
}
