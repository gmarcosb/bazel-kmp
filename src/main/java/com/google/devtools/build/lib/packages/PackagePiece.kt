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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/**
 * A piece of a [Package]: either the subset obtained by evaluating a BUILD file and not
 * expanding any symbolic macros; or the subset obtained by evaluating exactly one symbolic macro
 * instance.
 * 
 * 
 * To obtain a [Package] from a [PackagePiece], use a PackageProvider or skyframe
 * machinery.
 */
// TODO(https://github.com/bazelbuild/bazel/issues/23852): as a future optimization, consider adding
// another class of package piece obtained by evaluating a set of macros.
abstract class PackagePiece protected constructor(
    metadata: com.google.devtools.build.lib.packages.Package.Metadata?,
    declarations: Declarations?
) : Packageoid(metadata, declarations) {
    /**
     * The collection of all symbolic macro instances defined in this package piece, indexed by their
     * name (not by [id][MacroInstance.getId] - contrast with [Package.macros]). Null until
     * the package piece is fully initialized by [.setMacrosByName], in turn called by this
     * package piece's builder's `finishBuild()`.
     */
    private var macrosByName: com.google.common.collect.ImmutableSortedMap<String?, MacroInstance?>? = null

    abstract fun getIdentifier(): PackagePieceIdentifier?

    /**
     * Returns a (read-only, ordered) iterable of all the targets belonging to this package piece
     * which are instances of the specified class. Doesn't search in any other package pieces.
     */
    fun <T : com.google.devtools.build.lib.packages.Target?> getTargets(targetClass: java.lang.Class<T?>): Iterable<T?> {
        return com.google.common.collect.Iterables.filter<T?>(targets.values(), targetClass)
    }

    @Throws(NoSuchTargetException::class)
    override fun getTarget(targetName: String?): com.google.devtools.build.lib.packages.Target {
        val target: com.google.devtools.build.lib.packages.Target? = targets.get(targetName)
        if (target != null) {
            return target
        }

        throw noSuchTargetException(targetName)
    }

    /**
     * Returns the macro instance declared in this package piece having the provided name; or null if
     * no such macro instance exists.
     */
    fun getMacroByName(name: String?): MacroInstance? {
        return macrosByName.get(name)
    }

    /** Returns a list of all the macro instances defined in this package piece, ordered by name.  */
    fun getMacros(): com.google.common.collect.ImmutableList<MacroInstance?> {
        return com.google.common.collect.ImmutableList.copyOf<MacroInstance?>(macrosByName.values())
    }

    private fun noSuchTargetException(targetName: String?): NoSuchTargetException {
        val label: Label?
        try {
            label = Label.create(getPackageIdentifier(), targetName)
        } catch (e: LabelSyntaxException) {
            throw java.lang.IllegalArgumentException(targetName, e)
        }

        if (getMetadata().succinctTargetNotFoundErrors) {
            return NoSuchTargetException(
                label,
                java.lang.String.format("target '%s' not declared in %s", targetName, getShortDescription())
            )
        } else {
            val alternateTargetSuggestion: String =
                com.google.devtools.build.lib.packages.Package.Companion.getAlternateTargetSuggestion(
                    getMetadata(),
                    targetName,
                    targets.keySet()
                )
            return NoSuchTargetException(
                label,
                java.lang.String.format(
                    "target '%s' not declared in %s%s",
                    targetName, getShortDescription(), alternateTargetSuggestion
                )
            )
        }
    }

    override fun toString(): String {
        return java.lang.String.format(
            "PackagePiece(%s defined by %s)=%s",
            getIdentifier().getCanonicalFormName(),
            getCanonicalFormDefinedBy(),
            if (targets != null) getTargets<com.google.devtools.build.lib.packages.Rule?>(com.google.devtools.build.lib.packages.Rule::class.java) else "initializing..."
        )
    }

    /**
     * Returns the canonical form of the BUILD file label if this is a [ ], or the canonical form of the macro class's declaring .bzl label and
     * macro name, in `label%name` format, if this is a [PackagePiece.ForMacro].
     */
    abstract fun getCanonicalFormDefinedBy(): String?

    /**
     * Sets the macros map for this package piece. Intended only to be called by this package piece's
     * builder.
     * 
     * @param macros a collection of macro instances, which must have unique names.
     */
    protected fun setMacrosByName(macros: MutableCollection<MacroInstance>) {
        val macrosByName: com.google.common.collect.ImmutableSortedMap.Builder<String?, MacroInstance?> =
            com.google.common.collect.ImmutableSortedMap.naturalOrder<String?, MacroInstance?>()
        for (macro in macros) {
            macrosByName.put(macro.getName(), macro)
        }
        this.macrosByName = macrosByName.buildOrThrow()
    }

    /**
     * A [PackagePiece] obtained by evaluating a BUILD file, without expanding any symbolic
     * macros.
     */
    class ForBuildFile private constructor(
        identifier: com.google.devtools.build.lib.packages.PackagePieceIdentifier.ForBuildFile,
        metadata: com.google.devtools.build.lib.packages.Package.Metadata
    ) : PackagePiece(metadata, com.google.devtools.build.lib.packages.Package.Declarations.Builder()) {
        private val identifier: com.google.devtools.build.lib.packages.PackagePieceIdentifier.ForBuildFile

        // Can be changed during BUILD file evaluation due to exports_files() modifying its visibility.
        // Cannot be in declarations because, since it's a Target, it holds a back reference to this
        // PackagePiece.ForBuildFile object.
        private var buildFile: InputFile? = null

        override fun getIdentifier(): com.google.devtools.build.lib.packages.PackagePieceIdentifier.ForBuildFile {
            return identifier
        }

        override fun getCanonicalFormDefinedBy(): String {
            return getMetadata().buildFileLabel.getCanonicalForm()
        }

        override fun getShortDescription(): String? {
            return java.lang.String.format("top-level package piece defined by %s", getCanonicalFormDefinedBy())
        }

        /** Returns the InputFile target for this package's BUILD file.  */
        fun getBuildFile(): InputFile? {
            return buildFile
        }

        override fun checkMacroNamespaceCompliance(target: com.google.devtools.build.lib.packages.Target) {
            com.google.common.base.Preconditions.checkArgument(
                this == target.getPackageoid(),
                "Target must belong to this packageoid"
            )
            // No-op: no macros to violate.
        }

        init {
            com.google.common.base.Preconditions.checkArgument(
                identifier.getPackageIdentifier().equals(metadata.packageIdentifier)
            )
            this.identifier = identifier
        }

        /** A builder for [PackagePiece.ForBuildFile] objects.  */
        class Builder private constructor(
            forBuildFile: ForBuildFile,
            precomputeTransitiveLoads: Boolean,
            noImplicitFileExport: Boolean,
            simplifyUnconditionalSelectsInRuleAttrs: Boolean,
            mainRepositoryMapping: RepositoryMapping?,
            cpuBoundSemaphore: Semaphore?,
            packageOverheadEstimator: PackageOverheadEstimator?,
            generatorMap: com.google.common.collect.ImmutableMap<net.starlark.java.syntax.Location?, String?>?,
            globber: Globber?,
            enableNameConflictChecking: Boolean,
            trackFullMacroInformation: Boolean,
            packageLimits: PackageLimits?
        ) : com.google.devtools.build.lib.packages.Package.AbstractBuilder(
            forBuildFile.getMetadata(),
            forBuildFile,
            net.starlark.java.eval.SymbolGenerator.create<com.google.devtools.build.lib.packages.PackagePieceIdentifier.ForBuildFile?>(
                forBuildFile.getIdentifier()
            ),
            precomputeTransitiveLoads,
            noImplicitFileExport,
            simplifyUnconditionalSelectsInRuleAttrs,
            mainRepositoryMapping,
            cpuBoundSemaphore,
            packageOverheadEstimator,
            generatorMap,
            globber,
            enableNameConflictChecking,
            trackFullMacroInformation,  /* enableTargetMapSnapshotting= */
            false,
            packageLimits
        ) {
            fun getPackagePiece(): ForBuildFile? {
                return pkg as ForBuildFile?
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            override fun setLoads(directLoads: Iterable<net.starlark.java.eval.Module?>?): Builder? {
                return super.setLoads(directLoads) as Builder?
            }

            override fun eagerlyExpandMacros(): Boolean {
                return false
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            @Throws(NoSuchPackageException::class)
            override fun buildPartial(): Builder? {
                return super.buildPartial() as Builder?
            }

            override fun setBuildFile(buildFile: InputFile?) {
                (pkg as ForBuildFile).buildFile =
                    com.google.common.base.Preconditions.checkNotNull<InputFile?>(buildFile)
            }

            override fun finishBuild(): ForBuildFile? {
                return super.finishBuild() as ForBuildFile?
            }

            override fun packageoidInitializationHook() {
                super.packageoidInitializationHook()
                getPackagePiece().computationSteps = getComputationSteps()
                getPackagePiece().setMacrosByName(recorder.getMacroMap().values())
            }

            companion object {
                /** Retrieves this object from a Starlark thread. Returns null if not present.  */
                fun fromOrNull(thread: net.starlark.java.eval.StarlarkThread): Builder? {
                    val ctx: StarlarkThreadContext? =
                        thread.getThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java)
                    return if (ctx is Builder) ctx else null
                }
            }
        }

        companion object {
            /** Creates a new [PackagePiece.ForBuildFile.Builder].  */ // TODO(bazel-team): when JEP 482 ("flexible constructors") is enabled, we can remove this
            // method and use the builder's constructor directly.
            fun newBuilder(
                packageSettings: PackageSettings,
                identifier: com.google.devtools.build.lib.packages.PackagePieceIdentifier.ForBuildFile,
                filename: RootedPath?,
                workspaceName: String?,
                associatedModuleName: java.util.Optional<String?>?,
                associatedModuleVersion: java.util.Optional<String?>?,
                noImplicitFileExport: Boolean,
                simplifyUnconditionalSelectsInRuleAttrs: Boolean,
                repositoryMapping: RepositoryMapping?,
                mainRepositoryMapping: RepositoryMapping?,
                cpuBoundSemaphore: Semaphore?,
                packageOverheadEstimator: PackageOverheadEstimator?,
                generatorMap: com.google.common.collect.ImmutableMap<net.starlark.java.syntax.Location?, String?>?,
                configSettingVisibilityPolicy: ConfigSettingVisibilityPolicy?,
                globber: Globber?,
                enableNameConflictChecking: Boolean,
                trackFullMacroInformation: Boolean,
                packageLimits: PackageLimits?
            ): Builder {
                val metadata: com.google.devtools.build.lib.packages.Package.Metadata =
                    com.google.devtools.build.lib.packages.Package.Metadata.Companion.builder()
                        .packageIdentifier(identifier.getPackageIdentifier())
                        .buildFilename(filename)
                        .workspaceName(workspaceName)
                        .repositoryMapping(repositoryMapping)
                        .associatedModuleName(associatedModuleName)
                        .associatedModuleVersion(associatedModuleVersion)
                        .configSettingVisibilityPolicy(configSettingVisibilityPolicy)
                        .succinctTargetNotFoundErrors(packageSettings.succinctTargetNotFoundErrors())
                        .build()
                val forBuildFile: ForBuildFile =
                    com.google.devtools.build.lib.packages.PackagePiece.ForBuildFile(identifier, metadata)
                return com.google.devtools.build.lib.packages.PackagePiece.ForBuildFile.Builder(
                    forBuildFile,
                    packageSettings.precomputeTransitiveLoads(),
                    noImplicitFileExport,
                    simplifyUnconditionalSelectsInRuleAttrs,
                    mainRepositoryMapping,
                    cpuBoundSemaphore,
                    packageOverheadEstimator,
                    generatorMap,
                    globber,
                    enableNameConflictChecking,
                    trackFullMacroInformation,
                    packageLimits
                )
            }
        }
    }

    /** A [PackagePiece] obtained by evaluating a symbolic macro instance.  */
    class ForMacro private constructor(
        metadata: com.google.devtools.build.lib.packages.Package.Metadata,
        declarations: Declarations,
        evaluatedMacro: MacroInstance,
        parentIdentifier: PackagePieceIdentifier
    ) : PackagePiece(metadata, declarations.checkImmutable()) {
        private val identifier: com.google.devtools.build.lib.packages.PackagePieceIdentifier.ForMacro
        private val evaluatedMacro: MacroInstance

        // Null until the package piece is fully initialized by its builder's {@code finishBuild()}.
        private var macroNamespaceViolations: com.google.common.collect.ImmutableSet<String?>? = null

        override fun getIdentifier(): com.google.devtools.build.lib.packages.PackagePieceIdentifier.ForMacro {
            return identifier
        }

        override fun getCanonicalFormDefinedBy(): String? {
            val macroClass: MacroClass = evaluatedMacro.getMacroClass()
            return java.lang.String.format(
                "%s%%%s", macroClass.getDefiningBzlLabel().getCanonicalForm(), macroClass.getName()
            )
        }

        override fun getShortDescription(): String? {
            return java.lang.String.format(
                "package piece for %smacro %s defined by %s",
                if (getEvaluatedMacro().getMacroClass().isFinalizer()) "finalizer " else "",
                getIdentifier().getCanonicalFormName(),
                getCanonicalFormDefinedBy()
            )
        }

        fun getEvaluatedMacro(): MacroInstance {
            return evaluatedMacro
        }

        /**
         * Returns the ID of the package of the .bzl file declaring the macro which was expanded to
         * produce this package piece; it is considered to be the location in which this package piece's
         * targets are declared for visibility purposes.
         */
        fun getDeclaringPackage(): PackageIdentifier {
            return evaluatedMacro.getMacroClass().getDefiningBzlLabel().getPackageIdentifier()
        }

        @Throws(MacroNamespaceViolationException::class)
        override fun checkMacroNamespaceCompliance(target: com.google.devtools.build.lib.packages.Target) {
            com.google.common.base.Preconditions.checkArgument(
                this == target.getPackageoid(),
                "Target must belong to this packageoid"
            )
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSet<String?>?>(
                macroNamespaceViolations,
                "This method is only available after the package piece has been fully initialized."
            )
            if (macroNamespaceViolations.contains(target.getName())) {
                throw MacroNamespaceViolationException(
                    java.lang.String.format(
                        "Target %s declared in symbolic macro '%s' violates macro naming rules and cannot"
                                + " be built. %s",
                        target.getLabel(), evaluatedMacro.getName(), TargetRecorder.Companion.MACRO_NAMING_RULES
                    ),
                    target
                )
            }
        }

        init {
            com.google.common.base.Preconditions.checkArgument(
                metadata
                    .packageIdentifier
                    .equals(evaluatedMacro.getPackageMetadata().packageIdentifier)
            )
            com.google.common.base.Preconditions.checkArgument(metadata.packageIdentifier.equals(parentIdentifier.getPackageIdentifier()))
            if (evaluatedMacro.getParent() != null) {
                com.google.devtools.build.lib.packages.PackagePiece.ForMacro.Companion.checkIdentifierMatchesMacro(
                    parentIdentifier as com.google.devtools.build.lib.packages.PackagePieceIdentifier.ForMacro,
                    evaluatedMacro.getParent()
                )
            } else {
                com.google.common.base.Preconditions.checkArgument(parentIdentifier is com.google.devtools.build.lib.packages.PackagePieceIdentifier.ForBuildFile)
            }
            this.identifier =
                com.google.devtools.build.lib.packages.PackagePieceIdentifier.ForMacro(
                    metadata.packageIdentifier, parentIdentifier, evaluatedMacro.getName()
                )
            this.evaluatedMacro = evaluatedMacro
        }

        /** A builder for [PackagePieceForMacro] objects.  */
        class Builder private constructor(
            forMacro: ForMacro,
            simplifyUnconditionalSelectsInRuleAttrs: Boolean,
            mainRepositoryMapping: RepositoryMapping?,
            cpuBoundSemaphore: Semaphore?,
            packageOverheadEstimator: PackageOverheadEstimator?,
            enableNameConflictChecking: Boolean,
            trackFullMacroInformation: Boolean,
            packageLimits: PackageLimits?,
            existingRulesMapForFinalizer: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.packages.Rule?>?
        ) : TargetDefinitionContext(
            forMacro.getMetadata(),
            forMacro,
            net.starlark.java.eval.SymbolGenerator.create<com.google.devtools.build.lib.packages.PackagePieceIdentifier.ForMacro?>(
                forMacro.getIdentifier()
            ),
            simplifyUnconditionalSelectsInRuleAttrs,
            mainRepositoryMapping,
            cpuBoundSemaphore,
            packageOverheadEstimator,  /* generatorMap= */
            null,  /* globber= */
            null,
            enableNameConflictChecking,
            trackFullMacroInformation,  /* enableTargetMapSnapshotting= */
            false,
            packageLimits
        ) {
            // Non-null iff this is a builder for a finalizer package piece and the non-finalizer package
            // pieces that it depends upon are not in error. Used for native.existing_rules() and
            // native.existing_rule().
            private val existingRulesMapForFinalizer: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.packages.Rule?>?

            fun getPackagePiece(): ForMacro {
                return pkg as ForMacro
            }

            override fun eagerlyExpandMacros(): Boolean {
                return false
            }

            /** Can only be called for a finalizer package piece.  */
            override fun getRulesSnapshotView(): MutableMap<String?, com.google.devtools.build.lib.packages.Rule?> {
                com.google.common.base.Preconditions.checkState(
                    getPackagePiece().getEvaluatedMacro().getMacroClass().isFinalizer(),
                    "%s is defined by a non-finalizer macro",
                    getPackagePiece().getShortDescription()
                )
                return
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.packages.Rule?>?>(
                    existingRulesMapForFinalizer,
                    "native.existing_rules map was not set in builder for %s",
                    getPackagePiece().getShortDescription()
                )
            }

            /** Can only be called for a finalizer package piece.  */
            override fun getNonFinalizerInstantiatedRule(name: String?): com.google.devtools.build.lib.packages.Rule? {
                return getRulesSnapshotView().get(name)
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            @Throws(NoSuchPackageException::class)
            override fun buildPartial(): Builder? {
                return super.buildPartial() as Builder?
            }

            override fun finishBuild(): ForMacro? {
                return super.finishBuild() as ForMacro?
            }

            override fun packageoidInitializationHook() {
                getPackagePiece().computationSteps = getComputationSteps()
                super.packageoidInitializationHook()
                val forMacro = getPackagePiece()
                forMacro.setMacrosByName(recorder.getMacroMap().values())
                forMacro.macroNamespaceViolations =
                    com.google.common.collect.ImmutableSet.copyOf<String?>(
                        recorder.getMacroNamespaceViolatingTargets().keySet()
                    )
            }

            init {
                this.existingRulesMapForFinalizer = existingRulesMapForFinalizer
            }

            companion object {
                /** Retrieves this object from a Starlark thread. Returns null if not present.  */
                fun fromOrNull(thread: net.starlark.java.eval.StarlarkThread): Builder? {
                    val ctx: StarlarkThreadContext? =
                        thread.getThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java)
                    return if (ctx is Builder) ctx else null
                }
            }
        }

        companion object {
            private fun checkIdentifierMatchesMacro(
                identifier: com.google.devtools.build.lib.packages.PackagePieceIdentifier.ForMacro, macro: MacroInstance
            ) {
                com.google.common.base.Preconditions.checkArgument(
                    macro.getPackageMetadata().packageIdentifier.equals(identifier.getPackageIdentifier())
                )
                com.google.common.base.Preconditions.checkArgument(macro.getName() == identifier.getInstanceName())
            }

            /** Creates a new [PackagePiece.ForMacro.Builder].  */ // TODO(bazel-team): when JEP 482 ("flexible constructors") is enabled, we can remove this
            // method and use the builder's constructor directly.
            fun newBuilder(
                metadata: com.google.devtools.build.lib.packages.Package.Metadata,
                declarations: Declarations,
                evaluatedMacro: MacroInstance,
                parentIdentifier: PackagePieceIdentifier,
                simplifyUnconditionalSelectsInRuleAttrs: Boolean,
                mainRepositoryMapping: RepositoryMapping?,
                cpuBoundSemaphore: Semaphore?,
                packageOverheadEstimator: PackageOverheadEstimator?,
                enableNameConflictChecking: Boolean,
                trackFullMacroInformation: Boolean,
                packageLimits: PackageLimits?,
                existingRulesMapForFinalizer: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.packages.Rule?>?
            ): Builder {
                val forMacro: ForMacro = com.google.devtools.build.lib.packages.PackagePiece.ForMacro(
                    metadata,
                    declarations,
                    evaluatedMacro,
                    parentIdentifier
                )
                return com.google.devtools.build.lib.packages.PackagePiece.ForMacro.Builder(
                    forMacro,
                    simplifyUnconditionalSelectsInRuleAttrs,
                    mainRepositoryMapping,
                    cpuBoundSemaphore,
                    packageOverheadEstimator,
                    enableNameConflictChecking,
                    trackFullMacroInformation,
                    packageLimits,
                    existingRulesMapForFinalizer
                )
            }
        }
    }
}
