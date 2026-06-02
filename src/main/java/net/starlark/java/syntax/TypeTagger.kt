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

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.errorprone.annotations.FormatMethod
import net.starlark.java.spelling.SpellChecker
import net.starlark.java.spelling.SpellChecker.didYouMean
import java.lang.String
import java.util.*
import java.util.function.Function
import kotlin.Any
import kotlin.Boolean
import kotlin.IllegalArgumentException
import kotlin.Int

/**
 * A visitor for tagging the data structures of a resolved file with type information.
 * 
 * 
 * This populates the function type on the [Resolver.Function] objects in the AST and
 * records whether or not a given [Resolver.Function] is considered to use static type syntax;
 * populates the variable types on the [Resolver.Binding] objects; and populates the Starlark
 * type stored in [CastExpression]s. These type fields must all be null prior to running the
 * visitor.
 * 
 * 
 * The types assigned to the fields are based solely on the type annotations in the program. No
 * type inference is done here.
 * 
 * 
 * Only a file that has passed the `Resolver` without errors should be run through this
 * visitor.
 */
class TypeTagger private constructor(typeTable: TypeTable, module: Resolver.Module, loader: Loader?) : NodeVisitor() {
    /**
     * An immutable view of a `load()` dependency. Provides the exported symbols (in practice:
     * the evaluated module's global variables) and their types.
     * 
     * 
     * Contrast with [Resolver.Module], which resolves a program's own names during the
     * process of its compilation and type checking. A [LoadableModule] and [ ] in theory need not be objects of the same class (although in practice, they
     * are; see [net.starlark.java.eval.Module]).
     */
    interface LoadableModule {
        /** Returns the symbols (in practice, global variables) exported by this module.  */
        fun getExports(): MutableSet<String?>?

        /** Returns whether the module exports a given symbol.  */
        fun hasExport(name: String?): Boolean

        /**
         * Returns the Starlark type of the specified exported symbol, or null if the export was not
         * assigned a type (in particular, if type tagging for the module was disabled).
         */
        fun getExportType(name: String?): StarlarkType?
    }

    /** Returns the named module, or null if not found.  */
    fun interface Loader {
        fun load(importName: String?): LoadableModule?
    }

    private val typeTable: TypeTable

    private val module: Resolver.Module

    private val loader: Loader?

    // Empty if we are tagging a type expression (inside which no function definitions are allowed).
    // Populated and mutated by visitation.
    private val functionStack = ArrayDeque<Resolver.Function?>()

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

    init {
        this.typeTable = typeTable
        this.module = module
        this.loader = loader
    }

    private constructor(
        typeTable: TypeTable,
        module: Resolver.Module,
        loader: Loader?,
        toplevel: Resolver.Function?
    ) : this(typeTable, module, loader) {
        functionStack.push(toplevel)
    }

    fun getTypeTable(): TypeTable {
        return typeTable
    }

    /**
     * Given an identifier denoting a type constructor, obtains the type constructor from the module.
     * 
     * 
     * If no match, logs an error at the given node and returns null.
     */
    private fun resolveTypeConstructor(id: Identifier): TypeConstructor? {
        val name = id.getName()

        val scope = id.getBinding()!!.getScope()
        if (!(scope == Resolver.Scope.UNIVERSAL || scope == Resolver.Scope.PREDECLARED || scope == Resolver.Scope.GLOBAL)) {
            // Local names cannot by types. Don't allow `x: Foo` to succeed if Foo is a local shadowing a
            // type name.
            errorf(id, "local symbol '%s' cannot be used as a type", name)
            return null
        }

        try {
            val constructor = module.getTypeConstructor(name)
            if (constructor == null) {
                errorf(id, "%s symbol '%s' cannot be used as a type", scope, name)
                return null
            }
            return constructor
        } catch (ex: Resolver.Module.Undefined) {
            val suggestion = if (ex.candidates != null) didYouMean(name, ex.candidates) else ""
            errorf(id, "%s%s", ex.getMessage(), suggestion)
            return null
        }
    }

    private fun extractArg(expr: Expression): TypeConstructor.Arg? {
        when (expr.kind()) {
            Expression.Kind.BINARY_OPERATOR -> {
                // Syntax sugar for union types, i.e. a|b == Union[a,b]
                val binop = expr as BinaryOperatorExpression
                if (binop.getOperator() == TokenKind.PIPE) {
                    val x = extractType(binop.getX())
                    val y = extractType(binop.getY())
                    return Types.union(x, y)
                }
                errorf(expr, "binary operator '%s' is not supported", binop.getOperator())
                return Types.ANY
            }

            Expression.Kind.TYPE_APPLICATION -> {
                val app = expr as TypeApplication

                val constructor = resolveTypeConstructor(app.getConstructor())
                if (constructor == null) {
                    return Types.ANY
                }
                val arguments =
                    app.getArguments().stream()
                        .map<TypeConstructor.Arg?>(Function { expr: Expression? -> this.extractArg(expr!!) }).collect(
                            ImmutableList.toImmutableList<TypeConstructor.Arg?>()
                        )

                try {
                    return constructor.createStarlarkType(arguments)
                } catch (e: TypeConstructor.Failure) {
                    errorf(expr, "%s", e.getMessage())
                    return Types.ANY
                }
            }

            Expression.Kind.IDENTIFIER -> {
                val constructor = resolveTypeConstructor(expr as Identifier)
                if (constructor == null) {
                    return Types.ANY
                }
                try {
                    return constructor.createStarlarkType(ImmutableList.of<TypeConstructor.Arg?>())
                } catch (e: TypeConstructor.Failure) {
                    errorf(expr, "%s", e.getMessage())
                    return Types.ANY
                }
            }

            Expression.Kind.ELLIPSIS -> {
                return TypeConstructor.Arg.Companion.ELLIPSIS
            }

            Expression.Kind.LIST_EXPR -> {
                val listExpr = expr as ListExpression
                if (listExpr.isTuple() && listExpr.getElements().isEmpty()) {
                    return TypeConstructor.Arg.Companion.EMPTY_TUPLE
                }
            }

            Expression.Kind.DICT_EXPR -> {
                val dictExpr = expr as DictExpression
                val types = LinkedHashMap<kotlin.String?, StarlarkType?>()
                for (entry in dictExpr.getEntries()) {
                    if (entry.getKey() is StringLiteral) {
                        val key: kotlin.String? = str.getValue()
                        val previous = types.put(key, extractType(entry.getValue()))
                        if (previous != null) {
                            errorf(str, "dictionary expression has duplicate key: %s", str)
                        }
                    } else {
                        errorf(entry.getKey(), "expected a string literal but got '%s'", entry.getKey())
                    }
                }
                return TypeConstructor.Arg.TypeDict(ImmutableMap.copyOf<kotlin.String?, StarlarkType?>(types))
            }

            else -> {}
        }
        // TODO(ilist@): full evaluation: lists and dicts
        errorf(expr, "unexpected expression '%s'", expr)
        return Types.ANY
    }

    private fun extractType(expr: Expression): StarlarkType {
        val arg = extractArg(expr)
        if (arg !is StarlarkType) {
            errorf(expr, "expression '%s' is not a valid type.", expr)
            return Types.ANY
        }
        return arg
    }

    private fun createFunctionType(
        parameters: ImmutableList<Parameter>, returnTypeExpr: Expression?
    ): Types.CallableType {
        val names = ImmutableList.builder<kotlin.String?>()
        val types = ImmutableList.builder<StarlarkType?>()
        val mandatoryParameters = ImmutableSet.builder<kotlin.String?>()

        val nparams: Int = parameters.size()
        var numPositionalParameters = 0
        var star: Parameter.Star? = null
        var starStar: Parameter.StarStar? = null
        var i: Int
        i = 0
        while (i < nparams) {
            val param = parameters.get(i)
            if (param is Parameter.Star) {
                star = param
                i++
                continue
            }
            if (param is Parameter.StarStar) {
                starStar = param
                i++
                continue
            }
            if (star == null) {
                numPositionalParameters++
            }

            val name = param.getName()
            val typeExpr = param.getType()

            names.add(name)
            types.add(if (typeExpr == null) Types.ANY else extractType(typeExpr))
            if (param is Parameter.Mandatory) {
                mandatoryParameters.add(name)
            }
            i++
        }

        var varargsType: StarlarkType? = null
        if (star != null && star.getIdentifier() != null) {
            val typeExpr = star.getType()
            varargsType = if (typeExpr == null) Types.ANY else extractType(typeExpr)
        }

        var kwargsType: StarlarkType? = null
        if (starStar != null) {
            val typeExpr = starStar.getType()
            kwargsType = if (typeExpr == null) Types.ANY else extractType(typeExpr)
        }

        var returnType = Types.ANY
        if (returnTypeExpr != null) {
            returnType = extractType(returnTypeExpr)
        }

        return Types.callable(
            names.build(),
            types.build(),  /* numPositionalOnlyParameters= */
            0,
            numPositionalParameters,
            mandatoryParameters.build(),
            varargsType,
            kwargsType,
            returnType
        )
    }

    /**
     * Sets an identifier's type.
     * 
     * 
     * The `Binding` on the identifier must have already been set by the resolver.
     * (Therefore, this method cannot be called for identifiers that are not symbols, like field names
     * or call site keyword arguments.)
     * 
     * 
     * Logs an error if the identifier is not the first binding occurrence of the `Binding`.
     * In this case, the type is not updated.
     * 
     * 
     * Throws [IllegalArgumentException] if this is the first binding occurrence but somehow
     * the type is already set.
     */
    private fun setType(node: Node?, id: Identifier, type: StarlarkType?) {
        val binding = id.getBinding()
        Preconditions.checkNotNull<Resolver.Binding?>(binding, "no binding set on identifier '%s'", id.getName())

        if (binding!!.getFirst() != id) {
            if (node is DefStatement) {
                // A def statement appearing in typed code constitutes an implicit type annotation on the
                // function identifier's symbol. Even if the signature contains no type annotations, the
                // function identifier is still considered to be marked as a Callable. Therefore, this needs
                // to be the first binding occurrence of the symbol.
                //
                // A consequence of this is that `def f(): ...; f = lambda: ...` is permitted by the
                // type tagger (though the type checker will still require the assignment to be consistent
                // with the def's type signature), even though the opposite statement order is prohibited.
                //
                // When a violation occurs at a def statement, we use a more specific error message to avoid
                // confusing the user.
                errorf(id, "function '%s' was previously declared", id.getName())
            } else {
                errorf(id, "type annotation on '%s' may only appear at its declaration", id.getName())
            }
            if (binding.isSyntactic()) {
                errorf(binding.getFirst(), "'%s' previously declared here", id.getName())
            }
            return
        }

        val prevType = typeTable.getType(binding)
        require(prevType == null) {
            String.format(
                "Expected type of binding %s to be null but was %s",
                binding,
                prevType
            )
        }
        typeTable.setDeclaredType(binding, type)
    }

    private fun setType(resolved: Resolver.Function?, type: Types.CallableType?) {
        setType(resolved, type, typeTable)
    }

    private fun visitProgram(prog: Program) {
        Preconditions.checkState(
            functionStack.isEmpty(),
            "When tagging a Program, functionStack is expected to be initially empty"
        )
        val toplevel = prog.getResolvedFunction()
        this.functionStack.push(toplevel)
        visitBlock(toplevel.getBody())
        Preconditions.checkState(functionStack.pop() == toplevel)
    }

    override fun visit(file: StarlarkFile) {
        Preconditions.checkState(
            functionStack.isEmpty(),
            "When tagging a StarlarkFile, functionStack is expected to be initially empty"
        )
        val toplevel = file.getResolvedFunction()
        this.functionStack.push(toplevel)
        super.visit(file)
        Preconditions.checkState(functionStack.pop() == toplevel)
    }

    override fun visit(assignment: AssignmentStatement) {
        if (assignment.getType() != null) {
            setUsesTypeSyntax()
            val type = extractType(assignment.getType()!!)
            setType(assignment, (assignment.getLHS() as net.starlark.java.syntax.Identifier?)!!, type)
        }

        // Traverse children; RHS could contain a lambda.
        super.visit(assignment)
    }

    override fun visit(def: DefStatement) {
        val resolvedFunction = def.getResolvedFunction()
        functionStack.push(resolvedFunction)
        val type = createFunctionType(def.getParameters(), def.getReturnType())
        setType(resolvedFunction, type)
        setType(def, def.getIdentifier(), type)
        // Parameter types handled by visit(Parameter).
        if (def.getReturnType() != null || !def.getTypeParameters().isEmpty()) {
            setUsesTypeSyntax()
        }

        super.visit(def)
        Preconditions.checkState(functionStack.pop() == resolvedFunction)
    }

    override fun visit(param: Parameter) {
        if (param.getIdentifier() != null) {
            // Default to ANY for unannotated params.
            // This matches the behavior for the Resolver.Function's type.
            var type = Types.ANY
            if (param.getType() != null) {
                setUsesTypeSyntax()
                type = extractType(param.getType()!!)
            }
            setType(param, param.getIdentifier()!!, type)
        }

        super.visit(param)
    }

    override fun visit(load: LoadStatement) {
        if (loader == null) {
            errorf(load, "load statements are not supported because no module loader has been defined")
            return
        }
        val importName = load.getImport().getValue()
        val loadedModule = loader.load(importName)
        if (loadedModule == null) {
            errorf(load, "module '%s' not found", importName)
            return
        }
        for (binding in load.getBindings()) {
            val originalName = binding.getOriginalName().getName()
            if (!loadedModule.hasExport(originalName)) {
                errorf(
                    binding.getOriginalName(),
                    "module '%s' does not contain symbol '%s'%s",
                    importName,
                    originalName,
                    SpellChecker.didYouMean(originalName, loadedModule.getExports())
                )
                continue
            }
            setType(load, binding.getLocalName(), loadedModule.getExportType(originalName))
        }
    }

    override fun visit(node: TypeAliasStatement) {
        setUsesTypeSyntax()
        super.visit(node)
    }

    override fun visit(`var`: VarStatement) {
        val type = extractType(`var`.getType())
        setType(`var`, `var`.getIdentifier(), type)
        setUsesTypeSyntax()

        // No need to descend into type expression child.
    }

    // TODO: #28325 - Ensure we assign the type of an identifier referencing a universal/predeclared
    // symbol, i.e. with no binding occurrences in the file.
    override fun visit(cast: CastExpression) {
        setUsesTypeSyntax()
        cast.setStarlarkType(extractType(cast.getType()))
        super.visit(cast)
    }

    override fun visit(lambda: LambdaExpression) {
        val type =
            createFunctionType(lambda.getParameters(),  /* returnTypeExpr= */null)
        setType(lambda.getResolvedFunction(), type)

        super.visit(lambda)
    }

    private fun setUsesTypeSyntax() {
        // If anything in the file (or in the expr if TypeTagger is invoked via tagExpr()) uses type
        // syntax, the toplevel is considered to use type syntax.
        typeTable.setUsesTypeSyntax(functionStack.peekLast())
        // If anything nested in the most proximate def statement uses type syntax, the def statement
        // is considered to use type syntax
        typeTable.setUsesTypeSyntax(functionStack.peek())
    }

    companion object {
        /**
         * Statically evaluates a type expression to the [StarlarkType] it denotes.
         * 
         * @param expr a valid type expression with binding information resolved, which must have been
         * parsed with the appropriate [FileOptions] set; see [.tagFile]
         * @param exprFunction the resolver function for `expr` constructed by [     ][Resolver.resolveExpr]
         * @param module a static Resolver.Module containing type information for the bindings used in
         * type expressions
         * @throws SyntaxError.Exception if expr is not a type expression or if it could not be evaluated
         * to a type.
         */
        @kotlin.jvm.JvmStatic
        @Throws(SyntaxError.Exception::class)
        fun extractType(
            expr: Expression, exprFunction: Resolver.Function, module: Resolver.Module
        ): StarlarkType {
            // loader is null because expressions cannot contain load statements.
            val r = TypeTagger(TypeTable(exprFunction), module,  /* loader= */null)
            val result = r.extractType(expr)
            if (!r.getTypeTable().ok()) {
                throw SyntaxError.Exception(r.getTypeTable().errors())
            }
            return result
        }

        /**
         * Sets a resolved function's type.
         * 
         * 
         * Throws [IllegalArgumentException] if the type is already set.
         */
        private fun setType(
            resolved: Resolver.Function?, type: Types.CallableType?, typeTable: TypeTable
        ) {
            Preconditions.checkNotNull<Resolver.Function?>(resolved)
            val prevType: StarlarkType? = typeTable.getType(resolved)
            require(prevType == null) {
                String.format(
                    "Expected type of resolved function %s to be null but was %s",
                    resolved!!.getName(), prevType
                )
            }
            typeTable.setType(resolved, type)
        }

        // TODO: #27370 - Figure out the relationship between this visitor and identifiers introduced by
        // type alias statements. I don't think it's quite correct to say that `type A = B` is annotating
        // A's binding with the evaluation of type B. It probably should live in outer logic that
        // determines the type environment.
        private fun checkFileOptions(options: FileOptions) {
            Preconditions.checkArgument(
                options.resolveTypeSyntax(), "type tagging requires that resolveTypeSyntax is set"
            )
            Preconditions.checkArgument(
                !options.tolerateInvalidTypeExpressions(),
                "type tagging requires that tolerateInvalidTypeExpressions is not set"
            )
        }

        /**
         * Determines the Starlark types of the [Resolver.Function]s and [Resolver.Binding]s
         * in the given AST (which must have already been processed by [Resolver]), based on the
         * supplied annotations. Returns the resulting [TypeTable] for the file.
         * 
         * 
         * Any errors are appended to the file's list of errors.
         * 
         * @throws IllegalArgumentException if the file's [FileOptions] don't contain [     ][FileOptions.resolveTypeSyntax] or do contain [     ][FileOptions.tolerateInvalidTypeExpressions].
         * @param loader a [Loader] for loading modules via load() statements; may be null if the
         * file is known to not contain load() statements
         */
        @kotlin.jvm.JvmStatic
        fun tagFile(
            file: StarlarkFile, module: Resolver.Module, loader: Loader?
        ): TypeTable {
            checkFileOptions(file.getOptions())
            val typeTable = TypeTable(file)
            val r = TypeTagger(typeTable, module, loader)
            r.visit(file)
            return typeTable
        }

        /**
         * Like [.tagFile], but on an already-compiled [Program].
         * 
         * 
         * The program is *not* mutated. In particular, the pre-existing [Program.getTypeTable]
         * (if any) is ignored. Any errors are reported in the returned type table's [ ][TypeTable.errors] list.
         */
        @kotlin.jvm.JvmStatic
        fun tagProgram(
            prog: Program, module: Resolver.Module, loader: Loader?
        ): TypeTable {
            checkFileOptions(prog.getOptions())
            val toplevel = prog.getResolvedFunction()
            val typeTable = TypeTable(toplevel)
            val r = TypeTagger(typeTable, module, loader)
            r.visitProgram(prog)
            return typeTable
        }

        /**
         * Same as [.tagFile], but for an individual expression.
         * 
         * 
         * Any errors are thrown as a [SyntaxError.Exception].
         * 
         * @param function the [Resolver.Function] that the resolver generated to wrap an
         * expression.
         */
        @Throws(SyntaxError.Exception::class)
        fun tagExpr(
            expr: Expression, function: Resolver.Function, module: Resolver.Module
        ): TypeTable {
            val typeTable = TypeTable(function)
            // Use a null loader because load() cannot appear in expressions.
            val r = TypeTagger(typeTable, module,  /* loader= */null, function)

            r.visit(expr)

            if (!typeTable.ok()) {
                throw SyntaxError.Exception(typeTable.errors())
            }
            return typeTable
        }
    }
}
