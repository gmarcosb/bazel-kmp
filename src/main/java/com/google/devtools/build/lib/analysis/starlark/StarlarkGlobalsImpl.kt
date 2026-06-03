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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.analysis.RunEnvironmentInfo

/**
 * Sole implementation of [StarlarkGlobals].
 * 
 * 
 * The reason for the class-interface split is to allow [BazelStarlarkEnvironment] to
 * retrieve symbols defined and aggregated in the lib/analysis/ dir, without creating a dependency
 * from lib/packages/ to lib/analysis.
 */
class StarlarkGlobalsImpl private constructor() : StarlarkGlobals {
    private fun addCommonUtilToplevels(env: com.google.common.collect.ImmutableMap.Builder<String?, Any?>) {
        // Maintainer's note: Changes to this method are relatively high impact since it's sourced for
        // BUILD, .bzl, and even cquery environments.
        Starlark.addMethods(env, Depset.DepsetLibrary.INSTANCE)
        env.put("json", net.starlark.java.lib.json.Json.INSTANCE)
        env.put("proto", Proto.INSTANCE)
    }

    public override fun getUtilToplevels(): com.google.common.collect.ImmutableMap<String?, Any?> {
        // TODO(bazel-team): It's dubious that we include things like depset and select(), but not
        // struct() here.
        val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        addCommonUtilToplevels(env)
        Starlark.addMethods(env, SelectorList.SelectLibrary.INSTANCE)
        return env.buildOrThrow()
    }

    public override fun getUtilToplevelsForCquery(): com.google.common.collect.ImmutableMap<String?, Any?> {
        val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        addCommonUtilToplevels(env)
        env.put("struct", StructProvider.STRUCT)
        return env.buildOrThrow()
    }

    public override fun getFixedBuildFileToplevelsSharedWithNative(): com.google.common.collect.ImmutableMap<String?, Any?> {
        return StarlarkNativeModule.BINDINGS_FOR_BUILD_FILES
    }

    public override fun getFixedBuildFileToplevelsNotInNative(): com.google.common.collect.ImmutableMap<String?, Any?> {
        val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()

        env.putAll(getUtilToplevels())
        Starlark.addMethods(env, BuildGlobals.INSTANCE)

        return env.buildOrThrow()
    }

    public override fun getFixedBzlToplevels(): com.google.common.collect.ImmutableMap<String?, Any?> {
        val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()

        env.putAll(getUtilToplevels())

        Starlark.addMethods(env, BazelBuildApiGlobals()) // e.g. configuration_field
        Starlark.addMethods(env, StarlarkRuleClassFunctions()) // e.g. rule

        env.put("attr", StarlarkAttrModule())
        env.put("struct", StructProvider.STRUCT)
        env.put("OutputGroupInfo", OutputGroupInfo.Companion.STARLARK_CONSTRUCTOR)
        env.put("Actions", ActionsProvider.INSTANCE)
        env.put("DefaultInfo", DefaultInfo.Companion.PROVIDER)
        env.put("RunEnvironmentInfo", RunEnvironmentInfo.PROVIDER)
        env.put("MaterializedDepsInfo", MaterializedDepsInfo.Companion.PROVIDER)

        return env.buildOrThrow()
    }

    public override fun getSclToplevels(): com.google.common.collect.ImmutableMap<String?, Any?> {
        // TODO(bazel-team): We only want the visibility() symbol from BazelBuildApiGlobals, nothing
        // else, but Starlark#addMethods doesn't allow that kind of granularity, and the Starlark
        // interpreter doesn't provide any other way to turn a Java method definition into a
        // callable symbol. So we hack it by building the map of all symbols in that class and
        // retrieving just the one we want. The alternative of refactoring the class is more churn than
        // its worth, given the starlarkbuildapi/ split.
        val bazelBuildApiGlobalsSymbols: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        Starlark.addMethods(bazelBuildApiGlobalsSymbols, BazelBuildApiGlobals())
        val visibilitySymbol: Any? = bazelBuildApiGlobalsSymbols.buildOrThrow().get("visibility")

        val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        env.put("visibility", visibilitySymbol)
        env.put("struct", StructProvider.STRUCT)
        return env.buildOrThrow()
    }

    public override fun getModuleToplevels(): com.google.common.collect.ImmutableMap<String?, Any?> {
        val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        Starlark.addMethods(env, ModuleFileGlobals())
        return env.buildOrThrow()
    }

    public override fun getRepoToplevels(): com.google.common.collect.ImmutableMap<String?, Any?> {
        val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        Starlark.addMethods(env, RepoFileGlobals.INSTANCE)
        return env.buildOrThrow()
    }

    public override fun getVendorToplevels(): com.google.common.collect.ImmutableMap<String?, Any?> {
        val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        Starlark.addMethods(env, VendorFileGlobals.INSTANCE)
        return env.buildOrThrow()
    }

    companion object {
        val INSTANCE: StarlarkGlobalsImpl = StarlarkGlobalsImpl()
    }
}
