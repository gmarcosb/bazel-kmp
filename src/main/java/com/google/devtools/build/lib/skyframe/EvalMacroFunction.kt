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
 * A SkyFunction that evaluates a symbolic macro instance, identified by a
 * {PackagePieceIdentifier.ForMacro}, and produces a [PackagePieceValue.ForMacro].
 */
class EvalMacroFunction(packageFactory: PackageFactory, cpuBoundSemaphore: AtomicReference<Semaphore?>) : SkyFunction {
    private val packageFactory: PackageFactory
    private val cpuBoundSemaphore: AtomicReference<Semaphore?>

    init {
        this.packageFactory = packageFactory
        this.cpuBoundSemaphore = cpuBoundSemaphore
    }

    @Throws(EvalMacroFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: PackagePieceIdentifier.ForMacro = skyKey.argument() as PackagePieceIdentifier.ForMacro
        // Get the common metadata and declarations shared by all package pieces of the package.
        val packageDeclarationsValue: PackageDeclarationsValue?
        try {
            packageDeclarationsValue =
                env.getValueOrThrow<E1?, E2?>(
                    Key(key.getPackageIdentifier()),
                    NoSuchPackageException::class.java,
                    NoSuchPackagePieceException::class.java
                ) as PackageDeclarationsValue?
        } catch (e: NoSuchPackageException) {
            throw EvalMacroFunctionException(e)
        } catch (e: NoSuchPackagePieceException) {
            throw EvalMacroFunctionException(e)
        }
        if (packageDeclarationsValue == null) {
            return null
        }

        // Get the macro instance (owned by the parent package piece) which we will be expanding to
        // produce this package piece.
        val macroInstanceValue: MacroInstanceValue?
        try {
            macroInstanceValue =
                env.getValueOrThrow<E1?, E2?, E3?>(
                    Key(key.parentIdentifier, key.instanceName),
                    NoSuchPackageException::class.java,
                    NoSuchPackagePieceException::class.java,
                    NoSuchMacroInstanceException::class.java
                ) as MacroInstanceValue?
        } catch (e: NoSuchPackageException) {
            throw EvalMacroFunctionException(e)
        } catch (e: NoSuchPackagePieceException) {
            throw EvalMacroFunctionException(e)
        } catch (e: NoSuchMacroInstanceException) {
            throw EvalMacroFunctionException(e)
        }
        if (macroInstanceValue == null) {
            return null
        }
        val macroInstance: MacroInstance = macroInstanceValue.macroInstance()

        // Non-null iff the macro is a finalizer.
        var nonFinalizerPackagePiecesValue: NonFinalizerPackagePiecesValue? = null
        // Non-null iff the macro is a finalizer and finalizer dependencies were computed without error.
        var existingRulesMapForFinalizer: com.google.common.collect.ImmutableMap<String?, Rule?>? = null

        if (macroInstance.getMacroClass().isFinalizer) {
            try {
                nonFinalizerPackagePiecesValue =
                    env.getValueOrThrow<E1?, E2?, E3?>(
                        Key(key.getPackageIdentifier()),
                        NoSuchPackageException::class.java,
                        NoSuchPackagePieceException::class.java,
                        NoSuchMacroInstanceException::class.java
                    ) as NonFinalizerPackagePiecesValue?
            } catch (e: NoSuchPackageException) {
                throw EvalMacroFunctionException(e)
            } catch (e: NoSuchPackagePieceException) {
                throw EvalMacroFunctionException(e)
            } catch (e: NoSuchMacroInstanceException) {
                throw EvalMacroFunctionException(e)
            }
            if (nonFinalizerPackagePiecesValue == null) {
                // Restart
                return null
            } else if (!nonFinalizerPackagePiecesValue.containsErrors()) {
                existingRulesMapForFinalizer =
                    nonFinalizerPackagePiecesValue.targets().entrySet().stream()
                        .filter({ e -> e.getValue() is Rule })
                        .collect(
                            com.google.common.collect.ImmutableMap.toImmutableMap<T?, K?, V?>(
                                java.util.function.Function { java.util.Map.Entry.getKey() },
                                java.util.function.Function { e: T? -> e.getValue() as Rule? })
                        )
            }
        }

        // Expand the macro.
        val startTimeNanos: Long = com.google.devtools.build.lib.clock.BlazeClock.nanoTime()
        val packagePieceBuilder: PackagePiece.ForMacro.Builder =
            packageFactory.newPackagePieceForMacroBuilder(
                packageDeclarationsValue.metadata(),
                packageDeclarationsValue.declarations(),
                macroInstance,
                key.parentIdentifier,
                packageDeclarationsValue.starlarkSemantics(),
                packageDeclarationsValue.mainRepositoryMapping(),
                cpuBoundSemaphore.get(),
                existingRulesMapForFinalizer
            )
        if (nonFinalizerPackagePiecesValue != null && nonFinalizerPackagePiecesValue.containsErrors()) {
            // Error within one non-finalizer package piece or a name conflict between package pieces. It
            // was already reported as an event with stack trace by the computation of the
            // PackagePieceValue or NonFinalizerPackagePiecesValue, so we don't need to repeat the stack
            // trace - just a brief summary.
            if (!nonFinalizerPackagePiecesValue.getErrorKeys().isEmpty()) {
                val errorKey: PackagePieceIdentifier? = nonFinalizerPackagePiecesValue.getErrorKeys().getFirst()
                val errorPiece: PackagePiece = nonFinalizerPackagePiecesValue.getPackagePieces().get(errorKey)
                handleFinalizerDependencyError(
                    packagePieceBuilder, "error in " + errorPiece.getShortDescription()
                )
            } else {
                handleFinalizerDependencyError(
                    packagePieceBuilder,
                    nonFinalizerPackagePiecesValue
                        .nameConflictBetweenPackagePiecesException()
                        .getMessage()
                )
            }
            packagePieceBuilder.setContainsErrors()
        } else {
            try {
                MacroClass.executeMacroImplementation(
                    macroInstance, packagePieceBuilder, packageDeclarationsValue.starlarkSemantics()
                )
            } catch (e: net.starlark.java.eval.EvalException) {
                packagePieceBuilder
                    .getLocalEventHandler()
                    .handle(
                        Package.error(
                            e.getInnermostLocation(), e.getMessageWithStack(), Code.STARLARK_EVAL_ERROR
                        )
                    )
                packagePieceBuilder.setContainsErrors()
            }
        }
        val loadTimeNanos: Long =
            java.lang.Math.max(com.google.devtools.build.lib.clock.BlazeClock.nanoTime() - startTimeNanos, 0L)

        try {
            packagePieceBuilder.buildPartial()
            // TODO(https://github.com/bazelbuild/bazel/issues/23852): verify labels using
            // PackageFunction#handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions
        } catch (e: NoSuchPackageException) {
            throw EvalMacroFunctionException(e)
        }
        val packagePiece: PackagePiece.ForMacro? = packagePieceBuilder.finishBuild()
        packagePieceBuilder.getLocalEventHandler().replayOn(env.getListener())

        try {
            packageFactory.afterDoneLoadingPackagePiece(
                packagePiece,
                packageDeclarationsValue.starlarkSemantics(),
                Metrics(
                    loadTimeNanos,  // Symbolic macros don't use `native.glob`.
                    /* globFilesystemOperationCost= */
                    0L
                ),
                env.getListener()
            )
        } catch (e: InvalidPackagePieceException) {
            throw EvalMacroFunctionException(e)
        }

        return ForMacro(packagePiece)
    }

    /**
     * A mutable [PackagePieces] implementation which produces its collection of package pieces
     * by recursively expanding a starting collection of package piece identifiers.
     * 
     * 
     * Intended to be used as part of a skyfunction compute() implementation. The [ ] lacks any kind of invalidation of already-expanded package pieces, so it
     * cannot be reused across multiple skyframe evaluations.
     */
    internal class RecursiveExpander : PackagePieces {
        private val packagePieces: LinkedHashMap<PackagePieceIdentifier?, PackagePiece?> =
            LinkedHashMap<PackagePieceIdentifier?, PackagePiece?>()
        private val errorKeys: LinkedHashSet<PackagePieceIdentifier?> = LinkedHashSet<PackagePieceIdentifier?>()

        // The following two fields are set by a successful expansion of a PackagePiece.ForBuildFile.
        private var starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? = null
        private var mainRepositoryMapping: RepositoryMapping? = null

        public override fun getPackagePieces(): com.google.common.collect.ImmutableMap<PackagePieceIdentifier?, PackagePiece?> {
            return com.google.common.collect.ImmutableMap.copyOf<PackagePieceIdentifier?, PackagePiece?>(packagePieces)
        }

        val packagePieceForBuildFile: PackagePiece.ForBuildFile?
            get() = packagePieces.values().iterator().next() as PackagePiece.ForBuildFile?

        public override fun getErrorKeys(): com.google.common.collect.ImmutableList<PackagePieceIdentifier?> {
            return com.google.common.collect.ImmutableList.copyOf<PackagePieceIdentifier?>(errorKeys)
        }

        fun getStarlarkSemantics(): net.starlark.java.eval.StarlarkSemantics? {
            return starlarkSemantics
        }

        fun getMainRepositoryMapping(): RepositoryMapping? {
            return mainRepositoryMapping
        }

        /**
         * Recursively expands the pieces of a package. Intended for inlining into skyfunction
         * implementations.
         * 
         * @param pkgId the package whose pieces are being expanded
         * @param env the skyframe environment
         * @return this expander on success, or null to signal a skyframe restart.
         */
        @Throws(
            NoSuchPackageException::class,
            NoSuchPackagePieceException::class,
            NoSuchMacroInstanceException::class,
            java.lang.InterruptedException::class
        )
        fun expand(
            pkgId: PackageIdentifier?,
            env: SkyFunction.Environment,
            expandFinalizers: Boolean
        ): RecursiveExpander? {
            return expand(
                com.google.common.collect.ImmutableList.of<PackagePieceIdentifier?>(ForBuildFile(pkgId)),
                env,
                expandFinalizers
            )
        }

        /**
         * Performs "opportunistic BFS" recursive expansion of the given keys: expands in BFS order
         * (siblings ordered by name) as far as possible, skipping missing values, and then signals a
         * skyframe restart if any values were missing. Once all missing values have been obtained, the
         * final evaluation of this function - one which does not trigger a restart - will collect
         * package pieces in BFS order.
         * 
         * @param keys set of keys to expand. If the expander is empty, must contain a single [     ]. Otherwise, must contain package piece keys of the
         * same depth, with siblings ordered by name.
         * @return this expander on success, or null to signal a skyframe restart.
         */
        // TODO(https://github.com/bazelbuild/bazel/issues/23852) - use state machine to reduce restart
        // cost?
        @Throws(
            NoSuchPackageException::class,
            NoSuchPackagePieceException::class,
            NoSuchMacroInstanceException::class,
            java.lang.InterruptedException::class
        )
        private fun expand(
            keys: MutableCollection<out PackagePieceIdentifier>,
            env: SkyFunction.Environment,
            expandFinalizers: Boolean
        ): RecursiveExpander? {
            if (keys.isEmpty()) {
                return this
            }
            if (packagePieces.isEmpty()) {
                com.google.common.base.Preconditions.checkArgument(
                    keys.size() == 1
                            && keys.iterator().next() is PackagePieceIdentifier.ForBuildFile,
                    "expansion must start from a PackagePieceIdentifier.ForBuildFile"
                )
            }
            var valuesMissing = false
            val lookupResult: SkyframeLookupResult = env.getValuesAndExceptions(keys)
            val childKeys: com.google.common.collect.ImmutableList.Builder<PackagePieceIdentifier.ForMacro?> =
                com.google.common.collect.ImmutableList.builder<PackagePieceIdentifier.ForMacro?>()
            for (key in keys) {
                val packagePieceValue: PackagePieceValue? =
                    lookupResult.getOrThrow<E1?, E2?, E3?>(
                        key,
                        NoSuchPackageException::class.java,
                        NoSuchPackagePieceException::class.java,
                        NoSuchMacroInstanceException::class.java
                    ) as PackagePieceValue?
                if (packagePieceValue == null) {
                    valuesMissing = true
                    continue
                }
                if (packagePieceValue is PackagePieceValue.ForBuildFile) {
                    starlarkSemantics = packagePieceValue.starlarkSemantics()
                    mainRepositoryMapping = packagePieceValue.mainRepositoryMapping()
                }
                packagePieces.put(key, packagePieceValue.packagePiece)
                if (packagePieceValue.packagePiece.containsErrors()) {
                    errorKeys.add(key)
                } else {
                    // Find unexpanded macro keys
                    for (childMacroInstance in packagePieceValue.packagePiece.getMacros()) {
                        val childKey: PackagePieceIdentifier.ForMacro =
                            ForMacro(
                                key.getPackageIdentifier(), key, childMacroInstance.getName()
                            )
                        if (packagePieces.containsKey(childKey)) {
                            // Already expanded.
                            continue
                        }
                        if (expandFinalizers || !childMacroInstance.getMacroClass().isFinalizer) {
                            childKeys.add(childKey)
                        }
                    }
                }
            }
            if (expand(childKeys.build(), env, expandFinalizers) == null) {
                valuesMissing = true
            }
            return if (valuesMissing) null else this
        }

        companion object {
            @Throws(
                NoSuchPackageException::class,
                NoSuchPackagePieceException::class,
                NoSuchMacroInstanceException::class,
                java.lang.InterruptedException::class
            )
            fun expandFinalizers(
                nonFinalizerPackagePieces: NonFinalizerPackagePiecesValue, env: SkyFunction.Environment?
            ): PackagePieces? {
                val unexpandedKeysBuilder: com.google.common.collect.ImmutableList.Builder<PackagePieceIdentifier.ForMacro?> =
                    com.google.common.collect.ImmutableList.builder<PackagePieceIdentifier.ForMacro?>()
                for (packagePiece in nonFinalizerPackagePieces.getPackagePieces().values()) {
                    // Find unexpanded macro keys
                    for (macro in packagePiece.getMacros()) {
                        val key: PackagePieceIdentifier.ForMacro =
                            ForMacro(
                                packagePiece.getPackageIdentifier(),
                                packagePiece.getIdentifier(),
                                macro.getName()
                            )
                        if (!nonFinalizerPackagePieces.getPackagePieces().containsKey(key)) {
                            unexpandedKeysBuilder.add(key)
                        }
                    }
                }
                val unexpandedKeys: com.google.common.collect.ImmutableList<PackagePieceIdentifier.ForMacro?> =
                    unexpandedKeysBuilder.build()
                if (unexpandedKeys.isEmpty()) {
                    return nonFinalizerPackagePieces
                }
                val expander = RecursiveExpander()
                expander.starlarkSemantics = nonFinalizerPackagePieces.starlarkSemantics()
                expander.mainRepositoryMapping = nonFinalizerPackagePieces.mainRepositoryMapping()
                expander.packagePieces.putAll(nonFinalizerPackagePieces.getPackagePieces())
                expander.errorKeys.addAll(nonFinalizerPackagePieces.getErrorKeys())
                return expander.expand(unexpandedKeys, env,  /* expandFinalizers= */true)
            }
        }
    }

    /** Wrapper for exceptions which can be thrown by [EvalMacroFunction.compute].  */
    class EvalMacroFunctionException : SkyFunctionException {
        internal constructor(cause: NoSuchPackageException?) : super(cause, Transience.PERSISTENT)

        internal constructor(cause: NoSuchPackagePieceException?) : super(cause, Transience.PERSISTENT)

        internal constructor(cause: NoSuchMacroInstanceException?) : super(cause, Transience.PERSISTENT)
    }

    companion object {
        private fun handleFinalizerDependencyError(
            packagePieceBuilder: PackagePiece.ForMacro.Builder, message: String?
        ) {
            packagePieceBuilder
                .getLocalEventHandler()
                .handle(
                    Package.error(
                        packagePieceBuilder.getPackagePiece().getEvaluatedMacro().getBuildFileLocation(),
                        java.lang.String.format(
                            "cannot compute %s: %s",
                            packagePieceBuilder.getPackagePiece().getShortDescription(), message
                        ),
                        Code.STARLARK_EVAL_ERROR
                    )
                )
        }
    }
}
