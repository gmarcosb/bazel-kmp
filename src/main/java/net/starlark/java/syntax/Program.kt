// Copyright 2020 The Bazel Authors. All rights reserved.
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
import kotlin.collections.HashSet

/**
 * An opaque, executable representation of a valid Starlark program. Programs may
 * [eventually---TODO(adonovan)] be efficiently serialized and deserialized without parsing and
 * recompiling.
 */
class Program private constructor(
    options: FileOptions?,
    body: Resolver.Function,
    loads: ImmutableList<String?>,
    loadLocations: ImmutableList<Location>,
    docCommentsMap: ImmutableMap<String?, DocComments?>?,
    unusedDocCommentLines: ImmutableList<Comment?>?,
    typeTable: TypeTable?
) {
    private val options: FileOptions?
    @kotlin.jvm.JvmField
    private val body: Resolver.Function
    private val loads: ImmutableList<String?>
    private val loadLocations: ImmutableList<Location>
    @kotlin.jvm.JvmField
    private val docCommentsMap: ImmutableMap<String?, DocComments?>?
    @kotlin.jvm.JvmField
    private val unusedDocCommentLines: ImmutableList<Comment?>?

    // Set by withTypeTable()
    @kotlin.jvm.JvmField
    private val typeTable: TypeTable?

    init {
        Preconditions.checkArgument(
            loads.size == loadLocations.size, "each load must have a corresponding location"
        )

        // TODO(adonovan): compile here.
        this.options = options
        this.body = body
        this.loads = loads
        this.loadLocations = loadLocations
        this.docCommentsMap = docCommentsMap
        this.unusedDocCommentLines = unusedDocCommentLines
        this.typeTable = typeTable
    }

    /** Returns a copy of this program with the specified type table.  */
    fun withTypeTable(typeTable: TypeTable?): Program {
        return Program(
            this.options,
            this.body,
            this.loads,
            this.loadLocations,
            this.docCommentsMap,
            this.unusedDocCommentLines,
            typeTable
        )
    }

    /** Returns the file options under which this program was parsed and compiled.  */
    fun getOptions(): FileOptions? {
        return options
    }

    // TODO(adonovan): eliminate once Eval no longer needs access to syntax.
    fun getResolvedFunction(): Resolver.Function {
        return body
    }

    /** Returns the file name of this compiled program.  */
    fun getFilename(): String? {
        return body.getLocation().file()
    }

    /** Returns the list of load strings of this compiled program, in source order.  */
    fun getLoads(): ImmutableList<String?> {
        return loads
    }

    /*** Returns the location of the ith load (see [.getLoads]).  */
    fun getLoadLocation(i: Int): Location {
        return loadLocations.get(i)
    }

    /**
     * Returns a map from global variable names to Sphinx autodoc-style doc comments associated with
     * the variable's declarations; global variables without a doc comment are not included in the
     * map.
     */
    fun getDocCommentsMap(): ImmutableMap<String?, DocComments?>? {
        return docCommentsMap
    }

    /** Returns the list of doc comments not associated with any global variable.  */
    fun getUnusedDocCommentLines(): ImmutableList<Comment?>? {
        return unusedDocCommentLines
    }

    /**
     * Returns true if this program does not contain any top-level expressions that could mutate
     * collections (e.g., calls, index assignments, or augmented assignments).
     * 
     * 
     * This is a heuristic used by the evaluator to safely optimize collection literals (lists and
     * dicts) into compact, immutable implementations to save memory.
     */
    fun isMutationFreeAtTopLevel(): Boolean {
        return body.isMutationFreeAtTopLevel()
    }

    /**
     * Returns the static type table of this compiled program, or null if type resolution was not
     * performed.
     */
    fun getTypeTable(): TypeTable? {
        return typeTable
    }

    companion object {
        /**
         * Resolves a file syntax tree in the specified environment and compiles it to a Program. This
         * operation mutates the syntax tree by:
         * 
         * 
         *  * resolving identifiers to bindings,
         *  * resolving type information,
         *  * recording local variables, and
         *  * in case of error, appending to `file.errors()`.
         * 
         * 
         * @param loader A loader for processing load() statements; used by type tagging/checking; must be
         * specified if type tagging is enabled and the file contains load() statements.
         * @throws SyntaxError.Exception in case of resolution error, or if the syntax tree already
         * contained syntax scan/parse errors. Resolution errors are added to `file.errors()`.
         */
        @kotlin.jvm.JvmOverloads
        @Throws(SyntaxError.Exception::class)
        fun compileFile(
            file: StarlarkFile, env: Resolver.Module?, loader: TypeTagger.Loader? = null
        ): Program {
            Resolver.Companion.resolveFile(file, env)
            if (!file.ok()) {
                throw SyntaxError.Exception(file.errors())
            }

            // Extract load statements.
            val loads = ImmutableList.builder<String?>()
            val loadLocations = ImmutableList.builder<Location?>()
            for (stmt in file.getStatements()) {
                if (stmt is LoadStatement) {
                    val module = stmt.getImport().getValue()
                    loads.add(module)
                    loadLocations.add(stmt.getImport().getLocation())
                }
            }

            // Find unused doc comments.
            val docCommentsMap = ImmutableMap.copyOf<String?, DocComments?>(file.docCommentsMap)
            val usedDocCommentLines = HashSet<Comment?>()
            for (docComments in docCommentsMap.values) {
                usedDocCommentLines.addAll(docComments!!.getLines())
            }
            val unusedDocCommentLines =
                file.getComments().stream()
                    .filter { c: Comment? -> c!!.hasDocCommentPrefix() && !usedDocCommentLines.contains(c) }
                    .collect(ImmutableList.toImmutableList<Comment?>())

            return Program(
                file.getOptions(),
                file.getResolvedFunction()!!,
                loads.build(),
                loadLocations.build(),
                docCommentsMap,
                unusedDocCommentLines,  /* typeTable= */
                null
            )
        }

        /**
         * Resolves an expression syntax tree in the specified environment and compiles it to a Program.
         * This operation mutates the syntax tree. The `options` must match those used when parsing
         * expression.
         * 
         * @throws SyntaxError.Exception in case of resolution error.
         */
        @kotlin.jvm.JvmStatic
        @Throws(SyntaxError.Exception::class)
        fun compileExpr(expr: Expression?, module: Resolver.Module?, options: FileOptions?): Program {
            val body: Resolver.Function = Resolver.Companion.resolveExpr(expr, module, options)
            return Program(
                options,
                body,  /* loads= */
                ImmutableList.of<String?>(),  /* loadLocations= */
                ImmutableList.of<Location?>(),  /* docCommentsMap= */
                ImmutableMap.of<String?, DocComments?>(),  /* unusedDocCommentLines= */
                ImmutableList.of<Comment?>(),  /* typeTable= */
                null
            )
        }
    }
}
