// Copyright 2014 The Bazel Authors. All rights reserved.
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
import java.util.LinkedHashMap

/** A StarlarkFunction is a function value created by a Starlark `def` statement.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "function",
    category = "core",
    doc = "The type of functions declared in Starlark."
)
class StarlarkFunction internal constructor(
    rfn: net.starlark.java.syntax.Resolver.Function,
    typeTable: net.starlark.java.syntax.TypeTable?,
    module: net.starlark.java.eval.Module,
    globalIndex: IntArray,
    defaultValues: net.starlark.java.eval.Tuple,
    freevars: net.starlark.java.eval.Tuple,
    token: net.starlark.java.eval.SymbolGenerator.Symbol<*>
) : net.starlark.java.eval.StarlarkCallable {
    val rfn: net.starlark.java.syntax.Resolver.Function

    // TODO: #27370 - at eval time, we need only types of functions and globals; we could save some
    // memory by skipping the other types.
    private val typeTable: net.starlark.java.syntax.TypeTable?
    private val module: net.starlark.java.eval.Module // a function closes over its defining module

    // Index in Module.globals of ith Program global (Resolver.Binding(GLOBAL).index).
    // See explanation at Starlark.execFileProgram.
    val globalIndex: IntArray

    // Default values of optional parameters.
    // Indices correspond to the subsequence of parameters after the initial
    // required parameters and before *args/**kwargs.
    // Contain MANDATORY for the required keyword-only parameters.
    private val defaultValues: net.starlark.java.eval.Tuple

    // Cells (shared locals) of enclosing functions.
    // Indexed by Resolver.Binding(FREE).index values.
    private val freevars: net.starlark.java.eval.Tuple

    // A stable identifier for this function instance.
    //
    // This may be mutated by export.
    private var token: net.starlark.java.eval.SymbolGenerator.Symbol<*>

    // Sets a global variable, given its index in this function's compiled Program.
    fun setGlobal(progIndex: Int, value: Any?) {
        module.setGlobalByIndex(globalIndex[progIndex], value)
    }

    fun setGlobalDeclaredType(progIndex: Int, type: net.starlark.java.syntax.StarlarkType?) {
        module.setGlobalTypeByIndex(globalIndex[progIndex], type)
    }

    // Gets the value of a global variable, given its index in this function's compiled Program.
    fun getGlobal(progIndex: Int): Any? {
        return module.getGlobalByIndex(globalIndex[progIndex])
    }

    fun isToplevel(): Boolean {
        return rfn.isToplevel()
    }

    /** Whether this function is defined at the top level of a file.  */
    fun isGlobal(): Boolean {
        return module.getGlobal(getName()) === this
    }

    override fun getStarlarkType(semantics: net.starlark.java.eval.StarlarkSemantics?): net.starlark.java.syntax.StarlarkType? {
        if (typeTable == null) {
            return net.starlark.java.syntax.Types.ANY
        }
        val functionType: net.starlark.java.syntax.StarlarkType? = typeTable.getType(rfn)
        return if (functionType != null) functionType else net.starlark.java.syntax.Types.ANY
    }

    fun getTypeTable(): net.starlark.java.syntax.TypeTable? {
        return typeTable
    }

    // TODO(adonovan): many functions would be simpler if
    // parameterNames excluded the *args and **kwargs parameters,
    // (whose names are immaterial to the callee anyway). Do that.
    // Also, reject getDefaultValue for varargs and kwargs.
    /**
     * Returns the default value of the ith parameter (`0 <= i < getParameterNames().size()`),
     * or null if the parameter is required. Residual parameters, if any, are always last, and have no
     * default value.
     */
    fun getDefaultValue(i: Int): Any? {
        if (i < 0 || i >= rfn.getParameters().size()) {
            throw java.lang.IndexOutOfBoundsException()
        }
        val nparams = getNumNonResidualParameters()
        val prefix: Int = nparams - defaultValues.size()
        if (i < prefix) {
            return null // implicit prefix of mandatory parameters
        }
        if (i < nparams) {
            val v: Any? = defaultValues.get(i - prefix)
            return if (v === net.starlark.java.eval.StarlarkFunction.Companion.MANDATORY) null else v
        }
        return null // *args or *kwargs
    }

    /**
     * Returns the names of this function's parameters.
     * 
     * 
     * The first `getNumOrdinaryParameters()` parameters in the returned list are ordinary
     * (non-residual, non-keyword-only); the following `getNumKeywordOnlyParameters()` are
     * keyword-only; and the residual `*args` and `**kwargs` parameters, if any, are
     * always last.
     */
    fun getParameterNames(): com.google.common.collect.ImmutableList<String?> {
        return rfn.getParameterNames()
    }

    /** Returns the number of ordinary (non-residual, non-keyword-only) parameters.  */
    fun getNumOrdinaryParameters(): Int {
        return rfn.getNumOrdinaryParameters()
    }

    /** Returns the number of non-residual keyword-only parameters.  */
    fun getNumKeywordOnlyParameters(): Int {
        return rfn.numKeywordOnlyParams()
    }

    private fun getNumNonResidualParameters(): Int {
        return rfn.getNumNonResidualParameters()
    }

    /**
     * Reports whether this function has a residual positional arguments parameter, `def f(*args)`.
     */
    fun hasVarargs(): Boolean {
        return rfn.hasVarargs()
    }

    /**
     * Reports whether this function has a residual keyword arguments parameter, `def f(**kwargs)`.
     */
    fun hasKwargs(): Boolean {
        return rfn.hasKwargs()
    }

    /** Returns the location of the function's defining identifier.  */
    override fun getLocation(): net.starlark.java.syntax.Location? {
        return rfn.getLocation()
    }

    /**
     * Returns the name of the function, or "lambda" if anonymous. Implicit functions (those not
     * created by a def statement), may have names such as "<toplevel>" or "<expr>".
    </expr></toplevel> */
    override fun getName(): String {
        return rfn.getName()
    }

    /**
     * Returns the value denoted by the function's doc string literal (trimmed if necessary), or null
     * if absent.
     */
    fun getDocumentation(): String? {
        val documentation: String? = rfn.getDocumentation()
        return if (documentation != null) net.starlark.java.eval.Starlark.Companion.trimDocString(documentation) else null
    }

    fun getModule(): net.starlark.java.eval.Module {
        return module
    }

    override fun requestArgumentProcessor(thread: net.starlark.java.eval.StarlarkThread?): net.starlark.java.eval.StarlarkCallable.ArgumentProcessor {
        return net.starlark.java.eval.StarlarkFunction.ArgumentProcessor(this, thread)
    }

    fun getFreeVar(index: Int): Cell? {
        return freevars.get(index) as Cell?
    }

    fun export(thread: net.starlark.java.eval.StarlarkThread, name: String?) {
        // Checks that thread is the one that defines the StarlarkFunction. It's possible for one
        // StarlarkFunction to be exported in different places.
        if (token.getOwner() != thread.getOwner()) {
            return
        }
        if (token.isGlobal()) {
            // Keeps only the first token if the same function is exported under multiple aliases.
            return
        }
        token = token.exportAs(name)
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        // TODO(adonovan): use the file name instead. But that's a breaking Bazel change.
        val clientData: Any? = module.getClientData()

        printer.append("<function " + getName())
        if (clientData != null) {
            printer.append(" from " + clientData)
        }
        printer.append(">")
    }

    override fun toString(): String {
        val out: java.lang.StringBuilder = java.lang.StringBuilder()
        out.append(getName())
        out.append('(')
        var sep = ""
        // TODO(adonovan): include *, ** tokens.
        for (param in getParameterNames()) {
            out.append(sep).append(param)
            sep = ", "
        }
        out.append(')')
        return out.toString()
    }

    fun getToken(): net.starlark.java.eval.SymbolGenerator.Symbol<*> {
        return token
    }

    override fun hashCode(): Int {
        return token.hashCode()
    }

    override fun equals(obj: Any?): Boolean {
        if (obj === this) {
            return true
        }
        if (obj !is StarlarkFunction) {
            return false
        }
        return token == obj.token
    }

    override fun isImmutable(): Boolean {
        // Only correct because closures are not yet supported.
        return true
    }

    init {
        this.rfn = rfn
        this.typeTable = typeTable
        this.module = module
        this.globalIndex = globalIndex
        this.defaultValues = defaultValues
        this.freevars = freevars
        this.token = token
    }

    private class Mandatory : net.starlark.java.eval.StarlarkValue

    // A Cell is a local variable shared between an inner and an outer function.
    // It is a StarlarkValue because it is a stack operand and a Tuple element,
    // but it is not visible to Java or Starlark code.
    internal class Cell(x: Any?) : net.starlark.java.eval.StarlarkValue {
        var x: Any?

        init {
            this.x = x
        }
    }

    // Checks the positional and named arguments to ensure they match the signature. It returns a new
    // array of effective parameter values corresponding to the parameters of the signature. The
    // returned array has size of locals and is directly pushed to the stack.
    // Newly allocated values (e.g. a **kwargs dict) use the Mutability mu.
    //
    // If the function has optional parameters, their default values are supplied by getDefaultValue.
    private class ArgumentProcessor(owner: StarlarkFunction, thread: net.starlark.java.eval.StarlarkThread?) :
        net.starlark.java.eval.StarlarkCallable.ArgumentProcessor(thread) {
        // This is the general schema of a function:
        //
        //   def f(p1, p2=dp2, p3=dp3, *args, k1, k2=dk2, k3, **kwargs)
        //
        // The p parameters are non-kwonly, and may be specified positionally.
        // The k parameters are kwonly, and must be specified by name.
        // The defaults tuple is (dp2, dp3, MANDATORY, dk2, MANDATORY).
        // The missing prefix (p1) is assumed to be all MANDATORY.
        //
        // Arguments are processed as follows:
        // - positional arguments are bound to a prefix of [p1, p2, p3].
        // - surplus positional arguments are bound to *args.
        // - keyword arguments are bound to any of {p1, p2, p3, k1, k2, k3};
        //   duplicate bindings are rejected.
        // - surplus keyword arguments are bound to **kwargs.
        // - defaults are bound to each parameter from p2 to k3 if no value was set.
        //   default values come from the tuple above.
        //   It is an error if the defaults tuple entry for an unset parameter is MANDATORY.
        private val owner: StarlarkFunction

        // Number of positional args that were set by the caller and bound to ordinary params (in other
        // words, not counting surplus positional args that were spilled to *args, and not counting
        // positional params that weren't set via args but were instead filled with defaults).
        private var numNonSurplusPositionalArgs: Int

        // Local variable array for the function's call frame. It has the following layout:
        //
        // * The first owner.getNumOrdinaryParameters() entries are values of ordinary parameters
        //   * The first numNonSurplusPositionalArgs entries contain positional args, set by
        //     addPositionalArg()
        //   * The remaining entries contain keyword args (set by addNamedArg()) or default
        //     values (set by applyDefaultsReportMissingArgs())
        // * The next owner.getNumKeywordOnlyParameters() entries are values of keyword-only parameters,
        //   which may be either keyword args (set by addNamedArg()) or default values (set by
        //   applyDefaultsReportMissingArgs())
        // * An optional entry for *args - present if and only if the function takes varargs (set by
        //   bindSurplusPositionalArgsToVarArgs())
        // * An optional entry for **kwargs - present if and only if the function takes kwargs (set by
        //   addNamedArg())
        // * The remaining entries hold values of the function body's variables - these are left
        //   uninitialized by ArgumentProcessor, and will be set in the process of evaluating the
        //   function body.
        private val locals: Array<Any?>

        // unexpectedNamedArgs serves as accumulator for named arguments that can't be bound to any of
        // the function's parameters or to **kwargs. It is used to error-report all unexpected named
        // args, not just the first one that was encountered.
        private var unexpectedNamedArgs: MutableList<String?>?

        // varArgs and kwargs are used to collect the respective arguments before transforming them into
        // Starlark values and binding them to the right slots in the locals array.
        private var varArgs: java.util.ArrayList<Any?>?
        private var kwargs: LinkedHashMap<String?, Any?>?

        init {
            this.owner = owner
            this.locals = arrayOfNulls<Any>(owner.rfn.getLocals().size())
            this.numNonSurplusPositionalArgs = 0
            this.unexpectedNamedArgs = null
            this.varArgs = null
            this.kwargs = null
        }

        override fun getCallable(): net.starlark.java.eval.StarlarkCallable {
            return owner
        }

        fun getKwargsIndex(): Int {
            return if (owner.rfn.hasKwargs()) owner.rfn.getParameters().size() - 1 else -1
        }

        fun getVarArgsIndex(): Int {
            if (owner.rfn.hasVarargs()) {
                val index: Int = owner.rfn.getParameters().size()
                return if (owner.rfn.hasKwargs()) index - 2 else index - 1
            }
            return -1
        }

        fun addUnexpectedNamedArg(keyword: String?) {
            if (unexpectedNamedArgs == null) {
                unexpectedNamedArgs = java.util.ArrayList<String?>()
            }
            unexpectedNamedArgs!!.add(keyword)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun checkUnexpectedNamedArgs() {
            if (unexpectedNamedArgs != null) {
                // Give a spelling hint if there is exactly one.
                // More than that suggests the wrong function was called.
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "%s() got unexpected keyword argument%s: %s%s",
                    owner.getName(),
                    net.starlark.java.eval.StarlarkFunction.Companion.plural(unexpectedNamedArgs.size()),
                    com.google.common.base.Joiner.on(", ").join(unexpectedNamedArgs),
                    if (unexpectedNamedArgs.size() == 1)
                        net.starlark.java.spelling.SpellChecker.didYouMean(
                            unexpectedNamedArgs!!.get(0),
                            owner.getParameterNames().subList(0, owner.getNumNonResidualParameters())
                        )
                    else
                        ""
                )
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun applyDefaultsReportMissingArgs() {
            // Apply defaults and report errors for missing required arguments.
            // Inv: all params below positionalCount were bound (by bindPositionalArgsToLocals()).
            val numParams = owner.getNumNonResidualParameters()
            val defaultValues: net.starlark.java.eval.Tuple = owner.defaultValues
            val firstDefault: Int = numParams - defaultValues.size() // first default
            var missingPositional: MutableList<String?>? = null
            var missingKwonly: MutableList<String?>? = null
            for (i in numNonSurplusPositionalArgs..<numParams) {
                // provided?
                if (locals[i] != null) {
                    continue
                }

                // optional?
                if (i >= firstDefault) {
                    val dflt: Any? = defaultValues.get(i - firstDefault)
                    if (dflt !== net.starlark.java.eval.StarlarkFunction.Companion.MANDATORY) {
                        locals[i] = dflt
                        continue
                    }
                }

                // missing
                if (i < owner.getNumOrdinaryParameters()) {
                    if (missingPositional == null) {
                        missingPositional = java.util.ArrayList<String?>()
                    }
                    missingPositional!!.add(owner.getParameterNames().get(i))
                } else {
                    if (missingKwonly == null) {
                        missingKwonly = java.util.ArrayList<String?>()
                    }
                    missingKwonly!!.add(owner.getParameterNames().get(i))
                }
            }
            if (missingPositional != null) {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "%s() missing %d required positional argument%s: %s",
                    owner.getName(),
                    missingPositional.size(),
                    net.starlark.java.eval.StarlarkFunction.Companion.plural(missingPositional.size()),
                    com.google.common.base.Joiner.on(", ").join(missingPositional)
                )
            }
            if (missingKwonly != null) {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "%s() missing %d required keyword-only argument%s: %s",
                    owner.getName(),
                    missingKwonly.size(),
                    net.starlark.java.eval.StarlarkFunction.Companion.plural(missingKwonly.size()),
                    com.google.common.base.Joiner.on(", ").join(missingKwonly)
                )
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addPositionalArg(value: Any?) {
            if (numNonSurplusPositionalArgs < owner.getNumOrdinaryParameters()) {
                locals[numNonSurplusPositionalArgs++] = value
            } else if (owner.rfn.hasVarargs()) {
                if (varArgs == null) {
                    varArgs = java.util.ArrayList<Any?>()
                }
                varArgs.add(value)
            } else {
                // This indicates an error condition which is then checked in call().
                numNonSurplusPositionalArgs++
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun setKwargToLocal(index: Int, value: Any?, name: String?) {
            if (locals[index] != null) {
                throwDoubleDefinedKeywordArg(name)
            }
            locals[index] = value
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun throwDoubleDefinedKeywordArg(name: String?) {
            pushCallableAndThrow(
                net.starlark.java.eval.Starlark.Companion.errorf(
                    "%s() got multiple values for parameter '%s'",
                    owner.getName(),
                    name
                )
            )
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addNamedArg(name: String?, value: Any?) {
            val formalIndex: Int = owner.getParameterNames().indexOf(name)
            if (0 <= formalIndex && formalIndex < owner.getNumNonResidualParameters()) {
                setKwargToLocal(formalIndex, value, name)
            } else {
                if (owner.rfn.hasKwargs()) {
                    if (kwargs == null) {
                        kwargs = com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<String?, Any?>(1)
                    }
                    val oldValue: Any? = kwargs.put(name, value)
                    if (oldValue != null) {
                        throwDoubleDefinedKeywordArg(name)
                    }
                } else {
                    addUnexpectedNamedArg(name)
                }
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        override fun call(thread: net.starlark.java.eval.StarlarkThread): Any {
            // Check positional args count
            val numOrdinaryParams = owner.getNumOrdinaryParameters()
            if (numNonSurplusPositionalArgs > numOrdinaryParams) {
                if (numOrdinaryParams > 0) {
                    throw net.starlark.java.eval.Starlark.Companion.errorf(
                        "%s() accepts no more than %d positional argument%s but got %d",
                        owner.getName(),
                        numOrdinaryParams,
                        net.starlark.java.eval.StarlarkFunction.Companion.plural(numOrdinaryParams),
                        numNonSurplusPositionalArgs
                    )
                } else {
                    throw net.starlark.java.eval.Starlark.Companion.errorf(
                        "%s() does not accept positional arguments, but got %d",
                        owner.getName(), numNonSurplusPositionalArgs
                    )
                }
            }
            checkUnexpectedNamedArgs()
            val rfn: net.starlark.java.syntax.Resolver.Function = owner.rfn
            if (rfn.hasVarargs()) {
                locals[getVarArgsIndex()] =
                    if (varArgs == null)
                        net.starlark.java.eval.Tuple.Companion.empty()
                    else
                        if (varArgs.size() == 1)
                            net.starlark.java.eval.Tuple.Companion.of(varArgs.getFirst())
                        else
                            net.starlark.java.eval.Tuple.Companion.wrap(varArgs.toArray())
            }
            if (rfn.hasKwargs()) {
                locals[getKwargsIndex()] =
                    if (kwargs == null) net.starlark.java.eval.Dict.Companion.of<Any?, Any?>(thread.mutability()) else net.starlark.java.eval.Dict.Companion.wrap<String?, Any?>(
                        thread.mutability(),
                        kwargs
                    )
            }

            val dynamicTyping: Boolean =
                thread
                    .getSemantics()
                    .getBool(net.starlark.java.eval.StarlarkSemantics.Companion.EXPERIMENTAL_STARLARK_DYNAMIC_TYPE_CHECKING)
            var functionType: net.starlark.java.syntax.Types.CallableType? = null
            if (dynamicTyping
                && owner.getStarlarkType(thread.getSemantics()) is net.starlark.java.syntax.Types.CallableType
            ) {
                functionType = ct
            }

            // Argument value dynamic type check, if enabled.
            if (functionType != null) {
                for (i in functionType.getParameterTypes().indices) {
                    if (locals[i] == null) {
                        continue  // the default value is already type checked
                    }
                    val parameterType: net.starlark.java.syntax.StarlarkType? = functionType.getParameterTypeByPos(i)
                    if (!net.starlark.java.eval.TypeChecker.isValueSubtypeOf(
                            locals[i],
                            parameterType,
                            thread.getSemantics()
                        )
                    ) {
                        throw net.starlark.java.eval.Starlark.Companion.errorf(
                            "in call to %s(), parameter '%s' got value of type '%s', want '%s'",
                            owner.getName(),
                            owner.getParameterNames().get(i),
                            net.starlark.java.eval.Starlark.Companion.getStarlarkType(locals[i], thread.getSemantics()),
                            parameterType
                        )
                    }
                }
                // TODO(ilist@): typecheck *args and **kwargs, once we have more than primitive types
            }

            applyDefaultsReportMissingArgs()
            // Spill indicated locals to cells
            for (index in rfn.getCellIndices()) {
                locals[index] = net.starlark.java.eval.StarlarkFunction.Cell(locals[index])
            }

            // Check recursion
            if (!thread.isRecursionAllowed() && thread.isRecursiveCall(owner)) {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "function '%s' called recursively",
                    owner.getName()
                )
            }

            val fr: net.starlark.java.eval.StarlarkThread.Frame = thread.frame(0)
            fr.locals = locals
            val returnValue: Any = net.starlark.java.eval.Eval.execFunctionBody(fr, rfn.getBody())

            // Return value dynamic type check, if enabled.
            if (functionType != null) {
                if (!net.starlark.java.eval.TypeChecker.isValueSubtypeOf(
                        returnValue, functionType.getReturnType(), thread.getSemantics()
                    )
                ) {
                    throw net.starlark.java.eval.Starlark.Companion.errorf(
                        "%s(): returns value of type '%s', declares '%s'",
                        owner.getName(),
                        net.starlark.java.eval.Starlark.Companion.getStarlarkType(returnValue, thread.getSemantics()),
                        functionType.getReturnType()
                    )
                }
            }

            return returnValue
        }
    }

    companion object {
        private fun plural(n: Int): String {
            return if (n == 1) "" else "s"
        }

        // The MANDATORY sentinel indicates a slot in the defaultValues
        // tuple corresponding to a required parameter.
        // It is not visible to Java or Starlark code.
        val MANDATORY: Any = net.starlark.java.eval.StarlarkFunction.Mandatory()
    }
}
