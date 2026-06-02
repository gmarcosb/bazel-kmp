// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.proto

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.packages.BuiltinRestriction
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.StarlarkList
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue
import net.starlark.java.syntax.Location
import java.util.function.Function

/** Protocol buffers support for Starlark.  */
@StarlarkBuiltin(name = "proto_common", doc = "Private utilities for protocol buffers. Do not use.", documented = false)
class BazelProtoCommon protected constructor() : StarlarkValue {
    @StarlarkMethod(
        name = "incompatible_enable_proto_toolchain_resolution",
        useStarlarkThread = true,
        documented = false
    )
    @Throws(EvalException::class)
    fun getDefineProtoToolchains(thread: StarlarkThread): Boolean {
        BuiltinRestriction.failIfCalledOutsideBuiltins(thread)
        return thread
            .getSemantics()
            .getBool(BuildLanguageOptions.INCOMPATIBLE_ENABLE_PROTO_TOOLCHAIN_RESOLUTION)
    }

    @StarlarkMethod(name = "external_proto_infos", useStarlarkThread = true, documented = false)
    @Throws(EvalException::class)
    fun getExternalProtoInfos(thread: StarlarkThread?): StarlarkList<StarlarkProvider?>? {
        BuiltinRestriction.failIfCalledOutsideBuiltins(thread)
        return externalProtoInfos
    }

    companion object {
        val INSTANCE: BazelProtoCommon = BazelProtoCommon()

        private val externalProtoInfos: StarlarkList<StarlarkProvider?>? = StarlarkList.immutableCopyOf<T?>(
            ProtoConstants.EXTERNAL_PROTO_INFO_KEYS.stream()
                .map<Any?>(
                    Function { key: BzlLoadValue.Key? ->
                        StarlarkProvider.builder(Location.BUILTIN)
                            .buildExported(Key(key, "ProtoInfo"))
                    })
                .collect(ImmutableList.toImmutableList<Any?>())
        )
    }
}
