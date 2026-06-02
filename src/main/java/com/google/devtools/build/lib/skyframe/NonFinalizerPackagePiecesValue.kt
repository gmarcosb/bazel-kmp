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

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * A Skyframe value representing all package pieces of a package that are not defined by finalizer
 * macros.
 * 
 * 
 * The corresponding [com.google.devtools.build.skyframe.SkyKey] is [ ].
 */
@AutoCodec
class NonFinalizerPackagePiecesValue(
    packagePieces: com.google.common.collect.ImmutableMap<PackagePieceIdentifier?, PackagePiece?>?,
    errorKeys: com.google.common.collect.ImmutableList<PackagePieceIdentifier?>?,
    nameConflictBetweenPackagePiecesException: net.starlark.java.eval.EvalException?,
    targets: com.google.common.collect.ImmutableSortedMap<String?, Target?>?,
    macroInstances: com.google.common.collect.ImmutableSortedMap<String?, MacroInstance?>?,
    starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
    mainRepositoryMapping: RepositoryMapping?
) : PackagePieces, SkyValue {
    override fun getPackagePieces(): com.google.common.collect.ImmutableMap<PackagePieceIdentifier?, PackagePiece?>? {
        return packagePieces
    }

    val packagePieceForBuildFile: ForBuildFile?
        get() = packagePieces.values().iterator().next() as ForBuildFile

    override fun getErrorKeys(): com.google.common.collect.ImmutableList<PackagePieceIdentifier?>? {
        return errorKeys
    }

    fun containsErrors(): Boolean {
        return !errorKeys.isEmpty() || this.nameConflictBetweenPackagePiecesException != null
    }

    /** A SkyKey for a [NonFinalizerPackagePiecesValue].  */
    @AutoCodec
    class Key(pkgId: PackageIdentifier?) : SkyKey {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.NON_FINALIZER_PACKAGE_PIECES
        }

        val pkgId: PackageIdentifier?

        init {
            this.pkgId = pkgId
            com.google.common.base.Preconditions.checkNotNull<Any?>(pkgId)
        }
    }

    val packagePieces: com.google.common.collect.ImmutableMap<PackagePieceIdentifier?, PackagePiece?>?
    val errorKeys: com.google.common.collect.ImmutableList<PackagePieceIdentifier?>?
    val nameConflictBetweenPackagePiecesException: net.starlark.java.eval.EvalException?
    val targets: com.google.common.collect.ImmutableSortedMap<String?, Target?>?
    val macroInstances: com.google.common.collect.ImmutableSortedMap<String?, MacroInstance?>?
    val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
    val mainRepositoryMapping: RepositoryMapping?

    init {
        this.mainRepositoryMapping = mainRepositoryMapping
        this.starlarkSemantics = starlarkSemantics
        this.macroInstances = macroInstances
        this.targets = targets
        this.nameConflictBetweenPackagePiecesException = nameConflictBetweenPackagePiecesException
        this.errorKeys = errorKeys
        this.packagePieces = packagePieces
        com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMap<PackagePieceIdentifier?, PackagePiece?>?>(
            packagePieces
        )
        com.google.common.base.Preconditions.checkArgument(!packagePieces.isEmpty())
        com.google.common.base.Preconditions.checkArgument(packagePieces.values().iterator().next() is ForBuildFile)
        com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<PackagePieceIdentifier?>?>(
            errorKeys
        )
        com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSortedMap<String?, Target?>?>(
            targets
        )
        com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSortedMap<String?, MacroInstance?>?>(
            macroInstances
        )
        com.google.common.base.Preconditions.checkNotNull<net.starlark.java.eval.StarlarkSemantics?>(starlarkSemantics)
        com.google.common.base.Preconditions.checkNotNull<Any?>(mainRepositoryMapping)
    }
}
