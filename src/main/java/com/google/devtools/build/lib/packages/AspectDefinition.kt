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

import com.google.devtools.build.lib.analysis.config.Fragment

/**
 * The definition of an aspect (see [Aspect] for more information).
 * 
 * 
 * Contains enough information to build up the configured target graph except for the actual way
 * to build the Skyframe node (that is the territory of [ AspectFactory][com.google.devtools.build.lib.view]). In particular:
 * 
 * 
 *  * The condition that must be fulfilled for an aspect to be able to operate on a configured
 * target
 *  * The (implicit or late-bound) attributes of the aspect that denote dependencies the aspect
 * itself needs (e.g. runtime libraries for a new language for protocol buffers)
 *  * The aspects this aspect requires from its direct dependencies
 * 
 * 
 * 
 * The way to build the Skyframe node is not here because this data needs to be accessible from
 * the `.packages` package and that one requires references to the `.view` package.
 */
@Immutable
class AspectDefinition private constructor(
    aspectClass: AspectClass,
    advertisedProviders: AdvertisedProviderSet?,
    requiredProviders: RequiredProviders,
    requiredProvidersForAspects: RequiredProviders?,
    attributes: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.packages.Attribute?>,
    toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>?,
    restrictToAttributes: AspectPropagationEdgesSupplier<String?>?,
    propagateToToolchainsTypes: AspectPropagationEdgesSupplier<Label?>?,
    configurationFragmentPolicy: ConfigurationFragmentPolicy?,
    applyToFiles: Boolean,
    applyToGeneratingRules: Boolean,
    requiredAspectClasses: com.google.common.collect.ImmutableSet<AspectClass?>,
    propagationPredicate: AspectPropagationPredicate?,
    execCompatibleWith: com.google.common.collect.ImmutableSet<Label?>?,
    execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>?,
    subrules: com.google.common.collect.ImmutableSet<out StarlarkSubruleApi?>?
) {
    private val aspectClass: AspectClass
    private val advertisedProviders: AdvertisedProviderSet?
    private val requiredProviders: RequiredProviders
    private val requiredProvidersForAspects: RequiredProviders?
    private val attributes: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.packages.Attribute?>
    private val toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>?

    /** A supplier of the attributes to which the aspect will propagate.  */
    private val restrictToAttributes: AspectPropagationEdgesSupplier<String?>?

    /**
     * A supplier of the toolchains types for which the aspect will propagate to matching resolved
     * toolchains.
     */
    private val propagateToToolchainsTypes: AspectPropagationEdgesSupplier<Label?>?

    private val configurationFragmentPolicy: ConfigurationFragmentPolicy?
    private val applyToFiles: Boolean
    private val applyToGeneratingRules: Boolean

    private val requiredAspectClasses: com.google.common.collect.ImmutableSet<AspectClass?>

    private val propagationPredicate: AspectPropagationPredicate?

    private val execCompatibleWith: com.google.common.collect.ImmutableSet<Label?>?
    private val execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>?
    private val subrules: com.google.common.collect.ImmutableSet<out StarlarkSubruleApi?>?

    fun getAdvertisedProviders(): AdvertisedProviderSet? {
        return advertisedProviders
    }

    init {
        this.aspectClass = aspectClass
        this.advertisedProviders = advertisedProviders
        this.requiredProviders = requiredProviders
        this.requiredProvidersForAspects = requiredProvidersForAspects
        this.attributes = attributes
        this.toolchainTypes = toolchainTypes
        this.restrictToAttributes = restrictToAttributes
        this.propagateToToolchainsTypes = propagateToToolchainsTypes
        this.configurationFragmentPolicy = configurationFragmentPolicy
        this.applyToFiles = applyToFiles
        this.applyToGeneratingRules = applyToGeneratingRules
        this.requiredAspectClasses = requiredAspectClasses
        this.propagationPredicate = propagationPredicate
        this.execCompatibleWith = execCompatibleWith
        this.execGroups = execGroups
        this.subrules = subrules
    }

    fun getName(): String? {
        return aspectClass.getName()
    }

    /**
     * Returns the attributes of the aspect in the form of a String -&gt; [Attribute] map.
     * 
     * 
     * All attributes are either implicit or late-bound.
     */
    fun getAttributes(): com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.packages.Attribute?> {
        return attributes
    }

    /** Returns the required toolchains declared by this aspect.  */
    fun getToolchainTypes(): com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>? {
        return toolchainTypes
    }

    /**
     * Returns the constraint values that must be present on an execution platform for this aspect.
     */
    fun execCompatibleWith(): com.google.common.collect.ImmutableSet<Label?>? {
        return execCompatibleWith
    }

    /** Returns the execution groups that this aspect can use when creating actions.  */
    fun execGroups(): com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>? {
        return execGroups
    }

    /** Returns the subrules declared by this aspect.  */
    fun getSubrules(): com.google.common.collect.ImmutableSet<out StarlarkSubruleApi?>? {
        return subrules
    }

    /**
     * Returns [RequiredProviders] that a configured target must have so that this aspect can be
     * applied to it.
     * 
     * 
     * If a configured target does not satisfy required providers, the aspect is silently not
     * created for it.
     */
    fun getRequiredProviders(): RequiredProviders {
        return requiredProviders
    }

    /**
     * Aspects do not depend on other aspects applied to the same target *unless* the other
     * aspect satisfies the [RequiredProviders] this method returns
     */
    fun getRequiredProvidersForAspects(): RequiredProviders? {
        return requiredProvidersForAspects
    }

    /** Returns the supplier of the attributes to which the aspect will propagate.  */
    fun getAttributeAspects(): AspectPropagationEdgesSupplier<String?>? {
        return restrictToAttributes
    }

    /**
     * Returns the supplier of the toolchains types for which the aspect will propagate to matching
     * resolved toolchains.
     */
    fun getToolchainsAspects(): AspectPropagationEdgesSupplier<Label?>? {
        return propagateToToolchainsTypes
    }

    /** Returns the set of configuration fragments required by this Aspect.  */
    fun getConfigurationFragmentPolicy(): ConfigurationFragmentPolicy? {
        return configurationFragmentPolicy
    }

    /**
     * Returns whether this aspect applies to (output) files.
     * 
     * 
     * Currently only supported for top-level aspects and targets, and only for output files.
     */
    fun applyToFiles(): Boolean {
        return applyToFiles
    }

    /**
     * Returns whether this aspect should, when it would be applied to an output file, instead apply
     * to the generating rule of that output file.
     */
    fun applyToGeneratingRules(): Boolean {
        return applyToGeneratingRules
    }

    /** Checks if the given `maybeRequiredAspect` is required by this aspect definition  */
    fun requires(maybeRequiredAspect: Aspect): Boolean {
        return requiredAspectClasses.contains(maybeRequiredAspect.getAspectClass())
    }

    fun getPropagationPredicate(): AspectPropagationPredicate? {
        return propagationPredicate
    }

    /** Builder class for [AspectDefinition].  */
    class Builder(aspectClass: AspectClass) {
        private val aspectClass: AspectClass
        private val attributes: MutableMap<String?, com.google.devtools.build.lib.packages.Attribute?> =
            LinkedHashMap<String?, com.google.devtools.build.lib.packages.Attribute?>()
        private val advertisedProviders: com.google.devtools.build.lib.packages.AdvertisedProviderSet.Builder =
            AdvertisedProviderSet.Companion.builder()
        private val requiredProviders: com.google.devtools.build.lib.packages.RequiredProviders.Builder =
            RequiredProviders.Companion.acceptAnyBuilder()
        private val requiredAspectProviders: com.google.devtools.build.lib.packages.RequiredProviders.Builder =
            RequiredProviders.Companion.acceptNoneBuilder()
        private var propagateAlongAttributes: AspectPropagationEdgesSupplier<String?>? =
            AspectPropagationEdgesSupplier.Companion.DEFAULT_ATTR_ASPECTS_SUPPLIER
        private var propagateToToolchainsTypes: AspectPropagationEdgesSupplier<Label?>? =
            AspectPropagationEdgesSupplier.Companion.DEFAULT_TOOLCHAINS_ASPECTS_SUPPLIER
        private val configurationFragmentPolicy: com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy.Builder =
            com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy.Builder()
        private var applyToFiles = false
        private var applyToGeneratingRules = false
        private val toolchainTypes: MutableSet<ToolchainTypeRequirement?> = HashSet<ToolchainTypeRequirement?>()
        private var requiredAspectClasses: com.google.common.collect.ImmutableSet<AspectClass?> =
            com.google.common.collect.ImmutableSet.of<AspectClass?>()
        private var propagationPredicate: AspectPropagationPredicate? = null
        private var execCompatibleWith: com.google.common.collect.ImmutableSet<Label?>? =
            com.google.common.collect.ImmutableSet.of<Label?>()
        private var execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>? =
            com.google.common.collect.ImmutableMap.of<String?, DeclaredExecGroup?>()
        private var subrules: com.google.common.collect.ImmutableSet<out StarlarkSubruleApi?>? =
            com.google.common.collect.ImmutableSet.of<StarlarkSubruleApi?>()

        init {
            this.aspectClass = aspectClass
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun requireProviders(requiredProviders: RequiredProviders): Builder {
            this.requireStarlarkProviderSets(requiredProviders.getStarlarkProviders())
            return this
        }

        /**
         * Asserts that this aspect can only be evaluated for rules that supply all of the providers
         * from at least one set of required providers.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun requireStarlarkProviderSets(
            providerSets: Iterable<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>>
        ): Builder {
            for (providerSet in providerSets) {
                if (!providerSet.isEmpty()) {
                    requiredProviders.addStarlarkSet(providerSet)
                }
            }
            return this
        }

        /**
         * Asserts that this aspect can only be evaluated for rules that supply all of the specified
         * Starlark providers.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun requireStarlarkProviders(vararg starlarkProviders: StarlarkProviderIdentifier?): Builder {
            requiredProviders.addStarlarkSet(
                com.google.common.collect.ImmutableSet.copyOf<StarlarkProviderIdentifier?>(
                    starlarkProviders
                )
            )
            return this
        }

        /**
         * Asserts that this aspect requires a list of aspects to be applied before it on the configured
         * target.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun requiredAspectClasses(requiredAspectClasses: com.google.common.collect.ImmutableSet<AspectClass?>): Builder {
            this.requiredAspectClasses = requiredAspectClasses
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun propagationPredicate(propagationPredicate: AspectPropagationPredicate?): Builder {
            this.propagationPredicate = propagationPredicate
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun requireAspectsWithProviders(
            providerSets: Iterable<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>>
        ): Builder {
            for (providerSet in providerSets) {
                if (!providerSet.isEmpty()) {
                    requiredAspectProviders.addStarlarkSet(providerSet)
                }
            }
            return this
        }

        /** State that the aspect being built provides given providers.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun advertiseProvider(providers: com.google.common.collect.ImmutableList<StarlarkProviderIdentifier?>): Builder {
            for (provider in providers) {
                advertisedProviders.addStarlark(provider)
            }
            return this
        }

        /** Sets the supplier of the attributes to which the aspect will propagate.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun propagateToAttributes(attributes: AspectPropagationEdgesSupplier<String?>?): Builder {
            this.propagateAlongAttributes = attributes
            return this
        }

        /**
         * Sets the supplier of the toolchains types for which the aspect will propagate to matching
         * resolved toolchains.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun propagateToToolchainsTypes(
            toolchainsTypes: AspectPropagationEdgesSupplier<Label?>?
        ): Builder {
            this.propagateToToolchainsTypes = toolchainsTypes
            return this
        }

        /**
         * Adds an attribute to the aspect.
         */
        fun <TYPE> add(attr: com.google.devtools.build.lib.packages.Attribute.Builder<TYPE?>): Builder {
            val attribute: com.google.devtools.build.lib.packages.Attribute = attr.build()
            return add(attribute)
        }

        /**
         * Adds an attribute to the aspect.
         * 
         * 
         * Aspects attributes can be of any data type if they are not public, i.e. implicit (starting
         * with '$') or late-bound (starting with ':'). While public attributes can only be of types
         * string, integer or boolean.
         * 
         * 
         * Aspect definition currently cannot handle [ComputedDefault] dependencies (type LABEL
         * or LABEL_LIST), because all the dependencies are resolved from the aspect definition and the
         * defining rule.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun add(attribute: com.google.devtools.build.lib.packages.Attribute): Builder {
            com.google.common.base.Preconditions.checkArgument(
                attribute.isImplicit()
                        || attribute.isLateBound()
                        || (attribute.getType() === com.google.devtools.build.lib.packages.Type.Companion.STRING && attribute.checkAllowedValues())
                        || (attribute.getType() === com.google.devtools.build.lib.packages.Type.Companion.INTEGER && attribute.checkAllowedValues())
                        || attribute.getType() === com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN,
                "%s: Invalid attribute '%s' (%s)",
                aspectClass.getName(),
                attribute.getName(),
                attribute.getType()
            )

            // Attributes specifying dependencies using ComputedDefault value are currently not supported.
            // The limitation is in place because:
            //  - blaze query requires that all possible values are knowable without BuildConguration
            //  - aspects can attach to any rule
            // Current logic in #forEachLabelDepFromAllAttributesOfAspect is not enough,
            // however {Conservative,Precise}AspectResolver can probably be improved to make that work.
            com.google.common.base.Preconditions.checkArgument(
                !(attribute.getType().getLabelClass() == LabelClass.DEPENDENCY
                        && (attribute.getDefaultValueUnchecked() is ComputedDefault)),
                "%s: Invalid attribute '%s' (%s) with computed default dependencies",
                aspectClass.getName(),
                attribute.getName(),
                attribute.getType()
            )
            com.google.common.base.Preconditions.checkArgument(
                !attributes.containsKey(attribute.getName()),
                "%s: An attribute with the name '%s' already exists.",
                aspectClass.getName(),
                attribute.getName()
            )
            attributes.put(attribute.getName(), attribute)
            return this
        }

        /**
         * Declares that the implementation of the associated aspect definition requires the given
         * fragments to be present in this rule's exec and target configurations.
         * 
         * 
         * The value is inherited by subclasses.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun requiresConfigurationFragments(
            vararg configurationFragments: java.lang.Class<out Fragment?>?
        ): Builder {
            configurationFragmentPolicy.requiresConfigurationFragments(
                com.google.common.collect.ImmutableSet.copyOf<java.lang.Class<out Fragment?>?>(configurationFragments)
            )
            return this
        }

        /**
         * Declares the configuration fragments that are required by this rule for the target
         * configuration.
         * 
         * 
         * In contrast to [.requiresConfigurationFragments], this method takes the
         * Starlark module names of fragments instead of their classes.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun requiresConfigurationFragmentsByStarlarkBuiltinName(
            configurationFragmentNames: MutableCollection<String?>?
        ): Builder {
            configurationFragmentPolicy.requiresConfigurationFragmentsByStarlarkBuiltinName(
                configurationFragmentNames
            )
            return this
        }

        /**
         * Sets the policy for the case where the configuration is missing the required fragment class
         * (see [.requiresConfigurationFragments]).
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMissingFragmentPolicy(
            fragmentClass: java.lang.Class<*>?, missingFragmentPolicy: MissingFragmentPolicy?
        ): Builder {
            configurationFragmentPolicy.setMissingFragmentPolicy(fragmentClass, missingFragmentPolicy)
            return this
        }

        /**
         * Sets whether this aspect should apply to files.
         * 
         * 
         * Default is `false`. Currently only supported for top-level aspects and targets,
         * and only for output files.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun applyToFiles(propagateOverGeneratedFiles: Boolean): Builder {
            this.applyToFiles = propagateOverGeneratedFiles
            return this
        }

        /**
         * Sets whether this aspect should, when it would be applied to an output file, instead apply to
         * the generating rule of that output file.
         * 
         * 
         * Default is `false`. Currently only supported for aspects which do not have a
         * "required providers" list.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun applyToGeneratingRules(applyToGeneratingRules: Boolean): Builder {
            this.applyToGeneratingRules = applyToGeneratingRules
            return this
        }

        /** Adds the given toolchains as requirements for this aspect.  */
        fun addToolchainTypes(vararg toolchainTypes: ToolchainTypeRequirement?): Builder {
            return this.addToolchainTypes(
                com.google.common.collect.ImmutableSet.copyOf<ToolchainTypeRequirement?>(
                    toolchainTypes
                )
            )
        }

        /** Adds the given toolchains as requirements for this aspect.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addToolchainTypes(toolchainTypes: MutableCollection<ToolchainTypeRequirement?>?): Builder {
            this.toolchainTypes.addAll(toolchainTypes)
            return this
        }

        /**
         * Adds the given constraint values to the set required for execution platforms for this aspect.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun execCompatibleWith(execCompatibleWith: com.google.common.collect.ImmutableSet<Label?>?): Builder {
            this.execCompatibleWith = execCompatibleWith
            return this
        }

        /** Sets the execution groups that are available for actions created by this aspect.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun execGroups(execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>?): Builder {
            // TODO(b/230337573): validate names
            // TODO(b/230337573): handle copy_from_default
            this.execGroups = execGroups
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun subrules(subrules: com.google.common.collect.ImmutableSet<out StarlarkSubruleApi?>?): Builder {
            this.subrules = subrules
            return this
        }

        /**
         * Builds the aspect definition.
         * 
         * 
         * The builder object is reusable afterwards.
         */
        fun build(): AspectDefinition {
            val requiredProviders: RequiredProviders = this.requiredProviders.build()
            if (applyToGeneratingRules) {
                check(requiredProviders.acceptsAny()) {
                    ("An aspect cannot simultaneously have required providers "
                            + "and apply to generating rules.")
                }

                check(propagationPredicate == null) {
                    ("An aspect cannot simultaneously have a propagation predicate and apply to generating"
                            + " rules.")
                }
            }

            if (applyToFiles) {
                check(requiredProviders.acceptsAny()) { "An aspect cannot simultaneously have required providers and apply to files." }
                check(propagationPredicate == null) { "An aspect cannot simultaneously have a propagation predicate and apply to files." }
            }

            check(!(applyToFiles && !requiredProviders.acceptsAny())) { "An aspect cannot simultaneously have required providers and apply to files." }

            return AspectDefinition(
                aspectClass,
                advertisedProviders.build(),
                requiredProviders,
                requiredAspectProviders.build(),
                com.google.common.collect.ImmutableMap.copyOf<String?, com.google.devtools.build.lib.packages.Attribute?>(
                    attributes
                ),
                com.google.common.collect.ImmutableSet.copyOf<ToolchainTypeRequirement?>(toolchainTypes),
                propagateAlongAttributes,
                propagateToToolchainsTypes,
                configurationFragmentPolicy.build(),
                applyToFiles,
                applyToGeneratingRules,
                requiredAspectClasses,
                propagationPredicate,
                execCompatibleWith,
                execGroups,
                subrules
            )
        }
    }

    companion object {
        fun satisfies(aspect: Aspect, advertisedProviderSet: AdvertisedProviderSet?): Boolean {
            return aspect.getDefinition().requiredProviders.isSatisfiedBy(advertisedProviderSet)
        }

        /** Collects all attribute labels from the specified aspectDefinition.  */
        fun addAllAttributesOfAspect(
            labelBuilder: com.google.common.collect.Multimap<com.google.devtools.build.lib.packages.Attribute?, Label?>,
            aspect: Aspect,
            dependencyFilter: DependencyFilter
        ) {
            forEachLabelDepFromAllAttributesOfAspect(
                aspect,
                dependencyFilter,
                java.util.function.BiConsumer { key: com.google.devtools.build.lib.packages.Attribute?, value: Label? ->
                    labelBuilder.put(
                        key,
                        value
                    )
                })
        }

        fun forEachLabelDepFromAllAttributesOfAspect(
            aspect: Aspect,
            dependencyFilter: DependencyFilter,
            consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.packages.Attribute?, Label?>
        ) {
            val labelVisitor: com.google.devtools.build.lib.packages.Type.LabelVisitor =
                com.google.devtools.build.lib.packages.Type.LabelVisitor { label: Label?, aspectAttribute: com.google.devtools.build.lib.packages.Attribute? ->
                    if (label == null) {
                        return@LabelVisitor
                    }
                    consumer.accept(aspectAttribute, label)
                }
            for (aspectAttribute in aspect.getDefinition().attributes.values()) {
                if (!dependencyFilter.test(aspect, aspectAttribute)) {
                    continue
                }
                val type: com.google.devtools.build.lib.packages.Type<*> = aspectAttribute.getType()
                if (type.getLabelClass() != LabelClass.DEPENDENCY) {
                    continue
                }
                visitSingleAttribute(aspectAttribute, aspectAttribute.getType(), labelVisitor)
            }
        }

        private fun <T> visitSingleAttribute(
            attribute: com.google.devtools.build.lib.packages.Attribute,
            type: com.google.devtools.build.lib.packages.Type<T?>,
            labelVisitor: com.google.devtools.build.lib.packages.Type.LabelVisitor?
        ) {
            type.visitLabels(labelVisitor, type.cast(attribute.getDefaultValue(null)), attribute)
        }

        fun builder(aspectClass: AspectClass): Builder {
            return com.google.devtools.build.lib.packages.AspectDefinition.Builder(aspectClass)
        }
    }
}
