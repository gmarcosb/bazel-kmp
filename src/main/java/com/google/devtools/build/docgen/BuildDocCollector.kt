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

/**
 * Class that parses the documentation fragments of rule-classes and
 * generates the html format documentation.
 */
@com.google.common.annotations.VisibleForTesting
class BuildDocCollector(
    linkExpander: RuleLinkExpander,
    urlMapper: SourceUrlMapper,
    ruleClassProvider: ConfiguredRuleClassProvider
) {
    private val linkExpander: RuleLinkExpander
    private val urlMapper: SourceUrlMapper
    private val ruleClassProvider: ConfiguredRuleClassProvider

    init {
        this.linkExpander = linkExpander
        this.urlMapper = urlMapper
        this.ruleClassProvider = ruleClassProvider
    }

    /**
     * Creates a map of rule names (keys) to rule documentation (values).
     * 
     * 
     * This method crawls the specified input directories for rule class definitions (as Java
     * source files) which contain the rules' and attributes' definitions as comments in a specific
     * format. The keys in the returned Map correspond to these rule classes.
     * 
     * 
     * In the Map's values, all references pointing to other rules, rule attributes, and general
     * documentation (e.g. common definitions, make variables, etc.) are expanded into hyperlinks. The
     * links generated follow either the multi-page or single-page Build Encyclopedia model depending
     * on the mode set for the [RuleLinkExpander] that was passed to the constructor.
     * 
     * @param inputJavaDirs list of directories to scan for documentation in Java source code
     * @param buildEncyclopediaStardocProtos list of file paths of stardoc_output.ModuleInfo binary
     * proto files generated from Build Encyclopedia entry point .bzl files; documentation from
     * these protos takes precedence over documentation from `inputJavaDirs`
     * @param denyList specify an optional denylist file that list some rules that should not be
     * listed in the output.
     * @throws BuildEncyclopediaDocException
     * @throws IOException
     * @return Map of rule class to rule documentation.
     */
    @Throws(BuildEncyclopediaDocException::class, IOException::class)
    fun collect(
        inputJavaDirs: MutableList<String>, buildEncyclopediaStardocProtos: MutableList<String?>, denyList: String?
    ): MutableMap<String?, RuleDocumentation> {
        // Read the denyList file
        val denylistedRules = readDenyList(denyList)
        // RuleDocumentations are generated in order (based on rule type then alphabetically).
        // The ordering is also used to determine in which rule doc the common attribute docs are
        // generated (they are generated at the first appearance).
        val ruleDocEntries: MutableMap<String?, RuleDocumentation> = TreeMap<String?, RuleDocumentation>()
        // RuleDocumentationAttribute objects equal based on attributeName so they have to be
        // collected in a List instead of a Set.
        val attributeDocEntries: com.google.common.collect.ListMultimap<String?, RuleDocumentationAttribute?> =
            com.google.common.collect.LinkedListMultimap.create<String?, RuleDocumentationAttribute?>()

        // Map of rule name to the file (Java source file or Build Encyclopedia entry point .bzl file)
        // and symbol from which its documentation was obtained.
        val ruleDocOrigin: MutableMap<String?, DocumentationOrigin?> = HashMap<String?, DocumentationOrigin?>()

        // Set of files already processed. The same file may be encountered multiple times because
        // directories are processed recursively, and an input directory may be a subdirectory of
        // another one.
        val processedFiles: MutableSet<java.io.File?> = HashSet<java.io.File?>()

        for (inputJavaDir in inputJavaDirs) {
            logger.atFine().log("Processing input directory: %s", inputJavaDir)
            val ruleNum = ruleDocEntries.size
            collectJavaSourceDocs(
                processedFiles,
                ruleDocOrigin,
                ruleDocEntries,
                denylistedRules,
                attributeDocEntries,
                java.io.File(inputJavaDir)
            )
            logger.atFine().log(
                "%d rule documentations found in %s", ruleDocEntries.size - ruleNum, inputJavaDir
            )
        }
        processJavaSourceRuleAttributeDocs(ruleDocEntries.values, attributeDocEntries)

        for (stardocProtoPath in buildEncyclopediaStardocProtos) {
            logger.atFine().log("Processing input file: %s", stardocProtoPath)
            val numRulesCollected =
                collectModuleInfoDocs(
                    ruleDocOrigin,
                    ruleDocEntries,
                    denylistedRules,
                    attributeDocEntries,
                    ModuleInfo.parseFrom(
                        FileInputStream(stardocProtoPath), ExtensionRegistry.getEmptyRegistry()
                    ),
                    urlMapper
                )
            logger.atFine().log(
                "%d rule documentations found in %s", numRulesCollected, stardocProtoPath
            )
        }

        linkExpander.addIndex(buildRuleIndex(ruleDocEntries.values))
        for (rule in ruleDocEntries.values) {
            rule.setRuleLinkExpander(linkExpander)
        }
        return ruleDocEntries
    }

    /**
     * Generates an index mapping rule name to its normalized rule family name.
     */
    private fun buildRuleIndex(rules: Iterable<RuleDocumentation>): MutableMap<String?, String?> {
        val index: MutableMap<String?, String?> = HashMap<String?, String?>()
        for (rule in rules) {
            index.put(rule.getRuleName(), RuleFamily.Companion.normalize(rule.getRuleFamily()))
        }
        return index
    }

    /**
     * Go through all attributes of native rules whose documentation was retrieved from Java sources,
     * and search the best attribute documentation if exists. The best documentation is the closest
     * documentation in the ancestor graph. E.g. if java_library.deps documented in $rule and
     * $java_rule then the one in $java_rule is going to apply since it's a closer ancestor of
     * java_library.
     * 
     * 
     * Note: this function should be called before any calls to collectModuleInfoDocs.
     */
    @Throws(BuildEncyclopediaDocException::class)
    private fun processJavaSourceRuleAttributeDocs(
        ruleDocEntries: Iterable<RuleDocumentation>,
        attributeDocEntries: com.google.common.collect.ListMultimap<String?, RuleDocumentationAttribute?>
    ) {
        for (ruleDoc in ruleDocEntries) {
            val ruleClass: RuleClass? = ruleClassProvider.getRuleClassMap().get(ruleDoc.getRuleName())
            if (ruleClass != null) {
                if (ruleClass.isDocumented()) {
                    val ruleDefinition: java.lang.Class<out RuleDefinition?> =
                        ruleClassProvider.getRuleClassDefinition(ruleDoc.getRuleName()).javaClass
                    for (attribute in ruleClass.getAttributeProvider().getAttributes()) {
                        if (!attribute.isDocumented()) {
                            continue
                        }
                        val attrName: String? = attribute.getName()
                        val attributeDocList: MutableList<RuleDocumentationAttribute> =
                            attributeDocEntries.get(attrName)
                        if (attributeDocList != null) {
                            // There are attribute docs for this attribute.
                            // Search the closest one in the ancestor graph.
                            // Note that there can be only one 'closest' attribute since we forbid multiple
                            // inheritance of the same attribute in RuleClass.
                            var minLevel = Int.Companion.MAX_VALUE
                            var bestAttributeDoc: RuleDocumentationAttribute? = null
                            for (attributeDoc in attributeDocList) {
                                val level: Int = attributeDoc.getDefinitionClassAncestryLevel(
                                    ruleDefinition,
                                    ruleClassProvider
                                )
                                if (level >= 0 && level < minLevel) {
                                    bestAttributeDoc = attributeDoc
                                    minLevel = level
                                }
                            }
                            if (bestAttributeDoc != null) {
                                // We have to copy the matching RuleDocumentationAttribute here so that we don't
                                // overwrite the reference to the actual attribute later by another attribute with
                                // the same ancestor but different default values.
                                ruleDoc.addAttribute(bestAttributeDoc.copyAndUpdateFrom(attribute))
                                // If there is no matching attribute doc try to add the common.
                            } else if (ruleDoc.getRuleType() == com.google.devtools.build.docgen.DocgenConsts.RuleType.BINARY
                                && PredefinedAttributes.BINARY_ATTRIBUTES.containsKey(attrName)
                            ) {
                                ruleDoc.addAttribute(PredefinedAttributes.BINARY_ATTRIBUTES.get(attrName))
                            } else if (ruleDoc.getRuleType() == com.google.devtools.build.docgen.DocgenConsts.RuleType.TEST
                                && PredefinedAttributes.TEST_ATTRIBUTES.containsKey(attrName)
                            ) {
                                ruleDoc.addAttribute(PredefinedAttributes.TEST_ATTRIBUTES.get(attrName))
                            } else if (PredefinedAttributes.COMMON_ATTRIBUTES.containsKey(attrName)) {
                                ruleDoc.addAttribute(PredefinedAttributes.COMMON_ATTRIBUTES.get(attrName))
                            } else if (PredefinedAttributes.TYPICAL_ATTRIBUTES.containsKey(attrName)) {
                                ruleDoc.addAttribute(PredefinedAttributes.TYPICAL_ATTRIBUTES.get(attrName))
                            }
                        }
                    }
                }
            } else {
                throw ruleDoc.createException("Can't find RuleClass for " + ruleDoc.getRuleName())
            }
        }
    }

    /**
     * Crawls the specified inputPath and collects the raw rule and rule attribute documentation.
     * 
     * 
     * This method crawls the specified input directory (recursively calling itself for all
     * subdirectories) and reads each Java source file using [SourceFileReader] to extract the
     * raw rule and attribute documentation embedded in comments in a specific format. The extracted
     * documentation is then further processed, such as by [ ][BuildDocCollector.collect], in order to
     * associate each rule's documentation with its attribute documentation.
     * 
     * 
     * This method returns the following through its parameters: the set of Java source files
     * processed, a map of rule name to the source file it was extracted from, a map of rule name to
     * the documentation to the rule, and a multimap of attribute name to attribute documentation.
     * 
     * @param processedFiles The set of Java source files files that have already been processed in
     * order to avoid reprocessing the same file.
     * @param ruleDocOrigin Map of rule name to the file and symbol from which its documentation was
     * obtained.
     * @param ruleDocEntries Map of rule name to rule documentation.
     * @param denyList The set of denylisted rules whose documentation should not be extracted.
     * @param attributeDocEntries Multimap of rule attribute name to attribute documentation.
     * @param inputPath The File representing the Java source file or directory to read.
     * @throws BuildEncyclopediaDocException
     * @throws IOException
     */
    @Throws(BuildEncyclopediaDocException::class, IOException::class)
    fun collectJavaSourceDocs(
        processedFiles: MutableSet<java.io.File?>,
        ruleDocOrigin: MutableMap<String?, DocumentationOrigin?>,
        ruleDocEntries: MutableMap<String?, RuleDocumentation>,
        denyList: MutableSet<String?>,
        attributeDocEntries: com.google.common.collect.ListMultimap<String?, RuleDocumentationAttribute?>?,
        inputPath: java.io.File
    ) {
        if (processedFiles.contains(inputPath)) {
            return
        }

        if (inputPath.isFile()) {
            if (DocgenConsts.JAVA_SOURCE_FILE_SUFFIX.apply(inputPath.getName())) {
                val sfr: SourceFileReader =
                    SourceFileReader(
                        ruleClassProvider, inputPath.getAbsolutePath(), urlMapper.urlOfFile(inputPath)
                    )
                sfr.readDocsFromComments()
                for (d in sfr.getRuleDocEntries()) {
                    val ruleName: String? = d.getRuleName()
                    if (!denyList.contains(ruleName)) {
                        if (ruleDocEntries.containsKey(ruleName)
                            && ruleDocOrigin.get(ruleName)!!.file != inputPath.toString()
                        ) {
                            logger.atWarning().log(
                                "Rule '%s' from '%s' overrides previously seen rule '%s' from '%s'",
                                ruleName,
                                inputPath,
                                ruleDocOrigin.get(ruleName)!!.symbol,
                                ruleDocOrigin.get(ruleName)!!.file
                            )
                        }
                        ruleDocOrigin.put(
                            ruleName,
                            DocumentationOrigin.Companion.create(inputPath.toString(), ruleName)
                        )
                        ruleDocEntries.put(ruleName, d)
                    }
                }
                if (attributeDocEntries != null) {
                    // Collect all attribute documentations from this file.
                    attributeDocEntries.putAll(sfr.getAttributeDocEntries())
                }
            }
        } else if (inputPath.isDirectory()) {
            for (childPath in inputPath.listFiles()) {
                collectJavaSourceDocs(
                    processedFiles,
                    ruleDocOrigin,
                    ruleDocEntries,
                    denyList,
                    attributeDocEntries,
                    childPath
                )
            }
        }

        processedFiles.add(inputPath)
    }

    /** The file and symbol from which documentation was obtained.  */
    @kotlin.jvm.JvmRecord
    internal data class DocumentationOrigin(val file: String?, val symbol: String?) {
        init {
            java.util.Objects.requireNonNull<String?>(file, "file")
            java.util.Objects.requireNonNull<String?>(symbol, "symbol")
        }

        companion object {
            fun create(file: String?, symbol: String?): DocumentationOrigin {
                return DocumentationOrigin(file, symbol)
            }
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private val SHARP_SPLITTER: com.google.common.base.Splitter =
            com.google.common.base.Splitter.on('#').limit(2).trimResults()

        /**
         * Parse the file containing rules blocked from documentation. The list is simply a list of rules
         * separated by new lines. Line comments can be added to the file by starting them with #.
         * 
         * @param denyList The name of the file containing the denylist.
         * @return The set of denylisted rules.
         * @throws IOException
         */
        @com.google.common.annotations.VisibleForTesting
        @Throws(IOException::class)
        fun readDenyList(denyList: String?): MutableSet<String?> {
            val result: MutableSet<String?> = HashSet<String?>()
            if (denyList != null && !denyList.isEmpty()) {
                val file: java.io.File = java.io.File(denyList)
                java.nio.file.Files.newBufferedReader(file.toPath(), java.nio.charset.StandardCharsets.UTF_8)
                    .use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            val rule: String = SHARP_SPLITTER.split(line).iterator().next()
                            if (!rule.isEmpty()) {
                                result.add(rule)
                            }
                            line = reader.readLine()
                        }
                    }
            }
            return result
        }

        /**
         * Collects rule and rule attribute documentation from a stardoc_output.ModuleInfo message
         * generated from a Build Encyclopedia entry point .bzl file.
         * 
         * 
         * The module doc string for the .bzl file is interpreted as the rule family name.
         * 
         * 
         * Any rule exported by the .bzl file is expected to be contained in a struct whose name is a
         * [DocgenConsts.RuleType] name suffixed with "_rules" - for example, "binary_rules",
         * "library_rules", etc.
         * 
         * 
         * This method returns the following through its parameters: a map of rule name to the file and
         * symbol it was extracted from, a map of rule name to the documentation of the rule, and a
         * multimap of attribute name to attribute documentation.
         * 
         * @param ruleDocOrigin Map of rule name to the file and symbol from which its documentation was
         * obtained.
         * @param ruleDocEntries Map of rule name to rule documentation.
         * @param denyList The set of denylisted rules whose documentation should not be extracted.
         * @param attributeDocEntries Multimap of rule attribute name to attribute documentation.
         * @param moduleInfo A stardoc_output.ModuleInfo message representing a Build Encyclopedia entry
         * point .bzl file.
         * @param urlMapper Mapper from source labels to source code repository URLs
         * @return number of rules whose documentation was collected
         */
        @com.google.common.annotations.VisibleForTesting
        @Throws(BuildEncyclopediaDocException::class)
        fun collectModuleInfoDocs(
            ruleDocOrigin: MutableMap<String?, DocumentationOrigin?>,
            ruleDocEntries: MutableMap<String?, RuleDocumentation>,
            denyList: MutableSet<String?>,
            attributeDocEntries: com.google.common.collect.ListMultimap<String?, RuleDocumentationAttribute?>?,
            moduleInfo: ModuleInfo,
            urlMapper: SourceUrlMapper
        ): Int {
            val entryPointFileLabel: String? = moduleInfo.getFile()

            val familyMatcher: java.util.regex.Matcher =
                DocgenConsts.STARDOC_OUTPUT_FAMILY_NAME_AND_SUMMARY.matcher(
                    moduleInfo.getModuleDocstring().strip()
                )
            if (!familyMatcher.matches()) {
                throw BuildEncyclopediaDocException(
                    entryPointFileLabel,
                    ("Module doc string is expected to be a single line representing a rule family name, "
                            + "optionally followed by a blank line and summary text; for example, "
                            + "`\"\"\"C / C++\"\"\"`")
                )
            }
            val ruleFamily: String? = familyMatcher.group("family")
            val ruleFamilySummary: String = com.google.common.base.Strings.nullToEmpty(familyMatcher.group("summary"))

            var numRulesCollected = 0
            for (ruleInfo in moduleInfo.getRuleInfoList()) {
                val ruleNameMatcher: java.util.regex.Matcher =
                    DocgenConsts.STARDOC_OUTPUT_RULE_NAME.matcher(ruleInfo.getRuleName())
                if (!ruleNameMatcher.matches()) {
                    throw BuildEncyclopediaDocException(
                        entryPointFileLabel,
                        java.lang.String.format(
                            ("Unexpected rule symbol: %s; rules must be exported in structs, with the struct's"
                                    + " name specifying the rule type, for example, `library_rules = struct("
                                    + "java_import = _java_import, ...)`"),
                            ruleInfo.getRuleName()
                        )
                    )
                }
                val ruleType: String = com.google.common.base.Ascii.toUpperCase(ruleNameMatcher.group("type"))
                val ruleName: String? = ruleNameMatcher.group("name")
                if (!denyList.contains(ruleName)) {
                    val ruleOriginFileLabel: String? = ruleInfo.getOriginKey().getFile()
                    if (ruleDocEntries.containsKey(ruleName)) {
                        logger.atWarning().log(
                            "Rule '%s' from '%s' (defined in '%s') overrides previously seen rule '%s' from '%s'",
                            ruleInfo.getRuleName(),
                            entryPointFileLabel,
                            ruleOriginFileLabel,
                            ruleDocOrigin.get(ruleName)!!.symbol,
                            ruleDocOrigin.get(ruleName)!!.file
                        )
                    }
                    val flags: com.google.common.collect.ImmutableSet.Builder<String?> =
                        com.google.common.collect.ImmutableSet.builder<String?>()
                    if (ruleType == DocgenConsts.STARLARK_GENERIC_RULE_TYPE) {
                        // Note that if FLAG_GENERIC_RULE is set, RuleDocumentation constructor will set the rule
                        // type to OTHER.
                        flags.add(DocgenConsts.FLAG_GENERIC_RULE)
                    }
                    val ruleDoc: RuleDocumentation =
                        RuleDocumentation(
                            ruleName,
                            ruleType,
                            ruleFamily,
                            ruleInfo.getDocString(),
                            ruleOriginFileLabel,
                            urlMapper.urlOfLabel(ruleOriginFileLabel),
                            flags.build(),  // Add family summary only to the first rule encountered, to avoid duplication in
                            // final rendered output
                            if (numRulesCollected == 0) ruleFamilySummary else ""
                        )

                    // Inject standard inherited attributes for Starlark rules (since they always inherit from
                    // one of 3 possible base rule classes; see StarlarkRuleClassFunctions#createRule). If in
                    // the future we want to document native rules via ModuleInfo protos, we will need to list
                    // inherited attributes in the proto.
                    ruleDoc.addAttributes(PredefinedAttributes.COMMON_ATTRIBUTES.values)
                    if (ruleDoc.getRuleType() == com.google.devtools.build.docgen.DocgenConsts.RuleType.TEST) {
                        ruleDoc.addAttributes(PredefinedAttributes.TEST_ATTRIBUTES.values)
                    } else if (ruleDoc.getRuleType() == com.google.devtools.build.docgen.DocgenConsts.RuleType.BINARY) {
                        ruleDoc.addAttributes(PredefinedAttributes.BINARY_ATTRIBUTES.values)
                    }

                    for (attributeInfo in ruleInfo.getAttributeList()) {
                        val attributeName: String = attributeInfo.getName()
                        if (attributeName == "name") {
                            // We do not want the implicit "name" attribute injected into proto output by
                            // starlark_doc_extract because we inject "name" at the template level in
                            // templates/be/rules.vm
                            continue
                        }
                        if (attributeInfo.getDocString().isEmpty()
                            && PredefinedAttributes.TYPICAL_ATTRIBUTES.containsKey(attributeName)
                        ) {
                            // We link empty-docstring attributes to the common table based purely on attribute name
                            // (same as processJavaSourceRuleAttributeDocs does for native rule attributes).
                            // TODO(arostovtsev): should we verify attribute type and default value too? That would
                            // require moving the definition of common attributes from a free-text velocity template
                            // to a structured format.
                            ruleDoc.addAttribute(PredefinedAttributes.TYPICAL_ATTRIBUTES.get(attributeName))
                        } else {
                            val deprecated: Boolean =
                                DocgenConsts.STARDOC_OUTPUT_DEPRECATED_DOCSTRING
                                    .matcher(attributeInfo.getDocString())
                                    .find()
                            ruleDoc.addAttribute(
                                RuleDocumentationAttribute.Companion.createFromAttributeInfo(
                                    attributeInfo,
                                    ruleOriginFileLabel,
                                    if (deprecated)
                                        com.google.common.collect.ImmutableSet.of<String?>(DocgenConsts.FLAG_DEPRECATED)
                                    else
                                        com.google.common.collect.ImmutableSet.of<String?>()
                                )
                            )
                        }
                    }

                    ruleDocOrigin.put(
                        ruleName, DocumentationOrigin.Companion.create(entryPointFileLabel, ruleInfo.getRuleName())
                    )
                    ruleDocEntries.put(ruleName, ruleDoc)
                    numRulesCollected++
                }
            }
            return numRulesCollected
        }
    }
}
