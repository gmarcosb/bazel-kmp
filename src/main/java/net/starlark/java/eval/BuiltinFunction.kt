// Copyright 2018 The Bazel Authors. All rights reserved.
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

import java.util.LinkedHashMap

/**
 * A BuiltinFunction is a callable Starlark value that reflectively invokes a [ ]-annotated method of a Java object. The Java object may or may not itself be a
 * Starlark value. BuiltinFunctions are not produced for Java methods for which [ ][StarlarkMethod.structField] is true.
 */
// TODO(adonovan): support annotated static methods.
@net.starlark.java.annot.StarlarkBuiltin(
    name = "builtin_function_or_method",
    category = "core",
    doc = "The type of a built-in function, defined by Java code."
)
open class BuiltinFunction private constructor(obj: Any?, desc: net.starlark.java.eval.MethodDescriptor) :
    net.starlark.java.eval.StarlarkCallable {
    protected val obj: Any?
    protected val desc: net.starlark.java.eval.MethodDescriptor

    init {
        com.google.common.base.Preconditions.checkArgument(!desc.isStructField())
        this.obj = obj
        this.desc = desc
    }

    override fun getStarlarkType(semantics: net.starlark.java.eval.StarlarkSemantics?): net.starlark.java.syntax.StarlarkType? {
        // desc.manager embeds a semantics (which should be the same as the arg)
        return desc.getStarlarkType()
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    override fun positionalOnlyCall(thread: net.starlark.java.eval.StarlarkThread, vararg positional: Any): Any? {
        desc.checkEnabled(thread)
        val vector: Array<Any>?
        if (desc.isPositionalsReusableAsJavaArgsVectorIfArgumentCountValid()
            && positional.size == desc.getParameters().size
        ) {
            vector = positional
        } else {
            vector = getPositionalOnlyArgumentVector(thread, positional)
        }
        return desc.call(
            if (obj is String) net.starlark.java.eval.StringModule.Companion.INSTANCE else obj,
            vector,
            thread.mutability()
        )
    }

    /**
     * Converts the arguments of a Starlark call into the argument vector for a reflective call to a
     * StarlarkMethod-annotated Java method.
     * 
     * @param thread the Starlark thread for the call
     * @param desc descriptor for the StarlarkMethod-annotated method
     * @param positional an array of positional arguments
     * @return the array of arguments which may be passed to [MethodDescriptor.call]. It is
     * unsafe to mutate the returned array.
     * @throws EvalException if the given set of arguments are invalid for the given method. For
     * example, if any arguments are of unexpected type, or not all mandatory parameters are
     * specified by the user
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun getPositionalOnlyArgumentVector(
        thread: net.starlark.java.eval.StarlarkThread,
        positional: Array<Any>
    ): Array<Any> {
        // Overview of steps:
        // - allocate vector of actual arguments of correct size.
        // - process positional arguments, accumulating surplus ones into *args.
        // - set default values for missing optionals, and report missing mandatory parameters.
        // - set special parameters.
        // The static checks ensure that positional parameters appear before named,
        // and mandatory positionals appear before optional.
        // Flag-disabled parameters are skipped during argument matching, as if they do not exist. They
        // are instead assigned their flag-disabled values.

        val parameters: Array<net.starlark.java.eval.ParamDescriptor> = desc.getParameters()

        // Allocate argument vector.
        var n = parameters.size
        if (desc.acceptsExtraArgs()) {
            n++
        }
        if (desc.acceptsExtraKwargs()) {
            n++
        }
        if (desc.isUseStarlarkThread()) {
            n++
        }
        val vector: Array<Any> = arrayOfNulls<Any>(n)

        // positional arguments
        var paramIndex = 0
        var argIndex = 0
        if (obj is String) {
            // String methods get the string as an extra argument
            // because their true receiver is StringModule.INSTANCE.
            vector[paramIndex++] = obj
        }
        while (argIndex < positional.size && paramIndex < parameters.size) {
            val param: net.starlark.java.eval.ParamDescriptor = parameters[paramIndex]
            if (!param.isPositional()) {
                break
            }

            // disabled?
            if (!param.isEnabled(thread)) {
                // Skip disabled parameter as if not present at all.
                // The default value will be filled in below.
                paramIndex++
                continue
            }

            val value = positional[argIndex++]
            checkParamValue(param, value)
            vector[paramIndex] = value
            paramIndex++
        }

        // *args
        var varargs: net.starlark.java.eval.Tuple? = null
        if (desc.acceptsExtraArgs()) {
            varargs = net.starlark.java.eval.Tuple.Companion.wrap(
                java.util.Arrays.copyOfRange<Any?>(
                    positional,
                    argIndex,
                    positional.size
                )
            )
        } else if (argIndex < positional.size) {
            if (argIndex == 0) {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "%s() got unexpected positional argument",
                    getName()
                )
            } else {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "%s() accepts no more than %d positional argument%s but got %d",
                    getName(),
                    argIndex,
                    net.starlark.java.eval.BuiltinFunction.Companion.plural(argIndex),
                    positional.size
                )
            }
        }

        applyDefaultsReportMissingArgs(parameters, vector)

        // special parameters
        var i = parameters.size
        if (desc.acceptsExtraArgs()) {
            vector[i++] = varargs
        }
        if (desc.acceptsExtraKwargs()) {
            vector[i++] = net.starlark.java.eval.Dict.Companion.wrap<Any?, Any?>(
                thread.mutability(),
                com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<Any?, Any?>(1)
            )
        }
        if (desc.isUseStarlarkThread()) {
            vector[i++] = thread
        }

        return vector
    }

    /**
     * Returns the StarlarkMethod annotation of this Starlark-callable Java method.
     */
    fun getAnnotation(): net.starlark.java.annot.StarlarkMethod? {
        return desc.getAnnotation()
    }

    override fun getName(): String? {
        return desc.getName()
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        if (obj is net.starlark.java.eval.StarlarkValue || obj is String) {
            printer
                .append("<built-in method ")
                .append(getName())
                .append(" of ")
                .append(net.starlark.java.eval.Starlark.Companion.type(obj))
                .append(" value>")
        } else {
            printer.append("<built-in function ").append(getName()).append(">")
        }
    }

    override fun toString(): String {
        return getName()!!
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun requestArgumentProcessor(thread: net.starlark.java.eval.StarlarkThread?): net.starlark.java.eval.StarlarkCallable.ArgumentProcessor {
        return net.starlark.java.eval.BuiltinFunction.ArgumentProcessor(this, thread, desc)
    }

    /**
     * ArgumentProcessor for a call to the StarlarkMethod-annotated Java method.
     * 
     * 
     * Allocation of the vector of actual arguments with the correct size happens at a constructor
     * time.
     * 
     * 
     * Processing of positional arguments happens in [.addPositionalArg].
     * 
     * 
     * Processing of named arguments happens in [.addNamedArg].
     * 
     * 
     * Setting default values for missing optionals, and setting special parameters happens in
     * [.call].
     * 
     * 
     * Static checks ensure that positional parameters appear before named, and mandatory
     * positionals appear before optional. No additional memory allocation occurs in the common
     * (success) case. Flag-disabled parameters are skipped during argument matching, as if they do
     * not exist. They are instead assigned their flag-disabled values.
     */
    internal class ArgumentProcessor(
        private val owner: BuiltinFunction,
        thread: net.starlark.java.eval.StarlarkThread?,
        desc: net.starlark.java.eval.MethodDescriptor
    ) : net.starlark.java.eval.StarlarkCallable.ArgumentProcessor(thread) {
        private val desc: net.starlark.java.eval.MethodDescriptor
        private val parameters: Array<net.starlark.java.eval.ParamDescriptor>
        private val vector: Array<Any>
        private var varArgs: java.util.ArrayList<Any?>?
        private var kwargs: LinkedHashMap<String?, Any?>?
        private var paramIndex: Int
        private var argIndex: Int
        private var allPositionalParamsFilled: Boolean
        private var unexpectedPositionalArgCount: Int

        /**
         * Constructs an ArgumentProcessor for a call to the StarlarkMethod-annotated Java method.
         * 
         * 
         * The only work done at construction time is allocating the argument vector, and, only if
         * the method accepts extra args and/or extra kwargs, allocating the varArgs list and/or kwargs
         * map.
         * 
         * @param thread the Starlark thread for the call
         * @param desc descriptor for the StarlarkMethod-annotated method
         * @throws EvalException if the method is disabled
         */
        init {
            this.desc = desc
            desc.checkEnabled(thread)
            this.parameters = desc.getParameters()
            varArgs = null
            kwargs = null
            paramIndex = 0
            argIndex = 0
            allPositionalParamsFilled = false
            unexpectedPositionalArgCount = 0

            var n = parameters.size
            if (desc.acceptsExtraArgs()) {
                varArgs = java.util.ArrayList<Any?>()
                n++
            }
            if (desc.acceptsExtraKwargs()) {
                kwargs = com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<String?, Any?>(1)
                n++
            }
            if (desc.isUseStarlarkThread()) {
                n++
            }
            this.vector = arrayOfNulls<Any>(n)

            if (owner.obj is String) {
                // String methods get the string as an extra argument
                // because their true receiver is StringModule.INSTANCE.
                vector[paramIndex++] = owner.obj
            }
        }

        private fun getNextEnabledPositionalParam(): net.starlark.java.eval.ParamDescriptor? {
            while (!allPositionalParamsFilled && paramIndex < parameters.size) {
                val param: net.starlark.java.eval.ParamDescriptor = parameters[paramIndex]
                if (!param.isPositional()) {
                    allPositionalParamsFilled = true
                    return null
                }
                if (param.isEnabled(thread)) {
                    return param
                }
                paramIndex++
            }
            return null
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addPositionalArg(value: Any) {
            val param: net.starlark.java.eval.ParamDescriptor? = getNextEnabledPositionalParam()
            if (param != null) {
                checkParamValue(param, value)
                vector[paramIndex++] = value
                argIndex++
            } else if (varArgs != null) {
                varArgs.add(value)
            } else {
                unexpectedPositionalArgCount++
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addNamedArg(name: String?, value: Any) {
            // look up parameter
            val index: Int = desc.getParameterIndex(name)
            // unknown parameter?
            if (index < 0) {
                // spill to **kwargs
                if (kwargs == null) {
                    val allNames: com.google.common.collect.ImmutableList<String?> =
                        java.util.Arrays.stream<net.starlark.java.eval.ParamDescriptor?>(parameters)
                            .map<String?>(java.util.function.Function { obj: net.starlark.java.eval.ParamDescriptor? -> obj.getName() })
                            .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
                    pushCallableAndThrow(
                        net.starlark.java.eval.Starlark.Companion.errorf(
                            "%s() got unexpected keyword argument '%s'%s",
                            owner.getName(), name, net.starlark.java.spelling.SpellChecker.didYouMean(name, allNames)
                        )
                    )
                }

                // duplicate named argument?
                if (kwargs.put(name, value) != null) {
                    pushCallableAndThrow(
                        net.starlark.java.eval.Starlark.Companion.errorf(
                            "%s() got multiple values for keyword argument '%s'", owner.getName(), name
                        )
                    )
                }
                return
            }
            val param: net.starlark.java.eval.ParamDescriptor = parameters[index]

            // positional-only param?
            if (!param.isNamed()) {
                // spill to **kwargs
                if (kwargs == null) {
                    pushCallableAndThrow(
                        net.starlark.java.eval.Starlark.Companion.errorf(
                            "%s() got named argument for positional-only parameter '%s'",
                            owner.getName(), name
                        )
                    )
                }

                // duplicate named argument?
                if (kwargs.put(name, value) != null) {
                    pushCallableAndThrow(
                        net.starlark.java.eval.Starlark.Companion.errorf(
                            "%s() got multiple values for keyword argument '%s'", owner.getName(), name
                        )
                    )
                }
                return
            }

            // disabled?
            if (!param.isEnabled(thread)) {
                pushCallableAndThrow(
                    net.starlark.java.eval.Starlark.Companion.errorf(
                        "in call to %s(), parameter '%s' is %s",
                        owner.getName(), param.getName(), param.getDisabledErrorMessage()
                    )
                )
            }

            checkParamValue(param, value)

            // duplicate?
            if (vector[index] != null) {
                pushCallableAndThrow(
                    net.starlark.java.eval.Starlark.Companion.errorf(
                        "%s() got multiple values for argument '%s'",
                        owner.getName(),
                        name
                    )
                )
            }

            vector[index] = value
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkParamValue(param: net.starlark.java.eval.ParamDescriptor, value: Any) {
            val allowedClasses: MutableList<java.lang.Class<*>>? = param.getAllowedClasses()
            if (allowedClasses == null) {
                return
            }

            // Value must belong to one of the specified classes.
            var ok = false
            for (cls in allowedClasses) {
                if (cls.isInstance(value)) {
                    ok = true
                    break
                }
            }
            if (!ok) {
                pushCallableAndThrow(
                    net.starlark.java.eval.Starlark.Companion.errorf(
                        "in call to %s(), parameter '%s' got value of type '%s', want '%s'",
                        owner.getName(),
                        param.getName(),
                        net.starlark.java.eval.Starlark.Companion.type(value),
                        param.getTypeErrorMessage()
                    )
                )
            }
        }

        override fun getCallable(): net.starlark.java.eval.StarlarkCallable {
            return owner
        }

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        override fun call(thread: net.starlark.java.eval.StarlarkThread): Any? {
            if (unexpectedPositionalArgCount > 0) {
                if (argIndex == 0) {
                    throw net.starlark.java.eval.Starlark.Companion.errorf(
                        "%s() got unexpected positional argument",
                        owner.getName()
                    )
                } else {
                    throw net.starlark.java.eval.Starlark.Companion.errorf(
                        "%s() accepts no more than %d positional argument%s but got %d",
                        owner.getName(),
                        argIndex,
                        net.starlark.java.eval.BuiltinFunction.Companion.plural(argIndex),
                        argIndex + unexpectedPositionalArgCount
                    )
                }
            }

            owner.applyDefaultsReportMissingArgs(parameters, vector)

            var i = parameters.size
            if (desc.acceptsExtraArgs()) {
                vector[i++] = net.starlark.java.eval.Tuple.Companion.wrap(varArgs.toArray())
            }
            if (desc.acceptsExtraKwargs()) {
                vector[i++] = net.starlark.java.eval.Dict.Companion.wrap<String?, Any?>(thread.mutability(), kwargs)
            }
            if (desc.isUseStarlarkThread()) {
                vector[i++] = thread
            }

            return desc.call(
                if (owner.obj is String) net.starlark.java.eval.StringModule.Companion.INSTANCE else owner.obj,
                vector,
                thread.mutability()
            )
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun applyDefaultsReportMissingArgs(
        parameters: Array<net.starlark.java.eval.ParamDescriptor>,
        vector: Array<Any>
    ) {
        // Set default values for missing parameters,
        // and report any that are still missing.
        var missingPositional: MutableList<String?>? = null
        var missingNamed: MutableList<String?>? = null
        for (i in parameters.indices) {
            if (vector[i] == null) {
                val param: net.starlark.java.eval.ParamDescriptor = parameters[i]
                vector[i] = param.getDefaultValue()
                if (vector[i] == null) {
                    if (param.isPositional()) {
                        if (missingPositional == null) {
                            missingPositional = java.util.ArrayList<String?>()
                        }
                        missingPositional!!.add(param.getName())
                    } else {
                        if (missingNamed == null) {
                            missingNamed = java.util.ArrayList<String?>()
                        }
                        missingNamed!!.add(param.getName())
                    }
                }
            }
        }
        if (missingPositional != null) {
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "%s() missing %d required positional argument%s: %s",
                getName(),
                missingPositional.size(),
                net.starlark.java.eval.BuiltinFunction.Companion.plural(missingPositional.size()),
                com.google.common.base.Joiner.on(", ").join(missingPositional)
            )
        }
        if (missingNamed != null) {
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "%s() missing %d required named argument%s: %s",
                getName(),
                missingNamed.size(),
                net.starlark.java.eval.BuiltinFunction.Companion.plural(missingNamed.size()),
                com.google.common.base.Joiner.on(", ").join(missingNamed)
            )
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun checkParamValue(param: net.starlark.java.eval.ParamDescriptor, value: Any) {
        val allowedClasses: MutableList<java.lang.Class<*>>? = param.getAllowedClasses()
        if (allowedClasses == null) {
            return
        }

        // Value must belong to one of the specified classes.
        var ok = false
        for (cls in allowedClasses) {
            if (cls.isInstance(value)) {
                ok = true
                break
            }
        }
        if (!ok) {
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "in call to %s(), parameter '%s' got value of type '%s', want '%s'",
                getName(),
                param.getName(),
                net.starlark.java.eval.Starlark.Companion.type(value),
                param.getTypeErrorMessage()
            )
        }
    }

    /**
     * A [BuiltinFunction] whose symbol is also a type constructor; for example, `list` is
     * used both as a function that returns list values (`l = list((1, 2, 3))`) and a
     * constructor for list types (`type T = list[int]`).
     */
    // Non-private due to what appears to be a javac bug (present at least in JDK 21) causing
    // scripts/bootstrap/compile.sh and bazel_bootstrap_distfile_tar_test to spuriously fail with
    // "error: BuiltinTypeFunction has private access in BuiltinFunction".
    // TODO(bazel-team): check if we can make this class private once Bazel starts using JDK 25 or
    // newer to bootstrap
    internal class BuiltinTypeFunction private constructor(obj: Any?, desc: net.starlark.java.eval.MethodDescriptor) :
        BuiltinFunction(obj, desc), net.starlark.java.syntax.TypeConstructor {
        @Throws(net.starlark.java.syntax.TypeConstructor.Failure::class)
        override fun createStarlarkType(argsTuple: com.google.common.collect.ImmutableList<net.starlark.java.syntax.TypeConstructor.Arg?>?): net.starlark.java.syntax.StarlarkType? {
            // The Preconditions checks could morally be done in the constructor for eagerness.
            // However, this causes the MethodDescriptors of the proxy class to be materialized
            // while initializing a Module environment using Starlark#addMethods. That's inconvenient
            // due to circular dependencies (see the bootstrapping in ParamDescriptor#evalDefault)
            // and because it complicates unit tests where these preconditions fail.
            val tcProxy: java.lang.Class<*>? = desc.getTypeConstructorProxy()
            com.google.common.base.Preconditions.checkNotNull(tcProxy)
            val tc: net.starlark.java.syntax.TypeConstructor? = desc.getManager().getTypeConstructor(tcProxy)
            com.google.common.base.Preconditions.checkArgument(
                tc != null,
                "invalid type constructor proxy: %s",
                tcProxy
            )
            return tc.createStarlarkType(argsTuple)
        }
    }

    companion object {
        /**
         * Constructs a `BuiltinFunction` for a [StarlarkMethod]-annotated method on the given
         * receiver instance `obj`.
         * 
         * 
         * The method must be a proper Starlark method, not a field; i.e., [ ][StarlarkMethod.structField] must be false.
         */
        fun of(obj: Any?, desc: net.starlark.java.eval.MethodDescriptor): BuiltinFunction {
            if (desc.getTypeConstructorProxy() == null) {
                return net.starlark.java.eval.BuiltinFunction(obj, desc)
            } else {
                return net.starlark.java.eval.BuiltinFunction.BuiltinTypeFunction(obj, desc)
            }
        }

        private fun plural(n: Int): String {
            return if (n == 1) "" else "s"
        }
    }
}
