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

import com.google.devtools.build.lib.cmdline.Label

/**
 * A node in the build dependency graph, identified by a Label.
 * 
 * 
 * This StarlarkBuiltin does not contain any documentation since Starlark's Target type refers to
 * TransitiveInfoCollection.class, which contains the appropriate documentation.
 */
interface Target : TargetData {
    /** Returns the [Packageoid] to which this target belongs.  */
    fun getPackageoid(): Packageoid?

    /**
     * If this target is a direct member of a full [Package], returns it; otherwise, returns
     * null.
     * 
     * 
     * Avoid adding new uses of this method; it is incompatible with lazy symbolic macro
     * evaluation.
     */
    fun getPackage(): com.google.devtools.build.lib.packages.Package? {
        return if (getPackageoid() is com.google.devtools.build.lib.packages.Package) getPackageoid() as com.google.devtools.build.lib.packages.Package? else null
    }

    /** Returns the Package.Metadata of the package to which this target belongs.  */ // Overlaps signature of RuleOrMacroInstance#getPackageMetadata.
    fun getPackageMetadata(): com.google.devtools.build.lib.packages.Package.Metadata?

    /** Returns the Package.Declarations of the package to which this target belongs.  */
    fun getPackageDeclarations(): Declarations?

    /**
     * Returns the innermost symbolic macro that declared this target, or null if it was declared
     * outside any symbolic macro (i.e. directly in a BUILD file or only in one or more legacy
     * macros).
     * 
     * 
     * For targets in deserialized packages, throws [IllegalStateException].
     */
    // Overlaps RuleorMacroInstance#getDeclaringMacro.
    fun getDeclaringMacro(): MacroInstance? {
        val packageoid: Packageoid? = getPackageoid()
        if (packageoid is com.google.devtools.build.lib.packages.Package) {
            return packageoid.getDeclaringMacroForTarget(getName())
            // TODO: #19922 - We might replace Package#getDeclaringMacroForTarget by storing a reference
            // to the declaring macro in implementations of this interface (sharing memory with the field
            // for the package).
        } else if (packageoid is com.google.devtools.build.lib.packages.PackagePiece.ForMacro) {
            return packageoid.getEvaluatedMacro()
        } else if (packageoid is com.google.devtools.build.lib.packages.PackagePiece.ForBuildFile) {
            return null
        } else {
            throw java.lang.AssertionError("Unknown packageoid " + packageoid)
        }
    }

    /**
     * Returns the package that is considered to be the declaring location of this target.
     * 
     * 
     * For targets created inside a symbolic macro, this is the package containing the .bzl code of
     * the innermost running symbolic macro. For targets not in any symbolic macro, this is the same
     * as the package the target lives in.
     */
    // TODO(bazel-team): Clean up terminology throughout Target, RuleOrMacroInstance, MacroInstance,
    // CommonPrerequisiteInvalidator, etc., to be consistent. "Definition location" is the place where
    // a macro's .bzl code lives. "Declaration location" is the place where a target or macro instance
    // has its visibility checked (assuming no delegation applies) -- the definition location of its
    // declaring macro, or the BUILD file if not in a macro. "Declaring package" is perhaps ambiguous
    // and could mean either the declaration location or the package the target lives in.
    fun getDeclaringPackage(): PackageIdentifier? {
        val packageoid: Packageoid? = getPackageoid()
        if (packageoid is com.google.devtools.build.lib.packages.Package) {
            val pkgId: PackageIdentifier? = packageoid.getDeclaringPackageForTargetIfInMacro(getName())
            return if (pkgId != null) pkgId else packageoid.getPackageIdentifier()
        } else if (packageoid is com.google.devtools.build.lib.packages.PackagePiece.ForMacro) {
            return packageoid.getDeclaringPackage()
        } else if (packageoid is com.google.devtools.build.lib.packages.PackagePiece.ForBuildFile) {
            return packageoid.getPackageIdentifier()
        } else {
            throw java.lang.AssertionError("Unknown packageoid " + packageoid)
        }
    }

    /**
     * Returns true if this target was declared within one or more symbolic macros, or false if it was
     * the product of running only a BUILD file and the legacy macros it called.
     */
    fun isCreatedInSymbolicMacro(): Boolean {
        val packageoid: Packageoid? = getPackageoid()
        if (packageoid is com.google.devtools.build.lib.packages.Package) {
            return packageoid.getDeclaringPackageForTargetIfInMacro(getName()) != null
        } else if (packageoid is com.google.devtools.build.lib.packages.PackagePiece.ForMacro) {
            return true
        } else if (packageoid is com.google.devtools.build.lib.packages.PackagePiece.ForBuildFile) {
            return false
        } else {
            throw java.lang.AssertionError("Unknown packageoid " + packageoid)
        }
    }

    /**
     * Returns the rule associated with this target, if any.
     * 
     * 
     * If this is a Rule, returns itself; it this is an OutputFile, returns its generating rule; if
     * this is an input file, returns null.
     */
    fun getAssociatedRule(): com.google.devtools.build.lib.packages.Rule?

    /**
     * Returns the license associated with this target.
     */
    fun getLicense(): License?

    /**
     * Returns the visibility that was supplied at the point of this target's declaration -- e.g. the
     * `visibility` attribute/argument for a rule target or `exports_files()` declaration)
     * -- or null if none was given.
     * 
     * 
     * Although this value is "raw", it is still normalized through [ ][RuleVisibility.validateAndSimplify], e.g. eliminating redundant `//visibility:private` items and replacing the list with a single `//visibility:public`
     * item if at least one such item appears.
     * 
     * 
     * This value may be useful to tooling that wants to introspect a target's visibility via
     * `bazel query` and feed the result back into a modified target declaration, without
     * picking up the package's default visibility, or the added location of the package or symbolic
     * macro the target was declared in. It is not useful as a direct input to the visibility
     * semantics; for that see [.getActualVisibility].
     * 
     * 
     * This is also the value that is introspected through `native.existing_rules()`, except
     * that null is replaced by an empty visibility.
     */
    fun getRawVisibility(): RuleVisibility?

    /**
     * Returns the default visibility value to fall back on if this target does not have a raw
     * visibility.
     * 
     * 
     * Usually this is just the package's default visibility value for targets not declared in
     * symbolic macros, and private for targets within symbolic macros. (In other words, a package's
     * default visibility does not propagate to within a symbolic macro.) However, some targets may
     * inject additional default visibility behavior here.
     */
    fun getDefaultVisibility(): RuleVisibility? {
        return if (isCreatedInSymbolicMacro())
            RuleVisibility.Companion.PRIVATE
        else
            getPackageDeclarations().getPackageArgs().defaultVisibility()
    }

    /**
     * Returns the [raw visibility][.getRawVisibility] of this target, falling back on a [ ][.getDefaultVisibility] if no raw visibility was supplied.
     * 
     * 
     * Due to the fallback, the result cannot be null.
     * 
     * 
     * This value may be useful for introspecting a target's visibility and reporting it in a
     * context where the package's default visibility is not known. It is not useful as a direct input
     * to the visibility semantics; for that see [.getActualVisibility].
     */
    // TODO(brandjon): Perhaps the default value within a symbolic macro should be the value of the
    // `--default_visibility` flag / PrecomputedValue. This would ensure targets within macros are
    // always visible within unit tests or escape-hatched builds.
    // TODO(jhorvitz): Usually one of the following two methods suffice. Try to remove this.
    fun getVisibility(): RuleVisibility {
        val result: RuleVisibility? = getRawVisibility()
        return if (result != null) result else getDefaultVisibility()
    }

    /**
     * Equivalent to calling [RuleVisibility.getDependencyLabels] on the value returned by
     * [.getVisibility], but potentially more efficient.
     * 
     * 
     * Prefer this method over [.getVisibility] when only the dependency labels are needed
     * and not a [RuleVisibility] instance.
     */
    fun getVisibilityDependencyLabels(): Iterable<Label?>? {
        return getVisibility().getDependencyLabels()
    }

    /**
     * Equivalent to calling [RuleVisibility.getDeclaredLabels] on the value returned by [ ][.getVisibility], but potentially more efficient.
     * 
     * 
     * Prefer this method over [.getVisibility] when only the declared labels are needed and
     * not a [RuleVisibility] instance.
     */
    fun getVisibilityDeclaredLabels(): MutableList<Label?>? {
        return getVisibility().getDeclaredLabels()
    }

    /**
     * Returns the visibility of this target, as understood by the visibility semantics.
     * 
     * 
     * This is the result of [.getVisibility] unioned with the package where this target was
     * instantiated (which can differ from the package where this target lives if the target was
     * created inside a symbolic macro).
     * 
     * 
     * This is the value that feeds into visibility checking in the analysis phase. See [ ][ConfiguredTargetFactory.convertVisibility] and [ ][CommonPrerequisiteValidator.isVisibleToLocation].
     */
    fun getActualVisibility(): RuleVisibility? {
        val visibility: RuleVisibility = getVisibility()
        val declaringMacro: MacroInstance? = getDeclaringMacro()
        val instantiatingLoc: PackageIdentifier? =
            if (declaringMacro == null)
                getPackageMetadata().packageIdentifier
            else
                declaringMacro.getDefinitionPackage()
        return visibility.concatWithPackage(instantiatingLoc)
    }

    /** Returns whether this target type can be configured (e.g. accepts non-null configurations).  */
    fun isConfigurable(): Boolean

    /**
     * Creates a compact representation of this target with enough information for dependent parents.
     */
    fun reduceForSerialization(): TargetData?

    /** Returns the label identifying as a string formatted for display.  */
    fun getDisplayFormLabel(): String {
        return getLabel()
            .getDisplayForm(
                if (getLabel().getRepository().isMain()) getPackageMetadata().repositoryMapping else null
            )
    }
}
