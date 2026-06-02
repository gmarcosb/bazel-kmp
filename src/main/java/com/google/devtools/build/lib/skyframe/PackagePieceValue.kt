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

import com.google.devtools.build.lib.cmdline.RepositoryMapping

/**
 * A Skyframe value representing a package piece.
 * 
 * 
 * The corresponding [com.google.devtools.build.skyframe.SkyKey] is [ ]. Note that different subclasses of
 * PackagePieceIdentifier are evaluated by different SkyFunctions.
 */
interface PackagePieceValue : PackageoidValue {
    /**
     * Returns the package piece. This package piece may contain errors, in which case the caller
     * should throw an appropriate subclass of [ ] if an error-free package
     * piece is needed.
     */
    @kotlin.jvm.JvmField
    val packagePiece: PackagePiece?

    val packageoid: Packageoid?
        get() = this.packagePiece

    /**
     * A Skyframe value representing a package piece obtained by evaluating a BUILD file without
     * expanding any symbolic macros.
     * 
     * 
     * Inlines Starlark semantics and the main repository mapping to avoid extra dependency edges
     * in the package's package pieces for macros.
     * 
     * 
     * The corresponding [com.google.devtools.build.skyframe.SkyKey] is [ ].
     */
    @AutoCodec
    class ForBuildFile(
        forBuildFile: ForBuildFile?,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
        mainRepositoryMapping: RepositoryMapping?
    ) : PackagePieceValue {
        override fun getPackagePiece(): ForBuildFile? {
            return forBuildFile
        }

        override fun toString(): String {
            return java.lang.String.format(
                "<PackagePieceValue.ForBuildFile name=%s>",
                forBuildFile.getIdentifier().getCanonicalFormName()
            )
        }

        val forBuildFile: ForBuildFile?
        val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
        val mainRepositoryMapping: RepositoryMapping?

        init {
            this.mainRepositoryMapping = mainRepositoryMapping
            this.starlarkSemantics = starlarkSemantics
            this.forBuildFile = forBuildFile
            com.google.common.base.Preconditions.checkNotNull<Any?>(forBuildFile)
            com.google.common.base.Preconditions.checkNotNull<net.starlark.java.eval.StarlarkSemantics?>(
                starlarkSemantics
            )
            com.google.common.base.Preconditions.checkNotNull<Any?>(mainRepositoryMapping)
        }
    }

    /** A Skyframe value representing a package piece obtained by evaluating one symbolic macro.  */
    @AutoCodec
    @kotlin.jvm.JvmRecord
    data class ForMacro(val forMacro: ForMacro?) : PackagePieceValue {
        override fun getPackagePiece(): ForMacro? {
            return forMacro
        }

        override fun toString(): String {
            return java.lang.String.format(
                "<PackagePieceValue.ForMacro name=%s defined_by=%s>",
                forMacro.getIdentifier().getCanonicalFormName(), forMacro.getCanonicalFormDefinedBy()
            )
        }


        init {
            com.google.common.base.Preconditions.checkNotNull<Any?>(forMacro)
        }
    }
}
