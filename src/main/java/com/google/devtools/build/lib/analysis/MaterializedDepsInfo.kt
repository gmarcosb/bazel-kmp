// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.packages.BuiltinProvider

/** The provider returned from materializer rules to materialize dependencies.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class MaterializedDepsInfo private constructor(deps: com.google.common.collect.ImmutableList<Either<ConfiguredTarget?, DormantDependency?>?>?) :
    NativeInfo(), MaterializedDepsInfoApi {
    /**
     * The dependencies to be materialized. These may be ConfiguredTarget or DormantDependency
     * objects.
     */
    private val deps: com.google.common.collect.ImmutableList<Either<ConfiguredTarget?, DormantDependency?>?>?

    init {
        this.deps = deps
    }

    /**
     * The dependencies to be materialized. These may be ConfiguredTarget or DormantDependency
     * objects.
     */
    public override fun getDeps(): com.google.common.collect.ImmutableList<Either<ConfiguredTarget?, DormantDependency?>?>? {
        return deps
    }

    public override fun getProvider(): Provider {
        return PROVIDER
    }

    /** Provider class for [MaterializedDepsInfo].  */
    class Provider private constructor() :
        BuiltinProvider<MaterializedDepsInfo?>(MaterializedDepsInfoApi.NAME, MaterializedDepsInfo::class.java),
        MaterializedDepsInfoApi.Provider {
        @Throws(net.starlark.java.eval.EvalException::class)
        public override fun materializedDepsInfo(dependencies: net.starlark.java.eval.Sequence<*>): MaterializedDepsInfoApi? {
            val depsBuilder: com.google.common.collect.ImmutableList.Builder<Either<ConfiguredTarget?, DormantDependency?>?> =
                com.google.common.collect.ImmutableList.builder<Either<ConfiguredTarget?, DormantDependency?>?>()
            var index = 0
            for (dependency in dependencies) {
                when (dependency) {
                    -> depsBuilder.add(Either.ofLeft(configuredTarget))
                    -> depsBuilder.add(Either.ofRight(dormantDependency))
                    else -> throw Starlark.errorf(
                        ("MaterializedDepsInfo dependencies must be Target objects (retrieved from"
                                + " ctx.attr) or DormantDependency objects (from attr.dormant_label() or"
                                + " attr.dormant_label_list() attributes), but got %s at index %s"),
                        Starlark.type(dependency), index
                    )
                }
                index++
            }

            return MaterializedDepsInfo(depsBuilder.build())
        }
    }

    companion object {
        val PROVIDER: Provider = com.google.devtools.build.lib.analysis.MaterializedDepsInfo.Provider()
    }
}
