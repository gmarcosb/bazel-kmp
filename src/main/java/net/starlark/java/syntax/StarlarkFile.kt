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
import java.util.*

/**
 * Syntax tree for a Starlark file, such as a Bazel BUILD or .bzl file.
 * 
 * 
 * Call [.parse] to parse a file. Parser errors are recorded in the syntax tree (see [ ][.errors]), which may be incomplete.
 */
class StarlarkFile private constructor(
    locs: FileLocations?,
    statements: ImmutableList<Statement?>,
    options: FileOptions?,
    comments: ImmutableList<Comment?>?,
    errors: MutableList<SyntaxError?>
) : Node(locs) {
    @kotlin.jvm.JvmField
    private val statements: ImmutableList<Statement?>
    private val options: FileOptions?
    @kotlin.jvm.JvmField
    private val comments: ImmutableList<Comment?>?
    val errors: MutableList<SyntaxError?> // appended to by Resolver

    // Map from global variable name to doc comments. Added to by Resolver.
    @kotlin.jvm.JvmField
    val docCommentsMap: MutableMap<String?, DocComments?> = LinkedHashMap<String?, DocComments?>()

    // set by resolver
    @kotlin.jvm.JvmField
    private var resolved: Resolver.Function? = null

    override fun getStartOffset(): Int {
        return 0
    }

    override fun getEndOffset(): Int {
        return locs.size()
    }

    init {
        this.statements = statements
        this.options = options
        this.comments = comments
        this.errors = errors
    }

    /**
     * Returns an unmodifiable view of the list of scanner, parser, and (perhaps) resolver errors
     * accumulated in this Starlark file.
     */
    fun errors(): MutableList<SyntaxError?> {
        return Collections.unmodifiableList<SyntaxError?>(errors)
    }

    /** Returns errors().isEmpty().  */
    fun ok(): Boolean {
        return errors.isEmpty()
    }

    /** Returns an (immutable, ordered) list of statements in this BUILD file.  */
    fun getStatements(): ImmutableList<Statement?> {
        return statements
    }

    /** Returns an (immutable, ordered) list of comments in this BUILD file.  */
    fun getComments(): ImmutableList<Comment?>? {
        return comments
    }

    override fun toString(): String {
        return "<StarlarkFile with " + statements.size() + " statements>"
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }

    fun setResolvedFunction(resolved: Resolver.Function?) {
        this.resolved = resolved
    }

    /**
     * Returns information about the implicit function containing the top-level statements of the
     * file. Set by the resolver.
     */
    fun getResolvedFunction(): Resolver.Function? {
        return resolved
    }

    /** Returns the options specified when parsing this file.  */
    fun getOptions(): FileOptions? {
        return options
    }

    /** Returns the name of this file, as specified to the parser.  */
    fun getName(): String? {
        return locs.file()
    }

    /** A ParseProfiler records the start and end times of parse operations.  */
    interface ParseProfiler {
        fun start(): Long

        fun end(profileStartNanos: Long, filename: String?)
    }

    companion object {
        /**
         * Parse a Starlark file.
         * 
         * 
         * A syntax tree is always returned, even in case of error. Errors are recorded in the tree.
         * Example usage:
         * 
         * <pre>
         * StarlarkFile file = StarlarkFile.parse(input, options);
         * if (!file.ok()) {
         * Event.replayEventsOn(handler, file.errors());
         * ...
         * }
        </pre> * 
         */
        /** Parse a Starlark file with default options.  */
        @kotlin.jvm.JvmStatic
        @kotlin.jvm.JvmOverloads
        fun parse(input: ParserInput?, options: FileOptions? = FileOptions.Companion.DEFAULT): StarlarkFile {
            val result: Parser.ParseResult = Parser.Companion.parseFile(input, options)
            return StarlarkFile(
                result.locs, result.statements, options, result.comments, result.errors
            )
        }

        /** Installs a global hook that will be notified of parse operations.  */
        fun setParseProfiler(p: ParseProfiler?) {
            Parser.Companion.profiler = p
        }
    }
}
