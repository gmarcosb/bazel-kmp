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
 * Instances of RuleClass encapsulate the set of attributes of a given "class" of rule, such as
 * `cc_binary`.
 * 
 * 
 * This is an instance of the "meta-class" pattern for Rules: we achieve using *values* what
 * subclasses achieve using *types*. (The "Design Patterns" book doesn't include this pattern,
 * so think of it as something like a cross between a Flyweight and a State pattern. Like Flyweight,
 * we avoid repeatedly storing data that belongs to many instances. Like State, we delegate from
 * Rule to RuleClass for the specific behavior of that rule (though unlike state, a Rule object
 * never changes its RuleClass). This avoids the need to declare one Java class per class of Rule,
 * yet achieves the same behavior.)
 * 
 * 
 * The use of a metaclass also allows us to compute a mapping from Attributes to small integers
 * and share this between all rules of the same metaclass. This means we can save the attribute
 * dictionary for each rule instance using an array, which is much more compact than a hashtable.
 * 
 * 
 * Rule classes whose names start with "$" are considered "abstract"; since they are not valid
 * identifiers, they cannot be named in the build language. However, they are useful for grouping
 * related attributes which are inherited.
 * 
 * 
 * The exact values in this class are important. In particular:
 * 
 * 
 *  * Changing an attribute from MANDATORY to OPTIONAL creates the potential for null-pointer
 * exceptions in code that expects a value.
 *  * Attributes whose names are preceded by a "$" or a ":" are "hidden", and cannot be redefined
 * in a BUILD file. They are a useful way of adding a special dependency. By convention,
 * attributes starting with "$" are implicit dependencies, and those starting with a ":" are
 * late-bound implicit dependencies, i.e. dependencies that can only be resolved when the
 * configuration is known.
 *  * Attributes should not be introduced into the hierarchy higher then necessary.
 *  * The 'deps' and 'data' attributes are treated specially by the code that builds the runfiles
 * tree. All targets appearing in these attributes appears beneath the ".runfiles" tree; in
 * addition, "deps" may have rule-specific semantics.
 * 
 * 
 * TODO(bazel-team): Consider breaking up this class in more manageable subclasses.
 */
// Non-final only for mocking in tests. Do not subclass!
@javax.annotation.concurrent.Immutable
class RuleClass @com.google.common.annotations.VisibleForTesting internal constructor(
    name: String?,
    callstack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?,
    key: String?,
    type: RuleClassType?,
    starlarkParent: RuleClass?,
    initializer: net.starlark.java.eval.StarlarkFunction?,
    labelConverterForInitializer: LabelConverter?,
    isStarlark: Boolean,
    starlarkExtensionLabel: Label?,
    starlarkDocumentation: String?,
    extendable: Boolean,
    extendableAllowlist: Label?,
    starlarkTestable: Boolean,
    documented: Boolean,
    outputsToBindir: Boolean,
    dependencyResolutionRule: Boolean,
    isMaterializerRule: Boolean,
    materializerRuleAllowsRealDeps: Boolean,
    isExecutableStarlark: Boolean,
    isAnalysisTest: Boolean,
    hasAnalysisTestTransition: Boolean,
    allowlistCheckers: com.google.common.collect.ImmutableList<AllowlistChecker?>?,
    ignoreLicenses: Boolean,
    implicitOutputsFunction: ImplicitOutputsFunction?,
    transitionFactory: TransitionFactory<RuleTransitionData?>?,
    configuredTargetFactory: ConfiguredTargetFactory<*, *, *>?,
    advertisedProviders: AdvertisedProviderSet?,
    configuredTargetFunction: net.starlark.java.eval.StarlarkCallable?,
    optionReferenceFunction: com.google.common.base.Function<in com.google.devtools.build.lib.packages.Rule?, out MutableSet<String?>?>?,
    ruleDefinitionEnvironmentLabel: Label?,
    ruleDefinitionEnvironmentDigest: ByteArray?,
    configurationFragmentPolicy: ConfigurationFragmentPolicy?,
    supportsConstraintChecking: Boolean,
    toolchainTypes: MutableSet<ToolchainTypeRequirement?>,
    toolchainResolutionMode: ToolchainResolutionMode,
    executionPlatformConstraints: MutableSet<Label?>,
    declaredExecGroups: MutableMap<String?, DeclaredExecGroup>,
    autoExecGroupsMode: AutoExecGroupsMode?,
    outputFileKind: com.google.devtools.build.lib.packages.OutputFile.Kind?,
    attributes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute>,
    buildSetting: BuildSetting?,
    subrules: com.google.common.collect.ImmutableList<out StarlarkSubruleApi?>
) : RuleClassData {
    /** Interface for determining whether a rule needs toolchain resolution or not.  */
    @java.lang.FunctionalInterface
    interface ToolchainResolutionMode : java.io.Serializable {
        fun useToolchainResolution(rule: com.google.devtools.build.lib.packages.Rule?): Boolean

        companion object {
            @kotlin.jvm.JvmField
            val ENABLED: ToolchainResolutionMode =
                ToolchainResolutionMode { unused: com.google.devtools.build.lib.packages.Rule? -> true }
            @kotlin.jvm.JvmField
            val DISABLED: ToolchainResolutionMode =
                ToolchainResolutionMode { unused: com.google.devtools.build.lib.packages.Rule? -> false }
        }
    }

    /** Enum to determine whether a rule class uses auto exec groups.  */
    enum class AutoExecGroupsMode {
        /** The rule class does not support auto exec groups.  */
        DISABLED,

        /** The rule class uses auto exec groups regardless of other settings in the configuration.  */
        ENABLED,

        /**
         * The rule class uses auto exec groups if configured using the `_use_auto_exec_groups`
         * attribute and `--incompatible_auto_exec_groups` flag.
         */
        DYNAMIC;

        fun isEnabled(
            attributes: com.google.devtools.build.lib.packages.AttributeMap,
            isAllowedByConfiguration: Boolean
        ): Boolean {
            return when (this) {
                AutoExecGroupsMode.DISABLED -> false
                AutoExecGroupsMode.ENABLED -> true
                AutoExecGroupsMode.DYNAMIC -> {
                    if (attributes.has("\$use_auto_exec_groups")) {
                        attributes.get<Boolean?>(
                            "\$use_auto_exec_groups",
                            com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN
                        )
                    } else {
                        isAllowedByConfiguration
                    }
                }
            }
        }
    }

    /** A factory or builder class for rule implementations.  */
    interface ConfiguredTargetFactory<ConfiguredTargetT, ContextT, ActionConflictExceptionT : Throwable?> {
        /**
         * Returns a fully initialized configured target instance using the given context, or `null` on certain rule errors (typically if `ruleContext.hasErrors()` becomes `true` while trying to create the target).
         * 
         * @throws RuleErrorException if configured target creation could not be completed due to rule
         * errors
         * @throws ActionConflictExceptionT if there were conflicts during action registration
         */
        @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictExceptionT::class)
        fun create(ruleContext: ContextT?): ConfiguredTargetT?

        /**
         * Exception indicating that configured target creation could not be completed. General error
         * messaging should be done via [ ]; this exception only interrupts
         * configured target creation in cases where it can no longer continue.
         */
        class RuleErrorException : java.lang.Exception {
            constructor() : super()

            constructor(message: String?) : super(message)

            constructor(cause: Throwable?) : super(cause)

            constructor(message: String?, cause: Throwable?) : super(message, cause)
        }
    }

    /**
     * A support class to make it easier to create `RuleClass` instances. This class follows the
     * 'fluent builder' pattern.
     * 
     * 
     * The [.addAttribute] method will throw an exception if an attribute of that name
     * already exists. Use [.overrideAttribute] in that case.
     */
    class Builder(name: String, type: RuleClassType, starlark: Boolean, vararg parents: RuleClass) {
        /** The type of the rule class, which determines valid names and required attributes.  */
        enum class RuleClassType {
            /**
             * Abstract rules are intended for rule classes that are just used to factor out common
             * attributes, and for rule classes that are used only internally. These rules cannot be
             * instantiated by a BUILD file.
             * 
             * 
             * The rule name must contain a '$' and [TargetUtils.isTestRuleName] must return
             * false for the name.
             */
            ABSTRACT {
                override fun checkName(name: String) {
                    com.google.common.base.Preconditions.checkArgument(
                        (name.contains("$") && !TargetUtils.isTestRuleName(name)) || name.isEmpty()
                    )
                }

                override fun checkAttributes(attributes: MutableMap<String?, com.google.devtools.build.lib.packages.Attribute?>?) {
                    // No required attributes.
                }
            },

            /**
             * Invisible rule classes should contain a dollar sign so that they cannot be instantiated by
             * the user. They are different from abstract rules in that they can be instantiated at will.
             */
            INVISIBLE {
                override fun checkName(name: String) {
                    com.google.common.base.Preconditions.checkArgument(name.contains("$"))
                }

                override fun checkAttributes(attributes: MutableMap<String?, com.google.devtools.build.lib.packages.Attribute?>?) {
                    // No required attributes.
                }
            },

            /**
             * Normal rules are instantiable by BUILD files, possibly via a macro (symbolic or legacy), in
             * which case the rule's symbol is namespaced under `native`. Normal rule names must
             * therefore obey the rules for identifiers in the BUILD language. In addition, [ ][TargetUtils.isTestRuleName] must return false for the name.
             */
            NORMAL {
                override fun checkName(name: String) {
                    com.google.common.base.Preconditions.checkArgument(
                        !TargetUtils.isTestRuleName(name) && com.google.devtools.build.lib.packages.RuleClass.Builder.Companion.RULE_NAME_PATTERN.matcher(
                            name
                        ).matches(),
                        "Invalid rule name: %s",
                        name
                    )
                }

                override fun checkAttributes(attributes: MutableMap<String?, com.google.devtools.build.lib.packages.Attribute>) {
                    for (attribute in com.google.devtools.build.lib.packages.RuleClass.Builder.Companion.REQUIRED_ATTRIBUTES_FOR_NORMAL_RULES) {
                        val presentAttribute: com.google.devtools.build.lib.packages.Attribute =
                            attributes.get(attribute.getName())
                        com.google.common.base.Preconditions.checkState(
                            presentAttribute != null,
                            "Missing mandatory '%s' attribute in normal rule class.",
                            attribute.getName()
                        )
                        com.google.common.base.Preconditions.checkState(
                            presentAttribute.getType() == attribute.getType(),
                            "Mandatory attribute '%s' in normal rule class has incorrect type (expected"
                                    + " %s).",
                            attribute.getName(),
                            attribute.getType()
                        )
                    }
                }
            },

            /**
             * Normal rules with the additional restriction that they can only be instantiated by BUILD
             * files or legacy macros - but not symbolic macros.
             */
            BUILD_ONLY {
                override fun checkName(name: String) {
                    RuleClassType.NORMAL.checkName(name)
                }

                override fun checkAttributes(attributes: MutableMap<String, com.google.devtools.build.lib.packages.Attribute>?) {
                    RuleClassType.NORMAL.checkAttributes(attributes)
                }
            },

            /**
             * Test rules are instantiable by BUILD files and are handled specially when run with the
             * 'test' command. Their names must obey the rules for identifiers in the BUILD language and
             * [TargetUtils.isTestRuleName] must return true for the name.
             * 
             * 
             * In addition, test rules must contain certain attributes. See [ ][Builder.REQUIRED_ATTRIBUTES_FOR_TESTS].
             */
            TEST {
                override fun checkName(name: String) {
                    com.google.common.base.Preconditions.checkArgument(
                        TargetUtils.isTestRuleName(name) && com.google.devtools.build.lib.packages.RuleClass.Builder.Companion.RULE_NAME_PATTERN.matcher(
                            name
                        ).matches()
                    )
                }

                override fun checkAttributes(attributes: MutableMap<String?, com.google.devtools.build.lib.packages.Attribute>) {
                    for (attribute in com.google.devtools.build.lib.packages.RuleClass.Builder.Companion.REQUIRED_ATTRIBUTES_FOR_TESTS) {
                        val presentAttribute: com.google.devtools.build.lib.packages.Attribute =
                            attributes.get(attribute.getName())
                        com.google.common.base.Preconditions.checkState(
                            presentAttribute != null,
                            "Missing mandatory '%s' attribute in test rule class.",
                            attribute.getName()
                        )
                        com.google.common.base.Preconditions.checkState(
                            presentAttribute.getType() == attribute.getType(),
                            "Mandatory attribute '%s' in test rule class has incorrect type (expected %s).",
                            attribute.getName(),
                            attribute.getType()
                        )
                    }
                }
            },

            /**
             * Placeholder rules are only instantiated when packages which refer to non-native rule
             * classes are deserialized. At this time, non-native rule classes can't be serialized. To
             * prevent crashes on deserialization, when a package containing a rule with a non-native rule
             * class is deserialized, the rule is assigned a placeholder rule class. This is compatible
             * with our limited set of package serialization use cases.
             * 
             * 
             * Placeholder rule class names obey the rule for identifiers.
             */
            PLACEHOLDER {
                override fun checkName(name: String?) {
                    com.google.common.base.Preconditions.checkArgument(
                        com.google.devtools.build.lib.packages.RuleClass.Builder.Companion.RULE_NAME_PATTERN.matcher(
                            name
                        ).matches(), name
                    )
                }

                override fun checkAttributes(attributes: MutableMap<String?, com.google.devtools.build.lib.packages.Attribute?>?) {
                    // No required attributes; this rule class cannot have the wrong set of attributes now
                    // because, if it did, the rule class would have failed to build before the package
                    // referring to it was serialized.
                }
            };

            /**
             * Checks whether the given name is valid for the current rule class type.
             * 
             * @throws IllegalArgumentException if the name is not valid
             */
            abstract fun checkName(name: String?)

            /**
             * Checks whether the given set of attributes contains all the required attributes for the
             * current rule class type.
             * 
             * @throws IllegalArgumentException if a required attribute is missing
             */
            abstract fun checkAttributes(attributes: MutableMap<String, com.google.devtools.build.lib.packages.Attribute>?)
        }

        /**
         * A predicate that filters rule classes based on their names.
         * 
         * 
         * In [Rule], `ruleClass` refers to the string name of the rule class while
         * `ruleClassObject` refers to the actual instance of [RuleClass]. Here, `RuleClassName` emphasizes that the underlying logic of the predicate is based only on the
         * `String` name. The public methods, [.asPredicateOfRuleClass] and [ ][.asPredicateOfRuleClassObject] revert to the common convention used in [Rule].
         */
        @AutoCodec
        class RuleClassNamePredicate @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization internal constructor(
            ruleClassNames: com.google.common.collect.ImmutableSet<String?>,
            predicateType: PredicateType,
            overlappable: MutableSet<*>?
        ) {
            private val ruleClassNames: com.google.common.collect.ImmutableSet<String?>

            private val predicateType: PredicateType

            private val ruleClassNamePredicate: com.google.common.base.Predicate<String?>
            private val ruleClassPredicate: com.google.common.base.Predicate<RuleClass?>

            // if non-null, used ONLY for checking overlap
            private val overlappable: MutableSet<*>?

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            internal enum class PredicateType {
                ONLY,
                All_EXCEPT,
                UNSPECIFIED
            }

            init {
                this.ruleClassNames = ruleClassNames
                this.predicateType = predicateType
                this.overlappable = overlappable

                when (predicateType) {
                    PredicateType.All_EXCEPT -> {
                        val containing: com.google.common.base.Predicate<String?> =
                            Companion.only(ruleClassNames).asPredicateOfRuleClass()
                        ruleClassNamePredicate =
                            DescribedPredicate<String?>(
                                com.google.common.base.Predicates.not<String?>(containing),
                                "all but " + containing
                            )
                        ruleClassPredicate =
                            DescribedPredicate<RuleClass?>(
                                com.google.common.base.Predicates.compose<RuleClass?, String?>(
                                    ruleClassNamePredicate,
                                    com.google.common.base.Function { obj: RuleClass? -> obj!!.getName() }),
                                ruleClassNamePredicate.toString()
                            )
                    }

                    PredicateType.ONLY -> {
                        ruleClassNamePredicate =
                            DescribedPredicate<String?>(
                                com.google.common.base.Predicates.`in`<String?>(ruleClassNames),
                                com.google.devtools.build.lib.util.StringUtil.joinEnglishList(ruleClassNames)
                            )
                        ruleClassPredicate =
                            DescribedPredicate<RuleClass?>(
                                com.google.common.base.Predicates.compose<RuleClass?, String?>(
                                    ruleClassNamePredicate,
                                    com.google.common.base.Function { obj: RuleClass? -> obj!!.getName() }),
                                ruleClassNamePredicate.toString()
                            )
                    }

                    PredicateType.UNSPECIFIED -> {
                        ruleClassNamePredicate = com.google.common.base.Predicates.alwaysTrue<String?>()
                        ruleClassPredicate = com.google.common.base.Predicates.alwaysTrue<RuleClass?>()
                    }

                    else ->  // This shouldn't happen normally since the constructor is private and within this
                        // file.
                        throw java.lang.IllegalArgumentException(
                            "Predicate type was not specified when constructing a RuleClassNamePredicate."
                        )
                }
            }

            fun asPredicateOfRuleClass(): com.google.common.base.Predicate<String?> {
                return ruleClassNamePredicate
            }

            fun asPredicateOfRuleClassObject(): com.google.common.base.Predicate<RuleClass?> {
                return ruleClassPredicate
            }

            /**
             * Determines whether two `RuleClassNamePredicate`s should be considered incompatible as
             * rule class predicate and rule class warning predicate.
             * 
             * 
             * Specifically, if both list sets of explicit rule class names to permit, those two sets
             * must be disjoint, so the restriction only applies when both predicates have been created by
             * [.only].
             */
            fun consideredOverlapping(that: RuleClassNamePredicate): Boolean {
                return this.overlappable != null && that.overlappable != null && !Collections.disjoint(
                    this.overlappable,
                    that.overlappable
                )
            }

            override fun hashCode(): Int {
                return HashCodes.hashObjects(ruleClassNames, predicateType)
            }

            override fun equals(obj: Any?): Boolean {
                // NOTE: Specifically not checking equality of ruleClassPredicate.
                // By construction, if the name predicates are equals, the rule class predicates are, too.
                return obj is RuleClassNamePredicate
                        && ruleClassNames == obj.ruleClassNames
                        && predicateType == obj.predicateType
            }

            override fun toString(): String {
                return ruleClassNamePredicate.toString()
            }

            /** A pass-through predicate, except that an explicit [.toString] is provided.  */
            private class DescribedPredicate<T>(delegate: com.google.common.base.Predicate<T?>, description: String?) :
                com.google.common.base.Predicate<T?> {
                private val delegate: com.google.common.base.Predicate<T?> // the actual predicate
                private val description: String?

                init {
                    this.delegate = delegate
                    this.description = description
                }

                override fun apply(input: T?): Boolean {
                    return delegate.apply(input)
                }

                override fun hashCode(): Int {
                    return delegate.hashCode()
                }

                override fun equals(obj: Any?): Boolean {
                    return obj is DescribedPredicate<*>
                            && delegate == obj.delegate
                }

                override fun toString(): String {
                    return description!!
                }
            }

            companion object {
                private val UNSPECIFIED_INSTANCE = RuleClassNamePredicate(
                    com.google.common.collect.ImmutableSet.of<String?>(),
                    PredicateType.UNSPECIFIED,
                    null
                )

                fun only(ruleClassNamesAsIterable: Iterable<String?>): RuleClassNamePredicate {
                    val ruleClassNames: com.google.common.collect.ImmutableSet<String?> =
                        com.google.common.collect.ImmutableSet.copyOf<String?>(ruleClassNamesAsIterable)
                    return RuleClassNamePredicate(ruleClassNames, PredicateType.ONLY, ruleClassNames)
                }

                @kotlin.jvm.JvmStatic
                fun only(vararg ruleClasses: String?): RuleClassNamePredicate {
                    return Companion.only(java.util.Arrays.asList<String?>(*ruleClasses))
                }

                @kotlin.jvm.JvmStatic
                fun allExcept(vararg ruleClasses: String?): RuleClassNamePredicate {
                    val ruleClassNames: com.google.common.collect.ImmutableSet<String?> =
                        com.google.common.collect.ImmutableSet.copyOf<String?>(ruleClasses)
                    com.google.common.base.Preconditions.checkState(
                        !ruleClassNames.isEmpty(),
                        "Use unspecified() instead"
                    )
                    return RuleClassNamePredicate(ruleClassNames, PredicateType.All_EXCEPT, null)
                }

                /**
                 * This is a special sentinel value which represents a "default" [ ] which is unspecified. Note that a call to its [ ][RuleClassNamePredicate.asPredicateOfRuleClass] produces `Predicates.<RuleClass>alwaysTrue()`, which is a sentinel value for other parts of bazel.
                 */
                fun unspecified(): RuleClassNamePredicate {
                    return UNSPECIFIED_INSTANCE
                }
            }
        }

        private val name: String
        private var callstack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>? =
            com.google.common.collect.ImmutableList.of<net.starlark.java.eval.StarlarkThread.CallStackEntry?>()
        private val type: RuleClassType
        private var starlarkParent: RuleClass? = null
        private var initializer: net.starlark.java.eval.StarlarkFunction? = null
        private var labelConverterForInitializer: LabelConverter? = null

        // The extendable may take 3 value, null means that the default allowlist should be use when
        // rule is extendable in practice.
        private var extendable: Boolean? = null
        private var extendableAllowlist: Label? = null
        private var defaultExtendableAllowlist: Label? = null
        private val starlark: Boolean
        private var starlarkTestable = false
        private var documented: Boolean
        private var outputsToBindir = true
        private var dependencyResolutionRule = false
        private var isMaterializerRule = false
        private var materializerRuleAllowsRealDeps = false
        private var isExecutableStarlark = false
        private var isAnalysisTest = false
        private var hasAnalysisTestTransition = false
        private val allowlistCheckers: com.google.common.collect.ImmutableList.Builder<AllowlistChecker?> =
            com.google.common.collect.ImmutableList.builder<AllowlistChecker?>()
        private var ignoreLicenses = false
        private var implicitOutputsFunction: ImplicitOutputsFunction? = SafeImplicitOutputsFunction.Companion.NONE
        private var transitionFactory: TransitionFactory<RuleTransitionData?>? = NoTransition.getFactory()
        private var configuredTargetFactory: ConfiguredTargetFactory<*, *, *>? = null
        private val advertisedProviders: com.google.devtools.build.lib.packages.AdvertisedProviderSet.Builder =
            AdvertisedProviderSet.Companion.builder()
        private var configuredTargetFunction: net.starlark.java.eval.StarlarkCallable? = null
        private var buildSetting: BuildSetting? = null

        private var subrules: com.google.common.collect.ImmutableList<out StarlarkSubruleApi?> =
            com.google.common.collect.ImmutableList.of<StarlarkSubruleApi?>()
        private var optionReferenceFunction: com.google.common.base.Function<in com.google.devtools.build.lib.packages.Rule?, out MutableSet<String?>?> =
            NO_OPTION_REFERENCE

        /** The following 3 fields are null iff the rule is native.  */
        private var ruleDefinitionEnvironmentLabel: Label? = null

        private var ruleDefinitionEnvironmentDigest: ByteArray? = null

        // TODO(b/366027483): in theory, ruleDefinitionEnvironmentLabel ought to equal
        // starlarkExtensionLabel, and we ought to get rid of one of them.
        private var starlarkExtensionLabel: Label? = null

        // May be non-null only if the rule is Starlark-defined.
        private var starlarkDocumentation: String? = null

        private val configurationFragmentPolicy: com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy.Builder =
            com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy.Builder()

        private var supportsConstraintChecking = true

        private val attributes: MutableMap<String, com.google.devtools.build.lib.packages.Attribute> =
            LinkedHashMap<String, com.google.devtools.build.lib.packages.Attribute>()
        private val toolchainTypes: MutableSet<ToolchainTypeRequirement?> = LinkedHashSet<ToolchainTypeRequirement?>()
        private var toolchainResolutionMode = ToolchainResolutionMode.Companion.ENABLED
        private val executionPlatformConstraints: MutableSet<Label?> = LinkedHashSet<Label?>()
        private var outputFileKind: com.google.devtools.build.lib.packages.OutputFile.Kind =
            com.google.devtools.build.lib.packages.OutputFile.Kind.FILE
        private val execGroups: MutableMap<String?, DeclaredExecGroup> = LinkedHashMap<String?, DeclaredExecGroup>()
        private var autoExecGroupsMode: AutoExecGroupsMode? = AutoExecGroupsMode.DYNAMIC

        /**
         * Constructs a new [RuleClass.Builder] using all attributes from all parent rule classes.
         * An attribute cannot exist in more than one parent.
         * 
         * 
         * The rule type affects the allowed names and the required attributes (see [ ]).
         * 
         * @param parents There may be either multiple native `RuleClassType.ABSTRACT` rules or a
         * single Starlark rule.
         * @throws IllegalArgumentException if an attribute with the same name exists in more than one
         * parent
         */
        init {
            com.google.common.base.Preconditions.checkArgument(
                (parents.size == 1 && parents[0].isStarlark())
                        || java.util.Arrays.stream<RuleClass?>(parents)
                    .allMatch(java.util.function.Predicate { rule: RuleClass? -> !rule!!.isStarlark() })
            )
            this.name = name
            this.starlark = starlark
            this.type = type
            com.google.common.base.Preconditions.checkState(starlark || type !== RuleClassType.PLACEHOLDER, name)
            this.documented = type !== RuleClassType.ABSTRACT
            addAttribute(NAME_ATTRIBUTE)
            if (parents.size == 1 && parents[0].isStarlark()
                && parents[0].getRuleClassType() !== RuleClassType.ABSTRACT
            ) {
                // the condition removes {@link StarlarkRuleClassFunctions.baseRule} and binaryBaseRule,
                // which are marked as Starlark (because of Stardoc) && abstract at the same time
                starlarkParent = parents[0]
                com.google.common.base.Preconditions.checkArgument(starlarkParent!!.isExtendable())
            }

            for (parent in parents) {
                if (parent.isMaterializerRule()) {
                    isMaterializerRule = true
                    if (parent.materializerRuleAllowsRealDeps) {
                        materializerRuleAllowsRealDeps = true
                    }
                } else require(!isMaterializerRule) { "Inconsistent value of isMaterializerRule among parents" }

                if (parent.dependencyResolutionRule) {
                    dependencyResolutionRule = true
                } else require(!dependencyResolutionRule) { "Inconsistent value of dependencyResolutionRule among parents" }

                configurationFragmentPolicy.includeConfigurationFragmentsFrom(
                    parent.getConfigurationFragmentPolicy()
                )
                supportsConstraintChecking = parent.supportsConstraintChecking

                addToolchainTypes(parent.getToolchainTypes())
                addExecutionPlatformConstraints(parent.getExecutionPlatformConstraints())
                try {
                    addExecGroups(parent.getDeclaredExecGroups(), false)
                } catch (e: DuplicateExecGroupError) {
                    throw java.lang.IllegalArgumentException(
                        java.lang.String.format(
                            "An execution group named '%s' is inherited multiple times with different"
                                    + " requirements in %s ruleclass",
                            e.getDuplicateGroup(), name
                        )
                    )
                }

                this.autoExecGroupsMode = parent.getAutoExecGroupsMode()

                for (attribute in parent.getAttributeProvider().getAttributes()) {
                    val attrName: String? = attribute.getName()
                    com.google.common.base.Preconditions.checkArgument(
                        !attributes.containsKey(attrName!!) || attributes.get(attrName) == attribute,
                        "Attribute %s is inherited multiple times in %s ruleclass",
                        attrName,
                        name
                    )
                    attributes.put(attrName, attribute)
                }

                allowlistCheckers.addAll(parent.getAllowlistCheckers())

                advertisedProviders.addParent(parent.getAdvertisedProviders())

                if (parent.getDefaultImplicitOutputsFunction() !== SafeImplicitOutputsFunction.Companion.NONE) {
                    require(implicitOutputsFunction === SafeImplicitOutputsFunction.Companion.NONE) { "Only a single parent may set implicit outputs" }
                    implicitOutputsFunction = parent.getDefaultImplicitOutputsFunction()
                }
            }
            // TODO(bazel-team): move this testonly attribute setting to somewhere else
            // preferably to some base RuleClass implementation.
            if (this.type == RuleClassType.TEST) {
                val testOnlyAttr: com.google.devtools.build.lib.packages.Attribute.Builder<Boolean?> =
                    com.google.devtools.build.lib.packages.Attribute.Companion.attr<Boolean?>(
                        "testonly",
                        com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN
                    )
                        .value(true)
                        .nonconfigurable("policy decision: this shouldn't depend on the configuration")
                if (attributes.containsKey("testonly")) {
                    override<Boolean?>(testOnlyAttr)
                } else {
                    add<Boolean?>(testOnlyAttr)
                }
            }
        }

        /**
         * Same as [.build], except for a Starlark-defined rule class; the rule class's key will
         * be derived from the Starlark file label (falling back to the rule definition environment
         * label if null) and the name.
         * 
         * @param name rule class name; if the builder was initialized with an empty name, this value
         * will override it.
         * @param starlarkExtensionLabel the label of the Starlark file where the rule class was
         * exported.
         */
        fun buildStarlark(name: String?, starlarkExtensionLabel: Label?): RuleClass {
            com.google.common.base.Preconditions.checkState(starlark)
            this.starlarkExtensionLabel = starlarkExtensionLabel
            return build(name, starlarkExtensionLabel.toString() + "%" + name)
        }

        /**
         * For a native rule, checks that required attributes for test rules are present, creates the
         * [RuleClass] object and returns it.
         * 
         * @throws IllegalStateException if any of the required attributes is missing
         */
        fun build(): RuleClass {
            // For built-ins, name == key
            return build(name, name)
        }

        /** Same as [.build] except with setting the name and key parameters.  */
        private fun build(name: String?, key: String?): RuleClass {
            com.google.common.base.Preconditions.checkArgument(this.name.isEmpty() || this.name == name)
            type.checkName(name)

            checkAttributes(name)

            com.google.common.base.Preconditions.checkState(
                (type === RuleClassType.ABSTRACT)
                        == (configuredTargetFactory == null && configuredTargetFunction == null),
                "Bad combo for %s: %s %s %s",
                name,
                type,
                configuredTargetFactory,
                configuredTargetFunction
            )
            if (starlark) {
                assertStarlarkRuleClassHasImplementationFunction()
                assertStarlarkRuleClassHasEnvironmentLabel()
            }
            if (type === RuleClassType.PLACEHOLDER) {
                com.google.common.base.Preconditions.checkNotNull<ByteArray?>(
                    ruleDefinitionEnvironmentDigest,
                    this.name
                )
            }

            if (buildSetting != null) {
                val type: com.google.devtools.build.lib.packages.Type<*>? = buildSetting.getType()
                val defaultAttrBuilder: com.google.devtools.build.lib.packages.Attribute.Builder<*> =
                    com.google.devtools.build.lib.packages.Attribute.Companion.attr(
                        com.google.devtools.build.lib.packages.RuleClass.Builder.Companion.STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME,
                        type
                    )
                        .nonconfigurable(com.google.devtools.build.lib.packages.RuleClass.Builder.Companion.BUILD_SETTING_DEFAULT_NONCONFIGURABLE)
                        .mandatory()
                this.add(defaultAttrBuilder)

                this.add<String?>(
                    com.google.devtools.build.lib.packages.Attribute.Companion.attr<String?>(
                        com.google.devtools.build.lib.packages.RuleClass.Builder.Companion.STARLARK_BUILD_SETTING_HELP_ATTR_NAME,
                        com.google.devtools.build.lib.packages.Type.Companion.STRING
                    )
                        .nonconfigurable(com.google.devtools.build.lib.packages.RuleClass.Builder.Companion.BUILD_SETTING_DEFAULT_NONCONFIGURABLE)
                )

                // Build setting rules should opt out of toolchain resolution, since they form part of the
                // configuration.
                this.toolchainResolutionMode(ToolchainResolutionMode.Companion.DISABLED)
            }

            if (starlark
                && (type === RuleClassType.NORMAL || type === RuleClassType.TEST)
                && outputsToBindir
                && !starlarkTestable && !isAnalysisTest && buildSetting == null
            ) {
                if (extendable == null) { // The rule can be extended, use fallback
                    extendable = true
                    extendableAllowlist = defaultExtendableAllowlist
                }
            } else {
                // This kind of rule can't be extended
                require(!(java.lang.Boolean.TRUE == extendable || extendableAllowlist != null)) { "The rule cannot be extended" }
                extendable = false
            }

            return RuleClass(
                name,
                callstack,
                key,
                type,
                starlarkParent,
                initializer,
                labelConverterForInitializer,
                starlark,
                starlarkExtensionLabel,
                starlarkDocumentation,
                extendable!!,
                extendableAllowlist,
                starlarkTestable,
                documented,
                outputsToBindir,
                dependencyResolutionRule,
                isMaterializerRule,
                materializerRuleAllowsRealDeps,
                isExecutableStarlark,
                isAnalysisTest,
                hasAnalysisTestTransition,
                allowlistCheckers.build(),
                ignoreLicenses,
                implicitOutputsFunction,
                transitionFactory,
                configuredTargetFactory,
                advertisedProviders.build(),
                configuredTargetFunction,
                optionReferenceFunction,
                ruleDefinitionEnvironmentLabel,
                ruleDefinitionEnvironmentDigest,
                configurationFragmentPolicy.build(),
                supportsConstraintChecking,
                toolchainTypes,
                toolchainResolutionMode,
                executionPlatformConstraints,
                execGroups,
                autoExecGroupsMode,
                outputFileKind,
                com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.packages.Attribute?>(
                    attributes.values()
                ),
                buildSetting,
                subrules
            )
        }

        private fun checkAttributes(ruleClassName: String?) {
            com.google.common.base.Preconditions.checkArgument(
                attributes.size() <= MAX_ATTRIBUTES,
                "Rule class %s declared too many attributes (%s > %s)",
                ruleClassName,
                attributes.size(),
                MAX_ATTRIBUTES
            )

            val attributesNotForDependencyResolutionBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()

            for (entry in attributes.entrySet()) {
                val attributeName: String = entry.getKey()
                val attribute: com.google.devtools.build.lib.packages.Attribute = entry.getValue()

                val attributeNameLength: Int =
                    StarlarkSubruleApi.getUserDefinedNameIfSubruleAttr(subrules, attributeName)
                        .map<Int?>(java.util.function.Function { obj: String? -> obj.length() })
                        .orElse(attributeName.length())

                // TODO(b/151171037): This check would make more sense at Attribute creation time, but the
                // use of unchecked exceptions in these APIs makes it brittle.
                com.google.common.base.Preconditions.checkArgument(
                    attributeNameLength <= MAX_ATTRIBUTE_NAME_LENGTH,
                    "Attribute %s.%s's name is too long (%s > %s)",
                    ruleClassName,
                    attributeName,
                    attributeNameLength,
                    MAX_ATTRIBUTE_NAME_LENGTH
                )

                if (dependencyResolutionRule) {
                    if (attribute.getType().getLabelClass() == LabelClass.DEPENDENCY
                        && !attribute.isForDependencyResolution()
                    ) {
                        attributesNotForDependencyResolutionBuilder.add(attributeName)
                    }
                }
            }

            val attributesNotForDependencyResolution: com.google.common.collect.ImmutableList<String?> =
                attributesNotForDependencyResolutionBuilder.build()
            check(attributesNotForDependencyResolution.isEmpty()) {
                ("Rule is available for dependency resolution but some dependency attributes aren't: "
                        + com.google.common.base.Joiner.on(", ").join(attributesNotForDependencyResolution))
            }

            type.checkAttributes(attributes)
        }

        private fun assertStarlarkRuleClassHasImplementationFunction() {
            com.google.common.base.Preconditions.checkState(
                (type === RuleClassType.NORMAL || type === RuleClassType.TEST)
                        == (configuredTargetFunction != null),
                "%s %s",
                type,
                configuredTargetFunction
            )
        }

        private fun assertStarlarkRuleClassHasEnvironmentLabel() {
            com.google.common.base.Preconditions.checkState(
                (type === RuleClassType.NORMAL || type === RuleClassType.TEST || type === RuleClassType.PLACEHOLDER)
                        == (ruleDefinitionEnvironmentLabel != null),
                "Concrete Starlark rule classes can't have null labels: %s %s",
                ruleDefinitionEnvironmentLabel,
                type
            )
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun initializer(
            initializer: net.starlark.java.eval.StarlarkFunction?, labelConverterForInitializer: LabelConverter?
        ): Builder {
            this.initializer = initializer
            this.labelConverterForInitializer = labelConverterForInitializer
            return this
        }

        fun setExtendableByAllowlist(extendableAllowlist: Label?) {
            this.extendable = true
            this.extendableAllowlist = extendableAllowlist
        }

        /** Set the rule extendable or not, without an allowlist.  */
        fun setExtendable(extendable: Boolean) {
            this.extendable = extendable
            this.extendableAllowlist = null
        }

        /**
         * Sets the default allowlist, which is used as a fallback, when user doesn't set extendable or
         * extendable by allowlist
         */
        fun setDefaultExtendableAllowlist(extendableAllowlist: Label?) {
            this.defaultExtendableAllowlist = extendableAllowlist
        }

        /**
         * Declares that the implementation of the associated rule class requires the given fragments to
         * be present.
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
        fun requiresConfigurationFragmentsByStarlarkModuleName(
            configurationFragmentNames: MutableCollection<String?>?
        ): Builder {
            configurationFragmentPolicy.requiresConfigurationFragmentsByStarlarkBuiltinName(
                configurationFragmentNames
            )
            return this
        }

        /** Sets the Starlark call stack associated with this rule class's creation.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCallStack(callstack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?): Builder {
            this.callstack = callstack
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setStarlarkTestable(): Builder {
            com.google.common.base.Preconditions.checkState(
                starlark,
                "Cannot set starlarkTestable on a non-Starlark rule"
            )
            starlarkTestable = true
            return this
        }

        /**
         * Sets the policy for the case where the configuration is missing required fragment class (see
         * [.requiresConfigurationFragments]).
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMissingFragmentPolicy(
            fragmentClass: java.lang.Class<*>?, missingFragmentPolicy: MissingFragmentPolicy?
        ): Builder {
            configurationFragmentPolicy.setMissingFragmentPolicy(fragmentClass, missingFragmentPolicy)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setUndocumented(): Builder {
            documented = false
            return this
        }

        /**
         * Determines the outputs of this rule to be created beneath the `genfiles` directory. By
         * default, files are created beneath the `bin` directory.
         * 
         * 
         * This property is not inherited and this method should not be called by builder of [ ][RuleClassType.ABSTRACT] rule class.
         * 
         * @throws IllegalStateException if called for abstract rule class builder
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setOutputToGenfiles(): Builder {
            com.google.common.base.Preconditions.checkState(
                type !== RuleClassType.ABSTRACT,
                "Setting not inherited property (output to genrules) of abstract rule class '%s'",
                name
            )
            this.outputsToBindir = false
            return this
        }

        /**
         * Sets the implicit outputs function of the rule class. The default implicit outputs function
         * is [SafeImplicitOutputsFunction.NONE].
         * 
         * 
         * This property is not inherited and this method should not be called by builder of [ ][RuleClassType.ABSTRACT] rule class.
         * 
         * @throws IllegalStateException if called for abstract rule class builder
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setImplicitOutputsFunction(implicitOutputsFunction: ImplicitOutputsFunction?): Builder {
            com.google.common.base.Preconditions.checkState(
                type !== RuleClassType.ABSTRACT,
                "Setting not inherited property (implicit output function) of abstract rule class '%s'",
                name
            )
            this.implicitOutputsFunction = implicitOutputsFunction
            return this
        }

        /** Applies the given transition factory to all incoming edges for this rule class.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun cfg(transitionFactory: TransitionFactory<RuleTransitionData?>?): Builder {
            com.google.common.base.Preconditions.checkState(
                type !== RuleClassType.ABSTRACT,
                "Setting not inherited property (cfg) of abstract rule class '%s'",
                name
            )
            com.google.common.base.Preconditions.checkState(
                NoTransition.isInstance(this.transitionFactory), "Property cfg has already been set"
            )
            com.google.common.base.Preconditions.checkNotNull<Any?>(transitionFactory)
            com.google.common.base.Preconditions.checkArgument(
                transitionFactory.transitionType().isCompatibleWith(TransitionType.RULE)
            )
            com.google.common.base.Preconditions.checkArgument(!transitionFactory.isSplit())
            this.transitionFactory = transitionFactory
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun factory(factory: ConfiguredTargetFactory<*, *, *>?): Builder {
            this.configuredTargetFactory = factory
            return this
        }

        /**
         * State that the rule class being built always supplies the specified provider.
         * 
         * 
         * When computing the set of aspects required for a rule, only the providers listed here are
         * considered. The presence of a provider here means that the rule **must** implement said
         * provider.
         * 
         * 
         * This is here so that we can do the loading phase overestimation required for "blaze
         * query", which does not have the configured targets available.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun advertiseProvider(vararg providers: java.lang.Class<*>?): Builder {
            for (provider in providers) {
                advertisedProviders.addBuiltin(provider)
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun advertiseStarlarkProvider(vararg starlarkProviders: StarlarkProviderIdentifier?): Builder {
            for (starlarkProviderIdentifier in starlarkProviders) {
                advertisedProviders.addStarlark(starlarkProviderIdentifier)
            }
            return this
        }

        /**
         * Set if the rule can have any provider. This is called for the `alias` rule and other
         * alias-like rules such as `bind`.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun canHaveAnyProvider(): Builder {
            advertisedProviders.canHaveAnyProvider()
            return this
        }

        /**
         * Adds an attribute to the builder.
         * 
         * 
         * Throws an IllegalStateException if an attribute of that name already exists.
         * 
         * 
         * TODO(bazel-team): stop using unchecked exceptions in this way.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAttribute(attribute: com.google.devtools.build.lib.packages.Attribute): Builder {
            val prevVal: com.google.devtools.build.lib.packages.Attribute? =
                attributes.putIfAbsent(attribute.getName(), attribute)
            check(prevVal == null) {
                java.lang.String.format(
                    "There is already a built-in attribute '%s' which cannot be overridden.",
                    attribute.getName()
                )
            }
            return this
        }

        private fun overrideAttribute(attribute: com.google.devtools.build.lib.packages.Attribute) {
            val attrName: String? = attribute.getName()
            com.google.common.base.Preconditions.checkState(
                attributes.containsKey(attrName!!),
                "No such attribute '%s' to override in ruleclass '%s'.",
                attrName,
                name
            )
            val origType: com.google.devtools.build.lib.packages.Type<*> = attributes.get(attrName).getType()
            val newType: com.google.devtools.build.lib.packages.Type<*> = attribute.getType()
            com.google.common.base.Preconditions.checkState(
                origType == newType,
                "The type of the new attribute '%s' is different from the original one '%s'.",
                newType,
                origType
            )
            attributes.put(attrName, attribute)
        }

        /**
         * Builds provided attribute and attaches it to this rule class.
         * 
         * 
         * Typically rule classes should only declare a handful of attributes - this expectation is
         * enforced when the instance is built.
         * 
         * 
         * Attribute names should be meaningful but short; overly long names are rejected at
         * instantiation.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun <TYPE> add(attr: com.google.devtools.build.lib.packages.Attribute.Builder<TYPE?>): Builder {
            addAttribute(attr.build())
            return this
        }

        /**
         * Overrides the attribute with the same name. This method does additional checks required for
         * overriding attributes in Starlark
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        fun override(attr: com.google.devtools.build.lib.packages.Attribute): Builder {
            val parentAttr: com.google.devtools.build.lib.packages.Attribute = attributes.get(attr.getName())
            com.google.devtools.build.lib.packages.RuleClass.Builder.Companion.failIf(
                !parentAttr.starlarkDefined(),
                "attribute `%s`: built-in attributes cannot be overridden.",
                parentAttr.getPublicName()
            )
            com.google.devtools.build.lib.packages.RuleClass.Builder.Companion.failIf(
                !parentAttr.isPublic(),
                "attribute `%s`: private attributes cannot be overridden.",
                parentAttr.getPublicName()
            )
            com.google.devtools.build.lib.packages.RuleClass.Builder.Companion.failIf(
                parentAttr.getType() !== BuildType.LABEL_LIST && parentAttr.getType() !== BuildType.LABEL,
                "attribute `%s`: Only label types maybe be overridden.",
                parentAttr.getPublicName()
            )
            com.google.devtools.build.lib.packages.RuleClass.Builder.Companion.failIf(
                parentAttr.getType() !== attr.getType(),
                "attribute `%s`: Types of parent and child's attributes mismatch.",
                parentAttr.getPublicName()
            )
            attr.failIfNotAValidOverride()

            val attrBuilder: com.google.devtools.build.lib.packages.Attribute.Builder<*> = copy(attr.getName())
            if (attr.getDefaultValueUnchecked() != null) {
                attrBuilder.defaultValue(attr.getDefaultValueUnchecked())
            }
            attrBuilder.addAspects(attr.getAspectsList())
            override(attrBuilder)
            return this
        }

        /**
         * Builds attribute from the attribute builder and overrides the attribute with the same name.
         * 
         * @throws IllegalArgumentException if the attribute does not override one of the same name
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun <TYPE> override(attr: com.google.devtools.build.lib.packages.Attribute.Builder<TYPE?>): Builder {
            overrideAttribute(attr.build())
            return this
        }

        /** True if the rule class contains an attribute named `name`.  */
        fun contains(name: String?): Boolean {
            return attributes.containsKey(name!!)
        }

        fun getAttribute(name: String?): com.google.devtools.build.lib.packages.Attribute? {
            return attributes.get(name!!)
        }

        /** Returns a list of all attributes added to this Builder so far.  */
        fun getAttributes(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute?> {
            return com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.packages.Attribute?>(
                attributes.values()
            )
        }

        /** Sets the rule implementation function. Meant for Starlark usage.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setConfiguredTargetFunction(func: net.starlark.java.eval.StarlarkCallable?): Builder {
            this.configuredTargetFunction = func
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setBuildSetting(buildSetting: BuildSetting?): Builder {
            this.buildSetting = buildSetting
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSubrules(subrules: com.google.common.collect.ImmutableList<out StarlarkSubruleApi?>): Builder {
            this.subrules = subrules
            return this
        }

        fun getSubrules(): com.google.common.collect.ImmutableList<out StarlarkSubruleApi?> {
            return subrules
        }

        fun getParentSubrules(): com.google.common.collect.ImmutableSet<out StarlarkSubruleApi?> {
            val builder: com.google.common.collect.ImmutableSet.Builder<StarlarkSubruleApi?> =
                com.google.common.collect.ImmutableSet.builder<StarlarkSubruleApi?>()
            var currentParent = starlarkParent
            while (currentParent != null) {
                builder.addAll(starlarkParent!!.getSubrules())
                currentParent = currentParent.starlarkParent
            }
            return builder.build()
        }

        /**
         * Sets the rule definition environment label and transitive digest. Meant for Starlark usage.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRuleDefinitionEnvironmentLabelAndDigest(label: Label?, digest: ByteArray?): Builder {
            this.ruleDefinitionEnvironmentLabel =
                com.google.common.base.Preconditions.checkNotNull<Label?>(label, this.name)
            this.ruleDefinitionEnvironmentDigest =
                com.google.common.base.Preconditions.checkNotNull<ByteArray?>(digest, this.name)
            return this
        }

        /**
         * Sets the Starlark documentation string, if one was provided, for a Starlark-defined rule
         * class. Cannot be set for a non-Starlark-defined rule class.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setStarlarkDocumentation(starlarkDocumentation: String?): Builder {
            com.google.common.base.Preconditions.checkState(starlark, this.name)
            this.starlarkDocumentation =
                com.google.common.base.Preconditions.checkNotNull<String?>(starlarkDocumentation, this.name)
            return this
        }

        /**
         * Returns the Starlark documentation string, if one was provided, for a Starlark-defined rule
         * class.
         */
        fun getStarlarkDocumentation(): String? {
            return this.starlarkDocumentation
        }

        fun getRuleDefinitionEnvironmentLabel(): Label? {
            return this.ruleDefinitionEnvironmentLabel
        }

        /**
         * Removes an attribute with the same name from this rule class.
         * 
         * @throws IllegalArgumentException if the attribute with this name does not exist
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun removeAttribute(name: String?): Builder {
            com.google.common.base.Preconditions.checkState(
                attributes.containsKey(name!!), "No such attribute '%s' to remove.", name
            )
            attributes.remove(name)
            return this
        }

        /**
         * Mark the rule as "for dependency resolution". Rules so marked can only depend on other rules
         * also marked as such.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setDependencyResolutionRule(): Builder {
            this.dependencyResolutionRule = true
            return this
        }

        /** Mark the rule as a materializer rule.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setIsMaterializerRule(isMaterializerRule: Boolean): Builder {
            this.isMaterializerRule = isMaterializerRule
            return this
        }

        /** Mark the rule as a materializer rule that allows real deps.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMaterializerRuleAllowsRealDeps(allowRealDeps: Boolean): Builder {
            this.materializerRuleAllowsRealDeps = allowRealDeps
            return this
        }

        /**
         * This rule class outputs a default executable for every rule with the same name as the
         * rules's. Only works for Starlark.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutableStarlark(): Builder {
            this.isExecutableStarlark = true
            return this
        }

        /** This rule class is marked as an analysis test.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setIsAnalysisTest(): Builder {
            this.isAnalysisTest = true
            return this
        }

        fun isAnalysisTest(): Boolean {
            return this.isAnalysisTest
        }

        /**
         * This rule class has at least one attribute with an analysis test transition. (A
         * starlark-defined transition using analysis_test_transition()).
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setHasAnalysisTestTransition(): Builder {
            this.hasAnalysisTestTransition = true
            return this
        }

        /** Add an allowlistChecker to be checked as part of the rule implementation.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAllowlistChecker(allowlistChecker: AllowlistChecker): Builder {
            this.allowlistCheckers.add(allowlistChecker)
            return this
        }

        /**
         * This rule class never declares a license regardless of what the rule's or package's `
         * licenses` attribute says.
         */
        // TODO(b/130286108): remove the licenses attribute completely from such rules.
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setIgnoreLicenses(): Builder {
            this.ignoreLicenses = true
            return this
        }

        fun getType(): RuleClassType {
            return this.type
        }

        /**
         * Sets the kind of output files this rule creates. DO NOT USE! This only exists to support the
         * non-open-sourced `fileset` rule. {@see OutputFile.Kind}.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setOutputFileKind(outputFileKind: com.google.devtools.build.lib.packages.OutputFile.Kind?): Builder {
            this.outputFileKind =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.packages.OutputFile.Kind>(
                    outputFileKind
                )
            return this
        }

        /**
         * Declares that instances of this rule are compatible with the specified environments, in
         * addition to the defaults declared by their environment groups. This can be overridden by
         * rule-specific declarations. See [ ] for details.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun compatibleWith(vararg environments: Label?): Builder {
            add<MutableList<Label?>?>(
                com.google.devtools.build.lib.packages.Attribute.Companion.attr<MutableList<Label?>?>(
                    DEFAULT_COMPATIBLE_ENVIRONMENT_ATTR, BuildType.LABEL_LIST
                )
                    .value(com.google.common.collect.ImmutableList.copyOf<Label?>(environments))
            )
            return this
        }

        /**
         * Declares that instances of this rule are restricted to the specified environments, i.e. these
         * override the defaults declared by their environment groups. This can be overridden by
         * rule-specific declarations. See [ ] for details.
         * 
         * 
         * The input list cannot be empty.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun restrictedTo(firstEnvironment: Label, vararg otherEnvironments: Label?): Builder {
            val environments: com.google.common.collect.ImmutableList<Label?> =
                com.google.common.collect.ImmutableList.builder<Label?>().add(firstEnvironment).add(*otherEnvironments)
                    .build()
            add<MutableList<Label?>?>(
                com.google.devtools.build.lib.packages.Attribute.Companion.attr<MutableList<Label?>?>(
                    DEFAULT_RESTRICTED_ENVIRONMENT_ATTR, BuildType.LABEL_LIST
                ).value(environments)
            )
            return this
        }

        /**
         * Exempts rules of this type from the constraint enforcement system. This should only be
         * applied to rules that are intrinsically incompatible with constraint checking (any
         * application of this method weakens the reach and strength of the system).
         * 
         * @param reason user-informative message explaining the reason for exemption (not used)
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun exemptFromConstraintChecking(reason: String?): Builder {
            com.google.common.base.Preconditions.checkState(this.supportsConstraintChecking)
            this.supportsConstraintChecking = false
            attributes.remove(COMPATIBLE_ENVIRONMENT_ATTR)
            attributes.remove(RESTRICTED_ENVIRONMENT_ATTR)
            attributes.remove(TARGET_COMPATIBLE_WITH_ATTR)
            return this
        }

        /**
         * Causes rules of this type to implicitly reference the configuration fragments associated with
         * the options its attributes reference.
         * 
         * 
         * This is only intended for use by `config_setting` - other rules should not use this!
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setOptionReferenceFunctionForConfigSettingOnly(
            optionReferenceFunction: com.google.common.base.Function<in com.google.devtools.build.lib.packages.Rule?, out MutableSet<String?>?>?
        ): Builder {
            this.optionReferenceFunction = com.google.common.base.Preconditions.checkNotNull(optionReferenceFunction)
            return this
        }

        /**
         * Cause rules of this type to request the specified toolchains be available via toolchain
         * resolution when a target is configured.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addToolchainTypes(toolchainTypes: Iterable<ToolchainTypeRequirement?>): Builder {
            com.google.common.collect.Iterables.addAll<ToolchainTypeRequirement?>(this.toolchainTypes, toolchainTypes)
            return this
        }

        /**
         * Cause rules of this type to request the specified toolchains be available via toolchain
         * resolution when a target is configured.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addToolchainTypes(vararg toolchainTypes: ToolchainTypeRequirement?): Builder {
            return addToolchainTypes(
                com.google.common.collect.ImmutableList.copyOf<ToolchainTypeRequirement?>(
                    toolchainTypes
                )
            )
        }

        /**
         * Adds execution groups to this rule class. Errors out if multiple different groups with the
         * same name are added.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecGroups(execGroups: MutableMap<String?, DeclaredExecGroup?>, override: Boolean): Builder {
            for (group in execGroups.entrySet()) {
                val name: String? = group.getKey()
                if (this.execGroups.containsKey(name) && !override) {
                    // If trying to add a new execution group with the same name as a execution group that
                    // already exists, check if they are equivalent and error out if not.
                    val existingGroup: DeclaredExecGroup = this.execGroups.get(name)
                    val newGroup: DeclaredExecGroup? = group.getValue()
                    if (existingGroup != newGroup) {
                        throw DuplicateExecGroupError(name)
                    }
                } else {
                    this.execGroups.put(name, group.getValue())
                }
            }
            return this
        }

        /** An error to help report [DeclaredExecGroup]s with the same name  */
        internal class DuplicateExecGroupError(duplicateGroup: String?) : java.lang.RuntimeException(
            java.lang.String.format(
                "Multiple execution groups with the same name: '%s'.",
                duplicateGroup
            )
        ) {
            private val duplicateGroup: String?

            init {
                this.duplicateGroup = duplicateGroup
            }

            fun getDuplicateGroup(): String? {
                return duplicateGroup
            }
        }

        /** Checks whether the rule class has an exec group with the given name.  */
        fun hasExecGroup(name: String?): Boolean {
            return this.execGroups.containsKey(name)
        }

        /** Sets how this rule class uses auto exec groups.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun autoExecGroupsMode(autoExecGroupsMode: AutoExecGroupsMode?): Builder {
            this.autoExecGroupsMode = autoExecGroupsMode
            return this
        }

        /**
         * Causes rules to use toolchain resolution to determine the execution platform and toolchains.
         * Rules that are part of configuring toolchains and platforms should set this to `DISABLED`.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun toolchainResolutionMode(mode: ToolchainResolutionMode): Builder {
            this.toolchainResolutionMode = mode
            return this
        }

        /**
         * Adds additional execution platform constraints that apply for all targets from this rule.
         * 
         * 
         * Please note that this value is inherited by child rules.
         */
        fun addExecutionPlatformConstraints(vararg constraints: Label?): Builder {
            return this.addExecutionPlatformConstraints(com.google.common.collect.Lists.newArrayList<Label?>(*constraints))
        }

        /**
         * Adds additional execution platform constraints that apply for all targets from this rule.
         * 
         * 
         * Please note that this value is inherited by child rules.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecutionPlatformConstraints(constraints: Iterable<Label?>): Builder {
            com.google.common.collect.Iterables.addAll<Label?>(this.executionPlatformConstraints, constraints)
            return this
        }

        /**
         * Returns an Attribute.Builder object which contains a replica of the same attribute in the
         * parent rule if exists.
         * 
         * @param name the name of the attribute
         */
        fun copy(name: String?): com.google.devtools.build.lib.packages.Attribute.Builder<*> {
            com.google.common.base.Preconditions.checkArgument(
                attributes.containsKey(name!!), "Attribute %s does not exist in parent rule class.", name
            )
            return attributes.get(name).cloneBuilder()
        }

        companion object {
            private val RULE_NAME_PATTERN: java.util.regex.Pattern =
                java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*")

            /**
             * Name of default attribute implicitly added to all Starlark RuleClasses that are `build_setting`s.
             */
            const val STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME: String = "build_setting_default"

            const val STARLARK_BUILD_SETTING_HELP_ATTR_NAME: String = "help"

            const val BUILD_SETTING_DEFAULT_NONCONFIGURABLE: String =
                "Build setting defaults are referenced during analysis."

            /** List of required attributes for normal rules, name and type.  */
            val REQUIRED_ATTRIBUTES_FOR_NORMAL_RULES: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute> =
                com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.packages.Attribute?>(
                    com.google.devtools.build.lib.packages.Attribute.Companion.attr<MutableList<String?>?>(
                        "tags",
                        com.google.devtools.build.lib.packages.Types.STRING_LIST
                    ).build()
                )

            /** List of required attributes for test rules, name and type.  */
            val REQUIRED_ATTRIBUTES_FOR_TESTS: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute> =
                com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.packages.Attribute?>(
                    com.google.devtools.build.lib.packages.Attribute.Companion.attr<MutableList<String?>?>(
                        "tags",
                        com.google.devtools.build.lib.packages.Types.STRING_LIST
                    ).build(),
                    com.google.devtools.build.lib.packages.Attribute.Companion.attr<String?>(
                        "size",
                        com.google.devtools.build.lib.packages.Type.Companion.STRING
                    ).build(),
                    com.google.devtools.build.lib.packages.Attribute.Companion.attr<String?>(
                        "timeout",
                        com.google.devtools.build.lib.packages.Type.Companion.STRING
                    ).build(),
                    com.google.devtools.build.lib.packages.Attribute.Companion.attr<Boolean?>(
                        "flaky",
                        com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN
                    ).build(),
                    com.google.devtools.build.lib.packages.Attribute.Companion.attr<net.starlark.java.eval.StarlarkInt?>(
                        "shard_count",
                        com.google.devtools.build.lib.packages.Type.Companion.INTEGER
                    ).build(),
                    com.google.devtools.build.lib.packages.Attribute.Companion.attr<Boolean?>(
                        "local",
                        com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN
                    ).build()
                )

            @com.google.errorprone.annotations.FormatMethod
            @Throws(net.starlark.java.eval.EvalException::class)
            private fun failIf(condition: Boolean, message: String?, vararg args: Any?) {
                if (condition) {
                    throw net.starlark.java.eval.Starlark.errorf(message, *args)
                }
            }
        }
    }

    // record containing both the common rule_class 'name' (e.g. "cc_library") as
    // well as the unique 'key' for the rule class. Key has the same value as
    // 'name' for native rules and a combination of label + name for Starlark.
    private val ruleClassId: RuleClassId
    private val callstack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>? // of call to 'rule'

    /**
     * The kind of target represented by this RuleClass (e.g. "cc_library rule"). Note: Even though
     * there is partial duplication with the [RuleClass.name] field, we want to store this as a
     * separate field instead of generating it on demand in order to avoid string duplication.
     */
    private val targetKind: String

    private val type: RuleClassType?
    private val starlarkParent: RuleClass?
    private val initializer: net.starlark.java.eval.StarlarkFunction?
    private val labelConverterForInitializer: LabelConverter?
    @kotlin.jvm.JvmField
    private val isStarlark: Boolean
    private val extendable: Boolean

    // The following 2 fields may be non-null only if the rule is Starlark-defined.
    private val starlarkExtensionLabel: Label?
    @kotlin.jvm.JvmField
    private val starlarkDocumentation: String?
    private val extendableAllowlist: Label?
    private val starlarkTestable: Boolean
    private val documented: Boolean
    private val outputsToBindir: Boolean
    private val dependencyResolutionRule: Boolean
    private val isMaterializerRule: Boolean
    private val materializerRuleAllowsRealDeps: Boolean
    @kotlin.jvm.JvmField
    private val isExecutableStarlark: Boolean
    private val isAnalysisTest: Boolean
    private val hasAnalysisTestTransition: Boolean
    private val allowlistCheckers: com.google.common.collect.ImmutableList<AllowlistChecker?>?
    private val hasAspects: Boolean

    private val attributeProvider: com.google.devtools.build.lib.packages.AttributeProvider

    /** The set of implicit outputs generated by a rule, expressed as a function of that rule.  */
    private val implicitOutputsFunction: ImplicitOutputsFunction?

    /**
     * A factory which will produce a configuration transition that should be applied on any edge of
     * the configured target graph that leads into a target of this rule class.
     */
    private val transitionFactory: TransitionFactory<RuleTransitionData?>?

    /** The factory that creates configured targets from this rule.  */
    private val configuredTargetFactory: ConfiguredTargetFactory<*, *, *>?

    /** The list of transitive info providers this class advertises to aspects.  */
    private val advertisedProviders: AdvertisedProviderSet?

    /**
     * The Starlark rule implementation of this RuleClass. Null for non Starlark executable
     * RuleClasses.
     */
    private val configuredTargetFunction: net.starlark.java.eval.StarlarkCallable?

    /**
     * The BuildSetting associated with this rule. Null for all RuleClasses except Starlark-defined
     * rules that pass `build_setting` to their `rule()` declaration.
     */
    private val buildSetting: BuildSetting?

    /**
     * The subrules associated with this rule. Empty for all rule classes except Starlark-defined
     * rules that explicitly pass `subrules = [...]` to their `rule()` declaration
     */
    private val subrules: com.google.common.collect.ImmutableSet<out StarlarkSubruleApi?>

    /** Returns the options referenced by this rule's attributes.  */
    private val optionReferenceFunction: com.google.common.base.Function<in com.google.devtools.build.lib.packages.Rule?, out MutableSet<String?>?>?

    /**
     * The Starlark rule definition environment's label and hash code of this RuleClass. Null for non
     * Starlark executable RuleClasses.
     */
    private val ruleDefinitionEnvironmentLabel: Label?

    @kotlin.jvm.JvmField
    private val ruleDefinitionEnvironmentDigest: ByteArray?

    private val outputFileKind: com.google.devtools.build.lib.packages.OutputFile.Kind?

    /**
     * The set of configuration fragments which are legal for this rule's implementation to access.
     */
    private val configurationFragmentPolicy: ConfigurationFragmentPolicy?

    /**
     * Determines whether instances of this rule should be checked for constraint compatibility with
     * their dependencies and the rules that depend on them. This should be true for everything except
     * for rules that are intrinsically incompatible with the constraint system.
     */
    private val supportsConstraintChecking: Boolean

    private val toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>
    private val toolchainResolutionMode: ToolchainResolutionMode
    private val executionPlatformConstraints: com.google.common.collect.ImmutableSet<Label?>
    private val declaredExecGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>
    @kotlin.jvm.JvmField
    private val autoExecGroupsMode: AutoExecGroupsMode?

    /**
     * Constructs an instance of RuleClass whose name is 'name', attributes are 'attributes'. The
     * `srcsAllowedFiles` determines which types of files are allowed as parameters to the
     * "srcs" attribute; rules are always allowed. For the "deps" attribute, there are four cases:
     * 
     * 
     *  * if the parameter is a file, it is allowed if its file type is given in `depsAllowedFiles`,
     *  * if the parameter is a rule and the rule class is accepted by `depsAllowedRules`,
     * then it is allowed,
     *  * if the parameter is a rule and the rule class is not accepted by `depsAllowedRules`, but accepted by `depsAllowedRulesWithWarning`, then it is
     * allowed, but triggers a warning;
     *  * all other parameters trigger an error.
     * 
     * 
     * 
     * The `depsAllowedRules` predicate should have a `toString` method which returns a
     * plain English enumeration of the allowed rule class names, if it does not allow all rule
     * classes.
     */
    init {
        this.ruleClassId = RuleClassId.Companion.create(name, key)
        this.callstack = callstack
        this.type = type
        this.starlarkParent = starlarkParent
        this.initializer = initializer
        this.labelConverterForInitializer = labelConverterForInitializer
        this.isStarlark = isStarlark
        this.starlarkExtensionLabel = starlarkExtensionLabel
        this.starlarkDocumentation = starlarkDocumentation
        this.extendable = extendable
        this.extendableAllowlist = extendableAllowlist
        this.targetKind = name + com.google.devtools.build.lib.packages.Rule.Companion.targetKindSuffix()
        this.starlarkTestable = starlarkTestable
        this.documented = documented
        this.outputsToBindir = outputsToBindir
        this.implicitOutputsFunction = implicitOutputsFunction
        this.transitionFactory = transitionFactory
        this.configuredTargetFactory = configuredTargetFactory
        this.advertisedProviders = advertisedProviders
        this.configuredTargetFunction = configuredTargetFunction
        this.optionReferenceFunction = optionReferenceFunction
        this.ruleDefinitionEnvironmentLabel = ruleDefinitionEnvironmentLabel
        this.ruleDefinitionEnvironmentDigest = ruleDefinitionEnvironmentDigest
        this.outputFileKind = outputFileKind
        this.dependencyResolutionRule = dependencyResolutionRule
        this.isMaterializerRule = isMaterializerRule
        this.materializerRuleAllowsRealDeps = materializerRuleAllowsRealDeps
        this.isExecutableStarlark = isExecutableStarlark
        this.isAnalysisTest = isAnalysisTest
        this.hasAnalysisTestTransition = hasAnalysisTestTransition
        this.allowlistCheckers = allowlistCheckers
        this.configurationFragmentPolicy = configurationFragmentPolicy
        this.supportsConstraintChecking = supportsConstraintChecking
        this.toolchainTypes = com.google.common.collect.ImmutableSet.copyOf<ToolchainTypeRequirement?>(toolchainTypes)
        this.toolchainResolutionMode = toolchainResolutionMode
        this.executionPlatformConstraints =
            com.google.common.collect.ImmutableSet.copyOf<Label?>(executionPlatformConstraints)
        this.declaredExecGroups =
            com.google.common.collect.ImmutableMap.copyOf<String?, DeclaredExecGroup?>(declaredExecGroups)
        this.autoExecGroupsMode = autoExecGroupsMode
        this.buildSetting = buildSetting
        this.subrules = com.google.common.collect.ImmutableSet.copyOf(subrules)
        // Create the index and collect non-configurable attributes while doing some validation checks.
        com.google.common.base.Preconditions.checkState(
            !attributes.isEmpty() && attributes.get(0) == NAME_ATTRIBUTE,
            "Rule %s does not have name as its first attribute: %s",
            name,
            attributes
        )
        val attributeIndex: MutableMap<String?, Int?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<String?, Int?>(attributes.size())
        val publicToPrivateNames: MutableMap<String?, com.google.devtools.build.lib.packages.Attribute?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<String?, com.google.devtools.build.lib.packages.Attribute?>(
                attributes.size()
            )
        var computedHasAspects = false
        val nonConfigurableAttributes: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        for (i in attributes.indices) {
            val attribute: com.google.devtools.build.lib.packages.Attribute = attributes.get(i)
            val publicName: String? = attribute.getPublicName()
            val conflicting: com.google.devtools.build.lib.packages.Attribute? =
                publicToPrivateNames.put(publicName, attribute)
            check(conflicting == null) {
                java.lang.String.format(
                    "Rule %s: Attributes %s and %s have an identical public name: %s",
                    name, attribute.getName(), conflicting.getName(), publicName
                )
            }
            computedHasAspects = computedHasAspects or attribute.hasAspects()
            attributeIndex.put(attribute.getName(), i)
            if (!attribute.isConfigurable()) {
                nonConfigurableAttributes.add(attribute.getName())
            }
        }
        this.attributeProvider =
            com.google.devtools.build.lib.packages.AttributeProvider(
                attributes, attributeIndex, nonConfigurableAttributes.build(), name, ignoreLicenses
            )
        this.hasAspects = computedHasAspects
    }

    /**
     * Returns the default function for determining the set of implicit outputs generated by a given
     * rule. If not otherwise specified, this will be the implementation used by [Rule]s created
     * with this [RuleClass].
     * 
     * 
     * An implicit output is an OutputFile that automatically comes into existence when a rule of
     * this class is declared, and whose name is derived from the name of the rule.
     * 
     * 
     * Implicit outputs are a widely-relied upon. All ".so", and "_deploy.jar" targets referenced
     * in BUILD files are examples.
     */
    // (public for serialization)
    fun getDefaultImplicitOutputsFunction(): ImplicitOutputsFunction? {
        return implicitOutputsFunction
    }

    fun getTransitionFactory(): TransitionFactory<RuleTransitionData?>? {
        return transitionFactory
    }

    fun <T : ConfiguredTargetFactory<*, *, *>?> getConfiguredTargetFactory(clazz: java.lang.Class<T?>): T? {
        return clazz.cast(configuredTargetFactory)
    }

    fun getConfiguredTargetFactory(): ConfiguredTargetFactory<*, *, *>? {
        return configuredTargetFactory
    }

    /** Returns the class of rule that this RuleClass represents (e.g. "cc_library").  */
    override fun getName(): String? {
        return ruleClassId.name
    }

    fun getStarlarkParent(): RuleClass? {
        return this.starlarkParent
    }

    fun getInitializer(): net.starlark.java.eval.StarlarkFunction? {
        return initializer
    }

    fun getLabelConverterForInitializer(): LabelConverter? {
        return labelConverterForInitializer
    }

    /**
     * Returns the stack of Starlark active function calls at the moment this rule class was created.
     * Entries appear outermost first, and exclude the built-in itself ('rule'). Empty for
     * non-Starlark rules.
     */
    fun getCallStack(): com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>? {
        return callstack
    }

    /** Returns the type of rule that this RuleClass represents. Only for use during serialization.  */
    fun getRuleClassType(): RuleClassType? {
        return type
    }

    /** Returns a unique key. Used for profiling purposes.  */
    fun getKey(): String? {
        return ruleClassId.key
    }

    /** Returns the record containing both the name and key.  */
    fun getRuleClassId(): RuleClassId {
        return this.ruleClassId
    }

    /** Returns the target kind of this class of rule (e.g. "cc_library rule").  */
    override fun getTargetKind(): String {
        return targetKind
    }

    /**
     * Returns the attribute provider for this rule class. This can be queried to understand the
     * attribute schema associated with the rule.
     */
    fun getAttributeProvider(): com.google.devtools.build.lib.packages.AttributeProvider {
        return attributeProvider
    }

    /**
     * Returns the set of advertised transitive info providers.
     * 
     * 
     * When computing the set of aspects required for a rule, only the providers listed here are
     * considered. The presence of a provider here does not mean that the rule **must** implement
     * said provider, merely that it **can**. After the configured target is constructed from this
     * rule, aspects will be filtered according to the set of actual providers.
     * 
     * 
     * This is here so that we can do the loading phase overestimation required for "blaze query",
     * which does not have the configured targets available.
     */
    override fun getAdvertisedProviders(): AdvertisedProviderSet? {
        return advertisedProviders
    }

    /** Returns this rule's policy for configuration fragment access.  */
    fun getConfigurationFragmentPolicy(): ConfigurationFragmentPolicy? {
        return configurationFragmentPolicy
    }

    /** Returns true if rules of this type can be used with the constraint enforcement system.  */
    fun supportsConstraintChecking(): Boolean {
        return supportsConstraintChecking
    }

    fun hasAspects(): Boolean {
        return hasAspects
    }

    /**
     * Creates a new [Rule] `r` where `r.getPackageoid()` is the [Packageoid]
     * associated with `targetDefinitionContext`.
     * 
     * 
     * The created [Rule] will be populated with attribute values from `attributeValues` or the default attribute values associated with this [RuleClass] and
     * `targetDefinitionContext`.
     * 
     * 
     * The created [Rule] will also be populated with output files. These output files will
     * have been collected from the explicitly provided values of type [BuildType.OUTPUT] and
     * [BuildType.OUTPUT_LIST] as well as from the implicit outputs determined by this [ ] and the values in `attributeValues`.
     * 
     * 
     * This performs several validity checks. Invalid output file labels result in a thrown [ ]. Computed default attributes that fail during precomputation result in a
     * [CannotPrecomputeDefaultsException]. All other errors are reported on `eventHandler`.
     */
    @Throws(
        LabelSyntaxException::class,
        java.lang.InterruptedException::class,
        CannotPrecomputeDefaultsException::class
    )
    fun <T> createRule(
        targetDefinitionContext: TargetDefinitionContext,
        ruleLabel: Label?,
        attributeValues: com.google.devtools.build.lib.packages.RuleFactory.AttributeValues<T?>?,
        failOnUnknownAttributes: Boolean,
        callstack: MutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?
    ): com.google.devtools.build.lib.packages.Rule {
        val eventHandler: EventHandler = targetDefinitionContext.getLocalEventHandler()

        val rule: com.google.devtools.build.lib.packages.Rule =
            targetDefinitionContext.createRule(ruleLabel, this, callstack)
        attributeProvider.populateRuleAttributeValues<T?>(
            rule, targetDefinitionContext, attributeValues, failOnUnknownAttributes, isStarlark
        )
        checkAspectAllowedValues(rule, eventHandler)
        rule.populateOutputFiles(eventHandler, targetDefinitionContext.getPackageIdentifier())
        checkForDuplicateLabels(rule, eventHandler)

        checkForValidSizeAndTimeoutValues(rule, eventHandler)
        return rule
    }

    /**
     * Same as [.createRule], except without some internal checks.
     * 
     * 
     * Don't call this function unless you know what you're doing.
     */
    @Throws(java.lang.InterruptedException::class, CannotPrecomputeDefaultsException::class)
    fun <T> createRuleUnchecked(
        targetDefinitionContext: TargetDefinitionContext,
        ruleLabel: Label?,
        attributeValues: com.google.devtools.build.lib.packages.RuleFactory.AttributeValues<T?>?,
        callstack: CallStack.Node,
        implicitOutputsFunction: ImplicitOutputsFunction?
    ): com.google.devtools.build.lib.packages.Rule {
        val rule: com.google.devtools.build.lib.packages.Rule =
            targetDefinitionContext.createRule(
                ruleLabel, this, callstack.toLocation(), callstack.next()
            )
        attributeProvider.populateRuleAttributeValues<T?>(
            rule, targetDefinitionContext, attributeValues, true, isStarlark
        )
        rule.populateOutputFilesUnchecked(targetDefinitionContext, implicitOutputsFunction)
        return rule
    }

    override fun toString(): String {
        return ruleClassId.name
    }

    fun isDocumented(): Boolean {
        return documented
    }

    /**
     * Returns true iff the outputs of this rule should be created beneath the *bin* directory,
     * false if beneath *genfiles*. For most rule classes, this is a constant, but for genrule,
     * it is a property of the individual rule instance, derived from the 'output_to_bindir'
     * attribute; see Rule.outputsToBindir().
     */
    fun outputsToBindir(): Boolean {
        return outputsToBindir
    }

    /** Returns this RuleClass's custom Starlark rule implementation.  */
    fun getConfiguredTargetFunction(): net.starlark.java.eval.StarlarkCallable? {
        return configuredTargetFunction
    }

    fun getBuildSetting(): BuildSetting? {
        return buildSetting
    }

    /** Returns a function that computes the options referenced by a rule.  */
    fun getOptionReferenceFunction(): com.google.common.base.Function<in com.google.devtools.build.lib.packages.Rule?, out MutableSet<String?>?>? {
        return optionReferenceFunction
    }

    /**
     * For Starlark rule classes, returns this RuleClass's rule definition environment's label, which
     * is never null. Is null for native rules' RuleClass objects.
     * 
     * 
     * In certain unusual cases (for example, analysis test rule classes), the values of [ ][.getRuleDefinitionEnvironmentLabel] and [.getStarlarkExtensionLabel] may differ.
     */
    // TODO(b/366027483): unify starlarkExtensionLabel and ruleDefinitionEnvironmentLabel.
    override fun getRuleDefinitionEnvironmentLabel(): Label? {
        return ruleDefinitionEnvironmentLabel
    }

    /**
     * Returns the digest for the RuleClass's rule definition environment, a hash of the .bzl file
     * defining the rule class and all the .bzl files it transitively loads. Null for native rules'
     * RuleClass objects.
     * 
     * 
     * This digest is sensitive to any changes in the declaration of the RuleClass itself,
     * including changes in the .bzl files it transitively loads, but it is not unique: all
     * RuleClasses defined within in the same .bzl file have the same digest.
     * 
     * 
     * To uniquely identify a rule class, we need the triple: ([ ][.getRuleDefinitionEnvironmentLabel], [.getRuleDefinitionEnvironmentDigest], [ ][.getName]) The first two components are collectively known as the "rule definition
     * environment". Dependency analysis may compare these triples to detect whether a change to a
     * rule definition might have consequences for a rule instance that has not otherwise changed.
     * 
     * 
     * Note: this concept of rule definition environment is not related to the [ ] interface.
     */
    fun getRuleDefinitionEnvironmentDigest(): ByteArray? {
        return ruleDefinitionEnvironmentDigest
    }

    /** Returns true if this RuleClass is a Starlark-defined RuleClass.  */
    override fun isStarlark(): Boolean {
        return isStarlark
    }

    /**
     * If this is a Starlark-defined rule class which had been exported, returns the label of the
     * Starlark file (typically a .bzl file, except for analysis test rule classes where it is a BUILD
     * file) where the rule definition was exported, or null otherwise.
     * 
     * 
     * If a Starlark rule class has been exported, the tuple (rule name, starlark extension label)
     * uniquely identifies it.
     * 
     * 
     * In certain unusual cases (for example, analysis test rule classes), the values of [ ][.getRuleDefinitionEnvironmentLabel] and [.getStarlarkExtensionLabel] may differ.
     */
    // TODO(b/366027483): unify starlarkExtensionLabel and ruleDefinitionEnvironmentLabel.
    fun getStarlarkExtensionLabel(): Label? {
        return starlarkExtensionLabel
    }

    /**
     * If this is a Starlark-defined rule class which had been defined with a documentation string,
     * i.e. via `rule(doc = "...")`), returns that documentation string, or null otherwise.
     */
    fun getStarlarkDocumentation(): String? {
        return starlarkDocumentation
    }

    /** Returns true if this RuleClass can be extended.  */
    fun isExtendable(): Boolean {
        return extendable
    }

    fun getExtendableAllowlist(): Label? {
        return extendableAllowlist
    }

    /** Returns true if this RuleClass is Starlark-defined and is subject to analysis-time tests.  */
    fun isStarlarkTestable(): Boolean {
        return starlarkTestable
    }

    /** Returns true if rules of this class can be made available for dependency resolution.  */
    override fun isDependencyResolutionRule(): Boolean {
        return dependencyResolutionRule
    }

    /** Whether this rule class is a materializer rule.  */
    override fun isMaterializerRule(): Boolean {
        return isMaterializerRule
    }

    /** Whether this materializer rule allows real deps.  */
    override fun materializerRuleAllowsRealDeps(): Boolean {
        return materializerRuleAllowsRealDeps
    }

    /** Returns true if this rule class outputs a default executable for every rule.  */
    fun isExecutableStarlark(): Boolean {
        return isExecutableStarlark
    }

    /** Returns true if this rule class is an analysis test (set by analysis_test = true).  */
    fun isAnalysisTest(): Boolean {
        return isAnalysisTest
    }

    /**
     * Returns true if this rule class has at least one attribute with an analysis test transition. (A
     * starlark-defined transition using analysis_test_transition()).
     */
    fun hasAnalysisTestTransition(): Boolean {
        return hasAnalysisTestTransition
    }

    /** Returns a list of AllowlistChecker to check.  */
    fun getAllowlistCheckers(): com.google.common.collect.ImmutableList<AllowlistChecker?>? {
        return allowlistCheckers
    }

    /**
     * If true, no rule of this class ever declares a license regardless of what the rule's or
     * package's `licenses` attribute says.
     * 
     * 
     * This is useful for rule types that don't make sense for license checking.
     */
    fun ignoreLicenses(): Boolean {
        return attributeProvider.ignoreLicenses()
    }

    fun getToolchainTypes(): com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?> {
        return toolchainTypes
    }

    fun useToolchainResolution(rule: com.google.devtools.build.lib.packages.Rule?): Boolean {
        return this.toolchainResolutionMode.useToolchainResolution(rule)
    }

    fun getExecutionPlatformConstraints(): com.google.common.collect.ImmutableSet<Label?> {
        return executionPlatformConstraints
    }

    fun getDeclaredExecGroups(): com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?> {
        return declaredExecGroups
    }

    fun getAutoExecGroupsMode(): AutoExecGroupsMode? {
        return autoExecGroupsMode
    }

    fun getOutputFileKind(): com.google.devtools.build.lib.packages.OutputFile.Kind? {
        return outputFileKind
    }

    /**
     * Returns true if this rule is a `license()` as described in
     * https://docs.google.com/document/d/1uwBuhAoBNrw8tmFs-NxlssI6VRolidGYdYqagLqHWt8/edit# or
     * similar metadata.
     * 
     * 
     * The intended use is to detect if this rule is of a type which would be used in `
     * default_package_metadata`, so that we don't apply it to an instanced of itself when
     * `applicable_metadata` is left unset. Doing so causes a self-referential loop. To
     * prevent that, we are overly cautious at this time, treating all rules from `@rules_license
    ` *  as potential metadata rules.
     * 
     * 
     * Most users will only use declarations from `@rules_license`. If they which to
     * create organization local rules, they must be careful to avoid loops by explicitly setting
     * `applicable_metadata` on each of the metadata targets they define, so that default
     * processing is not an issue.
     */
    fun isPackageMetadataRule(): Boolean {
        // If it was not defined in Starlark, it can not be a new style package metadata rule.
        if (ruleDefinitionEnvironmentLabel == null) {
            return false
        }
        if (ruleDefinitionEnvironmentLabel.getRepository().name.equals("rules_license")) {
            // For now, we treat all rules in rules_license as potenial metadate rules.
            // In the future we should add a way to disambiguate the two. The least invasive
            // thing is to add a hidden attribute to mark metadata rules. That attribute
            // could have a default value referencing @rules_license//<something>. That style
            // of checking would allow users to apply it to their own metadata rules. We are
            // not building it today because the exact needs are not clear.
            return true
        }
        // BEGIN-INTERNAL
        // TODO(aiuto): This is a Google-ism, remove from Bazel.
        val packageName: String = ruleDefinitionEnvironmentLabel.getPackageName()
        if (packageName.startsWith("tools/build_defs/license")
            || packageName.startsWith("third_party/rules_license")
        ) {
            return true
        }
        // END-INTERNAL
        return false
    }

    fun getSubrules(): com.google.common.collect.ImmutableSet<out StarlarkSubruleApi?> {
        com.google.common.base.Preconditions.checkState(isStarlark())
        return subrules
    }

    companion object {
        /** The name attribute, present for all rules at index 0. Also defined for all symbolic macros.  */
        @kotlin.jvm.JvmField
        val NAME_ATTRIBUTE: com.google.devtools.build.lib.packages.Attribute =
            com.google.devtools.build.lib.packages.Attribute.Companion.attr<String?>(
                "name",
                com.google.devtools.build.lib.packages.Type.Companion.STRING_NO_INTERN
            )
                .nonconfigurable("All rules have a non-customizable \"name\" attribute")
                .mandatory()
                .build()

        /**
         * Maximum attributes per RuleClass. Current value was chosen to be high enough to be considered a
         * non-breaking change for reasonable use. It was also chosen to be low enough to give significant
         * headroom before hitting limits imposed by the compact attribute value storage strategy in
         * [Rule].
         */
        private const val MAX_ATTRIBUTES = 200

        /**
         * Maximum attribute name length. Chosen to accommodate existing and prevent extreme outliers from
         * forming - extreme values create bloat, both in memory usage and various outputs, including but
         * not limited to, query output.
         */
        private const val MAX_ATTRIBUTE_NAME_LENGTH = 128

        @kotlin.jvm.JvmField
        @SerializationConstant
        val NO_OPTION_REFERENCE: com.google.common.base.Function<in com.google.devtools.build.lib.packages.Rule?, MutableSet<String?>?> =
            com.google.common.base.Functions.constant<MutableSet<String?>?>(com.google.common.collect.ImmutableSet.of<String?>())

        val THIRD_PARTY_PREFIX: PathFragment? = PathFragment.create("third_party")
        val EXPERIMENTAL_PREFIX: PathFragment? = PathFragment.create("experimental")

        /** The attribute that declares the set of metadata labels which apply to this target.  */
        const val APPLICABLE_METADATA_ATTR: String = "package_metadata"

        const val APPLICABLE_METADATA_ATTR_ALT: String = "applicable_licenses"

        const val DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME: String = "test"
        val DEFAULT_TEST_RUNNER_EXEC_GROUP: DeclaredExecGroup? = DeclaredExecGroup.Companion.builder()
            .addToolchainType(
                ToolchainTypeRequirement.create(PlatformConstants.DEFAULT_TEST_TOOLCHAIN_TYPE)
            )
            .build()

        /**
         * For Bazel's constraint system: the attribute that declares the set of environments a rule
         * supports, overriding the defaults for their respective groups.
         */
        const val RESTRICTED_ENVIRONMENT_ATTR: String = "restricted_to"

        /**
         * For Bazel's constraint system: the attribute that declares the set of environments a rule
         * supports, appending them to the defaults for their respective groups.
         */
        const val COMPATIBLE_ENVIRONMENT_ATTR: String = "compatible_with"

        /**
         * For Bazel's constraint system: the attribute that declares the list of constraints that the
         * target platform must satisfy to be considered compatible.
         */
        const val TARGET_COMPATIBLE_WITH_ATTR: String = "target_compatible_with"

        /**
         * For Bazel's constraint system: the attribute that declares the list of constraints that the
         * default exec group's execution platform must satisfy to be considered compatible.
         */
        const val EXEC_COMPATIBLE_WITH_ATTR: String = "exec_compatible_with"

        /**
         * For Bazel's constraint system: the attribute that declares the list of constraints that the
         * given exec groups' execution platforms must satisfy to be considered compatible.
         */
        const val EXEC_GROUP_COMPATIBLE_WITH_ATTR: String = "exec_group_compatible_with"

        /**
         * The attribute that declares execution properties that should be added to actions created by
         * this target.
         */
        const val EXEC_PROPERTIES_ATTR: String = "exec_properties"

        /**
         * For Bazel's constraint system: the implicit attribute used to store rule class restriction
         * defaults as specified by [Builder.restrictedTo].
         */
        val DEFAULT_RESTRICTED_ENVIRONMENT_ATTR: String = "$" + RESTRICTED_ENVIRONMENT_ATTR

        /**
         * For Bazel's constraint system: the implicit attribute used to store rule class compatibility
         * defaults as specified by [Builder.compatibleWith].
         */
        val DEFAULT_COMPATIBLE_ENVIRONMENT_ATTR: String = "$" + COMPATIBLE_ENVIRONMENT_ATTR

        /**
         * Name of the attribute that stores all [ ] labels this rule references (i.e.
         * select() keys). This is specially populated in [.populateRuleAttributeValues].
         * 
         * 
         * This isn't technically necessary for builds: select() keys are evaluated in [ ][com.google.devtools.build.lib.skyframe.PrerequisiteProducer.computeConfigConditions] instead of
         * normal dependency resolution because they're needed to determine other dependencies. So there's
         * no intrinsic reason why we need an extra attribute to store them.
         * 
         * 
         * There are four reasons why we still create this attribute:
         * 
         * 
         *  1. Collecting them once in [.populateRuleAttributeValues] instead of multiple times in
         * ConfiguredTargetFunction saves extra looping over the rule's attributes.
         *  1. Query's dependency resolution has no equivalent of [       ][com.google.devtools.build.lib.skyframe.PrerequisiteProducer.computeConfigConditions] and
         * we need to make sure its coverage remains complete.
         *  1. Manual configuration trimming uses the normal dependency resolution process to work
         * correctly and config_setting keys are subject to this trimming.
         *  1. [supports conditional toolchain resolution for][Rule.useToolchainResolution]
         */
        const val CONFIG_SETTING_DEPS_ATTRIBUTE: String = "\$config_dependencies"

        /**
         * Report an error for each label that appears more than once in a LABEL_LIST attribute of the
         * given rule.
         * 
         * @param rule The rule.
         * @param eventHandler The eventHandler to use to report the duplicated deps.
         */
        private fun checkForDuplicateLabels(
            rule: com.google.devtools.build.lib.packages.Rule,
            eventHandler: EventHandler
        ) {
            val mapper: AggregatingAttributeMapper = AggregatingAttributeMapper.Companion.of(rule)
            for (attribute in rule.getAttributeProvider().getAttributes()) {
                val duplicates: MutableSet<Label?> = mapper.checkForDuplicateLabels(attribute)
                if (duplicates.isEmpty()) {
                    continue
                }
                for (label in duplicates) {
                    rule.reportError(
                        java.lang.String.format(
                            "Label '%s' is duplicated in the '%s' attribute of rule '%s'",
                            label, attribute.getName(), rule.getName()
                        ),
                        eventHandler
                    )
                }
            }
        }

        /**
         * Report an error if the rule has a timeout or size attribute that is not a legal value. These
         * attributes appear on all tests.
         * 
         * @param rule the rule to check
         * @param eventHandler the eventHandler to use to report the duplicated deps
         */
        private fun checkForValidSizeAndTimeoutValues(
            rule: com.google.devtools.build.lib.packages.Rule,
            eventHandler: EventHandler
        ) {
            if (rule.getRuleClassObject().getAttributeProvider()
                    .hasAttr("size", com.google.devtools.build.lib.packages.Type.Companion.STRING)
            ) {
                val size: String? = NonconfigurableAttributeMapper.Companion.of(rule)
                    .get<String?>("size", com.google.devtools.build.lib.packages.Type.Companion.STRING)
                if (TestSize.Companion.getTestSize(size) == null) {
                    rule.reportError(
                        java.lang.String.format("In rule '%s', size '%s' is not a valid size.", rule.getName(), size),
                        eventHandler
                    )
                }
            }
            if (rule.getRuleClassObject().getAttributeProvider()
                    .hasAttr("timeout", com.google.devtools.build.lib.packages.Type.Companion.STRING)
            ) {
                val timeout: String? = NonconfigurableAttributeMapper.Companion.of(rule)
                    .get<String?>("timeout", com.google.devtools.build.lib.packages.Type.Companion.STRING)
                if (TestTimeout.Companion.getTestTimeout(timeout) == null) {
                    rule.reportError(
                        java.lang.String.format(
                            "In rule '%s', timeout '%s' is not a valid timeout.", rule.getName(), timeout
                        ),
                        eventHandler
                    )
                }
            }
        }

        private fun checkAspectAllowedValues(
            rule: com.google.devtools.build.lib.packages.Rule,
            eventHandler: EventHandler
        ) {
            if (rule.hasAspects()) {
                for (attrOfRule in rule.getAttributeProvider().getAttributes()) {
                    for (aspect in attrOfRule.getAspects(rule)) {
                        for (attrOfAspect in aspect.getDefinition().getAttributes().values()) {
                            // By this point the AspectDefinition has been created and values assigned.
                            if (attrOfAspect.checkAllowedValues()) {
                                val allowedValues: PredicateWithMessage<Any?> = attrOfAspect.getAllowedValues()
                                val value: Any? = attrOfAspect.getDefaultValue(null)
                                if (!allowedValues.apply(value)) {
                                    if (RawAttributeMapper.Companion.of(rule).isConfigurable(attrOfAspect.getName())) {
                                        rule.reportError(
                                            java.lang.String.format(
                                                "%s: attribute '%s' has a select() and aspect %s also declares "
                                                        + "'%s'. Aspect attributes don't currently support select().",
                                                rule.getLabel(),
                                                attrOfAspect.getName(),
                                                aspect.getDefinition().getName(),
                                                rule.getLabel()
                                            ),
                                            eventHandler
                                        )
                                    } else {
                                        rule.reportError(
                                            java.lang.String.format(
                                                "%s: invalid value in '%s' attribute: %s",
                                                rule.getLabel(),
                                                attrOfAspect.getName(),
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
            }
        }
    }
}
