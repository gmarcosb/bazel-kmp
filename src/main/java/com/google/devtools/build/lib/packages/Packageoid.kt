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
 * A package-like entity (either a full [Package] or a package piece) which serves as a
 * container of [Target] and [MacroInstance] objects and links them to the package's
 * metadata and declarations.
 * 
 * 
 * Each [Target] or [MacroInstance] object is uniquely owned one packageoid (for
 * targets, that's the packageoid returned by [Target.getPackageoid]). In some cases, the
 * target or macro instance may also be referenced from other packageoids, provided that this
 * doesn't create a skyframe cycle.
 * 
 * 
 * To obtain a [Package] from a [Packageoid], use a PackageProvider or skyframe
 * machinery.
 */
abstract class Packageoid protected constructor(
    metadata: com.google.devtools.build.lib.packages.Package.Metadata?,
    declarations: Declarations?
) {
    // ==== General package metadata fields ====
    protected val metadata: com.google.devtools.build.lib.packages.Package.Metadata

    var declarations: Declarations?

    // ==== Common metadata fields ====
    /**
     * True iff this packageoid's Starlark files contained lexical or grammatical errors, or
     * experienced errors during evaluation, or semantic errors during the construction of any rule.
     * 
     * 
     * Note: A packageoid containing errors does not necessarily prevent a build; if all the rules
     * needed for a given build were constructed prior to the first error, the build may proceed.
     */
    var containsErrors: Boolean = false

    /**
     * The first detailed error encountered during this packageoid's construction and evaluation, or
     * `null` if there were no such errors or all its errors lacked details.
     */
    var failureDetail: FailureDetail? = null

    @kotlin.jvm.JvmField
    protected var computationSteps: Long = 0

    /**
     * A rough approximation of the memory and general accounting costs associated with a loaded
     * packageoid. A value of -1 means it is unset. Stored as a long to take up less memory per pkg.
     */
    var packageOverhead: Long = PACKAGE_OVERHEAD_UNSET

    // ==== Common target and macro fields ====
    /**
     * The collection of all targets defined in this packageoid, indexed by name. Null until the
     * packageoid is fully initialized by its builder's `finishBuild()`.
     */
    // TODO(bazel-team): Clarify what this map contains when a rule and its output both share the same
    // name.
    var targets: com.google.common.collect.ImmutableSortedMap<String?, com.google.devtools.build.lib.packages.Target?>? =
        null

    /**
     * Returns the metadata of the package; in other words, information which is known about a package
     * before BUILD file evaluation has started.
     */
    fun getMetadata(): com.google.devtools.build.lib.packages.Package.Metadata {
        return metadata
    }

    /**
     * Returns the package's identifier. This is a convenience wrapper for [ ][Package.Metadata.packageIdentifier].
     */
    fun getPackageIdentifier(): PackageIdentifier? {
        return getMetadata().packageIdentifier
    }

    /**
     * Returns data about the package which is known after BUILD file evaluation without expanding
     * symbolic macros.
     */
    fun getDeclarations(): Declarations? {
        return declarations
    }

    /**
     * Returns the label for the package's BUILD file.
     * 
     * 
     * Typically, `getBuildFileLabel().getName().equals("BUILD")` -- though not
     * necessarily: data in a subdirectory of a test package may use a different filename to avoid
     * inadvertently creating a new package.
     */
    fun getBuildFileLabel(): Label? {
        return getMetadata().buildFileLabel
    }

    /**
     * Returns a short, lower-case description of this packageoid, e.g. for use in logging and error
     * messages.
     */
    abstract fun getShortDescription(): String?

    /**
     * Returns an (immutable, ordered) view of all the targets belonging to this packageoid. Note that
     * if this packageoid is a package piece, this method does not search for targets in any other
     * package pieces.
     */
    fun getTargets(): com.google.common.collect.ImmutableSortedMap<String?, com.google.devtools.build.lib.packages.Target?>? {
        return targets
    }

    /**
     * Returns true if errors were encountered during evaluation of this packageoid.
     * 
     * 
     * If a packageoid contains errors, it may be incomplete and its contents should not be relied
     * upon for critical operations. All rules in such a packageoid will have their [ ][Rule.containsErrors] flag set to true.
     */
    fun containsErrors(): Boolean {
        return containsErrors
    }

    /**
     * Marks this packageoid as in error.
     * 
     * 
     * This method may only be called while the packageoid is being constructed. Intended only for
     * use by [Rule.reportError], since its callers might not have access to the packageoid's
     * builder instance.
     * 
     * @throws IllegalStateException if this packageoid has completed construction.
     */
    fun setContainsErrors() {
        com.google.common.base.Preconditions.checkState(
            targets == null,
            "setContainsErrors() can only be called while the packageoid is being constructed"
        )
        containsErrors = true
    }

    /**
     * Returns the first [FailureDetail] describing one of the packageoid's errors, or `null` if it has no errors or all its errors lack details.
     */
    fun getFailureDetail(): FailureDetail? {
        return failureDetail
    }

    /**
     * Returns the number of Starlark computation steps executed during the evaluation of this
     * packageoid.
     */
    fun getComputationSteps(): Long {
        return computationSteps
    }

    /** Returns package overhead as configured by the configured [PackageOverheadEstimator].  */
    fun getPackageOverhead(): OptionalLong {
        return if (packageOverhead == PACKAGE_OVERHEAD_UNSET)
            OptionalLong.empty()
        else
            OptionalLong.of(packageOverhead)
    }

    /**
     * Throws [MacroNamespaceViolationException] if the given target (which must be a member of
     * this packageoid) violates macro naming rules.
     */
    @Throws(MacroNamespaceViolationException::class)
    abstract fun checkMacroNamespaceCompliance(target: com.google.devtools.build.lib.packages.Target?)

    /**
     * Returns the target (a member of this packagoid) whose name is "targetName". First rules are
     * searched, then output files, then input files. The target name must be valid, as defined by
     * `LabelValidator#validateTargetName`.
     * 
     * 
     * Use with care. In particular, note that `target.getPackageoid().getTarget("sibling")`
     * will succeed for all package-wide sibling targets if the packageoid is a package, but will
     * throw for targets belonging to a different package piece if the packageoid is a package piece.
     * 
     * @throws NoSuchTargetException if the specified target was not found in this packageoid.
     */
    @Throws(NoSuchTargetException::class)
    abstract fun getTarget(targetName: String?): com.google.devtools.build.lib.packages.Target?

    init {
        this.metadata =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.packages.Package.Metadata>(
                metadata
            )
        this.declarations = com.google.common.base.Preconditions.checkNotNull<Declarations?>(declarations)
    }

    companion object {
        /** Sentinel value for package overhead being empty.  */
        val PACKAGE_OVERHEAD_UNSET: Long = -1
    }
}
