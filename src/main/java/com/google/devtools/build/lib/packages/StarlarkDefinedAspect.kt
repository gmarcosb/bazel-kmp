// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement

/** A Starlark value that is a result of an 'aspect(..)' function call.  */
class StarlarkDefinedAspect(
    implementation: net.starlark.java.eval.StarlarkCallable,
    documentation: java.util.Optional<String?>,
    attributeAspects: AspectPropagationEdgesSupplier<String?>?,
    toolchainsAspects: AspectPropagationEdgesSupplier<Label?>?,
    attributes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute>,
    requiredProviders: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?>,
    requiredAspectProviders: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?>,
    provides: com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier>,
    paramAttributes: com.google.common.collect.ImmutableSet<String?>,
    requiredAspects: com.google.common.collect.ImmutableSet<StarlarkAspect>,
    propagationPredicate: AspectPropagationPredicate?,
    fragments: com.google.common.collect.ImmutableSet<String?>?,
    toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>?,
    applyToGeneratingRules: Boolean,
    execCompatibleWith: com.google.common.collect.ImmutableSet<Label?>?,
    execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>?,
    subrules: com.google.common.collect.ImmutableSet<out StarlarkSubruleApi?>?,
    identityToken: net.starlark.java.eval.SymbolGenerator.Symbol<BzlLoadValue.Key?>?
) : StarlarkExportable, StarlarkAspect {
    private val implementation: net.starlark.java.eval.StarlarkCallable

    // @Nullable rather than Optional for the sake of serialization.
    private val documentation: String?

    // Supplier of the attributes to which the aspect will propagate.
    private val attributeAspects: AspectPropagationEdgesSupplier<String?>?

    // Supplier of the toolchains types for which the aspect will propagate to matching resolved
    // toolchains.
    private val toolchainsAspects: AspectPropagationEdgesSupplier<Label?>?

    private val attributes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute>
    private val requiredProviders: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?>
    private val requiredAspectProviders: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?>
    private val provides: com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier>

    /** Aspect attributes that are required to be specified by rules propagating this aspect.  */
    private val paramAttributes: com.google.common.collect.ImmutableSet<String?>

    private val requiredAspects: com.google.common.collect.ImmutableSet<StarlarkAspect>
    private val propagationPredicate: AspectPropagationPredicate?
    private val fragments: com.google.common.collect.ImmutableSet<String?>?
    private val toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>?
    private val applyToGeneratingRules: Boolean
    private val execCompatibleWith: com.google.common.collect.ImmutableSet<Label?>?
    private val execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>?
    private val subrules: com.google.common.collect.ImmutableSet<out StarlarkSubruleApi?>?

    /** [Symbol] before [.export] and a [StarlarkAspectClass] after.  */
    private var aspectClassOrIdentityToken: Any?

    fun getImplementation(): net.starlark.java.eval.StarlarkCallable {
        return implementation
    }

    /**
     * Returns the value of the doc parameter passed to aspect() Starlark builtin, or an empty
     * Optional if a doc string was not provided.
     */
    fun getDocumentation(): java.util.Optional<String?> {
        return java.util.Optional.ofNullable<String?>(documentation)
    }

    /** Returns the supplier of the attributes to which the aspect will propagate.  */
    fun getAttributeAspects(): AspectPropagationEdgesSupplier<String?>? {
        return attributeAspects
    }

    /**
     * Returns the supplier of the toolchain types to which resolved toolchains the aspect can
     * propagate.
     */
    @com.google.common.annotations.VisibleForTesting
    fun getToolchainsAspects(): AspectPropagationEdgesSupplier<Label?>? {
        return toolchainsAspects
    }

    fun getAttributes(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute> {
        return attributes
    }

    override fun isImmutable(): Boolean {
        return implementation.isImmutable()
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("<aspect>")
    }

    override fun getName(): String? {
        return getAspectClass().getName()
    }

    override fun getAspectClass(): StarlarkAspectClass {
        com.google.common.base.Preconditions.checkState(isExported())
        return aspectClassOrIdentityToken as StarlarkAspectClass
    }

    override fun getParamAttributes(): com.google.common.collect.ImmutableSet<String?> {
        return paramAttributes
    }

    // TODO(bazel-team): use exportedLocation as the callable symbol's location.
    override fun export(
        handler: EventHandler?,
        extensionLabel: Label?,
        name: String?,
        exportedLocation: net.starlark.java.syntax.Location?
    ) {
        com.google.common.base.Preconditions.checkArgument(!isExported())
        val identityToken: net.starlark.java.eval.SymbolGenerator.Symbol<BzlLoadValue.Key> =
            aspectClassOrIdentityToken as net.starlark.java.eval.SymbolGenerator.Symbol<BzlLoadValue.Key>
        val owner: BzlLoadValue.Key = identityToken.getOwner()
        checkArgument(
            owner.getLabel().equals(extensionLabel),
            "Exporting aspect as (%s, %s) but label did not match owner=%s",
            extensionLabel,
            name,
            owner
        )
        this.aspectClassOrIdentityToken = StarlarkAspectClass(owner, name)
    }

    /**
     * The `AspectDefinition` is a function of the aspect's parameters, so we can cache
     * that.
     * 
     * 
     * Parameters of Starlark aspects are combinatorially limited (only bool, int and enum types).
     * Using strong keys possibly results in a small memory leak. Weak keys don't work because
     * reference equality is used and AspectParameters are created per target.
     */
    @Transient
    private var definitionCache: com.github.benmanes.caffeine.cache.LoadingCache<AspectParameters?, AspectDefinition?>? =
        Caffeine.newBuilder()
            .build<AspectParameters?, AspectDefinition?>(com.github.benmanes.caffeine.cache.CacheLoader { aspectParams: AspectParameters? ->
                this.buildDefinition(aspectParams)
            })

    init {
        this.implementation = implementation
        this.documentation = documentation.orElse(null)
        this.attributeAspects = attributeAspects
        this.toolchainsAspects = toolchainsAspects
        this.attributes = attributes
        this.requiredProviders = requiredProviders
        this.requiredAspectProviders = requiredAspectProviders
        this.provides = provides
        this.paramAttributes = paramAttributes
        this.requiredAspects = requiredAspects
        this.propagationPredicate = propagationPredicate
        this.fragments = fragments
        this.toolchainTypes = toolchainTypes
        this.applyToGeneratingRules = applyToGeneratingRules
        this.execCompatibleWith = execCompatibleWith
        this.execGroups = execGroups
        this.subrules = subrules
        this.aspectClassOrIdentityToken = identityToken
    }

    fun getDefinition(aspectParams: AspectParameters?): AspectDefinition? {
        if (definitionCache == null) {
            definitionCache = Caffeine.newBuilder()
                .build<AspectParameters?, AspectDefinition?>(com.github.benmanes.caffeine.cache.CacheLoader { aspectParams: AspectParameters? ->
                    this.buildDefinition(aspectParams)
                })
        }
        return definitionCache.get(aspectParams)
    }

    private fun buildDefinition(aspectParams: AspectParameters): AspectDefinition {
        val builder: com.google.devtools.build.lib.packages.AspectDefinition.Builder =
            com.google.devtools.build.lib.packages.AspectDefinition.Builder(aspectClassOrIdentityToken as StarlarkAspectClass?)
        builder.propagateToAttributes(attributeAspects)
        builder.propagateToToolchainsTypes(toolchainsAspects)

        for (attribute in attributes) {
            var attr: com.google.devtools.build.lib.packages.Attribute = attribute // Might be reassigned.
            if (!aspectParams.getAttribute(attr.getName()).isEmpty()) {
                val attrType: com.google.devtools.build.lib.packages.Type<*> = attr.getType()
                val attrName: String = attr.getName()
                val attrValue: String = aspectParams.getOnlyValueOfAttribute(attrName)
                com.google.common.base.Preconditions.checkState(
                    !com.google.devtools.build.lib.packages.Attribute.Companion.isImplicit(
                        attrName
                    )
                )
                com.google.common.base.Preconditions.checkState(
                    attrType === com.google.devtools.build.lib.packages.Type.Companion.STRING || attrType === com.google.devtools.build.lib.packages.Type.Companion.INTEGER || attrType === com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN
                )
                com.google.common.base.Preconditions.checkArgument(
                    aspectParams.getAttribute(attrName).size() == 1,
                    "Aspect %s parameter %s has %s values (must have exactly 1).",
                    getName(),
                    attrName,
                    aspectParams.getAttribute(attrName).size()
                )

                attr = addAttrValue(attr, attrValue)
            }
            builder.add(attr)
        }
        builder.requireStarlarkProviderSets(requiredProviders)
        builder.requireAspectsWithProviders(requiredAspectProviders)
        val advertisedStarlarkProviders: com.google.common.collect.ImmutableList.Builder<StarlarkProviderIdentifier?> =
            com.google.common.collect.ImmutableList.builder<StarlarkProviderIdentifier?>()
        for (provider in provides) {
            advertisedStarlarkProviders.add(provider)
        }
        builder.advertiseProvider(advertisedStarlarkProviders.build())
        builder.requiresConfigurationFragmentsByStarlarkBuiltinName(fragments)
        builder.addToolchainTypes(toolchainTypes)
        builder.applyToGeneratingRules(applyToGeneratingRules)
        val requiredAspectsClasses: com.google.common.collect.ImmutableSet.Builder<AspectClass?> =
            com.google.common.collect.ImmutableSet.builder<AspectClass?>()
        for (requiredAspect in requiredAspects) {
            requiredAspectsClasses.add(requiredAspect.getAspectClass())
        }
        builder.requiredAspectClasses(requiredAspectsClasses.build())
        builder.propagationPredicate(propagationPredicate)
        builder.execCompatibleWith(execCompatibleWith)
        builder.execGroups(execGroups)
        builder.subrules(subrules)
        return builder.build()
    }

    override fun isExported(): Boolean {
        return aspectClassOrIdentityToken is StarlarkAspectClass
    }

    override fun getDefaultParametersExtractor(): com.google.common.base.Function<com.google.devtools.build.lib.packages.Rule?, AspectParameters?> {
        return com.google.common.base.Function { rule: com.google.devtools.build.lib.packages.Rule? ->
            val ruleAttrs: com.google.devtools.build.lib.packages.AttributeMap = RawAttributeMapper.Companion.of(rule)
            val builder: com.google.devtools.build.lib.packages.AspectParameters.Builder =
                com.google.devtools.build.lib.packages.AspectParameters.Builder()
            for (aspectAttr in attributes) {
                val param: String = aspectAttr.getName()
                if (com.google.devtools.build.lib.packages.Attribute.Companion.isImplicit(param) || com.google.devtools.build.lib.packages.Attribute.Companion.isAnalysisDependent(
                        param
                    )
                ) {
                    // These attributes are the private matters of the aspect
                    continue
                }

                val ruleAttr: com.google.devtools.build.lib.packages.Attribute? =
                    ruleAttrs.getAttributeDefinition(param)
                if (paramAttributes.contains(aspectAttr.getName())) {
                    // These are preconditions because if they are false, RuleFunction.call() should
                    // already have generated an error.
                    com.google.common.base.Preconditions.checkArgument(
                        ruleAttr != null,
                        "Cannot apply aspect %s to %s that does not define attribute '%s'.",
                        getName(),
                        rule.getTargetKind(),
                        param
                    )
                    com.google.common.base.Preconditions.checkArgument(
                        ruleAttr.getType() === com.google.devtools.build.lib.packages.Type.Companion.STRING || ruleAttr.getType() === com.google.devtools.build.lib.packages.Type.Companion.INTEGER || ruleAttr.getType() === com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN,
                        "Cannot apply aspect %s to %s since attribute '%s' is not boolean, integer, nor"
                                + " string.",
                        getName(),
                        rule.getTargetKind(),
                        param
                    )
                }

                if (ruleAttr != null && ruleAttr.getType() === aspectAttr.getType()) {
                    // If the attribute has a select() (which aspect attributes don't yet support), the
                    // error gets reported in RuleClass.checkAspectAllowedValues.
                    if (!ruleAttrs.isConfigurable(param)) {
                        builder.addAttribute(param, ruleAttrs.get(param, ruleAttr.getType()).toString())
                    }
                }
            }
            builder.build()
        } as com.google.common.base.Function<com.google.devtools.build.lib.packages.Rule?, AspectParameters?>
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    fun extractTopLevelParameters(parametersValues: com.google.common.collect.ImmutableMap<String?, String?>): AspectParameters? {
        val builder: com.google.devtools.build.lib.packages.AspectParameters.Builder =
            com.google.devtools.build.lib.packages.AspectParameters.Builder()
        for (aspectParameter in attributes) {
            val parameterName: String = aspectParameter.getName()
            val parameterType: com.google.devtools.build.lib.packages.Type<*> = aspectParameter.getType()

            if (com.google.devtools.build.lib.packages.Attribute.Companion.isImplicit(parameterName) || com.google.devtools.build.lib.packages.Attribute.Companion.isAnalysisDependent(
                    parameterName
                )
            ) {
                // These attributes are the private matters of the aspect
                continue
            }

            com.google.common.base.Preconditions.checkArgument(
                parameterType === com.google.devtools.build.lib.packages.Type.Companion.STRING || parameterType === com.google.devtools.build.lib.packages.Type.Companion.INTEGER || parameterType === com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN,
                "Aspect %s: Cannot pass value of attribute '%s' of type %s, only 'boolean', 'int' and"
                        + " 'string' attributes are allowed.",
                getName(),
                parameterName,
                parameterType
            )

            val parameterValue: String? =
                parametersValues.getOrDefault(
                    parameterName, parameterType.cast(aspectParameter.getDefaultValue(null)).toString()
                )

            var castedParameterValue: Any? = parameterValue
            // Validate integer and boolean parameters values
            if (parameterType === com.google.devtools.build.lib.packages.Type.Companion.INTEGER) {
                castedParameterValue = parseIntParameter(parameterName, parameterValue!!)
            } else if (parameterType === com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN) {
                castedParameterValue = parseBooleanParameter(parameterName, parameterValue!!)
            }

            if (aspectParameter.checkAllowedValues()) {
                val allowedValues: PredicateWithMessage<Any?> = aspectParameter.getAllowedValues()
                if (!allowedValues.apply(castedParameterValue)) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "%s: invalid value in '%s' attribute: %s",
                        getName(), parameterName, allowedValues.getErrorReason(castedParameterValue)
                    )
                }
            }
            builder.addAttribute(parameterName, castedParameterValue.toString())
        }
        return builder.build()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun parseIntParameter(name: String?, value: String): net.starlark.java.eval.StarlarkInt? {
        try {
            return net.starlark.java.eval.StarlarkInt.parse(value,  /*base=*/0)
        } catch (e: java.lang.NumberFormatException) {
            throw net.starlark.java.eval.EvalException(
                java.lang.String.format(
                    "%s: expected value of type 'int' for attribute '%s' but got '%s'",
                    getName(), name, value
                ),
                e
            )
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun parseBooleanParameter(name: String?, value: String): Boolean {
        var value = value
        value = com.google.common.base.Ascii.toLowerCase(value)
        if (TRUE_REPS.contains(value)) {
            return true
        }
        if (FALSE_REPS.contains(value)) {
            return false
        }
        throw net.starlark.java.eval.Starlark.errorf(
            "%s: expected value of type 'bool' for attribute '%s' but got '%s'",
            getName(), name, value
        )
    }

    fun getToolchainTypes(): com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>? {
        return toolchainTypes
    }

    fun getRequiredProviders(): com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?> {
        return requiredProviders
    }

    fun getRequiredAspects(): com.google.common.collect.ImmutableSet<StarlarkAspect> {
        return requiredAspects
    }

    fun getPropagationPredicate(): AspectPropagationPredicate? {
        return propagationPredicate
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || getClass() != o.getClass()) {
            return false
        }
        val that = o as StarlarkDefinedAspect
        return implementation == that.implementation
                && attributeAspects == that.attributeAspects
                && toolchainsAspects == that.toolchainsAspects
                && attributes == that.attributes
                && requiredProviders == that.requiredProviders
                && requiredAspectProviders == that.requiredAspectProviders
                && provides == that.provides
                && paramAttributes == that.paramAttributes
                && requiredAspects == that.requiredAspects
                && propagationPredicate == that.propagationPredicate
                && fragments == that.fragments
                && toolchainTypes == that.toolchainTypes
                && aspectClassOrIdentityToken == that.aspectClassOrIdentityToken
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(
            implementation,
            attributeAspects,
            toolchainsAspects,
            attributes,
            requiredProviders,
            requiredAspectProviders,
            provides,
            paramAttributes,
            requiredAspects,
            propagationPredicate,
            fragments,
            toolchainTypes,
            aspectClassOrIdentityToken
        )
    }

    @com.google.errorprone.annotations.Keep // used reflectively
    private class Codec : AbstractExportedStarlarkSymbolCodec<StarlarkDefinedAspect?>() {
        override fun getEncodedClass(): java.lang.Class<StarlarkDefinedAspect?> {
            return StarlarkDefinedAspect::class.java
        }

        override fun getBzlLoadKey(obj: StarlarkDefinedAspect): BzlLoadValue.Key {
            return obj.getAspectClass().getExtensionKey()
        }

        override fun getExportedName(obj: StarlarkDefinedAspect): String? {
            return obj.getAspectClass().getExportedName()
        }
    }

    companion object {
        private val TRUE_REPS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("true", "1", "yes", "t", "y")

        private val FALSE_REPS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("false", "0", "no", "f", "n")

        private fun addAttrValue(
            attr: com.google.devtools.build.lib.packages.Attribute,
            attrValue: String
        ): com.google.devtools.build.lib.packages.Attribute {
            val attrBuilder: com.google.devtools.build.lib.packages.Attribute.Builder<*>
            val attrType: com.google.devtools.build.lib.packages.Type<*> = attr.getType()
            var castedValue: Any? = attrValue

            if (attrType === com.google.devtools.build.lib.packages.Type.Companion.INTEGER) {
                castedValue = net.starlark.java.eval.StarlarkInt.parse(attrValue,  /*base=*/0)
                attrBuilder =
                    attr.cloneBuilder<net.starlark.java.eval.StarlarkInt?>(com.google.devtools.build.lib.packages.Type.Companion.INTEGER)
                        .value(castedValue as net.starlark.java.eval.StarlarkInt?)
            } else if (attrType === com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN) {
                castedValue = java.lang.Boolean.parseBoolean(attrValue)
                attrBuilder = attr.cloneBuilder<Boolean?>(com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN)
                    .value(castedValue as Boolean)
            } else {
                attrBuilder = attr.cloneBuilder<String?>(com.google.devtools.build.lib.packages.Type.Companion.STRING)
                    .value(castedValue as String?)
            }

            if (!attr.checkAllowedValues()) {
                // The aspect attribute can have no allowed values constraint if the aspect is used from
                // command-line. However, AspectDefinition.Builder$add requires the existence of allowed
                // values in all aspects string attributes for both native and starlark aspects.
                // Therefore, allowedValues list is added here with only the current value of the attribute.
                return attrBuilder
                    .allowedValues(AllowedValueSet(attrType.cast(castedValue)))
                    .build(attr.getName())
            } else {
                return attrBuilder.build(attr.getName())
            }
        }
    }
}
