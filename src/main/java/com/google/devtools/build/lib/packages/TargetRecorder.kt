// Copyright 2024 The Bazel Authors. All rights reserved.
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
 * A context in which targets and symbolic macros for a specific package may be added.
 * 
 * 
 * This object is responsible for recording the existence of these targets and macros, and
 * enforcing naming requirements on them. It is used by [Package.Builder] as part of package
 * construction.
 */
class TargetRecorder(
    enableNameConflictChecking: Boolean,
    trackFullMacroInformation: Boolean,
    enableTargetMapSnapshotting: Boolean
) {
    private var containsErrors = false

    // All targets added to the package.
    //
    // We use SnapshottableBiMap to help track insertion order of Rule targets, for use by
    // native.existing_rules().
    private var targetMap: com.google.common.collect.BiMap<String?, com.google.devtools.build.lib.packages.Target?> =
        SnapshottableBiMap<String?, com.google.devtools.build.lib.packages.Target?>(java.util.function.Predicate { target: com.google.devtools.build.lib.packages.Target? -> target is com.google.devtools.build.lib.packages.Rule })

    // All instances of symbolic macros created during package construction, indexed by id (not
    // name).
    private val macroMap: MutableMap<String?, MacroInstance?> = LinkedHashMap<String?, MacroInstance?>()

    /**
     * Represents the innermost currently executing symbolic macro, or null if none are running.
     * 
     * 
     * Logically, this is the top entry of a stack of frames where each frame corresponds to a
     * nested symbolic macro invocation. In actuality, symbolic macros do not necessarily run eagerly
     * when they are invoked, so this is not really a call stack per se. We leave it to the pkgbuilder
     * client to set the current frame, so that the choice of whether to push and pop, or process a
     * worklist of queued evaluations, is up to them.
     * 
     * 
     * The state of this field is used to determine what Starlark APIs are available (see user
     * documentation on `macro()` at [StarlarkRuleFunctionsApi.macro]), and to help
     * enforce naming requirements on targets and macros.
     */
    private var currentMacroFrame: MacroFrame? = null

    /**
     * Represents the state of a running symbolic macro (see [.currentMacroFrame]). Semi-opaque.
     */
    internal class MacroFrame(macroInstance: MacroInstance) {
        val macroInstance: MacroInstance

        // Most name conflicts are caught by checking the keys of the `targetMap` and `macroMap` maps.
        // It is not a conflict for a target or macro to have the same name as the macro it is
        // declared in, yet such a target or macro may still conflict with siblings in the same macro.
        // We use this bool to track whether or not a newly introduced macro, M, having the same name
        // as its parent (the current macro), would clash with an already defined sibling of M.
        private var mainSubmacroHasBeenDefined = false

        init {
            this.macroInstance = macroInstance
        }
    }

    /**
     * Stores labels for each rule so that we don't have to call the costly [Rule.getLabels]
     * twice (once for [Package.Builder.checkForInputOutputConflicts] and once for [ ][Package.Builder.beforeBuild]).
     * 
     * 
     * This field is null if name conflict checking is disabled.
     */
    // TODO(#19922): Technically we don't need to store entries for rules that were created by
    // macros; see rulesCreatedInMacros, below.
    private val ruleLabels: MutableMap<com.google.devtools.build.lib.packages.Rule?, MutableList<Label?>?>?

    /**
     * Stores labels of rule targets that were created in symbolic macros. We don't implicitly create
     * input files on behalf of such targets (though they may still be created on behalf of other
     * targets not in macros).
     * 
     * 
     * This field is null if name conflict checking is disabled.
     */
    // TODO(#19922): This can be eliminated once we have Targets directly store a reference to the
    // MacroInstance that instantiated them. (This is a little nontrivial because we'd like to avoid
    // simply adding a new field to Target subclasses, and instead want to combine it with the
    // existing Package-typed field.)
    private val rulesCreatedInMacros: MutableSet<com.google.devtools.build.lib.packages.Rule?>?

    /**
     * A map from names of targets declared in a symbolic macro which violate macro naming rules, such
     * as "lib%{name}-src.jar" implicit outputs in java rules, to the name of the macro instance where
     * they were declared.
     * 
     * 
     * Outside of package deserialization, the content of the map is manipulated only in [ ][.checkRuleAndOutputs]. During deserialization, this map may also be populated by calling [ ][.putAllMacroNamespaceViolatingTargets].
     */
    private val macroNamespaceViolatingTargets: LinkedHashMap<String?, String?> = LinkedHashMap<String?, String?>()

    /**
     * A map from target name to the (innermost) symbolic macro instance that declared it. Targets
     * that were not declared in a symbolic macro are omitted from the map. See [ ][Package.targetsToDeclaringMacro].
     * 
     * 
     * This field is null if the constructor is called with `trackFullMacroInformation` set
     * to false, in which case limited location information is still available via [ ][.targetsToDeclaringPackage].
     */
    private val targetsToDeclaringMacro: LinkedHashMap<String?, MacroInstance?>?

    /**
     * A map from target name to the package containing the .bzl code of the (innermost) macro that
     * declared it. Targets not declared in a symbolic macro are omitted from the map.
     * 
     * 
     * This field is null if the constructor is called with `trackFullMacroInformation` set
     * to true, in which case [.targetsToDeclaringMacro] supersedes this field.
     * 
     * 
     * Used on deserialization to hold location information needed to determine the target's
     * visibility attribute, when full macro information is not available.
     */
    // TODO(bazel-team): Eventually, serialize full macro information so this is moot.
    private val targetsToDeclaringPackage: LinkedHashMap<String?, PackageIdentifier?>?

    /**
     * The collection of the prefixes of every output file. Maps each prefix to an arbitrary output
     * file having that prefix. Used for error reporting.
     * 
     * 
     * This field is null if name conflict checking is disabled. The content of the map is
     * manipulated only in [.checkRuleAndOutputs].
     */
    private val outputFilePrefixes: MutableMap<String?, OutputFile?>?

    /**
     * Constructs a [TargetRecorder].
     * 
     * @param enableNameConflictChecking whether to perform all validation checks for name clashes
     * among targets, macros, and output file prefixes. This should only be disabled when the
     * package has already been validated, e.g. in package deserialization. Setting it to false
     * does not necessarily turn off *all* checking, just some of the more expensive ones. Do not
     * rely on being able to violate these checks.
     * @param trackFullMacroInformation if true, we record what [MacroInstance] each target was
     * created in, based on the macro stack maintained herein. If false, we only record the
     * definition location associated with said macro, as supplied by the caller via [     ][.putAllTargetsToDeclaringPackage].
     * @param enableTargetMapSnapshotting whether to use a snapshottable map as the targets map;
     * required for [TargetDefinitionContext.getRulesSnapshotView], which is used by `native.existing_rules()` machinery in the context of a [Package.Builder]. If false,
     * [.unwrapSnapshottableBiMap] cannot be called (since in that case the target map is
     * not snapshottable in the first place).
     */
    init {
        if (enableNameConflictChecking) {
            this.ruleLabels = HashMap<com.google.devtools.build.lib.packages.Rule?, MutableList<Label?>?>()
            this.rulesCreatedInMacros = HashSet<com.google.devtools.build.lib.packages.Rule?>()
            this.outputFilePrefixes = HashMap<String?, OutputFile?>()
        } else {
            this.ruleLabels = null
            this.rulesCreatedInMacros = null
            this.outputFilePrefixes = null
        }
        if (trackFullMacroInformation) {
            this.targetsToDeclaringMacro = LinkedHashMap<String?, MacroInstance?>()
            this.targetsToDeclaringPackage = null
        } else {
            this.targetsToDeclaringMacro = null
            this.targetsToDeclaringPackage = LinkedHashMap<String?, PackageIdentifier?>()
        }
        this.targetMap =
            if (enableTargetMapSnapshotting)
                SnapshottableBiMap<String?, com.google.devtools.build.lib.packages.Target?>(java.util.function.Predicate { target: com.google.devtools.build.lib.packages.Target? -> target is com.google.devtools.build.lib.packages.Rule })
            else
                com.google.common.collect.HashBiMap.create<String?, com.google.devtools.build.lib.packages.Target?>()
    }

    fun getTargetMap(): MutableMap<String?, com.google.devtools.build.lib.packages.Target?> {
        return targetMap
    }

    fun getMacroMap(): MutableMap<String?, MacroInstance?> {
        return macroMap
    }

    /**
     * Returns whether there exists a macro with the given name.
     * 
     * 
     * There may be more than one such macro, nested in a chain of main submacros.
     */
    fun hasMacroWithName(name: String?): Boolean {
        // Macros are indexed by id, not name, so we can't just use macroMap.get() directly.
        // Instead, we reason that if at least one macro by the given name exists, then there is one
        // with an id suffix of ":1".
        return macroMap.containsKey(name + ":1")
    }

    fun getRuleLabels(rule: com.google.devtools.build.lib.packages.Rule): MutableList<Label?>? {
        return if (ruleLabels != null) ruleLabels.get(rule) else rule.getLabels()
    }

    fun isRuleCreatedInMacro(rule: com.google.devtools.build.lib.packages.Rule?): Boolean {
        return rulesCreatedInMacros!!.contains(rule)
    }

    /**
     * Returns a map from names of targets declared in a symbolic macro which violate macro naming
     * rules, such as "lib%{name}-src.jar" implicit outputs in java rules, to the name of the macro
     * instance where they were declared.
     */
    fun getMacroNamespaceViolatingTargets(): MutableMap<String?, String?> {
        return if (macroNamespaceViolatingTargets != null)
            macroNamespaceViolatingTargets
        else
            com.google.common.collect.ImmutableMap.of<String?, String?>()
    }

    /**
     * Returns a map from target name to the (innermost) macro instance that declared it, or null if
     * `trackFullMacroInformation` was set to false in the constructor. Omits targets not
     * declared in symbolic macros.
     * 
     * 
     * See [Package.targetsToDeclaringMacro].
     */
    fun getTargetsToDeclaringMacro(): MutableMap<String?, MacroInstance?>? {
        return targetsToDeclaringMacro
    }

    /**
     * Returns a map from target name to the package where the macro that declared it was defined, or
     * null if `trackFullMacroInformation` was set to true in the constructor. Omits targets not
     * declared in symbolic macros.
     * 
     * 
     * See [Package.targetsToDeclaringPackage].
     */
    fun getTargetsToDeclaringPackage(): MutableMap<String?, PackageIdentifier?>? {
        return targetsToDeclaringPackage
    }

    /**
     * Declares that errors were encountering while loading this package.
     * 
     * 
     * If this method is called, then there should also be an ERROR event added to the handler on
     * the [Package.Builder]. The event should include a [FailureDetail].
     */
    // TODO(bazel-team): For simplicity it would be nice to replace the use of an error bit with
    // pkgBuilder.getLocalEventHandler().hasErrors(), since that would prevent the kind of
    // inconsistency where we have reported an ERROR event but not called setContainsErrors(), or vice
    // versa. We could even assert that the error event has a FailureDetail, though that's a linear
    // scan unless we customize the event handler.
    // TODO(bazel-team): At the moment the pkgBuilder's error bit is stored here on this class. But
    // there are ways that Package.Builder#setContainsErrors gets called that have nothing to do with
    // broken targets, e.g. a Starlark eval error. One fix is to put the error bit on the pkgBuilder
    // only, and have this class accept a callback to invoke when registering a target that's in
    // error, and set that callback to pkgBuilder::setContainsErrors. Another fix is to have both
    // classes store error bits, and have the builder union this class's error bit into its own in
    // finishBuild().
    fun setContainsErrors() {
        this.containsErrors = true
    }

    fun containsErrors(): Boolean {
        return containsErrors
    }

    private fun isNameConflictCheckingEnabled(): Boolean {
        return ruleLabels != null
    }

    /**
     * Inserts a target into `targetMap`. Returns the previous target if one was present, or
     * null.
     * 
     * 
     * No validation is done on the target's name.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    private fun putTargetInternal(target: com.google.devtools.build.lib.packages.Target): com.google.devtools.build.lib.packages.Target? {
        val existing: com.google.devtools.build.lib.packages.Target? = targetMap.put(target.getName(), target)
        if (targetsToDeclaringMacro != null && currentMacroFrame != null) {
            targetsToDeclaringMacro.put(target.getName(), currentMacroFrame!!.macroInstance)
        }
        return existing
    }

    /**
     * Inserts a target into the target map.
     * 
     * 
     * The target must have a valid name (for the current macro) and cannot have already been
     * added.
     */
    @Throws(NameConflictException::class)
    fun addTarget(target: com.google.devtools.build.lib.packages.Target) {
        if (target is com.google.devtools.build.lib.packages.Rule) {
            // Use addRule() to ensure all rule-related maps and caches are consulted.
            // checkTargetName() and putTargetInternal() are both reached through addRule().
            addRule(target)
        } else {
            checkTargetName(target)
            putTargetInternal(target)
        }
    }

    /**
     * Inserts an input file into the target map.
     * 
     * 
     * No validation is done on the target's name.
     * 
     * 
     * The target must not have already been added, and there cannot be any existing target by the
     * same name.
     */
    fun addInputFileUnchecked(file: InputFile) {
        val prev: com.google.devtools.build.lib.packages.Target? = putTargetInternal(file)
        com.google.common.base.Preconditions.checkState(prev == null)
    }

    /**
     * Inserts an input file into the target map, replacing an existing file by the same name.
     * 
     * 
     * It is an error if no input file by that name already exists.
     */
    fun replaceInputFileUnchecked(file: InputFile) {
        val prev: com.google.devtools.build.lib.packages.Target? = putTargetInternal(file)
        com.google.common.base.Preconditions.checkState(prev is InputFile, prev)
    }

    fun getTarget(name: String?): com.google.devtools.build.lib.packages.Target? {
        return targetMap.get(name)
    }

    /**
     * Transforms the target map in-place from a [SnapshottableBiMap] to its backing mutable
     * bimap. Intended only for use by [Package.Builder.beforeBuild] to allow removal of certain
     * broken targets from the map.
     * 
     * 
     * After this method has been called, [.getTargetMap] will return a map which is
     * non-snapshottable, but which does allow removal.
     * 
     * @throws IllegalStateException if this object was constructed with `enableTargetMapSnapshotting == false` or if this method had already been called.
     */
    fun unwrapSnapshottableBiMap() {
        com.google.common.base.Preconditions.checkState(targetMap is SnapshottableBiMap<*, *>)
        this.targetMap =
            (targetMap as SnapshottableBiMap<String?, com.google.devtools.build.lib.packages.Target?>).getUnderlyingBiMap()
    }

    // TODO(bazel-team): This method allows target deletion via the returned view, which is used in
    // PackageFunction#handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions.
    // Let's disallow that and make removal go through a dedicated method.
    fun getTargets(): MutableSet<com.google.devtools.build.lib.packages.Target?> {
        return targetMap.values()
    }

    /**
     * Returns an [Iterable] of all the rule instance targets belonging to this package.
     * 
     * 
     * The returned [Iterable] will be deterministically ordered, in the order the rule
     * instance targets were instantiated.
     */
    fun getRules(): Iterable<com.google.devtools.build.lib.packages.Rule?> {
        return com.google.common.collect.Iterables.filter<com.google.devtools.build.lib.packages.Rule?>(
            targetMap.values(),
            com.google.devtools.build.lib.packages.Rule::class.java
        )
    }

    /**
     * Adds a rule and its outputs to the targets map, and propagates the error bit from the rule to
     * the package.
     */
    private fun addRuleInternal(rule: com.google.devtools.build.lib.packages.Rule) {
        for (outputFile in rule.getOutputFiles()) {
            putTargetInternal(outputFile)
        }
        putTargetInternal(rule)
        if (rule.containsErrors()) {
            setContainsErrors()
        }
    }

    /**
     * Adds a rule without certain validation checks. Requires that the constructor was called with
     * `enableNameConflictChecking` set to false.
     */
    fun addRuleUnchecked(rule: com.google.devtools.build.lib.packages.Rule) {
        com.google.common.base.Preconditions.checkState(
            !isNameConflictCheckingEnabled(), "Expected name conflict checking to be disabled"
        )
        addRuleInternal(rule)
    }

    /**
     * Adds a rule, subject to the usual validation checks. Requires that the constructor was called
     * with `enableNameConflictChecking` set to true.
     */
    @Throws(NameConflictException::class)
    fun addRule(rule: com.google.devtools.build.lib.packages.Rule) {
        com.google.common.base.Preconditions.checkState(
            isNameConflictCheckingEnabled(), "Expected name conflict checking to be enabled"
        )

        val labels: MutableList<Label> = rule.getLabels()
        checkRuleAndOutputs(rule, labels)
        addRuleInternal(rule)
        ruleLabels!!.put(rule, labels)
        if (currentMacroFrame != null) {
            rulesCreatedInMacros!!.add(rule)
        }
    }

    /** Adds a symbolic macro instance to the package.  */
    @Throws(NameConflictException::class)
    fun addMacro(macro: MacroInstance) {
        checkMacroName(macro)
        val prev: Any? = macroMap.put(macro.getId(), macro)
        com.google.common.base.Preconditions.checkState(prev == null)

        // Track whether a main submacro has been seen yet. Conflict checking for this is done in
        // checkMacroName().
        if (currentMacroFrame != null) {
            if (macro.getName() == currentMacroFrame!!.macroInstance.getName()) {
                currentMacroFrame.mainSubmacroHasBeenDefined = true
            }
        }
    }

    /**
     * Adds all rules and macros from a given package piece. Intended only for use by skyframe
     * machinery.
     */
    @Throws(NameConflictException::class)
    fun addAllFromPackagePiece(packagePiece: PackagePiece, skipBuildFile: Boolean) {
        val prev: MacroFrame?
        if (packagePiece is com.google.devtools.build.lib.packages.PackagePiece.ForMacro) {
            prev = setCurrentMacroFrame(MacroFrame(packagePiece.getEvaluatedMacro()))
        } else {
            prev = setCurrentMacroFrame(null)
        }
        for (macro in packagePiece.getMacros()) {
            addMacro(macro)
        }
        val buildFile: InputFile? =
            if (packagePiece is com.google.devtools.build.lib.packages.PackagePiece.ForBuildFile)
                packagePiece.getBuildFile()
            else
                null
        for (target in packagePiece.getTargets().values()) {
            if (skipBuildFile && target === buildFile) {
                continue
            }
            if (target is OutputFile) {
                // Rule output files are recorded as a side effect of addTarget(rule)
                continue
            }
            addTarget(target)
        }
        val unused = setCurrentMacroFrame(prev)
    }

    /** Returns the current macro frame, or null if there is no currently running symbolic macro.  */
    fun getCurrentMacroFrame(): MacroFrame? {
        return currentMacroFrame
    }

    /**
     * Returns true if a symbolic macro is running and the current macro frame is not a rule
     * finalizer.
     * 
     * 
     * Note that this function examines only the current macro frame, not any parent frames; and
     * thus returns true even if the current non-finalizer macro was called within a finalizer macro.
     */
    fun currentlyInNonFinalizerMacro(): Boolean {
        return currentMacroFrame != null
                && !currentMacroFrame!!.macroInstance.getMacroClass().isFinalizer()
    }

    /**
     * Returns true if a symbolic macro is running and the current macro frame is a rule finalizer.
     */
    fun currentlyInFinalizer(): Boolean {
        return currentMacroFrame != null
                && currentMacroFrame!!.macroInstance.getMacroClass().isFinalizer()
    }

    /**
     * Sets the current macro frame and returns the old one.
     * 
     * 
     * Either the new or old frame may be null, indicating no currently running symbolic macro.
     */
    fun setCurrentMacroFrame(frame: MacroFrame?): MacroFrame? {
        val prev = currentMacroFrame
        currentMacroFrame = frame
        return prev
    }

    /**
     * Precondition check for [.addRule] (to be called before the rule and its outputs are in
     * the targets map). Verifies that:
     * 
     * 
     *  * The added rule's name, and the names of its output files, are not the same as the name of
     * any target already declared in the package.
     *  * The added rule's output files list does not contain the same name twice.
     *  * The added rule does not have an input file and an output file that share the same name.
     *  * For each of the added rule's output files, no directory prefix of that file matches the
     * name of another output file in the package; and conversely, the file is not itself a
     * prefix for another output file. (This check statefully mutates the `outputFilePrefixes` field.)
     * 
     */
    // TODO(bazel-team): We verify that all prefixes of output files are distinct from other output
    // file names, but not that they're distinct from other target names in the package. What
    // happens if you define an input file "abc" and output file "abc/xyz"?
    @Throws(NameConflictException::class)
    private fun checkRuleAndOutputs(rule: com.google.devtools.build.lib.packages.Rule, labels: MutableList<Label>) {
        com.google.common.base.Preconditions.checkNotNull<MutableMap<String?, OutputFile?>?>(outputFilePrefixes) // ensured by addRule's precondition

        // Check the name of the new rule itself.
        val ruleName: String = rule.getName()
        checkTargetName(rule)

        val outputFiles: com.google.common.collect.ImmutableList<OutputFile> = rule.getOutputFiles()
        val outputFilesByName: MutableMap<String?, OutputFile?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<String?, OutputFile?>(outputFiles.size())

        // Check the new rule's output files, both for direct conflicts and prefix conflicts.
        for (outputFile in outputFiles) {
            val outputFileName: String = outputFile.getName()
            // Check for duplicate within a single rule. (Can't use checkTargetName since this rule's
            // outputs aren't in the target map yet.)
            if (outputFilesByName.put(outputFileName, outputFile) != null) {
                throw NameConflictException(
                    java.lang.String.format(
                        "rule '%s' has more than one generated file named '%s'", ruleName, outputFileName
                    ),
                    outputFile
                )
            }
            // Check for conflict with any other already added target.
            checkTargetName(outputFile)

            // TODO(bazel-team): We also need to check for a conflict between an output file and its own
            // rule, which is not yet in the targets map.

            // Check if this output file is the prefix of an already existing one.
            if (outputFilePrefixes!!.containsKey(outputFileName)) {
                throw overlappingOutputFilePrefixes(outputFile, outputFilePrefixes.get(outputFileName))
            }

            // Check if a prefix of this output file matches an already existing one.
            val outputFileFragment: PathFragment = PathFragment.create(outputFileName)
            val segmentCount: Int = outputFileFragment.segmentCount()
            for (i in 1..<segmentCount) {
                val prefix: String? = outputFileFragment.subFragment(0, i).toString()
                if (outputFilesByName.containsKey(prefix)) {
                    throw overlappingOutputFilePrefixes(outputFile, outputFilesByName.get(prefix))
                }
                if (targetMap.get(prefix) is OutputFile) {
                    throw overlappingOutputFilePrefixes(outputFile, targetMap.get(prefix) as OutputFile?)
                }

                // Store in persistent map, for checking when adding future rules.
                outputFilePrefixes.putIfAbsent(prefix, outputFile)
            }
        }

        // Check for the same file appearing as both an input and output of the new rule.
        val packageIdentifier: PackageIdentifier = rule.getLabel().getPackageIdentifier()
        for (inputLabel in labels) {
            if (packageIdentifier.equals(inputLabel.getPackageIdentifier())
                && outputFilesByName.containsKey(inputLabel.name)
            ) {
                throw NameConflictException(
                    java.lang.String.format(
                        "rule '%s' has file '%s' as both an input and an output",
                        ruleName, inputLabel.name
                    ),
                    outputFilesByName.get(inputLabel.name)
                )
            }
        }
    }

    /**
     * Throws [NameConflictException] if the given target's name can't be added because of a
     * conflict. If the given target's name violates symbolic macro naming rules, this method doesn't
     * throw but instead records that the target's name is in violation, so that an attempt to use the
     * target will fail during the analysis phase.
     * 
     * 
     * The given target must *not* have already been added.
     * 
     * 
     * We defer enforcement of symbolic macro naming rules for targets to the analysis phase
     * because otherwise, we could not use java rules (which declare lib%{name}-src.jar implicit
     * outputs) transitively in any symbolic macro.
     */
    // TODO(#19922): Provide a way to allow targets which violate naming rules to be configured
    // (either only as a dep to other targets declared in the current macro, or also externally).
    // TODO(#19922): Ensure `bazel build //pkg:all` (or //pkg:*) ignores violating targets.
    @Throws(NameConflictException::class)
    private fun checkTargetName(target: com.google.devtools.build.lib.packages.Target) {
        // We only care about the target's name, but we accept the full Target object to produce better
        // error messages.
        checkForExistingTargetName(target)

        checkForExistingMacroName(TargetOrMacro.Companion.of(target))

        if (currentMacroFrame != null
            && !nameIsWithinMacroNamespace(
                target.getName(), currentMacroFrame!!.macroInstance.getName()
            )
        ) {
            macroNamespaceViolatingTargets.put(
                target.getName(), currentMacroFrame!!.macroInstance.getName()
            )
        }
    }

    /**
     * Adds all given map entries to the builder's map from names of targets declared in a symbolic
     * macro which violate macro naming rules to the name of the macro instance where they were
     * declared.
     * 
     * 
     * Intended to be used for package deserialization.
     */
    fun putAllMacroNamespaceViolatingTargets(
        macroNamespaceViolatingTargets: MutableMap<String?, String?>?
    ) {
        this.macroNamespaceViolatingTargets.putAll(macroNamespaceViolatingTargets)
    }

    /**
     * Adds all given map entries to this builder's map from names of targets declared in symbolic
     * macros to the definition location of said symbolic macro.
     * 
     * 
     * Intended to be used for package deserialization.
     */
    fun putAllTargetsToDeclaringPackage(
        targetsToDeclaringPackage: MutableMap<String?, PackageIdentifier?>?
    ) {
        com.google.common.base.Preconditions.checkState(
            this.targetsToDeclaringPackage != null,
            "can only be called if trackFullMacroInformation was set to false in constructor"
        )
        this.targetsToDeclaringPackage.putAll(targetsToDeclaringPackage)
    }

    /**
     * Throws [NameConflictException] if the given target's name matches that of an existing
     * target in the package, or an existing macro in the package that is not its ancestor.
     * 
     * 
     * The given target must *not* have already been added.
     */
    @Throws(NameConflictException::class)
    private fun checkForExistingTargetName(target: com.google.devtools.build.lib.packages.Target) {
        val existing: com.google.devtools.build.lib.packages.Target? = targetMap.get(target.getName())
        if (existing == null) {
            return
        }

        var subject: String? = java.lang.String.format("%s '%s'", target.getTargetKind(), target.getName())
        if (target is OutputFile) {
            subject += java.lang.String.format(" in rule '%s'", target.getGeneratingRule().getName())
        }

        var `object`: String? =
            if (existing is OutputFile)
                java.lang.String.format(
                    "generated file from rule '%s'", existing.getGeneratingRule().getName()
                )
            else
                existing.getTargetKind()
        `object` += ", defined at " + existing.getLocation()

        throw NameConflictException(
            java.lang.String.format("%s conflicts with existing %s", subject, `object`), target
        )
    }

    /**
     * Throws [NameConflictException] if the given macro's name can't be added, either because
     * of a conflict or because of a violation of symbolic macro naming rules (if applicable).
     * 
     * 
     * The given macro must *not* have already been added (via [.addMacro]).
     */
    @Throws(NameConflictException::class)
    private fun checkMacroName(macro: MacroInstance) {
        val name: String = macro.getName()

        // A macro can share names with its main target but no other target. Since the macro hasn't
        // even been added yet, it hasn't run, and its main target is not yet defined. Therefore, any
        // match in the targets map represents a real conflict.
        val existingTarget: com.google.devtools.build.lib.packages.Target? = targetMap.get(name)
        if (existingTarget != null) {
            throw NameConflictException(
                java.lang.String.format("macro '%s' conflicts with an existing target.", name), macro
            )
        }

        checkForExistingMacroName(TargetOrMacro.Companion.of(macro))

        if (currentMacroFrame != null
            && !nameIsWithinMacroNamespace(name, currentMacroFrame!!.macroInstance.getName())
        ) {
            throw MacroNamespaceViolationException(
                java.lang.String.format(
                    "macro '%s' cannot declare submacro named '%s'. %s",
                    currentMacroFrame!!.macroInstance.getName(), name, MACRO_NAMING_RULES
                ),
                macro
            )
        }
    }

    /**
     * Throws [NameConflictException] if the given name (of a hypothetical target or macro)
     * matches the name of an existing macro in the package, and the existing macro is not currently
     * executing (i.e. on the macro stack).
     * 
     * 
     * `what` must be either "macro" or "target".
     */
    @Throws(NameConflictException::class)
    private fun checkForExistingMacroName(targetOrMacro: TargetOrMacro) {
        val name = targetOrMacro.getName()
        if (!hasMacroWithName(name)) {
            return
        }

        // A conflict is still ok if it's only with enclosing macros. It's enough to check that 1) we
        // have the same name as the immediately enclosing macro (relying inductively on the check
        // that was done when that macro was added), and 2) there is no sibling macro of the same name
        // already defined in the current frame.
        if (currentMacroFrame != null) {
            if (name == currentMacroFrame!!.macroInstance.getName()
                && !currentMacroFrame.mainSubmacroHasBeenDefined
            ) {
                return
            }
        }

        // TODO(#19922): Add definition location info for the existing object, like we have in
        // checkForExistingTargetName. Complicated by the fact that there may be more than one macro
        // of that name.
        throw NameConflictException(
            java.lang.String.format(
                "%s '%s' conflicts with an existing macro (and was not created by it)",
                targetOrMacro.getKind(), name
            ),
            targetOrMacro
        )
    }

    private class TargetOrMacro(targetOrMacro: Any) {
        private val targetOrMacro: Any

        init {
            com.google.common.base.Preconditions.checkArgument(targetOrMacro is com.google.devtools.build.lib.packages.Target || targetOrMacro is MacroInstance)
            this.targetOrMacro = targetOrMacro
        }

        fun getMacro(): MacroInstance? {
            return if (targetOrMacro is MacroInstance) targetOrMacro else null
        }

        fun getTarget(): com.google.devtools.build.lib.packages.Target? {
            return if (targetOrMacro is com.google.devtools.build.lib.packages.Target) targetOrMacro else null
        }

        fun getName(): String {
            return if (targetOrMacro is com.google.devtools.build.lib.packages.Target) targetOrMacro.getName() else getMacro().getName()
        }

        fun getKind(): String {
            return if (targetOrMacro is com.google.devtools.build.lib.packages.Target) "target" else "macro"
        }

        companion object {
            fun of(target: com.google.devtools.build.lib.packages.Target): TargetOrMacro {
                return TargetOrMacro(target)
            }

            fun of(macro: MacroInstance): TargetOrMacro {
                return TargetOrMacro(macro)
            }
        }
    }

    /**
     * An exception used when the name of a target or symbolic macro clashes with another entity
     * defined in the package.
     * 
     * 
     * Common examples of conflicts include two targets or symbolic macros sharing the same name,
     * and one output file being a prefix of another. See [Package.Builder.checkForExistingName]
     * and [Package.Builder.checkRuleAndOutputs] for more details.
     */
    open class NameConflictException : java.lang.Exception {
        var subject: TargetOrMacro

        constructor(message: String?, subject: TargetOrMacro) : super(message) {
            this.subject = subject
        }

        constructor(message: String?, subject: com.google.devtools.build.lib.packages.Target) : super(message) {
            this.subject = TargetOrMacro.Companion.of(subject)
        }

        constructor(message: String?, subject: MacroInstance) : super(message) {
            this.subject = TargetOrMacro.Companion.of(subject)
        }

        /**
         * Returns the target whose evaluation threw this exception; or null if the exception was caused
         * by the evaluation of a macro.
         */
        fun getTarget(): com.google.devtools.build.lib.packages.Target? {
            return subject.getTarget()
        }

        /**
         * Returns the macro whose evaluation threw this exception; or null if the exception was caused
         * by the evaluation of a target.
         */
        fun getMacro(): MacroInstance? {
            return subject.getMacro()
        }
    }

    /**
     * An exception used when the name of a target or submacro declared within a symbolic macro
     * violates symbolic macro naming rules.
     * 
     * 
     * An example might be a target named "libfoo" declared within a macro named "foo".
     */
    class MacroNamespaceViolationException : NameConflictException {
        constructor(message: String?, subject: com.google.devtools.build.lib.packages.Target) : super(message, subject)

        constructor(message: String?, subject: MacroInstance) : super(message, subject)
    }

    companion object {
        /** Used for constructing macro namespace violation error messages.  */
        val MACRO_NAMING_RULES: String =
            ("Name must be the same as the macro's name, or the macro's name followed by '_'"
                    + " (recommended), '-', or '.', and a non-empty string.")

        /**
         * Returns whether a given `name` is within the namespace that would be owned by a macro
         * called `macroName`.
         * 
         * 
         * This is purely a string operation and does not reference actual targets and macros.
         * 
         * 
         * A macro named "foo" owns the namespace consisting of "foo" and all "foo_${BAR}",
         * "foo-${BAR}", or "foo.${BAR}", where ${BAR} is a non-empty string. ("_" is the recommended
         * separator; "." is required for file extensions.) This criteria is transitive; a submacro's
         * namespace is a subset of the parent macro's namespace. Therefore, if a name is valid w.r.t. the
         * macro that declares it, it is also valid for all ancestor macros.
         * 
         * 
         * Note that just because a name is within a macro's namespace does not necessarily mean the
         * corresponding target or macro was declared within this macro.
         */
        fun nameIsWithinMacroNamespace(name: String, macroName: String?): Boolean {
            if (name == macroName) {
                return true
            } else if (name.startsWith(macroName)) {
                val suffix: String = name.substring(macroName.length())
                // 0-length suffix handled above.
                if (suffix.length() >= 2
                    && (suffix.startsWith("_") || suffix.startsWith(".") || suffix.startsWith("-"))
                ) {
                    return true
                }
            }
            return false
        }

        /**
         * Returns a [NameConflictException] about two output files clashing (i.e., due to one being
         * a prefix of the other)
         */
        private fun overlappingOutputFilePrefixes(
            added: OutputFile, existing: OutputFile
        ): NameConflictException {
            if (added.getGeneratingRule() === existing.getGeneratingRule()) {
                return NameConflictException(
                    java.lang.String.format(
                        "rule '%s' has conflicting output files '%s' and '%s'",
                        added.getGeneratingRule().getName(), added.getName(), existing.getName()
                    ),
                    added
                )
            } else {
                return NameConflictException(
                    java.lang.String.format(
                        "output file '%s' of rule '%s' conflicts with output file '%s' of rule '%s'",
                        added.getName(),
                        added.getGeneratingRule().getName(),
                        existing.getName(),
                        existing.getGeneratingRule().getName()
                    ),
                    added
                )
            }
        }
    }
}
