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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition.PATCH_TRANSITION_KEY

/**
 * A Starlark API for the ruleContext.
 * 
 * 
 * "This object becomes featureless once the rule implementation function that it was created for
 * has completed. To achieve this, the [.close] should be called once the evaluation of the
 * function is completed. The method both frees memory by deleting all significant fields of the
 * object and makes it impossible to accidentally use this object where it's not supposed to be used
 * (such attempts will result in [EvalException]s).
 */
class StarlarkRuleContext
    (ruleContext: RuleContext?, aspectDescriptor: AspectDescriptor?) : StarlarkRuleContextApi<ConstraintValueInfo?>,
    StarlarkActionContext {
    // This field is a copy of the info from ruleContext, stored separately so it can be accessed
    // after this object has been nullified.
    private val ruleLabelCanonicalName: String?

    private val isForAspect: Boolean

    private val actionFactory: StarlarkActionFactory

    // The fields below are intended to be final except that they can be cleared by calling
    // `close()` when the object becomes featureless (analogous to freezing).
    private var ruleContext: RuleContext?
    private var fragments: FragmentCollection?
    private var aspectDescriptor: AspectDescriptor?

    // The current rule class under evaluation (in case of extended rule this changes to parent when
    // ctx.super is called)
    private var ruleClassUnderEvaluation: RuleClass

    // Was super called in the context of current parent, it's set to false each time
    // ruleClassUnderEvaluation changes, and it's expected to be set to true when ctx.super is called.
    private var superCalled = false

    /**
     * This variable is used to expose the state of [ ][RuleContext.configurationMakeVariableContext] to the user via `ctx.var`.
     * 
     * 
     * Computing this field causes a side-effect of initializing the Make var context.
     * 
     * 
     * Note that StarlarkRuleContext can (for pathological user-written rules) survive the analysis
     * phase and be accessed concurrently. Nonetheless, it is still safe to initialize `ctx.var`
     * lazily without synchronization, because `ctx.var` is inaccessible once `close()`
     * has been called.
     */
    private var cachedMakeVariables: Dict<String?, String?>? = null

    private var attributesCollection: StarlarkAttributesCollection? = null
    private var ruleAttributesCollection: StarlarkAttributesCollection? = null
    private var splitAttributes: StructImpl? = null
    private var outputsObject: Outputs? = null

    /**
     * Counter for calls to `ctx.resolve_command` with a command longer than [ ][CommandHelper.maxCommandLength].
     * 
     * 
     * Such calls require generating a script. This counter ensures that each call results in
     * unique script name to avoid action conflicts.
     */
    private var resolveCommandScriptCounter = 0

    // for temporarily freezing mutability, while evaluating a subrule this is set to the
    // corresponding subrule context, or is null otherwise
    private var lockedForSubruleEvaluation: SubruleContext? = null

    /**
     * Creates a new StarlarkRuleContext wrapping ruleContext.
     * 
     * 
     * `aspectDescriptor` is the aspect for which the context is created, or `
     * null` if it is for a rule.
     */
    init {
        // Init ruleContext first, we need it to obtain the StarlarkSemantics used by
        // StarlarkActionFactory (and possibly others).
        this.ruleContext = com.google.common.base.Preconditions.checkNotNull<RuleContext?>(ruleContext)
        this.actionFactory = StarlarkActionFactory(this)
        this.ruleLabelCanonicalName = ruleContext.getLabel().getCanonicalForm()
        this.fragments = FragmentCollection(ruleContext)
        this.aspectDescriptor = aspectDescriptor
        this.isForAspect = aspectDescriptor != null
        this.ruleClassUnderEvaluation = ruleContext.getRule().getRuleClassObject()

        val rule: Rule = ruleContext.getRule()

        if (aspectDescriptor == null) {
            val attributes: MutableCollection<Attribute> =
                rule.getAttributes().stream()
                    .filter({ attribute -> !attribute.getName().equals("aspect_hints") })
                    .collect(Collectors.toList())

            // Populate ctx.outputs.
            val outputs = Outputs(this)
            // These getters do some computational work to return a view, so ensure we only do it once.
            val explicitOutMap: com.google.common.collect.ImmutableListMultimap<String?, OutputFile?> =
                rule.getExplicitOutputFileMap()
            val implicitOutMap: com.google.common.collect.ImmutableMap<String?, OutputFile?> =
                rule.getStarlarkImplicitOutputFileMap()
            // Add the explicit outputs -- values of attributes of type OUTPUT or OUTPUT_LIST.
            // We must iterate over the attribute definitions, and not just the entries in the
            // explicitOutMap, because the latter omits empty output attributes, which must still
            // generate None or [] fields in the struct.
            for (a in attributes) {
                // Skip non-output attrs.
                val attrName: String? = a.getName()
                val type: Type<*> = a.getType()
                if (type.getLabelClass() !== LabelClass.OUTPUT) {
                    continue
                }

                // Grab all associated outputs.
                val artifactsBuilder: com.google.common.collect.ImmutableList.Builder<Artifact?> =
                    com.google.common.collect.ImmutableList.builder<Artifact?>()
                for (outputFile in explicitOutMap.get(attrName)) {
                    artifactsBuilder.add(ruleContext.createOutputArtifact(outputFile))
                }
                val artifacts: StarlarkList<Artifact?> =
                    StarlarkList.immutableCopyOf<Artifact?>(artifactsBuilder.build())

                // For singular output attributes, unwrap sole element or else use None for arity mismatch.
                if (type === BuildType.OUTPUT) {
                    if (artifacts.size() == 1) {
                        outputs.addOutput(
                            attrName,
                            com.google.common.collect.Iterables.getOnlyElement<Artifact?>(artifacts)
                        )
                    } else {
                        outputs.addOutput(attrName, Starlark.NONE)
                    }
                } else if (type === BuildType.OUTPUT_LIST) {
                    outputs.addOutput(attrName, artifacts)
                } else {
                    throw ruleContext.throwWithRuleError(
                        java.lang.String.format("Attribute %s has unexpected output type %s", attrName, type)
                    )
                }
            }
            // Add the implicit outputs. In the case where the rule has a native-defined implicit outputs
            // function, nothing is added. Note that Rule ensures that Starlark-defined implicit output
            // keys don't conflict with output attribute names.
            // TODO(bazel-team): Also see about requiring the key to be a valid Starlark identifier.
            for (e in implicitOutMap.entrySet()) {
                outputs.addOutput(e.getKey(), ruleContext.createOutputArtifact(e.getValue()))
            }

            this.outputsObject = outputs

            // Populate ctx.attr.
            val builder: com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder =
                StarlarkAttributesCollection.Companion.builder(this, ruleContext.getRulePrerequisitesCollection())
            for (attribute in attributes) {
                val value: Any? = ruleContext.attributes().get(attribute.getName(), attribute.getType())
                builder.addAttribute(attribute, value)
            }

            this.attributesCollection = builder.build()
            this.splitAttributes = buildSplitAttributeInfo(attributes, ruleContext)
            this.ruleAttributesCollection = null
        } else { // ASPECT
            this.outputsObject = null
            val attributes: com.google.common.collect.ImmutableCollection<Attribute> =
                ruleContext.getMainAspect().getDefinition().getAttributes().values()

            val aspectBuilder: com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder =
                StarlarkAttributesCollection.Companion.builder(
                    this, (ruleContext as AspectContext).getMainAspectPrerequisitesCollection()
                )
            for (attribute in attributes) {
                var defaultValue: Any? = attribute.getDefaultValue(null)
                if (defaultValue is ComputedDefault) {
                    defaultValue = defaultValue.getDefault(ruleContext.attributes())
                }
                aspectBuilder.addAttribute(attribute, defaultValue)
            }
            this.attributesCollection = aspectBuilder.build()

            this.splitAttributes = null
            val ruleBuilder: com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder =
                StarlarkAttributesCollection.Companion.builder(this, ruleContext.getRulePrerequisitesCollection())
            try {
                val makeVariables: Dict<String?, String?>? =
                    (ruleContext as AspectContext)
                        .getBaseTargetConfigurationMakeVariableContext()
                        .collectMakeVariables()
                        .buildImmutable()
                ruleBuilder.putAllRuleVariables(makeVariables)
            } catch (e: ExpansionException) {
                throw ruleContext.throwWithRuleError("Exception expanding template variables", e)
            }

            for (attribute in rule.getAttributes()) {
                val value: Any? = ruleContext.attributes().get(attribute.getName(), attribute.getType())
                ruleBuilder.addAttribute(attribute, value)
            }
            for (aspect in ruleContext.getAspects()) {
                if (aspect.equals(ruleContext.getMainAspect())) {
                    // Aspect's own attributes are in <code>attributesCollection</code>.
                    continue
                }
                for (attribute in aspect.getDefinition().getAttributes().values()) {
                    var defaultValue: Any? = attribute.getDefaultValue(null)
                    if (defaultValue is ComputedDefault) {
                        defaultValue = defaultValue.getDefault(ruleContext.attributes())
                    }
                    ruleBuilder.addAttribute(attribute, defaultValue)
                }
            }

            this.ruleAttributesCollection = ruleBuilder.build()
        }
    }

    /** Returns the subrules declared by the rule or aspect represented by this context.  */
    fun getSubrules(): com.google.common.collect.ImmutableSet<out StarlarkSubruleApi?> {
        if (isForAspect()) {
            return getRuleContext().getMainAspect().getDefinition().getSubrules()
        } else {
            return getRuleClassUnderEvaluation().getSubrules()
        }
    }

    fun getRuleClassUnderEvaluation(): RuleClass {
        return ruleClassUnderEvaluation
    }

    fun setLockedForSubrule(lockedBy: SubruleContext?) {
        this.lockedForSubruleEvaluation = lockedBy
    }

    fun getLockedForSubrule(): SubruleContext? {
        return lockedForSubruleEvaluation
    }

    /**
     * Represents `ctx.outputs`.
     * 
     * 
     * The value of its `ctx.outputs.executable` field is computed on-demand.
     * 
     * 
     * Note: There is only one `Outputs` object per rule context, so default (object
     * identity) equals and hashCode suffice.
     */
    // TODO(adonovan): add StarlarkBuiltin(name="ctx.outputs") annotation.
    private class Outputs(context: StarlarkRuleContext) : Structure, StarlarkValue {
        private val outputs: MutableMap<String?, Any?>
        private val context: StarlarkRuleContext
        private var executableCreated = false

        init {
            this.outputs = LinkedHashMap<String?, Any?>()
            this.context = context
        }

        @Throws(RuleErrorException::class)
        fun addOutput(key: String?, value: Any?) {
            com.google.common.base.Preconditions.checkState(!context.isImmutable())
            // TODO(bazel-team): We should reject outputs whose key is not an identifier. Today this is
            // allowed, and the resulting ctx.outputs value can be retrieved using getattr().
            if (outputs.containsKey(key)
                || (context.isExecutable() && EXECUTABLE_OUTPUT_NAME == key)
            ) {
                context.getRuleContext().throwWithRuleError("Multiple outputs with the same key: " + key)
            }
            outputs.put(key, value)
        }

        override fun isImmutable(): Boolean {
            return context.isImmutable()
        }

        override fun getFieldNames(): com.google.common.collect.ImmutableCollection<String?> {
            // TODO(b/175954936): There's an NPE here when accessing dir(ctx.outputs) after rule
            // analysis has completed. Since we can't throw EvalException here, this may require that we
            // preemptively copy the fields into this object, or at least keep a "nullified" bit so we
            // know to produce an empty result here.
            val result: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            if (context.isExecutable() && executableCreated) {
                result.add(EXECUTABLE_OUTPUT_NAME)
            }
            result.addAll(outputs.keySet())
            return result.build()
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun getValue(name: String?): Any? {
            checkMutable()
            if (context.isExecutable() && EXECUTABLE_OUTPUT_NAME == name) {
                executableCreated = true
                // createOutputArtifact() will cache the created artifact.
                return context.getRuleContext().createOutputArtifact()
            }

            return outputs.get(name)
        }

        override fun getErrorMessageForUnknownField(name: String?): String? {
            return java.lang.String.format(
                "No attribute '%s' in outputs. Make sure you declared a rule output with this name.",
                name
            )
        }

        override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
            if (isImmutable()) {
                printer.append("ctx.outputs(for ")
                printer.append(context.ruleLabelCanonicalName)
                printer.append(")")
                return
            }
            var first = true
            printer.append("ctx.outputs(")
            // Sort by field name to ensure deterministic output.
            try {
                for (field in com.google.common.collect.Ordering.natural<Comparable<*>?>()
                    .sortedCopy<String?>(getFieldNames())) {
                    if (!first) {
                        printer.append(", ")
                    }
                    first = false
                    printer.append(field)
                    printer.append(" = ")
                    printer.repr(getValue(field), semantics)
                }
                printer.append(")")
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.AssertionError("mutable ctx.outputs should not throw", e)
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun checkMutable() {
            if (isImmutable()) {
                throw Starlark.errorf(
                    "cannot access outputs of rule '%s' outside of its own rule implementation function",
                    context.ruleLabelCanonicalName
                )
            }
        }
    }

    fun isExecutable(): Boolean {
        return ruleContext.getRule().getRuleClassObject().isExecutableStarlark()
    }

    fun isDefaultExecutableCreated(): Boolean {
        return this.outputsObject.executableCreated
    }

    /**
     * Nullifies fields of the object when it's not supposed to be used anymore to free unused memory
     * and to make sure this object is not accessed when it's not supposed to (after the corresponding
     * rule implementation function has exited).
     * 
     * 
     * Does a check if parent was called.
     */
    fun close() {
        // Check super was called
        if (ruleClassUnderEvaluation.getStarlarkParent() != null && !superCalled && !isForAspect()) {
            ruleContext.ruleError("'super' was not called.")
        }

        ruleContext = null
        fragments = null
        aspectDescriptor = null
        cachedMakeVariables = null
        attributesCollection = null
        ruleAttributesCollection = null
        splitAttributes = null
        outputsObject = null
    }

    /** Returns the [ArtifactRoot] for newly declared artifacts for use in actions.  */
    override fun newFileRoot(): ArtifactRoot? {
        return if (isForAspect())
            getRuleContext().getBinDirectory()
        else
            getRuleContext().getBinOrGenfilesDirectory()
    }

    /** Throws an EvalException mentioning `attrName` if we've already been nullified.  */
    @Throws(net.starlark.java.eval.EvalException::class)
    override fun checkMutable(attrName: String?) {
        if (isImmutable()) {
            throw Starlark.errorf(
                "cannot access field or method '%s' of rule context for '%s' outside of its own rule "
                        + "implementation function",
                attrName, ruleLabelCanonicalName
            )
        }
    }

    fun getAspectDescriptor(): AspectDescriptor? {
        return aspectDescriptor
    }

    fun getRuleLabelCanonicalName(): String? {
        return ruleLabelCanonicalName
    }

    override fun isImmutable(): Boolean {
        return ruleContext == null || lockedForSubruleEvaluation != null
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
        if (isForAspect) {
            printer.append("<aspect context for " + ruleLabelCanonicalName + ">")
        } else {
            printer.append("<rule context for " + ruleLabelCanonicalName + ">")
        }
    }

    /** Returns the wrapped ruleContext.  */
    override fun getRuleContext(): RuleContext? {
        return ruleContext
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun actions(): StarlarkActionFactory {
        // ruleContext will be null when this StarlarkRuleContext is frozen. Accessing ctx.actions when
        // frozen will throw other errors, so just ignore this for materializer rules.
        if (ruleContext != null && ruleContext.getRule().getRuleClassObject().isMaterializerRule()) {
            throw Starlark.errorf("ctx.actions is not available in materializer rules")
        }
        return actionFactory
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    public override fun callParent(thread: StarlarkThread): Any? {
        checkMutable("super()")
        if (isForAspect()) {
            throw Starlark.errorf("Can't use 'super' call in an aspect.")
        }
        if (ruleClassUnderEvaluation.getStarlarkParent() == null) {
            throw Starlark.errorf("Can't use 'super' call, the rule has no parent.")
        }
        if (superCalled) {
            throw Starlark.errorf("'super' called the second time.")
        }

        val previousClassUnderEvaluation: RuleClass = ruleClassUnderEvaluation
        ruleClassUnderEvaluation = ruleClassUnderEvaluation.getStarlarkParent()

        var rawProviders: Any? = null
        try {
            superCalled = false
            rawProviders =
                StarlarkRuleConfiguredTargetUtil.evalRule(ruleContext, ruleClassUnderEvaluation)
        } finally {
            if (ruleClassUnderEvaluation.getStarlarkParent() != null && !superCalled) {
                ruleContext.ruleError(
                    java.lang.String.format(
                        "in %s rule: 'super' was not called.", ruleClassUnderEvaluation.getName()
                    )
                )
            }
            ruleClassUnderEvaluation = previousClassUnderEvaluation
        }

        if (rawProviders == null) {
            throw Starlark.errorf("Error evaluating parent rule.")
        }

        // Normalize the return type
        if (rawProviders is Info) {
            // Either an old-style struct or a single declared provider (not in a list)
            if (rawProviders.getProvider().getKey().equals(StructProvider.STRUCT.getKey())) {
                throw Starlark.errorf(
                    "Parent rule returned struct providers. Rules returning struct providers can't be"
                            + " extended."
                )
            }
            rawProviders = StarlarkList.of<Any?>(thread.mutability(), rawProviders)
        } else if (rawProviders === Starlark.NONE) {
            rawProviders = StarlarkList.empty<Any?>()
        }
        superCalled = true

        return rawProviders
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun createdActions(): StarlarkValue? {
        checkMutable("created_actions")
        if (ruleContext.getRule().getRuleClassObject().isStarlarkTestable()) {
            return ActionsProvider.create(ruleContext.getAnalysisEnvironment().getRegisteredActions())
        } else {
            return Starlark.NONE
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getAttr(): StructImpl? {
        checkMutable("attr")
        return attributesCollection.getAttr()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getSplitAttr(): StructImpl? {
        checkMutable("split_attr")
        if (splitAttributes == null) {
            throw net.starlark.java.eval.EvalException("'split_attr' is available only in rule implementations")
        }
        return splitAttributes
    }

    /** See [RuleContext.getExecutablePrerequisite].  */
    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getExecutable(): StructImpl? {
        checkMutable("executable")
        return attributesCollection.getExecutable()
    }

    /** See [RuleContext.getPrerequisiteArtifact].  */
    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getFile(): StructImpl? {
        checkMutable("file")
        return attributesCollection.getFile()
    }

    /** See [RuleContext.getPrerequisiteArtifacts].  */
    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getFiles(): StructImpl? {
        checkMutable("files")
        return attributesCollection.getFiles()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getWorkspaceName(): String? {
        checkMutable("workspace_name")
        return ruleContext.getWorkspaceName()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getLabel(): Label? {
        checkMutable("label")
        return ruleContext.getLabel()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getFragments(): FragmentCollection? {
        checkMutable("fragments")
        return fragments
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getConfiguration(): BuildConfigurationValue? {
        checkMutable("configuration")
        return ruleContext.getConfiguration()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getBuildSettingValue(): Any? {
        if (ruleContext.getRule().getRuleClassObject().getBuildSetting() == null) {
            throw Starlark.errorf(
                "attempting to access 'build_setting_value' of non-build setting %s",
                ruleLabelCanonicalName
            )
        }
        val starlarkFlagSettings: com.google.common.collect.ImmutableMap<Label?, Any?> =
            ruleContext.getConfiguration().getOptions().getStarlarkOptions()

        val buildSetting: BuildSetting = ruleContext.getRule().getRuleClassObject().getBuildSetting()
        if (starlarkFlagSettings.containsKey(ruleContext.getLabel())) {
            return starlarkFlagSettings.get(ruleContext.getLabel())
        } else {
            val defaultValue: Any =
                ruleContext
                    .attributes()
                    .get(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, buildSetting.getType())
            return if (buildSetting.allowsMultiple()) com.google.common.collect.ImmutableList.of<Any?>(defaultValue) else defaultValue
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun instrumentCoverage(targetUnchecked: Any?): Boolean {
        checkMutable("coverage_instrumented")
        val config: BuildConfigurationValue? = ruleContext.getConfiguration()
        if (!config.isCodeCoverageEnabled()) {
            return false
        }
        if (targetUnchecked === Starlark.NONE) {
            return InstrumentedFilesCollector.shouldIncludeLocalSources(
                ruleContext.getConfiguration(), ruleContext.getLabel(), ruleContext.isTestTarget()
            )
        }
        val target: TransitiveInfoCollection = targetUnchecked as TransitiveInfoCollection
        return (target.get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR) != null)
                && InstrumentedFilesCollector.shouldIncludeLocalSources(config, target)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getFeatures(): com.google.common.collect.ImmutableList<String?> {
        checkMutable("features")
        return com.google.common.collect.ImmutableList.copyOf<String?>(ruleContext.getFeatures())
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getDisabledFeatures(): com.google.common.collect.ImmutableList<String?> {
        checkMutable("disabled_features")
        return com.google.common.collect.ImmutableList.copyOf<String?>(ruleContext.getDisabledFeatures())
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getBinDirectory(): ArtifactRoot? {
        checkMutable("bin_dir")
        return getConfiguration().getBinDirectory(ruleContext.getRule().getRepository())
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getGenfilesDirectory(): ArtifactRoot? {
        checkMutable("genfiles_dir")
        return getConfiguration().getGenfilesDirectory(ruleContext.getRule().getRepository())
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun outputs(): Structure {
        checkMutable("outputs")
        if (outputsObject == null) {
            throw net.starlark.java.eval.EvalException("'outputs' is not defined")
        }
        return outputsObject
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun rule(): StarlarkAttributesCollection? {
        checkMutable("rule")
        if (!isForAspect) {
            throw net.starlark.java.eval.EvalException("'rule' is only available in aspect implementations")
        }
        return ruleAttributesCollection
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun aspectIds(): com.google.common.collect.ImmutableList<String?> {
        checkMutable("aspect_ids")
        if (!isForAspect) {
            throw net.starlark.java.eval.EvalException("'aspect_ids' is only available in aspect implementations")
        }

        val result: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        for (descriptor in ruleContext.getAspectDescriptors()) {
            result.add(descriptor.getDescription())
        }
        return result.build()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun `var`(): Dict<String?, String?>? {
        checkMutable("var")
        if (cachedMakeVariables == null) {
            val vars: net.starlark.java.eval.Dict.Builder<String?, String?>
            try {
                vars = ruleContext.getConfigurationMakeVariableContext().collectMakeVariables()
            } catch (e: ExpansionException) {
                throw net.starlark.java.eval.EvalException(e.getMessage())
            }

            // When tracking required fragments, use a key-tracking dict to support lookedUpVariables().
            cachedMakeVariables =
                if (ruleContext.shouldIncludeRequiredConfigFragmentsProvider())
                    vars.buildImmutableWithKeyTracking()
                else
                    vars.buildImmutable()
        }
        return cachedMakeVariables
    }

    /** Returns the set of variables accessed through `ctx.var`.  */
    fun lookedUpVariables(): com.google.common.collect.ImmutableSet<String?>? {
        com.google.common.base.Preconditions.checkState(
            ruleContext.shouldIncludeRequiredConfigFragmentsProvider(),
            this
        )
        return if (cachedMakeVariables == null)
            com.google.common.collect.ImmutableSet.of<String?>()
        else
            (cachedMakeVariables as ImmutableKeyTrackingDict<String?, String?>).getAccessedKeys()
    }

    // visible for subrules
    fun getRequestedToolchainTypeLabelsFromAutoExecGroups(): com.google.common.collect.ImmutableSet<Label?> {
        val toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>? =
            ruleContext.getToolchainContexts()

        return toolchainContexts.getExecGroupNames().stream()
            .filter(DeclaredExecGroup::isAutomatic)
            .flatMap(
                { execGroupName ->
                    toolchainContexts
                        .getToolchainContext(execGroupName)
                        .requestedToolchainTypeLabels()
                        .keySet()
                        .stream()
                })
            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun toolchains(): ToolchainContextApi {
        checkMutable("toolchains")

        if (ruleContext.getToolchainContext() == null) {
            return StarlarkToolchainContext.TOOLCHAINS_NOT_VALID
        }

        if (ruleContext.useAutoExecGroups()) {
            return StarlarkToolchainContext.create( /* targetDescription= */
                ruleContext.getToolchainContext().targetDescription(),  /* resolveToolchainDataFunc= */
                { toolchainType: Label -> ruleContext.getToolchainInfo(toolchainType) },  /* resolvedToolchainTypeLabels= */
                getRequestedToolchainTypeLabelsFromAutoExecGroups()
            )
        } else {
            return StarlarkToolchainContext.create( /* targetDescription= */
                ruleContext.getToolchainContext().targetDescription(),  /* resolveToolchainDataFunc= */
                ruleContext.getToolchainContext()::forToolchainType,  /* resolvedToolchainTypeLabels= */
                ruleContext
                    .getToolchainContext()
                    .requestedToolchainTypeLabels()
                    .keySet()
            )
        }
    }

    public override fun targetPlatformHasConstraint(constraintValue: ConstraintValueInfo?): Boolean {
        return ruleContext.targetPlatformHasConstraint(constraintValue)
    }

    public override fun execGroups(): StarlarkExecGroupCollection {
        // Create a thin wrapper around the toolchain collection, to expose the Starlark API.
        return StarlarkExecGroupCollection.create(ruleContext.getToolchainContexts())
    }

    override fun toString(): String {
        return ruleLabelCanonicalName!!
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun tokenize(optionString: String?): net.starlark.java.eval.Sequence<String?> {
        checkMutable("tokenize")
        val options: MutableList<String?> = java.util.ArrayList<String?>()
        try {
            ShellUtils.tokenize(options, optionString)
        } catch (e: TokenizationException) {
            throw Starlark.errorf("%s while tokenizing '%s'", e.getMessage(), optionString)
        }
        return StarlarkList.immutableCopyOf<String?>(options)
    }

    fun isForAspect(): Boolean {
        return isForAspect
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun checkPlaceholders(
        template: String?,
        allowedPlaceholders: net.starlark.java.eval.Sequence<*>?
    ): Boolean {
        checkMutable("check_placeholders")
        val actualPlaceHolders: MutableList<String?> = LinkedList<String?>()
        val allowedPlaceholderSet: MutableSet<String?> =
            com.google.common.collect.ImmutableSet.copyOf<String?>(
                net.starlark.java.eval.Sequence.cast<String?>(
                    allowedPlaceholders,
                    String::class.java,
                    "allowed_placeholders"
                )
            )
        ImplicitOutputsFunction.createPlaceholderSubstitutionFormatString(template, actualPlaceHolders)
        for (placeholder in actualPlaceHolders) {
            if (!allowedPlaceholderSet.contains(placeholder)) {
                return false
            }
        }
        return true
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun expandMakeVariables(
        attributeName: String?, command: String?, additionalSubstitutions: Dict<*, *>?
    ): String? {
        checkMutable("expand_make_variables")
        val additionalSubstitutionsMap: MutableMap<String?, String?> =
            Dict.cast<String?, String?>(
                additionalSubstitutions,
                String::class.java,
                String::class.java,
                "additional_substitutions"
            )
        return expandMakeVariables(attributeName, command, additionalSubstitutionsMap)
    }

    private fun expandMakeVariables(
        attributeName: String?, command: String?, additionalSubstitutionsMap: MutableMap<String?, String?>
    ): String? {
        val makeVariableContext: ConfigurationMakeVariableContext =
            object : ConfigurationMakeVariableContext(
                ruleContext.getRule().getPackageDeclarations(),
                ruleContext.getConfiguration(),
                ruleContext.getDefaultTemplateVariableProviders()
            ) {
                @Throws(ExpansionException::class)
                override fun lookupVariable(variableName: String?): String? {
                    if (additionalSubstitutionsMap.containsKey(variableName)) {
                        return additionalSubstitutionsMap.get(variableName)
                    } else {
                        return super.lookupVariable(variableName)
                    }
                }
            }
        return ruleContext.getExpander(makeVariableContext).expand(attributeName, command)
    }

    /** Returns the [FilesToRunProvider] corresponding to the supplied `executable`  */
    override fun getExecutableRunfiles(executable: Artifact?, what: String?): FilesToRunProvider? {
        return attributesCollection.getExecutableRunfilesMap().get(executable)
    }

    /**
     * Returns true iff the supplied [FilesToRunProvider] is from an executable attribute of
     * this rule.
     */
    override fun areRunfilesFromDeps(executable: FilesToRunProvider?): Boolean {
        return attributesCollection.getExecutableRunfilesMap().containsValue(executable)
    }

    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    public override fun getStableWorkspaceStatus(): Artifact? {
        checkMutable("info_file")
        return ruleContext.getAnalysisEnvironment().getStableWorkspaceStatusArtifact()
    }

    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    public override fun getVolatileWorkspaceStatus(): Artifact? {
        checkMutable("version_file")
        return ruleContext.getAnalysisEnvironment().getVolatileWorkspaceStatusArtifact()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getBuildFileRelativePath(): String {
        checkMutable("build_file_path")
        checkDeprecated("ctx.label.package + '/BUILD'", "ctx.build_file_path", getStarlarkSemantics())

        val pkgMetadata: Package.Metadata = ruleContext.getRule().getPackageMetadata()
        return pkgMetadata
            .sourceRoot()
            .relativize(pkgMetadata.buildFilename().asPath())
            .getPathString()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun expandLocation(
        input: String?, targets: net.starlark.java.eval.Sequence<*>?, shortPaths: Boolean, thread: StarlarkThread
    ): String? {
        checkMutable("expand_location")
        try {
            val labelMap: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?> =
                makeLabelMap(
                    net.starlark.java.eval.Sequence.cast<TransitiveInfoCollection?>(
                        targets,
                        TransitiveInfoCollection::class.java,
                        "targets"
                    ),
                    thread
                        .getSemantics()
                        .getBool(BuildLanguageOptions.INCOMPATIBLE_LOCATIONS_PREFERS_EXECUTABLE)
                )
            val expander: LocationExpander?
            if (!shortPaths) {
                expander = LocationExpander.Companion.withExecPaths(ruleContext, labelMap)
            } else {
                checkPrivateAccess(thread)
                expander = LocationExpander.Companion.withRunfilesPaths(ruleContext, labelMap)
            }
            return expander.expand(input)
        } catch (ise: java.lang.IllegalStateException) {
            throw net.starlark.java.eval.EvalException(ise)
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class, TypeException::class)
    public override fun runfiles(
        files: net.starlark.java.eval.Sequence<*>,
        transitiveFiles: Any?,
        collectData: Boolean?,
        collectDefault: Boolean?,
        symlinks: Any,
        rootSymlinks: Any,
        skipConflictChecking: Boolean,
        thread: StarlarkThread?
    ): com.google.devtools.build.lib.analysis.Runfiles {
        if (skipConflictChecking) {
            checkPrivateAccess(thread)
        }
        checkMutable("runfiles")
        val builder: com.google.devtools.build.lib.analysis.Runfiles.Builder =
            com.google.devtools.build.lib.analysis.Runfiles.Builder(ruleContext.getWorkspaceName())
        var checkConflicts = false
        if (Starlark.truth(collectData)) {
            builder.addRunfiles(ruleContext, RunfilesProvider.Companion.DATA_RUNFILES)
        }
        if (Starlark.truth(collectDefault)) {
            builder.addRunfiles(ruleContext, RunfilesProvider.Companion.DEFAULT_RUNFILES)
        }
        if (!files.isEmpty()) {
            val artifacts: net.starlark.java.eval.Sequence<Artifact?> =
                net.starlark.java.eval.Sequence.cast<Artifact?>(files, Artifact::class.java, "files")
            try {
                builder.addArtifacts(artifacts)
            } catch (e: java.lang.IllegalArgumentException) {
                throw Starlark.errorf("could not add all 'files': %s", e.getMessage())
            }
        }
        if (transitiveFiles !== Starlark.NONE) {
            val transitiveArtifacts: NestedSet<Artifact?> =
                Depset.cast(transitiveFiles, Artifact::class.java, "transitive_files")

            // Runfiles uses compile order. Check that the given transitive_files depset is compatible.
            if (!Order.COMPILE_ORDER.isCompatible(transitiveArtifacts.getOrder())) {
                throw Starlark.errorf(
                    "order '%s' is invalid for transitive_files",
                    transitiveArtifacts.getOrder().getStarlarkName()
                )
            }
            builder.addTransitiveArtifacts(transitiveArtifacts)
        }
        if (isNonEmptyDepset(symlinks)) {
            // If Starlark code directly manipulates symlinks, activate more stringent validity checking.
            checkConflicts = true
            builder.addSymlinks((symlinks as Depset).getSet(SymlinkEntry::class.java))
        } else if (isNonEmptyDict(symlinks)) {
            checkConflicts = true
            for (entry in Dict.cast<String?, Artifact?>(symlinks, String::class.java, Artifact::class.java, "symlinks")
                .entrySet()) {
                builder.addSymlink(PathFragment.create(entry.getKey()), entry.getValue())
            }
        }
        if (isNonEmptyDepset(rootSymlinks)) {
            checkConflicts = true
            builder.addRootSymlinks((rootSymlinks as Depset).getSet(SymlinkEntry::class.java))
        } else if (isNonEmptyDict(rootSymlinks)) {
            checkConflicts = true
            for (entry in Dict.cast<String?, Artifact?>(
                rootSymlinks,
                String::class.java,
                Artifact::class.java,
                "root_symlinks"
            ).entrySet()) {
                builder.addRootSymlink(PathFragment.create(entry.getKey()), entry.getValue())
            }
        }
        val runfiles: com.google.devtools.build.lib.analysis.Runfiles = builder.build()
        if (checkConflicts && !skipConflictChecking) {
            runfiles.setConflictPolicy(ConflictPolicy.ERROR)
        }
        return runfiles
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun resolveCommand(
        command: String?,
        attributeUnchecked: Any?,
        expandLocations: Boolean,
        makeVariablesUnchecked: Any?,
        tools: net.starlark.java.eval.Sequence<*>?,
        labelDictUnchecked: Dict<*, *>,
        executionRequirementsUnchecked: Dict<*, *>?,
        thread: StarlarkThread
    ): Tuple? {
        var command = command
        checkMutable("resolve_command")
        val labelDict: MutableMap<Label?, Iterable<Artifact?>?> = checkLabelDict(labelDictUnchecked)
        // The best way to fix this probably is to convert CommandHelper to Starlark.
        val helper: CommandHelper =
            CommandHelper.Companion.builder(ruleContext)
                .addToolDependencies(
                    net.starlark.java.eval.Sequence.cast<TransitiveInfoCollection?>(
                        tools,
                        TransitiveInfoCollection::class.java,
                        "tools"
                    )
                )
                .addLabelMap(labelDict)
                .build()
        val attribute: String? = Type.STRING.convertOptional(attributeUnchecked, "attribute")
        if (expandLocations) {
            command =
                helper.resolveCommandAndExpandLabels(command, attribute,  /* allowDataInLabel= */false)
        }
        if (!Starlark.isNullOrNone(makeVariablesUnchecked)) {
            val makeVariables: MutableMap<String?, String?> =
                Types.STRING_DICT.convert(makeVariablesUnchecked, "make_variables")
            command = expandMakeVariables(attribute, command, makeVariables)
        }
        // TODO(lberki): This flattens a NestedSet.
        // However, we can't turn this into a Depset because it's an incompatible change to Starlark.
        val inputs: MutableList<Artifact?> = java.util.ArrayList<Any?>(helper.getResolvedTools().toList())

        val executionRequirements: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.copyOf<String?, String?>(
                Dict.noneableCast<String?, String?>(
                    executionRequirementsUnchecked,
                    String::class.java,
                    String::class.java,
                    "execution_requirements"
                )
            )
        // TODO(b/234923262): Take exec_group into consideration instead of using the default
        // exec_group.
        val executionPlatform: PlatformInfo? = ruleContext.getExecutionPlatform()
        val shExecutable: PathFragment? =
            ShToolchain.getPathForPlatform(ruleContext.getConfiguration(), executionPlatform)

        val constructor: BashCommandConstructor =
            CommandHelper.Companion.buildBashCommandConstructor(
                executionRequirements,
                shExecutable,
                java.lang.String.format(".resolve_command_%d.script.sh", resolveCommandScriptCounter++)
            )
        val argv: MutableList<String?>? =
            helper.buildCommandLine(
                command, inputs, constructor, getOsFromConstraintsOrHost(executionPlatform)
            )
        return Tuple.triple(
            StarlarkList.copyOf<Artifact?>(thread.mutability(), inputs),
            StarlarkList.copyOf<String?>(thread.mutability(), argv),
            StarlarkList.empty<Any?>()
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun resolveTools(tools: net.starlark.java.eval.Sequence<*>?): Tuple? {
        checkMutable("resolve_tools")
        checkResolveToolsAllowed()
        val helper: CommandHelper =
            CommandHelper.Companion.builder(ruleContext)
                .addToolDependencies(
                    net.starlark.java.eval.Sequence.cast<TransitiveInfoCollection?>(
                        tools,
                        TransitiveInfoCollection::class.java,
                        "tools"
                    )
                )
                .build()
        return Tuple.pair(Depset.of(Artifact::class.java, helper.getResolvedTools()), StarlarkList.empty<Any?>())
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun checkResolveToolsAllowed() {
        if (getStarlarkSemantics()
                .getBool(BuildLanguageOptions.INCOMPATIBLE_DISALLOW_CTX_RESOLVE_TOOLS)
        ) {
            throw Starlark.errorf(
                ("Pass an executable or tools argument to ctx.actions.run or ctx.actions.run_shell"
                        + " instead of calling ctx.resolve_tools.\n"
                        + "Use --noincompatible_disallow_ctx_resolve_tools to temporarily disable this"
                        + " check.")
            )
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun packageRelativeLabel(input: Any?): Label? {
        checkMutable("package_relative_label")
        if (input is Label) {
            return input
        }
        try {
            return Label.parseWithPackageContext(
                input as String?,
                Label.PackageContext.of(
                    ruleContext.getLabel().getPackageIdentifier(),
                    ruleContext.getRule().getPackageMetadata().repositoryMapping()
                )
            )
        } catch (e: LabelSyntaxException) {
            throw Starlark.errorf("invalid label in ctx.package_relative_label: %s", e.getMessage())
        }
    }

    override fun getStarlarkSemantics(): StarlarkSemantics? {
        return ruleContext.getAnalysisEnvironment().getStarlarkSemantics()
    }

    companion object {
        private const val EXECUTABLE_OUTPUT_NAME = "executable"

        private fun buildSplitAttributeInfo(
            attributes: MutableCollection<Attribute>, ruleContext: RuleContext
        ): StructImpl {
            val splitAttrInfos: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builder<String?, Any?>()
            for (attr in attributes) {
                if (!attr.getTransitionFactory().isSplit()) {
                    continue
                }
                val splitPrereqs: MutableMap<com.google.common.base.Optional<String?>?, MutableList<ConfiguredTargetAndData?>?> =
                    ruleContext.getRulePrerequisitesCollection().getSplitPrerequisites(attr.getName())

                val splitPrereqsMap: MutableMap<Any?, Any?> = LinkedHashMap<Any?, Any?>()
                for (splitPrereq in splitPrereqs.entrySet()) {
                    // Skip a split with an empty dependency list.
                    // TODO(jungjw): Figure out exactly which cases trigger this and see if this can be made
                    // more error-proof.

                    if (splitPrereq.getValue().isEmpty()) {
                        continue
                    }

                    val value: Any?
                    if (attr.getType() === BuildType.LABEL) {
                        com.google.common.base.Preconditions.checkState(splitPrereq.getValue().size() == 1)
                        value = splitPrereq.getValue().get(0).getConfiguredTarget()
                    } else if (attr.getType() === BuildType.LABEL_DICT_UNARY) {
                        val prerequisites: com.google.common.collect.ImmutableList<ConfiguredTarget?> =
                            splitPrereq.getValue().stream()
                                .map<Any?>(ConfiguredTargetAndData::getConfiguredTarget)
                                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())

                        value =
                            com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder.Companion.convertStringToLabelMap(
                                ruleContext.attributes().get(attr.getName(), BuildType.LABEL_DICT_UNARY),
                                prerequisites
                            )
                    } else if (attr.getType() === BuildType.LABEL_LIST_DICT) {
                        val prerequisites: com.google.common.collect.ImmutableList<ConfiguredTarget?> =
                            splitPrereq.getValue().stream()
                                .map<Any?>(ConfiguredTargetAndData::getConfiguredTarget)
                                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())

                        value =
                            com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder.Companion.convertStringToLabelListMap(
                                ruleContext.attributes().get(attr.getName(), BuildType.LABEL_LIST_DICT),
                                prerequisites
                            )
                    } else {
                        value =
                            StarlarkList.immutableCopyOf<Any?>(
                                splitPrereq.getValue().stream()
                                    .map<Any?>(ConfiguredTargetAndData::getConfiguredTarget)
                                    .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
                            )
                    }

                    if (splitPrereq.getKey().isPresent()
                        && splitPrereq.getKey().get() != PATCH_TRANSITION_KEY
                    ) {
                        splitPrereqsMap.put(splitPrereq.getKey().get(), value)
                    } else {
                        // If the split transition is not in effect, then the key will be missing since there's
                        // nothing to key on because the dependencies aren't split and getSplitPrerequisites()
                        // behaves like getPrerequisites(). This also means there should be only one entry in
                        // the map. Use None in Starlark to represent this.
                        com.google.common.base.Preconditions.checkState(splitPrereqs.size() == 1)
                        splitPrereqsMap.put(Starlark.NONE, value)
                    }
                }

                splitAttrInfos.put(attr.getPublicName(), Dict.immutableCopyOf<Any?, Any?>(splitPrereqsMap))
            }

            return StructProvider.STRUCT.create(
                splitAttrInfos.buildOrThrow(),
                "No attribute '%s' in split_attr."
                        + "This attribute is not defined with a split configuration."
            )
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkDeprecated(newApi: String?, oldApi: String?, semantics: StarlarkSemantics) {
            if (semantics.getBool(BuildLanguageOptions.INCOMPATIBLE_STOP_EXPORTING_BUILD_FILE_PATH)) {
                throw Starlark.errorf(
                    ("Use %s instead of %s.\n"
                            + "Use --incompatible_stop_exporting_build_file_path=false to temporarily disable"
                            + " this check."),
                    newApi, oldApi
                )
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkPrivateAccess(thread: StarlarkThread?) {
            BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        }

        private fun isNonEmptyDict(o: Any?): Boolean {
            return o is Dict && !(o as Dict<*, *>).isEmpty()
        }

        private fun isNonEmptyDepset(o: Any): Boolean {
            return o is Depset && !(o as Depset).isEmpty()
        }

        /**
         * Ensures the given [Map] has keys that have [Label] type and values that have either
         * [Iterable] or [Depset] type, and raises [EvalException] otherwise. Returns a
         * corresponding map where any sets are replaced by iterables.
         */
        // TODO(bazel-team): find a better way to typecheck this argument.
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkLabelDict(labelDict: MutableMap<*, *>): MutableMap<Label?, Iterable<Artifact?>?> {
            val convertedMap: MutableMap<Label?, Iterable<Artifact?>?> = HashMap<Label?, Iterable<Artifact?>?>()
            for (entry in labelDict.entrySet()) {
                val key: Any? = entry.getKey()
                if (key !is Label) {
                    throw Starlark.errorf(
                        "invalid key %s in 'label_dict'", Starlark.repr(key, StarlarkSemantics.DEFAULT)
                    )
                }
                val files: com.google.common.collect.ImmutableList.Builder<Artifact?> =
                    com.google.common.collect.ImmutableList.builder<Artifact?>()
                val `val`: Any? = entry.getValue()
                val valIter: Iterable<*>?
                if (`val` is Iterable<*>) {
                    valIter = `val`
                } else {
                    throw Starlark.errorf(
                        "invalid value %s in 'label_dict': expected iterable, but got '%s'",
                        Starlark.repr(`val`, StarlarkSemantics.DEFAULT), Starlark.type(`val`)
                    )
                }
                for (file in valIter) {
                    if (file !is Artifact) {
                        throw Starlark.errorf(
                            "invalid value %s in 'label_dict'", Starlark.repr(`val`, StarlarkSemantics.DEFAULT)
                        )
                    }
                    files.add(file as Artifact?)
                }
                convertedMap.put(key as Label?, files.build())
            }
            return convertedMap
        }

        /**
         * Builds a map: Label -> List of files from the given labels
         * 
         * @param knownLabels List of known labels
         * @param locationsPrefersExecutable whether to prefer an executable over a list of files that
         * isn't a singleton when requesting a plural location function
         * @return Immutable map with immutable collections as values
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun makeLabelMap(
            knownLabels: Iterable<TransitiveInfoCollection>, locationsPrefersExecutable: Boolean
        ): com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?> {
            val targetsMap: LinkedHashMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?> =
                LinkedHashMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>()
            for (current in knownLabels) {
                val label: Label = AliasProvider.Companion.getDependencyLabel(current)
                if (targetsMap.containsKey(label)) {
                    throw Starlark.errorf(
                        "Label %s is found more than once in 'targets' list.",
                        Starlark.repr(label.toString(), StarlarkSemantics.DEFAULT)
                    )
                }

                val filesToRunProvider: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    current.getProvider(FilesToRunProvider::class.java)
                val expansion: com.google.common.collect.ImmutableCollection<Artifact?>?
                // Only use the executable if requesting the files to build via a singleton location function
                // would fail to ensure backwards compatibility (this function used to always return the
                // files to build). The case of a plural location function is potentially breaking, but gated
                // behind locationsPrefersExecutable.
                // Avoid flattening a nested set if the first branch is taken.
                if (filesToRunProvider != null && filesToRunProvider.getExecutable() != null && locationsPrefersExecutable
                    && !current.getProvider(FileProvider::class.java).getFilesToBuild().isSingleton()
                ) {
                    expansion = com.google.common.collect.ImmutableList.of<E?>(filesToRunProvider.getExecutable())
                } else {
                    expansion = current.getProvider(FileProvider::class.java).getFilesToBuild().toList()
                }
                targetsMap.put(label, expansion)
            }

            return com.google.common.collect.ImmutableMap.copyOf<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>(
                targetsMap
            )
        }
    }
}
