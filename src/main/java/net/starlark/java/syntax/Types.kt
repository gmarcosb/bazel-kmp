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

import com.google.auto.value.AutoValue
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableCollection
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import java.lang.String
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Predicate
import java.util.stream.Collectors
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.toString

/**
 * Definitions of types.
 * 
 * 
 * `
 * t1, t2 ::= None | bool | int | float | str | object
 * | t1|t2 | list[t1]
` * 
 */
object Types {
    // TODO(ilist@): constructed types should probably be interned. In some cases it might help
    // to precompute and memoize StarlarkTypes.getSupertypes.
    /**
     * The Dynamic type of gradual typing; compatible with any other type, but not related by
     * subtyping to any other type.
     */
    @kotlin.jvm.JvmField
    val ANY: StarlarkType = AnyType()

    /** The top type of the type hierarchy.  */
    @kotlin.jvm.JvmField
    val OBJECT: StarlarkType = ObjectType()

    /** The bottom type of the type hierarchy.  */
    @kotlin.jvm.JvmField
    val NEVER: StarlarkType = NeverType()

    // Primitive types
    @kotlin.jvm.JvmField
    val NONE: StarlarkType = NoneType()

    @kotlin.jvm.JvmField
    val BOOL: StarlarkType = BoolType()
    @kotlin.jvm.JvmField
    val INT: StarlarkType = IntType()
    @kotlin.jvm.JvmField
    val FLOAT: StarlarkType = FloatType()
    @kotlin.jvm.JvmField
    val STR: StarlarkType = StrType()

    // A frequently-used union `int | float`.
    @kotlin.jvm.JvmField
    val NUMERIC: UnionType = union(INT, FLOAT) as UnionType

    // A frequently-used empty tuple type.
    @kotlin.jvm.JvmField
    val EMPTY_TUPLE: FixedLengthTupleType = tuple(ImmutableList.of<StarlarkType?>())

    // A frequently-used arbitrary collection.
    val COLLECTION_OF_ANY: CollectionType = collection(ANY)

    // A frequently-used arbitrary struct
    @kotlin.jvm.JvmField
    val STRUCT_OF_ANY: StructType = partialStruct(ImmutableMap.of<String?, StarlarkType?>())

    // A frequently used function without parameters, that returns Any.
    val NO_PARAMS_CALLABLE: CallableType = callable(
        ImmutableList.of<String?>(),
        ImmutableList.of<StarlarkType?>(),
        0,
        0,
        ImmutableSet.of<String?>(),
        null,
        null,
        ANY
    )

    val ANY_CONSTRUCTOR: TypeConstructor = wrapType("Any", ANY)
    val OBJECT_CONSTRUCTOR: TypeConstructor = wrapType("object", OBJECT)
    val NONE_CONSTRUCTOR: TypeConstructor = wrapType("None", NONE)
    val BOOL_CONSTRUCTOR: TypeConstructor = wrapType("bool", BOOL)
    val INT_CONSTRUCTOR: TypeConstructor = wrapType("int", INT)
    val FLOAT_CONSTRUCTOR: TypeConstructor = wrapType("float", FLOAT)
    val STR_CONSTRUCTOR: TypeConstructor = wrapType("str", STR)
    val LIST_CONSTRUCTOR: TypeConstructor = wrapTypeConstructor("list", Function { obj: StarlarkType? -> Types.list() })
    val DICT_CONSTRUCTOR: TypeConstructor =
        wrapTypeConstructor("dict", BiFunction { obj: StarlarkType?, keyType: StarlarkType? -> Types.dict(keyType) })
    val SET_CONSTRUCTOR: TypeConstructor = wrapTypeConstructor("set", Function { obj: StarlarkType? -> Types.set() })
    val TUPLE_CONSTRUCTOR: TypeConstructor = wrapTupleConstructor()
    @kotlin.jvm.JvmField
    val COLLECTION_CONSTRUCTOR: TypeConstructor =
        wrapTypeConstructor("Collection", Function { obj: StarlarkType? -> Types.collection() })
    @kotlin.jvm.JvmField
    val SEQUENCE_CONSTRUCTOR: TypeConstructor =
        wrapTypeConstructor("Sequence", Function { obj: StarlarkType? -> Types.sequence() })
    @kotlin.jvm.JvmField
    val MAPPING_CONSTRUCTOR: TypeConstructor = wrapTypeConstructor(
        "Mapping",
        BiFunction { obj: StarlarkType?, keyType: StarlarkType? -> Types.mapping(keyType) })
    @kotlin.jvm.JvmField
    val STRUCT_CONSTRUCTOR: TypeConstructor = wrapStructConstructor()

    @kotlin.jvm.JvmField
    val TYPE_UNIVERSE: ImmutableMap<String?, TypeConstructor?> = makeTypeUniverse()

    // Note that STRUCT_CONSTRUCTOR is not in the type universe; applications are responsible for
    // adding it if needed.
    private fun makeTypeUniverse(): ImmutableMap<String?, TypeConstructor?> {
        val env = ImmutableMap.builder<String?, TypeConstructor?>()
        env //
            .put("Any", ANY_CONSTRUCTOR)
            .put("object", OBJECT_CONSTRUCTOR)
            .put("None", NONE_CONSTRUCTOR)
            .put("bool", BOOL_CONSTRUCTOR)
            .put("int", INT_CONSTRUCTOR)
            .put("float", FLOAT_CONSTRUCTOR)
            .put("str", STR_CONSTRUCTOR)
            .put("list", LIST_CONSTRUCTOR)
            .put("dict", DICT_CONSTRUCTOR)
            .put("set", SET_CONSTRUCTOR)
            .put("tuple", TUPLE_CONSTRUCTOR)
            .put("Collection", COLLECTION_CONSTRUCTOR)
            .put("Sequence", SEQUENCE_CONSTRUCTOR)
            .put("Mapping", MAPPING_CONSTRUCTOR)
        return env.buildOrThrow()
    }

    /** Construct a CallableType representing a Starlark Function  */
    fun callable(
        parameterNames: ImmutableList<String?>,
        parameterTypes: ImmutableList<StarlarkType?>,
        numPositionalOnlyParameters: Int,
        numPositionalParameters: Int,
        mandatoryParams: ImmutableSet<String?>?,
        varargsType: StarlarkType?,
        kwargsType: StarlarkType?,
        returns: StarlarkType?
    ): CallableType {
        Preconditions.checkArgument(
            parameterNames.size() == parameterTypes.size(),
            "%s != %s",
            parameterNames.size(),
            parameterTypes.size()
        )
        return AutoValue_Types_GeneralCallableType(
            parameterNames,
            parameterTypes,
            numPositionalOnlyParameters,
            numPositionalParameters,
            mandatoryParams,
            varargsType,
            kwargsType,
            returns
        )
    }

    /**
     * Constructs a union type.
     * 
     * 
     * If the types set contains another Union type it's flattened. Duplicates are removed.
     * Occurrences of Never are removed.
     * 
     * 
     * If types set contains Object type it's simplified to Object type. If the set contains a
     * single element, it is returned instead of constructing a union. And if the set is empty, Never
     * is returned.
     */
    @kotlin.jvm.JvmStatic
    fun union(vararg types: StarlarkType?): StarlarkType {
        return union(ImmutableSet.copyOf<StarlarkType?>(types))
    }

    /** Constructs a union type.  */ // TODO: #28043 - Seems more appropriate to use List<StarlarkType> for the param and let this
    // factory method take care of deduplication. For the moment we have a convenience overload below.
    fun union(types: ImmutableSet<StarlarkType>): StarlarkType {
        val subtypesBuilder = ImmutableSet.builder<StarlarkType?>()
        // Unions are flattened
        for (type in types) {
            if (type is UnionType) {
                subtypesBuilder.addAll(type.getTypes())
            } else if (type != NEVER) {
                subtypesBuilder.add(type)
            }
        }
        val subtypes: ImmutableSet<StarlarkType> = subtypesBuilder.build()
        if (subtypes.contains(OBJECT)) {
            return OBJECT
        }
        if (subtypes.size() == 1) {
            return subtypes.iterator().next()
        } else if (subtypes.isEmpty()) {
            return NEVER
        }
        return AutoValue_Types_UnionType(subtypes)
    }

    fun union(types: MutableList<StarlarkType?>): StarlarkType? {
        if (types.size() == 1) {
            // Optimize the common case.
            return types.getFirst()
        }
        return union(ImmutableSet.copyOf<StarlarkType?>(types))
    }

    /** Returns the list of a union's types, or a singleton list if `type` is not a union.  */
    fun unfoldUnion(type: StarlarkType): ImmutableCollection<StarlarkType>? {
        if (type is UnionType) {
            return type.getTypes()
        }
        return ImmutableList.of<StarlarkType?>(type)
    }

    @kotlin.jvm.JvmStatic
    fun list(elementType: StarlarkType?): ListType {
        return AutoValue_Types_ListType(elementType)
    }

    /**
     * Constructs a list rvalue type. Only for literals and anonymous temporary values.
     * 
     * 
     * Like all rvalue types, this type MUST NOT be used as or in the type of any variable or
     * parameter; and it MUST NOT be inferred as or in a type parameter of a generic function.
     */
    @kotlin.jvm.JvmStatic
    fun listRvalue(elementType: StarlarkType?): ListRvalueType {
        return AutoValue_Types_ListRvalueType(elementType)
    }

    @kotlin.jvm.JvmStatic
    fun dict(keyType: StarlarkType?, valueType: StarlarkType?): DictType {
        return AutoValue_Types_DictType(keyType, valueType)
    }

    /**
     * Constructs a dict rvalue type. Only for literals and anonymous temporary values.
     * 
     * 
     * Like all rvalue types, this type MUST NOT be used as or in the type of any variable or
     * parameter; and it MUST NOT be inferred as or in a type parameter of a generic function.
     */
    @kotlin.jvm.JvmStatic
    fun dictRvalue(keyType: StarlarkType?, valueType: StarlarkType?): DictRvalueType {
        return AutoValue_Types_DictRvalueType(keyType, valueType)
    }

    @kotlin.jvm.JvmStatic
    fun set(elementType: StarlarkType?): SetType {
        return AutoValue_Types_SetType(elementType)
    }

    fun tuple(elementTypes: ImmutableList<StarlarkType?>?): FixedLengthTupleType {
        return AutoValue_Types_FixedLengthTupleType(elementTypes)
    }

    @kotlin.jvm.JvmStatic
    fun tuple(first: StarlarkType, vararg rest: StarlarkType?): FixedLengthTupleType {
        return tuple(ImmutableList.builder<StarlarkType?>().add(first).add(*rest).build())
    }

    @kotlin.jvm.JvmStatic
    fun homogeneousTuple(elementType: StarlarkType?): HomogeneousTupleType {
        return AutoValue_Types_HomogeneousTupleType(elementType)
    }

    /** Collection type  */
    @kotlin.jvm.JvmStatic
    fun collection(elementType: StarlarkType?): CollectionType {
        return AutoValue_Types_CollectionType(elementType)
    }

    /** Returns true if `type` may be used as a collection.  */
    fun isCollection(type: StarlarkType?): Boolean {
        return StarlarkType.Companion.assignableFrom(COLLECTION_OF_ANY, type)
    }

    /** Sequence type  */
    @kotlin.jvm.JvmStatic
    fun sequence(elementType: StarlarkType?): SequenceType {
        return AutoValue_Types_SequenceType(elementType)
    }

    /** Mapping type  */
    @kotlin.jvm.JvmStatic
    fun mapping(keyType: StarlarkType?, valueType: StarlarkType?): MappingType {
        return AutoValue_Types_MappingType(keyType, valueType)
    }

    /** Immutable partial struct type  */
    fun partialStruct(fields: ImmutableMap<String?, StarlarkType?>?): StructType {
        return AutoValue_Types_StructType(fields,  /* partial= */true)
    }

    /** Immutable total struct type  */
    fun struct(fields: ImmutableMap<String?, StarlarkType?>?): StructType {
        return AutoValue_Types_StructType(fields,  /* partial= */false)
    }

    fun wrapType(name: String?, type: StarlarkType): TypeConstructor {
        return TypeConstructor { argsTuple: ImmutableList<TypeConstructor.Arg>? ->
            if (!argsTuple!!.isEmpty()) {
                throw TypeConstructor.Failure(String.format("'%s' does not accept arguments", name))
            }
            type
        }
    }

    @Throws(TypeConstructor.Failure::class)
    private fun toStarlarkTypes(
        name: kotlin.String?, args: ImmutableList<TypeConstructor.Arg>
    ): ImmutableList<StarlarkType?> {
        for (arg in args) {
            if (arg !is StarlarkType) {
                throw TypeConstructor.Failure(
                    String.format("in application to %s, got '%s', expected a type", name, arg)
                )
            }
        }
        val result// list is immutable and all elements verified above
                = args as ImmutableList<*> as ImmutableList<StarlarkType?>
        return result
    }

    /**
     * Returns a new type constructor wrapping the given one-argument type factory.
     * 
     * 
     * The type constructor can be invoked with one argument, which is passed to the underlying
     * factory, or with zero arguments, in which case the factory is invoked with [.ANY]. (This
     * allows, for instance, `list` to be treated as syntactic sugar for `list[Any]`.)
     */
    fun wrapTypeConstructor(
        name: kotlin.String?, factory: Function<StarlarkType?, StarlarkType?>
    ): TypeConstructor {
        return TypeConstructor { args: ImmutableList<TypeConstructor.Arg>? ->
            val types = Types.toStarlarkTypes(name, args!!)
            when (types.size()) {
                0 -> factory.apply(ANY)
                1 -> factory.apply(types.get(0))
                else -> {
                    throw TypeConstructor.Failure(
                        String.format("%s[] accepts exactly 1 argument but got %d", name, types.size())
                    )
                }
            }
        }
    }

    /**
     * Returns a new type constructor wrapping the given two-argument type factory.
     * 
     * 
     * The type constructor can be invoked with two arguments, which are passed to the underlying
     * factory, or with zero arguments, in which case the factory is invoked with [.ANY] for
     * both arguments. (This allows, for instance, `dict` to be treated as syntactic sugar for
     * `dict[Any, Any]`.)
     */
    fun wrapTypeConstructor(
        name: kotlin.String?, factory: BiFunction<StarlarkType?, StarlarkType?, StarlarkType?>
    ): TypeConstructor {
        return TypeConstructor { args: ImmutableList<TypeConstructor.Arg>? ->
            val types = Types.toStarlarkTypes(name, args!!)
            when (types.size()) {
                0 -> factory.apply(ANY, ANY)
                2 -> factory.apply(types.get(0), types.get(1))
                else -> throw TypeConstructor.Failure(
                    String.format("%s[] accepts exactly 2 arguments but got %d", name, types.size())
                )
            }
        }
    }

    private fun wrapTupleConstructor(): TypeConstructor {
        // This is a function instead of a constant, so that the order of evaluation doesn't depend on
        // the position in the class.
        return TypeConstructor { args: ImmutableList<TypeConstructor.Arg>? ->
            if (args!!.isEmpty()) {
                // `tuple` is equivalent to `tuple[Any, ...]`
                return@TypeConstructor homogeneousTuple(ANY)
            }
            for (i in args.indices) {
                val arg = args.get(i)
                if (arg == TypeConstructor.Arg.Companion.ELLIPSIS) {
                    if (i == 1 && args.size() == 2) {
                        return@TypeConstructor homogeneousTuple(args.getFirst() as StarlarkType?)
                    }
                    throw TypeConstructor.Failure(
                        "in application to tuple, '...' can only appear as the second of exactly 2 arguments,"
                                + " where the first argument is a type"
                    )
                } else if (arg == TypeConstructor.Arg.Companion.EMPTY_TUPLE) {
                    if (args.size() == 1) {
                        return@TypeConstructor EMPTY_TUPLE
                    }
                    throw TypeConstructor.Failure(
                        "in application to tuple, '()' can only appear if it is the only argument"
                    )
                } else if (arg !is StarlarkType) {
                    throw TypeConstructor.Failure(
                        String.format("in application to tuple, got '%s', expected a type", arg)
                    )
                }
            }
            val result// list is immutable and all elements verified above
                    = args as ImmutableList<*> as ImmutableList<StarlarkType?>
            tuple(result)
        }
    }

    private fun wrapStructConstructor(): TypeConstructor {
        return TypeConstructor { args: ImmutableList<TypeConstructor.Arg>? ->
            if (args!!.isEmpty()) {
                // `struct` is equivalent to `struct[{}, ...]`
                return@TypeConstructor STRUCT_OF_ANY
            } else if (args.size() <= 2) {
                val arg: TypeConstructor.Arg? = args.getFirst()
                val fields: ImmutableMap<kotlin.String?, StarlarkType?>?
                if (arg is TypeConstructor.Arg.TypeDict) {
                    fields = arg.getTypes()
                } else {
                    throw TypeConstructor.Failure(
                        String.format("in application to struct, got '%s', expected a dict", arg)
                    )
                }
                if (args.size() == 1) {
                    return@TypeConstructor struct(fields)
                } else {
                    if (args.get(1) !is TypeConstructor.Arg.Ellipsis) {
                        throw TypeConstructor.Failure(
                            String.format(
                                "in application to struct, got '%s' for optional argument #2, expected '...'",
                                args.get(1)
                            )
                        )
                    }
                    return@TypeConstructor partialStruct(fields)
                }
            } else {
                throw TypeConstructor.Failure(
                    String.format("struct[] accepts at most 2 arguments but got %d", args.size())
                )
            }
        }
    }

    // hashCode and equals implementation is a workaround for serialization code that may duplicate
    // otherwise singletons
    private class AnyType  // Singleton.
        : StarlarkType() {
        override fun toString(): kotlin.String {
            return "Any"
        }

        override fun hashCode(): Int {
            return AnyType::class.java.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            return obj is AnyType
        }

        override fun getField(name: kotlin.String?, context: TypeContext?): StarlarkType {
            return ANY
        }

        // TODO: #27370 - we may want to infer a more precise type when one of the operands is non-Any.
        // (For example, we could infer that int % Any is int | float; on the other hand, Any % int
        // could also be a string, since % is also a string substitution operator.) Requires a registry
        // of which types (including those of application-defined net.starlark.java.eval.HasBinary
        // values) support which binary operators. This would also imply that the inferred type of
        // `Any <op> T` could be application-dependent even if T is a universal built-in type.
        override fun inferBinaryOperator(operator: TokenKind, that: StarlarkType?, thisLeft: Boolean): StarlarkType? {
            return when (operator) {
                TokenKind.IN, TokenKind.NOT_IN ->  // If we are the LHS, fall through to RHS's inferBinaryOperator; RHS determines whether
                    // it is membership-testable.
                    // If we are the RHS, act as a membership-testable type that allows any LHS (e.g. list)
                    // and return bool.
                    if (thisLeft) null else BOOL

                else -> ANY
            }
        }

        override fun isComparable(that: StarlarkType): Boolean {
            // Instead of enumerating all comparable types here, allow StarlarkType#comparable to defer to
            // that.isComparable(ANY).
            return that == ANY
        }

        override fun hasSetIndex(): Boolean {
            return true
        }

        override fun hasSetField(): Boolean {
            return true
        }
    }

    private class ObjectType  // Singleton.
        : StarlarkType() {
        override fun toString(): kotlin.String {
            return "object"
        }

        override fun hashCode(): Int {
            return ObjectType::class.java.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            return obj is ObjectType
        }
    }

    private class NeverType  // Singleton.
        : StarlarkType() {
        override fun toString(): kotlin.String {
            return "Never"
        }

        override fun hashCode(): Int {
            return NeverType::class.java.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            return obj is NeverType
        }

        override fun isComparable(that: StarlarkType?): Boolean {
            // Regard Never - as the bottom type - to be comparable to anything; in particular, this
            // allows empty lists (i.e. list[Never]) to be comparable to arbitrary non-empty lists.
            return true
        }

        override fun hasSetIndex(): Boolean {
            return true
        }

        override fun hasSetField(): Boolean {
            return true
        }
    }

    private class NoneType  // Singleton.
        : StarlarkType() {
        override fun toString(): kotlin.String {
            return "None"
        }

        override fun hashCode(): Int {
            return NoneType::class.java.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            return obj is NoneType
        }
    }

    private class BoolType  // Singleton.
        : StarlarkType() {
        override fun toString(): kotlin.String {
            return "bool"
        }

        override fun hashCode(): Int {
            return BoolType::class.java.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            return obj is BoolType
        }

        override fun isComparable(that: StarlarkType?): Boolean {
            return StarlarkType.Companion.assignableFrom(BOOL, that)
        }
    }

    private class IntType  // Singleton.
        : StarlarkType() {
        override fun toString(): kotlin.String {
            return "int"
        }

        override fun hashCode(): Int {
            return IntType::class.java.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            return obj is IntType
        }

        override fun inferBinaryOperator(operator: TokenKind, that: StarlarkType, thisLeft: Boolean): StarlarkType? {
            return when (operator) {
                TokenKind.PLUS, TokenKind.MINUS, TokenKind.PERCENT, TokenKind.SLASH_SLASH -> if (NUMERIC.getTypes()!!
                        .contains(that)
                ) that else null

                TokenKind.SLASH -> if (NUMERIC.getTypes()!!.contains(that)) FLOAT else null
                TokenKind.STAR ->  // Repetition operator (int * str, int * list, etc.) is assumed to be symmetric and
                    // implemented by the rhs, so defer to rhs for non-numeric case.
                    if (NUMERIC.getTypes()!!.contains(that)) that else null

                TokenKind.AMPERSAND, TokenKind.CARET, TokenKind.GREATER_GREATER, TokenKind.LESS_LESS, TokenKind.PIPE -> if (that == INT) INT else null
                else -> null
            }
        }

        override fun isComparable(that: StarlarkType?): Boolean {
            return StarlarkType.Companion.assignableFrom(NUMERIC, that)
        }
    }

    private class FloatType  // Singleton.
        : StarlarkType() {
        override fun toString(): kotlin.String {
            return "float"
        }

        override fun hashCode(): Int {
            return FloatType::class.java.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            return obj is FloatType
        }

        override fun inferBinaryOperator(operator: TokenKind, that: StarlarkType?, thisLeft: Boolean): StarlarkType? {
            return when (operator) {
                TokenKind.PLUS, TokenKind.MINUS, TokenKind.PERCENT, TokenKind.SLASH, TokenKind.SLASH_SLASH, TokenKind.STAR -> if (NUMERIC.getTypes()!!
                        .contains(that)
                ) FLOAT else null

                else -> null
            }
        }

        override fun isComparable(that: StarlarkType?): Boolean {
            return StarlarkType.Companion.assignableFrom(NUMERIC, that)
        }
    }

    private class StrType  // Singleton.
        : StarlarkType() {
        override fun toString(): kotlin.String {
            return "str"
        }

        override fun hashCode(): Int {
            return StrType::class.java.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            return obj is StrType
        }

        override fun inferBinaryOperator(operator: TokenKind, that: StarlarkType, thisLeft: Boolean): StarlarkType? {
            return when (operator) {
                TokenKind.PLUS -> if (that == STR) STR else null
                TokenKind.PERCENT ->  // String substitution allows anything on the RHS
                    if (thisLeft) STR else null

                TokenKind.STAR -> if (that == INT) STR else null
                TokenKind.IN, TokenKind.NOT_IN ->  // If we are LHS, defer to the RHS.
                    // If we are RHS, explicitly handle Any since AnyType.inferBinaryOperator defers to us.
                    if (!thisLeft && (that == STR || that == ANY)) BOOL else null

                else -> null
            }
        }

        override fun getField(name: kotlin.String?, context: TypeContext): StarlarkType? {
            return context.getStrFieldType(name)
        }

        override fun isComparable(that: StarlarkType): Boolean {
            return that == STR || that == ANY
        }
    }

    /**
     * An interface for the general Starlark callable.
     * 
     * 
     * There are 3 flavours of parameters:
     * 
     * 
     *  * positional-only (can't be passed with a keyword),
     *  * ordinary (can be passed by position or with a keyword) and
     *  * keyword-only parameters.
     * 
     * 
     * The interface describes them as follows:
     * 
     * 
     *  * Their types are stored consecutively in `parameterTypes`.
     *  * The list `parameterNames` matches `parameterTypes`. (Even
     * positional-only parameters have names.)
     *  * `numPositionalOnlyParameters` counts positional-only arguments.
     *  * `numPositionalParameters` counts both positional-only and ordinary arguments.
     * 
     * 
     * 
     * Special parameters `*args` and `**kwargs` are stored separately. If they are
     * absent, they are set to `null`.
     * 
     * 
     * Mandatory parameters (non-special parameters without default values) are stored as an
     * ordered set.
     * 
     * 
     * The return type is marked as Any if not annotated.
     */
    abstract class CallableType : StarlarkType() {
        abstract fun getParameterNames(): ImmutableList<kotlin.String>?

        abstract fun getParameterTypes(): ImmutableList<StarlarkType>?

        abstract fun getNumPositionalOnlyParameters(): Int

        abstract fun getNumPositionalParameters(): Int

        abstract fun getMandatoryParameters(): ImmutableSet<kotlin.String?>?

        abstract fun getVarargsType(): StarlarkType?

        abstract fun getKwargsType(): StarlarkType?

        abstract fun getReturnType(): StarlarkType?

        fun getParameterTypeByPos(i: Int): StarlarkType {
            return getParameterTypes()!!.get(i)
        }

        override fun toString(): kotlin.String {
            // Approximate representation of the type - as much as Callable can do
            return ("Callable[["
                    + getParameterTypes()!!.stream()
                .map<kotlin.String?>(Function { obj: StarlarkType? -> obj.toString() })
                .collect(Collectors.joining(", "))
                    + "], "
                    + getReturnType()
                    + "]")
        }

        /** Returns a complete string representation of the type  */
        fun toSignatureString(): kotlin.String {
            val params = ImmutableList.builder<kotlin.String?>()

            // positional parameters
            var i = 0
            while (i < getNumPositionalOnlyParameters()) {
                val name = getParameterNames()!!.get(i)
                val type = getParameterTypeByPos(i)
                if (getMandatoryParameters()!!.contains(name)) {
                    params.add(type.toString())
                } else {
                    params.add("[" + type + "]")
                }
                i++
            }

            if (i > 0) { // if there were positional-only parameters, we need to separate them
                params.add("/")
            }

            while (i < getNumPositionalParameters()) {
                val name = getParameterNames()!!.get(i)
                val type = getParameterTypeByPos(i)
                if (getMandatoryParameters()!!.contains(name)) {
                    params.add(name + ": " + type)
                } else {
                    params.add(name + ": [" + type + "]")
                }
                i++
            }

            if (getVarargsType() != null) {
                params.add("*args: " + getVarargsType())
            } else if (i < getParameterTypes().size()) { // if there are going to be kwonly params
                params.add("*")
            }

            // keyword parameters
            while (i < getParameterTypes().size()) {
                val name = getParameterNames()!!.get(i)
                val type: kotlin.String? = getParameterTypeByPos(i).toString()
                if (getMandatoryParameters()!!.contains(name)) {
                    params.add(name + ": " + type)
                } else {
                    params.add(name + ": [" + type + "]")
                }
                i++
            }

            if (getKwargsType() != null) {
                params.add("**kwargs: " + getKwargsType())
            }

            val paramList = params.build()
            return "(" + String.join(", ", paramList) + ") -> " + getReturnType()
        }
    }

    // About 0.1% memory regression may be removed by specializing GeneralCallableType for function
    // without positional-only parameter and by retrieving parameter names from StarlarkFunction
    @AutoValue
    internal abstract class GeneralCallableType : CallableType()

    /**
     * Union type
     * 
     * 
     * Unions must contain at least two types, none of which may be Never or Object. See [ ][Types.union].
     */
    @AutoValue
    abstract class UnionType : StarlarkType() {
        abstract fun getTypes(): ImmutableSet<StarlarkType>?

        override fun toString(): kotlin.String {
            return getTypes()!!.stream().map<kotlin.String?>(Function { obj: StarlarkType? -> obj.toString() })
                .collect(Collectors.joining("|"))
        }

        override fun toLvalue(): StarlarkType {
            return union(
                getTypes()!!.stream().map<StarlarkType?>(Function { obj: StarlarkType? -> obj!!.toLvalue() }).collect(
                    ImmutableSet.toImmutableSet<StarlarkType?>()
                )
            )
        }

        override fun isComparable(that: StarlarkType?): Boolean {
            return getTypes()!!.stream()
                .allMatch(Predicate { type: StarlarkType? -> StarlarkType.Companion.comparable(type, that) })
        }

        override fun getField(name: kotlin.String?, context: TypeContext?): StarlarkType? {
            val resultTypes: ArrayList<StarlarkType?> = ArrayList<StarlarkType?>(getTypes().size())
            for (type in getTypes()!!) {
                val result = type.getField(name, context)
                if (result == null) {
                    return null
                }
                resultTypes.add(result)
            }
            return union(resultTypes)
        }

        override fun hasSetIndex(): Boolean {
            return getTypes()!!.stream().allMatch(Predicate { obj: StarlarkType? -> obj!!.hasSetIndex() })
        }

        override fun hasSetField(): Boolean {
            return getTypes()!!.stream().allMatch(Predicate { obj: StarlarkType? -> obj!!.hasSetField() })
        }
    }

    /** List type  */
    abstract class BaseListType : AbstractSequenceType() {
        override fun toString(): kotlin.String {
            return "list[" + getElementType() + "]"
        }

        override fun toLvalue(): ListType {
            return list(getElementType()!!.toLvalue())
        }

        override fun inferBinaryOperator(operator: TokenKind, that: StarlarkType, thisLeft: Boolean): StarlarkType? {
            return when (operator) {
                TokenKind.PLUS -> if (that is BaseListType)
                    listRvalue(union(getElementType(), that.getElementType()))
                else
                    null

                TokenKind.STAR -> if (that == INT) this.toRvalue() else null
                else -> super.inferBinaryOperator(operator, that, thisLeft)
            }
        }

        override fun getField(name: kotlin.String?, context: TypeContext): StarlarkType? {
            return context.getListFieldType(name)
        }

        override fun isComparable(that: StarlarkType): Boolean {
            if (that == ANY) {
                return true
            } else if (that is BaseListType) {
                return StarlarkType.Companion.comparable(getElementType(), that.getElementType())
            }
            return false
        }

        override fun hasSetIndex(): Boolean {
            return true
        }
    }

    /**
     * The type of a new, unaliased list value; for example, a list literal or the result of a binary
     * operator which has not yet been assigned.
     */
    @AutoValue
    abstract class ListRvalueType : BaseListType() {
        override fun getSupertypes(): MutableList<StarlarkType?> {
            return ImmutableList.of<StarlarkType?>(
                list(getElementType()), sequence(getElementType()), collection(getElementType())
            )
        }

        override fun toRvalue(): ListRvalueType {
            return this
        }

        protected override fun isRvalueAssignableTo(that: AbstractCollectionType?): Boolean {
            // Covariant in element type. Assignable only to types having a constructor which is a
            // constructor of one of this type's supertypes (in particular: not assignable to dicts,
            // sets, or application-defined types).
            // TODO: #27370 - when we have type deconstruction, replace `instanceof` checks below with
            // deconstruction of getSupertypes().
            return (that is BaseListType
                    || that is SequenceType
                    || that is CollectionType)
                    && StarlarkType.Companion.assignableFrom(that.getElementType(), this.getElementType())
        }
    }

    /**
     * The type of a potentially aliased list value; for example, the value of a variable, or nested
     * in a variable's compound value.
     */
    @AutoValue
    abstract class ListType : BaseListType() {
        override fun getSupertypes(): MutableList<StarlarkType?> {
            return ImmutableList.of<StarlarkType?>(sequence(getElementType()), collection(getElementType()))
        }

        override fun toRvalue(): ListRvalueType {
            return listRvalue(getElementType())
        }
    }

    /** Dict type  */
    abstract class BaseDictType : AbstractMappingType() {
        abstract override fun getKeyType(): StarlarkType?

        abstract override fun getValueType(): StarlarkType?

        override fun toString(): kotlin.String {
            return "dict[" + getKeyType() + ", " + getValueType() + "]"
        }

        override fun toLvalue(): DictType {
            return dict(getKeyType()!!.toLvalue(), getValueType()!!.toLvalue())
        }

        override fun getField(name: kotlin.String?, context: TypeContext): StarlarkType? {
            return context.getDictFieldType(name)
        }

        override fun hasSetIndex(): Boolean {
            return true
        }
    }

    /**
     * The type of a new, unaliased dict value; for example, a dict literal or the result of a binary
     * operator which has not yet been assigned.
     */
    @AutoValue
    abstract class DictRvalueType : BaseDictType() {
        override fun getSupertypes(): MutableList<StarlarkType?> {
            return ImmutableList.of<StarlarkType?>(
                dict(getKeyType(), getValueType()),
                mapping(getKeyType(), getValueType()),
                collection(getKeyType())
            )
        }

        override fun toRvalue(): DictRvalueType {
            return this
        }

        protected override fun isMappingRvalueAssignableTo(that: AbstractMappingType?): Boolean {
            // Covariant in both key and value types. This differs from Mapping, which is covariant only
            // in the value type, because we need to be able to assign e.g. an empty dict having Never key
            // type. Mapping avoids covariance in keys in order to catch type errors at lookups, but
            // that's not a concern for rvalue dicts since this method is only checked upon promotion to
            // lvalues, not at indexing expressions.
            // Assignable only to types having a constructor which is a constructor of one of this type's
            // supertypes (in particular: not assignable to sequences, sets, or application-defined
            // types).
            // TODO: #27370 - when we have type deconstruction, replace `instanceof` checks below with
            // deconstruction of getSupertypes().
            return (that is BaseDictType || that is MappingType)
                    && StarlarkType.Companion.assignableFrom(that.getKeyType(), getKeyType())
                    && StarlarkType.Companion.assignableFrom(that.getValueType(), getValueType())
        }
    }

    /**
     * The type of a potentially aliased dict value; for example, the value of a variable, or nested
     * in a variable's compound value.
     */
    @AutoValue
    abstract class DictType : BaseDictType() {
        override fun getSupertypes(): MutableList<StarlarkType?> {
            return ImmutableList.of<StarlarkType?>(mapping(getKeyType(), getValueType()), collection(getKeyType()))
        }

        override fun toRvalue(): DictRvalueType {
            return dictRvalue(getKeyType(), getValueType())
        }
    }

    /** Set type  */ // TODO: #27370 - add Rvalue version (same as for ListType and DictType) and have the {@code
    // set()} built-in function return an rvalue. To be useful, this would first require generics
    // support for StarlarkMethod.
    @AutoValue
    abstract class SetType : AbstractCollectionType() {
        abstract override fun getElementType(): StarlarkType?

        override fun getSupertypes(): MutableList<StarlarkType?> {
            return ImmutableList.of<StarlarkType?>(collection(getElementType()))
        }

        override fun toString(): kotlin.String {
            return "set[" + getElementType() + "]"
        }

        override fun getField(name: kotlin.String?, context: TypeContext): StarlarkType? {
            return context.getSetFieldType(name)
        }

        override fun inferBinaryOperator(operator: TokenKind, that: StarlarkType?, thisLeft: Boolean): StarlarkType? {
            return when (operator) {
                TokenKind.AMPERSAND, TokenKind.MINUS ->  // TODO: #27370 - we may want to tighten the type of a set intersection, but it's
                    // non-trivial.
                    if (that is SetType) this else null

                TokenKind.CARET, TokenKind.PIPE -> if (that is SetType)
                    set(union(getElementType(), that.getElementType()))
                else
                    null

                else -> super.inferBinaryOperator(operator, that, thisLeft)
            }
        }

        override fun toLvalue(): SetType {
            return set(getElementType()!!.toLvalue())
        }
    }

    /** Tuple type.  */
    abstract class TupleType : AbstractSequenceType() {
        /** Returns the type of this tuple concatenated with another.  */
        abstract fun concatenate(rhs: TupleType?): TupleType?

        /** Returns the type of this tuple repeated.  */
        abstract fun repeat(times: Int): TupleType?

        /** Returns the homogeneous version of this tuple type.  */
        abstract fun toHomogeneous(): HomogeneousTupleType?

        override fun inferBinaryOperator(operator: TokenKind, that: StarlarkType?, thisLeft: Boolean): StarlarkType? {
            return when (operator) {
                TokenKind.PLUS -> if (that is TupleType) concatenate(that) else null
                TokenKind.STAR -> null
                else -> super.inferBinaryOperator(operator, that, thisLeft)
            }
        }
    }

    /** Tuple type of a fixed length.  */
    @AutoValue
    abstract class FixedLengthTupleType : TupleType() {
        abstract fun getElementTypes(): ImmutableList<StarlarkType?>?

        override fun getElementType(): StarlarkType? {
            return Types.union(getElementTypes()!!)
        }

        override fun assignableFromHook(t: StarlarkType?): Boolean {
            if (t !is FixedLengthTupleType) {
                return false
            }
            // Covariant in each element type; the number of elements must match exactly.
            if (this.getElementTypes().size() != t.getElementTypes().size()) {
                return false
            }
            for (i in this.getElementTypes().indices) {
                if (!StarlarkType.Companion.assignableFrom(
                        this.getElementTypes()!!.get(i), t.getElementTypes()!!.get(i)
                    )
                ) {
                    return false
                }
            }
            return true
        }

        override fun getSupertypes(): MutableList<StarlarkType?> {
            val homogeneous = toHomogeneous()
            return ImmutableList.of<StarlarkType?>(
                homogeneous,
                sequence(homogeneous.getElementType()),
                collection(homogeneous.getElementType())
            )
        }

        override fun toString(): kotlin.String {
            return String.format(
                "tuple[%s]",
                if (getElementTypes()!!.isEmpty())
                    "()"
                else
                    getElementTypes()!!.stream().map<kotlin.String?>(Function { obj: StarlarkType? -> obj.toString() })
                        .collect(Collectors.joining(", "))
            )
        }

        override fun concatenate(rhs: TupleType): TupleType? {
            if (rhs is FixedLengthTupleType) {
                return tuple(
                    ImmutableList.builder<StarlarkType?>()
                        .addAll(getElementTypes())
                        .addAll(rhs.getElementTypes())
                        .build()
                )
            } else {
                return toHomogeneous().concatenate(rhs)
            }
        }

        override fun repeat(times: Int): FixedLengthTupleType {
            val builder = ImmutableList.builder<StarlarkType?>()
            for (i in 0..<times) {
                builder.addAll(getElementTypes())
            }
            return tuple(builder.build())
        }

        override fun toHomogeneous(): HomogeneousTupleType {
            return homogeneousTuple(Types.union(getElementTypes()!!))
        }

        override fun isComparable(that: StarlarkType): Boolean {
            if (that == ANY) {
                return true
            } else if (that is FixedLengthTupleType) {
                val commonLength: Int = Math.min(getElementTypes().size(), that.getElementTypes().size())
                for (i in 0..<commonLength) {
                    if (!StarlarkType.Companion.comparable(
                            getElementTypes()!!.get(i),
                            that.getElementTypes()!!.get(i)
                        )
                    ) {
                        return false
                    }
                }
                return true
            }
            // Comparison with HomogeneousTupleType defers to HomogeneousTupleType.
            return false
        }

        override fun toLvalue(): FixedLengthTupleType {
            return tuple(
                getElementTypes()!!.stream().map<StarlarkType?>(Function { obj: StarlarkType? -> obj!!.toLvalue() })
                    .collect(
                        ImmutableList.toImmutableList<StarlarkType?>()
                    )
            )
        }
    }

    /** Tuple type of an indeterminate length.  */
    @AutoValue
    abstract class HomogeneousTupleType : TupleType() {
        abstract override fun getElementType(): StarlarkType?

        override fun getSupertypes(): MutableList<StarlarkType?> {
            return ImmutableList.of<StarlarkType?>(sequence(getElementType()), collection(getElementType()))
        }

        override fun assignableFromHook(t: StarlarkType?): Boolean {
            if (t !is HomogeneousTupleType) {
                return false
            }
            // Covariant in element type.
            return StarlarkType.Companion.assignableFrom(this.getElementType(), t.getElementType())
        }

        override fun toString(): kotlin.String {
            return "tuple[" + getElementType() + ", ...]"
        }

        override fun concatenate(rhs: TupleType): HomogeneousTupleType? {
            return if (rhs is HomogeneousTupleType)
                homogeneousTuple(union(getElementType(), rhs.getElementType()))
            else
                concatenate(rhs.toHomogeneous())
        }

        override fun repeat(times: Int): TupleType {
            return if (times > 0) this else Types.EMPTY_TUPLE
        }

        override fun toHomogeneous(): HomogeneousTupleType {
            return this
        }

        override fun isComparable(that: StarlarkType): Boolean {
            if (that == ANY) {
                return true
            } else if (that is TupleType) {
                return StarlarkType.Companion.comparable(getElementType(), that.toHomogeneous()!!.getElementType())
            }
            return false
        }

        override fun toLvalue(): HomogeneousTupleType {
            return homogeneousTuple(getElementType()!!.toLvalue())
        }
    }

    /**
     * Abstract collection type implementing common functionality. Exists to be subclassed.
     * 
     * 
     * `AbstractCollectionType`'s default [.assignableFromHook] always returns false if
     * `t` is not an rvalue-subtype of this and not of the same Java class as this. Therefore,
     * subclasses having multiple Java classes corresponding to the same Starlark type family may need
     * to override [.assignableFromHook].
     */
    abstract class AbstractCollectionType : StarlarkType() {
        abstract fun getElementType(): StarlarkType?

        override fun assignableFromHook(t: StarlarkType?): Boolean {
            if (t is AbstractCollectionType) {
                if (t.isRvalueAssignableTo(this)) {
                    return true
                }
                // Assume 1-1 correspondence between Java subclass and Starlark type family.
                if (this.getClass() == t.getClass()) {
                    // Invariant in element type because `that` might be mutable.
                    return StarlarkType.Companion.consistentEquals(this.getElementType(), t.getElementType())
                }
            }
            return false
        }

        /**
         * Returns true if `this` is an rvalue type and is assignable to `that`.
         * 
         * 
         * Must be overridden by rvalue types.
         * 
         * 
         * Intended to be invoked by [.assignableFromHook] implementations.
         */
        // TODO: #27370 - Consider elevating to StarlarkType level if useful for non-collection types.
        open fun isRvalueAssignableTo(that: AbstractCollectionType?): Boolean {
            return false
        }

        override fun inferBinaryOperator(operator: TokenKind, that: StarlarkType?, thisLeft: Boolean): StarlarkType? {
            return when (operator) {
                TokenKind.IN, TokenKind.NOT_IN -> if (thisLeft) null else BOOL
                else -> null
            }
        }
    }

    /** Collection type.  */ // We need CollectionType to be a separate class from AbstractCollectionType for 2 reasons.
    // First, CollectionType is an immutable view of a collection (and so can be covariant in element
    // type), while AbstractCollectionType has mutable subtypes (which are invariant in element type).
    // Second, an @AutoValue class may not extend another - so we cannot have SequenceType or SetType
    // be subclasses of CollectionType (they are subclasses of AbstractCollectionType instead).
    @AutoValue
    abstract class CollectionType : AbstractCollectionType() {
        override fun assignableFromHook(t: StarlarkType?): Boolean {
            if (t is AbstractCollectionType) {
                if (t.isRvalueAssignableTo(this)) {
                    return true
                }
                // Covariant in element type when assigning from a Collection (which is immutable)
                return t is CollectionType
                        && StarlarkType.Companion.assignableFrom(this.getElementType(), t.getElementType())
            }
            return false
        }

        override fun toString(): kotlin.String {
            return "Collection[" + getElementType() + "]"
        }

        override fun toLvalue(): CollectionType {
            return collection(getElementType()!!.toLvalue())
        }
    }

    /** Abstract sequence type for common sequence functionality. Exists to be subclassed.  */
    abstract class AbstractSequenceType : AbstractCollectionType() {
        abstract override fun getElementType(): StarlarkType?

        override fun getSupertypes(): MutableList<StarlarkType?> {
            return ImmutableList.of<StarlarkType?>(collection(getElementType()))
        }
    }

    /** Sequence type.  */ // We need SequenceType to be a separate class from AbstractSequenceType for 2 reasons.
    // First, SequenceType is an immutable view of a sequence (and so can be covariant in element
    // type), while AbstractSequenceType has mutable subtypes (which are invariant in element type).
    // Second, an @AutoValue class may not extend another - so we cannot have ListType or TupleType
    // be subclasses of SequenceType (they are subclasses of AbstractSequenceType instead).
    @AutoValue
    abstract class SequenceType : AbstractSequenceType() {
        abstract override fun getElementType(): StarlarkType?

        override fun assignableFromHook(t: StarlarkType?): Boolean {
            if (t is AbstractSequenceType) {
                if (t.isRvalueAssignableTo(this)) {
                    return true
                }
                // Covariant in element type when assigning from a Sequence (which is immutable)
                return t is SequenceType
                        && StarlarkType.Companion.assignableFrom(this.getElementType(), t.getElementType())
            }
            return false
        }

        override fun toString(): kotlin.String {
            return "Sequence[" + getElementType() + "]"
        }

        override fun toLvalue(): SequenceType {
            return sequence(getElementType()!!.toLvalue())
        }

        protected override fun isRvalueAssignableTo(t: AbstractCollectionType?): Boolean {
            return false
        }
    }

    /**
     * Abstract mapping type for common map functionality. Exists to be subclassed.
     * 
     * 
     * `AbstractMappingType`'s default [.assignableFromHook] always returns false if
     * `t` and this are not of the same Java class. Therefore, subclasses having multiple Java
     * classes corresponding to the same Starlark type family may need to override [ ][.assignableFromHook].
     */
    abstract class AbstractMappingType : AbstractCollectionType() {
        abstract fun getKeyType(): StarlarkType?

        abstract fun getValueType(): StarlarkType?

        override fun getSupertypes(): MutableList<StarlarkType?> {
            return ImmutableList.of<StarlarkType?>(collection(getKeyType()))
        }

        override fun getElementType(): StarlarkType? {
            return getKeyType()
        }

        override fun assignableFromHook(t: StarlarkType?): Boolean {
            if (t is AbstractMappingType) {
                if (t.isMappingRvalueAssignableTo(this)) {
                    return true
                }
                // Assume 1-1 correspondence between Java subclass and Starlark type family.
                if (this.getClass() == t.getClass()) {
                    // Invariant in both key and value types because `that` might be mutable.
                    return StarlarkType.Companion.consistentEquals(this.getKeyType(), t.getKeyType())
                            && StarlarkType.Companion.consistentEquals(this.getValueType(), t.getValueType())
                }
            }
            return false
        }

        protected override fun isRvalueAssignableTo(t: AbstractCollectionType?): Boolean {
            return t is AbstractMappingType && this.isMappingRvalueAssignableTo(t)
        }

        /**
         * Returns true if `this` is an rvalue type and is assignable to `that`.
         * 
         * 
         * Must be overridden by rvalue types.
         * 
         * 
         * Intended to be invoked by [.assignableFromHook] implementations.
         */
        open fun isMappingRvalueAssignableTo(that: AbstractMappingType?): Boolean {
            return false
        }

        override fun inferBinaryOperator(operator: TokenKind, rhs: StarlarkType?, thisLeft: Boolean): StarlarkType? {
            return when (operator) {
                TokenKind.PIPE ->  // TODO: #27370 - mypy supports dict | dict, but doesn't support the | operator for
                    // non-dict mappings. Should we have the same restriction? (Note that such a restriction
                    // would break some uses of Bazel's native.existing_rules()).
                    // TODO: #27370 - do we need to handle Neve for the key or value type?
                    if (rhs is AbstractMappingType)
                        dictRvalue(
                            union(getKeyType(), rhs.getKeyType()),
                            union(getValueType(), rhs.getValueType())
                        )
                    else
                        null

                else -> super.inferBinaryOperator(operator, rhs, thisLeft)
            }
        }
    }

    /** Mapping type.  */ // We need MappingType to be a separate class from AbstractMappingType for 2 reasons.
    // First, MappingType is an immutable view of a mapping (and so can be covariant in value type),
    // while AbstractMappingType has mutable subtypes (which are invariant in value type).
    // Second, an @AutoValue class may not extend another - so we cannot have DictType be a subclass
    // of MappingType (it is a subclass of AbstractMappingType instead).
    @AutoValue
    abstract class MappingType : AbstractMappingType() {
        abstract override fun getKeyType(): StarlarkType?

        abstract override fun getValueType(): StarlarkType?

        override fun assignableFromHook(t: StarlarkType?): Boolean {
            if (t is AbstractMappingType) {
                if (t.isMappingRvalueAssignableTo(this)) {
                    return true
                }
                // Invariant in key type, covariant in value type when assigning from a Mapping (which is
                // immutable).
                // TODO: #27370 - Should Mapping assignment be covariant in key type as well?
                return t is MappingType
                        && StarlarkType.Companion.consistentEquals(this.getKeyType(), t.getKeyType())
                        && StarlarkType.Companion.assignableFrom(this.getValueType(), t.getValueType())
            }
            return false
        }

        override fun toString(): kotlin.String {
            return "Mapping[" + getKeyType() + ", " + getValueType() + "]"
        }

        override fun toLvalue(): MappingType {
            return mapping(getKeyType()!!.toLvalue(), getValueType()!!.toLvalue())
        }
    }

    /**
     * Immutable struct type.
     * 
     * 
     * This is intended to be either the type or a supertype for values implementing [ ] - for example, Bazel's structs and providers.
     * 
     * 
     * Morally non-struct types shouldn't add a [StructType] to their supertypes just because
     * they happen to have fields. For example, a `list` has `append` and `extend`
     * methods, but it is *not* a subtype of `struct[{"append": ..., "extend": ...}]`.
     * 
     * 
     * Since struct types don't support mutation, their assignability follows structural subtyping:
     * 
     * 
     *  * The set of LHS field names must be a subset of RHS field names. (This implies, in
     * particular, that a RHS total struct cannot be assigned to a LHS partial struct, since the
     * LHS partial struct admits any possible field name.)
     *  * The type of each LHS field must be assignable from the type of the corresponding RHS
     * field. (This implies, in particular, that [.STRUCT_OF_ANY] is assignable to all
     * struct types.)
     * 
     * 
     * In particular, these rules imply that:
     * 
     * 
     *  * A RHS total struct cannot be assigned to a LHS partial struct, since the LHS partial
     * struct admits any possible field name.
     *  * A LHS total struct with a particular set of fields `F` is assignable from any RHS
     * partial struct whose set of explicit fields is a subset of `F`.
     *  * [.STRUCT_OF_ANY] is assignable to all LHS struct types.
     * 
     */
    @AutoValue
    abstract class StructType : StarlarkType() {
        /** Returns the names and types of the mandatory fields of this struct type.  */ // TODO: #27370 - should we add optional fields? (Maybe useful for Bazel's providers.)
        // TODO: #27370 - should we add mutable fields / hasSetField()? If we do, such fields would need
        // to be treated as invariant for assignability.
        abstract fun getFields(): ImmutableMap<kotlin.String, StarlarkType>?

        /**
         * If true, then any field not specified in [.getFields] is assumed to potentially exist
         * and be of type [.ANY].
         */
        abstract fun isPartial(): Boolean

        override fun assignableFromHook(t: StarlarkType?): Boolean {
            if (t is StructType) {
                if (this.isPartial() && !t.isPartial()) {
                    return false
                }
                // The set of LHS field names must be a subset of RHS field names, and LHS field types must
                // be assignable from the corresponding RHS field types.
                return this.getFields().entrySet().stream()
                    .allMatch(
                        Predicate { entry1: MutableMap.MutableEntry<kotlin.String, StarlarkType>? ->
                            val fieldName: kotlin.String = entry1.getKey()
                            val fieldType1: StarlarkType = entry1.getValue()
                            val fieldType2 = t.getField(fieldName)
                            fieldType2 != null && StarlarkType.Companion.assignableFrom(fieldType1, fieldType2)
                        })
            }
            return false
        }

        override fun getField(name: kotlin.String?, context: TypeContext?): StarlarkType? {
            return getField(name)
        }

        /**
         * Returns the type of the field with the given name, or null if there is no such field.
         * 
         * 
         * Unlike for [StarlarkType.getField], this method doesn't take a [TypeContext]
         * because it's expected that the names and types of a struct's fields are fixed at type
         * construction time.
         */
        fun getField(name: kotlin.String?): StarlarkType? {
            val fieldType = getFields()!!.get(name)
            if (fieldType != null) {
                return fieldType
            }
            return if (isPartial()) ANY else null
        }

        override fun toString(): kotlin.String {
            if (this == STRUCT_OF_ANY) {
                return "struct"
            }
            val buf = StringBuilder()
            buf.append("struct[")
            TypeConstructor.Arg.TypeDict.Companion.print(buf, getFields())
            if (isPartial()) {
                if (!getFields()!!.isEmpty()) {
                    buf.append(", ")
                }
                buf.append("...")
            }
            buf.append("]")
            return buf.toString()
        }

        override fun toLvalue(): StructType {
            val builder = ImmutableMap.builder<kotlin.String?, StarlarkType?>()
            for (entry in getFields().entrySet()) {
                builder.put(entry.getKey(), entry.getValue().toLvalue())
            }
            return if (isPartial()) partialStruct(builder.buildOrThrow()) else struct(builder.buildOrThrow())
        }
    }
}
