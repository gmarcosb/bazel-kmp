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
package net.starlark.java.syntax

import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.errorprone.annotations.FormatMethod
import net.starlark.java.spelling.SpellChecker.didYouMean
import java.lang.String
import java.util.*
import java.util.function.Function
import java.util.stream.Collectors
import kotlin.Any
import kotlin.AssertionError
import kotlin.Boolean
import kotlin.IllegalArgumentException
import kotlin.Int

/**
 * A visitor for validating that expressions and statements respect the types of the symbols
 * appearing within them, as determined by the type tagger.
 * 
 * 
 * In addition, this visitor modifies the function type on the [Resolver.Function] objects
 * of [LambdaExpression]s in the [TypeTable] (originally populated by the [ ]) to have a more precise return type, if possible; and populates the types of the
 * [Resolver.Binding] objects of untyped variables with the inferred types of their values in
 * their first assignments in typed code.
 * 
 * 
 * Type annotations are not traversed by this visitor.
 */
class TypeChecker private constructor(typeTable: TypeTable, typeContext: TypeContext) : NodeVisitor() {
    private val typeTable: TypeTable
    private val typeContext: TypeContext

    // Empty if we were invoked via inferTypeOf() to type-check an expression (since inside
    // an expression, no function definitions are allowed). Populated and mutated by visitation.
    private val functionStack = ArrayDeque<Resolver.Function>()

    // Formats and reports an error at the start of the specified node.
    @FormatMethod
    private fun errorf(node: Node, format: String, vararg args: Any?) {
        errorf(node.getStartLocation(), format, *args)
    }

    // Formats and reports an error at the specified location.
    @FormatMethod
    private fun errorf(loc: Location?, format: String, vararg args: Any?) {
        typeTable.errors.add(SyntaxError(loc, String.format(format, *args)))
    }

    private fun binaryOperatorError(
        xType: StarlarkType?,
        operator: TokenKind?,
        operatorLocation: Location?,
        yType: StarlarkType?,
        augmentedAssignment: Boolean,
        extraMessage: kotlin.String = ""
    ) {
        // TODO: #28037 - better error message if LHS and/or RHS are unions?
        errorf(
            operatorLocation,
            "operator '%s%s' cannot be applied to types '%s' and '%s'%s",
            operator,
            if (augmentedAssignment) "=" else "",
            xType,
            yType,
            if (extraMessage.isEmpty()) "" else ": " + extraMessage
        )
    }

    init {
        this.typeTable = typeTable
        this.typeContext = typeContext
    }

    /**
     * Returns the annotated type of an identifier's symbol, asserting that the binding information is
     * present.
     * 
     * 
     * If a type is not set on the binding it is taken to be `Any`.
     */
    // TODO: #27370 - An unannotated variable should either be treated as Any or else inferred from
    // its first binding occurrence, depending on how the var is introduced and whether it's in typed
    // code.
    private fun getType(id: Identifier): StarlarkType {
        val binding = id.getBinding()
        Preconditions.checkNotNull<Resolver.Binding?>(binding)
        val type =
            when (binding!!.getScope()) {
                Resolver.Scope.UNIVERSAL -> Preconditions.checkNotNull<StarlarkType?>(
                    typeContext.getUniversalSymbolType(
                        binding.getName()
                    )
                )

                Resolver.Scope.PREDECLARED -> Preconditions.checkNotNull<StarlarkType?>(
                    typeContext.getPredeclaredSymbolType(
                        binding.getName()
                    )
                )

                else -> typeTable.getType(binding)
            }
        return if (type != null) type else Types.ANY
    }

    private fun errorIfKeyNotInt(index: IndexExpression, objType: StarlarkType?, keyType: StarlarkType?) {
        if (!StarlarkType.Companion.assignableFrom(Types.INT, keyType)) {
            errorf(
                index.getLbracketLocation(),
                "'%s' of type '%s' must be indexed by an integer, but got '%s'",
                index.getObject(),
                objType,
                keyType
            )
        }
    }

    /**
     * Infers the type of an expression from a bottom-up traversal, relying on type information stored
     * in identifier bindings by the [TypeTagger].
     * 
     * 
     * May not be called on type expressions (annotations, var statements, type alias statements).
     */
    private fun infer(expr: Expression): StarlarkType? {
        when (expr.kind()) {
            Expression.Kind.IDENTIFIER -> {
                return getType(expr as Identifier)
            }

            Expression.Kind.STRING_LITERAL -> {
                return Types.STR
            }

            Expression.Kind.INT_LITERAL -> {
                return Types.INT
            }

            Expression.Kind.FLOAT_LITERAL -> {
                return Types.FLOAT
            }

            Expression.Kind.CAST -> {
                val cast = expr as CastExpression
                val unused = infer(cast.getValue()) // only to verify the value expr is well-typed
                return cast.getStarlarkType()
            }

            Expression.Kind.DOT -> {
                return inferDot(expr as DotExpression)
            }

            Expression.Kind.INDEX -> {
                return inferIndex(expr as IndexExpression)
            }

            Expression.Kind.SLICE -> {
                return inferSlice(expr as SliceExpression)
            }

            Expression.Kind.LAMBDA -> {
                val lambda = expr as LambdaExpression
                val inferedReturnType = infer(lambda.getBody())
                val originalType =
                    Preconditions.checkNotNull<Types.CallableType>(
                        typeTable.getType(lambda.getResolvedFunction()),
                        "type tagger should have set type for lambda expr '%s'",
                        lambda
                    )
                if (originalType.getReturnType() != inferedReturnType) {
                    // Update the lambda function type with a more precise return type.
                    typeTable.setType(
                        lambda.getResolvedFunction(),
                        Types.callable(
                            originalType.getParameterNames(),
                            originalType.getParameterTypes(),
                            originalType.getNumPositionalOnlyParameters(),
                            originalType.getNumPositionalParameters(),
                            originalType.getMandatoryParameters(),
                            originalType.getVarargsType(),
                            originalType.getKwargsType(),
                            inferedReturnType
                        )
                    )
                }
                return typeTable.getType(lambda.getResolvedFunction())
            }

            Expression.Kind.LIST_EXPR -> {
                val list = expr as ListExpression
                val elementTypes: MutableList<StarlarkType> = ArrayList<StarlarkType>()
                for (element in list.getElements()) {
                    elementTypes.add(infer(element)!!)
                }
                return if (list.isTuple())
                    Types.tuple(ImmutableList.copyOf<StarlarkType?>(elementTypes))
                else
                    Types.listRvalue(Types.union(elementTypes))
            }

            Expression.Kind.DICT_EXPR -> {
                val dict = expr as DictExpression
                val keyTypes: MutableList<StarlarkType> = ArrayList<StarlarkType>()
                val valueTypes: MutableList<StarlarkType> = ArrayList<StarlarkType>()
                for (entry in dict.getEntries()) {
                    keyTypes.add(infer(entry.getKey())!!)
                    valueTypes.add(infer(entry.getValue())!!)
                }
                return Types.dictRvalue(Types.union(keyTypes), Types.union(valueTypes))
            }

            Expression.Kind.CALL -> {
                // TODO: #27370 - we could special-case set literals; e.g. check if a call expression is
                // `set()`, verifying using typeContext that `set` is the set type constructor.
                return inferCall(expr as CallExpression)
            }

            Expression.Kind.CONDITIONAL -> {
                val cond = expr as ConditionalExpression
                return Types.union(infer(cond.getThenCase()), infer(cond.getElseCase()))
            }

            Expression.Kind.BINARY_OPERATOR -> {
                val binop = expr as BinaryOperatorExpression
                val xType = infer(binop.getX())
                val yType = infer(binop.getY())
                return inferBinaryOperator(
                    binop.getX(),
                    xType!!,
                    binop.getOperator(),
                    binop.getOperatorLocation(),
                    binop.getY(),
                    yType,  /* augmentedAssignment= */
                    false
                )
            }

            Expression.Kind.UNARY_OPERATOR -> {
                val unop = expr as UnaryOperatorExpression
                if (unop.getOperator() == TokenKind.NOT) {
                    // NOT always returns a boolean (even if applied to Any or unions).
                    return Types.BOOL
                }
                val xType = infer(unop.getX())
                if (xType == Types.ANY
                    || ((unop.getOperator() == TokenKind.MINUS || unop.getOperator() == TokenKind.PLUS)
                            && StarlarkType.Companion.assignableFrom(Types.NUMERIC, xType))
                    || (unop.getOperator() == TokenKind.TILDE && xType == Types.INT)
                ) {
                    // Unary operators other than NOT preserve the type of their operand.
                    return xType
                }
                errorf(
                    unop.getStartLocation(),
                    "operator '%s' cannot be applied to type '%s'",
                    unop.getOperator(),
                    xType
                )
                return Types.ANY
            }

            Expression.Kind.COMPREHENSION -> {
                return inferComprehension(expr as Comprehension)
            }

            else -> {
                // TODO: #28037 - support isinstance expressions.
                errorf(expr, "UNSUPPORTED: cannot typecheck %s expression", expr.kind())
                return Types.ANY
            }
        }
    }

    private fun inferDot(dot: DotExpression): StarlarkType {
        return Types.union(inferDotUnfolded(dot, infer(dot.getObject())!!))
    }

    /**
     * Infers the non-flattened unfolded list of possible types of a dot expression.
     * 
     * 
     * For example, given if field f has type int for type T, and type str|bool for type U, this
     * function will return the list `[int, str|bool]` for x.f where x has type T|U.
     * 
     * 
     * When a dot expression is used as a value, one should take the union type of the returned
     * types. But when a dot expression is used as the LHS of an assignment, one should take their
     * meet.
     */
    private fun inferDotUnfolded(dot: DotExpression, objType: StarlarkType): ImmutableList<StarlarkType> {
        val name = dot.getField().getName()

        if (objType == Types.ANY) {
            return ImmutableList.of<StarlarkType?>(Types.ANY)
        }

        val objElemTypes = Types.unfoldUnion(objType)
        val resultTypes =
            ImmutableList.builderWithExpectedSize<StarlarkType?>(objElemTypes.size())
        for (objElemType in objElemTypes) {
            val fieldType = objElemType.getField(name, typeContext)
            if (fieldType == null) {
                errorf(
                    dot.getDotLocation(),
                    "'%s' of type '%s' does not have field '%s'",
                    dot.getObject(),
                    objType,
                    name
                )
                return ImmutableList.of<StarlarkType?>(Types.ANY)
            }
            resultTypes.add(fieldType)
        }
        return resultTypes.build()
    }

    private fun inferIndex(index: IndexExpression): StarlarkType {
        return Types.union(inferIndexUnfolded(index, infer(index.getObject())!!, infer(index.getKey())))
    }

    /**
     * Infers the non-flattened unfolded list of possible types of an index expression.
     * 
     * 
     * For example, given object type `list[int] | list[str|bool]`, this function will return
     * the list `[int, str|bool]`.
     * 
     * 
     * When an index expression is used as a value, one should take the union type of the returned
     * types. But when an index expression is used as the LHS of an assignment, one should take their
     * meet.
     */
    private fun inferIndexUnfolded(
        index: IndexExpression, objType: StarlarkType, keyType: StarlarkType?
    ): ImmutableList<StarlarkType> {
        val obj = index.getObject()
        val key = index.getKey()

        if (objType == Types.ANY) {
            return ImmutableList.of<StarlarkType?>(Types.ANY)
        }

        val objElemTypes = Types.unfoldUnion(objType)
        val resultTypes =
            ImmutableList.builderWithExpectedSize<StarlarkType?>(objElemTypes.size())
        for (objElemType in objElemTypes) {
            if (objElemType == Types.ANY) {
                resultTypes.add(Types.ANY)
            } else if (objElemType is Types.FixedLengthTupleType) {
                errorIfKeyNotInt(index, objElemType, keyType)
                val elementTypes = objElemType.getElementTypes()
                var resultType: StarlarkType? = null
                // Project out the type of the specific component if we can statically determine the index.
                val intKey: Int? = getIntValueExact(key)
                if (intKey != null) {
                    var i = intKey
                    if (i < 0) {
                        // Same logic as for EvalUtils#getSequenceIndex.
                        i += elementTypes.size()
                    }
                    if (0 <= i && i < elementTypes.size()) {
                        resultType = elementTypes.get(i)
                    } else {
                        errorf(
                            index.getLbracketLocation(),
                            "'%s' of type '%s' is indexed by integer %s, which is out-of-range",
                            obj,
                            objType,
                            intKey
                        )
                        // Don't complain about uses of the result type when we don't even know what result type
                        // the user wanted.
                        return ImmutableList.of<StarlarkType?>(Types.ANY)
                    }
                }
                if (resultType == null) {
                    resultType = objElemType.toHomogeneous().getElementType()
                }
                resultTypes.add(resultType)
            } else if (objElemType is Types.AbstractSequenceType) {
                errorIfKeyNotInt(index, objType, keyType) // fall through on error
                resultTypes.add(objElemType.getElementType())
            } else if (objElemType is Types.AbstractMappingType) {
                if (!StarlarkType.Companion.assignableFrom(objElemType.getKeyType(), keyType)) {
                    errorf(
                        index.getLbracketLocation(),
                        "'%s' of type '%s' requires key type '%s', but got '%s'",
                        obj,
                        objType,
                        objElemType.getKeyType(),
                        keyType
                    )
                    // Fall through to returning the value type.
                }
                resultTypes.add(objElemType.getValueType())
            } else if (objElemType == Types.STR) {
                errorIfKeyNotInt(index, objType, keyType) // fall through on error
                resultTypes.add(Types.STR)
            } else {
                errorf(index.getLbracketLocation(), "cannot index '%s' of type '%s'", obj, objType)
                return ImmutableList.of<StarlarkType?>(Types.ANY)
            }
        }
        return resultTypes.build()
    }

    private fun inferSlice(slice: SliceExpression): StarlarkType {
        var step: Int? = getIntValueExact(slice.getStep())
        if (step == null) {
            step = 1
            if (slice.getStep() != null) {
                val stepType = infer(slice.getStep()!!)
                if (!StarlarkType.Companion.assignableFrom(Types.INT, stepType)) {
                    errorf(slice.getStep()!!, "got '%s' for slice step, want int", stepType)
                    return Types.ANY
                }
            }
        } else if (step == 0) {
            errorf(slice.getStep()!!, "slice step cannot be zero")
            return Types.ANY
        }
        if (slice.getStart() != null) {
            val startType = infer(slice.getStart()!!)
            if (!StarlarkType.Companion.assignableFrom(Types.INT, startType)) {
                errorf(slice.getStart()!!, "got '%s' for start index, want int", startType)
                return Types.ANY
            }
        }
        if (slice.getStop() != null) {
            val stopType = infer(slice.getStop()!!)
            if (!StarlarkType.Companion.assignableFrom(Types.INT, stopType)) {
                errorf(slice.getStop()!!, "got '%s' for stop index, want int", stopType)
                return Types.ANY
            }
        }

        val objType = infer(slice.getObject())
        if (objType == Types.ANY) {
            return Types.ANY
        }
        val resultTypes = ArrayList<StarlarkType>()
        for (objElemType in Types.unfoldUnion(objType)) {
            if (objElemType == Types.ANY) {
                resultTypes.add(Types.ANY)
            } else if (objElemType == Types.STR) {
                resultTypes.add(Types.STR)
            } else if (objElemType is Types.FixedLengthTupleType) {
                val tupleElementTypes = objElemType.getElementTypes()
                val len: Int = tupleElementTypes.size()
                val start: Int? = getIntValueExact(slice.getStart())
                val stop: Int? = getIntValueExact(slice.getStop())
                val resultTupleElementTypes = ImmutableList.builder<StarlarkType?>()
                if (step != null && haveExactSliceBound(slice.getStart(), start)
                    && haveExactSliceBound(slice.getStop(), stop)
                ) {
                    if (step > 0) {
                        val startClamped = if (start != null) SyntaxUtils.toSliceBound(start, len) else 0
                        val stopClamped = if (stop != null) SyntaxUtils.toSliceBound(stop, len) else len
                        var i = startClamped.toLong()
                        while (i < stopClamped && i.toInt().toLong() == i) {
                            resultTupleElementTypes.add(tupleElementTypes.get(i.toInt()))
                            i += step.toLong()
                        }
                    } else {
                        val startClamped =
                            if (start != null) SyntaxUtils.toReverseSliceBound(start, len) else len - 1
                        val stopClamped = if (stop != null) SyntaxUtils.toReverseSliceBound(stop, len) else -1
                        var i = startClamped.toLong()
                        while (i > stopClamped && i.toInt().toLong() == i) {
                            resultTupleElementTypes.add(tupleElementTypes.get(i.toInt()))
                            i += step.toLong()
                        }
                    }
                    resultTypes.add(Types.tuple(resultTupleElementTypes.build()))
                } else {
                    resultTypes.add(objElemType.toHomogeneous())
                }
            } else if (objElemType is Types.AbstractSequenceType) {
                resultTypes.add(objElemType)
            } else {
                errorf(
                    slice.getLbracketLocation(),
                    "invalid slice operand '%s' of type '%s', expected Sequence or str",
                    slice.getObject(),
                    objElemType
                )
                resultTypes.add(Types.ANY)
            }
        }
        return Types.union(resultTypes)
    }

    private fun inferBinaryOperator(
        xExpr: Expression?,
        xType: StarlarkType,
        operator: TokenKind,
        operatorLocation: Location?,
        yExpr: Expression?,
        yType: StarlarkType?,
        augmentedAssignment: Boolean
    ): StarlarkType? {
        // TokenKind operator = binop.getOperator();
        when (operator) {
            TokenKind.AND, TokenKind.OR, TokenKind.EQUALS_EQUALS, TokenKind.NOT_EQUALS -> {
                // Boolean regardless of LHS and RHS.
                return Types.BOOL
            }

            TokenKind.LESS, TokenKind.LESS_EQUALS, TokenKind.GREATER, TokenKind.GREATER_EQUALS -> {
                // Boolean or type error.
                if (StarlarkType.Companion.comparable(xType, yType)) {
                    return Types.BOOL
                }
                binaryOperatorError(xType, operator, operatorLocation, yType, augmentedAssignment)
                return Types.ANY
            }

            else -> {
                // Take the union of all types inferred by crossing the left and right union elements
                // (each of which must be a valid combination of rhs and lhs for the operator).
                val xTypes = Types.unfoldUnion(xType)
                val yTypes = Types.unfoldUnion(yType)
                val resultTypes = ArrayList<StarlarkType>()
                for (xElemType in xTypes) {
                    for (yElemType in yTypes) {
                        var resultType = xElemType.inferBinaryOperator(operator, yElemType, true)
                        if (resultType == null) {
                            resultType = yElemType.inferBinaryOperator(operator, xElemType, false)
                        }
                        if (resultType == null && operator == TokenKind.STAR) {
                            // Tuple repetition is the only case where we need to examine the expressions.
                            // TODO: #28037 - We can get rid of the tuple repetition special case if we
                            // introduce ConstantIntType for integer constants.
                            if (StarlarkType.Companion.assignableFrom(Types.INT, xElemType)
                                && yElemType is Types.TupleType
                            ) {
                                resultType = inferTupleRepetition(yElemType, xExpr)
                            } else if (StarlarkType.Companion.assignableFrom(Types.INT, yElemType)
                                && xElemType is Types.TupleType
                            ) {
                                resultType = inferTupleRepetition(xElemType, yExpr)
                            }
                        }
                        if (resultType == null) {
                            binaryOperatorError(xType, operator, operatorLocation, yType, augmentedAssignment)
                            return Types.ANY
                        }
                        resultTypes.add(resultType)
                    }
                }
                return Types.union(resultTypes)
            }
        }
    }

    private fun inferCall(call: CallExpression): StarlarkType {
        // Collect and check the shape of the call's *args/**kwargs. (This check is independent of
        // callFunctionType.)
        var varargs: VarargsArgument? = null
        var kwargs: KwargsArgument? = null
        var numArgs: Int = call.getArguments().size()
        if (numArgs > 0 && call.getArguments().get(numArgs - 1) is Argument.StarStar) {
            kwargs = KwargsArgument.Companion.of(arg, this)
            if (kwargs == null) {
                // error already reported
                return Types.ANY
            }
            numArgs--
        }
        if (numArgs > 0 && call.getArguments().get(numArgs - 1) is Argument.Star) {
            varargs = VarargsArgument.Companion.of(arg, this)
            if (varargs == null) {
                // error already reported
                return Types.ANY
            }
            numArgs--
        }

        val callFunctionType = infer(call.getFunction())
        if (callFunctionType == Types.ANY) {
            return Types.ANY
        }

        // Collect call's argument types (excluding *args and **kwargs).
        val argTypes =
            call.getArguments().stream()
                .limit(numArgs.toLong())
                .map<StarlarkType?>(Function { arg: Argument? -> infer(arg!!.getValue()) })
                .collect(ImmutableList.toImmutableList<StarlarkType?>())

        val callFunctionTypes = Types.unfoldUnion(callFunctionType)
        val returnTypes = ArrayList<StarlarkType>()
        for (callFunctionElemType in callFunctionTypes) {
            if (callFunctionElemType == Types.ANY) {
                // Nothing we can check.
                returnTypes.add(Types.ANY)
                continue
            }
            val callable = toCallableType(callFunctionElemType)
            if (callable == null) {
                errorf(
                    call.getFunction(),
                    "'%s' is not callable; got type '%s'",
                    call.getFunction(),
                    callFunctionType
                )
                return Types.ANY
            }

            // TODO: #28043 - Some of the checks below can be used to implement
            // Types.CallableType.assignableFromHook().

            // Indices of residual arguments in call.getArguments() and their corresponding types in
            // argTypes. (Micro-optimization to avoid allocating <Argument, StarlarkType> pairs.)
            val residualPositional = ArrayList<Int?>(0)
            val residualNamed = ArrayList<Int?>(0)
            // Names of mandatory parameters (both positional and named) having a corresponding argument.
            val seenMandatoryParameters: ArrayList<kotlin.String?> =
                ArrayList<kotlin.String?>(callable.getMandatoryParameters().size())
            for (i in 0..<numArgs) {
                val arg = call.getArguments().get(i)
                val parameterIndex: Int
                if (i < call.getNumPositionalArguments()) {
                    // positional argument
                    if (i < callable.getNumPositionalParameters()) {
                        parameterIndex = i
                    } else {
                        residualPositional.add(i)
                        continue
                    }
                } else {
                    // keyword argument
                    parameterIndex = callable.getParameterNames().indexOf(arg.getName())
                    if (parameterIndex < callable.getNumPositionalOnlyParameters()) {
                        // Either no param was found (i<0) or it's positional-only (0<=i<numPosOnly).
                        residualNamed.add(i)
                        continue
                    }
                }
                // Argument is not residual; check it against the corresponding parameter.
                val parameterName = callable.getParameterNames().get(parameterIndex)
                val parameterType = callable.getParameterTypeByPos(parameterIndex)
                if (callable.getMandatoryParameters().contains(parameterName)) {
                    seenMandatoryParameters.add(parameterName)
                }
                if (!StarlarkType.Companion.assignableFrom(parameterType, argTypes.get(i))) {
                    errorf(
                        call.getArguments().get(i),
                        "in call to '%s()', parameter '%s' got value of type '%s', want '%s'",
                        call.getFunction(),
                        parameterName,
                        argTypes.get(i),
                        parameterType
                    )
                    return Types.ANY
                }
            }
            if (!checkCallResidualPositionals(residualPositional, call, callable, argTypes)
                || !checkCallResidualNamed(residualNamed, call, callable, argTypes)
            ) {
                return Types.ANY
            }
            if (!checkCallMissingMandatoryArgs(
                    seenMandatoryParameters,  /* callHasVarargs= */
                    varargs != null,  /* callHasKwargs= */
                    kwargs != null,
                    call,
                    callable
                )
            ) {
                return Types.ANY
            }
            // Like mypy, we check that the call's *args/**kwargs values are assignable to the callable's
            // varargs/kwargs type. This is useful for the common case of a wrapper around a function
            // which forwards its *args/**kwargs to the wrapped function unchanged; but it also raises
            // failures for some legitimate uses: `def f(x: Any, **kwargs: str): ... ; f(**{"x" : 42})`.
            // In that case, the caller can bypass the check by casting to Any: `f(**(cast(Any, ...)))`.
            // We skip the check if the callable doesn't accept *args/**kwargs because the call's
            // *args/**kwargs may be used to set any remaining unset arguments, or may be empty.
            if (varargs != null
                && !checkAssignable(
                    callable.getVarargsType(),
                    varargs.elementType,
                    call,
                    varargs.expr!!,
                    "elements of argument after *"
                )
            ) {
                return Types.ANY
            }
            if (kwargs != null
                && !checkAssignable(
                    callable.getKwargsType(),
                    kwargs.valueType,
                    call,
                    kwargs.expr!!,
                    "values of argument after **"
                )
            ) {
                return Types.ANY
            }
            returnTypes.add(callable.getReturnType())
        }
        return Types.union(returnTypes)
    }

    @kotlin.jvm.JvmRecord
    private data class VarargsArgument(expr: Expression?, elementType: StarlarkType?) {
        val expr: Expression?
        val elementType: StarlarkType?

        init {
            this.expr = expr
            this.elementType = elementType
        }

        companion object {
            fun of(arg: Argument.Star, checker: TypeChecker): VarargsArgument? {
                val varargs = arg.getValue()
                val varargsType = checker.infer(varargs)
                val varargsElementType: StarlarkType? = Companion.findElementType(varargsType!!)
                if (varargsElementType == null) {
                    checker.errorf(varargs, "argument after * must be a sequence, not '%s'", varargsType)
                    return null
                }
                return VarargsArgument(varargs, varargsElementType)
            }

            /**
             * Finds the smallest `Sequence[E]` type which is a supertype of the given type, and
             * return E; or null if the given type does not have such a supertype.
             */
            private fun findElementType(maybeSequence: StarlarkType): StarlarkType? {
                if (maybeSequence == Types.ANY) {
                    return Types.ANY
                }
                val unfolded = Types.unfoldUnion(maybeSequence)
                val elements: ArrayList<StarlarkType> = ArrayList<StarlarkType>(unfolded.size())
                for (unfoldedElem in unfolded) {
                    // TODO: #28037 - Check getSubtypes() instead of relying purely on Java inheritance.
                    if (unfoldedElem is Types.AbstractSequenceType) {
                        elements.add(unfoldedElem.getElementType())
                    } else {
                        return null
                    }
                }
                return Types.union(elements)
            }
        }
    }

    @kotlin.jvm.JvmRecord
    private data class KwargsArgument(expr: Expression?, valueType: StarlarkType?) {
        val expr: Expression?
        val valueType: StarlarkType?

        init {
            this.expr = expr
            this.valueType = valueType
        }

        companion object {
            fun of(arg: Argument.StarStar, checker: TypeChecker): KwargsArgument? {
                val kwargs = arg.getValue()
                val kwargsType = checker.infer(kwargs)
                val kwargsValueType: StarlarkType? = Companion.findValueType(kwargsType!!)
                if (kwargsValueType == null) {
                    checker.errorf(
                        kwargs, "argument after ** must be a dict with string keys, not '%s'", kwargsType
                    )
                    return null
                }
                return KwargsArgument(kwargs, kwargsValueType)
            }

            /**
             * Finds the smallest `Mapping[K, V]` type which is a supertype of the given type such
             * that K is (a consistent-subtype-of?) str, and returns V; or null if the given type does not
             * have such a supertype.
             */
            private fun findValueType(maybeMapping: StarlarkType): StarlarkType? {
                if (maybeMapping == Types.ANY) {
                    return Types.ANY
                }
                val unfolded = Types.unfoldUnion(maybeMapping)
                val values: ArrayList<StarlarkType> = ArrayList<StarlarkType>(unfolded.size())
                for (unfoldedElem in unfolded) {
                    // TODO: #28037 - Check getSubtypes() instead of relying purely on Java inheritance.
                    if (unfoldedElem is Types.AbstractMappingType
                        && StarlarkType.Companion.assignableFrom(Types.STR, unfoldedElem.getKeyType())
                    ) {
                        values.add(unfoldedElem.getValueType())
                    } else {
                        return null
                    }
                }
                return Types.union(values)
            }
        }
    }

    /**
     * Returns `t` if it is a [Types.CallableType]; or its callable supertype otherwise
     * (e.g. for self-call builtins); or null if it is not callable.
     */
    private fun toCallableType(t: StarlarkType): Types.CallableType? {
        if (t is Types.CallableType) {
            return t
        }
        for (supertype in t.getSupertypes()) {
            if (supertype is Types.CallableType) {
                return supertype
            }
        }
        return null
    }

    /**
     * Returns true if the call's residual positional arguments (if any) satisfy the type checker.
     * Otherwise, reports an error and returns false.
     */
    private fun checkCallResidualPositionals(
        residualPositional: MutableList<Int?>,
        call: CallExpression,
        callable: Types.CallableType,
        argTypes: MutableList<StarlarkType?>
    ): Boolean {
        if (residualPositional.isEmpty()) {
            return true
        } else if (callable.getVarargsType() == null) {
            // callable cannot accept residual positional args
            if (callable.getNumPositionalParameters() > 0) {
                errorf(
                    call.getArguments().get(callable.getNumPositionalParameters()),
                    "'%s()' accepts no more than %d positional argument%s but got %d",
                    call.getFunction(),
                    callable.getNumPositionalParameters(),
                    plural(callable.getNumPositionalParameters()),
                    call.getNumPositionalArguments()
                )
            } else {
                errorf(
                    call.getArguments().getFirst(),
                    "'%s()' does not accept positional arguments, but got %d",
                    call.getFunction(),
                    call.getNumPositionalArguments()
                )
            }
            return false
        } else {
            // residual positional args go into callable's varargs
            for (argIndex in residualPositional) {
                val arg = call.getArguments().get(argIndex!!)
                val argType = argTypes.get(argIndex)
                if (!checkAssignable(
                        callable.getVarargsType(), argType, call, arg, "residual positional arguments"
                    )
                ) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Returns true if the call's residual named arguments (if any) satisfy the type checker.
     * Otherwise, reports an error and returns false.
     */
    private fun checkCallResidualNamed(
        residualNamed: MutableList<Int?>,
        call: CallExpression,
        callable: Types.CallableType,
        argTypes: MutableList<StarlarkType?>
    ): Boolean {
        if (residualNamed.isEmpty()) {
            return true
        } else if (callable.getKwargsType() == null) {
            // callable cannot accept residual named args
            val residualNamedArgs =
                residualNamed.stream().map<Argument?>(Function { i: Int? -> call.getArguments().get(i!!) }).collect(
                    ImmutableList.toImmutableList<Argument?>()
                )
            errorf(
                residualNamedArgs.getFirst(),
                "'%s()' got unexpected keyword argument%s: %s%s",
                call.getFunction(),
                plural(residualNamedArgs.size()),
                residualNamedArgs.stream().map<kotlin.String?>(Function { obj: Argument? -> obj!!.getName() })
                    .collect(Collectors.joining(", ")),  // If there are multiple residual named args, it's likely due to calling the wrong
                // function or misunderstanding the API, so arg spelling suggestions would not help.
                if (residualNamedArgs.size() == 1)
                    didYouMean(
                        residualNamedArgs.getFirst().getName(),
                        callable
                            .getParameterNames()
                            .subList(
                                callable.getNumPositionalOnlyParameters(),
                                callable.getParameterNames().size()
                            )
                    )
                else
                    ""
            )
            return false
        } else {
            // residual named args go into callable's kwargs
            for (argIndex in residualNamed) {
                val arg = call.getArguments().get(argIndex!!)
                val argType = argTypes.get(argIndex)
                if (!checkAssignable(
                        callable.getKwargsType(), argType, call, arg, "residual keyword arguments"
                    )
                ) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Returns true if all mandatory parameters were explicitly supplied by the call or potentially
     * supplied through *args or **kwargs. Otherwise, reports an error and returns false.
     */
    private fun checkCallMissingMandatoryArgs(
        seenMandatoryParameters: MutableList<kotlin.String?>,
        callHasVarargs: Boolean,
        callHasKwargs: Boolean,
        call: CallExpression,
        callable: Types.CallableType
    ): Boolean {
        if (seenMandatoryParameters.size() < callable.getMandatoryParameters().size()) {
            val seenMandatorySet = ImmutableSet.copyOf<kotlin.String?>(seenMandatoryParameters)
            // Identify mandatory parameters which were not seen and which cannot be possibly supplied
            // from the call's *args or **kwargs.
            // TODO: #28037 - Perhaps report an error if no element of varargsElementTypes /
            // kwargsValueTypes is assignable to a missing parameter's type.
            val missingMandatory = ArrayList<kotlin.String?>(0)
            for (i in callable.getParameterNames().indices) {
                val name = callable.getParameterNames().get(i)
                if (!callable.getMandatoryParameters().contains(name)) {
                    continue
                }
                if (!seenMandatorySet.contains(name)) {
                    if (i < callable.getNumPositionalOnlyParameters() && !callHasVarargs) {
                        missingMandatory.add(name)
                    } else if (i < callable.getNumPositionalParameters() && !callHasVarargs && !callHasKwargs) {
                        missingMandatory.add(name)
                    } else if (i >= callable.getNumPositionalParameters() && !callHasKwargs) {
                        missingMandatory.add(name)
                    }
                }
            }
            if (!missingMandatory.isEmpty()) {
                errorf(
                    call.getLparenLocation(),
                    "'%s()' missing %d required argument%s: %s",
                    call.getFunction(),
                    missingMandatory.size(),
                    plural(missingMandatory.size()),
                    Joiner.on(", ").join(missingMandatory)
                )
                return false
            }
        }
        return true
    }

    private fun inferComprehension(comp: Comprehension): StarlarkType {
        for (clause in comp.getClauses()) {
            when (clause) {
                -> {
                    checkForClause(
                        forClause.getVars(), forClause.getIterable(), "comprehension 'for' clause"
                    )
                }

                -> {
                    // Infer only to type-check. Condition is evaluated as truthy/falsy, which is valid for
                    // every type.
                    val unused = infer(ifClause.getCondition())
                }
            }
        }
        if (comp.isDict()) {
            val bodyEntry = comp.getBody() as DictExpression.Entry
            return Types.dict(infer(bodyEntry.getKey()), infer(bodyEntry.getValue()))
        } else {
            val bodyElement = comp.getBody() as Expression
            return Types.list(infer(bodyElement))
        }
    }

    /** Recursively type-checks the vars and the iterable, and assigns the vars to the iterable.  */
    private fun checkForClause(vars: Expression, iterable: Expression, what: kotlin.String?) {
        val iterableType = infer(iterable)
        val varsRhsType: StarlarkType // The type of the value assigned to the vars expression.
        if (iterableType == Types.ANY) {
            varsRhsType = Types.ANY
        } else {
            val varUnionElements = ArrayList<StarlarkType>()
            for (iterableUnionElement in Types.unfoldUnion(iterableType)) {
                // TODO: #28037 - Replace with inferring T when assigning iterableType to Collection[T]
                // TODO: #28037 - Introduce an Iterable type and use it here to match language spec.
                if (iterableUnionElement == Types.ANY) {
                    varUnionElements.add(Types.ANY)
                } else if (iterableUnionElement is Types.AbstractCollectionType) {
                    varUnionElements.add(iterableUnionElement.getElementType())
                } else {
                    errorf(iterable, "%s operand must be an iterable, got '%s'", what, iterableType)
                }
            }
            varsRhsType = Types.union(varUnionElements)
        }
        assign(vars, varsRhsType)
    }

    private fun checkAssignable(
        lhs: StarlarkType?,
        rhs: StarlarkType?,
        call: CallExpression,
        node: Node,
        nodeDescription: kotlin.String?
    ): Boolean {
        if (lhs != null && rhs != null) {
            if (!StarlarkType.Companion.assignableFrom(lhs, rhs)) {
                errorf(
                    node,
                    "in call to '%s()', %s must be '%s', not '%s'",
                    call.getFunction(),
                    nodeDescription,
                    lhs,
                    rhs
                )
                return false
            }
        }
        return true
    }

    /**
     * Recursively typechecks the assignment of type `rhsType` to the target expression `lhs`.
     * 
     * 
     * Mutates the types on the [Resolver.Binding] objects of untyped variables by setting
     * them to their inferred type (if this is the first assignment to that variable in typed code).
     * 
     * 
     * The asymmetry of the parameter types comes from the fact that this helper recursively
     * decomposes the LHS syntactically, whereas the RHS has already been fully evaluated to a type.
     * For instance, `x, y = (1, 2)` and `x, y = my_pair` both trigger the same behavior
     * in this method. Decomposing the LHS syntactically rather than by type is what allows `(x, y) = [1, 2]` to succeed, even though assignment of a list to a tuple type is illegal (as in
     * `t : Tuple[int, int] = [1, 2]`).
     */
    private fun assign(lhs: Expression, rhsType: StarlarkType) {
        Preconditions.checkState(usesTypeSyntax())

        if (lhs.kind() == Expression.Kind.LIST_EXPR) {
            assignSequence(lhs as ListExpression, rhsType)
            return
        }

        val lhsMeet = inferIndividualAssignmentTarget(lhs)
        for (lhsType in lhsMeet) {
            if (!StarlarkType.Companion.assignableFrom(lhsType, rhsType)) {
                errorf(lhs, "cannot assign type '%s' to %s", rhsType, formatExprWithMeetType(lhs, lhsMeet))
                break
            }
        }

        if (lhs is Identifier && typeTable.getType(lhs.getBinding()) == null) {
            // If a variable has not been typed, infer its type from the rhs of the first assignment.
            typeTable.setInferredType(lhs.getBinding(), rhsType.toLvalue())
        }
    }

    /**
     * Verifies that the expression can be used as the target of a non-sequence assignment (or
     * augmented assignment). Returns a non-flattened unfolded list of LHS acceptor types, each of
     * which must be checked for being assignable by the assignment's RHS type.
     * 
     * 
     * In type theory terms, the returned list represents the meet of its type elements; however,
     * meet types don't (yet) exist in the Starlark type system.
     * 
     * 
     * If the LHS is an index or dot expression whose object is of a union type, then each of the
     * possible acceptor types must be assignable. We want to distinguish between the valid case
     * `x: list[int|str]; x[0] = 1` (where there is a single LHS acceptor type, int|str) and the
     * invalid case `y: list[int] | list[str]; y[0] = 1` (which has a pair of LHS acceptor
     * types, int and str, the latter of which is not assignable from 1).
     */
    private fun inferIndividualAssignmentTarget(lhs: Expression): ImmutableList<StarlarkType> {
        when (lhs.kind()) {
            Expression.Kind.INDEX -> {
                val indexExpr = lhs as IndexExpression
                val objectType = infer(indexExpr.getObject())
                val keyType = infer(indexExpr.getKey())
                if (!objectType!!.hasSetIndex()) {
                    errorf(
                        lhs,
                        "%s of type '%s' does not support item assignment",
                        indexExpr.getObject(),
                        objectType
                    )
                }
                return inferIndexUnfolded(indexExpr, objectType, keyType)
            }

            Expression.Kind.DOT -> {
                val dotExpr = lhs as DotExpression
                val objectType = infer(dotExpr.getObject())
                if (!objectType!!.hasSetField()) {
                    errorf(
                        lhs,
                        "%s of type '%s' does not support field assignment",
                        dotExpr.getObject(),
                        objectType
                    )
                }
                return inferDotUnfolded(dotExpr, objectType)
            }

            Expression.Kind.IDENTIFIER -> {
                return ImmutableList.of<StarlarkType?>(infer(lhs))
            }

            else -> {
                val lhsType = infer(lhs)
                errorf(lhs, "%s of type '%s' is not a valid target for assignment", lhs, lhsType)
                return ImmutableList.of<StarlarkType?>(Types.ANY)
            }
        }
    }

    private fun assignSequence(lhs: ListExpression, rhsType: StarlarkType) {
        if (rhsType == Types.ANY) {
            for (element in lhs.getElements()) {
                assign(element, Types.ANY)
            }
            return
        }

        // We effectively need to transform what may be a union of iterables into a fixed-length tuple
        // of unions; e.g. list[int] | tuple[str, bool] => tuple[int | str, int | bool].
        // (Of course, any tuples in the rhsType union must be of the expected length.)
        val rhsUnionElements = Types.unfoldUnion(rhsType)
        for (rhsUnionElement in rhsUnionElements) {
            if (rhsUnionElement is Types.FixedLengthTupleType) {
                if (lhs.getElements().size() != rhsUnionElement.getElementTypes().size()) {
                    errorf(
                        lhs,
                        "cannot assign type '%s' to '%s'; want %d-element sequence",
                        rhsType,
                        lhs,
                        lhs.getElements().size()
                    )
                    return
                }
            } else if (!Types.isCollection(rhsType)) {
                // TODO: #28043 - consider checking for an Iterable type (as it is in the eval layer)
                errorf(lhs, "cannot assign non-iterable type '%s' to '%s'", rhsType, lhs)
                return
            }
        }
        for (i in lhs.getElements().indices) {
            val rhsElementTypes: ArrayList<StarlarkType> = ArrayList<StarlarkType>(rhsUnionElements.size())
            for (rhsUnionElement in rhsUnionElements) {
                if (rhsUnionElement is Types.FixedLengthTupleType) {
                    rhsElementTypes.add(rhsUnionElement.getElementTypes().get(i))
                } else if (rhsUnionElement is Types.AbstractCollectionType) {
                    rhsElementTypes.add(rhsUnionElement.getElementType())
                } else if (rhsUnionElement == Types.ANY) {
                    rhsElementTypes.add(Types.ANY)
                }
            }
            assign(lhs.getElements().get(i), Types.union(rhsElementTypes))
        }
    }

    private fun visitProgram(prog: Program) {
        Preconditions.checkState(
            functionStack.isEmpty(),
            "When type-checkings a Program, functionStack is expected to be initially empty"
        )
        val toplevel = prog.getResolvedFunction()
        this.functionStack.push(toplevel)
        visitBlock(toplevel.getBody())
        Preconditions.checkState(functionStack.pop() == toplevel)
    }

    override fun visit(file: StarlarkFile) {
        Preconditions.checkState(
            functionStack.isEmpty(),
            "When type-checkings a StarlarkFile, functionStack is expected to be initially empty"
        )
        val toplevel = file.getResolvedFunction()
        this.functionStack.push(toplevel)
        super.visit(file)
        Preconditions.checkState(functionStack.pop() == toplevel)
    }

    // Expressions should only be visited via infer(), not the visit() dispatch mechanism.
    // Override visit(Identifier) as a poison pill.
    override fun visit(id: Identifier) {
        throw AssertionError(
            String.format(
                "TypeChecker#visit should not have reached Identifier node '%s'", id.getName()
            )
        )
    }

    override fun visit(assignment: AssignmentStatement) {
        if (!usesTypeSyntax()) {
            return
        }

        if (assignment.isAugmented()) {
            val operator = assignment.getOperator()
            val operatorLocation = assignment.getOperatorLocation()
            val lhs = assignment.getLHS()
            val rhs = assignment.getRHS()
            val lhsMeet = inferIndividualAssignmentTarget(lhs)
            val rhsType = infer(assignment.getRHS())
            for (lhsType in lhsMeet) {
                // TODO(b/141263526): if we decide to support list += sequence, we'd need to special-case it
                // here (since list + tuple is an error per inferBinaryOperator()).
                val resultType =
                    inferBinaryOperator(
                        lhs,
                        lhsType,
                        operator!!,
                        operatorLocation,
                        rhs,
                        rhsType,  /* augmentedAssignment= */
                        true
                    )
                if (!StarlarkType.Companion.assignableFrom(lhsType, resultType)) {
                    binaryOperatorError(
                        lhsType,
                        operator,
                        operatorLocation,
                        rhsType,  /* augmentedAssignment= */
                        true,
                        String.format(
                            "cannot update %s with a result value of type '%s'",
                            formatExprWithMeetType(lhs, lhsMeet), resultType
                        )
                    )
                }
            }
        } else {
            val rhsType = infer(assignment.getRHS())

            assign(assignment.getLHS(), rhsType!!)
        }
    }

    override fun visit(node: ForStatement) {
        if (usesTypeSyntax()) {
            checkForClause(node.getVars(), node.getCollection(), "'for' loop")
        }
        // Visit the for loop body even in untyped code; it may contain nested typed def statements.
        visitBlock(node.getBody())
    }

    override fun visit(def: DefStatement) {
        val function = def.getResolvedFunction()
        functionStack.push(function)
        if (typeTable.usesTypeSyntax(function)) {
            val callableType =
                Preconditions.checkNotNull<Types.CallableType>(
                    typeTable.getType(function),
                    "type tagger should have set type for def statement '%s'",
                    def
                )
            val numOrdinaryParams: Int = callableType.getParameterTypes().size()
            for (i in 0..<numOrdinaryParams) {
                val param = def.getParameters().get(i)
                if (param.getDefaultValue() != null) {
                    val defaultValueType = infer(param.getDefaultValue()!!)
                    if (!StarlarkType.Companion.assignableFrom(
                            callableType.getParameterTypeByPos(i), defaultValueType
                        )
                    ) {
                        errorf(
                            param.getDefaultValue()!!.getStartLocation(),
                            "%s(): parameter '%s' has default value of type '%s', declares '%s'",
                            def.getIdentifier().getName(),
                            param.getName(),
                            defaultValueType,
                            callableType.getParameterTypeByPos(i)
                        )
                    }
                }
            }

            val implicitNoneReturn: Statement? = getImplicitNoneReturn(def.getBody())
            if (implicitNoneReturn != null
                && !StarlarkType.Companion.assignableFrom(callableType.getReturnType(), Types.NONE)
            ) {
                errorf(
                    implicitNoneReturn,
                    "%s() declares return type '%s' but may exit without an explicit 'return'",
                    def.getIdentifier().getName(),
                    callableType.getReturnType()
                )
            }
        }

        // Visit body even in untyped code; it may contain nested typed def statements.
        visitBlock(def.getBody())
        Preconditions.checkState(functionStack.poll() == function)
    }

    override fun visit(node: IfStatement) {
        if (usesTypeSyntax()) {
            // Check type constraints in the condition.
            infer(node.getCondition())
        }
        // Visit then/else blocks even in untyped code; they may contain nested typed def statements.
        visitBlock(node.getThenBlock())
        if (node.getElseBlock() != null) {
            visitBlock(node.getElseBlock())
        }
    }

    override fun visit(expr: ExpressionStatement) {
        if (!usesTypeSyntax()) {
            return
        }
        // Check constraints in the expression, but ignore the resulting type.
        // Don't dispatch to it via visit().
        infer(expr.getExpression())
    }

    // No need to override visit() for FlowStatement.
    override fun visit(load: LoadStatement?) {
        // Don't descend into children.
    }

    override fun visit(ret: ReturnStatement) {
        if (!usesTypeSyntax()) {
            return
        }
        val returnType = if (ret.getResult() == null) Types.NONE else infer(ret.getResult()!!)
        Preconditions.checkState(!functionStack.isEmpty())
        val function = functionStack.peek()
        // May be null if function is the toplevel
        val callableType = typeTable.getType(function)
        if (callableType != null
            && !StarlarkType.Companion.assignableFrom(callableType.getReturnType(), returnType)
        ) {
            errorf(
                ret.getResult()!!.getStartLocation(),
                "%s() declares return type '%s' but may return '%s'",
                function.getName(),
                callableType.getReturnType(),
                returnType
            )
        }
    }

    override fun visit(alias: TypeAliasStatement?) {
        // Don't descend into children.
    }

    override fun visit(`var`: VarStatement?) {
        // Don't descend into children.
    }

    /**
     * Returns true if the current function is considered to use type syntax, or if we were invoked
     * via [.inferTypeOf]. If false, the current node must not be type-checked.
     */
    private fun usesTypeSyntax(): Boolean {
        return functionStack.isEmpty() || typeTable.usesTypeSyntax(functionStack.peek())
    }

    companion object {
        private fun plural(n: Int): kotlin.String {
            return if (n == 1) "" else "s"
        }

        /**
         * Returns the integer value of an expression if it's an integer value which can be exactly
         * represented as a Java integer, or null otherwise (in particular, if the expression itself is
         * null).
         */
        private fun getIntValueExact(expr: Expression?): Int? {
            if (expr is IntLiteral) {
                return expr.getIntValueExact()
            }
            return null
        }

        private fun haveExactSliceBound(
            expr: Expression?, exprIntValueExact: Int?
        ): Boolean {
            if (expr == null) {
                // Bound not specified, so we know its exact value (the default value)
                return true
            }
            if (exprIntValueExact != null) {
                // Bound is specified and is a 32-bit integer literal (or negation)
                return true
            }
            return false
        }

        private fun inferTupleRepetition(tuple: Types.TupleType, timesExpr: Expression?): StarlarkType? {
            val times: Int? = getIntValueExact(timesExpr)
            if (times != null) {
                return tuple.repeat(times)
            }
            return tuple.toHomogeneous()
        }

        /**
         * Returns the inferred type of an expression.
         * 
         * 
         * The expression must have already been resolved and successfully type-tagged, i.e.
         * identifiers must have their bindings set and these bindings must contain type information.
         * 
         * @throws SyntaxError.Exception if a static type error is present in the expression.
         */
        @kotlin.jvm.JvmStatic
        @Throws(SyntaxError.Exception::class)
        fun inferTypeOf(expr: Expression, typeTable: TypeTable, typeContext: TypeContext): StarlarkType? {
            val tc = TypeChecker(typeTable, typeContext)
            val result = tc.infer(expr)
            if (!typeTable.ok()) {
                throw SyntaxError.Exception(typeTable.errors())
            }
            return result
        }

        private fun formatExprWithMeetType(expr: Expression?, types: ImmutableList<StarlarkType>): kotlin.String? {
            if (types.size() == 1) {
                return String.format("'%s' of type '%s'", expr, types.getFirst())
            } else {
                return String.format(
                    "'%s' which expects a value satisfying all of the %d types [%s]",
                    expr,
                    types.size(),
                    types.stream().map<kotlin.String?>(Function { t: StarlarkType? -> String.format("'%s'", t) })
                        .collect(Collectors.joining(", "))
                )
            }
        }

        /**
         * Heuristically checks whether a function body ends with an implicit `None` return, i.e. a
         * non-return statement, and if so, retrieves the statement after which the implicit `None`
         * return occurs. Recurses into if statement bodies.
         * 
         * 
         * This check doesn't attempt to detect unreachable code within the body, so e.g.
         * 
         * <pre>
         * def f() -> int:
         * return 1
         * pass
        </pre> * 
         * 
         * will be flagged as implicitly returning `None` on the unreachable last line.
         * 
         * @return the first statement after which the function exits and the implicit `None` return
         * occurs, or `null` if none was found
         */
        private fun getImplicitNoneReturn(body: ImmutableList<Statement?>): Statement? {
            val last: Statement? = body.getLast()
            if (last is ReturnStatement) {
                return null
            } else if (last is IfStatement) {
                // An if statement is considered to have an explicit return if it has both `then` and `else`
                // branches, and both branches end with an explicit return.
                if (last.getElseBlock() == null) {
                    return last
                }
                val thenImplicitNoneReturn: Statement? = getImplicitNoneReturn(last.getThenBlock())
                return if (thenImplicitNoneReturn != null)
                    thenImplicitNoneReturn
                else
                    Companion.getImplicitNoneReturn(last.getElseBlock()!!)
            }
            return last
        }

        private fun checkFileOptions(options: FileOptions) {
            Preconditions.checkArgument(
                options.resolveTypeSyntax(), "static type checking requires that resolveTypeSyntax is set"
            )
            Preconditions.checkArgument(
                !options.tolerateInvalidTypeExpressions(),
                "static type checking requires that tolerateInvalidTypeExpressions is not set"
            )
        }

        /**
         * Checks that the given file's AST satisfies the types in the bindings of its identifiers.
         * 
         * 
         * The file must have already been passed through the [TypeTagger] without error
         * 
         * 
         * Any type checking errors are appended to the type table's errors list.
         * 
         * @throws IllegalArgumentException if the file's [FileOptions] don't contain [     ][FileOptions.resolveTypeSyntax] or do contain [     ][FileOptions.tolerateInvalidTypeExpressions].
         */
        @kotlin.jvm.JvmStatic
        fun checkFile(file: StarlarkFile, typeTable: TypeTable, typeContext: TypeContext) {
            checkFileOptions(file.getOptions())
            val checker = TypeChecker(typeTable, typeContext)
            checker.visit(file)
        }

        /**
         * Like [.checkFile], but on an already-compiled [Program].
         * 
         * 
         * The program is *not* mutated. Any errors are appended to the type table's errors list.
         * 
         * @throws IllegalArgumentException if the program's [FileOptions] don't contain [     ][FileOptions.resolveTypeSyntax] or do contain [     ][FileOptions.tolerateInvalidTypeExpressions].
         */
        @kotlin.jvm.JvmStatic
        fun checkProgram(prog: Program, typeTable: TypeTable, typeContext: TypeContext) {
            checkFileOptions(prog.getOptions())
            val checker = TypeChecker(typeTable, typeContext)
            checker.visitProgram(prog)
        }
    }
}
