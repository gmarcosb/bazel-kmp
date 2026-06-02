// Copyright 2019 The Bazel Authors. All rights reserved.
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

/** A pretty-printer for Starlark syntax trees.  */
internal class NodePrinter {
    private val buf: StringBuilder
    private var indent = 0

    constructor(buf: StringBuilder) {
        this.buf = buf
    }

    // Constructor exposed to legacy tests.
    // TODO(adonovan): rewrite the tests not to care about the indent parameter.
    constructor(buf: StringBuilder, indent: Int) {
        this.buf = buf
        this.indent = indent
    }

    // Main entry point for an arbitrary node.
    // Called by Node.prettyPrint.
    fun printNode(n: Node) {
        if (n is Expression) {
            printExpr(n)
        } else if (n is Statement) {
            printStmt(n)
        } else if (n is StarlarkFile) {
            // Only statements are printed, not comments.
            for (stmt in n.getStatements()) {
                printStmt(stmt)
            }
        } else if (n is Comment) {
            // We can't really print comments in the right place anyway,
            // due to how their relative order is lost in the representation
            // of StarlarkFile. So don't bother word-wrapping and just print
            // it on a single line.
            printIndent()
            buf.append(n.getText())
        } else if (n is Argument) {
            printArgument(n)
        } else if (n is Parameter) {
            printParameter(n)
        } else if (n is DictExpression.Entry) {
            printDictEntry(n)
        } else {
            throw IllegalArgumentException("unexpected: " + n.javaClass)
        }
    }

    private fun printSuite(statements: MutableList<Statement>) {
        // A suite is non-empty; pass statements are explicit.
        indent++
        for (stmt in statements) {
            printStmt(stmt)
        }
        indent--
    }

    private fun printIndent() {
        for (i in 0..<indent) {
            buf.append("  ")
        }
    }

    private fun printArgument(arg: Argument) {
        if (arg is Argument.Positional) {
            // nop
        } else if (arg is Argument.Keyword) {
            buf.append(arg.getIdentifier().getName())
            buf.append(" = ")
        } else if (arg is Argument.Star) {
            buf.append('*')
        } else if (arg is Argument.StarStar) {
            buf.append("**")
        }
        printExpr(arg.getValue(), true)
    }

    private fun printParameter(param: Parameter?) {
        if (param is Parameter.Mandatory) {
            buf.append(param.getName())
        } else if (param is Parameter.Optional) {
            buf.append(param.getName())
            buf.append('=')
            printExpr(param.getDefaultValue()!!)
        } else if (param is Parameter.Star) {
            buf.append('*')
            if (param.getName() != null) {
                buf.append(param.getName())
            }
        } else if (param is Parameter.StarStar) {
            buf.append("**")
            buf.append(param.getName())
        }
    }

    private fun printDictEntry(e: DictExpression.Entry) {
        printExpr(e.getKey())
        buf.append(": ")
        printExpr(e.getValue())
    }

    // Appends "def f(a, ..., z):" to the buf.
    // Also used by DefStatement.toString.
    fun printDefSignature(def: DefStatement) {
        buf.append("def ")
        printExpr(def.getIdentifier())
        if (!def.getTypeParameters().isEmpty()) {
            buf.append("[")
            var sep = ""
            for (typeParam in def.getTypeParameters()) {
                buf.append(sep)
                printExpr(typeParam)
                sep = ", "
            }
            buf.append("]")
        }
        buf.append('(')
        var sep = ""
        for (param in def.getParameters()) {
            buf.append(sep)
            printParameter(param)
            if (param.getType() != null) {
                buf.append(": ")
                printExpr(param.getType()!!, true)
            }
            sep = ", "
        }
        buf.append(")")
        if (def.getReturnType() != null) {
            buf.append(" -> ")
            printExpr(def.getReturnType()!!, true)
        }
        buf.append(":")
    }

    private fun printStmt(s: Statement) {
        printIndent()

        when (s.kind()) {
            Statement.Kind.ASSIGNMENT -> {
                val stmt = s as AssignmentStatement
                printExpr(stmt.getLHS())
                val type = stmt.getType()
                if (type != null) {
                    buf.append(" : ")
                    printExpr(type)
                }
                buf.append(' ')
                if (stmt.isAugmented()) {
                    buf.append(stmt.getOperator())
                }
                buf.append("= ")
                printExpr(stmt.getRHS())
                buf.append('\n')
            }

            Statement.Kind.EXPRESSION -> {
                val stmt = s as ExpressionStatement
                printExpr(stmt.getExpression())
                buf.append('\n')
            }

            Statement.Kind.FLOW -> {
                val stmt = s as FlowStatement
                buf.append(stmt.getFlowKind()).append('\n')
            }

            Statement.Kind.FOR -> {
                val stmt = s as ForStatement
                buf.append("for ")
                printExpr(stmt.getVars())
                buf.append(" in ")
                printExpr(stmt.getCollection())
                buf.append(":\n")
                printSuite(stmt.getBody())
            }

            Statement.Kind.DEF -> {
                val stmt = s as DefStatement
                printDefSignature(stmt)
                buf.append('\n')
                printSuite(stmt.getBody())
            }

            Statement.Kind.IF -> {
                val stmt = s as IfStatement
                buf.append(if (stmt.isElif()) "elif " else "if ")
                printExpr(stmt.getCondition())
                buf.append(":\n")
                printSuite(stmt.getThenBlock())
                val elseBlock: MutableList<Statement>? = stmt.getElseBlock()
                if (elseBlock != null) {
                    if (elseBlock.size == 1 && elseBlock.get(0) is IfStatement
                        && (elseBlock.get(0) as IfStatement).isElif()
                    ) {
                        printStmt(elseBlock.get(0))
                    } else {
                        printIndent()
                        buf.append("else:\n")
                        printSuite(elseBlock)
                    }
                }
            }

            Statement.Kind.LOAD -> {
                val stmt = s as LoadStatement
                buf.append("load(")
                printExpr(stmt.getImport())
                for (binding in stmt.getBindings()) {
                    buf.append(", ")
                    val local = binding.getLocalName()
                    val origName = binding.getOriginalName().getName()
                    if (origName == local.getName()) {
                        buf.append('"')
                        printExpr(local)
                        buf.append('"')
                    } else {
                        printExpr(local)
                        buf.append("=\"")
                        buf.append(origName)
                        buf.append('"')
                    }
                }
                buf.append(")\n")
            }

            Statement.Kind.RETURN -> {
                val stmt = s as ReturnStatement
                buf.append("return")
                if (stmt.getResult() != null) {
                    buf.append(' ')
                    printExpr(stmt.getResult()!!)
                }
                buf.append('\n')
            }

            Statement.Kind.TYPE_ALIAS -> {
                val stmt = s as TypeAliasStatement
                buf.append("type ")
                printExpr(stmt.getIdentifier())
                if (!stmt.getParameters().isEmpty()) {
                    buf.append('[')
                    var sep = ""
                    for (param in stmt.getParameters()) {
                        buf.append(sep)
                        printExpr(param)
                        sep = ", "
                    }
                    buf.append(']')
                }
                buf.append(" = ")
                printExpr(stmt.getDefinition(),  /* canSkipParenthesis= */true)
                buf.append('\n')
            }

            Statement.Kind.VAR -> {
                val stmt = s as VarStatement
                printExpr(stmt.getIdentifier())
                buf.append(" : ")
                printExpr(stmt.getType())
                buf.append('\n')
            }
        }
    }

    private fun printExpr(expr: Expression, canSkipParenthesis: Boolean = false) {
        when (expr.kind()) {
            Expression.Kind.BINARY_OPERATOR -> {
                val binop = expr as BinaryOperatorExpression
                // TODO(bazel-team): print minimal number of parentheses
                if (!canSkipParenthesis) {
                    buf.append('(')
                }
                printExpr(binop.getX())
                buf.append(' ')
                buf.append(binop.getOperator())
                buf.append(' ')
                printExpr(binop.getY())
                if (!canSkipParenthesis) {
                    buf.append(')')
                }
            }

            Expression.Kind.COMPREHENSION -> {
                val comp = expr as Comprehension
                buf.append(if (comp.isDict()) '{' else '[')
                printNode(comp.getBody()) // Expression or DictExpression.Entry
                for (clause in comp.getClauses()) {
                    buf.append(' ')
                    if (clause is Comprehension.For) {
                        buf.append("for ")
                        printExpr(clause.getVars())
                        buf.append(" in ")
                        printExpr(clause.getIterable())
                    } else {
                        val ifClause = clause as Comprehension.If
                        buf.append("if ")
                        printExpr(ifClause.getCondition())
                    }
                }
                buf.append(if (comp.isDict()) '}' else ']')
            }

            Expression.Kind.CONDITIONAL -> {
                val cond = expr as ConditionalExpression
                printExpr(cond.getThenCase())
                buf.append(" if ")
                printExpr(cond.getCondition())
                buf.append(" else ")
                printExpr(cond.getElseCase())
            }

            Expression.Kind.DICT_EXPR -> {
                val dictexpr = expr as DictExpression
                buf.append("{")
                var sep = ""
                for (entry in dictexpr.getEntries()) {
                    buf.append(sep)
                    printDictEntry(entry)
                    sep = ", "
                }
                buf.append("}")
            }

            Expression.Kind.DOT -> {
                val dot = expr as DotExpression
                printExpr(dot.getObject())
                buf.append('.')
                printExpr(dot.getField())
            }

            Expression.Kind.CALL -> {
                val call = expr as CallExpression
                printExpr(call.getFunction())
                buf.append('(')
                var sep = ""
                for (arg in call.getArguments()) {
                    buf.append(sep)
                    printArgument(arg)
                    sep = ", "
                }
                buf.append(')')
            }

            Expression.Kind.CAST -> {
                val cast = expr as CastExpression
                buf.append("cast(")
                printExpr(cast.getType(),  /* canSkipParenthesis= */true)
                buf.append(", ")
                printExpr(cast.getValue(),  /* canSkipParenthesis= */true)
                buf.append(')')
            }

            Expression.Kind.ELLIPSIS -> {
                buf.append("...")
            }

            Expression.Kind.IDENTIFIER -> buf.append((expr as Identifier).getName())
            Expression.Kind.INDEX -> {
                val index = expr as IndexExpression
                printExpr(index.getObject())
                buf.append('[')
                printExpr(index.getKey())
                buf.append(']')
            }

            Expression.Kind.INT_LITERAL -> {
                buf.append((expr as IntLiteral).getValue())
            }

            Expression.Kind.ISINSTANCE -> {
                val isinstance = expr as IsInstanceExpression
                buf.append("isinstance(")
                printExpr(isinstance.getValue(),  /* canSkipParenthesis= */true)
                buf.append(", ")
                printExpr(isinstance.getType(),  /* canSkipParenthesis= */true)
                buf.append(')')
            }

            Expression.Kind.FLOAT_LITERAL -> {
                buf.append((expr as FloatLiteral).getValue())
            }

            Expression.Kind.LAMBDA -> {
                val lambda = expr as LambdaExpression
                buf.append("lambda")
                var sep = " "
                for (param in lambda.getParameters()) {
                    buf.append(sep)
                    sep = ", "
                    printParameter(param)
                }
                buf.append(": ")
                printExpr(lambda.getBody())
            }

            Expression.Kind.LIST_EXPR -> {
                val list = expr as ListExpression
                buf.append(if (list.isTuple()) '(' else '[')
                var sep = ""
                for (e in list.getElements()) {
                    buf.append(sep)
                    printExpr(e, true)
                    sep = ", "
                }
                if (list.isTuple() && list.getElements().size == 1) {
                    buf.append(',')
                }
                buf.append(if (list.isTuple()) ')' else ']')
            }

            Expression.Kind.SLICE -> {
                val slice = expr as SliceExpression
                printExpr(slice.getObject())
                buf.append('[')
                // The first separator colon is unconditional.
                // The second separator appears only if step is printed.
                if (slice.getStart() != null) {
                    printExpr(slice.getStart()!!)
                }
                buf.append(':')
                if (slice.getStop() != null) {
                    printExpr(slice.getStop()!!)
                }
                if (slice.getStep() != null) {
                    buf.append(':')
                    printExpr(slice.getStep()!!)
                }
                buf.append(']')
            }

            Expression.Kind.STRING_LITERAL -> {
                val literal = expr as StringLiteral
                val value = literal.getValue()
                printStringLiteral(buf, value)
            }

            Expression.Kind.UNARY_OPERATOR -> {
                val unop = expr as UnaryOperatorExpression
                // TODO(bazel-team): print minimal number of parentheses
                buf.append(if (unop.getOperator() == TokenKind.NOT) "not " else unop.getOperator().toString())
                if (!canSkipParenthesis) {
                    buf.append('(')
                }
                printExpr(unop.getX())
                if (!canSkipParenthesis) {
                    buf.append(')')
                }
            }

            Expression.Kind.TYPE_APPLICATION -> {
                val typeApplication = expr as TypeApplication
                printExpr(typeApplication.getConstructor())
                buf.append('[')
                var sep = ""
                for (arg in typeApplication.getArguments()) {
                    buf.append(sep)
                    printExpr(arg, true)
                    sep = ", "
                }
                buf.append(']')
            }
        }
    }

    companion object {
        /** Appends the Starlark repr form of a string value to the buffer.  */
        fun printStringLiteral(buf: StringBuilder, value: String) {
            // TODO(adonovan): record the raw text of string (and integer) literals
            // so that we can use the syntax tree for source modification tools.
            // However, that may come with a memory cost until we start compiling
            // (at which point the cost is only transient).
            // For now, just simulate the behavior of repr(str).
            buf.append('"')
            for (i in 0..<value.length) {
                val c = value.get(i)
                when (c) {
                    '"' -> buf.append("\\\"")
                    '\\' -> buf.append("\\\\")
                    '\r' -> buf.append("\\r")
                    '\n' -> buf.append("\\n")
                    '\t' -> buf.append("\\t")
                    else -> {
                        // The Starlark spec (and lexer) are far from complete here,
                        // and it's hard to come up with a clean semantics for
                        // string escapes that serves Java (UTF-16) and Go (UTF-8).
                        // Clearly string literals should not contain non-printable
                        // characters. For now we'll continue to pretend that all
                        // non-printables are < 32, but this obviously false.
                        if (c.code < 32) {
                            buf.append(String.format("\\x%02x", c.code))
                        } else {
                            buf.append(c)
                        }
                    }
                }
            }
            buf.append('"')
        }
    }
}
