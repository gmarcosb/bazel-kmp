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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.packages.Attribute

/**
 * A helper class to generate valid rules with filled attributes if necessary.
 */
class BuildRuleWithDefaultsBuilder : BuildRuleBuilder {
    private val generateFiles: MutableSet<String?>
    private val generateRules: MutableMap<String?, BuildRuleBuilder?>

    constructor(ruleClass: String?, ruleName: String?) : super(ruleClass, ruleName) {
        this.generateFiles = HashSet<String?>()
        this.generateRules = HashMap<String?, BuildRuleBuilder?>()
    }

    private constructor(
        ruleClass: String?, ruleName: String?,
        ruleClassMap: MutableMap<String?, RuleClass?>, generateFiles: MutableSet<String?>,
        generateRules: MutableMap<String?, BuildRuleBuilder?>
    ) : super(ruleClass, ruleName, ruleClassMap) {
        this.generateFiles = generateFiles
        this.generateRules = generateRules
    }

    /**
     * Creates a dummy file with the given extension in the given package and returns a valid Blaze
     * label referring to the file. Note, the created label depends on the package of the rule.
     */
    private fun getDummyFileLabel(
        rulePkg: String, filePkg: String?, extension: String,
        attrType: Type<*>
    ): String {
        val isOutput = attrType.getLabelClass() === LabelClass.OUTPUT
        val fileName = (if (isOutput) "dummy_output" else "dummy_input") + extension
        generateFiles.add(filePkg + "/" + fileName)
        if (rulePkg == filePkg) {
            return ":" + fileName
        } else {
            return filePkg + ":" + fileName
        }
    }

    private fun getDummyRuleLabel(rulePkg: String, referencedRuleClass: RuleClass): String {
        val referencedRuleName = ruleName + "_ref_" + referencedRuleClass.getName()
            .replace("$", "").replace(":", "")
        // The new generated rule should have the same generatedFiles and generatedRules
        // in order to avoid duplications
        val builder = BuildRuleWithDefaultsBuilder(
            referencedRuleClass.getName(), referencedRuleName, ruleClassMap, generateFiles,
            generateRules
        )
        builder.populateAttributes(rulePkg, true)
        generateRules.put(referencedRuleClass.getName(), builder)
        return referencedRuleName
    }

    fun populateLabelAttribute(pkg: String, attribute: Attribute): BuildRuleWithDefaultsBuilder {
        return populateLabelAttribute(pkg, pkg, attribute)
    }

    /**
     * Populates the label type attribute with generated values. Populates with a file if possible, or
     * generates an appropriate rule. Note, that the rules are always generated in the same package.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun populateLabelAttribute(
        rulePkg: String, filePkg: String?, attribute: Attribute
    ): BuildRuleWithDefaultsBuilder {
        val attrType: Type<*> = attribute.getType()
        var label: String? = null
        if (attribute.getAllowedFileTypesPredicate() !== FileTypeSet.NO_FILE) {
            // Try to populate with files first
            var extension = ""
            if (attribute.getAllowedFileTypesPredicate() === FileTypeSet.ANY_FILE) {
                extension = ".txt"
            } else if (attribute.getAllowedFileTypesPredicate() != null) {
                val fileTypes: FileTypeSet = attribute.getAllowedFileTypesPredicate()
                // This argument should always hold, if not that means a Blaze design/implementation error
                com.google.common.base.Preconditions.checkArgument(
                    !fileTypes.getExtensions().isEmpty(),
                    "Attribute %s does not have any allowed file types",
                    attribute.name
                )
                extension = fileTypes.getExtensions().get(0)
            }
            label = getDummyFileLabel(rulePkg, filePkg, extension, attrType)
        } else {
            val allowedRuleClasses: com.google.common.base.Predicate<RuleClass?>? =
                attribute.getAllowedRuleClassObjectPredicate()
            if (allowedRuleClasses !== com.google.common.base.Predicates.alwaysFalse<RuleClass?>()) {
                // See if there is an applicable rule among the already enqueued rules
                val referencedRuleBuilder: BuildRuleBuilder? = getFirstApplicableRule(attribute)
                if (referencedRuleBuilder != null) {
                    label = ":" + referencedRuleBuilder.ruleName
                } else {
                    val referencedRuleClass: RuleClass? = getFirstApplicableRuleClass(attribute)
                    if (referencedRuleClass != null) {
                        // Generate a rule with the appropriate ruleClass and a label for it in
                        // the original rule
                        label = ":" + getDummyRuleLabel(rulePkg, referencedRuleClass)
                    }
                }
            }
        }
        if (label != null) {
            if (attrType is ListType<*>) {
                addMultiValueAttributes(attribute.name, label)
            } else {
                setSingleValueAttribute(attribute.name, label)
            }
        }
        return this
    }

    private fun doesRuleClassMatch(attribute: Attribute, ruleClass: RuleClass): Boolean {
        // The rule class isn't in the allowed list.
        if (!attribute.getAllowedRuleClassObjectPredicate().apply(ruleClass)) {
            return false
        }

        // Does this rule class have the correct providers?
        if (!attribute.getRequiredProviders().acceptsAny()) {
            // This attribute requires specific providers, so ignore any rule that claims to have every
            // provider.
            if (ruleClass.getAdvertisedProviders().canHaveAnyProvider()) {
                return false
            }

            if (!attribute.getRequiredProviders().isSatisfiedBy(ruleClass.getAdvertisedProviders())) {
                return false
            }
        }

        // Default to accept if nothing else prevents.
        return true
    }

    private fun getFirstApplicableRule(attribute: Attribute): BuildRuleBuilder? {
        // There is no direct way to get the set of allowedRuleClasses from the Attribute
        // The Attribute API probably should not be modified for sole testing purposes
        val result: java.util.Optional<BuildRuleBuilder?> =
            generateRules.entries.stream()
                .filter { entry: MutableMap.MutableEntry<String?, BuildRuleBuilder?>? ->
                    doesRuleClassMatch(
                        attribute,
                        ruleClassMap.get(entry!!.key)
                    )
                }
                .map<BuildRuleBuilder?> { java.util.Map.Entry.value }
                .findFirst()
        return result.orElse(null)
    }

    private fun getFirstApplicableRuleClass(attribute: Attribute): RuleClass? {
        val result: java.util.Optional<RuleClass?> =
            ruleClassMap.values.stream()
                .filter { ruleClass: RuleClass? -> doesRuleClassMatch(attribute, ruleClass) }
                .findFirst()
        return result.orElse(null)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun populateStringListAttribute(attribute: Attribute): BuildRuleWithDefaultsBuilder {
        addMultiValueAttributes(attribute.name, "x")
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun populateStringAttribute(attribute: Attribute): BuildRuleWithDefaultsBuilder {
        setSingleValueAttribute(attribute.name, "x")
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun populateBooleanAttribute(attribute: Attribute): BuildRuleWithDefaultsBuilder {
        setSingleValueAttribute(attribute.name, "false")
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun populateIntegerAttribute(attribute: Attribute): BuildRuleWithDefaultsBuilder {
        setSingleValueAttribute(attribute.name, 1)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun populateAttributes(rulePkg: String, heuristics: Boolean): BuildRuleWithDefaultsBuilder {
        for (attribute in ruleClass.getAttributeProvider().getAttributes()) {
            if (attribute.isMandatory()) {
                if (BuildType.isLabelType(attribute.getType())) {
                    // TODO(bazel-team): actually an empty list would be fine in the case where
                    // attribute instanceof ListType && !attribute.isNonEmpty(), but BuildRuleBuilder
                    // doesn't support that, and it makes little sense anyway
                    populateLabelAttribute(rulePkg, attribute)
                } else {
                    // Non label type attributes
                    if (attribute.getAllowedValues() is AllowedValueSet) {
                        val allowedValues: MutableCollection<Any?> =
                            (attribute.getAllowedValues() as AllowedValueSet).getAllowedValues()
                        setSingleValueAttribute(attribute.name, allowedValues.iterator().next())
                    } else if (attribute.getType() === Type.STRING) {
                        populateStringAttribute(attribute)
                    } else if (attribute.getType() === Type.BOOLEAN) {
                        populateBooleanAttribute(attribute)
                    } else if (attribute.getType() === Type.INTEGER) {
                        populateIntegerAttribute(attribute)
                    } else if (attribute.getType() === Types.STRING_LIST) {
                        populateStringListAttribute(attribute)
                    }
                }
                // TODO(bazel-team): populate for other data types
            } else if (heuristics) {
                populateAttributesHeuristics(rulePkg, attribute)
            }
        }
        return this
    }

    // Heuristics which might help to generate valid rules.
    // This is a bit hackish, but it helps some generated ruleclasses to pass analysis phase.
    private fun populateAttributesHeuristics(rulePkg: String, attribute: Attribute) {
        if (attribute.name.equals("srcs") && attribute.getType() === BuildType.LABEL_LIST) {
            // If there is a srcs attribute it might be better to populate it even if it's not mandatory
            populateLabelAttribute(rulePkg, attribute)
        } else if (attribute.name.equals("main_class") && attribute.getType() === Type.STRING) {
            populateStringAttribute(attribute)
        }
    }

    val filesToGenerate: MutableCollection<String?>
        get() = generateFiles

    val rulesToGenerate: MutableCollection<BuildRuleBuilder>
        get() = generateRules.values
}
