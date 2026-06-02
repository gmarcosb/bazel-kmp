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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.PackageArgs

/**
 * Utility class encapsulating the standard definition of the `package()` function of BUILD
 * files.
 */
@com.google.devtools.build.docgen.annot.GlobalMethods(environment = com.google.devtools.build.docgen.annot.GlobalMethods.Environment.BUILD)
class PackageCallable protected constructor() {
    @net.starlark.java.annot.StarlarkMethod(
        name = "package",
        doc = ("Declares metadata that applies to every rule in the package. It must be called at "
                + "most once within a package (BUILD file). If called, it should be the first call "
                + "in the BUILD file, right after the <code>load()</code> statements."),
        extraKeywords = net.starlark.java.annot.Param(
            name = "kwargs", doc = "See the <a href=\"\${link functions}#package\"><code>package()</code></a> "
                    + "function in the Build Encyclopedia for applicable arguments."
        ),
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun packageCallable(kwargs: MutableMap<String?, Any?>, thread: net.starlark.java.eval.StarlarkThread?): Any? {
        // TODO(bazel-team): we should properly ban package() in legacy macros
        val pkgBuilder: com.google.devtools.build.lib.packages.Package.AbstractBuilder
        try {
            pkgBuilder =
                com.google.devtools.build.lib.packages.Package.AbstractBuilder.Companion.fromOrFailAllowBuildOnly(
                    thread,
                    "package()"
                )
        } catch (unused: net.starlark.java.eval.EvalException) {
            // The eval exception thrown by fromOrFailAllowBuildOnly() advises the user that using
            // package() in legacy macros is ok. We don't want to give that advice.
            throw net.starlark.java.eval.Starlark.errorf("package() can only be used while evaluating a BUILD file")
        }
        if (pkgBuilder.isPackageFunctionUsed()) {
            throw net.starlark.java.eval.EvalException("'package' can only be used once per BUILD file")
        }
        pkgBuilder.setPackageFunctionUsed()

        if (kwargs.isEmpty()) {
            throw net.starlark.java.eval.EvalException("at least one argument must be given to the 'package' function")
        }

        val pkgArgsBuilder: com.google.devtools.build.lib.packages.PackageArgs.Builder = PackageArgs.Companion.builder()
        for (kwarg in kwargs.entrySet()) {
            val name: String = kwarg.getKey()
            val rawValue: Any? = kwarg.getValue()
            processParam(name, rawValue, pkgBuilder, pkgArgsBuilder)
        }
        pkgBuilder.mergePackageArgsFrom(pkgArgsBuilder)
        return net.starlark.java.eval.Starlark.NONE
    }

    /**
     * Handles one parameter. Subclasses can add new parameters by overriding this method and falling
     * back on the super method when the parameter does not match.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    protected fun processParam(
        name: String,
        rawValue: Any?,
        pkgBuilder: com.google.devtools.build.lib.packages.Package.AbstractBuilder,
        pkgArgsBuilder: com.google.devtools.build.lib.packages.PackageArgs.Builder?
    ) {
        PackageArgs.Companion.processParam(
            name,
            rawValue,
            "package() argument '" + name + "'",
            pkgBuilder.getLabelConverter(),
            pkgArgsBuilder
        )
    }

    companion object {
        val INSTANCE: PackageCallable = PackageCallable()
    }
}
