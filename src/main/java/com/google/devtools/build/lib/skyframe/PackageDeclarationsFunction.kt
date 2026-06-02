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

import com.google.devtools.build.lib.packages.NoSuchPackageException

/**
 * A SkyFunction that looks up a [Package.Declarations] in a [ ], producing a [ ].
 */
class PackageDeclarationsFunction : SkyFunction {
    @Throws(PackageDeclarationsFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: PackageDeclarationsValue.Key = skyKey.argument() as PackageDeclarationsValue.Key
        val packagePieceValue: PackagePieceValue.ForBuildFile?
        try {
            packagePieceValue =
                env.getValueOrThrow<E1?, E2?>(
                    ForBuildFile(key.packageId()),
                    NoSuchPackageException::class.java,
                    NoSuchPackagePieceException::class.java
                ) as PackagePieceValue.ForBuildFile?
        } catch (e: NoSuchPackageException) {
            throw PackageDeclarationsFunctionException(e)
        } catch (e: NoSuchPackagePieceException) {
            throw PackageDeclarationsFunctionException(e)
        }
        if (packagePieceValue == null) {
            return null
        }

        return PackageDeclarationsValue(
            packagePieceValue.getPackagePiece().getMetadata(),
            packagePieceValue.getPackagePiece().getDeclarations(),
            packagePieceValue.starlarkSemantics(),
            packagePieceValue.mainRepositoryMapping()
        )
    }

    /** Wrapper for exceptions which can be thrown by [PackageDeclarationsFunction.compute].  */
    class PackageDeclarationsFunctionException : SkyFunctionException {
        internal constructor(cause: NoSuchPackageException?) : super(cause, Transience.PERSISTENT)

        internal constructor(cause: NoSuchPackagePieceException?) : super(cause, Transience.PERSISTENT)
    }
}
