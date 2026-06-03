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

import com.google.devtools.build.docgen.BuildEncyclopediaDocException
import com.google.devtools.build.docgen.DocgenConsts
import com.google.devtools.build.docgen.RuleDocumentation
import com.google.devtools.build.docgen.RuleDocumentationAttribute
import com.google.devtools.build.docgen.RuleDocumentationVariable
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider
import java.io.BufferedReader
import java.io.IOException
import java.util.HashMap
import java.util.LinkedList

/**
 * A helper class to read and process documentations for rule classes and attributes
 * from exactly one java source file.
 */
class SourceFileReader(ruleClassProvider: ConfiguredRuleClassProvider, javaSourceFilePath: String, sourceUrl: String?) {
    private var ruleDocEntries: MutableCollection<RuleDocumentation?>? = null
    private var attributeDocEntries: com.google.common.collect.ListMultimap<String?, RuleDocumentationAttribute?>? =
        null
    private val ruleClassProvider: ConfiguredRuleClassProvider
    private val javaSourceFilePath: String
    private val sourceUrl: String?

    /**
     * The handler class of the line read from the text file.
     */
    abstract class ReadAction {
        // Text file line indexing starts from 1
        protected var lineCnt: Int = 1
            private set

        @Throws(BuildEncyclopediaDocException::class, IOException::class)
        protected abstract fun readLineImpl(line: String?)

        @Throws(BuildEncyclopediaDocException::class, IOException::class)
        fun readLine(line: String?) {
            readLineImpl(line)
            lineCnt++
        }
    }

    init {
        this.ruleClassProvider = ruleClassProvider
        this.javaSourceFilePath = javaSourceFilePath
        this.sourceUrl = sourceUrl
    }

    /**
     * Reads the attribute and rule documentation present in the file represented by
     * SourceFileReader.javaSourceFilePath. The rule doc variables are added to the rule
     * documentation (which therefore must be defined in the same file). The attribute docs are
     * stored in a different class member, so they need to be handled outside this method.
     */
    @Throws(BuildEncyclopediaDocException::class, IOException::class)
    fun readDocsFromComments() {
        val docMap: MutableMap<String?, RuleDocumentation?> = HashMap<String?, RuleDocumentation?>()
        val docVariables: MutableList<RuleDocumentationVariable> = LinkedList<RuleDocumentationVariable>()
        val docAttributes: com.google.common.collect.ListMultimap<String?, RuleDocumentationAttribute?> =
            com.google.common.collect.LinkedListMultimap.create<String?, RuleDocumentationAttribute?>()
        readTextFile(
            javaSourceFilePath,
            object : ReadAction() {
                private var inBlazeRuleDocs = false
                private var inBlazeRuleVarDocs = false
                private var inBlazeAttributeDocs = false
                private var inFamilySummary = false
                private var sb: java.lang.StringBuilder = java.lang.StringBuilder()
                private var ruleName: String? = null
                private var familySummary = ""
                private var ruleType: String? = null
                private var ruleFamily: String? = null
                private var variableName: String? = null
                private var attributeName: String? = null
                private var flags: com.google.common.collect.ImmutableSet<String?>? = null
                private var startLineCnt = 0

                @Throws(BuildEncyclopediaDocException::class)
                public override fun readLineImpl(line: String) {
                    // TODO(bazel-team): check if copy paste code can be reduced using inner classes
                    if (inBlazeRuleDocs) {
                        if (DocgenConsts.BLAZE_RULE_END.matcher(line).matches()) {
                            endBlazeRuleDoc(docMap)
                        } else {
                            appendLine(line)
                        }
                    } else if (inBlazeRuleVarDocs) {
                        if (DocgenConsts.BLAZE_RULE_VAR_END.matcher(line).matches()) {
                            endBlazeRuleVarDoc(docVariables)
                        } else {
                            appendLine(line)
                        }
                    } else if (inBlazeAttributeDocs) {
                        if (DocgenConsts.BLAZE_RULE_ATTR_END.matcher(line).matches()) {
                            endBlazeAttributeDoc(docAttributes)
                        } else {
                            appendLine(line)
                        }
                    } else if (inFamilySummary) {
                        if (DocgenConsts.FAMILY_SUMMARY_END.matcher(line).matches()) {
                            endFamilySummary()
                        } else {
                            appendLine(line)
                        }
                    }
                    val familySummaryStartMatcher: java.util.regex.Matcher =
                        DocgenConsts.FAMILY_SUMMARY_START.matcher(line)
                    val ruleStartMatcher: java.util.regex.Matcher = DocgenConsts.BLAZE_RULE_START.matcher(line)
                    val ruleVarStartMatcher: java.util.regex.Matcher = DocgenConsts.BLAZE_RULE_VAR_START.matcher(line)
                    val ruleAttrStartMatcher: java.util.regex.Matcher = DocgenConsts.BLAZE_RULE_ATTR_START.matcher(line)
                    if (familySummaryStartMatcher.find()) {
                        startFamilySummary()
                    } else if (ruleStartMatcher.find()) {
                        startBlazeRuleDoc(line, ruleStartMatcher)
                    } else if (ruleVarStartMatcher.find()) {
                        startBlazeRuleVarDoc(ruleVarStartMatcher)
                    } else if (ruleAttrStartMatcher.find()) {
                        startBlazeAttributeDoc(line, ruleAttrStartMatcher)
                    }
                }

                fun appendLine(line: String) {
                    // Add another line of html code to the building rule documentation
                    // Removing whitespace and java comment asterisk from the beginning of the line
                    sb.append(line.replace("^[\\s]*\\*".toRegex(), "") + LS)
                }

                @Throws(BuildEncyclopediaDocException::class)
                fun startBlazeRuleDoc(line: String?, matcher: java.util.regex.Matcher) {
                    sb = java.lang.StringBuilder()
                    checkDocValidity()
                    // Start of a new rule.
                    // e.g.: matcher.group(1) = "NAME = cc_binary, TYPE = BINARY, FAMILY = C / C++"
                    for (group in com.google.common.base.Splitter.on(",").split(matcher.group(1))) {
                        val parts: MutableList<String?> =
                            com.google.common.base.Splitter.on("=").limit(2).splitToList(group)
                        var good = false
                        if (parts.size == 2) {
                            val key: String = parts.get(0).trim { it <= ' ' }
                            val value: String = parts.get(1).trim { it <= ' ' }
                            good = true
                            if (DocgenConsts.META_KEY_NAME == key) {
                                ruleName = value
                            } else if (DocgenConsts.META_KEY_TYPE == key) {
                                ruleType = value
                            } else if (DocgenConsts.META_KEY_FAMILY == key) {
                                ruleFamily = value
                            } else {
                                good = false
                            }
                        }
                        if (!good) {
                            java.lang.System.err.printf(
                                "WARNING: bad rule definition in line %d: '%s'", this.lineCnt, line
                            )
                        }
                    }

                    startLineCnt = this.lineCnt
                    addFlags(line)
                    inBlazeRuleDocs = true
                }

                fun startFamilySummary() {
                    sb = java.lang.StringBuilder()
                    inFamilySummary = true
                }

                fun endFamilySummary() {
                    familySummary = sb.toString()
                }

                @Throws(BuildEncyclopediaDocException::class)
                fun endBlazeRuleDoc(documentations: MutableMap<String?, RuleDocumentation?>) {
                    // End of a rule, create RuleDocumentation object
                    documentations.put(
                        ruleName,
                        RuleDocumentation(
                            ruleName,
                            ruleType,
                            ruleFamily,
                            sb.toString(),
                            javaSourceFilePath,
                            this.lineCnt,
                            sourceUrl,
                            flags,
                            familySummary
                        )
                    )
                    sb = java.lang.StringBuilder()
                    inBlazeRuleDocs = false
                }

                @Throws(BuildEncyclopediaDocException::class)
                fun startBlazeRuleVarDoc(matcher: java.util.regex.Matcher) {
                    checkDocValidity()
                    // Start of a new rule variable
                    ruleName = matcher.group(1).replace("[\\s]".toRegex(), "")
                    variableName = matcher.group(2).replace("[\\s]".toRegex(), "")
                    startLineCnt = this.lineCnt
                    inBlazeRuleVarDocs = true
                }

                fun endBlazeRuleVarDoc(docVariables: MutableList<RuleDocumentationVariable>) {
                    // End of a rule, create RuleDocumentationVariable object
                    docVariables.add(
                        RuleDocumentationVariable(ruleName, variableName, sb.toString(), startLineCnt)
                    )
                    sb = java.lang.StringBuilder()
                    inBlazeRuleVarDocs = false
                }

                @Throws(BuildEncyclopediaDocException::class)
                fun startBlazeAttributeDoc(line: String?, matcher: java.util.regex.Matcher) {
                    checkDocValidity()
                    // Start of a new attribute
                    ruleName = matcher.group(1).replace("[\\s]".toRegex(), "")
                    attributeName = matcher.group(2).replace("[\\s]".toRegex(), "")
                    startLineCnt = this.lineCnt
                    addFlags(line)
                    inBlazeAttributeDocs = true
                }

                fun endBlazeAttributeDoc(
                    docAttributes: com.google.common.collect.ListMultimap<String?, RuleDocumentationAttribute?>
                ) {
                    // End of a attribute, create RuleDocumentationAttribute object
                    docAttributes.put(
                        attributeName,
                        RuleDocumentationAttribute.Companion.create(
                            ruleClassProvider.getRuleClassDefinition(ruleName).javaClass,
                            attributeName,
                            sb.toString(),
                            javaSourceFilePath,
                            startLineCnt,
                            flags
                        )
                    )
                    sb = java.lang.StringBuilder()
                    inBlazeAttributeDocs = false
                }

                fun addFlags(line: String?) {
                    // Add flags if there's any
                    val matcher: java.util.regex.Matcher = DocgenConsts.BLAZE_RULE_FLAGS.matcher(line)
                    if (matcher.find()) {
                        flags = com.google.common.collect.ImmutableSet.copyOf<String?>(
                            matcher.group(1).split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                        )
                    } else {
                        flags = com.google.common.collect.ImmutableSet.of<String?>()
                    }
                }

                @Throws(BuildEncyclopediaDocException::class)
                fun checkDocValidity() {
                    if (inBlazeRuleDocs || inBlazeRuleVarDocs || inBlazeAttributeDocs) {
                        throw BuildEncyclopediaDocException(
                            javaSourceFilePath,
                            this.lineCnt,
                            "Malformed documentation, #BLAZE_RULE started after another #BLAZE_RULE."
                        )
                    }
                }
            })

        // Adding rule doc variables to the corresponding rules
        for (docVariable in docVariables) {
            if (docMap.containsKey(docVariable.getRuleName())) {
                docMap.get(docVariable.getRuleName()).addDocVariable(
                    docVariable.getVariableName(), docVariable.getValue()
                )
            } else {
                throw BuildEncyclopediaDocException(
                    javaSourceFilePath, docVariable.getStartLineCnt(),
                    String.format(
                        "Malformed rule variable #BLAZE_RULE(%s).%s, rule %s not found in file.",
                        docVariable.getRuleName(), docVariable.getVariableName(),
                        docVariable.getRuleName()
                    )
                )
            }
        }
        ruleDocEntries = docMap.values
        attributeDocEntries = docAttributes
    }

    fun getRuleDocEntries(): MutableCollection<RuleDocumentation?>? {
        return ruleDocEntries
    }

    fun getAttributeDocEntries(): com.google.common.collect.ListMultimap<String?, RuleDocumentationAttribute?>? {
        return attributeDocEntries
    }

    companion object {
        private val LS: String = DocgenConsts.LS

        /**
         * Reads a template file and substitutes variables of the format ${FOO}.
         * 
         * @param variables keys are the possible variable names, e.g. "FOO", values are the substitutions
         * (can be null)
         */
        /**
         * Reads the template file without variable substitution.
         */
        @kotlin.jvm.JvmOverloads
        @Throws(BuildEncyclopediaDocException::class, IOException::class)
        fun readTemplateContents(
            templateFilePath: String,
            variables: MutableMap<String?, String?>? = null
        ): String {
            val sb: java.lang.StringBuilder = java.lang.StringBuilder()
            readTextFile(templateFilePath, object : ReadAction() {
                public override fun readLineImpl(line: String) {
                    sb.append(expandVariables(line, variables)).append(LS)
                }
            })
            return sb.toString()
        }

        private fun expandVariables(line: String, variables: MutableMap<String?, String?>?): String {
            var line = line
            if (variables == null || line.indexOf("\${") == -1) {
                return line
            }

            for (variable in variables.entries) {
                line = line.replace("\${" + variable.key + "}", variable.value)
            }
            return line
        }

        @Throws(IOException::class)
        private fun createReader(filePath: String): BufferedReader? {
            val file: java.io.File = java.io.File(filePath)
            if (file.exists()) {
                return java.nio.file.Files.newBufferedReader(file.toPath(), java.nio.charset.StandardCharsets.UTF_8)
            } else {
                val `is`: java.io.InputStream? = SourceFileReader::class.java.getResourceAsStream(filePath)
                if (`is` != null) {
                    return BufferedReader(java.io.InputStreamReader(`is`, java.nio.charset.StandardCharsets.UTF_8))
                } else {
                    return null
                }
            }
        }

        @Throws(BuildEncyclopediaDocException::class, IOException::class)
        fun readTextFile(filePath: String, action: ReadAction) {
            createReader(filePath).use { br ->
                if (br != null) {
                    var line: String? = null
                    while ((br.readLine().also { line = it }) != null) {
                        action.readLine(line)
                    }
                } else {
                    println("Couldn't find file or resource: " + filePath)
                }
            }
        }
    }
}
