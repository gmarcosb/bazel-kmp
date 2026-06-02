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

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/**
 * [AttributeMap] implementation that binds a rule's attribute as follows:
 * 
 * 
 *  1. If the attribute is selectable (i.e. its BUILD declaration is of the form "attr = {
 * config1: "value1", "config2: "value2", ... }", returns the subset of values chosen by the
 * current configuration in accordance with Bazel's documented policy on configurable
 * attribute selection.
 *  1. If the attribute is not selectable (i.e. its value is static), returns that value with no
 * additional processing.
 * 
 * 
 * 
 * Example usage:
 * 
 * <pre>
 * Label fooLabel = ConfiguredAttributeMapper.of(ruleConfiguredTarget).get("foo", Type.LABEL);
</pre> * 
 */
class ConfiguredAttributeMapper private constructor(
    rule: com.google.devtools.build.lib.packages.Rule?,
    configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>,
    configHash: String,
    alwaysSucceed: Boolean
) : com.google.devtools.build.lib.packages.AbstractAttributeMapper(
    com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.packages.Rule?>(
        rule
    )
) {
    private val configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>
    private val configHash: String
    private val alwaysSucceed: Boolean

    init {
        this.configConditions = configConditions
        this.configHash = configHash
        this.alwaysSucceed = alwaysSucceed
    }

    override fun describeRule(): String? {
        return String.format("%s (%s)", super.describeRule(), this.configHash.substring(0, 6))
    }

    /**
     * Checks that all attributes can be mapped to their configured values. This is useful for
     * checking that the configuration space in a configured attribute doesn't contain unresolvable
     * contradictions.
     * 
     * @throws ValidationException if any attribute's value can't be resolved under this mapper
     */
    @Throws(com.google.devtools.build.lib.packages.ConfiguredAttributeMapper.ValidationException::class)
    fun validateAttributes() {
        for (attrName in getAttributeNames()) {
            getAndValidate(attrName, getAttributeType(attrName))
        }
    }

    /** ValidationException indicates an error during attribute validation.  */
    class ValidationException(message: String?) : java.lang.Exception(message)

    /**
     * Variation of [.get] that throws an informative exception if the attribute can't be
     * resolved due to intrinsic contradictions in the configuration.
     */
    @Throws(com.google.devtools.build.lib.packages.ConfiguredAttributeMapper.ValidationException::class)
    fun <T> getAndValidate(attributeName: String?, type: com.google.devtools.build.lib.packages.Type<T?>): T? {
        val result = getResolvedAttribute<T?>(attributeName, type)
        if (result.getType() == AttributeResolutionResultType.FAILURE) {
            throw com.google.devtools.build.lib.packages.ConfiguredAttributeMapper.ValidationException(result.getFailure())
        }

        return result.getSuccess().orElse(null)
    }

    fun <T> getResolvedAttribute(attr: com.google.devtools.build.lib.packages.Attribute): AttributeResolutionResult<T?> {
        val type: com.google.devtools.build.lib.packages.Type<T?> =
            attr.getType() as com.google.devtools.build.lib.packages.Type<T?>
        return getResolvedAttribute<T?>(attr.getName(), type)
    }

    /**
     * Variation of [.getAndValidate] that does not throw Exception. Instead, the method returns
     * the AttributeResolutionResult
     */
    fun <T> getResolvedAttribute(
        attributeName: String?,
        type: com.google.devtools.build.lib.packages.Type<T?>
    ): AttributeResolutionResult<T?> {
        val selectorList: BuildType.SelectorList<T?>? = getSelectorList<T?>(attributeName, type)
        if (selectorList == null) {
            // This is a normal attribute.
            return AttributeResolutionResult.Companion.ofSuccess<T?>(super.get<T?>(attributeName, type))
        }

        val resolvedList: MutableList<T?> = java.util.ArrayList<T?>()
        for (selector in selectorList.getSelectors()) {
            val resolvedPath = resolveSelector<T?>(attributeName, selector)
            if (resolvedPath.getType() == SelectResolutionResultType.FAILURE) {
                return AttributeResolutionResult.Companion.ofFailure<T?>(resolvedPath.getFailure())
            }
            if (!selector.isValueSet(resolvedPath.getSuccess()!!.configKey)) {
                // Use the default. We don't have access to the rule here, so pass null to
                // Attribute.getValue(). This has the result of making attributes with condition
                // predicates ineligible for "None" values. But no user-facing attributes should
                // do that anyway, so that isn't a loss.
                val attr: com.google.devtools.build.lib.packages.Attribute? = getAttributeDefinition(attributeName)
                if (attr.isMandatory()) {
                    return AttributeResolutionResult.Companion.ofFailure<T?>(
                        String.format(
                            "Mandatory attribute '%s' resolved to 'None' after evaluating 'select'"
                                    + " expression",
                            attributeName
                        )
                    )
                }
                val defaultValue = attr.getDefaultValue(rule) as T?
                resolvedList.add(defaultValue)
            } else {
                resolvedList.add(resolvedPath.getSuccess()!!.value)
            }
        }

        return AttributeResolutionResult.Companion.ofSuccess<T?>(
            if (resolvedList.size == 1) resolvedList.get(0) else type.concat(resolvedList)
        )
    }

    /** Representation of the config key and it's value.  */
    class ConfigKeyAndValue<T> internal constructor(key: Label?, value: T?, provider: ConfigMatchingProvider?) {
        val configKey: Label?
        val value: T?

        /** If null, this means the default condition (doesn't correspond to a config_setting).  */
        val provider: ConfigMatchingProvider?

        init {
            this.configKey = key
            this.value = value
            this.provider = provider
        }
    }

    /**
     * AttributeResolutionResult combines all of the individual SelectResolutionResult instances if
     * there are multiple selects for the same attribute. AttributeResolutionResult is the instance of
     * either:
     * 
     * 
     *  1. 1. resolved value of an attribute as the result of successful attribute resolution
     *  1. 2. error string as the result of failed attribute resolution
     * 
     */
    @AutoOneOf(AttributeResolutionResultType::class)
    abstract class AttributeResolutionResult<T> {
        /** result type of attribute resolution  */
        enum class AttributeResolutionResultType {
            SUCCESS,
            FAILURE
        }

        abstract fun getType(): AttributeResolutionResultType?

        abstract fun getSuccess(): java.util.Optional<T?>?

        abstract fun getFailure(): String?

        companion object {
            fun <T> ofSuccess(value: T?): AttributeResolutionResult<T?> {
                return AutoOneOf_ConfiguredAttributeMapper_AttributeResolutionResult.success(
                    java.util.Optional.ofNullable<T?>(value)
                )
            }

            fun <T> ofFailure(error: String?): AttributeResolutionResult<T?> {
                return AutoOneOf_ConfiguredAttributeMapper_AttributeResolutionResult.failure(error)
            }
        }
    }

    /**
     * SelectResolutionResult is the instance of either:
     * 
     * 
     *  1. 1. ConfigKeyAndValue as the result of successful select resolution
     *  1. 2. error string as the result of failed select resolution
     * 
     */
    @AutoOneOf(SelectResolutionResultType::class)
    abstract class SelectResolutionResult<T> {
        /** result type of select resolution  */
        enum class SelectResolutionResultType {
            SUCCESS,
            FAILURE
        }

        abstract fun getType(): SelectResolutionResultType?

        abstract fun getSuccess(): ConfigKeyAndValue<T?>?

        abstract fun getFailure(): String?

        companion object {
            fun <T> ofSuccess(value: ConfigKeyAndValue<T?>?): SelectResolutionResult<T?> {
                return AutoOneOf_ConfiguredAttributeMapper_SelectResolutionResult.success(value)
            }

            fun <T> ofFailure(noMatchError: String?): SelectResolutionResult<T?> {
                return AutoOneOf_ConfiguredAttributeMapper_SelectResolutionResult.failure(noMatchError)
            }
        }
    }

    private fun <T> resolveSelector(
        attributeName: String?, selector: BuildType.Selector<T?>
    ): SelectResolutionResult<T?> {
        // Use a LinkedHashMap to guarantee a deterministic branch selection when multiple branches
        // matches but they
        // resolve to the same value.
        val matchingConditions: LinkedHashMap<Label?, ConfigKeyAndValue<T?>?> =
            LinkedHashMap<Label?, ConfigKeyAndValue<T?>?>()
        // Use a LinkedHashSet to guarantee deterministic error message ordering. We use a LinkedHashSet
        // vs. a more general SortedSet because the latter supports insertion-order, which should more
        // closely match how users see select() structures in BUILD files.
        val conditionLabels: LinkedHashSet<Label?> = LinkedHashSet<Label?>()

        val errors: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
        // Find the matching condition and record its value (checking for duplicates).
        selector.forEach(
            SelectorEntryConsumer { selectorKey: Label?, value: T? ->
                if (com.google.devtools.build.lib.packages.BuildType.Selector.Companion.isDefaultConditionLabel(
                        selectorKey
                    )
                ) {
                    return@forEach
                }
                val curCondition: ConfigMatchingProvider? = configConditions.get(selectorKey)
                if (curCondition == null) {
                    // This can happen if the rule is in error
                    return@forEach
                }
                conditionLabels.add(selectorKey)

                val matchResult: MatchResult? = curCondition.result()
                if (matchResult is) {
                    // Resolving selects so last chance to actually surface these errors.
                    errors.add(
                        ("config_setting "
                                + selectorKey
                                + " is unresolvable because: "
                                + java.lang.String.join(", ", messages))
                    )
                    // Defer the throw in order to collect all possible config_setting that are in error.
                } else if (matchResult is MatchResult.Match) {
                    // We keep track of all matches which are more precise than any we have found so
                    // far. Therefore, we remove any previous matches which are strictly less precise
                    // than this one, and only add this one if none of the previous matches are more
                    // precise. It is an error if we do not end up with only one most-precise match.
                    var suppressed = false
                    val it: MutableIterator<MutableMap.MutableEntry<Label?, ConfigKeyAndValue<T?>?>?> =
                        matchingConditions.entrySet().iterator()
                    while (it.hasNext()) {
                        val existingMatch: ConfigMatchingProvider? = it.next().getValue().provider
                        if (curCondition.refines(existingMatch)) {
                            it.remove()
                        } else if (existingMatch.refines(curCondition)) {
                            suppressed = true
                            break
                        }
                    }
                    if (!suppressed) {
                        matchingConditions.put(
                            selectorKey, ConfigKeyAndValue<T?>(selectorKey, value, curCondition)
                        )
                    }
                }
            })
        if (!errors.isEmpty()) {
            return SelectResolutionResult.Companion.ofFailure<T?>(
                ("Unresolvable config_settings for configurable attribute \""
                        + attributeName
                        + "\" in "
                        + getLabel()
                        + ":\n"
                        + com.google.common.base.Joiner.on("\n").join(errors))
            )
        }

        if (matchingConditions.values().stream()
                .map<T?>(java.util.function.Function { s: ConfigKeyAndValue<T?>? -> s!!.value }).distinct().count() > 1
        ) {
            return SelectResolutionResult.Companion.ofFailure<T?>(
                ("Illegal ambiguous match on configurable attribute \""
                        + attributeName
                        + "\" in "
                        + getLabel()
                        + ":\n"
                        + com.google.common.base.Joiner.on("\n").join(matchingConditions.keySet())
                        + "\nMultiple matches are not allowed unless one is unambiguously "
                        + "more specialized or they resolve to the same value. "
                        + "See https://bazel.build/reference/be/functions#select.")
            )
        } else if (!matchingConditions.isEmpty()) {
            return SelectResolutionResult.Companion.ofSuccess<T?>(
                com.google.common.collect.Iterables.getFirst<ConfigKeyAndValue<T?>?>(matchingConditions.values(), null)
            )
        }

        // If nothing matched, choose the default condition.
        if (selector.hasDefault()) {
            return SelectResolutionResult.Companion.ofSuccess<T?>(
                ConfigKeyAndValue<T?>(
                    com.google.devtools.build.lib.packages.BuildType.Selector.Companion.DEFAULT_CONDITION_LABEL,
                    selector.getDefault(),
                    null
                )
            )
        }

        // If we're in a debugging mode, set a fake default using the empty value for this select's
        // type.
        if (alwaysSucceed) {
            return SelectResolutionResult.Companion.ofSuccess<T?>(
                ConfigKeyAndValue<T?>(
                    com.google.devtools.build.lib.packages.BuildType.Selector.Companion.DEFAULT_CONDITION_LABEL,
                    selector.getOriginalType().getDefaultValue(),
                    null
                )
            )
        }

        return SelectResolutionResult.Companion.ofFailure<T?>(
            noMatchError(
                attributeName, selector.getNoMatchError(), conditionLabels, getLabel(), configHash
            )
        )
    }

    override fun <T> get(attributeName: String?, type: com.google.devtools.build.lib.packages.Type<T?>): T? {
        try {
            return getAndValidate<T?>(attributeName, type)
        } catch (e: ValidationException) {
            // Callers that reach this branch should explicitly validate the attribute through an
            // appropriate call (either {@link #validateAttributes} or {@link #getAndValidate}) and handle
            // the exception directly. This method assumes pre-validated attributes.
            throw java.lang.IllegalStateException(
                "lookup failed on attribute " + attributeName + ": " + e.getMessage()
            )
        }
    }

    override fun isAttributeValueExplicitlySpecified(attributeName: String?): Boolean {
        val selectorList: BuildType.SelectorList<*>? = getSelectorList(attributeName, getAttributeType(attributeName))
        if (selectorList == null) {
            // This is a normal attribute.
            return super.isAttributeValueExplicitlySpecified(attributeName)
        }
        for (selector in selectorList.getSelectors()) {
            val resolvedPath: SelectResolutionResult<*> = resolveSelector(attributeName, selector)
            if (resolvedPath.getType() == SelectResolutionResultType.FAILURE) {
                return true
            }

            if (selector.isValueSet(resolvedPath.getSuccess()!!.configKey)) {
                return true
            }
        }
        return false // Every select() in this list chooses a path with value "None".
    }

    /** Returns the labels that appear multiple times in the same attribute value.  */
    fun checkForDuplicateLabels(attribute: com.google.devtools.build.lib.packages.Attribute): MutableSet<Label?>? {
        val attrType: com.google.devtools.build.lib.packages.Type<MutableList<Label?>?> = BuildType.LABEL_LIST
        com.google.common.base.Preconditions.checkArgument(
            attribute.getType() === attrType,
            "Not a label list type: %s",
            attribute
        )
        val attrName: String? = attribute.getName()
        val selectorList: BuildType.SelectorList<MutableList<Label?>?>? =
            getSelectorList<MutableList<Label?>?>(attrName, attrType)
        // already checked in RuleClass via AggregatingAttributeMapper.checkForDuplicateLabels
        if (selectorList == null || selectorList.getSelectors().size() == 1) {
            return com.google.common.collect.ImmutableSet.of<Label?>()
        }
        val labels: MutableList<Label?>? = get<MutableList<Label?>?>(attrName, attrType)
        return CollectionUtils.duplicatedElementsOf(labels)
    }

    companion object {
        /**
         * "Manual" constructor that requires the caller to pass the set of configurability conditions
         * that trigger this rule's configurable attributes.
         * 
         * 
         * If you don't know how to do this, you really want to use one of the "do-it-all"
         * constructors.
         */
        fun of(
            rule: com.google.devtools.build.lib.packages.Rule?,
            configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>,
            configHash: String,
            alwaysSucceed: Boolean
        ): ConfiguredAttributeMapper {
            return ConfiguredAttributeMapper(rule, configConditions, configHash, alwaysSucceed)
        }

        /**
         * "Manual" constructor that requires the caller to pass the set of configurability conditions
         * that trigger this rule's configurable attributes.
         * 
         * 
         * If you don't know how to do this, you really want to use one of the "do-it-all"
         * constructors.
         */
        fun of(
            rule: com.google.devtools.build.lib.packages.Rule?,
            configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>,
            configuration: BuildConfigurationValue
        ): ConfiguredAttributeMapper {
            val alwaysSucceed: Boolean =
                configuration.getOptions().get(CoreOptions::class.java).getDebugSelectsAlwaysSucceed()
            return of(rule, configConditions, configuration.shortId(), alwaysSucceed)
        }

        /**
         * Constructs a [
         * beautiful error](https://bazel.build/designs/2016/05/23/beautiful-error-messages.html) for when no conditions in a configurable attribute match.
         */
        private fun noMatchError(
            attribute: String?,
            customNoMatchError: String,
            conditionLabels: LinkedHashSet<Label?>,
            targetLabel: Label?,
            configHash: String
        ): String {
            var error: String =
                java.lang.String.format(
                    "configurable attribute \"%s\" in %s doesn't match this configuration",
                    attribute, targetLabel
                )
            if (!customNoMatchError.isEmpty()) {
                error += java.lang.String.format(": %s\n", customNoMatchError)
            } else {
                error +=
                    (". Would a default condition help?\n\n"
                            + "Conditions checked:\n "
                            + com.google.common.base.Joiner.on("\n ").join(conditionLabels)
                            + "\n\n"
                            + "To see a condition's definition, run: bazel query --output=build "
                            + "<condition label>.\n")
            }
            // See ConfiguredTargetQueryEnvironment#shortID for the substring rationale.
            val configShortHash: String = configHash.substring(0, 7)
            error +=
                java.lang.String.format(
                    "\nThis instance of %s has configuration identifier %s. "
                            + "To inspect its configuration, run: bazel config %s.\n",
                    targetLabel, configShortHash, configShortHash
                )
            error +=
                ("\n"
                        + "For more help, see"
                        + " https://bazel.build/docs/configurable-attributes#faq-select-choose-condition.\n\n")
            return error
        }
    }
}
