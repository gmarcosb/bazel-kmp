// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.RepoThreadContext

/** Definition of the `repo()` function used in REPO.bazel files.  */
@com.google.devtools.build.docgen.annot.GlobalMethods(environment = com.google.devtools.build.docgen.annot.GlobalMethods.Environment.REPO)
class RepoFileGlobals private constructor() {
    @net.starlark.java.annot.StarlarkMethod(
        name = "ignore_directories",
        doc = ("The list of directories to ignore in this repository. <p>This function takes a list"
                + " of strings and a directory is ignored if any of the given strings matches its"
                + " repository-relative path according to the semantics of the <code>glob()</code>"
                + " function. This function can be used to ignore directories that are implementation"
                + " details of source control systems, output files of other build systems, etc."),
        useStarlarkThread = true,
        parameters = [net.starlark.java.annot.Param(
            name = "dirs",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            )]
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun ignoreDirectories(dirsUnchecked: Iterable<*>?, thread: net.starlark.java.eval.StarlarkThread) {
        val dirs: net.starlark.java.eval.Sequence<String?>? =
            net.starlark.java.eval.Sequence.cast<String?>(dirsUnchecked, String::class.java, "dirs")
        val context: RepoThreadContext = RepoThreadContext.Companion.fromOrFail(thread, "repo()")

        if (context.isIgnoredDirectoriesSet()) {
            throw net.starlark.java.eval.EvalException("'ignored_directories()' can only be called once")
        }

        context.setIgnoredDirectories(dirs)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "repo", doc = ("Declares metadata that applies to every rule in the repository. It must be called at "
                + "most once per REPO.bazel file. If called, it must be the first call in the "
                + "REPO.bazel file."), extraKeywords = net.starlark.java.annot.Param(
            name = "kwargs", doc = ("The <code>repo()</code> function accepts exactly the same arguments as the "
                    + "<a href=\"\${link functions}#package\"><code>package()</code></a> function "
                    + "in BUILD files.")
        ), useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun repoCallable(kwargs: MutableMap<String?, Any?>, thread: net.starlark.java.eval.StarlarkThread) {
        val context: RepoThreadContext = RepoThreadContext.Companion.fromOrFail(thread, "repo()")
        if (context.isRepoFunctionCalled()) {
            throw net.starlark.java.eval.Starlark.errorf("'repo' can only be called once in the REPO.bazel file")
        }

        if (context.isIgnoredDirectoriesSet()) {
            throw net.starlark.java.eval.Starlark.errorf("if repo() is called, it must be called before any other functions")
        }

        if (kwargs.isEmpty()) {
            throw net.starlark.java.eval.Starlark.errorf("at least one argument must be given to the 'repo' function")
        }

        context.setPackageArgsMap(kwargs)
    }

    companion object {
        val INSTANCE: RepoFileGlobals = RepoFileGlobals()
    }
}
