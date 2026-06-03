// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/**
 * Expansion of strings and string lists by replacing make variables and $(location) functions.
 */
class Expander internal constructor(
    ruleContext: RuleContext,
    templateContext: com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext?,
    labelMap: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>?,
    lookedUpVariables: TreeSet<String?>?
) {
    private val ruleContext: RuleContext
    private val templateContext: com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext?
    var labelMap: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>?

    /* Which variables were looked up over this instance's lifetime? */
    private val lookedUpVariables: TreeSet<String?>

    @kotlin.jvm.JvmOverloads
    internal constructor(
        ruleContext: RuleContext,
        templateContext: com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext?,
        labelMap: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>? = null
    ) : this(ruleContext, templateContext, labelMap,  /*lookedUpVariables=*/null)

    init {
        this.ruleContext = ruleContext
        this.templateContext = templateContext
        this.labelMap = labelMap
        // TODO(https://github.com/bazelbuild/bazel/issues/11221): Eliminate all methods that construct
        // an Expander from an existing Expander. These make it hard to keep lookeduUpVariables correct.
        this.lookedUpVariables = if (lookedUpVariables == null) TreeSet<String?>() else lookedUpVariables
    }

    /**
     * Returns a new instance that also expands locations using the default configuration of [ ].
     */
    private fun withLocations(execPaths: Boolean, allowData: Boolean): Expander {
        val newTemplateContext: com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext =
            LocationTemplateContext(
                templateContext, ruleContext, labelMap, execPaths, allowData, true, false
            )
        return com.google.devtools.build.lib.analysis.Expander(
            ruleContext,
            newTemplateContext,
            labelMap,
            lookedUpVariables
        )
    }

    /**
     * Returns a new instance that also expands locations, passing `allowData` to the underlying
     * [LocationTemplateContext].
     */
    fun withDataLocations(): Expander {
        return withLocations(false, true)
    }

    /**
     * Returns a new instance that also expands locations, passing `allowData` and `execPaths` to the underlying [LocationTemplateContext].
     */
    fun withDataExecLocations(): Expander {
        return withLocations(true, true)
    }

    /**
     * Returns a new instance that also expands locations, passing the given location map, as well as
     * `execPaths` to the underlying [LocationTemplateContext].
     */
    fun withExecLocationsNoSrcs(
        locations: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>?,
        windowsPath: Boolean
    ): Expander {
        val newTemplateContext: com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext =
            LocationTemplateContext(
                templateContext, ruleContext, locations, true, false, false, windowsPath
            )
        return com.google.devtools.build.lib.analysis.Expander(
            ruleContext,
            newTemplateContext,
            labelMap,
            lookedUpVariables
        )
    }

    fun withExecLocations(locations: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>?): Expander {
        val newTemplateContext: com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext =
            LocationTemplateContext(
                templateContext, ruleContext, locations, true, false, true, false
            )
        return com.google.devtools.build.lib.analysis.Expander(
            ruleContext,
            newTemplateContext,
            labelMap,
            lookedUpVariables
        )
    }

    /**
     * Expands the given value string, tokenizes it, and then adds it to the given list. The attribute
     * name is only used for error reporting.
     */
    @Throws(java.lang.InterruptedException::class)
    fun tokenizeAndExpandMakeVars(result: MutableList<String?>, attributeName: String?, value: String) {
        expandValue(result, attributeName, value,  /* shouldTokenize */true)
    }

    /** Expands make variables and $(location) tags in value, and optionally tokenizes the result.  */
    private fun expandValue(
        tokens: MutableList<String?>, attributeName: String?, value: String, shouldTokenize: Boolean
    ) {
        var value = value
        value = expand(attributeName, value)
        if (shouldTokenize) {
            try {
                ShellUtils.tokenize(tokens, value)
            } catch (e: ShellUtils.TokenizationException) {
                ruleContext.attributeError(attributeName, e.getMessage())
            }
        } else {
            tokens.add(value)
        }
    }

    /**
     * Returns the string "expression" after expanding all embedded references to "Make" variables. If
     * any errors are encountered, they are reported, and "expression" is returned unchanged.
     * 
     * @param attributeName the name of the attribute from which "expression" comes; used for error
     * reporting.
     * @param expression the string to expand.
     * @return the expansion of "expression".
     */
    /**
     * Returns the string "expression" after expanding all embedded references to "Make" variables. If
     * any errors are encountered, they are reported, and "expression" is returned unchanged.
     * 
     * @param attributeName the name of the attribute
     * @return the expansion of "expression".
     */
    @kotlin.jvm.JvmOverloads
    fun expand(
        attributeName: String?,
        expression: String = ruleContext.attributes().get(attributeName, Type.STRING)
    ): String {
        try {
            val expansion: Expansion = TemplateExpander.expand(expression, templateContext)
            lookedUpVariables.addAll(expansion.lookedUpVariables())
            return expansion.expansion()
        } catch (e: ExpansionException) {
            if (attributeName == null) {
                ruleContext.ruleError(e.getMessage())
            } else {
                ruleContext.attributeError(attributeName, e.getMessage())
            }
            return expression
        }
    }

    /**
     * Expands all the strings in the given list, optionally tokenizing them after expansion. The
     * attribute name is only used for error reporting.
     */
    private fun expandAndTokenizeList(
        attrName: String?, values: MutableList<String>, shouldTokenize: Boolean
    ): com.google.common.collect.ImmutableList<String?> {
        val variables: MutableList<String?> = java.util.ArrayList<String?>()
        for (variable in values) {
            expandValue(variables, attrName, variable, shouldTokenize)
        }
        return com.google.common.collect.ImmutableList.copyOf<String?>(variables)
    }

    /**
     * Expands all the strings in the given list. The attribute name is only used for error reporting.
     */
    /**
     * Obtains the value of the attribute, expands all values, and returns the resulting list. If the
     * attribute does not exist or is not of type [Types.STRING_LIST], then this method throws
     * an error.
     */
    @kotlin.jvm.JvmOverloads
    fun list(
        attrName: String?,
        values: MutableList<String> = ruleContext.attributes().get(attrName, Types.STRING_LIST)
    ): com.google.common.collect.ImmutableList<String?> {
        return expandAndTokenizeList(attrName, values,  /* shouldTokenize */false)
    }

    /**
     * Expands all the strings in the given list, and tokenizes them after expansion. The attribute
     * name is only used for error reporting.
     */
    /**
     * Obtains the value of the attribute, expands, and tokenizes all values. If the attribute does
     * not exist or is not of type [Types.STRING_LIST], then this method throws an error.
     */
    @kotlin.jvm.JvmOverloads
    @Throws(java.lang.InterruptedException::class)
    fun tokenized(
        attrName: String?,
        values: MutableList<String> = ruleContext.attributes().get(attrName, Types.STRING_LIST)
    ): com.google.common.collect.ImmutableList<String?> {
        return expandAndTokenizeList(attrName, values,  /* shouldTokenize */true)
    }

    /**
     * If the string consists of a single variable, returns the expansion of that variable. Otherwise,
     * returns null. Syntax errors are reported.
     * 
     * @param attrName the name of the attribute from which "expression" comes; used for error
     * reporting.
     * @param expression the string to expand.
     * @return the expansion of "expression", or null.
     */
    @Throws(java.lang.InterruptedException::class)
    fun expandSingleMakeVariable(attrName: String?, expression: String?): String? {
        try {
            return TemplateExpander.expandSingleVariable(expression, templateContext)
        } catch (e: ExpansionException) {
            ruleContext.attributeError(attrName, e.getMessage())
            return expression
        }
    }

    /**
     * Which variables were looked up over this [Expander]'s lifetime?
     * 
     * 
     * The returned set is guaranteed alphabetically ordered.
     */
    fun lookedUpVariables(): com.google.common.collect.ImmutableSortedSet<String?> {
        return com.google.common.collect.ImmutableSortedSet.copyOf<String?>(lookedUpVariables)
    }
}
