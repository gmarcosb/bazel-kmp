// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.LabelSyntaxException

/** Definition of the functions used in VENDOR.bazel file.  */
@com.google.devtools.build.docgen.annot.GlobalMethods(environment = com.google.devtools.build.docgen.annot.GlobalMethods.Environment.VENDOR)
class VendorFileGlobals private constructor() {
    @net.starlark.java.annot.StarlarkMethod(
        name = "ignore",
        doc = ("Ignore this repo from vendoring. Bazel will never vendor it or use the corresponding"
                + " directory (if exists) while building in vendor mode."),
        extraPositionals = net.starlark.java.annot.Param(
            name = "args",
            doc = "The canonical repo names of the repos to ignore."
        ),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun ignore(args: net.starlark.java.eval.Tuple?, thread: net.starlark.java.eval.StarlarkThread) {
        val context: VendorThreadContext = VendorThreadContext.Companion.fromOrFail(thread, "ignore()")
        for (repoName in net.starlark.java.eval.Sequence.cast<String>(args, String::class.java, "args")) {
            context.addIgnoredRepo(getRepositoryName(repoName))
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "pin",
        doc = ("Pin the contents of this repo under the vendor directory. Bazel will not update this"
                + " repo while vendoring, and will use the vendored source as if there is a"
                + " --override_repository flag when building in vendor mode"),
        extraPositionals = net.starlark.java.annot.Param(
            name = "args",
            doc = "The canonical repo names of the repos to pin."
        ),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun pin(args: net.starlark.java.eval.Tuple?, thread: net.starlark.java.eval.StarlarkThread) {
        val context: VendorThreadContext = VendorThreadContext.Companion.fromOrFail(thread, "pin()")
        for (repoName in net.starlark.java.eval.Sequence.cast<String>(args, String::class.java, "args")) {
            context.addPinnedRepo(getRepositoryName(repoName))
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun getRepositoryName(repoName: String): RepositoryName {
        var repoName = repoName
        if (!repoName.startsWith("@@")) {
            throw net.starlark.java.eval.Starlark.errorf("the canonical repository name must start with `@@`")
        }
        try {
            repoName = repoName.substring(2)
            return RepositoryName.create(repoName)
        } catch (e: LabelSyntaxException) {
            throw net.starlark.java.eval.Starlark.errorf("Invalid canonical repo name: %s", e.getMessage())
        }
    }

    companion object {
        val INSTANCE: VendorFileGlobals = VendorFileGlobals()
    }
}
