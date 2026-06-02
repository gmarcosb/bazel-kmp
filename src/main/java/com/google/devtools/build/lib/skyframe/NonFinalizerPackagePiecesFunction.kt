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
 * A SkyFunction that collects the non-finalizer-defined [ ]s of a package, producing a [ ].
 */
class NonFinalizerPackagePiecesFunction : SkyFunction {
    @Throws(NonFinalizerPackagePiecesFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: NonFinalizerPackagePiecesValue.Key = skyKey.argument() as NonFinalizerPackagePiecesValue.Key
        val expander: RecursiveExpander = RecursiveExpander()
        try {
            if (expander.expand(key.pkgId(), env,  /* expandFinalizers= */false) == null) {
                // Restart
                return null
            }
        } catch (e: NoSuchPackageException) {
            throw NonFinalizerPackagePiecesFunctionException(e)
        } catch (e: NoSuchPackagePieceException) {
            throw NonFinalizerPackagePiecesFunctionException(e)
        } catch (e: NoSuchMacroInstanceException) {
            throw NonFinalizerPackagePiecesFunctionException(e)
        }

        com.google.common.base.Preconditions.checkState(!expander.getPackagePieces().isEmpty())

        if (expander.getPackagePieces().size() == 1) {
            // Trivial case - BUILD file only; name conflicts were already checked by
            // PackagePiece.ForBuildFile construction.
            return NonFinalizerPackagePiecesValue(
                expander.getPackagePieces(),
                expander.getErrorKeys(),  /* nameConflictBetweenPackagePiecesException= */
                null,  // All targets are top-level; no non-finalizer macros.
                expander.getPackagePieceForBuildFile().getTargets(),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                expander.getStarlarkSemantics(),
                expander.getMainRepositoryMapping()
            )
        }

        val targetRecorder: TargetRecorder =
            TargetRecorder( /* enableNameConflictChecking= */
                true,  /* trackFullMacroInformation= */
                false,  /* enableTargetMapSnapshotting= */
                false
            )
        var nameConflictException: net.starlark.java.eval.EvalException? = null
        try {
            expander.recordTargetsAndMacros(targetRecorder)
        } catch (e: net.starlark.java.eval.EvalException) {
            nameConflictException = e
            env.getListener()
                .handle(
                    Package.error(
                        e.getInnermostLocation(), e.getMessageWithStack(), Code.STARLARK_EVAL_ERROR
                    )
                )
        }

        return NonFinalizerPackagePiecesValue(
            expander.getPackagePieces(),
            expander.getErrorKeys(),
            nameConflictException,
            com.google.common.collect.ImmutableSortedMap.copyOf(targetRecorder.getTargetMap()),
            com.google.common.collect.ImmutableSortedMap.copyOf(targetRecorder.getMacroMap()),
            expander.getStarlarkSemantics(),
            expander.getMainRepositoryMapping()
        )
    }

    /**
     * Wrapper for exceptions which can be thrown by [ ][NonFinalizerPackagePiecesFunction.compute].
     */
    class NonFinalizerPackagePiecesFunctionException

        : SkyFunctionException {
        internal constructor(cause: NoSuchPackageException?) : super(cause, Transience.PERSISTENT)

        internal constructor(cause: NoSuchPackagePieceException?) : super(cause, Transience.PERSISTENT)

        internal constructor(cause: NoSuchMacroInstanceException?) : super(cause, Transience.PERSISTENT)
    }
}
