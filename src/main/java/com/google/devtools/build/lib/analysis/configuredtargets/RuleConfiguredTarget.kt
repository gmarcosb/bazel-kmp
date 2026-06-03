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
package com.google.devtools.build.lib.analysis.configuredtargets

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/**
 * A [com.google.devtools.build.lib.analysis.ConfiguredTarget] that is produced by a rule.
 * 
 * 
 * Created by [com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder]. There
 * is an instance of this class for every analyzed rule. For more information about how analysis
 * works, see [com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory].
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@AutoCodec
class RuleConfiguredTarget : AbstractConfiguredTarget {
    /** A set of this target's implicitDeps.  */
    private val implicitDeps: com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?

    private val providers: TransitiveInfoProviderMap
    private val configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?
    private val ruleClassId: RuleClassId

    private val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?

    private constructor(
        actionLookupKey: ActionLookupKey?,
        visibility: NestedSet<PackageGroupContents?>?,
        isCreatedInSymbolicMacro: Boolean,
        providers: TransitiveInfoProviderMap,
        configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?,
        implicitDeps: com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?,
        ruleClassId: RuleClassId,
        actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?
    ) : super(actionLookupKey, visibility) {
        // We don't use ImmutableMap.Builder here to allow augmenting the initial list of 'default'
        // providers by passing them in.
        val providerBuilder: TransitiveInfoProviderMapBuilder =
            TransitiveInfoProviderMapBuilder().addAll(providers)
        if (isCreatedInSymbolicMacro) {
            // Rather than add a boolean field to all RuleConfiguredTargets, we add a marker provider to
            // just the ones that are created in symbolic macros. (This tradeoff may make less sense if
            // many targets are created in macros.)
            providerBuilder.add(CreatedInSymbolicMacroMarker.INSTANCE)
        }
        checkState(providerBuilder.contains(RunfilesProvider::class.java), actionLookupKey)
        checkState(providerBuilder.contains(FileProvider::class.java), actionLookupKey)
        checkState(providerBuilder.contains(FilesToRunProvider::class.java), actionLookupKey)

        // Initialize every StarlarkApiProvider
        for (i in 0..<providers.getProviderCount()) {
            val obj: Any? = providers.getProviderInstanceAt(i)
            if (obj is StarlarkApiProvider) {
                obj.init(this)
            }
        }

        this.providers = providerBuilder.build()
        this.configConditions = configConditions
        this.implicitDeps = IMPLICIT_DEPS_INTERNER.intern(implicitDeps)
        this.ruleClassId = ruleClassId
        this.actions = actions
    }

    constructor(
        ruleContext: RuleContext,
        providers: TransitiveInfoProviderMap,
        actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?
    ) : this(
        ruleContext.getOwner(),
        ruleContext.getVisibility(),  /* isCreatedInSymbolicMacro= */
        ruleContext.getRule().isCreatedInSymbolicMacro(),
        providers,
        ruleContext.getConfigConditions(),
        com.google.devtools.build.lib.analysis.Util.findImplicitDeps(ruleContext),
        ruleContext.getRule().getRuleClassObject().getRuleClassId(),
        actions
    ) {
        // Make sure that all declared output files are also created as artifacts. The
        // CachingAnalysisEnvironment makes sure that they all have generating actions.
        if (!ruleContext.hasErrors()) {
            for (out in ruleContext.getRule().getOutputFiles()) {
                ruleContext.createOutputArtifact(out)
            }
        }
    }

    /** Use this constructor for creating incompatible ConfiguredTarget instances.  */
    constructor(
        actionLookupKey: ActionLookupKey?,
        visibility: NestedSet<PackageGroupContents?>?,
        isCreatedInSymbolicMacro: Boolean,
        providers: TransitiveInfoProviderMap,
        configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?,
        ruleClassId: RuleClassId
    ) : this(
        actionLookupKey,
        visibility,
        isCreatedInSymbolicMacro,
        providers,
        configConditions,
        com.google.common.collect.ImmutableList.of<ConfiguredTargetKey?>(),
        ruleClassId,
        com.google.common.collect.ImmutableList.of<ActionAnalysisMetadata?>()
    ) {
        com.google.common.base.Preconditions.checkState(
            providers.get(IncompatiblePlatformProvider.PROVIDER) != null,
            actionLookupKey
        )
    }

    @VisibleForSerialization
    @AutoCodec.Instantiator
    @Deprecated("for serialization only")
    internal constructor(
        lookupKey: ActionLookupKey?,
        visibility: NestedSet<PackageGroupContents?>?,
        providers: TransitiveInfoProviderMap,
        configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?,
        implicitDeps: com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?,
        ruleClassId: RuleClassId,
        actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?
    ) : super(lookupKey, visibility) {
        this.providers = providers
        this.configConditions = configConditions
        this.implicitDeps = implicitDeps
        this.ruleClassId = ruleClassId
        this.actions = actions
    }

    /**
     * Marker provider that indicates this target was instantiated within one or more symbolic macros.
     */
    private object CreatedInSymbolicMacroMarker : TransitiveInfoProvider {
        val INSTANCE: CreatedInSymbolicMacroMarker = CreatedInSymbolicMacroMarker()
    }

    public override fun isCreatedInSymbolicMacro(): Boolean {
        return getProvider<CreatedInSymbolicMacroMarker?>(CreatedInSymbolicMacroMarker::class.java) != null
    }

    /** The configuration conditions that trigger this rule's configurable attributes.  */
    public override fun getConfigConditions(): com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>? {
        return configConditions
    }

    public override fun isRuleConfiguredTarget(): Boolean {
        return true
    }

    fun getImplicitDeps(): com.google.common.collect.ImmutableList<ConfiguredTargetKey?>? {
        return implicitDeps
    }

    override fun getRuleClassString(): String {
        return ruleClassId.name()
    }

    fun getRuleClassId(): RuleClassId {
        return ruleClassId
    }

    override fun <P : TransitiveInfoProvider?> getProvider(providerClass: java.lang.Class<P?>): P? {
        // TODO(bazel-team): Should aspects be allowed to override providers on the configured target
        // class?
        AnalysisUtils.Companion.checkProvider<P?>(providerClass)
        val provider: P? = providers.getProvider(providerClass)
        if (provider != null) {
            return provider
        }
        if (providerClass.isAssignableFrom(getClass())) {
            return providerClass.cast(this)
        }
        return null
    }

    override fun getErrorMessageForUnknownField(name: String?): String? {
        return java.lang.String.format(
            "%s (rule '%s') doesn't have provider '%s'",
            Starlark.repr(this, StarlarkSemantics.DEFAULT), ruleClassId.name(), name
        )
    }

    protected override fun addExtraStarlarkKeys(result: java.util.function.Consumer<String?>) {
        for (i in 0..<providers.getProviderCount()) {
            val classAt: Any? = providers.getProviderKeyAt(i)
            if (classAt is String) {
                result.accept(classAt)
            }
        }
        result.accept(AbstractConfiguredTarget.Companion.ACTIONS_FIELD_NAME)
    }

    override fun rawGetStarlarkProvider(providerKey: Provider.Key?): Info {
        return providers.get(providerKey)
    }

    override fun rawGetStarlarkProvider(providerKey: String): Any? {
        if (providerKey == AbstractConfiguredTarget.Companion.ACTIONS_FIELD_NAME) {
            // Only expose actions which are legitimate Starlark values, otherwise they will later
            // cause a Bazel crash.
            // TODO(cparsons): Expose all actions to Starlark.
            return getActions().stream()
                .filter(java.util.function.Predicate { action: ActionAnalysisMetadata -> action is ActionApi })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<ActionAnalysisMetadata?>())
        }
        return providers.get(providerKey)
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
        printer.append("<target " + getLabel() + ">")
    }

    public override fun debugPrint(printer: net.starlark.java.eval.Printer, thread: StarlarkThread?) {
        // Show the names of the provider keys that this target propagates.
        // Provider key names might potentially be *private* information, and thus a comprehensive
        // list of provider keys should not be exposed in any way other than for debug information.
        printer.append("<target " + getLabel() + ", keys:[")
        val starlarkProviderKeyStrings: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        for (providerIndex in 0..<providers.getProviderCount()) {
            val providerKey: Any = providers.getProviderKeyAt(providerIndex)
            if (providerKey is Provider.Key) {
                starlarkProviderKeyStrings.add(providerKey.toString())
            }
        }
        printer.append(com.google.common.base.Joiner.on(", ").join(starlarkProviderKeyStrings.build()))
        printer.append("]>")
    }

    /** Returns a list of actions that this configured target generated.  */
    fun getActions(): com.google.common.collect.ImmutableList<ActionAnalysisMetadata> {
        return com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>>(
            actions,
            "actions are not available on deserialized instances"
        )
    }

    /**
     * Finds an artifact (known to be produced by this rule) by its corresponding output label, for
     * use when creating an [OutputFileConfiguredTarget].
     */
    fun findArtifactByOutputLabel(outputLabel: Label): Artifact {
        checkArgument(
            outputLabel.getPackageIdentifier().equals(getLabel().getPackageIdentifier()),
            "%s not in same package as %s",
            outputLabel,
            this
        )
        val relativeOutputPath: PathFragment? = outputLabel.toPathFragment()
        for (action in getActions()) {
            for (output in action.getOutputs()) {
                if (output.getExecPath().endsWith(relativeOutputPath)) {
                    return output
                }
            }
        }
        throw java.lang.IllegalArgumentException("No output matching " + outputLabel + " in " + this)
    }

    public override fun getProvidersDictForQuery(): Dict<String?, Any?>? {
        return toProvidersDictForQuery(providers)
    }

    /**
     * Returns the providers map. Should only be used for metrics, as it is missing [ ].
     */
    fun getProvidersForMetrics(): TransitiveInfoProviderMap {
        return providers
    }

    companion object {
        /**
         * An interner for the implicitDeps set. [Util.findImplicitDeps] is called upon every
         * construction of a RuleConfiguredTarget and we expect many of these targets to contain the same
         * set of implicit deps so this reduces the memory load per build.
         */
        private val IMPLICIT_DEPS_INTERNER: com.google.common.collect.Interner<com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?> =
            BlazeInterners.newWeakInterner<com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?>()
    }
}
