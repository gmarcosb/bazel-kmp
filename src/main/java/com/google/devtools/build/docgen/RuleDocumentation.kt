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

import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.docgen.BuildEncyclopediaDocException
import com.google.devtools.build.docgen.DocgenConsts
import com.google.devtools.build.docgen.RuleDocumentationAttribute
import com.google.devtools.build.docgen.RuleLinkExpander
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import java.util.HashMap
import java.util.TreeSet

/**
 * A class representing the documentation of a rule along with some meta-data. The sole ruleName
 * field is used as a key for comparison, equals and hashcode.
 * 
 * 
 * The class contains meta information about the rule:
 * 
 * 
 *  * Rule type: categorizes the rule based on it's general (language independent) purpose, such
 * as "binary" or "library"; see [RuleType].
 *  * Rule family: categorizes the rule based on language, such as "Java" or "C / C++".
 * 
 * 
 * 
 * For generating error messages, the class also stores the location where raw documentation was
 * retrieved.
 */
class RuleDocumentation internal constructor(
    ruleName: String,
    ruleType: String?,
    ruleFamily: String?,
    htmlDocumentation: String,
    location: String?,
    sourceUrl: String?,
    flags: com.google.common.collect.ImmutableSet<String?>,
    familySummary: String?
) : Comparable<RuleDocumentation?> {
    /**
     * Returns the name of the rule.
     */
    val ruleName: String
    private val ruleType: DocgenConsts.RuleType

    /**
     * Returns the family of the rule. The family is usually the corresponding programming language,
     * except for rules independent of language, such as genrule. E.g. the family of the java_library
     * rule is 'JAVA', the family of genrule is 'GENERAL'.
     */
    val ruleFamily: String?

    /**
     * Return the contribution of this rule to the summary for the rule family. Usually, the "main"
     * rule in a family provides the summary, but all contributions are accumulated.
     */
    val familySummary: String?
    private val htmlDocumentation: String
    private val location: String? // for error messages

    /** Returns the URL of the rule's source file in its source code repository.  */
    val sourceUrl: String? // for linking in rendered docs
    private val flags: com.google.common.collect.ImmutableSet<String?>

    private val docVariables: MutableMap<String?, String?> = HashMap<String?, String?>()

    // Only one attribute per attributeName is allowed
    private val attributes: MutableSet<RuleDocumentationAttribute> = TreeSet<RuleDocumentationAttribute>()

    private var linkExpander: RuleLinkExpander? = null

    /**
     * Creates a RuleDocumentation from the rule's name, type, family and raw html documentation
     * (meaning without expanding the variables in the doc).
     */
    init {
        com.google.common.base.Preconditions.checkNotNull<String?>(ruleName)
        this.ruleName = ruleName
        if (flags.contains(DocgenConsts.FLAG_GENERIC_RULE)) {
            this.ruleType = com.google.devtools.build.docgen.DocgenConsts.RuleType.OTHER
        } else {
            try {
                this.ruleType = com.google.devtools.build.docgen.DocgenConsts.RuleType.valueOf(ruleType)
            } catch (e: java.lang.IllegalArgumentException) {
                throw BuildEncyclopediaDocException(location, "Invalid rule type " + ruleType)
            }
        }
        this.ruleFamily = ruleFamily
        this.htmlDocumentation = htmlDocumentation
        this.location = location
        this.sourceUrl = sourceUrl
        this.flags = flags
        this.familySummary = familySummary
    }

    internal constructor(
        ruleName: String,
        ruleType: String?,
        ruleFamily: String?,
        htmlDocumentation: String,
        fileName: String?,
        line: Int,
        sourceUrl: String?,
        flags: com.google.common.collect.ImmutableSet<String?>,
        familySummary: String?
    ) : this(
        ruleName,
        ruleType,
        ruleFamily,
        htmlDocumentation,
        BuildEncyclopediaDocException.Companion.formatLocation(fileName, line),
        sourceUrl,
        flags,
        familySummary
    )

    /**
     * Returns the type of the rule
     */
    fun getRuleType(): DocgenConsts.RuleType {
        return ruleType
    }

    /**
     * Returns true if this rule documentation has the parameter flag.
     */
    fun hasFlag(flag: String?): Boolean {
        return flags.contains(flag)
    }

    val isLanguageSpecific: Boolean
        /**
         * Returns true if this rule applies to a specific programming language (e.g. java_library),
         * returns false if it is a generic action (e.g. genrule, filegroup).
         * 
         * 
         * A rule is considered to be specific to a programming language by default. Generic rules
         * have to be marked with the flag GENERIC_RULE in their #BLAZE_RULE definition.
         */
        get() = !flags.contains(DocgenConsts.FLAG_GENERIC_RULE)

    /**
     * Adds a variable name - value pair to the documentation to be substituted.
     */
    fun addDocVariable(varName: String?, value: String?) {
        docVariables.put(varName, value)
    }

    /**
     * Adds a rule documentation attribute to this rule documentation.
     */
    fun addAttribute(attribute: RuleDocumentationAttribute?) {
        attributes.add(attribute)
    }

    /** Adds multiple rule documentation attributes to this rule documentation.  */
    fun addAttributes(attribute: MutableCollection<RuleDocumentationAttribute?>?) {
        attributes.addAll(attribute)
    }

    /**
     * Returns the rule's set of RuleDocumentationAttributes.
     */
    fun getAttributes(): MutableSet<RuleDocumentationAttribute> {
        return attributes
    }

    /**
     * Returns the rule's RuleDocumentationAttribute with the given name, or null if no such attribute
     * exists.
     */
    fun getAttribute(attributeName: String?): RuleDocumentationAttribute? {
        return attributes.stream()
            .filter { attribute: RuleDocumentationAttribute? -> attribute.getAttributeName() == attributeName }
            .findFirst()
            .orElse(null)
    }

    /**
     * Sets the [RuleLinkExpander] to be used to expand links in the HTML documentation for both
     * this RuleDocumentation and all [RuleDocumentationAttribute]s associated with this rule.
     */
    fun setRuleLinkExpander(linkExpander: RuleLinkExpander?) {
        this.linkExpander = linkExpander
        for (attribute in attributes) {
            attribute.setRuleLinkExpander(linkExpander)
        }
    }

    /**
     * Returns the html documentation in the exact format it should be written into the Build
     * Encyclopedia (expanding variables).
     */
    @Throws(BuildEncyclopediaDocException::class)
    fun getHtmlDocumentation(): String {
        var expandedDoc = htmlDocumentation
        // Substituting variables
        for (docVariable in docVariables.entries) {
            expandedDoc = expandedDoc.replace(
                "\${" + docVariable.key + "}",
                expandBuiltInVariables(docVariable.key!!, docVariable.value)
            )
        }
        if (linkExpander != null) {
            try {
                expandedDoc = linkExpander.expand(expandedDoc)
            } catch (e: java.lang.IllegalArgumentException) {
                throw BuildEncyclopediaDocException(location, e.message)
            }
        }
        return expandedDoc
    }

    val commandLineDocumentation: String
        /**
         * Returns the documentation of the rule in a form which is printable on the command line.
         */
        get() = "\n" + DocgenConsts.toCommandLineFormat(htmlDocumentation)

    @get:Throws(BuildEncyclopediaDocException::class)
    val nameExtraHtmlDoc: String?
        /**
         * Returns a string containing any extra documentation for the name attribute for this
         * rule.
         */
        get() {
            var expandedDoc = if (docVariables.containsKey(DocgenConsts.VAR_NAME))
                docVariables.get(DocgenConsts.VAR_NAME)
            else
                ""
            if (linkExpander != null) {
                try {
                    expandedDoc = linkExpander.expand(expandedDoc)
                } catch (e: java.lang.IllegalArgumentException) {
                    throw BuildEncyclopediaDocException(location, e.message)
                }
            }
            return expandedDoc
        }

    val isDeprecated: Boolean
        /**
         * Returns whether this rule is deprecated.
         */
        get() = hasFlag(DocgenConsts.FLAG_DEPRECATED)

    val attributeSignature: String
        /**
         * Returns a string containing the attribute signature for this rule with HTML links
         * to the attributes.
         */
        get() {
            val sb: java.lang.StringBuilder = java.lang.StringBuilder()
            sb.append(String.format("%s(<a href=\"#%s.name\">name</a>, ", ruleName, ruleName))
            var i = 0
            for (attributeDoc in attributes) {
                val attrName: String? = attributeDoc.getAttributeName()
                // Generate the link for the attribute documentation
                if (attributeDoc.isCommonType()) {
                    sb.append(
                        String.format(
                            "<a href=\"%s#%s.%s\">%s</a>",
                            COMMON_DEFINITIONS_PAGE,
                            com.google.common.base.Ascii.toLowerCase(attributeDoc.getGeneratedInRule(ruleName)),
                            attrName,
                            attrName
                        )
                    )
                } else {
                    sb.append(
                        String.format(
                            "<a href=\"#%s.%s\">%s</a>",
                            com.google.common.base.Ascii.toLowerCase(attributeDoc.getGeneratedInRule(ruleName)),
                            attrName,
                            attrName
                        )
                    )
                }
                if (i < attributes.size - 1) {
                    sb.append(", ")
                } else {
                    sb.append(")")
                }
                i++
            }
            return sb.toString()
        }

    private fun expandBuiltInVariables(key: String, value: String?): String? {
        // Some built in BLAZE variables need special handling, e.g. adding headers
        return when (key) {
            DocgenConsts.VAR_IMPLICIT_OUTPUTS -> String.format(
                "<h4 id=\"%s_implicit_outputs\">Implicit output targets</h4>\n%s",
                com.google.common.base.Ascii.toLowerCase(ruleName), value
            )

            else -> value
        }
    }

    /**
     * Creates a BuildEncyclopediaDocException with the file containing this rule doc and
     * the number of the first line (where the rule doc is defined). Can be used to create
     * general BuildEncyclopediaDocExceptions about this rule.
     */
    fun createException(msg: String?): BuildEncyclopediaDocException {
        return BuildEncyclopediaDocException(location, msg)
    }

    override fun hashCode(): Int {
        return ruleName.hashCode()
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is RuleDocumentation) {
            return false
        }
        return ruleName == obj.ruleName
    }

    private val typePriority: Int
        get() = when (ruleType) {
            com.google.devtools.build.docgen.DocgenConsts.RuleType.FLAG -> 1
            com.google.devtools.build.docgen.DocgenConsts.RuleType.BINARY -> 2
            com.google.devtools.build.docgen.DocgenConsts.RuleType.LIBRARY -> 3
            com.google.devtools.build.docgen.DocgenConsts.RuleType.TEST -> 4
            com.google.devtools.build.docgen.DocgenConsts.RuleType.OTHER -> 5
        }

    override fun compareTo(o: RuleDocumentation): Int {
        if (this.typePriority < o.typePriority) {
            return -1
        } else if (this.typePriority > o.typePriority) {
            return 1
        } else {
            return this.ruleName.compareTo(o.ruleName)
        }
    }

    override fun toString(): String {
        return String.format("%s (TYPE = %s, FAMILY = %s)", ruleName, ruleType, ruleFamily)
    }

    companion object {
        /**
         * Name of the page documenting common build rule terms and concepts.
         */
        const val COMMON_DEFINITIONS_PAGE: String = "common-definitions.html"

        /**
         * Returns a "normalized" version of the input string. Used to convert rule family names into
         * strings that are more friendly as file names. For example, "C / C++" is converted to
         * "c-cpp".
         */
        @com.google.common.annotations.VisibleForTesting
        fun normalize(s: String): String? {
            return com.google.common.base.Ascii.toLowerCase(s)
                .replace('+', 'p')
                .replace("[()]".toRegex(), "")
                .replace("[\\s/]".toRegex(), "-")
                .replace("[-]+".toRegex(), "-")
        }
    }
}
