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

import com.google.devtools.build.lib.actions.ExecutionRequirements

/**
 * Utility functions over Targets that don't really belong in the base [ ] interface.
 */
object TargetUtils {
    // *_test / test_suite attribute that used to specify constraint keywords.
    private const val CONSTRAINTS_ATTR = "tags"

    // We don't want to pollute the execution info with random things, and we also need to reserve
    // some internal tags that we don't allow to be set on targets. We also don't want to
    // exhaustively enumerate all the legal values here. Right now, only a ~small set of tags is
    // recognized by Bazel.
    private fun legalExecInfoKeys(tag: String): Boolean {
        return tag.startsWith("block-")
                || tag.startsWith("requires-")
                || tag.startsWith("no-")
                || tag.startsWith("supports-")
                || tag.startsWith("disable-")
                || tag.startsWith("cpu:")
                || tag == ExecutionRequirements.LOCAL
                || tag == ExecutionRequirements.WORKER_KEY_MNEMONIC
                || tag.startsWith("resources:")
    }

    @kotlin.jvm.JvmStatic
    fun isTestRuleName(name: String): Boolean {
        return name.endsWith("_test")
    }

    fun isTestSuiteRuleName(name: String): Boolean {
        return name == "test_suite"
    }

    fun isExecutableNonTestRule(target: com.google.devtools.build.lib.packages.Target?): Boolean {
        return target is com.google.devtools.build.lib.packages.Rule && target.isExecutable() && !isTestRule(target)
    }

    /**
     * Returns true iff `target` is a `*_test` rule; excludes `test_suite`.
     */
    fun isTestRule(target: com.google.devtools.build.lib.packages.Target?): Boolean {
        return (target is com.google.devtools.build.lib.packages.Rule) && isTestRuleName((target as com.google.devtools.build.lib.packages.Rule).getRuleClass())
    }

    /**
     * Returns true iff `target` is a `test_suite` rule.
     */
    fun isTestSuiteRule(target: com.google.devtools.build.lib.packages.Target?): Boolean {
        return target is com.google.devtools.build.lib.packages.Rule && isTestSuiteRuleName((target as com.google.devtools.build.lib.packages.Rule).getRuleClass())
    }

    /**
     * Returns true iff `target` is a `*_test` or `test_suite`.
     */
    fun isTestOrTestSuiteRule(target: com.google.devtools.build.lib.packages.Target?): Boolean {
        return isTestRule(target) || isTestSuiteRule(target)
    }

    /**
     * Returns true if `target` has "manual" in the tags attribute and thus should be ignored by
     * command-line wildcards or by test_suite $implicit_tests attribute.
     */
    fun hasManualTag(target: com.google.devtools.build.lib.packages.Target?): Boolean {
        return (target is com.google.devtools.build.lib.packages.Rule) && hasConstraint(
            target as com.google.devtools.build.lib.packages.Rule,
            "manual"
        )
    }

    /**
     * Returns true if test marked as "exclusive" by the appropriate keyword
     * in the tags attribute.
     * 
     * Method assumes that passed target is a test rule, so usually it should be
     * used only after isTestRule() or isTestOrTestSuiteRule(). Behavior is
     * undefined otherwise.
     */
    fun isExclusiveTestRule(rule: com.google.devtools.build.lib.packages.Rule?): Boolean {
        return hasConstraint(rule, "exclusive")
    }

    /**
     * Returns true if test marked as "exclusive-if-local" by the appropriate keyword in the tags
     * attribute.
     * 
     * 
     * Method assumes that passed target is a test rule, so usually it should be used only after
     * isTestRule() or isTestOrTestSuiteRule(). Behavior is undefined otherwise.
     */
    fun isExclusiveIfLocalTestRule(rule: com.google.devtools.build.lib.packages.Rule?): Boolean {
        return hasConstraint(rule, "exclusive-if-local")
    }

    /**
     * Returns true if test marked as "local" by the appropriate keyword
     * in the tags attribute.
     * 
     * Method assumes that passed target is a test rule, so usually it should be
     * used only after isTestRule() or isTestOrTestSuiteRule(). Behavior is
     * undefined otherwise.
     */
    fun isLocalTestRule(rule: com.google.devtools.build.lib.packages.Rule?): Boolean {
        return hasConstraint(rule, "local")
                || NonconfigurableAttributeMapper.Companion.of(rule)
            .get<Boolean?>("local", com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN)
    }

    /**
     * Returns true if test marked as "external" by the appropriate keyword
     * in the tags attribute.
     * 
     * Method assumes that passed target is a test rule, so usually it should be
     * used only after isTestRule() or isTestOrTestSuiteRule(). Behavior is
     * undefined otherwise.
     */
    fun isExternalTestRule(rule: com.google.devtools.build.lib.packages.Rule?): Boolean {
        return hasConstraint(rule, "external")
    }

    /**
     * Returns true if test marked as "no-testloasd" by the appropriate keyword in the tags attribute.
     * 
     * 
     * Method assumes that passed target is a test rule, so usually it should be used only after
     * isTestRule() or isTestOrTestSuiteRule(). Behavior is undefined otherwise.
     */
    fun isNoTestloasdTestRule(rule: com.google.devtools.build.lib.packages.Rule?): Boolean {
        return hasConstraint(rule, "no-testloasd")
    }

    fun getStringListAttr(
        target: com.google.devtools.build.lib.packages.Target?,
        attrName: String?
    ): MutableList<String?>? {
        com.google.common.base.Preconditions.checkArgument(target is com.google.devtools.build.lib.packages.Rule)
        return NonconfigurableAttributeMapper.Companion.of(target as com.google.devtools.build.lib.packages.Rule)
            .get<MutableList<String?>?>(attrName, com.google.devtools.build.lib.packages.Types.STRING_LIST)
    }

    fun getStringAttr(target: com.google.devtools.build.lib.packages.Target?, attrName: String?): String? {
        com.google.common.base.Preconditions.checkArgument(target is com.google.devtools.build.lib.packages.Rule)
        return NonconfigurableAttributeMapper.Companion.of(target as com.google.devtools.build.lib.packages.Rule)
            .get<String?>(attrName, com.google.devtools.build.lib.packages.Type.Companion.STRING)
    }

    fun getAttrAsString(target: com.google.devtools.build.lib.packages.Target?, attrName: String?): Iterable<String?> {
        com.google.common.base.Preconditions.checkArgument(target is com.google.devtools.build.lib.packages.Rule)
        val values: MutableList<String?> = java.util.ArrayList<String?>() // May hold null values.
        val attribute: com.google.devtools.build.lib.packages.Attribute? =
            (target as com.google.devtools.build.lib.packages.Rule).getAttributeDefinition(attrName)
        if (attribute != null) {
            val attributeType: com.google.devtools.build.lib.packages.Type<*>? = attribute.getType()
            for (attrValue in AggregatingAttributeMapper.Companion.of(target as com.google.devtools.build.lib.packages.Rule)
                .visitAttribute(attribute.getName(), attributeType)) {
                values.add(convertAttributeValue(attributeType, attrValue))
            }
        }
        return values
    }

    fun convertAttributeValue(
        attributeType: com.google.devtools.build.lib.packages.Type<*>?,
        attrValue: Any?
    ): String? {
        // Ugly hack to maintain backward 'attr' query compatibility for BOOLEAN and TRISTATE
        // attributes. These are internally stored as actual Boolean or TriState objects but were
        // historically queried as integers. To maintain compatibility, we inspect their actual
        // value and return the integer equivalent represented as a String. This code is the
        // opposite of the code in BooleanType and TriStateType respectively.
        if (attributeType === com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN) {
            return if (com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN.cast(attrValue)) "1" else "0"
        } else if (attributeType === BuildType.TRISTATE) {
            return when (BuildType.TRISTATE.cast(attrValue)) {
                com.google.devtools.build.lib.packages.TriState.AUTO -> "-1"
                com.google.devtools.build.lib.packages.TriState.NO -> "0"
                com.google.devtools.build.lib.packages.TriState.YES -> "1"
            }
        } else {
            return if (attrValue == null) null else attrValue.toString()
        }
    }

    /**
     * If the given target is a rule, returns its `deprecation`` value, or null if unset.
    ` */
    fun getDeprecation(target: com.google.devtools.build.lib.packages.Target?): String? {
        if (target !is com.google.devtools.build.lib.packages.Rule) {
            return null
        }
        val rule: com.google.devtools.build.lib.packages.Rule = target as com.google.devtools.build.lib.packages.Rule
        return if (rule.isAttrDefined("deprecation", com.google.devtools.build.lib.packages.Type.Companion.STRING))
            NonconfigurableAttributeMapper.Companion.of(rule)
                .get<String?>("deprecation", com.google.devtools.build.lib.packages.Type.Companion.STRING)
        else
            null
    }

    /**
     * Checks whether specified constraint keyword is present in the
     * tags attribute of the test or test suite rule.
     * 
     * Method assumes that provided rule is a test or a test suite. Behavior is
     * undefined otherwise.
     */
    private fun hasConstraint(rule: com.google.devtools.build.lib.packages.Rule?, keyword: String?): Boolean {
        return NonconfigurableAttributeMapper.Companion.of(rule)
            .get<MutableList<String?>?>(CONSTRAINTS_ATTR, com.google.devtools.build.lib.packages.Types.STRING_LIST)
            .contains(keyword)
    }

    /**
     * Returns the execution info from the tags declared on the target. These include only some tags
     * [.legalExecInfoKeys] as keys with empty values.
     */
    fun getExecutionInfo(rule: com.google.devtools.build.lib.packages.Rule?): MutableMap<String?, String?> {
        // tags may contain duplicate values.
        val map: MutableMap<String?, String?> = HashMap<String?, String?>()
        for (tag in NonconfigurableAttributeMapper.Companion.of(rule)
            .get<MutableList<String>?>(CONSTRAINTS_ATTR, com.google.devtools.build.lib.packages.Types.STRING_LIST)) {
            if (legalExecInfoKeys(tag)) {
                map.put(tag, "")
            }
        }
        return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(map)
    }

    /**
     * Returns the execution info from the tags declared on the target. These include only some tags
     * [.legalExecInfoKeys] as keys with empty values.
     * 
     * @param rule a rule instance to get tags from
     * @param allowTagsPropagation if set to true, tags will be propagated from a target to the
     * actions' execution requirements, for more details {@see
     * *     BuildLanguageOptions#experimentalAllowTagsPropagation}
     */
    fun getExecutionInfo(
        rule: com.google.devtools.build.lib.packages.Rule?, allowTagsPropagation: Boolean
    ): com.google.common.collect.ImmutableMap<String?, String?> {
        if (allowTagsPropagation) {
            return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(getExecutionInfo(rule))
        } else {
            return com.google.common.collect.ImmutableMap.of<String?, String?>()
        }
    }

    /**
     * Returns the execution info, obtained from the rule's tags and the execution requirements
     * provided. Only supported tags are included into the execution info, see [ ][.legalExecInfoKeys].
     * 
     * @param executionRequirementsUnchecked execution_requirements of a rule, expected to be of a
     * `Dict<String, String>` type, null or Starlark None.
     * @param rule a rule instance to get tags from
     * @param allowTagsPropagation if set to true, tags will be propagated from a target to the
     * actions' execution requirements, for more details {@see
     * *     StarlarkSematicOptions#experimentalAllowTagsPropagation}
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getFilteredExecutionInfo(
        executionRequirementsUnchecked: Any?,
        rule: com.google.devtools.build.lib.packages.Rule?,
        allowTagsPropagation: Boolean
    ): com.google.common.collect.ImmutableSortedMap<String?, String?> {
        var executionInfo =
            if (executionRequirementsUnchecked == null)
                com.google.common.collect.ImmutableMap.of<String?, String?>()
            else
                filter(
                    net.starlark.java.eval.Dict.noneableCast<String?, String?>(
                        executionRequirementsUnchecked,
                        String::class.java,
                        String::class.java,
                        "execution_requirements"
                    )
                )

        if (allowTagsPropagation) {
            executionInfo = HashMap<String?, String?>(executionInfo) // Make mutable.
            val checkedTags = getExecutionInfo(rule)
            // merging filtered tags to the execution info map avoiding duplicates
            checkedTags.forEach(java.util.function.BiConsumer { key: String?, value: String? ->
                executionInfo.putIfAbsent(
                    key,
                    value
                )
            })
        }

        return com.google.common.collect.ImmutableSortedMap.copyOf<String?, String?>(executionInfo)
    }

    /**
     * Returns the execution info. These include execution requirement tags ('block-*', 'requires-*',
     * 'no-*', 'supports-*', 'disable-*', 'local', and 'cpu:*') as keys with empty values.
     */
    private fun filter(executionInfo: MutableMap<String?, String?>): MutableMap<String?, String?> {
        return com.google.common.collect.Maps.filterKeys<String?, String?>(
            executionInfo,
            com.google.common.base.Predicate { obj: String? -> TargetUtils.legalExecInfoKeys() })
    }

    /**
     * Returns the language part of the rule name (e.g. "foo" for foo_test or foo_binary).
     * 
     * 
     * In practice this is the part before the "_", if any, otherwise the entire rule class name.
     * 
     * 
     * Precondition: isTestRule(target) || isRunnableNonTestRule(target).
     */
    fun getRuleLanguage(target: com.google.devtools.build.lib.packages.Target): String {
        return TargetUtils.getRuleLanguage((target as com.google.devtools.build.lib.packages.Rule).getRuleClass())
    }

    /**
     * Returns the language part of the rule name (e.g. "foo" for foo_test or foo_binary).
     * 
     * 
     * In practice this is the part before the "_", if any, otherwise the entire rule class name.
     */
    @kotlin.jvm.JvmStatic
    fun getRuleLanguage(ruleClass: String): String {
        val index: Int = ruleClass.lastIndexOf('_'.code)
        // Chop off "_binary" or "_test".
        return if (index != -1) ruleClass.substring(0, index) else ruleClass
    }

    private fun isExplicitDependency(rule: com.google.devtools.build.lib.packages.Rule, label: Label): Boolean {
        if (com.google.common.collect.Iterables.contains(rule.getVisibilityDependencyLabels(), label)) {
            return true
        }

        val mapper: AggregatingAttributeMapper = AggregatingAttributeMapper.Companion.of(rule)
        try {
            mapper.visitLabels(
                DependencyFilter.Companion.NO_IMPLICIT_DEPS,
                com.google.devtools.build.lib.packages.Type.LabelVisitor { depLabel: Label?, attribute: com.google.devtools.build.lib.packages.Attribute? ->
                    if (label.equals(depLabel)) {
                        throw StopIteration.INSTANCE
                    }
                })
        } catch (e: StopIteration) {
            return true
        }
        return false
    }

    /**
     * Returns a predicate to be used for test tag filtering, i.e., that only accepts tests that match
     * all of the required tags and none of the excluded tags.
     */
    fun tagFilter(tagFilterList: MutableList<String?>): com.google.common.base.Predicate<com.google.devtools.build.lib.packages.Target?> {
        val tagLists: com.google.devtools.build.lib.util.Pair<MutableCollection<String?>?, MutableCollection<String?>?> =
            TestTargetUtils.sortTagsBySense(tagFilterList)
        val requiredTags: MutableCollection<String?>? = tagLists.first
        val excludedTags: MutableCollection<String?>? = tagLists.second
        return com.google.common.base.Predicate { input: com.google.devtools.build.lib.packages.Target? ->
            if (requiredTags!!.isEmpty() && excludedTags!!.isEmpty()) {
                return@Predicate true
            }
            if (input !is com.google.devtools.build.lib.packages.Rule) {
                return@Predicate requiredTags.isEmpty()
            }
            TestTargetUtils.testMatchesFilters(
                (input as com.google.devtools.build.lib.packages.Rule).getRuleTags(), requiredTags, excludedTags, false
            )
        }
    }

    /** Return [Location] for [Target] target, if it should not be null.  */
    fun getLocationMaybe(target: com.google.devtools.build.lib.packages.Target?): net.starlark.java.syntax.Location? {
        return if ((target is com.google.devtools.build.lib.packages.Rule) || (target is InputFile)) target.getLocation() else null
    }

    /**
     * Return nicely formatted error message that [Label] label that was pointed to by [ ] target did not exist, due to [NoSuchThingException] e.
     */
    fun formatMissingEdge(
        target: com.google.devtools.build.lib.packages.Target?,
        label: Label,
        e: NoSuchThingException,
        attr: com.google.devtools.build.lib.packages.Attribute?
    ): String? {
        // instanceof returns false if target is null (which is exploited here)
        if (target is com.google.devtools.build.lib.packages.Rule) {
            if (isExplicitDependency(target, label)) {
                return java.lang.String.format("%s and referenced by '%s'", e.getMessage(), target.getLabel())
            } else {
                var additionalInfo: String? = ""
                if (attr != null && !com.google.common.base.Strings.isNullOrEmpty(attr.getDoc())) {
                    additionalInfo =
                        java.lang.String.format(
                            "\nDocumentation for implicit attribute %s of rules of type %s:\n%s",
                            attr.getPublicName(), target.getRuleClass(), attr.getDoc()
                        )
                }
                // N.B. If you see this error message in one of our integration tests during development of
                // a change that adds a new implicit dependency when running Blaze, maybe you forgot to add
                // a new mock target to the integration test's setup.
                return java.lang.String.format(
                    "every rule of type %s implicitly depends upon the target '%s', but "
                            + "this target could not be found because of: %s%s",
                    target.getRuleClass(), label, e.getMessage(), additionalInfo
                )
            }
        } else if (target is InputFile) {
            return (e.getMessage() + " (this is usually caused by a missing package group in the"
                    + " package-level visibility declaration)")
        } else {
            if (target != null) {
                return java.lang.String.format(
                    "in target '%s', no such label '%s': %s", target.getLabel(), label,
                    e.getMessage()
                )
            }
            return e.getMessage()
        }
    }

    fun formatMissingEdge(
        target: com.google.devtools.build.lib.packages.Target?, label: Label, e: NoSuchThingException
    ): String? {
        return formatMissingEdge(target, label, e, null)
    }

    private object StopIteration : java.lang.RuntimeException() {
        private val INSTANCE: StopIteration = StopIteration()
    }
}
