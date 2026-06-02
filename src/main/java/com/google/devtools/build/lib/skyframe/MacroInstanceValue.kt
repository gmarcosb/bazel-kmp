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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.packages.MacroInstance

/**
 * A Skyframe value representing the declaration of a symbolic macro instance.
 * 
 * 
 * The corresponding [com.google.devtools.build.skyframe.SkyKey] is [ ].
 * 
 * 
 * The purpose of this class is to store potentially large data (macro attribute values and the
 * Starlark stack) in a skyvalue rather than directly in a [PackagePieceValue.ForMacro]'s
 * skykey.
 */
@AutoCodec
class MacroInstanceValue(macroInstance: MacroInstance?) : SkyValue {
    /** A SkyKey for a [MacroInstanceValue].  */
    @AutoCodec
    class Key(packagePieceId: PackagePieceIdentifier?, @kotlin.jvm.JvmField val macroInstanceName: String?) : SkyKey {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.MACRO_INSTANCE
        }

        val packagePieceId: PackagePieceIdentifier?

        init {
            this.packagePieceId = packagePieceId
            com.google.common.base.Preconditions.checkNotNull<Any?>(packagePieceId)
            com.google.common.base.Preconditions.checkNotNull<String?>(macroInstanceName)
        }
    }

    val macroInstance: MacroInstance?

    init {
        this.macroInstance = macroInstance
        com.google.common.base.Preconditions.checkNotNull<Any?>(macroInstance)
    }
}
