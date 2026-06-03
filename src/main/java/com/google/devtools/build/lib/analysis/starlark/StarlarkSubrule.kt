// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Artifact

/**
 * Represents a subrule which can be invoked in a Starlark rule's implementation function.
 * 
 * 
 * The basic mechanism used is that a rule class declared a dependency on a set of subrules. The
 * (implicit) attributes of the subrule are lifted to the rule class, and thus, behave as if they
 * were directly declared on the rule class itself. The rule class also holds a reference to the set
 * of subrules. The latter is only used for validating that a rule invoking a subrule declared that
 * subrule as a dependency.
 */
class StarlarkSubrule(
    implementation: StarlarkFunction?,
    attributes: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>,
    toolchains: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement>,
    fragments: com.google.common.collect.ImmutableSet<String?>,
    subrules: com.google.common.collect.ImmutableSet<StarlarkSubrule?>?
) : StarlarkExportable, StarlarkCallable, StarlarkSubruleApi {
    // TODO(hvd) this class is a WIP, will be implemented over many commits
    private val implementation: StarlarkFunction?
    private val toolchains: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement>
    private val fragments: com.google.common.collect.ImmutableSet<String?>
    private val subrules: com.google.common.collect.ImmutableSet<StarlarkSubrule?>?

    // following fields are set on export
    private var extensionLabel: Label? = null
    private var exportedName: String? = null
    private var attributes: com.google.common.collect.ImmutableList<SubruleAttribute>

    init {
        this.implementation = implementation
        this.attributes = SubruleAttribute.Companion.from(attributes)
        this.toolchains = toolchains
        this.fragments = fragments
        this.subrules = subrules
    }

    override fun getName(): String? {
        if (isExported()) {
            return exportedName
        } else {
            return "unexported subrule"
        }
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
        printer.append("<subrule ").append(getName()).append(">")
    }

    override fun equals(other: Any?): Boolean {
        if (other !is StarlarkSubrule) {
            return false
        }
        if (isExported()) {
            return this.extensionLabel.equals(other.extensionLabel)
                    && this.exportedName == other.exportedName
        }
        return this === other
    }

    override fun hashCode(): Int {
        if (isExported()) {
            return java.util.Objects.hash(this.extensionLabel, this.exportedName)
        }
        return java.lang.System.identityHashCode(this)
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    override fun call(thread: StarlarkThread, args: Tuple, kwargs: Dict<String?, Any?>): Any? {
        checkExported()
        val ruleContext: StarlarkRuleContext =
            BazelRuleAnalysisThreadContext.Companion.fromOrFail(thread, getName())
                .getRuleContext()
                .getStarlarkRuleContext()
        val callerSubruleContext: SubruleContext? = ruleContext.getLockedForSubrule()
        if (callerSubruleContext != null) {
            if (!callerSubruleContext.subrule!!.getDeclaredSubrules().contains(this)) {
                throw Starlark.errorf(
                    "subrule %s must declare %s in 'subrules'",
                    callerSubruleContext.subrule!!.getName(), getName()
                )
            }
        } else if (!ruleContext.getSubrules().contains(this)) {
            throw getUndeclaredSubruleError(ruleContext)
        }
        val runfilesFromDeps: com.google.common.collect.ImmutableSet.Builder<FilesToRunProvider?> =
            com.google.common.collect.ImmutableSet.builder<FilesToRunProvider?>()
        val namedArgs: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        namedArgs.putAll(kwargs)
        for (attr in attributes) {
            // TODO: b/293304174 - maybe permit overriding?
            if (kwargs.containsKey(attr.attrName)) {
                throw Starlark.errorf(
                    "got invalid named argument: '%s' is an implicit dependency and cannot be overridden",
                    attr.attrName
                )
            }
            val attribute: Attribute = getAttributeByName(ruleContext, attr.ruleAttrName)
            // We need to use the underlying RuleContext because the subrule attributes are hidden from
            // the rule ctx.attr
            val value: Any?
            if (attribute.isExecutable()) {
                val runfiles: FilesToRunProvider? =
                    ruleContext.getRuleContext().getExecutablePrerequisite(attribute.getName())
                runfilesFromDeps.add(runfiles)
                value = runfiles
            } else if (attribute.getType() === BuildType.LABEL_LIST) {
                value = ruleContext.getRuleContext().getPrerequisites(attribute.getName())
            } else if (attribute.getType() === BuildType.LABEL) {
                if (attribute.isSingleArtifact()) {
                    value = ruleContext.getRuleContext().getPrerequisiteArtifact(attribute.getName())
                } else {
                    value = ruleContext.getRuleContext().getPrerequisite(attribute.getName())
                }
            } else {
                // this should never happen, we've already validated the type while evaluating the subrule
                throw java.lang.IllegalStateException("unexpected attribute type")
            }
            namedArgs.put(attr.attrName, if (value == null) Starlark.NONE else value)
        }
        val subruleContext =
            SubruleContext(this, ruleContext, toolchains, runfilesFromDeps.build())
        val positionals: com.google.common.collect.ImmutableList<Any?> =
            com.google.common.collect.ImmutableList.builder<Any?>().add(subruleContext).addAll(args).build()
        try {
            ruleContext.setLockedForSubrule(subruleContext)
            return Starlark.call(
                thread, implementation, positionals, Dict.immutableCopyOf<String?, Any?>(namedArgs.buildOrThrow())
            )
        } finally {
            subruleContext.nullify()
            // callerSubruleContext may be null if this subrule was called from the rule itself, but in
            // that case null is exactly what we want to set here
            ruleContext.setLockedForSubrule(callerSubruleContext)
        }
    }

    private fun getDeclaredSubrules(): com.google.common.collect.ImmutableSet<StarlarkSubrule?>? {
        return subrules
    }

    private fun getUndeclaredSubruleError(starlarkRuleContext: StarlarkRuleContext): net.starlark.java.eval.EvalException? {
        if (starlarkRuleContext.isForAspect()) {
            return Starlark.errorf(
                "aspect '%s' must declare '%s' in 'subrules'",
                starlarkRuleContext.getRuleContext().getMainAspect().getAspectClass().getName(),
                this.getName()
            )
        } else {
            return Starlark.errorf(
                "rule '%s' must declare '%s' in 'subrules'",
                starlarkRuleContext.getRuleClassUnderEvaluation(), this.getName()
            )
        }
    }

    /**
     * Returns the collection of attributes to be lifted to a rule that uses this `subrule`.
     * 
     * @throws EvalException if this subrule is unexported
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun attributesForRule(): com.google.common.collect.ImmutableList<Pair<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>?> {
        checkExported()
        val builder: com.google.common.collect.ImmutableList.Builder<Pair<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>?> =
            com.google.common.collect.ImmutableList.builder<Pair<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>?>()
        for (attr in attributes) {
            builder.add(Pair.of(attr.ruleAttrName, attr.descriptor))
        }
        return builder.build()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun checkExported() {
        if (!isExported()) {
            throw Starlark.errorf("Invalid subrule hasn't been exported by a bzl file")
        }
    }

    public override fun isExported(): Boolean {
        return this.extensionLabel != null && this.exportedName != null
    }

    // TODO(bazel-team): use exportedLocation as the callable symbol's location.
    public override fun export(
        handler: com.google.devtools.build.lib.events.EventHandler,
        extensionLabel: Label,
        exportedName: String?,
        exportedLocation: net.starlark.java.syntax.Location?
    ) {
        com.google.common.base.Preconditions.checkState(!isExported())
        this.extensionLabel = extensionLabel
        this.exportedName = exportedName
        this.attributes =
            SubruleAttribute.Companion.transformOnExport(attributes, extensionLabel, exportedName, handler)
    }

    public override fun getUserDefinedNameIfSubruleAttr(ruleAttrName: String): java.util.Optional<String?> {
        for (subrule in getTransitiveSubrules(com.google.common.collect.ImmutableList.of<StarlarkSubrule?>(this))) {
            for (attr in subrule.attributes) {
                if (ruleAttrName == attr.ruleAttrName) {
                    return java.util.Optional.of<String?>(attr.attrName)
                }
            }
        }
        return java.util.Optional.empty<String?>()
    }

    /**
     * The context object passed to the implementation function of a subrule.
     * 
     * 
     * This class exists to reduce the API surface visible to subrules and avoid leaking deprecated
     * or legacy APIs. It wraps the underlying rule's [StarlarkRuleContext] and either simply
     * delegates the operation to the latter, or has very similar behavior to it. Cases where behavior
     * differs is documented on the respective methods.
     */
    @StarlarkBuiltin(
        name = "subrule_ctx",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "A context object passed to the implementation function of a subrule."
    )
    internal class SubruleContext private constructor(// these fields are effectively final, set to null once this instance is no longer usable by
        // Starlark
        private var subrule: StarlarkSubrule?,
        ruleContext: StarlarkRuleContext?,
        requestedToolchains: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement>,
        runfilesFromDeps: com.google.common.collect.ImmutableSet<FilesToRunProvider?>?
    ) : StarlarkActionContext {
        private var starlarkRuleContext: StarlarkRuleContext?
        private var requestedToolchains: com.google.common.collect.ImmutableSet<Label?>?
        private var runfilesFromDeps: com.google.common.collect.ImmutableSet<FilesToRunProvider?>?
        private var actions: StarlarkActionFactory?
        private var fragmentCollection: FragmentCollectionApi?

        init {
            this.starlarkRuleContext = ruleContext
            this.requestedToolchains =
                requestedToolchains.stream()
                    .map<Any?>(ToolchainTypeRequirement::toolchainType)
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
            this.runfilesFromDeps = runfilesFromDeps
            this.actions = StarlarkActionFactory(this)
            this.fragmentCollection = SubruleFragmentCollection(this)
        }

        @StarlarkMethod(name = "label", doc = "The label of the target currently being analyzed", structField = true)
        @Throws(net.starlark.java.eval.EvalException::class)
        fun getLabel(): Label? {
            checkMutable("label")
            // we use the underlying RuleContext to bypass the mutability check in
            // StarlarkRuleContext.getLabel() since it's locked
            return starlarkRuleContext.getRuleContext().getLabel()
        }

        // This is identical to the StarlarkActionFactory used by StarlarkRuleContext, and subrule
        // specific behaviour is triggered by the methods inherited from StarlarkActionContext
        @StarlarkMethod(
            name = "actions",
            doc = "Contains methods for declaring output files and the actions that produce them",
            structField = true
        )
        @Throws(net.starlark.java.eval.EvalException::class)
        fun actions(): StarlarkActionFactoryApi? {
            checkMutable("actions")
            return actions
        }

        @StarlarkMethod(
            name = "toolchains",
            doc = "Contains methods for declaring output files and the actions that produce them",
            structField = true
        )
        @Throws(net.starlark.java.eval.EvalException::class)
        fun toolchains(): ToolchainContextApi {
            checkMutable("toolchains")
            val ruleContext: RuleContext = starlarkRuleContext.getRuleContext()
            if (ruleContext.getToolchainContext() == null) {
                return StarlarkToolchainContext.TOOLCHAINS_NOT_VALID
            }
            if (ruleContext.useAutoExecGroups()) {
                return StarlarkToolchainContext.create( /* targetDescription= */
                    ruleContext.getToolchainContext().targetDescription(),  /* resolveToolchainDataFunc= */
                    { toolchainType: Label -> ruleContext.getToolchainInfo(toolchainType) },  /* resolvedToolchainTypeLabels= */
                    getAutomaticExecGroupLabels()
                )
            } else {
                throw Starlark.errorf(
                    "subrules using toolchains must enable automatic exec-groups. For more info, see"
                            + " https://bazel.build/extending/auto-exec-groups#migration-aegs"
                )
            }
        }

        private fun getAutomaticExecGroupLabels(): com.google.common.collect.ImmutableSet<Label?> {
            return starlarkRuleContext.getRequestedToolchainTypeLabelsFromAutoExecGroups().stream()
                .filter(java.util.function.Predicate { label: Label? -> requestedToolchains.contains(label) })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Label?>())
        }

        @StarlarkMethod(
            name = "fragments",
            doc = "Allows access to configuration fragments in target configuration.",
            structField = true
        )
        @Throws(net.starlark.java.eval.EvalException::class)
        fun getFragmentCollection(): FragmentCollectionApi? {
            checkMutable("fragments")
            return fragmentCollection
        }

        override fun newFileRoot(): ArtifactRoot? {
            return starlarkRuleContext.getRuleContext().getBinDirectory()
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun checkMutable(attrName: String?) {
            if (isImmutable()) {
                throw Starlark.errorf(
                    "cannot access field or method '%s' of subrule context outside of its own"
                            + " implementation function",
                    attrName
                )
            }
        }

        override fun isImmutable(): Boolean {
            return starlarkRuleContext == null || starlarkRuleContext.getLockedForSubrule() !== this
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun getExecutableRunfiles(executable: Artifact, what: String?): FilesToRunProvider? {
            if (runfilesFromDeps.stream()
                    .anyMatch(java.util.function.Predicate { dep: FilesToRunProvider? -> executable.equals(dep.getExecutable()) })
            ) {
                // TODO: b/293304174 - maybe return the matched FilesToRunProvider instead of failing?
                throw Starlark.errorf("for '%s', expected FilesToRunProvider, got File", what)
            } else {
                // executable attributes of a subrule are passed to the implementation as FilesToRunProvider
                // so this should never happen unless this comes from somewhere else, in which case, we
                // can't resolve it anyway
                return null
            }
        }

        override fun areRunfilesFromDeps(executable: FilesToRunProvider?): Boolean {
            return runfilesFromDeps.contains(executable)
        }

        override fun getRuleContext(): RuleContext {
            return starlarkRuleContext.getRuleContext()
        }

        override fun getStarlarkSemantics(): StarlarkSemantics? {
            return starlarkRuleContext.getStarlarkSemantics()
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun maybeOverrideExecGroup(execGroupUnchecked: Any?): Any? {
            if (execGroupUnchecked !== Starlark.NONE) {
                throw Starlark.errorf("'exec_group' may not be specified in subrules")
            }
            // TODO: b/293304174 - return the correct exec group
            return execGroupUnchecked
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun maybeOverrideToolchain(toolchainUnchecked: Any?): Any? {
            if (toolchainUnchecked !== Starlark.UNBOUND) {
                throw Starlark.errorf("'toolchain' may not be specified in subrules")
            }
            return if (requestedToolchains.isEmpty())
                Starlark.NONE
            else
                com.google.common.collect.Iterables.getOnlyElement<Label?>(requestedToolchains)
        }

        // TODO: b/293304174 - maybe simplify all this by just relying on starlarkRuleContext
        private fun nullify() {
            this.subrule = null
            this.starlarkRuleContext = null
            this.actions = null
            this.requestedToolchains = null
            this.runfilesFromDeps = null
            this.fragmentCollection = null
        }

        override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
            printer.append(
                ("<"
                        + subrule!!.getName()
                        + " context for "
                        + starlarkRuleContext.getRuleContext().getLabel()
                        + ">")
            )
        }
    }

    private class SubruleAttribute(
        private val attrName: String,
        descriptor: com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor,
        ruleAttrName: String?
    ) {
        private val descriptor: com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor

        /**
         * This is the attribute name when lifted to a rule, see [.copyWithRuleAttributeName] and
         * is set only after the subrule is exported
         */
        private val ruleAttrName: String?

        init {
            this.descriptor = descriptor
            this.ruleAttrName = ruleAttrName
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun copyWithRuleAttributeName(label: Label, exportedName: String?): SubruleAttribute {
            val ruleAttrName =
                getRuleAttrName(label, exportedName, attrName, descriptor.getValueSource())
            return SubruleAttribute(attrName, descriptor, ruleAttrName)
        }

        companion object {
            private fun from(
                attributes: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>
            ): com.google.common.collect.ImmutableList<SubruleAttribute> {
                return attributes.entrySet().stream()
                    .map<SubruleAttribute?>(java.util.function.Function { e: MutableMap.MutableEntry<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>? ->
                        SubruleAttribute(
                            e.getKey(),
                            e.getValue(),
                            null
                        )
                    })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<SubruleAttribute?>())
            }

            private fun transformOnExport(
                attributes: com.google.common.collect.ImmutableList<SubruleAttribute>,
                label: Label,
                exportedName: String?,
                handler: com.google.devtools.build.lib.events.EventHandler
            ): com.google.common.collect.ImmutableList<SubruleAttribute> {
                val builder: com.google.common.collect.ImmutableList.Builder<SubruleAttribute?> =
                    com.google.common.collect.ImmutableList.builder<SubruleAttribute?>()
                for (attribute in attributes) {
                    try {
                        builder.add(attribute.copyWithRuleAttributeName(label, exportedName))
                    } catch (e: net.starlark.java.eval.EvalException) {
                        handler.handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                    }
                }
                return builder.build()
            }
        }
    }

    private class SubruleFragmentCollection(private val subruleContext: SubruleContext) : FragmentCollectionApi {
        @Throws(net.starlark.java.eval.EvalException::class)
        public override fun getValue(name: String?): Any? {
            val fragmentClass: java.lang.Class<out com.google.devtools.build.lib.analysis.config.Fragment?>? =
                subruleContext.getRuleContext().getConfiguration().getStarlarkFragmentByName(name)
            if (fragmentClass == null) {
                return null
            }
            if (!subruleContext.subrule!!.fragments.contains(name)) {
                throw Starlark.errorf(
                    ("%s has to declare '%s' as a required fragment in order to access it."
                            + " Please update the 'fragments' argument of the subrule definition "
                            + "(for example: fragments = [\"%s\"])"),
                    subruleContext.subrule!!.getName(), name, name
                )
            }
            return subruleContext.getRuleContext().getConfiguration().getFragment(fragmentClass)
        }

        public override fun getFieldNames(): com.google.common.collect.ImmutableCollection<String?> {
            return subruleContext.subrule!!.fragments
        }

        override fun toString(): String {
            return "[ " + fieldsToString() + "]"
        }
    }

    companion object {
        private fun getAttributeByName(ruleContext: StarlarkRuleContext, attr: String?): Attribute {
            if (ruleContext.isForAspect()) {
                return ruleContext.getRuleContext().getMainAspect().getDefinition().getAttributes().get(attr)
            } else {
                return ruleContext
                    .getRuleContext()
                    .getRule()
                    .getRuleClassObject()
                    .getAttributeProvider()
                    .getAttributeByName(attr)
            }
        }

        /**
         * Returns all attributes to be lifted from the given subrules to a rule/aspect
         * 
         * 
         * Attributes are discovered transitively (if a subrule depends on another subrule) and those
         * from common, transitive dependencies are de-duped.
         * 
         * @throws EvalException if any of the given subrules are unexported
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun discoverAttributes(
            subrules: com.google.common.collect.ImmutableList<out StarlarkSubruleApi?>
        ): com.google.common.collect.ImmutableList<Pair<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>?> {
            val attributes: com.google.common.collect.ImmutableList.Builder<Pair<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>?> =
                com.google.common.collect.ImmutableList.builder<Pair<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>?>()
            for (subrule in getTransitiveSubrules(subrules)) {
                attributes.addAll(subrule.attributesForRule())
            }
            return attributes.build()
        }

        /** Returns all toolchain types to be lifted from the given subrules to a rule/aspect  */
        fun discoverToolchains(
            subrules: com.google.common.collect.ImmutableList<out StarlarkSubruleApi?>
        ): com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?> {
            val toolchains: com.google.common.collect.ImmutableSet.Builder<ToolchainTypeRequirement?> =
                com.google.common.collect.ImmutableSet.builder<ToolchainTypeRequirement?>()
            for (subrule in getTransitiveSubrules(subrules)) {
                toolchains.addAll(subrule.toolchains)
            }
            return toolchains.build()
        }

        private fun getTransitiveSubrules(
            subrules: com.google.common.collect.ImmutableCollection<out StarlarkSubruleApi?>
        ): com.google.common.collect.ImmutableSet<StarlarkSubrule> {
            val uniqueSubrules: com.google.common.collect.ImmutableSet.Builder<StarlarkSubrule?> =
                com.google.common.collect.ImmutableSet.builder<StarlarkSubrule?>()
            for (subruleApi in subrules) {
                if (subruleApi is StarlarkSubrule) {
                    uniqueSubrules.add(subruleApi).addAll(getTransitiveSubrules(subruleApi.getDeclaredSubrules()))
                }
            }
            return uniqueSubrules.build()
        }

        @com.google.common.annotations.VisibleForTesting // _foo -> $//pkg:label%my_subrule%_foo
        @Throws(net.starlark.java.eval.EvalException::class)
        fun getRuleAttrName(
            label: Label, exportedName: String?, attrName: String?, valueSource: AttributeValueSource
        ): String {
            return valueSource.convertToNativeName(
                "_" + label.getCanonicalForm() + "%" + exportedName + "%" + attrName
            )
        }
    }
}
