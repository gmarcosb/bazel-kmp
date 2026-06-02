// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.BazelModuleContext

/**
 * A package, which is a container of [Rule]s, each of which contains a dictionary of named
 * attributes.
 * 
 * 
 * Package instances are intended to be immutable and for all practical purposes can be treated
 * as such. Note, however, that some member variables exposed via the public interface are not
 * strictly immutable, so until their types are guaranteed immutable we're not applying the
 * `@Immutable` annotation here.
 * 
 * 
 * This class should not be extended - it's only non-final for mocking!
 * 
 * 
 * When changing this class, make sure to make corresponding changes to serialization!
 */
class Package  // ==== Constructor ====
/**
 * Constructs a new (incomplete) Package instance. Intended only for use by [ ].
 * 
 * 
 * Packages and Targets refer to one another. Therefore, the builder needs to have a Package
 * instance on-hand before it can associate any targets with the package. The [ ] fields like the package's name must be known before that point, while other
 * fields are filled in only when the builder calls [Builder.finishBuild].
 */
// TODO(#19922): Better separate fields that must be known a priori from those determined through
// BUILD evaluation.
private constructor(metadata: Metadata?, declarations: Declarations?) : Packageoid(metadata, declarations) {
    // TODO(bazel-team): This class and its builder are ginormous. Future refactoring work might
    // attempt to separate the concerns of:
    //   - instantiating targets/macros, adding them to the package, and accessing/indexing them
    //     afterwards
    //   - utility logical like validating names, checking for conflicts, etc.
    //   - tracking and enforcement of limits
    // ==== Static fields and enums ====
    /**
     * How to enforce config_setting visibility settings.
     * 
     * 
     * This is a temporary setting in service of https://github.com/bazelbuild/bazel/issues/12669.
     * After enough depot cleanup, config_setting will have the same visibility enforcement as all
     * other rules.
     */
    enum class ConfigSettingVisibilityPolicy {
        /** Don't enforce visibility for any config_setting.  */
        LEGACY_OFF,

        /** Honor explicit visibility settings on config_setting, else use //visibility:public.  */
        DEFAULT_PUBLIC,

        /** Enforce config_setting visibility exactly the same as all other rules.  */
        DEFAULT_STANDARD
    }

    // ==== Target and macro fields ====
    // Can be changed during BUILD file evaluation due to exports_files() modifying its visibility.
    // Cannot be in Declarations because, since it's a Target, it holds a back reference to this
    // Package object.
    private var buildFile: InputFile? = null

    /**
     * The collection of all symbolic macro instances defined in this package, indexed by their [ ][MacroInstance.getId] (not name). Null until the package is fully initialized by its
     * builder's `finishBuild()`.
     */
    // TODO(bazel-team): Consider enforcing that macro namespaces are "exclusive", meaning that target
    // names may only suffix a macro name when the target is created (transitively) within the macro.
    // This would be a major change that would break the (common) use case where a BUILD file
    // declares both "foo" and "foo_test".
    private var macros: com.google.common.collect.ImmutableSortedMap<String?, MacroInstance?>? = null

    /**
     * A map from names of targets declared in a symbolic macro which violate macro naming rules, such
     * as "lib%{name}-src.jar" implicit outputs in java rules, to the name of the macro instance where
     * they were declared.
     * 
     * 
     * Initialized by the builder in [Builder.finishBuild].
     */
    private var macroNamespaceViolatingTargets: com.google.common.collect.ImmutableMap<String?, String?>? = null

    /**
     * A map from names of targets declared in a symbolic macro to the (innermost) macro instance
     * where they were declared. Omits targets not declared in symbolic macros.
     * 
     * 
     * Null for packages produced by deserialization.
     */
    // TODO: #19922 - If this field were made serializable (currently it's not), it would subsume
    // macroNamespaceViolatingTargets, since we can just map the target to its macro and then check
    // whether it is in the macro's namespace.
    //
    // TODO: #19922 - Don't maintain this extra map of all macro-instantiated targets. We have a
    // couple options:
    //   1) Have Target store a reference to its declaring MacroInstance directly. To avoid adding a
    //      field to that class (a not insignificant cost), we can merge it with the reference to its
    //      package: If we're not in a macro, we point to the package, and if we are, we point to the
    //      innermost macro, and hop to the MacroInstance to get a reference to the Package (or parent
    //      macro).
    //   2) To support lazy macro evaluation, we'll probably need a prefix trie in Package to find the
    //      macros whose namespaces contain the requested target name. For targets that respect their
    //      macro's namespace, we could just look them up in the trie. This assumes we already know
    //      whether the target is well-named, which we wouldn't if we got rid of
    //      macroNamespaceViolatingTargets.
    private var targetsToDeclaringMacro: com.google.common.collect.ImmutableMap<String?, MacroInstance?>? = null

    /**
     * A map from names of targets declared in a symbolic macro to the package where the macro that
     * declared it was defined, as per [MacroInstance.getDefinitionPackage]. Omits targets not
     * declared in symbolic macros.
     * 
     * 
     * Null for packages not produced by deserialization.
     */
    private var targetsToDeclaringPackage: com.google.common.collect.ImmutableMap<String?, PackageIdentifier?>? = null

    // ==== General package metadata accessors ====
    /**
     * Returns the name of this package. If this build is using external repositories then this name
     * may not be unique!
     */
    fun getName(): String {
        return metadata.getName()
    }

    /** Like [.getName], but has type `PathFragment`.  */
    fun getNameFragment(): PathFragment {
        return getPackageIdentifier().getPackageFragment()
    }

    /**
     * Returns the filename of the BUILD file which defines this package. The parent directory of the
     * BUILD file is the package directory.
     */
    fun getFilename(): RootedPath? {
        return metadata.buildFilename
    }

    /** Returns the directory containing the package's BUILD file.  */
    fun getPackageDirectory(): com.google.devtools.build.lib.vfs.Path? {
        return metadata.getPackageDirectory()
    }

    /**
     * How to enforce visibility on `config_setting` See [ ] for details.
     */
    fun getConfigSettingVisibilityPolicy(): ConfigSettingVisibilityPolicy? {
        return metadata.configSettingVisibilityPolicy
    }

    /** Convenience wrapper for [Metadata.workspaceName]  */
    fun getWorkspaceName(): String? {
        return getMetadata().workspaceName
    }

    /** Returns the InputFile target for this package's BUILD file.  */
    fun getBuildFile(): InputFile? {
        return buildFile
    }

    /** Convenience wrapper for [Declarations.getPackageArgs]  */
    fun getPackageArgs(): PackageArgs {
        return getDeclarations().getPackageArgs()
    }

    /** Convenience wrapper for [Declarations.getMakeEnvironment]  */
    fun getMakeEnvironment(): com.google.common.collect.ImmutableMap<String?, String?>? {
        return getDeclarations().getMakeEnvironment()
    }

    /**
     * Returns the root of the source tree beneath which this package's BUILD file was found.
     * 
     * 
     * Assumes invariant: `getSourceRoot().getRelative(packageId.getSourceRoot()).equals(getPackageDirectory())`
     */
    fun getSourceRoot(): Root? {
        return metadata.sourceRoot
    }

    // ==== Target and macro accessors ====
    /**
     * Returns a (read-only, ordered) iterable of all the targets belonging to this package which are
     * instances of the specified class.
     */
    fun <T : com.google.devtools.build.lib.packages.Target?> getTargets(targetClass: java.lang.Class<T?>): Iterable<T?> {
        return com.google.common.collect.Iterables.filter<T?>(targets.values(), targetClass)
    }

    /**
     * Returns the rule that corresponds to a particular BUILD target name. Useful for walking through
     * the dependency graph of a target. Fails if the target is not a Rule.
     */
    fun getRule(targetName: String?): com.google.devtools.build.lib.packages.Rule? {
        return targets.get(targetName) as com.google.devtools.build.lib.packages.Rule?
    }

    /**
     * Returns a map from names of targets declared in a symbolic macro which violate macro naming
     * rules, such as "lib%{name}-src.jar" implicit outputs in java rules, to the name of the macro
     * instance where they were declared.
     */
    fun getMacroNamespaceViolatingTargets(): com.google.common.collect.ImmutableMap<String?, String?> {
        com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMap<String?, String?>?>(
            macroNamespaceViolatingTargets,
            "This method is only available after the package has been loaded."
        )
        return macroNamespaceViolatingTargets
    }

    /**
     * Returns a map from names of targets declared in a symbolic macro to the package containing said
     * macro's .bzl code.
     */
    fun getTargetsToDeclaringPackage(): com.google.common.collect.ImmutableMap<String?, PackageIdentifier?> {
        if (targetsToDeclaringPackage != null) {
            return targetsToDeclaringPackage
        } else {
            val result: com.google.common.collect.ImmutableMap.Builder<String?, PackageIdentifier?> =
                com.google.common.collect.ImmutableMap.builder<String?, PackageIdentifier?>()
            for (entry in targetsToDeclaringMacro.entrySet()) {
                result.put(entry.getKey(), entry.getValue().getDefinitionPackage())
            }
            return result.buildOrThrow()
        }
    }

    @Throws(MacroNamespaceViolationException::class)
    override fun checkMacroNamespaceCompliance(target: com.google.devtools.build.lib.packages.Target) {
        com.google.common.base.Preconditions.checkArgument(
            this == target.getPackage(), "Target must belong to this package"
        )
        val macroNamespaceViolated: String? = getMacroNamespaceViolatingTargets().get(target.getName())
        if (macroNamespaceViolated != null) {
            throw MacroNamespaceViolationException(
                java.lang.String.format(
                    "Target %s declared in symbolic macro '%s' violates macro naming rules and cannot be"
                            + " built. %s",
                    target.getLabel(), macroNamespaceViolated, TargetRecorder.Companion.MACRO_NAMING_RULES
                ),
                target
            )
        }
    }

    @Throws(NoSuchTargetException::class)
    override fun getTarget(targetName: String?): com.google.devtools.build.lib.packages.Target {
        val target: com.google.devtools.build.lib.packages.Target? = targets.get(targetName)
        if (target != null) {
            return target
        }

        val label: Label?
        try {
            label = Label.create(metadata.packageIdentifier, targetName)
        } catch (e: LabelSyntaxException) {
            throw java.lang.IllegalArgumentException(targetName, e)
        }

        if (metadata.succinctTargetNotFoundErrors) {
            throw NoSuchTargetException(
                label, java.lang.String.format("target '%s' not declared in package '%s'", targetName, getName())
            )
        } else {
            val alternateTargetSuggestion: String =
                com.google.devtools.build.lib.packages.Package.Companion.getAlternateTargetSuggestion(
                    metadata,
                    targetName,
                    targets.keySet()
                )
            throw NoSuchTargetException(
                label,
                java.lang.String.format(
                    "target '%s' not declared in package '%s' defined by %s%s",
                    targetName,
                    getName(),
                    metadata.buildFilename.asPath().getPathString(),
                    alternateTargetSuggestion
                )
            )
        }
    }

    /**
     * Returns all symbolic macros defined in the package, indexed by [id][MacroInstance.getId].
     * 
     * 
     * Note that `MacroInstance`s hold just the information known at the time a macro was
     * declared, even though by the time the `Package` is fully constructed we already have
     * fully evaluated these macros.
     */
    fun getMacrosById(): com.google.common.collect.ImmutableMap<String?, MacroInstance?>? {
        return macros
    }

    /**
     * Returns the (innermost) symbolic macro instance that declared the given target, or null if the
     * target was not created in a symbolic macro.
     * 
     * 
     * Throws [IllegalArgumentException] if the given name is not a target in this package.
     * 
     * 
     * For packages produced by deserialization, this information is not available and `IllegalStateException` is thrown.
     */
    fun getDeclaringMacroForTarget(target: String?): MacroInstance? {
        com.google.common.base.Preconditions.checkState(
            targetsToDeclaringMacro != null,
            "Cannot retrieve MacroInstance information from deserialized packages"
        )
        com.google.common.base.Preconditions.checkArgument(targets.containsKey(target), "unknown target '%s'", target)
        return targetsToDeclaringMacro.get(target)
    }

    /**
     * Returns the id of the package where the (innermost) macro that declared the given target was
     * defined (as per [MacroInstance.getDefinitionLocation]), or null if the target was not
     * created in a symbolic macro.
     * 
     * 
     * The caller should interpret a null result to mean that the declaration location of the
     * target is this package.
     * 
     * 
     * Throws [IllegalArgumentException] if the given name is not a target in this package.
     */
    fun getDeclaringPackageForTargetIfInMacro(target: String?): PackageIdentifier? {
        com.google.common.base.Preconditions.checkArgument(targets.containsKey(target), "unknown target '%s'", target)
        // Exactly one of targetsToDeclaringMacro and targetsToDeclaringPackage is non-null, depending
        // on whether this package was produced by deserialization.
        if (targetsToDeclaringMacro != null) {
            val macro: MacroInstance? = targetsToDeclaringMacro.get(target)
            return if (macro != null) macro.getDefinitionPackage() else null
        } else {
            return targetsToDeclaringPackage.get(target)
        }
    }

    // ==== Stringification / debugging ====
    override fun toString(): String {
        return ("Package("
                + getName()
                + ")="
                + (if (targets != null) getTargets<com.google.devtools.build.lib.packages.Rule?>(com.google.devtools.build.lib.packages.Rule::class.java) else "initializing..."))
    }

    override fun getShortDescription(): String {
        return "package " + getPackageIdentifier().getCanonicalForm()
    }

    /**
     * Dumps the package for debugging. Do not depend on the exact format/contents of this debugging
     * output.
     */
    fun dump(out: PrintStream) {
        out.println("  Package " + getName() + " (" + metadata.buildFilename.asPath() + ")")

        // Rules:
        out.println("    Rules")
        for (rule in getTargets<com.google.devtools.build.lib.packages.Rule>(com.google.devtools.build.lib.packages.Rule::class.java)) {
            out.println("      " + rule.getTargetKind() + " " + rule.getLabel())
            for (attr in rule.getAttributes()) {
                for (possibleValue in AggregatingAttributeMapper.Companion.of(rule)
                    .visitAttribute(attr.getName(), attr.getType())) {
                    out.println("        " + attr.getName() + " = " + possibleValue)
                }
            }
        }

        // Files:
        out.println("    Files")
        for (file in getTargets<FileTarget>(FileTarget::class.java)) {
            out.print("      " + file.getTargetKind() + " " + file.getLabel())
            if (file is OutputFile) {
                out.println(" (generated by " + (file as OutputFile).getGeneratingRule().getLabel() + ")")
            } else {
                out.println()
            }
        }
    }

    // ==== Non-trivial nested classes ====
    /**
     * Common base class for builders for [Package] and [PackagePiece.ForBuildFile]
     * objects, containing the shared logic for processing top-level BUILD file declarations, for
     * example the "package" callable.
     */
    // TODO(https://github.com/bazelbuild/bazel/issues/23852): this class should be moved elsewhere -
    // probably to an inner clas of Packageoid - but that would require also moving Declarations and
    // PackageArgs, so that their private fields can be mutated only by the builder.
    abstract class AbstractBuilder internal constructor(
        metadata: Metadata,
        pkg: Packageoid?,
        symbolGenerator: net.starlark.java.eval.SymbolGenerator<*>?,
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
        enableTargetMapSnapshotting: Boolean,
        packageLimits: PackageLimits?
    ) : TargetDefinitionContext(
        metadata,
        pkg,
        symbolGenerator,
        simplifyUnconditionalSelectsInRuleAttrs,
        mainRepositoryMapping,
        cpuBoundSemaphore,
        packageOverheadEstimator,
        generatorMap,
        globber,
        enableNameConflictChecking,
        trackFullMacroInformation,
        enableTargetMapSnapshotting,
        packageLimits
    ) {
        private val precomputeTransitiveLoads: Boolean

        /** True iff the "package" function has already been called in this BUILD file.  */
        private var packageFunctionUsed = false

        // The following field is populated by setLoads() and should be used only for analysis_test.
        // TODO: b/291752414 - This should be serialized but only if needed for analysis_test;
        // serializing unconditionally would interfere with lazy symbolic macro change pruning.
        private var transitiveBzlDigest = ByteArray(0)

        protected val noImplicitFileExport: Boolean

        // TODO(#19922): Require this to be set before BUILD evaluation.
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        open fun setLoads(directLoads: Iterable<net.starlark.java.eval.Module?>): AbstractBuilder? {
            val declarationsBuilder: Declarations.Builder =
                pkg.getDeclarations().checkMutable().checkLoadsNotSet()
            if (precomputeTransitiveLoads) {
                declarationsBuilder.setTransitiveLoads(
                    com.google.devtools.build.lib.packages.Package.AbstractBuilder.Companion.computeTransitiveLoads(
                        directLoads
                    )
                )
            } else {
                declarationsBuilder.setDirectLoads(
                    com.google.common.collect.ImmutableList.copyOf<net.starlark.java.eval.Module?>(
                        directLoads
                    )
                )
            }
            val fp: Fingerprint = Fingerprint()
            for (module in directLoads) {
                fp.addBytes(BazelModuleContext.of(module).bzlTransitiveDigest())
            }
            this.transitiveBzlDigest = fp.digestAndReset()
            return this
        }

        fun getTransitiveBzlDigest(): ByteArray {
            com.google.common.base.Preconditions.checkState(transitiveBzlDigest.size != 0)
            return transitiveBzlDigest
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setTransitiveLoadsForDeserialization(transitiveLoads: com.google.common.collect.ImmutableList<Label?>?): AbstractBuilder {
            pkg.getDeclarations().checkMutable().checkLoadsNotSet().setTransitiveLoads(transitiveLoads)
            return this
        }

        fun mergePackageArgsFrom(packageArgs: PackageArgs?) {
            pkg.getDeclarations().checkMutable().mergePackageArgsFrom(packageArgs)
        }

        fun mergePackageArgsFrom(builder: com.google.devtools.build.lib.packages.PackageArgs.Builder) {
            mergePackageArgsFrom(builder.build())
        }

        fun setMakeVariable(name: String?, value: String?) {
            makeEnv.put(name, value)
        }

        /** Returns whether the "package" function has been called yet  */
        fun isPackageFunctionUsed(): Boolean {
            return packageFunctionUsed
        }

        fun setPackageFunctionUsed() {
            packageFunctionUsed = true
        }

        fun getTargets(): MutableSet<com.google.devtools.build.lib.packages.Target?> {
            return recorder.getTargets()
        }

        /** Adds an environment group to the package. Not valid within symbolic macros.  */
        @Throws(NameConflictException::class, LabelSyntaxException::class)
        fun addEnvironmentGroup(
            name: String?,
            environments: MutableList<Label?>?,
            defaults: MutableList<Label?>?,
            eventHandler: EventHandler,
            location: net.starlark.java.syntax.Location?
        ) {
            com.google.common.base.Preconditions.checkState(currentMacro() == null)

            if (com.google.devtools.build.lib.packages.Package.AbstractBuilder.Companion.hasDuplicateLabels(
                    environments,
                    name,
                    "environments",
                    location,
                    eventHandler
                )
                || com.google.devtools.build.lib.packages.Package.AbstractBuilder.Companion.hasDuplicateLabels(
                    defaults,
                    name,
                    "defaults",
                    location,
                    eventHandler
                )
            ) {
                setContainsErrors()
                return
            }

            val group: EnvironmentGroup =
                EnvironmentGroup(createLabel(name), pkg, environments, defaults, location)
            recorder.addTarget(group)

            // Invariant: once group is inserted into targets, it must also:
            // (a) be inserted into environmentGroups, or
            // (b) have its group.processMemberEnvironments called.
            // Otherwise it will remain uninitialized,
            // causing crashes when it is later toString-ed.
            for (error in group.validateMembership()) {
                eventHandler.handle(error)
                setContainsErrors()
            }

            // For each declared environment, make sure it doesn't also belong to some other group.
            for (environment in group.getEnvironments()) {
                val otherGroup: EnvironmentGroup? = environmentGroups.get(environment)
                if (otherGroup != null) {
                    eventHandler.handle(
                        com.google.devtools.build.lib.packages.Package.Companion.error(
                            location,
                            java.lang.String.format(
                                "environment %s belongs to both %s and %s",
                                environment, group.getLabel(), otherGroup.getLabel()
                            ),
                            Code.ENVIRONMENT_IN_MULTIPLE_GROUPS
                        )
                    )
                    setContainsErrors()
                    // Ensure the orphan gets (trivially) initialized.
                    group.processMemberEnvironments(com.google.common.collect.ImmutableMap.of<String?, com.google.devtools.build.lib.packages.Target?>())
                } else {
                    environmentGroups.put(environment, group)
                }
            }
        }

        @Throws(NoSuchPackageException::class)
        protected fun beforeBuildWithoutDiscoveringAssumedInputFiles() {
            // We create an InputFile corresponding to the BUILD file in Builder's constructor. However,
            // the visibility of this target may be overridden with an exports_files directive, so we wait
            // until now to obtain the current instance from the targets map.
            setBuildFile(recorder.getTargetMap().get(metadata.buildFileLabel.name) as InputFile?)

            super.beforeBuild()
        }

        protected abstract fun setBuildFile(buildFile: InputFile?)

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(NoSuchPackageException::class)
        protected override fun beforeBuild(): AbstractBuilder {
            beforeBuildWithoutDiscoveringAssumedInputFiles()
            val newInputFiles: MutableMap<String?, InputFile?> =
                com.google.devtools.build.lib.packages.Package.AbstractBuilder.Companion.createAssumedInputFiles(
                    pkg,
                    recorder,
                    noImplicitFileExport
                )
            for (file in newInputFiles.values()) {
                recorder.addInputFileUnchecked(file)
            }
            return this
        }

        override fun finalBuilderValidationHook() {
            // Now all targets have been loaded, so we validate the group's member environments.
            for (envGroup in com.google.common.collect.ImmutableSet.copyOf<EnvironmentGroup?>(environmentGroups.values())) {
                val errors: MutableList<Event?> = envGroup.processMemberEnvironments(recorder.getTargetMap())
                if (!errors.isEmpty()) {
                    Event.replayEventsOn(localEventHandler, errors)
                    // TODO(bazel-team): Can't we automatically infer containsError from the presence of
                    // ERRORs on our handler?
                    setContainsErrors()
                }
            }
        }

        override fun packageoidInitializationHook() {
            // Finish Package.Declarations construction.
            if (pkg.getDeclarations() is Declarations.Builder) {
                if (declarationsBuilder.directLoads == null
                    && declarationsBuilder.transitiveLoads == null
                ) {
                    com.google.common.base.Preconditions.checkState(
                        pkg.containsErrors(),
                        "Loads not set for error-free package"
                    )
                    setLoads(com.google.common.collect.ImmutableList.of<net.starlark.java.eval.Module?>())
                }
                pkg.declarations = declarationsBuilder.setMakeEnvironment(makeEnv).build()
            }
        }

        init {
            this.precomputeTransitiveLoads = precomputeTransitiveLoads
            this.noImplicitFileExport = noImplicitFileExport
            if (metadata.getName().startsWith("javatests/")) {
                mergePackageArgsFrom(PackageArgs.Companion.builder().setDefaultTestOnly(true))
            }
            // Add target for the BUILD file itself.
            // (This may be overridden by an exports_file declaration; or, for a package from package
            // pieces, by the PackagePiece.ForBuildFile's BUILD file target set in
            // newPackageFromPackagePiecesBuilder().)
            recorder.addInputFileUnchecked(
                InputFile(pkg, metadata.buildFileLabel, metadata.getBuildFileLocation())
            )
        }

        companion object {
            /** Retrieves this object from a Starlark thread. Returns null if not present.  */
            fun fromOrNull(thread: net.starlark.java.eval.StarlarkThread): AbstractBuilder? {
                val ctx: StarlarkThreadContext? =
                    thread.getThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java)
                return if (ctx is AbstractBuilder) ctx else null
            }

            /**
             * Retrieves this object from a Starlark thread. If not present, throws an [EvalException]
             * with an error message indicating that `what` can only be used in a BUILD file or a
             * legacy macro.
             */
            @com.google.errorprone.annotations.CanIgnoreReturnValue
            @Throws(net.starlark.java.eval.EvalException::class)
            fun fromOrFailAllowBuildOnly(
                thread: net.starlark.java.eval.StarlarkThread, what: String?, participle: String?
            ): AbstractBuilder {
                val ctx: StarlarkThreadContext? =
                    thread.getThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java)
                if (ctx is AbstractBuilder
                    && ctx.recorder.getCurrentMacroFrame() == null
                ) {
                    return ctx
                }
                throw TargetDefinitionContext.Companion.newFromOrFailException(
                    what, participle, thread.getSemantics(), EnumSet.of<FromOrFailMode?>(FromOrFailMode.NO_MACROS)
                )
            }

            /**
             * Retrieves this object from a Starlark thread. If not present, throws an [EvalException]
             * with an error message indicating that `what` can only be used in a BUILD file or a
             * legacy macro.
             */
            @com.google.errorprone.annotations.CanIgnoreReturnValue
            @Throws(net.starlark.java.eval.EvalException::class)
            fun fromOrFailAllowBuildOnly(
                thread: net.starlark.java.eval.StarlarkThread,
                what: String?
            ): AbstractBuilder {
                return com.google.devtools.build.lib.packages.Package.AbstractBuilder.Companion.fromOrFailAllowBuildOnly(
                    thread,
                    what,
                    "used"
                )
            }

            fun computeTransitiveLoads(directLoads: Iterable<net.starlark.java.eval.Module?>?): com.google.common.collect.ImmutableList<Label?> {
                val loads: MutableSet<Label?> = LinkedHashSet<Label?>()
                BazelModuleContext.visitLoadGraphRecursively(directLoads, loads::add)
                return com.google.common.collect.ImmutableList.copyOf<Label?>(loads)
            }

            /**
             * Returns true if any labels in the given list appear multiple times, reporting an appropriate
             * error message if so.
             * 
             * 
             * TODO(bazel-team): apply this to all build functions (maybe automatically?), possibly
             * integrate with RuleClass.checkForDuplicateLabels.
             */
            private fun hasDuplicateLabels(
                labels: MutableList<Label?>?,
                owner: String?,
                attrName: String?,
                location: net.starlark.java.syntax.Location?,
                eventHandler: EventHandler
            ): Boolean {
                val dupes: MutableSet<Label?> = CollectionUtils.duplicatedElementsOf(labels)
                for (dupe in dupes) {
                    eventHandler.handle(
                        com.google.devtools.build.lib.packages.Package.Companion.error(
                            location,
                            java.lang.String.format(
                                "label '%s' is duplicated in the '%s' list of '%s'", dupe, attrName, owner
                            ),
                            Code.DUPLICATE_LABEL
                        )
                    )
                }
                return !dupes.isEmpty()
            }

            /**
             * Creates and returns input files for targets that have been referenced but not explicitly
             * declared in this package.
             * 
             * 
             * Precisely: For each label L appearing in one or more label-typed attributes of one or more
             * declarations D (either of a target or a symbolic macro), we create an `InputFile` for L
             * and return it in the map (keyed by its name) if all of the following are true:
             * 
             * 
             *  1. L points to within the current package.
             *  1. The package does not otherwise declare a target or macro named L.
             *  1. D is not itself declared inside a symbolic macro.
             * 
             * 
             * 
             * The third condition ensures that we can know all *possible* implicitly created input files
             * without evaluating any symbolic macros. However, if the label lies within one or more
             * symbolic macro's namespaces, then we do still need to evaluate those macros to determine
             * whether or not the second condition is true, i.e. whether the label points to a target the
             * macro declares (or a submacro it clashes with), or defaults to an implicitly created input
             * file.
             */
            private fun createAssumedInputFiles(
                pkg: Packageoid, recorder: TargetRecorder, noImplicitFileExport: Boolean
            ): MutableMap<String?, InputFile?> {
                val implicitlyCreatedInputFiles: MutableMap<String?, InputFile?> = HashMap<String?, InputFile?>()

                for (rule in recorder.getRules()) {
                    if (!recorder.isRuleCreatedInMacro(rule)) {
                        for (label in recorder.getRuleLabels(rule)) {
                            com.google.devtools.build.lib.packages.Package.AbstractBuilder.Companion.maybeCreateAssumedInputFile(
                                implicitlyCreatedInputFiles,
                                pkg,
                                recorder,
                                noImplicitFileExport,
                                label,
                                rule.getLocation()
                            )
                        }
                    }
                }

                for (macro in recorder.getMacroMap().values()) {
                    if (macro.getParent() == null) {
                        macro.visitExplicitAttributeLabels(
                            java.util.function.Consumer { label: Label? ->
                                com.google.devtools.build.lib.packages.Package.AbstractBuilder.Companion.maybeCreateAssumedInputFile(
                                    implicitlyCreatedInputFiles,
                                    pkg,
                                    recorder,
                                    noImplicitFileExport,
                                    label,  // TODO(bazel-team): We don't save a MacroInstance's location information yet,
                                    // but when we do, use that here.
                                    net.starlark.java.syntax.Location.BUILTIN
                                )
                            })
                    }
                }

                return implicitlyCreatedInputFiles
            }

            /**
             * Adds an implicitly created input file to the given map if the label points within the current
             * package and there is no existing target or macro for that label.
             */
            private fun maybeCreateAssumedInputFile(
                implicitlyCreatedInputFiles: MutableMap<String?, InputFile?>,
                pkg: Packageoid,
                recorder: TargetRecorder,
                noImplicitFileExport: Boolean,
                label: Label,
                loc: net.starlark.java.syntax.Location?
            ) {
                val name: String? = label.name
                if (!label.getPackageIdentifier().equals(pkg.getPackageIdentifier())) {
                    return
                }
                if (recorder.getTargetMap().containsKey(name)
                    || recorder.hasMacroWithName(name)
                    || implicitlyCreatedInputFiles.containsKey(name)
                ) {
                    return
                }

                implicitlyCreatedInputFiles.put(
                    name,
                    if (noImplicitFileExport)
                        PrivateVisibilityInputFile(pkg, label, loc)
                    else
                        InputFile(pkg, label, loc)
                )
            }
        }
    }

    /**
     * A builder for [Package] objects. Only intended to be used by [PackageFactory] and
     * [com.google.devtools.build.lib.skyframe.PackageFunction].
     */
    class Builder private constructor(
        metadata: Metadata,
        declarations: Declarations?,
        symbolGenerator: net.starlark.java.eval.SymbolGenerator<*>?,
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
    ) : AbstractBuilder(
        metadata,
        com.google.devtools.build.lib.packages.Package(metadata, declarations),
        symbolGenerator,
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
        true,
        packageLimits
    ) {
        /**
         * A bundle of statically-defined options affecting package construction, that is not specific
         * to any particular package and does not change for the lifetime of the server.
         */
        interface PackageSettings {
            /**
             * Returns whether or not extra detail should be added to [NoSuchTargetException]s
             * thrown from [.getTarget]. Useful for toning down verbosity in situations where it can
             * be less helpful.
             */
            // TODO(bazel-team): Arguably, this could be replaced by a boolean param to getTarget(), or
            // some separate action taken by the caller. But there's a lot of call sites that would need
            // updating.
            fun succinctTargetNotFoundErrors(): Boolean {
                return false
            }

            /**
             * Determines whether to precompute a list of transitively loaded starlark files while
             * building packages.
             * 
             * 
             * Typically, direct loads are stored as a `ImmutableList<Module>`. This is
             * sufficient to reconstruct the full load graph by recursively traversing [ ][BazelModuleContext.loads]. If the package is going to be serialized, however, it may make
             * more sense to precompute a flat list containing the labels of all transitively loaded bzl
             * files since [Module] is costly to serialize.
             * 
             * 
             * If this returns `true`, transitive loads are stored as an `ImmutableList<Label>` and direct loads are not stored.
             */
            fun precomputeTransitiveLoads(): Boolean {
                return false
            }

            companion object {
                @kotlin.jvm.JvmField
                val DEFAULTS: PackageSettings = object : PackageSettings {}
            }
        }

        /** A bundle of options affecting resource limits on package construction.  */
        interface PackageLimits {
            /**
             * The maximum number of Starlark computation steps that are allowed to be executed while
             * building a package (or, transitively, any package piece). If this limit is exceeded, the
             * package or package piece immediately stops building.
             * 
             * 
             * Confusingly, for historical Google-specific reasons, this limit is *not* the same
             * as `--max_computation_steps`.
             * 
             * 
             *  * This limit (maxStarlarkComputationStepsPerPackage) is only set by Google-specific
             * logic, is currently not used in open-source Bazel, and exceeding the limit causes the
             * package builder to immediately stop and print a stack trace. The intent is to harden
             * infrastructure against runaway Starlark computations.
             *  * By contrast, `--max_computation_steps` is enforced by [PackageFactory]
             * post-factum, after the package has been built. The intent is to enforce code health
             * by limiting the complexity of packages in a repo.
             * 
             * 
             * 
             * If lazy symbolic macro expansion is enabled, unless a complete [Package] is
             * loaded, the limit is enforced only per package piece.
             */
            // TODO(b/417468797): merge with --max_computation_steps enforcement.
            fun maxStarlarkComputationStepsPerPackage(): Long {
                return java.lang.Long.MAX_VALUE
            }

            companion object {
                @kotlin.jvm.JvmField
                val DEFAULTS: PackageLimits = object : PackageLimits {}
            }
        }

        // The snapshot of {@link #targets} for use in rule finalizer macros. Contains all
        // non-finalizer-instantiated rule targets (i.e. all rule targets except for those instantiated
        // in a finalizer or in a macro called from a finalizer).
        //
        // Initialized by expandAllRemainingMacros() and reset to null by beforeBuild().
        private var rulesSnapshotViewForFinalizers: MutableMap<String?, com.google.devtools.build.lib.packages.Rule?>? =
            null

        /**
         * Ids of all symbolic macros that have been declared but not yet evaluated.
         * 
         * 
         * These are listed in the order they were declared. (This probably doesn't matter, but let's
         * be protective against possible non-determinism.)
         * 
         * 
         * Generally, ordinary symbolic macros are evaluated eagerly and not added to this set, while
         * finalizers, as well as any macros called by finalizers, always use deferred evaluation and
         * end up in here.
         */
        private val unexpandedMacros: MutableSet<String?> = LinkedHashSet<String?>()

        fun getPackage(): Package {
            return pkg as Package
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun setLoads(directLoads: Iterable<net.starlark.java.eval.Module?>): Builder? {
            return super.setLoads(directLoads) as Builder?
        }

        override fun getRulesSnapshotView(): MutableMap<String?, com.google.devtools.build.lib.packages.Rule?>? {
            if (rulesSnapshotViewForFinalizers != null) {
                return rulesSnapshotViewForFinalizers
            } else {
                return super.getRulesSnapshotView()
            }
        }

        override fun getNonFinalizerInstantiatedRule(name: String?): com.google.devtools.build.lib.packages.Rule? {
            if (rulesSnapshotViewForFinalizers != null) {
                return rulesSnapshotViewForFinalizers!!.get(name)
            } else {
                return super.getNonFinalizerInstantiatedRule(name)
            }
        }

        fun addRuleUnchecked(rule: com.google.devtools.build.lib.packages.Rule) {
            com.google.common.base.Preconditions.checkArgument(rule.getPackage() === pkg)
            recorder.addRuleUnchecked(rule)
        }

        /** Adds all targets, macros, and Starlark computation steps from a given package piece.  */
        @Throws(NameConflictException::class)
        fun addAllFromPackagePiece(packagePiece: PackagePiece) {
            // We add the BUILD file in newPackageFromPackagePiecesBuilder(), not here. (We want to ensure
            // that the package always has a BUILD file target, even if addAllFromPackagePiece would throw
            // a name conflict.)
            this.recorder.addAllFromPackagePiece(packagePiece,  /* skipBuildFile= */true)
            this.computationSteps += packagePiece.getComputationSteps()
        }

        override fun eagerlyExpandMacros(): Boolean {
            return true
        }

        @Throws(NameConflictException::class)
        override fun addMacro(macro: MacroInstance) {
            super.addMacro(macro)
            unexpandedMacros.add(macro.getId())
        }

        // For Package deserialization.
        fun putAllMacroNamespaceViolatingTargets(macroNamespaceViolatingTargets: MutableMap<String?, String?>?) {
            recorder.putAllMacroNamespaceViolatingTargets(macroNamespaceViolatingTargets)
        }

        fun putAllTargetsToDeclaringPackage(targetsToDeclaringPackage: MutableMap<String?, PackageIdentifier?>?) {
            recorder.putAllTargetsToDeclaringPackage(targetsToDeclaringPackage)
        }

        /**
         * Marks a symbolic macro as having finished evaluating.
         * 
         * 
         * This will prevent the macro from being run by [.expandAllRemainingMacros].
         * 
         * 
         * The macro must not have previously been marked complete.
         */
        fun markMacroComplete(macro: MacroInstance) {
            val id: String = macro.getId()
            require(unexpandedMacros.remove(id)) {
                java.lang.String.format(
                    "Macro id '%s' unknown or already marked complete",
                    id
                )
            }
        }

        /**
         * Ensures that all symbolic macros in an error-free package have expanded. No-op if the package
         * already [.containsErrors].
         * 
         * 
         * This does not run any macro that has already been evaluated. It *does* run macros that are
         * newly discovered during the operation of this method.
         */
        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        fun expandAllRemainingMacros(semantics: net.starlark.java.eval.StarlarkSemantics?) {
            // TODO: #19922 - Protect against unreasonable macro stack depth and large numbers of symbolic
            // macros overall, for both the eager and deferred evaluation strategies.

            // Note that this operation is idempotent for symmetry with build()/buildPartial(). Though
            // it's not entirely clear that this is necessary.

            // TODO: #19922 - Once compatibility with native.existing_rules() in legacy macros is no
            // longer a concern, we will want to support delayed expansion of non-finalizer macros before
            // the finalizer expansion step.

            // Finalizer expansion step. Requires that the package not be in error (no point in finalizing
            // a package that already threw an EvalException).

            if (!containsErrors() && !unexpandedMacros.isEmpty()) {
                com.google.common.base.Preconditions.checkState(
                    unexpandedMacros.stream()
                        .allMatch(java.util.function.Predicate { id: String? ->
                            recorder.getMacroMap().get(id).getMacroClass().isFinalizer()
                        }),
                    "At the beginning of finalizer expansion, unexpandedMacros must contain only"
                            + " finalizers"
                )

                // Save a snapshot of rule targets for use by native.existing_rules() inside all finalizers.
                // We must take this snapshot before calling any finalizer because the snapshot must not
                // include any rule instantiated by a finalizer or macro called from a finalizer.
                if (rulesSnapshotViewForFinalizers == null) {
                    com.google.common.base.Preconditions.checkState(
                        recorder.getTargetMap() is SnapshottableBiMap<*, *>,
                        "Cannot call expandAllRemainingMacros() after beforeBuild() has been called"
                    )
                    rulesSnapshotViewForFinalizers = getRulesSnapshotView()
                }

                while (!unexpandedMacros.isEmpty()) { // NB: collection mutated by body
                    val id = unexpandedMacros.iterator().next()
                    val macro: MacroInstance? = recorder.getMacroMap().get(id)
                    MacroClass.Companion.executeMacroImplementation(macro, this, semantics)
                }
            }
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(NoSuchPackageException::class)
        public override fun beforeBuild(): Builder {
            // For correct semantics, we refuse to build a package that hasn't thrown any EvalExceptions
            // but has declared symbolic macros that have not yet been expanded. (Currently finalizers are
            // the only use case where this happens, but the Package logic is agnostic to that detail.)
            //
            // Production code should be calling expandAllRemainingMacros() to guarantee that nothing is
            // left unexpanded. Tests that do not declare any symbolic macros need not make the call.
            // Package deserialization doesn't have to do it either, since we shouldn't be evaluating
            // symbolic macros on the deserialized result of an already evaluated package.
            com.google.common.base.Preconditions.checkState(
                unexpandedMacros.isEmpty() || containsErrors(),
                "Cannot build a package with unexpanded symbolic macros; call"
                        + " expandAllRemainingMacros()"
            )

            // SnapshottableBiMap does not allow removing targets (in order to efficiently track rule
            // insertion order). However, we *do* need to support removal of targets in
            // PackageFunction.handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions
            // which is called *between* calls to beforeBuild and finishBuild. We thus repoint the targets
            // map to the SnapshottableBiMap's underlying bimap and thus stop tracking insertion order.
            // After this point, snapshots of targets should no longer be used, and any further
            // getRulesSnapshotView calls will throw.
            if (recorder.getTargetMap() is SnapshottableBiMap<*, *>) {
                recorder.unwrapSnapshottableBiMap()
                rulesSnapshotViewForFinalizers = null
            }

            super.beforeBuild()

            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(NoSuchPackageException::class)
        override fun buildPartial(): Builder? {
            return super.buildPartial() as Builder?
        }

        public override fun setBuildFile(buildFile: InputFile?) {
            (pkg as Package).buildFile = com.google.common.base.Preconditions.checkNotNull<InputFile?>(buildFile)
        }

        override fun finishBuild(): Package? {
            return super.finishBuild() as Package?
        }

        override fun packageoidInitializationHook() {
            super.packageoidInitializationHook()
            val pkg = getPackage()
            pkg.computationSteps = getComputationSteps()
            pkg.macros =
                com.google.common.collect.ImmutableSortedMap.copyOf<String?, MacroInstance?>(recorder.getMacroMap())
            pkg.macroNamespaceViolatingTargets =
                com.google.common.collect.ImmutableMap.copyOf<String?, String?>(recorder.getMacroNamespaceViolatingTargets())
            pkg.targetsToDeclaringMacro =
                if (recorder.getTargetsToDeclaringMacro() != null)
                    com.google.common.collect.ImmutableSortedMap.copyOf<String?, MacroInstance?>(recorder.getTargetsToDeclaringMacro())
                else
                    null
            pkg.targetsToDeclaringPackage =
                if (recorder.getTargetsToDeclaringPackage() != null)
                    com.google.common.collect.ImmutableSortedMap.copyOf<String?, PackageIdentifier?>(recorder.getTargetsToDeclaringPackage())
                else
                    null
        }

        /** Completes package construction. Idempotent.  */ // TODO(brandjon): Do we actually care about idempotence?
        @Throws(NoSuchPackageException::class)
        fun build(): Package? {
            return build( /* discoverAssumedInputFiles= */true)
        }

        /**
         * Constructs the package (or does nothing if it's already built) and returns it.
         * 
         * @param discoverAssumedInputFiles whether to automatically add input file targets to this
         * package for "dangling labels", i.e. labels mentioned in this package that point to an
         * up-until-now non-existent target in this package
         */
        @Throws(NoSuchPackageException::class)
        fun build(discoverAssumedInputFiles: Boolean): Package? {
            if (alreadyBuilt) {
                return getPackage()
            }
            if (discoverAssumedInputFiles) {
                beforeBuild()
            } else {
                beforeBuildWithoutDiscoveringAssumedInputFiles()
            }
            return finishBuild()
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

    /** A collection of data that is known before BUILD file evaluation even begins.  */ // TODO(bazel-team): move to Packageoid.java or to its own file to reduce size of Package.java?
    @AutoCodec
    @kotlin.jvm.JvmRecord
    data class Metadata @Deprecated("Use {@link #builder()} instead.") constructor(
        packageIdentifier: PackageIdentifier?,
        buildFilename: RootedPath?,
        buildFileLabel: Label?,
        workspaceName: String?,
        repositoryMapping: RepositoryMapping?,
        associatedModuleName: java.util.Optional<String?>?,
        associatedModuleVersion: java.util.Optional<String?>?,
        configSettingVisibilityPolicy: ConfigSettingVisibilityPolicy?,
        succinctTargetNotFoundErrors: Boolean,
        sourceRoot: Root?
    ) {
        /** Builder for [Metadata].  */
        @AutoBuilder(callMethod = "of")
        interface Builder {
            fun packageIdentifier(packageIdentifier: PackageIdentifier?): Builder?

            fun buildFilename(buildFilename: RootedPath?): Builder?

            fun workspaceName(workspaceName: String?): Builder?

            fun repositoryMapping(repositoryMapping: RepositoryMapping?): Builder?

            fun associatedModuleName(associatedModuleName: java.util.Optional<String?>?): Builder?

            fun associatedModuleVersion(associatedModuleVersion: java.util.Optional<String?>?): Builder?

            fun configSettingVisibilityPolicy(
                configSettingVisibilityPolicy: ConfigSettingVisibilityPolicy?
            ): Builder?

            fun succinctTargetNotFoundErrors(succinctTargetNotFoundErrors: Boolean): Builder?

            fun build(): Metadata?
        }

        /** Returns the name of this package (sans repository), e.g. "foo/bar".  */
        fun getName(): String {
            return packageIdentifier.getPackageFragment().getPathString()
        }

        /**
         * Returns the directory in which this package's BUILD file resides.
         * 
         * 
         * All InputFile members of the packages are located relative to this directory.
         */
        fun getPackageDirectory(): com.google.devtools.build.lib.vfs.Path? {
            return com.google.devtools.build.lib.packages.Package.Metadata.Companion.getPackageDirectory(buildFilename)
        }

        /** Returns the [Location] of the package's BUILD file.  */
        fun getBuildFileLocation(): net.starlark.java.syntax.Location {
            return net.starlark.java.syntax.Location.fromFile(buildFilename.asPath().toString())
        }

        val packageIdentifier: PackageIdentifier?
        val buildFilename: RootedPath?
        val buildFileLabel: Label?
        val workspaceName: String?
        val repositoryMapping: RepositoryMapping?
        val associatedModuleName: java.util.Optional<String?>?
        val associatedModuleVersion: java.util.Optional<String?>?
        val configSettingVisibilityPolicy: ConfigSettingVisibilityPolicy?
        val succinctTargetNotFoundErrors: Boolean
        val sourceRoot: Root?

        init {
            this.sourceRoot = sourceRoot
            this.succinctTargetNotFoundErrors = succinctTargetNotFoundErrors
            this.configSettingVisibilityPolicy = configSettingVisibilityPolicy
            this.associatedModuleVersion = associatedModuleVersion
            this.associatedModuleName = associatedModuleName
            this.repositoryMapping = repositoryMapping
            this.workspaceName = workspaceName
            this.buildFileLabel = buildFileLabel
            this.buildFilename = buildFilename
            this.packageIdentifier = packageIdentifier
            com.google.common.base.Preconditions.checkNotNull<Any?>(packageIdentifier)
            com.google.common.base.Preconditions.checkNotNull<RootedPath?>(buildFilename)
            com.google.common.base.Preconditions.checkNotNull<String?>(workspaceName)
            com.google.common.base.Preconditions.checkNotNull<Any?>(repositoryMapping)
            com.google.common.base.Preconditions.checkNotNull<java.util.Optional<String?>?>(associatedModuleName)
            com.google.common.base.Preconditions.checkNotNull<java.util.Optional<String?>?>(associatedModuleVersion)
            com.google.common.base.Preconditions.checkNotNull<Root?>(sourceRoot)
        }

        companion object {
            @kotlin.jvm.JvmStatic
            fun builder(): Builder {
                return AutoBuilder_Package_Metadata_Builder()
            }

            fun of(
                packageIdentifier: PackageIdentifier?,
                buildFilename: RootedPath,
                workspaceName: String?,
                repositoryMapping: RepositoryMapping?,
                associatedModuleName: java.util.Optional<String?>?,
                associatedModuleVersion: java.util.Optional<String?>?,
                configSettingVisibilityPolicy: ConfigSettingVisibilityPolicy?,
                succinctTargetNotFoundErrors: Boolean
            ): Metadata {
                val buildFileLabel: Label?
                try {
                    buildFileLabel =
                        Label.create(packageIdentifier, buildFilename.getRootRelativePath().getBaseName())
                } catch (e: LabelSyntaxException) {
                    // This can't actually happen.
                    throw java.lang.AssertionError("Package BUILD file has an illegal name: " + buildFilename, e)
                }
                return com.google.devtools.build.lib.packages.Package.Metadata(
                    packageIdentifier,
                    buildFilename,
                    buildFileLabel,
                    workspaceName,
                    repositoryMapping,
                    associatedModuleName,
                    associatedModuleVersion,
                    configSettingVisibilityPolicy,
                    succinctTargetNotFoundErrors,
                    com.google.devtools.build.lib.packages.Package.Metadata.Companion.computeSourceRoot(
                        packageIdentifier,
                        buildFilename
                    )
                )
            }

            private fun getPackageDirectory(buildFilename: RootedPath): com.google.devtools.build.lib.vfs.Path? {
                return buildFilename.asPath().getParentDirectory()
            }

            private fun computeSourceRoot(
                packageIdentifier: PackageIdentifier?, buildFilename: RootedPath
            ): Root {
                com.google.common.base.Preconditions.checkNotNull<Any?>(packageIdentifier)
                com.google.common.base.Preconditions.checkNotNull<RootedPath?>(buildFilename)

                val buildFileRootedPath: RootedPath = buildFilename
                val buildFileRoot: Root = buildFileRootedPath.getRoot()
                val pkgIdFragment: PathFragment = packageIdentifier.getSourceRoot()
                val pkgDirFragment: PathFragment? = buildFileRootedPath.getRootRelativePath().getParentDirectory()

                val sourceRoot: Root
                if (pkgIdFragment == pkgDirFragment) {
                    // Fast path: BUILD file path and package name are the same, don't create an extra root.
                    sourceRoot = buildFileRoot
                } else {
                    // TODO(bazel-team): Can this expr be simplified to just pkgDirFragment?
                    var current: PathFragment? = buildFileRootedPath.asPath().asFragment().getParentDirectory()
                    var i = 0
                    val len: Int = pkgIdFragment.segmentCount()
                    while (i < len && current != null) {
                        current = current.getParentDirectory()
                        i++
                    }
                    if (current == null || current.isEmpty()) {
                        // This is never really expected to work. The below check should fail.
                        sourceRoot = buildFileRoot
                    } else {
                        // Note that current is an absolute path.
                        sourceRoot = Root.fromPath(buildFileRoot.getRelative(current))
                    }
                }

                com.google.common.base.Preconditions.checkArgument(
                    sourceRoot.asPath() != null
                            && sourceRoot.getRelative(pkgIdFragment) == com.google.devtools.build.lib.packages.Package.Metadata.Companion.getPackageDirectory(
                        buildFilename
                    ),
                    "Invalid BUILD file name for package '%s': %s (in source %s with packageDirectory %s and"
                            + " package identifier source root %s)",
                    packageIdentifier,
                    buildFilename,
                    sourceRoot,
                    com.google.devtools.build.lib.packages.Package.Metadata.Companion.getPackageDirectory(buildFilename),
                    packageIdentifier.getSourceRoot()
                )

                return sourceRoot
            }
        }
    }

    /**
     * A collection of data about a package that is known after BUILD file evaluation has completed,
     * which doesn't require expanding any symbolic macros, and which transitively doesn't hold
     * references to [Package] or [PackagePiece] objects. Treated as immutable after BUILD
     * file evaluation has completed.
     * 
     * 
     * Instances of the base [Declarations] class are immutable; for a mutable builder, see
     * [Declarations.Builder].
     */
    open class Declarations {
        // All fields are non-final only to allow builder subclass to mutate them.
        // These two fields are mutated only during BUILD file evaluation (not during symbolic macro
        // evaluation).
        protected var packageArgs: PackageArgs = PackageArgs.Companion.DEFAULT
        protected var makeEnv: com.google.common.collect.ImmutableMap<String?, String?>? = null

        // These two fields are mutually exclusive. Which one is set depends on
        // PackageSettings#precomputeTransitiveLoads. See Package.Builder#setLoads.
        var directLoads: com.google.common.collect.ImmutableList<net.starlark.java.eval.Module?>? = null
        var transitiveLoads: com.google.common.collect.ImmutableList<Label?>? = null

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            return obj is Declarations
                    && packageArgs == obj.packageArgs
                    && makeEnv == obj.makeEnv // Serializers use getOrComputeTransitivelyLoadedStarlarkFiles() and don't distinguish
                    // between directLoads and transitiveLoads.
                    && getOrComputeTransitivelyLoadedStarlarkFilesInternal() == obj.getOrComputeTransitivelyLoadedStarlarkFilesInternal()
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(
                packageArgs,
                makeEnv,  // Serializers use getOrComputeTransitivelyLoadedStarlarkFiles() and don't distinguish
                // between directLoads and transitiveLoads.
                getOrComputeTransitivelyLoadedStarlarkFilesInternal()
            )
        }

        /**
         * Returns the collection of package-level attributes set by the `package()` callable and
         * similar methods.
         */
        fun getPackageArgs(): PackageArgs {
            return packageArgs
        }

        /**
         * Returns the "Make" environment of this package, containing package-local definitions of
         * "Make" variables.
         */
        fun getMakeEnvironment(): com.google.common.collect.ImmutableMap<String?, String?>? {
            return makeEnv
        }

        /**
         * Returns a list of Starlark files transitively loaded by this package.
         * 
         * 
         * If transitive loads are not [ precomputed][PackageSettings.precomputeTransitiveLoads], performs a traversal over the load graph to compute them.
         * 
         * 
         * If only the count of transitively loaded files is needed, use [ ][.countTransitivelyLoadedStarlarkFiles]. For a customized online visitation, use [ ][.visitLoadGraph].
         * 
         * 
         * This method can only be used after the Package or PackagePiece has been fully initialized
         * (i.e. after [TargetDefinitionContext.finishBuild] has been called).
         */
        fun getOrComputeTransitivelyLoadedStarlarkFiles(): com.google.common.collect.ImmutableList<Label?> {
            return com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<Label?>>(
                getOrComputeTransitivelyLoadedStarlarkFilesInternal()
            )
        }

        private fun getOrComputeTransitivelyLoadedStarlarkFilesInternal(): com.google.common.collect.ImmutableList<Label?>? {
            if (transitiveLoads != null) {
                return transitiveLoads
            } else if (directLoads != null) {
                return com.google.devtools.build.lib.packages.Package.Companion.computeTransitiveLoads(directLoads)
            } else {
                // Declarations not fully initialized.
                return null
            }
        }

        /**
         * Counts the number Starlark files transitively loaded by this package.
         * 
         * 
         * If transitive loads are not [ precomputed][PackageSettings.precomputeTransitiveLoads], performs a traversal over the load graph to count them.
         * 
         * 
         * This method can only be used after the Package or PackagePiece has been fully initialized
         * (i.e. after [TargetDefinitionContext.finishBuild] has been called).
         */
        fun countTransitivelyLoadedStarlarkFiles(): Int {
            if (transitiveLoads != null) {
                return transitiveLoads.size()
            }
            val loads: MutableSet<Label?> = HashSet<Label?>()
            visitLoadGraph<E1?, E2?>(loads::add)
            return loads.size()
        }

        /**
         * Performs an online visitation of the load graph rooted at this package.
         * 
         * 
         * If transitive loads were [ precomputed][PackageSettings.precomputeTransitiveLoads], each file is passed to [LoadGraphVisitor.visit] once regardless of its
         * return value.
         * 
         * 
         * This method can only be used after the Package or PackagePiece has been fully initialized
         * (i.e. after [TargetDefinitionContext.finishBuild] has been called).
         */
        @Throws(E1::class, E2::class)
        fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?> visitLoadGraph(
            visitor: LoadGraphVisitor<E1?, E2?>
        ) {
            if (transitiveLoads != null) {
                for (load in transitiveLoads) {
                    visitor.visit(load)
                }
            } else {
                BazelModuleContext.visitLoadGraphRecursively(directLoads, visitor)
            }
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun checkMutable(): Builder {
            if (this is Builder) {
                return builder
            }
            throw java.lang.IllegalStateException("Package declarations has been finalized and is immutable.")
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun checkImmutable(): Declarations {
            check(!this is Builder) { "Package declarations is in mutable state." }
            return this
        }

        private constructor(
            packageArgs: PackageArgs?,
            makeEnv: com.google.common.collect.ImmutableMap<String?, String?>?,
            directLoads: com.google.common.collect.ImmutableList<net.starlark.java.eval.Module?>?,
            transitiveLoads: com.google.common.collect.ImmutableList<Label?>?
        ) {
            .also {
                this.packageArgs = it
            }<PackageArgs> com . google . common . base . Preconditions . checkNotNull < PackageArgs ? > (packageArgs)
            TODO(
                """
                |Cannot convert element
                |With text:
                |this.makeEnv = <ImmutableMap<String, String>>checkNotNull(makeEnv);
                """.trimMargin()
            )
            this.directLoads = directLoads
            this.transitiveLoads = transitiveLoads
            com.google.common.base.Preconditions.checkArgument(
                (directLoads == null) xor (transitiveLoads == null),
                "Exactly one of directLoads and transitiveLoads must be set"
            )
        }

        /** Default constructor for use only by [Builder].  */
        private constructor()

        /** Builder for [Declarations].  */
        class Builder : Declarations() {
            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setPackageArgs(packageArgs: PackageArgs?): Builder {
                this.packageArgs = com.google.common.base.Preconditions.checkNotNull<PackageArgs>(packageArgs)
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun mergePackageArgsFrom(packageArgs: PackageArgs?): Builder {
                this.packageArgs = this.packageArgs.mergeWith(
                    com.google.common.base.Preconditions.checkNotNull<PackageArgs?>(packageArgs)
                )
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setMakeEnvironment(makeEnv: MutableMap<String?, String?>?): Builder {
                TODO(
                    """
                    |Cannot convert element
                    |With text:
                    |this.makeEnv = ImmutableMap.<String, String>copyOf(<Map<String, String>>checkNotNull(makeEnv)
                    """.trimMargin()
                )

                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setDirectLoads(directLoads: MutableList<net.starlark.java.eval.Module?>?): Builder {
                this.directLoads = com.google.common.collect.ImmutableList.copyOf<net.starlark.java.eval.Module?>(
                    com.google.common.base.Preconditions.checkNotNull<MutableList<net.starlark.java.eval.Module?>?>(
                        directLoads
                    )
                )
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setTransitiveLoads(transitiveLoads: MutableList<Label?>?): Builder {
                this.transitiveLoads = com.google.common.collect.ImmutableList.copyOf<Label?>(
                    com.google.common.base.Preconditions.checkNotNull<MutableList<Label?>?>(transitiveLoads)
                )
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            private fun checkLoadsNotSet(): Builder {
                com.google.common.base.Preconditions.checkState(
                    directLoads == null,
                    "Direct loads already set: %s",
                    directLoads
                )
                com.google.common.base.Preconditions.checkState(
                    transitiveLoads == null,
                    "Transitive loads already set: %s",
                    transitiveLoads
                )
                return this
            }

            fun build(): Declarations {
                return Declarations(packageArgs, makeEnv, directLoads, transitiveLoads)
            }

            init {
                packageArgs = PackageArgs.Companion.DEFAULT
                makeEnv = com.google.common.collect.ImmutableMap.of<String?, String?>()
            }
        }
    }

    /** Package codec implementation.  */
    @com.google.common.annotations.VisibleForTesting
    internal class PackageCodec : ObjectCodec<Package?> {
        override fun getEncodedClass(): java.lang.Class<Package?> {
            return com.google.devtools.build.lib.packages.Package::class.java
        }

        @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        override fun serialize(context: SerializationContext, input: Package?, codedOut: CodedOutputStream?) {
            context.checkClassExplicitlyAllowed<Package?>(
                com.google.devtools.build.lib.packages.Package::class.java,
                input
            )
            val codecDeps: PackageCodecDependencies =
                context.getDependency<PackageCodecDependencies>(PackageCodecDependencies::class.java)
            codecDeps.getPackageSerializer().serialize(context, input, codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserialize(context: DeserializationContext, codedIn: CodedInputStream?): Package? {
            val codecDeps: PackageCodecDependencies =
                context.getDependency<PackageCodecDependencies>(PackageCodecDependencies::class.java)
            return codecDeps.getPackageSerializer().deserialize(context, codedIn)
        }
    }

    companion object {
        private fun computeTransitiveLoads(directLoads: Iterable<net.starlark.java.eval.Module?>?): com.google.common.collect.ImmutableList<Label?> {
            val loads: MutableSet<Label?> = LinkedHashSet<Label?>()
            BazelModuleContext.visitLoadGraphRecursively(directLoads, loads::add)
            return com.google.common.collect.ImmutableList.copyOf<Label?>(loads)
        }

        fun getAlternateTargetSuggestion(
            metadata: Metadata, targetName: String?, otherTargets: com.google.common.collect.ImmutableSet<String?>?
        ): String {
            // If there's a file on the disk that's not mentioned in the BUILD file,
            // produce a more informative error.  NOTE! this code path is only executed
            // on failure, which is (relatively) very rare.  In the common case no
            // stat(2) is executed.
            val filename: com.google.devtools.build.lib.vfs.Path =
                metadata.getPackageDirectory().getRelative(targetName)
            if (!PathFragment.isNormalized(targetName) || "*" == targetName) {
                // Don't check for file existence if the target name is not normalized
                // because the error message would be confusing and wrong. If the
                // targetName is "foo/bar/.", and there is a directory "foo/bar", it
                // doesn't mean that "//pkg:foo/bar/." is a valid label.
                // Also don't check if the target name is a single * character since
                // it's invalid on Windows.
                return ""
            } else if (filename.isDirectory()) {
                return ("; however, a source directory of this name exists.  (Perhaps add "
                        + "'exports_files([\""
                        + targetName
                        + "\"])' to "
                        + com.google.devtools.build.lib.packages.Package.Companion.getRepoRelativeBuildFilePathString(
                    metadata
                )
                        + ", or define a "
                        + "filegroup?)")
            } else if (filename.exists()) {
                return ("; however, a source file of this name exists.  (Perhaps add "
                        + "'exports_files([\""
                        + targetName
                        + "\"])' to "
                        + com.google.devtools.build.lib.packages.Package.Companion.getRepoRelativeBuildFilePathString(
                    metadata
                )
                        + "?)")
            } else {
                return TargetSuggester.suggestTargets(targetName, otherTargets)
            }
        }

        private fun getRepoRelativeBuildFilePathString(metadata: Metadata): String {
            return metadata
                .packageIdentifier
                .getPackageFragment()
                .getRelative(metadata.buildFilename.asPath().getBaseName())
                .getPathString()
        }

        // ==== Error reporting ====
        /**
         * Returns an error [Event] with [Location] and [DetailedExitCode] properties.
         */
        fun error(location: net.starlark.java.syntax.Location?, message: String?, code: Code?): Event {
            return com.google.devtools.build.lib.packages.Package.Companion.errorWithDetailedExitCode(
                location,
                message,
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setPackageLoading(PackageLoading.newBuilder().setCode(code))
                        .build()
                )
            )
        }

        /** Similar to [.error] but with a custom [DetailedExitCode].  */
        fun errorWithDetailedExitCode(
            location: net.starlark.java.syntax.Location?, message: String?, detailedExitCode: DetailedExitCode?
        ): Event {
            val error: Event = Event.error(location, message)
            return error.withProperty(DetailedExitCode::class.java, detailedExitCode)
        }

        /**
         * If `pkg.containsErrors()`, sends an errorful "package contains errors" [Event]
         * (augmented with `pkg.getFailureDetail()`, if present) to the given [EventHandler].
         */
        fun maybeAddPackageContainsErrorsEventToHandler(
            pkg: Package, eventHandler: EventHandler
        ) {
            if (pkg.containsErrors()) {
                eventHandler.handle(
                    Event.error(
                        java.lang.String.format(
                            "package contains errors: %s%s",
                            pkg.getNameFragment(),
                            if (pkg.getFailureDetail() != null)
                                ": " + pkg.getFailureDetail().getMessage()
                            else
                                ""
                        )
                    )
                )
            }
        }

        /**
         * Given a [FailureDetail] and target, returns a modified `FailureDetail` that
         * attributes its error to the target.
         * 
         * 
         * If the given detail is null, then a generic [Code.TARGET_MISSING] detail identifying
         * the target is returned.
         */
        fun contextualizeFailureDetailForTarget(
            failureDetail: FailureDetail?, target: com.google.devtools.build.lib.packages.Target
        ): FailureDetail {
            val prefix =
                "Target '" + target.getLabel() + "' contains an error and its package is in error"
            if (failureDetail == null) {
                return FailureDetail.newBuilder()
                    .setMessage(prefix)
                    .setPackageLoading(PackageLoading.newBuilder().setCode(Code.TARGET_MISSING))
                    .build()
            }
            return failureDetail.toBuilder().setMessage(prefix + ": " + failureDetail.getMessage()).build()
        }

        // ==== Builders ====
        /** Returns a new [Builder] suitable for constructing an ordinary package.  */
        fun newPackageBuilder(
            packageSettings: PackageSettings,
            id: PackageIdentifier?,
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
            generatorMap: com.google.common.collect.ImmutableMap<net.starlark.java.syntax.Location?, String?>?,  // TODO(bazel-team): See Builder() constructor comment about use of null for this param.
            configSettingVisibilityPolicy: ConfigSettingVisibilityPolicy?,
            globber: Globber?,
            enableNameConflictChecking: Boolean,
            trackFullMacroInformation: Boolean,
            packageLimits: PackageLimits?
        ): Builder {
            return com.google.devtools.build.lib.packages.Package.Builder(
                com.google.devtools.build.lib.packages.Package.Metadata.Companion.builder()
                    .packageIdentifier(id)
                    .buildFilename(filename)
                    .workspaceName(workspaceName)
                    .repositoryMapping(repositoryMapping)
                    .associatedModuleName(associatedModuleName)
                    .associatedModuleVersion(associatedModuleVersion)
                    .configSettingVisibilityPolicy(configSettingVisibilityPolicy)
                    .succinctTargetNotFoundErrors(packageSettings.succinctTargetNotFoundErrors())
                    .build(),
                com.google.devtools.build.lib.packages.Package.Declarations.Builder(),
                net.starlark.java.eval.SymbolGenerator.create<Any?>(id),
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

        /** Returns a new [Builder] suitable for constructing a package from package pieces.  */
        fun newPackageFromPackagePiecesBuilder(
            packageSettings: PackageSettings,
            metadata: Metadata,
            declarations: Declarations,
            noImplicitFileExport: Boolean,
            simplifyUnconditionalSelectsInRuleAttrs: Boolean,
            mainRepositoryMapping: RepositoryMapping?,
            cpuBoundSemaphore: Semaphore?,
            packageOverheadEstimator: PackageOverheadEstimator?,
            generatorMap: com.google.common.collect.ImmutableMap<net.starlark.java.syntax.Location?, String?>?,
            globber: Globber?,
            enableNameConflictChecking: Boolean,
            trackFullMacroInformation: Boolean,
            packageLimits: PackageLimits?,
            buildFile: InputFile
        ): Builder {
            val builder: Builder =
                com.google.devtools.build.lib.packages.Package.Builder(
                    metadata,
                    declarations.checkImmutable(),
                    net.starlark.java.eval.SymbolGenerator.create<Any?>(metadata.packageIdentifier),
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
            com.google.common.base.Preconditions.checkArgument(
                buildFile.getPackageMetadata().packageIdentifier.equals(metadata.packageIdentifier)
            )
            builder.recorder.replaceInputFileUnchecked(buildFile)
            builder.setBuildFile(buildFile)
            return builder
        }
    }
}
