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

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.math.BigInteger

/**
 * A value class to store Methods with their corresponding [StarlarkMethod] annotation
 * metadata. This is needed because the annotation is sometimes in a superclass.
 * 
 * 
 * The annotation metadata is duplicated in this class to avoid usage of Java dynamic proxies
 * which are ~7× slower.
 */
internal class MethodDescriptor private constructor(
    manager: net.starlark.java.eval.CallUtils.BuiltinManager?,
    method: java.lang.reflect.Method,
    annotation: net.starlark.java.annot.StarlarkMethod,
    name: String?,
    doc: String?,
    documented: Boolean,
    structField: Boolean,
    parameters: Array<net.starlark.java.eval.ParamDescriptor?>,
    extraPositionals: Boolean,
    extraKeywords: Boolean,
    selfCall: Boolean,
    allowReturnNones: Boolean,
    useStarlarkThread: Boolean,
    useStarlarkSemantics: Boolean,
    isTypeConstructor: Boolean
) {
    private val manager: net.starlark.java.eval.CallUtils.BuiltinManager?

    private val method: java.lang.reflect.Method

    @Transient
    private var annotation: net.starlark.java.annot.StarlarkMethod?

    private val name: String?
    private val doc: String?
    private val documented: Boolean
    private val structField: Boolean
    private val parameters: Array<net.starlark.java.eval.ParamDescriptor?>
    private val extraPositionals: Boolean
    private val extraKeywords: Boolean
    private val selfCall: Boolean
    private val allowReturnNones: Boolean
    private val useStarlarkThread: Boolean
    private val useStarlarkSemantics: Boolean
    private val typeConstructorProxy: java.lang.Class<*>?
    private val positionalsReusableAsJavaArgsVectorIfArgumentCountValid: Boolean
    private val starlarkType: net.starlark.java.syntax.StarlarkType?

    private val conditionalCheck: net.starlark.java.eval.ParamDescriptor.ConditionalCheck?

    private enum class HowToHandleReturn {
        NULL_TO_NONE,  // any Starlark value; null -> None
        ERROR_ON_NULL,  // any Starlark value; null -> error
        STARLARK_INT_OF_INT,  // Java int -> StarlarkInt
        FROM_JAVA,  // Starlark.fromJava conversion (List, Map, various Numbers, null perhaps)
    }

    private val howToHandleReturn: HowToHandleReturn

    private class ParameterizedTypeImpl(
        rawType: java.lang.reflect.Type?,
        actualTypeArguments: Array<java.lang.reflect.Type?>?
    ) : java.lang.reflect.ParameterizedType {
        private val rawType: java.lang.reflect.Type?
        private val actualTypeArguments: Array<java.lang.reflect.Type?>?

        init {
            this.rawType = rawType
            this.actualTypeArguments = actualTypeArguments
        }

        override fun getActualTypeArguments(): Array<java.lang.reflect.Type?>? {
            return actualTypeArguments
        }

        override fun getRawType(): java.lang.reflect.Type? {
            return rawType
        }

        override fun getOwnerType(): java.lang.reflect.Type? {
            return null
        }
    }

    /** Returns the StarlarkMethod annotation corresponding to this method.  */
    fun getAnnotation(): net.starlark.java.annot.StarlarkMethod? {
        if (annotation == null) {
            // Annotation is null on deserialization, because deserializer can't handle annotations
            annotation = net.starlark.java.annot.StarlarkAnnotations.getStarlarkMethod(method)
        }
        return annotation
    }

    init {
        this.manager = manager
        this.method = method
        this.annotation = annotation
        this.name = name
        this.doc = doc
        this.documented = documented
        this.structField = structField
        this.parameters = parameters
        this.extraPositionals = extraPositionals
        this.extraKeywords = extraKeywords
        this.selfCall = selfCall
        this.allowReturnNones = allowReturnNones
        this.useStarlarkThread = useStarlarkThread
        this.useStarlarkSemantics = useStarlarkSemantics
        this.typeConstructorProxy = if (isTypeConstructor) method.getReturnType() else null

        val ret: java.lang.Class<*>? = method.getReturnType()
        if (ret == Void.TYPE || ret == Boolean::class.javaPrimitiveType) {
            // * `void` function returns `null`
            // * `boolean` function never returns `null`
            // We could have specialized enum variant, but null check is cheap.
            howToHandleReturn = net.starlark.java.eval.MethodDescriptor.HowToHandleReturn.NULL_TO_NONE
        } else if (net.starlark.java.eval.StarlarkValue::class.java.isAssignableFrom(ret)
            || String::class.java == ret || Boolean::class.java == ret
        ) {
            howToHandleReturn =
                if (allowReturnNones) net.starlark.java.eval.MethodDescriptor.HowToHandleReturn.NULL_TO_NONE else net.starlark.java.eval.MethodDescriptor.HowToHandleReturn.ERROR_ON_NULL
        } else if (ret == Int::class.javaPrimitiveType) {
            howToHandleReturn = net.starlark.java.eval.MethodDescriptor.HowToHandleReturn.STARLARK_INT_OF_INT
        } else {
            howToHandleReturn = net.starlark.java.eval.MethodDescriptor.HowToHandleReturn.FROM_JAVA
        }

        this.positionalsReusableAsJavaArgsVectorIfArgumentCountValid =
            !extraKeywords && !extraPositionals && !useStarlarkSemantics && !useStarlarkThread && java.util.Arrays.stream<net.starlark.java.eval.ParamDescriptor?>(
                parameters
            ).allMatch(java.util.function.Predicate { param: net.starlark.java.eval.ParamDescriptor? ->
                net.starlark.java.eval.MethodDescriptor.Companion.paramUsableAsPositionalWithoutChecks(param)
            })

        if (!annotation.enableOnlyWithFlag.isEmpty() || !annotation.disableWithFlag.isEmpty()) {
            conditionalCheck =
                net.starlark.java.eval.ParamDescriptor.ConditionalCheck(
                    annotation.enableOnlyWithFlag,
                    annotation.disableWithFlag
                )
        } else {
            conditionalCheck = null
        }

        starlarkType =
            net.starlark.java.eval.MethodDescriptor.Companion.buildStarlarkType(
                method,
                annotation,
                parameters,
                structField,
                extraPositionals,
                extraKeywords,
                allowReturnNones
            )
    }

    /** Calls this method, which must have `structField=true`.  */
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun callField(
        obj: Any?,
        semantics: net.starlark.java.eval.StarlarkSemantics?,
        mu: net.starlark.java.eval.Mutability?
    ): Any? {
        check(structField) { "not a struct field: " + name }
        val args =
            if (useStarlarkSemantics) arrayOf<Any>(semantics) else net.starlark.java.eval.MethodDescriptor.Companion.EMPTY
        return call(obj, args, mu)
    }

    /**
     * Invokes this method using `obj` as a target and `args` as Java arguments.
     * 
     * 
     * Methods with `void` return type return `None` following Python convention.
     * 
     * 
     * The Mutability is used if it is necessary to allocate a Starlark copy of a Java result.
     */
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun call(obj: Any?, args: Array<Any>, mu: net.starlark.java.eval.Mutability?): Any? {
        com.google.common.base.Preconditions.checkNotNull<Any?>(obj)
        val result: Any?
        try {
            result = method.invoke(obj, *args)
        } catch (ex: java.lang.IllegalAccessException) {
            // "Can't happen": the annotated processor ensures that annotated methods are accessible.
            throw java.lang.IllegalStateException(ex)
        } catch (ex: java.lang.IllegalArgumentException) {
            // "Can't happen": unexpected type mismatch in obj/args.
            // Show details to aid debugging (see e.g. b/162444744).
            val buf: java.lang.StringBuilder = java.lang.StringBuilder()
            buf.append(
                java.lang.String.format(
                    "IllegalArgumentException (%s) in Starlark call of `%s`, obj=%s (%s), args=[",
                    ex.getMessage(),
                    method,
                    net.starlark.java.eval.Starlark.Companion.repr(
                        obj,
                        net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
                    ),
                    net.starlark.java.eval.Starlark.Companion.type(obj)
                )
            )
            var sep = ""
            for (arg in args) {
                buf.append(
                    java.lang.String.format(
                        "%s%s (%s)",
                        sep,
                        net.starlark.java.eval.Starlark.Companion.repr(
                            arg,
                            net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
                        ),
                        net.starlark.java.eval.Starlark.Companion.type(arg)
                    )
                )
                sep = ", "
            }
            buf.append(']')
            throw java.lang.IllegalStateException(buf.toString(), ex)
        } catch (ex: java.lang.reflect.InvocationTargetException) {
            val e: Throwable = ex.getCause()
            if (e == null) {
                throw java.lang.IllegalStateException(ex)
            }
            // Don't intercept unchecked exceptions.
            com.google.common.base.Throwables.throwIfUnchecked(e)
            if (e is net.starlark.java.eval.EvalException) {
                throw e as net.starlark.java.eval.EvalException
            } else if (e is java.lang.InterruptedException) {
                throw e as java.lang.InterruptedException
            } else {
                // All other checked exceptions (e.g. LabelSyntaxException) are reported to Starlark.
                throw net.starlark.java.eval.EvalException(e)
            }
        }

        // This switch is an optimization to reduce the overhead
        // of an unconditional null check and fromJava call.
        when (howToHandleReturn) {
            net.starlark.java.eval.MethodDescriptor.HowToHandleReturn.NULL_TO_NONE -> return if (result != null) result else net.starlark.java.eval.Starlark.Companion.NONE
            net.starlark.java.eval.MethodDescriptor.HowToHandleReturn.ERROR_ON_NULL -> {
                if (result == null) {
                    throw methodInvocationReturnedNull(args)
                }
                return result
            }

            net.starlark.java.eval.MethodDescriptor.HowToHandleReturn.STARLARK_INT_OF_INT -> return net.starlark.java.eval.StarlarkInt.Companion.of(
                result as Int?
            )

            net.starlark.java.eval.MethodDescriptor.HowToHandleReturn.FROM_JAVA -> {
                if (result == null && !allowReturnNones) {
                    throw methodInvocationReturnedNull(args)
                }
                return net.starlark.java.eval.Starlark.Companion.fromJava(result, mu)
            }
        }
        throw java.lang.IllegalStateException("unreachable: " + howToHandleReturn)
    }

    @com.google.errorprone.annotations.CheckReturnValue // don't forget to throw it
    private fun methodInvocationReturnedNull(args: Array<Any>): java.lang.NullPointerException {
        return java.lang.NullPointerException(
            "method invocation returned null: " + getName() + net.starlark.java.eval.Tuple.Companion.of(*args)
        )
    }

    /** @see StarlarkMethod.name
     */
    fun getName(): String? {
        return name
    }

    fun getManager(): net.starlark.java.eval.CallUtils.BuiltinManager? {
        return manager
    }

    fun getMethod(): java.lang.reflect.Method {
        return method
    }

    /** @see StarlarkMethod.structField
     */
    fun isStructField(): Boolean {
        return structField
    }

    /** @see StarlarkMethod.useStarlarkThread
     */
    fun isUseStarlarkThread(): Boolean {
        return useStarlarkThread
    }

    /** @see StarlarkMethod.useStarlarkSemantics
     */
    fun isUseStarlarkSemantics(): Boolean {
        return useStarlarkSemantics
    }

    /** @return `true` if this method accepts extra arguments (`*args`)
     */
    fun acceptsExtraArgs(): Boolean {
        return extraPositionals
    }

    /** @see StarlarkMethod.extraKeywords
     */
    fun acceptsExtraKwargs(): Boolean {
        return extraKeywords
    }

    /** @see StarlarkMethod.parameters
     */
    fun getParameters(): Array<net.starlark.java.eval.ParamDescriptor?> {
        return parameters
    }

    /** Returns the index of the named parameter or -1 if not found.  */
    fun getParameterIndex(name: String?): Int {
        for (i in parameters.indices) {
            if (parameters[i].getName() == name) {
                return i
            }
        }
        return -1
    }

    /** @see StarlarkMethod.documented
     */
    fun isDocumented(): Boolean {
        return documented
    }

    /** @see StarlarkMethod.doc
     */
    fun getDoc(): String? {
        return doc
    }

    /** @see StarlarkMethod.selfCall
     */
    fun isSelfCall(): Boolean {
        return selfCall
    }

    fun getStarlarkType(): net.starlark.java.syntax.StarlarkType? {
        return starlarkType
    }

    fun getTypeConstructorProxy(): java.lang.Class<*>? {
        return typeConstructorProxy
    }

    /**
     * Returns true if we may directly reuse the Starlark positionals vector as the Java `args`
     * vector passed to [.call] as long as the Starlark call was made with a valid number of
     * arguments.
     * 
     * 
     * More precisely, this means that we do not need to insert extra values into the args vector
     * (such as ones corresponding to `*args`, `**kwargs`, or `self` in Starlark),
     * and all Starlark parameters are simple positional parameters which cannot be disabled by a flag
     * and do not require type checking.
     */
    fun isPositionalsReusableAsJavaArgsVectorIfArgumentCountValid(): Boolean {
        return positionalsReusableAsJavaArgsVectorIfArgumentCountValid
    }

    /** Returns true if parameter is enabled.  */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun checkEnabled(thread: net.starlark.java.eval.StarlarkThread) {
        if (conditionalCheck == null) { // fast path
            return
        }

        // TODO(b/407506132): A method enabled by a non-experimental flag should not be marked as
        //  experimental
        if (!thread
                .getSemantics()
                .isFeatureEnabledBasedOnTogglingFlags(
                    conditionalCheck.enableOnlyWithFlag(), conditionalCheck.disableWithFlag()
                )
        ) {
            if (!conditionalCheck.enableOnlyWithFlag().isEmpty()) {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "function %s() is experimental and thus unavailable with the current flags. It may be"
                            + " enabled by setting --%s",
                    name, conditionalCheck.enableOnlyWithFlag().substring(1)
                ) // remove [+-] prefix
            }
            if (!conditionalCheck.disableWithFlag().isEmpty()) {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "function %s() is deprecated and will be removed soon. It may be temporarily re-enabled"
                            + " by setting --%s",
                    name, conditionalCheck.disableWithFlag().substring(1)
                ) // remove [+-] prefix
            }
        }
    }

    companion object {
        private fun buildStarlarkType(
            method: java.lang.reflect.Method,
            annotation: net.starlark.java.annot.StarlarkMethod,
            parameters: Array<net.starlark.java.eval.ParamDescriptor?>,
            structField: Boolean,
            extraPositionals: Boolean,
            extraKeywords: Boolean,
            allowReturnNones: Boolean
        ): net.starlark.java.syntax.StarlarkType? {
            var parameters: Array<net.starlark.java.eval.ParamDescriptor?> = parameters
            if (structField) {
                var returnType: net.starlark.java.syntax.StarlarkType? =
                    net.starlark.java.eval.MethodDescriptor.Companion.starlarkTypeFromJava(method.getGenericReturnType())
                if (allowReturnNones) {
                    returnType = net.starlark.java.syntax.Types.union(returnType, net.starlark.java.syntax.Types.NONE)
                }
                return returnType
            }

            var paramAnnotations: Array<net.starlark.java.annot.Param?> = annotation.parameters
            var methodParamTypes: Array<java.lang.reflect.Type?> = method.getGenericParameterTypes()

            // String methods are special-cased to pass the string receiver object as the first parameter
            // to the Java method. We don't want to include the string receiver in the callable's signature.
            if (method.getDeclaringClass() == net.starlark.java.eval.StringModule::class.java) {
                parameters = java.util.Arrays.copyOfRange<net.starlark.java.eval.ParamDescriptor?>(
                    parameters,
                    1,
                    parameters.size
                )
                paramAnnotations = java.util.Arrays.copyOfRange<net.starlark.java.annot.Param?>(
                    paramAnnotations,
                    1,
                    paramAnnotations.size
                )
                methodParamTypes =
                    java.util.Arrays.copyOfRange<java.lang.reflect.Type?>(methodParamTypes, 1, methodParamTypes.size)
            }

            val parameterNames: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            val parameterTypes: com.google.common.collect.ImmutableList.Builder<net.starlark.java.syntax.StarlarkType?> =
                com.google.common.collect.ImmutableList.builder<net.starlark.java.syntax.StarlarkType?>()
            val mandatoryParameters: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.builder<String?>()
            var processingPositionalOnly = true
            var processingPositional = true
            var numPositionalOnlyParameters = parameters.size
            var numOrdinaryParameters = parameters.size
            for (i in parameters.indices) {
                if (parameters[i].isNamed() && processingPositionalOnly) {
                    processingPositionalOnly = false
                    numPositionalOnlyParameters = i
                }
                if (!parameters[i].isPositional() && processingPositional) { // the first keyword argument
                    processingPositional = false
                    numOrdinaryParameters = i
                }
                parameterNames.add(parameters[i].getName())
                val allowedTypes: Array<net.starlark.java.annot.ParamType?> = paramAnnotations[i].allowedTypes
                // User supplied type
                if (allowedTypes.size > 0) {
                    parameterTypes.add(
                        net.starlark.java.eval.MethodDescriptor.Companion.starlarkTypeFromAnnotation(
                            allowedTypes
                        )
                    )
                } else {
                    parameterTypes.add(
                        net.starlark.java.eval.MethodDescriptor.Companion.starlarkTypeFromJava(
                            methodParamTypes[i]
                        )
                    )
                }
                if (parameters[i].getDefaultValue() == null) {
                    mandatoryParameters.add(parameters[i].getName())
                }
            }
            var returnType: net.starlark.java.syntax.StarlarkType?
            if (method.getReturnType() == Any::class.java) {
                returnType = net.starlark.java.syntax.Types.ANY
            } else {
                returnType =
                    net.starlark.java.eval.MethodDescriptor.Companion.starlarkTypeFromJava(method.getGenericReturnType())
                if (allowReturnNones) {
                    returnType = net.starlark.java.syntax.Types.union(returnType, net.starlark.java.syntax.Types.NONE)
                }
            }

            return net.starlark.java.syntax.Types.callable(
                parameterNames.build(),
                parameterTypes.build(),
                numPositionalOnlyParameters,
                numOrdinaryParameters,
                mandatoryParameters.build(),  // TODO(ilist@): more precise type on args and kwargs
                if (extraPositionals) net.starlark.java.syntax.Types.ANY else null,
                if (extraKeywords) net.starlark.java.syntax.Types.ANY else null,
                returnType
            )
        }

        fun starlarkTypeFromAnnotation(paramTypes: Array<net.starlark.java.annot.ParamType?>): net.starlark.java.syntax.StarlarkType? {
            return net.starlark.java.syntax.Types.union(
                java.util.Arrays.stream<net.starlark.java.annot.ParamType?>(paramTypes)
                    .map<java.lang.reflect.Type?>(
                        java.util.function.Function { paramType: net.starlark.java.annot.ParamType? ->
                            if (paramType.type.getTypeParameters().size == 1) {
                                return@map net.starlark.java.eval.MethodDescriptor.ParameterizedTypeImpl(
                                    paramType.type, arrayOf<java.lang.reflect.Type?>(paramType.generic1)
                                )
                            } else {
                                return@map paramType.type
                            }
                        })
                    .map<net.starlark.java.syntax.StarlarkType?>(java.util.function.Function { cls: java.lang.reflect.Type? ->
                        net.starlark.java.eval.MethodDescriptor.Companion.starlarkTypeFromJava(
                            cls
                        )
                    })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<net.starlark.java.syntax.StarlarkType?>())
            )
        }

        /** Returns the Starlark type corresponding to the given Java type.  */
        fun starlarkTypeFromJava(cls: java.lang.reflect.Type?): net.starlark.java.syntax.StarlarkType {
            if (cls === net.starlark.java.eval.NoneType::class.java || cls === Void.TYPE) {
                return net.starlark.java.syntax.Types.NONE
            } else if (cls === String::class.java) {
                return net.starlark.java.syntax.Types.STR
            } else if (cls === Boolean::class.java || cls === Boolean::class.javaPrimitiveType) {
                return net.starlark.java.syntax.Types.BOOL
            } else if (cls === Int::class.javaPrimitiveType || cls === Long::class.javaPrimitiveType || cls === Int::class.java || cls === Long::class.java || cls === net.starlark.java.eval.StarlarkInt::class.java || (cls is java.lang.Class<*> && BigInteger::class.java.isAssignableFrom(
                    cls
                ))
            ) {
                return net.starlark.java.syntax.Types.INT
            } else if (cls === Double::class.javaPrimitiveType || cls === Double::class.java || cls === net.starlark.java.eval.StarlarkFloat::class.java) {
                return net.starlark.java.syntax.Types.FLOAT
            } else if (cls is java.lang.reflect.ParameterizedType && cls.getRawType() === net.starlark.java.eval.Dict::class.java) {
                return net.starlark.java.syntax.Types.dict(
                    net.starlark.java.eval.MethodDescriptor.Companion.starlarkTypeFromJava(cls.getActualTypeArguments()[0]),
                    net.starlark.java.eval.MethodDescriptor.Companion.starlarkTypeFromJava(cls.getActualTypeArguments()[1])
                )
            } else if (cls is java.lang.reflect.ParameterizedType && cls.getRawType() === net.starlark.java.eval.StarlarkList::class.java) {
                return net.starlark.java.syntax.Types.list(
                    net.starlark.java.eval.MethodDescriptor.Companion.starlarkTypeFromJava(
                        cls.getActualTypeArguments()[0]
                    )
                )
            } else if (cls is java.lang.reflect.ParameterizedType && cls.getRawType() === net.starlark.java.eval.StarlarkSet::class.java) {
                return net.starlark.java.syntax.Types.set(
                    net.starlark.java.eval.MethodDescriptor.Companion.starlarkTypeFromJava(
                        cls.getActualTypeArguments()[0]
                    )
                )
            } else if (cls is java.lang.Class<*> && net.starlark.java.eval.Tuple::class.java.isAssignableFrom(cls)) {
                // TODO: #27370 - Should we ever return a narrower tuple type?
                return net.starlark.java.syntax.Types.homogeneousTuple(net.starlark.java.syntax.Types.ANY)
            } else if (cls is java.lang.reflect.ParameterizedType
                && cls.getRawType() === net.starlark.java.eval.StarlarkIterable::class.java
            ) {
                return net.starlark.java.syntax.Types.collection(
                    net.starlark.java.eval.MethodDescriptor.Companion.starlarkTypeFromJava(
                        cls.getActualTypeArguments()[0]
                    )
                )
            } else if (cls is java.lang.reflect.ParameterizedType && cls.getRawType() === net.starlark.java.eval.Sequence::class.java) {
                return net.starlark.java.syntax.Types.sequence(
                    net.starlark.java.eval.MethodDescriptor.Companion.starlarkTypeFromJava(
                        cls.getActualTypeArguments()[0]
                    )
                )
            } else if (cls === Any::class.java || cls === net.starlark.java.eval.StarlarkValue::class.java) {
                return net.starlark.java.syntax.Types.OBJECT
            } else {
                // TODO(ilist@): handle more complex types
                return net.starlark.java.syntax.Types.ANY
            }
        }

        private fun paramUsableAsPositionalWithoutChecks(param: net.starlark.java.eval.ParamDescriptor): Boolean {
            return param.isPositional()
                    && param.conditionalCheck == null && param.getAllowedClasses() == null
        }

        /** Returns starlark method descriptor for provided Java method and signature annotation.  */
        fun of(
            manager: net.starlark.java.eval.CallUtils.BuiltinManager?,
            method: java.lang.reflect.Method,
            annotation: net.starlark.java.annot.StarlarkMethod
        ): MethodDescriptor {
            // This happens when the interface is public but the implementation classes
            // have reduced visibility.
            method.setAccessible(true)

            val paramClasses: Array<java.lang.Class<*>?> = method.getParameterTypes()
            val paramAnnots: Array<net.starlark.java.annot.Param?> = annotation.parameters
            val params: Array<net.starlark.java.eval.ParamDescriptor?> =
                arrayOfNulls<net.starlark.java.eval.ParamDescriptor>(paramAnnots.size)
            java.util.Arrays.setAll<net.starlark.java.eval.ParamDescriptor?>(
                params,
                java.util.function.IntFunction { i: Int ->
                    net.starlark.java.eval.ParamDescriptor.Companion.of(
                        paramAnnots[i],
                        paramClasses[i]
                    )
                })

            return net.starlark.java.eval.MethodDescriptor(
                manager,
                method,
                annotation,
                annotation.name,
                annotation.doc,
                annotation.documented,
                annotation.structField,
                params,
                !annotation.extraPositionals.name.isEmpty(),
                !annotation.extraKeywords.name.isEmpty(),
                annotation.selfCall,
                annotation.allowReturnNones,
                annotation.useStarlarkThread,
                annotation.useStarlarkSemantics,
                annotation.isTypeConstructor
            )
        }

        private val EMPTY = arrayOf<Any?>()
    }
}
