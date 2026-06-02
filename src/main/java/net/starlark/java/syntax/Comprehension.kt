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

import com.google.common.collect.ImmutableList

/**
 * Syntax node for list and dict comprehensions.
 * 
 * 
 * A comprehension contains one or more clauses, e.g. [a+d for a in b if c for d in e] contains
 * three clauses: "for a in b", "if c", "for d in e". For and If clauses can happen in any order,
 * except that the first one has to be a For.
 * 
 * 
 * The code above can be expanded as:
 * 
 * <pre>
 * for a in b:
 * if c:
 * for d in e:
 * result.append(a+d)
</pre> * 
 * 
 * result is initialized to [] (list) or {} (dict) and is the return value of the whole expression.
 */
class Comprehension internal constructor(
    locs: FileLocations?,
    isDict: Boolean,
    lbracketOffset: Int,
    body: Node?,
    clauses: ImmutableList<Clause?>?,
    rbracketOffset: Int
) : Expression(locs, Kind.COMPREHENSION) {
    /** For or If  */
    abstract class Clause internal constructor(locs: FileLocations?) : Node(locs)

    /** A for clause in a comprehension, e.g. "for a in b" in the example above.  */
    class For internal constructor(locs: FileLocations?, forOffset: Int, vars: Expression?, iterable: Expression) :
        Clause(locs) {
        private val forOffset: Int
        @kotlin.jvm.JvmField
        val vars: Expression?
        @kotlin.jvm.JvmField
        val iterable: Expression

        init {
            this.forOffset = forOffset
            this.vars = vars
            this.iterable = iterable
        }

        override fun getStartOffset(): Int {
            return forOffset
        }

        override fun getEndOffset(): Int {
            return iterable.getEndOffset()
        }

        override fun accept(visitor: NodeVisitor) {
            visitor.visit(this)
        }
    }

    /** A if clause in a comprehension, e.g. "if c" in the example above.  */
    class If internal constructor(locs: FileLocations?, ifOffset: Int, condition: Expression) : Clause(locs) {
        private val ifOffset: Int
        val condition: Expression

        init {
            this.ifOffset = ifOffset
            this.condition = condition
        }

        override fun getStartOffset(): Int {
            return ifOffset
        }

        override fun getEndOffset(): Int {
            return condition.getEndOffset()
        }

        override fun accept(visitor: NodeVisitor) {
            visitor.visit(this)
        }
    }

    val isDict: Boolean // {k: v for vars in iterable}
    private val lbracketOffset: Int

    /**
     * Returns the loop body: an expression for a list comprehension, or a DictExpression.Entry for a
     * dict comprehension.
     */
    val body: Node? // Expression or DictExpression.Entry
    @kotlin.jvm.JvmField
    val clauses: ImmutableList<Clause?>?
    private val rbracketOffset: Int

    init {
        this.isDict = isDict
        this.lbracketOffset = lbracketOffset
        this.body = body
        this.clauses = clauses
        this.rbracketOffset = rbracketOffset
    }

    override fun getStartOffset(): Int {
        return lbracketOffset
    }

    override fun getEndOffset(): Int {
        return rbracketOffset + 1
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
