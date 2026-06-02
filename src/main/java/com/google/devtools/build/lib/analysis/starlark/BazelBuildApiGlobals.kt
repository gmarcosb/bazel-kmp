// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.analysis.starlark.StarlarkLateBoundDefault
import com.google.devtools.build.lib.analysis.starlark.StarlarkLateBoundDefault.InvalidConfigurationFieldException
import com.google.devtools.build.lib.cmdline.BazelModuleContext
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.packages.BzlInitThreadContext
import com.google.devtools.build.lib.packages.BzlVisibility
import com.google.devtools.build.lib.packages.PackageSpecification
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkBuildApiGlobals

/**
 * Bazel implementation of [StarlarkBuildApiGlobals]: a collection of global Starlark build
 * API functions that belong in the global namespace.
 */
// TODO(bazel-team): Consider renaming this file BzlGlobals for consistency with BuildGlobals.
// Maybe wait until after eliminating the StarlarkBuildApiGlobals interface along with the rest of
// the starlarkbuildapi/ dir.
class BazelBuildApiGlobals : StarlarkBuildApiGlobals {
    @Throws(net.starlark.java.eval.EvalException::class)
    override fun visibility(value: Any, thread: net.starlark.java.eval.StarlarkThread) {
        // Confirm load visibility is enabled. We manually check the experimental flag here because
        // StarlarkMethod.enableOnlyWithFlag doesn't work for top-level builtins.
        if (!thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_BZL_VISIBILITY)) {
            throw net.starlark.java.eval.Starlark.errorf("Use of `visibility()` requires --experimental_bzl_visibility")
        }

        // Fail if we're not initializing a .bzl module
        val context: BzlInitThreadContext = BzlInitThreadContext.fromOrFail(thread, "visibility()")
        // Fail if we're not called from the top level. (We prohibit calling visibility() from within
        // helper functions because it's more magical / less readable, and it makes it more difficult
        // for static tooling to mechanically find and modify visibility() declarations.)
        val callStack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?> =
            thread.getCallStack()
        if (!(callStack.size == 2 && callStack.get(0).name == net.starlark.java.eval.StarlarkThread.TOP_LEVEL
                    && callStack.get(1).name == "visibility")
        ) {
            throw net.starlark.java.eval.Starlark.errorf(
                "load visibility may only be set at the top level, not inside a function"
            )
        }

        // Fail if the module's visibility is already set.
        if (context.getBzlVisibility() != null) {
            throw net.starlark.java.eval.Starlark.errorf("load visibility may not be set more than once")
        }

        val moduleContext: BazelModuleContext = BazelModuleContext.ofInnermostBzlOrThrow(thread)
        val repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping? = moduleContext.repoMapping()

        val repo: RepositoryName? = context.getBzlFile().getRepository()
        val specs: com.google.common.collect.ImmutableList<PackageSpecification?>?
        if (value is String) {
            // `visibility("public")`, `visibility("private")`, visibility("//pkg")
            specs =
                com.google.common.collect.ImmutableList.of<PackageSpecification?>(
                    PackageSpecification.fromStringForBzlVisibility(repoMapping, repo, value)
                )
        } else if (value is net.starlark.java.eval.StarlarkList<*>) {
            // `visibility(["//pkg1", "//pkg2", ...])`
            val specStrings: MutableList<String?> =
                net.starlark.java.eval.Sequence.cast<String?>(value, String::class.java, "visibility list")
            val specsBuilder: com.google.common.collect.ImmutableList.Builder<PackageSpecification?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<PackageSpecification?>(specStrings.size)
            for (specString in specStrings) {
                val spec: PackageSpecification =
                    PackageSpecification.fromStringForBzlVisibility(repoMapping, repo, specString)
                specsBuilder.add(spec)
            }
            specs = specsBuilder.build()
        } else {
            throw net.starlark.java.eval.Starlark.errorf(
                "Invalid visibility: got '%s', want string or list of strings",
                net.starlark.java.eval.Starlark.type(value)
            )
        }
        context.setBzlVisibility(BzlVisibility.of(specs))
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun configurationField(
        fragment: String?, name: String?, thread: net.starlark.java.eval.StarlarkThread
    ): StarlarkLateBoundDefault<*> {
        val context: BzlInitThreadContext = BzlInitThreadContext.fromOrFail(thread, "configuration_field()")
        val fragmentClass: java.lang.Class<*>? = context.getFragmentNameToClass().get(fragment)
        if (fragmentClass == null) {
            throw net.starlark.java.eval.Starlark.errorf("invalid configuration fragment name '%s'", fragment)
        }
        try {
            return StarlarkLateBoundDefault.forConfigurationField(
                fragmentClass, name, context.getToolsRepository()
            )
        } catch (exception: InvalidConfigurationFieldException) {
            throw net.starlark.java.eval.EvalException(exception)
        }
    }
}
