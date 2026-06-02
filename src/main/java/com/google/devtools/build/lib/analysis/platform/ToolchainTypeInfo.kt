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
package com.google.devtools.build.lib.analysis.platform

import com.google.devtools.build.lib.packages.BuiltinProvider
import com.google.devtools.build.lib.packages.NativeInfo
import com.google.devtools.build.lib.starlarkbuildapi.platform.ToolchainTypeInfoApi
import com.google.devtools.build.lib.util.HashCodes

/** A provider that supplies information about a specific toolchain type.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class ToolchainTypeInfo private constructor(
    typeLabel: com.google.devtools.build.lib.cmdline.Label?,
    noneFoundError: String?
) : NativeInfo(), ToolchainTypeInfoApi {
    private val typeLabel: com.google.devtools.build.lib.cmdline.Label?
    private val noneFoundError: String?

    init {
        this.typeLabel = typeLabel
        this.noneFoundError = noneFoundError
    }

    val provider: BuiltinProvider<ToolchainTypeInfo?>
        get() = PROVIDER

    override fun typeLabel(): com.google.devtools.build.lib.cmdline.Label? {
        return typeLabel
    }

    fun noneFoundError(): String? {
        return noneFoundError
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append(String.format("ToolchainTypeInfo(%s)", typeLabel))
    }

    override fun hashCode(): Int {
        return HashCodes.hashObjects(typeLabel, noneFoundError)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ToolchainTypeInfo) {
            return false
        }

        return typeLabel == other.typeLabel
                && noneFoundError == other.noneFoundError
    }

    companion object {
        /** Name used in Starlark for accessing this provider.  */
        const val STARLARK_NAME: String = "ToolchainTypeInfo"

        /** Provider singleton constant.  */
        @kotlin.jvm.JvmField
        val PROVIDER: BuiltinProvider<ToolchainTypeInfo?> =
            object : BuiltinProvider<ToolchainTypeInfo?>(STARLARK_NAME, ToolchainTypeInfo::class.java) {}

        fun create(
            typeLabel: com.google.devtools.build.lib.cmdline.Label?,
            noneFoundError: String?
        ): ToolchainTypeInfo {
            return ToolchainTypeInfo(typeLabel, noneFoundError)
        }

        @com.google.common.annotations.VisibleForTesting
        fun create(typeLabel: com.google.devtools.build.lib.cmdline.Label?): ToolchainTypeInfo {
            return ToolchainTypeInfo(typeLabel,  /* noneFoundError= */null)
        }
    }
}
