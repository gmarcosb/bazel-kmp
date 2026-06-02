// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.starlark

import com.google.common.collect.ImmutableList
import com.google.devtools.build.docgen.annot.DocCategory
import com.google.devtools.build.lib.bazel.repository.RepositoryFunctionException
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkBaseExternalContext.ShouldWatch
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.vfs.Path
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.skyframe.SkyFunctionException.Transience
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.*
import java.io.IOException

/**
 * A Path object to be used in repo rules and module extensions.
 * 
 * 
 * This path object enable non-hermetic operations from Starlark and should not be returned by
 * something other than a StarlarkBaseExternalContext.
 */
@ThreadSafety.Immutable
@StarlarkBuiltin(
    name = "path",
    category = DocCategory.BUILTIN,
    doc = "A structure representing a file to be used inside a repository."
)
class StarlarkPath internal constructor(private val ctx: StarlarkBaseExternalContext, @kotlin.jvm.JvmField val path: Path) : StarlarkValue {
    override fun isImmutable(): Boolean {
        return true // immutable and Starlark-hashable
    }

    override fun equals(obj: Any?): Boolean {
        return (obj is StarlarkPath) && path == obj.path
    }

    override fun hashCode(): Int {
        return path.hashCode()
    }

    @get:StarlarkMethod(
        name = "basename",
        structField = true,
        doc = "A string giving the basename of the file."
    )
    val basename: String?
        get() = path.getBaseName()

    @StarlarkMethod(
        name = "readdir", doc = """
          Returns the list of entries in the directory denoted by this path. Each entry is a <code>path</code> object itself.
          
          """.trimIndent(), parameters = [Param(
            name = "watch", defaultValue = "'auto'", positional = false, named = true, doc = """
                whether Bazel should watch the list of entries in this directory and refetch the repository or re-evaluate the module extension next time when any changes are detected. Changes to detect include entry creation, deletion, and renaming. Note that this doesn't watch the <em>contents</em> of any entries in the directory.<p>Can be the string 'yes', 'no', or 'auto'. If set to 'auto', Bazel will only watch this directory when it is legal to do so (see <a href="repository_ctx.html#watch"><code>repository_ctx.watch()</code></a> docs for more information).
                
                """.trimIndent()
        )]
    )
    @Throws(EvalException::class, RepositoryFunctionException::class, InterruptedException::class)
    fun readdir(watch: String): ImmutableList<StarlarkPath?> {
        if (!this.isDir) {
            throw Starlark.errorf("can't readdir(), not a directory: %s", path)
        }
        ctx.maybeWatchDirents(path, ShouldWatch.Companion.fromString(watch))
        try {
            val builder = ImmutableList.builder<StarlarkPath?>()
            for (p in path.getDirectoryEntries()) {
                builder.add(StarlarkPath(ctx, p))
            }
            return builder.build()
        } catch (e: IOException) {
            throw RepositoryFunctionException(e, Transience.TRANSIENT)
        }
    }

    @get:StarlarkMethod(
        name = "dirname",
        structField = true,
        allowReturnNones = true,
        doc = "The parent directory of this file, or None if this file does not have a parent."
    )
    val dirname: StarlarkPath?
        get() {
            val parentPath = path.getParentDirectory()
            return if (parentPath == null) null else StarlarkPath(ctx, parentPath)
        }

    @StarlarkMethod(
        name = "get_child",
        doc = "Returns the path obtained by joining this path with the given relative paths.",
        extraPositionals = Param(
            name = "relative_paths", doc = """
                  Zero or more relative path strings to append to this path with path separators added as needed.
                  
                  """.trimIndent()
        )
    )
    @Throws(EvalException::class)
    fun getChild(relativePaths: Tuple?): StarlarkPath {
        return StarlarkPath(
            ctx,
            path.getRelative(
                java.lang.String.join(
                    Character.toString(PathFragment.SEPARATOR_CHAR),
                    Sequence.cast<String?>(relativePaths, String::class.java, "relative_paths")
                )
            )
        )
    }

    @StarlarkMethod(
        name = "exists", structField = true, doc = """
          Returns true if the file or directory denoted by this path exists.<p>Note that accessing this field does <em>not</em> cause the path to be watched. If you'd like the repo rule or module extension to be sensitive to the path's existence, use the <code>watch()</code> method on the context object.
          
          """.trimIndent()
    )
    fun exists(): Boolean {
        return path.exists()
    }

    @get:StarlarkMethod(
        name = "is_dir", structField = true, doc = """
          Returns true if this path points to a directory.<p>Note that accessing this field does <em>not</em> cause the path to be watched. If you'd like the repo rule or module extension to be sensitive to whether the path is a directory or a file, use the <code>watch()</code> method on the context object.
          
          """.trimIndent()
    )
    val isDir: Boolean
        get() = path.isDirectory()

    @StarlarkMethod(
        name = "realpath", structField = true, doc = """
          Returns the canonical path for this path by repeatedly replacing all symbolic links with their referents.
          
          """.trimIndent()
    )
    @Throws(IOException::class)
    fun realpath(): StarlarkPath {
        return StarlarkPath(ctx, path.resolveSymbolicLinks())
    }

    override fun toString(): String {
        return path.toString()
    }

    override fun str(printer: Printer, semantics: StarlarkSemantics?) {
        printer.append(path.toString())
    }

    override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
        printer.repr(path.toString(), semantics)
    }
}
