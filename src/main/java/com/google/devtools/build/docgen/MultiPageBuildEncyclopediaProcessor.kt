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

import com.google.devtools.build.docgen.BuildDocCollector
import com.google.devtools.build.docgen.BuildEncyclopediaDocException
import com.google.devtools.build.docgen.BuildEncyclopediaProcessor
import com.google.devtools.build.docgen.BuildEncyclopediaProcessor.RuleFamilies
import com.google.devtools.build.docgen.DocgenConsts
import com.google.devtools.build.docgen.PredefinedAttributes
import com.google.devtools.build.docgen.RuleDocumentation
import com.google.devtools.build.docgen.RuleFamily
import com.google.devtools.build.docgen.RuleLinkExpander
import com.google.devtools.build.docgen.SourceUrlMapper
import com.google.devtools.build.docgen.TemplateEngine
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider
import java.io.IOException

/** Assembles the multi-page version of the Build Encyclopedia with one page per rule family.  */
class MultiPageBuildEncyclopediaProcessor(
    linkExpander: RuleLinkExpander?,
    urlMapper: SourceUrlMapper?,
    ruleClassProvider: ConfiguredRuleClassProvider?,
// Whether a table-of-contents file should be created.
    protected val createToc: Boolean
) : BuildEncyclopediaProcessor(linkExpander, urlMapper, ruleClassProvider) {
    /**
     * Collects and processes all the rule and attribute documentation in inputJavaDirs and generates
     * the Build Encyclopedia into outputDir.
     * 
     * @param inputJavaDirs list of directories to scan for documentation in Java source code
     * @param buildEncyclopediaStardocProtos list of file paths of stardoc_output.ModuleInfo binary
     * proto files generated from Build Encyclopedia entry point .bzl files; documentation from
     * these protos takes precedence over documentation from `inputJavaDirs`
     * @param outputDir output directory where to write the build encyclopedia
     * @param denyList optional path to a file listing rules to not document
     */
    @Throws(BuildEncyclopediaDocException::class, IOException::class)
    override fun generateDocumentation(
        inputJavaDirs: MutableList<String?>,
        buildEncyclopediaStardocProtos: MutableList<String?>?,
        outputDir: String?,
        denyList: String?
    ) {
        val collector: BuildDocCollector = BuildDocCollector(linkExpander, urlMapper, ruleClassProvider)
        val ruleDocEntries: MutableMap<String?, RuleDocumentation?> =
            collector.collect(inputJavaDirs, buildEncyclopediaStardocProtos, denyList)
        BuildEncyclopediaProcessor.Companion.warnAboutUndocumentedRules(
            com.google.common.collect.Sets.difference<String?>(
                ruleClassProvider.getRuleClassMap().keys,
                ruleDocEntries.keys
            )
        )

        writeStaticDoc(outputDir, "make-variables")
        writeStaticDoc(outputDir, "functions")
        writeCommonDefinitionsPage(outputDir)

        writeRuleDocs(outputDir, ruleDocEntries.values)
    }

    @Throws(IOException::class)
    private fun writeStaticDoc(outputDir: String?, name: String?) {
        // TODO(dzc): Consider splitting out the call to writePage so that this method only creates the
        // Page object and adding docgen tests that test the state of Page objects constructed by
        // this method, and similar methods in this class.
        val page: com.google.devtools.build.docgen.Page =
            TemplateEngine.newPage(DocgenConsts.BE_TEMPLATE_DIR + "/" + name + ".vm")
        page.add("expander", linkExpander)
        BuildEncyclopediaProcessor.Companion.writePage(page, outputDir, name + ".html")
    }

    @Throws(IOException::class)
    private fun writeCommonDefinitionsPage(outputDir: String?) {
        val page: com.google.devtools.build.docgen.Page =
            TemplateEngine.newPage(DocgenConsts.COMMON_DEFINITIONS_TEMPLATE)
        page.add("expander", linkExpander)
        page.add("typicalAttributes", expandCommonAttributes(PredefinedAttributes.TYPICAL_ATTRIBUTES))
        page.add("commonAttributes", expandCommonAttributes(PredefinedAttributes.COMMON_ATTRIBUTES))
        page.add("testAttributes", expandCommonAttributes(PredefinedAttributes.TEST_ATTRIBUTES))
        page.add("binaryAttributes", expandCommonAttributes(PredefinedAttributes.BINARY_ATTRIBUTES))
        BuildEncyclopediaProcessor.Companion.writePage(page, outputDir, "common-definitions.html")
    }

    @Throws(BuildEncyclopediaDocException::class, IOException::class)
    private fun writeRuleDocs(outputDir: String?, docEntries: Iterable<RuleDocumentation?>?) {
        val ruleFamilies: RuleFamilies = assembleRuleFamilies(docEntries)

        // Generate documentation.
        writeOverviewPage(outputDir, ruleFamilies.langSpecific, ruleFamilies.generic)
        writeBeNav(outputDir, ruleFamilies.all)
        for (ruleFamily in ruleFamilies.all) {
            if (ruleFamily.size() > 0) {
                writeRuleDoc(outputDir, ruleFamily)
            }
        }
        if (createToc) {
            writeTableOfContents(outputDir, ruleFamilies.langSpecific, ruleFamilies.generic)
        }
    }

    @Throws(BuildEncyclopediaDocException::class, IOException::class)
    private fun writeOverviewPage(
        outputDir: String?,
        langSpecificRuleFamilies: MutableList<RuleFamily?>?,
        genericRuleFamilies: MutableList<RuleFamily?>?
    ) {
        val page: com.google.devtools.build.docgen.Page = TemplateEngine.newPage(DocgenConsts.OVERVIEW_TEMPLATE)
        page.add("expander", linkExpander)
        page.add("langSpecificRuleFamilies", langSpecificRuleFamilies)
        page.add("genericRuleFamilies", genericRuleFamilies)
        BuildEncyclopediaProcessor.Companion.writePage(page, outputDir, "overview.html")
    }

    @Throws(IOException::class)
    private fun writeTableOfContents(
        outputDir: String?,
        langSpecificRuleFamilies: MutableList<RuleFamily?>?,
        genericRuleFamilies: MutableList<RuleFamily?>?
    ) {
        val page: com.google.devtools.build.docgen.Page = TemplateEngine.newPage(DocgenConsts.BE_TOC_TEMPLATE)
        page.add("langSpecificRuleFamilies", langSpecificRuleFamilies)
        page.add("genericRuleFamilies", genericRuleFamilies)
        BuildEncyclopediaProcessor.Companion.writePage(page, outputDir, "_toc.yaml")
    }

    @Throws(BuildEncyclopediaDocException::class, IOException::class)
    private fun writeRuleDoc(outputDir: String?, ruleFamily: RuleFamily) {
        val page: com.google.devtools.build.docgen.Page = TemplateEngine.newPage(DocgenConsts.RULES_TEMPLATE)
        page.add("ruleFamily", ruleFamily)
        page.add("expander", linkExpander)
        BuildEncyclopediaProcessor.Companion.writePage(page, outputDir, ruleFamily.getId() + ".html")
    }

    @Throws(IOException::class)
    private fun writeBeNav(outputDir: String?, ruleFamilies: MutableList<RuleFamily>?) {
        val page: com.google.devtools.build.docgen.Page = TemplateEngine.newPage(DocgenConsts.BE_NAV_TEMPLATE)
        page.add("ruleFamilies", ruleFamilies)
        BuildEncyclopediaProcessor.Companion.writePage(page, outputDir, "be-nav.html")
    }
}
