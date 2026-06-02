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

/** Provides access to the attributes of a rule or macro class.  */
class AttributeProvider internal constructor(
    attributes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute>,
    attributeIndex: MutableMap<String?, Int?>,
    nonConfigurableAttributes: com.google.common.collect.ImmutableList<String?>?,
    owner: String,
    ignoreLicenses: Boolean
) {
    /**
     * A (unordered) mapping from attribute names to small integers indexing into the `attributes` array.
     */
    private val attributeIndex: MutableMap<String?, Int?>

    /**
     * All attributes of this rule or macro class (including inherited ones) ordered by attributeIndex
     * value.
     */
    private val attributes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute>

    /**
     * Names of the non-configurable attributes of this rule or macro class. This is null for macros
     * because it isn't used.
     */
    private val nonConfigurableAttributes: com.google.common.collect.ImmutableList<String?>?

    /* The name of the rule or macro class that owns these attributes. */
    private val owner: String

    private val ignoreLicenses: Boolean

    init {
        this.attributes = attributes
        this.attributeIndex = attributeIndex
        this.nonConfigurableAttributes = nonConfigurableAttributes
        this.owner = owner
        this.ignoreLicenses = ignoreLicenses
    }

    override fun toString(): String {
        return owner
    }

    /**
     * If true, no rule of this class ever declares a license regardless of what the rule's or
     * package's `licenses` attribute says.
     * 
     * 
     * This is useful for rule types that don't make sense for license checking.
     */
    fun ignoreLicenses(): Boolean {
        return ignoreLicenses
    }

    /**
     * Returns true iff the attribute 'attrName' is defined for this rule or macro class, and has type
     * 'type'.
     */
    fun hasAttr(attrName: String?, type: com.google.devtools.build.lib.packages.Type<*>?): Boolean {
        val index = getAttributeIndex(attrName)
        return index != null && getAttribute(index).getType() === type
    }

    /**
     * Returns the index of the specified attribute name. Use of indices allows space-efficient
     * storage of attribute values in rules or macros, since hashtables are not required. (The index
     * mapping is specific to each RuleClass and an attribute may have a different index in the parent
     * RuleClass.)
     * 
     * 
     * Returns null if the named attribute is not defined for this class of rule or macro.
     */
    fun getAttributeIndex(attrName: String?): Int? {
        return attributeIndex.get(attrName)
    }

    /** Returns the attribute whose index is 'attrIndex'. Fails if attrIndex is not in range.  */
    fun getAttribute(attrIndex: Int): com.google.devtools.build.lib.packages.Attribute {
        return attributes.get(attrIndex)
    }

    /**
     * Returns the attribute whose name is 'attrName'; fails with NullPointerException if not found.
     */
    fun getAttributeByName(attrName: String?): com.google.devtools.build.lib.packages.Attribute {
        val attrIndex: Int =
            com.google.common.base.Preconditions.checkNotNull<Int>(
                getAttributeIndex(attrName), "Attribute %s does not exist", attrName
            )
        return attributes.get(attrIndex)
    }

    /** Returns the attribute whose name is `attrName`, or null if not found.  */
    fun getAttributeByNameMaybe(attrName: String?): com.google.devtools.build.lib.packages.Attribute? {
        val i = getAttributeIndex(attrName)
        return if (i == null) null else attributes.get(i)
    }

    /** Returns the number of attributes defined for this rule or macro class.  */
    fun getAttributeCount(): Int {
        return attributeIndex.size()
    }

    /**
     * Returns an (immutable) list of all Attributes defined for this class of rule or macro, ordered
     * by increasing index.
     */
    fun getAttributes(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute> {
        return attributes
    }

    /**
     * Returns set of non-configurable attribute names defined for this class of rule. null for macros
     * to save memory, since this field is never read for macros.
     */
    fun getNonConfigurableAttributes(): MutableList<String?>? {
        return nonConfigurableAttributes
    }

    /**
     * Populates the attributes table of the new [RuleOrMacroInstance] with the values in the
     * `attributeValues` map and with default values provided by this [AttributeProvider]
     * and the `pkgBuilder`.
     * 
     * 
     * Errors are reported on `eventHandler`.
     */
    @Throws(java.lang.InterruptedException::class, CannotPrecomputeDefaultsException::class)
    fun <T> populateRuleAttributeValues(
        ruleOrMacroInstance: RuleOrMacroInstance,
        targetDefinitionContext: TargetDefinitionContext,
        attributeValues: com.google.devtools.build.lib.packages.RuleFactory.AttributeValues<T?>,
        failOnUnknownAttributes: Boolean,
        isStarlark: Boolean
    ) {
        val definedAttrIndices: BitSet =
            populateDefinedRuleAttributeValues<T?>(
                ruleOrMacroInstance,
                targetDefinitionContext.getLabelConverter(),
                attributeValues,
                failOnUnknownAttributes,
                targetDefinitionContext.getListInterner(),
                targetDefinitionContext.getLocalEventHandler(),
                targetDefinitionContext.simplifyUnconditionalSelectsInRuleAttrs()
            )
        populateDefaultRuleAttributeValues(
            ruleOrMacroInstance, targetDefinitionContext, definedAttrIndices, isStarlark
        )
        // Now that all attributes are bound to values, collect and store configurable attribute keys.
        com.google.devtools.build.lib.packages.AttributeProvider.Companion.populateConfigDependenciesAttribute(
            ruleOrMacroInstance
        )
    }

    /**
     * Populates the attributes table of the new [RuleOrMacroInstance] with the values in the
     * `attributeValues` map.
     * 
     * 
     * Handles the special cases of the attribute named `"name"` and attributes with value
     * [Starlark.NONE].
     * 
     * 
     * Returns a bitset `b` where `b.get(i)` is `true` if this method set a value
     * for the attribute with index `i` in this [AttributeProvider]. Errors are reported
     * on `eventHandler`.
     */
    private fun <T> populateDefinedRuleAttributeValues(
        ruleOrMacroInstance: RuleOrMacroInstance,
        labelConverter: LabelConverter?,
        attributeValues: com.google.devtools.build.lib.packages.RuleFactory.AttributeValues<T?>,
        failOnUnknownAttributes: Boolean,
        listInterner: com.google.common.collect.Interner<com.google.common.collect.ImmutableList<*>?>?,
        eventHandler: EventHandler?,
        simplifyUnconditionalSelects: Boolean
    ): BitSet {
        val definedAttrIndices: BitSet = BitSet()
        for (attributeAccessor in attributeValues.getAttributeAccessors()) {
            var attributeName: String = attributeValues.getName(attributeAccessor)
            val attributeValue: Any? = attributeValues.getValue(attributeAccessor)
            // Ignore all None values.
            if (attributeValue === net.starlark.java.eval.Starlark.NONE && !failOnUnknownAttributes) {
                continue
            }

            // If the user sets "applicable_liceneses", change it to the correct name.
            // TODO(aiuto): In the time frame of Bazel 9, remove this alternate spelling.
            if (attributeName == RuleClass.Companion.APPLICABLE_METADATA_ATTR_ALT) {
                attributeName = RuleClass.Companion.APPLICABLE_METADATA_ATTR
            }

            // Check that the attribute's name belongs to a valid attribute for this rule or macro class.
            val attrIndex = getAttributeIndex(attributeName)
            if (attrIndex == null) {
                ruleOrMacroInstance.reportError(
                    java.lang.String.format(
                        "%s: no such attribute '%s' in '%s' %s%s",
                        ruleOrMacroInstance.getLabel(),
                        attributeName,
                        owner,
                        if (ruleOrMacroInstance.isRuleInstance()) "rule" else "macro",
                        net.starlark.java.spelling.SpellChecker.didYouMean(
                            attributeName,
                            ruleOrMacroInstance.getAttributes().stream()
                                .filter(java.util.function.Predicate { obj: com.google.devtools.build.lib.packages.Attribute? -> obj.isDocumented() })
                                .map<String?>(java.util.function.Function { obj: com.google.devtools.build.lib.packages.Attribute? -> obj.getName() })
                                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
                        )
                    ),
                    eventHandler
                )
                continue
            }
            // Ignore all None values (after reporting an error)
            if (attributeValue === net.starlark.java.eval.Starlark.NONE) {
                continue
            }

            val attr: com.google.devtools.build.lib.packages.Attribute = getAttribute(attrIndex)

            if (attributeName == "licenses" && ignoreLicenses) {
                ruleOrMacroInstance.setAttributeValue(attr, License.Companion.NO_LICENSE,  /* explicit= */false)
                definedAttrIndices.set(attrIndex)
                continue
            }

            // Convert the build-lang value to a native value, if necessary.
            var nativeAttributeValue: Any?
            if (attributeValues.valuesAreBuildLanguageTyped()) {
                try {
                    nativeAttributeValue =
                        BuildType.convertFromBuildLangType(
                            ruleOrMacroInstance.getAttributeProvider().toString(),
                            attr,
                            attributeValue,
                            labelConverter,
                            listInterner,
                            simplifyUnconditionalSelects
                        )
                } catch (e: ConversionException) {
                    ruleOrMacroInstance.reportError(
                        java.lang.String.format("%s: %s", ruleOrMacroInstance.getLabel(), e.getMessage()),
                        eventHandler
                    )
                    continue
                }
                // Ignore select({"//conditions:default": None}) values for attr types with null default.
                if (nativeAttributeValue == null) {
                    continue
                }
            } else {
                nativeAttributeValue = attributeValue
            }

            if (attr.getName() == "visibility") {
                val vis: MutableList<Label?> = nativeAttributeValue as MutableList<Label?>
                try {
                    nativeAttributeValue = RuleVisibility.Companion.validateAndSimplify(vis)
                } catch (e: net.starlark.java.eval.EvalException) {
                    ruleOrMacroInstance.reportError(
                        ruleOrMacroInstance.getLabel().toString() + " " + e.getMessage(), eventHandler
                    )
                }
            }

            val explicit: Boolean = attributeValues.isExplicitlySpecified(attributeAccessor)
            ruleOrMacroInstance.setAttributeValue(attr, nativeAttributeValue, explicit)
            com.google.devtools.build.lib.packages.AttributeProvider.Companion.checkAllowedValues(
                ruleOrMacroInstance,
                attr,
                eventHandler
            )
            definedAttrIndices.set(attrIndex)
        }
        return definedAttrIndices
    }

    /**
     * Populates the attributes table of the new [RuleOrMacroInstance] with default values
     * provided by this [AttributeProvider] and the `pkgBuilder`. This will only provide
     * values for attributes that haven't already been populated, using `definedAttrIndices` to
     * determine whether an attribute was populated.
     * 
     * 
     * Errors are reported on `eventHandler`.
     */
    @Throws(java.lang.InterruptedException::class, CannotPrecomputeDefaultsException::class)
    private fun populateDefaultRuleAttributeValues(
        ruleOrMacroInstance: RuleOrMacroInstance,
        targetDefinitionContext: TargetDefinitionContext,
        definedAttrIndices: BitSet,
        isStarlark: Boolean
    ) {
        // Set defaults; ensure that every mandatory attribute has a value. Use the default if none
        // is specified.
        val attrsWithComputedDefaults: MutableList<com.google.devtools.build.lib.packages.Attribute> =
            java.util.ArrayList<com.google.devtools.build.lib.packages.Attribute>()
        val numAttributes = getAttributeCount()
        for (attrIndex in 0..<numAttributes) {
            if (definedAttrIndices.get(attrIndex)) {
                continue
            }
            val attr: com.google.devtools.build.lib.packages.Attribute = getAttribute(attrIndex)
            if (attr.isMandatory()) {
                ruleOrMacroInstance.reportError(
                    java.lang.String.format(
                        "%s: missing value for mandatory attribute '%s' in '%s' %s",
                        ruleOrMacroInstance.getLabel(),
                        attr.getName(),
                        owner,
                        if (ruleOrMacroInstance.isRuleInstance()) "rule" else "macro"
                    ),
                    targetDefinitionContext.getLocalEventHandler()
                )
            }

            // Macros don't have computed defaults or special logic for licenses or distributions.
            if (ruleOrMacroInstance is com.google.devtools.build.lib.packages.Rule) {
                // We must check both the name and the type of each attribute below in case a Starlark rule
                // defines a licenses or distributions attribute of another type.

                if (attr.hasComputedDefault()) {
                    // Note that it is necessary to set all non-computed default values before calling
                    // Attribute#getDefaultValue for computed default attributes. Computed default attributes
                    // may have a condition predicate (i.e. the predicate returned by Attribute#getCondition)
                    // that depends on non-computed default attribute values, and that condition predicate is
                    // evaluated by the call to Attribute#getDefaultValue.
                    attrsWithComputedDefaults.add(attr)
                } else if (attr.isLateBound()) {
                    ruleOrMacroInstance.setAttributeValue(attr, attr.getLateBoundDefault(),  /* explicit= */false)
                } else if (attr.isMaterializing()) {
                    ruleOrMacroInstance.setAttributeValue(attr, attr.getMaterializer(), false)
                } else if (attr.getName() == RuleClass.Companion.APPLICABLE_METADATA_ATTR
                    && attr.getType() === BuildType.LABEL_LIST
                ) {
                    // The check here is preventing against a corner case where the license()/package_info()
                    // rule can get itself as applicable_metadata. This breaks the graph because there is now
                    // a self-edge.
                    //
                    // There are two ways that I can see to resolve this. The first, what is shown here,
                    // simply prunes the attribute if the source is a new-style license/metadata rule, based
                    // on what's been provided publicly. This does create a tight coupling to the
                    // implementation, but this is unavoidable since licenses are no longer a first-class type
                    // but we want first class behavior in Bazel core.
                    //
                    // A different approach that would not depend on the implementation of the rule could
                    // filter the list of default_applicable_metadata and not include the metadata rule if it
                    // matches the name of the current rule. This obviously fixes the self-assignment rule,
                    // but the resulting graph is semantically strange. The interpretation of the graph would
                    // be that the metadata rule is subject to the metadata of the *other* default metadata,
                    // but not itself. That looks very odd, and it's not semantically accurate.
                    // As an alternate, if the self-edge is detected, why not simply drop all the
                    // default_applicable_metadata attributes and avoid this oddness? That would work and
                    // fix the self-edge problem, but for nodes that don't have the self-edge problem, they
                    // would get all default_applicable_metadata and now the graph is inconsistent in that
                    // license() rules have applicable_metadata while others do not.
                    if (ruleOrMacroInstance.getRuleClassObject().isPackageMetadataRule()) {
                        ruleOrMacroInstance.setAttributeValue(
                            attr,
                            com.google.common.collect.ImmutableList.of<Any?>(),  /* explicit= */
                            false
                        )
                    }
                } else if (attr.getName() == "licenses" && attr.getType() === BuildType.LICENSE) {
                    ruleOrMacroInstance.setAttributeValue(
                        attr,
                        if (ignoreLicenses)
                            License.Companion.NO_LICENSE
                        else
                            targetDefinitionContext.getPartialPackageArgs().license(),  /* explicit= */
                        false
                    )
                }
                // Don't store default values, querying materializes them at read time.
            }
        }
        // An instance of the built-in 'test_suite' rule with an undefined or empty 'tests' attribute
        // attribute gets an '$implicit_tests' attribute, whose value is a shared per-package list
        // of all test labels, populated later.
        // TODO(blaze-rules-team): This should be in test_suite's implementation, not
        // here.
        if (owner == "test_suite" && !isStarlark) {
            val implicitTests: com.google.devtools.build.lib.packages.Attribute? =
                this.getAttributeByName("\$implicit_tests")
            val attributeMapper: NonconfigurableAttributeMapper =
                NonconfigurableAttributeMapper.Companion.of(ruleOrMacroInstance)
            if (implicitTests != null && attributeMapper.get<MutableList<Label?>?>("tests", BuildType.LABEL_LIST)
                    .isEmpty()
            ) {
                val explicit = true // so that it appears in query output
                ruleOrMacroInstance.setAttributeValue(
                    implicitTests,
                    targetDefinitionContext.getTestSuiteImplicitTestsRef(
                        attributeMapper.get<MutableList<String?>?>(
                            "tags",
                            com.google.devtools.build.lib.packages.Types.STRING_LIST
                        )
                    ),
                    explicit
                )
            }
        }
        // Set computed default attribute values now that all other (i.e. non-computed) default values
        // have been set. Macros won't hit this because they don't have attrs with computed defaults.
        for (attr in attrsWithComputedDefaults) {
            // If Attribute#hasComputedDefault was true above, Attribute#getDefaultValue returns the
            // computed default function object or a Starlark computed default template. Note that we
            // cannot determine the exact value of the computed default function here because it may
            // depend on other attribute values that are configurable (i.e. they came from select({..})
            // expressions in the build language, and they require configuration data from the analysis
            // phase to be resolved). Instead, we're setting the attribute value to a reference to the
            // computed default function, or if #getDefaultValue is a Starlark computed default
            // template, setting the attribute value to a reference to the StarlarkComputedDefault
            // returned from StarlarkComputedDefaultTemplate#computePossibleValues.
            //
            // StarlarkComputedDefaultTemplate#computePossibleValues pre-computes all possible values the
            // function may evaluate to, and records them in a lookup table. By calling it here, with an
            // EventHandler, any errors that might occur during the function's evaluation can
            // be discovered and propagated here.
            val valueToSet: Any?
            val defaultValue: Any? = attr.getDefaultValue(null)
            if (defaultValue is StarlarkComputedDefaultTemplate) {
                valueToSet =
                    defaultValue.computePossibleValues(
                        attr, ruleOrMacroInstance, targetDefinitionContext.getLocalEventHandler()
                    )
            } else if (defaultValue is ComputedDefault) {
                // Compute all possible values to verify that the ComputedDefault is well-defined. This
                // was previously done implicitly as part of visiting all labels to check for null-ness in
                // Rule.checkForNullLabels, but that was changed to skip non-label attributes to improve
                // performance.
                // TODO: b/287492305 - This is technically an illegal call to getPossibleValues as the
                // package has not yet finished loading. Do we even need this still?
                val unused: MutableList<*>? = defaultValue.getPossibleValues(attr.getType(), ruleOrMacroInstance)
                valueToSet = defaultValue
            } else {
                valueToSet = defaultValue
            }
            ruleOrMacroInstance.setAttributeValue(attr, valueToSet,  /* explicit= */false)
        }
    }

    companion object {
        /**
         * Collects all labels used as keys for configurable attributes and places them into the special
         * implicit attribute that tracks them.
         */
        private fun populateConfigDependenciesAttribute(ruleOrMacroInstance: RuleOrMacroInstance) {
            val attributes: RawAttributeMapper = RawAttributeMapper.Companion.of(ruleOrMacroInstance)
            val configDepsAttribute: com.google.devtools.build.lib.packages.Attribute? =
                attributes.getAttributeDefinition(RuleClass.Companion.CONFIG_SETTING_DEPS_ATTRIBUTE)
            if (configDepsAttribute == null) {
                return
            }

            val configLabels: LinkedHashSet<Label?> = LinkedHashSet<Label?>()
            for (attr in ruleOrMacroInstance.getAttributeProvider().getAttributes()) {
                val selectorList: BuildType.SelectorList<*>? =
                    attributes.getSelectorList(attr.getName(), attr.getType())
                if (selectorList != null) {
                    configLabels.addAll(selectorList.getKeyLabels())
                }
            }

            ruleOrMacroInstance.setAttributeValue(
                configDepsAttribute,
                com.google.common.collect.ImmutableList.copyOf<Label?>(configLabels),  /* explicit= */
                false
            )
        }

        /**
         * Verifies that the [RuleOrMacroInstance] has a valid value for the attribute according to
         * its allowed values.
         * 
         * 
         * If the value for the given attribute on the given [RuleOrMacroInstance] is invalid, an
         * error will be recorded in the given EventHandler.
         * 
         * 
         * If the `attribute` is configurable, all of its potential values are evaluated, and
         * errors for each of the invalid values are reported.
         */
        private fun checkAllowedValues(
            ruleOrMacroInstance: RuleOrMacroInstance,
            attribute: com.google.devtools.build.lib.packages.Attribute,
            eventHandler: EventHandler?
        ) {
            if (attribute.checkAllowedValues()) {
                val allowedValues: PredicateWithMessage<Any?> = attribute.getAllowedValues()
                val values: Iterable<*> =
                    AggregatingAttributeMapper.Companion.of(ruleOrMacroInstance)
                        .visitAttribute(attribute.getName(), attribute.getType())
                for (value in values) {
                    if (!allowedValues.apply(value)) {
                        ruleOrMacroInstance.reportError(
                            java.lang.String.format(
                                "%s: invalid value in '%s' attribute: %s",
                                ruleOrMacroInstance.getLabel(),
                                attribute.getName(),
                                allowedValues.getErrorReason(value)
                            ),
                            eventHandler
                        )
                    }
                }
            }
        }
    }
}
