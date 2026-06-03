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

import com.google.devtools.build.lib.packages.Attribute.attr

/** A helper class to provide an easier API for Starlark rule definitions.  */
class StarlarkRuleClassFunctions : StarlarkRuleFunctionsApi {
    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun provider(doc: Any?, fields: Any?, init: Any?, thread: StarlarkThread): Any? {
        val builder: StarlarkProvider.Builder = StarlarkProvider.builder(thread.getCallerLocation())
        Starlark.toJavaOptional<String?>(doc, String::class.java)
            .map<String?>(java.util.function.Function { docString: String? -> Starlark.trimDocString(docString) })
            .ifPresent(builder::setDocumentation)
        if (fields is net.starlark.java.eval.Sequence) {
            builder.setSchema(net.starlark.java.eval.Sequence.cast<T?>(fields, String::class.java, "fields"))
        } else if (fields is Dict) {
            builder.setSchema(
                com.google.common.collect.Maps.< K, V1, V2 > transformValues<K?, V1?, V2?>(
                    Dict.cast<K?, V?>(fields, String::class.java, String::class.java, "fields"),
                    com.google.common.base.Function { docString: V1? -> Starlark.trimDocString(docString) })
            )
        }
        if (init === Starlark.NONE) {
            return builder.buildWithIdentityToken(thread.getNextIdentityToken())
        }
        if (init is StarlarkCallable) {
            builder.setInit(init)
        } else {
            throw Starlark.errorf("got %s for init, want callable value", Starlark.type(init))
        }
        val provider: StarlarkProvider = builder.buildWithIdentityToken(thread.getNextIdentityToken())
        return Tuple.of(provider, provider.createRawConstructor())
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun macro(
        implementation: StarlarkFunction,
        attrs: Dict<*, *>,
        inheritAttrs: Any,
        finalizer: Boolean,
        doc: Any?,
        thread: StarlarkThread
    ): MacroFunctionApi? {
        // Ordinarily we would use StarlarkMethod#enableOnlyWithFlag, but this doesn't work for
        // top-level symbols (due to StarlarkGlobalsImpl relying on the Starlark#addMethods overload
        // that uses default StarlarkSemantics), so enforce it here instead.
        if (!thread
                .getSemantics()
                .getBool(BuildLanguageOptions.EXPERIMENTAL_ENABLE_FIRST_CLASS_MACROS)
        ) {
            throw Starlark.errorf("Use of `macro()` requires --experimental_enable_first_class_macros")
        }
        // Ensure we're initializing a .bzl file.
        BzlInitThreadContext.fromOrFail(thread, "macro()")

        val builder: MacroClass.Builder = Builder(implementation)
        for (uncheckedEntry in attrs.entrySet()) {
            val attrName: String?
            val descriptor: com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?
            try {
                // Dict.cast() does not support none-able values - so we type-check manually, and translate
                // Starlark None to Java null.
                attrName = uncheckedEntry.getKey() as String?
                checkAttributeName(attrName)
                descriptor =
                    if (uncheckedEntry.getValue() !== Starlark.NONE)
                        uncheckedEntry.getValue() as com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?
                    else
                        null
            } catch (e: java.lang.ClassCastException) {
                throw Starlark.errorf(
                    "got dict<%s, %s> for 'attrs', want dict<string, Attribute|None>",
                    Starlark.type(uncheckedEntry.getKey()), Starlark.type(uncheckedEntry.getValue())
                )
            }

            // "name" and "visibility" attributes are added automatically by the builder.
            if (MacroClass.RESERVED_MACRO_ATTR_NAMES.contains(attrName)) {
                throw Starlark.errorf("Cannot declare a macro attribute named '%s'", attrName)
            }

            if (descriptor == null) {
                // a None descriptor should ignored.
                continue
            }

            if (!descriptor.getValueSource().equals(AttributeValueSource.DIRECT)) {
                // Note that inherited native attributes may have a computed default, e.g. testonly.
                throw Starlark.errorf(
                    "In macro attribute '%s': Macros do not support computed defaults or late-bound"
                            + " defaults",
                    attrName
                )
            }

            val attr: Attribute = descriptor.build(attrName)
            builder.addAttribute(attr)
        }
        for (attr in getAttrsOf(inheritAttrs)) {
            var attr: Attribute = attr
            val attrName: String? = attr.getName()
            if (attr.isPublic() // isDocumented() is false only for generator_* magic attrs (for which isPublic() is true)
                && attr.isDocumented()
                && !MacroClass.RESERVED_MACRO_ATTR_NAMES.contains(attrName) && !attrs.containsKey(attrName)
            ) {
                // Force the default value of optional inherited attributes to None.
                if (!attr.isMandatory() && attr.getDefaultValueUnchecked() != null && attr.getDefaultValueUnchecked() !== Starlark.NONE) {
                    attr = attr.cloneBuilder().defaultValueNone().build()
                }
                builder.addAttribute(attr)
            }
        }
        if (inheritAttrs !== Starlark.NONE && !implementation.hasKwargs()) {
            throw Starlark.errorf(
                "If inherit_attrs is set, implementation function must have a **kwargs parameter"
            )
        }

        if (finalizer) {
            builder.setIsFinalizer()
        }

        return MacroFunction(
            builder,
            Starlark.toJavaOptional<String?>(doc, String::class.java)
                .map<String?>(java.util.function.Function { docString: String? -> Starlark.trimDocString(docString) }),
            getBzlKeyToken(thread, "Macros")
        )
    }

    // TODO(bazel-team): implement attribute copy and other rule properties
    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun rule(
        implementation: StarlarkFunction?,
        testUnchecked: Any?,
        attrs: Dict<*, *>?,
        implicitOutputs: Any?,
        executableUnchecked: Any?,
        outputToGenfiles: Boolean,
        fragments: net.starlark.java.eval.Sequence<*>?,
        hostFragments: net.starlark.java.eval.Sequence<*>,
        starlarkTestable: Boolean,
        toolchains: net.starlark.java.eval.Sequence<*>,
        doc: Any?,
        providesArg: net.starlark.java.eval.Sequence<*>?,
        dependencyResolutionRule: Boolean,
        execCompatibleWith: net.starlark.java.eval.Sequence<*>,
        analysisTest: Boolean,
        buildSetting: Any,
        cfg: Any,
        execGroups: Any?,
        initializer: Any?,
        parentUnchecked: Any?,
        extendableUnchecked: Any?,
        subrules: net.starlark.java.eval.Sequence<*>,
        thread: StarlarkThread
    ): StarlarkRuleFunction {
        // Ensure we're initializing a .bzl file, which also means we have a RuleDefinitionEnvironment.
        val bazelContext: BzlInitThreadContext = BzlInitThreadContext.fromOrFail(thread, "rule()")

        val parent: RuleClass?
        val executable: Boolean
        val test: Boolean

        if (parentUnchecked === Starlark.NONE) {
            parent = null
            executable = (if (executableUnchecked === Starlark.UNBOUND) false else executableUnchecked as Boolean?)!!
            test = (if (testUnchecked === Starlark.UNBOUND) false else testUnchecked as Boolean?)!!
        } else {
            failIf(
                parentUnchecked !is StarlarkRuleFunction,
                "Parent needs to be a Starlark rule, was %s",
                Starlark.type(parentUnchecked)
            )
            // Assuming parent is already exported.
            failIf(
                (parentUnchecked as StarlarkRuleFunction).ruleClass == null,
                "Please export the parent rule before extending it."
            )

            parent = parentUnchecked.ruleClass
            executable = parent.isExecutableStarlark()
            test = parent.getRuleClassType() === RuleClassType.TEST

            failIf(
                !parent.isExtendable(),
                ("The rule '%s' is not extendable. Only Starlark rules not using deprecated features (like"
                        + " implicit outputs, output to genfiles) may be extended. Special rules like"
                        + " analysis tests or rules using build_settings cannot be extended."),
                parent.getName()
            )

            failIf(
                executableUnchecked !== Starlark.UNBOUND,
                "Omit executable parameter when extending rules."
            )
            failIf(testUnchecked !== Starlark.UNBOUND, "Omit test parameter when extending rules.")
            failIf(
                implicitOutputs !== Starlark.NONE,
                "implicit_outputs is not supported when extending rules (deprecated)."
            )
            failIf(
                !hostFragments.isEmpty(),
                "host_fragments are not supported when extending rules (deprecated)."
            )
            failIf(
                outputToGenfiles,
                "output_to_genfiles are not supported when extending rules (deprecated)."
            )
            failIf(starlarkTestable, "_skylark_testable is not supported when extending rules.")
            failIf(analysisTest, "analysis_test is not supported when extending rules.")
            failIf(buildSetting !== Starlark.NONE, "build_setting is not supported when extending rules.")
        }

        val labelConverter: LabelConverter = LabelConverter.forBzlEvaluatingThread(thread)

        return createRule( // Contextual parameters.
            bazelContext,
            thread,
            bazelContext.getBzlFile(),
            bazelContext.getTransitiveDigest(),
            labelConverter,  // rule() parameters
            parent,
            extendableUnchecked,
            implementation,
            if (initializer === Starlark.NONE) null else initializer as StarlarkFunction?,
            test,
            attrs,
            implicitOutputs,
            executable,
            outputToGenfiles,
            fragments,
            starlarkTestable,
            toolchains,
            doc,
            providesArg,
            dependencyResolutionRule,  /* isMaterializerRule= */
            false,  /* allowMaterializerRuleRealDeps= */
            false,
            execCompatibleWith,
            analysisTest,
            buildSetting,
            cfg,
            execGroups,
            subrules
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun materializerRule(
        implementation: StarlarkFunction?,
        attrs: Dict<*, *>?,
        doc: Any?,
        allowRealDeps: Boolean,
        thread: StarlarkThread
    ): StarlarkRuleFunction {
        // Ensure we're initializing a .bzl file, which also means we have a RuleDefinitionEnvironment.

        val bazelContext: BzlInitThreadContext = BzlInitThreadContext.fromOrFail(thread, "rule()")

        val labelConverter: LabelConverter = LabelConverter.forBzlEvaluatingThread(thread)

        return createRule( // Contextual parameters.
            bazelContext,
            thread,
            bazelContext.getBzlFile(),
            bazelContext.getTransitiveDigest(),
            labelConverter,  // rule() parameters
            /* parent= */
            null,  /* extendableUnchecked= */
            null,
            implementation,  /* initializer= */
            null,  /* test= */
            false,
            attrs,  /* implicitOutputs= */
            Starlark.NONE,  /* executable= */
            false,  /* outputToGenfiles= */
            false,  /* fragments= */
            StarlarkList.empty<Any?>(),  /* starlarkTestable= */
            false,  /* toolchains= */
            StarlarkList.empty<Any?>(),
            doc,  /* providesArg= */
            StarlarkList.empty<Any?>(),  /* dependencyResolutionRule= */
            false,  /* isMaterializerRule= */
            true,  /* allowMaterializerRuleRealDeps= */
            allowRealDeps,  /* execCompatibleWith= */
            StarlarkList.empty<Any?>(),  /* analysisTest= */
            false,  /* buildSetting= */
            Starlark.NONE,  /* cfg= */
            Starlark.NONE,  /* execGroups= */
            Starlark.NONE,  /* subrulesUnchecked= */
            StarlarkList.empty<Any?>()
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun aspect(
        implementation: StarlarkFunction?,
        attributeAspects: Any?,
        rawToolchainsAspects: Any?,
        attrs: Dict<*, *>?,
        requiredProvidersArg: net.starlark.java.eval.Sequence<*>,
        requiredAspectProvidersArg: net.starlark.java.eval.Sequence<*>,
        providesArg: net.starlark.java.eval.Sequence<*>?,
        requiredAspects: net.starlark.java.eval.Sequence<*>?,
        rawPropagationPredicate: Any?,
        fragments: net.starlark.java.eval.Sequence<*>?,
        hostFragments: net.starlark.java.eval.Sequence<*>?,
        toolchains: net.starlark.java.eval.Sequence<*>,
        doc: Any?,
        applyToGeneratingRules: Boolean,
        rawExecCompatibleWith: net.starlark.java.eval.Sequence<*>,
        rawExecGroups: Any?,
        subrulesUnchecked: net.starlark.java.eval.Sequence<*>,
        thread: StarlarkThread
    ): StarlarkAspect? {
        // Ensure we're initializing a .bzl file.
        BzlInitThreadContext.fromOrFail(thread, "aspect()")
        val labelConverter: LabelConverter = LabelConverter.forBzlEvaluatingThread(thread)

        var descriptors: com.google.common.collect.ImmutableList<Pair<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>> =
            attrObjectToAttributesList(attrs)

        if (!subrulesUnchecked.isEmpty()) {
            if (!thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_RULE_EXTENSION_API)) {
                BuiltinRestriction.failIfCalledOutsideAllowlist(thread, ALLOWLIST_RULE_EXTENSION_API)
            }
        }
        val subrules: com.google.common.collect.ImmutableList<StarlarkSubrule?> =
            net.starlark.java.eval.Sequence.cast<StarlarkSubrule?>(
                subrulesUnchecked,
                StarlarkSubrule::class.java,
                "subrules"
            ).getImmutableList()
        val subruleAttributes: com.google.common.collect.ImmutableList<Pair<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>> =
            StarlarkSubrule.Companion.discoverAttributes(subrules)
        if (!subruleAttributes.isEmpty()) {
            descriptors =
                com.google.common.collect.ImmutableList.builder<Pair<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>>()
                    .addAll(descriptors)
                    .addAll(subruleAttributes)
                    .build()
        }

        val attributes: com.google.common.collect.ImmutableList.Builder<Attribute?> =
            com.google.common.collect.ImmutableList.builder<Attribute?>()
        val requiredParams: com.google.common.collect.ImmutableSet.Builder<String?> =
            com.google.common.collect.ImmutableSet.builder<String?>()
        for (nameDescriptorPair in descriptors) {
            val nativeName: String = nameDescriptorPair.first
            var hasDefault: Boolean = nameDescriptorPair.second.hasDefault()
            val attribute: Attribute = nameDescriptorPair.second.build(nameDescriptorPair.first)

            // "configurable" may only be user-set for symbolic macros, not aspects.
            if (attribute.configurableAttrWasUserSet()) {
                throw Starlark.errorf(
                    "attribute '%s' has the 'configurable' argument set, which is not allowed in aspect"
                            + " definitions",
                    nativeName
                )
            }

            if (attribute.isMaterializing()) {
                throw Starlark.errorf(
                    "attribute '%s' has a materializer, which is not allowed on aspects", nativeName
                )
            }

            if (attribute.getType() === BuildType.DORMANT_LABEL
                || attribute.getType() === BuildType.DORMANT_LABEL_LIST
            ) {
                throw Starlark.errorf(
                    "attribute '%s' has a dormant label type, which is not allowed on aspects",
                    attribute.getPublicName()
                )
            }

            if (!Attribute.isImplicit(nativeName) && !Attribute.isAnalysisDependent(nativeName)) {
                if (attribute.getType() === Type.STRING) {
                    // isValueSet() is always true for attr.string as default value is "" by default.
                    hasDefault = attribute.getDefaultValue(null) != ""
                } else if (attribute.getType() === Type.INTEGER) {
                    // isValueSet() is always true for attr.int as default value is 0 by default.
                    hasDefault = attribute.getDefaultValue(null) != StarlarkInt.of(0)
                } else if (attribute.getType() === Type.BOOLEAN) {
                    hasDefault = attribute.getDefaultValue(null) != false
                } else {
                    throw Starlark.errorf(
                        "Aspect parameter attribute '%s' must have type 'bool', 'int' or 'string'.",
                        nativeName
                    )
                }

                if (hasDefault && attribute.checkAllowedValues()) {
                    val allowed: PredicateWithMessage<Any?> = attribute.getAllowedValues()
                    val defaultVal: Any? = attribute.getDefaultValue(null)
                    if (!allowed.apply(defaultVal)) {
                        throw Starlark.errorf(
                            "Aspect parameter attribute '%s' has a bad default value: %s",
                            nativeName, allowed.getErrorReason(defaultVal)
                        )
                    }
                }
                if (!hasDefault || attribute.isMandatory()) {
                    requiredParams.add(nativeName)
                }
            } else if (!hasDefault) { // Implicit or late bound attribute
                val starlarkName = "_" + nativeName.substring(1)
                if (attribute.isLateBound()
                    && attribute.getLateBoundDefault() !is StarlarkLateBoundDefault
                ) {
                    // Code elsewhere assumes that a late-bound attribute of a Starlark-defined aspects can
                    // exist in Java-land only as a StarlarkLateBoundDefault.
                    throw Starlark.errorf(
                        ("Starlark aspect attribute '%s' is late-bound but somehow is not defined in Starlark."
                                + " This violates an invariant inside of Bazel. Please file a bug with"
                                + " instructions for reproducing this. Thanks!"),
                        starlarkName
                    )
                }
                throw Starlark.errorf("Aspect attribute '%s' has no default value.", starlarkName)
            }
            if (attribute.getDefaultValueUnchecked() is StarlarkComputedDefaultTemplate) {
                // Attributes specifying dependencies using computed value are currently not supported.
                // The limitation is in place because:
                //  - blaze query requires that all possible values are knowable without BuildConguration
                //  - aspects can attach to any rule
                // Current logic in StarlarkComputedDefault is not enough,
                // however {Conservative,Precise}AspectResolver can probably be improved to make that work.
                val starlarkName = "_" + nativeName.substring(1)
                throw Starlark.errorf(
                    "Aspect attribute '%s' (%s) with computed default value is unsupported.",
                    starlarkName, attribute.getType()
                )
            }
            attributes.add(attribute)
        }

        if (applyToGeneratingRules && !requiredProvidersArg.isEmpty()) {
            throw Starlark.errorf(
                "An aspect cannot simultaneously have required providers and apply to generating rules."
            )
        }

        var propagationPredicate: AspectPropagationPredicate? = null
        if (!Starlark.isNullOrNone(rawPropagationPredicate)) {
            if (rawPropagationPredicate !is StarlarkFunction) {
                throw Starlark.errorf(
                    "Expected a function in 'propagation_predicate' parameter, got '%s'.",
                    Starlark.type(propagationPredicate)
                )
            }

            propagationPredicate =
                AspectPropagationPredicate(rawPropagationPredicate, thread.getSemantics())
        }

        if (applyToGeneratingRules && propagationPredicate != null) {
            throw Starlark.errorf(
                "An aspect cannot simultaneously have a propagation predicate and apply to generating"
                        + " rules."
            )
        }

        val execCompatibleWith: com.google.common.collect.ImmutableSet<Label?> =
            parseLabels(rawExecCompatibleWith, labelConverter, "exec_compatible_with")

        var execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?> =
            com.google.common.collect.ImmutableMap.of<String?, DeclaredExecGroup?>()
        if (rawExecGroups !== Starlark.NONE) {
            execGroups =
                com.google.common.collect.ImmutableMap.copyOf<String?, DeclaredExecGroup?>(
                    Dict.cast<String?, DeclaredExecGroup?>(
                        rawExecGroups,
                        String::class.java,
                        DeclaredExecGroup::class.java,
                        "exec_group"
                    )
                )
            for (group in execGroups.keySet()) {
                // TODO(b/151742236): document this in the param documentation.
                if (!StarlarkExecGroupCollection.isValidGroupName(group)) {
                    throw Starlark.errorf("Exec group name '%s' is not a valid name.", group)
                }
            }
        }

        val toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?> =
            com.google.common.collect.ImmutableSet.builder<ToolchainTypeRequirement?>()
                .addAll(parseToolchainTypes(toolchains, labelConverter))
                .addAll(StarlarkSubrule.Companion.discoverToolchains(subrules))
                .build()

        return StarlarkDefinedAspect(
            implementation,
            Starlark.toJavaOptional<String?>(doc, String::class.java)
                .map<U?>(java.util.function.Function { docString: String? -> Starlark.trimDocString(docString) }),
            AspectPropagationEdgesSupplier.createForAttrAspects(attributeAspects, thread),
            AspectPropagationEdgesSupplier.createForToolchainsAspects(
                rawToolchainsAspects, thread, labelConverter
            ),
            attributes.build(),
            StarlarkAttrModule.Companion.buildProviderPredicate(requiredProvidersArg, "required_providers"),
            StarlarkAttrModule.Companion.buildProviderPredicate(
                requiredAspectProvidersArg, "required_aspect_providers"
            ),
            StarlarkAttrModule.Companion.getStarlarkProviderIdentifiers(providesArg, "provides"),
            requiredParams.build(),
            com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (net.starlark.java.eval.Sequence.cast<StarlarkAspect?>(
                requiredAspects,
                StarlarkAspect::class.java,
                "requires"
            )),
            propagationPredicate,
            com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (net.starlark.java.eval.Sequence.cast<String?>(
                fragments,
                String::class.java,
                "fragments"
            )),
            toolchainTypes,
            applyToGeneratingRules,
            execCompatibleWith,
            execGroups,
            com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (subrules),
            getBzlKeyToken(thread, "Aspects")
        )
    }

    /**
     * A callable Starlark object representing a symbolic macro, which may be invoked during package
     * construction time to instantiate the macro.
     * 
     * 
     * Instantiating the macro does not necessarily imply that the macro's implementation function
     * will run synchronously with the call to this object. Just like a rule, a macro's implementation
     * function is evaluated in its own context separate from the caller.
     * 
     * 
     * This object is not usable until it has been [exported][.export]. Calling an unexported
     * macro function results in an [EvalException].
     */
    // Ideally, we'd want to merge this with {@link MacroFunctionApi}, but that would cause a circular
    // dependency between packages and starlarkbuildapi.
    class MacroFunction(
        builder: MacroClass.Builder?,
        documentation: java.util.Optional<String?>,
        identityToken: net.starlark.java.eval.SymbolGenerator.Symbol<BzlLoadValue.Key?>
    ) : StarlarkExportable, MacroFunctionApi {
        // Initially non-null, then null once exported.
        private var builder: MacroClass.Builder?

        // Initially null, then non-null once exported.
        private var macroClass: MacroClass? = null

        // Initially null, then non-null once exported.
        private var exportedLocation: net.starlark.java.syntax.Location? = null

        /** A token used for equality that may be mutated by [.export].  */
        private var identityToken: net.starlark.java.eval.SymbolGenerator.Symbol<BzlLoadValue.Key?>

        private val documentation: String?

        init {
            this.builder = builder
            this.documentation = documentation.orElse(null)
            this.identityToken = identityToken
        }

        public override fun getName(): String? {
            return if (macroClass != null) macroClass.getName() else "unexported macro"
        }

        public override fun getLocation(): net.starlark.java.syntax.Location {
            return if (exportedLocation != null) exportedLocation else net.starlark.java.syntax.Location.BUILTIN
        }

        /**
         * Returns the value of the doc parameter passed to `macro()` in Starlark, or an empty
         * Optional if a doc string was not provided.
         */
        fun getDocumentation(): java.util.Optional<String?> {
            return java.util.Optional.ofNullable<String?>(documentation)
        }

        /**
         * Returns the label of the .bzl module where macro() was called, or null if the rule has not
         * been exported yet.
         */
        fun getExtensionLabel(): Label? {
            if (identityToken.isGlobal()) {
                return identityToken.getOwner().getLabel()
            }
            return null
        }

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        public override fun call(thread: StarlarkThread, args: Tuple, kwargs: Dict<String?, Any?>?): Any? {
            val targetDefinitionContext: TargetDefinitionContext =
                TargetDefinitionContext.fromOrFail(thread, "a symbolic macro", "instantiated")

            if (macroClass == null) {
                throw Starlark.errorf(
                    "Cannot instantiate a macro that has not been exported (assign it to a global variable"
                            + " in the .bzl where it's defined)"
                )
            }

            if (macroClass.isFinalizer() && targetDefinitionContext.currentlyInNonFinalizerMacro()) {
                throw Starlark.errorf(
                    ("Cannot instantiate a rule finalizer within a non-finalizer symbolic macro. Rule"
                            + " finalizers may only be instantiated while evaluating a BUILD file, a legacy"
                            + " macro called from a BUILD file, or another rule finalizer.")
                )
            }

            if (!args.isEmpty()) {
                throw Starlark.errorf("unexpected positional arguments")
            }

            val macroInstance: MacroInstance? =
                macroClass.instantiateAndAddMacro(targetDefinitionContext, kwargs, thread.getCallStack())

            // Evaluate the macro now, if it's not a finalizer. Finalizer evaluation will be deferred to
            // the end of the BUILD file evaluation.
            //
            // Non-finalizers must be evaluated synchronously with the call to instantiate the macro,
            // because their side-effects must be visible to native.existing_rules() calls in legacy
            // macros.
            //
            // TODO: #19922 - Once compatibility with native.existing_rules() in legacy macros is no
            // longer a concern, we can make all symbolic macros use deferred evaluation rather than
            // expanding them here. And when we have lazy evaluation, they won't even be expanded at the
            // end of BUILD file evaluation, but rather at the end of package evaluation (which at that
            // time would be a distinct skyfunction).
            if (targetDefinitionContext.eagerlyExpandMacros() && !macroClass.isFinalizer()) {
                // TODO: #19922 - At some point we should maybe impose a check that the macro stack depth
                // isn't too big. Maybe this is unnecessary since we don't permit recursion. But in theory,
                // a big stack can crash under eager evaluation (where evaluation is on the Java call stack)
                // but not deferred evaluation, leading to a semantic difference.
                targetDefinitionContext.updatePausedThreadComputationSteps(thread).use { updater ->
                    MacroClass.executeMacroImplementation(
                        macroInstance, targetDefinitionContext, thread.getSemantics()
                    )
                }
            }

            return Starlark.NONE
        }

        /** Export a MacroFunction from a Starlark file with a given name.  */
        public override fun export(
            handler: com.google.devtools.build.lib.events.EventHandler?,
            starlarkLabel: Label?,
            exportedName: String?,
            exportedLocation: net.starlark.java.syntax.Location?
        ) {
            com.google.common.base.Preconditions.checkState(builder != null && macroClass == null)
            builder.setName(exportedName)
            builder.setDefiningBzlLabel(starlarkLabel)
            this.macroClass = builder.build()
            this.builder = null
            checkArgument(
                identityToken.getOwner().getLabel().equals(starlarkLabel),
                "created by %s, exporting as %s:%s",
                identityToken.getOwner(),
                starlarkLabel,
                exportedName
            )
            this.identityToken = identityToken.exportAs(exportedName)
            this.exportedLocation = exportedLocation
        }

        /**
         * Returns an exported macro's MacroClass (representing its schema and implementation function),
         * or null if the macro has not been exported yet.
         */
        fun getMacroClass(): MacroClass? {
            return macroClass
        }

        public override fun isExported(): Boolean {
            return macroClass != null
        }

        public override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
            if (isExported()) {
                printer.append("<macro ").append(macroClass.getName()).append(">")
            } else {
                printer.append("<macro>")
            }
        }

        override fun equals(obj: Any?): Boolean {
            if (obj is MacroFunction) {
                return identityToken == obj.identityToken
            }
            return false
        }

        override fun hashCode(): Int {
            return identityToken.hashCode()
        }

        override fun toString(): String {
            return "macro(...)"
        }

        public override fun isImmutable(): Boolean {
            // TODO(bazel-team): This seems technically wrong, analogous to
            // StarlarkRuleFunction#isImmutable.
            return true
        }
    }

    /**
     * A callable Starlark object representing a Starlark-defined rule, which may be invoked during
     * package construction time to instantiate the rule.
     * 
     * 
     * This is the object returned by calling `rule()`, e.g. the value that is bound in
     * `my_rule = rule(...)`}.
     */
    class StarlarkRuleFunction(
        builder: RuleClass.Builder?,
        definitionLocation: net.starlark.java.syntax.Location?,
        identityToken: net.starlark.java.eval.SymbolGenerator.Symbol<*>
    ) : StarlarkExportable, RuleFunction {
        // Initially non-null, then null once exported.
        private var builder: RuleClass.Builder?

        // Initially null, then non-null once exported.
        private var ruleClass: RuleClass? = null

        private val definitionLocation: net.starlark.java.syntax.Location?

        /**
         * A token representing the identity of this function.
         * 
         * 
         * This can be either a [Symbol] or a [AnalysisTestKey]. It's a [Symbol] if
         * it's unexported or a normal rule and a [AnalysisTestKey] if it's an exported
         * analysis_test. See comments at [AnalysisTestKey] for more details about the special
         * case.
         * 
         * 
         * Mutated by [.export].
         */
        private var identityToken: Any

        // TODO(adonovan): merge {Starlark,Builtin}RuleFunction and RuleClass,
        // making the latter a callable, StarlarkExportable value.
        // (Making RuleClasses first-class values will help us to build a
        // rich query output mode that includes values from loaded .bzl files.)
        // [Note from brandjon: Even if we merge RuleFunction and RuleClass, it may still be useful to
        // carry a distinction between loading-time vs analysis-time information about a rule type,
        // particularly when it comes to the possibility of lazy .bzl loading. For example, you can in
        // principle evaluate a BUILD file without loading and digesting .bzls that are only used by the
        // implementation function.]
        init {
            this.builder = builder
            this.definitionLocation = definitionLocation
            this.identityToken = identityToken
        }

        public override fun getName(): String? {
            return if (ruleClass != null) ruleClass.getName() else "unexported rule"
        }

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        public override fun call(thread: StarlarkThread, args: Tuple, kwargs: Dict<String?, Any?>): Any? {
            if (!args.isEmpty()) {
                throw net.starlark.java.eval.EvalException("Unexpected positional arguments")
            }
            if (ruleClass == null) {
                throw net.starlark.java.eval.EvalException("Invalid rule class hasn't been exported by a bzl file")
            }
            val targetDefinitionContext: TargetDefinitionContext =
                TargetDefinitionContext.fromOrFail(thread, "a rule", "instantiated")

            validateRulePropagatedAspects(ruleClass)

            val legacyAnyTypeAttrs: com.google.common.collect.ImmutableSet<String?> = getLegacyAnyTypeAttrs(ruleClass)

            try {
                // Temporarily remove `targetDefinitionContext` from the thread to prevent calls to load
                // time functions. Mutating values in initializers is mostly not a problem, because the
                // attribute values are copied before calling the initializers (<-TODO) and before they are
                // set on the target. Exception is a legacy case allowing arbitrary type of parameter
                // values. In that case the values may be mutated by the initializer, but they are still
                // copied when set on the target.
                thread.setThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java, null)
                // Allow access to the LabelConverter to support native.package_relative_label() in an
                // initializer.
                thread.setThreadLocal<T?>(LabelConverter::class.java, targetDefinitionContext.getLabelConverter())
                thread.setUncheckedExceptionContext(UncheckedExceptionContext { "an initializer" })

                // We call all the initializers of the rule and its ancestor rules, proceeding from child to
                // ancestor, so each initializer can transform the attributes it knows about in turn.
                var currentRuleClass: RuleClass? = ruleClass
                while (currentRuleClass != null
                ) {
                    if (currentRuleClass.getInitializer() == null) {
                        currentRuleClass = currentRuleClass.getStarlarkParent()
                        continue
                    }

                    // You might feel tempted to inspect the signature of the initializer function. The
                    // temptation might come from handling default values, making them work for better for the
                    // users.
                    // The less magic the better. Do not give in those temptations!
                    val initializerKwargs: net.starlark.java.eval.Dict.Builder<String?, Any?> =
                        Dict.builder<String?, Any?>()
                    for (attr in currentRuleClass.getAttributeProvider().getAttributes()) {
                        if ((attr.isPublic() && attr.starlarkDefined()) || attr.getName().equals("name")) {
                            if (kwargs.containsKey(attr.getName())) {
                                val value: Any? = kwargs.get(attr.getName())
                                if (value === Starlark.NONE) {
                                    continue
                                }
                                val reifiedValue =
                                    if (legacyAnyTypeAttrs.contains(attr.getName()))
                                        value
                                    else
                                        BuildType.copyAndLiftStarlarkValue(
                                            currentRuleClass.getName(),
                                            attr,
                                            value,
                                            targetDefinitionContext.getLabelConverter()
                                        )
                                initializerKwargs.put(attr.getName(), reifiedValue)
                            }
                        }
                    }
                    val ret: Any? =
                        Starlark.call(
                            thread,
                            currentRuleClass.getInitializer(),
                            Tuple.of(),
                            initializerKwargs.build(thread.mutability())
                        )
                    val newKwargs: Dict<String, Any?> =
                        if (ret === Starlark.NONE)
                            Dict.empty<String?, Any?>()
                        else
                            Dict.cast<String?, Any?>(
                                ret,
                                String::class.java,
                                Any::class.java,
                                "rule's initializer return value"
                            )

                    for (arg in newKwargs.keySet()) {
                        if (arg == "name") {
                            if (kwargs.get("name") != newKwargs.get("name")) {
                                throw Starlark.errorf("Initializer can't change the name of the target")
                            }
                            continue
                        }
                        checkAttributeName(arg)
                        if (arg.startsWith("_")) {
                            // allow setting private attributes from initializers in builtins
                            val definitionLabel: Label? = currentRuleClass.getRuleDefinitionEnvironmentLabel()
                            BuiltinRestriction.failIfLabelOutsideAllowlist(
                                definitionLabel,
                                targetDefinitionContext.getMainRepoMapping(),
                                ALLOWLIST_RULE_EXTENSION_API_EXPERIMENTAL
                            )
                        }
                        val nativeName = if (arg.startsWith("_")) "$" + arg.substring(1) else arg
                        val attr: Attribute? =
                            currentRuleClass.getAttributeProvider().getAttributeByNameMaybe(nativeName)
                        if (attr != null && !attr.starlarkDefined()) {
                            throw Starlark.errorf(
                                "Initializer can only set Starlark defined attributes, not '%s'", arg
                            )
                        }
                        val value: Any? = newKwargs.get(arg)
                        val reifiedValue =
                            if (attr == null || value === Starlark.NONE || legacyAnyTypeAttrs.contains(attr.getName()))
                                value
                            else
                                BuildType.copyAndLiftStarlarkValue(
                                    currentRuleClass.getName(),
                                    attr,
                                    value,  // Reify to the location of the initializer definition (except for outputs)
                                    if (attr.getType() === BuildType.OUTPUT
                                        || attr.getType() === BuildType.OUTPUT_LIST
                                    )
                                        targetDefinitionContext.getLabelConverter()
                                    else
                                        currentRuleClass.getLabelConverterForInitializer()
                                )
                        kwargs.putEntry(nativeName, reifiedValue)
                    }
                    currentRuleClass = currentRuleClass.getStarlarkParent()
                }
            } finally {
                thread.setThreadLocal<LabelConverter?>(LabelConverter::class.java, null)
                targetDefinitionContext.storeInThread(thread)
            }

            val attributeValues: BuildLangTypedAttributeValuesMap =
                BuildLangTypedAttributeValuesMap(kwargs)
            try {
                RuleFactory.createAndAddRule(
                    targetDefinitionContext,
                    ruleClass,
                    attributeValues,
                    thread
                        .getSemantics()
                        .getBool(BuildLanguageOptions.INCOMPATIBLE_FAIL_ON_UNKNOWN_ATTRIBUTES),
                    thread.getCallStack()
                )
            } catch (e: InvalidRuleException) {
                throw net.starlark.java.eval.EvalException(e)
            } catch (e: NameConflictException) {
                throw net.starlark.java.eval.EvalException(e)
            }
            return Starlark.NONE
        }

        /** Export a RuleFunction from a Starlark file with a given name.  */ // TODO(bazel-team): use exportedLocation as the callable symbol's location.
        public override fun export(
            handler: com.google.devtools.build.lib.events.EventHandler,
            starlarkLabel: Label?,
            ruleClassName: String?,
            exportedLocation: net.starlark.java.syntax.Location?
        ) {
            com.google.common.base.Preconditions.checkState(ruleClass == null && builder != null)
            val symbolToken: net.starlark.java.eval.SymbolGenerator.Symbol<*> =
                identityToken as net.starlark.java.eval.SymbolGenerator.Symbol<*> // always a Symbol before export
            this.identityToken =
                when (symbolToken.getOwner()) {
                    -> {
                        checkArgument(
                            bzlKey.getLabel().equals(starlarkLabel),
                            "Exporting rule as (%s, %s) but doesn't match owner %s",
                            starlarkLabel,
                            ruleClassName,
                            bzlKey
                        )
                        symbolToken.exportAs(ruleClassName)
                    }

                    else -> AnalysisTestKey.Companion.create(starlarkLabel, ruleClassName)
                }
            if (builder.getType() === RuleClassType.TEST != TargetUtils.isTestRuleName(ruleClassName)) {
                errorf(
                    handler,
                    "Invalid rule class name '%s', test rule class names must end with '_test' and other"
                            + " rule classes must not",
                    ruleClassName
                )
                return
            }

            // lift the subrule attributes to the rule class as if they were declared there, this lets us
            // exploit dependency resolution for "free"
            val subruleAttributes: com.google.common.collect.ImmutableList<Pair<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>>?
            try {
                val parentSubrules: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    builder.getParentSubrules()
                val subrulesNotInParents: com.google.common.collect.ImmutableList<StarlarkSubruleApi?>? =
                    builder.getSubrules().stream()
                        .filter({ subrule -> !parentSubrules.contains(subrule) })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
                subruleAttributes = StarlarkSubrule.Companion.discoverAttributes(subrulesNotInParents)
            } catch (e: net.starlark.java.eval.EvalException) {
                errorf(handler, "%s", e.getMessage())
                return
            }
            for (attribute in subruleAttributes) {
                val name: String? = attribute.getFirst()
                val descriptor: com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor =
                    attribute.getSecond()

                val attr: Attribute = descriptor.build(name)

                try {
                    builder.addAttribute(attr)
                } catch (ex: java.lang.IllegalStateException) {
                    // TODO(bazel-team): stop using unchecked exceptions in this way.
                    errorf(handler, "cannot add attribute: %s", ex.getMessage())
                }
            }

            try {
                this.ruleClass = builder.buildStarlark(ruleClassName, starlarkLabel)
            } catch (ex: java.lang.IllegalArgumentException) {
                // TODO(adonovan): this catch statement is an abuse of exceptions. Be more specific.
                val msg: String? = ex.getMessage()
                errorf(handler, "%s", if (msg != null) msg else ex.toString())
            } catch (ex: java.lang.IllegalStateException) {
                val msg: String? = ex.getMessage()
                errorf(handler, "%s", if (msg != null) msg else ex.toString())
            }

            this.builder = null
        }

        @com.google.errorprone.annotations.FormatMethod
        private fun errorf(
            handler: com.google.devtools.build.lib.events.EventHandler,
            format: String,
            vararg args: Any?
        ) {
            handler.handle(
                com.google.devtools.build.lib.events.Event.error(
                    definitionLocation,
                    java.lang.String.format(format, *args)
                )
            )
        }

        public override fun getRuleClass(): RuleClass? {
            com.google.common.base.Preconditions.checkState(ruleClass != null && builder == null)
            return ruleClass
        }

        public override fun isExported(): Boolean {
            if (identityToken is net.starlark.java.eval.SymbolGenerator.Symbol<*>) {
                return identityToken.isGlobal()
            }
            return true // it's an AnalysisTestKey
        }

        public override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
            if (isExported()) {
                printer.append("<rule ").append(getRuleClass().getName()).append(">")
            } else {
                printer.append("<rule>")
            }
        }

        override fun equals(obj: Any?): Boolean {
            if (obj is StarlarkRuleFunction) {
                return identityToken == obj.identityToken
            }
            return false
        }

        override fun hashCode(): Int {
            return identityToken.hashCode()
        }

        override fun toString(): String {
            return "rule(...)"
        }

        public override fun isImmutable(): Boolean {
            // TODO(bazel-team): It shouldn't be immutable until it's exported, no?
            return true
        }

        companion object {
            @Throws(net.starlark.java.eval.EvalException::class)
            private fun validateRulePropagatedAspects(ruleClass: RuleClass) {
                for (attribute in ruleClass.getAttributeProvider().getAttributes()) {
                    attribute.validateRulePropagatedAspectsParameters(ruleClass)
                }
            }
        }
    }

    /**
     * Special case exported [StarlarkRuleFunction.identityToken] for analysis_test.
     * 
     * 
     * [com.google.devtools.build.lib.rules.test.StarlarkTestingModule.analysisTest] is a
     * special case where a rule is instantiated in a BUILD file instead of a .bzl file.
     * 
     * @param label Label of the BUILD file exporting the analysis_test.
     */
    internal class AnalysisTestKey(label: Label?, val name: String?) {
        val label: Label?

        init {
            this.label = label
            java.util.Objects.requireNonNull<Any?>(label, "label")
            java.util.Objects.requireNonNull<String?>(name, "name")
        }

        companion object {
            private fun create(label: Label?, name: String?): AnalysisTestKey {
                return AnalysisTestKey(label, name)
            }
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun label(input: Any?, thread: StarlarkThread): Label? {
        if (input is Label) {
            return input as Label?
        }
        // The label string is interpreted with respect to the .bzl module containing the call to
        // `Label()`. An alternative to this approach that avoids stack inspection is to have each .bzl
        // module define its own copy of the `Label()` builtin embedding the module's own name. This
        // would lead to peculiarities like foo.bzl being able to call bar.bzl's `Label()` symbol to
        // resolve strings as if it were bar.bzl. It also would prevent sharing the same builtins
        // environment across .bzl files. Hence, we opt for stack inspection.
        val moduleContext: BazelModuleContext = BazelModuleContext.ofInnermostBzlOrFail(thread, "Label()")
        try {
            return Label.parseWithPackageContext(
                input as String?,
                moduleContext.packageContext(),
                thread.getThreadLocal<T?>(Label.RepoMappingRecorder::class.java)
            )
        } catch (e: LabelSyntaxException) {
            throw Starlark.errorf("invalid label in Label(): %s", e.getMessage())
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun execGroup(
        toolchains: net.starlark.java.eval.Sequence<*>,
        execCompatibleWith: net.starlark.java.eval.Sequence<*>,
        thread: StarlarkThread?
    ): DeclaredExecGroup {
        val labelConverter: LabelConverter = LabelConverter.forBzlEvaluatingThread(thread)
        val toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?> =
            parseToolchainTypes(toolchains, labelConverter)
        val constraints: com.google.common.collect.ImmutableSet<Label?> =
            parseLabels(execCompatibleWith, labelConverter, "exec_compatible_with")
        return DeclaredExecGroup.builder()
            .toolchainTypes(toolchainTypes)
            .execCompatibleWith(constraints)
            .build()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun subrule(
        implementation: StarlarkFunction?,
        attrsUnchecked: Dict<*, *>?,
        toolchainsUnchecked: net.starlark.java.eval.Sequence<*>,
        fragmentsUnchecked: net.starlark.java.eval.Sequence<*>?,
        subrulesUnchecked: net.starlark.java.eval.Sequence<*>?,
        thread: StarlarkThread?
    ): StarlarkSubruleApi? {
        val attrs: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?> =
            com.google.common.collect.ImmutableMap.copyOf<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>(
                Dict.cast<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>(
                    attrsUnchecked,
                    String::class.java,
                    com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor::class.java,
                    "attrs"
                )
            )
        val fragments: com.google.common.collect.ImmutableList<String?> =
            net.starlark.java.eval.Sequence.noneableCast<String?>(fragmentsUnchecked, String::class.java, "fragments")
                .getImmutableList()
        for (attr in attrs.entrySet()) {
            val attrName: String = attr.getKey()
            val descriptor: com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor =
                attr.getValue()
            val transitionFactory: TransitionFactory<AttributeTransitionData?> =
                descriptor.getTransitionFactory()
            if (!NoTransition.isInstance(transitionFactory) && !transitionFactory.isTool()) {
                throw Starlark.errorf(
                    "bad cfg for attribute '%s': subrules may only have target/exec attributes.", attrName
                )
            }
            checkAttributeName(attrName)
            val type: Type<*>? = descriptor.getType()
            if (!attrName.startsWith("_")) {
                throw Starlark.errorf(
                    "illegal attribute name '%s': subrules may only define private attributes (whose names"
                            + " begin with '_').",
                    attrName
                )
            } else if (descriptor.getValueSource() === AttributeValueSource.COMPUTED_DEFAULT) {
                throw Starlark.errorf(
                    "illegal default value for attribute '%s': subrules cannot define computed defaults.",
                    attrName
                )
            } else if (!descriptor.hasDefault()) {
                throw Starlark.errorf("for attribute '%s': no default value specified", attrName)
            } else if (type !== LABEL && type !== LABEL_LIST) {
                throw Starlark.errorf(
                    "bad type for attribute '%s': subrule attributes may only be label or lists of labels.",
                    attrName
                )
            }
        }
        val toolchains: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?> =
            parseToolchainTypes(toolchainsUnchecked, LabelConverter.forBzlEvaluatingThread(thread))
        if (toolchains.size() > 1) {
            throw Starlark.errorf("subrules may require at most 1 toolchain, got: %s", toolchains)
        }
        return StarlarkSubrule(
            implementation,
            attrs,
            toolchains,
            com.google.common.collect.ImmutableSet.copyOf<String?>(fragments),
            com.google.common.collect.ImmutableSet.copyOf<StarlarkSubrule?>(
                net.starlark.java.eval.Sequence.cast<StarlarkSubrule?>(
                    subrulesUnchecked,
                    StarlarkSubrule::class.java,
                    "subrules"
                )
            )
        )
    }

    /** Visitor to check whether a transition has any Starlark components.  */
    private class StarlarkTransitionCheckingVisitor : Visitor<RuleTransitionData?> {
        private var hasStarlarkDefinedTransition = false

        public override fun visit(factory: TransitionFactory<RuleTransitionData?>?) {
            this.hasStarlarkDefinedTransition =
                this.hasStarlarkDefinedTransition or factory is StarlarkRuleTransitionProvider
        }
    }

    @com.google.errorprone.annotations.Keep // used reflectively
    private class Codec : AbstractExportedStarlarkSymbolCodec<StarlarkRuleFunction?>() {
        public override fun getEncodedClass(): java.lang.Class<StarlarkRuleFunction?> {
            return StarlarkRuleFunction::class.java
        }

        protected override fun getBzlLoadKey(obj: StarlarkRuleFunction): BzlLoadValue.Key? {
            // TODO: b/326588519 - this does not support AnalysisTestKey but that type does not seem to
            // appear in action lookup values. Make this more robust if necessary.
            val symbol: GlobalSymbol<*> = obj.identityToken as GlobalSymbol<*>
            return symbol.getOwner() as BzlLoadValue.Key?
        }

        protected override fun getExportedName(obj: StarlarkRuleFunction): String? {
            return (obj.identityToken as GlobalSymbol<*>).getName()
        }
    }

    companion object {
        // A cache for base rule classes (especially tests).
        private val labelCache: com.github.benmanes.caffeine.cache.LoadingCache<String?, Label?> =
            Caffeine.newBuilder().build<String?, Label?>(Label::parseCanonical)

        // TODO(bazel-team): Remove the code duplication (BaseRuleClasses and this class).
        /** Parent rule class for non-executable non-test Starlark rules.  */
        val baseRule: RuleClass = BaseRuleClasses.commonCoreAndStarlarkAttributes(
            Builder("\$base_rule", RuleClassType.ABSTRACT, true)
                .add(attr("expect_failure", STRING))
        ) // TODO(skylark-team): Allow Starlark rules to extend native rules and remove duplication.
            .add(
                attr("toolchains", LABEL_LIST)
                    .allowedFileTypes(FileTypeSet.NO_FILE)
                    .mandatoryProviders(com.google.common.collect.ImmutableList.of<E?>(TemplateVariableInfo.PROVIDER.id()))
                    .dontCheckConstraints()
            )
            .add(
                attr(
                    RuleClass.EXEC_PROPERTIES_ATTR,
                    Types.STRING_DICT
                ).value(com.google.common.collect.ImmutableMap.of<K?, V?>())
            )
            .add(
                attr(RuleClass.EXEC_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST)
                    .allowedFileTypes()
                    .nonconfigurable("Used in toolchain resolution")
                    .tool(
                        "exec_compatible_with exists for constraint checking, not to create an"
                                + " actual dependency"
                    )
                    .value(com.google.common.collect.ImmutableList.of<E?>())
            )
            .add(
                attr(RuleClass.EXEC_GROUP_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST_DICT)
                    .allowedFileTypes()
                    .nonconfigurable("Used in toolchain resolution")
                    .tool(
                        "exec_group_compatible_with exists for constraint checking, not to create an"
                                + " actual dependency"
                    )
                    .value(com.google.common.collect.ImmutableMap.of<K?, V?>())
            )
            .add(
                attr(RuleClass.TARGET_COMPATIBLE_WITH_ATTR, LABEL_LIST)
                    .mandatoryProviders(ConstraintValueInfo.PROVIDER.id()) // This should be configurable to allow for complex types of restrictions.
                    .tool(
                        "target_compatible_with exists for constraint checking, not to create an"
                                + " actual dependency"
                    )
                    .allowedFileTypes(FileTypeSet.NO_FILE)
            )
            .build()

        val dependencyResolutionBaseRule: RuleClass? = Builder(
            "\$dependency_resolution_base_rule", RuleClassType.ABSTRACT, true, baseRule
        )
            .setDependencyResolutionRule()
            .removeAttribute(":action_listener")
            .removeAttribute("aspect_hints")
            .removeAttribute("toolchains")
            .removeAttribute(RuleClass.EXEC_COMPATIBLE_WITH_ATTR)
            .removeAttribute(RuleClass.EXEC_GROUP_COMPATIBLE_WITH_ATTR)
            .removeAttribute(RuleClass.TARGET_COMPATIBLE_WITH_ATTR)
            .removeAttribute("compatible_with")
            .removeAttribute("restricted_to")
            .removeAttribute("\$config_dependencies") // Dependency resolution rules can't have package defaults.
            .removeAttribute("package_metadata")
            .build()

        val materializerBaseRule: RuleClass? =
            Builder("\$materializer_base_rule", RuleClassType.ABSTRACT, true, baseRule)
                .setIsMaterializerRule(true)
                .removeAttribute(":action_listener")
                .removeAttribute("aspect_hints")
                .removeAttribute("toolchains")
                .removeAttribute(RuleClass.EXEC_COMPATIBLE_WITH_ATTR)
                .removeAttribute(RuleClass.EXEC_GROUP_COMPATIBLE_WITH_ATTR)
                .removeAttribute(RuleClass.TARGET_COMPATIBLE_WITH_ATTR)
                .removeAttribute("compatible_with")
                .removeAttribute("restricted_to") // Materializer rules can't have package defaults, in particular because materializer
                // rules can't have dependencies on non-dependency-resolution-rules or dependencies
                // through non-dormant attributes.
                .removeAttribute("package_metadata")
                .build()

        /** Parent rule class for executable non-test Starlark rules.  */
        private val binaryBaseRule: RuleClass? = Builder("\$binary_base_rule", RuleClassType.ABSTRACT, true, baseRule)
            .add(attr("args", STRING_LIST))
            .add(attr("output_licenses", STRING_LIST))
            .addAttribute(
                attr(Rule.IS_EXECUTABLE_ATTRIBUTE_NAME, BOOLEAN)
                    .value(true)
                    .nonconfigurable("Called from RunCommand.isExecutable, which takes a Target")
                    .build()
            )
            .build()

        val ALLOWLIST_RULE_EXTENSION_API: Allowlist? = BuiltinRestriction.Allowlist.of(
            mainRepoAllowlistEntry("initializer_testing"),
            mainRepoAllowlistEntry("extend_rule_testing"),
            mainRepoAllowlistEntry("subrule_testing")
        )

        val ALLOWLIST_RULE_EXTENSION_API_EXPERIMENTAL: Allowlist? = BuiltinRestriction.Allowlist.of(
            mainRepoAllowlistEntry("third_party/bazel_rules/rules_cc"),
            mainRepoAllowlistEntry("initializer_testing/builtins"),
            externalRepoAllowlistEntry("rules_cc", "")
        )

        private const val COMMON_ATTRIBUTES_NAME = "common"

        /** Parent rule class for test Starlark rules.  */
        fun getTestBaseRule(env: RuleDefinitionEnvironment): RuleClass {
            val toolsRepository: RepositoryName? = env.getToolsRepository()
            val builder: RuleClass.Builder =
                Builder("\$test_base_rule", RuleClassType.ABSTRACT, true, baseRule)
                    .requiresConfigurationFragments(TestConfiguration::class.java) // TestConfiguration only needed to create TestAction and TestProvider
                    // Only necessary at top-level and can be skipped if trimmed.
                    .setMissingFragmentPolicy(TestConfiguration::class.java, MissingFragmentPolicy.IGNORE)
                    .add(
                        attr("size", STRING)
                            .value("medium")
                            .taggable()
                            .nonconfigurable("used in loading phase rule validation logic")
                    )
                    .add(
                        attr("timeout", STRING)
                            .taggable()
                            .nonconfigurable("policy decision: should be consistent across configurations")
                            .value(BaseRuleClasses.TIMEOUT_DEFAULT)
                    )
                    .add(
                        attr("flaky", BOOLEAN)
                            .value(false)
                            .taggable()
                            .nonconfigurable("taggable - called in Rule.getRuleTags")
                    )
                    .add(attr("shard_count", INTEGER).value(StarlarkInt.of(-1)))
                    .add(
                        attr("local", BOOLEAN)
                            .value(false)
                            .taggable()
                            .nonconfigurable(
                                "policy decision: this should be consistent across configurations"
                            )
                    )
                    .add(attr("args", STRING_LIST)) // Input files for every test action
                    .add(
                        attr("\$test_wrapper", LABEL)
                            .cfg(
                                ExecutionTransitionFactory.Companion.createFactory(
                                    DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME
                                )
                            )
                            .singleArtifact()
                            .value(labelCache.get(toolsRepository.toString() + "//tools/test:test_wrapper"))
                    )
                    .add(
                        attr("\$xml_writer", LABEL)
                            .cfg(
                                ExecutionTransitionFactory.Companion.createFactory(
                                    DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME
                                )
                            )
                            .singleArtifact()
                            .value(labelCache.get(toolsRepository.toString() + "//tools/test:xml_writer"))
                    )
                    .add(
                        attr("\$test_runtime", LABEL_LIST)
                            .cfg(
                                ExecutionTransitionFactory.Companion.createFactory(
                                    DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME
                                )
                            ) // Getting this default value through the getTestRuntimeLabelList helper ensures
                            // we reuse the same ImmutableList<Label> instance for each $test_runtime attr.
                            .value(BaseRuleClasses.getTestRuntimeLabelList(env))
                    )
                    .add(
                        attr("\$test_setup_script", LABEL)
                            .cfg(
                                ExecutionTransitionFactory.Companion.createFactory(
                                    DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME
                                )
                            )
                            .singleArtifact()
                            .value(labelCache.get(toolsRepository.toString() + "//tools/test:test_setup"))
                    )
                    .add(
                        attr("\$xml_generator_script", LABEL)
                            .cfg(
                                ExecutionTransitionFactory.Companion.createFactory(
                                    DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME
                                )
                            )
                            .singleArtifact()
                            .value(labelCache.get(toolsRepository.toString() + "//tools/test:test_xml_generator"))
                    )
                    .add(
                        attr("\$collect_coverage_script", LABEL)
                            .cfg(
                                ExecutionTransitionFactory.Companion.createFactory(
                                    DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME
                                )
                            )
                            .value(labelCache.get(toolsRepository.toString() + "//tools/test:collect_coverage"))
                    ) // Input files for test actions collecting code coverage
                    .add(
                        attr(":coverage_support", LABEL)
                            .cfg(
                                ExecutionTransitionFactory.Companion.createFactory(
                                    DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME
                                )
                            )
                            .value(
                                BaseRuleClasses.coverageSupportAttribute(
                                    labelCache.get(
                                        toolsRepository.toString() + BaseRuleClasses.DEFAULT_COVERAGE_SUPPORT_VALUE
                                    )
                                )
                            )
                    ) // Used in the one-per-build coverage report generation action.
                    .add(
                        attr(":coverage_report_generator", LABEL)
                            .cfg(
                                ExecutionTransitionFactory.Companion.createFactory(
                                    DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME
                                )
                            )
                            .value(
                                BaseRuleClasses.coverageReportGeneratorAttribute(
                                    labelCache.get(
                                        toolsRepository
                                            .toString() + BaseRuleClasses.DEFAULT_COVERAGE_REPORT_GENERATOR_VALUE
                                    )
                                )
                            )
                    ) // See similar definitions in BaseRuleClasses for context.
                    .add(
                        attr(":run_under_exec_config", LABEL)
                            .cfg(
                                ExecutionTransitionFactory.Companion.createFactory(
                                    DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME
                                )
                            )
                            .value(BaseRuleClasses.RUN_UNDER_EXEC_CONFIG)
                            .skipPrereqValidatorCheck()
                    )
                    .add(
                        attr(":run_under_target_config", LABEL)
                            .value(BaseRuleClasses.RUN_UNDER_TARGET_CONFIG)
                            .skipPrereqValidatorCheck()
                    )
                    .addAttribute(
                        attr(Rule.IS_EXECUTABLE_ATTRIBUTE_NAME, BOOLEAN)
                            .value(true)
                            .nonconfigurable("Called from RunCommand.isExecutable, which takes a Target")
                            .build()
                    )

            env.getNetworkAllowlistForTests()
                .ifPresent(
                    { label ->
                        builder.add(
                            Allowlist.getAttributeFromAllowlistName("external_network").value(label)
                        )
                    })

            return builder.build()
        }

        @com.google.errorprone.annotations.FormatMethod
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun failIf(condition: Boolean, message: String?, vararg args: Any?) {
            if (condition) {
                throw Starlark.errorf(message, *args)
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun getAttrsOf(inheritAttrsArg: Any): com.google.common.collect.ImmutableList<Attribute>? {
            if (inheritAttrsArg === Starlark.NONE) {
                return com.google.common.collect.ImmutableList.of<Attribute?>()
            } else if (inheritAttrsArg is RuleFunction) {
                verifyInheritAttrsArgExportedIfExportable(inheritAttrsArg)
                return inheritAttrsArg.getRuleClass().getAttributeProvider().getAttributes()
            } else if (inheritAttrsArg is MacroFunction) {
                verifyInheritAttrsArgExportedIfExportable(inheritAttrsArg)
                return inheritAttrsArg.getMacroClass().getAttributeProvider().getAttributes()
            } else if (inheritAttrsArg == COMMON_ATTRIBUTES_NAME) {
                return baseRule.getAttributeProvider().getAttributes()
            }
            throw Starlark.errorf(
                "Invalid 'inherit_attrs' value %s; expected a rule, a macro, or \"common\"",
                Starlark.repr(inheritAttrsArg, StarlarkSemantics.DEFAULT)
            )
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun verifyInheritAttrsArgExportedIfExportable(inheritAttrsArg: Any?) {
            // Note that the value of 'inherit_attrs' can be non-exportable (e.g. native rule).
            if (inheritAttrsArg is StarlarkExportable && !inheritAttrsArg.isExported()) {
                throw Starlark.errorf(
                    "Invalid 'inherit_attrs' value: a rule or macro callable must be assigned to a global"
                            + " variable in a .bzl file before it can be inherited from"
                )
            }
        }

        private fun getBzlKeyToken(
            thread: StarlarkThread,
            onBehalfOf: String?
        ): net.starlark.java.eval.SymbolGenerator.Symbol<BzlLoadValue.Key?> {
            val untypedToken: net.starlark.java.eval.SymbolGenerator.Symbol<*> = thread.getNextIdentityToken()
            com.google.common.base.Preconditions.checkState(
                untypedToken.getOwner() is BzlLoadValue.Key,
                "%s may only be owned by .bzl files (owner=%s)",
                onBehalfOf,
                untypedToken
            )
            val typedToken: net.starlark.java.eval.SymbolGenerator.Symbol<BzlLoadValue.Key?> =
                untypedToken as net.starlark.java.eval.SymbolGenerator.Symbol<BzlLoadValue.Key?>
            return typedToken
        }

        /**
         * Returns a new callable representing a Starlark-defined rule.
         * 
         * 
         * This is public for the benefit of [ ], which has the unusual use case
         * of creating new rule types to house analysis-time test assertions (`analysis_test`). It's
         * probably not a good idea to add new callers of this method.
         * 
         * 
         * Note that the bzlFile and transitiveDigest params correspond to the outermost .bzl file
         * being evaluated, not the one in which rule() is called.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun createRule( // Contextual parameters.
            ruleDefinitionEnvironment: RuleDefinitionEnvironment,
            thread: StarlarkThread,
            bzlFile: Label,
            transitiveDigest: ByteArray?,
            labelConverter: LabelConverter,  // Parameters that come from rule().
            parent: RuleClass?,
            extendableUnchecked: Any?,
            implementation: StarlarkFunction?,
            initializer: StarlarkFunction?,
            test: Boolean,
            attrs: Dict<*, *>?,
            implicitOutputs: Any?,
            executable: Boolean,
            outputToGenfiles: Boolean,
            fragments: net.starlark.java.eval.Sequence<*>?,
            starlarkTestable: Boolean,
            toolchains: net.starlark.java.eval.Sequence<*>,
            doc: Any?,
            providesArg: net.starlark.java.eval.Sequence<*>?,
            dependencyResolutionRule: Boolean,
            isMaterializerRule: Boolean,
            allowMaterializerRuleRealDeps: Boolean,
            execCompatibleWith: net.starlark.java.eval.Sequence<*>,
            analysisTest: Any?,
            buildSetting: Any,
            cfg: Any,
            execGroups: Any?,
            subrulesUnchecked: net.starlark.java.eval.Sequence<*>
        ): StarlarkRuleFunction {
            // analysis_test=true implies test=true.

            var test = test
            test = test or (java.lang.Boolean.TRUE == analysisTest)

            val type: RuleClassType? = if (test) RuleClassType.TEST else RuleClassType.NORMAL

            val builder: RuleClass.Builder
            if (isMaterializerRule) {
                if (parent != null) {
                    throw Starlark.errorf("materializer rules cannot have a parent")
                }
                builder = Builder("", type, true, materializerBaseRule)
                builder.setMaterializerRuleAllowsRealDeps(allowMaterializerRuleRealDeps)
            } else if (dependencyResolutionRule) {
                if (parent != null) {
                    throw Starlark.errorf("rules used in dependency resolution cannot have a parent")
                }

                builder = Builder("", type, true, dependencyResolutionBaseRule)
            } else if (parent != null) {
                if (parent.isDependencyResolutionRule()) {
                    throw Starlark.errorf("dependency resolution rules cannot be parents")
                }
                if (parent.isMaterializerRule()) {
                    throw Starlark.errorf("materializer rules cannot be parents")
                }

                // We'll set the name later, pass the empty string for now.
                builder = Builder("", type, true, parent)
            } else {
                // We'll set the name later, pass the empty string for now.
                val baseParent: RuleClass? =
                    if (test)
                        getTestBaseRule(ruleDefinitionEnvironment)
                    else
                        (if (executable) binaryBaseRule else baseRule)
                builder = Builder("", type, true, baseParent)
            }

            builder.initializer(initializer, labelConverter)

            builder.setDefaultExtendableAllowlist(
                ruleDefinitionEnvironment.getToolsLabel("//tools/allowlists/extend_rule_allowlist")
            )
            if (extendableUnchecked is Boolean) {
                builder.setExtendable(extendableUnchecked)
            } else if (extendableUnchecked is String) {
                try {
                    builder.setExtendableByAllowlist(labelConverter.convert(extendableUnchecked))
                } catch (e: LabelSyntaxException) {
                    throw Starlark.errorf(
                        "Unable to parse label '%s': %s", extendableUnchecked, e.getMessage()
                    )
                }
            } else if (extendableUnchecked is Label) {
                builder.setExtendableByAllowlist(extendableUnchecked as Label?)
            } else {
                failIf(
                    !(extendableUnchecked === Starlark.NONE || extendableUnchecked == null),
                    "parameter 'extendable': expected bool, str or Label, but got '%s'",
                    if (extendableUnchecked == null) null else Starlark.type(extendableUnchecked)
                )
            }

            // Verify the child against parent's allowlist
            if (parent != null && parent.getExtendableAllowlist() != null && !bzlFile.getRepository().getName()
                    .equals("_builtins")
            ) {
                builder.addAllowlistChecker(EXTEND_RULE_ALLOWLIST_CHECKER)
                val allowlistAttr: Attribute.Builder<Label?>? =
                    attr("\$allowlist_extend_rule", LABEL)
                        .cfg(ExecutionTransitionFactory.Companion.createFactory())
                        .mandatoryBuiltinProviders(
                            com.google.common.collect.ImmutableList.of<E?>(
                                PackageSpecificationProvider::class.java
                            )
                        )
                        .value(parent.getExtendableAllowlist())
                if (builder.contains("\$allowlist_extend_rule")) {
                    // the allowlist already exist if this is the second extension of the rule
                    // in this case we need to override the allowlist with the one in the direct parent
                    builder.override(allowlistAttr)
                } else {
                    builder.add(allowlistAttr)
                }
            }

            if (parent != null && !thread.getSemantics()
                    .getBool(BuildLanguageOptions.EXPERIMENTAL_RULE_EXTENSION_API) && !bzlFile.getRepository().getName()
                    .equals("_builtins")
            ) {
                builder.addAllowlistChecker(EXTEND_RULE_API_ALLOWLIST_CHECKER)
                if (!builder.contains("\$allowlist_extend_rule_api")) {
                    val allowlistAttr: Attribute.Builder<Label?>? =
                        attr("\$allowlist_extend_rule_api", LABEL)
                            .cfg(ExecutionTransitionFactory.Companion.createFactory())
                            .mandatoryBuiltinProviders(
                                com.google.common.collect.ImmutableList.of<E?>(
                                    PackageSpecificationProvider::class.java
                                )
                            )
                            .value(
                                ruleDefinitionEnvironment.getToolsLabel(
                                    "//tools/allowlists/extend_rule_allowlist:extend_rule_api_allowlist"
                                )
                            )
                    builder.add(allowlistAttr)
                }
            }

            if (initializer != null) {
                if (!thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_RULE_EXTENSION_API)
                    && !bzlFile.getRepository().getName().equals("_builtins")
                ) {
                    builder.addAllowlistChecker(INITIALIZER_ALLOWLIST_CHECKER)
                    if (!builder.contains("\$allowlist_initializer")) {
                        // the allowlist already exist if this is an extended rule
                        val allowlistAttr: Attribute.Builder<Label?>? =
                            attr("\$allowlist_initializer", LABEL)
                                .cfg(ExecutionTransitionFactory.Companion.createFactory())
                                .mandatoryBuiltinProviders(
                                    com.google.common.collect.ImmutableList.of<E?>(
                                        PackageSpecificationProvider::class.java
                                    )
                                )
                                .value(
                                    ruleDefinitionEnvironment.getToolsLabel(
                                        "//tools/allowlists/initializer_allowlist"
                                    )
                                )
                        builder.add(allowlistAttr)
                    }
                }
            }

            if (!subrulesUnchecked.isEmpty()) {
                if (!thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_RULE_EXTENSION_API)
                    && !bzlFile.getRepository().getName().equals("_builtins")
                ) {
                    builder.addAllowlistChecker(SUBRULES_ALLOWLIST_CHECKER)
                    if (!builder.contains("\$allowlist_subrules")) {
                        // the allowlist already exist if this is an extended rule
                        val allowlistAttr: Attribute.Builder<Label?>? =
                            attr("\$allowlist_subrules", LABEL)
                                .cfg(ExecutionTransitionFactory.Companion.createFactory())
                                .mandatoryBuiltinProviders(
                                    com.google.common.collect.ImmutableList.of<E?>(
                                        PackageSpecificationProvider::class.java
                                    )
                                )
                                .value(
                                    ruleDefinitionEnvironment.getToolsLabel(
                                        "//tools/allowlists/subrules_allowlist"
                                    )
                                )
                        builder.add(allowlistAttr)
                    }
                }
            }

            if (isMaterializerRule) {
                builder.addAllowlistChecker(MATERIALIZER_RULE_ALLOWLIST_CHECKER)
                if (!builder.contains("\$allowlist_materializer_rule")) {
                    // the allowlist already exists if this is an extended rule
                    val allowlistAttr: Attribute.Builder<Label?>? =
                        attr("\$allowlist_materializer_rule", LABEL)
                            .cfg(ExecutionTransitionFactory.Companion.createFactory())
                            .mandatoryBuiltinProviders(
                                com.google.common.collect.ImmutableList.of<E?>(
                                    PackageSpecificationProvider::class.java
                                )
                            )
                            .value(
                                ruleDefinitionEnvironment.getToolsLabel(
                                    "//tools/allowlists/materializer_rule_allowlist"
                                )
                            )
                    builder.add(allowlistAttr)
                }

                if (allowMaterializerRuleRealDeps) {
                    builder.addAllowlistChecker(MATERIALIZER_RULE_REAL_DEPS_ALLOWLIST_CHECKER)
                    if (!builder.contains("\$allowlist_materializer_rule_real_deps")) {
                        // the allowlist already exists if this is an extended rule
                        val allowlistAttr: Attribute.Builder<Label?>? =
                            attr("\$allowlist_materializer_rule_real_deps", LABEL)
                                .cfg(ExecutionTransitionFactory.Companion.createFactory())
                                .mandatoryBuiltinProviders(
                                    com.google.common.collect.ImmutableList.of<E?>(
                                        PackageSpecificationProvider::class.java
                                    )
                                )
                                .value(
                                    ruleDefinitionEnvironment.getToolsLabel(
                                        "//tools/allowlists/materializer_rule_allowlist"
                                                + ":materializer_rule_real_deps_allowlist"
                                    )
                                )
                        builder.add(allowlistAttr)
                    }
                }
            }

            if (executable || test) {
                builder.setExecutableStarlark()
            }

            // Get the callstack, sans the last entry, which is the builtin 'rule' callable itself.
            var callStack: com.google.common.collect.ImmutableList<CallStackEntry?> = thread.getCallStack()
            callStack = callStack.subList(0, callStack.size() - 1)
            builder.setCallStack(callStack)

            val attributes: com.google.common.collect.ImmutableList<Pair<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>> =
                attrObjectToAttributesList(attrs)

            if (starlarkTestable) {
                builder.setStarlarkTestable()
            }
            if (java.lang.Boolean.TRUE == analysisTest) {
                builder.setIsAnalysisTest()
            }

            var hasStarlarkDefinedTransition = false
            var propagatesAspects = false
            var hasMaterializers = false
            val dormantAttributes: MutableList<String?> = java.util.ArrayList<String?>()

            for (attribute in attributes) {
                val name: String? = attribute.getFirst()
                val descriptor: com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor =
                    attribute.getSecond()

                var attr: Attribute = descriptor.build(name)
                val isDependency = attr.getType().getLabelClass() === LabelClass.DEPENDENCY

                if (dependencyResolutionRule && attr.isMaterializing()) {
                    throw Starlark.errorf(
                        "attribute '%s' has a materializer which is not allowed on rules for dependency"
                                + " resolution",
                        name
                    )
                }

                if (dependencyResolutionRule && isDependency) {
                    if (!attr.isForDependencyResolution() && attr.forDependencyResolutionExplicitlySet()) {
                        throw Starlark.errorf(
                            "attribute '%s' is explicitly marked as not for dependency"
                                    + " resolution, which is disallowed on rules for dependency resolution",
                            name
                        )
                    }

                    attr =
                        attr.cloneBuilder()
                            .setPropertyFlag("FOR_DEPENDENCY_RESOLUTION")
                            .nonconfigurable("On a rule used in dependency resolution")
                            .build()
                }

                // "configurable" may only be user-set for symbolic macros, not rules.
                if (attr.configurableAttrWasUserSet()) {
                    throw Starlark.errorf(
                        "attribute '%s' has the 'configurable' argument set, which is not allowed in rule"
                                + " definitions",
                        name
                    )
                }
                if (attr.skipValidations()) {
                    // This is mitigation for internal Blaze builds, and not planned to be a Bazel feature,
                    // and therefore has no extendable allowlists.
                    if (!builder.contains("\$allowlist_skip_validations")) {
                        val allowlistAttr: Attribute.Builder<Label?>? =
                            attr("\$allowlist_skip_validations", LABEL)
                                .cfg(ExecutionTransitionFactory.Companion.createFactory())
                                .mandatoryBuiltinProviders(
                                    com.google.common.collect.ImmutableList.of<E?>(
                                        PackageSpecificationProvider::class.java
                                    )
                                )
                                .value(
                                    Label.parseCanonicalUnchecked(
                                        "//tools/allowlists/skip_validations_allowlist"
                                    )
                                )
                        builder.add(allowlistAttr)
                        builder.addAllowlistChecker(SKIP_VALIDATIONS_ALLOWLIST_CHECKER)
                    }
                }

                if (attr.getAspectsList().hasAspects()) {
                    propagatesAspects = true
                }

                hasStarlarkDefinedTransition = hasStarlarkDefinedTransition or attr.hasStarlarkDefinedTransition()
                if (attr.hasAnalysisTestTransition()) {
                    if (!builder.isAnalysisTest()) {
                        throw Starlark.errorf(
                            "Only rule definitions with analysis_test=True may have attributes with"
                                    + " analysis_test_transition transitions"
                        )
                    }
                    builder.setHasAnalysisTestTransition()
                }

                if (attr.getType() === BuildType.DORMANT_LABEL
                    || attr.getType() === BuildType.DORMANT_LABEL_LIST
                ) {
                    dormantAttributes.add(name)
                }

                if (attr.isMaterializing()) {
                    hasMaterializers = true
                }

                try {
                    if (builder.contains(attr.getName())) {
                        builder.override(attr)
                    } else {
                        builder.addAttribute(attr)
                    }
                } catch (ex: java.lang.IllegalStateException) {
                    // TODO(bazel-team): stop using unchecked exceptions in this way.
                    throw Starlark.errorf("cannot add attribute: %s", ex.getMessage())
                }
            }

            // the set of subrules is stored in the rule class, primarily for validating that a rule class
            // declared the subrule when using it.
            val subrules: com.google.common.collect.ImmutableList<StarlarkSubrule?> =
                net.starlark.java.eval.Sequence.cast<StarlarkSubrule?>(
                    subrulesUnchecked,
                    StarlarkSubrule::class.java,
                    "subrules"
                ).getImmutableList()
            builder.addToolchainTypes(StarlarkSubrule.Companion.discoverToolchains(subrules))
            builder.setSubrules(subrules)

            if (implicitOutputs !== Starlark.NONE) {
                if (implicitOutputs is StarlarkFunction) {
                    val callback: StarlarkCallbackHelper =
                        StarlarkCallbackHelper(implicitOutputs as StarlarkFunction, thread.getSemantics())
                    builder.setImplicitOutputsFunction(
                        StarlarkImplicitOutputsFunctionWithCallback(callback)
                    )
                } else {
                    builder.setImplicitOutputsFunction(
                        StarlarkImplicitOutputsFunctionWithMap(
                            com.google.common.collect.ImmutableMap.< K, V > copyOf<K?, V?>(
                                Dict.cast<String?, String?>(
                                    implicitOutputs,
                                    String::class.java,
                                    String::class.java,
                                    "implicit outputs of the rule class"
                                )
                            )
                        )
                    )
                }
            }

            if (outputToGenfiles) {
                builder.setOutputToGenfiles()
            }

            builder.requiresConfigurationFragmentsByStarlarkModuleName(
                net.starlark.java.eval.Sequence.cast<T?>(fragments, String::class.java, "fragments")
            )
            builder.setConfiguredTargetFunction(implementation)

            // The rule definition's label and transitive digest typically come from the context of the .bzl
            // file being initialized.
            //
            // Note that if rule() was called via a helper function (a meta-macro), the label and digest of
            // the .bzl file of the innermost stack frame might not be the same as that of the outermost
            // frame. In this case we really do want the outermost, in order to ensure that the digest
            // includes the code that determines the helper function's argument values.
            builder.setRuleDefinitionEnvironmentLabelAndDigest(bzlFile, transitiveDigest)

            builder.addToolchainTypes(parseToolchainTypes(toolchains, labelConverter))

            if (execGroups !== Starlark.NONE) {
                val override = parent != null
                val execGroupDict: MutableMap<String?, DeclaredExecGroup?> =
                    Dict.cast<String?, DeclaredExecGroup?>(
                        execGroups,
                        String::class.java,
                        DeclaredExecGroup::class.java,
                        "exec_group"
                    )
                for (group in execGroupDict.keySet()) {
                    // TODO(b/151742236): document this in the param documentation.
                    if (!StarlarkExecGroupCollection.isValidGroupName(group)) {
                        throw Starlark.errorf("Exec group name '%s' is not a valid name.", group)
                    }
                }
                builder.addExecGroups(execGroupDict, override)
            }
            if (test && !builder.hasExecGroup(DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME)) {
                builder.addExecGroups(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME,
                        DEFAULT_TEST_RUNNER_EXEC_GROUP
                    ),
                    false
                )
            }

            if (buildSetting != Starlark.NONE && cfg != Starlark.NONE) {
                throw Starlark.errorf(
                    "Build setting rules cannot use the `cfg` param to apply transitions to themselves."
                )
            }
            if (buildSetting != Starlark.NONE) {
                builder.setBuildSetting(buildSetting as BuildSetting)
            }

            var transitionFactory: TransitionFactory<RuleTransitionData?> = convertConfig(cfg)
            // Check if the rule definition needs to be updated.
            transitionFactory.visit(
                { factory ->
                    if (factory is StarlarkExposedRuleTransitionFactory) {
                        // only used for native Android transitions (platforms and feature flags)
                        factory.addToRuleFromStarlark(ruleDefinitionEnvironment, builder)
                    }
                })
            if (parent != null) {
                transitionFactory =
                    ComposingTransitionFactory.of(transitionFactory, parent.getTransitionFactory())
            }
            // Check if the transition has any Starlark code.
            val visitor = StarlarkTransitionCheckingVisitor()
            transitionFactory.visit(visitor)
            hasStarlarkDefinedTransition = hasStarlarkDefinedTransition or visitor.hasStarlarkDefinedTransition
            builder.cfg(transitionFactory)

            checkAndAddAllowlistIfNecessary(
                builder,
                ruleDefinitionEnvironment,
                dependencyResolutionRule || hasMaterializers,
                bzlFile,
                DORMANT_DEPENDENCY_ALLOWLIST_CHECKER,
                "dormant dependency",
                java.util.function.Function { env: RuleDefinitionEnvironment? ->
                    createDormantDependencyAllowlistAttribute(
                        env
                    )
                },
                DormantDependency.ALLOWLIST_ATTRIBUTE_NAME,
                DormantDependency.ALLOWLIST_LABEL
            )

            checkAndAddAllowlistIfNecessary(
                builder,
                ruleDefinitionEnvironment,
                hasStarlarkDefinedTransition,
                bzlFile,
                FUNCTION_TRANSITION_ALLOWLIST_CHECKER,
                "function-based split transition",
                java.util.function.Function { env: RuleDefinitionEnvironment? ->
                    createStarlarkFunctionTransitionAllowlistAttribute(
                        env
                    )
                },
                FunctionSplitTransitionAllowlist.ATTRIBUTE_NAME,
                FunctionSplitTransitionAllowlist.LABEL
            )

            if (dependencyResolutionRule) {
                if (!subrules.isEmpty()) {
                    throw Starlark.errorf("Rules that can be required for materializers cannot have subrules")
                }

                if (!toolchains.isEmpty()) {
                    throw Starlark.errorf(
                        "Rules that can be required for materializers cannot depend on toolchains"
                    )
                }

                if (propagatesAspects) {
                    throw Starlark.errorf(
                        "Rules that can be required for materializes cannot propagate aspects"
                    )
                }
            }

            if (!dormantAttributes.isEmpty() && !dependencyResolutionRule && !isMaterializerRule) {
                throw Starlark.errorf(
                    "Has dormant attributes (%s) but is not marked as allowed in materializers",
                    dormantAttributes.stream().map<String?>(java.util.function.Function { n: String? -> "'" + n + "'" })
                        .collect(Collectors.joining(", "))
                )
            }

            for (starlarkProvider in StarlarkAttrModule.Companion.getStarlarkProviderIdentifiers(
                providesArg,
                "provides"
            )) {
                builder.advertiseStarlarkProvider(starlarkProvider)
            }

            if (!execCompatibleWith.isEmpty()) {
                builder.addExecutionPlatformConstraints(
                    parseLabels(execCompatibleWith, labelConverter, "exec_compatible_with")
                )
            }

            Starlark.toJavaOptional<String?>(doc, String::class.java)
                .map<String?>(java.util.function.Function { docString: String? -> Starlark.trimDocString(docString) })
                .ifPresent(builder::setStarlarkDocumentation)

            return StarlarkRuleFunction(
                builder, thread.getCallerLocation(), thread.getNextIdentityToken()
            )
        }

        private fun createStarlarkFunctionTransitionAllowlistAttribute(
            env: RuleDefinitionEnvironment
        ): Attribute.Builder<Label?> {
            return attr(FunctionSplitTransitionAllowlist.ATTRIBUTE_NAME, LABEL)
                .cfg(ExecutionTransitionFactory.Companion.createFactory())
                .mandatoryBuiltinProviders(com.google.common.collect.ImmutableList.of<E?>(PackageSpecificationProvider::class.java))
                .value(env.getToolsLabel(FunctionSplitTransitionAllowlist.LABEL_STR))
        }

        private fun createDormantDependencyAllowlistAttribute(
            env: RuleDefinitionEnvironment
        ): Attribute.Builder<Label?> {
            try {
                return attr(DormantDependency.ALLOWLIST_ATTRIBUTE_NAME, LABEL)
                    .cfg(ExecutionTransitionFactory.Companion.createFactory())
                    .mandatoryBuiltinProviders(
                        com.google.common.collect.ImmutableList.of<E?>(
                            PackageSpecificationProvider::class.java
                        )
                    )
                    .setPropertyFlag("FOR_DEPENDENCY_RESOLUTION")
                    .setPropertyFlag("FOR_DEPENDENCY_RESOLUTION_EXPLICITLY_SET")
                    .value(env.getToolsLabel(DormantDependency.ALLOWLIST_LABEL_STR))
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkAndAddAllowlistIfNecessary(
            builder: RuleClass.Builder,
            ruleDefinitionEnvironment: RuleDefinitionEnvironment?,
            usesFunctionality: Boolean,
            bzlFileLabel: Label,
            allowlistChecker: AllowlistChecker?,
            description: String?,
            attributeFactory: java.util.function.Function<RuleDefinitionEnvironment?, Attribute.Builder<Label?>?>,
            attributeName: String?,
            label: Label
        ) {
            var hasAllowlist = false
            // Check for existence of the allowlist attribute.
            if (builder.contains(attributeName)) {
                val attr: Attribute = builder.getAttribute(attributeName)
                if (!BuildType.isLabelType(attr.getType())) {
                    throw Starlark.errorf(
                        "%s attribute must be a label type", Attribute.getStarlarkName(attributeName)
                    )
                }
                if (attr.getDefaultValueUnchecked() == null) {
                    throw Starlark.errorf(
                        "%s attribute must have a default value", Attribute.getStarlarkName(attributeName)
                    )
                }
                val defaultLabel: Label = attr.getDefaultValueUnchecked() as Label
                // Check the label value for package and target name, to make sure this works properly
                // in Bazel where it is expected to be found under @bazel_tools.
                if (!(defaultLabel.getPackageName().equals(label.getPackageName())
                            && defaultLabel.getName().equals(label.getName()))
                ) {
                    throw Starlark.errorf(
                        "%s attribute (%s) does not have the expected value %s",
                        Attribute.getStarlarkName(attributeName), defaultLabel, label
                    )
                }
                hasAllowlist = true
            }
            if (usesFunctionality) {
                if (!bzlFileLabel.getRepository().getName().equals("_builtins")) {
                    if (!hasAllowlist) {
                        // add the allowlist automatically
                        builder.add(attributeFactory.apply(ruleDefinitionEnvironment))
                    }
                    builder.addAllowlistChecker(allowlistChecker)
                }
            } else {
                if (hasAllowlist) {
                    throw Starlark.errorf(
                        "Unused %s allowlist: %s %s",
                        description, builder.getRuleDefinitionEnvironmentLabel(), builder.getType()
                    )
                }
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun convertConfig(cfg: Any?): TransitionFactory<RuleTransitionData?> {
            if (cfg == Starlark.NONE) {
                return NoTransition.getFactory()
            }
            if (cfg is StarlarkDefinedConfigTransition) {
                // defined in Starlark via, cfg = transition
                return StarlarkRuleTransitionProvider(cfg)
            }
            if (cfg is ConfigurationTransitionApi) {
                // Every ConfigurationTransitionApi must be a TransitionFactory instance to be usable.
                if (cfg is TransitionFactory<*>) {
                    if (cta.transitionType().isCompatibleWith(TransitionType.RULE)) {
                        val ruleTransition: TransitionFactory<RuleTransitionData?> =
                            cta as TransitionFactory<RuleTransitionData?>
                        return ruleTransition
                    }
                } else {
                    throw java.lang.IllegalStateException(
                        "Every ConfigurationTransitionApi must be a TransitionFactory instance"
                    )
                }
            }
            throw Starlark.errorf(
                "`cfg` must be set to a transition object initialized by the transition() function."
            )
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkAttributeName(name: String?) {
            if (!net.starlark.java.syntax.Identifier.isValid(name)) {
                throw Starlark.errorf("attribute name `%s` is not a valid identifier.", name)
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun attrObjectToAttributesList(attrs: Dict<*, *>?): com.google.common.collect.ImmutableList<Pair<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>> {
            val attributes: com.google.common.collect.ImmutableList.Builder<Pair<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>?> =
                com.google.common.collect.ImmutableList.builder<Pair<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>?>()

            for (attr in Dict.cast<String?, com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor?>(
                attrs,
                String::class.java,
                com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor::class.java,
                "attrs"
            ).entrySet()) {
                val attrDescriptor: com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor =
                    attr.getValue()
                val source: AttributeValueSource = attrDescriptor.getValueSource()
                checkAttributeName(attr.getKey())
                val attrName: String? = source.convertToNativeName(attr.getKey())
                attributes.add(Pair.of(attrName, attrDescriptor))
            }
            return attributes.build()
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun parseLabels(
            inputs: net.starlark.java.eval.Sequence<*>, labelConverter: LabelConverter, attributeName: String?
        ): com.google.common.collect.ImmutableSet<Label?> {
            if (inputs.isEmpty()) {
                return com.google.common.collect.ImmutableSet.of<Label?>()
            }
            val parsedLabels: com.google.common.collect.ImmutableSet.Builder<Label?> =
                com.google.common.collect.ImmutableSet.Builder<Label?>()
            for (input in net.starlark.java.eval.Sequence.cast<String?>(inputs, String::class.java, attributeName)) {
                try {
                    val label: Label? = labelConverter.convert(input)
                    parsedLabels.add(label)
                } catch (e: LabelSyntaxException) {
                    throw Starlark.errorf(
                        "Unable to parse label '%s' in attribute '%s': %s",
                        input, attributeName, e.getMessage()
                    )
                }
            }
            return parsedLabels.build()
        }

        private fun getLegacyAnyTypeAttrs(ruleClass: RuleClass): com.google.common.collect.ImmutableSet<String?> {
            val attr: Attribute? =
                ruleClass.getAttributeProvider().getAttributeByNameMaybe("\$legacy_any_type_attrs")
            if (attr == null || attr.getType() !== STRING_LIST || (attr.getDefaultValueUnchecked() !is MutableList<*>)) {
                return com.google.common.collect.ImmutableSet.of<String?>()
            }
            return com.google.common.collect.ImmutableSet.copyOf(STRING_LIST.cast(attr.getDefaultValueUnchecked()))
        }

        @SerializationConstant
        val FUNCTION_TRANSITION_ALLOWLIST_CHECKER: AllowlistChecker? = AllowlistChecker.builder()
            .setAllowlistAttr(FunctionSplitTransitionAllowlist.NAME)
            .setErrorMessage("Non-allowlisted use of Starlark transition")
            .setLocationCheck(AllowlistChecker.LocationCheck.INSTANCE_OR_DEFINITION)
            .build()

        @SerializationConstant
        val DORMANT_DEPENDENCY_ALLOWLIST_CHECKER: AllowlistChecker? = AllowlistChecker.builder()
            .setAllowlistAttr(DormantDependency.NAME)
            .setErrorMessage("Non-allowlisted use of dormant dependencies")
            .setLocationCheck(AllowlistChecker.LocationCheck.DEFINITION)
            .build()

        @SerializationConstant
        val EXTEND_RULE_ALLOWLIST_CHECKER: AllowlistChecker? = AllowlistChecker.builder()
            .setAllowlistAttr("extend_rule")
            .setErrorMessage("Non-allowlisted attempt to extend a rule.")
            .setLocationCheck(AllowlistChecker.LocationCheck.DEFINITION)
            .build()

        @SerializationConstant
        val EXTEND_RULE_API_ALLOWLIST_CHECKER: AllowlistChecker? = AllowlistChecker.builder()
            .setAllowlistAttr("extend_rule_api")
            .setErrorMessage("Non-allowlisted attempt to use extend rule APIs.")
            .setLocationCheck(AllowlistChecker.LocationCheck.DEFINITION)
            .build()

        @SerializationConstant
        val INITIALIZER_ALLOWLIST_CHECKER: AllowlistChecker? = AllowlistChecker.builder()
            .setAllowlistAttr("initializer")
            .setErrorMessage("Non-allowlisted attempt to use initializer.")
            .setLocationCheck(AllowlistChecker.LocationCheck.DEFINITION)
            .build()

        @SerializationConstant
        val SUBRULES_ALLOWLIST_CHECKER: AllowlistChecker? = AllowlistChecker.builder()
            .setAllowlistAttr("subrules")
            .setErrorMessage("Non-allowlisted attempt to use subrules.")
            .setLocationCheck(AllowlistChecker.LocationCheck.DEFINITION)
            .build()

        @SerializationConstant
        val SKIP_VALIDATIONS_ALLOWLIST_CHECKER: AllowlistChecker? = AllowlistChecker.builder()
            .setAllowlistAttr("skip_validations")
            .setErrorMessage("Non-allowlisted use of skip_validations")
            .setLocationCheck(LocationCheck.DEFINITION)
            .build()

        @SerializationConstant
        val MATERIALIZER_RULE_ALLOWLIST_CHECKER: AllowlistChecker? = AllowlistChecker.builder()
            .setAllowlistAttr("materializer_rule")
            .setErrorMessage("Non-allowlisted use of materializer rule")
            .setLocationCheck(LocationCheck.DEFINITION)
            .build()

        @SerializationConstant
        val MATERIALIZER_RULE_REAL_DEPS_ALLOWLIST_CHECKER: AllowlistChecker? = AllowlistChecker.builder()
            .setAllowlistAttr("materializer_rule_real_deps")
            .setErrorMessage("Non-allowlisted use of real deps in materializer target")
            .setLocationCheck(LocationCheck.DEFINITION)
            .build()

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun parseToolchainTypes(
            rawToolchains: net.starlark.java.eval.Sequence<*>, labelConverter: LabelConverter
        ): com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?> {
            val toolchainTypes: MutableMap<Label?, ToolchainTypeRequirement?> =
                LinkedHashMap<Label?, ToolchainTypeRequirement?>()

            for (rawToolchain in rawToolchains) {
                var toolchainType: ToolchainTypeRequirement = parseToolchainType(rawToolchain, labelConverter)
                val typeLabel: Label? = toolchainType.toolchainType()
                val previous: ToolchainTypeRequirement? = toolchainTypes.get(typeLabel)
                if (previous != null) {
                    // Keep the one with the strictest requirements.
                    toolchainType = ToolchainTypeRequirement.strictest(previous, toolchainType)
                }
                toolchainTypes.put(typeLabel, toolchainType)
            }

            return com.google.common.collect.ImmutableSet.copyOf<ToolchainTypeRequirement?>(toolchainTypes.values())
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun parseToolchainType(
            rawToolchain: Any, labelConverter: LabelConverter
        ): ToolchainTypeRequirement {
            // Handle actual ToolchainTypeRequirement objects.
            if (rawToolchain is ToolchainTypeRequirement) {
                return rawToolchain as ToolchainTypeRequirement
            }

            // Handle Label-like objects.
            var toolchainLabel: Label? = null
            if (rawToolchain is Label) {
                toolchainLabel = rawToolchain as Label?
            } else if (rawToolchain is String) {
                try {
                    toolchainLabel = labelConverter.convert(rawToolchain)
                } catch (e: LabelSyntaxException) {
                    throw Starlark.errorf(
                        "Unable to parse toolchain_type label '%s': %s", rawToolchain, e.getMessage()
                    )
                }
            }

            if (toolchainLabel != null) {
                return ToolchainTypeRequirement.builder(toolchainLabel).mandatory(true).build()
            }

            // It's not a valid type.
            throw Starlark.errorf(
                "'toolchains' takes a toolchain_type, Label, or String, but instead got a %s",
                rawToolchain.getClass().getSimpleName()
            )
        }
    }
}
