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
package net.starlark.java.syntax

/**
 * A visitor for visiting the nodes of a syntax tree in lexical order (not evaluation order!).
 * 
 * 
 * Comments are *not* visited.
 * 
 * 
 * A subclass can change the traversal logic by setting [.skipNonSymbolIdentifiers].
 * 
 * 
 * Typical usage is for a subclass to just override the `visit()` method overloads for the
 * nodes that are relevant to its business logic, and to rely on the default implementations in this
 * class to ensure traversal over the remaining node types. Overriding implementations should
 * remember to traverse children using either `super.visit()` on the current node, or explicit
 * calls to [.visit], [.visitAll], or [.visitBlock] on child fields.
 * 
 * 
 * Contrary to usual Java style, it is *not* recommended to strictly group all overloads of
 * `visit()` together, but rather to place helper methods for a specific node type next to its
 * associated `visit()` overload. Rationale: The benefit of this style rule is that a reader
 * can rely on the absence of an overload in the immediate vicinity of the method as evidence that
 * no such overload exists. But this isn't helpful in the context of the visitor pattern, where the
 * reader expects there to be many unrelated overloads.
 */
open class NodeVisitor {
    /**
     * If set, we only visit [Identifier]s that correspond to a definition or use of a symbol in
     * the current file. Specifically, this omits:
     * 
     * 
     *  * names of keyword arguments (but not names of keyword parameters!)
     *  * field names in dot expressions
     * 
     * 
     * 
     * Note that `Identifier`s in such contexts have no [Binding] set for them by the
     * resolver.
     */
    @kotlin.jvm.JvmField
    protected var skipNonSymbolIdentifiers: Boolean = false

    // visit() overloads in this class are ordered by node type, first by category (misc / statement /
    // expression), then alphabetically within category. (Subclasses are not obliged to maintain the
    // same method ordering.)
    /** Entrypoint for visiting a node. Clients should avoid calling node-specific overloads.  */
    fun visit(node: Node) {
        // Double-dispatch pattern.
        // If a node type is added to the AST but no corresponding overload added to this class, we'll
        // see an infinite recursion in this method.
        node.accept(this)
    }

    // ==== Miscellaneous node types ====
    /**
     * Handles all four Argument node types uniformly. Subclasses should not add an overload for a
     * concrete Argument subclass; it won't be called.
     */
    fun visit(node: Argument) {
        if (!skipNonSymbolIdentifiers && node is Argument.Keyword) {
            visit(node.getIdentifier())
        }
        visit(node.getValue())
    }

    /**
     * @throws UnsupportedOperationException always.
     */
    @Deprecated(
        """Not supported.
    """
    )
    fun visit(@Suppress("unused") node: Comment?) {
        // No reason we can't support this if we needed to.
        throw UnsupportedOperationException("NodeVisitor does not support visiting comments")
    }

    // Clause and Entry are handled below next to Comprehension and Dict respectively.
    /**
     * Handles all four Parameter node types uniformly. Subclasses should not add an overload for a
     * concrete Parameter subclass; it won't be called.
     */
    open fun visit(node: Parameter) {
        if (node.getIdentifier() != null) {
            visit(node.getIdentifier())
        }
        if (node.getType() != null) {
            visit(node.getType()!!)
        }
        if (node.getDefaultValue() != null) {
            visit(node.getDefaultValue()!!)
        }
    }

    open fun visit(node: StarlarkFile) {
        visitBlock(node.getStatements())
    }

    // ==== Statement nodes ====
    open fun visit(node: AssignmentStatement) {
        visit(node.getLHS())
        if (node.getType() != null) {
            visit(node.getType()!!)
        }
        visit(node.getRHS())
    }

    open fun visit(node: ExpressionStatement) {
        visit(node.getExpression())
    }

    open fun visit(node: FlowStatement?) {}

    open fun visit(node: ForStatement) {
        visit(node.getVars())
        visit(node.getCollection())
        visitBlock(node.getBody())
    }

    open fun visit(node: DefStatement) {
        visit(node.getIdentifier())
        visitAll(node.getTypeParameters())
        visitAll(node.getParameters())
        if (node.getReturnType() != null) {
            visit(node.getReturnType()!!)
        }
        visitBlock(node.getBody())
    }

    open fun visit(node: IfStatement) {
        visit(node.getCondition())
        visitBlock(node.getThenBlock())
        if (node.getElseBlock() != null) {
            visitBlock(node.getElseBlock()!!)
        }
    }

    open fun visit(node: LoadStatement) {
        for (binding in node.getBindings()) {
            visit(binding.getLocalName())
            // We don't visit the original name.
            //
            // Currently, our AST doesn't distinguish between the case when the local name is omitted,
            // versus the case where it is given explicitly and exactly matches the original name. This
            // means that, if we visited both names here, we would end up double-visiting something that
            // often only appears once in the program source.
            //
            // TODO(bazel-team): Disambiguate these cases in LoadStatement.Binding, then visit it here,
            // but ONLY if skipNonSymbolIdentifiers is not set. Mind that subclasses might need updating
            // to continue to avoid traversing the original name.
        }
    }

    open fun visit(node: ReturnStatement) {
        if (node.getResult() != null) {
            visit(node.getResult()!!)
        }
    }

    open fun visit(node: TypeAliasStatement) {
        visit(node.getIdentifier())
        visitAll(node.getParameters())
        visit(node.getDefinition())
    }

    open fun visit(node: VarStatement) {
        visit(node.getIdentifier())
        visit(node.getType())
    }

    // ==== Expression nodes ====
    fun visit(node: BinaryOperatorExpression) {
        visit(node.getX())
        visit(node.getY())
    }

    open fun visit(node: CallExpression) {
        visit(node.getFunction())
        visitAll(node.getArguments())
    }

    open fun visit(node: CastExpression) {
        visit(node.getType())
        visit(node.getValue())
    }

    open fun visit(node: Comprehension) {
        visit(node.getBody())
        for (clause in node.getClauses()) {
            if (clause is Comprehension.For) {
                visit(clause)
            } else {
                visit((clause as net.starlark.java.syntax.Comprehension.If?)!!)
            }
        }
    }

    fun visit(node: Comprehension.For) {
        visit(node.getVars())
        visit(node.getIterable())
    }

    fun visit(node: Comprehension.If) {
        visit(node.getCondition())
    }

    fun visit(node: ConditionalExpression) {
        visit(node.getThenCase())
        visit(node.getCondition())
        if (node.getElseCase() != null) {
            visit(node.getElseCase())
        }
    }

    fun visit(node: DictExpression) {
        visitAll(node.getEntries())
    }

    fun visit(node: DictExpression.Entry) {
        visit(node.getKey())
        visit(node.getValue())
    }

    fun visit(node: DotExpression) {
        visit(node.getObject())
        if (!skipNonSymbolIdentifiers) {
            visit(node.getField())
        }
    }

    fun visit(node: Ellipsis?) {}

    fun visit(@Suppress("unused") node: FloatLiteral?) {}

    open fun visit(node: Identifier?) {}

    fun visit(node: IndexExpression) {
        visit(node.getObject())
        visit(node.getKey())
    }

    fun visit(@Suppress("unused") node: IntLiteral?) {}

    open fun visit(node: IsInstanceExpression) {
        visit(node.getValue())
        visit(node.getType())
    }

    open fun visit(node: LambdaExpression) {
        visitAll(node.getParameters())
        visit(node.getBody())
    }

    fun visit(node: ListExpression) {
        visitAll(node.getElements())
    }

    fun visit(node: SliceExpression) {
        visit(node.getObject())
        if (node.getStart() != null) {
            visit(node.getStart()!!)
        }
        if (node.getStop() != null) {
            visit(node.getStop()!!)
        }
        if (node.getStep() != null) {
            visit(node.getStep()!!)
        }
    }

    open fun visit(@Suppress("unused") node: StringLiteral?) {}

    fun visit(node: UnaryOperatorExpression) {
        visit(node.getX())
    }

    fun visit(node: TypeApplication) {
        visit(node.getConstructor())
        visitAll(node.getArguments())
    }

    // ==== Helpers for sequences of nodes ====
    /**
     * Visits a sequence of nodes (e.g. a list of arguments).
     * 
     * 
     * See [.visitBlock] for a common case.
     */
    // Final because this method is called across completely different categories of nodes, so it is
    // usually a mistake to attempt to override it.
    fun visitAll(nodes: MutableList<out Node>) {
        for (node in nodes) {
            visit(node)
        }
    }

    /** Convenience/readability method for visiting a block of statements (e.g. an if branch).  */ // Previously this method was non-final and it was recommended to override it to perform an action
    // for every block. However, this is error-prone if the subclass inadvertently calls visitAll()
    // rather than visitBlock() in one of its other overrides. No one seems to be overriding either
    // method, so we made both of them final to avoid this potential problem.
    fun visitBlock(statements: MutableList<Statement>) {
        visitAll(statements)
    }
}
