// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.math.BigInteger
import java.util.LinkedHashMap

internal object Eval {
    // ---- entry point ----
    // Called from StarlarkFunction.call().
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun execFunctionBody(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        statements: MutableList<net.starlark.java.syntax.Statement>
    ): Any? {
        fr.thread.checkInterrupt()
        net.starlark.java.eval.Eval.execStatements(fr, statements,  /* indented= */false)
        return fr.result
    }

    private fun fn(fr: net.starlark.java.eval.StarlarkThread.Frame): net.starlark.java.eval.StarlarkFunction {
        return fr.fn as net.starlark.java.eval.StarlarkFunction
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun execStatements(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        statements: MutableList<net.starlark.java.syntax.Statement>,
        indented: Boolean
    ): net.starlark.java.syntax.TokenKind? {
        val isToplevelFunction: Boolean = net.starlark.java.eval.Eval.fn(fr).isToplevel()

        // Hot code path, good chance of short lists which don't justify the iterator overhead.
        for (i in statements.indices) {
            val stmt: net.starlark.java.syntax.Statement = statements.get(i)
            val flow: net.starlark.java.syntax.TokenKind? = net.starlark.java.eval.Eval.exec(fr, stmt)
            if (flow != net.starlark.java.syntax.TokenKind.PASS) {
                return flow
            }

            // Hack for BzlLoadFunction's "export" semantics.
            // We enable it only for statements outside any function (isToplevelFunction)
            // and outside any if- or for- statements (!indented).
            if (isToplevelFunction && !indented && fr.thread.postAssignHook != null) {
                if (stmt is net.starlark.java.syntax.AssignmentStatement) {
                    for (id in net.starlark.java.syntax.Identifier.boundIdentifiers(stmt.getLHS())) {
                        val value: Any? = net.starlark.java.eval.Eval.fn(fr).getGlobal(id.getBinding().getIndex())
                        // TODO(bazel-team): Instead of special casing StarlarkFunction, make it implement
                        // StarlarkExportable.
                        if (value is net.starlark.java.eval.StarlarkFunction) {
                            // Optimization: The id token of a StarlarkFunction should be based on its global
                            // identifier when available. This enables an name-based lookup on deserialization.
                            value.export(fr.thread, id.getName())
                        } else {
                            fr.thread.postAssignHook.assign(id.getName(), id.getStartLocation(), value)
                        }
                    }
                } else if (stmt is net.starlark.java.syntax.DefStatement) {
                    val id: net.starlark.java.syntax.Identifier = stmt.getIdentifier()
                    (net.starlark.java.eval.Eval.fn(fr)
                        .getGlobal(id.getBinding().getIndex()) as net.starlark.java.eval.StarlarkFunction)
                        .export(fr.thread, id.getName())
                }
            }
        }
        return net.starlark.java.syntax.TokenKind.PASS
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun execAssignment(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        node: net.starlark.java.syntax.AssignmentStatement
    ) {
        try {
            if (node.isAugmented()) {
                net.starlark.java.eval.Eval.execAugmentedAssignment(fr, node)
            } else {
                val rvalue: Any = net.starlark.java.eval.Eval.eval(fr, node.getRHS())
                net.starlark.java.eval.Eval.assign(fr, node.getLHS(), rvalue)
            }
        } catch (ex: net.starlark.java.eval.EvalException) {
            fr.setErrorLocation(node.getOperatorLocation())
            throw ex
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun execFor(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        node: net.starlark.java.syntax.ForStatement
    ): net.starlark.java.syntax.TokenKind {
        val seq: Iterable<*> = net.starlark.java.eval.Eval.evalAsIterable(fr, node.getCollection())
        net.starlark.java.eval.EvalUtils.addIterator(seq)
        try {
            for (it in seq) {
                net.starlark.java.eval.Eval.assign(fr, node.getVars(), it)

                when (net.starlark.java.eval.Eval.execStatements(fr, node.getBody(),  /* indented= */true)) {
                    net.starlark.java.syntax.TokenKind.PASS, net.starlark.java.syntax.TokenKind.CONTINUE -> {
                        // Stay in loop.
                        fr.thread.checkInterrupt()
                        continue
                    }

                    net.starlark.java.syntax.TokenKind.BREAK ->             // Finish loop, execute next statement after loop.
                        return net.starlark.java.syntax.TokenKind.PASS

                    net.starlark.java.syntax.TokenKind.RETURN ->             // Finish loop, return from function.
                        return net.starlark.java.syntax.TokenKind.RETURN

                    else -> throw java.lang.IllegalStateException("unreachable")
                }
            }
        } catch (ex: net.starlark.java.eval.EvalException) {
            fr.setErrorLocation(node.getStartLocation())
            throw ex
        } finally {
            net.starlark.java.eval.EvalUtils.removeIterator(seq)
        }
        return net.starlark.java.syntax.TokenKind.PASS
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun newFunction(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        rfn: net.starlark.java.syntax.Resolver.Function
    ): net.starlark.java.eval.StarlarkFunction {
        // Evaluate default value expressions of optional parameters.
        // We use MANDATORY to indicate a required parameter
        // (not null, because defaults must be a legal tuple value, as
        // it will be constructed by the code emitted by the compiler).
        // As an optimization, we omit the prefix of MANDATORY parameters.
        var defaults: Array<Any?>? = null
        val nparams: Int =
            rfn.getParameters().size() - (if (rfn.hasKwargs()) 1 else 0) - (if (rfn.hasVarargs()) 1 else 0)

        // Nested functions use the same typeTable as their enclosing function, since both were compiled
        // from the same Program.
        val fn: net.starlark.java.eval.StarlarkFunction = net.starlark.java.eval.Eval.fn(fr)
        val functionType: net.starlark.java.syntax.Types.CallableType? =
            if (fn.getTypeTable() == null) null else fn.getTypeTable().getType(rfn)
        val dynamicTypeCheckingEnabled: Boolean =
            fr.thread
                .getSemantics()
                .getBool(net.starlark.java.eval.StarlarkSemantics.Companion.EXPERIMENTAL_STARLARK_DYNAMIC_TYPE_CHECKING)
        for (i in 0..<nparams) {
            val expr: net.starlark.java.syntax.Expression? = rfn.getParameters().get(i).getDefaultValue()
            if (expr == null && defaults == null) {
                continue  // skip prefix of required parameters
            }
            if (defaults == null) {
                defaults = arrayOfNulls<Any>(nparams - i)
            }
            val defaultValue: Any =
                if (expr == null) net.starlark.java.eval.StarlarkFunction.Companion.MANDATORY else net.starlark.java.eval.Eval.eval(
                    fr,
                    expr
                )
            defaults[i - (nparams - defaults.size)] = defaultValue

            if (dynamicTypeCheckingEnabled && functionType != null) {
                // Typecheck the default value
                val parameterType: net.starlark.java.syntax.StarlarkType? = functionType.getParameterTypeByPos(i)
                if (!net.starlark.java.eval.TypeChecker.isValueSubtypeOf(
                        defaultValue,
                        parameterType,
                        fr.thread.getSemantics()
                    )
                ) {
                    throw net.starlark.java.eval.Starlark.Companion.errorf(
                        "%s(): parameter '%s' has default value of type '%s', declares '%s'",
                        rfn.getName(),
                        rfn.getParameterNames().get(i),
                        net.starlark.java.eval.Starlark.Companion.getStarlarkType(
                            defaultValue,
                            fr.thread.getSemantics()
                        ),
                        parameterType
                    )
                }
            }
        }
        if (defaults == null) {
            defaults = net.starlark.java.eval.Eval.EMPTY
        }

        // Capture the cells of the function's
        // free variables from the lexical environment.
        val freevars = arrayOfNulls<Any>(rfn.getFreeVars().size())
        var i = 0
        for (bind in rfn.getFreeVars()) {
            // Unlike expr(Identifier), we want the cell itself, not its content.
            when (bind.getScope()) {
                net.starlark.java.syntax.Resolver.Scope.FREE -> freevars[i++] =
                    net.starlark.java.eval.Eval.fn(fr).getFreeVar(bind.getIndex())

                net.starlark.java.syntax.Resolver.Scope.CELL -> freevars[i++] = fr.locals[bind.getIndex()]
                else -> throw java.lang.IllegalStateException("unexpected: " + bind)
            }
        }

        // Nested functions use the same globalIndex as their enclosing function,
        // since both were compiled from the same Program.
        return net.starlark.java.eval.StarlarkFunction(
            rfn,
            fn.getTypeTable(),
            fn.getModule(),
            fn.globalIndex,
            net.starlark.java.eval.Tuple.Companion.wrap(defaults),
            net.starlark.java.eval.Tuple.Companion.wrap(freevars),
            fr.thread.getNextIdentityToken()
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun execIf(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        node: net.starlark.java.syntax.IfStatement
    ): net.starlark.java.syntax.TokenKind? {
        val cond: Boolean =
            net.starlark.java.eval.Starlark.Companion.truth(net.starlark.java.eval.Eval.eval(fr, node.getCondition()))
        if (cond) {
            return net.starlark.java.eval.Eval.execStatements(fr, node.getThenBlock(),  /* indented= */true)
        } else if (node.getElseBlock() != null) {
            return net.starlark.java.eval.Eval.execStatements(fr, node.getElseBlock(),  /* indented= */true)
        }
        return net.starlark.java.syntax.TokenKind.PASS
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun execLoad(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        node: net.starlark.java.syntax.LoadStatement
    ) {
        // Has the application defined a behavior for load statements in this thread?
        val loader: net.starlark.java.eval.StarlarkThread.Loader? = fr.thread.getLoader()
        if (loader == null) {
            fr.setErrorLocation(node.getStartLocation())
            throw net.starlark.java.eval.Starlark.Companion.errorf("load statements may not be executed in this thread")
        }

        // Load module.
        val moduleName: String = node.getImport().getValue()
        val module: net.starlark.java.eval.Module? = loader.load(moduleName)
        if (module == null) {
            fr.setErrorLocation(node.getStartLocation())
            throw net.starlark.java.eval.Starlark.Companion.errorf("module '%s' not found", moduleName)
        }

        for (binding in node.getBindings()) {
            // Extract symbol.
            val orig: net.starlark.java.syntax.Identifier = binding.getOriginalName()
            val value: Any? = module.getGlobal(orig.getName())
            if (value == null) {
                fr.setErrorLocation(orig.getStartLocation())
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "file '%s' does not contain symbol '%s'%s",
                    moduleName,
                    orig.getName(),
                    net.starlark.java.spelling.SpellChecker.didYouMean(orig.getName(), module.getGlobals().keySet())
                )
            }

            net.starlark.java.eval.Eval.assignIdentifier(fr, binding.getLocalName(), value)
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun execReturn(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        node: net.starlark.java.syntax.ReturnStatement
    ): net.starlark.java.syntax.TokenKind {
        val result: net.starlark.java.syntax.Expression? = node.getResult()
        if (result != null) {
            fr.result = net.starlark.java.eval.Eval.eval(fr, result)
        }
        return net.starlark.java.syntax.TokenKind.RETURN
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun exec(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        st: net.starlark.java.syntax.Statement
    ): net.starlark.java.syntax.TokenKind? {
        if (fr.dbg != null) {
            val loc: net.starlark.java.syntax.Location = st.getStartLocation() // not very precise
            fr.setLocation(loc)
            fr.dbg.before(fr.thread, loc) // location is now redundant since it's in the thread
        }

        if (++fr.thread.steps >= fr.thread.stepLimit) {
            throw net.starlark.java.eval.EvalException("Starlark computation cancelled: too many steps")
        }

        when (st.kind()) {
            net.starlark.java.syntax.Statement.Kind.ASSIGNMENT -> {
                net.starlark.java.eval.Eval.execAssignment(fr, st as net.starlark.java.syntax.AssignmentStatement)
                return net.starlark.java.syntax.TokenKind.PASS
            }

            net.starlark.java.syntax.Statement.Kind.EXPRESSION -> {
                net.starlark.java.eval.Eval.eval(
                    fr,
                    (st as net.starlark.java.syntax.ExpressionStatement).getExpression()
                )
                return net.starlark.java.syntax.TokenKind.PASS
            }

            net.starlark.java.syntax.Statement.Kind.FLOW -> return (st as net.starlark.java.syntax.FlowStatement).getFlowKind()
            net.starlark.java.syntax.Statement.Kind.FOR -> return net.starlark.java.eval.Eval.execFor(
                fr,
                st as net.starlark.java.syntax.ForStatement
            )

            net.starlark.java.syntax.Statement.Kind.DEF -> {
                val def: net.starlark.java.syntax.DefStatement = st as net.starlark.java.syntax.DefStatement
                val fn: net.starlark.java.eval.StarlarkFunction =
                    net.starlark.java.eval.Eval.newFunction(fr, def.getResolvedFunction())
                net.starlark.java.eval.Eval.assignIdentifier(fr, def.getIdentifier(), fn)
                return net.starlark.java.syntax.TokenKind.PASS
            }

            net.starlark.java.syntax.Statement.Kind.IF -> return net.starlark.java.eval.Eval.execIf(
                fr,
                st as net.starlark.java.syntax.IfStatement
            )

            net.starlark.java.syntax.Statement.Kind.LOAD -> {
                net.starlark.java.eval.Eval.execLoad(fr, st as net.starlark.java.syntax.LoadStatement)
                return net.starlark.java.syntax.TokenKind.PASS
            }

            net.starlark.java.syntax.Statement.Kind.RETURN -> return net.starlark.java.eval.Eval.execReturn(
                fr,
                st as net.starlark.java.syntax.ReturnStatement
            )

            net.starlark.java.syntax.Statement.Kind.TYPE_ALIAS -> return net.starlark.java.syntax.TokenKind.PASS
            net.starlark.java.syntax.Statement.Kind.VAR -> return net.starlark.java.syntax.TokenKind.PASS
        }
        throw java.lang.IllegalArgumentException("unexpected statement: " + st.kind())
    }

    /**
     * Updates the environment bindings, and possibly mutates objects, so as to assign the given value
     * to the given expression. Might not set the frame location on error.
     */
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun assign(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        lhs: net.starlark.java.syntax.Expression?,
        value: Any
    ) {
        if (lhs is net.starlark.java.syntax.Identifier) {
            // x = ...
            net.starlark.java.eval.Eval.assignIdentifier(fr, lhs, value)
        } else if (lhs is net.starlark.java.syntax.IndexExpression) {
            // x[i] = ...
            val `object`: Any = net.starlark.java.eval.Eval.eval(fr, lhs.getObject())
            val key: Any = net.starlark.java.eval.Eval.eval(fr, lhs.getKey())
            net.starlark.java.eval.EvalUtils.setIndex(`object`, key, value)
        } else if (lhs is net.starlark.java.syntax.ListExpression) {
            // a, b, c = ...
            net.starlark.java.eval.Eval.assignSequence(fr, lhs.getElements(), value)
        } else if (lhs is net.starlark.java.syntax.DotExpression) {
            // x.f = ...
            val `object`: Any = net.starlark.java.eval.Eval.eval(fr, lhs.getObject())
            val field: String? = lhs.getField().getName()
            try {
                net.starlark.java.eval.EvalUtils.setField(`object`, field, value)
            } catch (ex: net.starlark.java.eval.EvalException) {
                fr.setErrorLocation(lhs.getDotLocation())
                throw ex
            }
        } else {
            // Not possible for resolved ASTs.
            throw net.starlark.java.eval.Starlark.Companion.errorf("cannot assign to '%s'", lhs)
        }
    }

    private fun assignIdentifier(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        id: net.starlark.java.syntax.Identifier,
        value: Any?
    ) {
        val bind: net.starlark.java.syntax.Resolver.Binding? = id.getBinding()
        when (bind.getScope()) {
            net.starlark.java.syntax.Resolver.Scope.LOCAL -> fr.locals[bind.getIndex()] = value
            net.starlark.java.syntax.Resolver.Scope.CELL -> (fr.locals[bind.getIndex()] as net.starlark.java.eval.StarlarkFunction.Cell).x =
                value

            net.starlark.java.syntax.Resolver.Scope.GLOBAL -> {
                val fn: net.starlark.java.eval.StarlarkFunction = net.starlark.java.eval.Eval.fn(fr)
                fn.setGlobal(bind.getIndex(), value)
                val typeTable: net.starlark.java.syntax.TypeTable? = fn.getTypeTable()
                if (typeTable != null) {
                    fn.setGlobalDeclaredType(bind.getIndex(), typeTable.getGlobalDeclaredType(bind))
                }
            }

            else -> throw java.lang.IllegalStateException(bind.getScope().toString())
        }
    }

    /**
     * Recursively assigns an iterable value to a non-empty sequence of assignable expressions. Might
     * not set frame location on error.
     */
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun assignSequence(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        lhs: MutableList<net.starlark.java.syntax.Expression?>,
        x: Any
    ) {
        // TODO(adonovan): lock/unlock rhs during iteration so that
        // assignments fail when the left side aliases the right,
        // which is a tricky case in Python assignment semantics.
        val nrhs: Int = net.starlark.java.eval.Starlark.Companion.len(x)
        val nlhs: Int = lhs.size()
        if (nrhs < 0 || x is String) { // strings are not iterable
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "got '%s' in sequence assignment (want %d-element sequence)",
                net.starlark.java.eval.Starlark.Companion.type(x),
                nlhs
            )
        }
        val rhs: Iterable<*> = net.starlark.java.eval.Starlark.Companion.toIterable(x)
        if (nlhs != nrhs) {
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "too %s values to unpack (got %d, want %d)", if (nrhs < nlhs) "few" else "many", nrhs, nlhs
            )
        }
        var i = 0
        for (item in rhs) {
            net.starlark.java.eval.Eval.assign(fr, lhs.get(i), item)
            i++
        }
    }

    // Might not set frame location on error.
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun execAugmentedAssignment(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        stmt: net.starlark.java.syntax.AssignmentStatement
    ) {
        val lhs: net.starlark.java.syntax.Expression? = stmt.getLHS()
        val op: net.starlark.java.syntax.TokenKind? = stmt.getOperator()
        val rhs: net.starlark.java.syntax.Expression = stmt.getRHS()

        if (lhs is net.starlark.java.syntax.Identifier) {
            // x op= y    (lhs must be evaluated only once)
            val x: Any = net.starlark.java.eval.Eval.eval(fr, lhs)
            val y: Any = net.starlark.java.eval.Eval.eval(fr, rhs)
            val z: Any?
            try {
                z = net.starlark.java.eval.Eval.inplaceBinaryOp(fr, op, x, y)
            } catch (ex: net.starlark.java.eval.EvalException) {
                fr.setErrorLocation(stmt.getOperatorLocation())
                throw ex
            }
            net.starlark.java.eval.Eval.assignIdentifier(fr, lhs, z)
        } else if (lhs is net.starlark.java.syntax.IndexExpression) {
            // object[index] op= y
            // The object and key should be evaluated only once, so we don't use lhs.eval().
            val `object`: Any = net.starlark.java.eval.Eval.eval(fr, lhs.getObject())
            val key: Any = net.starlark.java.eval.Eval.eval(fr, lhs.getKey())
            val x: Any? = net.starlark.java.eval.EvalUtils.index(fr.thread, `object`, key)
            // Evaluate rhs after lhs.
            val y: Any = net.starlark.java.eval.Eval.eval(fr, rhs)
            val z: Any?
            try {
                z = net.starlark.java.eval.Eval.inplaceBinaryOp(fr, op, x, y)
            } catch (ex: net.starlark.java.eval.EvalException) {
                fr.setErrorLocation(stmt.getOperatorLocation())
                throw ex
            }
            try {
                net.starlark.java.eval.EvalUtils.setIndex(`object`, key, z)
            } catch (ex: net.starlark.java.eval.EvalException) {
                fr.setErrorLocation(stmt.getOperatorLocation())
                throw ex
            }
        } else if (lhs is net.starlark.java.syntax.DotExpression) {
            // object.field op= y  (lhs must be evaluated only once)
            val `object`: Any = net.starlark.java.eval.Eval.eval(fr, lhs.getObject())
            val field: String? = lhs.getField().getName()
            try {
                val x: Any = net.starlark.java.eval.Starlark.Companion.getattr(
                    fr.thread,
                    `object`,
                    field,  /* defaultValue= */
                    null
                )
                val y: Any = net.starlark.java.eval.Eval.eval(fr, rhs)
                val z: Any?
                try {
                    z = net.starlark.java.eval.Eval.inplaceBinaryOp(fr, op, x, y)
                } catch (ex: net.starlark.java.eval.EvalException) {
                    fr.setErrorLocation(stmt.getOperatorLocation())
                    throw ex
                }
                net.starlark.java.eval.EvalUtils.setField(`object`, field, z)
            } catch (ex: net.starlark.java.eval.EvalException) {
                fr.setErrorLocation(lhs.getDotLocation())
                throw ex
            }
        } else {
            // Not possible for resolved ASTs.
            fr.setErrorLocation(stmt.getOperatorLocation())
            throw net.starlark.java.eval.Starlark.Companion.errorf("cannot perform augmented assignment on '%s'", lhs)
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun inplaceBinaryOp(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        op: net.starlark.java.syntax.TokenKind,
        x: Any?,
        y: Any?
    ): Any? {
        when (op) {
            net.starlark.java.syntax.TokenKind.PLUS ->         // list += iterable  behaves like  list.extend(iterable)
                // TODO(b/141263526): following Python, allow list+=iterable (but not list+iterable).
                if (x is net.starlark.java.eval.StarlarkList<*> && y is net.starlark.java.eval.StarlarkList<*>) {
                    x.extend(y as net.starlark.java.eval.StarlarkIterable<*>)
                    return x
                }

            net.starlark.java.syntax.TokenKind.PIPE -> if (x is net.starlark.java.eval.Dict<*, *> && y is MutableMap<*, *>) {
                // dict |= map merges the contents of the second operand (usually a dict) into the first.
                val xDict: net.starlark.java.eval.Dict<Any?, Any?> = x as net.starlark.java.eval.Dict<Any?, Any?>
                val yMap = y as MutableMap<Any?, Any?>
                xDict.putEntries<Any?, Any?>(yMap)
                return xDict
            } else if (x is net.starlark.java.eval.StarlarkSet<*> && y is MutableSet<*>) {
                // set |= set merges the contents of the second operand into the first.
                x.update(net.starlark.java.eval.Tuple.Companion.of(y))
                return x
            }

            net.starlark.java.syntax.TokenKind.AMPERSAND -> if (x is net.starlark.java.eval.StarlarkSet<*> && y is MutableSet<*>) {
                // set &= set replaces the first set with the intersection of the two sets.
                x.intersectionUpdate(net.starlark.java.eval.Tuple.Companion.of(y))
                return x
            }

            net.starlark.java.syntax.TokenKind.CARET -> if (x is net.starlark.java.eval.StarlarkSet<*> && y is MutableSet<*>) {
                // set ^= set replaces the first set with the symmetric difference of the two sets.
                x.symmetricDifferenceUpdate(y)
                return x
            }

            net.starlark.java.syntax.TokenKind.MINUS -> if (x is net.starlark.java.eval.StarlarkSet<*> && y is MutableSet<*>) {
                // set -= set removes all elements of the second set from the first set.
                x.differenceUpdate(net.starlark.java.eval.Tuple.Companion.of(y))
                return x
            }

            else -> {}
        }
        return net.starlark.java.eval.EvalUtils.binaryOp(op, x, y, fr.thread)
    }

    // ---- expressions ----
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun eval(fr: net.starlark.java.eval.StarlarkThread.Frame, expr: net.starlark.java.syntax.Expression): Any {
        if (++fr.thread.steps >= fr.thread.stepLimit) {
            throw net.starlark.java.eval.EvalException("Starlark computation cancelled: too many steps")
        }

        // The switch cases have been split into separate functions
        // to reduce the stack usage during recursion, which is
        // especially important in practice for deeply nested a+...+z
        // expressions; see b/153764542.
        when (expr.kind()) {
            net.starlark.java.syntax.Expression.Kind.BINARY_OPERATOR -> return net.starlark.java.eval.Eval.evalBinaryOperator(
                fr,
                expr as net.starlark.java.syntax.BinaryOperatorExpression
            )

            net.starlark.java.syntax.Expression.Kind.COMPREHENSION -> return net.starlark.java.eval.Eval.evalComprehension(
                fr,
                expr as net.starlark.java.syntax.Comprehension
            )

            net.starlark.java.syntax.Expression.Kind.CONDITIONAL -> return net.starlark.java.eval.Eval.evalConditional(
                fr,
                expr as net.starlark.java.syntax.ConditionalExpression
            )

            net.starlark.java.syntax.Expression.Kind.DICT_EXPR -> return net.starlark.java.eval.Eval.evalDict(
                fr,
                expr as net.starlark.java.syntax.DictExpression
            )

            net.starlark.java.syntax.Expression.Kind.DOT -> return net.starlark.java.eval.Eval.evalDot(
                fr,
                expr as net.starlark.java.syntax.DotExpression
            )

            net.starlark.java.syntax.Expression.Kind.CALL -> return net.starlark.java.eval.Eval.evalCall(
                fr,
                expr as net.starlark.java.syntax.CallExpression
            )

            net.starlark.java.syntax.Expression.Kind.CAST -> return net.starlark.java.eval.Eval.eval(
                fr,
                (expr as net.starlark.java.syntax.CastExpression).getValue()
            )

            net.starlark.java.syntax.Expression.Kind.ISINSTANCE -> {
                fr.setErrorLocation(expr.getStartLocation())
                throw net.starlark.java.eval.EvalException("isinstance() is not yet supported")
            }

            net.starlark.java.syntax.Expression.Kind.IDENTIFIER -> return net.starlark.java.eval.Eval.evalIdentifier(
                fr,
                expr as net.starlark.java.syntax.Identifier
            )

            net.starlark.java.syntax.Expression.Kind.INDEX -> return net.starlark.java.eval.Eval.evalIndex(
                fr,
                expr as net.starlark.java.syntax.IndexExpression
            )

            net.starlark.java.syntax.Expression.Kind.INT_LITERAL -> {
                // TODO(adonovan): opt: avoid allocation by saving
                // the StarlarkInt in the IntLiteral (a temporary hack
                // until we use a compiled representation).
                val n: Number? = (expr as net.starlark.java.syntax.IntLiteral).getValue()
                if (n is Int) {
                    return net.starlark.java.eval.StarlarkInt.Companion.of(n)
                } else if (n is Long) {
                    return net.starlark.java.eval.StarlarkInt.Companion.of(n)
                } else {
                    return net.starlark.java.eval.StarlarkInt.Companion.of(n as BigInteger?)
                }
            }

            net.starlark.java.syntax.Expression.Kind.FLOAT_LITERAL -> return net.starlark.java.eval.StarlarkFloat.Companion.of(
                (expr as net.starlark.java.syntax.FloatLiteral).getValue()
            )

            net.starlark.java.syntax.Expression.Kind.LAMBDA -> return net.starlark.java.eval.Eval.newFunction(
                fr,
                (expr as net.starlark.java.syntax.LambdaExpression).getResolvedFunction()
            )

            net.starlark.java.syntax.Expression.Kind.LIST_EXPR -> return net.starlark.java.eval.Eval.evalList(
                fr,
                expr as net.starlark.java.syntax.ListExpression
            )

            net.starlark.java.syntax.Expression.Kind.SLICE -> return net.starlark.java.eval.Eval.evalSlice(
                fr,
                expr as net.starlark.java.syntax.SliceExpression
            )

            net.starlark.java.syntax.Expression.Kind.STRING_LITERAL -> return (expr as net.starlark.java.syntax.StringLiteral).getValue()
            net.starlark.java.syntax.Expression.Kind.UNARY_OPERATOR -> return net.starlark.java.eval.Eval.evalUnaryOperator(
                fr,
                expr as net.starlark.java.syntax.UnaryOperatorExpression
            )

            net.starlark.java.syntax.Expression.Kind.ELLIPSIS, net.starlark.java.syntax.Expression.Kind.TYPE_APPLICATION -> {}
        }
        throw java.lang.IllegalArgumentException("unexpected expression: " + expr.kind())
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun evalBinaryOperator(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        binop: net.starlark.java.syntax.BinaryOperatorExpression
    ): Any {
        val x: Any = net.starlark.java.eval.Eval.eval(fr, binop.getX())
        // AND and OR require short-circuit evaluation.
        when (binop.getOperator()) {
            net.starlark.java.syntax.TokenKind.AND -> return if (net.starlark.java.eval.Starlark.Companion.truth(x)) net.starlark.java.eval.Eval.eval(
                fr,
                binop.getY()
            ) else x

            net.starlark.java.syntax.TokenKind.OR -> return if (net.starlark.java.eval.Starlark.Companion.truth(x)) x else net.starlark.java.eval.Eval.eval(
                fr,
                binop.getY()
            )

            else -> {
                val y: Any = net.starlark.java.eval.Eval.eval(fr, binop.getY())
                try {
                    return net.starlark.java.eval.EvalUtils.binaryOp(binop.getOperator(), x, y, fr.thread)
                } catch (ex: net.starlark.java.eval.EvalException) {
                    fr.setErrorLocation(binop.getOperatorLocation())
                    throw ex
                }
            }
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun evalConditional(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        cond: net.starlark.java.syntax.ConditionalExpression
    ): Any {
        val v: Any = net.starlark.java.eval.Eval.eval(fr, cond.getCondition())
        return net.starlark.java.eval.Eval.eval(
            fr,
            if (net.starlark.java.eval.Starlark.Companion.truth(v)) cond.getThenCase() else cond.getElseCase()
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun evalDict(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        dictexpr: net.starlark.java.syntax.DictExpression
    ): Any {
        val map: LinkedHashMap<Any?, Any?> =
            com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<Any?, Any?>(dictexpr.getEntries().size())
        for (entry in dictexpr.getEntries()) {
            val k: Any = net.starlark.java.eval.Eval.eval(fr, entry.getKey())
            val v: Any = net.starlark.java.eval.Eval.eval(fr, entry.getValue())
            try {
                net.starlark.java.eval.Starlark.Companion.checkHashable(k)
            } catch (ex: net.starlark.java.eval.EvalException) {
                fr.setErrorLocation(entry.getColonLocation())
                throw ex
            }
            if (map.put(k, v) != null) {
                fr.setErrorLocation(entry.getColonLocation())
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "dictionary expression has duplicate key: %s",
                    net.starlark.java.eval.Starlark.Companion.repr(k, fr.thread.getSemantics())
                )
            }
        }
        val mu: net.starlark.java.eval.Mutability = fr.thread.mutability()
        return if (mu.isFrozen()) net.starlark.java.eval.CompactImmutableDict.Companion.copyOf<Any?, Any?>(map) else net.starlark.java.eval.Dict.Companion.wrap<Any?, Any?>(
            mu,
            map
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun evalDot(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        dot: net.starlark.java.syntax.DotExpression
    ): Any {
        val `object`: Any = net.starlark.java.eval.Eval.eval(fr, dot.getObject())
        val name: String? = dot.getField().getName()
        try {
            return net.starlark.java.eval.Starlark.Companion.getattr(
                fr.thread,
                `object`,
                name,  /* defaultValue= */
                null
            )
        } catch (ex: net.starlark.java.eval.EvalException) {
            fr.setErrorLocation(dot.getDotLocation())
            throw ex
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun evalCall(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        call: net.starlark.java.syntax.CallExpression
    ): Any {
        fr.thread.checkInterrupt()

        val fn: Any = net.starlark.java.eval.Eval.eval(fr, call.getFunction())

        // Starlark arguments are ordered: positionals < keywords < *args < **kwargs.
        //
        // This is stricter than Python2, which doesn't constrain keywords wrt *args,
        // but this ensures that the effects of evaluation of Starlark arguments occur
        // in source order.
        //
        // Starlark does not support Python3's multiple *args and **kwargs
        // nor freer ordering, such as f(a, *list, *list, **dict, **dict, b=1).
        // Supporting it would complicate a compiler, and produce effects out of order.
        // Also, Python's argument ordering rules are complex and the errors sometimes cryptic.

        // StarStar and Star args are guaranteed to be last, if they occur.
        val arguments: com.google.common.collect.ImmutableList<net.starlark.java.syntax.Argument> = call.getArguments()
        var numNonStarArgs: Int = arguments.size()
        var starstar: net.starlark.java.syntax.Argument.StarStar? = null
        if (numNonStarArgs > 0 && arguments.get(numNonStarArgs - 1) is net.starlark.java.syntax.Argument.StarStar) {
            starstar = arguments.get(numNonStarArgs - 1) as net.starlark.java.syntax.Argument.StarStar
            numNonStarArgs--
        }
        var star: net.starlark.java.syntax.Argument.Star? = null
        if (numNonStarArgs > 0 && arguments.get(numNonStarArgs - 1) is net.starlark.java.syntax.Argument.Star) {
            star = arguments.get(numNonStarArgs - 1) as net.starlark.java.syntax.Argument.Star
            numNonStarArgs--
        }

        // Inv: numNonStarArgs = |positional| + |named|
        val callable: net.starlark.java.eval.StarlarkCallable =
            net.starlark.java.eval.Starlark.Companion.getStarlarkCallable(fr.thread, fn)
        val numPositionalArguments: Int = call.getNumPositionalArguments()

        if (numNonStarArgs == numPositionalArguments // no named args
            && star == null && starstar == null
        ) {
            return net.starlark.java.eval.Eval.evalPositionalOnlyCall(
                fr,
                callable,
                call,
                arguments,
                numPositionalArguments
            )
        }

        val argumentProcessor: net.starlark.java.eval.StarlarkCallable.ArgumentProcessor =
            net.starlark.java.eval.Starlark.Companion.requestArgumentProcessor(fr.thread, callable)

        // Set the location of the call before the first calls to argumentProcessor.add*Arg().
        val loc: net.starlark.java.syntax.Location? = call.getLparenLocation()
        fr.setLocation(loc)

        // f(expr) -- positional args
        var i: Int
        i = 0
        while (i < numPositionalArguments) {
            val arg: net.starlark.java.syntax.Argument = arguments.get(i)
            argumentProcessor.addPositionalArg(net.starlark.java.eval.Eval.eval(fr, arg.getValue()))
            i++
        }

        // f(id=expr) -- named args
        while (i < numNonStarArgs) {
            val arg: net.starlark.java.syntax.Argument = arguments.get(i)
            argumentProcessor.addNamedArg(arg.getName(), net.starlark.java.eval.Eval.eval(fr, arg.getValue()))
            i++
        }

        // f(*args) -- varargs
        if (star != null) {
            val value: Any = net.starlark.java.eval.Eval.eval(fr, star.getValue())
            if (value !is net.starlark.java.eval.StarlarkIterable<*>) {
                fr.setErrorLocation(star.getStartLocation())
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "argument after * must be an iterable, not %s",
                    net.starlark.java.eval.Starlark.Companion.type(value)
                )
            }
            for (o in value) {
                argumentProcessor.addPositionalArg(o)
            }
        }

        // f(**kwargs)
        if (starstar != null) {
            val value: Any = net.starlark.java.eval.Eval.eval(fr, starstar.getValue())
            // Unlike *args, we don't have a Starlark-specific mapping interface to check for in **kwargs,
            // so check for Java's Map instead.
            if (value !is MutableMap<*, *>) {
                fr.setErrorLocation(starstar.getStartLocation())
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "argument after ** must be a dict, not %s",
                    net.starlark.java.eval.Starlark.Companion.type(value)
                )
            }
            for (e in value.entrySet()) {
                if (e.getKey() !is String) {
                    fr.setErrorLocation(starstar.getStartLocation())
                    throw net.starlark.java.eval.Starlark.Companion.errorf(
                        "keywords must be strings, not %s",
                        net.starlark.java.eval.Starlark.Companion.type(e.getKey())
                    )
                }
                argumentProcessor.addNamedArg(eKey, e.getValue())
            }
        }

        // Set the location of the call again after the argument values were evaluated.
        // Argument values that contain callable invocations may have changed the location.
        fr.setLocation(loc)

        try {
            return net.starlark.java.eval.Starlark.Companion.callViaArgumentProcessor(
                fr.thread,
                callable,
                argumentProcessor
            )
        } catch (ex: net.starlark.java.eval.EvalException) {
            fr.setErrorLocation(loc)
            throw ex
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun evalPositionalOnlyCall(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        callable: net.starlark.java.eval.StarlarkCallable?,
        call: net.starlark.java.syntax.CallExpression,
        arguments: com.google.common.collect.ImmutableList<net.starlark.java.syntax.Argument>,
        numPositionalArguments: Int
    ): Any {
        val positional = if (numPositionalArguments == 0) net.starlark.java.eval.Eval.EMPTY else arrayOfNulls<Any>(
            numPositionalArguments
        )
        var i: Int
        i = 0
        while (i < numPositionalArguments) {
            val arg: net.starlark.java.syntax.Argument = arguments.get(i)
            val value: Any = net.starlark.java.eval.Eval.eval(fr, arg.getValue())
            positional[i] = value
            i++
        }

        val loc: net.starlark.java.syntax.Location? = call.getLparenLocation() // (Location is prematerialized)
        fr.setLocation(loc)
        try {
            return net.starlark.java.eval.Starlark.Companion.positionalOnlyCall(fr.thread, callable, *positional)
        } catch (ex: net.starlark.java.eval.EvalException) {
            fr.setErrorLocation(loc)
            throw ex
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun evalIdentifier(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        id: net.starlark.java.syntax.Identifier
    ): Any {
        val bind: net.starlark.java.syntax.Resolver.Binding? = id.getBinding()
        val result: Any?
        when (bind.getScope()) {
            net.starlark.java.syntax.Resolver.Scope.LOCAL -> result = fr.locals[bind.getIndex()]
            net.starlark.java.syntax.Resolver.Scope.CELL -> result =
                (fr.locals[bind.getIndex()] as net.starlark.java.eval.StarlarkFunction.Cell).x

            net.starlark.java.syntax.Resolver.Scope.FREE -> result =
                net.starlark.java.eval.Eval.fn(fr).getFreeVar(bind.getIndex()).x

            net.starlark.java.syntax.Resolver.Scope.GLOBAL -> result =
                net.starlark.java.eval.Eval.fn(fr).getGlobal(bind.getIndex())

            net.starlark.java.syntax.Resolver.Scope.PREDECLARED -> result =
                net.starlark.java.eval.Eval.fn(fr).getModule().getPredeclared(id.getName())

            net.starlark.java.syntax.Resolver.Scope.UNIVERSAL -> result =
                net.starlark.java.eval.Starlark.Companion.UNIVERSE.get(id.getName())

            else -> throw java.lang.IllegalStateException(bind.toString())
        }
        if (result == null) {
            fr.setErrorLocation(id.getStartLocation())
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "%s variable '%s' is referenced before assignment.", bind.getScope(), id.getName()
            )
        }
        return result
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun evalIndex(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        index: net.starlark.java.syntax.IndexExpression
    ): Any? {
        val `object`: Any = net.starlark.java.eval.Eval.eval(fr, index.getObject())
        val key: Any = net.starlark.java.eval.Eval.eval(fr, index.getKey())
        try {
            return net.starlark.java.eval.EvalUtils.index(fr.thread, `object`, key)
        } catch (ex: net.starlark.java.eval.EvalException) {
            fr.setErrorLocation(index.getLbracketLocation())
            throw ex
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun evalList(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        expr: net.starlark.java.syntax.ListExpression
    ): Any {
        val n: Int = expr.getElements().size()
        val array = arrayOfNulls<Any>(n)
        for (i in 0..<n) {
            array[i] = net.starlark.java.eval.Eval.eval(fr, expr.getElements().get(i))
        }
        return if (expr.isTuple()) net.starlark.java.eval.Tuple.Companion.wrap(array) else net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(
            fr.thread.mutability(),
            array
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun evalSlice(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        slice: net.starlark.java.syntax.SliceExpression
    ): Any {
        val x: Any = net.starlark.java.eval.Eval.eval(fr, slice.getObject())
        val start: Any? =
            if (slice.getStart() == null) net.starlark.java.eval.Starlark.Companion.NONE else net.starlark.java.eval.Eval.eval(
                fr,
                slice.getStart()
            )
        val stop: Any? =
            if (slice.getStop() == null) net.starlark.java.eval.Starlark.Companion.NONE else net.starlark.java.eval.Eval.eval(
                fr,
                slice.getStop()
            )
        val step: Any? =
            if (slice.getStep() == null) net.starlark.java.eval.Starlark.Companion.NONE else net.starlark.java.eval.Eval.eval(
                fr,
                slice.getStep()
            )
        try {
            return net.starlark.java.eval.Starlark.Companion.slice(fr.thread.mutability(), x, start, stop, step)
        } catch (ex: net.starlark.java.eval.EvalException) {
            fr.setErrorLocation(slice.getLbracketLocation())
            throw ex
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun evalUnaryOperator(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        unop: net.starlark.java.syntax.UnaryOperatorExpression
    ): Any {
        val x: Any = net.starlark.java.eval.Eval.eval(fr, unop.getX())
        try {
            return net.starlark.java.eval.EvalUtils.unaryOp(unop.getOperator(), x)
        } catch (ex: net.starlark.java.eval.EvalException) {
            fr.setErrorLocation(unop.getStartLocation())
            throw ex
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun evalComprehension(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        comp: net.starlark.java.syntax.Comprehension
    ): Any {
        val map: LinkedHashMap<Any?, Any?>? =
            if (comp.isDict()) com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<Any?, Any?>(1) else null
        val list: MutableList<Any?>? = if (comp.isDict()) null else java.util.ArrayList<Any?>(0)

        // The Lambda class serves as a recursive lambda closure.
        class Lambda {
            // execClauses(index) recursively executes the clauses starting at index,
            // and finally evaluates the body and adds its value to the result.
            @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
            fun execClauses(index: Int) {
                fr.thread.checkInterrupt()

                // recursive case: one or more clauses
                if (index < comp.getClauses().size()) {
                    val clause: net.starlark.java.syntax.Comprehension.Clause = comp.getClauses().get(index)
                    if (clause is net.starlark.java.syntax.Comprehension.For) {
                        val seq: Iterable<*> = net.starlark.java.eval.Eval.evalAsIterable(fr, clause.getIterable())
                        net.starlark.java.eval.EvalUtils.addIterator(seq)
                        try {
                            for (elem in seq) {
                                net.starlark.java.eval.Eval.assign(fr, clause.getVars(), elem)
                                execClauses(index + 1)
                            }
                        } catch (ex: net.starlark.java.eval.EvalException) {
                            fr.setErrorLocation(clause.getStartLocation())
                            throw ex
                        } finally {
                            net.starlark.java.eval.EvalUtils.removeIterator(seq)
                        }
                    } else {
                        val ifClause: net.starlark.java.syntax.Comprehension.If =
                            clause as net.starlark.java.syntax.Comprehension.If
                        if (net.starlark.java.eval.Starlark.Companion.truth(
                                net.starlark.java.eval.Eval.eval(
                                    fr,
                                    ifClause.getCondition()
                                )
                            )
                        ) {
                            execClauses(index + 1)
                        }
                    }
                    return
                }

                // base case: evaluate body and add to result.
                if (map != null) {
                    val body: net.starlark.java.syntax.DictExpression.Entry =
                        comp.getBody() as net.starlark.java.syntax.DictExpression.Entry
                    val k: Any = net.starlark.java.eval.Eval.eval(fr, body.getKey())
                    try {
                        net.starlark.java.eval.Starlark.Companion.checkHashable(k)
                        val v: Any = net.starlark.java.eval.Eval.eval(fr, body.getValue())
                        map.put(k, v)
                    } catch (ex: net.starlark.java.eval.EvalException) {
                        fr.setErrorLocation(body.getColonLocation())
                        throw ex
                    }
                } else {
                    list!!.add(
                        net.starlark.java.eval.Eval.eval(
                            fr,
                            (comp.getBody() as net.starlark.java.syntax.Expression?)
                        )
                    )
                }
            }
        }
        Lambda().execClauses(0)

        val mu: net.starlark.java.eval.Mutability = fr.thread.mutability()
        if (!comp.isDict()) {
            return net.starlark.java.eval.StarlarkList.Companion.wrap<Any?>(mu, list.toArray())
        }
        return if (mu.isFrozen()) net.starlark.java.eval.CompactImmutableDict.Companion.copyOf<Any?, Any?>(map) else net.starlark.java.eval.Dict.Companion.wrap<Any?, Any?>(
            mu,
            map
        )
    }

    /**
     * Evaluates an expression to an iterable Starlark value and returns an `Iterable` view of
     * it. If evaluation fails or the value is not iterable, throws `EvalException` and sets the
     * error location to the expression's start.
     */
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun evalAsIterable(
        fr: net.starlark.java.eval.StarlarkThread.Frame,
        expr: net.starlark.java.syntax.Expression
    ): Iterable<*> {
        val o: Any = net.starlark.java.eval.Eval.eval(fr, expr)
        try {
            return net.starlark.java.eval.Starlark.Companion.toIterable(o)
        } catch (ex: net.starlark.java.eval.EvalException) {
            fr.setErrorLocation(expr.getStartLocation())
            throw ex
        }
    }

    private val EMPTY = arrayOf<Any?>()
}
