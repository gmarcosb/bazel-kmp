// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options

import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.AllowValues

/**
 * Enforces the [FlagPolicy]s (from an [InvocationPolicy] proto) on an [ ] by validating and changing the flag values in the given [OptionsParser].
 * 
 * 
 * "Flag" and "Option" are used interchangeably in this file.
 */
class InvocationPolicyEnforcer(
    invocationPolicy: InvocationPolicy?,
    loglevel: java.util.logging.Level?,
    private val conversionContext: Any?
) {
    // LINT.ThenChange(//src/main/java/com/google/devtools/common/options/GlobalRcUtils.java, //src/main/java/com/google/devtools/common/options/GlobalRcUtils.java.oss)
    private val invocationPolicy: InvocationPolicy
    private val loglevel: java.util.logging.Level

    /**
     * Creates an InvocationPolicyEnforcer that enforces the given policy.
     * 
     * @param invocationPolicy the policy to enforce.
     * @param loglevel the level at which to log informational statements. Warnings and errors will
     * still be logged at the appropriate level.
     */
    init {
        this.invocationPolicy = com.google.common.base.Preconditions.checkNotNull<InvocationPolicy>(invocationPolicy)
        this.loglevel = com.google.common.base.Preconditions.checkNotNull<java.util.logging.Level>(loglevel)
    }

    private class FlagPolicyWithContext(
        policy: FlagPolicy,
        description: com.google.devtools.common.options.OptionsParser.OptionDescription,
        origin: com.google.devtools.common.options.OptionInstanceOrigin?
    ) {
        private val policy: FlagPolicy
        private val description: com.google.devtools.common.options.OptionsParser.OptionDescription
        private val origin: com.google.devtools.common.options.OptionInstanceOrigin?

        init {
            this.policy = policy
            this.description = description
            this.origin = origin
        }
    }

    /**
     * Applies this instance's policy to the provided options parser.
     * 
     * @param parser The OptionsParser to enforce policy on.
     * @param command The blaze command to enforce the policy for. Flag policies that apply to
     * specific commands will be enforced only if they contain this command or a command it
     * inherits from.
     * @param invocationPolicyFlagListBuilder A builder that will be populated with the list of
     * unparsed flags that invocation policy applies to the command.
     * @throws OptionsParsingException if any flag policy is invalid.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun enforce(
        parser: com.google.devtools.common.options.OptionsParser,
        command: String?,
        invocationPolicyFlagListBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.common.options.OptionAndRawValue?>
    ) {
        com.google.common.base.Preconditions.checkNotNull<String?>(command, "command is required")
        if (invocationPolicy.getFlagPoliciesCount() === 0) {
            return
        }

        // The effective policy returned is expanded, filtered for applicable commands, and cleaned of
        // redundancies and conflicts.
        val effectivePolicies: MutableList<FlagPolicyWithContext> =
            getEffectivePolicies(invocationPolicy, parser, command, loglevel)

        for (flagPolicy in effectivePolicies) {
            val flagName: String? = flagPolicy.policy.getFlagName()

            val valueDescription: com.google.devtools.common.options.OptionValueDescription?
            try {
                valueDescription = parser.getOptionValueDescription(flagName)
            } catch (e: java.lang.IllegalArgumentException) {
                // This flag doesn't exist. We are deliberately lenient if the flag policy has a flag
                // we don't know about. This is for better future proofing so that as new flags are added,
                // new policies can use the new flags without worrying about older versions of Bazel.
                logger.at(loglevel).log(
                    "Flag '%s' specified by invocation policy does not exist", flagName
                )
                continue
            }

            // getOptionDescription() will return null if the option does not exist, however
            // getOptionValueDescription() above would have thrown an IllegalArgumentException if that
            // were the case.
            com.google.common.base.Verify.verifyNotNull<com.google.devtools.common.options.OptionsParser.OptionDescription?>(
                flagPolicy.description
            )

            when (flagPolicy.policy.getOperationCase()) {
                SET_VALUE -> applySetValueOperation(
                    parser,
                    flagPolicy,
                    valueDescription,
                    loglevel,
                    conversionContext,
                    invocationPolicyFlagListBuilder
                )

                USE_DEFAULT -> applyUseDefaultOperation(
                    parser,
                    "UseDefault",
                    flagPolicy.description.getOptionDefinition(),
                    loglevel,
                    conversionContext,
                    invocationPolicyFlagListBuilder
                )

                ALLOW_VALUES -> {
                    val allowValues: AllowValues = flagPolicy.policy.getAllowValues()
                    val allowValueOperation: AllowValueOperation =
                        AllowValueOperation(loglevel, conversionContext)
                    allowValueOperation.apply(
                        parser,
                        flagPolicy.origin,
                        allowValues.getAllowedValuesList(),
                        if (allowValues.hasNewValue()) allowValues.getNewValue() else null,
                        allowValues.hasUseDefault(),
                        valueDescription,
                        flagPolicy.description,
                        invocationPolicyFlagListBuilder
                    )
                }

                DISALLOW_VALUES -> {
                    val disallowValues: DisallowValues = flagPolicy.policy.getDisallowValues()
                    val disallowValueOperation: DisallowValueOperation =
                        DisallowValueOperation(loglevel, conversionContext)
                    disallowValueOperation.apply(
                        parser,
                        flagPolicy.origin,
                        disallowValues.getDisallowedValuesList(),
                        if (disallowValues.hasNewValue()) disallowValues.getNewValue() else null,
                        disallowValues.hasUseDefault(),
                        valueDescription,
                        flagPolicy.description,
                        invocationPolicyFlagListBuilder
                    )
                }

                OPERATION_NOT_SET -> throw PolicyOperationNotSetException(flagName)

                else -> logger.atWarning().log(
                    "Unknown operation '%s' from invocation policy for flag '%s'",
                    flagPolicy.policy.getOperationCase(), flagName
                )
            }
        }
    }

    private class PolicyOperationNotSetException(flagName: String?) :
        com.google.devtools.common.options.OptionsParsingException(
            java.lang.String.format(
                "Flag policy for flag '%s' does not " + "have an operation",
                flagName
            )
        )

    /** Checks the user's flag values against a filtering function.  */
    private abstract class FilterValueOperation(
        private val policyType: String?,
        loglevel: java.util.logging.Level?,
        conversionContext: Any?
    ) {
        private class AllowValueOperation(loglevel: java.util.logging.Level?, conversionContext: Any?) :
            FilterValueOperation("Allow", loglevel, conversionContext) {
            override fun isFlagValueAllowed(convertedPolicyValues: MutableSet<Any?>, value: Any?): Boolean {
                return convertedPolicyValues.contains(value)
            }
        }

        private class DisallowValueOperation(loglevel: java.util.logging.Level?, conversionContext: Any?) :
            FilterValueOperation("Disallow", loglevel, conversionContext) {
            override fun isFlagValueAllowed(convertedPolicyValues: MutableSet<Any?>, value: Any?): Boolean {
                // In a disallow operation, the values that the flag policy specifies are not allowed,
                // so the value is allowed if the set of policy values does not contain the current
                // flag value.
                return !convertedPolicyValues.contains(value)
            }
        }

        private val loglevel: java.util.logging.Level?
        private val conversionContext: Any?

        init {
            this.loglevel = loglevel
            this.conversionContext = conversionContext
        }

        /**
         * Determines if the given value is allowed.
         * 
         * @param convertedPolicyValues The values given from the FlagPolicy, converted to real objects.
         * @param value The user value of the flag.
         * @return True if the value should be allowed, false if it should not.
         */
        abstract fun isFlagValueAllowed(convertedPolicyValues: MutableSet<Any?>?, value: Any?): Boolean

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        fun apply(
            parser: com.google.devtools.common.options.OptionsParser,
            origin: com.google.devtools.common.options.OptionInstanceOrigin?,
            policyValues: MutableList<String?>,
            newValue: String?,
            useDefault: Boolean,
            valueDescription: com.google.devtools.common.options.OptionValueDescription?,
            optionDescription: com.google.devtools.common.options.OptionsParser.OptionDescription,
            invocationPolicyFlagListBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.common.options.OptionAndRawValue?>
        ) {
            val optionDefinition: com.google.devtools.common.options.OptionDefinition =
                optionDescription.getOptionDefinition()
            // Convert all the allowed values from strings to real objects using the options'
            // converters so that they can be checked for equality using real .equals() instead
            // of string comparison. For example, "--foo=0", "--foo=false", "--nofoo", and "-f-"
            // (if the option has an abbreviation) are all equal for boolean flags. Plus converters
            // can be arbitrarily complex.
            val convertedPolicyValues: MutableSet<Any?> = HashSet<Any?>()
            for (value in policyValues) {
                val convertedValue: Any? = optionDefinition.getConverter().convert(value, conversionContext)
                // Some converters return lists, and if the flag is a repeatable flag, the items in the
                // list from the converter should be added, and not the list itself. Otherwise the items
                // from invocation policy will be compared to lists, which will never work.
                // See OptionsParserImpl.ParsedOptionEntry.addValue.
                if (optionDefinition.allowsMultiple() && convertedValue is MutableList<*>) {
                    convertedPolicyValues.addAll(convertedValue)
                } else {
                    convertedPolicyValues.add(convertedValue)
                }
            }

            // Check that if the default value of the flag is disallowed by the policy, that the policy
            // does not also set use_default. Otherwise the default value would still be set if the
            // user uses a disallowed value. This doesn't apply to repeatable flags since the default
            // value for repeatable flags is always the empty list. It also doesn't apply to flags that
            // are null by default, since these flags' default value is not parsed by the converter, so
            // there is no guarantee that there exists an accepted user-input value that would also set
            // the value to NULL. In these cases, we assume that "unset" is a distinct value that is
            // always allowed.
            if (!optionDescription.getOptionDefinition().allowsMultiple()
                && !optionDescription.getOptionDefinition().isSpecialNullDefault()
            ) {
                val defaultValueAllowed =
                    isFlagValueAllowed(
                        convertedPolicyValues,
                        optionDescription.getOptionDefinition().getDefaultValue(conversionContext)
                    )
                if (!defaultValueAllowed && useDefault) {
                    throw com.google.devtools.common.options.OptionsParsingException(
                        java.lang.String.format(
                            "%sValues policy disallows the default value '%s' for %s but also specifies to "
                                    + "use the default value",
                            policyType,
                            optionDefinition.getDefaultValue(conversionContext),
                            optionDefinition
                        )
                    )
                }
            }

            if (valueDescription == null) {
                // Nothing has set the value yet, so check that the default value from the flag's
                // definition is allowed. The else case below (i.e. valueDescription is not null) checks for
                // the flag allowing multiple values, however, flags that allow multiple values cannot have
                // default values, and their value is always the empty list if they haven't been specified,
                // which is why new_default_value is not a repeated field.
                checkDefaultValue(
                    parser,
                    origin,
                    optionDescription,
                    policyValues,
                    newValue,
                    convertedPolicyValues,
                    invocationPolicyFlagListBuilder
                )
            } else {
                checkUserValue(
                    parser,
                    origin,
                    optionDescription,
                    valueDescription,
                    policyValues,
                    newValue,
                    useDefault,
                    convertedPolicyValues,
                    invocationPolicyFlagListBuilder
                )
            }
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        fun checkDefaultValue(
            parser: com.google.devtools.common.options.OptionsParser,
            origin: com.google.devtools.common.options.OptionInstanceOrigin?,
            optionDescription: com.google.devtools.common.options.OptionsParser.OptionDescription,
            policyValues: MutableList<String?>?,
            newValue: String?,
            convertedPolicyValues: MutableSet<Any?>?,
            invocationPolicyFlagListBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.common.options.OptionAndRawValue?>
        ) {
            val optionDefinition: com.google.devtools.common.options.OptionDefinition =
                optionDescription.getOptionDefinition()
            if (optionDefinition.isSpecialNullDefault()) {
                // Do nothing, the unset value by definition cannot be set. In option filtering operations,
                // the value is being filtered, but the value that is `no value` passes any filter.
                // Otherwise, there is no way to "usedefault" on one of these options that has no value by
                // default.
            } else if (!isFlagValueAllowed(
                    convertedPolicyValues, optionDefinition.getDefaultValue(conversionContext)
                )
            ) {
                if (newValue != null) {
                    // Use the default value from the policy, since the original default is not allowed
                    logger.at(loglevel).log(
                        "Overriding default value '%s' for %s with value '%s' specified by invocation "
                                + "policy. %sed values are: %s",
                        optionDefinition.getDefaultValue(conversionContext),
                        optionDefinition,
                        newValue,
                        policyType,
                        policyValues
                    )
                    parser.clearValue(optionDefinition)
                    parser.setOptionValueAtSpecificPriorityWithoutExpansion(
                        origin, optionDefinition, newValue
                    )
                    invocationPolicyFlagListBuilder.add(
                        com.google.devtools.common.options.OptionAndRawValue.Companion.create(
                            optionDefinition.getOptionName(),
                            newValue
                        )
                    )
                } else {
                    // The operation disallows the default value, but doesn't supply a new value.
                    throw com.google.devtools.common.options.OptionsParsingException(
                        java.lang.String.format(
                            "Default flag value '%s' for %s is not allowed by invocation policy, but "
                                    + "the policy does not provide a new value. %sed values are: %s",
                            optionDescription.getOptionDefinition().getDefaultValue(conversionContext),
                            optionDefinition,
                            policyType,
                            policyValues
                        )
                    )
                }
            }
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        fun checkUserValue(
            parser: com.google.devtools.common.options.OptionsParser,
            origin: com.google.devtools.common.options.OptionInstanceOrigin?,
            optionDescription: com.google.devtools.common.options.OptionsParser.OptionDescription,
            valueDescription: com.google.devtools.common.options.OptionValueDescription,
            policyValues: MutableList<String?>?,
            newValue: String?,
            useDefault: Boolean,
            convertedPolicyValues: MutableSet<Any?>?,
            invocationPolicyFlagListBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.common.options.OptionAndRawValue?>
        ) {
            val option: com.google.devtools.common.options.OptionDefinition = optionDescription.getOptionDefinition()
            if (optionDescription.getOptionDefinition().allowsMultiple()) {
                // allowMultiple requires that the type of the option be List<T>, so cast from Object
                // to List<?>.
                val optionValues = valueDescription.getValue() as MutableList<*>
                for (value in optionValues) {
                    if (!isFlagValueAllowed(convertedPolicyValues, value)) {
                        if (useDefault) {
                            applyUseDefaultOperation(
                                parser,
                                policyType + "Values",
                                option,
                                loglevel,
                                conversionContext,
                                invocationPolicyFlagListBuilder
                            )
                        } else {
                            throw com.google.devtools.common.options.OptionsParsingException(
                                java.lang.String.format(
                                    "Flag value '%s' for %s is not allowed by invocation policy. %sed values "
                                            + "are: %s",
                                    value, option, policyType, policyValues
                                )
                            )
                        }
                    }
                }
            } else {
                if (!isFlagValueAllowed(convertedPolicyValues, valueDescription.getValue())) {
                    if (newValue != null) {
                        logger.at(loglevel).log(
                            "Overriding disallowed value '%s' for %s with value '%s' "
                                    + "specified by invocation policy. %sed values are: %s",
                            valueDescription.getValue(), option, newValue, policyType, policyValues
                        )
                        parser.clearValue(option)
                        parser.setOptionValueAtSpecificPriorityWithoutExpansion(origin, option, newValue)
                        invocationPolicyFlagListBuilder.add(
                            com.google.devtools.common.options.OptionAndRawValue.Companion.create(
                                option.getOptionName(),
                                newValue
                            )
                        )
                    } else if (useDefault) {
                        applyUseDefaultOperation(
                            parser,
                            policyType + "Values",
                            option,
                            loglevel,
                            conversionContext,
                            invocationPolicyFlagListBuilder
                        )
                    } else {
                        throw com.google.devtools.common.options.OptionsParsingException(
                            java.lang.String.format(
                                "Flag value '%s' for %s is not allowed by invocation policy and the "
                                        + "policy does not specify a new value. %sed values are: %s",
                                valueDescription.getValue(), option, policyType, policyValues
                            )
                        )
                    }
                }
            }
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        // LINT.IfChange
        private const val INVOCATION_POLICY_SOURCE = "Invocation policy"
        private fun policyApplies(
            policy: FlagPolicy,
            applicableCommands: com.google.common.collect.ImmutableSet<String?>
        ): Boolean {
            // If the commands list is empty, then the policy applies to all commands.
            if (policy.getCommandsList().isEmpty()) {
                return true
            }

            return !Collections.disjoint(policy.getCommandsList(), applicableCommands)
        }

        /** Returns the expanded and filtered policy that would be enforced for the given command.  */
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        fun getEffectiveInvocationPolicy(
            invocationPolicy: InvocationPolicy?,
            parser: com.google.devtools.common.options.OptionsParser,
            command: String?,
            loglevel: java.util.logging.Level?
        ): InvocationPolicy {
            val effectivePolicies: com.google.common.collect.ImmutableList<FlagPolicyWithContext> =
                getEffectivePolicies(invocationPolicy, parser, command, loglevel)

            val builder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
            for (policyWithContext in effectivePolicies) {
                builder.addFlagPolicies(policyWithContext.policy)
            }
            return builder.build()
        }

        /**
         * Takes the provided policy and processes it to the form that can be used on the user options.
         * 
         * 
         * Expands any policies on expansion flags.
         */
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        private fun getEffectivePolicies(
            invocationPolicy: InvocationPolicy?,
            parser: com.google.devtools.common.options.OptionsParser,
            command: String?,
            loglevel: java.util.logging.Level?
        ): com.google.common.collect.ImmutableList<FlagPolicyWithContext> {
            if (invocationPolicy == null) {
                return com.google.common.collect.ImmutableList.of<FlagPolicyWithContext?>()
            }

            val commandAndParentCommands: com.google.common.collect.ImmutableSet<String?> =
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSet<String?>>(
                    com.google.devtools.common.options.CommandNameCache.CommandNameCacheInstance.Companion.INSTANCE.get(
                        command
                    ),
                    "Command %s does not exist",
                    command
                )

            // Expand all policies to transfer policies on expansion flags to policies on the child flags.
            val expandedPolicies: MutableList<FlagPolicyWithContext> = java.util.ArrayList<FlagPolicyWithContext>()
            var nextPriority: com.google.devtools.common.options.OptionPriority =
                com.google.devtools.common.options.OptionPriority.Companion.lowestOptionPriorityAtCategory(com.google.devtools.common.options.OptionPriority.PriorityCategory.INVOCATION_POLICY)
            for (policy in invocationPolicy.getFlagPoliciesList()) {
                // Explicitly disallow --config in invocation policy.
                if (policy.getFlagName().equals("config")) {
                    throw com.google.devtools.common.options.OptionsParsingException(
                        ("Invocation policy is applied after --config expansion, changing config values now "
                                + "would have no effect and is disallowed to prevent confusion. Please remove the "
                                + "following policy : "
                                +
                                policy)
                    )
                }

                // These policies are high-level, before expansion, and so are not the implicitDependents or
                // expansions of any other flag, other than in an obtuse sense from --invocation_policy.
                val currentPriority: com.google.devtools.common.options.OptionPriority = nextPriority
                val origin: com.google.devtools.common.options.OptionInstanceOrigin =
                    com.google.devtools.common.options.OptionInstanceOrigin(
                        currentPriority,
                        INVOCATION_POLICY_SOURCE,
                        null,
                        null
                    )
                nextPriority =
                    com.google.devtools.common.options.OptionPriority.Companion.nextOptionPriority(currentPriority)
                if (!policyApplies(policy, commandAndParentCommands)) {
                    // Only keep and expand policies that are applicable to the current command.
                    continue
                }

                val optionDescription: com.google.devtools.common.options.OptionsParser.OptionDescription? =
                    parser.getOptionDescription(policy.getFlagName())
                if (optionDescription == null) {
                    // InvocationPolicy ignores policy on non-existing flags by design, for version
                    // compatibility.
                    logger.at(loglevel).log(
                        "Flag '%s' specified by invocation policy does not exist, and will be ignored",
                        policy.getFlagName()
                    )
                    continue
                }
                val policyWithContext =
                    FlagPolicyWithContext(policy, optionDescription, origin)
                val policies: MutableList<FlagPolicyWithContext?> = expandPolicy(policyWithContext, parser, loglevel)
                expandedPolicies.addAll(policies)
            }

            // Only keep that last policy for each flag.
            val effectivePolicy: com.google.common.collect.ImmutableMap.Builder<String?, FlagPolicyWithContext?> =
                com.google.common.collect.ImmutableMap.builder<String?, FlagPolicyWithContext?>()
            for (expandedPolicy in expandedPolicies) {
                val flagName: String? = expandedPolicy.policy.getFlagName()
                effectivePolicy.put(flagName, expandedPolicy)
            }

            return effectivePolicy.buildKeepingLast().values().asList()
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        private fun throwAllowValuesOnExpansionFlagException(flagName: String?) {
            throw com.google.devtools.common.options.OptionsParsingException(
                java.lang.String.format("Allow_Values on expansion flags like %s is not allowed.", flagName)
            )
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        private fun throwDisallowValuesOnExpansionFlagException(flagName: String?) {
            throw com.google.devtools.common.options.OptionsParsingException(
                java.lang.String.format("Disallow_Values on expansion flags like %s is not allowed.", flagName)
            )
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        private fun throwUndefinedBehaviorException(policy: FlagPolicy?): com.google.devtools.common.options.OptionsParsingException? {
            throw com.google.devtools.common.options.OptionsParsingException(
                java.lang.String.format(
                    "SetValue operation from invocation policy for has an undefined behavior: %s",
                    policy
                )
            )
        }

        /**
         * Expand a single policy. If the policy is not about an expansion flag, this will simply return a
         * list with a single element, oneself. If the policy is for an expansion flag, the policy will
         * get split into multiple policies applying to each flag the original flag expands to.
         * 
         * 
         * None of the flagPolicies returned should be on expansion flags.
         */
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        private fun expandPolicy(
            originalPolicy: FlagPolicyWithContext,
            parser: com.google.devtools.common.options.OptionsParser,
            loglevel: java.util.logging.Level?
        ): com.google.common.collect.ImmutableList<FlagPolicyWithContext?> {
            val expandedPolicies: com.google.common.collect.ImmutableList.Builder<FlagPolicyWithContext?> =
                com.google.common.collect.ImmutableList.builder<FlagPolicyWithContext?>()

            val isExpansion: Boolean = originalPolicy.description.isExpansion()
            val subflags: com.google.common.collect.ImmutableList<com.google.devtools.common.options.ParsedOptionDescription> =
                parser.getExpansionValueDescriptions(
                    originalPolicy.description.getOptionDefinition(), originalPolicy.origin
                )

            // If we have nothing to expand to, no need to do any further work.
            if (subflags.isEmpty()) {
                return com.google.common.collect.ImmutableList.of<FlagPolicyWithContext?>(originalPolicy)
            }

            // Log the expansion. This is only really useful for understanding the invocation policy itself.
            logger.at(loglevel).log(
                "Expanding %s on option %s to its %s: %s.",
                originalPolicy.policy.getOperationCase(),
                originalPolicy.policy.getFlagName(),
                if (isExpansion) "expansions" else "implied flags",
                LazyArgs.lazy<String?>(
                    LazyArg {
                        subflags.stream()
                            .map<String?>(java.util.function.Function { f: com.google.devtools.common.options.ParsedOptionDescription? ->
                                "--" + f.getOptionDefinition().getOptionName()
                            })
                            .collect(Collectors.joining("; "))
                    })
            )

            // Repeated flags are special, and could set multiple times in an expansion, with the user
            // expecting both values to be valid. Collect these separately.
            val repeatableSubflagsInSetValues: com.google.common.collect.Multimap<com.google.devtools.common.options.OptionsParser.OptionDescription, com.google.devtools.common.options.ParsedOptionDescription> =
                com.google.common.collect.ArrayListMultimap.create<com.google.devtools.common.options.OptionsParser.OptionDescription?, com.google.devtools.common.options.ParsedOptionDescription?>()

            // Create a flag policy for the child that looks like the parent's policy "transferred" to its
            // child. Note that this only makes sense for SetValue, when setting an expansion flag, or
            // UseDefault, when preventing it from being set.
            for (currentSubflag in subflags) {
                val subflagOptionDescription: com.google.devtools.common.options.OptionsParser.OptionDescription =
                    parser.getOptionDescription(currentSubflag.getOptionDefinition().getOptionName())

                if (currentSubflag.getOptionDefinition().allowsMultiple()
                    && originalPolicy.policy.getOperationCase().equals(OperationCase.SET_VALUE)
                ) {
                    repeatableSubflagsInSetValues.put(subflagOptionDescription, currentSubflag)
                } else {
                    val subflagAsPolicy =
                        getSingleValueSubflagAsPolicy(
                            subflagOptionDescription, currentSubflag, originalPolicy, isExpansion
                        )
                    // In case any of the expanded flags are themselves expansions, recurse.
                    expandedPolicies.addAll(Companion.expandPolicy(subflagAsPolicy!!, parser, loglevel))
                }
            }

            // If there are any repeatable flag SetValues, deal with them together now.
            // Note that expansion flags have no value, and so cannot have multiple values either.
            // Skipping the recursion above is fine.
            for (repeatableFlag in repeatableSubflagsInSetValues.keySet()) {
                val numValues: Int = repeatableSubflagsInSetValues.get(repeatableFlag).size()
                val newValues: java.util.ArrayList<String?> = java.util.ArrayList<String?>(numValues)
                val origins: java.util.ArrayList<com.google.devtools.common.options.OptionInstanceOrigin> =
                    java.util.ArrayList<com.google.devtools.common.options.OptionInstanceOrigin>(numValues)
                for (setValue in repeatableSubflagsInSetValues.get(repeatableFlag)) {
                    newValues.add(setValue.getUnconvertedValue())
                    origins.add(setValue.getOrigin())
                }
                // These options come from expanding a single policy, so they have effectively the same
                // priority. They could have come from different expansions or implicit requirements in the
                // recursive resolving of the option list, so just pick the first one. Do collapse the source
                // strings though, in case there are different sources.
                val arbitraryFirstOptionOrigin: com.google.devtools.common.options.OptionInstanceOrigin = origins.get(0)
                val originOfSubflags: com.google.devtools.common.options.OptionInstanceOrigin =
                    com.google.devtools.common.options.OptionInstanceOrigin(
                        arbitraryFirstOptionOrigin.getPriority(),
                        origins.stream()
                            .map<String?>(java.util.function.Function { obj: com.google.devtools.common.options.OptionInstanceOrigin? -> obj.getSource() })
                            .distinct()
                            .collect(Collectors.joining(", ")),
                        arbitraryFirstOptionOrigin.getImplicitDependent(),
                        arbitraryFirstOptionOrigin.getExpandedFrom()
                    )
                expandedPolicies.add(
                    getSetValueSubflagAsPolicy(repeatableFlag, newValues, originOfSubflags, originalPolicy)
                )
            }

            // Don't add the original policy if it was an expansion flag, which have no value, but do add
            // it if there was either no expansion or if it was a valued flag with implicit requirements.
            if (!isExpansion) {
                expandedPolicies.add(originalPolicy)
            }

            return expandedPolicies.build()
        }

        /**
         * Expand a SetValue flag policy on a repeatable flag. SetValue operations are the only flag
         * policies that set the flag, and so interact with repeatable flags, flags that can be set
         * multiple times, in subtle ways.
         * 
         * @param subflagDesc, the description of the flag the SetValue'd expansion flag expands to.
         * @param subflagValue, the values that the SetValue'd expansion flag expands to for this flag.
         * @param originalPolicy, the original policy on the expansion flag.
         * @return the flag policy for the subflag given, this will be part of the expanded form of the
         * SetValue policy on the original flag.
         */
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        private fun getSetValueSubflagAsPolicy(
            subflagDesc: com.google.devtools.common.options.OptionsParser.OptionDescription,
            subflagValue: MutableList<String?>,
            subflagOrigin: com.google.devtools.common.options.OptionInstanceOrigin?,
            originalPolicy: FlagPolicyWithContext
        ): FlagPolicyWithContext {
            // Some checks.
            val subflag: com.google.devtools.common.options.OptionDefinition = subflagDesc.getOptionDefinition()
            com.google.common.base.Verify.verify(
                originalPolicy.policy.getOperationCase().equals(OperationCase.SET_VALUE)
            )
            if (!subflag.allowsMultiple()) {
                com.google.common.base.Verify.verify(subflagValue.size() <= 1)
            }

            // Flag value from the expansion, overridability from the original policy, unless the flag is
            // repeatable, in which case we care about appendability, not overridability.
            val setValueExpansion: SetValue.Builder = SetValue.newBuilder().addAllFlagValue(subflagValue)

            when (originalPolicy.policy.getSetValue().getBehavior()) {
                UNDEFINED -> throw throwUndefinedBehaviorException(originalPolicy.policy)
                FINAL_VALUE_IGNORE_OVERRIDES, APPEND -> setValueExpansion.setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
                ALLOW_OVERRIDES -> setValueExpansion.setBehavior(
                    if (subflag.allowsMultiple()) Behavior.APPEND else Behavior.ALLOW_OVERRIDES
                )

                FINAL_VALUE_THROW_ON_OVERRIDE -> setValueExpansion.setBehavior(Behavior.FINAL_VALUE_THROW_ON_OVERRIDE)
            }

            // Commands from the original policy, flag name of the expansion
            return FlagPolicyWithContext(
                FlagPolicy.newBuilder()
                    .addAllCommands(originalPolicy.policy.getCommandsList())
                    .setFlagName(subflag.getOptionName())
                    .setSetValue(setValueExpansion)
                    .build(),
                subflagDesc,
                subflagOrigin
            )
        }

        /**
         * For an expansion flag in an invocation policy, each flag it expands to must be given a
         * corresponding policy.
         */
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        private fun getSingleValueSubflagAsPolicy(
            subflagContext: com.google.devtools.common.options.OptionsParser.OptionDescription,
            currentSubflag: com.google.devtools.common.options.ParsedOptionDescription,
            originalPolicy: FlagPolicyWithContext,
            isExpansion: Boolean
        ): FlagPolicyWithContext? {
            var subflagAsPolicy: FlagPolicyWithContext? = null
            when (originalPolicy.policy.getOperationCase()) {
                SET_VALUE -> {
                    if (currentSubflag.getOptionDefinition().allowsMultiple()) {
                        throw java.lang.AssertionError(
                            "SetValue subflags with allowMultiple should have been dealt with separately and "
                                    + "accumulated into a single FlagPolicy."
                        )
                    }
                    // Accept null originalValueStrings, they are expected when the subflag is also an expansion
                    // flag.
                    val subflagValue: MutableList<String?>?
                    if (currentSubflag.getUnconvertedValue() == null) {
                        subflagValue = com.google.common.collect.ImmutableList.of<String?>()
                    } else {
                        subflagValue =
                            com.google.common.collect.ImmutableList.of<String?>(currentSubflag.getUnconvertedValue())
                    }
                    subflagAsPolicy =
                        Companion.getSetValueSubflagAsPolicy(
                            subflagContext, subflagValue!!, currentSubflag.getOrigin(), originalPolicy
                        )
                }

                USE_DEFAULT ->         // Commands from the original policy, flag name of the expansion
                    subflagAsPolicy =
                        FlagPolicyWithContext(
                            FlagPolicy.newBuilder()
                                .addAllCommands(originalPolicy.policy.getCommandsList())
                                .setFlagName(currentSubflag.getOptionDefinition().getOptionName())
                                .setUseDefault(UseDefault.getDefaultInstance())
                                .build(),
                            subflagContext,
                            currentSubflag.getOrigin()
                        )

                ALLOW_VALUES -> if (isExpansion) {
                    throwAllowValuesOnExpansionFlagException(originalPolicy.policy.getFlagName())
                }

                DISALLOW_VALUES -> if (isExpansion) {
                    throwDisallowValuesOnExpansionFlagException(originalPolicy.policy.getFlagName())
                }

                OPERATION_NOT_SET -> throw PolicyOperationNotSetException(originalPolicy.policy.getFlagName())

                else -> return null
            }
            return subflagAsPolicy
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        private fun applySetValueOperation(
            parser: com.google.devtools.common.options.OptionsParser,
            flagPolicy: FlagPolicyWithContext,
            valueDescription: com.google.devtools.common.options.OptionValueDescription?,
            loglevel: java.util.logging.Level?,
            conversionContext: Any?,
            invocationPolicyFlagListBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.common.options.OptionAndRawValue?>
        ) {
            val setValue: SetValue = flagPolicy.policy.getSetValue()
            val optionDefinition: com.google.devtools.common.options.OptionDefinition =
                flagPolicy.description.getOptionDefinition()

            // SetValue.flag_value must have at least 1 value.
            if (setValue.getFlagValueCount() === 0) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format(
                        "SetValue operation from invocation policy for %s does not have a value",
                        optionDefinition
                    )
                )
            }

            // Flag must allow multiple values if multiple values are specified by the policy.
            if (setValue.getFlagValueCount() > 1
                && !flagPolicy.description.getOptionDefinition().allowsMultiple()
            ) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format(
                        "SetValue operation from invocation policy sets multiple values for %s which "
                                + "does not allow multiple values",
                        optionDefinition
                    )
                )
            }

            when (setValue.getBehavior()) {
                UNDEFINED -> throw throwUndefinedBehaviorException(flagPolicy.policy)
                ALLOW_OVERRIDES -> if (valueDescription != null) {
                    // The user set the value for the flag but the flag policy is overridable, so keep the
                    // user's value.
                    logger.at(loglevel).log(
                        "Keeping value '%s' from source '%s' for %s because the invocation policy specifying "
                                + "the value(s) '%s' is overridable",
                        valueDescription.getValue(),
                        valueDescription.getSourceString(),
                        optionDefinition,
                        setValue.getFlagValueList()
                    )
                    // Nothing to do -- the value already has an override.
                    return
                }

                FINAL_VALUE_IGNORE_OVERRIDES ->         // Clear the value in case the flag is a repeated flag so that values don't accumulate.
                    parser.clearValue(flagPolicy.description.getOptionDefinition())

                APPEND -> {}
                FINAL_VALUE_THROW_ON_OVERRIDE -> if (valueDescription != null) {
                    throw com.google.devtools.common.options.OptionsParsingException(
                        java.lang.String.format(
                            "User set a value for %s which is not permitted by the invocation policy. This"
                                    + " flag value will always be overridden to %s. %s",
                            optionDefinition,
                            flagPolicy.policy.getSetValue().getFlagValueList(),
                            flagPolicy.policy.getCustomErrorMessage()
                        )
                    )
                }
            }

            // Set all the flag values from the policy.
            for (flagValue in setValue.getFlagValueList()) {
                if (valueDescription == null) {
                    logger.at(loglevel).log(
                        "Setting value for %s from invocation policy to '%s', overriding the default value "
                                + "'%s'",
                        optionDefinition, flagValue, optionDefinition.getDefaultValue(conversionContext)
                    )
                } else {
                    logger.at(loglevel).log(
                        "Setting value for %s from invocation policy to '%s', overriding value '%s' from '%s'",
                        optionDefinition,
                        flagValue,
                        valueDescription.getValue(),
                        valueDescription.getSourceString()
                    )
                }

                invocationPolicyFlagListBuilder.add(
                    com.google.devtools.common.options.OptionAndRawValue.Companion.create(
                        optionDefinition.getOptionName(),
                        flagValue
                    )
                )

                parser.setOptionValueAtSpecificPriorityWithoutExpansion(
                    flagPolicy.origin, optionDefinition, flagValue
                )
            }
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        private fun applyUseDefaultOperation(
            parser: com.google.devtools.common.options.OptionsParser,
            policyType: String?,
            option: com.google.devtools.common.options.OptionDefinition?,
            loglevel: java.util.logging.Level?,
            conversionContext: Any?,
            invocationPolicyFlagListBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.common.options.OptionAndRawValue?>
        ) {
            val clearedValueDescription: com.google.devtools.common.options.OptionValueDescription? =
                parser.clearValue(option)
            if (clearedValueDescription != null) {
                // Log the removed value.
                val clearedFlagName: String? = clearedValueDescription.getOptionDefinition().getOptionName()
                val clearedFlagDefaultValue: Any? =
                    clearedValueDescription.getOptionDefinition().getDefaultValue(conversionContext)
                logger.at(loglevel).log(
                    "Using default value '%s' for flag '%s' as specified by %s invocation policy, "
                            + "overriding original value '%s' from '%s'",
                    clearedFlagDefaultValue,
                    clearedFlagName,
                    policyType,
                    clearedValueDescription.getValue(),
                    clearedValueDescription.getSourceString()
                )
                invocationPolicyFlagListBuilder.add(
                    com.google.devtools.common.options.OptionAndRawValue.Companion.create(
                        clearedFlagName,
                        if (clearedFlagDefaultValue != null) clearedFlagDefaultValue.toString() else ""
                    )
                )
            }
        }
    }
}
