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
 * Base class of [Package.Builder] and [PackagePiece.Builder] that encapsulates all the
 * operations that may need to occur in the middle of BUILD file or symbolic macro evaluation,
 * without including operations specific to the setup or finalization of `Package`
 * construction.
 * 
 * 
 * In other words, if a [Package.Builder] or and [PackagePiece.Builder] method needs
 * to be called as a result of Starlark evaluation of either the BUILD file or its macros, the
 * operation belongs in this base class.
 * 
 * 
 * The motivation for this split is two-fold: 1) It keeps the size of Package.java smaller. 2) It
 * will make it easier to factor out common code for evaluating a whole package vs an individual
 * symbolic macro of that package (lazy macro evaluation).
 */
abstract class TargetDefinitionContext internal constructor(
    metadata: com.google.devtools.build.lib.packages.Package.Metadata,
    pkg: Packageoid,
    symbolGenerator: net.starlark.java.eval.SymbolGenerator<*>?,
    simplifyUnconditionalSelectsInRuleAttrs: Boolean,
    mainRepositoryMapping: RepositoryMapping?,
    cpuBoundSemaphore: Semaphore?,
    packageOverheadEstimator: PackageOverheadEstimator,
    generatorMap: com.google.common.collect.ImmutableMap<net.starlark.java.syntax.Location?, String?>?,
    globber: Globber?,
    enableNameConflictChecking: Boolean,
    trackFullMacroInformation: Boolean,
    enableTargetMapSnapshotting: Boolean,
    packageLimits: PackageLimits
) : StarlarkThreadContext({ mainRepositoryMapping }) {
    // TODO: #19922 - Avoid protected fields, encapsulate with getters/setters. Temporary state on way
    // to separating this class from Package.Builder.
    private val symbolGenerator: net.starlark.java.eval.SymbolGenerator<*>?

    // Same as pkg.metadata.
    protected val metadata: com.google.devtools.build.lib.packages.Package.Metadata

    /**
     * The [Package] to be constructed with the help of this context.
     * 
     * 
     * Since the package has not yet been constructed, it is in an intermediate state and some
     * operations may fail unexpectedly. `TargetDefinitionContext` only uses this field to help
     * create the cyclic links between packages and their targets.
     */
    protected val pkg: Packageoid

    // The container object on which targets and macro instances are added and conflicts are
    // detected.
    val recorder: TargetRecorder

    private val simplifyUnconditionalSelectsInRuleAttrs: Boolean

    /** Converts label literals to Label objects within this package.  */
    private val labelConverter: LabelConverter

    /**
     * Semaphore held by the Skyframe thread when performing CPU work.
     * 
     * 
     * This should be released when performing I/O.
     */
    // Only non-null when inside PackageFunction.compute and the semaphore is enabled.
    private val cpuBoundSemaphore: Semaphore?

    /** Estimates the cost of this packageoid.  */
    protected val packageOverheadEstimator: PackageOverheadEstimator

    // TreeMap so that the iteration order of variables is consistent regardless of insertion order
    // (which may change due to serialization). This is useful so that the serialized representation
    // is deterministic.
    protected val makeEnv: TreeMap<String?, String?> = TreeMap<String?, String?>()

    protected val localEventHandler: StoredEventHandler = StoredEventHandler()

    protected var ioExceptionMessage: String? = null
    protected var ioException: IOException? = null
    protected var ioExceptionDetailedExitCode: DetailedExitCode? = null

    // Used by glob(). Null for contexts where glob() is disallowed, like some tests.
    private val globber: Globber?

    protected val environmentGroups: MutableMap<Label?, EnvironmentGroup?> = HashMap<Label?, EnvironmentGroup?>()

    private val listInterner: com.google.common.collect.Interner<com.google.common.collect.ImmutableList<*>?> =
        ThreadCompatibleInterner<com.google.common.collect.ImmutableList<*>?>()

    private val generatorMap: com.google.common.collect.ImmutableMap<net.starlark.java.syntax.Location?, String?>

    private val packageLimits: PackageLimits

    protected val testSuiteImplicitTestsAccumulator: TestSuiteImplicitTestsAccumulator =
        TestSuiteImplicitTestsAccumulator()

    // A packageoid's FailureDetail field derives from the events on its Builder's event handler.
    // During package deserialization, those events are unavailable, because those events aren't
    // serialized [*]. Its FailureDetail value is serialized, however. During deserialization, that
    // value is assigned here, so that it can be assigned to the deserialized package.
    //
    // Likewise, during workspace part assembly, errors from parent parts should propagate to their
    // children.
    //
    // [*] Not in the context of the package, anyway. Skyframe values containing a package may
    // serialize events emitted during its construction/evaluation.
    private var failureDetailOverride: FailureDetail? = null

    protected var alreadyBuilt: Boolean = false

    protected var computationSteps: Long = 0

    internal enum class FromOrFailMode {
        NO_MACROS,
        ONLY_FINALIZER_MACROS,
    }

    /**
     * Returns an auto-closeable resource to synchronize the computation step count between this
     * context and its thread which has started execution.
     */
    fun updateStartedThreadComputationSteps(
        thread: net.starlark.java.eval.StarlarkThread
    ): StartedThreadComputationStepUpdater {
        return StartedThreadComputationStepUpdater(this, thread)
    }

    /**
     * Returns an auto-closeable resource to synchronize the computation step count between this
     * context and its thread whose execution is being paused, e.g. before pushing a new macro frame.
     */
    fun updatePausedThreadComputationSteps(
        thread: net.starlark.java.eval.StarlarkThread
    ): PausedThreadComputationStepUpdater {
        return PausedThreadComputationStepUpdater(this, thread)
    }

    /**
     * An auto-closeable resource to synchronize the computation step count between a [ ] and its thread which has started execution.
     */
    class StartedThreadComputationStepUpdater(
        context: TargetDefinitionContext,
        thread: net.starlark.java.eval.StarlarkThread
    ) : java.lang.AutoCloseable {
        private val context: TargetDefinitionContext
        private val thread: net.starlark.java.eval.StarlarkThread
        private var closed = false

        init {
            this.context = context
            this.thread = thread
            // Initialize the thread's computation step count to the context's total computation step
            // count.
            thread.incrementExecutedSteps(context.computationSteps)
            var threadMaxExecutionSteps: Long = context.packageLimits.maxStarlarkComputationStepsPerPackage()
            if (threadMaxExecutionSteps < java.lang.Long.MAX_VALUE) {
                // StarlarkThread.setMaxExecutionSteps(limit) throws if we hit limit, but we want to allow
                // hitting the limit (but not going over).
                threadMaxExecutionSteps++
            }
            thread.setMaxExecutionSteps(threadMaxExecutionSteps)
        }

        override fun close() {
            if (!closed) {
                context.setComputationSteps(thread.getExecutedSteps())
            }
            closed = true
        }
    }

    /**
     * An auto-closeable resource to synchronize the computation step count between a [ ] and its thread whose execution is being paused.
     */
    class PausedThreadComputationStepUpdater(
        context: TargetDefinitionContext,
        thread: net.starlark.java.eval.StarlarkThread
    ) : java.lang.AutoCloseable {
        private val context: TargetDefinitionContext
        private val thread: net.starlark.java.eval.StarlarkThread
        private var closed = false

        init {
            this.context = context
            this.thread = thread
            context.setComputationSteps(thread.getExecutedSteps())
        }

        override fun close() {
            if (!closed) {
                com.google.common.base.Preconditions.checkState(
                    thread.getExecutedSteps() <= context.computationSteps,
                    "previously paused thread computation steps = %s cannot be greater than currently"
                            + " recorded computation steps = %s",
                    thread.getExecutedSteps(),
                    context.computationSteps
                )
                thread.incrementExecutedSteps(context.computationSteps - thread.getExecutedSteps())
            }
            closed = true
        }
    }

    /**
     * Sets the context's computation step count from the computation step count of the current
     * thread.
     */
    private fun setComputationSteps(threadComputationSteps: Long) {
        com.google.common.base.Preconditions.checkState(
            threadComputationSteps >= computationSteps,
            "currently running thread computation steps = %s cannot be less than previously recorded"
                    + " computation steps = %s",
            threadComputationSteps,
            computationSteps
        )
        computationSteps = threadComputationSteps
    }

    /** Returns the "generator_name" to use for a given call site location in a BUILD file.  */
    fun getGeneratorNameByLocation(loc: net.starlark.java.syntax.Location?): String? {
        return generatorMap.get(loc)
    }

    /**
     * Returns the map from BUILD file locations to "generator_name" values; intended only for use by
     * skyframe.PackageFunction.
     */
    fun getGeneratorMap(): com.google.common.collect.ImmutableMap<net.starlark.java.syntax.Location?, String?> {
        return generatorMap
    }

    /**
     * Returns the value to use for `test_suite`s' `$implicit_tests` attribute, as-is,
     * when the `test_suite` doesn't specify an explicit, non-empty `tests` value. The
     * returned list is mutated by the package-building process - it may be observed to be empty or
     * incomplete before package loading is complete. When package loading is complete it will contain
     * the label of each non-manual test matching the provided tags in the package, in label order.
     * 
     * 
     * This method **MUST** be called before the package is built - otherwise the requested
     * implicit tests won't be accumulated.
     */
    fun getTestSuiteImplicitTestsRef(tags: MutableList<String?>?): MutableList<Label?>? {
        return testSuiteImplicitTestsAccumulator.getTestSuiteImplicitTestsRefForTags(tags)
    }

    @ThreadCompatible
    private class ThreadCompatibleInterner<T> : com.google.common.collect.Interner<T?> {
        private val interns: MutableMap<T?, T?> = HashMap<T?, T?>()

        override fun intern(sample: T?): T? {
            val existing: T? = interns.putIfAbsent(sample, sample)
            return com.google.common.base.MoreObjects.firstNonNull<T?>(existing, sample)
        }
    }

    init {
        this.metadata = metadata
        this.pkg = pkg
        this.symbolGenerator = symbolGenerator
        this.simplifyUnconditionalSelectsInRuleAttrs = simplifyUnconditionalSelectsInRuleAttrs
        this.labelConverter =
            LabelConverter(metadata.packageIdentifier, metadata.repositoryMapping)
        this.cpuBoundSemaphore = cpuBoundSemaphore
        this.packageOverheadEstimator = packageOverheadEstimator
        this.generatorMap =
            if (generatorMap == null) com.google.common.collect.ImmutableMap.of<net.starlark.java.syntax.Location?, String?>() else generatorMap
        this.globber = globber
        this.recorder =
            TargetRecorder(
                enableNameConflictChecking, trackFullMacroInformation, enableTargetMapSnapshotting
            )
        this.packageLimits = packageLimits
    }

    fun getMetadata(): com.google.devtools.build.lib.packages.Package.Metadata {
        return metadata
    }

    fun getSymbolGenerator(): net.starlark.java.eval.SymbolGenerator<*>? {
        return symbolGenerator
    }

    fun getPackageIdentifier(): PackageIdentifier? {
        return metadata.packageIdentifier
    }

    /**
     * Returns a short, lower-case description of the packageoid under construction, e.g. for use in
     * logging and error messages.
     */
    fun getShortDescription(): String? {
        return pkg.getShortDescription()
    }

    /**
     * Returns the name of the Bazel module associated with the repo this package is in. If this
     * package is in the special `@_builtins` pseudo-repo, this is empty. For repos generated by
     * module extensions, this is the name of the module hosting the extension.
     */
    fun getAssociatedModuleName(): java.util.Optional<String?>? {
        return metadata.associatedModuleName
    }

    /**
     * Returns the version of the Bazel module associated with the repo this package is in. If this
     * package is in the special `@_builtins` pseudo-repo, this is empty. For repos generated by
     * module extensions, this is the version of the module hosting the extension.
     */
    fun getAssociatedModuleVersion(): java.util.Optional<String?>? {
        return metadata.associatedModuleVersion
    }

    fun getLabelConverter(): LabelConverter {
        return labelConverter
    }

    fun getListInterner(): com.google.common.collect.Interner<com.google.common.collect.ImmutableList<*>?> {
        return listInterner
    }

    fun getFilename(): RootedPath? {
        return metadata.buildFilename
    }

    /** Returns the [StoredEventHandler] associated with this builder.  */
    fun getLocalEventHandler(): StoredEventHandler {
        return localEventHandler
    }

    /**
     * Retrieves the current package args. Note that during BUILD file evaluation these are still
     * subject to mutation.
     */
    fun getPartialPackageArgs(): PackageArgs? {
        return pkg.getDeclarations().getPackageArgs()
    }

    fun containsErrors(): Boolean {
        return recorder.containsErrors()
    }

    /**
     * Declares that errors were encountering while loading this package.
     * 
     * 
     * If this method is called, then there should also be an ERROR event added to the handler on
     * the [Package.Builder]. The event should include a [FailureDetail].
     */
    fun setContainsErrors() {
        com.google.common.base.Preconditions.checkState(
            pkg.targets == null,
            "TargetDefinitionContext.setContainsErrors() can only be used before finishBuild() has"
                    + " propagated the builder's error status to the packageoid"
        )
        recorder.setContainsErrors()
    }

    fun setIOException(e: IOException?, message: String?, detailedExitCode: DetailedExitCode?) {
        this.ioException = e
        this.ioExceptionMessage = message
        this.ioExceptionDetailedExitCode = detailedExitCode
        setContainsErrors()
    }

    /**
     * Returns the [Globber] used to implement `glob()` functionality during BUILD
     * evaluation. Null for contexts where globbing is not possible, like some tests.
     */
    fun getGlobber(): Globber? {
        return globber
    }

    /**
     * Returns true if values of conditional rule attributes which only contain unconditional selects
     * should be simplified and stored as a non-select value.
     */
    fun simplifyUnconditionalSelectsInRuleAttrs(): Boolean {
        return this.simplifyUnconditionalSelectsInRuleAttrs
    }

    /**
     * Returns the innermost currently executing symbolic macro, or null if not in a symbolic macro.
     */
    fun currentMacro(): MacroInstance? {
        val frame: MacroFrame? = recorder.getCurrentMacroFrame()
        return if (frame == null) null else frame.macroInstance
    }

    /**
     * Creates a new [Rule] `r` where `r.getPackageoid()` is the [Packageoid]
     * associated with this [Builder].
     * 
     * 
     * The created [Rule] will have no output files and therefore will be in an invalid
     * state.
     * 
     * @param threadCallStack the call stack of the thread that created the rule. Call stacks for
     * threads of enclosing symbolic macros (if any) will be prepended to it automatically to form
     * the rule's full call stack.
     */
    fun createRule(
        label: Label?,
        ruleClass: RuleClass,
        threadCallStack: MutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>
    ): com.google.devtools.build.lib.packages.Rule {
        var fullInteriorCallStack: CallStack.Node?
        val location: net.starlark.java.syntax.Location?
        if (currentMacro() != null) {
            location = currentMacro().getBuildFileLocation()
            fullInteriorCallStack = CallStack.compact(threadCallStack,  /* start= */0)
            var macro: MacroInstance? = currentMacro()
            while (macro != null) {
                fullInteriorCallStack =
                    CallStack.concatenate(macro.getParentCallStack(), fullInteriorCallStack)
                macro = macro.getParent()
            }
        } else {
            location =
                if (threadCallStack.isEmpty()) net.starlark.java.syntax.Location.BUILTIN else threadCallStack.get(0).location
            fullInteriorCallStack = CallStack.compact(threadCallStack,  /* start= */1)
        }
        return createRule(label, ruleClass, location, fullInteriorCallStack)
    }

    fun createRule(
        label: Label?,
        ruleClass: RuleClass,
        location: net.starlark.java.syntax.Location?,
        interiorCallStack: CallStack.Node?
    ): com.google.devtools.build.lib.packages.Rule {
        return com.google.devtools.build.lib.packages.Rule(pkg, label, ruleClass, location, interiorCallStack)
    }

    /** Creates a new [MacroInstance] in this builder's packageoid.  */
    @Throws(LabelSyntaxException::class, net.starlark.java.eval.EvalException::class)
    fun createMacro(
        macroClass: MacroClass,
        name: String?,
        sameNameDepth: Int,
        parentCallStack: MutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>
    ): MacroInstance {
        val parent: MacroInstance? = currentMacro()
        val location: net.starlark.java.syntax.Location?
        val compactParentCallStack: CallStack.Node?
        if (parent != null) {
            location = parent.getBuildFileLocation()
            compactParentCallStack = CallStack.compact(parentCallStack,  /* start= */0)
        } else {
            location =
                if (parentCallStack.isEmpty()) net.starlark.java.syntax.Location.BUILTIN else parentCallStack.get(0).location
            compactParentCallStack = CallStack.compact(parentCallStack,  /* start= */1)
        }
        return MacroInstance(
            pkg.getMetadata(),
            pkg.getDeclarations(),
            parent,
            if (parent != null) parent.getGeneratorName() else generatorMap.get(location),
            location,
            compactParentCallStack,
            macroClass,
            Label.create(pkg.getMetadata().packageIdentifier, name),
            sameNameDepth
        )
    }

    /** Returns true if symbolic macros should be eagerly expanded in this context.  */
    abstract fun eagerlyExpandMacros(): Boolean

    fun getCurrentMacroFrame(): MacroFrame? {
        return recorder.getCurrentMacroFrame()
    }

    fun setCurrentMacroFrame(frame: MacroFrame?): MacroFrame? {
        return recorder.setCurrentMacroFrame(frame)
    }

    fun currentlyInNonFinalizerMacro(): Boolean {
        return recorder.currentlyInNonFinalizerMacro()
    }

    fun getTarget(name: String?): com.google.devtools.build.lib.packages.Target? {
        return recorder.getTarget(name)
    }

    // TODO: #19922 - Refactor finalizer expansion such that TargetDefinitionContext can handle
    // working with finalizer macros. At that point, getRulesSnapshotView() and
    // getNonFinalizerInstantiatedRule() must account for the snapshot view here rather than in the
    // override in Package.Builder.
    /**
     * Returns a lightweight snapshot view of the names of all rule targets belonging to this package
     * at the time of this call; in finalizer expansion stage, returns a lightweight snapshot view of
     * only the non-finalizer-instantiated rule targets.
     * 
     * @throws IllegalStateException if this method is called after [     ][Package.Builder.beforeBuild] has been called.
     */
    open fun getRulesSnapshotView(): MutableMap<String?, com.google.devtools.build.lib.packages.Rule?>? {
        if (recorder.getTargetMap() is SnapshottableBiMap<*, *>) {
            return com.google.common.collect.Maps.transformValues<String?, com.google.devtools.build.lib.packages.Target?, com.google.devtools.build.lib.packages.Rule?>(
                (recorder.getTargetMap() as SnapshottableBiMap<String?, com.google.devtools.build.lib.packages.Target?>).getTrackedSnapshot(),
                com.google.common.base.Function { target: com.google.devtools.build.lib.packages.Target? -> target as com.google.devtools.build.lib.packages.Rule? })
        } else {
            // TODO(https://github.com/bazelbuild/bazel/issues/23852): if we are in a PackagePiece
            // builder, trigger a skyframe restart and request a full Package.
            throw java.lang.IllegalStateException(
                "getRulesSnapshotView() cannot be used after beforeBuild() has been called"
            )
        }
    }

    /**
     * Returns a non-finalizer-instantiated rule target with the provided name belonging to this
     * package at the time of this call. If such a rule target cannot be returned, returns null.
     */
    // TODO(https://github.com/bazelbuild/bazel/issues/23765): when we restrict
    // native.existing_rule() to be usable only in finalizer context, we can replace this method
    // with {@code getRulesSnapshotView().get(name)}; we don't do so at present because we do not
    // want to make unnecessary snapshots.
    open fun getNonFinalizerInstantiatedRule(name: String?): com.google.devtools.build.lib.packages.Rule? {
        val target: com.google.devtools.build.lib.packages.Target? = recorder.getTargetMap().get(name)
        return if (target is com.google.devtools.build.lib.packages.Rule) target as com.google.devtools.build.lib.packages.Rule else null
    }

    /**
     * Creates an input file target in this package with the specified name, if it does not yet exist.
     * 
     * 
     * This operation is idempotent.
     * 
     * @param targetName name of the input file. This must be a valid target name as defined by [     ][com.google.devtools.build.lib.cmdline.LabelValidator.validateTargetName].
     * @return the newly-created `InputFile`, or the old one if it already existed.
     * @throws NameConflictException if the name was already taken by another target that is not an
     * input file
     * @throws IllegalArgumentException if the name is not a valid label
     */
    @Throws(NameConflictException::class)
    fun createInputFile(targetName: String?, location: net.starlark.java.syntax.Location?): InputFile {
        val existing: com.google.devtools.build.lib.packages.Target? = recorder.getTargetMap().get(targetName)

        if (existing is InputFile) {
            return existing as InputFile // idempotent
        }

        val inputFile: InputFile
        try {
            inputFile = InputFile(pkg, createLabel(targetName), location)
        } catch (e: LabelSyntaxException) {
            throw java.lang.IllegalArgumentException(
                "FileTarget in package " + metadata.getName() + " has illegal name: " + targetName, e
            )
        }

        recorder.addTarget(inputFile)
        return inputFile
    }

    /**
     * Sets the visibility and license for an input file. The input file must already exist as a
     * member of this package.
     * 
     * @throws IllegalArgumentException if the input file doesn't exist in this package's target map.
     */
    // TODO: #19922 - Don't allow exports_files() to modify visibility of targets that the current
    // symbolic macro did not create. Fun pathological example: exports_files() modifying the
    // visibility of :BUILD inside a symbolic macro.
    fun setVisibilityAndLicense(inputFile: InputFile, visibility: RuleVisibility?, license: License?) {
        val filename: String? = inputFile.getName()
        val cacheInstance: com.google.devtools.build.lib.packages.Target? = recorder.getTargetMap().get(filename)
        require(cacheInstance is InputFile) {
            ("Can't set visibility for nonexistent FileTarget "
                    + filename
                    + " in package "
                    + metadata.getName()
                    + ".")
        }
        if (!(cacheInstance as InputFile).isVisibilitySpecified() || cacheInstance.getVisibility() !== visibility || (cacheInstance.getLicense() != license)) {
            recorder.replaceInputFileUnchecked(
                VisibilityLicenseSpecifiedInputFile(
                    pkg, cacheInstance.getLabel(), cacheInstance.getLocation(), visibility, license
                )
            )
        }
    }

    /**
     * Creates a label for a target inside this package.
     * 
     * @throws LabelSyntaxException if the `targetName` is invalid
     */
    @Throws(LabelSyntaxException::class)
    fun createLabel(targetName: String?): Label {
        return Label.create(metadata.packageIdentifier, targetName)
    }

    /** Adds a package group to the package.  */
    @Throws(NameConflictException::class, LabelSyntaxException::class)
    fun addPackageGroup(
        name: String?,
        packages: MutableCollection<String?>,
        includes: MutableCollection<Label?>?,
        allowPublicPrivate: Boolean,
        repoRootMeansCurrentRepo: Boolean,
        eventHandler: EventHandler?,
        location: net.starlark.java.syntax.Location?
    ) {
        val group: PackageGroup =
            PackageGroup(
                createLabel(name),
                pkg,
                packages,
                includes,
                allowPublicPrivate,
                repoRootMeansCurrentRepo,
                eventHandler,
                location
            )
        recorder.addTarget(group)

        if (group.containsErrors()) {
            setContainsErrors()
        }
    }

    @Throws(NameConflictException::class)
    fun addRule(rule: com.google.devtools.build.lib.packages.Rule) {
        com.google.common.base.Preconditions.checkArgument(rule.getPackageoid() === pkg)
        recorder.addRule(rule)
    }

    @Throws(NameConflictException::class)
    open fun addMacro(macro: MacroInstance?) {
        recorder.addMacro(macro)
    }

    fun getCpuBoundSemaphore(): Semaphore? {
        return cpuBoundSemaphore
    }

    fun setFailureDetailOverride(failureDetail: FailureDetail?) {
        failureDetailOverride = failureDetail
    }

    fun getFailureDetail(): FailureDetail? {
        if (failureDetailOverride != null) {
            return failureDetailOverride
        }

        var undetailedEvents: MutableList<Event?>? = null
        for (event in localEventHandler.getEvents()) {
            if (event.getKind() !== EventKind.ERROR) {
                continue
            }
            val detailedExitCode: DetailedExitCode? = event.getProperty(DetailedExitCode::class.java)
            if (detailedExitCode != null && detailedExitCode.getFailureDetail() != null) {
                return detailedExitCode.getFailureDetail()
            }
            if (containsErrors()) {
                if (undetailedEvents == null) {
                    undetailedEvents = java.util.ArrayList<Event?>()
                }
                undetailedEvents!!.add(event)
            }
        }
        if (undetailedEvents != null) {
            BugReport.sendNonFatalBugReport(
                java.lang.IllegalStateException(
                    ("TargetDefinitionContext has undetailed error from "
                            + undetailedEvents
                            + " for packageoid "
                            + pkg)
                )
            )
        }
        return null
    }

    /**
     * Returns the number of Starlark computation steps executed thus far by threads performing
     * evaluation of this packageoid, which are recorded by updaters created by [ ][.updateStartedThreadComputationSteps] and [.updatePausedThreadComputationSteps].
     */
    fun getComputationSteps(): Long {
        return computationSteps
    }

    //
    // Packageoid (package or package piece) construction methods, intended for use only by
    // PackageFunction and friends.
    //
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(NoSuchPackageException::class)
    open fun beforeBuild(): TargetDefinitionContext? {
        if (ioException != null) {
            throw NoSuchPackageException(
                getPackageIdentifier(), ioExceptionMessage, ioException, ioExceptionDetailedExitCode
            )
        }

        // TODO(bazel-team): We run testSuiteImplicitTestsAccumulator here in beforeBuild(), but what
        // if one of the accumulated tests is later removed in PackageFunction, between the call to
        // buildPartial() and finishBuild(), due to a label-crossing-subpackage-boundary error? Seems
        // like that would mean a test_suite is referencing a Target that's been deleted from its
        // Package.

        // Clear tests before discovering them again in order to keep this method idempotent -
        // otherwise we may double-count tests if we're called twice due to a skyframe restart, etc.
        testSuiteImplicitTestsAccumulator.clearAccumulatedTests()
        for (rule in recorder.getRules()) {
            testSuiteImplicitTestsAccumulator.processRule(rule)
        }
        // Make sure all accumulated values are sorted for determinism.
        testSuiteImplicitTestsAccumulator.sortTests()

        return this
    }

    /** Intended for use by [com.google.devtools.build.lib.skyframe.PackageFunction] only.  */ // TODO(bazel-team): It seems like the motivation for this method (added in cl/74794332) is to
    // allow PackageFunction to delete targets that are found to violate the
    // label-crossing-subpackage-boundaries check. Is there a simpler way to express this idea that
    // doesn't make package-building a multi-stage process?
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(NoSuchPackageException::class)
    open fun buildPartial(): TargetDefinitionContext? {
        if (alreadyBuilt) {
            return this
        }
        return beforeBuild()
    }

    /**
     * Intended for use by [com.google.devtools.build.lib.skyframe.PackageFunction] only.
     * 
     * 
     * This method is intended to be overridden by subclasses to perform packageoid-specific final
     * initialization steps.
     */
    // Non-final only to allow subclasses to return a more specific type.
    open fun finishBuild(): Packageoid {
        if (alreadyBuilt) {
            return pkg
        }
        alreadyBuilt = true

        // Freeze rules, compacting their attributes' representations.
        for (rule in recorder.getRules()) {
            rule.freeze()
        }

        // Freeze macros, compacting their attributes' representations.
        for (macro in recorder.getMacroMap().values()) {
            macro.freeze()
        }

        // Last chance to set the builder's error status.
        finalBuilderValidationHook()

        // Initialize packageoid.
        pkg.containsErrors = pkg.containsErrors or containsErrors()
        pkg.failureDetail = getFailureDetail()
        pkg.targets =
            com.google.common.collect.ImmutableSortedMap.copyOf<String?, com.google.devtools.build.lib.packages.Target?>(
                recorder.getTargetMap()
            )

        packageoidInitializationHook()

        // Overhead should be estimated after all packageoid fields have been set.
        val overheadEstimate: OptionalLong = packageOverheadEstimator.estimatePackageOverhead(pkg)
        pkg.packageOverhead = overheadEstimate.orElse(Packageoid.Companion.PACKAGE_OVERHEAD_UNSET)

        // Verify that we haven't introduced new errors on the builder since the call to
        // finalBuilderValidationHook().
        if (containsErrors()) {
            com.google.common.base.Preconditions.checkState(
                pkg.containsErrors(), "Builder error status not propagated to package or package piece"
            )
        }

        return pkg
    }

    /**
     * Performs final builder validations (if needed), possibly modifying the builder's error status.
     * 
     * 
     * This method is intended to be overridden by subclasses; it is invoked by [ ][.finishBuild] immediately before initializing the packageoid and copying error status from
     * the builder to the packageoid.
     */
    protected open fun finalBuilderValidationHook() {}

    /**
     * Sets remaining subclass-specific fields on the packageoid.
     * 
     * 
     * This method is intended to be overridden by subclasses; it is invoked by [ ][.finishBuild] after [.finalBuilderValidationHook] has passed and the packageoid's
     * base fields (such as error information, targets, and macros) have been frozen and set. This
     * method must not call [.setContainsErrors] on the builder; but it is allowed to set
     * packageoid fields that impact overhead estimation.
     */
    protected open fun packageoidInitializationHook() {}

    companion object {
        /** Retrieves this object from a Starlark thread. Returns null if not present.  */
        fun fromOrNull(thread: net.starlark.java.eval.StarlarkThread): TargetDefinitionContext? {
            val ctx: StarlarkThreadContext? =
                thread.getThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java)
            return if (ctx is TargetDefinitionContext)
                ctx
            else
                null
        }

        /**
         * Retrieves this object from a Starlark thread. If not present, throws an [EvalException]
         * with an error message indicating that `what` can only be used in a target definition
         * context - meaning in a BUILD file, or a legacy or symbolic macro.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        fun fromOrFail(thread: net.starlark.java.eval.StarlarkThread, what: String?): TargetDefinitionContext? {
            return fromOrFail(thread, what, "used")
        }

        /**
         * Retrieves this object from a Starlark thread. If not present, throws an [EvalException]
         * with an error message indicating that `what` can only be `participle`d in a target
         * definition context - meaning in a BUILD file, or a legacy or symbolic macro.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        fun fromOrFail(
            thread: net.starlark.java.eval.StarlarkThread, what: String?, participle: String?
        ): TargetDefinitionContext? {
            val ctx: StarlarkThreadContext? =
                thread.getThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java)
            if (ctx is TargetDefinitionContext) {
                return ctx
            }
            throw newFromOrFailException(
                what, participle, thread.getSemantics(), EnumSet.noneOf<FromOrFailMode?>(FromOrFailMode::class.java)
            )
        }

        /**
         * Retrieves this object from a Starlark thread. If not present, throws an [EvalException]
         * with an error message indicating that `what` can only be used in a BUILD file or a
         * finalizer symbolic macro.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        fun fromOrFailDisallowNonFinalizerMacros(
            thread: net.starlark.java.eval.StarlarkThread, what: String?
        ): TargetDefinitionContext {
            val ctx: StarlarkThreadContext? =
                thread.getThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java)
            if (ctx is TargetDefinitionContext
                && !ctx.recorder.currentlyInNonFinalizerMacro()
            ) {
                return ctx
            }
            throw newFromOrFailException(
                what, thread.getSemantics(), EnumSet.of<FromOrFailMode?>(FromOrFailMode.ONLY_FINALIZER_MACROS)
            )
        }

        fun newFromOrFailException(
            what: String?, semantics: net.starlark.java.eval.StarlarkSemantics, modes: EnumSet<FromOrFailMode?>
        ): net.starlark.java.eval.EvalException {
            return newFromOrFailException(what, "used", semantics, modes)
        }

        fun newFromOrFailException(
            what: String?,
            participle: String?,
            semantics: net.starlark.java.eval.StarlarkSemantics,
            modes: EnumSet<FromOrFailMode?>
        ): net.starlark.java.eval.EvalException {
            // TODO(bazel-team): append a description of the current evaluation context to the error, e.g.
            // "foo() can only be used while evaluating a BUILD file or a legacy macro; in particular, it
            // cannot be used at the top level of a .bzl file"
            val symbolicMacrosEnabled: Boolean =
                semantics.getBool(BuildLanguageOptions.Companion.EXPERIMENTAL_ENABLE_FIRST_CLASS_MACROS)
            val allowedUses: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
            allowedUses.add("a BUILD file")
            allowedUses.add(
                java.lang.String.format(
                    "a %s%smacro",
                    if (symbolicMacrosEnabled) "legacy " else "",
                    if (symbolicMacrosEnabled
                        && !modes.contains(FromOrFailMode.NO_MACROS) && !modes.contains(FromOrFailMode.ONLY_FINALIZER_MACROS)
                    )
                        "or symbolic "
                    else
                        ""
                )
            )
            if (symbolicMacrosEnabled && modes.contains(FromOrFailMode.ONLY_FINALIZER_MACROS)) {
                allowedUses.add("a rule finalizer")
            }

            return net.starlark.java.eval.Starlark.errorf(
                "%s can only be %s while evaluating %s",
                what, participle, com.google.devtools.build.lib.util.StringUtil.joinEnglishList(allowedUses)
            )
        }
    }
}
