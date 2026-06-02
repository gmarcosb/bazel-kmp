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
package com.google.devtools.build.lib.cmdline

import com.google.auto.value.AutoValue
import com.google.auto.value.AutoValue.CopyAnnotations
import com.google.devtools.build.lib.cmdline.BazelModuleKey
import com.google.devtools.build.lib.cmdline.Label.PackageContext

/**
 * BazelModuleContext records Bazel-specific information associated with a .bzl [ ].
 * 
 * 
 * Maintainer's note: This object is determined prior to the module's evaluation in
 * BzlLoadFunction. It is saved in the `Module` as [client data][Module.getClientData].
 * The `Module` used during .bzl compilation is separate and uses [BazelCompileContext]
 * as client data. For data that is computed after the module's evaluation and which need not be
 * exposed outside the module-loading machinery, consider [BzlLoadValue].
 */
// Immutability is useful because this object is retrievable from a Module and therefore from a
// BzlLoadValue.
@AutoValue
abstract class BazelModuleContext {
    /** Label associated with the Starlark [net.starlark.java.eval.Module].  */
    fun label(): com.google.devtools.build.lib.cmdline.Label? {
        return key().getLabel()
    }

    /** [com.google.devtools.build.lib.skyframe.BzlLoadValue.Key] used to create the module.  */
    abstract fun key(): BazelModuleKey?

    /** The repository mapping applicable to the repo where the .bzl file is located in.  */
    abstract fun repoMapping(): com.google.devtools.build.lib.cmdline.RepositoryMapping?

    /** Returns the name of the module's .bzl file, as provided to the parser.  */
    abstract fun filename(): String?

    /**
     * Returns a list of modules loaded by this .bzl file, in source order.
     * 
     * 
     * By traversing these modules' loads, it is possible to reconstruct the complete load DAG (not
     * including `@_builtins` .bzl files). See [.visitLoadGraphRecursively].
     */
    abstract fun loads(): com.google.common.collect.ImmutableList<net.starlark.java.eval.Module>?

    /**
     * Consumes labels of loaded Starlark files during a call to [.visitLoadGraphRecursively].
     * 
     * 
     * The value returned by [.visit] determines whether the traversal should continue (true)
     * or backtrack (false). Using a method reference to [Set.add] is a convenient way to
     * aggregate Starlark files while pruning branches when a file was already seen. The same set may
     * be reused across multiple calls to [.visitLoadGraphRecursively] in order to prune the
     * graph at files already seen during a previous traversal.
     */
    fun interface LoadGraphVisitor<E1 : java.lang.Exception?, E2 : java.lang.Exception?> {
        /**
         * Processes a single loaded Starlark file and determines whether to recurse into that file's
         * loads.
         * 
         * @return true if the visitation should recurse into the loads of the given file
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(E1::class, E2::class)
        fun visit(load: com.google.devtools.build.lib.cmdline.Label?): Boolean
    }

    /**
     * Transitive digest of the .bzl file of the [net.starlark.java.eval.Module] itself and all
     * files it transitively loads.
     */
    @CopyAnnotations
    abstract fun bzlTransitiveDigest(): ByteArray?

    /**
     * Returns a map from the module's global variable names to Sphinx autodoc-style doc comments
     * associated with the variable's declarations; global variables without a doc comment are not
     * included in the map.
     * 
     * 
     * Intended only for use by documentation extraction machinery. Comments - including doc
     * comments - must not affect Starlark evaluation; use of this method during the evaluation of a
     * Starlark builtin is almost certainly an error.
     */
    @kotlin.jvm.JvmField
    abstract val docCommentsMap: com.google.common.collect.ImmutableMap<String?, net.starlark.java.syntax.DocComments?>?

    /**
     * Returns the list of doc comments not associated with any global variable in the module.
     * 
     * 
     * Intended only for use by documentation extraction machinery. Comments - including doc
     * comments - must not affect Starlark evaluation; use of this method during the evaluation of a
     * Starlark builtin is almost certainly an error.
     */
    @kotlin.jvm.JvmField
    abstract val unusedDocCommentLines: com.google.common.collect.ImmutableList<net.starlark.java.syntax.Comment?>?

    /**
     * Returns a label for a [net.starlark.java.eval.Module].
     * 
     * 
     * This is a user-facing value and we rely on this string to be a valid label for the [ ] (and that only).
     */
    override fun toString(): String {
        return label().toString()
    }

    fun packageContext(): PackageContext {
        return PackageContext.Companion.of(label().getPackageIdentifier(), repoMapping())
    }

    companion object {
        /** Performs an online visitation of the load graph rooted at a given list of loads.  */
        @Throws(E1::class, E2::class)
        fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?> visitLoadGraphRecursively(
            loads: Iterable<net.starlark.java.eval.Module>, visitor: LoadGraphVisitor<E1?, E2?>
        ) {
            for (module in loads) {
                val ctx = of(module)
                if (visitor.visit(ctx!!.label())) {
                    Companion.visitLoadGraphRecursively<E1?, E2?>(ctx.loads(), visitor)
                }
            }
        }

        /**
         * Returns the `BazelModuleContext` associated with the specified Starlark module, or null
         * if there isn't any.
         */
        fun of(m: net.starlark.java.eval.Module): BazelModuleContext? {
            val data: Any? = m.getClientData()
            if (data is BazelModuleContext) {
                return data
            } else {
                return null
            }
        }

        /**
         * Returns the `BazelModuleContext` associated with the innermost Starlark function on the
         * call stack of the given thread.
         * 
         * 
         * Usage note: Following the example of [Module.ofInnermostEnclosingStarlarkFunction],
         * the name of this method is intentionally clumsy to remind the reader that introspecting the
         * current module is a dubious practice. We went with a different name here because the null
         * tolerance of the two methods differs.
         * 
         * @throws NullPointerException if there is no currently executing Starlark function, or the
         * innermost Starlark function's module has no `BazelModuleContext`.
         */
        fun ofInnermostBzlOrThrow(thread: net.starlark.java.eval.StarlarkThread?): BazelModuleContext {
            val m: net.starlark.java.eval.Module =
                com.google.common.base.Preconditions.checkNotNull<net.starlark.java.eval.Module>(
                    net.starlark.java.eval.Module.ofInnermostEnclosingStarlarkFunction(thread)
                )
            return com.google.common.base.Preconditions.checkNotNull<BazelModuleContext>(of(m))
        }

        /**
         * Returns the `BazelModuleContext` associated with the innermost Starlark function on the
         * call stack of the given thread. If not present, throws `EvalException` with an error
         * message indicating that `what` can't be used in this Starlark environment.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        fun ofInnermostBzlOrFail(thread: net.starlark.java.eval.StarlarkThread?, what: String?): BazelModuleContext {
            var ctx: BazelModuleContext = null
            val m: net.starlark.java.eval.Module? =
                net.starlark.java.eval.Module.ofInnermostEnclosingStarlarkFunction(thread)
            if (m != null) {
                ctx = of(m)!!
            }
            if (ctx == null) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "%s can only be used during .bzl initialization (top-level evaluation)", what
                )
            }
            return ctx
        }

        fun create(
            key: BazelModuleKey?,
            repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?,
            filename: String?,
            loads: com.google.common.collect.ImmutableList<net.starlark.java.eval.Module?>?,
            bzlTransitiveDigest: ByteArray?,
            docCommentsMap: com.google.common.collect.ImmutableMap<String?, net.starlark.java.syntax.DocComments?>?,
            unusedDocCommentLines: com.google.common.collect.ImmutableList<net.starlark.java.syntax.Comment?>?
        ): BazelModuleContext {
            return AutoValue_BazelModuleContext(
                key,
                repoMapping,
                filename,
                loads,
                bzlTransitiveDigest,
                docCommentsMap,
                unusedDocCommentLines
            )
        }
    }
}
