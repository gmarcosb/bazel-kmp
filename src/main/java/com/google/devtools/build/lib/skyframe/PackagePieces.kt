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

/** A subtree of package pieces belonging to one package.  */
interface PackagePieces {
    /**
     * Returns the package pieces, with parents ordered before children, and with siblings ordered by
     * name.
     */
    @kotlin.jvm.JvmField
    val packagePieces: com.google.common.collect.ImmutableMap<PackagePieceIdentifier?, PackagePiece?>?

    /** Returns the package piece for the BUILD file.  */
    val packagePieceForBuildFile: ForBuildFile?

    /** Returns the identifiers of package pieces which contain errors.  */
    @kotlin.jvm.JvmField
    val errorKeys: com.google.common.collect.ImmutableList<PackagePieceIdentifier?>?

    val firstPieceContainingErrors: PackagePiece?
        get() {
            if (this.errorKeys.isEmpty()) {
                return null
            }
            val firstPieceContainingErrors: PackagePiece? = this.packagePieces.get(this.errorKeys.getFirst())
            com.google.common.base.Preconditions.checkState(firstPieceContainingErrors.containsErrors())
            return firstPieceContainingErrors
        }

    /**
     * Records the targets and macros of the package pieces in this collection, verifying that there
     * are no name conflicts between package pieces.
     * 
     * @throws EvalException with a reconstructed Starlark call stack if there is a name conflict.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun recordTargetsAndMacros(recorder: TargetRecorder) {
        try {
            for (packagePiece in this.packagePieces.values()) {
                recorder.addAllFromPackagePiece(packagePiece,  /* skipBuildFile= */false)
            }
        } catch (e: TargetRecorder.NameConflictException) {
            throw wrapNameConflictException(e)
        }
    }

    /**
     * Records the targets and macros of the package pieces in this collection, verifying that there
     * are no name conflicts between package pieces.
     * 
     * @throws EvalException with a reconstructed Starlark call stack if there is a name conflict.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun recordTargetsAndMacros(pkgBuilder: Package.Builder) {
        try {
            for (packagePiece in this.packagePieces.values()) {
                pkgBuilder.addAllFromPackagePiece(packagePiece)
            }
        } catch (e: TargetRecorder.NameConflictException) {
            throw wrapNameConflictException(e)
        }
    }

    companion object {
        private fun wrapNameConflictException(e: TargetRecorder.NameConflictException): net.starlark.java.eval.EvalException? {
            return net.starlark.java.eval.EvalException(e)
                .withCallStack(
                    if (e.getMacro() != null)
                        e.getMacro().reconstructParentCallStack()
                    else
                        reconstructCallStack(e.getTarget())
                )
        }

        private fun reconstructCallStack(target: Target): com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>? {
            val rule: Rule? = target.getAssociatedRule()
            if (rule != null) {
                return rule.reconstructCallStack()
            }
            val declaringMacro: MacroInstance? = target.getDeclaringMacro()
            if (declaringMacro != null) {
                return declaringMacro.reconstructParentCallStack()
            }
            // Top-level non-rule target
            return com.google.common.collect.ImmutableList.of<net.starlark.java.eval.StarlarkThread.CallStackEntry?>(
                net.starlark.java.eval.StarlarkThread.callStackEntry(
                    net.starlark.java.eval.StarlarkThread.TOP_LEVEL,
                    target.getLocation()
                )
            )
        }
    }
}
