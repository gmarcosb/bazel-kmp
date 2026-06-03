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

import com.google.devtools.build.lib.packages.RuleClass

/**
 * Utility for quickly creating BUILD file rules for use in tests.
 * 
 * 
 * The use case for this class is writing BUILD files where simple
 * readability for the sake of rules' relationship to the test framework
 * is more important than detailed semantics and layout.
 * 
 * 
 * The behavior provided by this class is not meant to be exhaustive,
 * but should handle a majority of simple cases.
 * 
 * 
 * Example:
 * 
 * <pre>
 * String text = new BuildRuleBuilder("java_library", "MyRule")
 * .setSources("First.java", "Second.java", "Third.java")
 * .setDeps(":library", "//java/com/google/common/collect")
 * .setResources("schema/myschema.xsd")
 * .build();
</pre> * 
 * 
 */
open class BuildRuleBuilder @kotlin.jvm.JvmOverloads constructor(
    ruleClass: String?,
    val ruleName: String,
    ruleClassMap: MutableMap<String?, RuleClass> = defaultRuleClassMap
) {
    protected val ruleClass: RuleClass
    private val multiValueAttributes: com.google.common.collect.Multimap<String?, String?>
    private val singleValueAttributes: MutableMap<String?, Any?>
    protected var ruleClassMap: MutableMap<String?, RuleClass>?

    /**
     * Create a new instance.
     * 
     * @param ruleClass the rule class of the new rule
     * @param ruleName the name of the new rule.
     */
    init {
        this.ruleClass = ruleClassMap.get(ruleClass)
        this.multiValueAttributes = com.google.common.collect.LinkedHashMultimap.create<String?, String?>()
        this.singleValueAttributes = HashMap<String?, Any?>()
        this.ruleClassMap = ruleClassMap
    }

    /** Sets the value of a single valued attribute  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setSingleValueAttribute(attrName: String?, value: Any?): BuildRuleBuilder {
        com.google.common.base.Preconditions.checkState(
            !singleValueAttributes.containsKey(attrName), "attribute '%s' already set", attrName
        )
        singleValueAttributes.put(attrName, value)
        return this
    }

    /** Sets the value of a list type attribute  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addMultiValueAttributes(attrName: String?, vararg value: String?): BuildRuleBuilder {
        multiValueAttributes.putAll(attrName, com.google.common.collect.Lists.newArrayList<String?>(*value))
        return this
    }

    /**
     * Generate the rule
     * 
     * @return a string representation of the rule.
     */
    fun build(): String {
        val sb: java.lang.StringBuilder = java.lang.StringBuilder()
        sb.append(ruleClass.getName()).append("(")
        printNormal(sb, "name", ruleName)
        for (entry in multiValueAttributes.asMap().entries) {
            printArray(sb, entry.key, entry.value)
        }
        for (entry in singleValueAttributes.entries) {
            printNormal(sb, entry.key, entry.value)
        }
        sb.append(")\n")
        return sb.toString()
    }

    private fun printArray(sb: java.lang.StringBuilder, attr: String?, values: MutableCollection<String?>?) {
        if (values == null || values.isEmpty()) {
            return
        }
        sb.append("      ").append(attr).append(" = ")
        printList(sb, values)
        sb.append(",")
        sb.append("\n")
    }

    private fun printNormal(sb: java.lang.StringBuilder, attr: String?, value: Any?) {
        if (value == null) {
            return
        }
        sb.append("      ").append(attr).append(" = ")
        if (value is Int) {
            sb.append(value)
        } else {
            sb.append("'").append(value).append("'")
        }
        sb.append(",")
        sb.append("\n")
    }

    /**
     * Turns iterable of {a b c} into string "['a', 'b', 'c']", appends to
     * supplied StringBuilder.
     */
    private fun printList(sb: java.lang.StringBuilder, elements: MutableCollection<String?>) {
        sb.append("[")
        com.google.common.base.Joiner.on(",").appendTo(
            sb,
            com.google.common.collect.Iterables.transform<String?, String?>(
                elements,
                object : com.google.common.base.Function<String?, String?> {
                    override fun apply(from: String?): String? {
                        return "'" + from + "'"
                    }
                })
        )
        sb.append("]")
    }

    open val filesToGenerate: MutableCollection<String?>?
        /**
         * Returns the transitive closure of file names need to be generated in order
         * for this rule to build.
         */
        get() = com.google.common.collect.ImmutableList.of<String?>()

    open val rulesToGenerate: MutableCollection<BuildRuleBuilder?>?
        /**
         * Returns the transitive closure of BuildRuleBuilders need to be generated in order
         * for this rule to build.
         */
        get() = com.google.common.collect.ImmutableList.of<BuildRuleBuilder?>()

    /**
     * Returns a [Dependency] of this [BuildRuleBuilder] using attrName.
     */
    fun dependsVia(attrName: String?): Dependency {
        return com.google.devtools.build.lib.testutil.BuildRuleBuilder.Dependency(this, attrName)
    }

    /**
     * Representing a [BuildRuleBuilder] depending on an other rule via a certain attribute.
     */
    inner class Dependency private constructor(
        private val buildRuleBuilder: BuildRuleBuilder,
        private val attrName: String?
    ) {
        /**
         * Returns this [BuildRuleBuilder] with a new dependency on otherRule.
         */
        fun on(otherRule: BuildRuleBuilder): BuildRuleBuilder {
            buildRuleBuilder.addMultiValueAttributes(attrName, otherRule.ruleName)
            return buildRuleBuilder
        }
    }

    companion object {
        protected val defaultRuleClassMap: MutableMap<String?, RuleClass>
            get() = TestRuleClassProvider.getRuleClassProvider().getRuleClassMap()
    }
}
