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
package com.google.devtools.build.docgen

import com.google.devtools.build.lib.packages.RuleClass

/**
 * A class to assemble documentation for the Build Encyclopedia. This class uses
 * [BuildDocCollector] to extract documentation fragments from rule classes.
 */
abstract class BuildEncyclopediaProcessor(
    linkExpander: RuleLinkExpander?,
    urlMapper: SourceUrlMapper?,
    ruleClassProvider: ConfiguredRuleClassProvider?
) {
    /** Class that expand links to the BE.  */
    protected val linkExpander: RuleLinkExpander?

    /** Mapper from source files/labels to source code repository URLs.  */
    var urlMapper: SourceUrlMapper?

    /** Rule class provider from which to extract the rule class hierarchy and attributes.  */
    protected val ruleClassProvider: ConfiguredRuleClassProvider

    /**
     * Creates the BuildEncyclopediaProcessor instance. The ruleClassProvider parameter is used for
     * rule class hierarchy and attribute checking.
     */
    init {
        this.linkExpander = linkExpander
        this.urlMapper = com.google.common.base.Preconditions.checkNotNull<SourceUrlMapper?>(urlMapper)
        this.ruleClassProvider =
            com.google.common.base.Preconditions.checkNotNull<ConfiguredRuleClassProvider>(ruleClassProvider)
    }

    /**
     * Collects and processes all the rule and attribute documentation in inputJavaDirs and generates
     * the Build Encyclopedia into outputDir.
     * 
     * @param inputJavaDirs list of directories to scan for documentation in Java source code
     * @param buildEncyclopediaStardocProtos list of file paths of stardoc_output.ModuleInfo binary
     * proto files generated from Build Encyclopedia entry point .bzl files; documentation from
     * these protos takes precedence over documentation from `inputJavaDirs`
     * @param outputRootDir output directory where to write the build encyclopedia
     * @param denyList optional path to a file listing rules to not document
     */
    @Throws(BuildEncyclopediaDocException::class, IOException::class)
    abstract fun generateDocumentation(
        inputJavaDirs: MutableList<String?>?,
        buildEncyclopediaStardocProtos: MutableList<String?>?,
        outputDir: String?,
        denyList: String?
    )

    /**
     * POD class for containing lists of rule families separated into language-specific and generic as
     * returned by [&lt;][.assembleRuleFamilies].
     */
    protected class RuleFamilies(
        langSpecific: MutableList<RuleFamily?>?, generic: MutableList<RuleFamily?>?,
        all: MutableList<RuleFamily?>?
    ) {
        var langSpecific: MutableList<RuleFamily?>?
        var generic: MutableList<RuleFamily?>?
        var all: MutableList<RuleFamily?>?

        init {
            this.langSpecific = langSpecific
            this.generic = generic
            this.all = all
        }
    }

    @Throws(BuildEncyclopediaDocException::class, IOException::class)
    protected fun assembleRuleFamilies(docEntries: Iterable<RuleDocumentation>): RuleFamilies {
        // Separate rule families into language-specific and generic ones.
        val langSpecificRuleFamilyNames: MutableSet<String?> = TreeSet<String?>()
        val genericRuleFamilyNames: MutableSet<String?> = TreeSet<String?>()
        separateRuleFamilies(docEntries, langSpecificRuleFamilyNames, genericRuleFamilyNames)

        // Create a mapping of rules based on rule type and family.
        val ruleMapping: MutableMap<String?, com.google.common.collect.ListMultimap<DocgenConsts.RuleType?, RuleDocumentation?>> =
            HashMap<String?, com.google.common.collect.ListMultimap<DocgenConsts.RuleType?, RuleDocumentation?>>()
        createRuleMapping(docEntries, ruleMapping)

        // Create a mapping with the summary string for the individual rule families
        val familySummary: MutableMap<String?, java.lang.StringBuilder?> = HashMap<String?, java.lang.StringBuilder?>()
        createFamilySummary(docEntries, familySummary)

        // Create lists of RuleFamily objects that will be used to generate the documentation.
        // The separate language-specific and general rule families will be used to generate
        // the Overview page while the list containing all rule families will be used to
        // generate all other documentation.
        val langSpecificRuleFamilies: MutableList<RuleFamily?> =
            filterRuleFamilies(ruleMapping, langSpecificRuleFamilyNames, familySummary)
        val genericRuleFamilies: MutableList<RuleFamily?> =
            filterRuleFamilies(ruleMapping, genericRuleFamilyNames, familySummary)
        val allRuleFamilies: MutableList<RuleFamily?> = java.util.ArrayList<RuleFamily?>(langSpecificRuleFamilies)
        allRuleFamilies.addAll(genericRuleFamilies)
        return RuleFamilies(langSpecificRuleFamilies, genericRuleFamilies, allRuleFamilies)
    }

    private fun filterRuleFamilies(
        ruleMapping: MutableMap<String?, com.google.common.collect.ListMultimap<DocgenConsts.RuleType?, RuleDocumentation?>>,
        ruleFamilyNames: MutableSet<String?>,
        familySummary: MutableMap<String?, java.lang.StringBuilder?>
    ): MutableList<RuleFamily?> {
        val ruleFamilies: MutableList<RuleFamily?> = java.util.ArrayList<RuleFamily?>(ruleFamilyNames.size)
        for (name in ruleFamilyNames) {
            val ruleTypeMap: com.google.common.collect.ListMultimap<DocgenConsts.RuleType?, RuleDocumentation?> =
                ruleMapping.get(name)
            ruleFamilies.add(
                RuleFamily(
                    ruleTypeMap, name, familySummary.getOrDefault(name, java.lang.StringBuilder()).toString()
                )
            )
        }
        return ruleFamilies
    }

    /**
     * Create a mapping of rules based on rule type and family.
     */
    @Throws(BuildEncyclopediaDocException::class)
    private fun createRuleMapping(
        docEntries: Iterable<RuleDocumentation>,
        ruleMapping: MutableMap<String?, com.google.common.collect.ListMultimap<DocgenConsts.RuleType?, RuleDocumentation?>>
    ) {
        for (ruleDoc in docEntries) {
            val ruleClass: RuleClass? = ruleClassProvider.getRuleClassMap().get(ruleDoc.getRuleName())
            if (ruleClass != null) {
                val ruleFamily: String? = ruleDoc.getRuleFamily()
                if (!ruleMapping.containsKey(ruleFamily)) {
                    ruleMapping.put(
                        ruleFamily,
                        com.google.common.collect.LinkedListMultimap.create<DocgenConsts.RuleType?, RuleDocumentation?>()
                    )
                }
                if (ruleClass.isDocumented()) {
                    ruleMapping.get(ruleFamily).put(ruleDoc.getRuleType(), ruleDoc)
                }
            } else {
                val ruleFamily: String? = ruleDoc.getRuleFamily()
                ruleMapping.putIfAbsent(
                    ruleFamily,
                    com.google.common.collect.LinkedListMultimap.create<DocgenConsts.RuleType?, RuleDocumentation?>()
                )
                ruleMapping.get(ruleFamily).put(ruleDoc.getRuleType(), ruleDoc)
            }
        }
    }

    /**
     * Obtain the summary string for a rule family from whatever member of the family providing it (if
     * any; otherwise use the empty string).
     */
    private fun createFamilySummary(
        docEntries: Iterable<RuleDocumentation>, familySummary: MutableMap<String?, java.lang.StringBuilder?>
    ) {
        for (ruleDoc in docEntries) {
            val ruleClass: RuleClass? = ruleClassProvider.getRuleClassMap().get(ruleDoc.getRuleName())
            if (ruleClass != null) {
                val ruleFamily: String? = ruleDoc.getRuleFamily()
                familySummary.computeIfAbsent(ruleFamily) { k: String? -> java.lang.StringBuilder() }
                familySummary.get(ruleFamily).append(ruleDoc.getFamilySummary())
            }
        }
    }

    /**
     * Separates all rule families in docEntries into language-specific rules and generic rules.
     */
    @Throws(BuildEncyclopediaDocException::class)
    private fun separateRuleFamilies(
        docEntries: Iterable<RuleDocumentation>,
        langSpecific: MutableSet<String?>, generic: MutableSet<String?>
    ) {
        for (ruleDoc in docEntries) {
            if (ruleDoc.isLanguageSpecific()) {
                if (generic.contains(ruleDoc.getRuleFamily())) {
                    throw ruleDoc.createException(
                        "The rule is marked as being language-specific, but other "
                                + "rules of the same family have already been marked as being not."
                    )
                }
                langSpecific.add(ruleDoc.getRuleFamily())
            } else {
                if (langSpecific.contains(ruleDoc.getRuleFamily())) {
                    throw ruleDoc.createException(
                        "The rule is marked as being generic, but other rules of "
                                + "the same family have already been marked as being language-specific."
                    )
                }
                generic.add(ruleDoc.getRuleFamily())
            }
        }
    }

    /**
     * Sets the [RuleLinkExpander] for the provided [RuleDocumentationAttributes].
     * 
     * 
     * This method is used to set the [RuleLinkExpander] for common attributes, such as those
     * defined in [PredefinedAttributes], so that rule references in the docs for those
     * attributes can be expanded.
     * 
     * @param attributes The map containing the RuleDocumentationAttributes, keyed by attribute name.
     * @return The provided map of attributes.
     */
    protected fun expandCommonAttributes(
        attributes: MutableMap<String?, RuleDocumentationAttribute>
    ): MutableMap<String?, RuleDocumentationAttribute> {
        for (attribute in attributes.values) {
            attribute.setRuleLinkExpander(linkExpander)
        }
        return attributes
    }

    companion object {
        protected val RULE_WORTH_DOCUMENTING: com.google.common.base.Predicate<String?> =
            object : com.google.common.base.Predicate<String?>() {
                override fun apply(name: String): Boolean {
                    return !name.contains("$")
                }
            }

        /**
         * Helper method for displaying an warning message about undocumented rules.
         * 
         * @param rulesWithoutDocumentation Undocumented rules to list in the warning message.
         */
        protected fun warnAboutUndocumentedRules(rulesWithoutDocumentation: Iterable<String?>) {
            val undocumentedRules: Iterable<String?> = com.google.common.collect.Iterables.filter<String?>(
                rulesWithoutDocumentation,
                RULE_WORTH_DOCUMENTING
            )
            java.lang.System.err.printf(
                "WARNING: The following rules are undocumented: [%s]\n",
                com.google.common.base.Joiner.on(", ").join(
                    com.google.common.collect.Ordering.natural<String?>()
                        .immutableSortedCopy<String?>(undocumentedRules)
                )
            )
        }

        /**
         * Writes the [Page] using the provided file name in the specified output directory.
         * 
         * @param page The page to write.
         * @param outputDir The output directory to write the file.
         * @param fileName The name of the file to write the page to.
         * @throws IOException
         */
        @Throws(IOException::class)
        protected fun writePage(page: com.google.devtools.build.docgen.Page, outputDir: String?, fileName: String?) {
            page.write(java.io.File(outputDir + "/" + fileName))
        }
    }
}
