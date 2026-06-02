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
 * A SkyFunction that looks up a [com.google.devtools.build.lib.packages.MacroInstance] in a
 * [com.google.devtools.build.lib.packages.PackagePiece], producing a [ ].
 */
class MacroInstanceFunction : SkyFunction {
    @Throws(MacroInstanceFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: MacroInstanceValue.Key = skyKey.argument() as MacroInstanceValue.Key
        val packagePieceValue: PackagePieceValue?
        try {
            packagePieceValue =
                env.getValueOrThrow<E1?, E2?>(
                    key.packagePieceId(),
                    NoSuchPackageException::class.java,
                    NoSuchPackagePieceException::class.java
                ) as PackagePieceValue?
        } catch (e: NoSuchPackageException) {
            throw MacroInstanceFunctionException(e)
        } catch (e: NoSuchPackagePieceException) {
            throw MacroInstanceFunctionException(e)
        }
        if (packagePieceValue == null) {
            return null
        }

        val packagePiece: PackagePiece = packagePieceValue.packagePiece
        val macroInstance: MacroInstance? = packagePiece.getMacroByName(key.macroInstanceName)
        if (macroInstance == null) {
            throw MacroInstanceFunctionException(NoSuchMacroInstanceException(key, packagePiece))
        }
        return MacroInstanceValue(macroInstance)
    }

    /**
     * Wrapper for exceptions which can be thrown by [MacroInstanceFunctionException.compute].
     */
    class MacroInstanceFunctionException : SkyFunctionException {
        internal constructor(cause: NoSuchPackageException?) : super(cause, Transience.PERSISTENT)

        internal constructor(cause: NoSuchPackagePieceException?) : super(cause, Transience.PERSISTENT)

        internal constructor(cause: NoSuchMacroInstanceException?) : super(cause, Transience.PERSISTENT)
    }

    /**
     * Exception indicating that the given macro instance does not exist in the given package piece.
     */
    class NoSuchMacroInstanceException internal constructor(key: MacroInstanceValue.Key, packagePiece: PackagePiece) :
        NoSuchThingException(
            java.lang.String.format(
                "Macro instance '%s' not found in %s",
                key.macroInstanceName, packagePiece.getShortDescription()
            )
        )
}
