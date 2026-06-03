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

import com.google.devtools.build.lib.packages.Attribute

/**
 * A class storing a rule attribute documentation along with some meta information. For native
 * attributes, the class provides functionality to compute the ancestry level of this attribute's
 * generator rule definition class compared to other rule definition classes.
 * 
 * 
 * Warning, two RuleDocumentationAttribute objects are equal based on only the attributeName.
 */
class RuleDocumentationAttribute
private constructor(
    definitionClass: java.lang.Class<out RuleDefinition?>?,
    attributeName: String,
    htmlDocumentation: String?,
    location: String?,
    flags: MutableSet<String?>,
    commonType: String?,
    type: Type<*>?,
    defaultValue: String?,
    mandatory: Boolean,
    nonconfigurable: Boolean
) : Comparable<RuleDocumentationAttribute?>, Cloneable {
    private val definitionClass: java.lang.Class<out RuleDefinition?>?

    /**
     * Returns the name of the rule attribute.
     */
    val attributeName: String
    private val htmlDocumentation: String?
    private val commonType: String?

    // Used to expand rule link references in the attribute documentation.
    private var linkExpander: RuleLinkExpander? = null

    /**
     * Returns the file name or label, optionally with a line number, where the rule attribute is
     * defined.
     */
    val location: String? // for error messages
    private val flags: MutableSet<String?>

    // The following are not set by create() or createCommon()
    private val type: Type<*>?
    private val defaultValue: String?

    /** Returns whether the param is required or optional.  */
    val isMandatory: Boolean
    private val nonconfigurable: Boolean

    /**
     * Copies this RuleDocumentationAttribute and sets additional metadata (type, default value, and
     * whether the attribute is mandatory or nonconfigurable) from a native attribute object.
     */
    fun copyAndUpdateFrom(attribute: Attribute): RuleDocumentationAttribute {
        return RuleDocumentationAttribute(
            this.definitionClass,
            this.attributeName,
            this.htmlDocumentation,
            this.location,
            this.flags,
            this.commonType,
            attribute.getType(),
            reprDefaultValue(attribute),
            attribute.isMandatory(),
            !attribute.isConfigurable()
        )
    }

    init {
        com.google.common.base.Preconditions.checkNotNull<String?>(attributeName, "AttributeName must not be null.")
        this.definitionClass = definitionClass
        this.attributeName = attributeName
        this.htmlDocumentation = htmlDocumentation
        this.location = location
        this.flags = flags
        this.commonType = commonType
        this.type = type
        this.defaultValue = defaultValue
        this.isMandatory = mandatory
        this.nonconfigurable = nonconfigurable
    }

    /** Returns the attribute's default value, or null if none set.  */
    fun getDefaultValue(): String? {
        // Strings are stored as "foo". Remove the surrounding quotes.
        return if (defaultValue == null) null else defaultValue.substring(1, defaultValue.length - 1)
    }

    val isDeprecated: Boolean
        /**
         * Returns whether this attribute is marked as deprecated.
         */
        get() = hasFlag(DocgenConsts.FLAG_DEPRECATED)

    /**
     * Sets the [RuleLinkExpander] to be used to expand links in the HTML documentation.
     */
    fun setRuleLinkExpander(linkExpander: RuleLinkExpander?) {
        this.linkExpander = linkExpander
    }

    /**
     * Returns the html documentation of the rule attribute.
     */
    @Throws(BuildEncyclopediaDocException::class)
    fun getHtmlDocumentation(): String? {
        return tryExpand(htmlDocumentation)
    }

    @Throws(BuildEncyclopediaDocException::class)
    fun tryExpand(html: String?): String? {
        if (linkExpander == null) {
            return html
        }
        try {
            return linkExpander.expand(html)
        } catch (e: java.lang.IllegalArgumentException) {
            throw BuildEncyclopediaDocException(location, e.message)
        }
    }

    @get:Throws(BuildEncyclopediaDocException::class)
    val synopsis: String
        /** Returns a string containing the synopsis for this attribute.  */
        get() {
            if (type == null) {
                return ""
            }
            val rawType: String? = TYPE_DESC.get(type)
            val sb: java.lang.StringBuilder =
                java.lang.StringBuilder()
                    .append(if (rawType == null) null else tryExpand(rawType))
                    .append(
                        if (nonconfigurable) String.format(
                            "; <a href=\"%s#configurable-attributes\">nonconfigurable</a>",
                            RuleDocumentation.Companion.COMMON_DEFINITIONS_PAGE
                        ) else
                            ""
                    )
            if (this.isMandatory) {
                sb.append("; required")
            } else if (defaultValue != null && !defaultValue.isEmpty()) {
                sb.append("; default is <code>").append(defaultValue).append("</code>")
            } else {
                // Computed default or other non-representable value
                sb.append("; optional")
            }
            return sb.toString()
        }

    /**
     * Returns true if the attribute doc is of a common attribute type.
     */
    fun isCommonType(): Boolean {
        return commonType != null
    }

    /**
     * Returns the common attribute type if this attribute doc is of a common type
     * otherwise actualRule.
     */
    fun getGeneratedInRule(actualRule: String?): String? {
        return if (isCommonType()) commonType else actualRule
    }

    /**
     * Returns true if this attribute documentation has the parameter flag.
     */
    fun hasFlag(flag: String?): Boolean {
        return flags.contains(flag)
    }

    /**
     * Returns the length of a shortest path from usingClass to the definitionClass of this
     * RuleDocumentationAttribute in the rule definition ancestry graph. Returns -1
     * if definitionClass is not the ancestor (transitively) of usingClass.
     */
    fun getDefinitionClassAncestryLevel(
        usingClass: java.lang.Class<out RuleDefinition?>,
        ruleClassProvider: ConfiguredRuleClassProvider?
    ): Int {
        if (usingClass == definitionClass) {
            return 0
        }
        // Storing nodes (rule class definitions) with the length of the shortest path from usingClass
        val visited: MutableMap<java.lang.Class<out RuleDefinition?>?, Int?> =
            HashMap<java.lang.Class<out RuleDefinition?>?, Int?>()
        val toVisit: LinkedList<java.lang.Class<out RuleDefinition?>?> =
            LinkedList<java.lang.Class<out RuleDefinition?>?>()
        visited.put(usingClass, 0)
        toVisit.add(usingClass)
        // Searching the shortest path from usingClass to this.definitionClass using BFS
        do {
            val ancestor: java.lang.Class<out RuleDefinition> = toVisit.removeFirst()
            visitAncestor(ancestor, visited, toVisit, ruleClassProvider)
            if (ancestor == definitionClass) {
                return visited.get(ancestor)!!
            }
        } while (!toVisit.isEmpty())
        return -1
    }

    private fun visitAncestor(
        usingClass: java.lang.Class<out RuleDefinition>,
        visited: MutableMap<java.lang.Class<out RuleDefinition?>?, Int?>,
        toVisit: LinkedList<java.lang.Class<out RuleDefinition?>?>,
        ruleClassProvider: ConfiguredRuleClassProvider?
    ) {
        val instance: RuleDefinition = getRuleDefinition(usingClass, ruleClassProvider)
        for (ancestor in instance.getMetadata().ancestors) {
            if (!visited.containsKey(ancestor)) {
                toVisit.addLast(ancestor)
                visited.put(ancestor, visited.get(usingClass)!! + 1)
            }
        }
    }

    private fun getRuleDefinition(
        usingClass: java.lang.Class<out RuleDefinition>,
        ruleClassProvider: ConfiguredRuleClassProvider?
    ): RuleDefinition {
        if (ruleClassProvider == null) {
            try {
                return usingClass.getConstructor().newInstance()
            } catch (e: java.lang.ReflectiveOperationException) {
                throw java.lang.IllegalStateException(e)
            } catch (e: java.lang.IllegalArgumentException) {
                throw java.lang.IllegalStateException(e)
            }
        }
        return ruleClassProvider.getRuleClassDefinition(usingClass.getName())
    }

    private fun getAttributeOrderingPriority(attribute: RuleDocumentationAttribute): Int {
        if (DocgenConsts.ATTRIBUTE_ORDERING.containsKey(attribute.attributeName)) {
            return DocgenConsts.ATTRIBUTE_ORDERING.get(attribute.attributeName)
        } else {
            return 0
        }
    }

    override fun compareTo(o: RuleDocumentationAttribute): Int {
        val thisPriority = getAttributeOrderingPriority(this)
        val otherPriority = getAttributeOrderingPriority(o)
        if (thisPriority > otherPriority) {
            return 1
        } else if (thisPriority < otherPriority) {
            return -1
        } else {
            return this.attributeName.compareTo(o.attributeName)
        }
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is RuleDocumentationAttribute) {
            return false
        }
        return attributeName == obj.attributeName
    }

    override fun hashCode(): Int {
        return attributeName.hashCode()
    }

    companion object {
        private val TYPE_DESC: com.google.common.collect.ImmutableMap<Type<*>?, String?> =
            com.google.common.collect.ImmutableMap.builder<Type<*>?, String?>()
                .put(Type.BOOLEAN, "Boolean")
                .put(Type.INTEGER, "Integer")
                .put(Types.INTEGER_LIST, "List of integers")
                .put(Type.STRING, "String")
                .put(Types.STRING_DICT, "Dictionary: String -> String")
                .put(Types.STRING_LIST, "List of strings")
                .put(BuildType.TRISTATE, "Integer")
                .put(BuildType.LABEL, "<a href=\"\${link build-ref#labels}\">Label</a>")
                .put(
                    BuildType.LABEL_KEYED_STRING_DICT,
                    "Dictionary: <a href=\"\${link build-ref#labels}\">label</a> -> String"
                )
                .put(BuildType.LABEL_LIST, "List of <a href=\"\${link build-ref#labels}\">labels</a>")
                .put(
                    BuildType.GENQUERY_SCOPE_TYPE_LIST,
                    "List of <a href=\"\${link build-ref#labels}\">labels</a>"
                )
                .put(
                    BuildType.LABEL_DICT_UNARY,
                    "Dictionary mapping strings to <a href=\"\${link build-ref#labels}\">labels</a>"
                )
                .put(BuildType.LICENSE, "Licence type")
                .put(BuildType.NODEP_LABEL, "<a href=\"\${link build-ref#name}\">Name</a>")
                .put(BuildType.NODEP_LABEL_LIST, "List of <a href=\"\${link build-ref#name}\">names</a>")
                .put(BuildType.OUTPUT, "<a href=\"\${link build-ref#filename}\">Filename</a>")
                .put(
                    BuildType.OUTPUT_LIST, "List of <a href=\"\${link build-ref#filename}\">filenames</a>"
                )
                .buildOrThrow()

        /**
         * Creates a RuleDocumentationAttribute from comments in Java sources. Additional metadata may be
         * filled in later via [copyAndUpdateFrom].
         */
        fun create(
            definitionClass: java.lang.Class<out RuleDefinition?>?,
            attributeName: String,
            htmlDocumentation: String?,
            file: String?,
            lineNumber: Int,
            flags: MutableSet<String?>
        ): RuleDocumentationAttribute {
            return RuleDocumentationAttribute(
                definitionClass,
                attributeName,
                htmlDocumentation,
                BuildEncyclopediaDocException.Companion.formatLocation(file, lineNumber),
                flags,  /* commonType= */
                null,  /* type= */
                null,  /* defaultValue= */
                null,  /* mandatory= */
                false,  /* nonconfigurable= */
                false
            )
        }

        /**
         * Creates common RuleDocumentationAttribute such as deps or data. These attribute docs have no
         * definitionClass or htmlDocumentation (it's in the BE header).
         */
        fun createCommon(
            attributeName: String, commonType: String?, htmlDocumentation: String?
        ): RuleDocumentationAttribute {
            return RuleDocumentationAttribute(
                null,
                attributeName,
                htmlDocumentation,
                "",
                com.google.common.collect.ImmutableSet.of<String?>(),
                commonType,  /* type= */
                null,  /* defaultValue= */
                null,  /* mandatory= */
                false,  /* nonconfigurable= */
                false
            )
        }

        /** Creates a RuleDocumentationAttribute from a stardoc_output.AttributeInfo proto.  */
        @Throws(BuildEncyclopediaDocException::class)
        fun createFromAttributeInfo(
            attributeInfo: AttributeInfo, location: String?, flags: MutableSet<String?>
        ): RuleDocumentationAttribute {
            return RuleDocumentationAttribute(
                null,
                attributeInfo.getName(),
                attributeInfo.getDocString(),
                location,
                flags,  /* commonType= */
                null,
                getAttributeInfoType(attributeInfo, location),
                attributeInfo.getDefaultValue(),
                attributeInfo.getMandatory(),
                attributeInfo.getNonconfigurable()
            )
        }

        @Throws(BuildEncyclopediaDocException::class)
        private fun getAttributeInfoType(attributeInfo: AttributeInfo, location: String?): Type<*>? {
            return when (attributeInfo.getType()) {
                INT -> Type.INTEGER
                LABEL -> BuildType.LABEL
                NAME, STRING -> Type.STRING
                STRING_LIST -> Types.STRING_LIST
                INT_LIST -> Types.INTEGER_LIST
                LABEL_LIST -> BuildType.LABEL_LIST
                BOOLEAN -> Type.BOOLEAN
                LABEL_STRING_DICT -> BuildType.LABEL_KEYED_STRING_DICT
                STRING_DICT -> Types.STRING_DICT
                STRING_LIST_DICT -> Types.STRING_LIST_DICT
                LABEL_DICT_UNARY -> BuildType.LABEL_DICT_UNARY
                LABEL_LIST_DICT -> BuildType.LABEL_LIST_DICT
                OUTPUT -> BuildType.OUTPUT
                OUTPUT_LIST -> BuildType.OUTPUT_LIST
                else -> throw BuildEncyclopediaDocException(
                    location,
                    java.lang.String.format(
                        "attribute %s: unknown type %s",
                        attributeInfo.getName(), attributeInfo.getType()
                    )
                )
            }
        }

        private fun reprDefaultValue(attribute: Attribute): String? {
            val value: Any? = attribute.getDefaultValueUnchecked()
            if (value is ComputedDefault || value is StarlarkComputedDefaultTemplate) {
                // We cannot print anything useful here other than "optional". Let's assume the doc string for
                // the attribute explains the details.
                return null
            } else if (value is TriState) {
                return when (value) {
                    AUTO -> "-1"
                    NO -> "0"
                    YES -> "1"
                }
            }
            return LabelRenderer.DEFAULT.reprWithoutLabelConstructor(Attribute.valueToStarlark(value))
        }
    }
}
