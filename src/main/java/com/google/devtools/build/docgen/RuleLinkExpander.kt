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

import com.google.devtools.build.docgen.DocLinkMap
import com.google.devtools.build.docgen.DocgenConsts
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import java.nio.file.Path
import java.util.HashMap

/**
 * Helper class used for expanding link references in rule and attribute documentation.
 * 
 * 
 * See [com.google.devtools.build.docgen.DocgenConsts.BLAZE_RULE_LINK] for the regex used
 * to match link references.
 */
// TODO(fwe): rename to LinkExpander
// TODO(fwe): prefix rule links with BE root?
class RuleLinkExpander {
    private val linkMap: DocLinkMap
    private val ruleIndex: MutableMap<String?, String?> = HashMap<String?, String?>()
    private val singlePage: Boolean

    internal constructor(ruleIndex: MutableMap<String?, String?>?, singlePage: Boolean, linkMap: DocLinkMap) {
        this.ruleIndex.putAll(ruleIndex!!)
        this.ruleIndex.putAll(FUNCTIONS)
        this.singlePage = singlePage
        this.linkMap = linkMap
    }

    internal constructor(singlePage: Boolean, linkMap: DocLinkMap) {
        this.ruleIndex.putAll(FUNCTIONS)
        this.singlePage = singlePage
        this.linkMap = linkMap
    }

    fun beRoot(): String? {
        return linkMap.beRoot
    }

    fun addIndex(ruleIndex: MutableMap<String?, String?>?) {
        this.ruleIndex.putAll(ruleIndex!!)
    }

    private fun appendRuleLink(
        matcher: java.util.regex.Matcher,
        sb: java.lang.StringBuffer,
        ruleName: String?,
        ref: String
    ) {
        val ruleFamily = ruleIndex.get(ruleName)
        val link =
            if (singlePage)
                "#" + ref
            else
                Path.of(linkMap.beRoot, String.format("%s.html#%s", ruleFamily, ref)).toString()
        matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(link))
    }

    /*
   * Match and replace all ${link rule.attribute} references.
   */
    @Throws(java.lang.IllegalArgumentException::class)
    private fun expandRuleLinks(htmlDoc: String): String {
        val matcher: java.util.regex.Matcher = DocgenConsts.BLAZE_RULE_LINK.matcher(htmlDoc)
        val sb: java.lang.StringBuffer = java.lang.StringBuffer(htmlDoc.length)
        while (matcher.find()) {
            // The first capture group matches the entire reference, e.g. "cc_binary.deps".
            val ref: String = matcher.group(1)
            // The second capture group only matches the rule name, e.g. "cc_binary" in "cc_binary.deps".
            val name: String = matcher.group(2)

            // The name in the reference is the name of a rule. Get the rule family for the rule and
            // replace the reference with a link with the form of rule-family.html#rule.attribute. For
            // example, ${link cc_library.deps} expands to c-cpp.html#cc_library.deps.
            if (ruleIndex.containsKey(name)) {
                appendRuleLink(matcher, sb, name, ref)
                continue
            }

            // The name is referencing the examples, arguments, or implicit outputs of a rule (e.g.
            // "cc_library_args", "cc_library_examples", or "java_binary_implicit_outputs"). Strip the
            // suffix and then try matching the name to a rule family.
            if (name.endsWith(EXAMPLES_SUFFIX)
                || name.endsWith(ARGS_SUFFIX)
                || name.endsWith(IMPLICIT_OUTPUTS_SUFFIX)
            ) {
                val endIndex: Int
                if (name.endsWith(EXAMPLES_SUFFIX)) {
                    endIndex = name.indexOf(EXAMPLES_SUFFIX)
                } else if (name.endsWith(ARGS_SUFFIX)) {
                    endIndex = name.indexOf(ARGS_SUFFIX)
                } else {
                    endIndex = name.indexOf(IMPLICIT_OUTPUTS_SUFFIX)
                }
                val ruleName: String = name.substring(0, endIndex)
                if (ruleIndex.containsKey(ruleName)) {
                    appendRuleLink(matcher, sb, ruleName, ref)
                    continue
                }
            }

            // The name is not the name of a rule but is the name of a static page, such as
            // common-definitions. Generate a link to that page.
            val mapping: String? = linkMap.beReferences.get(name)
            if (mapping != null) {
                val link =
                    if (singlePage && STATIC_PAGES_REPLACED_BY_SINGLE_PAGE_BE.contains(name))
                        "#" + name
                    else
                        mapping
                // For referencing headings on a static page, use the following syntax:
                // ${link static_page_name#heading_name}, example: ${link make-variables#gendir}
                val pageHeading: String? = matcher.group(4)
                require(pageHeading == null) {
                    ("Invalid link syntax for BE page: " + matcher.group()
                            + "\nUse \${link static-page#heading} syntax instead.")
                }
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(link))
                continue
            }

            // If the reference does not match any rule or static page, throw an exception.
            throw java.lang.IllegalArgumentException(
                ("Rule family " + name + " in link tag does not match any rule or BE page: "
                        + matcher.group())
            )
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    /*
   * Match and replace all ${link rule#heading} references.
   */
    @Throws(java.lang.IllegalArgumentException::class)
    private fun expandRuleHeadingLinks(htmlDoc: String): String {
        val matcher: java.util.regex.Matcher = DocgenConsts.BLAZE_RULE_HEADING_LINK.matcher(htmlDoc)
        val sb: java.lang.StringBuffer = java.lang.StringBuffer(htmlDoc.length)
        while (matcher.find()) {
            // The first capture group matches the entire reference, e.g. "cc_library#some_heading".
            val ref: String = matcher.group(1)
            // The second capture group only matches the rule name, e.g. "cc_library" in
            // "cc_library#some_heading"
            val name: String = matcher.group(2)
            // The third capture group only matches the heading, e.g. "some_heading" in
            // "cc_library#some_heading"
            val heading: String = matcher.group(3)

            // The name in the reference is the name of a rule. Get the rule family for the rule and
            // replace the reference with the link in the form of rule-family.html#heading. Examples of
            // this include custom <a name="heading"> tags in the description or examples for the rule.
            if (ruleIndex.containsKey(name)) {
                val ruleFamily = ruleIndex.get(name)
                val link = if (singlePage)
                    "#" + heading
                else
                    ruleFamily + ".html#" + heading
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(link))
                continue
            }

            // The name is of a static page, such as common.definitions. Generate a link to that page, and
            // append the page heading. For example, ${link common-definitions#label-expansion} expands to
            // common-definitions.html#label-expansion.

            // We need to search for the entire match first since some documentation files have a 1:n
            // relation between Blaze and Bazel. Example: build-ref#labels points to build-ref.html#labels
            // for Blaze, but to /concepts/labels for Bazel. However, we have to consider whether a single
            // heading or the entire page has to be redirected.

            // Not-null if page#heading has a mapping (other headings on the page are unaffected):
            val headingMapping: String? = linkMap.beReferences.get(ref)
            // Not-null if the entire page has a mapping, i.e. all headings should be redirected:
            val pageMapping: String? = linkMap.beReferences.get(name)

            if (headingMapping != null || pageMapping != null) {
                val link: String?
                if (singlePage && STATIC_PAGES_REPLACED_BY_SINGLE_PAGE_BE.contains(name)) {
                    // Special case: Some of the stand-alone files in the multi-page BE are made redundant
                    // by the BE in the single-page case. Consequently, we ignore the value of the mapping
                    // in this case (we only need to know that the mapping exists, since this means it is
                    // a legitimate reference).
                    link = "#" + heading
                } else if (headingMapping != null) {
                    // Multi-page BE where page#heading has to be redirected.
                    link = headingMapping
                } else { // pageMapping != null
                    // Multi-page BE where the entire page has to be forwarded (but the new page has
                    // identical headings).
                    link = String.format("%s#%s", pageMapping, heading)
                }

                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(link))
                continue
            }

            // If the reference does not match any rule or static page, throw an exception.
            throw java.lang.IllegalArgumentException(
                ("Rule family " + name + " in link tag does not match any rule or BE page: "
                        + matcher.group())
            )
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    /**
     * Expands all rule references in the input HTML documentation.
     * 
     * @param htmlDoc The input HTML documentation with ${link foo.bar} references.
     * @return The HTML documentation with all link references expanded.
     */
    @Throws(java.lang.IllegalArgumentException::class)
    fun expand(htmlDoc: String): String {
        val expanded = expandRuleLinks(htmlDoc)
        return expandRuleHeadingLinks(expanded)
    }

    /**
     * Expands the rule reference.
     * 
     * 
     * This method is used to expand references in the BE velocity templates.
     * 
     * @param ref The rule reference to expand.
     * @return The expanded rule reference.
     */
    @Throws(java.lang.IllegalArgumentException::class)
    fun expandRef(ref: String?): String {
        return expand("\${link " + ref + "}")
    }

    companion object {
        private const val EXAMPLES_SUFFIX = "_examples"
        private const val ARGS_SUFFIX = "_args"
        private const val IMPLICIT_OUTPUTS_SUFFIX = "_implicit_outputs"
        private const val FUNCTIONS_PAGE = "functions"

        private val FUNCTIONS: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.builder<String?, String?>()
                .put("load", FUNCTIONS_PAGE)
                .put("package", FUNCTIONS_PAGE)
                .put("package_group", FUNCTIONS_PAGE)
                .put("description", FUNCTIONS_PAGE)
                .put("licenses", FUNCTIONS_PAGE)
                .put("exports_files", FUNCTIONS_PAGE)
                .put("glob", FUNCTIONS_PAGE)
                .put("select", FUNCTIONS_PAGE)
                .buildOrThrow()

        // These static pages only exist in the multi-page BE. In the single-page BE
        // their content is part of the BE.
        private val STATIC_PAGES_REPLACED_BY_SINGLE_PAGE_BE: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("common-definitions", "make-variables")
    }
}
