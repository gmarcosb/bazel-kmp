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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.TemplateVariableInfo
import com.google.devtools.build.lib.packages.BuiltinProvider
import com.google.devtools.build.lib.packages.NativeInfo
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import com.google.devtools.build.lib.starlarkbuildapi.TemplateVariableInfoApi

/** Provides access to make variables from the current fragments.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class TemplateVariableInfo(variables: com.google.common.collect.ImmutableMap<String?, String?>?) : NativeInfo(),
    TemplateVariableInfoApi {
    /** Provider for [TemplateVariableInfo] objects.  */
    private class Provider :
        BuiltinProvider<TemplateVariableInfo?>(TemplateVariableInfoApi.NAME, TemplateVariableInfo::class.java),
        TemplateVariableInfoApi.Provider {
        @Throws(net.starlark.java.eval.EvalException::class)
        override fun templateVariableInfo(vars: net.starlark.java.eval.Dict<*, *>?): TemplateVariableInfo {
            val varsMap: MutableMap<String?, String?> = net.starlark.java.eval.Dict.noneableCast<String?, String?>(
                vars,
                String::class.java,
                String::class.java,
                "vars"
            )
            return TemplateVariableInfo(com.google.common.collect.ImmutableMap.copyOf<String?, String?>(varsMap))
        }
    }

    private val variables: com.google.common.collect.ImmutableMap<String?, String?>?

    init {
        this.variables = variables
    }

    val provider: BuiltinProvider<TemplateVariableInfo?>
        get() = PROVIDER

    override fun getVariables(): com.google.common.collect.ImmutableMap<String?, String?>? {
        return variables
    }

    override fun equals(other: Any?): Boolean {
        return other === this
    }

    override fun hashCode(): Int {
        return java.lang.System.identityHashCode(this)
    }

    companion object {
        /** Provider singleton constant.  */
        @kotlin.jvm.JvmField
        @SerializationConstant
        val PROVIDER: BuiltinProvider<TemplateVariableInfo?> =
            com.google.devtools.build.lib.analysis.TemplateVariableInfo.Provider()
    }
}
