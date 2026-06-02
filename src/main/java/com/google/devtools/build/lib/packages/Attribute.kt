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

import com.google.devtools.build.lib.analysis.TransitiveInfoProvider

/**
 * Metadata of a rule or macro attribute. Contains the attribute name and type, and a default value
 * to be used if none is provided in a rule or macro declaration in a BUILD file. Attributes are
 * immutable, and may be shared by more than one rule or macro (for example, `foo_binary`
 * and `foo_library ` may share many attributes in common).
 */
@javax.annotation.concurrent.Immutable
@AutoCodec
class Attribute @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization constructor(
    name: String,
    doc: String?,
    type: com.google.devtools.build.lib.packages.Type<*>,
    propertyFlags: MutableSet<PropertyFlag?>,
    defaultValue: Any?,
    transitionFactory: TransitionFactory<AttributeTransitionData?>,
    allowedRuleClassesForLabels: RuleClassNamePredicate,
    allowedRuleClassesForLabelsWarning: RuleClassNamePredicate,
    allowedFileTypesForLabels: FileTypeSet?,
    allowedValues: PredicateWithMessage<Any?>?,
    requiredProviders: RequiredProviders,
    aspects: AspectsList
) : Comparable<Attribute?> {
    private enum class PropertyFlag {
        MANDATORY,
        EXECUTABLE,
        UNDOCUMENTED,
        TAGGABLE,

        /** Whether the list attribute is order-independent and can be sorted.  */
        ORDER_INDEPENDENT,

        /**
         * Whether the allowedRuleClassesForLabels or allowedFileTypesForLabels are set to custom
         * values. If so, and the attribute is called "deps", the legacy deps checking is skipped, and
         * the new stricter checks are used instead. For non-"deps" attributes, this allows skipping the
         * check if it would pass anyway, as the default setting allows any rule classes and file types.
         */
        STRICT_LABEL_CHECKING,

        /**
         * Set for things that would cause a compile or lint-like action to be executed when the input
         * changes. Used by compile_one_dependency. Set for attributes like hdrs and srcs on cc_ rules
         * or srcs on java_ or py_rules. Generally not set on data/resource attributes.
         */
        DIRECT_COMPILE_TIME_INPUT,

        /** Whether the value of the list type attribute must not be an empty list.  */
        NON_EMPTY,

        /**
         * Verifies that the referenced rule produces a single artifact. Note that this check happens on
         * a per label basis, i.e. the check happens separately for every label in a label list.
         */
        SINGLE_ARTIFACT,

        /**
         * Whether we perform silent ruleclass filtering of the dependencies of the label type attribute
         * according to their rule classes. I.e. elements of the list which don't match the
         * allowedRuleClasses predicate or not rules will be filtered out without throwing any errors.
         * This flag is introduced to handle plugins, do not use it in other cases.
         */
        SILENT_RULECLASS_FILTER,  // TODO(bazel-team): This is a hack introduced because of the bad design of the original rules.
        // Depot cleanup would be too expensive, but don't migrate this to Starlark.

        /**
         * Whether to perform analysis time filetype check on this label-type attribute or not. If the
         * flag is set, we skip the check that applies the allowedFileTypes filter to generated files.
         * Do not use this if avoidable.
         */
        SKIP_ANALYSIS_TIME_FILETYPE_CHECK,

        /** Whether the value of the attribute should come from a given set of values.  */
        CHECK_ALLOWED_VALUES,

        /**
         * Whether this attribute is opted out of "configurability", i.e. the ability to determine its
         * value based on properties of the build configuration.
         */
        NONCONFIGURABLE,

        /** True if the "configurable" attribute was user-set.  */
        CONFIGURABLE_ATTR_WAS_USER_SET,

        /**
         * Whether we should skip dependency validation checks done by [ ] (for visibility,
         * etc.).
         */
        SKIP_PREREQ_VALIDATOR_CHECKS,

        /**
         * Whether we should check constraints on this attribute even if default enforcement policy
         * would skip it. See [ ] for more on
         * constraints.
         */
        CHECK_CONSTRAINTS_OVERRIDE,

        /**
         * Whether we should skip constraints checking on this attribute even if default enforcement
         * policy would check it.
         */
        SKIP_CONSTRAINTS_OVERRIDE,

        /** Whether we should use output_licenses to check the licences on this attribute.  */
        OUTPUT_LICENSES,

        /**
         * Has a Starlark-defined configuration transition. Transitions for analysis testing are tracked
         * separately: see [.HAS_ANALYSIS_TEST_TRANSITION].
         */
        HAS_STARLARK_DEFINED_TRANSITION,

        /**
         * Has a Starlark-defined configuration transition designed specifically for rules which run
         * analysis tests.
         */
        HAS_ANALYSIS_TEST_TRANSITION,

        /**
         * Signals that a dependency attribute is used as a tool (regardless of the actual configuration
         * or transition). Cannot be used for non-dependency attributes.
         */
        IS_TOOL_DEPENDENCY,

        /** Whether this attribute was defined using Starlark's `attrs` module.  */
        STARLARK_DEFINED,

        /** Whether to run the transitive validation actions from this attribute.  */
        SKIP_VALIDATIONS,

        /**
         * Whether the attribute is available during dependency resolution. If set, only rules also
         * marked as such can be referenced through this attribute.
         */
        FOR_DEPENDENCY_RESOLUTION,

        /**
         * Whether `FOR_DEPENDENCY_RESOLUTION` was explicitly set.
         * 
         * 
         * This is because for rules for dependency resolution, we should error out if an attribute
         * has this value explicitly set to false and it has a different value depending on the rule is
         * on is for dependency resolution or not.
         */
        FOR_DEPENDENCY_RESOLUTION_EXPLICITLY_SET,
    }

    /** A predicate class to check if the value of the attribute comes from a predefined set.  */
    class AllowedValueSet(values: Iterable<*>?) : PredicateWithMessage<Any?> {
        private val allowedValues: com.google.common.collect.ImmutableSet<Any?>

        constructor(vararg values: Any?) : this(java.util.Arrays.asList<Any?>(*values))

        init {
            com.google.common.base.Preconditions.checkNotNull(values)
            com.google.common.base.Preconditions.checkArgument(!com.google.common.collect.Iterables.isEmpty(values))
            for (v in values!!) {
                net.starlark.java.eval.Starlark.checkValid<Any?>(v)
            }
            allowedValues = com.google.common.collect.ImmutableSet.copyOf<Any?>(values)
        }

        override fun apply(input: Any?): Boolean {
            return allowedValues.contains(input)
        }

        override fun getErrorReason(value: Any?): String? {
            return java.lang.String.format(
                "has to be one of %s instead of '%s'",
                com.google.devtools.build.lib.util.StringUtil.joinEnglishListSingleQuoted(allowedValues), value
            )
        }

        @com.google.common.annotations.VisibleForTesting
        fun getAllowedValues(): MutableCollection<Any?> {
            return allowedValues
        }
    }

    /** A factory to generate [Attribute] instances.  */
    class ImmutableAttributeFactory private constructor(
        type: com.google.devtools.build.lib.packages.Type<*>,
        doc: String?,
        propertyFlags: com.google.common.collect.ImmutableSet<PropertyFlag?>,
        value: Any?,
        transitionFactory: TransitionFactory<AttributeTransitionData?>,
        allowedRuleClassesForLabels: RuleClassNamePredicate,
        allowedRuleClassesForLabelsWarning: RuleClassNamePredicate,
        allowedFileTypesForLabels: FileTypeSet?,
        valueSource: AttributeValueSource?,
        valueSet: Boolean,
        allowedValues: PredicateWithMessage<Any?>?,
        requiredProviders: RequiredProviders,
        aspects: AspectsList
    ) {
        private val type: com.google.devtools.build.lib.packages.Type<*>
        private val doc: String?
        private val transitionFactory: TransitionFactory<AttributeTransitionData?>
        private val allowedRuleClassesForLabels: RuleClassNamePredicate
        private val allowedRuleClassesForLabelsWarning: RuleClassNamePredicate
        private val allowedFileTypesForLabels: FileTypeSet?
        private val value: Any?
        private val valueSource: AttributeValueSource?
        private val valueSet: Boolean
        private val propertyFlags: com.google.common.collect.ImmutableSet<PropertyFlag?>
        private val allowedValues: PredicateWithMessage<Any?>?
        private val requiredProviders: RequiredProviders
        private val aspects: AspectsList
        private val hashCode: Int

        init {
            this.type = type
            this.doc = doc
            this.transitionFactory = transitionFactory
            this.allowedRuleClassesForLabels = allowedRuleClassesForLabels
            this.allowedRuleClassesForLabelsWarning = allowedRuleClassesForLabelsWarning
            this.allowedFileTypesForLabels = allowedFileTypesForLabels
            this.value = value
            this.valueSource = valueSource
            this.valueSet = valueSet
            this.propertyFlags = propertyFlags
            this.allowedValues = allowedValues
            this.requiredProviders = requiredProviders
            this.aspects = aspects
            this.hashCode =
                java.util.Objects.hash(
                    type,
                    doc,
                    transitionFactory,
                    allowedRuleClassesForLabels,
                    allowedRuleClassesForLabelsWarning,
                    allowedFileTypesForLabels,
                    value,
                    valueSource,
                    valueSet,
                    propertyFlags,
                    allowedValues,
                    requiredProviders,
                    aspects
                )
        }

        fun getValueSource(): AttributeValueSource? {
            return valueSource
        }

        fun isValueSet(): Boolean {
            return valueSet
        }

        fun getType(): com.google.devtools.build.lib.packages.Type<*> {
            return type
        }

        fun build(name: String): Attribute {
            com.google.common.base.Preconditions.checkState(!name.isEmpty(), "name has not been set")
            if (valueSource == AttributeValueSource.LATE_BOUND) {
                com.google.common.base.Preconditions.checkState(
                    com.google.devtools.build.lib.packages.Attribute.Companion.isAnalysisDependent(
                        name
                    )
                )
                com.google.common.base.Preconditions.checkState(!transitionFactory.isSplit())
            }

            if (valueSource == AttributeValueSource.MATERIALIZER) {
                com.google.common.base.Preconditions.checkState(
                    com.google.devtools.build.lib.packages.Attribute.Companion.isAnalysisDependent(
                        name
                    )
                )
            }

            // TODO(bazel-team): Set the default to be no file type, then remove this check, and also
            // remove all allowedFileTypes() calls without parameters.

            // do not modify this.allowedFileTypesForLabels, instead create a copy.
            var allowedFileTypesForLabels: FileTypeSet? = this.allowedFileTypesForLabels
            if (type.getLabelClass() == LabelClass.DEPENDENCY) {
                if (com.google.devtools.build.lib.packages.Attribute.Companion.isPrivateAttribute(name) && allowedFileTypesForLabels == null) {
                    allowedFileTypesForLabels = FileTypeSet.ANY_FILE
                }
                com.google.common.base.Preconditions.checkNotNull<FileTypeSet?>(
                    allowedFileTypesForLabels, "allowedFileTypesForLabels not set for %s", name
                )
            } else if (type.getLabelClass() == LabelClass.OUTPUT) {
                // TODO(bazel-team): Set the default to no file type and make explicit calls instead.
                if (allowedFileTypesForLabels == null) {
                    allowedFileTypesForLabels = FileTypeSet.ANY_FILE
                }
            }

            return com.google.devtools.build.lib.packages.Attribute(
                name,
                doc,
                type,
                propertyFlags,
                value,
                transitionFactory,
                allowedRuleClassesForLabels,
                allowedRuleClassesForLabelsWarning,
                allowedFileTypesForLabels,
                allowedValues,
                requiredProviders,
                aspects
            )
        }

        // Value equality semantics - same as for Attribute.
        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is ImmutableAttributeFactory) {
                return false
            }
            val that = o
            return hashCode == that.hashCode && type == that.type
                    && doc == that.doc
                    && transitionFactory == that.transitionFactory
                    && allowedRuleClassesForLabels == that.allowedRuleClassesForLabels
                    && allowedRuleClassesForLabelsWarning == that.allowedRuleClassesForLabelsWarning
                    && allowedFileTypesForLabels == that.allowedFileTypesForLabels
                    && value == that.value
                    && valueSource == that.valueSource
                    && valueSet == that.valueSet && propertyFlags == that.propertyFlags
                    && allowedValues == that.allowedValues
                    && requiredProviders == that.requiredProviders
                    && aspects == that.aspects
        }

        override fun hashCode(): Int {
            return hashCode
        }

        fun getTransitionFactory(): TransitionFactory<AttributeTransitionData?> {
            return transitionFactory
        }
    }

    /**
     * A fluent builder for the `Attribute` instances.
     * 
     * 
     * All methods could be called only once per builder. The attribute already undocumented based
     * on its name cannot be marked as undocumented.
     */
    class Builder<TYPE>(name: String?, type: com.google.devtools.build.lib.packages.Type<TYPE?>?) {
        private val name: String
        private val type: com.google.devtools.build.lib.packages.Type<TYPE?>
        private var transitionFactory: TransitionFactory<AttributeTransitionData?> = NoTransition.getFactory()
        private var allowedRuleClassesForLabels: RuleClassNamePredicate =
            com.google.devtools.build.lib.packages.Attribute.Companion.ANY_RULE
        private var allowedRuleClassesForLabelsWarning: RuleClassNamePredicate =
            com.google.devtools.build.lib.packages.Attribute.Companion.NO_RULE
        private var allowedFileTypesForLabels: FileTypeSet? = null
        private var value: Any? = null
        private var doc: String? = null
        private var valueSource: AttributeValueSource = AttributeValueSource.DIRECT
        private var valueSet = false
        private var propertyFlags: MutableSet<PropertyFlag?> = EnumSet.noneOf<PropertyFlag?>(PropertyFlag::class.java)
        private var allowedValues: PredicateWithMessage<Any?>? = null
        private var requiredProvidersBuilder: com.google.devtools.build.lib.packages.RequiredProviders.Builder =
            RequiredProviders.Companion.acceptAnyBuilder()
        private var aspectsListBuilder: com.google.devtools.build.lib.packages.AspectsList.Builder =
            com.google.devtools.build.lib.packages.AspectsList.Builder()

        /**
         * Creates an attribute builder with given name and type. This attribute is optional, uses
         * target configuration and has a default value the same as its type default value. This
         * attribute will be marked as undocumented if its name starts with the dollar sign (`$`)
         * or colon (`:`).
         * 
         * @param name attribute name
         * @param type attribute type
         */
        init {
            this.name = com.google.common.base.Preconditions.checkNotNull<String>(name)
            this.type =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.packages.Type<TYPE?>>(
                    type
                )
            if (com.google.devtools.build.lib.packages.Attribute.Companion.isImplicit(name) || com.google.devtools.build.lib.packages.Attribute.Companion.isAnalysisDependent(
                    name
                )
            ) {
                setPropertyFlag(PropertyFlag.UNDOCUMENTED)
            }
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun setPropertyFlag(flag: PropertyFlag?): Builder<TYPE?> {
            propertyFlags.add(flag)
            return this
        }

        /**
         * Sets the property flag of the corresponding name if exists, otherwise throws an Exception.
         * Only meant to use from Starlark, do not use from Java.
         * 
         * @throws EvalException if a property flag with the provided name does not exist or cannot be
         * set.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        fun setPropertyFlag(propertyName: String?): Builder<TYPE?> {
            val flag: PropertyFlag =
                com.google.devtools.build.lib.packages.Attribute.Builder.Companion.resolvePropertyFlagByName(
                    propertyName
                )
            setPropertyFlag(flag)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        fun removePropertyFlag(propertyName: String?): Builder<TYPE?> {
            val flag: PropertyFlag =
                com.google.devtools.build.lib.packages.Attribute.Builder.Companion.resolvePropertyFlagByName(
                    propertyName
                )
            propertyFlags.remove(flag)
            return this
        }

        /** Makes the built attribute mandatory.  */
        fun mandatory(): Builder<TYPE?> {
            return setPropertyFlag(PropertyFlag.MANDATORY)
        }

        /**
         * Makes the built attribute non empty, meaning the attribute cannot have an empty list value.
         * Only applicable for list type attributes.
         */
        fun nonEmpty(): Builder<TYPE?> {
            com.google.common.base.Preconditions.checkNotNull(
                type.getListElementType(),
                "attribute '%s' must be a list",
                name
            )
            return setPropertyFlag(PropertyFlag.NON_EMPTY)
        }

        /** Makes the built attribute producing a single artifact.  */
        fun singleArtifact(): Builder<TYPE?> {
            com.google.common.base.Preconditions.checkState(
                type.getLabelClass() == LabelClass.DEPENDENCY,
                "attribute '%s' must be a label-valued type",
                name
            )
            return setPropertyFlag(PropertyFlag.SINGLE_ARTIFACT)
        }

        /**
         * Forces silent ruleclass filtering on the label type attribute. This flag is introduced to
         * handle plugins, do not use it in other cases.
         */
        fun silentRuleClassFilter(): Builder<TYPE?> {
            com.google.common.base.Preconditions.checkState(
                type.getLabelClass() == LabelClass.DEPENDENCY, "must be a label-valued type"
            )
            return setPropertyFlag(PropertyFlag.SILENT_RULECLASS_FILTER)
        }

        /** Skip analysis time filetype check. Don't use it if avoidable.  */
        fun skipAnalysisTimeFileTypeCheck(): Builder<TYPE?> {
            com.google.common.base.Preconditions.checkState(
                type.getLabelClass() == LabelClass.DEPENDENCY, "must be a label-valued type"
            )
            return setPropertyFlag(PropertyFlag.SKIP_ANALYSIS_TIME_FILETYPE_CHECK)
        }

        /** Mark the built attribute as order-independent.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun orderIndependent(): Builder<TYPE?> {
            com.google.common.base.Preconditions.checkNotNull(
                type.getListElementType(),
                "attribute '%s' must be a list",
                name
            )
            return setPropertyFlag(PropertyFlag.ORDER_INDEPENDENT)
        }

        /** Mark the built attribute as to use output_licenses for license checking.  */
        fun useOutputLicenses(): Builder<TYPE?> {
            com.google.common.base.Preconditions.checkState(BuildType.isLabelType(type), "must be a label type")
            return setPropertyFlag(PropertyFlag.OUTPUT_LICENSES)
        }

        /**
         * Indicate the attribute uses uses a starlark-defined (non-analysis-test) configuration
         * transition. Transitions for analysis testing are tracked separately: see [ ][.hasAnalysisTestTransition].
         */
        fun hasStarlarkDefinedTransition(): Builder<TYPE?> {
            return setPropertyFlag(PropertyFlag.HAS_STARLARK_DEFINED_TRANSITION)
        }

        /**
         * Indicate the attribute uses uses a starlark-defined analysis-test configuration transition.
         * Such a configuration transition may only be applied on rules with `analysis_test=true`.
         */
        fun hasAnalysisTestTransition(): Builder<TYPE?> {
            return setPropertyFlag(PropertyFlag.HAS_ANALYSIS_TEST_TRANSITION)
        }

        /** Defines the configuration transition for this attribute.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun cfg(transitionFactory: TransitionFactory<AttributeTransitionData?>): Builder<TYPE?> {
            com.google.common.base.Preconditions.checkNotNull<Any?>(transitionFactory)
            com.google.common.base.Preconditions.checkState(
                NoTransition.isInstance(this.transitionFactory),
                "the configuration transition is already set"
            )
            this.transitionFactory = transitionFactory
            return this
        }

        /**
         * Requires the attribute target to be executable; only for label or label list attributes.
         * Defaults to `false`.
         */
        fun exec(): Builder<TYPE?> {
            return setPropertyFlag(PropertyFlag.EXECUTABLE)
        }

        /**
         * Indicates that the attribute (like srcs or hdrs) should be used as an input when calculating
         * compile_one_dependency.
         */
        fun direct_compile_time_input(): Builder<TYPE?> {
            return setPropertyFlag(PropertyFlag.DIRECT_COMPILE_TIME_INPUT)
        }

        /**
         * Makes the built attribute undocumented.
         * 
         * @param reason explanation why the attribute is undocumented. This is not used but required
         * for documentation
         */
        fun undocumented(reason: String?): Builder<TYPE?> {
            return setPropertyFlag(PropertyFlag.UNDOCUMENTED)
        }

        /**
         * Set the doc string for the attribute.
         * 
         * @param doc The doc string for this attribute.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setDoc(doc: String?): Builder<TYPE?> {
            this.doc = doc
            return this
        }

        /**
         * Sets the attribute default value. The type of the default value must match the type
         * parameter. (e.g. list=[], integer=0, string="", label=null). The `defaultValue` must be
         * immutable.
         * 
         * 
         * If defaultValue is of type Label and is a target, that target will become an implicit
         * dependency of the Rule; we will load the target (and its dependencies) if it encounters the
         * Rule and build the target if needs to apply the Rule.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun value(defaultValue: TYPE?): Builder<TYPE?> {
            value = defaultValue
            valueSet = true
            return this
        }

        /**
         * Sets the attribute default value to a computed default value - use this when the default
         * value is a function of other attributes of the Rule. The type of the computed default value
         * for a mandatory attribute must match the type parameter: (e.g. list=[], integer=0, string="",
         * label=null). The `defaultValue` implementation must be immutable.
         * 
         * 
         * If the computed default returns a Label that is a target, that target will become an
         * implicit dependency of this Rule; we will load the target (and its dependencies) if it
         * encounters the Rule and build the target if needs to apply the Rule.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun value(defaultValue: ComputedDefault?): Builder<TYPE?> {
            value = defaultValue
            valueSource = AttributeValueSource.COMPUTED_DEFAULT
            valueSet = true
            return this
        }

        /** Used for b/200065655#comment3.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun value(defaultValue: NativeComputedDefaultApi?): Builder<TYPE?> {
            value = defaultValue
            valueSource = AttributeValueSource.NATIVE_COMPUTED_DEFAULT
            valueSet = true
            return this
        }

        /**
         * Sets the attribute default value to a Starlark computed default template. Like a native
         * Computed Default, this allows a Starlark-defined Rule Class to specify that the default value
         * of an attribute is a function of other attributes of the Rule.
         * 
         * 
         * During the loading phase, the computed default template will be specialized for each rule
         * it applies to. Those rules' attribute values will not be references to [ ]s, but instead will be references to [ ]s.
         * 
         * 
         * If the computed default returns a Label that is a target, that target will become an
         * implicit dependency of this Rule; we will load the target (and its dependencies) if it
         * encounters the Rule and build the target if needs to apply the Rule.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun value(starlarkComputedDefaultTemplate: StarlarkComputedDefaultTemplate?): Builder<TYPE?> {
            value = starlarkComputedDefaultTemplate
            valueSource = AttributeValueSource.COMPUTED_DEFAULT
            valueSet = true
            return this
        }

        /**
         * Sets the attribute default value to be late-bound, i.e., it is derived from the build
         * configuration and/or the rule's configured attributes.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun value(defaultValue: LateBoundDefault<*, out TYPE?>?): Builder<TYPE?> {
            value = defaultValue
            valueSource = AttributeValueSource.LATE_BOUND
            valueSet = true
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun value(defaultValue: MaterializingDefault<*, out TYPE?>?): Builder<TYPE?> {
            value = defaultValue
            valueSource = AttributeValueSource.MATERIALIZER
            valueSet = true
            return this
        }

        /**
         * See value(TYPE) above. This method is only meant for Starlark usage.
         * 
         * 
         * The parameter `labelConverter` is relevant iff the default value is a Label string.
         * 
         * @param parameterName The name of the attribute to use in error messages
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(ConversionException::class)
        fun defaultValue(
            defaultValue: Any?, labelConverter: LabelConverter?, parameterName: String?
        ): Builder<TYPE?> {
            value =
                type.convert(
                    defaultValue,
                    (if (parameterName == null) "" else java.lang.String.format("parameter '%s' of ", parameterName))
                            + java.lang.String.format("attribute '%s'", name),
                    labelConverter
                )
            valueSet = true
            return this
        }

        /** See value(TYPE) above. This method is only meant for Starlark usage.  */
        @Throws(ConversionException::class)
        fun defaultValue(defaultValue: Any?): Builder<TYPE?> {
            return defaultValue(defaultValue, null, null)
        }

        /**
         * Force the default value to be `None`. This method is meant only for usage by symbolic
         * macro machinery.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun defaultValueNone(): Builder<TYPE?> {
            value = null
            valueSet = true
            valueSource = AttributeValueSource.DIRECT
            return this
        }

        /** Returns where the value of this attribute comes from. Useful only for Starlark.  */
        fun getValueSource(): AttributeValueSource {
            return valueSource
        }

        /** Switches on the capability of an attribute to be published to the rule's tag set.  */
        fun taggable(): Builder<TYPE?> {
            return setPropertyFlag(PropertyFlag.TAGGABLE)
        }

        /**
         * Disables dependency checks done by [ ].
         */
        fun skipPrereqValidatorCheck(): Builder<TYPE?> {
            return setPropertyFlag(PropertyFlag.SKIP_PREREQ_VALIDATOR_CHECKS)
        }

        /**
         * Enforces constraint checking on this attribute even if default enforcement policy would skip
         * it. If default policy checks the attribute, this is a no-op.
         * 
         * 
         * Most attributes are enforced by default, so in the common case this call is unnecessary.
         * 
         * 
         * See [com.google.devtools.build.lib.analysis.constraints.ConstraintSemantics] for
         * enforcement policy details.
         */
        fun checkConstraints(): Builder<TYPE?> {
            com.google.common.base.Verify.verify(
                !propertyFlags.contains(PropertyFlag.SKIP_CONSTRAINTS_OVERRIDE),
                "constraint checking is already overridden to be skipped"
            )
            return setPropertyFlag(PropertyFlag.CHECK_CONSTRAINTS_OVERRIDE)
        }

        /**
         * Skips constraint checking on this attribute even if default enforcement policy would check
         * it. If default policy skips the attribute, this is a no-op.
         * 
         * 
         * See [com.google.devtools.build.lib.analysis.constraints.ConstraintSemantics] for
         * enforcement policy details.
         */
        fun dontCheckConstraints(): Builder<TYPE?> {
            com.google.common.base.Verify.verify(
                !propertyFlags.contains(PropertyFlag.CHECK_CONSTRAINTS_OVERRIDE),
                "constraint checking is already overridden to be checked"
            )
            return setPropertyFlag(PropertyFlag.SKIP_CONSTRAINTS_OVERRIDE)
        }

        /**
         * If this is a label or label-list attribute, then this sets the allowed rule types for the
         * labels occurring in the attribute.
         * 
         * 
         * If the attribute contains Labels of any other rule type, then if they're in [ ][.allowedRuleClassesForLabelsWarning], the build continues with a warning. Else if they
         * fulfill [.mandatoryBuiltinProvidersList], the build continues without error. Else the
         * build fails during analysis.
         * 
         * 
         * If neither this nor [.allowedRuleClassesForLabelsWarning] is set, only rules that
         * fulfill [.mandatoryBuiltinProvidersList] build without error.
         * 
         * 
         * This only works on a per-target basis, not on a per-file basis; with other words, it works
         * for 'deps' attributes, but not 'srcs' attributes.
         */
        fun allowedRuleClasses(allowedRuleClasses: Iterable<String?>?): Builder<TYPE?> {
            return allowedRuleClasses(RuleClassNamePredicate.Companion.only(allowedRuleClasses))
        }

        /**
         * If this is a label or label-list attribute, then this sets the allowed rule types for the
         * labels occurring in the attribute.
         * 
         * 
         * If the attribute contains Labels of any other rule type, then if they're in [ ][.allowedRuleClassesForLabelsWarning], the build continues with a warning. Else if they
         * fulfill [.mandatoryBuiltinProvidersList], the build continues without error. Else the
         * build fails during analysis.
         * 
         * 
         * If neither this nor [.allowedRuleClassesForLabelsWarning] is set, only rules that
         * fulfill [.mandatoryBuiltinProvidersList] build without error.
         * 
         * 
         * This only works on a per-target basis, not on a per-file basis; with other words, it works
         * for 'deps' attributes, but not 'srcs' attributes.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun allowedRuleClasses(allowedRuleClasses: RuleClassNamePredicate): Builder<TYPE?> {
            com.google.common.base.Preconditions.checkState(
                type.getLabelClass() == LabelClass.DEPENDENCY, "must be a label-valued type"
            )
            propertyFlags.add(PropertyFlag.STRICT_LABEL_CHECKING)
            allowedRuleClassesForLabels = allowedRuleClasses
            return this
        }

        /**
         * If this is a label or label-list attribute, then this sets the allowed rule types for the
         * labels occurring in the attribute.
         * 
         * 
         * If the attribute contains Labels of any other rule type, then if they're in [ ][.allowedRuleClassesForLabelsWarning], the build continues with a warning. Else if they
         * fulfill [.mandatoryBuiltinProvidersList], the build continues without error. Else the
         * build fails during analysis.
         * 
         * 
         * If neither this nor [.allowedRuleClassesForLabelsWarning] is set, only rules that
         * fulfill [.mandatoryBuiltinProvidersList] build without error.
         * 
         * 
         * This only works on a per-target basis, not on a per-file basis; with other words, it works
         * for 'deps' attributes, but not 'srcs' attributes.
         */
        fun allowedRuleClasses(vararg allowedRuleClasses: String?): Builder<TYPE?> {
            return allowedRuleClasses(com.google.common.collect.ImmutableSet.copyOf<String?>(allowedRuleClasses))
        }

        /**
         * If this is a label or label-list attribute, then this sets the allowed file types for file
         * labels occurring in the attribute. If the attribute contains labels that correspond to files
         * of any other type, then an error is produced during the analysis phase.
         * 
         * 
         * This only works on a per-target basis, not on a per-file basis; with other words, it works
         * for 'deps' attributes, but not 'srcs' attributes.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun allowedFileTypes(allowedFileTypes: FileTypeSet?): Builder<TYPE?> {
            com.google.common.base.Preconditions.checkState(
                type.getLabelClass() == LabelClass.DEPENDENCY
                        || type.getLabelClass() == LabelClass.GENQUERY_SCOPE_REFERENCE,
                "must be a label-valued type"
            )
            propertyFlags.add(PropertyFlag.STRICT_LABEL_CHECKING)
            allowedFileTypesForLabels =
                com.google.common.base.Preconditions.checkNotNull<FileTypeSet?>(allowedFileTypes)
            return this
        }

        /**
         * If this is a label or label-list attribute, then this sets the allowed file types for file
         * labels occurring in the attribute. If the attribute contains labels that correspond to files
         * of any other type, then an error is produced during the analysis phase.
         * 
         * 
         * This only works on a per-target basis, not on a per-file basis; with other words, it works
         * for 'deps' attributes, but not 'srcs' attributes.
         */
        fun allowedFileTypes(vararg allowedFileTypes: com.google.devtools.build.lib.util.FileType?): Builder<TYPE?> {
            return allowedFileTypes(FileTypeSet.of(*allowedFileTypes))
        }

        /**
         * Allow all files for legacy compatibility. All uses of this method should be audited and then
         * removed. In some cases, it's correct to allow any file, but mostly the set of files should be
         * restricted to a reasonable set.
         */
        fun legacyAllowAnyFileType(): Builder<TYPE?> {
            return allowedFileTypes(FileTypeSet.ANY_FILE)
        }

        /**
         * If this is a label or label-list attribute, then this sets the allowed rule types with
         * warning for the labels occurring in the attribute. This must be a disjoint set from [ ][.allowedRuleClasses].
         * 
         * 
         * If the attribute contains Labels of any other rule type (other than this or those set in
         * allowedRuleClasses()) and they fulfill [.mandatoryBuiltinProvidersList]}, the build
         * continues without error. Else the build fails during analysis.
         * 
         * 
         * If neither this nor [.allowedRuleClassesForLabels] is set, only rules that fulfill
         * [.mandatoryBuiltinProvidersList] build without error.
         * 
         * 
         * This only works on a per-target basis, not on a per-file basis; with other words, it works
         * for 'deps' attributes, but not 'srcs' attributes.
         */
        fun allowedRuleClassesWithWarning(allowedRuleClasses: MutableCollection<String?>?): Builder<TYPE?> {
            return allowedRuleClassesWithWarning(RuleClassNamePredicate.Companion.only(allowedRuleClasses))
        }

        /**
         * If this is a label or label-list attribute, then this sets the allowed rule types with
         * warning for the labels occurring in the attribute. This must be a disjoint set from [ ][.allowedRuleClasses].
         * 
         * 
         * If the attribute contains Labels of any other rule type (other than this or those set in
         * allowedRuleClasses()) and they fulfill [.mandatoryBuiltinProvidersList]}, the build
         * continues without error. Else the build fails during analysis.
         * 
         * 
         * If neither this nor [.allowedRuleClassesForLabels] is set, only rules that fulfill
         * [.mandatoryBuiltinProvidersList] build without error.
         * 
         * 
         * This only works on a per-target basis, not on a per-file basis; with other words, it works
         * for 'deps' attributes, but not 'srcs' attributes.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun allowedRuleClassesWithWarning(allowedRuleClasses: RuleClassNamePredicate): Builder<TYPE?> {
            com.google.common.base.Preconditions.checkState(
                type.getLabelClass() == LabelClass.DEPENDENCY, "must be a label-valued type"
            )
            propertyFlags.add(PropertyFlag.STRICT_LABEL_CHECKING)
            allowedRuleClassesForLabelsWarning = allowedRuleClasses
            return this
        }

        /**
         * If this is a label or label-list attribute, then this sets the allowed rule types with
         * warning for the labels occurring in the attribute. This must be a disjoint set from [ ][.allowedRuleClasses].
         * 
         * 
         * If the attribute contains Labels of any other rule type (other than this or those set in
         * allowedRuleClasses()) and they fulfill [.getRequiredProviders]}, the build continues
         * without error. Else the build fails during analysis.
         * 
         * 
         * If neither this nor [.allowedRuleClassesForLabels] is set, only rules that fulfill
         * [.getRequiredProviders] build without error.
         * 
         * 
         * This only works on a per-target basis, not on a per-file basis; with other words, it works
         * for 'deps' attributes, but not 'srcs' attributes.
         */
        fun allowedRuleClassesWithWarning(vararg allowedRuleClasses: String?): Builder<TYPE?> {
            return allowedRuleClassesWithWarning(
                com.google.common.collect.ImmutableSet.copyOf<String?>(
                    allowedRuleClasses
                )
            )
        }

        /**
         * Sets a list of lists of mandatory built-in providers. Every configured target occurring in
         * this label type attribute has to provide all the providers from one of those lists, otherwise
         * an error is produced during the analysis phase.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun mandatoryBuiltinProvidersList(
            providersList: Iterable<out Iterable<java.lang.Class<out TransitiveInfoProvider?>?>>
        ): Builder<TYPE?> {
            com.google.common.base.Preconditions.checkState(
                type.getLabelClass() == LabelClass.DEPENDENCY, "must be a label-valued type"
            )

            for (providers in providersList) {
                this.requiredProvidersBuilder.addBuiltinSet(
                    com.google.common.collect.ImmutableSet.copyOf<java.lang.Class<out TransitiveInfoProvider?>?>(
                        providers
                    )
                )
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun mandatoryBuiltinProviders(
            providers: Iterable<java.lang.Class<out TransitiveInfoProvider?>?>
        ): Builder<TYPE?> {
            if (providers.iterator().hasNext()) {
                mandatoryBuiltinProvidersList(
                    com.google.common.collect.ImmutableList.of<Iterable<java.lang.Class<out TransitiveInfoProvider?>?>?>(
                        providers
                    )
                )
            }
            return this
        }

        /**
         * Sets a list of sets of mandatory Starlark providers. Every configured target occurring in
         * this label type attribute has to provide all the providers from one of those sets, or be one
         * of [.allowedRuleClasses], otherwise an error is produced during the analysis phase.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun mandatoryProvidersList(
            providersList: Iterable<out Iterable<StarlarkProviderIdentifier?>>
        ): Builder<TYPE?> {
            com.google.common.base.Preconditions.checkState(
                type.getLabelClass() == LabelClass.DEPENDENCY, "must be a label-valued type"
            )
            for (providers in providersList) {
                this.requiredProvidersBuilder.addStarlarkSet(
                    com.google.common.collect.ImmutableSet.copyOf<StarlarkProviderIdentifier?>(
                        providers
                    )
                )
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun mandatoryProviders(providers: Iterable<StarlarkProviderIdentifier?>): Builder<TYPE?> {
            if (providers.iterator().hasNext()) {
                mandatoryProvidersList(
                    com.google.common.collect.ImmutableList.of<Iterable<StarlarkProviderIdentifier?>?>(
                        providers
                    )
                )
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun mandatoryProviders(vararg providers: StarlarkProviderIdentifier?): Builder<TYPE?> {
            mandatoryProviders(java.util.Arrays.asList<StarlarkProviderIdentifier?>(*providers))
            return this
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun addAspects(aspectsList: AspectsList) {
            aspectsListBuilder.addAspects(aspectsList)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        fun aspect(aspect: StarlarkAspect?): Builder<TYPE?> {
            aspectsListBuilder.addAspect(aspect)
            return this
        }

        /**
         * Asserts that a particular parameterized aspect probably needs to be computed for all direct
         * dependencies through this attribute.
         * 
         * @param evaluator function that extracts aspect parameters from rule. If it returns null, then
         * the aspect will not be attached.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun aspect(
            aspect: NativeAspectClass?,
            evaluator: com.google.common.base.Function<com.google.devtools.build.lib.packages.Rule?, AspectParameters?>?
        ): Builder<TYPE?> {
            aspectsListBuilder.addAspect(aspect, evaluator)
            return this
        }

        /**
         * Asserts that a particular parameterized aspect probably needs to be computed for all direct
         * dependencies through this attribute.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun aspect(aspect: NativeAspectClass?): Builder<TYPE?> {
            aspectsListBuilder.addAspect(aspect)
            return this
        }

        /** Should only be used for deserialization.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun aspect(aspect: Aspect?): Builder<TYPE?> {
            aspectsListBuilder.addAspect(aspect)
            return this
        }

        /** The value of the attribute must be one of allowedValues.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun allowedValues(allowedValues: PredicateWithMessage<Any?>?): Builder<TYPE?> {
            this.allowedValues = allowedValues
            propertyFlags.add(PropertyFlag.CHECK_ALLOWED_VALUES)
            return this
        }

        /**
         * Makes the built attribute "non-configurable", i.e. its value cannot be influenced by the
         * build configuration. Attributes are "configurable" unless explicitly opted out here.
         * 
         * 
         * Non-configurability indicates an exceptional state: there exists Blaze logic that needs
         * the attribute's value, has no access to configurations, and can't apply a workaround through
         * an appropriate [AbstractAttributeMapper] implementation. Scenarios like this should be
         * as uncommon as possible, so it's important we maintain clear documentation on what causes
         * them and why users consequently can't configure certain attributes.
         * 
         * @param reason why this attribute can't be configurable. This isn't used by Blaze - it's
         * solely a documentation mechanism.
         */
        fun nonconfigurable(reason: String): Builder<TYPE?> {
            com.google.common.base.Preconditions.checkState(!reason.isEmpty())
            return setPropertyFlag(PropertyFlag.NONCONFIGURABLE)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun configurableAttrWasUserSet(): Builder<TYPE?> {
            return setPropertyFlag(PropertyFlag.CONFIGURABLE_ATTR_WAS_USER_SET)
        }

        fun tool(reason: String): Builder<TYPE?> {
            com.google.common.base.Preconditions.checkState(
                type.getLabelClass() == LabelClass.DEPENDENCY, "must be a label-valued type"
            )
            com.google.common.base.Preconditions.checkState(!reason.isEmpty())
            return setPropertyFlag(PropertyFlag.IS_TOOL_DEPENDENCY)
        }

        /** Marks the built attribute as defined in Starlark.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun starlarkDefined(): Builder<TYPE?> {
            return setPropertyFlag(PropertyFlag.STARLARK_DEFINED)
        }

        /** Returns an [ImmutableAttributeFactory] that can be invoked to create attributes.  */
        fun buildPartial(): ImmutableAttributeFactory {
            com.google.common.base.Preconditions.checkState(
                !allowedRuleClassesForLabels.consideredOverlapping(allowedRuleClassesForLabelsWarning),
                "allowedRuleClasses %s and allowedRuleClassesWithWarning %s "
                        + "may not contain the same rule classes",
                allowedRuleClassesForLabels,
                allowedRuleClassesForLabelsWarning
            )

            return ImmutableAttributeFactory(
                type,
                doc,
                com.google.common.collect.Sets.immutableEnumSet<PropertyFlag?>(propertyFlags),
                if (valueSet) value else type.getDefaultValue(),
                transitionFactory,
                allowedRuleClassesForLabels,
                allowedRuleClassesForLabelsWarning,
                allowedFileTypesForLabels,
                valueSource,
                valueSet,
                allowedValues,
                requiredProvidersBuilder.build(),
                aspectsListBuilder.build()
            )
        }

        /**
         * Creates the attribute. Uses type, optionality, configuration type and the default value
         * configured by the builder. Use the name passed as an argument. This function is used by
         * Starlark where the name is provided only when we build. We don't want to modify the builder,
         * as it is shared in a multithreaded environment.
         */
        /**
         * Creates the attribute. Uses name, type, optionality, configuration type and the default value
         * configured by the builder.
         */
        @kotlin.jvm.JvmOverloads
        fun build(name: String = this.name): Attribute {
            return buildPartial().build(name)
        }

        companion object {
            @Throws(net.starlark.java.eval.EvalException::class)
            private fun resolvePropertyFlagByName(propertyName: String?): PropertyFlag {
                try {
                    return com.google.devtools.build.lib.packages.Attribute.PropertyFlag.valueOf(propertyName)
                } catch (e: java.lang.IllegalArgumentException) {
                    throw net.starlark.java.eval.Starlark.errorf("unknown attribute flag '%s'", propertyName)
                }
            }
        }
    }

    /**
     * A strategy for dealing with too many computations, used when creating lookup tables for [ ]s.
     * 
     * @param <ExceptionT> The type of exception this strategy throws if too many computations are
     * attempted.
    </ExceptionT> */
    internal interface ComputationLimiter<ExceptionT : java.lang.Exception?> {
        @Throws(ExceptionT::class)
        fun onComputationCount(count: Int)
    }

    /**
     * An implementation of [ComputationLimiter] that never throws. For use with
     * natively-defined [ComputedDefault]s, which are limited in the number of configurable
     * attributes they depend on, not on the number of different combinations of possible inputs.
     */
    /** Exception for computed default attributes that depend on too many configurable attributes.  */
    private class TooManyConfigurableAttributesException(max: Int) : java.lang.Exception(
        java.lang.String.format(
            "Too many configurable attributes to compute all possible values: "
                    + "Found more than %d possible values.",
            max
        )
    )

    private class FixedComputationLimiter

        : ComputationLimiter<TooManyConfigurableAttributesException?> {
        @Throws(TooManyConfigurableAttributesException::class)
        override fun onComputationCount(count: Int) {
            if (count > COMPUTED_DEFAULT_MAX_COMBINATIONS) {
                throw TooManyConfigurableAttributesException(COMPUTED_DEFAULT_MAX_COMBINATIONS)
            }
        }

        companion object {
            /** Upper bound of the number of combinations of values for a computed default attribute.  */
            private const val COMPUTED_DEFAULT_MAX_COMBINATIONS = 64

            private val INSTANCE = FixedComputationLimiter()
        }
    }

    /**
     * Specifies how values of [ComputedDefault] attributes are computed based on the values of
     * other attributes.
     * 
     * 
     * The `TComputeException` type parameter allows the two specializations of this class to
     * describe whether and how their computations throw. For natively defined computed defaults,
     * computation does not throw, but for Starlark-defined computed defaults, computation may throw
     * [InterruptedException].
     */
    private abstract class ComputationStrategy<TComputeException : java.lang.Exception?> {
        @Throws(TComputeException::class)
        abstract fun compute(map: com.google.devtools.build.lib.packages.AttributeMap?): Any?

        /**
         * Returns a lookup table mapping from:
         * 
         * 
         *  * tuples of values that may be assigned by `rule` to attributes with names in
         * `dependencies` (note that there may be more than one such tuple for any given
         * rule, if any of the dependencies are configurable)
         * 
         * 
         * 
         * to:
         * 
         * 
         *  * the value [.compute] evaluates to when the provided [       ] contains the values specified by that assignment, or `null` if the
         * [ComputationStrategy] failed to evaluate.
         * 
         * 
         * 
         * The lookup table contains a tuple for each possible assignment to the `dependencies`
         * attributes. The meaning of each tuple is well-defined because `dependencies` is
         * ordered.
         * 
         * 
         * This is useful because configurable attributes may have many possible values. During the
         * loading phase a configurable attribute can't be resolved to a single value. Configuration
         * information, needed to resolve such an attribute, is only available during analysis. However,
         * any labels that a ComputedDefault attribute may evaluate to must be loaded during the loading
         * phase.
         */
        @Throws(TComputeException::class, TLimitException::class)
        fun <T, TLimitException : java.lang.Exception?> computeValuesForAllCombinations(
            dependencies: MutableList<String?>,
            type: com.google.devtools.build.lib.packages.Type<T?>,
            rule: RuleOrMacroInstance?,
            limiter: ComputationLimiter<TLimitException?>?
        ): MutableMap<MutableList<Any?>?, T?> {
            val mapper: AggregatingAttributeMapper = AggregatingAttributeMapper.Companion.of(rule)
            // This will hold every (value1, value2, ..) combination of the declared dependencies.
            // Collect those combinations.
            val depMaps: MutableList<MutableMap<String?, Any?>?> =
                mapper.visitAttributes<TLimitException?>(dependencies, limiter)
            // For each combination, call compute() on a specialized AttributeMap providing those
            // values.
            val valueMap: MutableMap<MutableList<Any?>?, T?> =
                com.google.common.collect.Maps.newHashMapWithExpectedSize<MutableList<Any?>?, T?>(depMaps.size())
            for (depMap in depMaps) {
                val attrMap: com.google.devtools.build.lib.packages.AttributeMap =
                    mapper.createMapBackedAttributeMap(depMap)
                val value = compute(attrMap)
                val key = createDependencyAssignmentTuple(dependencies, attrMap)
                valueMap.put(key, type.cast(value))
            }
            return valueMap
        }

        companion object {
            /**
             * Given an [AttributeMap], containing an assignment to each attribute in `dependencies`, this returns a list of the assigned values, ordered as `dependencies` is
             * ordered.
             */
            fun createDependencyAssignmentTuple(
                dependencies: MutableList<String?>, attrMap: com.google.devtools.build.lib.packages.AttributeMap
            ): MutableList<Any?> {
                val tuple: java.util.ArrayList<Any?> = java.util.ArrayList<Any?>(dependencies.size())
                for (attrName in dependencies) {
                    val attrType: com.google.devtools.build.lib.packages.Type<*>? = attrMap.getAttributeType(attrName)
                    tuple.add(attrMap.get(attrName, attrType))
                }
                return tuple
            }
        }
    }

    /**
     * A computed default is a default value for a Rule attribute that is a function of other
     * attributes of the rule or package.
     * 
     * 
     * Attributes with computed defaults defer their evaluation until after the package is loaded
     * (and thus all other attributes without computed defaults are determined). There is no defined
     * order among computed defaults, so they must not depend on each other.
     * 
     * 
     * If a computed default reads the value of another attribute, at least one of the following
     * must be true:
     * 
     * 
     *  1. The other attribute must be declared in the computed default's constructor
     *  1. The other attribute must be non-configurable ([Builder.nonconfigurable]
     * 
     * 
     * 
     * Note that merely checking if an attribute is explicitly specified does not count as
     * 'reading' it as, currently, this determination cannot be configuration-dependent.
     * 
     * 
     * The reason for enforced declarations is that, since attribute values might be configurable,
     * a computed default that depends on them may itself take multiple values. As we have no access
     * to a target's configuration at the time these values are computed, we need the ability to probe
     * the default's *complete* dependency space. Declared dependencies allow us to do so sanely.
     * Non-configurable attributes don't have this problem because their value is fixed and known even
     * without configuration information.
     * 
     * 
     * Implementations of this interface must be immutable.
     */
    abstract class ComputedDefault internal constructor(dependencies: com.google.common.collect.ImmutableList<String?>) :
        net.starlark.java.eval.StarlarkValue {
        private val dependencies: com.google.common.collect.ImmutableList<String?>

        /**
         * Create a computed default that can read all non-configurable attribute values and no
         * configurable attribute values.
         */
        protected constructor() : this(com.google.common.collect.ImmutableList.of<String?>())

        /**
         * Create a computed default that can read all non-configurable attributes values and one
         * explicitly specified configurable attribute value
         */
        protected constructor(depAttribute: String) : this(
            com.google.common.collect.ImmutableList.of<String?>(
                depAttribute
            )
        )

        /**
         * Create a computed default that can read all non-configurable attributes values and two
         * explicitly specified configurable attribute values.
         */
        protected constructor(
            depAttribute1: String?,
            depAttribute2: String?
        ) : this(com.google.common.collect.ImmutableList.of<String?>(depAttribute1, depAttribute2))

        /**
         * Creates a computed default that can read all non-configurable attributes and some explicitly
         * specified configurable attribute values.
         * 
         * 
         * This constructor should not be used by native [ComputedDefault] functions. The limit
         * of at-most-two depended-on configurable attributes is intended, to limit the exponential
         * growth of possible values. [StarlarkComputedDefault] uses this, but is limited by
         * [FixedComputationLimiter.COMPUTED_DEFAULT_MAX_COMBINATIONS].
         */
        init {
            // Order is important for #createDependencyAssignmentTuple.
            this.dependencies =
                dependenciesInterner.intern(
                    com.google.common.collect.Ordering.natural<Comparable<*>?>()
                        .immutableSortedCopy<String?>(dependencies)
                )
        }

        open fun <T> getPossibleValues(
            type: com.google.devtools.build.lib.packages.Type<T?>,
            rule: RuleOrMacroInstance?
        ): MutableList<T?> {
            val owner = this@ComputedDefault
            if (dependencies.isEmpty()) {
                val mapper: AggregatingAttributeMapper = AggregatingAttributeMapper.Companion.of(rule)
                val value =
                    owner.getDefault(mapper.createMapBackedAttributeMap(com.google.common.collect.ImmutableMap.of<String?, Any?>()))
                return com.google.common.collect.Lists.newArrayList<T?>(type.cast(value))
            }
            val strategy: ComputationStrategy<java.lang.RuntimeException?> =
                object : ComputationStrategy<java.lang.RuntimeException?>() {
                    public override fun compute(map: com.google.devtools.build.lib.packages.AttributeMap?): Any? {
                        return owner.getDefault(map)
                    }
                }
            // Note that this uses ArrayList instead of something like ImmutableList because some
            // values may be null.
            return java.util.ArrayList<T?>(
                strategy.computeValuesForAllCombinations<T?, java.lang.RuntimeException?>(
                    dependencies,
                    type,
                    rule,
                    ComputationLimiter { count: Int -> }).values()
            )
        }

        /** The list of configurable attributes this ComputedDefault declares it may read.  */
        fun dependencies(): com.google.common.collect.ImmutableList<String?> {
            return dependencies
        }

        /**
         * Return true if [.getDefault] can be safely called with a RawAttributeMapper.
         * 
         * 
         * Notably, this means [.getDefault] does not call [AttributeMap.get] on any
         * configurable attributes as they could potentially contain a SelectorList. In practice, only
         * call get on [Attribute.Builder.nonconfigurable] attributes unless you really know what
         * you are doing.
         */
        fun resolvableWithRawAttributes(): Boolean {
            return dependencies.isEmpty()
        }

        /**
         * Returns the value this [ComputedDefault] evaluates to, given the inputs contained in
         * `rule`.
         */
        abstract fun getDefault(rule: com.google.devtools.build.lib.packages.AttributeMap?): Any?

        companion object {
            private val dependenciesInterner: com.google.common.collect.Interner<com.google.common.collect.ImmutableList<String?>> =
                BlazeInterners.newWeakInterner()
        }
    }

    /**
     * A Starlark-defined computed default, which can be precomputed for a specific [Rule] by
     * calling [.computePossibleValues], which returns a [StarlarkComputedDefault] that
     * contains a lookup table.
     */
    class StarlarkComputedDefaultTemplate(
        type: com.google.devtools.build.lib.packages.Type<*>?,
        dependencies: com.google.common.collect.ImmutableList<String?>?,
        callback: StarlarkCallbackHelper?
    ) {
        private val type: com.google.devtools.build.lib.packages.Type<*>
        private val callback: StarlarkCallbackHelper
        private val dependencies: com.google.common.collect.ImmutableList<String?>

        /**
         * Creates a new StarlarkComputedDefaultTemplate that allows the computation of attribute values
         * via a callback function during loading phase.
         * 
         * @param type The type of the value of this attribute.
         * @param dependencies A list of all names of other attributes that are accessed by this
         * attribute.
         * @param callback A function to compute the actual attribute value.
         */
        init {
            this.type = com.google.common.base.Preconditions.checkNotNull(type)
            // Order is important for #createDependencyAssignmentTuple.
            this.dependencies =
                com.google.common.collect.Ordering.natural<Comparable<*>?>().immutableSortedCopy<String?>(
                    com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<String?>?>(
                        dependencies
                    )
                )
            this.callback = com.google.common.base.Preconditions.checkNotNull<StarlarkCallbackHelper>(callback)
        }

        /**
         * Returns a [StarlarkComputedDefault] containing a lookup table specifying the output of
         * this [StarlarkComputedDefaultTemplate]'s callback given each possible assignment `rule` might make to the attributes specified by [.dependencies].
         * 
         * 
         * If the rule is missing an attribute specified by [.dependencies], or if there are
         * too many possible assignments, or if any evaluation fails, this throws [ ].
         * 
         * 
         * May only be called after all non-[ComputedDefault] attributes have been set on the
         * `rule`.
         */
        @Throws(java.lang.InterruptedException::class, CannotPrecomputeDefaultsException::class)
        fun computePossibleValues(
            attr: Attribute, rule: RuleOrMacroInstance, eventHandler: EventHandler?
        ): StarlarkComputedDefault {
            val owner = this@StarlarkComputedDefaultTemplate
            val caughtEvalExceptionIfAny: AtomicReference<net.starlark.java.eval.EvalException?> =
                AtomicReference<net.starlark.java.eval.EvalException?>()
            val strategy: ComputationStrategy<java.lang.InterruptedException?> =
                object : ComputationStrategy<java.lang.InterruptedException?>() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun compute(map: com.google.devtools.build.lib.packages.AttributeMap): Any? {
                        try {
                            return owner.computeValue(eventHandler, map)
                        } catch (ex: net.starlark.java.eval.EvalException) {
                            caughtEvalExceptionIfAny.compareAndSet(null, ex)
                            return null
                        }
                    }
                }

            val dependencyTypesBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.packages.Type<*>?> =
                com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.packages.Type<*>?>()
            val lookupTable: MutableMap<MutableList<Any?>?, Any?>?
            try {
                for (dependency in dependencies) {
                    val attribute: Attribute? = rule.getAttributeProvider().getAttributeByNameMaybe(dependency)
                    if (attribute == null) {
                        throw com.google.devtools.build.lib.packages.Attribute.StarlarkComputedDefaultTemplate.AttributeNotFoundException(
                            java.lang.String.format("No such attribute %s in rule %s", dependency, rule.getLabel())
                        )
                    }
                    dependencyTypesBuilder.add(attribute.getType())
                }
                lookupTable =
                    HashMap<MutableList<Any?>?, Any?>(
                        strategy.computeValuesForAllCombinations(
                            dependencies, attr.getType(), rule, FixedComputationLimiter.Companion.INSTANCE
                        )
                    )
                if (caughtEvalExceptionIfAny.get() != null) {
                    throw caughtEvalExceptionIfAny.get()
                }
            } catch (ex: AttributeNotFoundException) {
                val msg: String? =
                    java.lang.String.format(
                        "Cannot compute default value of attribute '%s' in rule '%s': ",
                        attr.getPublicName(), rule.getLabel()
                    )
                val error = msg + ex.getMessage()
                rule.reportError(error, eventHandler)
                throw CannotPrecomputeDefaultsException(error)
            } catch (ex: TooManyConfigurableAttributesException) {
                val msg: String? =
                    java.lang.String.format(
                        "Cannot compute default value of attribute '%s' in rule '%s': ",
                        attr.getPublicName(), rule.getLabel()
                    )
                val error = msg + ex.getMessage()
                rule.reportError(error, eventHandler)
                throw CannotPrecomputeDefaultsException(error)
            } catch (ex: net.starlark.java.eval.EvalException) {
                val msg: String? =
                    java.lang.String.format(
                        "Cannot compute default value of attribute '%s' in rule '%s': ",
                        attr.getPublicName(), rule.getLabel()
                    )
                val error = msg + ex.getMessage()
                rule.reportError(error, eventHandler)
                throw CannotPrecomputeDefaultsException(error)
            }
            return StarlarkComputedDefault(dependencies, dependencyTypesBuilder.build(), lookupTable)
        }

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        private fun computeValue(
            eventHandler: EventHandler?,
            rule: com.google.devtools.build.lib.packages.AttributeMap
        ): Any? {
            val attrValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
            for (attrName in rule.getAttributeNames()) {
                val attr: Attribute? = rule.getAttributeDefinition(attrName)
                if (!attr!!.hasComputedDefault()) {
                    val value: Any? = rule.get(attrName, attr.getType())
                    if (!net.starlark.java.eval.Starlark.isNullOrNone(value)) {
                        // Some attribute values are not valid Starlark values:
                        // visibility is an ImmutableList, for example.
                        attrValues.put(
                            attr.getName(),
                            com.google.devtools.build.lib.packages.Attribute.Companion.valueToStarlark(value)
                        )
                    }
                }
            }
            return invokeCallback(eventHandler, attrValues)
        }

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        private fun invokeCallback(eventHandler: EventHandler?, attrValues: MutableMap<String?, Any?>?): Any? {
            val attrs: net.starlark.java.eval.Structure =
                StructProvider.Companion.STRUCT.create(
                    attrValues, "No such regular (non computed) attribute '%s'."
                )
            val uncheckedResult: Any = callback.call(eventHandler, attrs)
            try {
                val result: Any? =
                    type.cast(
                        if (uncheckedResult === net.starlark.java.eval.Starlark.NONE) type.getDefaultValue() else uncheckedResult
                    )
                // type.cast() for lists just ensures the returned result is a list, so we also need to
                // validate each element has the right subtype
                if (type is com.google.devtools.build.lib.packages.Type.ListType<*>) {
                    for (elem in (result as kotlin.collections.MutableList<*>?)!!) {
                        try {
                            val unused: Any? = type.getListElementType().cast(elem)
                        } catch (ex: java.lang.ClassCastException) {
                            throw net.starlark.java.eval.Starlark.errorf(
                                "expected '%s', but got '%s'",
                                type.getListElementType(),
                                net.starlark.java.eval.Starlark.type(elem)
                            )
                        }
                    }
                }
                return result
            } catch (ex: java.lang.ClassCastException) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "expected '%s', but got '%s'",
                    type,
                    net.starlark.java.eval.Starlark.type(uncheckedResult)
                )
            }
        }

        private class AttributeNotFoundException(message: String?) : java.lang.Exception(message)

        internal class CannotPrecomputeDefaultsException private constructor(message: String?) :
            java.lang.Exception(message)
    }

    /**
     * A class for computed attributes defined in Starlark.
     * 
     * 
     * Unlike [ComputedDefault], instances of this class contain a pre-computed table of all
     * possible assignments of depended-on attributes and what the Starlark function evaluates to, and
     * [.getPossibleValues] and [.getDefault] do lookups in that
     * table.
     */
    internal class StarlarkComputedDefault(
        dependencies: com.google.common.collect.ImmutableList<String?>?,
        dependencyTypes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Type<*>?>?,
        lookupTable: MutableMap<MutableList<Any?>?, Any?>?
    ) : ComputedDefault(
        com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<String?>?>(
            dependencies
        )
    ) {
        private val dependencyTypes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Type<*>?>
        private val lookupTable: MutableMap<MutableList<Any?>?, Any?>

        /**
         * Creates a new StarlarkComputedDefault containing a lookup table.
         * 
         * @param dependencies A list of all names of other attributes that are accessed by this
         * attribute.
         * @param dependencyTypes A list of requiredAttributes' types.
         * @param lookupTable An exhaustive mapping from requiredAttributes assignments to values this
         * computed default evaluates to.
         */
        init {
            this.dependencyTypes =
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Type<*>?>>(
                    dependencyTypesInterner.intern(dependencyTypes)
                )
            this.lookupTable =
                com.google.common.base.Preconditions.checkNotNull<MutableMap<MutableList<Any?>?, Any?>>(lookupTable)
        }

        fun getDependencyTypes(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Type<*>?> {
            return dependencyTypes
        }

        fun getLookupTable(): MutableMap<MutableList<Any?>?, Any?> {
            return lookupTable
        }

        override fun getDefault(rule: com.google.devtools.build.lib.packages.AttributeMap): Any? {
            val key = ComputationStrategy.Companion.createDependencyAssignmentTuple(dependencies(), rule)
            com.google.common.base.Preconditions.checkState(
                lookupTable.containsKey(key),
                "Error in rule '%s': precomputed value missing for dependencies: %s. Available keys: %s.",
                rule.describeRule(),
                com.google.common.collect.Iterables.toString(key),
                com.google.common.collect.Iterables.toString(lookupTable.keySet())
            )
            return lookupTable.get(key)
        }

        override fun <T> getPossibleValues(
            type: com.google.devtools.build.lib.packages.Type<T?>,
            rule: RuleOrMacroInstance?
        ): MutableList<T?> {
            val result: MutableList<T?> = java.util.ArrayList<T?>(lookupTable.size())
            for (obj in lookupTable.values()) {
                result.add(type.cast(obj))
            }
            return result
        }

        companion object {
            private val dependencyTypesInterner: com.google.common.collect.Interner<com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Type<*>?>?> =
                BlazeInterners.newWeakInterner()
        }
    }

    internal open class SimpleLateBoundDefault<FragmentT, ValueT>
    private constructor(
        fragmentClass: java.lang.Class<FragmentT?>?,
        defaultValueEvaluator: com.google.common.base.Function<RuleOrMacroInstance?, ValueT?>,
        private val resolver: Resolver<FragmentT?, ValueT?>
    ) : LateBoundDefault<FragmentT?, ValueT?>(fragmentClass, defaultValueEvaluator) {
        override fun resolve(
            rule: com.google.devtools.build.lib.packages.Rule?,
            attributes: com.google.devtools.build.lib.packages.AttributeMap?,
            input: FragmentT?
        ): ValueT? {
            return resolver.resolve(rule, attributes, input)
        }
    }

    // TODO(b/65746853): Remove documentation about accepting BuildConfigurationValue when uses are
    // cleaned
    // up.
    /**
     * Provider of values for late-bound attributes. See [ ][Attribute.Builder.value].
     * 
     * 
     * Use sparingly - having different values for attributes during loading and analysis can
     * confuse users.
     * 
     * @param <FragmentT> The type of value that is used to compute this value. This is usually a
     * subclass of BuildConfigurationValue.Fragment. It may also be Void to receive null, or
     * BuildConfigurationValue itself to receive the entire configuration.
     * @param <ValueT> The type of value returned by this class. Must be either [Void], a [     ], or a [List] of [Label] objects.
    </ValueT></FragmentT> */
    @javax.annotation.concurrent.Immutable
    abstract class LateBoundDefault<FragmentT, ValueT> protected constructor(
        fragmentClass: java.lang.Class<FragmentT?>?,
        defaultValueEvaluator: com.google.common.base.Function<RuleOrMacroInstance?, ValueT?>
    ) : net.starlark.java.eval.StarlarkValue {
        /**
         * Functional interface for computing the value of a late-bound attribute.
         * 
         * 
         * Implementations of this interface must be immutable.
         */
        fun interface Resolver<FragmentT, ValueT> {
            fun resolve(
                rule: com.google.devtools.build.lib.packages.Rule?,
                attributeMap: com.google.devtools.build.lib.packages.AttributeMap?,
                input: FragmentT?
            ): ValueT?
        }

        private val defaultValueEvaluator: com.google.common.base.Function<RuleOrMacroInstance?, ValueT?>
        private val fragmentClass: java.lang.Class<FragmentT?>?

        init {
            this.defaultValueEvaluator = defaultValueEvaluator
            this.fragmentClass = fragmentClass
        }

        /**
         * Returns the input type that the attribute expects. This is almost always a configuration
         * fragment to be retrieved from the target's configuration (or the exec configuration).
         * 
         * 
         * It may also be [Void] to receive null. This is rarely necessary, but can be used,
         * e.g., if the attribute is named to match an attribute in another rule which is late-bound.
         * 
         * 
         * It may also be BuildConfigurationValue to receive the entire configuration. This is
         * deprecated, and only necessary when the default is computed from methods of
         * BuildConfigurationValue itself.
         */
        fun getFragmentClass(): java.lang.Class<FragmentT?>? {
            return fragmentClass
        }

        /** The default value for the attribute that is set during the loading phase.  */
        fun getDefault(rule: RuleOrMacroInstance?): ValueT? {
            return defaultValueEvaluator.apply(rule)
        }

        /**
         * The actual value for the attribute for the analysis phase, which depends on the build
         * configuration. Note that configurations transitions are applied after the late-bound
         * attribute was evaluated.
         * 
         * @param rule the rule being evaluated
         * @param attributes interface for retrieving the values of the rule's other attributes
         * @param input the configuration fragment to evaluate with
         */
        @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
        abstract fun resolve(
            rule: com.google.devtools.build.lib.packages.Rule?,
            attributes: com.google.devtools.build.lib.packages.AttributeMap?,
            input: FragmentT?
        ): ValueT?

        companion object {
            /**
             * Creates a new LateBoundDefault which always returns the given value.
             * 
             * 
             * This is used primarily for matching names with late-bound attributes on other rules and
             * for testing. Use normal default values if the name does not matter.
             */
            @com.google.common.annotations.VisibleForTesting
            fun fromConstantForTesting(defaultValue: Label?): LabelLateBoundDefault<java.lang.Void?> {
                return object : LabelLateBoundDefault<java.lang.Void?>(
                    java.lang.Void::class.java,
                    com.google.common.base.Function { rule: RuleOrMacroInstance? ->
                        com.google.common.base.Preconditions.checkNotNull<Label?>(
                            defaultValue
                        )
                    },
                    com.google.devtools.build.lib.packages.Attribute.LateBoundDefault.Resolver { rule: com.google.devtools.build.lib.packages.Rule?, attributes: com.google.devtools.build.lib.packages.AttributeMap?, unused: java.lang.Void? -> defaultValue }) {}
            }

            /**
             * Creates a new LateBoundDefault which always returns null.
             * 
             * 
             * This is used primarily for matching names with late-bound attributes on other rules and
             * for testing. Use normal default values if the name does not matter.
             */
            // bivariant implementation
            @kotlin.jvm.JvmStatic
            fun <ValueT> alwaysNull(): LateBoundDefault<java.lang.Void?, ValueT?> {
                return AlwaysNullLateBoundDefault.INSTANCE as LateBoundDefault<java.lang.Void?, ValueT?>
            }
        }
    }

    /**
     * An abstract [LateBoundDefault] class so that `StarlarkLateBoundDefault` can derive
     * from [LateBoundDefault] without compromising the type-safety of the second generic
     * parameter to [LateBoundDefault].
     */
    abstract class AbstractLabelLateBoundDefault<FragmentT>
    protected constructor(fragmentClass: java.lang.Class<FragmentT?>?, defaultValue: Label?) :
        LateBoundDefault<FragmentT?, Label?>(
            fragmentClass,
            com.google.common.base.Function { rule: RuleOrMacroInstance? -> defaultValue } as com.google.common.base.Function<RuleOrMacroInstance?, Label?>)

    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal object AlwaysNullLateBoundDefault : SimpleLateBoundDefault<java.lang.Void?, java.lang.Void?>() {
        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val INSTANCE: AlwaysNullLateBoundDefault = AlwaysNullLateBoundDefault()
    }

    /** A [LateBoundDefault] for a [Label].  */
    open class LabelLateBoundDefault<FragmentT>
    @com.google.common.annotations.VisibleForTesting protected constructor(
        fragmentClass: java.lang.Class<FragmentT?>?,
        defaultValueEvaluator: com.google.common.base.Function<RuleOrMacroInstance?, Label?>,
        resolver: Resolver<FragmentT?, Label?>
    ) : SimpleLateBoundDefault<FragmentT?, Label?>(fragmentClass, defaultValueEvaluator, resolver) {
        companion object {
            /**
             * Creates a new LabelLateBoundDefault which uses the rule, its configured attributes, and a
             * fragment of the target configuration.
             * 
             * 
             * Note that the configuration fragment here does not take into account any transitions that
             * are on the attribute with this LabelLateBoundDefault as its value. The configuration will be
             * the same as the configuration given to the target bearing the attribute.
             * 
             * 
             * Nearly all LateBoundDefaults should use this constructor or [ ][LabelListLateBoundDefault.fromTargetConfiguration]. There are few situations where it isn't
             * the appropriate option.
             * 
             * 
             * If you want to decide an attribute's value based on the value of its other attributes, use
             * a subclass of [ComputedDefault]. The only time you should need [ ][LabelListLateBoundDefault.fromRuleAndAttributesOnly] is if you need access to three or more
             * configurable attributes, or if you need to match names with a late-bound attribute on another
             * rule.
             * 
             * 
             * If you have a constant-valued attribute, but you need it to have the same name as an
             * attribute on another rule which is late-bound, use [.alwaysNull].
             * 
             * @param fragmentClass The fragment to receive from the target configuration. May also be
             * BuildConfigurationValue.class to receive the entire configuration (deprecated) - in this
             * case, you must only use methods of BuildConfigurationValue itself, and not use any
             * fragments.
             * @param defaultValue The default [Label] to return at loading time, when the
             * configuration is not available.
             * @param resolver A function which will compute the actual value with the configuration.
             */
            fun <FragmentT> fromTargetConfiguration(
                fragmentClass: java.lang.Class<FragmentT?>, defaultValue: Label?, resolver: Resolver<FragmentT?, Label?>
            ): LabelLateBoundDefault<FragmentT?> {
                return fromTargetConfigurationWithRuleBasedDefault<FragmentT?>(
                    fragmentClass,
                    com.google.common.base.Function { rule: RuleOrMacroInstance? -> defaultValue } as com.google.common.base.Function<RuleOrMacroInstance?, Label?>,
                    resolver)
            }

            /**
             * Variant of [.fromTargetConfiguration] that can read the rule instance to determine the
             * default value (e.g. by reading an attribute).
             * 
             * 
             * Has a different name than [.fromTargetConfiguration] because many callers to [ ][.fromTargetConfiguration] pass a null value to the `defaultValue` parameter, which
             * makes a proper method overload ambiguous.
             */
            fun <FragmentT>
                    fromTargetConfigurationWithRuleBasedDefault(
                fragmentClass: java.lang.Class<FragmentT?>,
                defaultValueEvaluator: com.google.common.base.Function<RuleOrMacroInstance?, Label?>,
                resolver: Resolver<FragmentT?, Label?>
            ): LabelLateBoundDefault<FragmentT?> {
                com.google.common.base.Preconditions.checkArgument(
                    fragmentClass != java.lang.Void::class.java,
                    "Use fromRuleAndAttributesOnly to specify a LateBoundDefault which does not use "
                            + "configuration."
                )
                return LabelLateBoundDefault<FragmentT?>(fragmentClass, defaultValueEvaluator, resolver)
            }
        }
    }

    /** A [LateBoundDefault] for a [List] of [Label] objects.  */
    class LabelListLateBoundDefault<FragmentT>
    private constructor(
        fragmentClass: java.lang.Class<FragmentT?>?,
        resolver: Resolver<FragmentT?, MutableList<Label?>?>
    ) : SimpleLateBoundDefault<FragmentT?, MutableList<Label?>?>(
        fragmentClass,
        com.google.common.base.Function { rule: RuleOrMacroInstance? -> com.google.common.collect.ImmutableList.of<Label?>() } as com.google.common.base.Function<RuleOrMacroInstance?, MutableList<Label?>?>,
        resolver) {
        companion object {
            fun <FragmentT> fromTargetConfiguration(
                fragmentClass: java.lang.Class<FragmentT?>, resolver: Resolver<FragmentT?, MutableList<Label?>?>
            ): LabelListLateBoundDefault<FragmentT?> {
                com.google.common.base.Preconditions.checkArgument(
                    fragmentClass != java.lang.Void::class.java,
                    "Use fromRuleAndAttributesOnly to specify a LateBoundDefault which does not use "
                            + "configuration."
                )
                return LabelListLateBoundDefault<FragmentT?>(fragmentClass, resolver)
            }

            /**
             * Creates a new LabelListLateBoundDefault which uses only the rule and its configured
             * attributes.
             * 
             * 
             * This should only be necessary in very specialized cases. In almost all cases, you don't
             * need this method, just use [ComputedDefault].
             * 
             * 
             * This is used primarily for computing values based on three or more configurable attributes
             * and/or matching names with late-bound attributes on other rules.
             * 
             * @param resolver A function which will compute the actual value with the configuration.
             */
            fun fromRuleAndAttributesOnly(
                resolver: Resolver<java.lang.Void?, MutableList<Label?>?>
            ): LabelListLateBoundDefault<java.lang.Void?> {
                return LabelListLateBoundDefault<java.lang.Void?>(java.lang.Void::class.java, resolver)
            }
        }
    }

    @kotlin.jvm.JvmField
    private val name: String

    @kotlin.jvm.JvmField
    private val doc: String?

    private val type: com.google.devtools.build.lib.packages.Type<*>

    private val propertyFlags: MutableSet<PropertyFlag?>

    // The default value, either as specified in the attribute definition, or else as given by
    // Type.getDefaultValue (which may be null).
    //
    // Exactly one of these conditions is true:
    // 1. defaultValue == null.
    // 2. defaultValue instanceof ComputedDefault &&
    //    type.isValid(defaultValue.getDefault())
    // 3. defaultValue instanceof StarlarkComputedDefaultTemplate &&
    //    type.isValid(defaultValue.computePossibleValues().getDefault())
    // 4. type.isValid(defaultValue).
    // 5. defaultValue instanceof LateBoundDefault &&
    //    type.isValid(defaultValue.getDefault(configuration))
    // (We assume a hypothetical Type.isValid(Object) predicate.)
    @kotlin.jvm.JvmField
    private val defaultValue: Any?

    private val transitionFactory: TransitionFactory<AttributeTransitionData?>

    /**
     * For label or label-list attributes, this predicate returns which rule classes are allowed for
     * the targets in the attribute.
     */
    private val allowedRuleClassesForLabels: RuleClassNamePredicate

    /**
     * For label or label-list attributes, this predicate returns which rule classes are allowed for
     * the targets in the attribute with warning.
     */
    private val allowedRuleClassesForLabelsWarning: RuleClassNamePredicate

    /**
     * For label or label-list attributes, this predicate returns which file types are allowed for
     * targets in the attribute that happen to be file targets (rather than rules).
     */
    private val allowedFileTypesForLabels: FileTypeSet?

    private val allowedValues: PredicateWithMessage<Any?>?

    private val requiredProviders: RequiredProviders

    private val aspects: AspectsList

    @Transient
    private val hashCode: Int

    /**
     * Constructs a rule attribute with the specified name, type and default value.
     * 
     * @param name the name of the attribute
     * @param type the type of the attribute
     * @param defaultValue the default value to use for this attribute if none is specified in rule
     * declaration in the BUILD file. Must be null, or of type "type". May be an instance of
     * ComputedDefault, in which case its getDefault() method must return an instance of "type",
     * or null. Must be immutable.
     * @param transitionFactory the configuration transition for this attribute (which must be of type
     * LABEL, LABEL_LIST, NODEP_LABEL or NODEP_LABEL_LIST).
     */
    init {
        com.google.common.base.Preconditions.checkArgument(
            NoTransition.isInstance(transitionFactory)
                    || type.getLabelClass() == LabelClass.DEPENDENCY || type.getLabelClass() == LabelClass.NONDEP_REFERENCE,
            "Configuration transitions can only be specified for label or label list attributes"
        )
        com.google.common.base.Preconditions.checkArgument(
            com.google.devtools.build.lib.packages.Attribute.Companion.isAnalysisDependent(name)
                    == (defaultValue is LateBoundDefault<*, *>
                    || defaultValue is MaterializingDefault<*, *>),
            "analysis dependent attributes require a default value that is one (and vice versa): %s",
            name
        )
        this.name = name
        this.doc = doc
        this.type = type
        this.propertyFlags = propertyFlags
        this.defaultValue = defaultValue
        this.transitionFactory = transitionFactory
        this.allowedRuleClassesForLabels = allowedRuleClassesForLabels
        this.allowedRuleClassesForLabelsWarning = allowedRuleClassesForLabelsWarning
        this.allowedFileTypesForLabels = allowedFileTypesForLabels
        this.allowedValues = allowedValues
        this.requiredProviders = requiredProviders
        this.aspects = aspects
        this.hashCode =
            java.util.Objects.hash(
                name,
                doc,
                type,
                propertyFlags,
                defaultValue,
                transitionFactory,
                allowedRuleClassesForLabels,
                allowedRuleClassesForLabelsWarning,
                allowedFileTypesForLabels,
                allowedValues,
                requiredProviders,
                aspects
            )
    }

    /** Returns the name of this attribute.  */
    fun getName(): String {
        return name
    }

    /** Returns the doc string for that attribute, if any.  */
    fun getDoc(): String? {
        return doc
    }

    /**
     * Returns the public name of this attribute. This is the name we use in Starlark code and we can
     * use it to display to the end-user. Implicit and late-bound attributes start with '_' (instead
     * of '$' or ':').
     */
    fun getPublicName(): String? {
        return com.google.devtools.build.lib.packages.Attribute.Companion.getStarlarkName(name)
    }

    /**
     * Returns the logical type of this attribute. (May differ from the actual representation as a
     * value in the build interpreter; for example, an attribute may logically be a list of labels,
     * but be represented as a list of strings.)
     */
    fun getType(): com.google.devtools.build.lib.packages.Type<*> {
        return type
    }

    private fun getPropertyFlag(flag: PropertyFlag?): Boolean {
        return propertyFlags.contains(flag)
    }

    /** Returns true if this parameter is mandatory.  */
    fun isMandatory(): Boolean {
        return getPropertyFlag(PropertyFlag.MANDATORY)
    }

    /** Returns true if this list parameter cannot have an empty list as a value.  */
    fun isNonEmpty(): Boolean {
        return getPropertyFlag(PropertyFlag.NON_EMPTY)
    }

    /** Returns true if this label parameter must produce a single artifact.  */
    fun isSingleArtifact(): Boolean {
        return getPropertyFlag(PropertyFlag.SINGLE_ARTIFACT)
    }

    /** Returns true if this label type parameter is checked by silent ruleclass filtering.  */
    fun isSilentRuleClassFilter(): Boolean {
        return getPropertyFlag(PropertyFlag.SILENT_RULECLASS_FILTER)
    }

    /**
     * Returns whether the dependencies through this attribute are accessible during dependency
     * resolution.
     * 
     * 
     * Only makes sense for attributes where `getType().getLabelClass()` is `DEPENDENCY`. Non-dependency attributes (non-label ones and label ones with a different label
     * class) are always accessible during dependency resolution.
     */
    fun isForDependencyResolution(): Boolean {
        return getPropertyFlag(PropertyFlag.FOR_DEPENDENCY_RESOLUTION)
    }

    fun forDependencyResolutionExplicitlySet(): Boolean {
        return getPropertyFlag(PropertyFlag.FOR_DEPENDENCY_RESOLUTION_EXPLICITLY_SET)
    }

    fun skipValidations(): Boolean {
        return getPropertyFlag(PropertyFlag.SKIP_VALIDATIONS)
    }

    /** Returns true if this label type parameter skips the analysis time filetype check.  */
    fun isSkipAnalysisTimeFileTypeCheck(): Boolean {
        return getPropertyFlag(PropertyFlag.SKIP_ANALYSIS_TIME_FILETYPE_CHECK)
    }

    /** Returns true if this parameter is order-independent.  */
    fun isOrderIndependent(): Boolean {
        return getPropertyFlag(PropertyFlag.ORDER_INDEPENDENT)
    }

    /** Returns true if output_licenses should be used for checking licensing.  */
    fun useOutputLicenses(): Boolean {
        return getPropertyFlag(PropertyFlag.OUTPUT_LICENSES)
    }

    /**
     * Returns true if this attribute uses a starlark-defined, non analysis-test configuration
     * transition. Starlark-defined analysis-test configuration transitions are handled separately.
     * See [.hasAnalysisTestTransition].
     */
    fun hasStarlarkDefinedTransition(): Boolean {
        return getPropertyFlag(PropertyFlag.HAS_STARLARK_DEFINED_TRANSITION)
    }

    /**
     * Returns true if this attributes uses Starlark-defined configuration transition designed
     * specifically for rules which run analysis tests.
     */
    fun hasAnalysisTestTransition(): Boolean {
        return getPropertyFlag(PropertyFlag.HAS_ANALYSIS_TEST_TRANSITION)
    }

    /**
     * Returns the configuration transition factory for this attribute for label or label list
     * attributes. For other attributes it will always return `NONE`.
     */
    fun getTransitionFactory(): TransitionFactory<AttributeTransitionData?> {
        return transitionFactory
    }

    /**
     * Returns whether the target is required to be executable for label or label list attributes. For
     * other attributes it always returns `false`.
     */
    fun isExecutable(): Boolean {
        return getPropertyFlag(PropertyFlag.EXECUTABLE)
    }

    /** Returns `true` iff the rule is a direct input for an action.  */
    fun isDirectCompileTimeInput(): Boolean {
        return getPropertyFlag(PropertyFlag.DIRECT_COMPILE_TIME_INPUT)
    }

    /** Returns `true` iff this attribute requires documentation.  */
    fun isDocumented(): Boolean {
        return !getPropertyFlag(PropertyFlag.UNDOCUMENTED)
    }

    /**
     * Returns `true` iff this attribute should be published to the rule's tag set. Note that
     * not all Type classes support tag conversion.
     */
    fun isTaggable(): Boolean {
        return getPropertyFlag(PropertyFlag.TAGGABLE)
    }

    fun isStrictLabelCheckingEnabled(): Boolean {
        return getPropertyFlag(PropertyFlag.STRICT_LABEL_CHECKING)
    }

    /** Returns true if the value of this attribute should be a part of a given set.  */
    fun checkAllowedValues(): Boolean {
        return getPropertyFlag(PropertyFlag.CHECK_ALLOWED_VALUES)
    }

    fun performPrereqValidatorCheck(): Boolean {
        return !getPropertyFlag(PropertyFlag.SKIP_PREREQ_VALIDATOR_CHECKS)
    }

    fun checkConstraintsOverride(): Boolean {
        return getPropertyFlag(PropertyFlag.CHECK_CONSTRAINTS_OVERRIDE)
    }

    fun skipConstraintsOverride(): Boolean {
        return getPropertyFlag(PropertyFlag.SKIP_CONSTRAINTS_OVERRIDE)
    }

    /** Returns true if this attribute's value can be influenced by the build configuration.  */
    fun isConfigurable(): Boolean {
        // Output types are excluded because of Rule#populateExplicitOutputFiles.
        return type.getLabelClass() != LabelClass.OUTPUT
                && !getPropertyFlag(PropertyFlag.NONCONFIGURABLE)
    }

    /** Returns true if the "configurable" attribute parameter was user-set  */
    fun configurableAttrWasUserSet(): Boolean {
        return getPropertyFlag(PropertyFlag.CONFIGURABLE_ATTR_WAS_USER_SET)
    }

    /**
     * Returns true if this attribute is used as a tool dependency, either because the attribute
     * declares it directly (with [Attribute.Builder.tool]), or because the value's [ ] declares it.
     * 
     * 
     * Non-dependency attributes will always return `false`.
     */
    fun isToolDependency(): Boolean {
        if (type.getLabelClass() != LabelClass.DEPENDENCY) {
            return false
        }
        if (getPropertyFlag(PropertyFlag.IS_TOOL_DEPENDENCY)) {
            return true
        }
        return transitionFactory.isTool()
    }

    /**
     * Returns true if this attribute was defined using Starlark's `attrs` module.
     * 
     * 
     * This may be used as a hint by documentation generators; for example, in the documentation
     * for a Starlark rule, we may want to fully document the Starlark-defined attributes set via
     * `rule(attrs=...)`), but skip or abbreviate documentation for implicitly added
     * non-Starlark attributes like "tags" and "testonly".
     */
    fun starlarkDefined(): Boolean {
        return getPropertyFlag(PropertyFlag.STARLARK_DEFINED)
    }

    /**
     * Returns a predicate that evaluates to true for rule classes that are allowed labels in this
     * attribute. If this is not a label or label-list attribute, the returned predicate always
     * evaluates to true.
     * 
     * 
     * NOTE: This may return Predicates.<RuleClass>alwaysTrue() as a sentinel meaning "do the right
     * thing", rather than actually allowing all rule classes in that attribute. Others parts of bazel
     * code check for that specific instance.
    </RuleClass> */
    fun getAllowedRuleClassObjectPredicate(): com.google.common.base.Predicate<RuleClass?>? {
        return allowedRuleClassesForLabels.asPredicateOfRuleClassObject()
    }

    fun getAllowedRuleClassPredicate(): com.google.common.base.Predicate<String?>? {
        return allowedRuleClassesForLabels.asPredicateOfRuleClass()
    }

    /**
     * Returns a predicate that evaluates to true for rule classes that are allowed labels in this
     * attribute with warning. If this is not a label or label-list attribute, the returned predicate
     * always evaluates to true.
     */
    fun getAllowedRuleClassObjectWarningPredicate(): com.google.common.base.Predicate<RuleClass?>? {
        return allowedRuleClassesForLabelsWarning.asPredicateOfRuleClassObject()
    }

    fun getAllowedRuleClassWarningPredicate(): com.google.common.base.Predicate<String?>? {
        return allowedRuleClassesForLabelsWarning.asPredicateOfRuleClass()
    }

    fun getRequiredProviders(): RequiredProviders {
        return requiredProviders
    }

    fun getAllowedFileTypesPredicate(): FileTypeSet? {
        return allowedFileTypesForLabels
    }

    fun getAllowedValues(): PredicateWithMessage<Any?>? {
        return allowedValues
    }

    fun hasAspects(): Boolean {
        return aspects.hasAspects()
    }

    /** Returns the list of aspects required for dependencies through this attribute.  */
    fun getAspects(rule: com.google.devtools.build.lib.packages.Rule?): com.google.common.collect.ImmutableList<Aspect?>? {
        return aspects.getAspects(rule)
    }

    fun getAspectsList(): AspectsList {
        return aspects
    }

    fun getAspectClasses(): com.google.common.collect.ImmutableList<AspectClass?> {
        return aspects.getAspectClasses()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    fun validateRulePropagatedAspectsParameters(ruleClass: RuleClass?) {
        aspects.validateRulePropagatedAspectsParameters(ruleClass)
    }

    /**
     * Returns the default value of this attribute. If no default was given by the attribute schema,
     * this is just the default of the type ([Type.getDefaultValue]).
     * 
     * 
     * The result may be null, for instance when the schema does not specify any default value and
     * the attribute is of LabelType. In Starlark, null is typically converted to None.
     * 
     * 
     * During population of the rule's attribute dictionary, all non-computed defaults must be set
     * before all computed ones.
     * 
     * @param rule the rule this attribute is attached to, if one exists. Otherwise null. Aspect
     * attributes, for example, aren't associated with rules. The [LabelLateBoundDefault]'s
     * author is responsible for ensuring null inputs work properly: either [     ][LateBoundDefault.getDefaultValue] works on null inputs or the attribute is known to
     * always be attached to rules.
     */
    fun getDefaultValue(rule: RuleOrMacroInstance?): Any? {
        if (defaultValue is LateBoundDefault<*, *>) {
            return defaultValue.getDefault(rule)
        } else {
            return defaultValue
        }
    }

    /**
     * Returns the default value of this attribute, even if it is a computed default, or a late-bound
     * default.
     */
    fun getDefaultValueUnchecked(): Any? {
        return defaultValue
    }

    fun getLateBoundDefault(): LateBoundDefault<*, *>? {
        return defaultValue as LateBoundDefault<*, *>?
    }

    fun getMaterializer(): MaterializingDefault<*, *> {
        com.google.common.base.Preconditions.checkState(isMaterializing())
        return defaultValue as MaterializingDefault<*, *>
    }

    /**
     * Returns true iff this attribute has a computed default.
     * 
     * @see .getDefaultValue
     */
    fun hasComputedDefault(): Boolean {
        return defaultValue is ComputedDefault
                || defaultValue is StarlarkComputedDefaultTemplate
    }

    fun isPublic(): Boolean {
        return !com.google.devtools.build.lib.packages.Attribute.Companion.isPrivateAttribute(name)
    }

    /**
     * Returns if this attribute is an implicit dependency according to the naming policy that
     * designates implicit attributes.
     */
    fun isImplicit(): Boolean {
        return com.google.devtools.build.lib.packages.Attribute.Companion.isImplicit(name)
    }

    /**
     * Returns if this attribute is late-bound according to the naming policy that designates
     * late-bound attributes.
     */
    fun isLateBound(): Boolean {
        return defaultValue is LateBoundDefault<*, *>
    }

    fun isMaterializing(): Boolean {
        return defaultValue is MaterializingDefault<*, *>
    }

    /**
     * Throws Eval exception if this attribute cannot override another one using Starlark rule
     * extensions.
     * 
     * 
     * Starlark rule extension only allow to override aspects and default value.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun failIfNotAValidOverride() {
        com.google.devtools.build.lib.packages.Attribute.Companion.failIf(
            allowedRuleClassesForLabels != com.google.devtools.build.lib.packages.Attribute.Companion.ANY_RULE,
            "attribute `%s`: can't override allowed rule classes",
            name
        )
        com.google.devtools.build.lib.packages.Attribute.Companion.failIf(
            allowedRuleClassesForLabelsWarning != com.google.devtools.build.lib.packages.Attribute.Companion.NO_RULE,
            "attribute `%s`: can't override allowed rule classes",
            name
        )
        com.google.devtools.build.lib.packages.Attribute.Companion.failIf(
            !NoTransition.isInstance(transitionFactory),
            "attribute `%s`: can't override configuration transition",
            name
        )
        com.google.devtools.build.lib.packages.Attribute.Companion.failIf(
            allowedFileTypesForLabels !== FileTypeSet.NO_FILE,
            "attribute `%s`: can't override allowed files",
            name
        )
        com.google.devtools.build.lib.packages.Attribute.Companion.failIf(
            !requiredProviders.acceptsAny(), "attribute `%s`: can't override required providers", name
        )
        com.google.devtools.build.lib.packages.Attribute.Companion.failIf(
            propertyFlags != com.google.common.collect.ImmutableSet.of<PropertyFlag?>(
                PropertyFlag.STARLARK_DEFINED,
                PropertyFlag.STRICT_LABEL_CHECKING
            ),
            "attribute `%s`: can't have additional flags",
            name
        ) // mandatory?*/
    }

    override fun toString(): String {
        return "Attribute(" + name + ", " + type + ")"
    }

    override fun compareTo(other: Attribute): Int {
        return name.compareTo(other.name)
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || getClass() != o.getClass()) {
            return false
        }
        val attribute = o as Attribute
        return hashCode == attribute.hashCode && name == attribute.name
                && doc == attribute.doc
                && type == attribute.type
                && propertyFlags == attribute.propertyFlags
                && defaultValue == attribute.defaultValue
                && transitionFactory == attribute.transitionFactory
                && allowedRuleClassesForLabels == attribute.allowedRuleClassesForLabels
                && allowedRuleClassesForLabelsWarning == attribute.allowedRuleClassesForLabelsWarning
                && allowedFileTypesForLabels == attribute.allowedFileTypesForLabels
                && allowedValues == attribute.allowedValues
                && requiredProviders == attribute.requiredProviders
                && aspects == attribute.aspects
    }

    override fun hashCode(): Int {
        return hashCode
    }

    /** Returns a replica builder of this Attribute.  */
    fun <TypeT> cloneBuilder(tp: com.google.devtools.build.lib.packages.Type<TypeT?>?): Builder<TypeT?> {
        com.google.common.base.Preconditions.checkArgument(tp === this.type)
        val builder: Builder<TypeT?> = com.google.devtools.build.lib.packages.Attribute.Builder<TypeT?>(name, tp)
        builder.doc = doc
        builder.allowedFileTypesForLabels = allowedFileTypesForLabels
        builder.allowedRuleClassesForLabels = allowedRuleClassesForLabels
        builder.allowedRuleClassesForLabelsWarning = allowedRuleClassesForLabelsWarning
        builder.requiredProvidersBuilder = requiredProviders.copyAsBuilder()
        builder.transitionFactory = transitionFactory
        builder.propertyFlags =
            com.google.common.collect.Sets.newEnumSet<PropertyFlag?>(propertyFlags, PropertyFlag::class.java)
        builder.value = defaultValue
        builder.valueSet = true
        builder.allowedValues = allowedValues
        builder.aspectsListBuilder = com.google.devtools.build.lib.packages.AspectsList.Builder(aspects)

        return builder
    }

    fun cloneBuilder(): Builder<*> {
        return cloneBuilder(this.type)
    }

    companion object {
        @kotlin.jvm.JvmField
        val ANY_RULE: RuleClassNamePredicate = RuleClassNamePredicate.Companion.unspecified()

        private val NO_RULE: RuleClassNamePredicate = RuleClassNamePredicate.Companion.only()

        /**
         * Creates a new attribute builder.
         * 
         * @param name attribute name
         * @param type attribute type
         * @return attribute builder
         * @param <TYPE> attribute type class
        </TYPE> */
        fun <TYPE> attr(name: String?, type: com.google.devtools.build.lib.packages.Type<TYPE?>?): Builder<TYPE?> {
            return com.google.devtools.build.lib.packages.Attribute.Builder<TYPE?>(name, type)
        }

        /**
         * Returns if an attribute with the given name is an implicit dependency according to the naming
         * policy that designates implicit attributes.
         */
        fun isImplicit(name: String): Boolean {
            return name.startsWith("$")
        }

        /**
         * Returns if an attribute with the given name is late-bound according to the naming policy that
         * designates late-bound attributes.
         */
        @kotlin.jvm.JvmStatic
        fun isAnalysisDependent(name: String): Boolean {
            return name.startsWith(":")
        }

        /** Returns whether this attribute is considered private in Starlark.  */
        private fun isPrivateAttribute(nativeAttrName: String): Boolean {
            return com.google.devtools.build.lib.packages.Attribute.Companion.isAnalysisDependent(nativeAttrName) || com.google.devtools.build.lib.packages.Attribute.Companion.isImplicit(
                nativeAttrName
            )
        }

        /**
         * Returns the Starlark-usable name of this attribute.
         * 
         * 
         * Implicit and late-bound attributes start with '_' (instead of '$' or ':').
         */
        fun getStarlarkName(nativeAttrName: String): String? {
            if (com.google.devtools.build.lib.packages.Attribute.Companion.isPrivateAttribute(nativeAttrName)) {
                return "_" + nativeAttrName.substring(1)
            }
            return nativeAttrName
        }

        @com.google.errorprone.annotations.FormatMethod
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun failIf(condition: Boolean, message: String?, vararg args: Any?) {
            if (condition) {
                throw net.starlark.java.eval.Starlark.errorf(message, *args)
            }
        }

        /**
         * Converts a rule or macro attribute value from internal form to Starlark form. Internal form may
         * use any subtype of [List] or [Map] for `list` and `dict` attributes,
         * whereas Starlark uses only immutable [net.starlark.java.eval.StarlarkList] and [ ].
         * 
         * 
         * The conversion is similar to [Starlark.fromJava] for all types except `attr.string_list_dict` (`Map<String, List<String>>`) and [ ][BuildType.LABEL_LIST_DICT], for which fromJava does not recursively convert elements. (Doing so
         * is expensive.)
         * 
         * 
         * It is tempting to require that attributes are stored internally in Starlark form. However, a
         * number of obstacles would need to be overcome:
         * 
         * 
         *  1. Some obscure attribute types such as TRISTATE and DISTRIBUTION are not currently legal
         * Starlark values.
         *  1. ImmutableList is significantly more compact than StarlarkList for small lists (n &lt; 2).
         * StarlarkList would need multiple representations and a builder to achieve parity.
         *  1. The types used by the Type mechanism would need changing; this has extensive
         * ramifications.
         * 
         */
        // TODO: b/403344971 - This a duplicate of StarlarkNativeModule.starlarkifyValue, with confusing
        // skew! Try to merge the two.
        @kotlin.jvm.JvmStatic
        fun valueToStarlark(x: Any?): Any? {
            if (x is MutableMap<*, *>) {
                // Is x a non-empty {label,string}_list_dict?
                if (!x.isEmpty() && x.values().iterator().next() is MutableList<*>) {
                    // Recursively convert subelements.
                    val dict: net.starlark.java.eval.Dict.Builder<Any?, Any?> =
                        net.starlark.java.eval.Dict.builder<Any?, Any?>()
                    x.forEach { key: Any?, value: Any? ->
                        dict.put(
                            key,
                            net.starlark.java.eval.Starlark.fromJava(value, null)
                        )
                    }
                    return dict.buildImmutable()
                }
            } else if (x is MutableSet<*>) {
                // Until Starlark gains a set data type, shallow-convert Java sets (e.g. DISTRIBUTION values)
                // to Starlark lists.
                return net.starlark.java.eval.StarlarkList.immutableCopyOf(x)
            } else if (x is com.google.devtools.build.lib.packages.TriState) {
                // Convert TriState to integer (same as in query output and native.existing_rules())
                return net.starlark.java.eval.Starlark.fromJava(x.toInt(),  /* mutability= */null)
            } else if (x is BuildType.SelectorList<*>) {
                val selectors: MutableList<Any?> = java.util.ArrayList<Any?>()
                for (selector in x.getSelectors()) {
                    val m: com.google.common.collect.ImmutableMap.Builder<Any?, Any?> =
                        com.google.common.collect.ImmutableMap.builderWithExpectedSize<Any?, Any?>(selector.getNumEntries())
                    selector.forEach { rawKey: Label?, rawValue: Any? ->
                        val key: Any? =
                            com.google.devtools.build.lib.packages.Attribute.Companion.valueToStarlark(rawKey)
                        // BuildType.Selector constructor transforms `None` values of selector branches into
                        // the default value of the attribute's type. We need to reverse this transformation,
                        // but leave alone cases where the select value was actually set to the default value.
                        val mapVal: Any? =
                            if (!selector.isValueSet(rawKey)) net.starlark.java.eval.Starlark.NONE else com.google.devtools.build.lib.packages.Attribute.Companion.valueToStarlark(
                                rawValue
                            )
                        m.put(key, mapVal)
                    }
                    val selectorDict: com.google.common.collect.ImmutableMap<Any?, Any?> = m.buildKeepingLast()
                    if (!selectorDict.isEmpty()) {
                        selectors.add(SelectorValue(selectorDict, selector.getNoMatchError()))
                    }
                }
                try {
                    return com.google.devtools.build.lib.packages.SelectorList.Companion.of(selectors)
                } catch (e: net.starlark.java.eval.EvalException) {
                    // This would happen if we were trying to create a SelectorList containing different types
                    // of selects (e.g. list select, string select). This should never happen because we are
                    // converting from a valid Native select.
                    throw java.lang.IllegalStateException(e)
                }
            }

            // For all other attribute values, shallow conversion is safe.
            return net.starlark.java.eval.Starlark.fromJava(x,  /* mutability= */null)
        }
    }
}
